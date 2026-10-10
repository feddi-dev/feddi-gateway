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

## Status (2026-10-10)

| | v1 start | overnight run | now |
|---|---|---|---|
| Fusion parity: tests planned (`FusionPlanParityTest`) | 47 of 91 | 53 of 91 | 58 of 91 (4 have invalid queries) |
| Correct data vs monolith (`PlanExecutionOracleTest`) | 170 of 223 | 181 of 223 | 194 of 227 |
| Wrong data without an error (oracle "different data") | | 4 | 0 |

Done: comparison tool, Fusion test import, parity and oracle tests, four composition rules aligned with the
spec, lookups below a type condition only run for that type, entity fields below abstract fields go into inline
fragments. Since the overnight run:

- Fields in an inline fragment that resolve in another subgraph go to that subgraph's plan; their lookup keys are
  selected inside the fragment, and lookup steps carry the type conditions along their entity path
  (`ExecutionStep.entityPathTypes`), so `... on UserReview { product { x } }` only looks up UserReview products.
- Lookups that return an interface or union (`node(id:): Node`) resolve the types that implement it, also from
  subgraphs without the abstract type; the step selects the type's fields in `... on Product`.
- `@skip`/`@include` on a fragment with a redundant type condition are kept (they were dropped, so the fields were
  always selected); variables used only inside fragments are declared in the subgraph operation.
- Requirements like `comments[authorId]`, whose leaf needs a lookup per list item, are resolved; a requirement
  that cannot be resolved fails planning instead of running the step without its value.

Next, in order: (1) `@provides` for requirements (`Provides_With_Requires_Interaction` now fails planning).
(2) "Cannot find path" for fields below duplicate or impossible type fragments (2 tests). (3) Nested lookups
(`lookups { brandById }`), lookups with extra arguments, key requirements (`Plan_Key_Requirement`,
`Requires_Circular_2`). (4) Step and depth gaps against Fusion: equal lookups of several union members are not
merged (13 steps vs 6 in one test), sibling-aware subgraph choice. (5) Arguments in FieldSelectionMap
(`price(withDiscount: true)`). feddi keeps rejecting `@require` fields from the requiring schema itself (spec:
other schemas only; Fusion is more lenient there, 8 tests).

## Composition: aligning with the spec

Fusion composes all of its tests; feddi rejected 26. Each deviation is checked against the spec text
(composite-schemas-spec, Section 4 for the rules). Where the spec agrees with Fusion, feddi changes:

| Rule | feddi before | Spec | Change |
|---|---|---|---|
| `LOOKUP_RETURNS_NON_NULLABLE_TYPE` | error | severity WARNING (the only warning-level rule) | warning |
| `INVALID_FIELD_SHARING`, key exemption | only `@key` fields | fields of a key; a key "that could be inferred from a lookup field's arguments MAY be omitted" (#224, 2026-07-09) | lookup argument fields count as keys; `@internal` types skipped |
| `INVALID_FIELD_SHARING`, root lookups | exempt | no exemption for root fields | root lookups in several schemas need `@shareable` or `@internal` |
| `UNREACHABLE_TYPE` (feddi-only lint) | error | not a spec rule; Fusion composes such schemas (e.g. a union in one schema that only contributes members) | warning |

## Release notes (breaking)

- **Root fields defined by several source schemas must be `@shareable` (or `@internal`), lookups included.**
  The composite-schema spec has no exemption for lookups; Fusion rejects these schemas too
  (`INVALID_FIELD_SHARING`). Before, feddi accepted lookup fields like `Query.productById` in several subgraphs
  without `@shareable`. Fix: add `@shareable` to every definition, or `@internal` to the ones that only serve
  entity resolution (like the benchmark's Reviews and Inventory subgraphs do).
- Non-null `@lookup` fields compose with a warning instead of failing.
- Types that are unreachable from a source schema's root types compose with a warning instead of failing.

## Open questions for Andi

- `composition/errors/lookup_does_not_imply_shareable.yaml` (June) asserted that a lookup does not make fields
  shareable. Spec PR #224 (July 9) added that keys may be inferred from lookup arguments, and Fusion treats such
  fields as keys. The fixture was replaced by rule tests that follow the current spec.
