package dev.feddi.federation.app;

import dev.feddi.federation.extension.FeddiGatewayDefinition;
import dev.feddi.federation.extension.FeddiGatewayRequestContext;
import dev.feddi.federation.extension.FeddiGatewaySettings;
import dev.feddi.federation.extension.SubgraphClient;
import dev.feddi.federation.extension.SubgraphDefinition;
import dev.feddi.federation.extension.SubgraphSettings;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslOptions;
import org.springframework.boot.ssl.SslStoreBundle;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import javax.net.ssl.KeyManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Subgraphs with an {@code sslBundle} connect with that Spring Boot SSL bundle's trust (and key) material. */
class SubgraphSslBundleTest {

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final OperationDefinition QUERY = Parser.parse("{ hello }")
        .getDefinitionsOfType(OperationDefinition.class).get(0);

    @TempDir
    static Path directory;
    private static KeyStore serverKeys;
    private static KeyStore trusted;
    private DisposableServer server;

    /** A self-signed certificate for localhost, made with the JDK's keytool. */
    @BeforeAll
    static void createCertificate() throws Exception {
        Path keystore = directory.resolve("server.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "subgraph", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
            "-keystore", keystore.toString(), "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD))
            .inheritIO().start();
        assertThat(keytool.waitFor()).isZero();
        serverKeys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            serverKeys.load(in, PASSWORD);
        }
        Certificate certificate = serverKeys.getCertificate("subgraph");
        trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("subgraph", certificate);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.disposeNow();
        }
    }

    @Test
    void connectsWithTheBundlesTrustMaterial() throws Exception {
        startHttpsServer();
        var bundles = new DefaultSslBundleRegistry("subgraphs", SslBundle.of(SslStoreBundle.of(null, null, trusted)));
        var factory = new DefaultSubgraphClientFactory(null, bundles);

        SubgraphClient client = factory.create("accounts", settings(Map.of("sslBundle", "subgraphs")));

        assertThat(client.execute(QUERY, Map.of(), FeddiGatewayRequestContext.empty()).block().<Object>getData())
            .isNotNull();
    }

    @Test
    void withoutTheBundleTheCertificateIsNotTrusted() throws Exception {
        startHttpsServer();
        var factory = new DefaultSubgraphClientFactory(null, new DefaultSslBundleRegistry());

        SubgraphClient client = factory.create("accounts", settings(Map.of()));

        assertThatThrownBy(() -> client.execute(QUERY, Map.of(), FeddiGatewayRequestContext.empty()).block())
            .hasStackTraceContaining("PKIX");
    }

    @Test
    void appliesTheBundlesProtocolsAndCiphers() throws Exception {
        startHttpsServer();
        var options = SslOptions.of(new String[] {"TLS_AES_128_GCM_SHA256"}, new String[] {"TLSv1.3"});
        var bundles = new DefaultSslBundleRegistry("subgraphs",
            SslBundle.of(SslStoreBundle.of(null, null, trusted), null, options));
        SubgraphClient client = new DefaultSubgraphClientFactory(null, bundles)
            .create("accounts", settings(Map.of("sslBundle", "subgraphs")));
        assertThat(client.execute(QUERY, Map.of(), FeddiGatewayRequestContext.empty()).block().<Object>getData())
            .isNotNull();

        var unusable = new DefaultSslBundleRegistry("subgraphs", SslBundle.of(SslStoreBundle.of(null, null, trusted),
            null, SslOptions.of(null, new String[] {"TLSv9"})));
        assertThatThrownBy(() -> new DefaultSubgraphClientFactory(null, unusable)
            .create("accounts", settings(Map.of("sslBundle", "subgraphs"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot use sslBundle subgraphs");
    }

    @Test
    void anUnknownBundleRejectsTheUpload() {
        var factory = new DefaultSubgraphClientFactory(null, new DefaultSslBundleRegistry());
        var service = new FeddiGatewayReloadService(new FeddiGatewayHolder(), factory,
            new FeddiGatewayMetrics(new SimpleMeterRegistry()), null, new FeddiGatewayConfigFile());

        assertThatThrownBy(() -> service.reload(new FeddiGatewayDefinition(Map.of("accounts", new SubgraphDefinition(
                "type Query { hello: String }", settings(Map.of("sslBundle", "missing")))),
            FeddiGatewaySettings.defaults())).block())
            .isInstanceOf(FeddiGatewayDefinitionException.class)
            .hasMessageContaining("unknown sslBundle: missing");
    }

    @Test
    void rejectsUnknownOrUnusableBundles() {
        var bundles = new DefaultSslBundleRegistry("subgraphs", SslBundle.of(SslStoreBundle.of(null, null, trusted)));
        assertThatThrownBy(() -> new DefaultSubgraphClientFactory(null, bundles)
            .create("accounts", settings(Map.of("sslBundle", "other"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown sslBundle: other");
        assertThatThrownBy(() -> new DefaultSubgraphClientFactory(null, null)
            .create("accounts", settings(Map.of("sslBundle", "subgraphs"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no SSL bundles are configured");
        assertThatThrownBy(() -> new DefaultSubgraphClientFactory(null, bundles)
            .create("accounts", new SubgraphSettings(Map.of("url", "http://localhost/graphql", "sslBundle", "subgraphs"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no https URL");
    }

    private SubgraphSettings settings(Map<String, Object> extra) {
        Map<String, Object> config = new java.util.HashMap<>(extra);
        config.put("url", "https://localhost:" + (server == null ? 1 : server.port()) + "/graphql");
        return new SubgraphSettings(config);
    }

    private void startHttpsServer() throws Exception {
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(serverKeys, PASSWORD);
        var sslContext = SslContextBuilder.forServer(keyManagers).build();
        server = HttpServer.create().port(0).secure(spec -> spec.sslContext(sslContext))
            .handle((request, response) -> request.receive().then(response.status(HttpResponseStatus.OK)
                .header("Content-Type", "application/json")
                .sendString(Mono.just("{\"data\":{\"hello\":\"world\"}}")).then()))
            .bindNow();
    }
}
