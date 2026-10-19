package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A title's page, drawn like Explore (1920×1080 design pixels): the photo on the right fading into its own blurred
 * colors; on the left, type and age rating, the name, the tagline, year · length · genres · rating, the synopsis, who
 * directed or created it and the main cast. Below, "Watch on": a button per streaming service it's on in your country
 * (the ones the TV has open their app; the others are shown dimmed), and where to rent or buy it. Last, "More like
 * this" (OK opens that title here; Back comes back).
 */
final class DetailsView extends View {
    private static final float M = 80, SIM_W = Cards.POSTER_W, SIM_H = Cards.POSTER_H, SIM_STEP = 224;
    private static final int INK = Cards.INK, INK2 = Cards.INK2, INK3 = Cards.INK3, GLASS = Cards.GLASS, RIM = Cards.RIM;
    private static final int SERVICES = 0, SIMILAR = 1;

    private final Activity activity;
    private final Recs recs;
    private final Click click;
    private final Cards cards;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean destroyed, okDown, backDown;

    // The page
    private Title title;
    private final List<Title> history = new ArrayList<>();   // titles opened from "More like this", for Back
    private boolean loading = true;
    private String kicker = "", tagline = "", meta = "", direction = "", cast = "", others = "";
    private final List<String[]> services = new ArrayList<>();   // {name, HomeApps key or null, package or null}
    private final List<Title> similar = new ArrayList<>();
    private StaticLayout nameLayout, overviewLayout;
    private Bitmap[] photo, photoBefore;
    private int generation;

    // Focus
    private int row = SERVICES, col, similarCol;
    private final Spring grow = new Spring(0.32f, 0.78f, 0), fade = new Spring(0.3f, 1f, 1), scroll = new Spring(0.34f, 0.9f, 0);
    /** The page scrolls up while "More like this" has the focus (its posters sit below the screen's edge). */
    private final Spring pageY = new Spring(0.36f, 0.92f, 0);
    private static final float SIMILAR_SCROLL = 330;
    private long lastFrame;
    private String toast;
    private long toastAt;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint kickerPaint, taglinePaint, metaPaint, labelPaint, namePaint, sectionPaint, buttonPaint, buttonOn,
            buttonDim, notePaint, toastPaint;
    private final TextPaint titlePaint, overviewPaint;
    private final RectF r = new RectF();

    DetailsView(Activity a, Title t) {
        super(a);
        activity = a;
        recs = Recs.get(a);
        click = new Click(a);
        cards = new Cards(a, this::invalidate);
        setFocusable(true);
        kickerPaint = Ui.text(Ui.semibold, 20, INK3);
        kickerPaint.setLetterSpacing(0.12f);
        taglinePaint = Ui.text(Ui.medium, 26, INK2);
        metaPaint = Ui.text(Ui.regular, 24, INK2);
        labelPaint = Ui.text(Ui.semibold, 22, INK3);
        namePaint = Ui.text(Ui.regular, 22, INK2);
        sectionPaint = Ui.text(Ui.semibold, 26, INK);
        buttonPaint = Ui.text(Ui.semibold, 24, INK);
        buttonOn = Ui.text(Ui.semibold, 24, 0xFF111214);
        buttonDim = Ui.text(Ui.medium, 22, INK3);
        notePaint = Ui.text(Ui.regular, 22, INK3);
        toastPaint = Ui.text(Ui.semibold, 24, 0xFF111214);
        titlePaint = new TextPaint(Ui.text(Ui.displaySemibold, 64, INK));
        overviewPaint = new TextPaint(Ui.text(Ui.regular, 24, INK2));
        stroke.setStyle(Paint.Style.STROKE);
        show(t);
    }

    void destroy() {
        destroyed = true;
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cards.destroy();
        click.release();
    }

    // MARK: - Data

    private void show(Title t) {
        title = t;
        final int gen = ++generation;
        loading = true;
        kicker = t.kind.equals("movie") ? Ui.tr("Filme", "Movie") : Ui.tr("Série", "Series");
        tagline = meta = direction = cast = others = "";
        services.clear();
        similar.clear();
        row = SERVICES;
        col = similarCol = 0;
        scroll.snap(0);
        pageY.snap(0);
        layoutText("");
        photoBefore = photo;
        photo = cards.photo(t.backdrop, this::photoReady);
        fade.snap(photo != null ? 1 : 0);
        startFrames();
        if (!Recs.hasKey(getContext())) {
            loading = false;
            toast(Ui.tr("Sem chave do TMDB: veja o README (config.properties).", "No TMDB key: see the README (config.properties)."));
            return;
        }
        io.execute(() -> {
            try {
                JSONObject o = Recs.tmdb("/" + t.kind + "/" + t.id + "?language=" + Recs.lang()
                        + "&append_to_response=watch/providers,credits,recommendations,"
                        + (t.kind.equals("movie") ? "release_dates" : "content_ratings"));
                main.post(() -> { if (gen == generation && !destroyed) fill(t, o); });
            } catch (Exception e) {
                AppLog.w("details: " + e.getMessage());
                main.post(() -> {
                    if (gen != generation || destroyed) return;
                    loading = false;
                    toast(Ui.tr("Não consegui carregar os detalhes.", "Couldn't load the details."));
                });
            }
        });
    }

    private void fill(Title t, JSONObject o) {
        boolean movie = t.kind.equals("movie");
        // Type, age rating and (shows) seasons.
        List<String> k = new ArrayList<>();
        k.add(movie ? Ui.tr("Filme", "Movie") : Ui.tr("Série", "Series"));
        String rating = movie ? movieRating(o.optJSONObject("release_dates")) : showRating(o.optJSONObject("content_ratings"));
        if (!rating.isEmpty()) k.add(rating);
        if (!movie) {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) k.add(seasons == 1 ? Ui.tr("1 temporada", "1 season") : seasons + Ui.tr(" temporadas", " seasons"));
        }
        kicker = String.join("  ·  ", k);
        tagline = o.optString("tagline", "");
        // Year · length · genres · rating (votes).
        List<String> m = new ArrayList<>();
        String date = o.optString(movie ? "release_date" : "first_air_date", "");
        if (date.length() >= 4) {
            String last = movie ? "" : o.optString("last_air_date", "");
            boolean ended = !movie && "Ended".equals(o.optString("status"));
            m.add(ended && last.length() >= 4 && !last.startsWith(date.substring(0, 4))
                    ? date.substring(0, 4) + "–" + last.substring(0, 4) : date.substring(0, 4));
        }
        int minutes = movie ? o.optInt("runtime") : firstInt(o.optJSONArray("episode_run_time"));
        if (minutes > 0) m.add((movie ? "" : Ui.tr("episódios de ", "episodes of ")) + duration(minutes));
        JSONArray genres = o.optJSONArray("genres");
        List<String> g = new ArrayList<>();
        for (int i = 0; genres != null && i < genres.length() && i < 3; i++) g.add(Recs.genreName(genres.optJSONObject(i).optString("name")));
        if (!g.isEmpty()) m.add(String.join(", ", g));
        double vote = o.optDouble("vote_average", 0);
        int votes = o.optInt("vote_count");
        if (vote > 0 && votes > 0) {
            m.add("★ " + String.format(Ui.en ? Locale.US : Ui.PT, "%.1f", vote) + "  ("
                    + String.format(Ui.en ? Locale.US : Ui.PT, "%,d", votes) + Ui.tr(" votos)", " votes)"));
        }
        meta = String.join("  ·  ", m);
        // Who made it and who's in it.
        JSONObject credits = o.optJSONObject("credits");
        List<String> who = new ArrayList<>();
        if (movie) {
            JSONArray crew = credits == null ? null : credits.optJSONArray("crew");
            for (int i = 0; crew != null && i < crew.length(); i++) {
                JSONObject c = crew.optJSONObject(i);
                if ("Director".equals(c.optString("job")) && !who.contains(c.optString("name"))) who.add(c.optString("name"));
            }
        } else {
            JSONArray created = o.optJSONArray("created_by");
            for (int i = 0; created != null && i < created.length(); i++) who.add(created.optJSONObject(i).optString("name"));
        }
        direction = fit(String.join(", ", who.subList(0, Math.min(3, who.size()))));
        List<String> actors = new ArrayList<>();
        JSONArray castList = credits == null ? null : credits.optJSONArray("cast");
        for (int i = 0; castList != null && i < castList.length() && actors.size() < 5; i++) actors.add(castList.optJSONObject(i).optString("name"));
        cast = fit(String.join(", ", actors));
        t.overview = o.optString("overview", "");
        if (t.backdrop == null) {
            String b = o.optString("backdrop_path", "");
            if (!b.isEmpty() && !"null".equals(b)) {
                t.backdrop = b;
                photo = cards.photo(b, this::photoReady);
            }
        }
        // Where to watch: subscription services first; then a line with where to rent or buy.
        List<String> rentBuy = new ArrayList<>();
        boolean afterSeparator = false;
        for (String[] s : recs.servicesOf(o.optJSONObject("watch/providers"))) {
            if (s[0] == null) { afterSeparator = true; continue; }
            if (afterSeparator) rentBuy.add(s[0]); else services.add(s);
        }
        // The ones the TV has first.
        List<String[]> sorted = new ArrayList<>();
        for (String[] s : services) if (s[2] != null) sorted.add(s);
        for (String[] s : services) if (s[2] == null) sorted.add(s);
        services.clear();
        services.addAll(sorted);
        others = rentBuy.isEmpty() ? "" : Ui.tr("Para alugar ou comprar: ", "To rent or buy: ") + String.join(", ", rentBuy.subList(0, Math.min(5, rentBuy.size())));
        JSONObject recsJson = o.optJSONObject("recommendations");
        JSONArray list = recsJson == null ? null : recsJson.optJSONArray("results");
        for (int i = 0; list != null && i < list.length() && similar.size() < 14; i++) {
            JSONObject x = list.optJSONObject(i);
            String kind = x.optString("media_type", t.kind);
            Title s = Cards.title(x, kind.equals("tv") ? "tv" : "movie");
            if (s != null) similar.add(s);
        }
        loading = false;
        row = services.isEmpty() && !similar.isEmpty() ? SIMILAR : SERVICES;
        col = 0;
        layoutText(t.overview);
        grow.snap(0);
        grow.target = 1;
        startFrames();
    }

    private void layoutText(String overview) {
        nameLayout = Cards.layout(title.title, titlePaint, 960, 2, 1.0f);
        overviewLayout = Cards.layout(overview, overviewPaint, 900, nameLayout.getLineCount() > 1 ? 4 : 5, 1.45f);
    }

    private void photoReady(String path, Bitmap[] p) {
        if (path.equals(title.backdrop)) {
            photo = p;
            fade.snap(0);
            fade.target = 1;
            startFrames();
        }
    }

    /** The Brazilian (or your region's) age rating: "Livre", "14 anos"… */
    private static String movieRating(JSONObject releaseDates) {
        JSONArray results = releaseDates == null ? null : releaseDates.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject c = results.optJSONObject(i);
            if (!region().equals(c.optString("iso_3166_1"))) continue;
            JSONArray dates = c.optJSONArray("release_dates");
            for (int j = 0; dates != null && j < dates.length(); j++) {
                String cert = dates.optJSONObject(j).optString("certification", "");
                if (!cert.isEmpty()) return age(cert);
            }
        }
        return "";
    }

    private static String showRating(JSONObject contentRatings) {
        JSONArray results = contentRatings == null ? null : contentRatings.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject c = results.optJSONObject(i);
            if (region().equals(c.optString("iso_3166_1")) && !c.optString("rating").isEmpty()) return age(c.optString("rating"));
        }
        return "";
    }

    private static String region() {
        return Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);
    }

    private static String age(String cert) {
        if (cert.equalsIgnoreCase("L")) return Ui.tr("Livre", "All ages");
        return cert.matches("\\d+") ? cert + Ui.tr(" anos", "+") : cert;
    }

    private static int firstInt(JSONArray a) {
        return a == null || a.length() == 0 ? 0 : a.optInt(0);
    }

    private static String duration(int minutes) {
        if (minutes < 60) return minutes + " min";
        return Ui.en ? (minutes / 60) + "h " + (minutes % 60) + "m" : (minutes / 60) + " h " + (minutes % 60) + " min";
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
        pageY.target = row == SIMILAR ? SIMILAR_SCROLL : 0;
        boolean moving = grow.step(dt) | fade.step(dt) | scroll.step(dt) | pageY.step(dt);
        float k = getWidth() / 1920f;
        c.drawColor(Cards.BG);
        c.save();
        c.scale(k, k);
        cards.drawSky(c, photo, photoBefore, fade.value);
        c.translate(0, -pageY.value);
        if (photoBefore != null && fade.value < 1) cards.drawPhoto(c, photoBefore[0], 1 - Ui.clamp(fade.value));
        if (photo != null) cards.drawPhoto(c, photo[0], Ui.clamp(fade.value));
        drawText(c);
        drawServices(c);
        drawSimilar(c);
        c.translate(0, pageY.value);
        drawToast(c, now);
        c.restore();
        moving |= !scroll.settled() || !pageY.settled();
        if (moving || (toast != null && now - toastAt < 2400) || loading) postInvalidateOnAnimation();
    }

    private void drawText(Canvas c) {
        float y = 96;
        c.drawText(kicker.toUpperCase(Ui.en ? Locale.US : Ui.PT), M, y + Ui.cap(kickerPaint), kickerPaint);
        y += 44;
        c.save();
        c.translate(M, y);
        nameLayout.draw(c);
        c.restore();
        y += nameLayout.getHeight() + 14;
        if (!tagline.isEmpty()) {
            c.drawText(tagline, M, y + Ui.cap(taglinePaint), taglinePaint);
            y += 46;
        }
        if (!meta.isEmpty()) {
            c.drawText(meta, M, y + Ui.cap(metaPaint), metaPaint);
            y += 48;
        } else if (loading) {
            stroke.setColor(INK3);
            stroke.setStrokeWidth(3);
            Ui.spinner(c, M + 14, y + 12, 12, SystemClock.uptimeMillis() / 1000f, stroke);
            y += 48;
        }
        c.save();
        c.translate(M, y);
        overviewLayout.draw(c);
        c.restore();
        y += overviewLayout.getHeight() + 22;
        y = credit(c, Ui.tr(title.kind.equals("movie") ? "Direção" : "Criação", title.kind.equals("movie") ? "Director" : "Created by"), direction, y);
        credit(c, Ui.tr("Elenco", "Cast"), cast, y);
    }

    private float credit(Canvas c, String label, String names, float y) {
        if (names.isEmpty()) return y;
        c.drawText(label, M, y + Ui.cap(labelPaint), labelPaint);
        c.drawText(names, M + 150, y + Ui.cap(namePaint), namePaint);
        return y + 38;
    }

    /** Cut to fit the credits column (once, when the details arrive). */
    private String fit(String names) {
        return android.text.TextUtils.ellipsize(names, new TextPaint(namePaint), 900 - 150,
                android.text.TextUtils.TruncateAt.END).toString();
    }

    private void drawServices(Canvas c) {
        float y = 690;
        c.drawText(Ui.tr("Assistir em", "Watch on"), M, y + Ui.cap(sectionPaint), sectionPaint);
        y += 48;
        if (services.isEmpty()) {
            String text = loading ? "" : Ui.tr("Não está em nenhum streaming por assinatura no Brasil.",
                    "Not on any subscription service in your country.");
            c.drawText(text, M, y + 34, notePaint);
            if (!others.isEmpty()) c.drawText(others, M, y + 74, notePaint);
            return;
        }
        float x = M;
        for (int i = 0; i < services.size(); i++) {
            String[] s = services.get(i);
            boolean has = s[2] != null, focus = row == SERVICES && i == col;
            String name = s[0], note = has ? "" : Ui.tr("  ·  não instalado", "  ·  not installed");
            Paint p = focus ? buttonOn : has ? buttonPaint : buttonDim;
            float w = p.measureText(name) + (has ? 0 : buttonDim.measureText(note)) + 64;
            r.set(x, y, x + w, y + 72);
            if (focus) {
                float f = Ui.clamp(grow.value);
                scale(r, 1 + 0.05f * f);
                cards.pillShadow(c, r, r.height() / 2, f);
                fill.setColor(INK);
            } else {
                fill.setColor(has ? Cards.GLASS2 : GLASS);
            }
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            if (!focus) {
                stroke.setColor(RIM);
                stroke.setStrokeWidth(1.5f);
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, stroke);
            }
            float tx = r.left + 32, base = r.centerY() + Ui.halfCap(p);
            c.drawText(name, tx, base, p);
            if (!has) {
                int color = buttonDim.getColor();
                if (focus) buttonDim.setColor(0x99111214);
                c.drawText(note, tx + p.measureText(name), base, buttonDim);
                buttonDim.setColor(color);
            }
            x += w + 18;
        }
        if (!others.isEmpty()) c.drawText(others, x + 12, y + 36 + Ui.halfCap(notePaint), notePaint);
    }

    private void drawSimilar(Canvas c) {
        if (similar.isEmpty()) return;
        float y = 860;
        c.drawText(Ui.tr("Parecidos", "More like this"), M, y + Ui.cap(sectionPaint), sectionPaint);
        y += 46;
        int focused = row == SIMILAR ? col : -1, n = similar.size();
        int at = focused >= 0 ? focused : similarCol;
        scroll.target = Math.max(0, Math.min((at - 2) * SIM_STEP, n * SIM_STEP - (1920 - 2 * M)));
        float x0 = M - scroll.value;
        for (int i = 0; i < n; i++) {
            float x = x0 + i * SIM_STEP;
            if (i == focused || x + SIM_W < -40 || x > 1960) continue;
            cards.drawPoster(c, similar.get(i), x, y, SIM_W, SIM_H, 0, false);
        }
        if (focused >= 0) cards.drawPoster(c, similar.get(focused), x0 + focused * SIM_STEP, y, SIM_W, SIM_H, grow.value, false);
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

    private static void scale(RectF rect, float s) {
        float w = rect.width() * s / 2, h = rect.height() * s / 2, cx = rect.centerX(), cy = rect.centerY();
        rect.set(cx - w, cy - h, cx + w, cy + h);
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int code = e.getKeyCode();
        // OK and Back act on release, and only if pressed here (a press on the previous screen does nothing).
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
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (dx == 0 && dy == 0) return false;
        if (down) move(dx, dy);
        return true;
    }

    private void move(int dx, int dy) {
        int beforeRow = row, beforeCol = col;
        if (dy > 0 && row == SERVICES && !similar.isEmpty()) {
            row = SIMILAR;
            col = similarCol;
        } else if (dy < 0 && row == SIMILAR && !services.isEmpty()) {
            similarCol = col;
            row = SERVICES;
            col = 0;
        } else if (dx != 0) {
            int n = row == SERVICES ? services.size() : similar.size();
            col = Math.max(0, Math.min(n - 1, col + dx));
        }
        if (beforeRow != row || beforeCol != col) {
            click.move();
            grow.snap(0);
            grow.target = 1;
            startFrames();
        }
    }

    private void ok() {
        if (row == SERVICES) {
            if (col >= services.size()) return;
            String[] s = services.get(col);
            if (s[2] == null) {
                toast(Ui.tr(s[0] + " não está instalado na TV.", s[0] + " isn't installed on the TV."));
                return;
            }
            Intent intent = HomeApps.launchIntent(activity.getPackageManager(), s[2]);
            if (intent == null) {
                toast(Ui.tr(s[0] + " não está instalado na TV.", s[0] + " isn't installed on the TV."));
                return;
            }
            click.open();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                activity.startActivity(intent);
            } catch (RuntimeException e) {
                toast(Ui.tr("Não deu para abrir o app.", "Couldn't open the app."));
            }
        } else if (col < similar.size()) {
            click.open();
            history.add(title);
            show(similar.get(col));
        }
    }

    private void back() {
        if (!history.isEmpty()) {
            show(history.remove(history.size() - 1));
            return;
        }
        activity.finish();
    }
}
