package dev.feddi.federation.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTTP behaviour of {@code POST /graphql} before a definition is uploaded: content type, body reading and errors.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // A context of its own (no definition uploaded by other tests)
    properties = {"server.address=127.0.0.1", "feddi.test.context=graphql-endpoint"}
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphQLEndpointIntegrationTest {

    @LocalServerPort
    private int gatewayPort;

    private WebClient client;

    @BeforeAll
    void setup() {
        // A connection per request: the server closes the connection after rejecting an oversized body
        client = WebClient.builder().baseUrl("http://localhost:" + gatewayPort)
            .clientConnector(new ReactorClientHttpConnector(HttpClient.newConnection()))
            .build();
    }

    @Test
    void rejectsOtherContentTypes() {
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), post(MediaType.TEXT_PLAIN, "{ topProducts { upc } }"));
    }

    @Test
    void rejectsInvalidJson() {
        assertEquals(HttpStatus.BAD_REQUEST.value(), post(MediaType.APPLICATION_JSON, "{\"query\":"));
    }

    @Test
    void rejectsAMissingBody() {
        assertEquals(HttpStatus.BAD_REQUEST.value(), post(MediaType.APPLICATION_JSON, ""));
    }

    @Test
    void readsARequestWithUnknownFieldsAndAnswersUnavailableBeforeTheUpload() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(),
            post(MediaType.APPLICATION_JSON, "{\"query\":\"{ a }\",\"variables\":{\"x\":1},\"other\":true}"));
    }

    @Test
    void rejectsABodyOverTheSizeLimit() {
        // Netty logs a buffer released twice (and closes the connection) when the body fails while the response
        // is being written
        Logger netty = (Logger) LoggerFactory.getLogger("reactor.netty");
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        netty.addAppender(events);
        try {
            String query = "{\"query\":\"" + "a".repeat(3 * 1024 * 1024) + "\"}";
            assertEquals(HttpStatus.CONTENT_TOO_LARGE.value(), post(MediaType.APPLICATION_JSON, query));
        } finally {
            netty.detachAppender(events);
        }
        assertEquals(List.of(), events.list.stream().filter(event -> event.getLevel() == Level.ERROR)
            .map(ILoggingEvent::getFormattedMessage).toList());
    }

    private int post(MediaType contentType, String body) {
        return client.post().uri("/graphql").contentType(contentType).bodyValue(body)
            .exchangeToMono(response -> response.releaseBody()
                .thenReturn(response.statusCode().value()))
            .block(Duration.ofSeconds(10));
    }
}
