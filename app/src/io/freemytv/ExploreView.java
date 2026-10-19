package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Explore screen, drawn by hand on one Canvas in 1920×1080 design pixels (scaled to the screen):
 *  - at the top, the focused title: its photo on the right, fading into the background, with name, type, genre, year,
 *    rating, the service it's on and the synopsis; behind everything, the same photo heavily blurred and dark;
 *  - rows: "Recommended for you" (the home screen's "Your Taste" list), new releases on your services, genre chips and
 *    the selected genre's titles;
 *  - search: a TV keyboard (arrows + OK, or a real keyboard) and results on the right, only titles on your services.
 * Posters are small (TMDB's 342 px, scaled to the card) and only the ones on screen are loaded, with a few dozen kept;
 * all of it goes away when the screen closes.
 */
final class ExploreView extends View {
    private static final class Title {
        int id;
        String kind, title, poster, backdrop, overview = "", meta = "", app, pkg;
        boolean detailed;
        StaticLayout label;   // the name, shown while the poster loads
    }

    private static final class Row {
        final String title, note;
        final boolean chips, badge;
        final List<Title> items = new ArrayList<>();
        boolean loading = true;
        int col;
        final Spring scroll = new Spring(0.34f, 0.9f, 0);

        Row(String title, String note, boolean chips, boolean badge) {
            this.title = title;
            this.note = note;
            this.chips = chips;
            this.badge = badge;
        }

        int size() { return chips ? GENRES.length : items.size(); }
    }

    /** Name (pt, en), TMDB movie genre, show genre ("" = no show equivalent). */
    private static final String[][] GENRES = {
        {"Ação", "Action", "28", "10759"}, {"Ficção científica", "Sci-Fi", "878", "10765"}, {"Guerra", "War", "10752", "10768"},
        {"Crime", "Crime", "80", "80"}, {"Drama", "Drama", "18", "18"}, {"Suspense", "Thriller", "53", ""},
        {"Comédia", "Comedy", "35", "35"}, {"Terror", "Horror", "27", ""}, {"Animação", "Animation", "16", "16"},
        {"Documentário", "Documentary", "99", "99"}, {"Mistério", "Mystery", "9648", "9648"}, {"Família", "Family", "10751", "10751"},
    };
    private static final String IMAGES = "https://image.tmdb.org/t/p/";
    private static final int REC = 0, NEW = 1, CHIPS = 2, GENRE = 3;
    private static final float M = 80, POSTER_W = 196, POSTER_H = 294, STEP = 224, ROWS_TOP = 560;
    private static final int BG = 0xFF0E0F12, INK = 0xFFF5F5F7, INK2 = 0xA3F5F5F7, INK3 = 0x6BF5F5F7;
    private static final int GLASS = 0x14FFFFFF, GLASS2 = 0x24FFFFFF, RIM = 0x1FFFFFFF;
    private static final String[] KEYS = "abcdefghijklmnopqrstuvwxyz1234567890".split("");

    private final Activity activity;
    private final Recs recs;
    private final Click click;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newFixedThreadPool(3);
    private final Row[] rows = new Row[4];
    private int genre;
    private int focusRow, focusCol;   // focusRow -1 = the search box
    private final Spring scrollY = new Spring(0.34f, 0.9f, 0), grow = new Spring(0.32f, 0.78f, 0), heroFade = new Spring(0.3f, 1f, 1);
    private long lastFrame;
    private boolean destroyed, okDown, backDown;

    // Images: posters by path (a few dozen, least recently drawn go first) and the hero's photos.
    private final Map<String, Bitmap> posters = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 48; }
    };
    private final java.util.Set<String> loadingPosters = new java.util.HashSet<>();
    private final Map<String, Bitmap[]> photos = new LinkedHashMap<String, Bitmap[]>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap[]> e) { return size() > 6; }
    };

    // Hero
    private Title hero, heroBefore;
    private Bitmap[] heroPhoto, heroPhotoBefore;   // {photo, tiny copy for the blurred background}
    private StaticLayout heroTitle, heroOverview;
    private String heroKicker = "";
    private final Runnable detailsLater = this::loadHeroDetails;

    // Search
    private boolean searching, inResults;
    private int keyFocus, resultFocus;
    private final StringBuilder query = new StringBuilder();
    private final List<Title> results = new ArrayList<>();
    private boolean searchBusy;
    private int searchGeneration;
    private final Runnable searchLater = this::runSearch;

    // Toast
    private String toast;
    private long toastAt;

    // Paints and scratch objects (no allocations while drawing)
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mask = new Paint();
    // The home screen's shadows and focus rim (HomeView: shadowDeep, shadow, rim, sheen).
    private final Paint softShadow = new Paint(Paint.ANTI_ALIAS_FLAG), pillShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pageTitle, rowTitle, rowNote, chip, chipOn, kicker, meta, pill, badge, keyText, queryText, small, toastText;
    private final TextPaint heroTitlePaint, overviewPaint, labelPaint;
    private final Shader skyShade, rowsFade;
    private final RectF r = new RectF(), r2 = new RectF();
    private final Rect src = new Rect();
    private final Path clip = new Path();
    private final Shader maskH, maskV;

    ExploreView(Activity a, boolean startInSearch) {
        super(a);
        activity = a;
        recs = Recs.get(a);
        click = new Click(a);
        setFocusable(true);
        pageTitle = Ui.text(Ui.displaySemibold, 64, INK);
        rowTitle = Ui.text(Ui.semibold, 28, INK);
        rowNote = Ui.text(Ui.medium, 22, INK3);
        chip = Ui.text(Ui.medium, 24, INK2);
        chipOn = Ui.text(Ui.semibold, 24, INK);
        kicker = Ui.text(Ui.semibold, 20, INK3);
        kicker.setLetterSpacing(0.12f);
        meta = Ui.text(Ui.regular, 24, INK2);
        pill = Ui.text(Ui.semibold, 20, INK);
        badge = Ui.text(Ui.semibold, 15, INK);
        badge.setLetterSpacing(0.06f);
        keyText = Ui.text(Ui.medium, 28, INK);
        queryText = Ui.text(Ui.regular, 34, INK);
        small = Ui.text(Ui.medium, 26, INK2);
        toastText = Ui.text(Ui.semibold, 24, 0xFF111214);
        heroTitlePaint = new TextPaint(Ui.text(Ui.displaySemibold, 60, INK));
        overviewPaint = new TextPaint(Ui.text(Ui.regular, 22, INK2));
        labelPaint = new TextPaint(Ui.text(Ui.medium, 22, INK2));
        skyShade = new LinearGradient(0, 0, 0, 1080, 0x1A0A0B0E, 0xD90A0B0E, Shader.TileMode.CLAMP);
        rowsFade = new LinearGradient(0, 960, 0, 1080, 0x000A0B0E, 0xE60A0B0E, Shader.TileMode.CLAMP);
        stroke.setStyle(Paint.Style.STROKE);
        softShadow.setMaskFilter(new android.graphics.BlurMaskFilter(30, android.graphics.BlurMaskFilter.Blur.NORMAL));
        pillShadow.setMaskFilter(new android.graphics.BlurMaskFilter(26, android.graphics.BlurMaskFilter.Blur.NORMAL));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        maskH = new LinearGradient(670, 0, 1232, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
        maskV = new LinearGradient(0, 380, 0, 700, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP);

        rows[REC] = new Row(Ui.tr("Recomendados para você", "Recommended for You"), Ui.tr("do seu gosto", "your taste"), false, false);
        rows[NEW] = new Row(Ui.tr("Lançamentos", "New Releases"), Ui.tr("nos seus streamings", "on your services"), false, true);
        rows[CHIPS] = new Row(Ui.tr("Gêneros", "Genres"), null, true, false);
        rows[CHIPS].loading = false;
        rows[GENRE] = new Row("", Ui.tr("filmes e séries", "movies and shows"), false, false);
        if (!Recs.hasKey(a)) {
            for (Row row : rows) row.loading = false;
            toast(Ui.tr("Sem chave do TMDB: veja o README (config.properties).", "No TMDB key: see the README (config.properties)."));
        } else {
            loadRec();
            loadNew();
            loadGenre();
        }
        if (startInSearch) openSearch();
    }

    void destroy() {
        destroyed = true;
        io.shutdownNow();
        images.shutdownNow();
        main.removeCallbacksAndMessages(null);
        click.release();
        synchronized (posters) { posters.clear(); }
        photos.clear();
    }

    // MARK: - Lists

    private void loadRec() {
        List<Title> list = new ArrayList<>();
        for (Recs.Pick p : recs.taste()) {
            if (p.poster == null) continue;
            Title t = new Title();
            t.id = p.id;
            t.kind = p.kind;
            t.title = p.title;
            t.poster = p.poster;
            t.backdrop = p.backdrop;
            t.app = p.app;
            t.pkg = p.pkg;
            list.add(t);
        }
        if (!list.isEmpty()) {
            rows[REC].items.addAll(list);
            rows[REC].loading = false;
            rowChanged(REC);
            return;
        }
        // No "Your Taste" list yet (no taste set, or not fetched): the most popular on your services.
        io.execute(() -> {
            List<Title> out = new ArrayList<>();
            try {
                String f = recs.servicesFilter();
                List<Title> m = discover("/discover/movie?sort_by=popularity.desc&vote_count.gte=500" + f, "movie");
                List<Title> s = discover("/discover/tv?sort_by=popularity.desc&vote_count.gte=200" + f, "tv");
                alternate(out, m, s, 20);
            } catch (Exception e) {
                AppLog.w("explore: " + e.getMessage());
            }
            main.post(() -> fill(REC, out));
        });
    }

    private void loadNew() {
        io.execute(() -> {
            List<Title> out = new ArrayList<>();
            try {
                String f = recs.servicesFilter();
                List<Title> m = discover("/discover/movie?sort_by=popularity.desc&vote_count.gte=20&primary_release_date.gte="
                        + day(-90) + "&primary_release_date.lte=" + day(0) + f, "movie");
                List<Title> s = discover("/discover/tv?sort_by=popularity.desc&vote_count.gte=10&first_air_date.gte="
                        + day(-180) + "&first_air_date.lte=" + day(0) + f, "tv");
                alternate(out, m, s, 20);
            } catch (Exception e) {
                AppLog.w("explore: " + e.getMessage());
            }
            main.post(() -> fill(NEW, out));
        });
    }

    private void loadGenre() {
        final int g = genre;
        rows[GENRE].items.clear();
        rows[GENRE].loading = true;
        rows[GENRE].col = 0;
        rows[GENRE].scroll.snap(0);
        io.execute(() -> {
            List<Title> out = new ArrayList<>();
            try {
                String f = recs.servicesFilter();
                List<Title> m = discover("/discover/movie?sort_by=popularity.desc&vote_count.gte=300&with_genres=" + GENRES[g][2] + f, "movie");
                List<Title> s = GENRES[g][3].isEmpty() ? new ArrayList<>()
                        : discover("/discover/tv?sort_by=popularity.desc&vote_count.gte=100&with_genres=" + GENRES[g][3] + f, "tv");
                alternate(out, m, s, 20);
            } catch (Exception e) {
                AppLog.w("explore: " + e.getMessage());
            }
            main.post(() -> {
                if (g == genre) fill(GENRE, out);
            });
        });
    }

    private void fill(int row, List<Title> list) {
        if (destroyed) return;
        rows[row].items.clear();
        rows[row].items.addAll(list);
        rows[row].loading = false;
        rowChanged(row);
    }

    private void rowChanged(int row) {
        if (focusRow == row) focusCol = Math.min(focusCol, Math.max(0, rows[row].size() - 1));
        if (hero == null || focusRow == row) focusChanged();
        invalidate();
    }

    private List<Title> discover(String path, String kind) throws Exception {
        List<Title> out = new ArrayList<>();
        JSONArray a = Recs.tmdb(path).getJSONArray("results");
        for (int i = 0; i < a.length(); i++) {
            Title t = title(a.getJSONObject(i), kind);
            if (t != null) out.add(t);
        }
        return out;
    }

    private static Title title(JSONObject o, String kind) {
        String poster = o.optString("poster_path", ""), backdrop = o.optString("backdrop_path", "");
        if (poster.isEmpty() || "null".equals(poster)) return null;
        Title t = new Title();
        t.id = o.optInt("id");
        t.kind = kind;
        t.title = o.optString(kind.equals("movie") ? "title" : "name");
        t.poster = poster;
        t.backdrop = backdrop.isEmpty() || "null".equals(backdrop) ? null : backdrop;
        return t;
    }

    private static void alternate(List<Title> out, List<Title> a, List<Title> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size() && out.size() < max) out.add(b.get(i));
        }
    }

    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    // MARK: - The focused title (hero)

    private Title focused() {
        if (searching) return inResults && resultFocus < results.size() ? results.get(resultFocus) : null;
        if (focusRow < 0 || rows[focusRow].chips) return null;
        Row row = rows[focusRow];
        return focusCol < row.items.size() ? row.items.get(focusCol) : null;
    }

    private void focusChanged() {
        grow.snap(0);
        grow.target = 1;
        Title t = focused();
        if (t == null && hero == null) {
            for (Row row : rows) if (!row.chips && !row.items.isEmpty()) { t = row.items.get(0); break; }
        }
        if (t != null && t != hero) {
            heroBefore = hero;
            heroPhotoBefore = heroPhoto;
            hero = t;
            heroPhoto = t.backdrop == null ? null : photos.get(t.backdrop);
            heroFade.snap(heroPhoto != null ? 0 : 1);
            heroFade.target = 1;
            heroKicker = kickerFor(t);
            layoutHero();
            main.removeCallbacks(detailsLater);
            main.postDelayed(detailsLater, 180);
        }
        startFrames();
    }

    private String kickerFor(Title t) {
        if (searching) return Ui.tr("Resultado da busca", "Search result");
        if (rows[REC].items.contains(t)) return Ui.tr("Recomendado para você", "Recommended for you");
        if (rows[NEW].items.contains(t)) return Ui.tr("Lançamento", "New release");
        return Ui.en ? GENRES[genre][1] : GENRES[genre][0];
    }

    private void layoutHero() {
        if (hero == null) return;
        heroTitle = layout(hero.title, heroTitlePaint, 860, 2, 1.0f);
        // A two-line name leaves room for two lines of synopsis (more would run into the rows).
        heroOverview = layout(hero.overview, overviewPaint, 860, heroTitle.getLineCount() > 1 ? 2 : 3, 1.4f);
    }

    private static StaticLayout layout(String text, TextPaint p, int width, int lines, float spacing) {
        return StaticLayout.Builder.obtain(text == null ? "" : text, 0, text == null ? 0 : text.length(), p, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, spacing).setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END).build();
    }

    /** Details for the focused title (genre, length, rating, synopsis, service) and its photo, in the background. */
    private void loadHeroDetails() {
        final Title t = hero;
        if (t == null || destroyed) return;
        if (!t.detailed) {
            io.execute(() -> {
                try {
                    details(t);
                } catch (Exception e) {
                    AppLog.w("explore details: " + e.getMessage());
                }
                main.post(() -> {
                    if (t == hero) {
                        layoutHero();
                        loadPhoto(t);
                        invalidate();
                    }
                });
            });
        } else {
            loadPhoto(t);
        }
    }

    private void details(Title t) throws Exception {
        JSONObject o = Recs.tmdb("/" + t.kind + "/" + t.id + "?language=" + Recs.lang() + "&append_to_response=watch/providers");
        List<String> parts = new ArrayList<>();
        parts.add(t.kind.equals("movie") ? Ui.tr("Filme", "Movie") : Ui.tr("Série", "Series"));
        JSONArray genres = o.optJSONArray("genres");
        if (genres != null && genres.length() > 0) parts.add(Recs.genreName(genres.getJSONObject(0).optString("name")));
        String date = o.optString(t.kind.equals("movie") ? "release_date" : "first_air_date", "");
        if (date.length() >= 4) parts.add(date.substring(0, 4));
        double vote = o.optDouble("vote_average", 0);
        if (vote > 0) parts.add("★ " + String.format(Ui.en ? Locale.US : Ui.PT, "%.1f", vote));
        String[] s = recs.serviceOf(o.optJSONObject("watch/providers"));
        if (s != null) {
            t.app = s[0];
            t.pkg = s[1];
        }
        t.meta = String.join("  ·  ", parts);
        t.overview = o.optString("overview", "");
        if (t.backdrop == null) {
            String b = o.optString("backdrop_path", "");
            if (!b.isEmpty() && !"null".equals(b)) t.backdrop = b;
        }
        t.detailed = true;
    }

    private void loadPhoto(Title t) {
        if (t.backdrop == null || photos.containsKey(t.backdrop)) {
            if (t == hero && t.backdrop != null && heroPhoto == null) {
                heroPhoto = photos.get(t.backdrop);
                startFrames();
            }
            return;
        }
        final String path = t.backdrop;
        images.execute(() -> {
            Bitmap[] pair = null;
            try {
                Bitmap b = decode(download("w780", path), 1250, 703);
                if (b != null) pair = new Bitmap[] {b, Bitmap.createScaledBitmap(b, 32, 18, true)};
            } catch (Exception e) {
                AppLog.w("explore photo: " + e.getMessage());
            }
            final Bitmap[] done = pair;
            main.post(() -> {
                if (destroyed || done == null) return;
                photos.put(path, done);
                if (hero != null && path.equals(hero.backdrop)) {
                    heroPhoto = done;
                    heroFade.snap(0);
                    heroFade.target = 1;
                    startFrames();
                }
            });
        });
    }

    // MARK: - Posters

    private Bitmap poster(Title t) {
        Bitmap b;
        synchronized (posters) { b = posters.get(t.poster); }
        if (b != null || destroyed) return b;
        final String path = t.poster;
        synchronized (posters) { if (!loadingPosters.add(path)) return null; }
        images.execute(() -> {
            Bitmap done = null;
            try {
                done = decode(download("w342", path), Math.round(POSTER_W), Math.round(POSTER_H));
            } catch (Exception e) {
                AppLog.w("explore poster: " + e.getMessage());
            }
            final Bitmap result = done;
            synchronized (posters) {
                loadingPosters.remove(path);
                if (result != null) posters.put(path, result);
            }
            if (result != null) main.post(this::invalidate);
        });
        return null;
    }

    /** The image at TMDB's `size`, from the disk cache or downloaded into it. */
    private byte[] download(String size, String path) throws IOException {
        File dir = new File(getContext().getCacheDir(), "explore");
        File f = new File(dir, size + path.replace("/", "_"));
        if (f.exists()) return readAll(new java.io.FileInputStream(f));
        HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + size + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            byte[] data = readAll(c.getInputStream());
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
            return data;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** Decodes and scales to cover w×h (16 bits per pixel: photos have no transparency). */
    private static Bitmap decode(byte[] data, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null) return null;
        float s = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
        int sw = Math.round(b.getWidth() * s), sh = Math.round(b.getHeight() * s);
        Bitmap scaled = Bitmap.createScaledBitmap(b, sw, sh, true);
        if (scaled != b) b.recycle();
        if (sw == w && sh == h) return scaled;
        Bitmap out = Bitmap.createBitmap(scaled, (sw - w) / 2, 0, Math.min(w, sw), Math.min(h, sh));
        if (out != scaled) scaled.recycle();
        return out;
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
        boolean moving = step(dt);
        float k = getWidth() / 1920f;
        c.drawColor(BG);
        c.save();
        c.scale(k, k);
        drawSky(c);
        if (searching) {
            drawSearch(c, now);
        } else {
            drawHero(c);
            c.drawText(Ui.tr("Explorar", "Explore"), M, 58 + Ui.cap(pageTitle), pageTitle);
            drawSearchBox(c);
            drawRows(c);
        }
        drawToast(c, now);
        c.restore();
        // The scroll targets are set while drawing (they depend on the layout): keep going until they settle.
        moving |= !scrollY.settled();
        for (Row row : rows) moving |= !row.scroll.settled();
        if (moving || (toast != null && now - toastAt < 2400) || searching) postInvalidateOnAnimation();
    }

    private boolean step(float dt) {
        boolean moving = scrollY.step(dt) | grow.step(dt) | heroFade.step(dt);
        for (Row row : rows) moving |= row.scroll.step(dt);
        return moving;
    }

    /** The focused title's photo, blurred (a 32×18 copy stretched to the screen) and darkened. */
    private void drawSky(Canvas c) {
        Bitmap[] now = heroPhoto, before = heroPhotoBefore;
        float f = heroFade.value;
        r.set(-60, -60, 1980, 1140);
        if (before != null && f < 1) {
            bitmapPaint.setAlpha(255);
            c.drawBitmap(before[1], null, r, bitmapPaint);
        }
        if (now != null) {
            bitmapPaint.setAlpha(Math.round(255 * (before != null ? Ui.clamp(f) : 1)));
            c.drawBitmap(now[1], null, r, bitmapPaint);
            bitmapPaint.setAlpha(255);
        }
        fill.setShader(null);
        fill.setColor(0xC70A0B0E);
        c.drawRect(0, 0, 1920, 1080, fill);
        fill.setShader(skyShade);
        c.drawRect(0, 0, 1920, 1080, fill);
        fill.setShader(null);
    }

    private void drawHero(Canvas c) {
        if (hero == null) return;
        float f = Ui.clamp(heroFade.value);
        if (heroPhotoBefore != null && f < 1) drawPhoto(c, heroPhotoBefore[0], 1 - f);
        if (heroPhoto != null) drawPhoto(c, heroPhoto[0], f);
        float y = 190;
        c.drawText(heroKicker.toUpperCase(Ui.en ? Locale.US : Ui.PT), M, y + Ui.cap(kicker), kicker);
        y += 44;
        if (heroTitle != null) {
            c.save();
            c.translate(M, y);
            heroTitle.draw(c);
            c.restore();
            y += heroTitle.getHeight() + 18;
        }
        float x = M;
        if (!hero.meta.isEmpty()) {
            c.drawText(hero.meta, x, y + Ui.cap(meta), meta);
            x += meta.measureText(hero.meta) + 20;
        }
        String service = Recs.serviceName(hero.app);
        if (!service.isEmpty()) {
            float w = pill.measureText(service) + 28;
            r.set(x, y - 6, x + w, y + Ui.cap(meta) + 8);
            fill.setColor(GLASS2);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            c.drawText(service, x + 14, r.centerY() + Ui.halfCap(pill), pill);
        }
        y += 44;
        if (heroOverview != null) {
            c.save();
            c.translate(M, y);
            heroOverview.draw(c);
            c.restore();
        }
    }

    /** The photo on the right, fading out to the left and at the bottom (masked in a layer). */
    private void drawPhoto(Canvas c, Bitmap b, float alpha) {
        if (alpha <= 0.01f) return;
        r.set(670, 0, 1920, 703);
        int layer = c.saveLayerAlpha(r, Math.round(255 * Ui.clamp(alpha)));
        c.drawBitmap(b, null, r, bitmapPaint);
        mask.setShader(maskH);
        c.drawRect(r, mask);
        mask.setShader(maskV);
        c.drawRect(r, mask);
        mask.setShader(null);
        c.restoreToCount(layer);
    }

    private void drawSearchBox(Canvas c) {
        boolean on = focusRow == -1;
        float f = on ? Ui.clamp(grow.value) : 0;
        r.set(1920 - M - 560, 62, 1920 - M, 130);
        scale(r, 1 + 0.05f * f);
        if (on) shadow(c, r, r.height() / 2, f);
        fill.setColor(on ? INK : 0x9E121318);
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
        if (!on) outline(c, r, r.height() / 2);
        Paint p = on ? toastText : small;
        float cx = r.left + 46, cy = r.centerY();
        magnifier(c, cx, cy, 13, on ? 0xFF111214 : INK2);
        c.drawText(Ui.tr("Buscar filmes e séries", "Search movies and shows"), r.left + 76, cy + Ui.halfCap(p), p);
    }

    private void drawRows(Canvas c) {
        float target = rowTop(Math.max(0, focusRow == GENRE ? CHIPS : focusRow));
        scrollY.target = target;
        c.save();
        c.clipRect(0, ROWS_TOP - 8, 1920, 1080);
        float y = ROWS_TOP - scrollY.value;
        for (int i = 0; i < rows.length; i++) {
            Row row = rows[i];
            float h = row.chips ? 150 : 380;
            if (y + h > ROWS_TOP - 30 && y < 1080) drawRow(c, i, row, y);
            y += h;
        }
        c.restore();
        // Fades the rows out at the bottom edge.
        fill.setShader(rowsFade);
        c.drawRect(0, 960, 1920, 1080, fill);
        fill.setShader(null);
    }

    private static float rowTop(int index) {
        return index <= 0 ? 0 : index == 1 ? 380 : index == 2 ? 760 : 910;
    }

    private void drawRow(Canvas c, int index, Row row, float top) {
        String title = index == GENRE ? (Ui.en ? GENRES[genre][1] : GENRES[genre][0]) : row.title;
        c.drawText(title, M, top + Ui.cap(rowTitle), rowTitle);
        if (row.note != null) c.drawText(row.note, M + rowTitle.measureText(title) + 14, top + Ui.cap(rowTitle), rowNote);
        boolean rowFocused = focusRow == index && !searching;
        int col = rowFocused ? focusCol : row.col;
        if (row.chips) {
            drawChips(c, top + 52, rowFocused ? col : -1);
            return;
        }
        int n = row.items.size();
        row.scroll.target = Math.max(0, Math.min((col - 2) * STEP, n * STEP - (1920 - 2 * M)));
        float x0 = M - row.scroll.value, y = top + 56;
        if (n == 0) {
            if (row.loading) for (int i = 0; i < 8; i++) placeholder(c, x0 + i * STEP, y);
            else c.drawText(Ui.tr("Nada por aqui agora.", "Nothing here right now."), M, y + 40, rowNote);
            return;
        }
        int focusedIndex = rowFocused ? col : -1;
        for (int i = 0; i < n; i++) {
            float x = x0 + i * STEP;
            if (x + POSTER_W < -40 || x > 1960 || i == focusedIndex) continue;
            drawPoster(c, row.items.get(i), x, y, POSTER_W, POSTER_H, 0, row.badge);
        }
        if (focusedIndex >= 0 && focusedIndex < n) {
            drawPoster(c, row.items.get(focusedIndex), x0 + focusedIndex * STEP, y, POSTER_W, POSTER_H, grow.value, row.badge);
        }
    }

    private void placeholder(Canvas c, float x, float y) {
        r.set(x, y, x + POSTER_W, y + POSTER_H);
        fill.setColor(GLASS);
        c.drawRoundRect(r, 18, 18, fill);
    }

    /**
     * A poster with the home screen's card focus (HomeView.drawLightCard): 5% larger and lifted a little, a soft shadow
     * that deepens, a diagonal sheen and a light rim that fades toward the bottom. `v` is the focus spring's value.
     */
    private void drawPoster(Canvas c, Title t, float x, float y, float w, float h, float v, boolean isNew) {
        float f = Ui.clamp(v), s = 1 + 0.05f * v, rad = 18 * (w / POSTER_W) * s;
        float cx = x + w / 2, cy = y + h / 2 - 6 * v;
        r.set(cx - w * s / 2, cy - h * s / 2, cx + w * s / 2, cy + h * s / 2);
        r2.set(r);
        r2.offset(0, 10 + 14 * f);
        softShadow.setColor(Ui.alpha(0xFF000000, 0.32f + 0.28f * f));
        c.drawRoundRect(r2, rad, rad, softShadow);
        c.save();
        clip.reset();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        Bitmap b = poster(t);
        if (b != null) {
            c.drawBitmap(b, null, r, bitmapPaint);
        } else {
            fill.setColor(0xFF2C2C2E);
            c.drawRect(r, fill);
            if (t.title != null) {
                if (t.label == null) t.label = layout(t.title, labelPaint, Math.round(POSTER_W - 24), 3, 1.1f);
                StaticLayout l = t.label;
                c.save();
                c.translate(r.left + 12, r.centerY() - l.getHeight() / 2f);
                l.draw(c);
                c.restore();
            }
        }
        if (f > 0.01f) {
            sheen.setShader(new LinearGradient(r.left, r.top, r.left + r.width() * 0.5f, r.bottom,
                    Ui.alpha(0xFFFFFFFF, 0.22f * f), 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(r, sheen);
        }
        c.restore();
        if (isNew) {
            String label = Ui.tr("NOVO", "NEW");
            r2.set(r.left + 10, r.top + 10, r.left + 10 + badge.measureText(label) + 20, r.top + 36);
            fill.setColor(0xBF0A0A0C);
            c.drawRoundRect(r2, 13, 13, fill);
            c.drawText(label, r2.left + 10, r2.centerY() + Ui.halfCap(badge), badge);
        }
        if (f > 0.01f) {
            rim.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    Ui.alpha(0xFFFFFFFF, 0.9f * f), Ui.alpha(0xFFFFFFFF, 0.35f * f), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, rim);
        }
    }

    private void drawChips(Canvas c, float y, int focusedChip) {
        float x = M - chipsScroll(focusedChip);
        for (int i = 0; i < GENRES.length; i++) {
            String name = Ui.en ? GENRES[i][1] : GENRES[i][0];
            boolean on = i == genre, focus = i == focusedChip;
            Paint p = focus ? toastText : on ? chipOn : chip;
            float w = p.measureText(name) + 60;
            r.set(x, y, x + w, y + 64);
            if (focus) {
                float f = Ui.clamp(grow.value);
                scale(r, 1 + 0.05f * f);
                shadow(c, r, r.height() / 2, f);
                fill.setColor(INK);
            } else {
                fill.setColor(on ? GLASS2 : GLASS);
            }
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            if (!focus) {
                stroke.setColor(on ? 0x59FFFFFF : RIM);
                stroke.setStrokeWidth(on ? 2 : 1);
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, stroke);
            }
            c.drawText(name, r.centerX() - p.measureText(name) / 2, r.centerY() + Ui.halfCap(p), p);
            x += w + 16;
        }
    }

    /** How far the chips scroll so the focused one stays on screen. */
    private float chipsScroll(int focusedChip) {
        int target = focusedChip >= 0 ? focusedChip : genre;
        float x = 0;
        for (int i = 0; i <= target; i++) x += chip.measureText(Ui.en ? GENRES[i][1] : GENRES[i][0]) + 76;
        return Math.max(0, x - 16 - (1920 - 2 * M));
    }

    // MARK: - Search screen

    private void drawSearch(Canvas c, long now) {
        fill.setColor(0xB30A0B0E);
        c.drawRect(0, 0, 1920, 1080, fill);
        r.set(M, 70, 1920 - M, 154);
        fill.setColor(GLASS);
        c.drawRoundRect(r, 42, 42, fill);
        outline(c, r, 42);
        magnifier(c, r.left + 52, r.centerY(), 16, INK3);
        float tx = r.left + 92, base = r.centerY() + Ui.halfCap(queryText);
        if (query.length() == 0) {
            c.drawText(Ui.tr("Digite o nome de um filme ou série", "Type a movie or show name"), tx, base, rowNote);
        } else {
            c.drawText(query.toString(), tx, base, queryText);
            tx += queryText.measureText(query.toString()) + 4;
        }
        if ((now / 530) % 2 == 0 && !inResults) {
            fill.setColor(INK);
            c.drawRect(tx, r.centerY() - 20, tx + 3, r.centerY() + 20, fill);
        }
        drawKeys(c);
        drawResults(c);
    }

    private void drawKeys(Canvas c) {
        for (int i = 0; i < 39; i++) {
            keyRect(i, r);
            boolean focus = !inResults && i == keyFocus;
            if (focus) {
                float f = Ui.clamp(grow.value);
                scale(r, 1 + 0.05f * f);
                shadow(c, r, 16, f);
                fill.setColor(INK);
            } else {
                fill.setColor(GLASS);
            }
            c.drawRoundRect(r, 16, 16, fill);
            if (!focus) outline(c, r, 16);
            String label = i < 36 ? KEYS[i] : i == 36 ? Ui.tr("Espaço", "Space") : i == 37 ? Ui.tr("Apagar", "Delete") : Ui.tr("Limpar", "Clear");
            Paint p = i < 36 ? keyText : chip;
            int color = p.getColor();
            p.setColor(focus ? 0xFF111214 : (i < 36 ? INK : INK2));
            c.drawText(label, r.centerX() - p.measureText(label) / 2, r.centerY() + Ui.halfCap(p), p);
            p.setColor(color);
        }
    }

    private static void keyRect(int i, RectF out) {
        float x0 = M, y0 = 200, w = 84, h = 72, g = 12;
        if (i < 36) {
            float x = x0 + (i % 10) * (w + g), y = y0 + (i / 10) * (h + g);
            out.set(x, y, x + w, y + h);
        } else {
            float wide = 3 * w + 2 * g, x = x0 + (i - 36) * (wide + g), y = y0 + 4 * (h + g);
            out.set(x, y, x + wide, y + h);
        }
    }

    private void drawResults(Canvas c) {
        float x0 = 1100, y0 = 200;
        String q = query.toString().trim();
        String head = q.isEmpty() ? Ui.tr("Recomendados para você", "Recommended for you")
                : Ui.tr("Resultados para “" + q + "”", "Results for “" + q + "”");
        c.drawText(head, x0, y0 + Ui.cap(small), small);
        List<Title> list = q.isEmpty() ? rows[REC].items : results;
        if (list.isEmpty()) {
            String status = searchBusy ? Ui.tr("Buscando…", "Searching…")
                    : q.isEmpty() ? "" : Ui.tr("Nada encontrado nos seus streamings.", "Nothing found on your services.");
            c.drawText(status, x0, y0 + 90, rowNote);
            return;
        }
        int n = Math.min(list.size(), 12);
        for (int i = 0; i < n; i++) {
            if (inResults && i == resultFocus) continue;
            drawPoster(c, list.get(i), x0 + (i % 4) * 184, y0 + 56 + (i / 4) * 264, 160, 240, 0, false);
        }
        if (inResults && resultFocus < n) {
            int i = resultFocus;
            drawPoster(c, list.get(i), x0 + (i % 4) * 184, y0 + 56 + (i / 4) * 264, 160, 240, grow.value, false);
        }
        if (searchBusy) c.drawText(Ui.tr("Buscando mais…", "Searching for more…"), x0, 1040, rowNote);
    }

    private List<Title> resultList() {
        return query.toString().trim().isEmpty() ? rows[REC].items : results;
    }

    private void openSearch() {
        searching = true;
        inResults = false;
        startFrames();
    }

    private void closeSearch() {
        searching = false;
        hero = null;
        focusChanged();
        invalidate();
    }

    private void queryChanged() {
        searchGeneration++;
        results.clear();
        resultFocus = 0;
        inResults = false;
        main.removeCallbacks(searchLater);
        searchBusy = !query.toString().trim().isEmpty();
        if (searchBusy) main.postDelayed(searchLater, 500);
        invalidate();
    }

    /** TMDB search, then keeps only what is on one of your services (checked title by title, shown as found). */
    private void runSearch() {
        final String q = query.toString().trim();
        final int gen = searchGeneration;
        if (q.isEmpty()) return;
        io.execute(() -> {
            try {
                JSONArray found = Recs.tmdb("/search/multi?language=" + Recs.lang() + "&include_adult=false&query="
                        + URLEncoder.encode(q, "UTF-8")).getJSONArray("results");
                int kept = 0;
                for (int i = 0; i < found.length() && i < 20 && kept < 12; i++) {
                    if (gen != searchGeneration || destroyed) return;
                    JSONObject o = found.getJSONObject(i);
                    String kind = o.optString("media_type");
                    if (!kind.equals("movie") && !kind.equals("tv")) continue;
                    Title t = title(o, kind);
                    if (t == null) continue;
                    try {
                        details(t);
                    } catch (Exception e) {
                        continue;
                    }
                    if (t.pkg == null) continue;
                    kept++;
                    main.post(() -> {
                        if (gen != searchGeneration) return;
                        results.add(t);
                        invalidate();
                    });
                }
            } catch (Exception e) {
                AppLog.w("explore search: " + e.getMessage());
            }
            main.post(() -> {
                if (gen != searchGeneration) return;
                searchBusy = false;
                invalidate();
            });
        });
    }

    // MARK: - Small helpers

    private void drawToast(Canvas c, long now) {
        if (toast == null) return;
        float t = (now - toastAt) / 1000f;
        float a = t < 0.25f ? t / 0.25f : t > 2.1f ? Math.max(0, 1 - (t - 2.1f) / 0.3f) : 1;
        if (a <= 0) {
            toast = null;
            return;
        }
        float w = toastText.measureText(toast) + 68;
        r.set(960 - w / 2, 960 - 30 * (1 - a), 960 + w / 2, 1024 - 30 * (1 - a));
        fill.setColor(Ui.alpha(INK, a));
        c.drawRoundRect(r, 32, 32, fill);
        int color = toastText.getColor();
        toastText.setColor(Ui.alpha(color, a));
        c.drawText(toast, r.left + 34, r.centerY() + Ui.halfCap(toastText), toastText);
        toastText.setColor(color);
    }

    private void toast(String text) {
        toast = text;
        toastAt = SystemClock.uptimeMillis();
        startFrames();
    }

    /** The home screen's tab focus shadow: soft, below, growing with the focus. */
    private void shadow(Canvas c, RectF rect, float rad, float focus) {
        r2.set(rect);
        r2.offset(0, 8 * focus);
        pillShadow.setColor(Ui.alpha(0xFF000000, 0.4f * focus));
        c.drawRoundRect(r2, rad, rad, pillShadow);
    }

    private void outline(Canvas c, RectF rect, float rad) {
        stroke.setColor(RIM);
        stroke.setStrokeWidth(1.5f);
        c.drawRoundRect(rect, rad, rad, stroke);
    }

    private void magnifier(Canvas c, float cx, float cy, float rad, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(rad * 0.3f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        c.drawCircle(cx - rad * 0.2f, cy - rad * 0.2f, rad * 0.75f, stroke);
        c.drawLine(cx + rad * 0.35f, cy + rad * 0.35f, cx + rad, cy + rad, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    private static void scale(RectF rect, float s) {
        float w = rect.width() * s / 2, h = rect.height() * s / 2, cx = rect.centerX(), cy = rect.centerY();
        rect.set(cx - w, cy - h, cx + w, cy + h);
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int code = e.getKeyCode();
        // OK and Back act on release, and only if pressed here (a press from the previous screen does nothing).
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down) { if (e.getRepeatCount() == 0) okDown = true; }
            else if (okDown) { okDown = false; ok(); }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (down) { if (e.getRepeatCount() == 0) backDown = true; }
            else if (backDown) { backDown = false; back(); }
            return true;
        }
        if (!down) return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (code == KeyEvent.KEYCODE_SEARCH) {
            if (!searching) openSearch();
            return true;
        }
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (dx != 0 || dy != 0) {
            if (searching) moveSearch(dx, dy); else move(dx, dy);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DEL) {
            if (searching && query.length() > 0) {
                query.setLength(query.length() - 1);
                queryChanged();
            }
            return true;
        }
        int ch = e.getUnicodeChar();
        if (ch > 0 && (Character.isLetterOrDigit(ch) || ch == ' ')) {
            if (!searching) openSearch();
            query.appendCodePoint(Character.toLowerCase(ch));
            queryChanged();
            return true;
        }
        return false;
    }

    private void move(int dx, int dy) {
        int row = focusRow, col = focusCol;
        if (dy != 0) {
            if (focusRow >= 0) rows[focusRow].col = focusCol;
            int next = focusRow + dy;
            if (next < -1 || next >= rows.length) return;
            focusRow = next;
            focusCol = next < 0 ? 0 : Math.min(rows[next].col, Math.max(0, rows[next].size() - 1));
        } else if (focusRow >= 0) {
            focusCol = Math.max(0, Math.min(rows[focusRow].size() - 1, focusCol + dx));
        }
        if (row != focusRow || col != focusCol) {
            click.move();
            focusChanged();
        }
    }

    private void moveSearch(int dx, int dy) {
        int before = inResults ? 100 + resultFocus : keyFocus;
        List<Title> list = resultList();
        int n = Math.min(list.size(), 12);
        if (inResults) {
            int col = resultFocus % 4;
            if (dx < 0) { if (col == 0) inResults = false; else resultFocus--; }
            if (dx > 0 && col < 3 && resultFocus + 1 < n) resultFocus++;
            if (dy < 0 && resultFocus >= 4) resultFocus -= 4;
            if (dy > 0 && resultFocus + 4 < n) resultFocus += 4;
        } else if (keyFocus < 36) {
            int col = keyFocus % 10, line = keyFocus / 10;
            if (dx < 0 && col > 0) keyFocus--;
            if (dx > 0) { if (col == 9 || keyFocus == 35) { if (n > 0) { inResults = true; resultFocus = 0; } } else keyFocus++; }
            if (dy < 0 && line > 0) keyFocus -= 10;
            if (dy > 0) keyFocus = keyFocus + 10 < 36 ? keyFocus + 10 : 36 + Math.min(2, col / 3);
        } else {
            if (dx < 0 && keyFocus > 36) keyFocus--;
            if (dx > 0) { if (keyFocus < 38) keyFocus++; else if (n > 0) { inResults = true; resultFocus = 0; } }
            if (dy < 0) keyFocus = 30 + Math.min(5, (keyFocus - 36) * 3);
        }
        if (before != (inResults ? 100 + resultFocus : keyFocus)) {
            click.move();
            grow.snap(0);
            grow.target = 1;
            if (inResults) focusChanged();
            startFrames();
        }
    }

    private void ok() {
        if (searching) {
            if (inResults) {
                List<Title> list = resultList();
                if (resultFocus < list.size()) open(list.get(resultFocus));
                return;
            }
            if (keyFocus < 36) query.append(KEYS[keyFocus]);
            else if (keyFocus == 36) query.append(' ');
            else if (keyFocus == 37) { if (query.length() > 0) query.setLength(query.length() - 1); }
            else query.setLength(0);
            click.move();
            queryChanged();
            return;
        }
        if (focusRow == -1) {
            click.open();
            openSearch();
            return;
        }
        Row row = rows[focusRow];
        if (row.chips) {
            if (focusCol != genre) {
                genre = focusCol;
                click.open();
                loadGenre();
                invalidate();
            }
            return;
        }
        if (focusCol < row.items.size()) open(row.items.get(focusCol));
    }

    /** Opens the title's streaming app (the details say which; fetched first if they aren't in yet). */
    private void open(Title t) {
        if (t.pkg == null && !t.detailed) {
            io.execute(() -> {
                try {
                    details(t);
                } catch (Exception ignored) {
                }
                main.post(() -> { if (!destroyed) launch(t); });
            });
            return;
        }
        launch(t);
    }

    private void launch(Title t) {
        if (t.pkg == null) {
            toast(Ui.tr("Não está nos seus streamings.", "Not on your streaming services."));
            return;
        }
        Intent intent = HomeApps.launchIntent(activity.getPackageManager(), t.pkg);
        if (intent == null) {
            toast(Ui.tr("O app não está instalado.", "The app isn't installed."));
            return;
        }
        click.open();
        toast(Ui.tr("Abrindo no ", "Opening in ") + Recs.serviceName(t.app) + "…");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            toast(Ui.tr("Não deu para abrir o app.", "Couldn't open the app."));
        }
    }

    private void back() {
        if (searching) {
            closeSearch();
        } else if (focusRow > 0) {
            if (focusRow >= 0) rows[focusRow].col = focusCol;
            focusRow = 0;
            focusCol = rows[0].col;
            focusChanged();
        } else {
            activity.finish();
        }
    }
}
