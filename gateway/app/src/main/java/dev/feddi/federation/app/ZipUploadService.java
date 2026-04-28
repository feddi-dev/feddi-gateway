package dev.feddi.federation.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.feddi.federation.customization.FeddiGatewayDefinition;
import dev.feddi.federation.customization.FeddiGatewaySettings;
import dev.feddi.federation.customization.SubgraphDefinition;
import dev.feddi.federation.customization.SubgraphSettings;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Service for processing zip uploads containing subgraph configurations.
 *
 * <p>Only created when no custom {@link FeddiGatewayDefinitionSource}
 * is registered (see {@link FeddiGatewayDefinitionSourceConfiguration}).
 *
 * Expected zip structure:
 * <pre>
 * subgraphs/
 *   products/
 *     schema.graphqls
 *     config.yaml       # contains: url: http://products:4000/graphql
 *   reviews/
 *     schema.graphqls
 *     config.yaml       # contains: url: http://reviews:4001/graphql
 * </pre>
 */
public class ZipUploadService {

    private final DefaultFeddiGatewayDefinitionSource gatewayDefinitionSource;
    private final ObjectMapper yamlMapper;

    public ZipUploadService(DefaultFeddiGatewayDefinitionSource gatewayDefinitionSource) {
        this.gatewayDefinitionSource = gatewayDefinitionSource;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    /**
     * Processes a zip file containing subgraph configurations and refreshes the gateway.
     *
     * @param zipBytes the zip file contents
     * @throws FeddiGatewayDefinitionException if parsing or validation fails
     */
    public void processZip(byte[] zipBytes) {
        gatewayDefinitionSource.replace(parseZip(zipBytes));
    }

    private FeddiGatewayDefinition parseZip(byte[] zipBytes) {
        Map<String, MutableSubgraphDefinition> subgraphs = new LinkedHashMap<>();
        FeddiGatewaySettings gatewaySettings = FeddiGatewaySettings.defaults();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String path = normalizePath(entry.getName());
                if (path.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }

                byte[] content = zis.readAllBytes();

                // Check for root config.yaml (gateway-level configuration)
                if (path.equals("config.yaml") || path.equals("config.yml")) {
                    gatewaySettings = yamlMapper.readValue(content, FeddiGatewaySettings.class);
                    zis.closeEntry();
                    continue;
                }

                // Expected path: subgraphs/<name>/schema.graphqls or subgraphs/<name>/config.yaml
                // Also accept: <name>/schema.graphqls or <name>/config.yaml
                String[] parts = path.split("/");
                if (parts.length < 2) {
                    zis.closeEntry();
                    continue;
                }

                String subgraphName;
                String fileName;

                if (parts[0].equals("subgraphs") && parts.length >= 3) {
                    subgraphName = parts[1];
                    fileName = parts[parts.length - 1];
                } else if (parts.length >= 2) {
                    subgraphName = parts[0];
                    fileName = parts[parts.length - 1];
                } else {
                    zis.closeEntry();
                    continue;
                }

                MutableSubgraphDefinition data = subgraphs.computeIfAbsent(subgraphName, k -> new MutableSubgraphDefinition());

                if (fileName.equals("schema.graphqls")) {
                    data.sdl = new String(content, StandardCharsets.UTF_8);
                } else if (fileName.equals("config.yaml") || fileName.equals("config.yml")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> configMap = yamlMapper.readValue(content, Map.class);
                    data.settings = new SubgraphSettings(configMap);
                }

                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new FeddiGatewayDefinitionException("Failed to parse zip file: " + e.getMessage(), e);
        }

        if (subgraphs.isEmpty()) {
            throw new FeddiGatewayDefinitionException("No valid subgraphs found in zip");
        }

        Map<String, SubgraphDefinition> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, MutableSubgraphDefinition> entry : subgraphs.entrySet()) {
            String name = entry.getKey();
            MutableSubgraphDefinition data = entry.getValue();
            if (data.sdl == null) {
                throw new FeddiGatewayDefinitionException("Missing schema.graphqls for subgraph: " + name);
            }
            if (data.settings == null) {
                throw new FeddiGatewayDefinitionException("Missing config.yaml for subgraph: " + name);
            }
            definitions.put(name, new SubgraphDefinition(data.sdl, data.settings));
        }

        return new FeddiGatewayDefinition(definitions, gatewaySettings);
    }

    private String normalizePath(String rawPath) {
        String path = rawPath.replace('\\', '/');
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        StringBuilder normalized = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                throw new FeddiGatewayDefinitionException("Invalid zip entry path: " + rawPath);
            }
            if (normalized.length() > 0) {
                normalized.append('/');
            }
            normalized.append(part);
        }
        return normalized.toString();
    }

    private static class MutableSubgraphDefinition {
        String sdl;
        SubgraphSettings settings;
    }
}
