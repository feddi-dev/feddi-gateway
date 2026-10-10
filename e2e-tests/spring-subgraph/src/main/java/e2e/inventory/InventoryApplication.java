package e2e.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A plain Spring for GraphQL subgraph: one request carries one query with one variables
 * object, as in the GraphQL-over-HTTP spec. It supports neither variable nor request batching.
 */
@SpringBootApplication
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
