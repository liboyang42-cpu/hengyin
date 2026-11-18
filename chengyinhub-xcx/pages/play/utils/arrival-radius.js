'use strict'
/**
 * 到达判定的端上实现，数值与规则以 docs/contracts/arrival-policy.v2.json 为准（后端 ArrivalPolicy、iOS 同读一份）。
 * 端上只做预检和提示，最终结果一律以服务端为准。
 *
 *   - 开局（城市定向本局第一次到达）：精度 > 200 m → 定位不准；距离 − min(精度, 200) ≤ 300 m 通过。
 *   - 自由探索到访：半径 = clamp(配置 ?? 300, 300, 500)；精度缺失按 80 m；精度 > 200 m → 定位不准；
 *     距离 ≤ 半径 + min(精度, 100) 通过。
 *
 * ARRIVAL_RADIUS_METERS（50 m）只是「人已在站点旁」时点亮「我已到达」按钮的提示半径，不会自动到达，
 * 服务端也不以它拒绝任何到达。
 */
const START = { radiusMeters: 300, maxRelaxMeters: 200, maxAccuracyMeters: 200 }
const VISIT = { defaultRadiusMeters: 300, minRadiusMeters: 300, maxRadiusMeters: 500, maxRelaxMeters: 100, missingAccuracyMeters: 80, maxAccuracyMeters: 200 }
const STATION_HIGHLIGHT_METERS = 50

const ARRIVAL_START_RADIUS_METERS = START.radiusMeters
const ARRIVAL_START_RADIUS_TEXT = ARRIVAL_START_RADIUS_METERS + ' 米'
const ARRIVAL_RADIUS_METERS = STATION_HIGHLIGHT_METERS

const PASS = 'PASS'
const TOO_FAR = 'TOO_FAR'
const ACCURACY_LOW = 'ACCURACY_LOW'

function finite(v) {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) && n >= 0 ? n : null
}

/** 开局判定。精度缺失时不放宽（只按距离预检，服务端再判）。 */
function startVerdict(distance, accuracy) {
  const acc = finite(accuracy)
  if (acc !== null && acc > START.maxAccuracyMeters) return ACCURACY_LOW
  const d = finite(distance)
  if (d === null) return TOO_FAR
  return d - Math.min(acc || 0, START.maxRelaxMeters) <= START.radiusMeters ? PASS : TOO_FAR
}

function visitRadius(configured) {
  const r = finite(configured)
  return Math.min(Math.max(r === null ? VISIT.defaultRadiusMeters : r, VISIT.minRadiusMeters), VISIT.maxRadiusMeters)
}

/** 自由探索到访判定。 */
function visitVerdict(distance, accuracy, configuredRadius) {
  const a = finite(accuracy)
  const acc = a === null ? VISIT.missingAccuracyMeters : a
  if (acc > VISIT.maxAccuracyMeters) return ACCURACY_LOW
  const d = finite(distance)
  if (d === null) return TOO_FAR
  return d <= visitRadius(configuredRadius) + Math.min(acc, VISIT.maxRelaxMeters) ? PASS : TOO_FAR
}

/** 「离这里还有约 X 米」的 X：取整到 10 米，至少 10 米。 */
function roundedMeters(v) {
  const n = finite(v)
  if (n === null) return null
  return Math.max(10, Math.round(n / 10) * 10)
}

module.exports = {
  ARRIVAL_START_RADIUS_METERS, ARRIVAL_START_RADIUS_TEXT, ARRIVAL_RADIUS_METERS,
  START, VISIT, STATION_HIGHLIGHT_METERS, PASS, TOO_FAR, ACCURACY_LOW,
  startVerdict, visitVerdict, visitRadius, roundedMeters,
}
