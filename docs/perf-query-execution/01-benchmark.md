# The benchmark

Repository: https://github.com/ChilliCream/graphql-gateway-benchmarks
Results: `RESULTS_CONSTANT.md` (Synthetic), `RESULTS_CONSTANT_LATENCY.md` (Real-World), `RESULTS_BURST.md` (Burst).

## Setup

- 1 gateway on port `5220` with 4 subgraphs on `5221`–`5224`: **accounts, inventory, products, reviews**.
- feddi runs in the `composite-schema` family (GraphQL composite-schemas spec), next to ChilliCream Fusion.
  The Apollo Federation gateways (Hive Router, Apollo Router, Cosmo, Grafbase, …) run in `apollo-federation/`
  against equivalent subgraphs.
- Subgraphs are implemented in two variants: Rust (`async-graphql` + axum) and .NET (HotChocolate).
- feddi integration: `composite-schema/gateways/feddi/`
  - `install.sh` bundles JDK 25, clones feddi at a **pinned commit** (`FEDDI_REF=5ff8b61…`), builds
    `:app:feddiGatewayDistZip`.
  - `start.sh` starts the gateway and uploads `subgraphs.zip` (built from `subgraph-config/*/config.yaml` plus the
    SDL). It raises `reactor.netty.pool.maxConnections`.
  - The feddi README there documents the "heavy query planner defect" (see below).

## Test modes

| Mode | Load | Subgraph latency |
|---|---|---|
| Synthetic (`constant`) | 50 virtual users, 120 s | none (measures gateway overhead) |
| Real-World (`constant-latency`) | 50 virtual users, 120 s | +4 ms per subgraph call (.NET only) |
| Burst (`burst`) | 50 → 500 → 50 virtual users over 60 s | none |

Each gateway gets 1 warmup run plus 9 measured runs, ranked by median RPS. A request counts only if:
HTTP 200, no `errors` in the response, and a valid response structure.

Every k6 request sends a unique `Authorization: Bearer bench-<vu>-<iter>` header. As a result, sharing in-flight
subgraph calls across *different client requests* won't help here.

## The query (`k6/k6.js`)

```graphql
fragment User on User { id username name }
fragment Review on Review { id body }
fragment Product on Product { inStock name price shippingEstimate upc weight }

query TestQuery {
  users {
    ...User
    reviews {
      ...Review
      product {
        ...Product
        reviews {
          ...Review
          author {
            ...User
            reviews { ...Review product { ...Product } }
          }
        }
      }
    }
  }
  topProducts {
    ...Product
    reviews {
      ...Review
      author {
        ...User
        reviews { ...Review product { ...Product } }
      }
    }
  }
}
```

## Subgraph schemas (composite-schema, .NET)

| Subgraph | Root / lookups | Owns |
|---|---|---|
| accounts | `users`, `me`, `user(id) @lookup` | `User { id name username birthday }` |
| products | `topProducts(first = 5)`, `product(upc) @lookup` | `Product { upc name price weight }` |
| inventory | `productByUpc(upc) @lookup @internal` | `Product { upc inStock shippingEstimate(@require weight, price) }` |
| reviews | `product(upc) @lookup @internal`, `user(id) @lookup @internal`, `review(id) @lookup` | `Product.reviews`, `User.reviews`, `Review { id body author product authorId productUpc }` |

Notes:
- All lookups are **single-entity** (no list lookups). To avoid one call per entity, a gateway has to batch.
- The subgraphs support **variable batching** (POST with `variables: [ {...}, {...} ]`, JSONL response with
  `variableIndex`) and **request batching** (array of requests). See `subgraphs-rust/handler.rs`. Fusion uses this.
- `shippingEstimate` needs `weight` and `price` from products (`@require`), so inventory has to run after products.
- feddi's copy of the SDL adds `@key` to `User`/`Product`, because feddi's composer otherwise reports
  `INVALID_FIELD_SHARING`.

## Data

- 6 users. `users` returns all 6.
- 11 reviews. `User.reviews` always returns reviews 1 and 2 (the `authorId` argument is ignored), and every
  review's author is user 1.
- Products upc 1–4 have 4, 4, 1 and 2 reviews. `topProducts` returns 5 products.

Many entities repeat (product `upc=1`, user `1`), so removing duplicates before a lookup pays off.

## Published results for feddi (commit `5ff8b61`, 2026-06-28)

| Mode | Rust subgraphs | .NET subgraphs | Leaders (.NET) |
|---|---|---|---|
| Synthetic | wrong results (1117 failures over 9/9 runs) | 19 RPS, avg latency 2.5 s | fusion-nightly-net11 2,505, hive-router 2,463 |
| Real-World | — | 21 RPS | — |
| Burst | not run | not run | — |

The failures with Rust subgraphs: the planner attached the root field `topProducts` to a `User` lookup sent to
accounts (`The field 'topProducts' does not exist on the type 'User'`). Commit `af2666d` ("Fix nested entity lookup
targeting", 2026-09-13) changed this area, but no test yet confirms the full query is correct (see
[02-feddi-current.md](02-feddi-current.md)).

## Running it locally

```bash
./k6/benchmark.sh composite-schema/gateways/feddi subgraphs-net constant
```

- Requirements: k6, jq, python3, curl, bash, and the .NET 10 SDK (for .NET subgraphs) or Rust (for Rust
  subgraphs). `taskset` CPU pinning is Linux-only.
- For quicker iteration: `MEASURE_SECONDS=20 BENCH_RUNS=3`.
- To benchmark a local checkout, replace the pinned clone in `install.sh` with a copy of the local build.
- On a laptop, only compare results against each other (before/after, or feddi vs fusion on the same machine).
  They can't be compared with the published numbers.
