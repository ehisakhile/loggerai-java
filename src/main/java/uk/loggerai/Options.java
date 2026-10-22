package uk.loggerai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Settings for {@link LoggerAI#init(Options)}. Anything left unset is read from
 * the environment (LOGGERAI_KEY, LOGGERAI_SERVICE, LOGGERAI_ENVIRONMENT,
 * LOGGERAI_RELEASE, LOGGERAI_ENABLED, LOGGERAI_ENDPOINT, LOGGERAI_HOST_METRICS,
 * LOGGERAI_APP_PACKAGES) or has a default.
 */
public final class Options {
    String key;
    String endpoint;
    String service;
    String environment;
    String release;
    Boolean enabled;
    Boolean hostMetrics;
    Duration flushInterval = Duration.ofSeconds(5);
    Duration timeout = Duration.ofSeconds(2);
    Duration healthInterval = Duration.ofSeconds(60);
    Duration hostInterval = Duration.ofSeconds(30);
    String logsLevel = "WARNING";
    List<String> ignorePaths = new ArrayList<>(List.of("/health", "/healthz", "/up", "/favicon.ico", "/metrics", "/actuator/"));
    List<String> appPackages = new ArrayList<>();
    final Map<String, Supplier<Object>> healthChecks = new LinkedHashMap<>();
    String root;

    public static Options fromEnvironment() {
        return new Options();
    }

    /** The project's secret key (sk_live_...). */
    public Options key(String v) { key = v; return this; }
    public Options endpoint(String v) { endpoint = v; return this; }
    /** Names the process: an API, a worker. Default "app". */
    public Options service(String v) { service = v; return this; }
    /** Default "production". */
    public Options environment(String v) { environment = v; return this; }
    /** Your version or git SHA, so LoggerAI can say "since release X". */
    public Options release(String v) { release = v; return this; }
    public Options enabled(boolean v) { enabled = v; return this; }
    /** The server's CPU, memory and disk. On by default. */
    public Options hostMetrics(boolean v) { hostMetrics = v; return this; }
    public Options flushInterval(Duration v) { flushInterval = v; return this; }
    public Options timeout(Duration v) { timeout = v; return this; }
    /** The lowest level sent as a log: "WARNING" (default), "ERROR", or null for none. */
    public Options logsLevel(String v) { logsLevel = v; return this; }
    /** Paths not recorded (prefixes; a trailing slash matches everything under it). */
    public Options ignorePaths(List<String> v) { ignorePaths = new ArrayList<>(v); return this; }
    /** Your own packages (com.acme): stack frames in them are marked as your code. */
    public Options appPackages(List<String> v) { appPackages = new ArrayList<>(v); return this; }
    /** A check run every minute: return true/false, or throw for a failure with its message. */
    public Options healthCheck(String name, Supplier<Object> check) { healthChecks.put(name, check); return this; }
    public Options healthInterval(Duration v) { healthInterval = v; return this; }
    public Options hostInterval(Duration v) { hostInterval = v; return this; }
    /** The app's folder, for the disk it's on. Default: the working directory. */
    public Options root(String v) { root = v; return this; }
}
