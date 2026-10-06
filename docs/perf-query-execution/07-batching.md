# Batching: research and decision

How feddi sends N entity lookups to a subgraph without making N HTTP calls, and what each option requires from the
subgraph server.

## Batching mechanisms

| Mode | What goes over the wire | Works with a vanilla (spec-compliant) server |
|---|---|---|
| per-entity (today) | one request per entity | ✅ |
| **alias** | one plain request: `query($k0: ID!, $k1: ID!) { e0: product(upc: $k0) {…} e1: product(upc: $k1) {…} }` | ✅ |
| **variables** | one request, `"variables": [ {…}, {…} ]`; response is JSONL (or an array) with one result per entry | ❌ needs server support |
| requests | JSON array of complete requests in one body | ❌ needs server support |
| batch fields (Andi's proposal, see below) | plain request against a subgraph field that takes lists of keys and requirements | ✅, but the subgraph schema needs extra fields |

## What the specs say

### GraphQL Federation spec (formerly "Composite Schemas")
- Repo: https://github.com/graphql/graphql-federation-spec. Draft: https://graphql.github.io/graphql-federation-spec/draft/
  (checked 2026-10-03).
- The text has **no mention of batching**, and not even of HTTP. The executor chapter is two sentences, so how a
  gateway talks to subgraphs is up to the implementation.
- `LOOKUP_RETURNS_LIST` (error): a `@lookup` field must return a single entity. List or batch lookups are not
  allowed. feddi enforces this (`LookupReturnsListRule`).
- Discussion:
  - [#25 "Finding the right Batching Mechanism"](https://github.com/graphql/graphql-federation-spec/issues/25)
    (Michael Staib, open since 2024-03). The core problem: `@require` needs a value per entity, which a plain
    list-of-keys lookup can't carry.
    - **Andi's comment** ([2024-03-16](https://github.com/graphql/graphql-federation-spec/issues/25#issuecomment-2001052662)):
      the subgraph could expose a root field taking the keys *and* the requirement values as lists, e.g.
      `ordersDimensions(ids: $ids, dimensions: $requirements)`. This "would not require any special batching
      mechanism at all". xuorig supported explicit resolvers in 2024-09. It was not discussed further.
    - Later comments (Grafbase, others) lean toward variable batching.
  - [#181 "batch lookup"](https://github.com/graphql/graphql-federation-spec/issues/181) (closed 2025-03). Dariusz
    Kuc (editor) explains that batch `@lookup`s are not supported, "at least not in the first version", because of
    `@require`. He calls aliasing possible but complex, and suggests **variable batching (plus request batching if
    necessary)**.
  - [#27](https://github.com/graphql/graphql-federation-spec/issues/27): the batch *protocol* is handed off to the
    GraphQL-over-HTTP working group.

### GraphQL-over-HTTP
- `rfcs/Batching.md` describes request batching (implementations: Apollo, graphql-php, graphql-ruby, HotChocolate).
- [graphql-over-http#307 "Adds Batching Proposal"](https://github.com/graphql/graphql-over-http/pull/307)
  (Michael Staib, open since 2024-08) and [#308 "Add Request Batching Appendix"](https://github.com/graphql/graphql-over-http/pull/308)
  are both still open (#307 last updated 2026-08-26). #307 adds `Appendix B -- Batching` to the HTTP spec and says
  it is "in the first place meant for Subgraphs/Source Schema in a federated graph".

### Working group answer (2026-10-04)

Leif asked Michael Staib (ChilliCream, federation spec WG) whether the federation spec will say something about
batching. Answer, paraphrased:

- Batching **is a v1 feature**, but it is **solved by the GraphQL-over-HTTP spec**, not by the federation spec itself.
- Both ChilliCream's (Fusion) and Apollo's implementations use **variable batching**.
- Apollo is working on its planner update.

Consequences for feddi:

- `variables` is the standard direction. Our wire format (variables array in, JSONL with `variableIndex` out)
  matches HotChocolate and #307. Check it against the final appendix once #307 is merged (content type, field names,
  ordering).
- `alias` stays as the bridge for servers without variable batching (today: Spring for GraphQL, DGS, plain
  graphql-java servers, graphql-js).
- Closing the Java gap (graphql-java + Spring for GraphQL supporting variable batching) moves from "later, depending
  on measurements" to planned work: see [08-java-ecosystem.md](08-java-ecosystem.md).
- Possible later default: `variables` when the probe confirms support, otherwise `alias`. For the release that
  merges this branch the built-in default stays `none`.

### Our view on where batching belongs
- The **wire format** belongs in GraphQL-over-HTTP: it is transport-specific and useful beyond federation, and server
  frameworks implement transport specs.
- The **contract** should be in the federation spec: what a gateway may assume about subgraphs (required? declared
  capability?), how results per entity behave (order, nulls, partial errors), and ideally a schema-level option that
  works over a vanilla transport (Andi's batch fields). Apollo Federation put batching in the schema (`_entities`),
  which is why it works with every server. The new spec puts it in the transport, which makes efficiency depend on
  servers adopting an extension that is not yet standard.

## What servers support (checked 2026-10-03)

| Server | variables | requests | Evidence |
|---|---|---|---|
| graphql-java (engine) | n/a | n/a | No HTTP layer; executes one `ExecutionInput` at a time. "Batch" in graphql-java means DataLoader |
| Spring for GraphQL | ❌ | ❌ | `SerializableGraphQlRequest.variables` is a `Map`. [#817](https://github.com/spring-projects/spring-graphql/issues/817) (Clozel, closed 2024-07): not adding features at odds with the spec; can't tell batched from single requests on the same endpoint and content type; no response media type. [#693](https://github.com/spring-projects/spring-graphql/issues/693) (Stoyanchev): every request in a batch is as slow as the slowest one; recommends HTTP/2 instead |
| Netflix DGS | ❌ | ❌ | Uses Spring for GraphQL as its transport ([dgs#1608](https://github.com/Netflix/dgs-framework/issues/1608)) |
| graphql-java-kickstart servlet | ❌ | ✅ | `GraphQLBatchedQueryResult`; last commit 2024-10 |
| HotChocolate | ✅ | ✅ | used by Fusion and by the benchmark subgraphs |
| graphql-js (plain) | ❌ | ❌ | feddi's e2e subgraphs |

## Decisions

1. **feddi is server-agnostic.** The baseline is plain GraphQL-over-HTTP (one query plus one variables object). Any
   other capability is opt-in per subgraph and is never required.
2. **Configuration**, with a gateway-wide default and a per-subgraph override:
   ```yaml
   # gateway config
   subgraph-defaults:
     batching: alias
   # subgraph config.yaml
   batching: none | alias | variables
   ```
   The built-in default is **`none`** for the release that merges this branch (no behavior change). Revisit
   switching to `alias` after it has been used in practice.
3. **`variables` gets a check at startup or config load**: send a tiny variables-array request. If the subgraph
   rejects it, fail with a clear message ("subgraph X does not support variable batching; use `alias` or `none`").
   *Implemented:* the check runs asynchronously on reload; a rejection (other 4xx, or 2xx without a valid batch
   response) falls back to `alias` with an ERROR log. 401, 403, 408, 429 and 5xx are inconclusive (the probe carries
   no client authorization) and, like an unreachable subgraph, keep `variables` with a warning.
4. **`alias` safety:** a maximum batch size (split above it), and batch sizes rounded up to fixed buckets (1, 2, 4,
   8, …) so subgraph document caches get hits.
5. **SPI:** `SubgraphClient` (published in `dev.feddi:feddi-gateway-extension`) gets a `default` method
   `executeBatch(operation, List<variables>, context)`. The default calls `execute` once per variable set, so
   existing custom clients keep working. The built-in HTTP client overrides it per mode.
   *Changed after review (2026-10-05, breaking):* `executeBatch` is abstract. As a `default` it failed silently:
   a wrapper that overrode only `execute` lost the wrapped client's variable batching, and the fallback failed
   the whole batch when one request failed. Implementations now batch, delegate, or call the static
   `SubgraphClient.executeEach` (one request per variable set; a failed request becomes an error result for that
   entity only). **Release note:** custom `SubgraphClient`s must implement `executeBatch` (usually one line) and
   can no longer be written as lambdas.
6. **Metrics:** the existing per-call subgraph metrics change meaning (fewer, longer calls). Add
   `feddi.gateway.subgraph.batch.size` and `feddi.gateway.subgraph.entities`, and note the change in the release
   notes. `UsageReporter` (client operation usage) is not affected.
7. **HTTP/2 to subgraphs where they support it.** This makes even per-entity calls cheaper.
8. **Proof by tests:** a real Spring for GraphQL subgraph in e2e, with the suite run in `none` and `alias` mode.

## Effects to document for users of `alias`

- Subgraphs see different operation text: trusted-document or persisted-query allowlists will reject it, and
  alias/complexity/depth limits must allow the batch size.
- Subgraph-side analytics and logs change (operation hashes vary by batch size; field usage counts may drop).
- A non-null lookup (`Product!`) that fails nulls the whole batch, so lookups should be nullable.

## Persisted documents

feddi supports persisted documents from clients via the `DocumentProvider` SPI (see
[02-feddi-current.md](02-feddi-current.md)).

**Client → gateway: no conflict.** Persisted documents decide which client operation runs; call reduction happens
later and changes neither the client document nor the response. The plan cache (step 1) makes persisted requests
skip normalizing and planning as well.

**Gateway → subgraph: matters only for subgraphs that accept registered operations exclusively** (a trusted-document
allowlist). feddi always sends *generated* operations, so such subgraphs already have to allow feddi's operations.
How the techniques change the set of operation texts:

| Technique | Changes operation texts? | Effect on subgraph allowlists |
|---|---|---|
| Plan cache, entity dedup | no | none |
| `variables` batching | no (one text for any number of entities) | none |
| Merging identical steps, planner v2 | yes, but a fixed set | regenerate the allowlist on feddi upgrades |
| `alias` batching | one text per batch-size bucket | ~5–10× more texts per lookup |
| `alias-combine` (different steps in one document) | combinations × buckets | too many to register in practice |

Consequences:
- Subgraphs with allowlists: use `batching: variables` (if supported) or `none`; dedup and step merging still apply.
- `alias-combine` is a separate switch, so `alias` can be used without it.
- Generated subgraph operations are printed **deterministically** (tested), so allowlists stay valid across restarts.
- Follow-ups: export of all generated subgraph operations per subgraph; sending persisted IDs to subgraphs.
