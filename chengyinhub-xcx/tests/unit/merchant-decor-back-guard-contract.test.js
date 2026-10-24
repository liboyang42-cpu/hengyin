const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

test('装修页挂不可见 page-container 返回守卫', () => {
  const wxml = read('pages/merchant/decor/index.wxml')
  const m = wxml.match(/<page-container[\s\S]*?\/>/)
  assert.ok(m, '缺 page-container')
  // owner 2026-10-22:选图跳裁剪页期间(imagePicking)守卫让位;胶囊多选 sheet 也受返回键保护
  assert.match(m[0], /show="\{\{!backGuardOff && !imagePicking && \(imageEditor \|\| fieldSheet\.show \|\| discardVisible \|\| chipSheet\.show\)\}\}"/)
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

test('选图期间守卫 leave 不关图片编辑,上传结果落到编辑器预览(owner 2026-10-22 裁剪无效)', () => {
  const js = read('pages/merchant/decor/index.js')
  const guard = js.slice(js.indexOf('onNativeBackGuard()'), js.indexOf('onNativeBackGuard()') + 200)
  assert.match(guard, /if \(this\._imagePicking\) return;/)
  const choose = js.slice(js.indexOf('  chooseEditImage()'), js.indexOf('  applyPickedImage('))
  assert.ok(choose.indexOf('this._beginPicking()') < choose.indexOf('app.chooseImage('), '必须先标记 picking 再离页')
  assert.match(choose, /this\.applyPickedImage\(key, urls\[0\]\)/)
  assert.doesNotMatch(choose, /this\.data\.imageEditor && this\.data\.imageEditor\.key === key\) this\.setData/,
    '不能再以「编辑器仍开着」为前提才落图')
})
