// 据点(POI)深链宿主。站内入口与漫游页内仍走各自的 sceneStack,本页只服务
// 地图 marker / 转发卡片 / 收藏这类「从外面直接进来」的冷启动。
//
// ★ /subpackageRoam/poi-detail/index?poiId=<cityNodeId> 一旦上线即长期契约:
//   onShareTimeline 只能自定义 query、不能自定义 path(微信官方 Page 文档),
//   朋友圈卡片的落点路径恒等于生成它的那一页 ⇒ 事后改路径 = 历史卡片全失效。
//
// 正文与到店验证逻辑只有 components/cy/scene-roam-poi-detail 一份,这里不复制。
const SHARE_PATH = '/subpackageRoam/poi-detail/index'

function shareQuery(poiId) {
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
    this.setData({ poiId, sceneParams: { poiId }, ready: true })
  },

  onShareAppMessage() {
    return { title: '这个节点等你到店打卡', path: SHARE_PATH + '?' + shareQuery(this.data.poiId) }
  },

  // 第七轮 C 第 1 项:暂不实现 onShareTimeline。节点详情读接口 GET /api/city/nodes/{id} 需要登录
  // (SecurityConfig 未放行,控制器本身按游客 memberId=0 可读),朋友圈单页模式没有登录态 ⇒ 只能看到错误。
  // 业主放行该只读接口后恢复:return { title, query: shareQuery(this.data.poiId) }(路径仍是本页,历史契约不变)。

  onAddToFavorites() {
    return { title: '节点详情', path: SHARE_PATH + '?' + shareQuery(this.data.poiId) }
  },
})
