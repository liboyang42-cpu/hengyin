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
test('U5 外层 const 失败闭包只接受可见反馈，遮蔽、改写与参数错位仍红', t => {
  const { scanU5 } = require('../lint/ui-integrity');
  const dir = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'u5-outer-fail-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const file = path.join(dir, 'index.js');
  fs.writeFileSync(path.join(dir, 'index.wxml'), '<view>{{error}}</view>');
  const request = 'app.sendRequest({url:"/api/project/home",silentError:true,fail:failed})';
  const visible = 'const failed = res => { if (current()) this.setData({error:res.msg || "请重试"}); };';
  const cases = [
    [visible, '', '', true],
    ['const failed = res => toast("请重试");', '', '', true],
    [visible, 'const failed = unknown;', '', false],
    [visible, '', 'failed', false],
    [visible, '', '{failed}', false],
    [visible, 'var failed;', '', false],
    [visible + 'failed = unknown;', '', '', false],
    [visible, 'failed = unknown;', '', false],
    ['const failed = res => {};', '', '', false],
    ['const failed = res => resolve(null);', '', '', false],
    ['const failed = res => () => toast("请重试");', '', '', false],
    ['const failed = res => { failed(res); toast("请重试"); };', '', '', false],
    ['const failed = toast => toast("请重试");', '', '', false],
    ['const failed = res => this.showFailure(res, "请重试");', '', '', false],
    ['const failed = res => this.showFailure("请重试");', '', '', true],
  ];
  for (const [declaration, shadow, parameter, green] of cases) {
    const source = 'const app=getApp();Page({showFailure(message){this.setData({error:message});},load(){'
      + declaration + 'const next=(' + parameter + ')=>{' + shadow + request + '};next();}})';
    fs.writeFileSync(file, source);
    assert.equal(scanU5([file], new Map()).violations.length === 0, green, source);
  }
  fs.writeFileSync(file, 'const app=getApp();Page({load(){const failed=()=>{};const next=()=>{'
    + 'app.sendRequest({silentError:true,fail:failed,success:()=>toast("成功")});};next();}})');
  assert.equal(scanU5([file], new Map()).violations.length, 1);
});
test('U5 共享方法只认真实 Page methods 宿主，所有宿主都必须消费错误态', t => {
  const { scanU5 } = require('../lint/ui-integrity');
  const dir = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'u5-method-owner-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const moduleFile = path.join(dir, 'shared.js'), page = path.join(dir, 'index.js'), other = path.join(dir, 'other.js');
  fs.writeFileSync(moduleFile, 'const methods={load(){const failed=res=>{if(current())this.setData({error:res.msg || "请重试"});};'
    + 'const next=()=>{getApp().sendRequest({silentError:true,fail:failed});};next();}};module.exports={methods};');
  const owner = 'const shared=require("./shared.js");Page({...shared.methods});';
  fs.writeFileSync(page, owner);
  fs.writeFileSync(path.join(dir, 'index.wxml'), '<include src="fragment.wxml"/>');
  fs.writeFileSync(path.join(dir, 'fragment.wxml'), '<view>{{error}}</view>');
  const green = files => assert.equal(scanU5(files, new Map()).violations.length, 0);
  const red = files => assert.equal(scanU5(files, new Map()).violations.length, 1);
  green([moduleFile, page]);
  red([moduleFile]);
  for (const source of ['const shared=require("./shared.js");Page({});',
    'const shared=require("./shared.js");Page({...shared.data});',
    'const shared=require("./unrelated.js");Page({...shared.methods});']) {
    fs.writeFileSync(page, source); red([moduleFile, page]);
  }
  fs.writeFileSync(page, owner);
  fs.writeFileSync(path.join(dir, 'fragment.wxml'), '<view>{{unrelatedError}}</view>'); red([moduleFile, page]);
  fs.writeFileSync(path.join(dir, 'fragment.wxml'), '<view>{{error}}</view>');
  fs.writeFileSync(other, owner);
  fs.writeFileSync(path.join(dir, 'other.wxml'), '<view>{{unrelatedError}}</view>'); red([moduleFile, page, other]);
  fs.writeFileSync(path.join(dir, 'other.wxml'), '<view>{{error}}</view>'); green([moduleFile, page, other]);
  const sharedSource = fs.readFileSync(moduleFile, 'utf8');
  fs.writeFileSync(moduleFile, sharedSource.replace('const methods=', 'const unused=').replace('module.exports={methods};',
    'const methods={other(){}};module.exports={methods};'));
  red([moduleFile, page]);
  fs.writeFileSync(moduleFile, sharedSource);
  const actual = path.resolve(__dirname, '../../pages/publish/fabu');
  const result = scanU5([path.join(actual, 'theme-runtime-structure.js'), path.join(actual, 'index.js')], new Map());
  assert.deepEqual(result.violations, []);
  assert.ok(result.autoSafe.some(item => item.key.endsWith('theme-runtime-structure.js:336')));
  assert.ok(result.autoSafe.some(item => item.key.endsWith('theme-runtime-structure.js:359')));
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
