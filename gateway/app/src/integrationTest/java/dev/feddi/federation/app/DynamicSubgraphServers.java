package dev.feddi.federation.app;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages GraphQL subgraph servers for each subgraph in a schema.
 *
 * <p>Unlike the previous WireMock-based implementation, these servers execute
 * actual GraphQL operations using GraphQL Java, validating that queries sent
 * by the gateway are syntactically and semantically correct.
 */
public class DynamicSubgraphServers {

    private final Map<String, GraphQLSubgraphServer> servers = new HashMap<>();

    /**
     * Starts GraphQL subgraph servers for each subgraph.
     *
     * @param subgraphSdls map of subgraph name to SDL schema
     */
    public void startServersForSubgraphs(Map<String, String> subgraphSdls) {
        for (Map.Entry<String, String> entry : subgraphSdls.entrySet()) {
            String subgraphName = entry.getKey();
            String sdl = entry.getValue();

            GraphQLSubgraphServer server = new GraphQLSubgraphServer(subgraphName, sdl);
            server.start();
            servers.put(subgraphName, server);
        }
    }

    /**
     * Gets the GraphQL server for a specific subgraph.
     */
    public GraphQLSubgraphServer getServer(String subgraphName) {
        GraphQLSubgraphServer server = servers.get(subgraphName);
        if (server == null) {
            throw new IllegalArgumentException("No server found for subgraph: " + subgraphName);
        }
        return server;
    }

    /**
     * Gets the base URL for a subgraph's server.
     * Example: "http://localhost:54321"
     */
    public String getUrl(String subgraphName) {
        return getServer(subgraphName).getUrl();
    }

    /**
     * Gets all subgraph name -> URL mappings for gateway configuration.
     */
    public Map<String, String> getSubgraphUrls() {
        Map<String, String> urls = new HashMap<>();
        for (Map.Entry<String, GraphQLSubgraphServer> entry : servers.entrySet()) {
            urls.put(entry.getKey(), entry.getValue().getUrl());
        }
        return urls;
    }

    /**
     * Resets all stubs on all servers.
     */
    public void resetAllStubs() {
        servers.values().forEach(GraphQLSubgraphServer::resetStubs);
    }

    /**
     * Stops all servers and clears the map.
     */
    public void stopAll() {
        servers.values().forEach(GraphQLSubgraphServer::stop);
        servers.clear();
    }

    /**
     * Returns true if any servers are running.
     */
    public boolean hasRunningServers() {
        return !servers.isEmpty();
    }
}
