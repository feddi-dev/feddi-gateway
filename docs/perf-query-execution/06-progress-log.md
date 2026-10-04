# Progress log

Add an entry after each step. Call counts come from the heavy-query call-count test (exact). RPS comes from local
benchmark runs (relative only; note the machine and settings).

| Date | Commit | Step | Subgraph calls / heavy query | Local RPS (feddi / fusion) | Notes |
|---|---|---|---|---|---|
| 2026-10-03 | `912aeb5` | baseline | ~500 (estimated, not yet measured) | — | Published: 19 RPS (.NET, Synthetic), wrong results with Rust subgraphs, at `5ff8b61` |
| 2026-10-04 | step 0 | baseline measured | **337** (13 plan steps; accounts 60, inventory 135, products 131, reviews 11) | — | `BenchmarkHeavyQueryTest`: full heavy query matches a monolith oracle, also with random subgraph completion order (20 runs). The `topProducts` mis-plan from the published run is **no longer reproducible** on `main`. Budget `MAX_SUBGRAPH_CALLS = 337` |
| 2026-10-04 | step 0 | local benchmark baseline | 337 | **22.6** / — | `scripts/local-benchmark.sh` (Rust subgraphs in Docker, 50 VUs, 30 s, MacBook 8 cores). 100% checks passed with Rust subgraphs. Latency med 715 ms, p95 6.6 s, max 17.8 s. No Fusion comparison (needs .NET) |
| 2026-10-04 | `9254078` | 1. plan cache | 337 (unchanged; cache saves CPU, not calls) | **31.1** / — | Same setup as baseline. p95 2.9 s (was 6.6 s), max 4.4 s (was 17.8 s); median 1.24 s (was 0.72 s; single runs are noisy). Also fixed a race in a test helper (`ExecutingMockSubgraphClient`) that made `NestedEntityTargetTest` flaky |
| 2026-10-04 | `32dfe70` | 2. entity dedup | **26** (accounts 3, inventory 8, products 4, reviews 11) | **387.9** / — | Same setup. Median 106 ms, p95 211 ms, max 723 ms; 100% checks. Each unique entity fetched once per step; extra positions get deep copies; failed calls reported once |
| 2026-10-04 | step 3a | 3a. share identical calls per request | **20** (accounts 2, inventory 5, products 2, reviews 11) | — | `SharedCallSubgraphClient`: identical query calls (subgraph + operation + variables) from different plan steps run once per request; extra consumers get deep copies; mutations never shared. Known gap: `ExecutionListener.onSubgraphFetchComplete` still fires per consumer, fixed with batch metrics in step 4. CI coverage gate fixed with `DependentStepExecutionTest` |
