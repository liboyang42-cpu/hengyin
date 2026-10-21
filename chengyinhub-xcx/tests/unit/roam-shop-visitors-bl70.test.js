'use strict'
// BL-70:漫游商家横滑卡头像堆。只消费 /api/roam/shop/visitors 真值;total=0 不展示,失败不阻塞卡片。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { visitorSourceOf, buildVisitorRequests, shapeVisitorRows, SOURCE_MAX } = require('../../utils/roam-shop-visitors.js')

const ROOT = path.resolve(__dirname, '../..')
const ROAM_JS = fs.readFileSync(path.join(ROOT, 'pages/roam/index.js'), 'utf8')
const ROAM_WXML = fs.readFileSync(path.join(ROOT, 'pages/roam/index.wxml'), 'utf8')

test('来源口径与 _apiShopVisit 一致:_roamId=1 优先,regId=2,演示点无来源', () => {
  assert.deepEqual(visitorSourceOf({ _roamId: 7, regId: 3 }), { sourceType: 1, sourceId: 7 })
  assert.deepEqual(visitorSourceOf({ regId: '3' }), { sourceType: 2, sourceId: 3 })
  assert.equal(visitorSourceOf({ name: '演示' }), null)
  assert.equal(visitorSourceOf({ regId: 0 }), null)
  assert.equal(visitorSourceOf(null), null)
})

test('按类型分组、去重、逗号分隔,不超过后端上限', () => {
  const reqs = buildVisitorRequests([
    { sourceType: 1, sourceId: 7 }, { sourceType: 2, sourceId: 3 }, { sourceType: 2, sourceId: 3 },
    { sourceType: 2, sourceId: 4 }, { id: 'demo' }, { sourceType: 9, sourceId: 1 },
  ])
  assert.deepEqual(reqs, [{ sourceType: 1, sourceIds: '7' }, { sourceType: 2, sourceIds: '3,4' }])
  assert.deepEqual(buildVisitorRequests([]), [])
  const many = Array.from({ length: 30 }, (_, i) => ({ sourceType: 2, sourceId: i + 1 }))
  assert.equal(buildVisitorRequests(many)[0].sourceIds.split(',').length, SOURCE_MAX)
})

test('回包:total=0/坏行不展示;头像只认 http(s),最多 5 张;人数照 total', () => {
  const out = shapeVisitorRows(2, [
    { sourceId: 3, avatars: ['https://a/1.png', '', null, 'javascript:x', 'http://a/2.png'], total: 12 },
    { sourceId: 4, avatars: [], total: 0 },
    { sourceId: 'x', avatars: [], total: 3 },
    null,
  ])
  assert.deepEqual(Object.keys(out), ['2:3'])
  assert.deepEqual(out['2:3'].faces, ['https://a/1.png', 'http://a/2.png'])
  assert.equal(out['2:3'].total, 12)
  assert.equal(out['2:3'].label, '12 人打过卡')
  const six = shapeVisitorRows(1, [{ sourceId: 1, total: 6, avatars: Array.from({ length: 7 }, (_, i) => 'https://a/' + i) }])
  assert.equal(six['1:1'].faces.length, 5)
  assert.deepEqual(shapeVisitorRows(1, null), {})
  assert.deepEqual(shapeVisitorRows(1, { rows: [] }), {})
})

test('页面:POST 正确路径与参数名,失败静默,迟到回包按 epoch 丢弃', () => {
  assert.match(ROAM_JS, /req\('\/api\/roam\/shop\/visitors', 'POST', \{\s*sourceType: String\(body\.sourceType\),\s*sourceIds: body\.sourceIds,\s*\}, \{ silentError: true \}\)/)
  assert.match(ROAM_JS, /if \(epoch !== this\._shopStripEpoch \|\| !apiOk\(res\)\) return;/)
  assert.match(ROAM_JS, /closeShopStrip\(\) \{\s*this\._shopStripEpoch = /)
})

test('模板:沿用原型 poi__who/poi__face/poi__more,只在有 visitors 时渲染', () => {
  assert.match(ROAM_WXML, /<view class="poi__who" wx:if="\{\{item\.visitors\}\}"/)
  assert.match(ROAM_WXML, /<image class="poi__face" wx:for="\{\{item\.visitors\.faces\}\}"/)
  assert.match(ROAM_WXML, /<text class="poi__more">\{\{item\.visitors\.label\}\}<\/text>/)
})
