#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const root = path.resolve(__dirname, '..');
const { digest, generated, sourceDigest } = require('./verify-mini-artifacts');

function prepare() {
  const source = sourceDigest();
  for (const [script, flag] of [['build-route-matrix.js', '--json'], ['build-action-ledger.js', '--write']])
    cp.execFileSync(process.execPath, [path.join(root, 'chengyinhub-xcx/scripts/uiaudit', script), flag], { stdio: 'inherit' });
  const ledger = JSON.parse(fs.readFileSync(path.join(root, generated[0]), 'utf8'));
  const overlay = require('../chengyinhub-xcx/scripts/uiaudit/action-evidence.json');
  const { validateEvidenceOverlay } = require('../chengyinhub-xcx/scripts/uiaudit/build-action-ledger');
  const errors = validateEvidenceOverlay(ledger, overlay);
  if (errors.length) throw new Error(errors.join('\n'));
  if (ledger.controlCount !== ledger.controls.length
      || new Set(ledger.controls.map(item => item.id)).size !== ledger.controlCount
      || ledger.controls.some(item => !item.control.handler || !item.evidence.staticSource || /:0$/.test(item.evidence.staticSource) || !item.requiredBranches.length))
    throw new Error('generated ledger has missing/duplicate/unmapped controls');
  if (source !== sourceDigest()) throw new Error('source changed during generation');
  fs.writeFileSync(path.join(root, 'chengyinhub-xcx/scripts/uiaudit/generated-source.json'), JSON.stringify({
    sourceSha: cp.execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
    sourceDigest: source,
    planDigest: fs.existsSync(path.join(root, '.ci-impact.json')) ? digest('.ci-impact.json') : null,
    files: Object.fromEntries(generated.map(file => [file, digest(file)])),
  }, null, 2) + '\n');
}

if (require.main === module) prepare();
module.exports = { generated, prepare };
