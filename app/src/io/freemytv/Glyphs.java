package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;

import java.util.HashMap;
import java.util.Map;

/**
 * Currency symbols for the exchange rates ($, €, £, ¥): Phosphor's filled icons (MIT), rendered by tools/make_phosphor.py
 * as white PNGs in assets/symbols/cur_*.png. All use the same frame, and only the spare space on the sides is trimmed.
 * Each size is drawn once into an exact-size bitmap and reused (sharp, no resampling).
 */
final class Glyphs {
    private Glyphs() {}

    private static final Map<String, Bitmap> BITMAPS = new HashMap<>();
    private static final Map<Integer, PorterDuffColorFilter> FILTERS = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /** Draws the coin centered at (cx, cy), sized to the text next to it (cap height `cap`), in `color`. */
    static void coin(Context context, Canvas c, String coin, float cx, float cy, float cap, int color) {
        Bitmap b = bitmap(context, coin, frame(cap));
        if (b == null) return;
        PorterDuffColorFilter filter = FILTERS.get(color);
        if (filter == null) {
            if (FILTERS.size() > 256) FILTERS.clear();   // animated colors: one per frame
            filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            FILTERS.put(color, filter);
        }
        PAINT.setColorFilter(filter);
        c.drawBitmap(b, Math.round(cx - b.getWidth() / 2f), Math.round(cy - b.getHeight() / 2f), PAINT);
    }

    /** Width the symbol takes up (for laying out the line). */
    static float width(Context context, String coin, float cap) {
        Bitmap b = bitmap(context, coin, frame(cap));
        return b == null ? 0 : b.getWidth();
    }

    /** Side of the 32-unit frame: the largest symbol (the ETH diamond) ends up ~1.3 × the cap height. */
    private static int frame(float cap) {
        return Math.max(12, Math.round(cap * 1.75f));
    }

    private static Bitmap bitmap(Context context, String coin, int frame) {
        String key = coin + "@" + frame;
        synchronized (BITMAPS) {
            if (BITMAPS.containsKey(key)) return BITMAPS.get(key);
            Bitmap src = null;
            try (java.io.InputStream in = context.getAssets().open("symbols/cur_" + coin.toLowerCase(java.util.Locale.ROOT) + ".png")) {
                src = android.graphics.BitmapFactory.decodeStream(in);
            } catch (java.io.IOException ignored) {
            }
            Bitmap b = null;
            if (src != null) {
                Bitmap full = Bitmap.createBitmap(frame, frame, Bitmap.Config.ARGB_8888);
                float scale = Math.min((float) frame / src.getWidth(), (float) frame / src.getHeight()) * 0.8f;
                float w = src.getWidth() * scale, h = src.getHeight() * scale;
                new Canvas(full).drawBitmap(src, null, new android.graphics.RectF((frame - w) / 2, (frame - h) / 2,
                        (frame + w) / 2, (frame + h) / 2), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
                b = trimSides(full);
            }
            BITMAPS.put(key, b);
            return b;
        }
    }

    /** Trims only the empty columns on the sides (the frame height stays, so all coins center on the same line). */
    private static Bitmap trimSides(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), left = w, right = -1;
        int[] column = new int[h];
        for (int x = 0; x < w; x++) {
            b.getPixels(column, 0, 1, x, 0, 1, h);
            for (int a : column) {
                if ((a >>> 24) > 8) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    break;
                }
            }
        }
        if (right < left) return b;
        return Bitmap.createBitmap(b, left, 0, right - left + 1, h);
    }
}
