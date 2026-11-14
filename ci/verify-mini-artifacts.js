#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const generated = [
  'chengyinhub-xcx/scripts/uiaudit/action-ledger.json',
  'chengyinhub-xcx/scripts/uiaudit/route-matrix.json',
];
const metadata = 'chengyinhub-xcx/scripts/uiaudit/generated-source.json';
const digest = file => crypto.createHash('sha256').update(fs.readFileSync(path.join(root, file))).digest('hex');

function sourceDigest() {
  const hash = crypto.createHash('sha256');
  const files = cp.execFileSync('git', ['ls-files', '-co', '--exclude-standard', '-z'], {
    cwd: root, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024,
  }).split('\0').filter(Boolean);
  const excluded = new Set([...generated, metadata, 'chengyinhub-xcx/scripts/uiaudit/impact-map.json', '.ci-impact.json']);
  for (const file of [...new Set(files)].sort()) {
    if (excluded.has(file)) continue;
    const absolute = path.join(root, file);
    if (!fs.existsSync(absolute)) continue;
    const stat = fs.lstatSync(absolute);
    hash.update(file + '\0');
    if (stat.isSymbolicLink()) hash.update(fs.readlinkSync(absolute));
    else if (stat.isFile()) hash.update(fs.readFileSync(absolute));
  }
  return hash.digest('hex');
}

function verify() {
  const manifest = JSON.parse(fs.readFileSync(path.join(root, metadata), 'utf8'));
  const sha = cp.execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  if (manifest.sourceSha !== sha || manifest.sourceDigest !== sourceDigest())
    throw new Error('generated artifacts do not match current checkout/source');
  if (JSON.stringify(Object.keys(manifest.files).sort()) !== JSON.stringify([...generated].sort()))
    throw new Error('generated artifact set changed');
  for (const file of generated) if (manifest.files[file] !== digest(file)) throw new Error('generated artifact drift: ' + file);
  const planDigest = fs.existsSync(path.join(root, '.ci-impact.json')) ? digest('.ci-impact.json') : null;
  if (manifest.planDigest !== planDigest) throw new Error('test impact plan drift');
  console.log('generated artifacts match source and checkout ' + sha);
}

if (require.main === module) verify();
module.exports = { digest, generated, sourceDigest, verify };
