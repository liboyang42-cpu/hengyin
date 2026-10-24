'use strict'

// C:U-120 / C:U-110:客户端距离/精度阈值是后端判定常量的镜像,这里逐个对照后端源码,漂移即判红。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const T = require('../../utils/roam-geo-thresholds.js')

const API = path.resolve(__dirname, '../../../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api')
const read = (file) => fs.readFileSync(path.join(API, file), 'utf8')

function javaConst(src, name) {
  const m = src.match(new RegExp('static final (?:int|double) ' + name + ' = ([0-9.]+)d?;'))
  assert.ok(m, '后端找不到常量 ' + name)
  return Number(m[1])
}

test('U-120: 打卡/到点半径与 ApiRoamController 常量一致,CHECKIN_NEAR_M = 后端拒收分界点', () => {
  const roam = read('ApiRoamController.java')
  assert.equal(T.SHOP_VISIT_RADIUS_M, javaConst(roam, 'SHOP_VISIT_RADIUS_M'))
  assert.equal(T.ROAM_GPS_TOLERANCE_M, javaConst(roam, 'ROAM_GPS_TOLERANCE_M'))
  assert.equal(T.POI_DISCOVER_RADIUS_M, javaConst(roam, 'POI_DISCOVER_RADIUS_M'))
  assert.match(roam, /> SHOP_VISIT_RADIUS_M \+ ROAM_GPS_TOLERANCE_M\)/, '后端 shop/visit 分界点口径变了,客户端要同步')
  assert.equal(T.CHECKIN_NEAR_M, T.SHOP_VISIT_RADIUS_M + T.ROAM_GPS_TOLERANCE_M)
})

test('U-110: 玩法地理围栏备查值与 ApiPlayProgressController 一致', () => {
  assert.equal(T.PLAY_GEOFENCE_M, javaConst(read('ApiPlayProgressController.java'), 'GEOFENCE_METERS'))
})

test('U-110: 本端轨迹过滤的瞬移速度与后端反作弊同一口径,且过滤器引用共享表而非字面量', () => {
  assert.equal(T.ROAM_MAX_SPEED_MPS, javaConst(read('ApiRoamController.java'), 'MAX_MOVE_SPEED_MPS'))
  const filter = fs.readFileSync(path.resolve(__dirname, '../../utils/roam-track-filter.js'), 'utf8')
  assert.match(filter, /loc\.accuracy > ROAM_MAX_ACCURACY_M/)
  assert.match(filter, /> ROAM_MAX_SPEED_MPS\) return null/)
  const { acceptRoamLocation } = require('../../utils/roam-track-filter.js')
  const base = { latitude: 31.23, longitude: 121.47, type: 'gcj02' }
  assert.ok(acceptRoamLocation({ ...base, accuracy: T.ROAM_MAX_ACCURACY_M }, null, 1000), '等于门槛放行')
  assert.equal(acceptRoamLocation({ ...base, accuracy: T.ROAM_MAX_ACCURACY_M + 1 }, null, 1000), null, '超过门槛丢弃')
})

test('U-120: 漫游页打卡半径取自共享表,不再手抄 130', () => {
  const page = fs.readFileSync(path.resolve(__dirname, '../../pages/roam/index.js'), 'utf8')
  assert.match(page, /const \{ CHECKIN_NEAR_M \} = require\('\.\.\/\.\.\/utils\/roam-geo-thresholds\.js'\);/)
  assert.doesNotMatch(page, /const CHECKIN_NEAR_M = \d+/)
})
