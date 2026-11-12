'use strict'
// x-fix1 / B-1:所有 .wxs(含 WXML 内联 wxs)必须是 wcc 能编的 ES5 子集,且不得把 Number()/String() 等当函数调。
// 闸本体用 Node 自带 Acorn,需 --expose-internals,故在子进程里跑。
const test = require('node:test')
const assert = require('node:assert')
const path = require('path')
const { spawnSync } = require('child_process')

const GATE = path.resolve(__dirname, '../../scripts/wxs-syntax-gate.js')

function run(args) {
  return spawnSync(process.execPath, ['--expose-internals', GATE, ...args], { encoding: 'utf8' })
}

function runEval(code) {
  return spawnSync(process.execPath, ['--expose-internals', '-e', code], { encoding: 'utf8' })
}

test('全部 .wxs 与内联 wxs 通过 WXS 语法闸', () => {
  const r = run([])
  assert.strictEqual(r.status, 0, r.stdout + r.stderr)
  assert.match(r.stdout, /WXS-SYNTAX: PASS/)
})

test('自检夹具:bad-* 全部被拦、ok-* 全部通过', () => {
  const r = run(['--selftest'])
  assert.strictEqual(r.status, 0, r.stdout + r.stderr)
  assert.match(r.stdout, /WXS-SYNTAX SELFTEST: PASS/)
})

test('B-1 复现:对象字面量里的 Number(ds.wh) 必须被拦截,改成 (ds.wh * 1) 后放行', () => {
  const code = `
    const g = require(${JSON.stringify(GATE)});
    const bad = g.checkSource('function f(ds){ return { a: 1, wh: Number(ds.wh) || 0 }; }\\nmodule.exports = { f: f };', 'b1');
    const ok = g.checkSource('function f(ds){ return { a: 1, wh: (ds.wh * 1) || 0 }; }\\nmodule.exports = { f: f };', 'b1');
    process.stdout.write(JSON.stringify({ bad: bad, ok: ok }));
  `
  const r = runEval(code)
  assert.strictEqual(r.status, 0, r.stderr)
  const out = JSON.parse(r.stdout)
  assert.strictEqual(out.bad.length, 1, JSON.stringify(out.bad))
  assert.match(out.bad[0], /^b1:1:\d+ .*Number\(\)/)
  assert.deepStrictEqual(out.ok, [])
})
