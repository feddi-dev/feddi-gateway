package e2e.inventory;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stock per product. {@code inventoryRequests} reports how many GraphQL requests this subgraph
 * has received, so tests can check how feddi batches lookups (it is not part of the schema
 * uploaded to feddi).
 */
@Controller
public class InventoryController implements WebGraphQlInterceptor {

    /** A product's stock. */
    public record Product(String id, int stock, String warehouse) {
    }

    private static final Map<String, Product> PRODUCTS = Map.of(
        "1", new Product("1", 12, "Berlin"),
        "2", new Product("2", 0, "Hamburg"),
        "3", new Product("3", 3, "Berlin"));

    private final AtomicInteger requests = new AtomicInteger();

    @QueryMapping
    public Product productById(@Argument String id) {
        return PRODUCTS.get(id);
    }

    @QueryMapping
    public int inventoryRequests() {
        return requests.get();
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        if (!request.getDocument().contains("inventoryRequests")) {
            requests.incrementAndGet();
        }
        return chain.next(request);
    }
}
