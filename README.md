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
| Gateway engine | 1108 | 1104 | - | - | 4 |
| Gateway app unit | 11 | 11 | - | - | - |
| Gateway app integration | 331 | 327 | - | - | 4 |
| E2E tests | 31 | 31 | - | - | - |

### Test Categories
| Category | Count |
|:---------|------:|
| Composition success | 39 |
| Composition errors | 76 |
| Planning | 132 |
| Execution | 201 |
| Engine other | 660 |

### Code Coverage
| Metric | Coverage | Covered / Total |
|:-------|---------:|----------------:|
| Line | 83.9% | 7126/8494 |
| Branch | 76.1% | 3452/4538 |
| Method | 80.1% | 1229/1535 |
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

The default runtime source accepts feddi Gateway definitions through `POST /admin/upload` as multipart form data with a `file` part containing a ZIP archive. Each upload replaces the active definition immediately.

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

Per-subgraph keys in each required `config.yaml` or `config.yml`:

| Key | Type | Default | Required | Meaning |
| --- | --- | --- | --- | --- |
| `url` | string | none | yes | GraphQL HTTP endpoint used by the default subgraph client |

Each subgraph entry must contain:

- `schema.graphqls`
- `config.yaml` or `config.yml`

For the built-in subgraph client, `url` is the only consumed subgraph config key. Extension-provided `SubgraphClientFactory` implementations can read additional keys from the same per-subgraph config map.

### Launcher Environment Variables

The distribution launcher script supports these environment variables:

| Variable                  | Meaning                                                                            |
|---------------------------|------------------------------------------------------------------------------------|
| `FEDDI_GATEWAY_JAVA_HOME` | Java installation to use for the feddi Gateway; takes precedence over `JAVA_HOME` |
| `JAVA_HOME`               | Fallback Java installation if `FEDDI_GATEWAY_JAVA_HOME` is not set                 |
| `JAVA_OPTS`               | Extra JVM options appended to the launch command                                   |

The launcher requires Java 25 or later.

## Repository Layout

- `gateway/engine` - Composition, validation, query planning, and execution
- `gateway/app` - Spring Boot application that serves the feddi Gateway over HTTP
- `gateway/extension-api` - Public extension API for integrating gateway behavior
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

If you run `e2e-tests` directly (without `./scripts/run-e2e-tests.sh`) after changing `gateway/extension-api`, publish the API to your local Maven repository first so the e2e-tests subproject can resolve it:

```bash
cd gateway
./gradlew :extension-api:publishToMavenLocal
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
