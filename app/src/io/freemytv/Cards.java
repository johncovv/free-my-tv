package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the Explore and Details screens share: a title from TMDB, its poster and photo (small, loaded only when drawn,
 * a few dozen kept in memory and the files cached on disk; all freed when the screen closes) and the home screen's
 * look for them: the card focus (lift, soft shadow, sheen, rim), the blurred background and the photo fading in from
 * the right. Everything is in 1920×1080 design pixels.
 */
final class Cards {
    static final class Title {
        int id;
        String kind, title, poster, backdrop, overview = "", meta = "", app, pkg;
        boolean detailed;
        StaticLayout label;   // the name, shown while the poster loads
        String uri;           // Spotify: what to open (spotify:album:…)
        boolean square, round;   // music: square cover; artists: a circle
    }

    static final float POSTER_W = 196, POSTER_H = 294;
    /** Music covers and artist photos are kept at this size (square). */
    static final int SQUARE = 240;
    static final int BG = 0xFF0E0F12, INK = 0xFFF5F5F7, INK2 = 0xA3F5F5F7, INK3 = 0x6BF5F5F7;
    static final int GLASS = 0x14FFFFFF, GLASS2 = 0x24FFFFFF, RIM = 0x1FFFFFFF;
    private static final String IMAGES = "https://image.tmdb.org/t/p/";

    private final Context context;
    private final Runnable redraw;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newFixedThreadPool(3);
    private volatile boolean destroyed;

    // Posters by path (least recently drawn go first) and photos ({photo, 32×18 copy for the blurred background}).
    private final Map<String, Bitmap> posters = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 48; }
    };
    private final Set<String> loading = new HashSet<>(), loadingPhotos = new HashSet<>();
    private final Map<String, Bitmap[]> photos = new LinkedHashMap<String, Bitmap[]>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap[]> e) { return size() > 6; }
    };

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    // The home screen's shadows and focus rim (HomeView: shadowDeep, shadow, rim, sheen).
    private final Paint softShadow = new Paint(Paint.ANTI_ALIAS_FLAG), pillShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG), mask = new Paint();
    private final Paint badge;
    private final TextPaint labelPaint;
    private final Shader skyShade, maskH, maskV;
    private final RectF r = new RectF(), r2 = new RectF();
    private final Path clip = new Path();

    Cards(Context c, Runnable redraw) {
        context = c;
        this.redraw = redraw;
        softShadow.setMaskFilter(new BlurMaskFilter(30, BlurMaskFilter.Blur.NORMAL));
        pillShadow.setMaskFilter(new BlurMaskFilter(26, BlurMaskFilter.Blur.NORMAL));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        badge = Ui.text(Ui.semibold, 15, INK);
        badge.setLetterSpacing(0.06f);
        labelPaint = new TextPaint(Ui.text(Ui.medium, 22, INK2));
        skyShade = new LinearGradient(0, 0, 0, 1080, 0x1A0A0B0E, 0xD90A0B0E, Shader.TileMode.CLAMP);
        maskH = new LinearGradient(670, 0, 1232, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
        maskV = new LinearGradient(0, 380, 0, 700, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
    }

    void destroy() {
        destroyed = true;
        images.shutdownNow();
        main.removeCallbacksAndMessages(null);
        synchronized (posters) { posters.clear(); }
        photos.clear();
    }

    /** A TMDB list or search result as a title (null without a poster). */
    static Title title(JSONObject o, String kind) {
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

    static StaticLayout layout(String text, TextPaint p, int width, int lines, float spacing) {
        return StaticLayout.Builder.obtain(text == null ? "" : text, 0, text == null ? 0 : text.length(), p, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, spacing).setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END).build();
    }

    // MARK: - Images

    /** The poster if it's in memory; otherwise null, and it's loaded (redraws when ready). */
    Bitmap poster(Title t) {
        if (t.poster == null || destroyed) return null;
        final String path = t.poster;
        synchronized (posters) {
            Bitmap b = posters.get(path);
            if (b != null || !loading.add(path)) return b;
        }
        images.execute(() -> {
            Bitmap done = null;
            try {
                int w = t.square ? SQUARE : Math.round(POSTER_W), h = t.square ? SQUARE : Math.round(POSTER_H);
                done = decode(download("w342", path), w, h);
            } catch (Exception e) {
                AppLog.w("poster: " + e.getMessage());
            }
            synchronized (posters) {
                loading.remove(path);
                if (done != null) posters.put(path, done);
            }
            if (done != null) main.post(redraw);
        });
        return null;
    }

    interface PhotoReady { void ready(String path, Bitmap[] photo); }

    /** The photo ({photo, tiny copy}) if it's in memory; otherwise null, and `ready` is called on the main thread. */
    Bitmap[] photo(String path, PhotoReady ready) {
        if (path == null || destroyed) return null;
        Bitmap[] p = photos.get(path);
        if (p != null || !loadingPhotos.add(path)) return p;   // already on its way: one download per photo
        images.execute(() -> {
            Bitmap[] pair = null;
            try {
                Bitmap b = decode(download("w780", path), 1250, 703);
                if (b != null) pair = new Bitmap[] {b, Bitmap.createScaledBitmap(b, 32, 18, true)};
            } catch (Exception e) {
                AppLog.w("photo: " + e.getMessage());
            }
            final Bitmap[] done = pair;
            main.post(() -> {
                loadingPhotos.remove(path);
                if (destroyed || done == null) return;
                photos.put(path, done);
                ready.ready(path, done);
            });
        });
        return null;
    }

    /** The image at TMDB's `size` (or a full URL: Spotify), from the disk cache or downloaded into it. */
    byte[] download(String size, String path) throws IOException {
        boolean url = path.startsWith("https://");
        File dir = new File(context.getCacheDir(), "explore");
        File f = new File(dir, url ? "u" + Integer.toHexString(path.hashCode()) + path.length() : size + path.replace("/", "_"));
        if (f.exists()) return readAll(new FileInputStream(f));
        HttpURLConnection c = (HttpURLConnection) new URL(url ? path : IMAGES + size + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            byte[] data = readAll(c.getInputStream());
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            // Written aside and renamed: a half-written file (the screen closed mid-download) is never served.
            File part = new File(f.getPath() + "." + Thread.currentThread().getId() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(data);
            }
            //noinspection ResultOfMethodCallIgnored
            if (!part.renameTo(f)) part.delete();
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
    static Bitmap decode(byte[] data, int w, int h) {
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

    /** The photo blurred (its 32×18 copy stretched to the screen) and darkened; crossfading from `before`. */
    void drawSky(Canvas c, Bitmap[] now, Bitmap[] before, float fade) {
        r.set(-60, -60, 1980, 1140);
        if (before != null && fade < 1) {
            bitmapPaint.setAlpha(255);
            c.drawBitmap(before[1], null, r, bitmapPaint);
        }
        if (now != null) {
            bitmapPaint.setAlpha(Math.round(255 * (before != null ? Ui.clamp(fade) : 1)));
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

    /** The photo on the right, fading out to the left and at the bottom (masked in a layer). */
    void drawPhoto(Canvas c, Bitmap b, float alpha) {
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

    /**
     * A poster with the home screen's card focus (HomeView.drawLightCard): 5% larger and lifted a little, a soft shadow
     * that deepens, a diagonal sheen and a light rim that fades toward the bottom. `v` is the focus spring's value.
     */
    void drawPoster(Canvas c, Title t, float x, float y, float w, float h, float v, boolean isNew) {
        float f = Ui.clamp(v), s = 1 + 0.05f * v, rad = t.round ? Math.min(w, h) * s / 2 : 18 * (Math.min(w, POSTER_W) / POSTER_W) * s;
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
                c.save();
                c.translate(r.left + 12, r.centerY() - t.label.getHeight() / 2f);
                t.label.draw(c);
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

    /** The home screen's tab focus shadow: soft, below, growing with the focus. */
    void pillShadow(Canvas c, RectF rect, float rad, float focus) {
        r2.set(rect);
        r2.offset(0, 8 * focus);
        pillShadow.setColor(Ui.alpha(0xFF000000, 0.4f * focus));
        c.drawRoundRect(r2, rad, rad, pillShadow);
    }
}
