package dev.feddi.federation.customization;

/**
 * Interface for extensions that receive configuration from gateway.yml.
 *
 * <p>Extensions implement this interface with their typed config POJO.
 * The gateway will:
 * <ol>
 *   <li>Parse gateway.yml and find the {@code extensions.<namespace>} section</li>
 *   <li>Deserialize it into the type returned by {@link #configType()}</li>
 *   <li>Call {@link #onConfigLoaded(Object)} with the typed config</li>
 * </ol>
 *
 * <p>Example gateway.yml — replace {@code my-extension} with the namespace
 * your implementation returns from {@link #configNamespace()} and the keys
 * with whatever your config POJO declares:
 * <pre>
 * extensions:
 *   my-extension:
 *     some-key: value
 *     another-key: 42
 * </pre>
 *
 * @param <T> the configuration type
 */
public interface ConfigurableExtension<T> {

    /**
     * The namespace key under {@code extensions:} in gateway.yml.
     */
    String configNamespace();

    /**
     * The class to deserialize the config section into.
     */
    Class<T> configType();

    /**
     * Called with the deserialized configuration after startup.
     */
    void onConfigLoaded(T config);
}
