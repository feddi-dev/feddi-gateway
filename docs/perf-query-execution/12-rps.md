# Request throughput (branch `perf/rps`)

CPU work on the request path after planner v2, measured with ChilliCream's GraphQL federation gateway benchmark on
AWS (`c6a.4xlarge`, gateway pinned to 4 of 16 vCPUs; setup and log: `feddi-repo/load-testing/gateway-rps-plan.md`).
Goal: 2–2.5× the requests per second of `main` at `dfd69b4`.

## Results

| Mode | `main` (`dfd69b4`) | `perf/rps` (`3c75a7d`) | ChilliCream's Fusion federation gateway |
|:--|--:|--:|--:|
| constant (50 VUs) | 1,172 | 3,141 (2.68×) | 2,385 |
| constant-latency (4 ms per subgraph call) | 896 | 1,561 (1.74×) | 1,178 |
| burst (up to 500 VUs) | 770 | 3,003 (3.90×) | 2,303 |

Median of 3 measured runs after a warmup, CV below 2% everywhere; separate sessions vary by up to ±7%. At ~3,100 req/s the subgraphs (89% of their cores) and k6 (82%) are close to their limits, the gateway is at 88%. Latency mode is bound by the query's four
sequential subgraph levels (users → reviews → products → inventory), the same for every gateway.

## What changed

1. **One-pass response writer** (`ResponseWriter`, `ResponseShape`, `JsonBuffer`): the response is written as
   JSON bytes in one walk guided by a shape compiled per cached plan, with null propagation by discarding written
   bytes back to the nearest nullable parent. Replaces three walks (internal-field filter, null propagation, field
   order) and Jackson's map serialization. Strings are encoded by the JDK, integers written as digits, the buffer
   handed to Netty without copies.
2. **ParallelGC** as the launcher default (2 GB heap, 1 GB young generation): +33% over ZGC with 1 GB, and lower
   p50/p95 latencies.
3. **Subgraph calls on Reactor Netty's `HttpClient`** instead of Spring's `WebClient`, with request bodies written
   as bytes (query part cached per operation), the client configured with host and port once, and the subscriber
   context cached per call.
4. **Compact maps**: subgraph responses are parsed into `CompactMap`s (keys and values in two arrays, interned keys
   looked up by reference) instead of `LinkedHashMap`s.
5. **Strings as JSON text**: `JsonTreeReader`, a small JSON reader over the response bytes, keeps string values as
   `RawString`s (slices of the JSON text) that the response writer copies as they are; strings are decoded only
   where the executor compares them (`__typename`, error messages and paths). The end of a string is found eight
   bytes at a time (SWAR).
6. **Less subgraph work**: lookup steps with the same operation and dependencies run as one, entities are reused
   across the steps of a request, lookups of the same entities with different selections are merged (planner),
   and subgraphs receive the compact operation text.
7. **Step scheduling** with counters of open dependencies instead of Reactor operators per step.
8. **Per-call and per-entity overhead**: no URL parsing per subgraph call, a named timeout, the subscriber context
   cached per call and per execution, one variables map per entity, single-batch lookup steps without a `Flux`,
   integers written as digits, encoded strings reused by identity.

Tried and reverted: reading the GraphQL request body as bytes with a shared reader (fewer requests per second with
less CPU used).

## Release notes

- **The launcher uses ParallelGC with a fixed 2 GB heap** (was ZGC, 256 MB initial, 1 GB maximum). Set
  `FEDDI_GATEWAY_JVM_OPTS` to replace the launcher's JVM options, e.g. `-XX:+UseZGC -Xmx1g`. The Docker image
  uses ParallelGC too and still sizes the heap to the container's memory limit.
- **New launcher variable `FEDDI_GATEWAY_JVM_OPTS`**: replaces the default JVM options (`JAVA_OPTS` is still
  appended).
- **Invalid entries in a subgraph's `errors`** (not an object with a `message` string) become an error
  `Subgraph <name> returned an invalid error: <entry>` instead of failing the request (or, for an object without
  a message, the error `Unknown error`).
- **Subgraph responses are no longer limited to 256 KB** (WebClient's default in-memory buffer, which made large
  variable batches fail).
- **`DefaultSubgraphClient` and `DefaultSubgraphClientFactory` take a Reactor Netty `HttpClient`** instead of a
  `WebClient` (app classes, not part of the extension SPI). `SubgraphRequestHeaderCustomizer` is unchanged: it
  still receives Spring `HttpHeaders`, now a view on the request's Netty headers.
- Strings from subgraph responses are passed through as the subgraph wrote them, escapes included (e.g. `\u00e4`
  stays escaped); other strings are written unescaped where JSON allows it.
- `GatewayResult.executionResult()` and `Executor.execute()` return `String`s as before;
  `Executor.executeWithInternalFields()` may return `RawString` values (decode with `RawString.decodeAll`).
- Strings with unpaired UTF-16 surrogates are written with `?` in their place (the JDK's UTF-8 encoding) instead
  of a `\u` escape.
- JSON objects in subgraph responses with duplicate field names keep both entries; lookups find the first (JSON
  leaves duplicates undefined; Jackson kept the last).
