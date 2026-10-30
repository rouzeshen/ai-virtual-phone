package app.floatphone.shell

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Personal Supabase for self-hosted Float. Native storage is private to this App.
 * Keeps the website's existing service credential on-device, never logs it or sends
 * it to any destination except the matching HTTPS Supabase project. */
object PersonalCloudPush {
    private const val PREFS = "float_personal_push_v1"
    data class Config(val url: String, val key: String)
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun configure(context: Context, raw: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        val url = obj?.optString("url")?.trim()?.trimEnd('/') ?: ""
        val key = obj?.optString("key")?.trim() ?: ""
        // Reject arbitrary target origins; the only network destination is the
        // user's own *.supabase.co project configured by Float.
        if (url.isEmpty() || key.isEmpty()) {
            prefs.edit().remove("url").remove("key").remove("registered").remove("lastSeen").remove("lastSeenId").apply()
            return
        }
        if (!Regex("^https://[a-z0-9-]+\\.supabase\\.co$").matches(url) || key.length < 24) return
        val oldUrl = prefs.getString("url", "")
        val newProject = oldUrl != url || prefs.getString("key", "") != key
        val editor = prefs.edit().putString("url", url).putString("key", key)
        if (newProject) {
            editor.remove("registered").remove("lastSeenId").putString("lastSeen", Instant.now().toString())
        }
        if (!prefs.contains("lastSeen")) editor.putString("lastSeen", Instant.now().toString())
        editor.apply()
    }

    fun load(context: Context): Config? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString("url", "").orEmpty()
        val key = prefs.getString("key", "").orEmpty()
        if (!Regex("^https://[a-z0-9-]+\\.supabase\\.co$").matches(url) || key.length < 24) return null
        return Config(url, key)
    }

    fun registered(context: Context): Boolean {
        val config = load(context) ?: return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("registered", "") == config.url
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    private fun request(config: Config, url: String, body: String? = null,
                        extraHeaders: Map<String, String> = emptyMap()): JSONObject? {
        val builder = Request.Builder().url(url)
            .header("apikey", config.key)
            .header("Authorization", "Bearer ${config.key}")
            .header("Accept", "application/json")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        val req = if (body != null) builder.post(body.toRequestBody("application/json".toMediaType())).build()
                  else builder.get().build()
        return http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return null
            val raw = response.body?.string().orEmpty()
            runCatching { JSONObject(raw) }.getOrNull()
        }
    }

    /** Register personal cloud shell endpoint to unblock offline job generation. */
    private fun register(context: Context, config: Config): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("registered", "") == config.url) return true
        val endpoint = prefs.getString("endpoint", null)
            ?: "shell:personal:${UUID.randomUUID()}".also {
                prefs.edit().putString("endpoint", it).apply()
            }
        val payload = JSONObject()
            .put("endpoint", endpoint)
            .put("keys", JSONObject().put("p256dh", "shell").put("auth", "shell"))
        val result = request(config, "${config.url}/functions/v1/ai-phone-push?action=subscribe",
            payload.toString(), mapOf("x-ai-phone-service-key" to config.key))
        if (result?.optBoolean("ok") == true) {
            prefs.edit().putString("registered", config.url).apply()
            return true
        }
        return false
    }

    /** The database outbox remains after foreground syncing; never consume entries here.
     * A timestamp cursor prevents duplicate notifications across process restarts. */
    fun poll(context: Context, config: Config, onMessage: (String, String) -> Unit): Boolean {
        if (!register(context, config)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cursor = prefs.getString("lastSeen", "") ?: ""
        if (cursor.isEmpty()) {
            prefs.edit().putString("lastSeen", Instant.now().toString()).apply()
            return true
        }
        val cursorId = prefs.getString("lastSeenId", "").orEmpty()
        val cursorFilter = if (cursorId.isEmpty()) "(created_at.gt.$cursor)"
            else "(created_at.gt.$cursor,and(created_at.eq.$cursor,id.gt.$cursorId))"
        val url = "${config.url}/rest/v1/push_outbox".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("user_id", "eq.owner")
            ?.addQueryParameter("or", cursorFilter)
            ?.addQueryParameter("meta->>pushGenerated", "eq.true")
            ?.addQueryParameter("select", "id,created_at,raw_text,meta")
            ?.addQueryParameter("order", "created_at.asc,id.asc")
            ?.addQueryParameter("limit", "40")
            ?.build() ?: return false
        val req = Request.Builder().url(url).header("apikey", config.key)
            .header("Authorization", "Bearer ${config.key}").get().build()
        val rows = http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return false
            JSONArray(response.body?.string().orEmpty())
        }
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            val timestamp = item.optString("created_at")
            val meta = item.optJSONObject("meta") ?: JSONObject()
            val raw = item.optString("raw_text").trim()
            val title = meta.optString("characterName").ifBlank { "Float" }
            val preview = if (meta.optString("kind") == "shell_test") {
                "Float 个人云通知通道已连通。"
            } else {
                raw.replace(Regex("\\[(?:状态栏|内心)\\][\\s\\S]*?\\[/(?:状态栏|内心)\\]"), "")
                    .replace(Regex("\\[[^\\]]{1,15}[:：]\\d+(?:\\.\\d+)?\\]"), "")
                    .trim().take(160).ifBlank { "有一条新消息" }
            }
            onMessage(title, preview)
            // Persist after delivery, not before. This is notification-only;
            // Float's existing browser outbox merger owns message consumption.
            if (timestamp.isNotEmpty()) prefs.edit().putString("lastSeen", timestamp)
                .putString("lastSeenId", item.optString("id")).apply()
        }
        return true
    }

    /** End-to-end test: enqueue one marked row in personal Supabase after 6s.
     * The native polling loop delivers it; the website ignores it as chat. */
    fun postTest(context: Context, config: Config): Boolean {
        val entry = JSONObject()
            .put("id", "out_${UUID.randomUUID()}")
            .put("user_id", "owner")
            .put("session_id", JSONObject.NULL)
            .put("raw_text", "Float 个人云通知通道已连通。")
            .put("meta", JSONObject().put("kind", "shell_test")
                .put("pushGenerated", true).put("characterName", "Float"))
        val endpoint = "${config.url}/rest/v1/push_outbox"
        val req = Request.Builder().url(endpoint)
            .header("apikey", config.key)
            .header("Authorization", "Bearer ${config.key}")
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .post(JSONArray().put(entry).toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(req).execute().use { it.isSuccessful }
    }
}
