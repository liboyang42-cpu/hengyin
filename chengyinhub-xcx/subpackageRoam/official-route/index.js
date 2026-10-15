// 官方路线详情。GET /api/preset/route/info?id=(含站点)。
// 「按这条路线开始」:把带坐标的站点写进一次性交接(utils/roam-follow-route.js),再 switchTab 回漫游;
// 漫游页 onShow 消费一次,照常开局并在地图上叠路线。纯前端,不写服务端、不影响结算。
const app = getApp()
const { isBizOk } = require('../../utils/response-shape.js')
const { normalizeRouteDetail } = require('../utils/official-route.js')
const follow = require('../../utils/roam-follow-route.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'loading', // loading | ready | notfound | error
    route: null,
    canFollow: false,
    followHint: '', // 不能跟随时的降级说明;能跟随但有站点缺坐标时的提示
    followError: '',
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
        that._plan = follow.buildFollowPlan(route)
        const canFollow = !!that._plan.supported
        const followHint = canFollow
          ? (that._plan.skipped ? `${that._plan.skipped} 个站点未标注位置，跟随时不会画在地图上` : '')
          : that._plan.reason
        that.setData({ route, state: 'ready', canFollow, followHint, followError: '' })
      },
      fail() { if (seq === that._seq) that.setData({ state: 'error' }) },
    })
  },
  openNode(e) {
    const node = this.data.route && this.data.route.nodes[Number(e.currentTarget.dataset.index)]
    if (!node || node.lat == null || node.lng == null) return
    wx.openLocation({ latitude: node.lat, longitude: node.lng, name: node.name, address: node.address || '' })
  },
  startFollow() {
    if (!this._plan || !this._plan.supported) return
    if (!follow.writeHandoff(wx, this._plan, follow.currentOwner(app))) {
      this.setData({ followError: '线路没能交给漫游页，请再点一次' })
      return
    }
    this.setData({ followError: '' })
    wx.switchTab({ url: '/pages/roam/index' })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/roam/index' })
  },
})
