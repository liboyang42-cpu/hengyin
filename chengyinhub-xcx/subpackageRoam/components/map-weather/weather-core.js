'use strict'

// 地图天气氛围的纯逻辑:片区取整、按片区的十分钟缓存、粒子数与每帧推进。画布与请求在 index.js。
// 片区与后端 MapWeatherServiceImpl 同为每度 20 格(约 5.5 km),同一片区十分钟内最多问一次。
const CELLS_PER_DEGREE = 20
const TTL_MS = 10 * 60 * 1000
const MAX_DPR = 2
const KINDS = { rain: 1, snow: 1, fog: 1 }

const cache = new Map()

function cellOf(lat, lng) {
  const la = Number(lat), lo = Number(lng)
  if (!Number.isFinite(la) || !Number.isFinite(lo) || Math.abs(la) > 90 || Math.abs(lo) > 180) return null
  if (la === 0 && lo === 0) return null
  return Math.floor(la * CELLS_PER_DEGREE) + ':' + Math.floor(lo * CELLS_PER_DEGREE)
}

/** 服务端 { kind, intensity } → 可画的天气;其余(含 clear、异常结构)一律 null = 什么都不画。 */
function normalize(data) {
  if (!data || typeof data !== 'object' || !KINDS[data.kind]) return null
  const n = Number(data.intensity)
  const intensity = Number.isFinite(n) ? Math.max(0.15, Math.min(1, n)) : 0.5
  return { kind: data.kind, intensity }
}

/** 返回 undefined = 无缓存或已过期;null = 缓存了「不画」。 */
function cached(cell, now) {
  const row = cache.get(cell)
  if (!row) return undefined
  if (now - row.at >= TTL_MS) { cache.delete(cell); return undefined }
  return row.weather
}

function remember(cell, weather, now) {
  cache.set(cell, { at: now, weather: weather || null })
  if (cache.size > 64) cache.delete(cache.keys().next().value)
}

function particleCount(weather) {
  if (!weather) return 0
  if (weather.kind === 'rain') return Math.round(60 + 100 * weather.intensity)
  if (weather.kind === 'snow') return Math.round(40 + 70 * weather.intensity)
  return 0
}

function makeParticles(weather, w, h, rand) {
  const r = rand || Math.random
  const out = []
  for (let i = particleCount(weather); i > 0; i--) {
    out.push({ x: r() * (w + 90), y: r() * h, v: 0.6 + r() * 0.7, s: r() })
  }
  return out
}

/** 推进一帧。k = 实际帧间隔 / 16.7ms,掉帧时速度不变。 */
function step(weather, parts, w, h, k, rand) {
  const r = rand || Math.random
  if (weather.kind === 'rain') {
    for (const p of parts) {
      p.y += 15 * p.v * k; p.x -= 2.6 * p.v * k
      if (p.y > h) { p.y = -20; p.x = r() * (w + 90) }
    }
  } else if (weather.kind === 'snow') {
    for (const p of parts) {
      p.y += 1.3 * p.v * k; p.x += Math.sin(p.y / 40 + p.s * 6) * 0.5 * k
      if (p.y > h) { p.y = -6; p.x = r() * w }
    }
  }
}

function dprOf(pixelRatio) {
  const n = Number(pixelRatio)
  return Number.isFinite(n) && n > 0 ? Math.min(MAX_DPR, n) : 1
}

function resetCache() { cache.clear() }

module.exports = { CELLS_PER_DEGREE, TTL_MS, MAX_DPR, cellOf, normalize, cached, remember, particleCount, makeParticles, step, dprOf, resetCache }
