'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const script = path.resolve(__dirname, '../lint/ui-integrity.js');

test('U5 局部失败回调有可见反馈才放行，未知、遮蔽、递归仍红', t => {
  const { scanU5 } = require('../lint/ui-integrity');
  const dir = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'u5-local-fail-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'index.js');
  const request = 'app.sendRequest({url:"/api/project/home",silentError:true,fail:failed})';
  const cases = [
    ['const failed = () => toast("失败");', '', true],
    ['const failed = res => { if (active()) toast(app.getRequestErrorMessage(res, "请重试")); };', '', true],
    ['const failed = (res) => { toast(res.message); };', '', true],
    ['', '', false],
    ['let failed = res => { toast(res.message); };', '', false],
    ['const failed = res => { resolve(null); };', '', false],
    ['const failed = res => { failed(res); toast("失败"); };', '', false],
    ['const failed = toast => { toast("失败"); };', '', false],
    ['const failed = res => { const toast = unknown; toast("失败"); };', '', false],
    ['const failed = res => { return () => toast("失败"); };', '', false],
    ['const failed = res => { toast("失败"); };', 'const failed = unknown;', false],
    ['function other() { const failed = res => { toast("失败"); }; }', '', false],
  ];
  for (const [declaration, shadow, green] of cases) {
    const source = shadow
      ? 'const app=getApp();Page({load(){' + declaration + '{' + shadow + request + '}}})'
      : 'const app=getApp();Page({load(){' + declaration + request + '}})';
    fs.writeFileSync(file, source);
    assert.equal(scanU5([file], new Map()).violations.length === 0, green, source);
  }
  const actual = path.resolve(__dirname, '../../subpackageA/pages/myproject/index.js');
  assert.deepEqual(scanU5([actual], new Map()).violations, []);
});
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
