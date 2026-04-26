package dev.feddi.federation.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Converts {@link DataBufferLimitException} to HTTP 413 (Payload Too Large).
 *
 * <p>Spring WebFlux throws this when the request body exceeds
 * {@code spring.codec.max-in-memory-size}. By default this results in HTTP 500.
 * This handler intercepts it and returns a proper 413 response with a JSON error body.
 */
@Component
@Order(-2) // Before the default Spring error handler
public class PayloadTooLargeHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PayloadTooLargeHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (isPayloadTooLarge(ex)) {
            log.warn("Request rejected: payload too large ({})", ex.getMessage());

            exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

            byte[] body = "{\"errors\":[{\"message\":\"Request payload too large\"}]}"
                    .getBytes(StandardCharsets.UTF_8);

            var buffer = exchange.getResponse().bufferFactory().wrap(body);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        }

        // Not our exception — let other handlers deal with it
        return Mono.error(ex);
    }

    private boolean isPayloadTooLarge(Throwable ex) {
        if (ex instanceof DataBufferLimitException) {
            return true;
        }
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof DataBufferLimitException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
