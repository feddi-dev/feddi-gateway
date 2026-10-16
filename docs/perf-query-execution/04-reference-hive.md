# Reference: The Guild Hive Router

- Repo: https://github.com/graphql-hive/router (MIT)
- Path: `bin/router/src/query_planner/` (~30k lines with tests) and `bin/router/src/executor/`. There is no
  separate `lib/query-planner` crate anymore.
- Language: Rust.
- Spec: **Apollo Federation** (`@key`, `@requires`, `_entities`). The planning code can't be ported directly to
  composite schemas. Its techniques can.

## Planning pipeline (`planner/mod.rs::plan_from_normalized_operation`)

1. `ast/normalization/`: normalize the operation (inline fragments, merge fields, …).
2. `walker/` (`walk_operation`, `pathfinder.rs`): over a graph of subgraph nodes (`graph/`), find the **best paths
   for each leaf field**.
3. `best.rs` (`find_best_combination`): pick the cheapest combination of those paths, which gives a query tree.
4. `fetch/fetch_graph.rs` (`build_fetch_graph_from_query_tree`): build a DAG of fetch steps.
5. `fetch/optimize/mod.rs`: **run passes repeatedly until the graph stops changing**:
   `merge_passthrough_child`, `merge_children_with_parents`, `merge_siblings`, `merge_leafs`,
   `deduplicate_and_prune_fetch_steps`, `batch_multi_type`, `normalize_selection_sets`,
   `fold_concrete_selections_to_interfaces`. After that, once: `turn_mutations_into_sequence`,
   `fix_conflicting_type_mismatches`, `apply_internal_aliases_patching`.
6. `query_plan.rs`: topological ordering (in-degree) into `Sequence`/`Parallel`/`Fetch`/`Flatten` plan nodes,
   then `query_plan/optimize.rs`.

## Execution (`executor/`)

| File | What it does |
|---|---|
| `execution/plan.rs` (~2.2k lines) | Runs the plan. For an entity fetch it **hashes each representation** (`entity.to_hash(required_selections, …)`), sends each unique one once (`representation_hash_to_index`), and maps results back to every original position (~line 980–1005) |
| `executors/inflight.rs`, `executors/dedupe.rs` | Shares identical in-flight subgraph requests across client requests. The fingerprint includes headers |
| `projection/` | **Compiled response projection**: a per-plan program that shapes the final response, instead of walking it generically |
| `executors/http.rs`, `executors/map.rs` | HTTP subgraph executors |

`bin/differential` is a tool that compares Hive's plans against another planner. This is the model for
validating feddi's planner v2 against v1.

## What feddi cherry-picks

1. **A separate optimization phase that repeats until stable.** Small, independently testable merge/dedupe
   passes over feddi's existing `ExecutionPlan`, run after `buildPlan()`. This can land before any planner
   rewrite.
2. **Entity dedup in a batch.** Hash the key/requirement values, send each unique value once, keep an index to
   write results back to every position.
3. **Compiled response projection** (later; matters for the CPU-bound Synthetic mode).
4. **Differential testing** of planner v2 vs v1.

Not useful for this benchmark: in-flight dedup across client requests, because every k6 request has a unique
`Authorization` header. It's still worth adding later for production.
