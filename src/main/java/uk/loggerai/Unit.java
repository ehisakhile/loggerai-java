package uk.loggerai;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The unit of work in progress (an HTTP request, a job, a command): what it
 * waited on, grouped by target and operation, until it finishes. Kept per
 * thread, which is how servlet containers run a request.
 */
public final class Unit {
    private static final ThreadLocal<Unit> CURRENT = new ThreadLocal<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final Pattern SESSION = Pattern.compile("^[A-Za-z0-9._:-]+$");

    final String kind;
    final String method;
    volatile String route;
    final String traceId;
    final String sessionId;
    volatile String user;
    final double ts;
    final long started;
    private final Map<String, double[]> deps = new LinkedHashMap<>(); // target \0 op -> count, ms, errors
    volatile boolean finished;

    Unit(String kind, String method, String traceId, String sessionId) {
        this.kind = kind;
        this.method = method;
        this.traceId = traceId != null ? traceId : newTraceId();
        this.sessionId = sessionId;
        this.ts = System.currentTimeMillis() / 1000.0;
        this.started = System.nanoTime();
    }

    public static Unit current() {
        return CURRENT.get();
    }

    static void set(Unit u) {
        if (u == null) CURRENT.remove();
        else CURRENT.set(u);
    }

    public String traceId() {
        return traceId;
    }

    public String route() {
        return route;
    }

    synchronized void dep(String target, String op, double ms, boolean ok) {
        double[] d = deps.computeIfAbsent(target + "\u0000" + op, k -> new double[3]);
        d[0] += 1;
        d[1] += ms;
        if (!ok) d[2] += 1;
    }

    synchronized Map<String, Object> record(int status) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, double[]> e : deps.entrySet()) {
            String[] k = e.getKey().split("\u0000", 2);
            double[] d = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("target", k[0]);
            m.put("op", k[1]);
            m.put("count", (long) d[0]);
            m.put("ms", Math.round(d[1] * 100) / 100.0);
            m.put("errors", (long) d[2]);
            list.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ts", Math.round(ts * 1000) / 1000.0);
        r.put("kind", kind);
        r.put("method", method);
        r.put("route", route != null ? route : "(unmatched)");
        r.put("status", status);
        r.put("duration_ms", Math.round((System.nanoTime() - started) / 1e4) / 100.0);
        r.put("trace_id", traceId);
        r.put("session_id", sessionId);
        r.put("user", user);
        r.put("deps", list);
        return r;
    }

    static String newTraceId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** The trace id from a W3C traceparent header, if it's well formed. */
    static String traceFrom(String header) {
        if (header == null) return null;
        var m = TRACEPARENT.matcher(header.trim().toLowerCase());
        return m.matches() && !m.group(1).equals("0".repeat(32)) ? m.group(1) : null;
    }

    static String sessionFrom(String value) {
        if (value == null) return null;
        String v = value.trim();
        return !v.isEmpty() && SESSION.matcher(v).matches() ? v.substring(0, Math.min(v.length(), 64)) : null;
    }

    /** Record a call made during the current unit (ignored outside one). */
    public static void dependency(String target, String op, double ms, boolean ok) {
        Unit u = CURRENT.get();
        if (u != null && !u.finished) {
            String t = target == null ? "unknown" : target.length() > 120 ? target.substring(0, 120) : target;
            String o = op == null ? "?" : op.toUpperCase();
            u.dep(t, o.length() > 20 ? o.substring(0, 20) : o, ms, ok);
        }
    }

    static String sqlVerb(String sql) {
        if (sql == null) return "?";
        String s = sql.stripLeading();
        while (s.startsWith("(")) s = s.substring(1).stripLeading();
        int i = 0;
        while (i < s.length() && Character.isLetter(s.charAt(i))) i++;
        return i == 0 ? "?" : s.substring(0, Math.min(i, 20)).toUpperCase();
    }
}
