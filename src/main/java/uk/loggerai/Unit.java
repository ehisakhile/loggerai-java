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
 *
 * Traces cross services with W3C traceparent: a unit continues the trace it was
 * called with (its parent is the caller's span), calls to other services are
 * spans of their own, and calls to the project's own services carry the trace on.
 */
public final class Unit {
    private static final ThreadLocal<Unit> CURRENT = new ThreadLocal<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");
    private static final Pattern SESSION = Pattern.compile("^[A-Za-z0-9._:-]+$");
    private static final Pattern CALLER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/ -]{0,59}$");
    static final int MAX_SPANS = 200; // the server keeps this many steps per request

    final String kind;
    final String method;
    volatile String route;
    final String traceId;
    final String spanId;
    final String parentSpanId;
    final String caller;
    volatile String transport;
    final String sessionId;
    volatile String user;
    final double ts;
    final long started;
    private final Map<String, double[]> deps = new LinkedHashMap<>(); // target \0 op -> count, ms, errors
    private final List<Map<String, Object>> spans = new ArrayList<>();
    volatile boolean finished;

    Unit(String kind, String method, String traceId, String sessionId) {
        this(kind, method, traceId, sessionId, null, null);
    }

    /** parent: {trace id, span id} from traceparent; caller: the calling service's name. */
    Unit(String kind, String method, String traceId, String sessionId, String[] parent, String caller) {
        this.kind = kind;
        this.method = method;
        this.traceId = traceId != null ? traceId : parent != null ? parent[0] : newTraceId();
        this.spanId = newSpanId();
        this.parentSpanId = parent != null && parent[0].equals(this.traceId) ? parent[1] : null;
        this.caller = callerFrom(caller);
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

    public String spanId() {
        return spanId;
    }

    /** The traceparent for a call this unit makes, with that call's own span id. */
    public String traceparent(String childSpanId) {
        return "00-" + traceId + "-" + childSpanId + "-01";
    }

    synchronized void dep(String target, String op, double ms, boolean ok) {
        dep(target, op, ms, ok, null, null, 0);
    }

    /** A call: calls to other services and queues (kind client or producer) are kept one by one,
     * since they link the trace across services; the rest stay as totals per target. */
    synchronized void dep(String target, String op, double ms, boolean ok, String callSpanId, String kind, int status) {
        double[] d = deps.computeIfAbsent(target + "\u0000" + op, k -> new double[3]);
        d[0] += 1;
        d[1] += ms;
        if (!ok) d[2] += 1;
        String k = kind != null ? kind : stepKind(target);
        if ((k.equals("client") || k.equals("producer")) && spans.size() < MAX_SPANS) {
            double end = (System.nanoTime() - started) / 1e6;
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("span_id", callSpanId != null ? callSpanId : newSpanId());
            s.put("name", (k.equals("producer") ? "publish " : op + " ") + target);
            s.put("kind", k);
            s.put("target", target);
            s.put("start_ms", Math.round(Math.max(0, end - ms) * 100) / 100.0);
            s.put("duration_ms", Math.round(ms * 100) / 100.0);
            s.put("ok", ok);
            if (status > 0) s.put("status", status);
            spans.add(s);
        }
    }

    static String stepKind(String target) {
        String t = target == null ? "" : target.toLowerCase();
        return switch (t) {
            case "postgres", "postgresql", "mysql", "mariadb", "oracle", "sqlserver", "h2", "sqlite", "mongodb" -> "db";
            case "redis", "memcached" -> "cache";
            default -> "client";
        };
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
        r.put("span_id", spanId);
        if (parentSpanId != null) r.put("parent_span_id", parentSpanId);
        if (caller != null) r.put("caller", caller);
        if (transport != null) r.put("transport", transport);
        r.put("session_id", sessionId);
        r.put("user", user);
        r.put("deps", list);
        if (!spans.isEmpty()) r.put("spans", new ArrayList<>(spans));
        return r;
    }

    static String newSpanId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String newTraceId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** The trace id from a W3C traceparent header, if it's well formed. */
    static String traceFrom(String header) {
        String[] p = parentFrom(header);
        return p == null ? null : p[0];
    }

    /** {trace id, parent span id} from a W3C traceparent header, if it's well formed. */
    static String[] parentFrom(String header) {
        if (header == null) return null;
        var m = TRACEPARENT.matcher(header.trim().toLowerCase());
        if (!m.matches() || m.group(1).equals("0".repeat(32)) || m.group(2).equals("0".repeat(16))) return null;
        return new String[] {m.group(1), m.group(2)};
    }

    static String callerFrom(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.length() > 60) v = v.substring(0, 60);
        return !v.isEmpty() && CALLER.matcher(v).matches() ? v : null;
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
