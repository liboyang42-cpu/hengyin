// 官方路线列表。GET /api/preset/route/list(只读,后端只回已上架路线)。
const app = getApp()
const { isBizOk } = require('../../utils/response-shape.js')
const { normalizeRouteList } = require('../utils/official-route.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'loading', // loading | ready | empty | error
    routes: [],
  },
  onLoad() { this.load() },
  load() {
    const that = this
    const seq = (this._seq = (this._seq || 0) + 1)
    this.setData({ state: 'loading' })
    app.sendRequest({
      url: '/api/preset/route/list', method: 'GET', hideLoading: true, silentError: true,
      success(res) {
        if (seq !== that._seq) return
        const list = isBizOk(res) ? normalizeRouteList(res.data) : null
        if (!list) { that.setData({ state: 'error' }); return }
        const routes = list.map((r) => Object.assign({}, r, { cover: r.previewImg ? app.getImgUrl(r.previewImg) : '' }))
        that.setData({ routes, state: routes.length ? 'ready' : 'empty' })
      },
      fail() { if (seq === that._seq) that.setData({ state: 'error' }) },
    })
  },
  openRoute(e) {
    const id = e.currentTarget.dataset.id
    if (id) wx.navigateTo({ url: `/subpackageRoam/official-route/index?id=${encodeURIComponent(id)}` })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/roam/index' })
  },
})
