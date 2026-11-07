'use strict'
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')
const { createRouteLine, MAX_BASE, TAIL } = require('../../utils/my-route-line.js')

function walk(n) {
  const out = []
  for (let i = 0; i < n; i++) out.push({ lat: 31.23 + i * 0.00003 + (i % 7) * 0.00001, lng: 121.47 + Math.sin(i / 9) * 0.0004 })
  return out
}

test('两层:宽半透明光晕在下,窄亮线在上', () => {
  const lines = createRouteLine().lines(walk(5))
  assert.strictEqual(lines.length, 2)
  assert.ok(lines[0].width > lines[1].width * 2)
  assert.match(lines[0].color, /^#FFFFFF[0-9A-F]{2}$/)
  assert.ok(parseInt(lines[0].color.slice(7), 16) < 0x80, 'glow is translucent')
  assert.deepStrictEqual(lines[0].points, lines[1].points)
})

test('少于两个点不画', () => {
  assert.deepStrictEqual(createRouteLine().lines([{ lat: 31, lng: 121 }]), [])
  assert.deepStrictEqual(createRouteLine().lines(null), [])
})

test('长轨迹点数有上限,首尾保留,随走随加', () => {
  const line = createRouteLine()
  const track = []
  let max = 0
  for (const p of walk(3000)) {
    track.push(p)
    const l = line.lines(track)
    if (l.length) max = Math.max(max, l[0].points.length)
  }
  assert.ok(max <= MAX_BASE + TAIL + 1, 'max points ' + max)
  const last = line.lines(track)[1].points
  assert.strictEqual(last[0].latitude, track[0].lat)
  assert.strictEqual(last[last.length - 1].latitude, track[track.length - 1].lat)
})

test('换了新的一段轨迹(新会话/恢复)不沿用旧缓存', () => {
  const line = createRouteLine()
  const a = walk(400)
  line.lines(a)
  const b = walk(500).map((p) => ({ lat: p.lat + 1, lng: p.lng }))
  const pts = line.lines(b)[0].points
  assert.ok(pts.every((p) => p.latitude > 32))
})

test('漫游页用同一条线;藏到后台不推线,回来补一次', () => {
  const src = fs.readFileSync(path.join(__dirname, '../../pages/roam/index.js'), 'utf8')
  assert.match(src, /require\('\.\.\/\.\.\/utils\/my-route-line\.js'\)/)
  assert.match(src, /this\._track\.length > 1 && !this\._hidden\) patch\.polyline = this\._myRouteLine\(\)\.lines\(this\._track\)/)
  assert.match(src, /if \(this\._realOn\) this\._flushTrackLine\(\);/)
  assert.doesNotMatch(src, /points: this\._track\.map/)
})

const { createPlayTrail } = require('../../pages/play/utils/play-trail.js')
function memStore() { const m = {}; return { m, getStorageSync: (k) => m[k], setStorageSync: (k, v) => { m[k] = v }, removeStorageSync: (k) => { delete m[k] } } }

test('游玩轨迹:抖动与低精度点不记,按场次落本机,换场不接上一场', () => {
  const st = memStore()
  let t = 0
  const tr = createPlayTrail(st, 'a:7', () => t)
  assert.strictEqual(tr.note({ latitude: 31.23, longitude: 121.47, accuracy: 10 }), true)
  assert.strictEqual(tr.note({ latitude: 31.23001, longitude: 121.47, accuracy: 10 }), false, '1 m jitter')
  assert.strictEqual(tr.note({ latitude: 31.2310, longitude: 121.47, accuracy: 200 }), false, 'poor accuracy')
  assert.strictEqual(tr.note({ latitude: 31.2310, longitude: 121.47, accuracy: 20 }), true)
  assert.strictEqual(tr.lines().length, 2)
  tr.persist()
  assert.strictEqual(createPlayTrail(st, 'a:7').points().length, 2)
  assert.strictEqual(createPlayTrail(st, 'a:8').points().length, 0)
})

test('游玩页:定位回调记点,地图露出才推线,藏后台不推;free-map 合并走过的线', () => {
  const src = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8')
  assert.match(src, /that\._noteMyTrail\(loc\);/)
  assert.match(src, /if \(this\._themeHidden \|\| this\._playDisposed \|\| !this\._trail\) return;/)
  assert.match(src, /if \(!force && !this\._trailVisible\(\)\) return;/)
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/play/index.wxml'), 'utf8')
  assert.strictEqual((wxml.match(/trail-polyline="\{\{fmTrail\}\}"/g) || []).length, 2)
  const fm = fs.readFileSync(path.join(__dirname, '../../components/cy/free-map/index.wxml'), 'utf8')
  assert.match(fm, /polyline="\{\{lines\}\}"/)
})
