#!/usr/bin/env node

const path = require('path');
const { updateReadmeFromBaseline } = require('../.github/scripts/test-report.js');

const repoRoot = path.resolve(__dirname, '..');

updateReadmeFromBaseline({
  baselineFile: path.join(repoRoot, 'test-baseline.json'),
  readmeFile: path.join(repoRoot, 'README.md'),
});

console.log('README.md updated from test-baseline.json');
