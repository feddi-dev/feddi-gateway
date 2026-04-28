package dev.feddi.federation.customization;

import graphql.ExecutionInput;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import reactor.core.publisher.Mono;

/**
 * Extension point for providing pre-parsed, pre-validated documents.
 * If registered, the feddi Gateway calls this before ParseAndValidate.
 *
 * <p>Return a Mono with a PreparsedDocumentEntry:
 * <ul>
 *   <li>With a Document: feddi Gateway skips parsing and validation, uses this document</li>
 *   <li>With errors: feddi Gateway returns these errors to the client</li>
 * </ul>
 * <p>Return Mono.empty() to fall through to normal ParseAndValidate.
 */
public interface DocumentProvider {
    Mono<PreparsedDocumentEntry> getDocument(ExecutionInput executionInput, FeddiGatewayRequestContext context);
}
