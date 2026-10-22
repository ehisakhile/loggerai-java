package uk.loggerai.servlet;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import uk.loggerai.Hooks;
import uk.loggerai.LoggerAI;
import uk.loggerai.Unit;

/**
 * Times every request under its route template. Spring Boot registers it on
 * its own; in any other servlet app (Jakarta Servlet 5+), register it for
 * every path, first in the chain.
 */
public class LoggerAIFilter implements Filter {
    /** Spring MVC's matched pattern (/orders/{id}), read by name so Spring isn't needed. */
    static final String SPRING_PATTERN = "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";
    /** Where Spring Boot keeps an exception a handler turned into an error response. */
    static final String SPRING_ERROR = "org.springframework.boot.web.servlet.error.DefaultErrorAttributes.ERROR";
    static final String SERVLET_ERROR = "jakarta.servlet.error.exception";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse res) || !LoggerAI.enabled()
                || Unit.current() != null || Hooks.ignored(path(req))) {
            chain.doFilter(request, response);
            return;
        }
        Unit unit = Hooks.start(req.getMethod(), req.getHeader("traceparent"), req.getHeader("x-loggerai-session"));
        Throwable failure = null;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            failure = e instanceof ServletException se && se.getRootCause() != null ? se.getRootCause() : e;
            throw e;
        } finally {
            if (failure == null && req.isAsyncStarted()) {
                // Answered later on another thread (DeferredResult, CompletableFuture): finish then.
                Hooks.detach();
                req.getAsyncContext().addListener(new AsyncListener() {
                    @Override public void onComplete(AsyncEvent e) { finish(req, res, unit, null); }
                    @Override public void onError(AsyncEvent e) { finish(req, res, unit, e.getThrowable()); }
                    @Override public void onTimeout(AsyncEvent e) {}
                    @Override public void onStartAsync(AsyncEvent e) {}
                });
            } else {
                finish(req, res, unit, failure);
            }
        }
    }

    private static void finish(HttpServletRequest req, HttpServletResponse res, Unit unit, Throwable failure) {
        Object pattern = req.getAttribute(SPRING_PATTERN);
        String route = pattern instanceof String s ? s : Hooks.fallbackRoute(path(req));
        int status = failure != null && res.getStatus() < 500 ? 500 : res.getStatus();
        if (failure == null && status >= 500) {
            Object e = req.getAttribute(SPRING_ERROR);
            if (!(e instanceof Throwable)) e = req.getAttribute(SERVLET_ERROR);
            if (e instanceof Throwable t) failure = t;
        }
        Hooks.end(unit, route, status, failure);
    }

    private static String path(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }
}
