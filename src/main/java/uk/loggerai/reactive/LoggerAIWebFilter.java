package uk.loggerai.reactive;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import uk.loggerai.Hooks;
import uk.loggerai.LoggerAI;
import uk.loggerai.Unit;

/**
 * Spring WebFlux: times every request under its route pattern and reports
 * the errors that fail it. Spring Boot registers it on its own.
 */
public class LoggerAIWebFilter implements WebFilter, Ordered {
    /** WebFlux's matched pattern (a PathPattern; its text is /orders/{id}). */
    static final String PATTERN = "org.springframework.web.reactive.HandlerMapping.bestMatchingPattern";
    /** Where Spring Boot keeps an exception a handler turned into an error response. */
    static final String BOOT_ERROR = "org.springframework.boot.web.reactive.error.DefaultErrorAttributes.ERROR";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String path = req.getPath().pathWithinApplication().value();
        if (!LoggerAI.enabled() || Hooks.ignored(path)) return chain.filter(exchange);
        Unit unit = Hooks.begin(req.getMethod().name(), req.getHeaders().getFirst("traceparent"), req.getHeaders().getFirst("x-loggerai-session"));
        exchange.getAttributes().put(ReactiveLoggerAI.KEY, unit);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        return chain.filter(exchange)
                .doOnError(e -> {
                    failure.set(e);
                    // Now, before Spring's error handler logs it, so it keeps its route and status.
                    int status = statusOf(e);
                    if (status >= 500) Hooks.error(unit, route(exchange, path), status, e);
                })
                .doFinally(signal -> finish(exchange, unit, path, signal, failure.get()))
                .contextWrite(ctx -> ctx.put(ReactiveLoggerAI.KEY, unit));
    }

    private static String route(ServerWebExchange exchange, String path) {
        Object pattern = exchange.getAttribute(PATTERN);
        return pattern != null ? pattern.toString() : Hooks.fallbackRoute(path);
    }

    private static int statusOf(Throwable e) {
        return e instanceof ResponseStatusException rse ? rse.getStatusCode().value() : 500;
    }

    private static void finish(ServerWebExchange exchange, Unit unit, String path, SignalType signal, Throwable failure) {
        String route = route(exchange, path);
        HttpStatusCode code = exchange.getResponse().getStatusCode();
        int status = code != null ? code.value() : 200;
        if (failure != null) {
            // The error handler hasn't written the response yet: its status is what the client gets.
            status = statusOf(failure);
            failure = null; // reported when it went past (or a 4xx thrown on purpose)
        } else if (signal == SignalType.CANCEL) {
            status = code != null ? code.value() : 499; // the client went away
        } else if (status >= 500) {
            Object e = exchange.getAttribute(BOOT_ERROR);
            if (e instanceof Throwable t) failure = t;
        }
        Hooks.end(unit, route, status, failure);
    }
}
