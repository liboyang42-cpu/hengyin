'use strict'

// C:U-117 等价性回归:两套回放几何收口到 utils/roam-route-geometry 后,输出必须与收口前逐值一致。
// 下面两个 legacy* 函数是收口前实现的原文冻结(组件 route-replay.buildRouteGeometry 与 pages/roam _buildArchFrom),
// 只作为对照 oracle,不得改动;改几何参数 = 视觉变更,需要产品确认。
const test = require('node:test')
const assert = require('node:assert/strict')
const { limitTrack, normalizeTrack } = require('../../utils/roam-track-simplify.js')
const { m2lat, m2lng } = require('../../utils/roam-geo.js')
const { fitRoute, buildShareArch } = require('../../utils/roam-route-geometry.js')
const { buildRouteGeometry } = require('../../components/cy/scene-roam-session/route-replay.js')

const WIDTH = 670
const HEIGHT = 520
const PADDING = 60

function legacyBuildRouteGeometry(session) {
  const track = limitTrack(session && session.track)
  if (track.length < 2) return { segs: [], poiDots: [] }

  const kx = Math.cos(track[0].lat * Math.PI / 180)
  const project = (point) => ({ x: point.lng * kx, y: -point.lat })
  const trackPoints = track.map(project)
  const poiPoints = normalizeTrack(session && session.pois).map(project)
  const all = trackPoints.concat(poiPoints)
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  all.forEach((point) => {
    minX = Math.min(minX, point.x)
    minY = Math.min(minY, point.y)
    maxX = Math.max(maxX, point.x)
    maxY = Math.max(maxY, point.y)
  })
  const scale = Math.min(
    (WIDTH - PADDING * 2) / Math.max(1e-9, maxX - minX),
    (HEIGHT - PADDING * 2) / Math.max(1e-9, maxY - minY),
  )
  const offsetX = (WIDTH - (maxX - minX) * scale) / 2 - minX * scale
  const offsetY = (HEIGHT - (maxY - minY) * scale) / 2 - minY * scale
  const x = (point) => point.x * scale + offsetX
  const y = (point) => point.y * scale + offsetY
  const segs = []
  for (let index = 1; index < trackPoints.length; index += 1) {
    const fromX = x(trackPoints[index - 1])
    const fromY = y(trackPoints[index - 1])
    const toX = x(trackPoints[index])
    const toY = y(trackPoints[index])
    const length = Math.hypot(toX - fromX, toY - fromY)
    if (length < 2) continue
    segs.push({
      i: index,
      x: Math.round(fromX),
      y: Math.round(fromY),
      len: Math.round(length) + 2,
      deg: Math.round(Math.atan2(toY - fromY, toX - fromX) * 180 / Math.PI),
    })
  }
  const poiDots = poiPoints.map((point, index) => ({
    i: index,
    x: Math.round(x(point)),
    y: Math.round(y(point)),
    tone: index % 4,
  }))
  return { segs, poiDots }
}

function legacyBuildArchFrom(track, pois, center) {
  const W = 576; const H = 520; const padT = 100; const padB = 50; const padX = 60;
  const points = limitTrack(track);
  if (points.length < 2) return { archSegs: [], archPois: [], archEnd: {} };
  const c = center || points[0];
  const M = (p) => ({ x: (p.lng - c.lng) / m2lng(1, c.lat), y: -(p.lat - c.lat) / m2lat(1) });
  const pts = points.map(M);
  let x0 = 1e9; let y0 = 1e9; let x1 = -1e9; let y1 = -1e9;
  pts.forEach((p) => {
    x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y);
    x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y);
  });
  const s = Math.min((W - padX * 2) / Math.max(1, x1 - x0), (H - padT - padB) / Math.max(1, y1 - y0));
  const ox = (W - (x1 - x0) * s) / 2 - x0 * s;
  const oy = padT + (H - padT - padB - (y1 - y0) * s) / 2 - y0 * s;
  const X = (p) => p.x * s + ox; const Y = (p) => p.y * s + oy;
  const archSegs = [];
  for (let i = 1; i < pts.length; i++) {
    const ax = X(pts[i - 1]); const ay = Y(pts[i - 1]);
    const bx = X(pts[i]); const by = Y(pts[i]);
    const len = Math.hypot(bx - ax, by - ay);
    if (len === 0) continue;
    archSegs.push({
      i, x: Math.round(ax), y: Math.round(ay), len: Math.round(len) + 2,
      deg: Math.round(Math.atan2(by - ay, bx - ax) * 180 / Math.PI),
    });
  }
  const archPois = (Array.isArray(pois) ? pois : [])
    .filter((p) => p && p.lat != null && p.lng != null)
    .map((p, i) => {
      const m = M(p);
      return { i, x: Math.round(X(m)), y: Math.round(Y(m)), color: p.color };
    });
  const e = pts[pts.length - 1];
  return { archSegs, archPois, archEnd: { x: Math.round(X(e)), y: Math.round(Y(e)) } };
}

function rng(seed) {
  let x = seed >>> 0
  return () => { x = (x * 1664525 + 1013904223) >>> 0; return x / 4294967296 }
}

function randomRoute(seed) {
  const r = rng(seed)
  const n = 2 + Math.floor(r() * 400)
  const spread = [0.0002, 0.003, 0.05][seed % 3]
  let lat = 22 + r() * 20, lng = 100 + r() * 20
  const track = []
  for (let i = 0; i < n; i++) {
    if (!(seed % 7 === 0 && i % 5 === 1)) { lat += (r() - 0.5) * spread; lng += (r() - 0.5) * spread }
    track.push({ lat, lng })
  }
  const pois = []
  for (let i = 0; i < seed % 6; i++) pois.push({ lat: lat + (r() - 0.5) * spread * 4, lng: lng + (r() - 0.5) * spread * 4, color: '#' + i })
  return { track, pois }
}

test('U-117: 会话详情回放几何与收口前逐值一致(300 条随机轨迹,含零长度段与远处 POI)', () => {
  for (let seed = 1; seed <= 300; seed++) {
    const session = randomRoute(seed)
    assert.deepEqual(buildRouteGeometry(session), legacyBuildRouteGeometry(session), 'seed=' + seed)
  }
})

test('U-117: 分享卡拱形路线与收口前 _buildArchFrom 逐值一致', () => {
  for (let seed = 1; seed <= 300; seed++) {
    const { track, pois } = randomRoute(seed)
    const center = seed % 2 ? track[0] : { lat: track[0].lat + 0.001, lng: track[0].lng - 0.001 }
    assert.deepEqual(buildShareArch(limitTrack(track), pois, center), legacyBuildArchFrom(track, pois, center), 'seed=' + seed)
  }
  assert.deepEqual(buildShareArch(limitTrack([{ lat: 31, lng: 121 }]), [], null), { archSegs: [], archPois: [], archEnd: {} })
})

test('U-117: 等比投影 —— x/y 同一 scale,路线宽高比保持', () => {
  // 东西 400m × 南北 100m 的矩形回路:画框内宽高比必须仍是 4:1
  const o = { lat: 31.23, lng: 121.47 }
  const pt = (eastM, northM) => ({ lat: o.lat + m2lat(northM), lng: o.lng + m2lng(eastM, o.lat) })
  const track = [pt(0, 0), pt(400, 0), pt(400, 100), pt(0, 100), pt(0, 0)]
  const fit = fitRoute(track, [], { width: 576, height: 520, padTop: 100, padBottom: 50, padX: 60 })
  const xs = fit.segs.map((s) => s.x), ys = fit.segs.map((s) => s.y)
  const w = Math.max(...xs) - Math.min(...xs), h = Math.max(...ys) - Math.min(...ys)
  assert.ok(Math.abs(w / h - 4) < 0.05, 'ratio=' + w / h)
  assert.ok(Math.abs(fit.scale * 400 - (576 - 120)) < 1e-6, '宽方向贴满画框内宽')
})
