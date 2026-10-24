'use strict'

/**
 * 漫游分享导出的 loading 收口:cyLoading 按层计数,每次 show 必须配一次 hide。
 * 作用域失效(换号/离开本次漫游)走提前返回时也要收;_cancelShareWork 已替本轮收过
 * (它会推进 _shareEpoch)时不重复 hide。
 */
function loadingRelease(page, loading, title) {
  page._shareLoading = true
  loading.show(title)
  const shownEpoch = page._shareEpoch || 0
  let released = false
  return function release() {
    if (released) return
    released = true
    if ((page._shareEpoch || 0) !== shownEpoch) return
    page._shareLoading = false
    loading.hide()
  }
}

module.exports = { loadingRelease }
