// 据点(POI)深链宿主。站内入口与漫游页内仍走各自的 sceneStack,本页只服务
// 地图 marker / 转发卡片 / 收藏这类「从外面直接进来」的冷启动。
//
// ★ /subpackageRoam/poi-detail/index?poiId=<cityNodeId> 一旦上线即长期契约:
//   onShareTimeline 只能自定义 query、不能自定义 path(微信官方 Page 文档),
//   朋友圈卡片的落点路径恒等于生成它的那一页 ⇒ 事后改路径 = 历史卡片全失效。
//
// 正文与到店验证逻辑只有 components/cy/scene-roam-poi-detail 一份,这里不复制。
const SHARE_PATH = '/subpackageRoam/poi-detail/index'
const { ROAM_SHARE_COVER } = require('../../utils/roam-menu-share.js')
const FALLBACK_TITLE = '这个节点等你到店打卡'

function shareQuery(poiId, merchantId) {
  // 第九轮 m1:地图地点卡分享只有商家(没有据点)时按 merchantId 落地,正文组件本来就支持只给 merchantId
  if (!poiId && merchantId) return 'merchantId=' + encodeURIComponent(merchantId)
  return 'poiId=' + encodeURIComponent(poiId || '')
}

Page({
  data: {
    ready: false,
    poiId: '',
    sceneParams: {},
  },

  onLoad(options) {
    const poiId = (options && options.poiId) || ''
    const merchantId = poiId ? '' : ((options && /^\d+$/.test(String(options.merchantId || ''))) ? String(options.merchantId) : '')
    this._merchantId = merchantId
    this.setData({ poiId, sceneParams: merchantId ? { poiId, merchantId } : { poiId }, ready: true })
  },

  // 第七轮 C 第 3 项:卡片用店名与店铺封面。本页不取数,只读正文场景组件已经加载好的同一据点数据;
  // 没加载到就用泛化标题 + 仓内不含位置的固定插画,不让微信截屏。
  _shareSnapshot() {
    let d = null
    try {
      const host = this.selectComponent && this.selectComponent('#roamDeepLink')
      const scene = host && host.selectComponent && host.selectComponent('#roamPoiDetail')
      d = scene && scene.data
    } catch (e) { d = null }
    if (!d || String(d.poiId || '') !== String(this.data.poiId || '')) return { title: FALLBACK_TITLE, imageUrl: ROAM_SHARE_COVER }
    const merchant = d.merchant || {}
    const node = d.node || {}
    const gallery = Array.isArray(d.gallery) ? d.gallery : []
    return {
      title: merchant.displayName || node.name || FALLBACK_TITLE,
      imageUrl: merchant.coverImage || gallery[0] || ROAM_SHARE_COVER,
    }
  },

  onShareAppMessage() {
    const snap = this._shareSnapshot()
    return { title: snap.title, path: SHARE_PATH + '?' + shareQuery(this.data.poiId, this._merchantId), imageUrl: snap.imageUrl }
  },

  // 第九轮 m4(owner 2026-11-04 放行单个据点 GET):恢复朋友圈分享。朋友圈只能自定义 query,路径恒为本页(历史契约不变);
  // 单页模式没有登录态,读接口按游客投影只回公开字段。
  onShareTimeline() {
    const snap = this._shareSnapshot()
    return { title: snap.title, query: shareQuery(this.data.poiId, this._merchantId), imageUrl: snap.imageUrl }
  },

  onAddToFavorites() {
    return { title: '节点详情', path: SHARE_PATH + '?' + shareQuery(this.data.poiId, this._merchantId) }
  },
})
