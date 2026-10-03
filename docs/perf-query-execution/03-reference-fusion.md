# Reference: ChilliCream Fusion

- Repo: https://github.com/ChilliCream/graphql-platform (MIT)
- Path: `src/HotChocolate/Fusion/src/Fusion.Execution/`
- Language: C#. Planning code is ~17.7k lines; execution code is larger.
- Spec: **GraphQL composite schemas** (`@lookup`, `@require`, `@internal`, `@is`), the same spec feddi implements.
  That is why Fusion is the **base design** for feddi's planner.

## Planning (`Planning/`)

| File | What it does |
|---|---|
| `OperationPlanner.cs` (~5k lines) | Entry point `CreatePlan`. Best-first search over partial plans: take the cheapest `PlanNode` from the queue, expand its next work item into one branch per candidate source schema or lookup, enqueue, repeat until a plan is complete. `TryBuildGreedyCompletePlan` is a fast path. `PlanFieldWithRequirement` / `PlanInlineFieldWithRequirements` handle `@require`, preferring to inline a requirement into an existing step. |
| `PlanQueue.cs` | Priority queue of plan nodes, cheapest first; expands branches |
| `PlannerCostEstimator.cs` | Scores a branch: cost so far + estimated remaining backlog (with penalties for depth and excess fan-out) + a small adjustment for fields spilling into other schemas or requirements that can be inlined |
| `Backlog.cs`, `WorkItem*.cs`, `OperationWorkItem.cs`, `FieldWithRequirementWorkItem.cs` | Queue of remaining work (selection sets / fields with requirements / lookups) |
| `Partitioners/SelectionSetPartitioner.cs` | Splits a selection set into the part a given schema can resolve and the rest |
| `OperationPlanner.BuildExecutionTree.cs` (~2.9k lines) | Turns plan steps into the execution graph. **Merges structurally identical operations** into a `BatchOperationDefinition` (~line 813) and **groups lookups to the same schema at the same dependency depth into batch execution nodes** (~line 932) |
| `OperationPlannerOptions.cs`, `OperationPlannerGuardrailException.cs` | Limits that stop planning from running away |

## Execution (`Execution/`)

| File | What it does |
|---|---|
| `Nodes/OperationExecutionNode.cs`, `Nodes/OperationBatchExecutionNode.cs` | Run a single operation or a batch against one source schema |
| `Clients/SourceSchemaClientCapabilities.cs` | Per-client capability flags: `VariableBatching`, `RequestBatching`, `AliasBatching` (the default is variable + request batching) |
| `Clients/HttpSourceSchemaClient.cs` (+ `.AliasBatching.cs`, `AliasBatching/*`) | HTTP transport; builds variable-batched / request-batched / alias-batched bodies and splits the responses |
| `OperationPlanExecutor.cs`, `OperationPlanContext.cs`, `ExecutionState.cs` | Run the plan: track dependencies and build up the result |
| `Pipeline/OperationPlanCacheMiddleware.cs` | **Plan cache** |
| `Pipeline/OperationPlanInFlightRelease.cs`, `RequestDeduplicationHandler` (opt-in) | Share identical in-flight work across requests |

## What feddi adopts

1. **Variable batching with per-schema capabilities.** One call per (step × schema) with a `variables` array,
   falling back to per-entity calls when a subgraph doesn't support batching.
2. **Merge identical operations and batch lookups by (schema, dependency depth)**, as in `BuildExecutionTree`.
3. **Plan cache** keyed by the normalized operation (both Fusion and Hive have one).
4. Later: the **cost-based best-first search** (`PlanQueue` + `PlannerCostEstimator`) as planner v2. Take the
   architecture and cost model, not a line-by-line port. Much of Fusion's size comes from `@defer`, subscriptions
   and edge cases we don't need yet.
