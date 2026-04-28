package dev.feddi.federation.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the feddi-gateway.yml configuration file from the working directory.
 * This is loaded early in startup, before the Spring context, so it
 * cannot depend on any Spring beans.
 */
public class FeddiGatewayConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(FeddiGatewayConfigLoader.class);
    private static final String CONFIG_FILE_NAME = "feddi-gateway.yml";

    /**
     * Load feddi-gateway.yml from the current working directory.
     * Returns defaults if the file doesn't exist or can't be parsed.
     */
    public static FeddiGatewayConfigFile load() {
        return load(Path.of(CONFIG_FILE_NAME));
    }

    /**
     * Load feddi-gateway config from the specified path.
     */
    public static FeddiGatewayConfigFile load(Path path) {
        if (!Files.exists(path)) {
            log.info("No {} found, using defaults", path);
            return new FeddiGatewayConfigFile();
        }

        try {
            var mapper = new ObjectMapper(new YAMLFactory())
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            var config = mapper.readValue(path.toFile(), FeddiGatewayConfigFile.class);
            log.info("Loaded configuration from {}", path);
            return config;
        } catch (Exception e) {
            log.error("Failed to parse {}: {}. Using defaults.", path, e.getMessage());
            return new FeddiGatewayConfigFile();
        }
    }
}
