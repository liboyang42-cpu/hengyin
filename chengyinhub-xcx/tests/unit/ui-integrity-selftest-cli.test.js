'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const script = path.resolve(__dirname, '../lint/ui-integrity.js');
for (const flags of [[], ['--warn-as-error']]) {
  test('UI 门禁自证独立于扫描参数：--selftest ' + flags.join(' '), () => {
    const result = spawnSync(process.execPath, [script, '--selftest', ...flags], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.match(result.stdout, /\[SELFTEST\]\[WARN-AS-ERROR\] RED caught; restored GREEN/);
    assert.match(result.stdout, /UI-GATE-0 selftest:PASS/);
  });
}

test('负控：自证误用默认CLI策略时，省略warn-as-error必须再次判红', () => {
  const source = fs.readFileSync(script, 'utf8');
  const anchor = 'enforcedLegacyViolations(scanLegacy([js], []), true)';
  assert.equal(source.split(anchor).length - 1, 2);
  const mutated = source.replaceAll(anchor, 'enforcedLegacyViolations(scanLegacy([js], []))');
  const bootstrap = `const Module = require('node:module'), path = require('node:path');
    const filename = ${JSON.stringify(script)}, m = new Module(filename, module);
    m.filename = filename; m.paths = Module._nodeModulePaths(path.dirname(filename));
    m._compile(${JSON.stringify(mutated + '\nrunSelftest();')}, filename);`;
  const result = spawnSync(process.execPath, ['-e', bootstrap], { encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /WARN-AS-ERROR 自证失败/);
});
