package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import io.freemytv.Cards.Title;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Music screen, drawn like Explore (1920×1080 design pixels), from your Spotify account: at the top the focused
 * item (its cover on the right, its colors blurred behind everything; name, artist, year…), then rows: recently
 * played, your top tracks of the last weeks, your artists, their new releases (the past year), your playlists and saved
 * albums. OK opens it in the Spotify app. Spotify's own "Made for you" mixes aren't available to apps since 2024.
 */
final class MusicView extends View {
    private static final class Row {
        final String title;
        final List<Title> items = new ArrayList<>();
        boolean loading = true;
        int col;
        final Spring scroll = new Spring(0.34f, 0.9f, 0);

        Row(String title) { this.title = title; }
    }

    private static final String SPOTIFY_PKG = "com.spotify.tv.android";
    private static final float M = 80, CARD = 220, STEP = 248, ROWS_TOP = 600, ROW_H = 316, COVER = 480;
    private static final int INK = Cards.INK, INK2 = Cards.INK2, INK3 = Cards.INK3;
    private static final int RECENT = 0, TOP = 1, ARTISTS = 2, NEW = 3, PLAYLISTS = 4, SAVED = 5;

    private final Activity activity;
    private final Spotify spotify;
    private final Cards cards;
    private final Click click;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Row[] rows = {
        new Row(Ui.tr("Tocadas recentemente", "Recently Played")), new Row(Ui.tr("Suas mais ouvidas", "Your Top Tracks")),
        new Row(Ui.tr("Seus artistas", "Your Artists")), new Row(Ui.tr("Lançamentos dos seus artistas", "New From Your Artists")),
        new Row(Ui.tr("Suas playlists", "Your Playlists")), new Row(Ui.tr("Álbuns salvos", "Saved Albums")),
    };
    private int row, error;   // error: 0 none, 1 not connected
    private boolean destroyed, okDown, backDown;
    private final Spring scrollY = new Spring(0.34f, 0.9f, 0), grow = new Spring(0.32f, 0.78f, 0), fade = new Spring(0.3f, 1f, 1);
    private long lastFrame;

    // The focused item: big cover (loaded at 480 px) and its colors for the background.
    private Title hero;
    private StaticLayout heroName;
    private String kicker = "";
    private final Map<String, Bitmap> covers = new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 4; }
    };
    private final Set<String> loadingCovers = new HashSet<>();
    private final Map<String, Bitmap[]> skies = new HashMap<>();
    private Bitmap[] sky, skyBefore;
    private String toast;
    private long toastAt;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pageTitle, rowTitle, note, kickerPaint, meta, detail, toastPaint, help, helpBold;
    private final TextPaint namePaint;
    private final Shader rowsFade;
    private final RectF r = new RectF(), sh = new RectF();
    private String heroDetail = "";
    private final Path clip = new Path();

    MusicView(Activity a) {
        super(a);
        activity = a;
        spotify = Spotify.get(a);
        cards = new Cards(a, this::invalidate);
        click = new Click(a);
        setFocusable(true);
        pageTitle = Ui.text(Ui.displaySemibold, 64, INK);
        rowTitle = Ui.text(Ui.semibold, 28, INK);
        note = Ui.text(Ui.medium, 22, INK3);
        kickerPaint = Ui.text(Ui.semibold, 20, INK3);
        kickerPaint.setLetterSpacing(0.12f);
        meta = Ui.text(Ui.regular, 26, INK2);
        detail = Ui.text(Ui.regular, 22, INK3);
        toastPaint = Ui.text(Ui.semibold, 24, 0xFF111214);
        help = Ui.text(Ui.regular, 26, INK2);
        helpBold = Ui.text(Ui.semibold, 26, INK);
        namePaint = new TextPaint(Ui.text(Ui.displaySemibold, 60, INK));
        shadow.setMaskFilter(new android.graphics.BlurMaskFilter(40, android.graphics.BlurMaskFilter.Blur.NORMAL));
        rowsFade = new LinearGradient(0, 960, 0, 1080, 0x000A0B0E, 0xE60A0B0E, Shader.TileMode.CLAMP);
        if (!Spotify.configured()) {
            error = 1;
            for (Row x : rows) x.loading = false;
        } else {
            load();
        }
    }

    void destroy() {
        destroyed = true;
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cards.destroy();
        click.release();
        covers.clear();
        skies.clear();
    }

    // MARK: - Spotify

    private void load() {
        io.execute(() -> {
            fetch(RECENT, () -> {
                List<Title> out = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                JSONArray a = spotify.get("/me/player/recently-played?limit=50").optJSONArray("items");
                for (int i = 0; a != null && i < a.length() && out.size() < 20; i++) {
                    JSONObject album = a.optJSONObject(i).optJSONObject("track").optJSONObject("album");
                    if (album != null && seen.add(album.optString("id"))) add(out, album(album));
                }
                return out;
            });
            fetch(TOP, () -> {
                List<Title> out = new ArrayList<>();
                JSONArray a = spotify.get("/me/top/tracks?time_range=short_term&limit=30").optJSONArray("items");
                for (int i = 0; a != null && i < a.length(); i++) add(out, track(a.optJSONObject(i)));
                return out;
            });
            List<String> artistIds = new ArrayList<>();
            fetch(ARTISTS, () -> {
                List<Title> out = new ArrayList<>();
                JSONArray a = spotify.get("/me/top/artists?time_range=medium_term&limit=20").optJSONArray("items");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    artistIds.add(o.optString("id"));
                    add(out, artist(o));
                }
                return out;
            });
            fetch(NEW, () -> {
                // Albums and singles from your top artists, released in the past year, newest first.
                List<Object[]> found = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                String since = day(-365);
                for (int i = 0; i < artistIds.size() && i < 10; i++) {
                    JSONArray a = spotify.get("/artists/" + artistIds.get(i) + "/albums?include_groups=album,single&limit=10")
                            .optJSONArray("items");
                    for (int j = 0; a != null && j < a.length(); j++) {
                        JSONObject o = a.optJSONObject(j);
                        String date = o.optString("release_date", "");
                        if (date.compareTo(since) >= 0 && seen.add(o.optString("name").toLowerCase(Locale.ROOT))) {
                            found.add(new Object[] {date, album(o)});
                        }
                    }
                }
                Collections.sort(found, (x, y) -> ((String) y[0]).compareTo((String) x[0]));
                List<Title> out = new ArrayList<>();
                for (Object[] f : found) if (out.size() < 20) add(out, (Title) f[1]);
                return out;
            });
            fetch(PLAYLISTS, () -> {
                List<Title> out = new ArrayList<>();
                JSONArray a = spotify.get("/me/playlists?limit=30").optJSONArray("items");
                for (int i = 0; a != null && i < a.length(); i++) add(out, playlist(a.optJSONObject(i)));
                return out;
            });
            fetch(SAVED, () -> {
                List<Title> out = new ArrayList<>();
                JSONArray a = spotify.get("/me/albums?limit=30").optJSONArray("items");
                for (int i = 0; a != null && i < a.length(); i++) add(out, album(a.optJSONObject(i).optJSONObject("album")));
                return out;
            });
        });
    }

    private interface Fetch { List<Title> run() throws Exception; }

    /** Runs one row's request (on the io thread) and shows it; a failing row doesn't stop the others. */
    private void fetch(int index, Fetch f) {
        List<Title> list;
        try {
            list = f.run();
        } catch (Exception e) {
            AppLog.w("spotify: " + e.getMessage());
            list = new ArrayList<>();
            final String message = e.getMessage();
            if (message != null && message.contains("expired")) {
                main.post(() -> toast(Ui.tr("O login do Spotify expirou: rode tools/spotify_login.py de novo.",
                        "The Spotify login expired: run tools/spotify_login.py again.")));
            }
        }
        final List<Title> done = list;
        main.post(() -> {
            if (destroyed) return;
            rows[index].items.addAll(done);
            rows[index].loading = false;
            if (hero == null || index == row) focusChanged();
            invalidate();
        });
    }

    private static void add(List<Title> out, Title t) {
        if (t != null && t.poster != null) out.add(t);
    }

    private static Title album(JSONObject o) {
        if (o == null) return null;
        Title t = base(o, false);
        String date = o.optString("release_date", "");
        List<String> parts = new ArrayList<>();
        parts.add(artists(o));
        parts.add("single".equals(o.optString("album_type")) ? "Single" : Ui.tr("Álbum", "Album"));
        if (date.length() >= 4) parts.add(date.substring(0, 4));
        t.meta = join(parts);
        int tracks = o.optInt("total_tracks");
        t.overview = tracks > 0 ? tracks + Ui.tr(tracks == 1 ? " música" : " músicas", tracks == 1 ? " song" : " songs") : "";
        return t;
    }

    private static Title track(JSONObject o) {
        if (o == null) return null;
        Title t = base(o, false);
        JSONObject album = o.optJSONObject("album");
        images(t, album == null ? null : album.optJSONArray("images"));
        List<String> parts = new ArrayList<>();
        parts.add(artists(o));
        parts.add(Ui.tr("Música", "Song"));
        long ms = o.optLong("duration_ms");
        if (ms > 0) parts.add(String.format(Locale.US, "%d:%02d", ms / 60000, (ms / 1000) % 60));
        t.meta = join(parts);
        t.overview = album == null ? "" : Ui.tr("Do álbum ", "From the album ") + album.optString("name");
        return t;
    }

    private static Title artist(JSONObject o) {
        if (o == null) return null;
        Title t = base(o, true);
        t.meta = Ui.tr("Artista", "Artist");
        JSONArray g = o.optJSONArray("genres");
        List<String> genres = new ArrayList<>();
        for (int i = 0; g != null && i < g.length() && i < 3; i++) genres.add(g.optString(i));
        t.overview = String.join(", ", genres);
        return t;
    }

    private static Title playlist(JSONObject o) {
        if (o == null) return null;
        Title t = base(o, false);
        JSONObject owner = o.optJSONObject("owner");
        JSONObject items = o.optJSONObject("items");
        if (items == null) items = o.optJSONObject("tracks");
        List<String> parts = new ArrayList<>();
        parts.add("Playlist");
        if (owner != null && !owner.optString("display_name").isEmpty()) parts.add(owner.optString("display_name"));
        int n = items == null ? 0 : items.optInt("total");
        if (n > 0) parts.add(n + Ui.tr(n == 1 ? " música" : " músicas", n == 1 ? " song" : " songs"));
        t.meta = join(parts);
        t.overview = android.text.Html.fromHtml(o.optString("description", ""), android.text.Html.FROM_HTML_MODE_LEGACY).toString();
        return t;
    }

    private static Title base(JSONObject o, boolean round) {
        Title t = new Title();
        t.kind = o.optString("type");
        t.title = o.optString("name");
        t.uri = o.optString("uri");
        t.square = true;
        t.round = round;
        images(t, o.optJSONArray("images"));
        return t;
    }

    /** Spotify lists images largest first: the ~300 px one for the card, the largest for the big cover. */
    private static void images(Title t, JSONArray images) {
        if (images == null || images.length() == 0) return;
        t.backdrop = images.optJSONObject(0).optString("url", null);
        JSONObject card = images.optJSONObject(Math.min(1, images.length() - 1));
        t.poster = card.optString("url", t.backdrop);
    }

    private static String artists(JSONObject o) {
        JSONArray a = o.optJSONArray("artists");
        List<String> names = new ArrayList<>();
        for (int i = 0; a != null && i < a.length() && i < 3; i++) names.add(a.optJSONObject(i).optString("name"));
        return String.join(", ", names);
    }

    private static String join(List<String> parts) {
        List<String> kept = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isEmpty()) kept.add(p);
        return String.join("  ·  ", kept);
    }

    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    // MARK: - Focus and the big cover

    private Title focused() {
        Row x = rows[row];
        return x.col < x.items.size() ? x.items.get(x.col) : null;
    }

    private void focusChanged() {
        grow.snap(0);
        grow.target = 1;
        Title t = focused();
        if (t == null && hero == null) for (Row x : rows) if (!x.items.isEmpty()) { t = x.items.get(0); break; }
        if (t != null && t != hero) {
            hero = t;
            kicker = rows[row].items.contains(t) ? rows[row].title : "";
            heroName = Cards.layout(t.title, namePaint, 1120, 2, 1.0f);
            heroDetail = t.overview == null ? "" : android.text.TextUtils.ellipsize(t.overview, new TextPaint(detail), 1100,
                    android.text.TextUtils.TruncateAt.END).toString();
            loadCover(t);
            Bitmap[] s = skies.get(t.poster);
            if (s == null) {
                Bitmap card = cards.poster(t);   // the card's copy: colors for the background right away
                if (card != null) {
                    s = new Bitmap[] {null, Bitmap.createScaledBitmap(card, 32, 18, true)};
                    skies.put(t.poster, s);
                }
            }
            if (s != sky) {
                skyBefore = sky;
                sky = s;
                fade.snap(s != null ? 0 : 1);
                fade.target = 1;
            }
        }
        startFrames();
    }

    /**
     * The big cover from the card's image (Spotify's ~300 px, already on disk once the card showed), not the 640 px
     * one: it's ready almost at once. Until then the card's own copy in memory is drawn.
     */
    private void loadCover(Title t) {
        final String url = t.poster;
        if (url == null || covers.containsKey(url) || !loadingCovers.add(url)) return;
        io.execute(() -> {
            Bitmap b = null;
            try {
                b = Cards.decode(cards.download("", url), Cards.SQUARE * 3 / 2, Cards.SQUARE * 3 / 2);
            } catch (Exception e) {
                AppLog.w("cover: " + e.getMessage());
            }
            final Bitmap done = b;
            main.post(() -> {
                loadingCovers.remove(url);
                if (destroyed || done == null) return;
                covers.put(url, done);
                Bitmap[] s = {null, Bitmap.createScaledBitmap(done, 32, 18, true)};
                skies.put(url, s);
                if (hero != null && url.equals(hero.poster)) {
                    skyBefore = sky;
                    sky = s;
                    fade.snap(0);
                    fade.target = 1;
                }
                startFrames();
            });
        });
    }

    // MARK: - Drawing

    private void startFrames() {
        lastFrame = 0;
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        scrollY.target = row * ROW_H;
        boolean moving = scrollY.step(dt) | grow.step(dt) | fade.step(dt);
        for (Row x : rows) moving |= x.scroll.step(dt);
        float k = getWidth() / 1920f;
        c.drawColor(Cards.BG);
        c.save();
        c.scale(k, k);
        cards.drawSky(c, sky, skyBefore, fade.value);
        if (error == 1) {
            drawConnect(c);
        } else {
            drawHero(c);
            drawRows(c);
        }
        drawToast(c, now);
        c.restore();
        moving |= !scrollY.settled();
        for (Row x : rows) moving |= !x.scroll.settled();
        if (moving || (toast != null && now - toastAt < 2400)) postInvalidateOnAnimation();
    }

    private void drawConnect(Canvas c) {
        c.drawText(Ui.tr("Música", "Music"), M, 58 + Ui.cap(pageTitle), pageTitle);
        float y = 260;
        c.drawText(Ui.tr("Conecte sua conta do Spotify (precisa de Premium):", "Connect your Spotify account (needs Premium):"), M, y, helpBold);
        String[] steps = {
            Ui.tr("1. Em developer.spotify.com/dashboard, crie um app com o Redirect URI http://127.0.0.1:8888/callback",
                    "1. At developer.spotify.com/dashboard, create an app with the Redirect URI http://127.0.0.1:8888/callback"),
            Ui.tr("2. Cole o Client ID em config.properties: spotify_client_id=…", "2. Paste its Client ID in config.properties: spotify_client_id=…"),
            Ui.tr("3. No computador: python3 -I tools/spotify_login.py e autorize no navegador", "3. On the computer: python3 -I tools/spotify_login.py and authorize in the browser"),
            Ui.tr("4. ./build.sh e instale de novo", "4. ./build.sh and install again"),
        };
        for (String s : steps) {
            y += 60;
            c.drawText(s, M, y, help);
        }
    }

    private void drawHero(Canvas c) {
        c.drawText(Ui.tr("Música", "Music"), M, 58 + Ui.cap(pageTitle), pageTitle);
        if (hero == null) {
            if (rows[RECENT].loading) c.drawText(Ui.tr("Carregando sua conta do Spotify…", "Loading your Spotify account…"), M, 260, note);
            return;
        }
        // The big cover on the right (a circle for artists), with a soft shadow.
        float x = 1920 - M - COVER, y = 60;
        r.set(x, y, x + COVER, y + COVER);
        float rad = hero.round ? COVER / 2 : 22;
        Bitmap b = hero.poster == null ? null : covers.get(hero.poster);
        if (b == null) b = cards.poster(hero);   // the card's copy until the sharper one is in
        sh.set(r);
        sh.offset(0, 24);
        shadow.setColor(0x8C000000);
        c.drawRoundRect(sh, rad, rad, shadow);
        if (b != null) {
            c.save();
            clip.reset();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            c.drawBitmap(b, null, r, bitmapPaint);
            c.restore();
        } else {
            fill.setColor(0xFF2C2C2E);
            c.drawRoundRect(r, rad, rad, fill);
        }
        float ty = 200;
        if (!kicker.isEmpty()) c.drawText(kicker.toUpperCase(Ui.en ? Locale.US : Ui.PT), M, ty + Ui.cap(kickerPaint), kickerPaint);
        ty += 44;
        c.save();
        c.translate(M, ty);
        heroName.draw(c);
        c.restore();
        ty += heroName.getHeight() + 20;
        c.drawText(hero.meta, M, ty + Ui.cap(meta), meta);
        ty += 46;
        if (!heroDetail.isEmpty()) c.drawText(heroDetail, M, ty + Ui.cap(detail), detail);
    }

    private void drawRows(Canvas c) {
        c.save();
        c.clipRect(0, ROWS_TOP - 8, 1920, 1080);
        float y = ROWS_TOP - scrollY.value;
        for (int i = 0; i < rows.length; i++) {
            if (y + ROW_H > ROWS_TOP - 30 && y < 1080) drawRow(c, i, y);
            y += ROW_H;
        }
        c.restore();
        fill.setShader(rowsFade);
        c.drawRect(0, 960, 1920, 1080, fill);
        fill.setShader(null);
    }

    private void drawRow(Canvas c, int index, float top) {
        Row x = rows[index];
        c.drawText(x.title, M, top + Ui.cap(rowTitle), rowTitle);
        int n = x.items.size();
        float y = top + 56;
        if (n == 0) {
            String text = x.loading ? Ui.tr("Carregando…", "Loading…") : Ui.tr("Nada por aqui ainda.", "Nothing here yet.");
            c.drawText(text, M, y + 40, note);
            return;
        }
        boolean rowFocused = index == row;
        x.scroll.target = Math.max(0, Math.min((x.col - 2) * STEP, n * STEP - (1920 - 2 * M)));
        float x0 = M - x.scroll.value;
        for (int i = 0; i < n; i++) {
            float px = x0 + i * STEP;
            if ((rowFocused && i == x.col) || px + CARD < -40 || px > 1960) continue;
            cards.drawPoster(c, x.items.get(i), px, y, CARD, CARD, 0, false);
        }
        if (rowFocused && x.col < n) cards.drawPoster(c, x.items.get(x.col), x0 + x.col * STEP, y, CARD, CARD, grow.value, false);
    }

    private void drawToast(Canvas c, long now) {
        if (toast == null) return;
        float t = (now - toastAt) / 1000f;
        float a = t < 0.25f ? t / 0.25f : t > 2.1f ? Math.max(0, 1 - (t - 2.1f) / 0.3f) : 1;
        if (a <= 0) {
            toast = null;
            return;
        }
        float w = toastPaint.measureText(toast) + 68;
        r.set(960 - w / 2, 960 - 30 * (1 - a), 960 + w / 2, 1024 - 30 * (1 - a));
        fill.setColor(Ui.alpha(INK, a));
        c.drawRoundRect(r, 32, 32, fill);
        int color = toastPaint.getColor();
        toastPaint.setColor(Ui.alpha(color, a));
        c.drawText(toast, r.left + 34, r.centerY() + Ui.halfCap(toastPaint), toastPaint);
        toastPaint.setColor(color);
    }

    private void toast(String text) {
        toast = text;
        toastAt = SystemClock.uptimeMillis();
        startFrames();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down) { if (e.getRepeatCount() == 0) okDown = true; }
            else if (okDown) { okDown = false; open(); }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (down) { if (e.getRepeatCount() == 0) backDown = true; }
            else if (backDown) {
                backDown = false;
                if (row > 0) {
                    row = 0;
                    focusChanged();
                } else {
                    activity.finish();
                }
            }
            return true;
        }
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (dx == 0 && dy == 0) return false;
        if (down) move(dx, dy);
        return true;
    }

    private void move(int dx, int dy) {
        int beforeRow = row, beforeCol = rows[row].col;
        if (dy != 0) {
            int next = row + dy;
            // Skips rows that ended up empty.
            while (next >= 0 && next < rows.length && !rows[next].loading && rows[next].items.isEmpty()) next += dy;
            if (next >= 0 && next < rows.length) row = next;
        } else {
            Row x = rows[row];
            x.col = Math.max(0, Math.min(Math.max(0, x.items.size() - 1), x.col + dx));
        }
        if (beforeRow != row || beforeCol != rows[row].col) {
            click.move();
            focusChanged();
        }
    }

    /** Opens the item in Spotify (straight to it when the app takes Spotify links, otherwise just the app). */
    private void open() {
        Title t = focused();
        if (t == null || t.uri == null) return;
        click.open();
        Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse(t.uri)).setPackage(SPOTIFY_PKG)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(link);
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        Intent app = HomeApps.launchIntent(activity.getPackageManager(), SPOTIFY_PKG);
        if (app == null) {
            toast(Ui.tr("O app do Spotify não está instalado.", "The Spotify app isn't installed."));
            return;
        }
        toast(Ui.tr("Abrindo o Spotify…", "Opening Spotify…"));
        app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(app);
    }
}
