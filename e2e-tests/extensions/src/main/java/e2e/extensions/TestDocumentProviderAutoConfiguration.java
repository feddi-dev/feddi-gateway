package e2e.extensions;

import dev.feddi.federation.extension.DocumentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that registers a {@link TestDocumentProvider} bean.
 */
@AutoConfiguration
public class TestDocumentProviderAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(TestDocumentProviderAutoConfiguration.class);

    @Bean
    public DocumentProvider testDocumentProvider() {
        logger.info("[TEST] TestDocumentProvider registered (known hash: {})", TestDocumentProvider.KNOWN_HASH);
        return new TestDocumentProvider();
    }
}
