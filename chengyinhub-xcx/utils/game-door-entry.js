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

function isWxacodeEnter(scene) {
  const n = Number(scene)
  return n === 1047 || n === 1048 || n === 1049
}

function captureDoorScene(e, currentPending) {
  const query = (e && e.query) || {}
  const topicId = parseTopicScene(query.scene)
  const code = parseDoorScene(query.scene) || (topicId ? 'tp.' + topicId : null)
  if (!code || !isWxacodeEnter(e && e.scene)) {
    return currentPending || null
  }
  return code
}

module.exports = { parseDoorScene, parseTopicScene, pathForScanEntry, captureDoorScene }
