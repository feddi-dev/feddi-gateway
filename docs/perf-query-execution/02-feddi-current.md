# feddi today (as of `912aeb5`)

## Request path

`app/.../FeddiFederationGateway.execute` (around line 300) handles every request like this:

1. Resolve the document: the optional **`DocumentProvider`** (published SPI, `extension/.../DocumentProvider.java`)
   is asked first. This is how **persisted documents** are supported, e.g. an APQ-style provider that looks up
   `extensions.persistedQuery.sha256Hash` (see `e2e-tests/extensions/.../TestDocumentProvider.java`,
   `DocumentProviderIntegrationTest`, and the persisted-query tests in `GatewayE2ETest`). It may return a
   document (feddi skips parse and validate), errors (e.g. `PersistedQueryNotFound`), or empty (feddi falls back to
   `ParseAndValidate`).
2. `OperationNormalizer.normalize` → `Operation.fromOperationDefinition` → `planner.plan(query)`.
3. A new `Executor` per request (with per-request `SubgraphClientAdapter`s) → `Executor.execute`.
4. `GatewayResult(result, normalizedDocument)`. `GraphQLController` passes that **normalized** document to
   `UsageReporter` via `ExecutionOutcome`.

**There is no plan cache.** Even a persisted document is normalized and planned again on every request; the
`DocumentProvider` only saves parsing and validation.

## Planner (`engine/.../planner/`, ~3.7k lines)

| Class | Role |
|---|---|
| `OperationPlanner` (2.6k lines) | Walks the selection tree and records field resolutions into a `PlanningContext`, which builds the `ExecutionPlan` |
| `PathFinder` | For one field, finds direct paths (same subgraph) and indirect paths (through `LookupMoveEdge`s to other subgraphs, checking requirements) |
| `BestPathTracker` | Keeps the cheapest paths |
| `ExecutionPlan` / `ExecutionStep` | Steps with `subgraph`, operation, `dependsOn`, requirements, `repeatedExecution` |

How it works and its limits:

1. **Greedy, one field at a time.** `planFieldSelection` takes `paths.get(0)`, the cheapest path for *this field*
   (`OperationPlanner.java` ~131–139). It never searches over whole plans, so it can't weigh a local choice
   against what that choice costs later on.
2. **Steps are keyed by response path.** `SubgraphPlanKey(subgraph, entryLookup, entryPath)` (~line 312) creates
   one step per place in the response. The same lookup (e.g. Product from products) reached at
   `users.reviews.product`, `users.reviews.product.reviews.author.reviews.product`, and
   `topProducts.reviews.author.reviews.product` becomes several steps that are never merged.
3. **No optimization pass.** `PlanningContext.buildPlan()` goes straight to execution. Nothing merges, removes
   duplicates or batches.

## Executor (`engine/.../executor/Executor.java`, ~1.3k lines)

- Builds a reactive graph of steps (`buildReactiveGraph`). Each step is a cached `Mono` that waits on its
  dependencies via `Mono.zip`. Steps that don't depend on each other already run in parallel, which is fine.
- **Repeated steps fan out per entity** (~line 342–358): `Flux.fromIterable(contexts)` makes one subgraph call per
  parent entity, each with its own requirement variables.
- **No dedup.** The same entity (e.g. `Product upc=1`) is fetched many times within one request.
- `SubgraphClient` (`execute(OperationDefinition, Map<String,Object> variables)`) has no batch method.

### Why it's slow

The executor makes one call per entity, and identical steps never get merged. A rough count for the benchmark
query over the benchmark data comes to **roughly 500 subgraph HTTP calls per client request**. Fusion and Hive group
work per (subgraph × dependency depth) and need about 10–15 calls. With 50 concurrent users, that explains the
~2.5 s average latency and ~19 RPS. The exact current number will be measured by the call-count test (step 0 in
[05-plan.md](05-plan.md)).

## Tests

`test-baseline.json` (at `912aeb5`): engine 1,129 · app unit 14 · app integration 341 · e2e 31.

| Category | Count | Where | What it checks |
|---|---|---|---|
| Planning | 132 | `engine/src/test/resources/schemas/*/planning/*.yaml`, run by `OperationPlannerTest` | The exact plan: per-step subgraph, operation text, `dependsOn`, requirements, `repeatedExecution` (when `expectedPlan` is present) |
| Execution | 209 | `schemas/*/executions/*.yaml`, run by `ExecutionTest`, `ExecutorTest`, `NestedEntityTargetTest`, `LegacyExecutionPlanTest` | The response against mocked subgraphs |
| Composition | 39 success + 76 error | `test/resources/composition/` | Supergraph composition and validation rules |

The benchmark query itself is covered in `schemas/benchmark_heavy_query/`:
- `planning/01_heavy_query.yaml`: the full query, but **no `expectedPlan`**. It only asserts that planning doesn't
  throw.
- `executions/01–04`: parts of the query (nested product lookups, null lookups, the same product reached from
  different response paths).

### Gaps

1. **Nothing checks the full heavy query's result** with both roots against benchmark-like data. So we can't
   confirm whether `af2666d` fixed the `topProducts` mis-plan.
2. **No efficiency tests.** No test asserts how many subgraph calls a query makes or whether they are batched,
   so today's ~500 calls pass every test.

## CI

- `.github/workflows/test.yml` runs only on PRs into `main` and pushes to `main`. Pushing to a feature branch
  alone does not run CI.
- A PR gate compares tests and coverage against `main`'s `test-baseline.json`. Every push to main recommits the
  baseline (`Update test baseline [skip ci]`).
- Local run: `./scripts/run-all-tests.sh` (`-c` allows cached results).
