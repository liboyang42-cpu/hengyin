'use strict'
// 路线卡底图:真实地图快照,样式随主题外观,失败只报错不画假街道。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')
const m = require('../../pages/play/utils/route-card-map.js')
const rc = require('../../pages/play/utils/route-card.js')

const route = [{ lat: 31.2, lng: 121.47 }, { lat: 31.21, lng: 121.48 }, { lat: 31.22, lng: 121.47 }]

test('底图样式随主题外观:纸本 paper、夜城 2077、其余与没设外观 black', () => {
  const cfg = { subkey: 'k', black: 1, white: 2, paper: 3, cyber: 4 }
  const ms = require('../../utils/map-style.js')
  assert.strictEqual(m.styleFor({ style: 'PAPER' }).kind, 'paper')
  assert.strictEqual(m.styleFor({ style: 'CYBER' }).kind, '2077')
  assert.strictEqual(m.styleFor({ style: 'DUST' }).kind, 'black')
  assert.strictEqual(m.styleFor(null).kind, 'black')
  assert.strictEqual(m.styleFor({ style: 'PAPER', legacy: true }).kind, 'black')
  const paper = m.styleFor({ style: 'PAPER' }, cfg)
  assert.deepStrictEqual([paper.subkey, paper.layerStyle, paper.tone], ['k', 3, 'paper'])
  assert.strictEqual(m.styleFor({ style: 'CYBER' }, cfg).layerStyle, ms.mapStyleFor('2077', cfg).layerStyle)
})

test('include-points 与 polyline 来自同一条路线,不足两点为空', () => {
  const pts = m.includePoints(route)
  assert.deepStrictEqual(pts[0], { latitude: 31.2, longitude: 121.47 })
  assert.strictEqual(pts.length, 3)
  const lines = m.polyline(route)
  assert.strictEqual(lines.length, 1)
  assert.deepStrictEqual(lines[0].points, pts)
  assert.deepStrictEqual(m.includePoints([route[0]]), [])
  assert.deepStrictEqual(m.polyline(null), [])
  assert.strictEqual(m.includePoints([{ lat: 'x', lng: 1 }, { lat: 1, lng: 1 }, { lat: 2, lng: 2 }]).length, 2)
})

test('快照成功给临时路径;失败/无 ctx/空结果都 reject,不给占位图', async () => {
  assert.strictEqual(await m.takeSnapshot({ takeSnapshot: (o) => o.success({ tempImagePath: '/tmp/a.jpg' }) }), '/tmp/a.jpg')
  await assert.rejects(m.takeSnapshot({ takeSnapshot: (o) => o.fail({ errMsg: 'takeSnapshot:fail' }) }), /takeSnapshot:fail/)
  await assert.rejects(m.takeSnapshot({ takeSnapshot: (o) => o.success({}) }), /snapshot-empty/)
  await assert.rejects(m.takeSnapshot({ takeSnapshot: () => { throw new Error('boom') } }), /boom/)
  await assert.rejects(m.takeSnapshot(null), /snapshot-unavailable/)
})

test('卡片底图:没有街道格网,有快照才 drawImage 铺底', () => {
  const src = fs.readFileSync(path.join(__dirname, '../../pages/play/utils/route-card.js'), 'utf8')
  assert.doesNotMatch(src, /bezierCurveTo|gridBold|街道/)
  const calls = []
  const g = new Proxy({}, { get: (t, k) => (k === 'measureText' ? () => ({ width: 1 }) : k === 'createLinearGradient' ? () => ({ addColorStop() {} }) : (typeof k === 'string' ? (...a) => calls.push([k, a]) : undefined)), set: () => true })
  const model = rc.cardModel({ track: route, name: 'A' })
  rc.drawCard(g, model, 0.5, null, null)
  assert.ok(!calls.some(([k]) => /^(bezierCurveTo|stroke|drawImage)$/.test(k)))
  calls.length = 0
  const snap = { snap: 1 }
  rc.drawCard(g, model, 1, null, snap)
  assert.strictEqual(calls.find(([k]) => k === 'drawImage')[1][0], snap)
})

test('2026-11-14 route-walk：快照失败显示已有线稿，重试保留真实快照优先', () => {
  const js = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8')
  const body = js.slice(js.indexOf('  _startRouteCard() {'), js.indexOf('  _routeCardPath(cb) {'))
  assert.match(body, /routeCard: \{ on: true, ready: false, error: true \}/)
  assert.match(body, /\.catch\(fail\)/)
  assert.match(body, /routeCardMap\.takeSnapshot/)
  assert.doesNotMatch(body, /drawCard\(g, model, /)
  assert.match(js, /retryRouteThumb\(\) \{\s*if \(\(this\.data\.routeCard \|\| \{\}\)\.on\) \{ this\.paintRouteThumb\(\); this\._startRouteCard\(\); return; \}/)
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/play/index.wxml'), 'utf8')
  assert.match(wxml, /id="routemap"[\s\S]*?fit-points="\{\{routeMap\.points\}\}"/)
  assert.match(wxml, /routeCard\.error && !finishSnap\}\}" bindtap="retryRouteThumb"/)
})
