'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { acceptRoamLocation } = require('../../utils/roam-track-filter.js')
const anchor = { lat: 31.2, lng: 121.5, ts: 1000 }
test('C7坐标结构/精度/GCJ02边界拒绝', () => {
  for (const loc of [{ latitude: NaN, longitude: 121 }, { latitude: 31, longitude: Infinity },
    { latitude: 91, longitude: 121 }, { latitude: 31, longitude: 181 },
    { latitude: 31.2, longitude: 121.5, accuracy: NaN }, { latitude: 31.2, longitude: 121.5, accuracy: 81 },
    { latitude: 31.2, longitude: 121.5, type: 'wgs84' }]) assert.equal(acceptRoamLocation(loc, anchor, 2000), null)
})
test('C7抖动、跳点、瞬移和倒序时间拒绝，原anchor不受污染', () => {
  for (const [loc, now] of [[{ latitude: 31.2, longitude: 121.5 }, 2000],
    [{ latitude: 31.3, longitude: 121.5 }, 2000],
    [{ latitude: 31.2008, longitude: 121.5 }, 2000],
    [{ latitude: 31.2001, longitude: 121.5 }, 1000]]) assert.equal(acceptRoamLocation(loc, anchor, now), null)
  const accepted = acceptRoamLocation({ latitude: 31.2001, longitude: 121.5 }, anchor, 2000)
  assert.ok(accepted.distance > 10 && accepted.distance < 12)
  assert.deepEqual(anchor, { lat: 31.2, lng: 121.5, ts: 1000 })
})
test('C7恢复首点重新建立采样anchor，累计原距离由调用者保留', () => {
  const accepted = acceptRoamLocation({ latitude: 32, longitude: 121, type: 'gcj02', accuracy: 80 }, null, 2000)
  assert.equal(accepted.distance, 0); assert.deepEqual(accepted.point, { lat: 32, lng: 121, ts: 2000 })
})
