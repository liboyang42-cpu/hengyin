// 积分明细。POST /api/points/list:后端一次返回全部明细,这里按 20 条一页展开。
const app = getApp()
const { isBizOk } = require('../../utils/response-shape.js')
const { PAGE_SIZE, normalizeRows } = require('./rows.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'loading', // loading | ready | empty | error
    rows: [],
    hasMore: false,
    refreshing: false,
  },
  onLoad() { this.load() },
  onPullDownRefresh() { this.load(true) },
  load(fromPull) {
    const that = this
    const seq = (this._seq = (this._seq || 0) + 1)
    const keep = this.data.state === 'ready'
    if (!keep) this.setData({ state: 'loading' })
    app.sendRequest({
      url: '/api/points/list', method: 'POST', data: {}, hideLoading: true, silentError: true,
      success(res) {
        if (seq !== that._seq) return
        if (fromPull) wx.stopPullDownRefresh()
        const all = isBizOk(res) ? normalizeRows(res.data) : null
        if (!all) {
          // 已经有数据时下拉刷新失败:保留旧列表,只提示
          if (keep) wx.showToast({ title: '刷新失败，稍后再试', icon: 'none' })
          else that.setData({ state: 'error' })
          return
        }
        that._all = all
        that._shown = Math.min(PAGE_SIZE, all.length)
        that._sync()
      },
      fail() {
        if (seq !== that._seq) return
        if (fromPull) wx.stopPullDownRefresh()
        if (keep) wx.showToast({ title: '刷新失败，稍后再试', icon: 'none' })
        else that.setData({ state: 'error' })
      },
    })
  },
  _sync() {
    const all = this._all || []
    this.setData({
      rows: all.slice(0, this._shown),
      hasMore: this._shown < all.length,
      state: all.length ? 'ready' : 'empty',
    })
  },
  onReachBottom() { this.loadMore() },
  loadMore() {
    const all = this._all || []
    if (this._shown >= all.length) return
    this._shown = Math.min(this._shown + PAGE_SIZE, all.length)
    this._sync()
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/member/index/index' })
  },
})
