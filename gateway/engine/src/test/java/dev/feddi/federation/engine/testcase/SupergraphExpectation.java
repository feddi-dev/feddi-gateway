package dev.feddi.federation.engine.testcase;

/**
 * Expected supergraph SDL for composition tests.
 *
 * @param sdl the expected GraphQL SDL for the consumer-facing API
 */
public record SupergraphExpectation(String sdl) {
    public SupergraphExpectation {
        if (sdl == null || sdl.isBlank()) {
            throw new IllegalArgumentException("supergraph SDL cannot be null or blank");
        }
    }
}
