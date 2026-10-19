const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

test('装修页挂不可见 page-container 返回守卫', () => {
  const wxml = read('pages/merchant/decor/index.wxml')
  const m = wxml.match(/<page-container[\s\S]*?\/>/)
  assert.ok(m, '缺 page-container')
  assert.match(m[0], /show="\{\{!backGuardOff && \(imageEditor \|\| fieldSheet\.show \|\| discardVisible\)\}\}"/)
  assert.match(m[0], /bind:afterleave="onNativeBackGuard"/)
  assert.match(m[0], /overlay="\{\{false\}\}"/)
})

test('onNativeBackGuard 按 放弃确认>字段面板>图片编辑 关闭并重挂', () => {
  const js = read('pages/merchant/decor/index.js')
  const body = js.slice(js.indexOf('onNativeBackGuard()'), js.indexOf('onNativeBackGuard()') + 700)
  assert.ok(body.indexOf('keepEditing') < body.indexOf('closeFieldSheet'))
  assert.ok(body.indexOf('closeFieldSheet') < body.indexOf('closeImage'))
  assert.match(body, /backGuardOff: true/)
  assert.match(js, /backGuardOff: false/)
})
