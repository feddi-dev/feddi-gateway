package dev.feddi.federation.engine.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * All imported Fusion planner tests are green: each one is either planned by feddi and returns the monolith's
 * data (PlanExecutionOracleTest), or rejected for a reason the specs give (fusion-planning/spec-rejections.yaml,
 * checked against FusionPlanParityTest's status). Both baselines are kept current by their own tests, which fail
 * on any change; this test checks what they contain.
 */
class FusionTestsGreenTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void everyFusionTestIsPlannedCorrectlyOrRejectedBySpec() throws Exception {
        JsonNode parity = YAML.readTree(baseline("fusionParity.baseline").toFile());
        JsonNode oracle = YAML.readTree(baseline("planOracle.baseline").toFile());
        JsonNode rejections = YAML.readTree(resource("fusion-planning/spec-rejections.yaml").toFile());

        List<String> notGreen = new ArrayList<>();
        parity.properties().forEach(entry -> {
            String id = entry.getKey();
            String status = entry.getValue().path("status").asText();
            JsonNode rejection = rejections.path(id);
            if (!rejection.isMissingNode()) {
                if (!status.contains(rejection.path("expect").asText())) {
                    notGreen.add(id + ": expected the spec rejection '" + rejection.path("expect").asText()
                        + "', got: " + status);
                }
                JsonNode valid = oracle.path("fusion/" + id + "/valid");
                if (!valid.isMissingNode() && !valid.asText().equals("same data")) {
                    notGreen.add(id + " (valid variant): " + valid.asText());
                }
            } else if (!status.equals("ok")) {
                notGreen.add(id + ": " + status);
            } else if (!oracle.path("fusion/" + id).asText().equals("same data")) {
                notGreen.add(id + ": " + oracle.path("fusion/" + id).asText());
            }
        });
        rejections.properties().forEach(entry -> {
            if (parity.path(entry.getKey()).isMissingNode()) {
                notGreen.add(entry.getKey() + " is listed in spec-rejections.yaml but not imported");
            }
        });

        assertThat(notGreen).as("Fusion planner tests that are neither planned correctly nor rejected by spec")
            .isEmpty();
    }

    private static Path baseline(String property) {
        return Path.of(Objects.requireNonNull(System.getProperty(property), property + " is set by the test task"));
    }

    private static Path resource(String name) throws URISyntaxException, IOException {
        Path path = Path.of(Objects.requireNonNull(FusionTestsGreenTest.class.getClassLoader().getResource(name)).toURI());
        if (!Files.exists(path)) {
            throw new IOException(name + " not found");
        }
        return path;
    }
}
