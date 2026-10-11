package com.rudnam.wallet.widget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The widgets' side of the server API (internal/widget). They sign in with
 * the app's own session: the web app keeps its token and the server's
 * address in Capacitor's preferences (web/src/native.ts), which are these
 * SharedPreferences. Everything here blocks; call it off the main thread.
 */
final class Api {
    private static final String APP_PREFS = "CapacitorStorage";
    private static final String TOKEN = "session";
    private static final String SERVER = "server";
    private static final String CACHE = "widgets";
    private static final String SUMMARY = "summary";

    /** The app isn't signed in, or the server ended its session. */
    static final class SignedOut extends IOException {
        SignedOut() { super("Not signed in"); }
    }

    private Api() {}

    private static SharedPreferences app(Context c) {
        return c.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
    }

    static boolean signedIn(Context c) {
        return app(c).getString(TOKEN, null) != null && app(c).getString(SERVER, null) != null;
    }

    /** This month's summary, which is also kept for when the phone is offline. */
    static JSONObject summary(Context c) throws IOException {
        String token = app(c).getString(TOKEN, null);
        String server = app(c).getString(SERVER, null);
        if (token == null || server == null) throw new SignedOut();
        Calendar from = Calendar.getInstance();
        from.set(Calendar.DAY_OF_MONTH, 1);
        from.set(Calendar.HOUR_OF_DAY, 0);
        from.set(Calendar.MINUTE, 0);
        from.set(Calendar.SECOND, 0);
        from.set(Calendar.MILLISECOND, 0);
        Calendar to = (Calendar) from.clone();
        to.add(Calendar.MONTH, 1);
        String url = server + "/api/widget?from=" + URLEncoder.encode(rfc3339(from.getTime()), "UTF-8")
                + "&to=" + URLEncoder.encode(rfc3339(to.getTime()), "UTF-8");
        JSONObject s = get(url, token);
        c.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit().putString(SUMMARY, s.toString()).apply();
        return s;
    }

    /** The last summary fetched, or null. */
    static JSONObject cachedSummary(Context c) {
        String s = c.getSharedPreferences(CACHE, Context.MODE_PRIVATE).getString(SUMMARY, null);
        try {
            return s == null ? null : new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    static void forgetSummary(Context c) {
        c.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit().remove(SUMMARY).apply();
    }

    private static JSONObject get(String url, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            int status = conn.getResponseCode();
            if (status == 401) throw new SignedOut();
            if (status >= 400) throw new IOException("The server answered " + status);
            return new JSONObject(read(conn.getInputStream()));
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from the server", e);
        } finally {
            conn.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) != -1; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String rfc3339(Date d) {
        // "XXX" (an offset like +09:00) needs API 24, this app's minimum.
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(d);
    }
}
