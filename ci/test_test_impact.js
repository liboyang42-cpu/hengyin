'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { dependencies, moneyPath, plan, selectTests } = require('./test-impact');

test('money and SQL changes always require the full backend; frontend changes do not', () => {
  for (const file of ['utils/payment.js', 'RegistrationService.java', 'pass/index.js', 'sql/migration_any.sql'])
    assert.equal(moneyPath(file), true, file);
  assert.equal(moneyPath('pages/roam/index.wxss'), false);
  assert.equal(plan(['chengyinhub-xcx/pages/roam/index.wxss']).backendFull, false);
  assert.equal(plan(['chengyinhub-system/src/main/java/RefundService.java']).backendFull, true);
  assert.equal(plan(['chengyinhub-admin/src/main/java/ApiActivityController.java']).money, true);
  assert.equal(plan(['chengyinhub-system/src/main/java/ThemeService.java']).backendFull, true);
  const moduleOnly = plan(['chengyinhub-generator/src/main/java/GeneratorService.java']);
  assert.equal(moduleOnly.backendFull, false);
  assert.deepEqual(moduleOnly.backendModules, ['chengyinhub-generator']);
  assert.equal(plan([], 'push').shards, 20);
  assert.equal(plan([], 'schedule').backendFull, true);
});

test('require graph follows transitive imports and unknown reads remain conservative', t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ci-impact-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.writeFileSync(path.join(dir, 'a.js'), "require('./b');");
  fs.writeFileSync(path.join(dir, 'b.js'), "require('./c');");
  fs.writeFileSync(path.join(dir, 'c.js'), 'module.exports = 1;');
  const graph = dependencies(path.join(dir, 'a.js'));
  assert.equal(graph.unknown, false);
  assert.equal(graph.files.size, 3);
  fs.writeFileSync(path.join(dir, 'c.js'), 'fs.readFileSync(variable);');
  assert.equal(dependencies(path.join(dir, 'a.js')).unknown, true);
  fs.writeFileSync(path.join(dir, 'c.js'), 'require(variable);');
  assert.equal(dependencies(path.join(dir, 'a.js')).unknown, true);
});

test('CI changes and unresolved impacts include every test rather than silently narrowing', () => {
  const result = plan(['ci/test-impact.js']);
  assert.equal(result.tests.length, result.totalTests);
  assert.ok(result.tests.length >= 294);
  assert.ok(result.shards <= 20);
});

test('known transitive impact includes its test, excludes proven unrelated tests, and includes unknowns', t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ci-selection-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  for (const [name, source] of Object.entries({
    'source.js': 'module.exports = 1;', 'other.js': 'module.exports = 2;',
    'known.test.js': "require('./source');", 'other.test.js': "require('./other');",
    'dynamic.test.js': 'require(variable);',
  })) fs.writeFileSync(path.join(dir, name), source);
  const mini = path.resolve(__dirname, '../chengyinhub-xcx');
  const files = ['known.test.js', 'other.test.js', 'dynamic.test.js'].map(file => path.relative(mini, path.join(dir, file)));
  assert.deepEqual(selectTests([path.join(dir, 'source.js')], files), [files[0], files[2]]);
});

test('Node hanging-test negative control exits red and the real runner keeps the 60s bound', t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ci-timeout-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'hang.test.js');
  fs.writeFileSync(file, "require('node:test')('blocked', t => { const timer = setInterval(() => {}, 1000); t.after(() => clearInterval(timer)); return new Promise(() => {}); });");
  const environment = { ...process.env };
  delete environment.NODE_TEST_CONTEXT;
  const result = require('node:child_process').spawnSync(process.execPath, ['--test', '--test-timeout=20', file], {
    encoding: 'utf8', timeout: 3000, env: environment,
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stdout + result.stderr, /testTimeoutFailure|timed out/);
  assert.match(fs.readFileSync(path.join(__dirname, 'run-mini-tests.js'), 'utf8'), /--test-timeout=60000/);
});
