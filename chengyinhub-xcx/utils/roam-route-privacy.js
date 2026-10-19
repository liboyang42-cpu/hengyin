'use strict'

/**
 * 城瘾 · 漫游分享的位置隐私闸
 *
 * 为什么存在:漫游足迹卡把 `track` 原样画出来,轨迹的第一个点就是用户按下 GO 的地方 ——
 * 通常是家门口,末尾那个点通常是回到家。把整条轨迹发到广场 = 把住址发到广场。
 * 这不是"图好看不好看",是位置隐私事故。
 *
 * 做法(照 Any Distance「Clip route for sharing」):**默认开启**,分享出去的轨迹
 * 首尾各裁掉 clipPercent%,可调 1%–40%。裁剪只作用在**分享产物**上(足迹卡预览 + 导出
 * canvas + 广场发帖用的那张图),自己回看的完整轨迹不动 —— 裁自己的记录没有意义。
 *
 * ⚠️ 裁剪比例是按**点数**裁,不是按里程。漫游轨迹按固定节奏采点,点数≈时间,
 *    起终点附近停留久、采点密,按点数裁反而对"家门口"这一段裁得更狠,方向是对的。
 */

const CLIP_MIN = 1
const CLIP_MAX = 40
const CLIP_DEFAULT = 10
const STORAGE_KEY = 'roam_share_privacy'
// M0112/N015 敏感地点遮挡。区域坐标只存本机(与本机轨迹同级),从不上传;
// 上传的只有「去敏声明」(规则版本/裁剪比例/遮挡个数),服务端按同一版本号校验。
const REDACTION_VERSION = 1
const MASK_ZONE_MAX = 5
const MASK_RADIUS_MIN = 100
const MASK_RADIUS_MAX = 1000
const MASK_RADIUS_DEFAULT = 300
const { distM } = require('./roam-geo.js')
const { normalizeTrack } = require('./roam-track-simplify.js')

// 可见性:客户端**真能兑现**的三档,不写后端不认识的字段。
//   public  = 发广场(真发帖,全站可见)
//   friends = 不进广场,只存图后自己发朋友圈(只有你的好友看得到);没有转发给好友的渠道
//   private = 只存相册,任何地方都不发布
// 三档都不影响「漫游历史」——本次记录始终留在本机的我的记录里。
const VISIBILITY = Object.freeze({
  PUBLIC: 'public',
  FRIENDS: 'friends',
  PRIVATE: 'private',
})
const VISIBILITY_VALUES = Object.freeze([VISIBILITY.PUBLIC, VISIBILITY.FRIENDS, VISIBILITY.PRIVATE])
const VISIBILITY_DEFAULT = VISIBILITY.PUBLIC

// 各档放行哪些渠道。渠道名与 pages/roam/index.js shareTo 的 data-ch 逐字对齐。
const VISIBILITY_CHANNELS = Object.freeze({
  [VISIBILITY.PUBLIC]: Object.freeze(['广场', '朋友圈', '小红书', '保存']),
  [VISIBILITY.FRIENDS]: Object.freeze(['朋友圈', '保存']),
  [VISIBILITY.PRIVATE]: Object.freeze(['保存']),
})

function normalizeClipPercent(value) {
  // null/undefined/'' = 没设置过,退默认;`Number(null)===0` 会被夹成 1%,那是把"没设置"
  // 误读成"用户选了最小值",说明文案会跟着显示 1%,与实际生效的 10% 对不上。
  if (value == null || value === '') return CLIP_DEFAULT
  const number = Math.round(Number(value))
  if (!Number.isFinite(number)) return CLIP_DEFAULT
  return Math.min(CLIP_MAX, Math.max(CLIP_MIN, number))
}

function normalizeVisibility(value) {
  return VISIBILITY_VALUES.indexOf(value) >= 0 ? value : VISIBILITY_DEFAULT
}

function normalizeMaskZone(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const lat = Number(raw.lat)
  const lng = Number(raw.lng)
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return null
  if (lat === 0 && lng === 0) return null
  const r = Math.round(Number(raw.r))
  return {
    id: String(raw.id || (lat.toFixed(4) + ',' + lng.toFixed(4))).slice(0, 32),
    name: String(raw.name || '敏感地点').slice(0, 20),
    lat: Math.round(lat * 1e4) / 1e4,
    lng: Math.round(lng * 1e4) / 1e4,
    r: Number.isFinite(r) ? Math.min(MASK_RADIUS_MAX, Math.max(MASK_RADIUS_MIN, r)) : MASK_RADIUS_DEFAULT,
  }
}

function normalizeMaskZones(raw) {
  const list = Array.isArray(raw) ? raw : []
  const out = []
  const seen = {}
  list.forEach((item) => {
    const zone = normalizeMaskZone(item)
    if (!zone || seen[zone.id] || out.length >= MASK_ZONE_MAX) return
    seen[zone.id] = true
    out.push(zone)
  })
  return out
}

/** 未设置过 = 用默认值(裁剪**开着**)。读不出来也一样 —— 隐私闸不因存储故障而失效。 */
function normalizeSharePrivacy(raw) {
  const source = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {}
  return {
    clipEnabled: source.clipEnabled == null ? true : !!source.clipEnabled,
    clipPercent: normalizeClipPercent(source.clipPercent),
    visibility: normalizeVisibility(source.visibility),
    maskZones: normalizeMaskZones(source.maskZones),
  }
}

function isPointMasked(point, setting) {
  const zones = setting && Array.isArray(setting.maskZones) ? setting.maskZones : []
  if (!zones.length || !point) return false
  const lat = Number(point.lat)
  const lng = Number(point.lng)
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return false
  return zones.some((zone) => distM({ lat, lng }, zone) <= zone.r)
}

/**
 * 去掉落在任一敏感区域内的点。与首尾裁剪相互独立:关掉裁剪也照样遮挡。
 * 只删点不补点:输出里不会出现区域内的坐标(区域两侧的点可能直线相连,那是图上的跨越,不是区域内的位置)。
 */
function maskTrackForSharing(track, setting) {
  const points = Array.isArray(track) ? track : []
  const privacy = normalizeSharePrivacy(setting)
  if (!privacy.maskZones.length) return points.slice()
  return points.filter((point) => !isPointMasked(point, privacy))
}

/** 分享产物里的地点同样过遮挡:家附近的店名+坐标一样能反推住址。 */
function maskPoisForSharing(pois, setting) {
  const list = Array.isArray(pois) ? pois : []
  const privacy = normalizeSharePrivacy(setting)
  if (!privacy.maskZones.length) return list.slice()
  return list.filter((poi) => !(poi && poi.lat != null && poi.lng != null && isPointMasked(poi, privacy)))
}

/** 随快照上传的去敏声明:不含区域坐标。服务端按 v 校验同一版本。 */
function redactionDeclaration(setting) {
  const privacy = normalizeSharePrivacy(setting)
  return {
    v: REDACTION_VERSION,
    clip: privacy.clipEnabled,
    pct: privacy.clipEnabled ? privacy.clipPercent : 0,
    zones: privacy.maskZones.length,
  }
}

function readSharePrivacy(storage) {
  const api = storage || (typeof wx !== 'undefined' ? wx : null)
  if (!api || typeof api.getStorageSync !== 'function') return normalizeSharePrivacy(null)
  try {
    return normalizeSharePrivacy(api.getStorageSync(STORAGE_KEY))
  } catch (_) {
    return normalizeSharePrivacy(null)
  }
}

/** 返回落盘后的完整设置;写失败时返回合并结果并带 ok:false,调用方要如实告诉用户没存上。 */
function writeSharePrivacy(patch, storage) {
  const next = normalizeSharePrivacy(Object.assign({}, readSharePrivacy(storage), patch))
  const api = storage || (typeof wx !== 'undefined' ? wx : null)
  if (!api || typeof api.setStorageSync !== 'function') return Object.assign({ ok: false }, next)
  try {
    api.setStorageSync(STORAGE_KEY, next)
    return Object.assign({ ok: true }, next)
  } catch (_) {
    return Object.assign({ ok: false }, next)
  }
}

/**
 * 裁掉轨迹首尾各 clipPercent%(按点数,各端向下取整)。
 *
 * 关不掉的下限:裁完少于 2 个点就画不出线段,这时返回空数组 —— 宁可让卡片显示
 * 「这次没有记录到轨迹」,也不能退回原轨迹,退回就是把隐私闸变成恒绿的假保证。
 */
function clipTrackForSharing(track, setting) {
  const points = Array.isArray(track) ? track : []
  const privacy = normalizeSharePrivacy(setting)
  const { clipEnabled, clipPercent } = privacy
  if (!clipEnabled) return maskTrackForSharing(points, privacy)
  if (points.length < 2) return maskTrackForSharing(points, privacy)
  const drop = Math.floor(points.length * clipPercent / 100)
  if (drop <= 0) return maskTrackForSharing(points, privacy)
  if (points.length - drop * 2 < 2) return []
  return maskTrackForSharing(points.slice(drop, points.length - drop), privacy)
}

/** 历史点已按几何抽稀，点数不再代表采样时间；按折线长度裁剪，短直线也不能泄露原首尾。 */
function clipSavedTrackForSharing(track, setting) {
  const points = normalizeTrack(track)
  const privacy = normalizeSharePrivacy(setting)
  if (!privacy.clipEnabled) return maskTrackForSharing(points, privacy)
  if (points.length < 2) return []
  const lengths = points.map((point, i) => i ? distM(points[i - 1], point) : 0)
  const total = lengths.reduce((sum, length) => sum + length, 0)
  if (!Number.isFinite(total) || total <= 0) return []
  const start = total * privacy.clipPercent / 100, end = total - start
  let travelled = 0
  const result = []
  for (let i = 1; i < points.length; i++) {
    const length = lengths[i], next = travelled + length
    if (length > 0 && next > start && travelled < end) {
      const a = points[i - 1], b = points[i]
      const interpolate = distance => {
        const t = (distance - travelled) / length
        return { lat: a.lat + (b.lat - a.lat) * t, lng: a.lng + (b.lng - a.lng) * t }
      }
      if (!result.length) result.push(interpolate(Math.max(start, travelled)))
      result.push(interpolate(Math.min(end, next)))
    }
    travelled = next
    if (travelled >= end) break
  }
  return maskTrackForSharing(result, privacy)
}

function channelsForVisibility(visibility) {
  return VISIBILITY_CHANNELS[normalizeVisibility(visibility)].slice()
}

function isChannelAllowed(channel, visibility) {
  return channelsForVisibility(visibility).indexOf(channel) >= 0
}

/**
 * 给 WXML 用的逐渠道布尔。
 * ⚠️ 别在 WXML 里写 `share.channels.indexOf('广场') >= 0` —— 实测(2026-08-27 automator
 *    回读 class)WXML 表达式里带中文字面量的 indexOf 恒返回 -1,四个渠道会一起置灰,
 *    而 data 层断言全绿。判断留在 JS 里,模板只读布尔。
 */
function channelFlags(visibility) {
  const list = channelsForVisibility(visibility)
  return {
    square: list.indexOf('广场') >= 0,
    moments: list.indexOf('朋友圈') >= 0,
    xhs: list.indexOf('小红书') >= 0,
    save: list.indexOf('保存') >= 0,
  }
}

module.exports = {
  CLIP_MIN,
  CLIP_MAX,
  CLIP_DEFAULT,
  STORAGE_KEY,
  REDACTION_VERSION,
  MASK_ZONE_MAX,
  MASK_RADIUS_DEFAULT,
  normalizeMaskZones,
  maskTrackForSharing,
  maskPoisForSharing,
  redactionDeclaration,
  VISIBILITY,
  VISIBILITY_VALUES,
  VISIBILITY_DEFAULT,
  normalizeClipPercent,
  normalizeVisibility,
  normalizeSharePrivacy,
  readSharePrivacy,
  writeSharePrivacy,
  clipTrackForSharing,
  clipSavedTrackForSharing,
  channelsForVisibility,
  channelFlags,
  isChannelAllowed,
}
