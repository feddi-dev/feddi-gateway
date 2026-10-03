# Plan

## Workflow

- All work happens on **`perf/query-execution`**. Nothing gets merged into `main` along the way.
- One commit (or a small group of commits) per step below. Tests pass at every commit, so Andi can review it
  step by step.
- After each step, record the call count and (when measured) local RPS in [06-progress-log.md](06-progress-log.md).
- CI (`test.yml`) doesn't run on branch pushes alone. Either run `./scripts/run-all-tests.sh` locally, or open a
  draft PR into `main` so CI runs on every push (not decided yet).
- When finished: Andi reviews, then the branch is merged into `main`. After that, a PR to
  ChilliCream/graphql-gateway-benchmarks updates `FEDDI_REF` (and the batching config in
  `subgraph-config/*/config.yaml`).

## Steps

| # | Step | Source | Expected effect | Done when |
|---|---|---|---|---|
| 0 | **Tests first**: (a) execution test for the full heavy query (both roots, benchmark-like data, both subgraph schema variants if they differ); (b) a test that counts subgraph calls for the heavy query, recording the current count | — | makes the rest safe to change | tests green; current call count recorded |
| 1 | **Plan cache** keyed by the normalized operation (plus operation name), bounded in size | Fusion / Hive | less CPU per request (Synthetic) | cache hit/miss tests; no behavior change |
| 2 | **Variable batching**: `SubgraphClient` gets a batch method (sends a `variables` array, parses the JSONL / array response). Repeated steps send one call per step instead of one per entity. Per-subgraph opt-in config (`batching: variables \| request \| none`, default `none`), falling back to per-entity calls | Fusion | **largest** (~500 → a few dozen calls) | all execution tests pass with batching on and off; call count drops |
| 3 | **Entity dedup**: within a batch, send each unique key/requirement value once and map results back to every position | Hive | fewer calls / smaller batches | dedup tests (aliases, nulls, overlapping ids) |
| 4 | **Plan merge passes**: after `buildPlan()`, merge identical lookup steps (same subgraph, lookup, selection, dependency depth) across response paths; repeat until stable | Hive (passes), Fusion (merge by schema × depth) | → ~10–15 calls | planning YAMLs updated; call count near Fusion |
| 5 | **Planner v2** (cost-based best-first search, Fusion architecture) behind `planner: v1 \| v2`; differential test runs every planning/execution fixture through both | Fusion (+ Hive differential) | correctness/robustness; plan quality | v2 matches v1 responses on all fixtures and makes no more calls; then becomes the default |
| 6 | **CPU work**: compiled response projection, fewer allocations, JSON handling | Hive | Synthetic RPS | profiling-driven |

Steps 1–4 keep the current planner and should close most of the gap. Step 5 is the riskiest and matters least for
this benchmark. It comes after the tests and batching are in place.

## Measuring

- **In CI (exact):** call-count test against mocked subgraphs.
- **Locally (relative):** ChilliCream's `k6/benchmark.sh` against a local build. See
  [01-benchmark.md](01-benchmark.md#running-it-locally). Needs k6 and the .NET 10 SDK (or Rust). Compare
  before/after and against fusion on the same machine.

## Open decisions

- Draft PR for CI, or local test runs only?
- Order of step 0 vs setting up the local benchmark harness. Proposal: tests first (no installs needed).
- Config naming for per-subgraph batching (`batching:` in subgraph `config.yaml`).
- Should request batching (array of requests) also be supported, or only variable batching at first?
