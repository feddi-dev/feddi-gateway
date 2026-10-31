package dev.feddi.federation.app;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Experiment: the arrival and written times of traced requests ({@link RequestTrace}). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!RequestTrace.ENABLED) {
            return chain.filter(exchange);
        }
        exchange.getAttributes().put(RequestTrace.ARRIVED, System.nanoTime());
        return chain.filter(exchange).doFinally(signal -> {
            RequestTrace trace = exchange.getAttribute(RequestTrace.TRACE);
            if (trace != null) {
                trace.written(System.nanoTime());
            }
        });
    }
}
