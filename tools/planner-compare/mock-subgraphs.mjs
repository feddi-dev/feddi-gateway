// Mock source schemas for comparing gateways: serves every subgraph of one test case with
// deterministic data and logs every request it receives.
//
//   node mock-subgraphs.mjs <case.json>
//
// case.json: { "delayMs": 100, "controlPort": 6099,
//              "subgraphs": [{ "name": "products", "port": 6001, "sdl": "..." }, ...] }
//
// Data model: every object has a numeric seed. Key fields (fields in any @key, or targets of
// lookup arguments, across all subgraphs) encode the seed ("s123" for strings and IDs, 123 for
// numbers), so a lookup in another subgraph recovers the same seed. All other values derive
// from (type, seed, field, arguments) only, so every subgraph that offers a field returns the
// same value for the same entity, whichever path a gateway takes.
//
// Transport (same as the benchmark's Rust subgraphs): a request object, a request object whose
// "variables" is an array (variable batching, JSONL with "variableIndex"), or an array of
// requests (request batching, JSONL with "requestIndex").
//
// Control endpoint on controlPort: GET /log returns and clears the request log.

import { readFileSync } from "node:fs";
import http from "node:http";
import {
  buildSchema, execute, getNamedType, isAbstractType, isCompositeType, isEnumType, isListType,
  isNonNullType, parse,
} from "graphql";

const LIST_SIZE = 2;
const config = JSON.parse(readFileSync(process.argv[2], "utf8"));
const log = [];

const subgraphs = config.subgraphs.map(({ name, port, sdl }) => ({
  name, port, schema: buildSchema(sdl, { assumeValidSDL: true }),
}));
const keyFields = collectKeyFields(subgraphs.map(s => s.schema));

for (const subgraph of subgraphs) {
  http.createServer((req, res) => handle(subgraph, req, res)).listen(subgraph.port, "0.0.0.0");
}
http.createServer((req, res) => {
  res.writeHead(200, { "content-type": "application/json" });
  res.end(JSON.stringify(log.splice(0)));
}).listen(config.controlPort, "127.0.0.1");
console.log(`mock subgraphs ready: ${subgraphs.map(s => `${s.name}:${s.port}`).join(" ")}`);

// --- Transport ------------------------------------------------------------------------------

function handle(subgraph, req, res) {
  const started = performance.now();
  let body = "";
  req.on("data", chunk => (body += chunk));
  req.on("end", () => {
    let response;
    let entry;
    try {
      const json = JSON.parse(body || "{}");
      ({ response, entry } = respond(subgraph, json));
    } catch (e) {
      response = { status: 400, type: "application/json", body: JSON.stringify({ errors: [{ message: String(e) }] }) };
      entry = { kind: "invalid", operations: 0 };
    }
    setTimeout(() => {
      log.push({ subgraph: subgraph.name, start: started, end: performance.now(), ...entry });
      res.writeHead(response.status, { "content-type": response.type });
      res.end(response.body);
    }, config.delayMs);
  });
}

function respond(subgraph, json) {
  if (Array.isArray(json)) {
    const lines = [];
    let operations = 0;
    json.forEach((request, requestIndex) => {
      for (const [variableIndex, result] of executeAll(subgraph, request)) {
        lines.push(JSON.stringify({ ...result, requestIndex, ...(variableIndex === null ? {} : { variableIndex }) }));
        operations++;
      }
    });
    return { response: jsonl(lines), entry: { kind: "requests", operations, query: json[0]?.query } };
  }
  if (Array.isArray(json.variables)) {
    const lines = executeAll(subgraph, json).map(([variableIndex, result]) => JSON.stringify({ ...result, variableIndex }));
    return { response: jsonl(lines), entry: { kind: "variables", operations: lines.length, query: json.query } };
  }
  const [[, result]] = executeAll(subgraph, json);
  return {
    response: { status: 200, type: "application/json", body: JSON.stringify(result) },
    entry: { kind: "single", operations: 1, query: json.query },
  };
}

function jsonl(lines) {
  return { status: 200, type: "application/jsonl", body: lines.join("\n") + "\n" };
}

function executeAll(subgraph, request) {
  const sets = Array.isArray(request.variables) ? request.variables : [request.variables ?? {}];
  const document = parse(request.query);
  return sets.map((variables, index) => [
    Array.isArray(request.variables) ? index : null,
    executeOne(subgraph.schema, document, request.operationName, variables),
  ]);
}

function executeOne(schema, document, operationName, variableValues) {
  const result = execute({
    schema, document, operationName, variableValues, rootValue: { __seed: 0 },
    fieldResolver: resolveField, typeResolver: value => value.__type,
  });
  return { data: result.data ?? null, ...(result.errors ? { errors: result.errors.map(e => ({ message: e.message, path: e.path })) } : {}) };
}

// --- Data -----------------------------------------------------------------------------------

function resolveField(source, args, _context, info) {
  const parentType = info.parentType.name;
  const named = getNamedType(info.returnType);
  const list = isListType(isNonNullType(info.returnType) ? info.returnType.ofType : info.returnType);
  const argsJson = JSON.stringify(args);
  const base = `${parentType}.${info.fieldName}|${source.__seed}|${argsJson}`;

  if (isCompositeType(named)) {
    const argSeeds = seedsIn(argsJson);
    if (list) {
      // A list field that receives keys (e.g. a batch lookup) returns one entity per key.
      const seeds = argSeeds.length > 0 ? argSeeds : range(LIST_SIZE).map(i => hash(`${base}|${i}`));
      return seeds.map(seed => entity(named, info.schema, seed));
    }
    return entity(named, info.schema, argSeeds[0] ?? hash(base));
  }

  const value = i => scalar(named, parentType, info.fieldName, source.__seed, args, i);
  return list ? range(LIST_SIZE).map(value) : value(0);
}

function entity(type, schema, seed) {
  const concrete = isAbstractType(type)
    ? [...schema.getPossibleTypes(type)].map(t => t.name).sort()[seed % schema.getPossibleTypes(type).length]
    : type.name;
  return { __type: concrete, __seed: seed };
}

function scalar(type, parentType, fieldName, seed, args, index) {
  const isKey = keyFields.get(parentType)?.has(fieldName);
  const h = isKey ? seed : hash(`${parentType}.${fieldName}|${seed}|${JSON.stringify(args)}|${index}`);
  if (isEnumType(type)) {
    const values = type.getValues();
    return values[h % values.length].name;
  }
  switch (type.name) {
    case "ID": return `s${h}`;
    case "String": return isKey ? `s${h}` : `${fieldName}-${h % 10000}`;
    case "Boolean": return h % 2 === 0;
    case "Float": return (h % 100000) / 100;
    case "Int": return isKey ? h : h % 10000;
    default: return isKey ? `s${h}` : h % 100000; // custom scalars: Long, Decimal, ... as numbers
  }
}

function seedsIn(text) {
  return [...text.matchAll(/"s(\d{1,10})"|\b(\d{6,10})\b/g)].map(m => Number(m[1] ?? m[2]));
}

/** Fields that identify an entity: @key fields and lookup argument targets, across all subgraphs. */
function collectKeyFields(schemas) {
  const keys = new Map();
  const add = (type, field) => {
    if (!keys.has(type)) keys.set(type, new Set());
    keys.get(type).add(field);
  };
  for (const schema of schemas) {
    for (const type of Object.values(schema.getTypeMap())) {
      for (const node of [type.astNode, ...(type.extensionASTNodes ?? [])].filter(Boolean)) {
        for (const directive of node.directives ?? []) {
          if (directive.name.value !== "key") continue;
          const fields = directive.arguments?.find(a => a.name.value === "fields")?.value.value ?? "";
          topLevelNames(fields).forEach(f => add(type.name, f));
        }
      }
      if (!type.getFields || !isCompositeType(type)) continue;
      for (const field of Object.values(type.getFields())) {
        if (!field.astNode?.directives?.some(d => d.name.value === "lookup")) continue;
        const target = getNamedType(field.type);
        const targets = isAbstractType(target) ? schema.getPossibleTypes(target) : [target];
        for (const arg of field.args) {
          const is = arg.astNode?.directives?.find(d => d.name.value === "is");
          const mapped = is?.arguments?.find(a => a.name.value === "field")?.value.value;
          const name = mapped && /^\w+$/.test(mapped) ? mapped : arg.name;
          targets.forEach(t => add(t.name, name));
        }
      }
    }
  }
  return keys;
}

function topLevelNames(selection) {
  const names = [];
  let depth = 0;
  for (const token of selection.replace(/[{}]/g, m => ` ${m} `).split(/[\s,]+/).filter(Boolean)) {
    if (token === "{") depth++;
    else if (token === "}") depth--;
    else if (depth === 0) names.push(token);
  }
  return names;
}

function hash(text) {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return ((h >>> 0) % 900000000) + 100000; // 6-9 digits, so seedsIn can find it again
}

function range(n) {
  return Array.from({ length: n }, (_, i) => i);
}
