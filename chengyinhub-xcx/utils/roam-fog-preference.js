'use strict'
// 2026-10-27 owner P-32:漫游设置里的「迷雾」开关。关掉只是不画雾层(足迹与点亮照常记录),
// 是本机显示偏好,不上传。读不出来就当开着(默认有雾)。
const FOG_OFF_KEY = 'roam_fog_off'

function target(storage) {
  if (storage) return storage
  return typeof wx !== 'undefined' ? wx : null
}

function readFogOff(storage) {
  const s = target(storage)
  if (!s || typeof s.getStorageSync !== 'function') return false
  try { return s.getStorageSync(FOG_OFF_KEY) === true } catch (e) { return false }
}

// 返回保存后的值;没存上返回 null,页面据此提示重试,不假装已切换
function writeFogOff(storage, value) {
  const s = target(storage)
  if (!s || typeof s.setStorageSync !== 'function') return null
  try { s.setStorageSync(FOG_OFF_KEY, value === true); return value === true } catch (e) { return null }
}

module.exports = { FOG_OFF_KEY, readFogOff, writeFogOff }
