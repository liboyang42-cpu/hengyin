'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const { digest, generated, sourceDigest, verify } = require('./verify-mini-artifacts');
const root = path.resolve(__dirname, '..');

test('transport rejects changed output, plan, source and checkout', () => {
  const metadata = 'chengyinhub-xcx/scripts/uiaudit/generated-source.json';
  const plan = '.ci-impact.json';
  const probe = 'ci/__artifact_source_probe.js';
  const files = [...generated, metadata, plan, probe];
  const saved = new Map(files.map(file => [file, fs.existsSync(path.join(root, file)) ? fs.readFileSync(path.join(root, file)) : null]));
  assert.equal(saved.get(probe), null, 'probe must not overwrite an existing source');
  const write = (file, value) => fs.writeFileSync(path.join(root, file), value);
  try {
    for (const file of generated) write(file, '{}\n');
    write(plan, '{}\n');
    const manifest = {
      sourceSha: cp.execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
      sourceDigest: sourceDigest(), planDigest: digest(plan),
      files: Object.fromEntries(generated.map(file => [file, digest(file)])),
    };
    const saveManifest = () => write(metadata, JSON.stringify(manifest));
    saveManifest();
    verify();
    write(generated[0], '{"changed":true}\n');
    assert.throws(verify, /artifact drift/);
    write(generated[0], '{}\n');
    write(plan, '{"changed":true}\n');
    assert.throws(verify, /plan drift/);
    write(plan, '{}\n');
    write(probe, 'module.exports = true;\n');
    assert.throws(verify, /checkout\/source/);
    fs.unlinkSync(path.join(root, probe));
    manifest.sourceSha = '0'.repeat(40);
    saveManifest();
    assert.throws(verify, /checkout\/source/);
  } finally {
    for (const [file, value] of saved) {
      if (value === null) fs.rmSync(path.join(root, file), { force: true });
      else write(file, value);
    }
  }
});
