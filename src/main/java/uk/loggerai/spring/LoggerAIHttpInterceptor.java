package uk.loggerai.spring;

import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import uk.loggerai.Hooks;
import uk.loggerai.Unit;

/** Times calls made with RestTemplate and RestClient, by host, and carries the trace to the
 * project's own services (traceparent, x-loggerai-caller). Spring Boot adds it on its own. */
public class LoggerAIHttpInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        Unit unit = Unit.current();
        String host = request.getURI().getHost() == null ? "unknown" : request.getURI().getHost();
        String sid = Hooks.newSpanId();
        if (unit != null && !request.getHeaders().containsKey("traceparent")) {
            for (Map.Entry<String, String> e : Hooks.carry(unit, host, sid).entrySet()) {
                if (!request.getHeaders().containsKey(e.getKey())) request.getHeaders().set(e.getKey(), e.getValue());
            }
        }
        long t = System.nanoTime();
        boolean ok = false;
        int status = 0;
        try {
            ClientHttpResponse r = execution.execute(request, body);
            status = r.getStatusCode().value();
            ok = status < 500;
            return r;
        } finally {
            double ms = (System.nanoTime() - t) / 1e6;
            if (unit != null) Hooks.call(unit, host, request.getMethod().name(), ms, ok, sid, status);
            else Hooks.dependency(host, request.getMethod().name(), ms, ok);
        }
    }
}
