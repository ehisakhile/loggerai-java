package uk.loggerai.reactive;

import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import uk.loggerai.Hooks;
import uk.loggerai.Unit;

/** Times calls made with WebClient, by host, for the request that made them.
 * Spring Boot adds it to every WebClient.Builder on its own. */
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
            long t = System.nanoTime();
            return next.exchange(request)
                    .doOnSuccess(r -> Hooks.dependency(u, host, method, (System.nanoTime() - t) / 1e6, r == null || r.statusCode().value() < 500))
                    .doOnError(e -> Hooks.dependency(u, host, method, (System.nanoTime() - t) / 1e6, false));
        });
    }
}
