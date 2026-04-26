package dev.feddi.federation.app;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Schema for the gateway.yml configuration file.
 * Parsed from YAML by Jackson. All fields have sensible defaults.
 */
public class GatewayConfigFile {

    /**
     * Server port. Defaults to 8080.
     */
    private int port = 8080;

    /**
     * Whether GraphQL introspection is enabled. Defaults to true.
     * Set to false for production to prevent schema discovery.
     */
    @JsonProperty("enable-introspection")
    private boolean enableIntrospection = true;

    /**
     * Admin port for the schema upload endpoint (/admin/upload).
     * Defaults to 9091.
     */
    @JsonProperty("admin-port")
    private int adminPort = 9091;

    /**
     * Admin server bind address. Defaults to 127.0.0.1 (localhost only).
     * Set to 0.0.0.0 if admin access is needed from outside the host (e.g. Docker).
     */
    @JsonProperty("admin-address")
    private String adminAddress = "127.0.0.1";

    /**
     * Management port for actuator endpoints (health, metrics, info).
     * Defaults to 9090.
     */
    @JsonProperty("management-port")
    private int managementPort = 9090;

    /**
     * Management server bind address. Defaults to 127.0.0.1 (localhost only).
     * Set to 0.0.0.0 if health checks come from outside the host (e.g. Docker, K8s).
     */
    @JsonProperty("management-address")
    private String managementAddress = "127.0.0.1";

    /**
     * Maximum request body size in bytes. Defaults to 2MB.
     * Requests exceeding this limit are rejected with HTTP 413.
     * Set to 0 to disable the limit.
     */
    @JsonProperty("max-request-size-bytes")
    private long maxRequestSizeBytes = 2 * 1024 * 1024; // 2MB

    private LoggingConfig logging = new LoggingConfig();

    /**
     * Extension configuration sections, keyed by namespace.
     * Each extension declares its namespace and config type via {@link dev.feddi.federation.customization.ConfigurableExtension}.
     */
    private Map<String, Object> extensions = new LinkedHashMap<>();

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isIntrospectionEnabled() {
        return enableIntrospection;
    }

    public void setEnableIntrospection(boolean enableIntrospection) {
        this.enableIntrospection = enableIntrospection;
    }

    public int getAdminPort() {
        return adminPort;
    }

    public void setAdminPort(int adminPort) {
        this.adminPort = adminPort;
    }

    public String getAdminAddress() {
        return adminAddress;
    }

    public void setAdminAddress(String adminAddress) {
        this.adminAddress = adminAddress;
    }

    public int getManagementPort() {
        return managementPort;
    }

    public void setManagementPort(int managementPort) {
        this.managementPort = managementPort;
    }

    public String getManagementAddress() {
        return managementAddress;
    }

    public void setManagementAddress(String managementAddress) {
        this.managementAddress = managementAddress;
    }

    public long getMaxRequestSizeBytes() {
        return maxRequestSizeBytes;
    }

    public void setMaxRequestSizeBytes(long maxRequestSizeBytes) {
        this.maxRequestSizeBytes = maxRequestSizeBytes;
    }

    public LoggingConfig getLogging() {
        return logging;
    }

    public void setLogging(LoggingConfig logging) {
        this.logging = logging;
    }

    public Map<String, Object> getExtensions() {
        return extensions;
    }

    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }

    public static class LoggingConfig {
        /**
         * Directory for log files. Defaults to the current working directory.
         */
        private String dir = ".";

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }
}
