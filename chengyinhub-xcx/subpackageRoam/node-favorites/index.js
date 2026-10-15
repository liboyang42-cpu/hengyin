// 我收藏的据点。GET /api/city/nodes/favorites(只读,需登录;后端只回已上线且内容已审的商家据点)。
// 卡片与四态照搬官方线路列表(subpackageRoam/official-routes)。点开进据点深链宿主 poi-detail。
const app = getApp()
const { isBizOk, bizFailureMessage } = require('../../utils/response-shape.js')
const { normalizeFavoriteNodes } = require('../utils/node-favorites.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'loading', // loading | ready | empty | error
    errorSub: '网络请求失败，重试会重新拉取一次',
    nodes: [],
  },
  onLoad() { this.load() },
  onShow() {
    // 从详情里取消收藏后返回,列表要回读,不本地猜。
    if (this._loadedOnce) this.load({ quiet: true })
  },
  load(opts) {
    const that = this
    const quiet = !!(opts && opts.quiet)
    const seq = (this._seq = (this._seq || 0) + 1)
    if (!quiet) this.setData({ state: 'loading' })
    app.sendRequest({
      url: '/api/city/nodes/favorites', method: 'GET', hideLoading: true, silentError: true,
      success(res) {
        if (seq !== that._seq) return
        that._loadedOnce = true
        const list = isBizOk(res) ? normalizeFavoriteNodes(res.data) : null
        if (!list) {
          if (quiet && that.data.state === 'ready') return
          that.setData({ state: 'error', errorSub: errorSubOf(res) })
          return
        }
        const nodes = list.map((n) => Object.assign({}, n, { cover: n.coverImg ? app.getImgUrl(n.coverImg) : '' }))
        that.setData({ nodes, state: nodes.length ? 'ready' : 'empty' })
      },
      fail() {
        if (seq !== that._seq) return
        if (quiet && that.data.state === 'ready') return
        that.setData({ state: 'error', errorSub: '网络请求失败，重试会重新拉取一次' })
      },
    })
  },
  openNode(e) {
    const id = e.currentTarget.dataset.id
    if (id) wx.navigateTo({ url: `/subpackageRoam/poi-detail/index?poiId=${encodeURIComponent(id)}` })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/roam/index' })
  },
})

function errorSubOf(res) {
  return bizFailureMessage(res, '收藏数据暂时不可用，重试会重新拉取一次')
}
