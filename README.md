<picture>
  <source media="(prefers-color-scheme: dark)" srcset="feddi-logo-light.svg">
  <source media="(prefers-color-scheme: light)" srcset="feddi-logo-dark.svg">
  <img alt="feddi" src="feddi-logo-dark.svg" width="220">
</picture>

# feddi Gateway

feddi Gateway is a JVM-native GraphQL federation gateway built on [GraphQL Java](https://github.com/graphql-java/graphql-java) — the foundation of Spring GraphQL, Netflix DGS, and thousands of enterprise deployments.

It implements the [GraphQL Composite Schemas Spec](https://github.com/graphql/composite-schemas-spec), composes source schemas, plans cross-subgraph operations, and executes GraphQL requests against a unified schema — entirely inside the JVM.

Federation gateways sit on the critical path for every GraphQL request. For Java-centric enterprises, running that infrastructure outside the JVM means rebuilding security, policy enforcement, and compliance controls in a foreign runtime. feddi Gateway eliminates that split.

This repository is an open source project and can be used independently of the [feddi Platform](https://feddi.dev?utm_source=github_feddi_gateway&utm_medium=readme&utm_campaign=site_referral&utm_content=feddi_platform). You can run it as a standalone feddi Gateway with your own feddi Gateway definition source, your own subgraph client integration, or the built-in ZIP upload flow.

It works best overall when used together with the feddi Platform. For full documentation on running the feddi Gateway with the feddi Platform — including pre-built binaries — see [feddi.dev/get-started](https://feddi.dev/get-started?utm_source=github_feddi_gateway&utm_medium=readme&utm_campaign=site_referral&utm_content=feddi_get_started).

## Tests

<!-- test-results-start -->
### Test Results
| Suite | Tests | Passed | Failed | Errors | Skipped |
|:------|------:|-------:|-------:|-------:|--------:|
| Gateway engine | 1814 | 1813 | - | - | 1 |
| Gateway app unit | 92 | 92 | - | - | - |
| Gateway app integration | 352 | 352 | - | - | - |
| E2E tests | 35 | 35 | - | - | - |

### Test Categories
| Category | Count |
|:---------|------:|
| Composition success | 43 |
| Composition errors | 85 |
| Planning | 157 |
| Execution | 214 |
| Engine other | 1315 |

### Code Coverage
| Metric | Coverage | Covered / Total |
|:-------|---------:|----------------:|
| Line | 88.3% | 9499/10763 |
| Branch | 80.3% | 4943/6155 |
| Method | 86.4% | 1743/2017 |
<!-- test-results-end -->

## Requirements

- Java 25 or later
- Docker, for `e2e-tests`

## Quick Start

From the repository root, create a minimal two-subgraph definition:

```bash
mkdir -p subgraphs/products subgraphs/reviews
```

`subgraphs/products/schema.graphqls`:

```graphql
type Query {
  product(id: ID!): Product
}

type Product {
  id: ID!
  name: String!
}
```

`subgraphs/products/config.yaml`:

```yaml
url: http://localhost:4001/graphql
```

`subgraphs/reviews/schema.graphqls`:

```graphql
type Query {
  review(id: ID!): Review
}

type Review {
  id: ID!
  body: String!
}
```

`subgraphs/reviews/config.yaml`:

```yaml
url: http://localhost:4002/graphql
```

Package them into a ZIP:

```bash
cd subgraphs && zip -r ../subgraphs.zip . && cd ..
```

Build the distribution ZIP:

```bash
cd gateway
./gradlew :app:feddiGatewayDistZip
```

Extract the distribution from the repository root:

```bash
unzip gateway/app/build/distributions/feddi-gateway.zip
```

Create `feddi-gateway/feddi-gateway.yml`:

```yaml
port: 8080
```

Start the gateway:

```bash
cd feddi-gateway
bin/feddi-gateway
```

In a separate terminal, upload your subgraph definitions from the repository root:

```bash
curl -X POST http://localhost:9091/admin/upload \
  -F file=@subgraphs.zip
```

Your federated graph is now available at `POST http://localhost:8080/graphql`. Make sure the subgraph servers are running at the configured URLs before sending queries.

## Configuration

The feddi Gateway has three configuration surfaces:

- `feddi-gateway.yml` in the working directory controls the feddi Gateway process itself.
- `POST /admin/upload` accepts a ZIP file that defines the active feddi Gateway definition and subgraph settings.
- The launcher script accepts a small set of environment variables for Java selection and JVM tuning.

If `feddi-gateway.yml` is missing or cannot be parsed, the feddi Gateway starts with defaults. The loader only reads `feddi-gateway.yml` from the working directory.

### `feddi-gateway.yml`

Example:

```yaml
port: 8080
max-request-size-bytes: 2097152

logging:
  dir: ./logs
```

Supported top-level keys:

| Key | Type | Default | Meaning                                                                                                                        |
| --- | --- | --- |--------------------------------------------------------------------------------------------------------------------------------|
| `port` | integer | `8080` | HTTP port for the feddi Gateway server                                                                                         |
| `enable-introspection` | boolean | `true` | Whether GraphQL introspection is enabled. Set to `false` in production to prevent schema discovery                             |
| `enable-graphiql` | boolean | `false` | Serves a GraphiQL UI at `/graphiql`. For local development and demos, not production. Requires `enable-introspection: true`. Set to `false` in production |
| `admin-port` | integer | `9091` | Port for the admin endpoint (`/admin/upload`)                                                                                  |
| `admin-address` | string | `127.0.0.1` | Bind address for the admin server. Set to `0.0.0.0` if admin access is needed from outside the host (e.g. Docker)              |
| `management-port` | integer | `9090` | Port for the actuator endpoints; `GET /actuator/health` is the primary health check URL                                        |
| `management-address` | string | `127.0.0.1` | Bind address for the management server. Set to `0.0.0.0` if health checks come from outside the host (e.g. Docker, Kubernetes) |
| `max-request-size-bytes` | long | `2097152` | Maximum GraphQL request body size in bytes; set to `0` to disable the limit                                                    |
| `logging.dir` | string | `.` | Directory where rolling log files are written                                                                                  |
| `extensions` | map | `{}` | Namespace-based configuration passed to installed extensions; omit entirely for a standalone deployment                        |

Logging behavior is fixed by the application:

- Current log file: `feddi-gateway.log`
- Rotation: daily, with additional rollover at `100MB`
- Retention: `30` days
- Total retained size cap: `1GB`

### Extension Namespaces

The `extensions` map is intentionally open-ended. Each installed extension JAR contributes its own namespace and supported keys. The distribution launcher adds all JARs in `libs/` to the runtime classpath, so extension configuration only becomes active when the corresponding extension JAR is present.

The feddi Gateway itself recognizes the namespace and forwards its configuration, but it does not validate or consume arbitrary extension keys directly. Refer to your extension's documentation for the keys it accepts.

### feddi Gateway Definition Uploads

The default runtime source accepts feddi Gateway definitions through `POST /admin/upload` as multipart form data with a `file` part containing a ZIP archive. The response is sent once the upload is active: `200` when the new definition is active, `400` with an `error` message when it was rejected (invalid ZIP, composition error, or a subgraph configured with `batching: variables` that does not support it); the active definition is then kept. `409` means another upload is still being activated.

If an extension-provided `FeddiGatewayDefinitionSource` is installed and active, ZIP uploads are disabled.

Accepted ZIP layouts:

```text
config.yaml
subgraphs/
  products/
    schema.graphqls
    config.yaml
  reviews/
    schema.graphqls
    config.yml
```

or:

```text
products/
  schema.graphqls
  config.yaml
reviews/
  schema.graphqls
  config.yaml
```

feddi Gateway-level ZIP config keys in the optional root `config.yaml` or `config.yml`:

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `timeoutMs` | long | `30000` | Per-subgraph request timeout in milliseconds |
| `maxResponseBytes` | long | `8388608` (8 MB) | Largest subgraph response body read, in bytes; a larger response fails that subgraph call |
| `connectTimeoutMs` | long | `5000` | How long to wait for a connection to a subgraph, in milliseconds |
| `maxIdleTimeMs` | long | `4000` | How long a connection to a subgraph may stay unused before the gateway closes it, in milliseconds. Keep it below the subgraphs' (or their load balancers') idle timeout, e.g. Node.js closes idle connections after 5 seconds, an AWS ALB after 60 |
| `maxConnections` | int | Reactor Netty's (`2 × max(cores, 8)`) | Most connections to one subgraph address |

Per-subgraph keys in each required `config.yaml` or `config.yml`:

| Key | Type | Default | Required | Meaning |
| --- | --- | --- | --- | --- |
| `url` | string | none | yes | GraphQL HTTP endpoint used by the default subgraph client |
| `batching` | string | `none` | no | How entity lookups are batched: `none`, `alias` or `variables` |
| `batch-max-size` | int | `64` | no | Most entities per batch |
| `maxResponseBytes` | long | the root `config.yaml`'s | no | Overrides the gateway's `maxResponseBytes` for this subgraph |
| `sslBundle` | string | none | no | Name of a Spring Boot SSL bundle (`spring.ssl.bundle.*`) for this subgraph's TLS connections (`https` URL) |

Each subgraph entry must contain:

- `schema.graphqls`
- `config.yaml` or `config.yml`

Extension-provided `SubgraphClientFactory` implementations can read additional keys from the same per-subgraph config map; it also holds each subgraph's effective `maxResponseBytes`.

### Subgraph HTTP Calls

The built-in subgraph client calls subgraphs with Reactor Netty's `HttpClient` (not Spring's `WebClient`, whose per-call overhead costs throughput), so `WebClientCustomizer` beans do not apply. What can be customized:

- **Headers**: `DefaultSubgraphClient` forwards `Authorization`, `User-Agent` and the W3C Trace Context headers (`traceparent`, `tracestate`, so that subgraph spans join the client's trace; not `baggage`). If a `SubgraphRequestHeaderCustomizer` bean is registered, it is called after that, so it can add new headers or override forwarded ones.
- **Connections**: `connectTimeoutMs`, `maxIdleTimeMs` and `maxConnections` (see above).
- **TLS** (mTLS, a private certificate authority, protocols, ciphers): define a Spring Boot SSL bundle, e.g. `spring.ssl.bundle.pem.subgraphs.truststore.certificate=classpath:ca.pem`, and name it in the subgraph's `sslBundle`. An unknown or unusable bundle rejects the definition.
- **Response size**: `maxResponseBytes` (see above). The error names the subgraph, the limit and the setting.
- **Anything else** (a proxy, retries, another HTTP client): register your own `SubgraphClientFactory` bean; it replaces the built-in one.

### Launcher Environment Variables

The distribution launcher script supports these environment variables:

| Variable                  | Meaning                                                                            |
|---------------------------|------------------------------------------------------------------------------------|
| `FEDDI_GATEWAY_JAVA_HOME` | Java installation to use for the feddi Gateway; takes precedence over `JAVA_HOME` |
| `JAVA_HOME`               | Fallback Java installation if `FEDDI_GATEWAY_JAVA_HOME` is not set                 |
| `JAVA_OPTS`               | Extra JVM options appended to the launch command                                   |
| `FEDDI_GATEWAY_JVM_OPTS`  | JVM options that replace the launcher's defaults (e.g. another garbage collector)  |

The launcher requires Java 25 or later.

## Repository Layout

- `gateway/engine` - Composition, validation, query planning, and execution
- `gateway/app` - Spring Boot application that serves the feddi Gateway over HTTP
- `gateway/extension` - Public extension API for integrating gateway behavior
- `e2e-tests` - Docker-based end-to-end tests
- `scripts` - Helper scripts for common local workflows

## Running Tests

Run everything in this repository:

```bash
./scripts/run-all-tests.sh
```

Run only the Docker-based end-to-end tests:

```bash
./scripts/run-e2e-tests.sh
```

Run targeted Gradle tasks:

```bash
cd gateway
./gradlew :engine:test
./gradlew :app:test
./gradlew :app:integrationTest
```

If you run `e2e-tests` directly (without `./scripts/run-e2e-tests.sh`) after changing `gateway/extension`, publish the API to your local Maven repository first so the e2e-tests subproject can resolve it:

```bash
cd gateway
./gradlew :extension:publishToMavenLocal
cd ../e2e-tests
./gradlew test
```

## Contributing

Pull requests are welcome. Keep changes focused — small, reviewable PRs are preferred over broad mixed refactors. If you change behavior, add or update tests in the same change. If you change public behavior, update the documentation in the same change.

### Expectations

- Add tests for new behavior and bug fixes.
- Keep names, comments, and documentation clear and generic.
- Avoid introducing product or vendor references in code comments unless they are required for correctness.
- Prefer incremental refactors over wide, mechanical rewrites.

### Pull Requests

- Describe the problem being solved and the approach you took.
- Include the tests you ran.
- Call out any follow-up work or known limitations.
- Make sure CI is passing before requesting review.

### Reporting Bugs

Open an issue with enough detail to reproduce the problem.

## Code of Conduct

See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).

---

Built by Andi Marek, creator of GraphQL Java, and the feddi team.
