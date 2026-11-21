# Design: a Spring-integrated subgraph client

**Status:** proposal (2026-11-21; cost measured). **Area:** `gateway/app` (subgraph clients), `gateway/extension` (SPI).

## Problem

feddi's built-in subgraph client (`DefaultSubgraphClient`) calls subgraphs with its own Reactor Netty
`HttpClient`, not with Spring's `WebClient`. That was a deliberate trade for throughput (`perf/rps`, see
`docs/perf-query-execution/12-rps.md`), but it means none of the Spring setup that hangs off Boot's
`WebClient.Builder` applies to subgraph calls. A Spring developer running feddi as a Spring Boot app would expect
the outgoing calls to behave like any other `WebClient` call in their app:

| Spring feature | Expected for subgraph calls | feddi today |
|:--|:--|:--|
| Spring Security, OAuth2 client | Token relay, or a client-credentials token per subgraph | `Authorization` forwarded; anything else by hand in a `SubgraphRequestHeaderCustomizer` |
| `ExchangeFilterFunction`s | Their filters (logging, auth, signing, retries) | not applied |
| Spring Cloud LoadBalancer, discovery | `lb://reviews` via Eureka, Consul or Kubernetes | not supported |
| Resilience4j, Spring Cloud CircuitBreaker | Circuit breaker or retries per subgraph | not supported |
| Micrometer Observation | `http.client.requests` metrics, a client span per call, MDC correlation | `traceparent`/`tracestate` forwarded unchanged, no span |
| `WebClientCustomizer`, `ClientHttpConnector` beans, properties | Proxy, timeouts, pool and codecs the usual way | feddi's own settings and SSL bundles only |

Today the only way to get any of this is to write a `SubgraphClientFactory` from scratch, including variable and
alias batching, the response size limit and feddi's error handling.

## Proposal

Ship a second built-in client, **`spring`**, built on the Boot-managed `WebClient.Builder`, next to the current
one (named **`native`**). The client is chosen per subgraph, like `batching`:

```yaml
# subgraph config.yaml
client: spring        # spring | native; default from the gateway config
```

```yaml
# gateway config: the default for all subgraphs
client: native
```

### The `spring` client

- `SpringSubgraphClientFactory` takes `ObjectProvider<WebClient.Builder>` and clones the builder per subgraph.
  Everything the user configured on it applies: `WebClientCustomizer`s, filters, the `ObservationRegistry`,
  codecs, and a `@LoadBalanced` builder if the user declares one.
- A new SPI hook in `extension`, `SubgraphWebClientCustomizer` (`customize(String subgraphName,
  WebClient.Builder builder)`), for per-subgraph setup: an OAuth2 registration per subgraph, a circuit breaker
  per subgraph.
- Same behavior as `native` where feddi defines it: header forwarding and `SubgraphRequestHeaderCustomizer`,
  `maxResponseBytes` (as the codec's `maxInMemorySize`, with feddi's error), `sslBundle` (through Boot's
  `WebClientSsl`), `connectTimeoutMs`/`maxIdleTimeMs`/`maxConnections` (on the connector, unless the user
  supplies one), `batching: none | alias | variables`, and the subgraph timeout.
- The body is read as `byte[]` and parsed by the same `JsonTreeReader`, so results, errors and batching are
  identical. The parsing and request-body code is shared with `native`, not copied.
- Observations use the subgraph name as the URI tag (one series per subgraph, not per query).

### Reactor context

Token relay and Spring Security filters read the security context and the exchange from the Reactor context of
the incoming request. The executor subscribes subgraph calls with a cached per-call context, so the request's
context may not reach them today. The `spring` client needs the request's context (or at least Security's and
Micrometer's keys) on each call. To check first; it may need a small executor change.

### What stays

- `native` stays as it is: the fast path, documented as "feddi settings only".
- The `SubgraphClient`/`SubgraphClientFactory` SPI is unchanged; a user-provided factory still replaces both
  built-in clients.
- The parked plain-Netty client (`perf/netty-client`, about +2% in constant mode) would at most become a third,
  opt-in client, since it would have to re-implement everything above by hand.

## Decision needed: the default

| Default | For | Against |
|:--|:--|:--|
| `spring` | What a Spring user expects without reading docs; security, tracing and metrics work out of the box | Costs throughput by default; the benchmark needs `client: native` |
| `native` | Keeps today's performance and behavior; no breaking change | Spring features silently don't apply until the user finds the setting |

**Measured** (AWS, 1 run each, prototype `perf/webclient-cost` `497b715`: subgraph calls through a `WebClient` from
Boot's builder on the same per-loop Reactor Netty clients, same parsing; baseline 4,969 / 2,132 / 4,476 req/s for
constant / constant-latency / burst):

| | constant | constant-latency | burst | gateway cores (constant) |
|:--|--:|--:|--:|--:|
| `WebClient`, Boot defaults (client observations on) (`20261121-173051`) | 4,366 (−12.1%) | 2,116 (−0.8%) | 3,997 (−10.7%) | 99% |
| `WebClient`, `management.observations.enable.http.client.requests=false` (`20261121-175212`) | 4,549 (−8.5%) | 2,112 (−0.9%) | 4,183 (−6.5%) | 97% |

`WebClient` itself costs about 7–9% at full load, its observations about 4% more; latency-bound load is unaffected.

Recommendation: keep `native` as the default; `spring` is opt-in per subgraph for setups that need Spring's
features, documented with its cost. At startup, log that Spring features don't apply to `native` subgraph calls
when beans that would affect them (filters, `@LoadBalanced`, OAuth2 client) are present.

## Plan

1. Check how the request's Reactor context reaches subgraph calls; fix it if needed (with a test).
2. Extract the shared request-body, parsing and batching code from `DefaultSubgraphClient`.
3. `SpringSubgraphClientFactory`, the `client` setting (per subgraph and gateway-wide) and
   `SubgraphWebClientCustomizer`.
4. Tests: the app's integration tests and the e2e tests with both clients; tests for a filter, an observation and
   token relay reaching a subgraph.
5. README section "Subgraph HTTP Calls" and a release note.
