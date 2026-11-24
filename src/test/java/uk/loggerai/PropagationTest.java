package uk.loggerai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import uk.loggerai.spring.LoggerAIHttpInterceptor;

/** Microservices: the trace and the caller's name travel to the project's own services. */
class PropagationTest {
    static HttpServer orders;
    static final List<Map<String, String>> seen = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        LoggerAI.init(new Options().key("sk_live_test").endpoint("http://127.0.0.1:9/ingest/batch").service("payments").hostMetrics(false));
        orders = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        orders.createContext("/", ex -> {
            seen.add(Map.of("traceparent", String.valueOf(ex.getRequestHeaders().getFirst("traceparent")),
                    "caller", String.valueOf(ex.getRequestHeaders().getFirst("x-loggerai-caller"))));
            ex.sendResponseHeaders(ex.getRequestURI().getPath().contains("fail") ? 503 : 200, -1);
            ex.close();
        });
        orders.start();
    }

    @AfterAll
    static void stop() {
        orders.stop(0);
    }

    @Test
    void anIncomingRequestContinuesTheTrace() {
        Unit u = Hooks.start("GET", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", null, "gateway");
        try {
            Map<String, Object> r = u.record(200);
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", r.get("trace_id"));
            assertEquals("00f067aa0ba902b7", r.get("parent_span_id"));
            assertEquals("gateway", r.get("caller"));
            assertTrue(((String) r.get("span_id")).matches("[0-9a-f]{16}"));
        } finally {
            Hooks.detach();
        }
        assertNull(Unit.parentFrom("00-00000000000000000000000000000000-00f067aa0ba902b7-01"));
        assertNull(Unit.callerFrom("<img src=x>"));
    }

    @Test
    void restTemplateCarriesTheTraceToInternalServicesOnly() {
        seen.clear();
        RestTemplate rest = new RestTemplate();
        rest.getInterceptors().add(new LoggerAIHttpInterceptor());
        rest.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
        });
        Unit u = Hooks.start("POST", null, null, null);
        try {
            rest.getForObject("http://127.0.0.1:" + orders.getAddress().getPort() + "/ledger", String.class);
            rest.getForObject("http://localhost:" + orders.getAddress().getPort() + "/fail", String.class);
        } finally {
            Hooks.detach();
        }
        Map<String, Object> r = u.record(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) r.get("spans");
        assertEquals(2, seen.size());
        for (Map<String, String> h : seen) {
            String[] p = Unit.parentFrom(h.get("traceparent"));
            assertEquals(u.traceId, p[0]);
            assertTrue(spans.stream().anyMatch(s -> p[1].equals(s.get("span_id"))));
            assertEquals("payments", h.get("caller"));
        }
        assertTrue(spans.stream().anyMatch(s -> Integer.valueOf(503).equals(s.get("status")) && Boolean.FALSE.equals(s.get("ok"))));
        // Outside APIs get nothing.
        assertTrue(Hooks.carry(u, "api.stripe.com", "1234567890abcdef").isEmpty());
        assertFalse(Hooks.carry(u, "orders.default.svc.cluster.local", "1234567890abcdef").isEmpty());
    }

    @Test
    void queuesCarryTheTraceInMessageHeaders() throws Exception {
        Unit u = Hooks.start("POST", null, null, null);
        Map<String, String> headers;
        try {
            headers = LoggerAI.traceHeaders("orders.created");
        } finally {
            Hooks.detach();
        }
        assertEquals("payments", headers.get("x-loggerai-caller"));
        Unit[] job = new Unit[1];
        LoggerAI.track("job", "order.created", Map.of("Traceparent", headers.get("traceparent"), "X-LoggerAI-Caller", "checkout"), () -> {
            job[0] = Unit.current();
            return null;
        });
        Map<String, Object> r = job[0].record(200);
        assertEquals(u.traceId, r.get("trace_id"));
        assertEquals(Unit.parentFrom(headers.get("traceparent"))[1], r.get("parent_span_id"));
        assertEquals("checkout", r.get("caller"));
        assertEquals("queue", r.get("transport"));
    }

    @Test
    void whichHostsGetTheTrace() {
        var rule = Propagation.rule(null);
        for (String h : List.of("orders", "payments.default.svc.cluster.local", "billing.internal", "10.1.2.3", "172.20.0.5", "localhost")) assertTrue(rule.test(h), h);
        for (String h : List.of("api.stripe.com", "8.8.8.8", "")) assertFalse(rule.test(h), h);
        var listed = Propagation.rule("*.acme.io");
        assertTrue(listed.test("pay.acme.io"));
        assertFalse(listed.test("acme.io.evil.com"));
        assertTrue(Propagation.rule("all").test("api.stripe.com"));
        assertFalse(Propagation.rule("none").test("orders"));
    }
}
