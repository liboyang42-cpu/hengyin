'use strict'
const { distM } = require('./roam-geo.js')

/**
 * 漫游轨迹采样过滤（业主 2026-11-11 D-70：跳点不再丢段，补插值补里程，但要带 sanity cap）。
 *
 * 旧口径：单次位移 > 120m 或隐含速度 > 35m/s 直接丢样本 —— 地铁、隧道口、GPS 漂移后回来的那一段
 * 既不上图也不计里程，玩家实际走了 8km 却只记到 6km。
 *
 * 新口径：
 *   - 抖动（< 3m）仍然丢，避免静止时堆点；
 *   - 其余样本一律接收，但**计入里程的距离**按隐含速度封顶：`min(实际位移, MAX_SPEED_MPS × 间隔)`；
 *     这样瞬移/GPS 毛刺只能贡献"物理上走得完"的那一段，不会把总里程吹起来；
 *   - 同时按 `INTERP_STEP_M` 在上一锚点与本次点之间补插值点（覆盖整段真实位移，供画线与点亮判定连续），
 *     补的点只影响轨迹形状，不影响里程（里程只加封顶后的 distance）。
 *
 * 返回值保持向后兼容（point / distance 仍在），新增：
 *   - `points`：本段应补进轨迹的点（含终点，不含上一锚点）；直线段时长度为 1；
 *   - `interpolated`：补出来的中间点数（0 = 没补）；
 *   - `capped`：true 表示本段里程被速度上限截断过。
 *
 * Existing roam displacement/jitter/accuracy limits and server POI speed guard.
 */
const MIN_STEP_M = 3            // 抖动地板：静止/微动不上图
const MAX_SPEED_MPS = 35        // 隐含速度上限（与后端 POI 速度守卫同量级）
const INTERP_STEP_M = 30        // 插值步长：一段最多补 ceil(d/30)-1 个点
const MAX_INTERP_POINTS = 64    // 单段插值点上限，防止大跳变一次补几千个点
const MAX_JUMP_M = 5000         // sanity cap：超过 5km 的单次跳变按瞬移拒绝（不是"走过去了"）

function interpolate(previous, point, distance) {
  const steps = Math.min(MAX_INTERP_POINTS, Math.max(1, Math.ceil(distance / INTERP_STEP_M)))
  if (steps <= 1) return [{ lat: point.lat, lng: point.lng }]
  const out = []
  for (let i = 1; i < steps; i += 1) {
    const t = i / steps
    out.push({ lat: previous.lat + (point.lat - previous.lat) * t, lng: previous.lng + (point.lng - previous.lng) * t })
  }
  out.push({ lat: point.lat, lng: point.lng })
  return out
}

function acceptRoamLocation(loc, previous, now) {
  const lat = loc && loc.latitude, lng = loc && loc.longitude
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) return null
  if (loc.type != null && loc.type !== 'gcj02') return null
  if (loc.accuracy != null && (!Number.isFinite(loc.accuracy) || loc.accuracy < 0 || loc.accuracy > 80)) return null
  if (!Number.isFinite(now)) return null
  const point = { lat, lng, ts: now }
  if (!previous) return { point, distance: 0, points: [{ lat, lng }], interpolated: 0, capped: false }
  const elapsed = now - previous.ts
  if (elapsed <= 0) return null
  const distance = distM(previous, point)
  if (!Number.isFinite(distance) || distance < MIN_STEP_M) return null
  // 5km 之外不是"跳点"而是瞬移/坐标系错乱：仍拒绝，否则锚点会跳到另一个城市
  if (distance > MAX_JUMP_M) return null
  // 保守封顶：只承认「这段时间按上限速度走得到」的距离。低于地板时仍记地板值，
  // 免得高频采样把正常步行也压成 0（3m/次、1s 一次 = 3m/s < 35m/s，正常情况不会触发）。
  const capM = MAX_SPEED_MPS * (elapsed / 1000)
  const counted = Math.max(MIN_STEP_M, Math.min(distance, capM))
  const points = interpolate(previous, point, distance)
  return { point, distance: counted, points, interpolated: Math.max(0, points.length - 1), capped: counted < distance }
}
module.exports = { acceptRoamLocation, MIN_STEP_M, MAX_SPEED_MPS, INTERP_STEP_M, MAX_JUMP_M }
