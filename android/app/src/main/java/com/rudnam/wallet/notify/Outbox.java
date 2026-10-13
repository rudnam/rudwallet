package com.rudnam.wallet.notify;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Payment notifications waiting to reach the server. They are kept until
 * the server has them, so a payment made offline (or signed out) is sent
 * later: with the next notification, or when the app is next left. Sending
 * one twice is harmless; the server records each payment once.
 */
final class Outbox {
    private static final String PREFS = "payment-notifications";
    private static final String QUEUE = "queue";
    // The app's session, which the web app keeps in Capacitor's preferences (web/src/native.ts).
    private static final String APP_PREFS = "CapacitorStorage";
    private static final String TOKEN = "session";
    private static final String SERVER = "server";
    /** Kept at most this long, and this many: older ones are no longer worth recording. */
    private static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_KEPT = 200;
    /** The most the server takes in one request (internal/notify). */
    private static final int BATCH = 50;

    private static final Object lock = new Object();
    private static boolean sending;

    private Outbox() {}

    static void add(Context c, String pkg, long when, String title, String text) {
        synchronized (lock) {
            JSONArray queue = load(c);
            JSONArray out = new JSONArray();
            long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
            for (int i = 0; i < queue.length(); i++) {
                JSONObject n = queue.optJSONObject(i);
                if (n == null || n.optLong("when") < cutoff) continue;
                // An update of a notification still waiting replaces it.
                if (n.optString("package").equals(pkg) && n.optLong("when") == when) continue;
                out.put(n);
            }
            try {
                out.put(new JSONObject().put("package", pkg).put("when", when).put("title", title).put("text", text));
            } catch (JSONException e) {
                return;
            }
            while (out.length() > MAX_KEPT) out.remove(0);
            save(c, out);
        }
    }

    /** Sends what is waiting, in the background. */
    static void flush(Context context) {
        final Context c = context.getApplicationContext();
        synchronized (lock) {
            if (sending || load(c).length() == 0) return;
            sending = true;
        }
        new Thread(() -> {
            try {
                while (sendBatch(c)) { /* until empty or failing */ }
            } finally {
                synchronized (lock) { sending = false; }
            }
        }).start();
    }

    /** Sends the oldest batch; true if it went and there may be more. */
    private static boolean sendBatch(Context c) {
        SharedPreferences app = c.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        String token = app.getString(TOKEN, null), server = app.getString(SERVER, null);
        if (token == null || server == null) return false; // kept until the app is signed in
        JSONArray batch = new JSONArray();
        synchronized (lock) {
            JSONArray queue = load(c);
            for (int i = 0; i < Math.min(BATCH, queue.length()); i++) batch.put(queue.opt(i));
        }
        if (batch.length() == 0) return false;
        try {
            post(server + "/api/notifications", token, new JSONObject().put("notifications", batch));
        } catch (IOException | JSONException e) {
            return false; // offline, signed out or a server problem: try again later
        }
        synchronized (lock) {
            // Drop what was sent. Notifications added meanwhile were appended,
            // or replaced one in the batch, which is then sent again.
            JSONArray queue = load(c), rest = new JSONArray();
            for (int i = 0; i < queue.length(); i++) {
                JSONObject n = queue.optJSONObject(i);
                if (n != null && !contains(batch, n)) rest.put(n);
            }
            save(c, rest);
            return rest.length() > 0;
        }
    }

    private static boolean contains(JSONArray batch, JSONObject n) {
        for (int i = 0; i < batch.length(); i++) {
            JSONObject b = batch.optJSONObject(i);
            if (b != null && b.toString().equals(n.toString())) return true;
        }
        return false;
    }

    private static void post(String url, String token, JSONObject body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            if (status >= 400) throw new IOException("The server answered " + status);
        } finally {
            conn.disconnect();
        }
    }

    private static JSONArray load(Context c) {
        try {
            return new JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(QUEUE, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static void save(Context c, JSONArray queue) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(QUEUE, queue.toString()).commit();
    }
}
