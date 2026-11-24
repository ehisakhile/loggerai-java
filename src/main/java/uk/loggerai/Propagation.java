package uk.loggerai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Which outgoing calls carry the trace (traceparent) and this service's name
 * (x-loggerai-caller): the project's own services. By default internal hosts
 * only (names without a dot, private addresses, .internal, .svc.cluster.local...),
 * so outside APIs never get your trace ids or service names.
 */
final class Propagation {
    private static final Pattern PRIVATE_V4 = Pattern.compile("^(10\\.|127\\.|192\\.168\\.|169\\.254\\.|172\\.(1[6-9]|2\\d|3[01])\\.).*");
    private static final Pattern IPV4 = Pattern.compile("^\\d+\\.\\d+\\.\\d+\\.\\d+$");
    private static final Pattern INTERNAL_SUFFIX = Pattern.compile(".*\\.(local|internal|svc|cluster\\.local|lan|home\\.arpa|consul|localdomain)$");

    private Propagation() {}

    static boolean isInternal(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (h.isEmpty()) return false;
        if (h.equals("localhost") || h.equals("::1") || PRIVATE_V4.matcher(h).matches() || h.matches("^f[cd][0-9a-f]{2}:.*")) return true;
        if (IPV4.matcher(h).matches()) return false;
        return !h.contains(".") || INTERNAL_SUFFIX.matcher(h).matches();
    }

    /** setting: null (internal hosts), "all", "none", or hosts and patterns ("api.acme.io,*.acme.io"). */
    static Predicate<String> rule(String setting) {
        if (setting == null || setting.isBlank()) return Propagation::isInternal;
        String s = setting.trim().toLowerCase(Locale.ROOT);
        if (s.equals("all") || s.equals("true")) return h -> true;
        if (s.equals("none") || s.equals("false")) return h -> false;
        List<String> patterns = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isBlank()) patterns.add(p.trim());
        return host -> {
            if (isInternal(host)) return true;
            String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
            for (String p : patterns) if (p.startsWith("*.") ? h.endsWith(p.substring(1)) : h.equals(p)) return true;
            return false;
        };
    }
}
