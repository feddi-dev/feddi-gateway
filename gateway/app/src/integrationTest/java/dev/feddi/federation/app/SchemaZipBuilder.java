package dev.feddi.federation.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Creates ZIP files with schemas and dynamically generated config.yaml files
 * pointing to WireMock servers.
 */
public final class SchemaZipBuilder {

    private SchemaZipBuilder() {}

    /**
     * Creates a ZIP file with subgraph schemas and config files.
     *
     * @param subgraphSdls map of subgraph name to SDL content
     * @param subgraphUrls map of subgraph name to WireMock URL
     * @return the ZIP file as a byte array
     */
    public static byte[] createZip(Map<String, String> subgraphSdls, Map<String, String> subgraphUrls) {
        return createZip(subgraphSdls, subgraphUrls, null);
    }

    /**
     * Creates a ZIP file with subgraph schemas, config files, and optional root config.
     *
     * @param subgraphSdls map of subgraph name to SDL content
     * @param subgraphUrls map of subgraph name to WireMock URL
     * @param timeoutMs optional timeout in milliseconds for gateway config (null = use default)
     * @return the ZIP file as a byte array
     */
    public static byte[] createZip(Map<String, String> subgraphSdls, Map<String, String> subgraphUrls, Long timeoutMs) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Add root config.yaml if timeout is specified
            if (timeoutMs != null) {
                String rootConfig = "timeoutMs: " + timeoutMs + "\n";
                zos.putNextEntry(new ZipEntry("config.yaml"));
                zos.write(rootConfig.getBytes());
                zos.closeEntry();
            }

            for (Map.Entry<String, String> entry : subgraphSdls.entrySet()) {
                String name = entry.getKey();
                String sdl = entry.getValue();
                String url = subgraphUrls.get(name);

                if (url == null) {
                    throw new IllegalArgumentException("No URL found for subgraph: " + name);
                }

                // Add schema.graphqls
                zos.putNextEntry(new ZipEntry("subgraphs/" + name + "/schema.graphqls"));
                zos.write(sdl.getBytes());
                zos.closeEntry();

                // Generate and add config.yaml with WireMock URL
                String configYaml = "url: " + url + "\n";
                zos.putNextEntry(new ZipEntry("subgraphs/" + name + "/config.yaml"));
                zos.write(configYaml.getBytes());
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create schema ZIP", e);
        }
        return baos.toByteArray();
    }
}
