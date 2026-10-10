// Imports ChilliCream Fusion's planner tests (MIT) as feddi planner fixtures.
//
//   node import-fusion-tests.mjs <graphql-platform checkout> [<target dir>]
//
// Reads src/HotChocolate/Fusion/test/Fusion.Execution.Tests/Planning/*Tests.cs and takes every test
// that composes its source schemas from raw string literals (ComposeSchema("""...""", ...)) and plans
// one operation (PlanOperation(schema, """...""")) with a plan snapshot. For each it writes
//
//   <target>/<TestClass>/<TestMethod>/schema.yaml        subgraphs a, b, c, ... (Fusion's names)
//   <target>/<TestClass>/<TestMethod>/query.graphql      the planned operation
//   <target>/<TestClass>/<TestMethod>/fusion-plan.yaml   Fusion's plan snapshot
//
// Tests that need other setup (options, shared schemas, @defer, subscriptions, theories) are skipped
// and listed in <target>/SKIPPED.md with the reason.

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { stringify } from "yaml";

const source = resolve(process.argv[2] ?? "");
const target = resolve(process.argv[3] ?? new URL("../../gateway/engine/src/test/resources/fusion-planning", import.meta.url).pathname);
const planning = join(source, "src/HotChocolate/Fusion/test/Fusion.Execution.Tests/Planning");
const snapshots = join(planning, "__snapshots__");
if (!existsSync(planning)) throw new Error(`not a graphql-platform checkout: ${source}`);

const commit = execFileSync("git", ["rev-parse", "HEAD"], { cwd: source, encoding: "utf8" }).trim();
const tag = execFileSync("git", ["describe", "--tags", "--always"], { cwd: source, encoding: "utf8" }).trim();
rmSync(target, { recursive: true, force: true });
mkdirSync(target, { recursive: true });

const testBase = readFileSync(join(source, "src/HotChocolate/Fusion/test/Fusion.Execution.Tests/FusionTestBase.cs"), "utf8");
const imported = [];
const skipped = [];
for (const file of readdirSync(planning).filter(f => f.endsWith("Tests.cs")).sort()) {
  const className = file.replace(/\.cs$/, "");
  const text = readFileSync(join(planning, file), "utf8");
  for (const test of splitTests(text)) {
    const result = extract(test, [text, testBase]);
    const id = `${className}.${test.name}`;
    if (result.skip) {
      skipped.push({ id, reason: result.skip });
      continue;
    }
    const snapshot = join(snapshots, `${id}.yaml`);
    if (!existsSync(snapshot)) {
      skipped.push({ id, reason: "no plan snapshot" });
      continue;
    }
    if (/type: Apollo/.test(readFileSync(snapshot, "utf8"))) {
      skipped.push({ id, reason: "Apollo Federation connector (other spec)" });
      continue;
    }
    const dir = join(target, className, test.name);
    mkdirSync(dir, { recursive: true });
    const subgraphs = sourceSchemas(result.schemas);
    writeFileSync(join(dir, "schema.yaml"), stringify({
      name: `Fusion ${className}.${test.name}`,
      description: `Imported from ChilliCream/graphql-platform ${tag} (${commit}), `
        + `src/HotChocolate/Fusion/test/Fusion.Execution.Tests/Planning/${file}. MIT License.`,
      subgraphs,
    }, { lineWidth: 0, blockQuote: "literal" }));
    writeFileSync(join(dir, "query.graphql"), result.query.endsWith("\n") ? result.query : `${result.query}\n`);
    writeFileSync(join(dir, "fusion-plan.yaml"), readFileSync(snapshot, "utf8"));
    imported.push(id);
  }
}

writeFileSync(join(target, "README.md"), [
  "# Fusion planner fixtures",
  "",
  `Imported from [ChilliCream/graphql-platform](https://github.com/ChilliCream/graphql-platform) ${tag} `
    + `(\`${commit}\`), \`src/HotChocolate/Fusion/test/Fusion.Execution.Tests/Planning\`, `
    + "with `tools/planner-compare/import-fusion-tests.mjs`. Do not edit by hand; re-run the import.",
  "",
  "Each directory holds one test: `schema.yaml` (source schemas a, b, ...), `query.graphql` and Fusion's plan",
  "snapshot `fusion-plan.yaml`. `FusionPlanParityTest` plans every query with feddi and compares it with",
  "Fusion's plan.",
  "",
  `${imported.length} tests imported, ${skipped.length} skipped (see SKIPPED.md).`,
  "",
  "## License",
  "",
  "The fixtures are derived from Fusion's tests, which are licensed under the MIT License:",
  "",
  "```",
  readFileSync(join(source, "LICENSE"), "utf8").trim(),
  "```",
  "",
].join("\n"));
writeFileSync(join(target, "SKIPPED.md"), [
  "# Skipped Fusion planner tests",
  "",
  "| Test | Reason |",
  "|---|---|",
  ...skipped.map(s => `| ${s.id} | ${s.reason} |`),
  "",
].join("\n"));
console.log(`imported ${imported.length}, skipped ${skipped.length} -> ${target}`);
const reasons = skipped.reduce((acc, s) => ({ ...acc, [s.reason]: (acc[s.reason] ?? 0) + 1 }), {});
console.log(reasons);

// --- C# parsing ---------------------------------------------------------------------------------

/** Splits a test class into [Fact] methods; [Theory] methods are marked. */
function splitTests(text) {
  const tests = [];
  const pattern = /\[(Fact|Theory)[^\]]*\]\s*(?:\[[^\]]*\]\s*)*public\s+(?:async\s+)?(?:void|Task)\s+(\w+)\s*\(/g;
  const matches = [...text.matchAll(pattern)];
  matches.forEach((m, i) => {
    const end = i + 1 < matches.length ? matches[i + 1].index : text.length;
    tests.push({ kind: m[1], name: m[2], body: text.slice(m.index, end) });
  });
  return tests;
}

function extract(test, sources) {
  if (test.kind === "Theory") return { skip: "theory" };
  let compose = callArguments(test.body, "ComposeSchema(");
  if (!compose) {
    // var schema = SomeHelper(); with the helper in the test class or in FusionTestBase
    const helper = test.body.match(/var\s+schema\s*=\s*(\w+)\(\s*\)\s*;/)?.[1];
    const definition = helper && sources.map(text => methodBody(text, helper)).find(Boolean);
    compose = definition && callArguments(definition, "ComposeSchema(");
    if (!compose) return { skip: helper ? `schema from ${helper}() without ComposeSchema(...)` : "no ComposeSchema(...)" };
  }
  compose = compose.replace(/new\s+TestSourceSchema\(/g, "(");
  const schemas = rawStrings(compose);
  const rest = compose.replace(/"{3,}[\s\S]*?"{3,}/g, "").replace(/[\s,()]/g, "");
  if (schemas.length === 0 || rest.length > 0) return { skip: "ComposeSchema with options or shared schemas" };
  if (/@defer|@stream|subscription\s*[{(]/i.test(test.body)) return { skip: "@defer, @stream or subscription" };
  const plans = [...test.body.matchAll(/PlanOperation\(/g)];
  if (plans.length !== 1) return { skip: plans.length === 0 ? "no PlanOperation(...)" : "several PlanOperation(...) calls" };
  const planArgs = callArguments(test.body, "PlanOperation(");
  const queries = rawStrings(planArgs);
  if (queries.length !== 1) return { skip: "PlanOperation without a raw string query" };
  if (!/^\s*schema\s*,/.test(planArgs)) return { skip: "PlanOperation with extra arguments" };
  return { schemas, query: queries[0] };
}

/** Body of a parameterless method with the given name, or null. */
function methodBody(text, name) {
  const m = text.match(new RegExp(`\\b${name}\\s*\\(\\s*\\)\\s*(=>|\\{)`));
  if (!m) return null;
  return text.slice(m.index, m.index + 20000);
}

/** Source schema names as in Fusion's tests: "# name: X" on the first line, otherwise a, b, c, ... */
function sourceSchemas(schemas) {
  let autoName = 97;
  const result = {};
  for (const schema of schemas) {
    const lines = schema.split("\n");
    if (lines[0].startsWith("# name:")) {
      result[lines[0].slice("# name:".length).trim()] = lines.slice(1).join("\n");
    } else {
      result[String.fromCharCode(autoName++)] = schema;
    }
  }
  return result;
}

/** Returns the text between `call` and its matching closing parenthesis, skipping raw strings. */
function callArguments(text, call) {
  const start = text.indexOf(call);
  if (start < 0) return null;
  let depth = 1;
  let i = start + call.length;
  while (i < text.length && depth > 0) {
    const quotes = text.slice(i).match(/^"{3,}/);
    if (quotes) {
      const close = text.indexOf(quotes[0], i + quotes[0].length);
      i = close + quotes[0].length;
      continue;
    }
    if (text[i] === "(") depth++;
    if (text[i] === ")") depth--;
    i++;
  }
  return text.slice(start + call.length, i - 1);
}

/** C# raw string literals ("""\n...\n""") with the closing line's indentation removed. */
function rawStrings(text) {
  const result = [];
  for (const m of text.matchAll(/("{3,})\r?\n([\s\S]*?)\r?\n([ \t]*)\1(?!")/g)) {
    const indent = m[3];
    result.push(m[2].split(/\r?\n/).map(line => (line.startsWith(indent) ? line.slice(indent.length) : line.trimStart())).join("\n"));
  }
  return result;
}
