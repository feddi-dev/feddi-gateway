# Closing the gap to Fusion 16.7 (analysis)

Since HotChocolate 16.7.0 (2026-10-24), ChilliCream's Fusion federation gateway leads the benchmark again. These
notes cover what Fusion changed, where feddi spends its CPU and memory, and what to change, for the five topics:
I/O threads, response framing, subgraph response parsing, fan-out copies and memory.

## Published results (run #460, feddi `56ea3bf`)

| Mode | fusion-net-11 (16.7.3) | feddi | Gap | Gateway CPU per 1,000 req/s: Fusion / feddi |
|:--|--:|--:|--:|--:|
| constant | 5,017 | 4,137 | −18% | 16.9% / 20.8% |
| burst | 4,725 | 4,150 | −12% | 18.1% / 21.5% |
| constant-latency | 3,428 | 3,283 | −4% | 17.0% / 20.2% |

Peak RSS: Fusion 390 MB, feddi 2,294 MB. The results table now also has a "Memory per Request" column: 116 KB
against 715 KB.

Neither gateway uses its 4 cores fully (85–90%) with 50 VUs, so the subgraphs (and k6) are part of the
response time. Per request, feddi uses about **20% more gateway CPU** than Fusion. Its subgraph traffic is about
the same: entity reuse and step merging are in place, with 8 subgraph calls per request.

## What Fusion changed

- **16.7.0**: request-scoped entity reuse (#10606, #10653, #10661); duplicate lookup targets completed once,
  copied into the others and kept as cursors (#10612–#10614; 324 targets, 7 distinct variable sets in the
  benchmark query); a vectorized JSON tokenizer for subgraph responses that writes into a flat row table
  (#10635, #10644; parsing was about 10% of its CPU); a minimized result writer straight into Kestrel's pipe
  (#10617, #10624); HTTP clients shared across requests (#10609); compact operation text (#10604).
- **16.7.2**: Content-Length instead of chunked encoding (#10700, +4.2%), larger response chunks (#10690),
  faster value completion (#10696), subgraph calls without nested async iterators (#10707), recycled per-request
  arrays (#10711), no HttpClient timeout timer per subgraph call (#10712), pooled timeout tokens (#10713).
- **Benchmark config** (`a272552`, 2026-10-27): `UnsafePreferInlineScheduling` and
  `DOTNET_SYSTEM_NET_SOCKETS_INLINE_COMPLETIONS=1`, which run requests on the socket thread with no thread-pool
  hop. Fusion also runs with `disableDefaultSecurity: true` (no default request limits).

## Where feddi's CPU goes

JFR from our AWS run `20261023-151301` (constant mode, feddi `673256d`, ParallelGC with 2 GB), 83k execution
samples. Each sample is attributed to the innermost frame that matches an area:

| Area | Share |
|:--|--:|
| Reactor operators (step scheduling 8.5%, rest in server/client pipelines) | 22.6% |
| Subgraph response parsing (`JsonTreeReader`) | 12.7% |
| Response writing (`ResponseWriter`, `JsonBuffer`) | 12.4% |
| Executor, other | 11.6% |
| Entity targets and variables (`collectEntityTargets`, `RequirementReader`) | 8.5% |
| Merge and copies (`deepMerge`, `fanOut`, `ResultCopies`) | 6.2% |
| Spring WebFlux | 6.8% |
| Netty (server codec, client, epoll) | ~11% |
| Subgraph body aggregation and copy | 3.0% |
| Parse/plan cache lookups, metrics, Jackson request body | ~5% |

Allocation (JFR allocation pressure): the response `JsonBuffer` **19%**, `ByteBufMono.asByteArray` (subgraph
bodies) **16%**, `CompactMap` **13%**, `JsonTreeReader` values and objects ~7%, the executor's per-entity
objects and immutable-collection iterators most of the rest. 621 young collections of the 1 GB young generation
in about 360 s at ~3,700 req/s: roughly **400 KB allocated per request**. Fusion reports ~30 KB.

## The five topics

### 1. I/O threads

**State:** feddi already works like Fusion's new inline scheduling. There is no `publishOn` or `subscribeOn` on
the request path; requests and subgraph calls run on Reactor Netty's event loops (native epoll, 4 workers with the
gateway pinned to 4 cores), and server and client share them.

**To do:**
- Replace `Mono.timeout` in `TimeoutAwareSubgraphClient`. It schedules a task on `Schedulers.parallel()` for
  every subgraph call and disposes it afterwards: a cross-thread hop twice per call
  (`Schedulers.directSchedule`, `SchedulerTask.dispose` in the profile). Instead, use one timer per request on
  the channel's event loop, or Reactor Netty's `responseTimeout`. This is the equivalent of Fusion #10712.
- Reduce Reactor operators per step in `StepScheduler`, which builds Mono chains per step: 8.5%.
- Measure: io_uring transport (Netty 4.2) against epoll, and 2 against 4 event loops.

### 2. Response framing

**State:** already right. Spring's `EncoderHttpMessageWriter` sets Content-Length for a `Mono<DataBuffer>`
(checked in spring-web 7.0.9), and the body goes out as one buffer.

**To do:** write the response straight into a pooled Netty buffer (from the response's buffer factory) instead
of a new `JsonBuffer` byte array of about 100 KB per request: 19% of all allocation. `JsonBuffer`'s undo
(`reset(position)`) maps onto the writer index.

**Done:** `ResponseWriter` writes into one scratch buffer per platform thread (kept up to 1 MB) and hands the
bytes to a `Sink`; the controller copies them into a buffer from the response's buffer factory (pooled and direct
with Netty), which Netty releases after the write. Same number of copies as before (Netty copied the wrapped heap
array into a direct buffer), no per-request array. Writing straight into the Netty buffer would also save that
copy, but needs `JsonBuffer` on top of `ByteBuf`; left for later.

### 3. Subgraph response parsing

**State:** 12.7% CPU plus 3% aggregation. `asByteArray` copies each body into a new byte array (16% of
allocation), and `JsonTreeReader` builds a `CompactMap`/`RawString` tree (about 20% of allocation).

**To do, in order of effort:**
1. Rent the body arrays from a request-scoped pool and return them once the response is written: `RawString`s
   point into them until then.
2. Profile `readObject` and `skipString` at line level: key interning, `Integer.valueOf` (3.3% self time,
   from boxing small numbers), and growing arrays (`ArrayList.grow` 1.6%).
3. Larger change: a flat token table like Fusion's MetaDb instead of a tree of maps. Merge and the response
   writer would then read rows. Only worth it if 1 and 2 don't close the gap.

### 4. Fan-out copies and entity targets

**State:** 6.2% merge/copies and 8.5% entity targets and variables. `ResultCopies.forMerge` deep-copies a
lookup result for every additional step whose positions it fills (unless it holds only leaves).
`collectEntityTargets` walks the response from the root for every step. `RequirementReader` builds a variables
map per entity before deduplication.

**To do:**
- Key unique entities on the raw key values (as `RawString` bytes) before building variable maps, as in
  Fusion #10614.
- Share the walks to entity positions across the steps of a request while the response's shape doesn't change,
  as in Fusion #10706.
- Copy only what later steps write into. The plan knows which steps merge below a position, so a result whose
  positions get no later merge can be shared without a copy.

### 5. Memory

**State:** the launcher's ParallelGC with `-Xms2g -Xmx2g -Xmn1g -XX:+AlwaysPreTouch` gives a fixed RSS of about
2.3 GB, whatever the load. Pause time is negligible: 1.5 ms median, 0.3% of the time.

**To do:**
- Measure smaller heaps on AWS (constant and burst): `-Xms512m -Xmx512m -Xmn384m`, with and without
  `AlwaysPreTouch`. With ~400 KB per request, a 384 MB young generation still fills only about every 0.25 s.
- Topics 2 and 3 cut allocation by roughly half, which allows a smaller heap again.
- Change the launcher default only with measurements (release note: heap default).

## AWS measurements

`scripts/bench.sh` in `feddi-repo/load-testing/feddi-gateway-benchmark-infra`. `remote/run-benchmark.sh` has
been adapted to the benchmark's folder rename (`composite-schema` → `graphql-federation`). It now builds Fusion
like the published `fusion-net-11` row (.NET 11 JIT, stable packages).

1. Baseline: `--gateways feddi,fusion --runs 5`, all modes, with feddi `main`. This gives the gap on our machine.
2. Memory: `--gateways feddi --modes constant,burst --jvm-opts "…"` with the heap variants from topic 5.
3. Profile: `--profile` with the current `main` (the profile above is from before the last merges).
4. After each change: A/B against the baseline with `--feddi-ref`.

## Order

1. Timeout without `Schedulers.parallel` (topic 1): small change, removes cross-thread hops on every call.
2. Response written into a pooled Netty buffer (topic 2): 19% of allocation.
3. Pooled subgraph body arrays and `JsonTreeReader` hot spots (topic 3).
4. Entity keys from raw values, shared walks, fewer copies (topic 4).
5. Heap defaults (topic 5), measured after 2 and 3.
6. Fewer Reactor operators in `StepScheduler` (topic 1), if the gap remains.
