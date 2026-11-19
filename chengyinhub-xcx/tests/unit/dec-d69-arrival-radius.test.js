'use strict'
// 业主 2026-11-13 拍板（取代 2026-11-11 D-69 的逐站 50m）：
// “大概就是 300 米，开头需要注意，游戏中就不需要了，开始的时候稍微停一下。”
// => 只在开局校验一次 300m；开局之后各站到点不要求定位。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8')
const { ARRIVAL_START_RADIUS_METERS, ARRIVAL_START_RADIUS_TEXT, ARRIVAL_RADIUS_METERS } = require('../../pages/play/utils/arrival-radius.js')

const policy = require('../../pages/play/utils/arrival-radius.js')
// F08 v2:两端(及 iOS)测试读同一份合同,路径相对仓库根
const contract = JSON.parse(fs.readFileSync(path.join(root, '..', 'docs/contracts/arrival-policy.v2.json'), 'utf8'))

test('开局校验半径 300m,文案由常量派生;常量与 v2 合同一致', () => {
  assert.equal(ARRIVAL_START_RADIUS_METERS, contract.start.radiusMeters)
  assert.equal(ARRIVAL_START_RADIUS_TEXT, contract.start.radiusMeters + ' 米')
  assert.equal(ARRIVAL_RADIUS_METERS, contract.stationHighlightMeters, '50m 只剩靠近点亮「我已到达」的提示半径,不是规则')
  assert.deepEqual(policy.START, { radiusMeters: contract.start.radiusMeters, maxRelaxMeters: contract.start.maxRelaxMeters, maxAccuracyMeters: contract.start.maxAccuracyMeters })
  const v = contract.freeExploreVisit
  assert.deepEqual(policy.VISIT, { defaultRadiusMeters: v.defaultRadiusMeters, minRadiusMeters: v.minRadiusMeters, maxRadiusMeters: v.maxRadiusMeters,
    maxRelaxMeters: v.maxRelaxMeters, missingAccuracyMeters: v.missingAccuracyMeters, maxAccuracyMeters: v.maxAccuracyMeters })
})

test('v2 合同:每个用例端上判定与合同一致', () => {
  assert.ok(contract.cases.length >= 10)
  for (const c of contract.cases) {
    const got = c.kind === 'start' ? policy.startVerdict(c.distance, c.accuracy) : policy.visitVerdict(c.distance, c.accuracy, c.radius)
    assert.equal(got, c.expect, JSON.stringify(c))
  }
  assert.equal(policy.visitRadius(null), 300)
  assert.equal(policy.visitRadius(900), 500)
  assert.equal(policy.visitRadius(100), 300)
  assert.equal(policy.roundedMeters(184), 180)
  assert.equal(policy.roundedMeters(3), 10)
})

test('客户端开局闸按 v2 判:精度 150m 时 420m 放行、460m 拦下;精度 250m 提示重新定位', () => {
  const play = read('pages/play/index.js')
  const start = play.indexOf('  _startTooFar(node) {')
  const end = play.indexOf('\n  },', start)
  const body = play.slice(start, end + 4)
  const run = (distance, accuracy) => new Function('arrivalPolicy', 'START_ACCURACY_LOW_TEXT', 'ARRIVAL_START_RADIUS_TEXT', 'return ({' + body + '})._startTooFar')(policy, '定位不准，再试一次', '300 米')
    .call({ data: { mode: 1, nodes: [] }, _loc: { latitude: 0, longitude: 0, accuracy }, _startLocationNeeded: () => true, haversine: () => distance }, { lat: 1, lng: 1 })
  assert.equal(run(420, 150), '')
  assert.match(run(460, 150), /离起点太远/)
  assert.equal(run(10, 250), '定位不准，再试一次')
  assert.equal(run(300, undefined), '')
  assert.match(run(301, undefined), /离起点太远/)
})

test('城市定向每站主按钮统一「我已到达」,不再有「到了」', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /\(!sheet\.node\.lat \? '开始' : '我已到达'\)/)
  assert.doesNotMatch(wxml, /'到了'/)
  assert.match(wxml, /bindtap="onScanArrive">扫码到达<\/cy-btn>/, '拒绝定位的人保留扫码到达')
})

test('逐站不再申请定位：到点按钮只在未开局时才要求定位', () => {
  const play = read('pages/play/index.js')
  assert.match(play, /_startLocationNeeded\(\) \{\s*return !\(this\.data\.nodes \|\| \[\]\)\.some\(\(n\) => n && n\.done\);/)
  assert.match(play, /const needsLocation = this\._startLocationNeeded\(\) && !this\._deskStart\(\);/)
  assert.doesNotMatch(play, /needsLocation = this\._advancedReadyNodeId/, '首站已解开玩法也要开局定位(2026-11-13 走查 1066km 外通关)')
  assert.doesNotMatch(play, /到这一站 ' \+ ARRIVAL_RADIUS_TEXT/)
})

test('作答前的定位提示只在开局时出现，文案与 300m 常量同源', () => {
  const play = read('pages/play/index.js')
  assert.match(play, /gameNode\.lng && \(this\._startLocationNeeded\(\) \|\| this\._forceStartFix === true\)/)
  assert.match(play, /'开局前请先开启定位，在起点 ' \+ ARRIVAL_START_RADIUS_TEXT \+ '内再开始'/)
})

test('后端：开局 300m 校验，站点不再用 50m 围栏', () => {
  const javaPolicy = read('../chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/ArrivalPolicy.java')
  assert.match(javaPolicy, /startViolation/)
  const basic = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/service/play/PlayBasicCommandApplication.java')
  assert.match(basic, /ArrivalPolicy\.startViolation\(/)
  assert.match(basic, /shopDayProofService\.runStarted\(/)
  assert.doesNotMatch(basic, /GEOFENCE_METERS/)
})
