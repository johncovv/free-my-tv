package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exchange rates (AwesomeAPI, every 5 min) and weather/sunrise/sunset for your location (Open-Meteo, every 30 min), cached
 * so the screen opens with numbers. Only fetched while the home screen or the screensaver is visible (the screen asks).
 * Neither source needs a key. Location and currency come from config.properties.
 */
final class HomeData {
    private static final String CURRENCY = Config.CURRENCY.trim().toUpperCase(Locale.ROOT);
    /** Dollar, euro, pound and yuan, priced in the configured currency (the one you pay in is left out). */
    static final String[] COINS = without(new String[] {"USD", "EUR", "GBP", "CNY"}, CURRENCY);
    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast?latitude=" + Config.LATITUDE
            + "&longitude=" + Config.LONGITUDE
            + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
            + "&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=2";
    private static final long PRICES_EVERY = 5 * 60_000L, WEATHER_EVERY = 30 * 60_000L;
    private static final Locale PT = new Locale("pt", "BR");

    interface Listener { void onData(); }

    static int coinCount() {
        return COINS.length;
    }

    static String coinName(int i) {
        switch (COINS[i]) {
            case "USD": return Ui.tr("Dólar", "Dollar");
            case "EUR": return "Euro";
            case "GBP": return Ui.tr("Libra", "Pound");
            case "CNY": return "Yuan";
            default: return COINS[i];
        }
    }

    private static String[] without(String[] list, String drop) {
        List<String> out = new ArrayList<>();
        for (String s : list) if (!s.equals(drop)) out.add(s);
        return out.toArray(new String[0]);
    }

    /**
     * One request per pair: AwesomeAPI rejects a whole batch when one pair doesn't exist (GBP-CAD, say), so a currency
     * it only partly covers still shows what it has. Returns {"USDBRL": {...}, ...} like a batch would.
     */
    private static String fetchPrices() throws IOException, JSONException {
        JSONObject all = new JSONObject();
        for (String coin : COINS) {
            try {
                JSONObject one = new JSONObject(get("https://economia.awesomeapi.com.br/json/last/" + coin + "-" + CURRENCY));
                java.util.Iterator<String> keys = one.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    all.put(key, one.get(key));
                }
            } catch (IOException e) {
                AppLog.w("no " + coin + "-" + CURRENCY + " rate: " + e.getMessage());
            }
        }
        if (all.length() == 0) throw new IOException("no exchange rates for " + CURRENCY);
        return all.toString();
    }

    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;   // today's, in epoch ms
        // Next hours (starting at the next full hour): time, temperature, code and day/night.
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
    }

    /** Only the home screen's process (:home) saves; the Android 11 screensaver (main process) just reads. */
    private static final boolean WRITER = android.app.Application.getProcessName().endsWith(":home");

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    final double[] prices = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    /** 24 h change (%), in the same order. */
    final double[] changes = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    volatile long pricesAt, weatherAt;
    volatile Weather weather = new Weather();

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetchingPrices, fetchingWeather;

    private HomeData(Context c) {
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        try {
            pricesAt = cache.getLong("pricesAt", 0);
            String p = cache.getString("prices", null);
            if (p != null) parsePrices(p);   // after pricesAt: an unreadable cache resets it, so it's fetched again
            String w = cache.getString("weather", null);
            if (w != null) weather = parseWeather(w);
            weatherAt = cache.getLong("weatherAt", 0);
            // Saved for another place (the city changed in config.properties): fetch again right away.
            if (!WEATHER_URL.equals(cache.getString("weatherFor", WEATHER_URL))) weatherAt = 0;
        } catch (Exception ignored) {}
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Fetches whatever is stale. The screen calls this when it appears and every minute while it's visible. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        if (!fetchingPrices && (now - pricesAt > PRICES_EVERY || now < pricesAt)) {
            fetchingPrices = true;
            io.execute(() -> {
                try {
                    String body = fetchPrices();
                    parsePrices(body);
                    pricesAt = System.currentTimeMillis();
                    if (WRITER) cache.edit().putString("prices", body).putLong("pricesAt", pricesAt).apply();
                    notifyListeners();
                } catch (Exception e) {
                    AppLog.w("prices: " + e.getMessage());
                } finally {
                    fetchingPrices = false;
                }
            });
        }
        if (!fetchingWeather && (now - weatherAt > WEATHER_EVERY || now < weatherAt)) {
            fetchingWeather = true;
            io.execute(() -> {
                try {
                    String body = get(WEATHER_URL);
                    weather = parseWeather(body);
                    weatherAt = System.currentTimeMillis();
                    if (WRITER) {
                        cache.edit().putString("weather", body).putLong("weatherAt", weatherAt)
                                .putString("weatherFor", WEATHER_URL).apply();
                    }
                    notifyListeners();
                } catch (Exception e) {
                    AppLog.w("weather: " + e.getMessage());
                } finally {
                    fetchingWeather = false;
                }
            });
        }
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
    }

    /** {"USDBRL": {"bid": "5.03", "pctChange": "-0.68", ...}, ...} */
    private void parsePrices(String body) throws Exception {
        JSONObject all = new JSONObject(body.trim().startsWith("{") ? body : "{}");
        int found = 0;
        for (int k = 0; k < COINS.length; k++) {
            JSONObject o = all.optJSONObject(COINS[k] + CURRENCY);
            if (o == null) {
                prices[k] = changes[k] = Double.NaN;   // not in this answer: no old number passed off as current
                continue;
            }
            found++;
            prices[k] = Double.parseDouble(o.optString("bid", "NaN"));
            changes[k] = Double.parseDouble(o.optString("pctChange", "NaN"));
        }
        if (found == 0) pricesAt = 0;   // cached in another currency or format (config changed): fetch again
    }

    private static Weather parseWeather(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        TimeZone zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        Weather w = new Weather();
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        w.isDay = current.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone);
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length() && w.hours.size() < 6; i++) {
                long at = localTime(times.getString(i), zone);
                if (at <= now) continue;
                w.hours.add(at);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(codes.getInt(i));
                w.hourDay.add(days == null || days.optInt(i, 1) == 1);
            }
        }
        return w;
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
     * Uses the last weather fetched (it moves ~1 min a day); without data, 6:00 and 18:00.
     */
    boolean isNight(long now) {
        // theme in config.properties: "dark" or "light" pin the look; "auto" follows the sun.
        if ("dark".equals(Config.THEME)) return true;
        if ("light".equals(Config.THEME)) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(weather.sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(weather.sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }

    // MARK: - Formatting

    private static Locale locale() {
        return Ui.en ? Locale.US : PT;
    }

    /** 580.4k · 21.0k · 1.04k · 5.61 (as short as possible; the dollar always with cents). */
    static String price(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        return String.format(locale(), "%.2f", v);
    }

    /** 416.3k · 12.2k · 1.04k · 531 · 5.05: abbreviated, for the price row on the home screen. */
    static String compact(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        if (v >= 100) return String.format(locale(), "%.0f", v);
        return String.format(locale(), "%.2f", v);
    }

    /** $108,234 · $185.20 · R$ 581.234 · R$ 5,61 (cents only below a thousand), in the configured currency. */
    static String brl(double v) {
        String symbol = currencySymbol();
        if (Double.isNaN(v)) return symbol + "—";
        return v >= 1000 ? String.format(locale(), symbol + "%,.0f", v) : String.format(locale(), symbol + "%.2f", v);
    }

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (Ui.en) {
            if (code == 0) return "Clear";
            if (code == 1) return day ? "Mostly Sunny" : "Mostly Clear";
            if (code == 2) return "Partly Cloudy";
            if (code == 3) return "Cloudy";
            if (code == 45 || code == 48) return "Fog";
            if (code >= 51 && code <= 57) return "Drizzle";
            if (code == 65 || code == 67 || code == 82) return "Heavy Rain";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Snow";
            if (code >= 95) return "Thunderstorms";
            return "Rain";
        }
        if (code == 0) return day ? "Céu limpo" : "Noite limpa";
        if (code == 1) return "Poucas nuvens";
        if (code == 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Neblina";
        if (code >= 51 && code <= 57) return "Garoa";
        if (code == 65 || code == 67 || code == 82) return "Chuva forte";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Neve";
        if (code >= 95) return "Tempestade";
        return "Chuva";
    }

    private static String currencySymbol() {
        switch (CURRENCY) {
            case "USD": return "$";
            case "BRL": return "R$ ";
            case "EUR": return "€";
            case "GBP": return "£";
            case "JPY": return "¥";
            default: return CURRENCY + " ";
        }
    }

    /** Celsius or Fahrenheit (config.properties "temperature": C, F, or auto = Fahrenheit in the US). */
    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round(fahrenheit() ? v * 9 / 5 + 32 : v) + "°";
    }

    private static boolean fahrenheit() {
        String unit = Config.TEMPERATURE.trim().toUpperCase(Locale.ROOT);
        if (unit.startsWith("F")) return true;
        if (unit.startsWith("C")) return false;
        return "US".equals(Locale.getDefault().getCountry());
    }

    /** "+1.2%" / "−0.8%" (a real minus sign). */
    static String change(double v) {
        if (Double.isNaN(v)) return "";
        return (v >= 0 ? "+" : "−") + String.format(locale(), "%.1f%%", Math.abs(v));
    }

    static String hour(long at) {
        return Ui.en ? new SimpleDateFormat("h a", Locale.US).format(new java.util.Date(at))
                : new SimpleDateFormat("HH'h'", PT).format(new java.util.Date(at));
    }

    /** How long ago the data was fetched, if it's stale (no internet): "12 min ago". */
    static String staleness(long at, long every) {
        if (at == 0) return "";
        long age = System.currentTimeMillis() - at;
        if (age < every * 2) return "";
        long minutes = age / 60_000;
        if (Ui.en) return minutes < 60 ? minutes + " min ago" : (minutes / 60) + " h ago";
        return minutes < 60 ? "há " + minutes + " min" : "há " + (minutes / 60) + " h";
    }
}
