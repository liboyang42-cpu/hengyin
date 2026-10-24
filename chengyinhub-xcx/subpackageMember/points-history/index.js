// 积分明细。POST /api/points/list:后端一次返回全部明细,这里按 20 条一页展开。
const app = getApp()
const cyToast = require('../../utils/toast.js'); const pageMotion = require('../../utils/page-motion.js')
const { isBizOk } = require('../../utils/response-shape.js')
const { PAGE_SIZE, FILTERS, normalizeRows, filterRows, groupByMonth } = require('./rows.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'loading', // loading | ready | empty | error
    groups: [],
    hasMore: false,
    // BL-04 全部/获得/使用(端上对全量列表筛选) + 月份分组
    filters: FILTERS,
    filter: 'all',
    filterEmpty: false,
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
          if (keep) cyToast('刷新失败，稍后再试')
          else that.setData({ state: 'error' })
          return
        }
        that._all = all
        that._shown = Math.min(PAGE_SIZE, that._filtered().length)
        that._sync()
      },
      fail() {
        if (seq !== that._seq) return
        if (fromPull) wx.stopPullDownRefresh()
        if (keep) cyToast('刷新失败，稍后再试')
        else that.setData({ state: 'error' })
      },
    })
  },
  _filtered() { return filterRows(this._all || [], this.data.filter) },
  _sync() {
    const all = this._all || []
    const list = this._filtered()
    pageMotion.enter(this, 'pmList'); this.setData({
      groups: groupByMonth(list.slice(0, this._shown)),
      hasMore: this._shown < list.length,
      filterEmpty: !list.length,
      state: all.length ? 'ready' : 'empty',
    })
  },
  onFilter(e) {
    const key = e && e.detail && e.detail.key
    if (!key || key === this.data.filter) return
    this.setData({ filter: key })
    this._shown = Math.min(PAGE_SIZE, this._filtered().length)
    this._sync()
  },
  onReachBottom() { this.loadMore() },
  loadMore() {
    const list = this._filtered()
    if (this._shown >= list.length) return
    this._shown = Math.min(this._shown + PAGE_SIZE, list.length)
    this._sync()
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/member/index/index' })
  },
})
