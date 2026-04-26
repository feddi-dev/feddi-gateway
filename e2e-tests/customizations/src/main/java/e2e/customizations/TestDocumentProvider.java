package e2e.customizations;

import dev.feddi.federation.customization.DocumentProvider;
import dev.feddi.federation.customization.GatewayRequestContext;
import graphql.ExecutionInput;
import graphql.GraphqlErrorBuilder;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import graphql.language.Document;
import graphql.parser.Parser;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test DocumentProvider that implements a simple persisted-query protocol.
 * Stores a known query by its SHA-256 hash and resolves it on demand.
 * Falls through to normal parsing (Mono.empty()) when no persisted query extension is present.
 */
public class TestDocumentProvider implements DocumentProvider {

    private final Map<String, Document> cache = new ConcurrentHashMap<>();

    /** The known query cached by this provider. */
    public static final String KNOWN_QUERY = "{ products { id } }";

    /** SHA-256 hash of {@link #KNOWN_QUERY}. */
    public static final String KNOWN_HASH;

    static {
        KNOWN_HASH = sha256Hex(KNOWN_QUERY);
    }

    public TestDocumentProvider() {
        cache.put(KNOWN_HASH, Parser.parse(KNOWN_QUERY));
    }

    @Override
    public Mono<PreparsedDocumentEntry> getDocument(ExecutionInput executionInput, GatewayRequestContext context) {
        var ext = executionInput.getExtensions();
        if (ext != null && ext.get("persistedQuery") instanceof Map<?, ?> pq) {
            if (pq.get("sha256Hash") instanceof String hash) {
                var doc = cache.get(hash);
                if (doc != null) {
                    return Mono.just(new PreparsedDocumentEntry(doc));
                }
                return Mono.just(new PreparsedDocumentEntry(
                    List.of(GraphqlErrorBuilder.newError().message("PersistedQueryNotFound").build())));
            }
        }
        return Mono.empty();
    }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
