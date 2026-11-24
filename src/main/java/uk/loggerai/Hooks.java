package uk.loggerai;

/**
 * For LoggerAI's own integrations (the servlet filter, JDBC, Spring, Logback):
 * starting and finishing a request, and recording what it waited on. Not for
 * apps to call; use {@link LoggerAI}.
 */
public final class Hooks {
    private Hooks() {}

    public static boolean ignored(String path) {
        Client c = LoggerAI.client();
        return c == null || c.ignored(path);
    }

    public static String fallbackRoute(String path) {
        Client c = LoggerAI.client();
        return c == null ? "(unmatched)" : c.fallbackRoute(path);
    }

    /** A request starts on this thread. */
    public static Unit start(String method, String traceparent, String session) {
        return start(method, traceparent, session, null);
    }

    /** A request starts on this thread; caller: the x-loggerai-caller header (another of the project's services). */
    public static Unit start(String method, String traceparent, String session, String caller) {
        Unit u = begin(method, traceparent, session, caller);
        Unit.set(u);
        return u;
    }

    /** The request goes on on another thread: this one is free again. */
    public static void detach() {
        Unit.set(null);
    }

    /** It finished: sends the request record, and the error that failed it, if any. */
    public static void end(Unit unit, String route, int status, Throwable failure) {
        Unit.set(null);
        Client c = LoggerAI.client();
        if (c == null) return;
        if (unit.route == null) unit.route = route;
        if (failure != null) c.exception(failure, status, false, null, unit.route, unit.method);
        c.finish(unit, status);
    }

    public static void dependency(String target, String op, double ms, boolean ok) {
        Unit.dependency(target, op, ms, ok);
    }

    /** A request that isn't tied to this thread (reactive apps): started, but not set as current. */
    public static Unit begin(String method, String traceparent, String session) {
        return begin(method, traceparent, session, null);
    }

    public static Unit begin(String method, String traceparent, String session, String caller) {
        return new Unit("http", method == null ? "GET" : method.toUpperCase(), null, Unit.sessionFrom(session), Unit.parentFrom(traceparent), caller);
    }

    public static String newSpanId() {
        return Unit.newSpanId();
    }

    /** The headers that carry unit's trace to host, for the call with this span id; empty when the
     * host isn't one of the project's own services (outside APIs get nothing). */
    public static java.util.Map<String, String> carry(Unit unit, String host, String callSpanId) {
        Client c = LoggerAI.client();
        java.util.Map<String, String> h = new java.util.LinkedHashMap<>();
        if (unit == null || c == null || !c.enabled || !c.propagates.test(host)) return h;
        h.put("traceparent", unit.traceparent(callSpanId));
        h.put("x-loggerai-caller", c.service);
        if (unit.sessionId != null) h.put("x-loggerai-session", unit.sessionId);
        return h;
    }

    /** A call to another service made for that request, with its own span id and status. */
    public static void call(Unit unit, String target, String op, double ms, boolean ok, String callSpanId, int status) {
        if (unit == null || unit.finished) return;
        String o = op == null ? "?" : op.toUpperCase();
        unit.dep(target == null ? "unknown" : target, o.length() > 20 ? o.substring(0, 20) : o, ms, ok, callSpanId, "client", status);
    }

    /** The error that failed a request, reported as soon as it's seen (before a framework logs it). */
    public static void error(Unit unit, String route, int status, Throwable failure) {
        Client c = LoggerAI.client();
        if (c == null || failure == null) return;
        if (unit != null && unit.route == null) unit.route = route;
        c.exception(failure, status, false, null, unit != null ? unit.route : route, unit != null ? unit.method : null);
    }

    /** A call made for that request. */
    public static void dependency(Unit unit, String target, String op, double ms, boolean ok) {
        if (unit == null || unit.finished) return;
        String o = op == null ? "?" : op.toUpperCase();
        unit.dep(target == null ? "unknown" : target, o.length() > 20 ? o.substring(0, 20) : o, ms, ok);
    }

    public static void setUser(Unit unit, Object id) {
        Client c = LoggerAI.client();
        if (unit != null && c != null) unit.user = c.userRef(id);
    }

    public static void setRoute(Unit unit, String route) {
        if (unit != null && route != null) unit.route = route;
    }

    public static String sqlVerb(String sql) {
        return Unit.sqlVerb(sql);
    }

    public static boolean logs(String level) {
        Client c = LoggerAI.client();
        return c != null && c.enabled && c.logs(level);
    }

    public static void log(String level, String logger, String message, Throwable t) {
        Client c = LoggerAI.client();
        if (c == null) return;
        c.log(level, logger, message);
        // An ERROR logged with an exception is an error too: grouped, with its stack.
        if (t != null && (level.equals("ERROR") || level.equals("CRITICAL"))) c.exception(t, null, true, null, null, null);
    }
}
