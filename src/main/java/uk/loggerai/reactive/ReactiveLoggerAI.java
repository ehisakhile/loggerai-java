package uk.loggerai.reactive;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uk.loggerai.Hooks;
import uk.loggerai.Unit;

/**
 * LoggerAI in reactive code (Spring WebFlux), where a request hops between
 * threads: the request travels in the Reactor context instead.
 *
 * <pre>
 *   return ReactiveLoggerAI.measure("api.stripe.com", "POST", stripe.confirm(id));
 *   return ReactiveLoggerAI.setUser(user.getId()).then(orders.find(id));
 * </pre>
 */
public final class ReactiveLoggerAI {
    /** Where the request is kept: in the Reactor context and in the exchange's attributes. */
    public static final String KEY = "uk.loggerai.unit";

    private ReactiveLoggerAI() {}

    public static Unit unitOf(ServerWebExchange exchange) {
        Object u = exchange.getAttribute(KEY);
        return u instanceof Unit unit ? unit : null;
    }

    /** Time a call LoggerAI can't see on its own (an SDK, R2DBC, MongoDB reactive). */
    public static <T> Mono<T> measure(String target, String operation, Mono<T> call) {
        return Mono.deferContextual(ctx -> {
            Unit unit = ctx.getOrDefault(KEY, null);
            if (unit == null) return call;
            long[] t = new long[1];
            return call.doOnSubscribe(s -> t[0] = System.nanoTime())
                    .doOnSuccess(v -> Hooks.dependency(unit, target, operation, (System.nanoTime() - t[0]) / 1e6, true))
                    .doOnError(e -> Hooks.dependency(unit, target, operation, (System.nanoTime() - t[0]) / 1e6, false));
        });
    }

    public static <T> Flux<T> measure(String target, String operation, Flux<T> call) {
        return Flux.deferContextual(ctx -> {
            Unit unit = ctx.getOrDefault(KEY, null);
            if (unit == null) return call;
            long[] t = new long[1];
            return call.doOnSubscribe(s -> t[0] = System.nanoTime())
                    .doOnComplete(() -> Hooks.dependency(unit, target, operation, (System.nanoTime() - t[0]) / 1e6, true))
                    .doOnError(e -> Hooks.dependency(unit, target, operation, (System.nanoTime() - t[0]) / 1e6, false));
        });
    }

    /** Count the current request against a user. Only a one-way hash of the id is sent. */
    public static Mono<Void> setUser(Object id) {
        return Mono.deferContextual(ctx -> {
            Hooks.setUser(ctx.getOrDefault(KEY, null), id);
            return Mono.empty();
        });
    }

    /** Name the current request yourself (a template like /orders/{id}). */
    public static Mono<Void> setRoute(String template) {
        return Mono.deferContextual(ctx -> {
            Hooks.setRoute(ctx.getOrDefault(KEY, null), template);
            return Mono.empty();
        });
    }
}
