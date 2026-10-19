package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Your Spotify account, read-only, for the Music screen. config.properties has the app's Client ID and a refresh token
 * (written by tools/spotify_login.py, after you authorize in the browser). Spotify hands out a new refresh token with
 * each access token (PKCE), so the latest one is kept in the app's private storage; a new login (a different token in
 * config.properties) starts over from it. Blocking calls: background threads only.
 */
final class Spotify {
    private static final String API = "https://api.spotify.com/v1";
    private final SharedPreferences prefs;
    private String access;
    private long expiresAt;

    Spotify(Context c) {
        prefs = c.getSharedPreferences("spotify", Context.MODE_PRIVATE);
    }

    static boolean configured() {
        return !Config.SPOTIFY_CLIENT_ID.trim().isEmpty() && !Config.SPOTIFY_REFRESH_TOKEN.trim().isEmpty();
    }

    /** GET on the Web API (path after /v1). */
    JSONObject get(String path) throws IOException, org.json.JSONException {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Authorization", "Bearer " + token());
            try {
                int code = c.getResponseCode();
                if (code == 401 && attempt == 0) {
                    synchronized (this) { access = null; }
                    continue;
                }
                if (code == 204) return new JSONObject();
                if (code != 200) throw new IOException("Spotify returned HTTP " + code);
                return new JSONObject(read(c.getInputStream()));
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Spotify rejected the login");
    }

    private synchronized String token() throws IOException, org.json.JSONException {
        if (access != null && System.currentTimeMillis() < expiresAt) return access;
        String seed = Config.SPOTIFY_REFRESH_TOKEN.trim();
        if (!seed.equals(prefs.getString("seed", ""))) prefs.edit().putString("seed", seed).putString("refresh", seed).commit();
        String refresh = prefs.getString("refresh", seed);
        HttpURLConnection c = (HttpURLConnection) new URL("https://accounts.spotify.com/api/token").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        String body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8")
                + "&client_id=" + URLEncoder.encode(Config.SPOTIFY_CLIENT_ID.trim(), "UTF-8");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code == 400) throw new IOException("the Spotify login expired: run tools/spotify_login.py again");
            if (code != 200) throw new IOException("Spotify login returned HTTP " + code);
            JSONObject o = new JSONObject(read(c.getInputStream()));
            access = o.getString("access_token");
            expiresAt = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 60) * 1000L;
            String next = o.optString("refresh_token", "");
            if (!next.isEmpty()) prefs.edit().putString("refresh", next).commit();   // the old one stops working
            return access;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
