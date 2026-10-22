package uk.loggerai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import uk.loggerai.jdbc.LoggerAIDataSource;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SdkTest {
    static HttpServer ingest;
    static final List<String> bodies = new CopyOnWriteArrayList<>();
    static final List<String> keys = new CopyOnWriteArrayList<>();
    static volatile int answer = 202;
    static Client client;

    @BeforeAll
    static void start() throws Exception {
        // A stand-in for LoggerAI's ingest: keeps every batch it's sent.
        ingest = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ingest.createContext("/ingest/batch", ex -> {
            try (GZIPInputStream in = new GZIPInputStream(ex.getRequestBody())) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                bodies.add(out.toString(StandardCharsets.UTF_8));
                keys.add(ex.getRequestHeaders().getFirst("X-LoggerAI-Key"));
            }
            ex.sendResponseHeaders(answer, -1);
            ex.close();
        });
        ingest.start();
        client = LoggerAI.init(new Options()
                .key("sk_live_test")
                .endpoint("http://127.0.0.1:" + ingest.getAddress().getPort() + "/ingest/batch")
                .service("api")
                .release("v1")
                .flushInterval(Duration.ofMinutes(5))
                .appPackages(List.of("uk.loggerai.SdkTest"))
                .healthCheck("database", () -> true)
                .healthCheck("queue", () -> {
                    throw new IllegalStateException("backlog 900");
                }));
    }

    @AfterAll
    static void stop() {
        ingest.stop(0);
    }

    static String sent() {
        assertTrue(LoggerAI.flush(Duration.ofSeconds(5)));
        return String.join("\n", bodies);
    }

    @Test
    @Order(1)
    void aJobWithItsCallsAndUser() throws Exception {
        String r = LoggerAI.track("job", "SendInvoices", () -> {
            LoggerAI.setUser(42);
            LoggerAI.measure("api.stripe.com", "post", () -> {
                Thread.sleep(5);
                return null;
            });
            return "done";
        });
        assertEquals("done", r);
        String all = sent();
        assertTrue(all.contains("\"route\":\"SendInvoices\""), all);
        assertTrue(all.contains("\"kind\":\"job\",\"method\":\"JOB\""), all);
        assertTrue(all.contains("\"target\":\"api.stripe.com\",\"op\":\"POST\",\"count\":1"), all);
        assertTrue(all.contains("\"sdk\":{\"name\":\"loggerai-java\""), all);
        assertTrue(all.contains("\"release\":\"v1\""), all);
        assertFalse(all.contains("\"user\":\"42\""), "user ids are hashed");
        assertEquals("sk_live_test", keys.get(0));
        assertTrue(all.contains("\"name\":\"database\",\"ok\":true"), all);
        assertTrue(all.contains("\"name\":\"queue\",\"ok\":false"), all);
        assertTrue(all.contains("backlog 900"), all);
    }

    @Test
    @Order(2)
    void errorsWithFramesAndRedaction() {
        assertThrows(IllegalStateException.class, () -> LoggerAI.track("job", "Broken", () -> {
            throw new IllegalStateException("token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U is bad");
        }));
        String all = sent();
        assertTrue(all.contains("\"type\":\"java.lang.IllegalStateException\""), all);
        assertTrue(all.contains("[REDACTED_JWT]") && !all.contains("eyJzdWIi"), all);
        assertTrue(all.contains("\"file\":\"uk/loggerai/SdkTest.java\""), all);
        assertTrue(all.contains("\"class\":\"uk.loggerai.SdkTest\",\"in_app\":true"), all);
        assertTrue(all.contains("\"route\":\"Broken\",\"status\":500"), all);
    }

    @Test
    @Order(3)
    void jdbcQueriesAreTimed() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:t;DB_CLOSE_DELAY=-1");
        DataSource ds = LoggerAIDataSource.wrap(h2);
        assertTrue(ds.isWrapperFor(JdbcDataSource.class));
        assertEquals(h2, ds.unwrap(JdbcDataSource.class));
        LoggerAI.track("job", "Report", () -> {
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE t (id INT)");
                try (PreparedStatement p = c.prepareStatement("INSERT INTO t VALUES (?)")) {
                    p.setInt(1, 1);
                    p.executeUpdate();
                }
                s.executeQuery("SELECT * FROM t").close();
                assertThrows(Exception.class, () -> s.executeQuery("SELECT * FROM missing"));
            }
            return null;
        });
        String all = sent();
        assertTrue(all.contains("\"target\":\"h2\",\"op\":\"CREATE\",\"count\":1"), all);
        assertTrue(all.contains("\"target\":\"h2\",\"op\":\"INSERT\",\"count\":1"), all);
        assertTrue(all.contains("\"target\":\"h2\",\"op\":\"SELECT\",\"count\":2") && all.contains("\"errors\":1"), all);
    }

    @Test
    @Order(4)
    void redaction() {
        assertTrue(Redact.isSensitive("accessToken"));
        assertTrue(Redact.isSensitive("X-Api-Key"));
        assertFalse(Redact.isSensitive("page"));
        String u = Redact.url("https://user:pw@a.b/c?token=1&page=2&key=k");
        assertTrue(u.contains("page=2") && !u.contains("token=1") && !u.contains("key=k") && !u.contains("pw"), u);
        assertEquals("Authorization: Bearer [REDACTED]", Redact.string("Authorization: Bearer abcdefghijklmnop1234"));
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Redact.value(Map.of("password", "x", "nested", Map.of("card_number", "4242", "ok", "y")), 0);
        assertEquals("[REDACTED]", m.get("password"));
        assertEquals("[REDACTED]", ((Map<?, ?>) m.get("nested")).get("card_number"));
    }

    @Test
    @Order(5)
    void routesFromPaths() {
        assertEquals("/orders/{id}/items/{id}", client.fallbackRoute("/orders/1842/items/3f9c2a1b7d4e?x=1"));
        assertEquals("/users/me", client.fallbackRoute("/users/me"));
        assertTrue(client.ignored("/actuator/health"));
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", Unit.traceFrom("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
    }

    @Test
    @Order(6)
    void serverMetrics() throws Exception {
        HostSampler s = new HostSampler(".");
        Thread.sleep(300);
        Map<String, Object> m = s.sample();
        assertNotNull(m.get("mem_used_pct"), m.toString());
        assertNotNull(m.get("disk_used_pct"), m.toString());
        assertNotNull(m.get("process_mem_mb"), m.toString());
        double cpu = m.containsKey("cpu_pct") ? (double) m.get("cpu_pct") : 0;
        assertTrue(cpu >= 0 && cpu <= 100);
    }

    @Test
    @Order(7)
    void aRefusedKeyStopsSending() {
        answer = 401;
        LoggerAI.log("error", "one more");
        LoggerAI.flush(Duration.ofSeconds(3));
        answer = 202;
        assertFalse(client.isEnabled());
    }
}
