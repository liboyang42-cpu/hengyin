'use strict'
// D-69（业主 2026-11-11）：三处到点校验统一一个半径，前后端同值。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8')
const { ARRIVAL_RADIUS_METERS, ARRIVAL_RADIUS_TEXT } = require('../../pages/play/utils/arrival-radius.js')

test('D-69 端上半径常量为 50m，且文案由常量派生', () => {
  assert.equal(ARRIVAL_RADIUS_METERS, 50)
  assert.equal(ARRIVAL_RADIUS_TEXT, '50 米')
})

test('D-69 自动到达用常量，不再写死 50', () => {
  const play = read('pages/play/index.js')
  assert.match(play, /d <= ARRIVAL_RADIUS_METERS/)
  assert.doesNotMatch(play, /if \(d <= 50 &&/)
})

test('D-69 作答前的提示文案与同一个常量同源', () => {
  const play = read('pages/play/index.js')
  assert.match(play, /'先开启定位，到这一站 ' \+ ARRIVAL_RADIUS_TEXT \+ '内再作答'/)
})

test('D-69 后端三处引用同一个常量（50m 围栏，80m 精度闸独立）', () => {
  const guard = read('../chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/ArriveLocationGuardPolicy.java')
  assert.match(guard, /GEOFENCE_METERS = 50d/)
  const policy = read('../chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/ArrivalPolicy.java')
  assert.match(policy, /LEGACY_RADIUS_METERS = ArriveLocationGuardPolicy\.GEOFENCE_METERS/)
  const basic = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/service/play/PlayBasicCommandApplication.java')
  assert.match(basic, /GEOFENCE_METERS = ArrivalPolicy\.LEGACY_RADIUS_METERS/)
})
