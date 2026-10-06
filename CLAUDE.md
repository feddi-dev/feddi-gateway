# feddi Gateway

JVM GraphQL composite-schema federation gateway (Java 25, graphql-java, Reactor).

## Layout

- `gateway/` – Gradle build (`engine`, `extension`, `app`)
  - `engine/` – composition (`compose/`), query graph (`graph/`), planner (`planner/`), executor (`executor/`)
  - `extension/` – **public SPI**, published to Maven Central as `dev.feddi:feddi-gateway-extension`
    (`SubgraphClient`, `SubgraphClientFactory`, `SubgraphSettings`, …). Prefer backward-compatible changes
    (`default` methods). Breaking changes are allowed before 1.0 when agreed, with a release note.
  - `app/` – Spring Boot gateway (`FeddiFederationGateway` is the request path: parse → normalize → plan → execute)
- `e2e-tests/` – separate Gradle build, end-to-end tests against Docker subgraphs
- `docs/perf-query-execution/` – context and plan for the query-execution performance work

## Build & test

```bash
./scripts/run-all-tests.sh        # everything (engine, app unit + integration, e2e); -c allows cached results
                                  # run with JDK 25: JAVA_HOME=$(/usr/libexec/java_home -v 25) (Gradle build scripts fail on JDK 26)
cd gateway && ./gradlew :engine:test   # fast loop for planner/executor work
scripts/local-benchmark.sh <graphql-gateway-benchmarks checkout>   # relative RPS (Docker + k6)
```

Checkstyle runs with the build and fails on any violation. Project rules include: use `FederationDirectives`
constants instead of directive string literals, no introspection field literals (`__typename`, …), no reflection
(`getDeclaredMethod`/`setAccessible`) in tests, no star imports.

## Conventions

- Null annotations: `jspecify`.
- Async: Reactor `Mono`/`Flux`; no blocking in the executor.
- Planner/executor tests are YAML fixtures under `gateway/engine/src/test/resources/schemas/<schema>/`:
  `schema.yaml` plus `planning/*.yaml` (`expectedPlan`, checked by `OperationPlannerTest`) and
  `executions/*.yaml` (mocked subgraph data + expected response, checked by `ExecutionTest`). Prefer adding a
  fixture over writing a new Java test.
- `test-baseline.json` and the README test report are updated by CI on `main`; don't edit them by hand.

## Working on `perf/query-execution`

- All performance work stays on this branch (draft PR feddi-dev/feddi-gateway#53) until it is finished; Andi
  reviews before it is merged into `main`.
- Small commits, each with tests passing. Follow the step order in `docs/perf-query-execution/05-plan.md`.
- feddi is **server-agnostic**: the baseline is plain GraphQL-over-HTTP. Batching is per-subgraph config
  `batching: none | alias | variables` with a gateway-wide default (built-in default `none`); `variables` is checked
  at startup. `SubgraphClient.executeBatch` is abstract (breaking change on this branch): implementations batch,
  delegate (wrappers) or call `SubgraphClient.executeEach` (one request per variable set, failures per entity).
  Details: `docs/perf-query-execution/07-batching.md`.
- Java-subgraph tuning and graphql-java/Spring upstream work are a separate track
  (`docs/perf-query-execution/08-java-ecosystem.md`), not part of this branch.
- After each step, add an entry to `docs/perf-query-execution/06-progress-log.md` and update the status in its
  `README.md`.
