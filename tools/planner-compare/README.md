# Planner comparison: feddi vs Fusion

Runs feddi's planner test fixtures (`gateway/engine/src/test/resources/schemas/*/planning/*.yaml`) through feddi
and [ChilliCream Fusion](https://github.com/ChilliCream/graphql-platform) against the same mock subgraphs, and
reports per query:

- **ops**: GraphQL operations the gateway sends to subgraphs (every variable set of a batch counts)
- **depth**: the longest chain of subgraph requests that ran one after another
- **requests**: HTTP requests; Fusion also batches different operations to the same subgraph into one request
  (request batching), so this mixes plan and transport. Ops and depth measure the plan.
- whether both gateways return the **same data**

Fusion implements the same composite-schema spec as feddi and plans with a cost-based search, so it is the
reference for plan quality. The benchmark's subgraph model is too simple to show planner differences; these
fixtures have shared fields, `@require`, `@is`, abstract types and cycles.

## Requirements

- Node 20+ and npm, `zip`, Docker (Fusion runs in a container, no .NET SDK needed on the host)
- JDK 25 (`JAVA_HOME`, or found with `/usr/libexec/java_home -v 25`)
- A feddi distribution built from this checkout:

  ```bash
  cd gateway && ./gradlew :app:feddiGatewayDistZip
  ```

## Running

```bash
cd tools/planner-compare
npm install
node compare.mjs                                  # all fixtures
node compare.mjs --cases complex_nested,cyclic_graph
node compare.mjs --batching none --delay 100
```

| Option | Default | |
|---|---|---|
| `--cases a,b` | all | fixture directories to run |
| `--batching none\|alias\|variables` | `variables` | feddi's batching mode for every subgraph (Fusion uses its default: variable and request batching) |
| `--delay <ms>` | `50` | delay of every mock subgraph response |

Results go to `out/`: `report.md` (summary and tables), `results.json` (all numbers, per-subgraph request
counts, first error), and `plans/<case>/<query>.fusion.json` (Fusion's operation plan, for looking at how
Fusion planned a query differently).

## How it works

- **`mock-subgraphs.mjs`** serves every subgraph of a fixture from its SDL with deterministic data and logs
  each request. Every object has a seed; key fields (from `@key` and lookup arguments in any subgraph) encode
  it, and all other values derive only from type, seed, field and arguments. A field therefore has the same
  value in every subgraph that offers it, so differing data between the gateways points at a real difference
  rather than at the mock. Lists have two items. It answers single requests, variable batches and request
  batches (JSONL), like the benchmark's Rust subgraphs.
- **`fusion-gateway/`** is a minimal Fusion gateway (`HotChocolate.Fusion.AspNetCore`) built into a Docker
  image. It allows `Fusion-Operation-Plan: 1`, so responses include the plan. The archive is composed per
  fixture with the Nitro CLI (`@chillicream/nitro`, same version as the gateway packages).
- **feddi** runs from the distribution ZIP; each fixture is uploaded through the admin endpoint.
- **`compare.mjs`** starts the mocks, composes and starts both gateways per fixture, sends every query once to
  warm up and once to measure, and reads the request log of the mocks. Required variables without a value in the
  fixture get a sample value from their type, so both gateways execute the query instead of rejecting it.
- **Fusion's copy of the schemas** gets `@shareable` on root fields that several subgraphs define. The
  composite-schema spec requires it (Fusion: `INVALID_FIELD_SHARING`); feddi accepts them without. The report
  lists every such adjustment.

Depth is measured from the request timings (a request counts as after another one when it starts after that one
ended), so it reflects what the gateway did, not what its plan says.

## Limits

- The mock returns no nulls and always two list items; plans that only differ on null handling or empty lists
  look the same.
- Abstract types resolve to one concrete type per seed, chosen from the types the subgraph knows. Subgraphs
  with different sets of possible types can then disagree, which can show up as "data differs".
- Fixtures that Fusion rejects for other reasons (e.g. `REQUIRE_INVALID_FIELDS`) show up as Fusion composition
  errors.
- Introspection results differ between the gateways by nature (different composed schemas).
- Ports 6001-6020, 6099, 6100 and 6200-6202 must be free.
