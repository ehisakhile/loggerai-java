package uk.loggerai;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The client: settings, the buffer, and a background thread that sends batches
 * to LoggerAI every few seconds. Nothing here ever throws into the app or makes
 * a request wait; when LoggerAI can't be reached, records are dropped rather
 * than piling up.
 */
public final class Client {
    static final String DEFAULT_ENDPOINT = "https://loggerapp.ehigiator.com/api/v1/ingest/batch";
    static final List<String> KINDS = List.of("requests", "errors", "logs", "checks", "hosts");
    private static final int MAX_BUFFER = 10_000; // per kind; beyond this the oldest go
    private static final int MAX_PER_BATCH = 1_000; // the server's limit per kind
    private static final int MAX_BATCH_BYTES = 900_000; // under the server's 1 MB
    private static final int MAX_FALLBACK_ROUTES = 300;
    private static final Pattern ID_SEGMENT = Pattern.compile(
            "^(\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{12,}|[A-Za-z0-9_-]{20,})$");
    private static final Map<String, Integer> LEVELS = Map.of("DEBUG", 10, "INFO", 20, "WARNING", 30, "ERROR", 40, "CRITICAL", 50);

    final String key;
    final String endpoint;
    final String service;
    final String environment;
    final String release;
    volatile boolean enabled;
    final boolean hostMetrics;
    final String logsLevel;
    final List<String> ignorePaths;
    final List<String> appPackages;
    final Map<String, Supplier<Object>> healthChecks;
    final String host;
    final String root;
    private final Duration timeout;
    private final long healthIntervalMs;
    private final long hostIntervalMs;
    private final long flushIntervalMs;

    private final Map<String, ConcurrentLinkedQueue<Map<String, Object>>> buf = new LinkedHashMap<>();
    private final Map<String, AtomicInteger> sizes = new LinkedHashMap<>();
    private final Set<String> fallbackRoutes = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService sender;
    private final HttpClient http;
    private long nextHealth;
    private long nextHost;
    private HostSampler sampler;
    volatile String lastError;
    private volatile boolean stopped;

    Client(Options o) {
        this.key = first(o.key, env("LOGGERAI_KEY"));
        this.endpoint = first(o.endpoint, env("LOGGERAI_ENDPOINT"), DEFAULT_ENDPOINT);
        this.service = first(o.service, env("LOGGERAI_SERVICE"), "app");
        this.environment = first(o.environment, env("LOGGERAI_ENVIRONMENT"), env("SPRING_PROFILES_ACTIVE"), "production");
        this.release = first(o.release, env("LOGGERAI_RELEASE"));
        boolean on = o.enabled != null ? o.enabled : truthy(first(env("LOGGERAI_ENABLED"), "true"));
        this.enabled = on && key != null && key.startsWith("sk_");
        if (on && key != null && !key.startsWith("sk_")) {
            warn("servers need the secret key (sk_live_...); the public key is for browsers. Not sending.");
        }
        this.hostMetrics = o.hostMetrics != null ? o.hostMetrics : truthy(first(env("LOGGERAI_HOST_METRICS"), "true"));
        this.logsLevel = o.logsLevel;
        this.ignorePaths = List.copyOf(o.ignorePaths);
        String pkgs = env("LOGGERAI_APP_PACKAGES");
        this.appPackages = !o.appPackages.isEmpty() ? List.copyOf(o.appPackages)
                : pkgs != null ? Arrays.stream(pkgs.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList() : List.of();
        this.healthChecks = new ConcurrentHashMap<>(o.healthChecks);
        this.root = first(o.root, System.getProperty("user.dir"), "/");
        this.timeout = o.timeout;
        this.healthIntervalMs = o.healthInterval.toMillis();
        this.hostIntervalMs = Math.max(10_000, o.hostInterval.toMillis());
        this.flushIntervalMs = Math.max(1000, o.flushInterval.toMillis());
        String h;
        try {
            h = first(env("HOSTNAME"), InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            h = "unknown";
        }
        this.host = h;
        for (String k : KINDS) {
            buf.put(k, new ConcurrentLinkedQueue<>());
            sizes.put(k, new AtomicInteger());
        }
        // HTTP/1.1: some servers drop the HTTP/2 upgrade Java attempts on plain http when a body comes with it.
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        this.sender = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "loggerai");
            t.setDaemon(true);
            return t;
        });
        if (enabled) sender.scheduleWithFixedDelay(this::tick, 0, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) v = System.getProperty(name.toLowerCase(Locale.ROOT).replace('_', '.'));
        return v == null || v.isEmpty() ? null : v;
    }

    @SafeVarargs
    static <T> T first(T... values) {
        for (T v : values) if (v != null && !"".equals(v)) return v;
        return null;
    }

    static boolean truthy(String v) {
        return !Set.of("0", "false", "no", "off", "").contains(String.valueOf(v).trim().toLowerCase(Locale.ROOT));
    }

    static void warn(String msg) {
        System.err.println("LoggerAI: " + msg);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String lastError() {
        return lastError;
    }

    // ---- recording ---------------------------------------------------------------------

    boolean ignored(String path) {
        if (path == null) return false;
        for (String p : ignorePaths) {
            if (path.equals(p) || (p.endsWith("/") ? path.startsWith(p) : path.startsWith(p + "/"))) return true;
        }
        return false;
    }

    /** A route name from a raw path when the framework didn't name it: ids become {id}. */
    String fallbackRoute(String rawPath) {
        String p = rawPath == null ? "/" : rawPath.split("[?#]", 2)[0];
        StringBuilder sb = new StringBuilder();
        for (String s : p.split("/")) {
            if (s.isEmpty()) continue;
            sb.append('/').append(ID_SEGMENT.matcher(s).matches() || s.length() > 60 ? "{id}" : s);
        }
        String route = sb.length() == 0 ? "/" : sb.toString();
        if (fallbackRoutes.contains(route)) return route;
        if (fallbackRoutes.size() >= MAX_FALLBACK_ROUTES) return "(other)";
        fallbackRoutes.add(route);
        return route;
    }

    void add(String kind, Map<String, Object> record) {
        if (!enabled) return;
        buf.get(kind).add(record);
        if (sizes.get(kind).incrementAndGet() > MAX_BUFFER && buf.get(kind).poll() != null) sizes.get(kind).decrementAndGet();
    }

    void finish(Unit unit, int status) {
        if (unit.finished) return;
        unit.finished = true;
        add("requests", unit.record(status));
    }

    private final Map<Throwable, Boolean> seen = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    void exception(Throwable t, Integer status, boolean handled, Map<String, Object> extra, String route, String method) {
        if (!enabled || t == null || seen.put(t, Boolean.TRUE) != null) return; // reported once, however many layers see it
        Unit unit = Unit.current();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ts", System.currentTimeMillis() / 1000.0);
        r.put("type", t.getClass().getName());
        String msg = t.getMessage() == null ? "" : t.getMessage();
        r.put("message", Redact.string(msg.length() > 1000 ? msg.substring(0, 1000) : msg));
        r.put("handled", handled);
        r.put("route", route != null ? route : unit != null ? unit.route : null);
        r.put("method", method != null ? method : unit != null ? unit.method : null);
        r.put("status", status);
        r.put("trace_id", unit != null ? unit.traceId : null);
        r.put("frames", Frames.of(t, appPackages));
        r.put("context", extra != null ? Redact.value(extra, 0) : Map.of());
        add("errors", r);
    }

    boolean logs(String level) {
        if (logsLevel == null) return false;
        return LEVELS.getOrDefault(level, 0) >= LEVELS.getOrDefault(logsLevel.toUpperCase(Locale.ROOT), 30);
    }

    void log(String level, String logger, String message) {
        Unit unit = Unit.current();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ts", System.currentTimeMillis() / 1000.0);
        r.put("level", level);
        r.put("logger", logger == null ? "app" : logger.length() > 120 ? logger.substring(logger.length() - 120) : logger);
        String m = message == null ? "" : message;
        r.put("message", Redact.string(m.length() > 8000 ? m.substring(0, 8000) : m));
        r.put("route", unit != null ? unit.route : null);
        r.put("trace_id", unit != null ? unit.traceId : null);
        add("logs", r);
    }

    /** An opaque, stable id for a user: never the id itself, never an email. */
    String userRef(Object id) {
        if (id == null || "".equals(id)) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((key == null ? "" : key).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(String.valueOf(id).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- sending ---------------------------------------------------------------------------

    private synchronized void tick() {
        try {
            runHealth();
            runHost();
            sendAll();
        } catch (Throwable t) { // never let the thread die
            lastError = "send failed: " + t;
        }
    }

    private void runHealth() {
        if (healthChecks.isEmpty() || System.currentTimeMillis() < nextHealth) return;
        nextHealth = System.currentTimeMillis() + healthIntervalMs;
        for (Map.Entry<String, Supplier<Object>> e : healthChecks.entrySet()) {
            long t = System.nanoTime();
            boolean ok;
            String detail = null;
            try {
                Object r = e.getValue().get();
                ok = !Boolean.FALSE.equals(r);
                if (r instanceof String s) detail = s.length() > 255 ? s.substring(0, 255) : s;
            } catch (Throwable x) {
                ok = false;
                String d = x.getClass().getSimpleName() + ": " + x.getMessage();
                detail = d.length() > 255 ? d.substring(0, 255) : d;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ts", System.currentTimeMillis() / 1000.0);
            r.put("name", e.getKey());
            r.put("ok", ok);
            r.put("ms", Math.round((System.nanoTime() - t) / 1e4) / 100.0);
            r.put("detail", detail);
            add("checks", r);
        }
    }

    private void runHost() {
        if (!hostMetrics || System.currentTimeMillis() < nextHost) return;
        nextHost = System.currentTimeMillis() + hostIntervalMs;
        try {
            if (sampler == null) {
                sampler = new HostSampler(root); // primes the CPU counter; the next sample has CPU
                return;
            }
            add("hosts", sampler.sample());
        } catch (Throwable ignored) {
        }
    }

    private Map<String, List<Map<String, Object>>> drain() {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (String k : KINDS) {
            List<Map<String, Object>> list = new ArrayList<>();
            Map<String, Object> r;
            while (list.size() < MAX_PER_BATCH && (r = buf.get(k).poll()) != null) {
                sizes.get(k).decrementAndGet();
                list.add(r);
            }
            if (!list.isEmpty()) out.put(k, list);
        }
        return out;
    }

    private void sendAll() {
        for (int i = 0; i < 20; i++) { // a burst goes out in several batches, then waits for the next tick
            Map<String, List<Map<String, Object>>> parts = drain();
            if (parts.isEmpty() || !enabled) return;
            post(parts);
        }
    }

    private void post(Map<String, List<Map<String, Object>>> parts) {
        Map<String, Object> batch = new LinkedHashMap<>();
        Map<String, Object> sdk = new LinkedHashMap<>();
        sdk.put("name", "loggerai-java");
        sdk.put("version", LoggerAI.VERSION);
        batch.put("sdk", sdk);
        batch.put("service", service);
        batch.put("environment", environment);
        batch.put("release", release);
        batch.put("host", host);
        batch.putAll(parts);
        byte[] body = Json.write(batch).getBytes(StandardCharsets.UTF_8);
        int n = parts.values().stream().mapToInt(List::size).sum();
        if (body.length > MAX_BATCH_BYTES && n > 1) {
            Map<String, List<Map<String, Object>>> a = new LinkedHashMap<>();
            Map<String, List<Map<String, Object>>> b = new LinkedHashMap<>();
            parts.forEach((k, v) -> {
                int mid = v.size() / 2;
                if (mid > 0) a.put(k, v.subList(0, mid));
                if (v.size() > mid) b.put(k, v.subList(mid, v.size()));
            });
            if (!a.isEmpty()) post(a);
            if (!b.isEmpty()) post(b);
            return;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(body.length / 4 + 64);
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(body);
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Content-Encoding", "gzip")
                    .header("X-LoggerAI-Key", key == null ? "" : key)
                    .header("User-Agent", "loggerai-java/" + LoggerAI.VERSION)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray()))
                    .build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                lastError = null;
                return;
            }
            lastError = "LoggerAI answered " + status;
            if (status == 401) {
                enabled = false; // a wrong or revoked key won't start working by retrying
                warn("the key was refused (401). Check LOGGERAI_KEY; sending stopped.");
            } else if (status != 429 && status < 500) {
                warn("batch rejected (" + status + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            lastError = "couldn't reach LoggerAI: " + e.getClass().getSimpleName();
        }
    }

    private boolean empty() {
        for (String k : KINDS) if (!buf.get(k).isEmpty()) return false;
        return true;
    }

    /** Sends everything recorded so far. True when the buffer is empty. */
    boolean flush(Duration wait) {
        if (!enabled) return true;
        long deadline = System.nanoTime() + wait.toNanos();
        // At least one pass: it also waits for a batch the sender is already sending.
        for (int pass = 0; pass == 0 || (!empty() && System.nanoTime() < deadline); pass++) {
            try {
                Future<?> f = stopped ? null : sender.submit(this::tick);
                if (f == null) tick();
                else f.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (Exception e) {
                break;
            }
        }
        return empty();
    }

    /** Runs health checks and samples the server now (for a quick check from the command line). */
    void runChecksNow() {
        nextHealth = 0;
        runHealth();
    }

    void close(Duration wait) {
        flush(wait);
        stopped = true;
        sender.shutdownNow();
    }
}
