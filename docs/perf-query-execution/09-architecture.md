# Target architecture

The target for the end of this branch. It keeps feddi's structure (planner → `ExecutionPlan` → reactive `Executor` →
`SubgraphClient`) and adds layers in between.

```
HTTP request
  │
  ▼
GraphQLController
  │
  ▼
Document resolution ── DocumentProvider (persisted docs, SPI) │ parse + validate
  │
  ▼
OperationPlanCache ─── hit ──────────────────────────────┐   step 1
  │ miss                                                  │
  ▼                                                       │
normalize → Operation                                     │
  │                                                       │
  ▼                                                       │
Planner (today's OperationPlanner; v2 decided after step 5)│   step 6
  │                                                       │
  ▼                                                       │
PlanOptimizer: passes until stable + canonical printing   │   step 3
  │                                                       │
  ▼                                                       │
ExecutionPlan (immutable, cached) ◄──────────────────────┘
  │
  ▼
Executor (reactive DAG of steps, as today)
  │  per step:
  │   1. collect targets     – every response position the step serves
  │   2. EntityDeduplicator  – unique keys + index map             step 2
  │   3. BatchDispatcher     – mode per subgraph, chunk by max size step 4
  │        none      → execute() per entity
  │        alias     → AliasBatchRewriter → one execute()
  │        variables → executeBatch()
  │   4. ResultMerger        – fan results back out, rewrite error paths
  ▼
SubgraphClient (SPI)
  └─ DefaultSubgraphClient: reactor-netty, HTTP/2, variables wire format,
     startup capability check                                      step 4
  │
  ▼
Response assembly → UsageReporter / metrics (ExecutionListener)
```

## Components

1. **`OperationPlanCache`** (step 1): our own bounded cache (no new dependency), one per gateway instance, so a
   schema reload (new `FeddiFederationGateway`) clears it. Two-level key: the `Document` instance, then content
   (printed document + operation name). Caches the normalized document and the plan. `UsageReporter` keeps
   receiving the same normalized document as today.
2. **Planner**: today's `OperationPlanner`. If v2 is built (decided after step 5), v1 and v2 coexist only behind an
   internal, test-only switch for differential testing; v1's responses and call counts are recorded as fixtures and
   v1 is deleted before merging. No user-facing planner setting.
3. **`PlanOptimizer`** (step 3, package `planner/optimize`): small `PlanPass`es, repeated until nothing changes
   (`MergeIdenticalSteps`, `PruneEmptySteps`, later `CoalesceStepsPerSubgraph` for `alias-combine`). A final
   `CanonicalizeOperations` pass prints subgraph operations deterministically (needed for subgraph allowlists and
   stable tests).
4. **`ExecutionPlan` model**: a step can serve several target paths (after merging); explicit key/requirement
   extraction per step for dedup and batching; immutable so it can be cached and shared.
5. **Per-step execution** (steps 2 and 4): the reactive DAG stays; the per-entity fan-out
   (`Flux.fromIterable(contexts)`) is replaced by `EntityDeduplicator` → `BatchDispatcher` → `ResultMerger`.
6. **Where the batching modes live:**
   - `alias` is an **engine** concern (pure GraphQL rewriting) and calls the ordinary `execute()` once. It works with
     every `SubgraphClient`, including users' custom ones.
   - `variables` is a **transport** concern: the engine calls `executeBatch(op, List<vars>)`; `DefaultSubgraphClient`
     sends the variables array and parses the JSONL/array response. Custom clients fall back via the default method.
7. **Transport** (`DefaultSubgraphClient`): HTTP/2 where supported; the `variables` wire format; the capability check
   for `batching: variables`.
8. **Configuration**: `batching: none | alias | variables`, `batch-max-size`, `alias-combine` per subgraph;
   `subgraph-defaults:` at gateway level; built-in default `none`.
9. **SPI** (`dev.feddi:feddi-gateway-extension`), non-breaking only: `SubgraphClient.executeBatch` default method;
   later `DocumentProvider.knownDocuments()`.
10. **Observability**: `ExecutionListener` (engine-internal) gets `onBatch(subgraph, batchSize, entityCount)`; metrics
    `feddi.gateway.subgraph.batch.size`, `feddi.gateway.subgraph.entities`.

## Tests

- **Call-count recorder**: a mock subgraph client that records every call; used by the heavy-query call-count test.
- **Execution fixtures in every batching mode** (parameterized), so all existing execution tests check each mode.
- **Determinism**: the same plan produces byte-identical subgraph operations.
- **Differential v1/v2** (only if step 6 happens).
- **e2e**: a real Spring for GraphQL subgraph in `none` and `alias` mode (step 5).
