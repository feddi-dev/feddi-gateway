package dev.feddi.federation.app;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that PayloadTooLargeHandler correctly converts DataBufferLimitException to HTTP 413.
 */
class PayloadTooLargeHandlerTest {

    private final PayloadTooLargeHandler handler = new PayloadTooLargeHandler();

    @Test
    void dataBufferLimitExceptionReturns413() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/graphql").build());

        handler.handle(exchange, new DataBufferLimitException("Exceeded limit on max bytes to buffer: 1024"))
                .block();

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.getResponse().getStatusCode());
    }

    @Test
    void wrappedDataBufferLimitExceptionReturns413() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/graphql").build());

        var wrapped = new RuntimeException("request failed",
                new DataBufferLimitException("Exceeded limit"));

        handler.handle(exchange, wrapped).block();

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.getResponse().getStatusCode());
    }

    @Test
    void otherExceptionPropagates() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/graphql").build());

        try {
            handler.handle(exchange, new RuntimeException("some other error")).block();
        } catch (RuntimeException e) {
            assertEquals("some other error", e.getMessage());
            return;
        }
        // Should have thrown
        org.junit.jupiter.api.Assertions.fail("Expected exception to propagate");
    }
}
