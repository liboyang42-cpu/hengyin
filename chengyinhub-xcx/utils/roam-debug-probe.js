'use strict'

// 漫游页自动化/调试探针(C:U-126):原先 _probeClearFog / _probeFogPng / _dbg 直接挂在 Page 对象上,随正式包发布。
// 收到这里后由页面在开发版(app.isDevEnv())才挂载;读取的都是页面实例字段,不改任何业务状态。
const { stampProgress, doneShopCount } = require('./roam-poi-group.js')

function roamDebugSnapshot(page) {
  const pois = page._pois || []
  const nearby = (page.data && page.data.nearbyPois) || []
  return {
    fogMode: page._fogMode || 'none',
    fogFlushes: page._fogFlushCount || 0,
    fogCv: !!page._fogCv,
    fogErr: page._fogErr || null,
    fogPolys: ((page.data && page.data.fogPolys) || []).length,
    fogUpdates: page._fogUpdates || 0,
    reveals: (page._reveals || []).length,
    history: (page._historyReveals || []).length,
    pois: pois.length,
    poisReal: pois.filter((p) => p.nodeId).length, // /api/map/nearby 报名商家
    poisRoam: pois.filter((p) => p._roamId).length, // /api/roam/pois(未部署=0)
    poisSeen: pois.filter((p) => p.state === 'seen').length, // 雾揭开过的商家
    hasPlayer: !!page._player,
    // #22 去重自证:卡数 vs 卡上店名去重后的个数,相等即无同名重复
    nearbyCards: nearby.length,
    nearbyNames: nearby.map((m) => m.name).join('|'),
    shopsDedup: doneShopCount(pois),
    shopsRaw: pois.filter((p) => p.state === 'done' && p.cat === 'merchant').length,
    stamp: stampProgress(pois),
    // #23 自证:阈值是否真来自配置 + 结算勋章是否来自后端(而非本地造)
    shopBadgeThreshold: page._shopBadgeThreshold(),
    shopBadgeCfgLoaded: !!page._shopBadgeCfg,
    shopMedalFromServer: !!page._shopMedalFromServer,
  }
}

/** 开发版才把探针方法挂到页面实例;体验版/正式版不挂,调用方拿到 undefined。 */
function attachRoamDebugProbes(page, enabled) {
  if (!enabled) return false
  page._probeClearFog = function () {
    if (this._fogFlushTimer) { clearTimeout(this._fogFlushTimer); this._fogFlushTimer = null }
    this.setData({ fogPolys: [] })
  }
  page._probeFogPng = function () {
    if (!this._fogCv) return null
    const u = this._fogCv.toDataURL('image/png')
    return u.slice(u.indexOf(',') + 1)
  }
  page._dbg = function () { return roamDebugSnapshot(this) }
  return true
}

module.exports = { roamDebugSnapshot, attachRoamDebugProbes }
