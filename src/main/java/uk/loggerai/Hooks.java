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
        Unit u = new Unit("http", method == null ? "GET" : method.toUpperCase(), Unit.traceFrom(traceparent), Unit.sessionFrom(session));
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
        return new Unit("http", method == null ? "GET" : method.toUpperCase(), Unit.traceFrom(traceparent), Unit.sessionFrom(session));
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
