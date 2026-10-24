'use strict'

// C:U-089:旧 overlay/poly 迷雾降级链路生产不可达,待删(删除补丁见
// docs/delivery/defer-20261024/DEFER-ROAM-U089-fog-dead-code.patch;落地需 C00 同步重排
// tests/lint/ui-integrity-baseline.js 的行号键)。在删除前先把「不可达」锁住:
// 一旦有人新增 'overlay' 赋值或从别处调用 poly 入口,这条测试判红,删除补丁就不再安全。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const src = fs.readFileSync(path.join(__dirname, '../../pages/roam/index.js'), 'utf8')

function methodBody(name) {
  const start = src.indexOf('\n  ' + name + '(')
  assert.ok(start >= 0, '找不到方法 ' + name)
  const next = src.slice(start + 1).search(/\n  [_a-zA-Z]\w*\([^)]*\) \{/)
  return src.slice(start, next < 0 ? undefined : start + 1 + next)
}

test("U-089: 生产代码没有任何 _fogMode = 'overlay' 赋值", () => {
  assert.doesNotMatch(src, /this\._fogMode\s*=\s*'overlay'/)
})

test('U-089: poly 唯一入口 _fogToPoly 只被 _fogFallbackToPoly 调,后者只在 overlay 守卫下的 _flushFogOverlay 内调', () => {
  const toPolyCalls = src.match(/this\._fogToPoly\(\)/g) || []
  assert.equal(toPolyCalls.length, 1)
  assert.match(methodBody('_fogFallbackToPoly'), /this\._fogToPoly\(\)/)
  const fallbackCalls = src.match(/this\._fogFallbackToPoly\(\)/g) || []
  const flush = methodBody('_flushFogOverlay')
  assert.equal((flush.match(/this\._fogFallbackToPoly\(\)/g) || []).length, fallbackCalls.length,
    '_fogFallbackToPoly 只能从 _flushFogOverlay 内调用')
  assert.match(flush, /^\s*_flushFogOverlay\(\) \{\s*if \(this\._fogMode !== 'overlay'/m)
})

test('U-089: _initFogGrid 只从 _fogToPoly 与 _rebuildFogDisplay 的 this._fog 分支进入(_fog 只由它自己赋值)', () => {
  assert.equal((src.match(/this\._fog = \{/g) || []).length, 1)
  assert.match(methodBody('_initFogGrid'), /this\._fog = \{/)
})
