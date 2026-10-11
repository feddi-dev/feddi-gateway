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

## Status (2026-10-10, late evening): all Fusion planner tests green

| | v1 start | overnight run | now |
|---|---|---|---|
| Fusion planner tests planned with the monolith's data | 47 of 91 planned, data unchecked | 53 | **72 of 91** |
| Fusion planner tests rejected for a spec reason | | | **19 of 91** (`fusion-planning/spec-rejections.yaml`) |
| Correct data vs monolith (`PlanExecutionOracleTest`, all fixtures) | 170 of 223 | 181 of 223 | 221 of 240 (the rest: the 19 rejections) |
| Wrong data without an error | | 4 | 0 (now also checking `@require` values) |
| Planned with no more steps / depth than Fusion | | | **72 / 72 of 72** |
| Random graphs (`RandomGraphOracleTest`, same data as a monolith) | | | 150 seeds in CI, 2000 checked locally with all scenarios |

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

`RandomGraphOracleTest` generates graphs and operations from fixed seeds and compares feddi's data with a
monolith's (the composite schema executed as one server on the same deterministic data). The 150 seeds run in
every test run, CI included; a seed always generates the same graph, so a failure reproduces. Failing seeds become
hand-written fixtures. Scenarios, each switchable with `-PrandomGraphFeatures=...` (default all): keys by code
(nullable on some types), `@provides`, entity requirements, interfaces, unions, abstract lookups, interface
`@require`, root-field `@require`, `@override`, nested lookups, mutations with several fields, and
`@skip`/`@include` with literals and a variable. `-PrandomGraphSeeds=N -PrandomGraphFirstSeed=K` runs other
seeds, `-PrandomGraphWriteFixture=SEED` writes a seed as a planner fixture (schema, operations, current plans) to
`engine/build/random-fixtures`. Extending it to these scenarios found: mutations rejected when the first source
schema has no Mutation type, a cycle between root requirements of two subgraphs, `@skip`/`@include` fields merged
with keys and requirements, and a duplicate requirement variable (all fixed, with fixtures). 2000 seeds pass.

Nice to have: a nightly CI job running a new range of seeds each night (e.g. 5000 from an offset by date), whose
failures report a seed to reproduce and turn into fixtures; the 150 fixed seeds keep pull requests deterministic.

## Spec compliance

feddi's composition follows the composite-schemas spec draft (graphql/graphql-federation-spec, Section 4): all 66
error codes are reported, each rule checked against the draft's examples and counter-examples. The breaking changes
in the release notes are schemas feddi accepted before although the spec rejects them; they are now rejected as
the spec requires.

What remains:

- **Ambiguous spec text** (4 places, see Spec readings): feddi follows one reading each; raised with the spec
  authors.
- **Not supported yet**: `@require` on mutation fields (resolving it would run another subgraph's mutation field),
  and an `@external` field whose arguments all carry `@require` (any other `@external` field with arguments the
  spec rejects as well).
- **feddi-only warnings** (never block composition): `UNREACHABLE_TYPE`, `LOOKUP_DUPLICATE`,
  `INTERFACE_HAS_NO_IMPLEMENTATIONS`.

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
- `LOOKUP_KEY_MISSING_FOR_TYPE` asks whether "all fields referenced by {alternative} are defined for
  {possibleType}" in the lookup's source schema, while `IS_INVALID_FIELDS` resolves the same `@is` paths across
  the other schemas. feddi takes the possible types from the lookup's schema and accepts fields from any schema.
- `MergeOutputFields` keeps an argument only if every merged field declares it, and does not say whether
  `@external` definitions count. feddi leaves them out: they mirror the field, and `@external` fields cannot have
  arguments anyway (`EXTERNAL_FIELD_HAS_ARGUMENTS`; the spec rejects them through `KEY_FIELDS_HAS_ARGUMENTS`,
  `PROVIDES_FIELDS_HAS_ARGUMENTS` or `EXTERNAL_UNUSED`).

## Composition: aligning with the spec

Fusion composes all of its tests; feddi rejected 26. Each deviation is checked against the spec text
(composite-schemas-spec, Section 4 for the rules). Where the spec agrees with Fusion, feddi changes:

| Rule | feddi before | Spec | Change |
|---|---|---|---|
| `LOOKUP_RETURNS_NON_NULLABLE_TYPE` | error | severity WARNING (the only warning-level rule) | warning |
| `INVALID_FIELD_SHARING`, key exemption | only `@key` fields | fields of a key; a key "that could be inferred from a lookup field's arguments MAY be omitted" (#224, 2026-07-09) | lookup argument fields count as keys; `@internal` types skipped |
| `INVALID_FIELD_SHARING`, root lookups | exempt | no exemption for root fields | root lookups in several schemas need `@shareable` or `@internal` |
| `UNREACHABLE_TYPE` (feddi-only lint) | error | not a spec rule; Fusion composes such schemas (e.g. a union in one schema that only contributes members) | warning |

### Spec check of all composition rules (2026-10-10)

Every error code of the draft's Section 4 (66) against feddi's rules, each rule probed with the draft's examples
and counter-examples. All 66 codes are now reported. Changes:

| Rule | feddi before | Now |
|---|---|---|
| `EXTERNAL_UNUSED` | a field used only by `@key` was an error | `@key` selections count as usage, as the spec says |
| `ENUM_VALUES_MISMATCH` | not implemented: enum values of all schemas were combined | error; values only some schemas know must be `@inaccessible` |
| `LOOKUP_KEY_MISSING_FOR_TYPE` | only unions, only `@is`, only the first path segment (`LOOKUP_UNION_KEY_FIELD_MISSING`) | the spec rule for objects, interfaces and unions, arguments by name or `@is` alternatives |
| `IS_FIELDS_HAS_ARGUMENTS`, `REQUIRE_INCONSISTENT_ON_IMPLEMENTATION`, `REFERENCE_TO_INTERNAL_TYPE` | not implemented (the last: the field was dropped silently) | errors |
| `INVALID_GRAPHQL` | `Composer.compose` threw graphql-java's exception | composition error |
| `INTERFACE_FIELD_NO_IMPLEMENTATION`, `INTERFACE_FIELD_ARGUMENT_NO_IMPLEMENTATION` | `SCHEMA_BUILD_ERROR` | the spec codes (graphql-java's error types mapped) |
| Merge of field arguments | union of all definitions' arguments | only arguments every definition declares (`MergeOutputFields`) |
| `KEY_/PROVIDES_DIRECTIVE_IN_FIELDS_ARG` | misspelled codes | `..._IN_FIELDS_ARGUMENT` |
| `REQUIRE_ON_NON_ENTITY` (feddi-only) | rejected `@require` on interface fields, which the spec allows | removed: interface fields with `@require` are planned per possible type (`schemas/interface_requirements`); satisfiability rejects requirements that cannot be resolved. Root fields with `@require` are supported too (`schemas/root_requirements`) |
| `LOOKUP_DUPLICATE`, `INTERFACE_HAS_NO_IMPLEMENTATIONS` (feddi-only) | errors | warnings: the spec allows several lookups for a type, GraphQL an interface without implementations |

Codes besides the spec's: `EXTERNAL_FIELD_HAS_ARGUMENTS` (the schemas it rejects the spec rejects through other
rules, apart from `@external` fields whose arguments all carry `@require`, which feddi does not support),
`SCHEMA_BUILD_ERROR` (any other failure of building the merged schema, for schemas the spec rejects too) and the
warnings `UNREACHABLE_TYPE`, `LOOKUP_DUPLICATE`, `INTERFACE_HAS_NO_IMPLEMENTATIONS`.

## Release notes (breaking)

Composition now follows the spec where feddi accepted schemas the spec rejects; a schema that composed before can
fail now. The planner and gateway changes keep results the same or fix wrong ones.

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
- **Enums with the same name must have the same values in every source schema** (`ENUM_VALUES_MISMATCH`); a value
  only some schemas define must be `@inaccessible` there. Before, feddi combined the values.
- **A field's arguments in the composite schema are those every source schema declares.** Before, feddi offered
  the union, including arguments some subgraphs do not accept.
- **Every lookup argument must map to a field of every possible type of the lookup's return type**
  (`LOOKUP_KEY_MISSING_FOR_TYPE`, replaces `LOOKUP_UNION_KEY_FIELD_MISSING`).
- New errors from the spec: `IS_FIELDS_HAS_ARGUMENTS`, `REQUIRE_INCONSISTENT_ON_IMPLEMENTATION`,
  `REFERENCE_TO_INTERNAL_TYPE`. Invalid source schemas fail composition with `INVALID_GRAPHQL` instead of an
  exception; broken interface contracts report `INTERFACE_FIELD_NO_IMPLEMENTATION` /
  `INTERFACE_FIELD_ARGUMENT_NO_IMPLEMENTATION` instead of `SCHEMA_BUILD_ERROR`.
- `@require` on interface fields and on root fields is supported (`REQUIRE_ON_NON_ENTITY` is removed; it also
  rejected interface fields). A root field's requirements are selected on the other subgraphs' root types; its
  step waits for them and runs once. `@external` fields used only by a `@key` compose.
- Renamed codes: `KEY_DIRECTIVE_IN_FIELDS_ARGUMENT`, `PROVIDES_DIRECTIVE_IN_FIELDS_ARGUMENT`. `LOOKUP_DUPLICATE`
  and `INTERFACE_HAS_NO_IMPLEMENTATIONS` are warnings.
- **`@skip`/`@include` on variables are evaluated before planning**, with the request's values; the plan cache
  keeps one plan per combination of these values (most operations have none). Subgraph operations no longer
  carry these directives, and a skipped field's requirement lookups are not planned. A condition on a variable
  without a value (and without a default) is a request error.
- **A mutation's top-level fields execute in order.** Before, fields of different subgraphs ran concurrently and
  fields of one subgraph were merged across others (`A1, B, A2` became `{A1 A2}` and `{B}`). Now each group of
  consecutive fields of one subgraph is its own step, waiting for the previous group and its lookups.
  `@require` on mutation fields fails planning (resolving it would run another subgraph's mutation field).

## Performance

Local benchmark (`scripts/local-benchmark.sh`, heavy query, 50 VUs, 30 s, two rounds each, alternating with v1 =
`perf/query-execution` in the same runs; one machine, so relative only):

| | req/s, round 1 / 2 |
|---|---|
| v1 | 923 / 864, then 865 / 903 |
| v2 before optimizing | 828 / 850 |
| v2 now | 897 / 892 |

Re-run on 2026-10-11 after root requirements, field aliasing, mutation ordering and the refactoring (same
script and settings, machine otherwise idle, four rounds alternating, order reversed in rounds 3-4):

| | round 1 | round 2 | round 3 | round 4 |
|---|---|---|---|---|
| v1 req/s (median latency) | 824 (51 ms) | 799 (50 ms) | 849 (45 ms) | 923 (46 ms) |
| v2 req/s (median latency) | 878 (47 ms) | 858 (48 ms) | 850 (48 ms) | 981 (43 ms) |

v2 is ahead in three rounds and level in one; all checks passed. (An earlier run the same day on a busy machine,
load average 40-70, gave 150-250 req/s for both, with v2 ahead in all four rounds.)

What it took (JFR under the benchmark load): `ResponseFieldOrder` collected the fields of the same selection sets
again for every object and list item, about 10% of the CPU samples; it now caches them per response. Planning the
heavy query took 1.47 ms in v2 vs 0.69 ms in v1 (plans are cached, so this only hits a query's first request):
the per-child resolvability check ran also for fields with a single path, and `PathSegment` printed its arguments
for every hash. Now about 0.6 ms (best of 5 × 2000 plans; the field clash check below costs about 3%).

## Review of the v2 code (2026-10-10)

The v2 changes touch 39 files of the engine (+3374 / -1599 lines). Done in this review: the duplicated key loops
of the lookup paths became `addLookupKeys`; `SubgraphPlan` keeps the lookup edge instead of copies of its parts;
the lookup that enters a subgraph is found in one place (`enteringLookup`); unused `ExecutionStep` constructors and
pre-v2 methods without callers are removed; satisfiability finds a schema's lookups for a type once per check.

Follow-up (2026-10-11), each a commit without plan changes unless noted:

- **Requirements indexed in `Graph`** (`requirementsOf`, `fieldHasRequire` is a map lookup instead of a scan of
  all edges).
- **`@require` on root fields** (new): the requirements are selected on the root types of the other subgraphs
  (directly, below a root field, or through lookups); the requiring root step waits for them and runs once with
  the values from the response root (`schemas/root_requirements`; the oracle now checks root requirement values).
- **FieldsConflict fixed** (new): a key or required field the planner adds gets an alias where another field
  with its response key at that position (in the plan or the client's query) cannot be merged with it
  (FieldsInSetCanMerge: same field on the same or an abstract type, same output type otherwise). The graph
  records field types for this. `schemas/typed_internal_fields`; the random graphs make `code` nullable on some
  types, and without the fix 6 of 1000 seeds fail.
- **One code path for fields with and without fragments**: the field tree and fragments share navigation and
  field adding (`SelectionNode`), one method plans fields in and out of fragments, and lookups and requirements of
  fields in fragments use the same code as all others (the fragment copy lacked the fallback for keys the source
  subgraph does not have).
- **`OperationPlanner` split** (3184 → 341 lines): `PlanningContext` (plans, dependencies, lookups, keys),
  `RequirementPlanner`, `FieldPathSelector` (key and requirement paths, aliases), `PlanAssembler` (execution
  steps), `ResponseKeys`, `SubgraphPlan`, `SelectionNode`, `InlineFragmentContext`.

Main code size: 25,874 lines before the follow-up, 25,798 after. Since v2 started (`6a7c591`, 24,180 lines) the
growth is v2's functionality (planner features, `RequirementReader`, spec rules, satisfiability), not the split.

Simplifications (2026-10-11), each a commit without plan changes; main code -1016 lines net:

- **One walker for FieldSelectionMaps against the source schemas**: `SelectionMapWalker` reports a map's fields
  and type conditions to a visitor; `IsInvalidFieldsRule` and `RequireInvalidFieldsRule` (field checks and type
  condition coverage) are visitors. `SourceSchemas` (the types of all source schemas, or all but one) replaces
  `CrossSchemaFieldResolver`. (-697 lines)
- **Requirements through lookups with one recursion** in `RequirementPlanner`: direct lookups and chains of
  lookups both recurse into the target subgraph, whose lookup plan `enterThroughLookup` creates on first use; a
  chain's hop starts from the previous hop's plan. New fixture `requirement_chains` (two lookups). (-89)
- **Shared rule helpers** (`SchemaElements`): built-in types and directive argument strings, which 17 rules and
  9 classes each had their own copy of. (-169)
- **`ExecutionStep.with…` methods** instead of 13-argument copies; unused factory methods removed. (-38)
- **Executor**: one method merges an entity's lookup result (four `synchronized` blocks before), one turns a
  failure into its GraphQL error; one `RequirementReader` per step. (-23)

Afterwards (2026-10-11):

- **Mutation ordering** (see Release notes): `mutation_basic/06-08`. Fusion runs mutation operations one after
  another too but does not list that as dependencies, so the parity depth leaves the ordering of mutation steps
  out.
- **Implied dependencies left out**: a step waiting for 3 and 4, where 4 waits for 3, lists only 4 (10 fixtures).
- **Directive handling removed from the planner**: the supergraph's only executable directives are `@skip` and
  `@include`, and the normalizer now always evaluates them, so a normalized operation has no directives.
  `Operation` rejects one that has; the operation model, `SubgraphPlan`, `SelectionNode` and the planner no
  longer carry directives, and the safeguards for fields under unevaluated conditions (separate fragments per
  condition in the normalizer, combined conditions of nested fragments, no reuse of a conditional field as a key)
  are gone. Fragments without type condition are unwrapped. The fixtures that expected preserved conditions
  (`condition_variables`, formerly `directive_preservation`; `include_skip/05-06`) give variable values; Fusion
  parity evaluates its conditions so that everything is selected (unchanged results).

Left as they are: `SatisfiabilityValidationRule` extracts path sets the way the spec's algorithm does (products
of object fields' alternatives), which is not a validation walk; `LookupKeyMissingForTypeRule` asks whether some
alternative maps a possible type, and keeps its own short walk.

## Open questions for Andi

- The spec readings above (requirements and lookup inputs; possible types of abstract fields; where lookup key
  fields must be defined; `@external` in argument merging). All could be raised upstream as clarifications of
  the draft.

- `composition/errors/lookup_does_not_imply_shareable.yaml` (June) asserted that a lookup does not make fields
  shareable. Spec PR #224 (July 9) added that keys may be inferred from lookup arguments, and Fusion treats such
  fields as keys. The fixture was replaced by rule tests that follow the current spec.
