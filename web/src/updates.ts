// In the Android app: whether the server offers a newer build than the one
// installed. The server reads the APK's versionCode (GET /api/downloads,
// internal/server/download.go) and the app compares it with its own. Copies
// from the Play Store are left to the Play Store. App builds from before this
// file can't ask, so those phones need one update by hand.
import { useEffect, useState } from 'react';
import { App as CapApp } from '@capacitor/app';
import { Browser } from '@capacitor/browser';
import { registerPlugin } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';
import { isApp, server } from './native';

/** Which app installed this one (android/…/UpdatesPlugin.java). */
const updates = registerPlugin<{ installer(): Promise<{ installer?: string | null }> }>('Updates');

export interface Update {
  /** The new build's versionCode. */
  version: number;
  /** e.g. "2.1"; may be empty. */
  name: string;
  /** Where to download it, on the server. */
  url: string;
}

// The version the user said "Later" to: not offered again until a newer one.
const laterKey = 'update-later';
// How often a check may run, as the app comes back into view.
const every = 60 * 60 * 1000; // ms

async function check(): Promise<Update | null> {
  const { installer } = await updates.installer();
  if (installer === 'com.android.vending') return null;
  const [info, res, later] = await Promise.all([
    CapApp.getInfo(),
    fetch(`${server}/api/downloads`),
    Preferences.get({ key: laterKey }),
  ]);
  if (!res.ok) return null;
  const { android } = (await res.json()) as { android: { url: string; version?: number; version_name?: string } | null };
  const installed = Number(info.build);
  const version = android?.version ?? 0;
  if (!android || !installed || version <= installed || version <= Number(later.value ?? 0)) return null;
  return { version, name: android.version_name ?? '', url: server + android.url };
}

/**
 * The newer build the server offers, or null. Checked when the app starts
 * and, at most hourly, when it comes back into view.
 */
export function useUpdate(): { update: Update | null; install: () => void; later: () => void } {
  const [update, setUpdate] = useState<Update | null>(null);
  useEffect(() => {
    if (!isApp) return;
    let last = 0;
    const run = () => {
      if (Date.now() - last < every) return;
      last = Date.now();
      check().then(setUpdate, () => {}); // offline: try again later
    };
    run();
    const resume = CapApp.addListener('resume', run);
    return () => {
      resume.then((l) => l.remove());
    };
  }, []);
  return {
    update,
    install: () => {
      if (update) Browser.open({ url: update.url }).catch(() => {});
    },
    later: () => {
      if (update) Preferences.set({ key: laterKey, value: String(update.version) }).catch(() => {});
      setUpdate(null);
    },
  };
}
