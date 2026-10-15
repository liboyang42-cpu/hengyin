// 官方路线详情。GET /api/preset/route/info?id=(含站点)。
// 「按这条路线开始」暂未接:漫游起点页是自由漫游,没有「跟随路线」的入参/状态,
// 接入需要改 pages/roam 的开局流程,不属于只读接口接线,见 PR 说明。
const app = getApp()
const { isBizOk } = require('../../utils/response-shape.js')
const { normalizeRouteDetail } = require('../utils/official-route.js')

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
    wx.openLocation({ latitude: node.lat, longitude: node.lng, name: node.name, address: node.address || '' })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/roam/index' })
  },
})
