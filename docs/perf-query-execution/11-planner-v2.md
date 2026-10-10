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

## Status (2026-10-10, evening): all Fusion planner tests green

| | v1 start | overnight run | now |
|---|---|---|---|
| Fusion planner tests planned with the monolith's data | 47 of 91 planned, data unchecked | 53 | **72 of 91** |
| Fusion planner tests rejected for a spec reason | | | **19 of 91** (`fusion-planning/spec-rejections.yaml`) |
| Correct data vs monolith (`PlanExecutionOracleTest`, all fixtures) | 170 of 223 | 181 of 223 | 213 of 232 (the rest: the 19 rejections) |
| Wrong data without an error | | 4 | 0 (now also checking `@require` values) |
| Planned with no more steps / depth than Fusion | | | 69 / 60 of 72 |

`FusionTestsGreenTest` fails unless every imported Fusion test is either planned with the monolith's data or
rejected with the reason listed in `spec-rejections.yaml`. Fusion's tests do not validate their inputs; the 19
rejections are invalid queries (a nullable variable for `@include(if: Boolean!)`; valid variants are checked
instead), an undeclared directive argument, paths only satisfiable through `@provides`, a missing required
argument in a FieldSelectionMap, an unused `@external` field, and `@require` paths whose fields exist only in the
requiring schema.

What changed for that, roughly in order (each a commit with numbers):

- **Planner**: per-child path choice (a field is selected in several subgraphs when its children need it,
  e.g. a shared root field); fields of abstract types per possible type when the abstract type cannot resolve
  them as a whole; lookups returning interfaces/unions; nested lookups (`Query.lookups.brandById`); lookup keys
  from other subgraphs; requirements below lists (`comments[authorId]`) and of fields the planner adds itself;
  FieldSelectionMap arguments (`price(withDiscount: true)`, aliased apart from the client's fields, own
  variables); `@override`; `@provides` at any depth; fragments inside lookup steps; type conditions along entity
  paths (`ExecutionStep.entityPathTypes`) and for requirements (`ExecutionStep.possibleTypes`).
- **Executor**: deep merge of step results (one lock); requirement reading moved to `RequirementReader`.
- **Composition**: satisfiability as the spec specifies it (per query path, `UNSATISFIABLE_QUERY_PATH`); all
  source schemas merged before building; FieldSelectionMap argument validity and fields on the type in scope;
  `@override` exempt from field sharing.
- **Oracle**: the mock keeps an entity's type across lookups, picks abstract results per field, and checks every
  `@require` argument value against the required data.

Open (not needed for green, plan quality only): Fusion batches equal lookups of several type branches into one
operation (`Plan_Should_Merge_Nested_Lookups_Across_Union_Members...`: 13 steps vs 6); 12 plans are deeper than
Fusion's.

## Spec readings

Where the draft (graphql/composite-schemas-spec at e5ce483, 2026-10-04) is ambiguous, feddi reads it as follows
(also documented on `SatisfiabilityValidationRule`):

- `ResolveRequirements` restricts candidates to the schemas other than the requiring one and passes that set on
  to `IsReachable`. Taken literally, a lookup's own key (the `id` of the entity the path is on) could then not
  come from the requiring schema either, which makes almost every requirement unsatisfiable, including the
  draft's examples. feddi restricts only the required fields; lookup inputs may come from any schema.
- `CollectExecutablePaths` walks into every possible type of an abstract field in the composite schema. A type
  that only another schema adds to the union (Fusion's `Union_Union_Member_Mix`) is no executable path from a
  schema whose field cannot return it; feddi walks into a possible type only from schemas that can return it.

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
- **Satisfiability errors use the spec's code `UNSATISFIABLE_QUERY_PATH`** (was `SATISFIABILITY_ERROR`) and name
  the failing query path. The check follows the spec: it accepts schemas the old check rejected (e.g. a shared
  root field whose schemas each resolve different fields), and rejects paths that only `@provides` makes
  satisfiable.
- **FieldSelectionMap paths (`@require`, `@is`) must select fields defined on the type in scope.** A field that
  only an interface's implementations or a union's members define needs a type condition (`data<Bar>.bar`,
  `<Product>.id | <Category>.id`). Arguments in paths are now supported and validated (required arguments must
  be given).
- Non-null `@lookup` fields compose with a warning instead of failing.
- Types that are unreachable from a source schema's root types compose with a warning instead of failing.

## Open questions for Andi

- The two spec readings above (requirements and lookup inputs; possible types of abstract fields). Both could
  be raised upstream as clarifications of the draft.

- `composition/errors/lookup_does_not_imply_shareable.yaml` (June) asserted that a lookup does not make fields
  shareable. Spec PR #224 (July 9) added that keys may be inferred from lookup arguments, and Fusion treats such
  fields as keys. The fixture was replaced by rule tests that follow the current spec.
