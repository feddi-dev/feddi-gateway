package dev.feddi.federation.engine.graph;

import java.util.List;

/**
 * Represents a cross-subgraph lookup edge.
 * This edge is used when resolving a field requires jumping to another subgraph
 * via a @lookup field.
 *
 * @param lookupField the name of the @lookup field used for resolution
 * @param source the source node
 * @param target the target node (different subgraph)
 * @param cost the cost of this edge (typically higher than direct field access, e.g., 10)
 * @param lookupArguments the lookup arguments needed for the lookup (from @is mappings), with type info
 * @param requires the field requirements (from @require directive), with type info
 */
public record LookupMoveEdge(
    String lookupField,
    Node source,
    Node target,
    int cost,
    List<LookupArgument> lookupArguments,
    List<Requirement> requires
) implements Edge {

    public LookupMoveEdge {
        if (lookupField == null || lookupField.isBlank()) {
            throw new IllegalArgumentException("lookupField cannot be null or blank");
        }
        if (source == null) {
            throw new IllegalArgumentException("source cannot be null");
        }
        if (target == null) {
            throw new IllegalArgumentException("target cannot be null");
        }
        if (cost < 0) {
            throw new IllegalArgumentException("cost cannot be negative");
        }
        lookupArguments = lookupArguments == null ? List.of() : List.copyOf(lookupArguments);
        requires = requires == null ? List.of() : List.copyOf(requires);
    }

    /**
     * Creates a LookupMoveEdge with default cost of 10 and no requirements.
     */
    public static LookupMoveEdge withDefaultCost(String lookupField, Node source, Node target, List<LookupArgument> lookupArguments) {
        return new LookupMoveEdge(lookupField, source, target, 10, lookupArguments, List.of());
    }

    @Override
    public String fieldName() {
        return lookupField;
    }

    /**
     * Checks if this lookup has any lookup arguments.
     */
    public boolean hasLookupArguments() {
        return !lookupArguments.isEmpty();
    }

    /**
     * Checks if this lookup has any @require dependencies.
     */
    public boolean hasRequirements() {
        return !requires.isEmpty();
    }

    @Override
    public String toString() {
        List<String> argNames = lookupArguments.stream()
            .map(LookupArgument::argumentName)
            .toList();
        return String.format("LookupMove(%s: %s -> %s, cost=%d, args=%s)",
            lookupField, source, target, cost, argNames);
    }
}
