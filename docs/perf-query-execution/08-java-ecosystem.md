# Java ecosystem track

The gateway work (this branch) makes feddi efficient against **any** subgraph server. Java subgraphs (Spring for
GraphQL, DGS, plain graphql-java) also need tuning on the subgraph side. The feddi team maintains graphql-java and
has good connections to the Spring for GraphQL team, so some gaps can be closed upstream.

This track is separate from `perf/query-execution`. Parts 1 and 2 belong to the same timeframe; part 3 depends on
the measurements.

## Why it matters

With alias batching, feddi sends one call with `e0 … e29: product(upc: …)` instead of 30 calls. If the subgraph has
**no DataLoader** for its lookups, it still runs 30 lookups (e.g. 30 DB queries) for that one call: fewer HTTP calls,
same backend load. Gateway-side batching and subgraph-side DataLoaders only give the full benefit together.

## 1. Now: tuning guide for Java subgraphs

To be published in the feddi docs when alias batching ships.

1. **Back `@lookup` fields with a DataLoader.** This is the biggest win. Aliased lookups are root fields, which
   graphql-java resolves in parallel, so a DataLoader turns N of them into one backend call. In Spring for GraphQL,
   `@BatchMapping` only covers child fields; for root lookups, load through a `DataLoader` in the `@QueryMapping`
   and return a `CompletableFuture`.
2. **Enable the parsed-document cache.** Spring for GraphQL doesn't enable one by default. Register a
   `PreparsedDocumentProvider` via `GraphQlSource.Builder#configureGraphQl(...)` (see Spring docs, "Request
   Execution"). feddi rounds alias batch sizes to fixed buckets so the cache gets hits.
3. **Allow the batch size in security limits.** Alias, complexity and depth limits must allow feddi's maximum batch
   size, or feddi's maximum must be configured below them.
4. **Make lookups nullable** (`Product`, not `Product!`). Otherwise one missing entity nulls the whole batch. The
   spec recommends this and feddi's composition warns about it.
5. **Transport:** enable HTTP/2 for internal traffic where the server supports it, keep connections alive, and
   enable virtual threads (`spring.threads.virtual.enabled=true`) for blocking resolvers.
6. **Keep per-field instrumentation off the hot path.** Per-field tracing is expensive at high entity counts.

## 2. Now: reference subgraph and measurements

- A **Spring for GraphQL subgraph in `e2e-tests/`** (plan step 5), built according to the guide. It is both the
  vanilla-compatibility test and the example the guide points to.
- **Measure** against it: per-entity vs alias vs alias + DataLoader (+ document cache), and a quick prototype of
  variable batching. Record backend calls, subgraph CPU and latency.
- Expectation (not yet measured): alias + DataLoader + document cache gets close to variable batching. The
  remaining costs are larger documents and one parse per bucket size.

## 3. Later, depending on the measurements: upstream work

| Where | What | Why |
|---|---|---|
| graphql-java | Execute **one document with many variable sets**: parse and validate once, execute N times, share one `DataLoaderRegistry` across all N executions so N lookups become one batch load | The server-side core of variable batching; every Java framework benefits |
| Spring for GraphQL | HTTP support for variable batching once [graphql-over-http#307](https://github.com/graphql/graphql-over-http/pull/307) settles (addresses Clozel's content-type/media-type objections in [#817](https://github.com/spring-projects/spring-graphql/issues/817)); possibly an opt-in handler on a separate path before that | Makes `batching: variables` usable for Spring and DGS subgraphs |
| feddi | Detect and use it automatically (`variables` mode + startup check) | No user config beyond enabling it |
| feddi (optional) | Support **Andi-style batch fields** (lists of keys and requirements) via a feddi directive | Cheapest resolution, works over a vanilla transport |
| GraphQL Federation spec | Bring the working implementation to [#25](https://github.com/graphql/graphql-federation-spec/issues/25): variable batching as the normative reference, and/or a schema-level batch option | A spec contract backed by real implementations |

If the measurements show alias + DataLoader is close enough, the upstream work is about standardization and
cleaner semantics rather than speed. That is still valuable, but not urgent.
