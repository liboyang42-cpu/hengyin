const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const source = fs.readFileSync(
  path.join(__dirname, '../../src/components/HeaderSearch/index.vue'),
  'utf8'
)

test('菜单搜索可由键盘打开、自动聚焦并用 Esc 关闭', () => {
  assert.match(source, /<button[^>]+aria-label="打开菜单搜索"/)
  assert.match(source, /ref="headerSearchSelectRef"/)
  assert.match(source, /\$refs\.headerSearchSelectRef\.focus\(\)/)
  assert.match(source, /@keyup\.esc\.native="close"/)
  assert.doesNotMatch(source, /\$refs\.headerSearchSelect(?!Ref)/)
})
