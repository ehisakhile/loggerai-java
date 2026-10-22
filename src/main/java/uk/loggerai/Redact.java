package uk.loggerai;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets before anything leaves the app: by key name (fields, headers,
 * query parameters) and by shape (bearer tokens and JWTs anywhere in a string).
 * The same rules as LoggerAI's server, browser script and other SDKs.
 */
public final class Redact {
    public static final String MASK = "[REDACTED]";

    // A key is sensitive when one of its words is one of these
    // ("accessToken" -> access, token; "X-CSRF-Token" -> x, csrf, token).
    private static final Set<String> WORDS = Set.of(
            "password", "passwd", "pwd", "pass", "passphrase", "secret", "token", "otp", "pin", "cvv", "cvc",
            "ssn", "cookie", "authorization", "auth", "signature", "sig", "credential", "credentials", "bvn", "nin",
            // where someone is: user_lat, userLng, pickup_latitude... (analytics never keep locations)
            "lat", "lng", "lon", "latitude", "longitude", "coords", "coordinates", "geolocation", "gps");
    // ...or when its words run together into one of these ("x-api-key" -> xapikey).
    private static final List<String> JOINED = List.of(
            "apikey", "accesskey", "privatekey", "secretkey", "cardnumber", "clientsecret", "sessionid",
            "authtoken", "accesstoken", "refreshtoken", "idtoken", "csrftoken", "xsrf", "setcookie",
            "latlng", "latlon", "lnglat", "lonlat", "geopoint");
    // Query parameters that carry credentials in URLs (e.g. Google's ?key=).
    private static final Set<String> URL_EXTRA = Set.of("key", "code", "access_token", "id_token", "ll", "location"); // ?ll=6.52,3.37 (maps)

    private static final Pattern SPLIT = Pattern.compile("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic|token)\\s+[A-Za-z0-9\\-._~+/]{12,}=*");
    // A latitude,longitude pair anywhere, e.g. in a path: /nearby/6.524379,3.379206
    private static final Pattern COORDS = Pattern.compile("(?i)(?<![\\d.])-?\\d{1,3}\\.\\d{4,}\\s*(?:,|%2C)\\s*-?\\d{1,3}\\.\\d{4,}(?![\\d.])");

    private Redact() {}

    public static boolean isSensitive(String key) {
        if (key == null || key.isEmpty()) return false;
        StringBuilder joined = new StringBuilder();
        for (String w : SPLIT.split(key)) {
            if (w.isEmpty()) continue;
            String lw = w.toLowerCase(Locale.ROOT);
            if (WORDS.contains(lw)) return true;
            joined.append(lw);
        }
        String j = joined.toString();
        for (String s : JOINED) if (j.contains(s)) return true;
        return false;
    }

    public static String string(String s) {
        if (s == null) return null;
        s = JWT.matcher(s).replaceAll("[REDACTED_JWT]");
        s = COORDS.matcher(s).replaceAll("[REDACTED_LOCATION]");
        Matcher m = BEARER.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + " " + MASK));
        m.appendTail(sb);
        return sb.toString();
    }

    public static String url(String url) {
        if (url == null) return null;
        try {
            URI u = new URI(url);
            String q = u.getRawQuery();
            String userInfo = u.getRawUserInfo();
            if (q == null && userInfo == null) return string(url);
            List<String> parts = new ArrayList<>();
            if (q != null) {
                for (String pair : q.split("&")) {
                    int eq = pair.indexOf('=');
                    String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                    String v = eq < 0 ? "" : pair.substring(eq + 1);
                    boolean secret = !v.isEmpty() && (isSensitive(k) || URL_EXTRA.contains(k.toLowerCase(Locale.ROOT)));
                    parts.add(eq < 0 ? pair : pair.substring(0, eq) + "=" + (secret ? MASK : v));
                }
            }
            StringBuilder sb = new StringBuilder();
            if (u.getScheme() != null) sb.append(u.getScheme()).append("://");
            if (userInfo != null) sb.append(MASK).append('@');
            if (u.getRawAuthority() != null) {
                String auth = u.getRawAuthority();
                sb.append(auth.contains("@") ? auth.substring(auth.lastIndexOf('@') + 1) : auth);
            }
            if (u.getRawPath() != null) sb.append(u.getRawPath());
            if (!parts.isEmpty()) sb.append('?').append(String.join("&", parts));
            if (u.getRawFragment() != null) sb.append('#').append(u.getRawFragment());
            return string(sb.toString());
        } catch (Exception e) {
            return string(url);
        }
    }

    /** A copy of a map (of maps, lists and strings) with secrets masked. */
    @SuppressWarnings("unchecked")
    public static Object value(Object v, int depth) {
        if (depth > 40) return v;
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object x = e.getValue();
                boolean empty = x == null || "".equals(x);
                if (isSensitive(k) && !empty) out.put(k, MASK);
                else if (x instanceof String s && Set.of("url", "href", "referrer", "referer", "location").contains(k.toLowerCase(Locale.ROOT))) out.put(k, url(s));
                else out.put(k, value(x, depth + 1));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(value(o, depth + 1));
            return out;
        }
        if (v instanceof String s) return s.startsWith("http://") || s.startsWith("https://") ? url(s) : string(s);
        if (v == null || v instanceof Number || v instanceof Boolean) return v;
        return string(String.valueOf(v));
    }
}
