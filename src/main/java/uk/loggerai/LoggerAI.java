package uk.loggerai;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * LoggerAI for Java: performance monitoring, errors, logs, health checks and
 * server metrics. In Spring Boot it starts on its own (add the dependency and
 * set LOGGERAI_KEY); anywhere else:
 *
 * <pre>
 *   LoggerAI.init(Options.fromEnvironment());       // reads LOGGERAI_KEY (sk_live_...)
 *   // and register uk.loggerai.servlet.LoggerAIFilter for every request
 * </pre>
 *
 * Nothing is sent without a key, nothing ever throws into your app, and
 * secrets are masked before anything leaves it.
 */
public final class LoggerAI {
    public static final String VERSION = "0.1.0";
    private static volatile Client client;
    private static volatile boolean hooked;

    private LoggerAI() {}

    /** Start LoggerAI (again, if it was started). */
    public static synchronized Client init(Options options) {
        if (client != null) client.close(Duration.ofSeconds(1));
        Client c = new Client(options == null ? new Options() : options);
        client = c;
        if (c.enabled) {
            System.err.println("LoggerAI: sending as " + c.service + " (" + c.environment + ") to " + c.endpoint);
            if (!hooked) {
                hooked = true;
                // Send what's left when the JVM stops (deploys, containers stopping).
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    Client last = client;
                    if (last != null) last.close(Duration.ofSeconds(2));
                }, "loggerai-shutdown"));
            }
        } else if (c.key == null) {
            System.err.println("LoggerAI: off (no LOGGERAI_KEY)");
        }
        return c;
    }

    public static Client client() {
        return client;
    }

    public static boolean enabled() {
        Client c = client;
        return c != null && c.enabled;
    }

    /** Report an exception you handled but still want to see. */
    public static void captureException(Throwable t) {
        captureException(t, null);
    }

    public static void captureException(Throwable t, Map<String, Object> extra) {
        Client c = client;
        if (c != null) c.exception(t, null, true, extra, null, null);
    }

    /** Count the current request against a user. Only a one-way hash of the id is sent. */
    public static void setUser(Object id) {
        Unit u = Unit.current();
        Client c = client;
        if (u != null && c != null) u.user = c.userRef(id);
    }

    /** Name the current request yourself (a template like /orders/{id}, never a raw path). */
    public static void setRoute(String template) {
        Unit u = Unit.current();
        if (u != null && template != null) u.route = template.length() > 255 ? template.substring(0, 255) : template;
    }

    /** Time a call LoggerAI can't see on its own, e.g. an SDK with its own HTTP client:
     * {@code LoggerAI.measure("api.stripe.com", "POST", () -> stripe.confirm(id))} */
    public static <T> T measure(String target, String operation, Callable<T> fn) throws Exception {
        long t = System.nanoTime();
        boolean ok = false;
        try {
            T r = fn.call();
            ok = true;
            return r;
        } finally {
            Unit.dependency(target, operation == null ? "CALL" : operation, (System.nanoTime() - t) / 1e6, ok);
        }
    }

    /** Time a unit of work outside a web request: a scheduled task, a queue listener, a script.
     * {@code LoggerAI.track("job", "SendInvoices", () -> invoices.sendAll())} */
    public static <T> T track(String kind, String name, Callable<T> fn) throws Exception {
        Client c = client;
        if (c == null || !c.enabled || Unit.current() != null) return fn.call();
        String k = "command".equals(kind) ? "command" : "http".equals(kind) ? "http" : "job";
        Unit u = new Unit(k, "command".equals(k) ? "CLI" : "JOB", null, null);
        u.route = name;
        Unit.set(u);
        int status = 200;
        try {
            return fn.call();
        } catch (Exception | Error e) {
            status = 500;
            c.exception(e, 500, false, null, null, null);
            throw e;
        } finally {
            Unit.set(null);
            c.finish(u, status);
        }
    }

    public static void track(String kind, String name, Runnable fn) {
        try {
            track(kind, name, () -> {
                fn.run();
                return null;
            });
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A log line of your own: DEBUG, INFO, WARNING, ERROR or CRITICAL. */
    public static void log(String level, String message) {
        Client c = client;
        if (c != null) c.log(level.toUpperCase(), "app", message);
    }

    /** Add a health check, run every minute: return true/false, or throw for a failure. */
    public static void healthCheck(String name, java.util.function.Supplier<Object> check) {
        Client c = client;
        if (c != null) c.healthChecks.put(name, check);
    }

    /** Send everything now (at the end of a script or a serverless call). */
    public static boolean flush(Duration wait) {
        Client c = client;
        return c == null || c.flush(wait);
    }

    /** {@code java -jar loggerai-java.jar test}: check this machine can send to LoggerAI. */
    public static void main(String[] args) {
        if (args.length != 1 || !args[0].equals("test")) {
            System.out.println("usage: java -jar loggerai-java-" + VERSION + ".jar test");
            System.exit(2);
        }
        String key = Client.env("LOGGERAI_KEY");
        System.out.println("loggerai-java " + VERSION);
        System.out.println("LOGGERAI_KEY: " + (key == null ? "(not set)" : key.substring(0, Math.min(12, key.length())) + "…"));
        Client c = init(new Options().hostMetrics(false).healthCheck("loggerai-test", () -> true));
        System.out.println("endpoint: " + c.endpoint);
        System.out.println("service: " + c.service + " · environment: " + c.environment + " · release: " + (c.release == null ? "(none)" : c.release));
        if (!c.enabled) {
            System.out.println("Not sending: set LOGGERAI_KEY to the project's secret key (sk_live_…) from LoggerAI → Connect.");
            System.exit(1);
        }
        track("command", "loggerai test", () -> {});
        c.runChecksNow();
        boolean sent = c.flush(Duration.ofSeconds(10));
        if (c.lastError != null || !sent) {
            System.out.println("Failed: " + (c.lastError != null ? c.lastError : "timed out"));
            System.exit(1);
        }
        System.out.println("Sent. The project in LoggerAI shows as connected within a few seconds.");
        System.exit(0);
    }
}
