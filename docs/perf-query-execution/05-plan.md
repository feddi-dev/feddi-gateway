# Plan

## Workflow

- All work happens on **`perf/query-execution`**. Nothing gets merged into `main` along the way.
- Draft PR [feddi-dev/feddi-gateway#53](https://github.com/feddi-dev/feddi-gateway/pull/53) runs CI on every push.
- Small commits, each with tests passing, grouped by the steps below so Andi can review step by step.
- After each step, record the call count and (when measured) local RPS in [06-progress-log.md](06-progress-log.md).
- When finished: Andi reviews, then the branch is merged into `main`. After that, a PR to
  ChilliCream/graphql-gateway-benchmarks updates `FEDDI_REF` and sets `batching: variables` in
  `subgraph-config/*/config.yaml`.

## Principles

- **Server-agnostic:** the baseline is plain GraphQL-over-HTTP; batching beyond that is opt-in per subgraph. See
  [07-batching.md](07-batching.md).
- **No breaking changes to the published extension SPI** (`dev.feddi:feddi-gateway-extension`): add `default`
  methods.

## Steps

| # | Step | Source | Expected effect | Done when |
|---|---|---|---|---|
| 0 | **Tests first**: (a) execution test for the full heavy query (both roots, benchmark-like data); (b) a test that counts subgraph calls for the heavy query, recording the current count | — | makes the rest safe to change | tests green; current call count recorded |
| 1 | **Plan cache** keyed by the normalized operation (plus operation name), bounded in size. `UsageReporter` still receives the client document | Fusion / Hive | less CPU per request | cache tests; no behavior change |
| 2 | **Entity dedup**: per step, send each unique key/requirement value once and map results back to every position | Hive | fewer calls, for every server | dedup tests (aliases, nulls, overlapping ids) |
| 3 | **Merge identical steps** across response paths (same subgraph, lookup, selection, dependency depth); repeat until stable | Hive (passes), Fusion (merge by schema × depth) | ~one call per (lookup × depth) | planning YAMLs updated |
| 4 | **Batching**: `SubgraphClient.executeBatch` (default method); `alias` mode (max batch size, size buckets) including combining different steps to the same subgraph at the same depth; `variables` mode (opt-in, startup check); `batching:` config with a gateway-wide default (built-in default `none`); batch metrics; HTTP/2 to subgraphs | Fusion | ~500 → ~10–15 calls | all execution tests pass in every mode; call count near Fusion |
| 5 | **Spring for GraphQL subgraph in e2e**, set up according to the tuning guide (DataLoader, document cache); e2e suite runs in `none` and `alias` mode | — | proves the vanilla-server baseline | e2e green in both modes |
| 6 | **Planner v2** (cost-based best-first search, Fusion architecture) behind `planner: v1 \| v2`; differential test runs every planning/execution fixture through both | Fusion (+ Hive differential) | better plans: fewer hops and less depth | v2 matches v1 responses on all fixtures and makes no more calls; then becomes the default |
| 7 | **CPU work**: compiled response projection, fewer allocations, JSON handling | Hive | Synthetic RPS | profiling-driven |

Steps 1–4 keep the current planner and should close most of the gap. Step 6 is the riskiest and matters least for
this benchmark.

## Follow-up (not on this branch)

- **Java ecosystem track** ([08-java-ecosystem.md](08-java-ecosystem.md)): publish the tuning guide with the
  release; measure alias vs variables against the Spring reference subgraph; then decide on graphql-java / Spring
  work and on the spec discussion in graphql-federation-spec#25.
- Andi-style batch fields (feddi directive).
- Reconsider switching the default batching mode to `alias`.
- Request batching (`batching: requests`) if a use case appears.

## Measuring

- **In CI (exact):** call-count test against mocked subgraphs.
- **Locally (relative):** ChilliCream's `k6/benchmark.sh` against a local build. See
  [01-benchmark.md](01-benchmark.md#running-it-locally). Needs k6 and the .NET 10 SDK (or Rust). Compare
  before/after and against fusion on the same machine.
