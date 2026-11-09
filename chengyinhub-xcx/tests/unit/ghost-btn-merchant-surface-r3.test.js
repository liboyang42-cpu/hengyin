'use strict'

// 打磨第 3 轮 §4-14:日间商家域的 ghost 按钮用 --cy-bg-subtle 底(白卡上原玻璃白底看不出按钮);其它域不变。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')
const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '')
function block(src, selector) {
  const m = strip(src).match(new RegExp('(^|\\n)' + selector.replace('.', '\\.') + ' \\{([^}]*)\\}'))
  assert.ok(m, selector)
  return m[2]
}

test('只在 .theme-merchant 与商家日间镜像里声明 ghost 底;暗色逃生口撤销声明', () => {
  const tokens = read('style/tokens.wxss')
  assert.match(block(tokens, '.theme-merchant'), /--cy-comp-btn-ghost-bg:\s*var\(--cy-bg-subtle\)/)
  assert.match(block(tokens, '.theme-dark'), /--cy-comp-btn-ghost-bg:\s*initial/)
  assert.doesNotMatch(block(tokens, 'page'), /--cy-comp-btn-ghost-bg/)
  assert.doesNotMatch(block(tokens, '.theme-topic-editor'), /--cy-comp-btn-ghost-bg/)
  assert.match(block(read('style/merchant-light.wxss'), 'page'), /--cy-comp-btn-ghost-bg:\s*var\(--cy-bg-subtle\)/)
})

test('cy-btn ghost 读组件 token,未声明时回落玻璃底', () => {
  assert.match(read('components/cy/btn/index.wxss'), /\.btn--ghost \{[^}]*background: var\(--cy-comp-btn-ghost-bg, var\(--cy-bg-glass\)\);/)
})
