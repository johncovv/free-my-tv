package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
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

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
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
    private static final int REC = 0, NEW = 1, CHIPS = 2, GENRE = 3;
    private static final float M = 80, POSTER_W = Cards.POSTER_W, POSTER_H = Cards.POSTER_H, STEP = 224, ROWS_TOP = 560;
    private static final int BG = Cards.BG, INK = Cards.INK, INK2 = Cards.INK2, INK3 = Cards.INK3;
    private static final int GLASS = Cards.GLASS, GLASS2 = Cards.GLASS2, RIM = Cards.RIM;
    private static final String[] KEYS = "abcdefghijklmnopqrstuvwxyz1234567890".split("");

    private final Activity activity;
    private final Recs recs;
    private final Click click;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Cards cards;
    private final Row[] rows = new Row[4];
    private int genre;
    private int focusRow, focusCol;   // focusRow -1 = the search box
    private final Spring scrollY = new Spring(0.34f, 0.9f, 0), grow = new Spring(0.32f, 0.78f, 0), heroFade = new Spring(0.3f, 1f, 1);
    private long lastFrame;
    private boolean destroyed, okDown, backDown;

    // Hero
    private Title hero, heroBefore;
    private Bitmap[] heroPhoto, heroPhotoBefore;   // {photo, tiny copy for the blurred background}
    private StaticLayout heroTitle, heroOverview;
    private String heroKicker = "";
    private final Runnable detailsLater = this::loadHeroDetails;

    // Search
    private boolean searching, inResults;
    private int keyFocus, resultFocus;   // keyFocus MIC = the microphone button in the search bar
    private static final int MIC = -1;
    // Voice search: hold OK on the microphone and speak; letting go searches what you said (it fills the bar as you
    // speak).
    private android.speech.SpeechRecognizer speech;
    private boolean listening;
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
    private final Paint pageTitle, rowTitle, rowNote, chip, chipOn, kicker, meta, pill, keyText, queryText, small, toastText;
    private final TextPaint heroTitlePaint, overviewPaint;
    private final Shader rowsFade;
    private final RectF r = new RectF(), r2 = new RectF();

    ExploreView(Activity a, boolean startInSearch) {
        super(a);
        activity = a;
        recs = Recs.get(a);
        click = new Click(a);
        cards = new Cards(a, this::invalidate);
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
        keyText = Ui.text(Ui.medium, 28, INK);
        queryText = Ui.text(Ui.regular, 34, INK);
        small = Ui.text(Ui.medium, 26, INK2);
        toastText = Ui.text(Ui.semibold, 24, 0xFF111214);
        heroTitlePaint = new TextPaint(Ui.text(Ui.displaySemibold, 60, INK));
        overviewPaint = new TextPaint(Ui.text(Ui.regular, 22, INK2));
        rowsFade = new LinearGradient(0, 960, 0, 1080, 0x000A0B0E, 0xE60A0B0E, Shader.TileMode.CLAMP);
        stroke.setStyle(Paint.Style.STROKE);

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
        cards.destroy();
        stopMic();
        main.removeCallbacksAndMessages(null);
        click.release();
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
            Title t = Cards.title(a.getJSONObject(i), kind);
            if (t != null) out.add(t);
        }
        return out;
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
            heroPhoto = cards.photo(t.backdrop, this::photoReady);
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
        heroTitle = Cards.layout(hero.title, heroTitlePaint, 860, 2, 1.0f);
        // A two-line name leaves room for two lines of synopsis (more would run into the rows).
        heroOverview = Cards.layout(hero.overview, overviewPaint, 860, heroTitle.getLineCount() > 1 ? 2 : 3, 1.4f);
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
        Bitmap[] p = cards.photo(t.backdrop, this::photoReady);
        if (p != null && t == hero && heroPhoto == null) {
            heroPhoto = p;
            startFrames();
        }
    }

    private void photoReady(String path, Bitmap[] photo) {
        if (hero != null && path.equals(hero.backdrop)) {
            heroPhoto = photo;
            heroFade.snap(0);
            heroFade.target = 1;
            startFrames();
        }
    }
    /** Decodes and scales to cover w×h (16 bits per pixel: photos have no transparency). */
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
        cards.drawSky(c, heroPhoto, heroPhotoBefore, heroFade.value);
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
    private void drawHero(Canvas c) {
        if (hero == null) return;
        float f = Ui.clamp(heroFade.value);
        if (heroPhotoBefore != null && f < 1) cards.drawPhoto(c, heroPhotoBefore[0], 1 - f);
        if (heroPhoto != null) cards.drawPhoto(c, heroPhoto[0], f);
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
    private void drawSearchBox(Canvas c) {
        boolean on = focusRow == -1;
        float f = on ? Ui.clamp(grow.value) : 0;
        r.set(1920 - M - 560, 62, 1920 - M, 130);
        scale(r, 1 + 0.05f * f);
        if (on) cards.pillShadow(c, r, r.height() / 2, f);
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
            cards.drawPoster(c, row.items.get(i), x, y, POSTER_W, POSTER_H, 0, row.badge);
        }
        if (focusedIndex >= 0 && focusedIndex < n) {
            cards.drawPoster(c, row.items.get(focusedIndex), x0 + focusedIndex * STEP, y, POSTER_W, POSTER_H, grow.value, row.badge);
        }
    }

    private void placeholder(Canvas c, float x, float y) {
        r.set(x, y, x + POSTER_W, y + POSTER_H);
        fill.setColor(GLASS);
        c.drawRoundRect(r, 18, 18, fill);
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
                cards.pillShadow(c, r, r.height() / 2, f);
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
            c.drawText(listening ? Ui.tr("Ouvindo… solte OK quando terminar", "Listening… let go of OK when done")
                    : Ui.tr("Digite, ou segure OK no microfone e fale", "Type, or hold OK on the microphone and speak"), tx, base, rowNote);
        } else {
            c.drawText(query.toString(), tx, base, queryText);
            tx += queryText.measureText(query.toString()) + 4;
        }
        if ((now / 530) % 2 == 0 && !inResults && !listening) {
            fill.setColor(INK);
            c.drawRect(tx, r.centerY() - 20, tx + 3, r.centerY() + 20, fill);
        }
        drawMic(c, r.right - 46, r.centerY(), now);
        drawKeys(c);
        drawResults(c);
    }

    /** The microphone button: glass; white when focused; red, with a slow pulse, while listening. */
    private void drawMic(Canvas c, float cx, float cy, long now) {
        boolean focus = !inResults && keyFocus == MIC;
        float f = focus ? Ui.clamp(grow.value) : 0, rad = 32 * (1 + 0.05f * f);
        r2.set(cx - rad, cy - rad, cx + rad, cy + rad);
        if (listening) {
            float pulse = (float) (0.5 + 0.5 * Math.sin(now / 260.0));
            fill.setColor(Ui.alpha(0xFFE5484D, 0.25f + 0.2f * pulse));
            c.drawCircle(cx, cy, rad + 8 + 6 * pulse, fill);
        }
        if (focus) cards.pillShadow(c, r2, rad, f);
        fill.setColor(listening ? 0xFFE5484D : focus ? INK : GLASS2);
        c.drawCircle(cx, cy, rad, fill);
        Symbols.draw(getContext(), c, "microphone", cx, cy, 30, listening ? INK : focus ? 0xFF111214 : INK2);
    }

    private void drawKeys(Canvas c) {
        for (int i = 0; i < 39; i++) {
            keyRect(i, r);
            boolean focus = !inResults && i == keyFocus;
            if (focus) {
                float f = Ui.clamp(grow.value);
                scale(r, 1 + 0.05f * f);
                cards.pillShadow(c, r, 16, f);
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
            cards.drawPoster(c, list.get(i), x0 + (i % 4) * 184, y0 + 56 + (i / 4) * 264, 160, 240, 0, false);
        }
        if (inResults && resultFocus < n) {
            int i = resultFocus;
            cards.drawPoster(c, list.get(i), x0 + (i % 4) * 184, y0 + 56 + (i / 4) * 264, 160, 240, grow.value, false);
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
        stopMic();
        searching = false;
        hero = null;
        focusChanged();
        invalidate();
    }

    void startMic() {
        if (getContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ((ExploreActivity) activity).askMicrophone();   // the TV asks once; micAllowed() continues
            return;
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(getContext())) {
            toast(Ui.tr("A busca por voz não está disponível nesta TV.", "Voice search isn't available on this TV."));
            return;
        }
        if (speech == null) {
            speech = android.speech.SpeechRecognizer.createSpeechRecognizer(getContext());
            speech.setRecognitionListener(new Voice());
        } else {
            speech.cancel();   // a new press starts over
        }
        click.open();
        listening = true;
        listen();
        startFrames();
    }

    /** OK let go: stop listening; the result (what was said) comes in onResults and searches. */
    private void releaseMic() {
        if (speech != null && listening) speech.stopListening();
        listening = false;
        invalidate();
    }

    void micAllowed(boolean allowed) {
        if (allowed) toast(Ui.tr("Pronto: segure OK no microfone e fale.", "Ready: hold OK on the microphone and speak."));
        else toast(Ui.tr("Sem permissão para usar o microfone.", "No permission to use the microphone."));
    }

    void stopMic() {
        listening = false;
        if (speech != null) {
            speech.cancel();
            speech.destroy();
            speech = null;
        }
        invalidate();
    }

    private void listen() {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Ui.en ? "en-US" : "pt-BR")
                .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Held: don't stop at a short pause; letting go of OK ends it.
                .putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
                .putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L);
        speech.startListening(i);
    }

    private final class Voice implements android.speech.RecognitionListener {
        @Override public void onPartialResults(android.os.Bundle b) {
            String text = best(b);
            if (text == null || speech == null) return;
            query.setLength(0);
            query.append(text.toLowerCase(Ui.en ? Locale.US : Ui.PT));
            invalidate();
        }

        @Override public void onResults(android.os.Bundle b) {
            String text = best(b);
            listening = false;
            if (text != null) {
                query.setLength(0);
                query.append(text.toLowerCase(Ui.en ? Locale.US : Ui.PT));
                queryChanged();
            }
            invalidate();
        }

        @Override public void onError(int error) {
            listening = false;
            AppLog.w("voice: error " + error);
            if (error == android.speech.SpeechRecognizer.ERROR_NO_MATCH || error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                toast(Ui.tr("Não ouvi nada. Segure OK e fale.", "I didn't hear anything. Hold OK and speak."));
            } else if (error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                toast(Ui.tr("Sem permissão para usar o microfone.", "No permission to use the microphone."));
            } else if (error != android.speech.SpeechRecognizer.ERROR_CLIENT) {
                toast(Ui.tr("A busca por voz falhou. Tente de novo.", "Voice search failed. Try again."));
            }
            invalidate();
        }

        private String best(android.os.Bundle b) {
            java.util.ArrayList<String> list = b == null ? null
                    : b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
            return list == null || list.isEmpty() || list.get(0).trim().isEmpty() ? null : list.get(0).trim();
        }

        @Override public void onReadyForSpeech(android.os.Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int eventType, android.os.Bundle params) {}
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
                    Title t = Cards.title(o, kind);
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
            boolean onMic = searching && !inResults && keyFocus == MIC;
            if (down) {
                if (e.getRepeatCount() == 0) {
                    okDown = true;
                    if (onMic) startMic();   // hold to talk
                }
            } else if (okDown) {
                okDown = false;
                if (onMic) releaseMic(); else ok();
            }
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
        } else if (keyFocus == MIC) {
            if (dy > 0) keyFocus = 9;
        } else if (keyFocus < 36) {
            int col = keyFocus % 10, line = keyFocus / 10;
            if (dx < 0 && col > 0) keyFocus--;
            if (dx > 0) { if (col == 9 || keyFocus == 35) { if (n > 0) { inResults = true; resultFocus = 0; } } else keyFocus++; }
            if (dy < 0) keyFocus = line > 0 ? keyFocus - 10 : MIC;
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

    /** Opens the title's page (details and where to watch it). */
    private void open(Title t) {
        click.open();
        ((ExploreActivity) activity).openDetails(t);
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
