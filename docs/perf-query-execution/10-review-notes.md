# Review notes (2026-10-04)

A self-review of the `perf/query-execution` work, done on the branch `perf/query-execution-review` so the
benchmark run pinned to `7edf00e` is not affected.

## Method

Besides reading the diff, the key safety properties were checked with **sabotage runs**: break the guarded behavior
on purpose, run the tests, and see whether a test fails. A property that no test catches is either unnecessary or
untested.

| Sabotage | Caught by | Verdict |
|---|---|---|
| `SharedCallSubgraphClient` hands the same result to every consumer (no copy) | only the unit test `SharedCallSubgraphClientTest` | **Gap**: no execution-level test. Added `EntityDedupTest.sharedCallDoesNotLeakFieldsBetweenResponsePaths` (two aliases share a lookup, only one asks for the author's name). It fails without the copy: `name` leaks into the other alias. The copy is necessary. |
| `Executor.fanOut` hands the same result map to every position of a deduplicated entity (no copy) | nothing | **Open question** (see below) |

## Fixed on this branch

- **A test that did not test what it claimed.** `DependentStepExecutionTest` said it covered "single (non-repeated)
  dependent steps below a single object". The planner never creates those for lookups: a dependent step is
  non-repeated only when it has no requirements (`OperationPlanner`, `repeatedExecution = !stepRequirements.isEmpty()`),
  and every lookup has a key requirement. Renamed the test and fixed its Javadoc; `executeSingleDependentStep` stays
  uncovered (as on `main`).
- **Missing execution-level leak test** for call sharing (see the table).
- **Assertions that only served the coverage gate.** `SubgraphBatchingConfigTest.timeoutAppliesToBatches` also asserted
  two unrelated getters. Removed.
- **A test that did not check its claim.** `OperationTextsTest.staysBounded` did not check the bound. It now asserts
  the cache size (`OperationTexts.size()`, package-private).
- **Documented a weak test.** `reloadKeepsVariableBatchingWhenSubgraphIsUnreachable` can only observe that no fallback
  happens; it would also pass if the probe never ran. Noted in the test.
- **Readability.** The batching code passed `Map<Map<String, Object>, List<Map<String, Object>>>` around. Replaced by a
  record `UniqueEntity(variables, positions)`; `batches()` now uses `subList` chunks.
- **Cohesion.** `deepCopy` lived in `SharedCallSubgraphClient` but was also used by `Executor`. Moved to
  `ResultCopies` (`copy`, `copyData`, `deepCopy`).
- **Misplaced Javadoc.** The new batching methods had been inserted between the Javadoc of `EntityResult` and the
  record, detaching it. Fixed.
- **Copy constructor** of `FeddiFederationGateway` (used by `withSubgraphBatching`): commented that it must copy every
  field.

## Answers (2026-10-04, after reading the code)

- **Q1 `fanOut` copy.** Positions of a repeated step come from `collectEntityTargets(step.entityPath())`, i.e. one
  response path, so later steps write the same data for all of them and sharing is safe. Only steps **without** an
  `entityPath` select positions by searching for key fields (possibly across paths). The copy was added in step 2
  (`32dfe70`) as a precaution; it is not on `main`. **Done:** path-less plans were removed on `main`
  (feddi-dev/feddi-gateway#55), merged here, and the copy in `fanOut` is removed (positions share the result's data).
  The copy in `SharedCallSubgraphClient` stays: there one result is shared across different paths.
- **Q2 `executeSingleDependentStep`.** The planner creates a non-repeated dependent step only when it has no
  requirements, i.e. for a `@lookup` without arguments (`type Query { product: Product @lookup }`). feddi composes
  that today; the spec forbids it (`LOOKUP_MUST_HAVE_ARGUMENTS`, error). Rule + removal: feddi-dev/feddi-gateway#56.
- **Q3 field order.** Still open.
- **Q4 error semantics.** Data is unchanged. A failing lookup is reported once per unique entity (`none`) or once per
  batch (`alias`/`variables`) instead of once per position; the timeout listener fires once per batch. Example:
  products subgraph down in the heavy query: ~131 identical errors before, 2 now.

## Path-less plans: history and removal

- Before #49 the executor found lookup targets by searching the response for objects with the required key fields
  (parent-step contexts, then `findMatchingContexts` over the whole response). That hit unrelated objects with equal
  keys (e.g. review `1` treated as user `1`) and mis-targeted aliases: issue #49.
- `af2666d` (2026-09-13) added `entityPath` and made planned steps follow it; the key-field search stayed as a
  fallback for "legacy, manually assembled plans without a path", covered by `LegacyExecutionPlanTest` (`a3db5b3`).
- Nobody builds plans outside the planner (the `engine` module is not published; the extension SPI has no plan
  types; plans are not stored). The planner always sets `entityPath` for lookup steps (all 316 repeated steps of the
  341 fixture queries). Tests use the path-less constructor only for expected plans, which are compared, not executed.
- Decision: remove execution of path-less repeated steps; keep the constructor for tests. Done in
  feddi-dev/feddi-gateway#55 (merged), then the `fanOut` copy was removed here.

## Open questions for Andi

1. *(Answered above.)* **Is the deep copy in `Executor.fanOut` needed?** All positions of one repeated step share the same response path,
   so later steps fetch the same data for all of them; sharing one result map across those positions looks safe, and
   no test fails without the copy. It costs a few percent CPU (deep copies were ~2–4% of samples in the profile).
   Kept for now as a defensive measure; removing it needs a proof (or a counterexample test).
2. *(Answered above.)* **Is `Executor.executeSingleDependentStep` reachable?** The planner only creates a non-repeated dependent step when
   it has no requirements. It was uncovered on `main` too. Either find the case that produces it (and test it) or
   remove it.
3. **Field order is not checked.** The monolith comparisons use `Map.equals`, which ignores key order. GraphQL
   responses should follow the query's field order. Not changed by this work, but none of the new tests would notice
   an ordering regression.
4. **Error semantics changed (intentionally).** With dedup and batching, a failing lookup is reported **once** per
   unique entity or batch instead of once per position. Existing tests pass; worth a line in the release notes.
