'use strict'

function parseDoorScene(scene) {
  if (scene == null || scene === '') return null
  let raw = String(scene)
  try { raw = decodeURIComponent(raw) } catch (e) { /* already decoded */ }
  raw = raw.trim()
  if (!/^[0-9a-fA-F]{32}$/.test(raw)) return null
  return raw.toLowerCase()
}

function pathForScanEntry(data) {
  if (!data || data.topicId == null || data.topicId === '') return null
  if (data.action === 'play') {
    const query = ['topicId=' + encodeURIComponent(data.topicId)]
    if (data.activityId != null && data.activityId !== '') {
      query.push('activityId=' + encodeURIComponent(data.activityId))
    }
    return '/pages/play/index?' + query.join('&')
  }
  return '/pages/topic/index/index?id=' + encodeURIComponent(data.topicId)
}

/** 小程序码进入：1047 扫码 / 1048 长按识别 / 1049 相册选取。其它 scene 可能仍带着上次的 query。 */
// 走查 v2 R15:主办方「本主题二维码」的 scene 是 tp.<主题id>(后端 /api/topic/host-qrcode),扫了进主题详情
function parseTopicScene(scene) {
  if (scene == null || scene === '') return null
  let raw = String(scene)
  try { raw = decodeURIComponent(raw) } catch (e) { /* already decoded */ }
  const m = /^tp\.(\d{1,18})$/.exec(raw.trim())
  return m ? m[1] : null
}

// 2026-10-31 P-8:官方活动分享卡上的小程序码,scene = ac.<活动id>
function parseActivityScene(scene) {
  if (scene == null || scene === '') return null
  let raw = String(scene)
  try { raw = decodeURIComponent(raw) } catch (e) { /* already decoded */ }
  const m = /^ac\.(\d{1,18})$/.exec(raw.trim())
  return m ? m[1] : null
}

// 第九轮 m1-9:商家主页分享卡上的店铺码,scene = sh.<商家会员ID>
function parseShopScene(scene) {
  if (scene == null || scene === '') return null
  let raw = String(scene)
  try { raw = decodeURIComponent(raw) } catch (e) { /* already decoded */ }
  const m = /^sh\.(\d{1,18})$/.exec(raw.trim())
  return m ? m[1] : null
}

// l-misc(2026-11-10 业主批准):队伍邀请小程序码。后端 /api/team/invite-qrcode 的 scene:
//   tm.<六位加入码或旧 22 位 base64url 邀请码> —— 原样;
//   th.<22 位 base64url>         —— 迁移回填的 32 位十六进制旧码(scene 限 32 字符,放不下前缀 + 32 位),还原成小写十六进制。
// 返回邀请码本身(交给加入页 /api/team/info、/api/team/join 由服务端裁决),认不出就是 null。
const B64URL = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'
function b64urlToHex(text) {
  let bits = 0
  let value = 0
  let hex = ''
  for (let i = 0; i < text.length; i++) {
    const v = B64URL.indexOf(text[i])
    if (v < 0) return null
    value = (value << 6) | v
    bits += 6
    if (bits >= 8) {
      bits -= 8
      hex += ((value >> bits) & 0xff).toString(16).padStart(2, '0')
    }
  }
  return hex
}

function parseTeamScene(scene) {
  if (scene == null || scene === '') return null
  let raw = String(scene)
  try { raw = decodeURIComponent(raw) } catch (e) { /* already decoded */ }
  const short = /^tm\.([23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6})$/.exec(raw.trim())
  if (short) return short[1]
  const m = /^(tm|th)\.([A-Za-z0-9_-]{22})$/.exec(raw.trim())
  if (!m) return null
  if (m[1] === 'tm') return m[2]
  const hex = b64urlToHex(m[2])
  return hex && hex.length === 32 ? hex : null
}

function isWxacodeEnter(scene) {
  const n = Number(scene)
  return n === 1047 || n === 1048 || n === 1049
}

// ONB-5:「扫普通链接二维码打开小程序」。印在小程序码旁的标准二维码是
// https://www.chengyinhub.com/door?scene=<同一 scene>,微信把整条链接放在 query.q(编码过一次)。
// 只认这个前缀、只取 scene,其它链接一律不当 scene。
const DOOR_LINK_PREFIX = 'https://www.chengyinhub.com/door?'
function sceneFromDoorLink(q) {
  if (q == null || q === '') return null
  let link = String(q)
  try { link = decodeURIComponent(link) } catch (e) { return null }
  link = link.trim()
  if (link.indexOf(DOOR_LINK_PREFIX) !== 0 || link.length > 512) return null
  const pairs = link.slice(DOOR_LINK_PREFIX.length).split('#')[0].split('&')
  let scene = null
  for (let i = 0; i < pairs.length; i++) {
    const at = pairs[i].indexOf('=')
    const key = at < 0 ? pairs[i] : pairs[i].slice(0, at)
    if (key !== 'scene') continue
    if (scene !== null) return null
    scene = at < 0 ? '' : pairs[i].slice(at + 1)
  }
  if (!scene) return null
  try { scene = decodeURIComponent(scene) } catch (e) { return null }
  scene = scene.trim()
  return scene && scene.length <= 32 ? scene : null
}

// 普通链接二维码进入:1011 扫码 / 1012 长按识别 / 1013 相册选取。
function isLinkQrEnter(scene) {
  const n = Number(scene)
  return n === 1011 || n === 1012 || n === 1013
}

/** 页面 options 归一:带 q 的标准链接码换成与小程序码相同的 options.scene;原有 scene 优先。 */
function withDoorLinkScene(options) {
  if (!options || options.scene || !options.q) return options
  const scene = sceneFromDoorLink(options.q)
  return scene ? Object.assign({}, options, { scene: scene }) : options
}

function captureDoorScene(e, currentPending) {
  const linkScene = e && isLinkQrEnter(e.scene) ? sceneFromDoorLink(e.query && e.query.q) : null
  if (linkScene) return captureDoorScene({ scene: 1047, query: { scene: linkScene } }, currentPending)
  const query = (e && e.query) || {}
  const topicId = parseTopicScene(query.scene)
  const activityId = parseActivityScene(query.scene)
  const shopId = parseShopScene(query.scene)
  let teamScene = null
  if (parseTeamScene(query.scene)) {
    teamScene = String(query.scene)
    try { teamScene = decodeURIComponent(teamScene) } catch (err) { /* already decoded */ }
    teamScene = teamScene.trim()
  }
  const code = parseDoorScene(query.scene) || (topicId ? 'tp.' + topicId : null) || (activityId ? 'ac.' + activityId : null)
    || (shopId ? 'sh.' + shopId : null) || teamScene
  if (!code || !isWxacodeEnter(e && e.scene)) {
    return currentPending || null
  }
  return code
}

module.exports = { parseDoorScene, parseTopicScene, pathForScanEntry, captureDoorScene, parseActivityScene, parseShopScene, parseTeamScene, sceneFromDoorLink, withDoorLinkScene }
