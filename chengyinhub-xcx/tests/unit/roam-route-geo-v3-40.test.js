'use strict'

// 2026-11-09 V3-40 / V1-P37:漫游记录卡用真实底图画这次自己的轨迹;没有坐标退回示意图,不编造位置。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { routeGeo, MAX_POINTS } = require('../../utils/roam-route-geo.js')

const read = (rel) => fs.readFileSync(path.join(__dirname, '../..', rel), 'utf8')

test('有两个以上有效坐标才出真实地图数据:折线、起终点与途经店铺圆点,视野框住全部点', () => {
  const track = [{ lat: 31.23, lng: 121.47 }, { lat: 31.235, lng: 121.475 }, { lat: 'x', lng: 1 }, { lat: 31.24, lng: 121.48 }]
  const geo = routeGeo(track, [{ lat: 31.236, lng: 121.476 }, null])
  assert.equal(geo.polyline.length, 1)
  assert.deepEqual(geo.polyline[0].points.map((p) => p.latitude), [31.23, 31.235, 31.24], '坏点丢掉,顺序不变')
  assert.equal(geo.circles.length, 3, '1 个店铺点 + 起点 + 终点')
  assert.deepEqual(geo.circles.slice(-2).map((c) => c.latitude), [31.23, 31.24])
  assert.equal(geo.include.length, 6)
  assert.ok(geo.include.at(-2).latitude < Math.min(...geo.polyline[0].points.map(p => p.latitude)))
  assert.ok(geo.include.at(-1).longitude > Math.max(...geo.polyline[0].points.map(p => p.longitude)))
  assert.ok(geo.circles.every((c) => c.radius >= 6))
})

test('没有轨迹或只有一个点返回 null(卡片退回示意图)', () => {
  assert.equal(routeGeo(null, null), null)
  assert.equal(routeGeo([{ lat: 31, lng: 121 }], [{ lat: 31, lng: 121 }]), null)
  assert.equal(routeGeo([{ lat: 999, lng: 121 }, { lat: 31, lng: 121 }]), null)
})

test('长轨迹抽稀到上限,首尾点保留', () => {
  const track = Array.from({ length: 1000 }, (_, i) => ({ lat: 31 + i * 1e-5, lng: 121 }))
  const pts = routeGeo(track).polyline[0].points
  assert.equal(pts.length, MAX_POINTS)
  assert.equal(pts[0].latitude, 31)
  assert.equal(pts[pts.length - 1].latitude, track[999].lat)
})

test('记录卡把 geo 交给地图卡;地图卡是不可拖动缩放的原生 map,点击层仍落到外层卡片', () => {
  assert.match(read('components/cy/scene-roam-history/index.js'), /geo: routeGeo\(session && session\.track, session && session\.pois\)/)
  assert.match(read('components/cy/scene-roam-history/index.wxml'), /<cy-route-thumb route="\{\{item\.route\}\}" geo="\{\{item\.geo\}\}" \/>/)
  const wxml = read('components/cy/route-thumb/index.wxml')
  assert.match(wxml, /<map class="rc__real"[^>]*enable-scroll="\{\{false\}\}" enable-zoom="\{\{false\}\}"/)
  assert.match(wxml, /<view class="rc__hit"><\/view>/)
  assert.match(wxml, /<view class="rc__map" wx:else>/, '没有坐标仍是原示意图')
})

test('缩放级按轨迹范围给:短途放大、长途缩小,限制在 3–18', () => {
  const { fitScale } = require('../../utils/roam-route-geo.js')
  assert.ok(fitScale(1500, 31) > fitScale(20000, 31))
  assert.equal(fitScale(1, 31), 18)
  assert.equal(fitScale(5e7, 31), 3)
})
