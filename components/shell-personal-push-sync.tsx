"use client";

import { useEffect } from "react";
import { hydrateKvDb } from "@/lib/kv-db";
import { loadCloudBackupConfig } from "@/lib/cloud-backup/config";
import { isPersonalPushCloudActive } from "@/lib/personal-push-cloud";

import { markAccountPushSubscribed } from "@/lib/push-client";

type NativeBridge = {
  configurePersonalPush?: (config: string) => void;
  isPersonalPushRegistered?: () => boolean;
};

/** Once running inside the Float Android shell, hand the user's personal cloud
 * settings to native private storage. The key never leaves the device except
 * when native talks directly to that same user-owned Supabase project. */
export function ShellPersonalPushSync() {
  useEffect(() => {
    const bridge = (window as Window & { AndroidShell?: NativeBridge }).AndroidShell;
    if (!bridge?.configurePersonalPush) return;
    let stopped = false;
    let last: string | undefined;
    let hydrated = false;
    let registered = false;
    const sync = () => {
      if (stopped || !hydrated) return;
      const config = loadCloudBackupConfig();
      const value = isPersonalPushCloudActive() && config.url && config.key
        ? JSON.stringify({ url: config.url, key: config.key }) : "";
      if (value !== last) {
        last = value;
        registered = false;
        bridge.configurePersonalPush?.(value);
        return;
      }
      if (value && !registered && bridge.isPersonalPushRegistered?.()) {
        markAccountPushSubscribed(true);
        registered = true;
      }
    };
    void hydrateKvDb().then(() => { hydrated = true; sync(); }).catch(() => undefined);
    const interval = window.setInterval(sync, 8000);
    window.addEventListener("focus", sync);
    window.addEventListener("pageshow", sync);
    return () => {
      stopped = true;
      window.clearInterval(interval);
      window.removeEventListener("focus", sync);
      window.removeEventListener("pageshow", sync);
    };
  }, []);
  return null;
}
