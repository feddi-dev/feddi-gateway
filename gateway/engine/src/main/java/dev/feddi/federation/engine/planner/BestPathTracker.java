package dev.feddi.federation.engine.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tracks the best paths to each destination subgraph.
 * Used to select the lowest-cost paths during pathfinding.
 */
public final class BestPathTracker {
    
    /**
     * Map from subgraph name to (best paths, best cost).
     * Uses TreeMap for deterministic ordering.
     */
    private final Map<String, PathsWithCost> subgraphToBestPaths;
    
    public BestPathTracker() {
        this.subgraphToBestPaths = new TreeMap<>();
    }
    
    /**
     * Adds a path to the tracker.
     * If it's better than existing paths to the same subgraph, replaces them.
     * If it's equal cost, adds it as an alternative.
     */
    public void add(OperationPath path) {
        String subgraph = path.currentSubgraph();
        int cost = path.cost();
        
        PathsWithCost existing = subgraphToBestPaths.get(subgraph);
        
        if (existing == null) {
            // First path to this subgraph
            List<OperationPath> paths = new ArrayList<>();
            paths.add(path);
            subgraphToBestPaths.put(subgraph, new PathsWithCost(paths, cost));
        } else if (cost < existing.cost()) {
            // New path is better - replace all existing
            List<OperationPath> paths = new ArrayList<>();
            paths.add(path);
            subgraphToBestPaths.put(subgraph, new PathsWithCost(paths, cost));
        } else if (cost == existing.cost()) {
            // Same cost - add as alternative
            existing.paths().add(path);
        }
        // If cost > existing.cost(), ignore (path is worse)
    }
    
    /**
     * Gets all best paths across all subgraphs.
     */
    public List<OperationPath> getBestPaths() {
        List<OperationPath> result = new ArrayList<>();
        for (PathsWithCost pwc : subgraphToBestPaths.values()) {
            result.addAll(pwc.paths());
        }
        return result;
    }
    
    /**
     * Gets the best paths to a specific subgraph.
     */
    public List<OperationPath> getBestPathsTo(String subgraph) {
        PathsWithCost pwc = subgraphToBestPaths.get(subgraph);
        return pwc != null ? List.copyOf(pwc.paths()) : List.of();
    }
    
    /**
     * Gets the best cost to a specific subgraph.
     */
    public OptionalInt getBestCostTo(String subgraph) {
        PathsWithCost pwc = subgraphToBestPaths.get(subgraph);
        return pwc != null ? OptionalInt.of(pwc.cost()) : OptionalInt.empty();
    }
    
    /**
     * Checks if there are any paths tracked.
     */
    public boolean isEmpty() {
        return subgraphToBestPaths.isEmpty();
    }
    
    /**
     * Gets the number of subgraphs with tracked paths.
     */
    public int subgraphCount() {
        return subgraphToBestPaths.size();
    }
    
    /**
     * Gets all subgraphs that have paths.
     */
    public Set<String> getSubgraphs() {
        return Set.copyOf(subgraphToBestPaths.keySet());
    }
    
    /**
     * Clears all tracked paths.
     */
    public void clear() {
        subgraphToBestPaths.clear();
    }
    
    @Override
    public String toString() {
        if (isEmpty()) {
            return "BestPathTracker(empty)";
        }
        
        StringBuilder sb = new StringBuilder("BestPathTracker:\n");
        for (Map.Entry<String, PathsWithCost> entry : subgraphToBestPaths.entrySet()) {
            sb.append(String.format("  %s: %d paths, cost=%d\n", 
                entry.getKey(), 
                entry.getValue().paths().size(), 
                entry.getValue().cost()));
        }
        return sb.toString();
    }
    
    /**
     * Helper record to store paths with their cost.
     */
    private record PathsWithCost(List<OperationPath> paths, int cost) {
        PathsWithCost {
            if (paths == null) {
                throw new IllegalArgumentException("paths cannot be null");
            }
        }
    }
    
    /**
     * Utility method to find best paths from a list.
     */
    public static List<OperationPath> findBestPaths(List<OperationPath> paths) {
        if (paths.isEmpty()) {
            return List.of();
        }
        
        int bestCost = paths.stream()
            .mapToInt(OperationPath::cost)
            .min()
            .orElse(Integer.MAX_VALUE);
        
        return paths.stream()
            .filter(p -> p.cost() == bestCost)
            .toList();
    }
}
