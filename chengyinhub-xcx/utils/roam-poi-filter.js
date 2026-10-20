'use strict'

/* BL-01 漫游 POI 筛选:范围(1/3/5 km)+ 只看未到过。
 *
 * - 范围只作用于 GET /api/roam/pois 的 radius 参数(后端已有,上限 20km),不改后端;
 *   默认 3km 与原先写死的 ROAM_POI_RADIUS_M 一致,未设置过筛选的用户行为不变。
 * - 「到过」只认点位本地态里由服务端 found 推出的 passed,以及本次打完卡的 done;
 *   待核销(pendingRedeem)还差一步(券没到手),不算「到过」,筛选时保留。
 * - 按账号持久化 `roam_filter_v1:<userId>`;拿不到账号时只在本次页面内生效,不落盘,
 *   避免换号后串用上一个人的偏好。
 */

const STORAGE_PREFIX = 'roam_filter_v1'
const RADIUS_OPTIONS = [1000, 3000, 5000]
const DEFAULT_FILTER = Object.freeze({ radiusM: 3000, unvisitedOnly: false })
const VISITED_STATES = { passed: true, done: true }

function normalizeFilter(raw) {
  const value = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {}
  const radius = Number(value.radiusM)
  return {
    radiusM: RADIUS_OPTIONS.indexOf(radius) >= 0 ? radius : DEFAULT_FILTER.radiusM,
    unvisitedOnly: value.unvisitedOnly === true,
  }
}

function storageKey(userId) {
  const owner = String(userId == null ? '' : userId).trim()
  return owner && owner !== '0' ? STORAGE_PREFIX + ':' + owner : ''
}

function readFilter(userId, storage) {
  const key = storageKey(userId)
  if (!key || !storage || typeof storage.getStorageSync !== 'function') return normalizeFilter(null)
  try {
    return normalizeFilter(storage.getStorageSync(key))
  } catch (_) {
    return normalizeFilter(null)
  }
}

function writeFilter(userId, filter, storage) {
  const key = storageKey(userId)
  if (!key || !storage || typeof storage.setStorageSync !== 'function') return false
  try {
    storage.setStorageSync(key, normalizeFilter(filter))
    return true
  } catch (_) {
    return false
  }
}

function isVisitedPoi(poi) {
  return !!(poi && Object.prototype.hasOwnProperty.call(VISITED_STATES, poi.state))
}

/** 地图上这个点该不该因筛选被藏起来。正在打卡/打卡失败的目标点永远保留。 */
function hiddenByFilter(poi, filter, stateKey) {
  if (stateKey === 'target' || stateKey === 'checkin-failed') return false
  return !!(filter && filter.unvisitedOnly && isVisitedPoi(poi))
}

function radiusLabel(radiusM) {
  return (Number(radiusM) / 1000) + ' km'
}

function radiusTabs() {
  return RADIUS_OPTIONS.map((m) => ({ key: String(m), label: radiusLabel(m) }))
}

module.exports = {
  STORAGE_PREFIX,
  RADIUS_OPTIONS,
  DEFAULT_FILTER,
  normalizeFilter,
  storageKey,
  readFilter,
  writeFilter,
  isVisitedPoi,
  hiddenByFilter,
  radiusLabel,
  radiusTabs,
}
