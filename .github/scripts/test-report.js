#!/usr/bin/env node

const fs = require('fs');
const path = require('path');
const childProcess = require('child_process');
const {
  parseJacocoXml,
  pct,
  counterPct,
  zeroCov,
  isRegression,
} = require('./parse-jacoco.js');

const markerStart = '<!-- test-results-start -->';
const markerEnd = '<!-- test-results-end -->';
const zeroTest = { total: 0, passed: 0, failed: 0, errors: 0, skipped: 0 };

const testSuites = [
  { key: 'gateway-engine', label: 'Gateway engine', results: 'gateway/engine/build/test-results/test' },
  { key: 'gateway-app-unit', label: 'Gateway app unit', results: 'gateway/app/build/test-results/test' },
  { key: 'gateway-app-integration', label: 'Gateway app integration', results: 'gateway/app/build/test-results/integrationTest' },
  { key: 'e2e-tests', label: 'E2E tests', results: 'e2e-tests/build/test-results/test' },
];

const testCategories = [
  { key: 'composition-success', label: 'Composition success' },
  { key: 'composition-errors', label: 'Composition errors' },
  { key: 'planning', label: 'Planning' },
  { key: 'execution', label: 'Execution' },
  { key: 'engine-other', label: 'Engine other' },
];

function usage() {
  console.error(`Usage:
  node .github/scripts/test-report.js collect --output <file>
  node .github/scripts/test-report.js baseline --stats <file> --coverage <file> --baseline <file>
  node .github/scripts/test-report.js readme --baseline <file> --readme <file>
  node .github/scripts/test-report.js comment --stats <file> --baseline <file> --coverage <file> --output <file>
  node .github/scripts/test-report.js gate --baseline <file> --coverage <file>
  node .github/scripts/test-report.js verify-generated --base-ref <ref>`);
}

function argValue(args, name, fallback = null) {
  const index = args.indexOf(name);
  if (index === -1) return fallback;
  if (index + 1 >= args.length) {
    throw new Error(`Missing value for ${name}`);
  }
  return args[index + 1];
}

function ensureParentDir(file) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
}

function readJson(file, fallback) {
  if (!fs.existsSync(file)) return fallback;
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function writeJson(file, data) {
  ensureParentDir(file);
  fs.writeFileSync(file, JSON.stringify(data, null, 2) + '\n');
}

function listFilesRecursive(dir, predicate = () => true) {
  if (!fs.existsSync(dir)) return [];
  const files = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...listFilesRecursive(fullPath, predicate));
    } else if (predicate(fullPath)) {
      files.push(fullPath);
    }
  }
  return files;
}

function parseXmlAttributes(tag) {
  const attrs = {};
  const attrRe = /([A-Za-z_:][-A-Za-z0-9_:.]*)="([^"]*)"/g;
  let match;
  while ((match = attrRe.exec(tag)) !== null) {
    attrs[match[1]] = match[2];
  }
  return attrs;
}

function parseJunitResults(resultsPath) {
  const stats = { ...zeroTest };
  const files = fs.existsSync(resultsPath) && fs.statSync(resultsPath).isFile()
    ? [resultsPath]
    : listFilesRecursive(resultsPath, file => path.basename(file).startsWith('TEST-') && file.endsWith('.xml'));

  for (const file of files) {
    const xml = fs.readFileSync(file, 'utf8');
    const suiteMatch = xml.match(/<testsuite\b[^>]*>/);
    if (!suiteMatch) continue;
    const attrs = parseXmlAttributes(suiteMatch[0]);
    const total = parseInt(attrs.tests || '0', 10);
    const failed = parseInt(attrs.failures || '0', 10);
    const errors = parseInt(attrs.errors || '0', 10);
    const skipped = parseInt(attrs.skipped || '0', 10);
    stats.total += total;
    stats.failed += failed;
    stats.errors += errors;
    stats.skipped += skipped;
  }

  stats.passed = Math.max(0, stats.total - stats.failed - stats.errors - stats.skipped);
  return stats;
}

function countYamlFiles(dir) {
  return listFilesRecursive(dir, file => file.endsWith('.yaml') || file.endsWith('.yml')).length;
}

function countSchemaCaseFiles(resourcesDir, childDir) {
  const schemasDir = path.join(resourcesDir, 'schemas');
  if (!fs.existsSync(schemasDir)) return 0;
  let total = 0;
  for (const entry of fs.readdirSync(schemasDir, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    total += countYamlFiles(path.join(schemasDir, entry.name, childDir));
  }
  return total;
}

function collectTestStats(repoRoot = process.cwd()) {
  const tests = {};
  for (const suite of testSuites) {
    tests[suite.key] = parseJunitResults(path.join(repoRoot, suite.results));
  }

  const resourcesDir = path.join(repoRoot, 'gateway/engine/src/test/resources');
  const compositionSuccess = countYamlFiles(path.join(resourcesDir, 'composition/success'));
  const compositionErrors = countYamlFiles(path.join(resourcesDir, 'composition/errors'));
  const planning = countSchemaCaseFiles(resourcesDir, 'planning');
  const execution = countSchemaCaseFiles(resourcesDir, 'executions');
  const yamlDrivenTotal = compositionSuccess + compositionErrors + planning + execution;
  const engineOther = Math.max(0, tests['gateway-engine'].total - yamlDrivenTotal);

  const categories = {
    'composition-success': { total: compositionSuccess },
    'composition-errors': { total: compositionErrors },
    planning: { total: planning },
    execution: { total: execution },
    'engine-other': { total: engineOther },
  };

  return { tests, categories };
}

function statusLabel(stats) {
  const total = stats?.total || 0;
  const failed = (stats?.failed || 0) + (stats?.errors || 0);
  const skipped = stats?.skipped || 0;
  if (total === 0) return '-';
  if (failed > 0) return 'fail';
  if (skipped > 0) return 'skip';
  return 'pass';
}

function num(value) {
  return Number.isFinite(Number(value)) ? Number(value) : 0;
}

function valueOrDash(value) {
  return num(value) === 0 ? '-' : String(value);
}

function delta(curr, base) {
  const d = num(curr) - num(base);
  if (d === 0) return '+0';
  return d > 0 ? `+${d}` : String(d);
}

function cellWithDelta(curr, base) {
  return `${num(curr)} (${delta(curr, base)})`;
}

function formatPct(value) {
  return `${value.toFixed(1)}%`;
}

function coverageCounts(counter) {
  const covered = counter?.covered || 0;
  const missed = counter?.missed || 0;
  const total = covered + missed;
  return total === 0 ? '-' : `${covered}/${total}`;
}

function coveragePct(counter) {
  const covered = counter?.covered || 0;
  const missed = counter?.missed || 0;
  const total = covered + missed;
  return total === 0 ? '-' : formatPct(pct(covered, missed));
}

function coveragePctDelta(curr, base) {
  const currPct = counterPct(curr || zeroCov);
  const basePct = counterPct(base || zeroCov);
  const d = currPct - basePct;
  if (Math.abs(d) < 0.05) return '+0.0%';
  return d > 0 ? `+${d.toFixed(1)}%` : `${d.toFixed(1)}%`;
}

function hasBaselineData(baseline) {
  return Object.keys(baseline?.tests || {}).length > 0 ||
    Object.keys(baseline?.categories || {}).length > 0 ||
    Object.keys(baseline?.coverage?.overall || {}).length > 0;
}

function buildReadmeSection(baseline) {
  if (!hasBaselineData(baseline)) {
    return 'Baseline test results are populated automatically after the next successful `main` build.';
  }

  const tests = baseline.tests || {};
  const categories = baseline.categories || {};
  const coverage = baseline.coverage?.overall || {};
  const lines = [];

  lines.push('### Test Results');
  lines.push('| Suite | Tests | Passed | Failed | Errors | Skipped | Status |');
  lines.push('|:------|------:|-------:|-------:|-------:|--------:|:-------|');
  for (const suite of testSuites) {
    const stats = tests[suite.key] || zeroTest;
    lines.push(`| ${suite.label} | ${valueOrDash(stats.total)} | ${valueOrDash(stats.passed)} | ${valueOrDash(stats.failed)} | ${valueOrDash(stats.errors)} | ${valueOrDash(stats.skipped)} | ${statusLabel(stats)} |`);
  }

  lines.push('');
  lines.push('### Test Categories');
  lines.push('| Category | Count |');
  lines.push('|:---------|------:|');
  for (const category of testCategories) {
    const stats = categories[category.key] || { total: 0 };
    lines.push(`| ${category.label} | ${valueOrDash(stats.total)} |`);
  }

  lines.push('');
  lines.push('### Code Coverage');
  lines.push('| Metric | Coverage | Covered / Total |');
  lines.push('|:-------|---------:|----------------:|');
  for (const { label, key } of [
    { label: 'Line', key: 'line' },
    { label: 'Branch', key: 'branch' },
    { label: 'Method', key: 'method' },
  ]) {
    lines.push(`| ${label} | ${coveragePct(coverage[key])} | ${coverageCounts(coverage[key])} |`);
  }

  return lines.join('\n');
}

function replaceMarkedSection(content, section) {
  const start = content.indexOf(markerStart);
  const end = content.indexOf(markerEnd);
  if (start === -1 || end === -1 || end < start) {
    throw new Error(`README.md must contain ${markerStart} and ${markerEnd}`);
  }

  const before = content.slice(0, start + markerStart.length);
  const after = content.slice(end);
  return `${before}\n${section}\n${after}`;
}

function updateReadmeFromBaseline({ baselineFile, readmeFile }) {
  const baseline = readJson(baselineFile, { tests: {}, categories: {}, coverage: {} });
  const content = fs.readFileSync(readmeFile, 'utf8');
  fs.writeFileSync(readmeFile, replaceMarkedSection(content, buildReadmeSection(baseline)));
}

function updateBaseline({ statsFile, coverageFile, baselineFile }) {
  const current = readJson(baselineFile, { tests: {}, categories: {}, coverage: { overall: {}, classes: {} } });
  const stats = readJson(statsFile, { tests: {}, categories: {} });
  const parsedCoverage = parseJacocoXml(coverageFile);
  const coverage = parsedCoverage || current.coverage || { overall: {}, classes: {} };
  writeJson(baselineFile, {
    tests: stats.tests || {},
    categories: stats.categories || {},
    coverage,
  });
}

function buildCommentBody({ statsFile, baselineFile, coverageFile }) {
  const current = readJson(statsFile, { tests: {}, categories: {} });
  const baseline = readJson(baselineFile, { tests: {}, categories: {}, coverage: {} });
  const currentCoverage = parseJacocoXml(coverageFile)?.overall || {};
  const baselineCoverage = baseline.coverage?.overall || {};
  const now = new Date().toISOString().replace('T', ' ').replace(/\.\d+Z$/, ' UTC');
  const lines = [];

  lines.push('<!-- test-report -->');
  lines.push('## Test Report');
  lines.push('');
  lines.push('### Test Results');
  lines.push('| Suite | Total | Passed | Failed | Errors | Skipped |');
  lines.push('|:------|------:|-------:|-------:|-------:|--------:|');
  for (const suite of testSuites) {
    const curr = current.tests?.[suite.key] || zeroTest;
    const base = baseline.tests?.[suite.key] || zeroTest;
    lines.push(`| ${suite.label} | ${cellWithDelta(curr.total, base.total)} | ${cellWithDelta(curr.passed, base.passed)} | ${cellWithDelta(curr.failed, base.failed)} | ${cellWithDelta(curr.errors, base.errors)} | ${cellWithDelta(curr.skipped, base.skipped)} |`);
  }

  lines.push('');
  lines.push('### Test Categories');
  lines.push('| Category | Count |');
  lines.push('|:---------|------:|');
  for (const category of testCategories) {
    const curr = current.categories?.[category.key] || { total: 0 };
    const base = baseline.categories?.[category.key] || { total: 0 };
    lines.push(`| ${category.label} | ${cellWithDelta(curr.total, base.total)} |`);
  }

  lines.push('');
  lines.push('### Code Coverage');
  if (Object.keys(currentCoverage).length === 0) {
    lines.push('Coverage report not available.');
  } else {
    lines.push('| Metric | Covered | Missed | Coverage | vs Main |');
    lines.push('|:-------|--------:|-------:|---------:|--------:|');
    for (const { label, key } of [
      { label: 'Lines', key: 'line' },
      { label: 'Branches', key: 'branch' },
      { label: 'Methods', key: 'method' },
    ]) {
      const curr = currentCoverage[key] || zeroCov;
      const base = baselineCoverage[key] || zeroCov;
      lines.push(`| ${label} | ${curr.covered} | ${curr.missed} | ${coveragePct(curr)} | ${coveragePctDelta(curr, base)} |`);
    }
  }

  lines.push('');
  lines.push(`> Updated: ${now}`);
  return lines.join('\n') + '\n';
}

function formatMethodName(name) {
  if (name === '&lt;init&gt;' || name === '<init>') return 'constructor';
  if (name === '&lt;clinit&gt;' || name === '<clinit>') return 'static initializer';
  return name;
}

function coverageGate({ baselineFile, coverageFile }) {
  const baseline = readJson(baselineFile, { coverage: {} });
  const baseClasses = baseline.coverage?.classes || {};
  const parsed = parseJacocoXml(coverageFile);

  if (!parsed) {
    if (Object.keys(baseClasses).length === 0) {
      console.log('No JaCoCo report or baseline found; skipping coverage gate.');
      return;
    }
    throw new Error(`No JaCoCo report found at ${coverageFile}`);
  }

  if (Object.keys(baseClasses).length === 0) {
    console.log('No baseline coverage classes found; skipping coverage gate.');
    return;
  }

  const regressions = [];
  for (const [className, curr] of Object.entries(parsed.classes || {})) {
    const base = baseClasses[className] || { line: zeroCov, branch: zeroCov, method: zeroCov, methods: [] };
    const classRegressions = [];

    for (const { label, key } of [
      { label: 'Line', key: 'line' },
      { label: 'Branch', key: 'branch' },
      { label: 'Method', key: 'method' },
    ]) {
      const currCounter = curr[key] || zeroCov;
      const baseCounter = base[key] || zeroCov;
      const currPct = pct(currCounter.covered, currCounter.missed);
      const basePct = pct(baseCounter.covered, baseCounter.missed);
      if (isRegression(currPct, basePct, currCounter.missed, baseCounter.missed)) {
        classRegressions.push(`  ${className} ${label}: ${currPct.toFixed(1)}% was ${basePct.toFixed(1)}%, missed ${currCounter.missed} was ${baseCounter.missed}`);
      }
    }

    if (classRegressions.length === 0) continue;
    regressions.push(...classRegressions);

    const currMethods = curr.methods || [];
    const baseMethods = base.methods || [];
    const baseByKey = {};
    for (const method of baseMethods) {
      baseByKey[method.name + method.desc] = method;
    }

    for (const method of currMethods) {
      const baseMethod = baseByKey[method.name + method.desc];
      if (!baseMethod) continue;
      const currLine = method.counters?.line || zeroCov;
      const baseLine = baseMethod.counters?.line || zeroCov;
      const currLinePct = pct(currLine.covered, currLine.missed);
      const baseLinePct = pct(baseLine.covered, baseLine.missed);
      if (isRegression(currLinePct, baseLinePct, currLine.missed, baseLine.missed)) {
        regressions.push(`      ${formatMethodName(method.name)}: ${currLinePct.toFixed(1)}% was ${baseLinePct.toFixed(1)}%, missed ${currLine.missed} was ${baseLine.missed}`);
      }
    }
  }

  if (regressions.length > 0) {
    throw new Error(`Per-class coverage regressions detected:\n${regressions.join('\n')}`);
  }

  console.log('No per-class coverage regressions detected.');
}

function git(args) {
  return childProcess.execFileSync('git', args, { encoding: 'utf8' }).trim();
}

function gitObjectExists(refPath) {
  try {
    git(['cat-file', '-e', refPath]);
    return true;
  } catch (error) {
    return false;
  }
}

function extractMarkedSection(content) {
  const start = content.indexOf(markerStart);
  const end = content.indexOf(markerEnd);
  if (start === -1 || end === -1 || end < start) return null;
  return content.slice(start, end + markerEnd.length);
}

function verifyGeneratedFiles(baseRef) {
  if (!gitObjectExists(`${baseRef}:test-baseline.json`)) {
    console.log('No baseline exists on the base branch yet; generated-file guard is in bootstrap mode.');
    return;
  }

  const baselineDiff = git(['diff', '--name-only', `${baseRef}...HEAD`, '--', 'test-baseline.json']);
  if (baselineDiff) {
    throw new Error('test-baseline.json is generated from main builds and must not be changed directly in PRs.');
  }

  if (!gitObjectExists(`${baseRef}:README.md`) || !fs.existsSync('README.md')) {
    return;
  }

  const baseReadme = git(['show', `${baseRef}:README.md`]);
  const currentReadme = fs.readFileSync('README.md', 'utf8');
  const baseSection = extractMarkedSection(baseReadme);
  const currentSection = extractMarkedSection(currentReadme);
  if (baseSection && currentSection && baseSection !== currentSection) {
    throw new Error('The generated README test report section must not be changed directly in PRs.');
  }
}

function main() {
  const [command, ...args] = process.argv.slice(2);

  try {
    if (command === 'collect') {
      const output = argValue(args, '--output');
      if (!output) throw new Error('Missing --output');
      writeJson(output, collectTestStats(process.cwd()));
    } else if (command === 'baseline') {
      updateBaseline({
        statsFile: argValue(args, '--stats'),
        coverageFile: argValue(args, '--coverage'),
        baselineFile: argValue(args, '--baseline'),
      });
    } else if (command === 'readme') {
      updateReadmeFromBaseline({
        baselineFile: argValue(args, '--baseline'),
        readmeFile: argValue(args, '--readme'),
      });
    } else if (command === 'comment') {
      const output = argValue(args, '--output');
      if (!output) throw new Error('Missing --output');
      ensureParentDir(output);
      fs.writeFileSync(output, buildCommentBody({
        statsFile: argValue(args, '--stats'),
        baselineFile: argValue(args, '--baseline'),
        coverageFile: argValue(args, '--coverage'),
      }));
    } else if (command === 'gate') {
      coverageGate({
        baselineFile: argValue(args, '--baseline'),
        coverageFile: argValue(args, '--coverage'),
      });
    } else if (command === 'verify-generated') {
      verifyGeneratedFiles(argValue(args, '--base-ref', 'origin/main'));
    } else {
      usage();
      process.exit(1);
    }
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}

if (require.main === module) {
  main();
}

module.exports = {
  buildCommentBody,
  buildReadmeSection,
  collectTestStats,
  coverageGate,
  parseJunitResults,
  replaceMarkedSection,
  updateBaseline,
  updateReadmeFromBaseline,
  verifyGeneratedFiles,
};
