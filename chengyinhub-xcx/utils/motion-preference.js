const REDUCED_MOTION_KEY = 'play_reduced_motion'
const LEGACY_REDUCED_MOTION_KEY = 'roam_reduced_motion'

function resolveStorage(storage) {
  if (storage) return storage
  return typeof wx !== 'undefined' ? wx : null
}

function readReducedMotion(storage) {
  const target = resolveStorage(storage)
  if (!target || typeof target.getStorageSync !== 'function') return false
  try {
    const current = target.getStorageSync(REDUCED_MOTION_KEY)
    if (current === true || current === false) return current
    return target.getStorageSync(LEGACY_REDUCED_MOTION_KEY) === true
  } catch (e) {
    return false
  }
}

// null 表示主键未保存；false 是已保存的“恢复动态效果”。
function writeReducedMotion(storage, value) {
  const enabled = value === true
  const target = resolveStorage(storage)
  if (!target || typeof target.setStorageSync !== 'function') return null
  try {
    target.setStorageSync(REDUCED_MOTION_KEY, enabled)
  } catch (e) {
    return null
  }
  // 读取优先主键；兼容键写失败不能把已保存的主键伪报为失败。
  try {
    target.setStorageSync(LEGACY_REDUCED_MOTION_KEY, enabled)
  } catch (e) {}
  return enabled
}

module.exports = {
  REDUCED_MOTION_KEY,
  LEGACY_REDUCED_MOTION_KEY,
  readReducedMotion,
  writeReducedMotion,
}
