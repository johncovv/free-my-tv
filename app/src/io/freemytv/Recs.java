package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, poster, meta = "", app, pkg;
        boolean detailed;
        boolean mine;           // from "Your Taste" (taste_titles and taste_genres), not the trending list
        String uri;             // music: the Spotify item to open (spotify:album:…)
    }

    interface Listener { void onRecs(); }

    /** The "Your Taste" tab's kind: like the titles in taste_titles, in the genres of taste_genres. */
    static final String MINE = "mine";
    /** The "Music" tab: from your Spotify account (recently played, and recommended: your artists' new releases and
     *  the albums of what you've been playing). Square covers, shown whole on the cards (see compose). */
    static final String MUSIC = "music";
    private static final String SPOTIFY_PKG = "com.spotify.tv.android";
    private static final long MUSIC_EVERY = 3600_000L;
    private volatile List<Pick> musicRecent = new ArrayList<>(), musicNew = new ArrayList<>();
    private volatile long musicAt;
    private volatile boolean fetchingMusic;
    /** Bump when the way "Your Taste" is put together changes (the list is fetched again). */
    private static final int TASTE_VERSION = 2;

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/original";
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 4;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    private static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // this hour's, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp;
    private volatile boolean listEnglish;   // the saved list came in English (titles, genres and texts)

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listEnglish = cache.getBoolean("english", false);
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** A taste in config.properties (taste_titles or taste_genres): the "Your Taste" tab. */
    static boolean tasteSet() {
        return !Config.TASTE_TITLES.trim().isEmpty() || !Config.TASTE_GENRES.trim().isEmpty();
    }

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        return (Ui.en ? PERIOD_LABEL_EN : PERIOD_LABEL)[period(System.currentTimeMillis())];
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's 3, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        return at(kind, System.currentTimeMillis() / 3600_000L);
    }

    /** Music's 3 for an hour: one you played recently (the big card), then two recommended; rotating hourly. */
    private List<Pick> musicAt(long hour) {
        List<Pick> recent = musicRecent, rec = musicNew, out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (!recent.isEmpty()) {
            Pick p = recent.get((int) (hour % recent.size()));
            if (seen.add(p.uri)) out.add(p);
        }
        List<Pick> rest = rec.isEmpty() ? recent : rec;
        for (int i = 0; i < rest.size() && out.size() < 3; i++) {
            Pick p = rest.get((int) ((hour * 2 + i) % rest.size()));
            if (seen.add(p.uri)) out.add(p);
        }
        return out;
    }

    /** The 3 for a given hour (hours since 1970): the next hour's are fetched ahead, so the switch is instant. */
    private List<Pick> at(String kind, long hour) {
        if (MUSIC.equals(kind)) return musicAt(hour);
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        boolean mine = MINE.equals(kind);
        for (Pick p : picks) {
            if (p.mine != mine || (!mine && kind != null && !kind.equals(p.kind))) continue;
            if (!p.detailed || p.pkg != null) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return list;
        // Three per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < 3; i++) {
            Pick p = list.get((int) ((hour * 3 + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        refreshMusic();
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        // A new key, country or taste refetches.
        long stamp = (Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode()) * 31L
                + (TASTE_VERSION + "|" + Config.TASTE_TITLES + "|" + Config.TASTE_GENRES).hashCode();
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || listEnglish != Ui.en;
        boolean missing = false;
        for (Pick pick : wantedSoon()) missing |= !pick.detailed || (pick.pkg != null && !hasImage(pick));
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wantedSoon()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: "Your Taste" picks with an app on the TV and an image already on disk (this hour's first), never two
     * from the same franchise. Without any yet (no taste set, or still downloading), the trending ones as before.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : now(MINE)) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        for (Pick p : picks) {
            if (p.mine && p.detailed && p.pkg != null && hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        }
        if (!out.isEmpty()) return out;
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every "Your Taste" pick (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        // "Your Taste" first (what the screensaver shows); the rest only when there is no taste list.
        boolean anyMine = false;
        for (Pick p : picks) anyMine |= p.mine;
        for (Pick p : picks) if ((p.mine || !anyMine) && (!p.detailed || (p.pkg != null && !hasImage(p)))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, MINE, "movie", "tv", MUSIC}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** This hour's picks and the next hour's: details and image on disk ahead of the switch. */
    private List<Pick> wantedSoon() {
        List<Pick> out = wanted();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : out) seen.add(key(p));
        long next = System.currentTimeMillis() / 3600_000L + 1;
        for (String kind : new String[] {null, MINE, "movie", "tv", MUSIC}) {
            for (Pick p : at(kind, next)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Music, from Spotify, at most hourly (only with a Spotify login in config.properties). */
    private void refreshMusic() {
        long now = System.currentTimeMillis();
        if (!Spotify.configured() || fetchingMusic || (now - musicAt < MUSIC_EVERY && now >= musicAt && !musicRecent.isEmpty())) return;
        // Only the home screen's process: Spotify swaps the login token on each refresh, so one process uses it.
        if (!android.app.Application.getProcessName().endsWith(":home")) return;
        fetchingMusic = true;
        io.execute(() -> {
            try {
                Spotify spotify = Spotify.get(context);
                List<Pick> recent = new ArrayList<>(), rec = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                JSONArray played = spotify.get("/me/player/recently-played?limit=50").optJSONArray("items");
                for (int i = 0; played != null && i < played.length() && recent.size() < 12; i++) {
                    JSONObject album = played.optJSONObject(i).optJSONObject("track").optJSONObject("album");
                    Pick p = musicPick(album, Ui.tr("Tocado recentemente", "Recently played"));
                    if (p != null && seen.add(p.uri)) recent.add(p);
                }
                // Recommended: your top artists' releases of the past year, alternating with the albums of your top
                // tracks of the last weeks (Spotify's own recommendations aren't open to apps since 2024).
                List<Object[]> releases = new ArrayList<>();
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, -365);
                String since = String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
                JSONArray artists = spotify.get("/me/top/artists?time_range=medium_term&limit=8").optJSONArray("items");
                for (int i = 0; artists != null && i < artists.length(); i++) {
                    JSONArray albums = spotify.get("/artists/" + artists.optJSONObject(i).optString("id")
                            + "/albums?include_groups=album,single&limit=5").optJSONArray("items");
                    for (int j = 0; albums != null && j < albums.length(); j++) {
                        JSONObject o = albums.optJSONObject(j);
                        if (o.optString("release_date", "").compareTo(since) >= 0) {
                            releases.add(new Object[] {o.optString("release_date"), musicPick(o, Ui.tr("Lançamento", "New release"))});
                        }
                    }
                }
                java.util.Collections.sort(releases, (x, y) -> ((String) y[0]).compareTo((String) x[0]));
                List<Pick> fresh = new ArrayList<>(), top = new ArrayList<>();
                for (Object[] r : releases) if (r[1] != null && seen.add(((Pick) r[1]).uri)) fresh.add((Pick) r[1]);
                JSONArray tracks = spotify.get("/me/top/tracks?time_range=short_term&limit=30").optJSONArray("items");
                for (int i = 0; tracks != null && i < tracks.length(); i++) {
                    Pick p = musicPick(tracks.optJSONObject(i).optJSONObject("album"), Ui.tr("Você tem ouvido", "On repeat"));
                    if (p != null && seen.add(p.uri)) top.add(p);
                }
                for (int i = 0; i < Math.max(fresh.size(), top.size()) && rec.size() < 16; i++) {
                    if (i < fresh.size()) rec.add(fresh.get(i));
                    if (i < top.size() && rec.size() < 16) rec.add(top.get(i));
                }
                musicRecent = recent;
                musicNew = rec;
                musicAt = System.currentTimeMillis();
                notifyListeners();
            } catch (Exception e) {
                AppLog.w("music: " + e.getMessage());
                musicAt = System.currentTimeMillis() - MUSIC_EVERY + 10 * 60_000L;   // try again in 10 min
            } finally {
                fetchingMusic = false;
            }
        });
    }

    /** An album as a music card (the artists below it); the largest cover (640 px) for the card. */
    private static Pick musicPick(JSONObject album, String label) {
        if (album == null) return null;
        JSONArray images = album.optJSONArray("images");
        if (images == null || images.length() == 0) return null;
        Pick p = new Pick();
        p.kind = MUSIC;
        p.uri = album.optString("uri");
        p.id = p.uri.hashCode();
        p.title = album.optString("name");
        p.backdrop = images.optJSONObject(0).optString("url");
        p.poster = p.backdrop;
        JSONArray a = album.optJSONArray("artists");
        List<String> names = new ArrayList<>();
        for (int i = 0; a != null && i < a.length() && i < 2; i++) names.add(a.optJSONObject(i).optString("name"));
        p.meta = String.join(", ", names);   // just the artists under the card (no "Recently played" label)
        p.app = "spotify";
        p.pkg = SPOTIFY_PKG;
        p.detailed = true;
        return p;
    }

    /**
     * A square cover made into a 16:9 picture for the home cards: the cover blurred and darkened behind, the cover
     * itself in the middle (sized so both the wide card and the tall ones show it whole).
     */
    private static Bitmap compose(Bitmap cover) {
        Bitmap out = Bitmap.createBitmap(Backdrop.W, Backdrop.H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        Bitmap tiny = Bitmap.createScaledBitmap(cover, 24, 24, true);
        c.drawBitmap(tiny, null, new RectF(-200, -620, Backdrop.W + 200, Backdrop.H + 620), p);
        tiny.recycle();
        c.drawColor(0x8C000000);
        float size = 720, left = (Backdrop.W - size) / 2, top = (Backdrop.H - size) / 2;
        RectF r = new RectF(left, top, left + size, top + size);
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x66000000);
        shadow.setMaskFilter(new android.graphics.BlurMaskFilter(40, android.graphics.BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(new RectF(r.left, r.top + 24, r.right, r.bottom + 24), 20, 20, shadow);
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(r, 20, 20, android.graphics.Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawBitmap(cover, null, r, p);
        c.restore();
        cover.recycle();
        return out;
    }

    /** Ready to show: details in, and its image built in memory (or being built: then false, and Recs notifies). */
    boolean ready(Pick p) {
        return p.detailed && backdrop(p) != null;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException("none of the TV's streaming services are on TMDB");
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8");
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, hotMovies, hotShows, 24);
        alternate(list, seen, newMovies, newShows, 24);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        // 3. "Your Taste": its own list, after the trending one (a failure here doesn't lose the rest).
        if (tasteSet()) try {
            list.addAll(fetchTaste(common, seen));
        } catch (Exception e) {
            AppLog.w("taste: " + e.getMessage());
        }
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listEnglish = Ui.en;
    }

    /**
     * "Your Taste": what TMDB recommends for each title in taste_titles (only what is on the TV's services), alternating
     * with well-rated movies and shows in the taste_genres, up to 24. Titles already in the trending list are skipped.
     */
    private List<Pick> fetchTaste(String common, java.util.Set<String> seen) throws Exception {
        List<Pick> liked = new ArrayList<>(), movies = new ArrayList<>(), shows = new ArrayList<>();
        java.util.Set<String> seeds = new java.util.HashSet<>();
        for (String title : Config.TASTE_TITLES.split(",")) {
            title = title.trim();
            if (title.isEmpty()) continue;
            JSONArray found = get("/search/multi?language=" + lang() + "&include_adult=false&query="
                    + URLEncoder.encode(title, "UTF-8")).getJSONArray("results");
            for (int i = 0; i < found.length(); i++) {
                JSONObject o = found.getJSONObject(i);
                String kind = o.optString("media_type");
                if (!kind.equals("movie") && !kind.equals("tv")) continue;
                seeds.add(kind + ":" + o.optInt("id"));
                JSONArray recs = get("/" + kind + "/" + o.optInt("id") + "/recommendations?language=" + lang())
                        .getJSONArray("results");
                List<Pick> candidates = new ArrayList<>();
                for (int k = 0; k < recs.length() && k < 20; k++) add(candidates, recs.getJSONObject(k), kind);
                for (Pick pick : candidates) {
                    if (seeds.contains(key(pick)) || seen.contains(key(pick))) continue;
                    try {
                        fetchDetails(pick);
                    } catch (Exception e) {
                        continue;
                    }
                    if (pick.pkg != null) liked.add(pick);
                }
                break;
            }
        }
        String movieGenres = genreIds(false), showGenres = genreIds(true);
        if (!movieGenres.isEmpty()) {
            // Well known and well rated; animation, family, comedy, horror and music only if asked for.
            JSONArray r = get("/discover/movie?vote_average.gte=7&vote_count.gte=1500&with_genres="
                    + URLEncoder.encode(movieGenres, "UTF-8") + "&without_genres=" + without(movieGenres, "16,10751,35,27,10402")
                    + common).getJSONArray("results");
            for (int i = 0; i < r.length(); i++) add(movies, r.getJSONObject(i), "movie");
        }
        if (!showGenres.isEmpty()) {
            // TMDB's show genres are broad ("Sci-Fi & Fantasy" also holds horror; "Action & Adventure" police
            // procedurals): mystery and crime are left out unless asked for, along with animation, kids, comedy and talk.
            JSONArray r = get("/discover/tv?vote_average.gte=7.5&vote_count.gte=500&with_genres="
                    + URLEncoder.encode(showGenres, "UTF-8") + "&without_genres="
                    + without(showGenres, "16,10762,10751,35,9648,10764,10767,80") + common).getJSONArray("results");
            for (int i = 0; i < r.length(); i++) add(shows, r.getJSONObject(i), "tv");
        }
        List<Pick> byGenre = new ArrayList<>(), out = new ArrayList<>();
        alternate(byGenre, new java.util.HashSet<>(), movies, shows, 48);
        java.util.Set<String> taken = new java.util.HashSet<>(seen);
        taken.addAll(seeds);
        // Two like the titles you love for each one from the genres.
        for (int i = 0, j = 0; out.size() < 24 && (i < liked.size() || j < byGenre.size()); ) {
            for (int n = 0; n < 2 && i < liked.size(); i++) if (taken.add(key(liked.get(i)))) { out.add(liked.get(i)); n++; }
            for (int n = 0; n < 1 && j < byGenre.size(); j++) if (taken.add(key(byGenre.get(j)))) { out.add(byGenre.get(j)); n++; }
        }
        while (out.size() > 24) out.remove(out.size() - 1);
        for (Pick p : out) p.mine = true;
        return out;
    }

    /** The genres to leave out, minus the ones you asked for ("16,35" without "16" → "16" dropped). */
    private static String without(String asked, String exclude) throws java.io.UnsupportedEncodingException {
        java.util.Set<String> mine = new java.util.HashSet<>(java.util.Arrays.asList(asked.split("\\|")));
        StringBuilder out = new StringBuilder();
        for (String id : exclude.split(",")) if (!mine.contains(id)) out.append(out.length() == 0 ? "" : ",").append(id);
        return URLEncoder.encode(out.toString(), "UTF-8");
    }

    /**
     * taste_genres ("ficção científica, ação, guerra") as TMDB genre ids joined with "|" (any of them). Shows have
     * their own, broader genres ("Sci-Fi & Fantasy"); a genre with no show equivalent is left out for shows.
     */
    private static String genreIds(boolean shows) {
        StringBuilder out = new StringBuilder();
        java.util.Set<Integer> added = new java.util.HashSet<>();
        for (String name : Config.TASTE_GENRES.split(",")) {
            String n = java.text.Normalizer.normalize(name.trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "");
            for (String[] g : TASTE_GENRES) {
                if (!g[0].equals(n)) continue;
                int id = Integer.parseInt(shows ? g[2] : g[1]);
                if (id > 0 && added.add(id)) out.append(out.length() == 0 ? "" : "|").append(id);
            }
        }
        return out.toString();
    }

    /** Genre name (lowercase, no accents) → TMDB movie genre id, show genre id (0 = none for shows). */
    private static final String[][] TASTE_GENRES = {
        {"acao", "28", "10759"}, {"action", "28", "10759"}, {"aventura", "12", "10759"}, {"adventure", "12", "10759"},
        {"animacao", "16", "16"}, {"animation", "16", "16"}, {"comedia", "35", "35"}, {"comedy", "35", "35"},
        {"crime", "80", "80"}, {"documentario", "99", "99"}, {"documentary", "99", "99"}, {"drama", "18", "18"},
        {"familia", "10751", "10751"}, {"family", "10751", "10751"}, {"fantasia", "14", "10765"}, {"fantasy", "14", "10765"},
        {"ficcao cientifica", "878", "10765"}, {"science fiction", "878", "10765"}, {"sci-fi", "878", "10765"},
        {"guerra", "10752", "10768"}, {"war", "10752", "10768"}, {"historia", "36", "0"}, {"history", "36", "0"},
        {"terror", "27", "0"}, {"horror", "27", "0"}, {"misterio", "9648", "9648"}, {"mystery", "9648", "9648"},
        {"romance", "10749", "0"}, {"suspense", "53", "0"}, {"thriller", "53", "0"}, {"faroeste", "37", "37"},
        {"western", "37", "37"}, {"musica", "10402", "0"}, {"music", "10402", "0"},
    };

    /** "Your Taste" picks ready to show (details in, on an app the TV has), for the Explore screen. */
    List<Pick> taste() {
        List<Pick> out = new ArrayList<>();
        for (Pick p : picks) if (p.mine && p.detailed && p.pkg != null) out.add(p);
        return out;
    }

    /**
     * The discover filter for the TV's streaming services in your region ("&language=…&with_watch_providers=8|119…"),
     * looked up once per process. Blocking.
     */
    String servicesFilter() throws Exception {
        if (filter != null) return filter;
        StringBuilder ids = new StringBuilder();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null && installed(s[2])) ids.append(ids.length() == 0 ? "" : "|").append(o.getInt("provider_id"));
        }
        if (ids.length() == 0) throw new IOException("none of the TV's streaming services are on TMDB");
        filter = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8");
        return filter;
    }

    private volatile String filter;

    /** From a title's "watch/providers": {HomeApps key, package} of the first service the TV has, or null. */
    String[] serviceOf(JSONObject watchProviders) {
        JSONObject results = watchProviders == null ? null : watchProviders.optJSONObject("results");
        JSONObject here = results == null ? null : results.optJSONObject(REGION);
        JSONArray flat = here == null ? null : here.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length(); i++) {
            String[] s = service(flat.optJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2])) return new String[] {s[1], s[2]};
        }
        return null;
    }

    /**
     * Every subscription service a title is on in your region, as {name, HomeApps key, package}; key and package are
     * null when the TV doesn't have that app. Then, after a {null, null, null} separator, where to rent or buy it (names).
     */
    List<String[]> servicesOf(JSONObject watchProviders) {
        List<String[]> out = new ArrayList<>();
        JSONObject results = watchProviders == null ? null : watchProviders.optJSONObject("results");
        JSONObject here = results == null ? null : results.optJSONObject(REGION);
        if (here == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        JSONArray flat = here.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length(); i++) {
            String name = flat.optJSONObject(i).optString("provider_name");
            String[] s = service(name);
            boolean has = s != null && installed(s[2]);
            String shown = s != null ? SERVICE_NAME.get(s[1]) : name;
            if (!seen.add(shown)) continue;
            out.add(new String[] {shown, has ? s[1] : null, has ? s[2] : null});
        }
        List<String> other = new ArrayList<>();
        for (String type : new String[] {"rent", "buy"}) {
            JSONArray list = here.optJSONArray(type);
            for (int i = 0; list != null && i < list.length(); i++) {
                String name = list.optJSONObject(i).optString("provider_name");
                if (seen.add(name) && !other.contains(name)) other.add(name);
            }
        }
        if (!other.isEmpty()) {
            out.add(new String[] {null, null, null});
            for (String name : other) out.add(new String[] {name, null, null});
        }
        return out;
    }

    static String serviceName(String appKey) {
        return appKey == null ? "" : SERVICE_NAME.get(appKey);
    }

    static String genreName(String name) {
        return Ui.en ? name : genre(name);
    }

    /** Language of titles and genres; the services come from the region. */
    static String lang() {
        return Ui.en ? "en-US" : "pt-BR";
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        String poster = o.optString("poster_path", "");
        p.poster = poster.isEmpty() || "null".equals(poster) ? null : poster;
        list.add(p);
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images&include_image_language=null,pt,en");
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(Ui.en ? name : genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0 && Ui.en) parts.add(minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m");
            else if (minutes > 0) parts.add(minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min");
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0 && Ui.en) parts.add(seasons == 1 ? "1 season" : seasons + " seasons");
            else if (seasons > 0) parts.add(seasons == 1 ? "1 temporada" : seasons + " temporadas");
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /** TV genres that TMDB returns in English even in pt-BR. */
    private static final String[][] GENRES_PT = {
        {"Action & Adventure", "Ação e Aventura"}, {"Sci-Fi & Fantasy", "Ficção Científica e Fantasia"},
        {"War & Politics", "Guerra e Política"}, {"Kids", "Infantil"}, {"News", "Notícias"}, {"Soap", "Novela"},
        {"Talk", "Entrevistas"},
    };

    private static String genre(String name) {
        for (String[] g : GENRES_PT) if (g[0].equalsIgnoreCase(name)) return g[1];
        return name;
    }

    /** The same for lists saved earlier ("Série · Action & Adventure · …" in Portuguese). */
    private static String fixGenres(String meta) {
        for (String[] g : GENRES_PT) {
            meta = meta.replace(" · " + g[0] + " ·", " · " + g[1] + " ·");
            if (meta.endsWith(" · " + g[0])) meta = meta.substring(0, meta.length() - g[0].length()) + g[1];
        }
        return meta;
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), at the highest resolution
     * (up to 3840, TMDB's 4K) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 3840) * 100 + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    private static String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String[] s : SERVICES) {
            if (s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0])) return s;
        }
        return null;
    }

    private boolean installed(String pkg) {
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        return tmdb(path);
    }

    /** A TMDB API call (path after /3), with your key. Blocking: background threads only. */
    static JSONObject tmdb(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static String readKey() {
        String key = Config.TMDB_KEY.trim();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        String url = p.backdrop.startsWith("https://") ? p.backdrop : IMAGES + p.backdrop;   // music: a full URL
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the original (≥ 1920 wide) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            if (MUSIC.equals(p.kind)) b = compose(b);
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                // Light design: every tab's 3 cards (5 tabs), the 3 on screen while a new set waits for its images, and
                // one more; fewer kept them evicting each other in a loop.
                while (images.size() > (forLight ? 19 : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 36) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 36; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                        .put("poster", p.poster == null ? "" : p.poster)
                        .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed).put("mine", p.mine));
            }
            cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putBoolean("english", listEnglish)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION).apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.poster = o.optString("poster").isEmpty() ? null : o.optString("poster");
            p.meta = fixGenres(o.optString("meta"));
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            p.mine = o.optBoolean("mine");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
