package dev.feddi.federation.app;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphiqlControllerTest {

    @Test
    void notFoundWhenDisabledByDefault() {
        GraphiqlController controller = new GraphiqlController(new FeddiGatewayConfigFile());

        ResponseEntity<String> response = controller.graphiql();

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void explainsWhenIntrospectionDisabled() {
        FeddiGatewayConfigFile config = new FeddiGatewayConfigFile();
        config.setEnableGraphiql(true);
        config.setEnableIntrospection(false);

        ResponseEntity<String> response = new GraphiqlController(config).graphiql();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("introspection is not"));
        assertTrue(response.getBody().contains("enable-introspection: true"));
    }

    @Test
    void servesGraphiqlWhenEnabledWithIntrospection() {
        FeddiGatewayConfigFile config = new FeddiGatewayConfigFile();
        config.setEnableGraphiql(true);
        config.setEnableIntrospection(true);

        ResponseEntity<String> response = new GraphiqlController(config).graphiql();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("GraphiQL.createFetcher"));
        assertTrue(response.getBody().contains("url: '/graphql'"));
    }
}
