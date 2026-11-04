'use strict'
// 第九轮 m2 第 3 项:路线分享卡。首尾各剪 150 米,路线框与大数字不相交,画面只用传入的数据。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')
const rc = require('../../pages/play/utils/route-card.js')

function line(n, stepDeg) {
  const out = []
  for (let i = 0; i < n; i++) out.push({ lat: 31.2 + i * stepDeg, lng: 121.47 + Math.sin(i / 5) * 0.001 })
  return out
}

test('首尾各剪掉 150 米,起点终点都不在原处', () => {
  const pts = line(60, 0.0002) // 约 22 米一步,1.3 公里
  const total = rc.lengthM(pts)
  const f = rc.fuzzEnds(pts, 150)
  assert.ok(Math.abs(rc.lengthM(f) - (total - 300)) < 5, 'trimmed length ' + rc.lengthM(f))
  assert.ok(rc.distM(pts[0], f[0]) > 140)
  assert.ok(rc.distM(pts[pts.length - 1], f[f.length - 1]) > 140)
})

test('很短的路线各剪四分之一,总还剩一段', () => {
  const pts = line(6, 0.0002) // 约 110 米
  const f = rc.fuzzEnds(pts, 150)
  assert.ok(f.length >= 2)
  assert.ok(rc.lengthM(f) > rc.lengthM(pts) * 0.45)
})

test('路线只落在上面的框里,框与大数字、小程序码都不相交', () => {
  const box = rc.ROUTE_BOX, num = rc.NUMBER_BOX, code = rc.CODE
  assert.ok(box.y + box.h < num.y, 'route box ends above number box')
  assert.ok(box.y + box.h < code.cy - code.r, 'route box ends above code')
  for (const pts of [line(40, 0.0003), line(40, 0.00001), [{ lat: 31, lng: 121 }, { lat: 31.3, lng: 121.0001 }]]) {
    for (const p of rc.fitInto(pts, box)) {
      assert.ok(p.x >= box.x - 0.01 && p.x <= box.x + box.w + 0.01 && p.y >= box.y - 0.01 && p.y <= box.y + box.h + 0.01)
    }
  }
})

test('卡片数据:真实轨迹优先,没有轨迹用完成站与站间距离;城市去掉「市」', () => {
  const withTrack = rc.cardModel({ track: line(60, 0.0002), stops: [], name: '海上方舟', time: '1:26:54', doneCount: 7, city: '上海市' })
  assert.strictEqual(withTrack.big, rc.kmText(rc.lengthM(line(60, 0.0002))))
  assert.strictEqual(withTrack.stops, '7 站')
  assert.strictEqual(withTrack.city, '上海')
  const noTrack = rc.cardModel({ track: [], stops: line(5, 0.002), fallbackKm: '4.0', doneCount: 0 })
  assert.strictEqual(noTrack.big, '4.0')
  assert.strictEqual(noTrack.stops, '')
  assert.ok(noTrack.route.length >= 2)
})

test('画卡:进度未满时只画路线,满了才出字和码', () => {
  const calls = []
  const g = new Proxy({}, { get: (t, k) => (k === 'measureText' ? () => ({ width: 100 }) : (typeof k === 'string' && /^(fill|stroke|begin|move|line|arc|bezier|save|restore|clip|draw|fillText|fillRect)/.test(k) ? (...a) => calls.push(k) : undefined)), set: () => true })
  const model = rc.cardModel({ track: line(30, 0.0003), name: 'A', time: '10:00', doneCount: 2, city: '杭州' })
  rc.drawCard(g, model, 0.5)
  assert.ok(!calls.includes('fillText'))
  calls.length = 0
  rc.drawCard(g, model, 1, { w: 1 })
  assert.ok(calls.includes('fillText'))
  assert.ok(calls.includes('drawImage'))
})

test('游玩页:通关面板里就是这张卡,保存走同一张,不另加通关页,不奖励分享', () => {
  const src = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8')
  assert.match(src, /this\.buildReview\(\); this\._startRouteCard\(\);/)
  assert.match(src, /paintFinishCard\(cb\) \{\s*if \(this\.data\.routeCard\.on && this\.data\.routeCard\.ready\)/)
  const share = src.slice(src.indexOf('  shareRouteCard() {'), src.indexOf('  paintFinishCard(cb) {'))
  assert.doesNotMatch(share, /reward|grant|coupon|xp/i)
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/play/index.wxml'), 'utf8')
  assert.match(wxml, /<canvas type="2d" id="routecv"/)
  assert.match(wxml, /bindtap="saveFinishCard">保存图片<\/cy-btn>/)
})
