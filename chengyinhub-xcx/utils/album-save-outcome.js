'use strict'

/**
 * 存相册结果建模成值:saved / cancelled / denied / failed。
 * 布尔结果分不出「用户自己取消」和「真失败」,调用方会把取消当失败提示(或反之)。
 * 判定顺序与原有 errMsg 分支一致:先认权限拒绝,再认取消,其余都是失败。
 */
const ALBUM_SAVE = Object.freeze({ SAVED: 'saved', CANCELLED: 'cancelled', DENIED: 'denied', FAILED: 'failed' })

function classifyAlbumSaveError(error) {
  const msg = String((error && error.errMsg) || '')
  if (/auth|deny/i.test(msg)) return ALBUM_SAVE.DENIED
  if (/cancel/i.test(msg)) return ALBUM_SAVE.CANCELLED
  return ALBUM_SAVE.FAILED
}

/** 兼容旧的布尔约定:true=saved,其它未知值一律按失败处理(不得把未知当成功)。 */
function normalizeAlbumOutcome(value) {
  if (value === true) return ALBUM_SAVE.SAVED
  const known = Object.keys(ALBUM_SAVE).some((k) => ALBUM_SAVE[k] === value)
  return known ? value : ALBUM_SAVE.FAILED
}

module.exports = { ALBUM_SAVE, classifyAlbumSaveError, normalizeAlbumOutcome }
