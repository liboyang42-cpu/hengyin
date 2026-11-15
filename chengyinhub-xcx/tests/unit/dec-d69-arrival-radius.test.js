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

test('开局校验半径 300m，文案由常量派生', () => {
  assert.equal(ARRIVAL_START_RADIUS_METERS, 300)
  assert.equal(ARRIVAL_START_RADIUS_TEXT, '300 米')
  assert.equal(ARRIVAL_RADIUS_METERS, 50, '50m 只剩靠近自动点亮的提示半径，不是规则')
})

test('逐站不再申请定位：到点按钮只在未开局时才要求定位', () => {
  const play = read('pages/play/index.js')
  assert.match(play, /_startLocationNeeded\(\) \{\s*return !\(this\.data\.nodes \|\| \[\]\)\.some\(\(n\) => n && n\.done\);/)
  assert.match(play, /const needsLocation = this\._startLocationNeeded\(\);/)
  assert.doesNotMatch(play, /needsLocation = this\._advancedReadyNodeId/, '首站已解开玩法也要开局定位(2026-11-13 走查 1066km 外通关)')
  assert.doesNotMatch(play, /到这一站 ' \+ ARRIVAL_RADIUS_TEXT/)
})

test('作答前的定位提示只在开局时出现，文案与 300m 常量同源', () => {
  const play = read('pages/play/index.js')
  assert.match(play, /gameNode\.lng && \(this\._startLocationNeeded\(\) \|\| this\._forceStartFix === true\)/)
  assert.match(play, /'开局前请先开启定位，在起点 ' \+ ARRIVAL_START_RADIUS_TEXT \+ '内再开始'/)
})

test('后端：开局 300m 校验，站点不再用 50m 围栏', () => {
  const policy = read('../chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/ArrivalPolicy.java')
  assert.match(policy, /START_RADIUS_METERS = 300d/)
  const basic = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/service/play/PlayBasicCommandApplication.java')
  assert.match(basic, /ArrivalPolicy\.startViolation\(/)
  assert.match(basic, /shopDayProofService\.runStarted\(/)
  assert.doesNotMatch(basic, /GEOFENCE_METERS/)
})
