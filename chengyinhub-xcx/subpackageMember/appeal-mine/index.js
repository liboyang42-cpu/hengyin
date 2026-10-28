// 我的申诉(原型 C / B,照抄「我的举报」report-mine):顶部一行账号信誉(X7,只写正常 / 受限),
// 下面计分申诉与成绩争议(C1)。两条接口都是「需后端」新增,读不到时信誉行不出、列表给失败态,不编数据。
const app = getApp()
const { isBizOk } = require('../../utils/response-shape.js')
const { retryLogin } = require('../../utils/session/login-reload.js')
const view = require('./view.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    tabs: view.TABS,
    tab: 'all',
    state: 'loading', // loading | ready | empty | error | login
    rows: [],
    reputation: null,
    repSheet: false,
  },
  onLoad() { this.load() },
  onShow() {
    this._navigating = false
    if (this._loadedOnce) this.load(true)
  },
  onPullDownRefresh() { this.load(false, true) },
  retry() { this.load() },
  login() { return retryLogin(this, app, function () { this.load() }) },
  load(quiet, fromPull) {
    const that = this
    if (!app.getUserID || !app.getUserID() || !app.getAuthorization || !app.getAuthorization()) {
      if (fromPull) wx.stopPullDownRefresh()
      this.setData({ state: 'login', rows: [], reputation: null })
      return
    }
    const seq = this._seq = (this._seq || 0) + 1
    if (!quiet) this.setData({ state: 'loading' })
    app.sendRequest({
      url: '/api/member/reputation', method: 'GET', hideLoading: true, autoErrorToast: false,
      success(res) { if (seq === that._seq) that.setData({ reputation: isBizOk(res) ? view.reputation(res.data) : null }) },
      fail() { if (seq === that._seq) that.setData({ reputation: null }) },
      successStatusAbnormal() { if (seq === that._seq) that.setData({ reputation: null }) },
    })
    const settle = function (list) {
      if (fromPull) wx.stopPullDownRefresh()
      if (seq !== that._seq) return
      const all = list ? view.normalizeList(list) : null
      if (!all) { if (!quiet || !that._loadedOnce) that.setData({ state: 'error' }); return }
      that._loadedOnce = true
      that._all = all
      that._sync()
    }
    app.sendRequest({
      url: '/api/orient/scoring/mine', method: 'GET', data: { pageSize: 50 }, hideLoading: true, autoErrorToast: false,
      success(res) { settle(isBizOk(res) && res.data ? res.data.rows : null) },
      fail() { settle(null) },
      successStatusAbnormal() { settle(null) },
    })
  },
  _sync() {
    this.setData({
      rows: view.filterRows(this._all || [], this.data.tab),
      state: (this._all || []).length ? 'ready' : 'empty',
    })
  },
  onTab(e) {
    const key = e && e.detail && e.detail.key
    if (!key || key === this.data.tab) return
    this.setData({ tab: key }, () => this._sync())
  },
  openReputation() { if (this.data.reputation) this.setData({ repSheet: true }) },
  closeReputation() { this.setData({ repSheet: false }) },
  // 点开落到那一局游玩页的计分全屏层(原型 A,X1);没有主题编号就不跳。
  openAppeal(e) {
    const ds = e && e.currentTarget && e.currentTarget.dataset || {}
    const topicId = Number(ds.topic)
    const sessionId = Number(ds.session)
    if (!(topicId > 0) || this._navigating) return
    this._navigating = true
    wx.navigateTo({ url: '/pages/play/index?topicId=' + topicId + (sessionId > 0 ? '&sessionId=' + sessionId : '') + '&layer=scoring',
      complete: () => { this._navigating = false } })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/member/index/index' })
  },
})
