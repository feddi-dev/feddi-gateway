// Runs the planner test fixtures through feddi and ChilliCream Fusion against the same mock
// subgraphs and compares how many subgraph requests each gateway makes, how many of them run
// one after another (depth), and whether both return the same data.
//
//   node compare.mjs [--cases complex_nested,cyclic_graph] [--delay 50] [--batching variables]
//
// Needs: a built feddi distribution (gateway/app/build/distributions/feddi-gateway.zip), JDK 25
// (JAVA_HOME or /usr/libexec/java_home -v 25), Docker (for Fusion), zip. See README.md.

import { execFileSync, spawn } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Kind, parse as parseGraphQL, print, visit } from "graphql";
import { parse as parseYaml } from "yaml";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, "../..");
const FIXTURES = join(REPO, "gateway/engine/src/test/resources/schemas");
const FUSION_FIXTURES = join(REPO, "gateway/engine/src/test/resources/fusion-planning");
const FEDDI_ZIP = join(REPO, "gateway/app/build/distributions/feddi-gateway.zip");
const NITRO = join(HERE, "node_modules/.bin/nitro");
const WORK = join(HERE, ".work");
const OUT = join(HERE, "out");

const FUSION_IMAGE = "feddi-planner-compare-fusion";
const FUSION_CONTAINER = "feddi-planner-compare-fusion";
// 6000 (X11) is a port fetch() refuses to connect to.
const PORTS = { control: 6099, firstSubgraph: 6001, fusion: 6100, feddi: 6200, feddiAdmin: 6201, feddiManagement: 6202 };

const options = parseArgs(process.argv.slice(2));
const children = [];
process.on("exit", cleanup);
process.on("SIGINT", () => process.exit(130));

main().then(() => process.exit(0), e => {
  console.error(e);
  process.exit(1);
});

async function main() {
  if (!existsSync(FEDDI_ZIP)) throw new Error(`missing ${FEDDI_ZIP}: run ./gradlew :app:feddiGatewayDistZip in gateway/`);
  rmSync(OUT, { recursive: true, force: true });
  mkdirSync(join(OUT, "plans"), { recursive: true });
  ensureFusionImage();
  await startFeddi();

  const cases = discoverCases()
    .filter(c => !options.cases || options.cases.some(filter => c.name === filter || c.name.startsWith(`${filter}/`)));

  const results = [];
  for (const testCase of cases) {
    console.log(`\n== ${testCase.name}`);
    results.push(...await runCase(testCase));
    writeFileSync(join(OUT, "results.json"), JSON.stringify(results, null, 2));
  }
  writeFileSync(join(OUT, "report.md"), report(results));
  console.log(`\nReport: ${join(OUT, "report.md")}`);
}

// --- One fixture ------------------------------------------------------------------------------

/**
 * Fixtures to run: feddi's planner fixtures (schemas/<case>/planning/*.yaml, suite "schemas") and the
 * imported Fusion planner tests (fusion-planning/<class>/<test>/query.graphql, suite "fusion").
 */
function discoverCases() {
  const cases = [];
  if (options.suite !== "fusion") {
    for (const name of readdirSync(FIXTURES).sort()) {
      const dir = join(FIXTURES, name);
      if (!existsSync(join(dir, "schema.yaml")) || !existsSync(join(dir, "planning"))) continue;
      const queries = readdirSync(join(dir, "planning")).filter(f => f.endsWith(".yaml")).sort()
        .map(file => ({ file, ...parseYaml(readFileSync(join(dir, "planning", file), "utf8")) }));
      cases.push({ name, schemaFile: join(dir, "schema.yaml"), queries, globalObjectIdentification: false });
    }
  }
  if (options.suite !== "schemas" && existsSync(FUSION_FIXTURES)) {
    for (const className of readdirSync(FUSION_FIXTURES).sort()) {
      if (!statSync(join(FUSION_FIXTURES, className)).isDirectory()) continue;
      for (const test of readdirSync(join(FUSION_FIXTURES, className)).sort()) {
        const dir = join(FUSION_FIXTURES, className, test);
        if (!existsSync(join(dir, "query.graphql"))) continue;
        cases.push({
          name: `fusion/${className}/${test}`,
          schemaFile: join(dir, "schema.yaml"),
          queries: [{ file: "query.graphql", query: readFileSync(join(dir, "query.graphql"), "utf8") }],
          // Fusion's planner tests compose with global object identification (FusionTestBase.ComposeSchema).
          globalObjectIdentification: true,
        });
      }
    }
  }
  return cases;
}

async function runCase(testCase) {
  const name = testCase.name;
  const schema = parseYaml(readFileSync(testCase.schemaFile, "utf8"));
  const queries = testCase.queries.map(q => ({ ...q }));
  const subgraphs = Object.entries(schema.subgraphs ?? {})
    .map(([subgraph, sdl], i) => ({ name: subgraph, port: PORTS.firstSubgraph + i, sdl }));
  const dir = join(WORK, "cases", name);
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });

  const caseFile = join(dir, "case.json");
  writeFileSync(caseFile, JSON.stringify({ delayMs: options.delay, controlPort: PORTS.control, subgraphs }));
  const mock = startProcess("node", [join(HERE, "mock-subgraphs.mjs"), caseFile], join(dir, "mock.log"));
  try {
    await waitFor(() => readFileSync(join(dir, "mock.log"), "utf8").includes("ready"), "mock subgraphs");

    const feddi = await uploadToFeddi(dir, subgraphs);
    const fusion = await startFusion(dir, subgraphs, testCase.globalObjectIdentification);
    console.log(`   feddi: ${feddi.ok ? "ok" : feddi.error}; fusion: ${fusion.ok ? "ok" : fusion.error}`);

    const types = typeDefinitions(subgraphs);
    const results = [];
    for (const query of queries) {
      query.variables = { ...requiredVariables(query.query, types), ...(query.variables ?? {}) };
      const row = { case: name, query: query.file.replace(/\.(yaml|graphql)$/, ""), name: query.name, fusionAdjustments: fusion.adjustments ?? [] };
      row.feddi = feddi.ok ? await measure(PORTS.feddi, query, false) : { error: `composition: ${feddi.error}` };
      row.fusion = fusion.ok ? await measure(PORTS.fusion, query, true) : { error: `composition: ${fusion.error}` };
      if (row.fusion.plan) {
        mkdirSync(join(OUT, "plans", name), { recursive: true });
        writeFileSync(join(OUT, "plans", name, `${row.query}.fusion.json`), JSON.stringify(row.fusion.plan, null, 2));
        delete row.fusion.plan;
      }
      row.sameData = row.feddi.data !== undefined && row.feddi.data === row.fusion.data;
      delete row.feddi.data;
      delete row.fusion.data;
      console.log(`   ${row.query}: feddi ${summary(row.feddi)} | fusion ${summary(row.fusion)}${row.sameData || row.feddi.error || row.fusion.error ? "" : " | data differs"}`);
      results.push(row);
    }
    return results;
  } finally {
    stopFusion();
    mock.kill();
  }
}

async function measure(port, query, fusion) {
  const body = JSON.stringify({ query: query.query, variables: query.variables ?? {} });
  const headers = { "content-type": "application/json", accept: "application/graphql-response+json, application/json" };
  try {
    await post(port, body, headers); // warm-up: plan cache, JIT, connections
    await getLog();
    const started = performance.now();
    const response = await post(port, body, fusion ? { ...headers, "fusion-operation-plan": "1" } : headers);
    const latencyMs = Math.round(performance.now() - started);
    const log = await getLog();
    return {
      requests: log.length,
      operations: log.reduce((sum, e) => sum + e.operations, 0),
      depth: depth(log),
      latencyMs,
      perSubgraph: countBy(log, e => e.subgraph),
      errors: response.errors?.length ?? 0,
      firstError: response.errors?.[0]?.message,
      data: response.data === undefined ? undefined : canonical(response.data),
      plan: response.extensions?.fusion?.operationPlan,
    };
  } catch (e) {
    return { error: String(e.message ?? e) };
  }
}

/** Longest chain of subgraph requests where each one starts after the previous one ended. */
function depth(log) {
  const entries = [...log].sort((a, b) => a.start - b.start);
  const chain = [];
  entries.forEach((entry, i) => {
    chain[i] = 1 + Math.max(0, ...entries.slice(0, i).map((other, j) => (other.end <= entry.start + 1 ? chain[j] : 0)));
  });
  return Math.max(0, ...chain);
}

// --- feddi ------------------------------------------------------------------------------------

async function startFeddi() {
  const dir = join(WORK, "feddi");
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  execFileSync("unzip", ["-q", FEDDI_ZIP, "-d", dir]);
  writeFileSync(join(dir, "feddi-gateway.yml"), [
    `port: ${PORTS.feddi}`,
    "enable-introspection: true",
    `admin-port: ${PORTS.feddiAdmin}`,
    "admin-address: 127.0.0.1",
    `management-port: ${PORTS.feddiManagement}`,
    "management-address: 127.0.0.1",
    "logging:",
    "  dir: ./logs",
    "",
  ].join("\n"));
  const javaHome = process.env.JAVA_HOME ?? execFileSync("/usr/libexec/java_home", ["-v", "25"], { encoding: "utf8" }).trim();
  startProcess(join(dir, "feddi-gateway/bin/feddi-gateway"), [], join(dir, "gateway.log"),
    { cwd: dir, env: { ...process.env, FEDDI_GATEWAY_JAVA_HOME: javaHome } });
  await waitFor(async () => (await fetch(`http://127.0.0.1:${PORTS.feddiManagement}/actuator/health`)).ok, "feddi");
  console.log(`feddi started (${FEDDI_ZIP}, ${statSync(FEDDI_ZIP).mtime.toISOString()})`);
}

async function uploadToFeddi(dir, subgraphs) {
  const config = join(dir, "feddi", "subgraph-config");
  for (const { name, port, sdl } of subgraphs) {
    mkdirSync(join(config, name), { recursive: true });
    writeFileSync(join(config, name, "schema.graphqls"), sdl);
    writeFileSync(join(config, name, "config.yaml"),
      `url: http://127.0.0.1:${port}/graphql\nbatching: ${options.batching}\n`);
  }
  const zip = join(dir, "feddi", "subgraphs.zip");
  execFileSync("zip", ["-qr", zip, "."], { cwd: config });
  const response = await fetch(`http://127.0.0.1:${PORTS.feddiAdmin}/admin/upload`, {
    method: "POST", headers: { "content-type": "application/octet-stream" }, body: readFileSync(zip),
  });
  const text = await response.text();
  return response.ok ? { ok: true } : { ok: false, error: firstLine(text) };
}

// --- Fusion -----------------------------------------------------------------------------------

function ensureFusionImage() {
  execFileSync("docker", ["build", "-q", "-t", FUSION_IMAGE, join(HERE, "fusion-gateway")], { stdio: ["ignore", "ignore", "inherit"] });
}

async function startFusion(dir, subgraphs, globalObjectIdentification) {
  const fusionDir = join(dir, "fusion");
  const files = [];
  const { sdls, adjustments } = shareRootFields(subgraphs);
  for (const { name, port } of subgraphs) {
    mkdirSync(join(fusionDir, name), { recursive: true });
    writeFileSync(join(fusionDir, name, "schema.graphqls"), sdls.get(name));
    writeFileSync(join(fusionDir, name, "schema-settings.json"), JSON.stringify({
      name, transports: { http: { url: `http://host.docker.internal:${port}/graphql` } },
    }));
    files.push("-f", join(name, "schema.graphqls"));
  }
  try {
    execFileSync(NITRO, ["fusion", "compose", ...files, "--archive", "gateway.far",
      ...(globalObjectIdentification ? ["--enable-global-object-identification"] : [])],
      { cwd: fusionDir, encoding: "utf8", stdio: "pipe" });
  } catch (e) {
    return { ok: false, adjustments, error: firstLine(`${e.stdout ?? ""}${e.stderr ?? ""}`.split("\n").find(l => /ERR|error|fail/i.test(l)) ?? e.message) };
  }
  stopFusion();
  execFileSync("docker", ["run", "-d", "--rm", "--name", FUSION_CONTAINER, "-p", `${PORTS.fusion}:8080`,
    "--add-host", "host.docker.internal:host-gateway", "-v", `${fusionDir}:/archive:ro`, FUSION_IMAGE], { stdio: "ignore" });
  try {
    await waitFor(async () => (await post(PORTS.fusion, JSON.stringify({ query: "{ __typename }" }),
      { "content-type": "application/json" })).data !== undefined, "fusion", 60);
    return { ok: true, adjustments };
  } catch (e) {
    const logs = execFileSync("docker", ["logs", FUSION_CONTAINER], { encoding: "utf8", stdio: "pipe" });
    return { ok: false, adjustments, error: firstLine(logs.split("\n").find(l => /fail|error|exception/i.test(l)) ?? String(e.message)) };
  }
}

/**
 * The composite-schema spec requires @shareable on a root field that several subgraphs define,
 * lookups included (Fusion: INVALID_FIELD_SHARING); feddi accepts them without it. Adds the
 * directive in Fusion's copy of the schemas only, and returns what it changed.
 */
/** Enum and input object definitions from all subgraphs, by name. */
function typeDefinitions(subgraphs) {
  const types = new Map();
  for (const { sdl } of subgraphs) {
    for (const definition of parseGraphQL(sdl).definitions) {
      if (definition.kind === Kind.ENUM_TYPE_DEFINITION || definition.kind === Kind.INPUT_OBJECT_TYPE_DEFINITION) {
        types.set(definition.name.value, definition);
      }
    }
  }
  return types;
}

/** Values for the query's required variables, so both gateways execute instead of rejecting it. */
function requiredVariables(query, types) {
  const values = {};
  for (const definition of parseGraphQL(query).definitions) {
    for (const variable of definition.variableDefinitions ?? []) {
      if (variable.type.kind === Kind.NON_NULL_TYPE && !variable.defaultValue) {
        values[variable.variable.name.value] = sampleValue(variable.type, types);
      }
    }
  }
  return values;
}

function sampleValue(typeNode, types) {
  if (typeNode.kind === Kind.NON_NULL_TYPE) return sampleValue(typeNode.type, types);
  if (typeNode.kind === Kind.LIST_TYPE) return [sampleValue(typeNode.type, types)];
  const name = typeNode.name.value;
  const definition = types.get(name);
  if (definition?.kind === Kind.ENUM_TYPE_DEFINITION) return definition.values[0].name.value;
  if (definition?.kind === Kind.INPUT_OBJECT_TYPE_DEFINITION) {
    return Object.fromEntries(definition.fields
      .filter(f => f.type.kind === Kind.NON_NULL_TYPE && !f.defaultValue)
      .map(f => [f.name.value, sampleValue(f.type, types)]));
  }
  switch (name) {
    case "Boolean": return true;
    case "Int": return 1;
    case "Float": return 1.5;
    case "String": return "x";
    default: return "s123456"; // ID and custom scalars: a key the mock subgraphs recognize
  }
}

function shareRootFields(subgraphs) {
  const documents = new Map(subgraphs.map(({ name, sdl }) => [name, parseGraphQL(sdl)]));
  const owners = new Map();
  for (const [name, document] of documents) {
    forEachRootField(document, field => {
      const key = field.name.value;
      owners.set(key, new Set([...(owners.get(key) ?? []), name]));
    });
  }
  const adjustments = [];
  const sdls = new Map();
  for (const [name, document] of documents) {
    const adjusted = visit(document, {
      FieldDefinition(field, _key, _parent, _path, ancestors) {
        const type = ancestors.findLast(node => !Array.isArray(node));
        if (!isQueryType(type) || owners.get(field.name.value).size < 2
          || field.directives?.some(d => d.name.value === "shareable")) {
          return undefined;
        }
        adjustments.push(`${name}: Query.${field.name.value} @shareable`);
        return { ...field, directives: [...(field.directives ?? []), { kind: Kind.DIRECTIVE, name: { kind: Kind.NAME, value: "shareable" }, arguments: [] }] };
      },
    });
    sdls.set(name, adjusted === document ? subgraphs.find(s => s.name === name).sdl : print(adjusted));
  }
  return { sdls, adjustments };
}

function forEachRootField(document, callback) {
  for (const definition of document.definitions) {
    if (isQueryType(definition)) definition.fields?.forEach(callback);
  }
}

function isQueryType(node) {
  return (node?.kind === Kind.OBJECT_TYPE_DEFINITION || node?.kind === Kind.OBJECT_TYPE_EXTENSION) && node.name.value === "Query";
}

function stopFusion() {
  try {
    execFileSync("docker", ["rm", "-f", FUSION_CONTAINER], { stdio: "ignore" });
  } catch {
    // not running
  }
}

// --- Report -----------------------------------------------------------------------------------

function report(results) {
  const both = results.filter(r => !r.feddi.error && !r.fusion.error);
  const compare = (key, cmp) => both.filter(r => cmp(r.feddi[key], r.fusion[key])).length;
  const lines = [
    "# Query plans: feddi vs Fusion",
    "",
    `Planner fixtures from \`gateway/engine/src/test/resources/schemas\`, mock subgraphs with ${options.delay} ms per request, `
      + `feddi with \`batching: ${options.batching}\`, Fusion ${fusionVersion()} with its defaults.`,
    "",
    "- **requests**: HTTP requests to subgraphs; **ops**: GraphQL operations in them (batched variable sets count each)",
    "- **depth**: longest chain of subgraph requests that ran one after another",
    "",
    "## Summary",
    "",
    `| | count |`,
    `|---|---:|`,
    `| queries | ${results.length} |`,
    `| run on both gateways | ${both.length} |`,
    `| same data | ${both.filter(r => r.sameData).length} |`,
    `| feddi more operations / fewer / equal | ${compare("operations", (a, b) => a > b)} / ${compare("operations", (a, b) => a < b)} / ${compare("operations", (a, b) => a === b)} |`,
    `| feddi deeper / shallower / equal | ${compare("depth", (a, b) => a > b)} / ${compare("depth", (a, b) => a < b)} / ${compare("depth", (a, b) => a === b)} |`,
    `| feddi more HTTP requests (transport, not plan) | ${compare("requests", (a, b) => a > b)} |`,
    `| feddi failed (composition or request) | ${results.filter(r => r.feddi.error).length} |`,
    `| Fusion failed (composition or request) | ${results.filter(r => r.fusion.error).length} |`,
    "",
    "## Plan differences (operations or depth)",
    "",
    table(both.filter(r => r.feddi.operations !== r.fusion.operations || r.feddi.depth !== r.fusion.depth)
      .sort((a, b) => (b.feddi.depth - b.fusion.depth) - (a.feddi.depth - a.fusion.depth) || (b.feddi.operations - b.fusion.operations) - (a.feddi.operations - a.fusion.operations))),
    "",
    "## Same plan, more HTTP requests (Fusion also batches different operations into one request)",
    "",
    table(both.filter(r => r.feddi.operations === r.fusion.operations && r.feddi.depth === r.fusion.depth && r.feddi.requests !== r.fusion.requests)),
    "",
    "## Fusion schema adjustments",
    "",
    "Root fields defined by several subgraphs got `@shareable` in Fusion's copy (the composite-schema spec requires it; feddi does not):",
    "",
    ...[...new Set(results.flatMap(r => r.fusionAdjustments.map(a => `- ${r.case} / ${a}`)))],
    "",
    "## Data differs",
    "",
    table(both.filter(r => !r.sameData)),
    "",
    "## All queries",
    "",
    table(results),
    "",
  ];
  return lines.join("\n");
}

function table(rows) {
  if (rows.length === 0) return "_none_";
  const cell = m => (m.error ? `error: ${m.error.slice(0, 80)}` : `${m.requests} / ${m.operations} / ${m.depth}${m.errors ? ` (${m.errors} errors)` : ""}`);
  return [
    "| case / query | feddi req / ops / depth | Fusion req / ops / depth | same data |",
    "|---|---|---|---|",
    ...rows.map(r => `| ${r.case} / ${r.query} | ${cell(r.feddi)} | ${cell(r.fusion)} | ${r.sameData ? "yes" : "no"} |`),
  ].join("\n");
}

function fusionVersion() {
  const csproj = readFileSync(join(HERE, "fusion-gateway/FusionGateway.csproj"), "utf8");
  return csproj.match(/HotChocolate\.Fusion\.AspNetCore" Version="([^"]+)"/)?.[1] ?? "?";
}

// --- Helpers ----------------------------------------------------------------------------------

async function post(port, body, headers) {
  const response = await fetch(`http://127.0.0.1:${port}/graphql`, { method: "POST", body, headers, signal: AbortSignal.timeout(30000) });
  const text = await response.text();
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`HTTP ${response.status}: ${firstLine(text)}`);
  }
}

async function getLog() {
  return (await fetch(`http://127.0.0.1:${PORTS.control}/`)).json();
}

function startProcess(command, args, logFile, spawnOptions = {}) {
  writeFileSync(logFile, "");
  const child = spawn("sh", ["-c", `exec "$0" "$@" >> "${logFile}" 2>&1`, command, ...args], { ...spawnOptions, stdio: "ignore" });
  children.push(child);
  return child;
}

async function waitFor(check, what, seconds = 120) {
  const deadline = Date.now() + seconds * 1000;
  while (Date.now() < deadline) {
    try {
      if (await check()) return;
    } catch {
      // not ready yet
    }
    await new Promise(r => setTimeout(r, 250));
  }
  throw new Error(`${what} not ready after ${seconds}s`);
}

function cleanup() {
  stopFusion();
  children.forEach(child => child.kill());
}

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map(k => `${JSON.stringify(k)}:${canonical(value[k])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function countBy(items, key) {
  return items.reduce((acc, item) => ({ ...acc, [key(item)]: (acc[key(item)] ?? 0) + 1 }), {});
}

function summary(m) {
  return m.error ? `error: ${m.error.slice(0, 60)}` : `${m.requests} req / ${m.operations} ops / depth ${m.depth}${m.errors ? ` / ${m.errors} errors` : ""}`;
}

function firstLine(text) {
  return String(text).trim().split("\n")[0].slice(0, 300);
}

function parseArgs(args) {
  const result = { delay: 50, batching: "variables", cases: null, suite: "all" };
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--cases") result.cases = args[++i].split(",");
    else if (args[i] === "--suite") result.suite = args[++i];
    else if (args[i] === "--delay") result.delay = Number(args[++i]);
    else if (args[i] === "--batching") result.batching = args[++i];
    else throw new Error(`unknown option ${args[i]}`);
  }
  return result;
}
