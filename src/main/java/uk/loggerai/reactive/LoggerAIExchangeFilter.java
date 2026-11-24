package uk.loggerai.reactive;

import java.util.Map;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import uk.loggerai.Hooks;
import uk.loggerai.Unit;

/** Times calls made with WebClient, by host, for the request that made them, and carries the
 * trace to the project's own services. Spring Boot adds it to every WebClient.Builder on its own. */
public class LoggerAIExchangeFilter implements ExchangeFilterFunction {
    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.deferContextual(ctx -> {
            Unit unit = ctx.getOrDefault(ReactiveLoggerAI.KEY, null);
            if (unit == null) unit = Unit.current(); // WebClient used from a servlet app
            if (unit == null) return next.exchange(request);
            Unit u = unit;
            String host = request.url().getHost() == null ? "unknown" : request.url().getHost();
            String method = request.method().name();
            String sid = Hooks.newSpanId();
            ClientRequest out = request;
            Map<String, String> carry = request.headers().containsKey("traceparent") ? Map.of() : Hooks.carry(u, host, sid);
            if (!carry.isEmpty()) {
                out = ClientRequest.from(request).headers(h -> carry.forEach((k, v) -> {
                    if (!h.containsKey(k)) h.set(k, v);
                })).build();
            }
            long t = System.nanoTime();
            return next.exchange(out)
                    .doOnSuccess(r -> Hooks.call(u, host, method, (System.nanoTime() - t) / 1e6, r == null || r.statusCode().value() < 500, sid,
                            r == null ? 0 : r.statusCode().value()))
                    .doOnError(e -> Hooks.call(u, host, method, (System.nanoTime() - t) / 1e6, false, sid, 0));
        });
    }
}
