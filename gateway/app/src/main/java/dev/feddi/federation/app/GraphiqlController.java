package dev.feddi.federation.app;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Serves a GraphiQL UI at /graphiql, opt-in via {@code enable-graphiql: true}
 * in feddi-gateway.yml. Disabled by default — intended for local development
 * and demos, not production.
 *
 * <p>GraphiQL needs introspection to load the schema. If it is enabled while
 * {@code enable-introspection} is false, this returns an explanatory page
 * instead of an empty schema explorer.
 */
@RestController
public class GraphiqlController {

    // Not under resources/static/ — that would make graphiql/index.html
    // reachable directly regardless of enable-graphiql, bypassing the checks below.
    private static final String GRAPHIQL_PAGE = readResource("graphiql/index.html");
    private static final String INTROSPECTION_DISABLED_PAGE = readResource("graphiql/introspection-disabled.html");

    private final FeddiGatewayConfigFile config;

    public GraphiqlController(FeddiGatewayConfigFile config) {
        this.config = config;
    }

    @GetMapping(value = "/graphiql", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> graphiql() {
        if (!config.isGraphiqlEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!config.isIntrospectionEnabled()) {
            return ResponseEntity.ok(INTROSPECTION_DISABLED_PAGE);
        }
        return ResponseEntity.ok(GRAPHIQL_PAGE);
    }

    private static String readResource(String classpathLocation) {
        try {
            return new ClassPathResource(classpathLocation).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + classpathLocation, e);
        }
    }
}
