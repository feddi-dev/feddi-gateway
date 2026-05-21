#!/usr/bin/env node

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { isRegression } = require('./parse-jacoco.js');
const {
  parseJunitResults,
  replaceMarkedSection,
} = require('./test-report.js');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'test-report-'));

try {
  const resultsDir = path.join(tmp, 'results');
  fs.mkdirSync(resultsDir, { recursive: true });
  fs.writeFileSync(path.join(resultsDir, 'TEST-one.xml'), '<testsuite tests="3" failures="1" errors="0" skipped="1"></testsuite>');
  fs.writeFileSync(path.join(resultsDir, 'TEST-two.xml'), '<testsuite tests="2" failures="0" errors="1" skipped="0"></testsuite>');

  assert.deepStrictEqual(parseJunitResults(resultsDir), {
    total: 5,
    passed: 2,
    failed: 1,
    errors: 1,
    skipped: 1,
  });

  assert.strictEqual(
    replaceMarkedSection('before\n<!-- test-results-start -->\nold\n<!-- test-results-end -->\nafter', 'new'),
    'before\n<!-- test-results-start -->\nnew\n<!-- test-results-end -->\nafter'
  );

  assert.strictEqual(isRegression(79.9, 80, 11, 10), true);
  assert.strictEqual(isRegression(79.9, 80, 10, 10), false);
  assert.strictEqual(isRegression(79.99, 80, 11, 10), false);
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}

console.log('test-report helpers passed');
