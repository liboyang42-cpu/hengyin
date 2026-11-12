const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { parseReviewRouteOptions } = require('../../pages/merchant/reviews/view-model.js')

// x-fix3 L-5：商家自己裸开口碑不能落进「商家信息不完整」死路。

test('商家视角裸开 → 管理态；玩家视角裸开仍是缺参', () => {
  assert.deepEqual(parseReviewRouteOptions({}, true), { mode: 'manage', merchantRowId: null })
  assert.deepEqual(parseReviewRouteOptions(undefined, true), { mode: 'manage', merchantRowId: null })
  assert.equal(parseReviewRouteOptions({}, false).mode, 'public')
  assert.equal(parseReviewRouteOptions({}).mode, 'public')
})

test('显式参数不被视角改写', () => {
  assert.equal(parseReviewRouteOptions({ merchantRowId: '17' }, true).mode, 'public')
  assert.equal(parseReviewRouteOptions({ mode: 'public' }, true).mode, 'public')
  assert.equal(parseReviewRouteOptions({ mode: 'manage' }, false).mode, 'manage')
})

test('页面把当前视角传给路由解析', () => {
  const js = fs.readFileSync(path.join(__dirname, '../../pages/merchant/reviews/index.js'), 'utf8')
  assert.match(js, /require\('\.\.\/\.\.\/\.\.\/utils\/roleGuard\.js'\)/)
  assert.match(js, /parseReviewRouteOptions\(options, merchantView\)/)
})

// x-fix3 ME-1：门店信息基础资料一张列表里空值只用一种说法。
test('门店信息空值统一为「未设置」', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/merchant/decor/index.wxml'), 'utf8')
  const start = wxml.indexOf("<block wx:elif=\"{{view === 'basic'}}\">")
  const basic = wxml.slice(start, wxml.indexOf("<block wx:elif=\"{{view === 'qualification'}}\">", start))
  assert.doesNotMatch(basic, /待填写|未填写|未上传/)
  for (const v of ["m.name || '未设置'", "m.preference || '未设置'", "m.phone || '未设置'", "wechatId || '未设置'", "basicEditing ? '上传二维码' : '未设置'"]) {
    assert.ok(basic.includes(v), v)
  }
})
