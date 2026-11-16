'use strict'

// MPP-7:在小程序里走过去(步行路线视图模型、偏离重算规则)与锁站常驻卡。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const walk = require('../../pages/play/utils/walk-guide.js')
const { lockCard, BRANCH_FALLBACK, LINEAR_FALLBACK } = require('../../pages/play/utils/lock-card.js')

const line = [[31.2300, 121.4700], [31.2310, 121.4700], [31.2320, 121.4700]]

test('readRoute keeps a whole polyline or nothing', () => {
  const route = walk.readRoute({ distanceM: 420, durationS: 360, polyline: line })
  assert.equal(route.line.length, 3)
  assert.equal(route.distanceM, 420)
  assert.equal(walk.readRoute({ polyline: [[31.23, 121.47]] }), null)
  assert.equal(walk.readRoute({ polyline: [[31.23, 121.47], ['x', 1]] }), null)
  assert.equal(walk.readRoute(null), null)
})

test('remainingText reads metres, kilometres and minutes', () => {
  assert.equal(walk.remainingText(420), '还有 420 米 · 约 6 分钟')
  assert.equal(walk.remainingText(1500), '还有 1.5 公里 · 约 20 分钟')
  assert.equal(walk.remainingText(-1), '')
})

test('remaining distance follows the line', () => {
  const along = walk.remainingAlong([31.2300, 121.4700], line)
  assert.ok(along > 200 && along < 240, String(along))
})

test('three fixes more than 40 m off the line replan, at most twice', () => {
  const off = [31.2310, 121.4710] // ~95 m east of the line
  let state = walk.createState()
  let step
  for (let round = 0; round < 2; round++) {
    step = walk.onFix(state, off, line, line[2]); assert.equal(step.action, 'keep'); state = step.state
    step = walk.onFix(state, off, line, line[2]); assert.equal(step.action, 'keep'); state = step.state
    step = walk.onFix(state, off, line, line[2]); assert.equal(step.action, 'replan'); state = step.state
  }
  for (let i = 0; i < 4; i++) { step = walk.onFix(state, off, line, line[2]); assert.equal(step.action, 'keep'); state = step.state }
  assert.equal(state.replans, walk.MAX_REPLANS)
})

test('an on-route fix resets the off-route count and 50 m hands back to auto-arrive', () => {
  let step = walk.onFix(walk.createState(), [31.2310, 121.4710], line, line[2])
  assert.equal(step.state.offFixes, 1)
  step = walk.onFix(step.state, [31.2305, 121.4700], line, line[2])
  assert.equal(step.state.offFixes, 0)
  assert.equal(walk.onFix(step.state, [31.2318, 121.4700], line, line[2]).action, 'arrive')
})

test('failures fall back to the 导航过去 button with plain copy', () => {
  assert.equal(walk.failureCode({ netFail: true }), 'NETWORK')
  assert.equal(walk.failureCode({ code: 400, errorCode: 'ROUTE_TOO_FAR' }), 'ROUTE_TOO_FAR')
  assert.equal(walk.failureCode({ code: 500, errorCode: 'ROUTE_UNAVAILABLE' }), 'ROUTE_UNAVAILABLE')
  for (const code of ['NO_LOCATION', 'WEAK_GPS', 'NETWORK', 'ROUTE_TOO_FAR', 'ROUTE_UNAVAILABLE']) {
    const text = walk.errorText(code)
    assert.ok(text.length > 0)
    assert.doesNotMatch(text, /即将开放|敬请期待|lease|gate/)
  }
})

test('lock card keeps the server reason and the original fallback copy', () => {
  assert.equal(lockCard({ nodeId: 1 }, [], 'LINEAR'), null)
  assert.equal(lockCard({ nodeId: 2, locked: true, lockReason: '先拿到钥匙' }, [], 'BRANCH_GRAPH').text, '先拿到钥匙')
  assert.equal(lockCard({ nodeId: 2, locked: true }, [], 'BRANCH_GRAPH').text, BRANCH_FALLBACK)
  const nodes = [{ nodeId: 1, name: '钟楼', done: false }]
  assert.equal(lockCard({ nodeId: 2, locked: true, unlockAfterNodeId: 1 }, nodes, 'LINEAR').text, '先完成「钟楼」才会解锁')
  assert.equal(lockCard({ nodeId: 2, locked: true, unlockAfterNodeId: 1 }, [{ nodeId: 1, name: '钟楼', done: true }], 'LINEAR'), null)
  assert.equal(lockCard({ nodeId: 3, locked: true }, nodes, 'LINEAR').text, LINEAR_FALLBACK)
})

test('the play page uses the card, not a toast, for a locked stop, and offers the walk next to 导航过去', () => {
  const root = path.join(__dirname, '../..')
  const js = fs.readFileSync(path.join(root, 'pages/play/index.js'), 'utf8')
  const tip = js.slice(js.indexOf('  _lockedTip(node) {'), js.indexOf('  closeLockCard() {'))
  assert.ok(tip.includes('lockCard(node, this.data.nodes, this._routeState.routeMode)'))
  assert.ok(!tip.includes('前路未知'))
  assert.ok(js.includes("req('/api/play/walk-route', 'GET', data, undefined, true)"))
  assert.ok(js.includes('if (that._walkTarget) that._walkGuideOnFix(that._loc);'))
  const wxml = fs.readFileSync(path.join(root, 'pages/play/index.wxml'), 'utf8')
  assert.ok(wxml.includes('bindtap="startWalkGuide"'))
  assert.ok(wxml.includes('bindtap="openNextStopNav"'))
  assert.ok(wxml.includes('class="lock-card" wx:if="{{lockCard.show}}"'))
})
