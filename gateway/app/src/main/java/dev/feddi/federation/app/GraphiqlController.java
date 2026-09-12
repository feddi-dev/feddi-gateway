package dev.feddi.federation.app;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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

    private static final String INTROSPECTION_DISABLED_PAGE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>GraphiQL — introspection disabled</title>
            </head>
            <body style="font-family: sans-serif; max-width: 40rem; margin: 4rem auto; line-height: 1.5;">
              <h1>GraphiQL is enabled, but introspection is not</h1>
              <p>GraphiQL needs GraphQL introspection to load the schema and power its
                 explorer, but <code>enable-introspection</code> is set to <code>false</code>
                 in <code>feddi-gateway.yml</code>.</p>
              <p>Set <code>enable-introspection: true</code> to use GraphiQL.</p>
            </body>
            </html>
            """;

    private static final String GRAPHIQL_PAGE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>feddi Gateway — GraphiQL</title>
              <style>
                body { margin: 0; }
                #graphiql { height: 100vh; }
              </style>
              <script src="https://unpkg.com/react@18/umd/react.production.min.js" crossorigin></script>
              <script src="https://unpkg.com/react-dom@18/umd/react-dom.production.min.js" crossorigin></script>
              <link rel="stylesheet" href="https://unpkg.com/graphiql/graphiql.min.css" />
            </head>
            <body>
              <div id="graphiql">Loading GraphiQL...</div>
              <script src="https://unpkg.com/graphiql/graphiql.min.js" crossorigin></script>
              <script>
                const fetcher = GraphiQL.createFetcher({ url: '/graphql' });
                ReactDOM.render(
                  React.createElement(GraphiQL, { fetcher: fetcher }),
                  document.getElementById('graphiql'),
                );
              </script>
            </body>
            </html>
            """;
}
