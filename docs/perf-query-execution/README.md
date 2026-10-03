# Query execution performance

Working notes for the `perf/query-execution` branch. The goal is to make feddi produce correct results in the
[ChilliCream graphql-gateway-benchmarks](https://github.com/ChilliCream/graphql-gateway-benchmarks)
and come close to the leading gateways in throughput.

All work stays on this branch until it is finished. Andi then reviews it before it is merged into `main`.

## Status

| | |
|---|---|
| Branch | `perf/query-execution` (from `main` @ `912aeb5`) |
| Current step | 0, tests (not started) |
| Benchmark result (published, commit `5ff8b61`) | Rust subgraphs: wrong results · .NET subgraphs: ~19 RPS (leaders ~2,400–2,900) · Burst: not run |

## Files

| File | Contents |
|---|---|
| [01-benchmark.md](01-benchmark.md) | What the benchmark measures: the query, data, test modes and published results |
| [02-feddi-current.md](02-feddi-current.md) | How feddi plans and executes today, why it is slow, and what the tests cover |
| [03-reference-fusion.md](03-reference-fusion.md) | ChilliCream Fusion's planner and what to adopt from it |
| [04-reference-hive.md](04-reference-hive.md) | The Guild Hive Router's planner and what to cherry-pick from it |
| [05-plan.md](05-plan.md) | Step-by-step plan, workflow and open decisions |
| [06-progress-log.md](06-progress-log.md) | Measurements and notes after each step |

## Summary

- feddi makes **one HTTP request per entity** for every lookup step, never removes duplicate entities, and never
  merges identical lookup steps. For the benchmark query that adds up to roughly 500 subgraph calls per request,
  while Fusion and Hive make about 10–15 batched calls.
- feddi also re-plans every request (there is no plan cache).
- The fix, in order: batch lookups (variable batching), remove duplicate entities, merge identical steps, then
  (optionally) a cost-based planner modelled on Fusion. Fusion implements the same composite-schema spec as feddi,
  so it is the base design. Hive Router contributes the repeat-until-stable merge passes and entity dedup.
