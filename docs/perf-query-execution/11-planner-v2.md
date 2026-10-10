# Planner v2

Working notes for `perf/planner-v2` (branched from `perf/query-execution`). The goal: plan complex graphs at least
as well as ChilliCream Fusion, measured on Fusion's own planner tests.

The benchmark schema is too simple to judge a planner: every field lives in exactly one subgraph, so there is no
choice to make. Fusion implements the same composite-schema spec, plans with a cost-based search and has an
extensive planner test suite, so it is the reference.

## Measuring

| Tool | What it measures |
|---|---|
| `FusionPlanParityTest` (engine tests) | Plans the 91 imported Fusion planner tests (`engine/src/test/resources/fusion-planning`) with feddi, composed like a gateway reload, validates every subgraph operation, and compares steps and depth with Fusion's plan. Results are recorded in `parity-baseline.yaml`; any change fails the test until the baseline is updated (`./gradlew :engine:test -PupdateFusionParity`), so every planner commit carries its numbers. |
| `tools/planner-compare` | Runs feddi's fixtures and the Fusion tests through both gateways against the same mock subgraphs and measures operations, depth and whether both return the same data. See its README. |

## Baseline (planner v1, 2026-10-10)

Fusion parity, composed like a gateway reload: **47 of 91** Fusion tests planned. 26 failed composition although
Fusion composes all of them, 9 failed to plan, 9 produced an invalid subgraph operation.

`tools/planner-compare` on feddi's own fixtures (132 queries, 119 ran on both gateways): feddi sent more operations
than Fusion for 17 queries and had more depth for 5; it was better in 1.

## Composition: aligning with the spec

Fusion composes all of its tests; feddi rejected 26. Each deviation is checked against the spec text
(composite-schemas-spec, Section 4 for the rules). Where the spec agrees with Fusion, feddi changes:

| Rule | feddi before | Spec | Change |
|---|---|---|---|
| `LOOKUP_RETURNS_NON_NULLABLE_TYPE` | error | severity WARNING (the only warning-level rule) | warning |
| `INVALID_FIELD_SHARING`, key exemption | only `@key` fields | fields of a key; a key "that could be inferred from a lookup field's arguments MAY be omitted" (#224, 2026-07-09) | lookup argument fields count as keys; `@internal` types skipped |
| `INVALID_FIELD_SHARING`, root lookups | exempt | no exemption for root fields | root lookups in several schemas need `@shareable` or `@internal` |

## Release notes (breaking)

- **Root fields defined by several source schemas must be `@shareable` (or `@internal`), lookups included.**
  The composite-schema spec has no exemption for lookups; Fusion rejects these schemas too
  (`INVALID_FIELD_SHARING`). Before, feddi accepted lookup fields like `Query.productById` in several subgraphs
  without `@shareable`. Fix: add `@shareable` to every definition, or `@internal` to the ones that only serve
  entity resolution (like the benchmark's Reviews and Inventory subgraphs do).
- Non-null `@lookup` fields compose with a warning instead of failing.

## Open questions for Andi

- `composition/errors/lookup_does_not_imply_shareable.yaml` (June) asserted that a lookup does not make fields
  shareable. Spec PR #224 (July 9) added that keys may be inferred from lookup arguments, and Fusion treats such
  fields as keys. The fixture was replaced by rule tests that follow the current spec.
