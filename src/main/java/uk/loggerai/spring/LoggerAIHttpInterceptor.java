package uk.loggerai.spring;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import uk.loggerai.Hooks;

/** Times calls made with RestTemplate and RestClient, by host. Spring Boot adds it on its own. */
public class LoggerAIHttpInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        long t = System.nanoTime();
        boolean ok = false;
        try {
            ClientHttpResponse r = execution.execute(request, body);
            ok = r.getStatusCode().value() < 500;
            return r;
        } finally {
            String host = request.getURI().getHost();
            Hooks.dependency(host == null ? "unknown" : host, request.getMethod().name(), (System.nanoTime() - t) / 1e6, ok);
        }
    }
}
