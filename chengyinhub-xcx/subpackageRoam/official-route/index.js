// 官方路线详情。GET /api/preset/route/info?id=(含站点)。
// 「按这条路线开始」暂未接:漫游起点页是自由漫游,没有「跟随路线」的入参/状态,
// 接入需要改 pages/roam 的开局流程,不属于只读接口接线,见 PR 说明。
const app = getApp()
const { isBizOk } = require('../../utils/response-shape.js')
const { normalizeRouteDetail } = require('../utils/official-route.js')
function openAddress() { return require('../../utils/open-address.js') }
const { ROAM_SHARE_COVER } = require('../../utils/roam-menu-share.js')
const SHARE_PATH = '/subpackageRoam/official-route/index'

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'loading', // loading | ready | notfound | error
    route: null,
  },
  onLoad(options) {
    const id = String((options && options.id) || '')
    this._id = /^\d{1,18}$/.test(id) ? id : ''
    if (!this._id) { this.setData({ state: 'notfound' }); return }
    this.load()
  },
  load() {
    const that = this
    const seq = (this._seq = (this._seq || 0) + 1)
    this.setData({ state: 'loading' })
    app.sendRequest({
      url: '/api/preset/route/info', method: 'GET', hideLoading: true, silentError: true,
      data: { id: this._id },
      success(res) {
        if (seq !== that._seq) return
        if (!isBizOk(res)) {
          // 后端对「不存在/已下架」回业务失败(HTTP 200 + code≠200);网络类失败走 fail。
          that.setData({ state: 'notfound' })
          return
        }
        const route = normalizeRouteDetail(res.data)
        if (!route) { that.setData({ state: 'error' }); return }
        route.cover = route.previewImg ? app.getImgUrl(route.previewImg) : ''
        route.nodes = route.nodes.map((n) => Object.assign({}, n, { img: n.imgUrl ? app.getImgUrl(n.imgUrl) : '' }))
        that.setData({ route, state: 'ready' })
      },
      fail() { if (seq === that._seq) that.setData({ state: 'error' }) },
    })
  },
  openNode(e) {
    const node = this.data.route && this.data.route.nodes[Number(e.currentTarget.dataset.index)]
    if (!node || node.lat == null || node.lng == null) return
    openAddress().openAddress({ lat: node.lat, lng: node.lng, name: node.name, address: node.address || '' })
  },
  // 第九轮 m4:好友与朋友圈分享(owner 2026-11-04 放行路线详情 GET,游客与朋友圈单页模式都能读到已上架路线)。
  _shareCard() {
    const route = this.data.state === 'ready' ? this.data.route : null
    return { title: (route && route.name) || '城瘾官方路线', imageUrl: (route && route.cover) || ROAM_SHARE_COVER }
  },
  onShareAppMessage() {
    const card = this._shareCard()
    return { title: card.title, path: SHARE_PATH + '?id=' + encodeURIComponent(this._id || ''), imageUrl: card.imageUrl }
  },
  onShareTimeline() {
    const card = this._shareCard()
    return { title: card.title, query: 'id=' + encodeURIComponent(this._id || ''), imageUrl: card.imageUrl }
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/roam/index' })
  },
})
