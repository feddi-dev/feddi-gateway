package dev.feddi.federation.engine.executor;

import java.util.Locale;
import java.util.Map;

/**
 * How entity lookups of one repeated step are sent to a subgraph.
 *
 * <ul>
 *   <li>{@link Mode#NONE}: one request per unique entity (works with every GraphQL server).</li>
 *   <li>{@link Mode#ALIAS}: one spec-compliant request with an aliased copy of the lookup per
 *       entity (works with every GraphQL server).</li>
 *   <li>{@link Mode#VARIABLES}: one request whose {@code variables} is an array; the subgraph
 *       must support variable batching (e.g. HotChocolate).</li>
 * </ul>
 *
 * @param mode         the batching mode
 * @param maxBatchSize the maximum number of entities per request; larger batches are split
 */
public record BatchingOptions(Mode mode, int maxBatchSize) {

    /** Batching modes. */
    public enum Mode {
        NONE, ALIAS, VARIABLES
    }

    /** Subgraph setting key for the mode ({@code none | alias | variables}). */
    public static final String MODE_SETTING = "batching";

    /** Subgraph setting key for the maximum batch size. */
    public static final String MAX_SIZE_SETTING = "batch-max-size";

    public static final int DEFAULT_MAX_BATCH_SIZE = 64;

    public static final BatchingOptions NONE = new BatchingOptions(Mode.NONE, DEFAULT_MAX_BATCH_SIZE);

    public BatchingOptions {
        if (mode == null) {
            throw new IllegalArgumentException("mode cannot be null");
        }
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("maxBatchSize must be at least 1, was " + maxBatchSize);
        }
    }

    /**
     * Reads batching options from subgraph settings, falling back to {@code defaults} for
     * missing keys.
     *
     * @throws IllegalArgumentException for unknown modes or invalid sizes
     */
    public static BatchingOptions fromSettings(Map<String, Object> settings, BatchingOptions defaults) {
        Mode mode = defaults.mode();
        int maxBatchSize = defaults.maxBatchSize();
        if (settings != null) {
            Object configuredMode = settings.get(MODE_SETTING);
            if (configuredMode != null) {
                mode = parseMode(configuredMode.toString());
            }
            Object configuredSize = settings.get(MAX_SIZE_SETTING);
            if (configuredSize != null) {
                try {
                    maxBatchSize = Integer.parseInt(configuredSize.toString().trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                        "Invalid " + MAX_SIZE_SETTING + ": '" + configuredSize + "' (expected a positive integer)", e);
                }
            }
        }
        return new BatchingOptions(mode, maxBatchSize);
    }

    private static Mode parseMode(String value) {
        try {
            return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid " + MODE_SETTING + ": '" + value + "' (expected none, alias or variables)", e);
        }
    }
}
