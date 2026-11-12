'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { acceptRoamLocation, MAX_SPEED_MPS } = require('../../utils/roam-track-filter.js')
const anchor = { lat: 31.2, lng: 121.5, ts: 1000 }
test('C7坐标结构/精度/GCJ02边界拒绝', () => {
  for (const loc of [{ latitude: NaN, longitude: 121 }, { latitude: 31, longitude: Infinity },
    { latitude: 91, longitude: 121 }, { latitude: 31, longitude: 181 },
    { latitude: 31.2, longitude: 121.5, accuracy: NaN }, { latitude: 31.2, longitude: 121.5, accuracy: 81 },
    { latitude: 31.2, longitude: 121.5, type: 'wgs84' }]) assert.equal(acceptRoamLocation(loc, anchor, 2000), null)
})
test('C7静止抖动与倒序时间拒绝，原anchor不受污染', () => {
  for (const [loc, now] of [[{ latitude: 31.2, longitude: 121.5 }, 2000],
    [{ latitude: 31.2001, longitude: 121.5 }, 1000]]) assert.equal(acceptRoamLocation(loc, anchor, now), null)
  const accepted = acceptRoamLocation({ latitude: 31.2001, longitude: 121.5 }, anchor, 2000)
  assert.ok(accepted.distance > 10 && accepted.distance < 12)
  assert.deepEqual(anchor, { lat: 31.2, lng: 121.5, ts: 1000 })
})
test('C7恢复首点重新建立采样anchor，累计原距离由调用者保留', () => {
  const accepted = acceptRoamLocation({ latitude: 32, longitude: 121, type: 'gcj02', accuracy: 80 }, null, 2000)
  assert.equal(accepted.distance, 0); assert.deepEqual(accepted.point, { lat: 32, lng: 121, ts: 2000 })
})

// D-70（业主 2026-11-11）：跳点不再丢段 —— 补插值、补里程，但里程按隐含速度封顶。
test('D-70 >120m 跳点不再丢样本，补插值并计里程', () => {
  // 约 300m、30 秒（10m/s，步行/慢跑都够）：整段计入，且补出中间点
  const accepted = acceptRoamLocation({ latitude: 31.2027, longitude: 121.5 }, anchor, 31000)
  assert.ok(accepted, '跳点必须被接收，不能是 null')
  assert.ok(accepted.distance > 200 && accepted.distance < 400, '估计里程应接近实际位移: ' + accepted.distance)
  assert.ok(accepted.points.length > 1, '应补出中间点保证轨迹连续')
  assert.equal(accepted.points[accepted.points.length - 1].lat, accepted.point.lat)
  assert.equal(accepted.capped, false)
})
test('D-70 大位移但物理上走不完：补插值但里程按隐含速度封顶', () => {
  // 约 2km、5 秒 => 400m/s，远超 35m/s；只承认 5 * MAX_SPEED_MPS
  const accepted = acceptRoamLocation({ latitude: 31.218, longitude: 121.5 }, anchor, 6000)
  assert.ok(accepted)
  assert.equal(accepted.capped, true)
  assert.ok(accepted.distance <= MAX_SPEED_MPS * 5 + 0.001, '封顶后里程: ' + accepted.distance)
  assert.ok(accepted.distance > 0, '封顶不是归零：仍承认走得到的那一段')
  assert.ok(accepted.points.length <= 65, '单段插值点上限 64：' + accepted.points.length)
})
test('D-70 超过 5km 的瞬移仍拒绝（sanity cap：不是走过去）', () => {
  assert.equal(acceptRoamLocation({ latitude: 31.3, longitude: 121.5 }, anchor, 3000), null, '约 11km')
  assert.equal(acceptRoamLocation({ latitude: 40, longitude: 121.5 }, anchor, 3000), null, '跨城/跨省')
})
