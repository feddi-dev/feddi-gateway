# feddi Gateway — Branding Refactoring Plan

## Goal

Make clear that this open source project belongs to feedi by consistently using
the brand name **feddi Gateway** throughout the codebase, configuration,
deployment artifacts, and documentation.

### Naming rules

| Context | Correct form | Wrong forms |
|---|---|---|
| Brand name (prose, UI, docs) | `feddi Gateway` | `Feddi Gateway`, `gateway`, `Gateway` |
| File / directory names | `feddi-gateway` prefix | `gateway` prefix |
| Java classes | `FeddiGateway` prefix | `Gateway` prefix |
| Env vars | `FEDDI_GATEWAY_` prefix | `GATEWAY_` prefix |
| Gradle tasks (camelCase) | `feddiGateway` prefix | `gateway` prefix |

Spring / Gradle build-file defaults (e.g. `settings.gradle` filename,
`build.gradle` filename) are **not changed** — only their content where
it contains branding strings.

Hard-cut: `gateway.yml` is no longer accepted as a config filename.
No fallback or backward-compatibility shim.

`/gateway` subdirectory should be `feddi-gateway` 

---

## Phase 1 — Config & runtime artifacts

User-facing files and names that appear at install/run time.

### 1.1 Config file

| Before | After                                              |
|---|----------------------------------------------------|
| `gateway/dist/gateway.yml.sample` | `feddi-gateway/dist/feddi-gateway.yml.sample`      |
| `e2e-tests/docker/gateway/gateway.yml` | `e2e-tests/docker/feddi-gateway/feddi-gateway.yml` |

All code that references the string `gateway.yml` (default config filename,
log messages, error messages, help text) must be updated to `feddi-gateway.yml`.

### 1.2 Log files

| Before | After |
|---|---|
| `gateway.log` | `feddi-gateway.log` |
| `gateway-YYYY-MM-DD.N.log` | `feddi-gateway-YYYY-MM-DD.N.log` |

Update the Logback / logging config that defines the log filename pattern.

### 1.3 Version resource

| Before | After |
|---|---|
| `gateway-version.txt` (embedded resource) | `feddi-gateway-version.txt` |

Update the resource filename and all code that loads it by name.

### 1.4 Environment variable

| Before | After |
|---|---|
| `GATEWAY_JAVA_HOME` | `FEDDI_GATEWAY_JAVA_HOME` |

Update in launcher scripts (`dist/bin/feddi-gateway`, `dist/bin/feddi-gateway.bat`)
and in README documentation.

---

## Phase 2 — Docker & deployment

### 2.1 docker-compose.yml

| Before | After |
|---|---|
| service name `gateway` | service name `feddi-gateway` |

### 2.2 Dockerfiles

| Before | After |
|---|---|
| Docker user `gateway` | Docker user `feddi-gateway` |

---

## Phase 3 — Build system

Only branding content inside build files changes; filenames stay as-is.

### 3.1 Build descriptions

Any `description` string in `build.gradle` files that reads `"gateway …"` or
`"Gateway …"` is updated to `"feddi Gateway …"`.

### 3.2 Gradle archive / task names

| Before | After |
|---|---|
| task `gatewayDistZip` | task `feddiGatewayDistZip` |
| produced archive `feddi-gateway.zip` | unchanged (already correct) |

---

## Phase 4 — Java class renames

All `Gateway`-prefixed classes in `gateway/app/src/` are renamed with the
`FeddiGateway` prefix. File names and all import / reference sites must
be updated in the same step.

| Before | After |
|---|---|
| `GatewayApplication` | `FeddiGatewayApplication` |
| `GatewayConfig` | `FeddiGatewayConfig` |
| `GatewayConfigFile` | `FeddiGatewayConfigFile` |
| `GatewayConfigLoader` | `FeddiGatewayConfigLoader` |
| `GatewayDefinition` | `FeddiGatewayDefinition` |
| `GatewayDefinitionException` | `FeddiGatewayDefinitionException` |
| `GatewayDefinitionSourceManager` | `FeddiGatewayDefinitionSourceManager` |
| `GatewayHolder` | `FeddiGatewayHolder` |
| `GatewayMetrics` | `FeddiGatewayMetrics` |
| `GatewayReloadService` | `FeddiGatewayReloadService` |
| `GatewayRequestContext` | `FeddiGatewayRequestContext` |
| `FederationGateway` | `FeddiGatewayFederation` |
| `DefaultGatewayDefinitionSource` | `DefaultFeddiGatewayDefinitionSource` |

Test classes follow the same pattern (e.g. `GatewayApplicationTests` →
`FeddiGatewayApplicationTests`).

### 4.1 Spring application name

In `gateway/app/src/main/resources/application.properties`:

```
spring.application.name=feddi-gateway
```

---

## Phase 5 — Documentation

### 5.1 README.md

- Project title and tagline use `feddi Gateway`
- All config file references updated to `feddi-gateway.yml`
- Env var references updated to `FEDDI_GATEWAY_JAVA_HOME`
- Log file references updated to `feddi-gateway.log`
- Build / run instructions updated to reflect new task names
- No occurrence of standalone `gateway` (lowercase, unqualified) in prose

### 5.2 Other markdown files

Scan `CODE_OF_CONDUCT.md` and any files under `scripts/` or `e2e-tests/`
for unqualified `gateway` references and update to `feddi Gateway` where
appropriate.

---

## Execution order

1. Phase 4 (Java renames) first — IDE refactoring tools handle cross-file updates cleanly.
2. Phase 1 (config/runtime) — rename files, update all string references.
3. Phase 3 (build system) — update Gradle descriptions and task names.
4. Phase 2 (Docker) — update compose and Dockerfiles.
5. Phase 5 (docs) — update README and remaining markdown.
6. Run full test suite + e2e tests to verify nothing broken.

---

## Out of scope

- `settings.gradle` filename — unchanged
- `build.gradle` filename — unchanged
- Gradle wrapper files
- Third-party dependency names or Spring Boot internals
