// 我的举报。GET /api/community-trust/reports/mine:后端一次返回本人全部举报,这里按 20 条一页展开。
// 举报原因文案取 GET /reports/taxonomy;读不到不挡列表,只显示兜底「举报原因」。
const app = getApp()
const cyToast = require('../../utils/toast.js')
const { isBizOk } = require('../../utils/response-shape.js')
const view = require('../report-common/view.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    tabs: view.TABS,
    tab: 'all',
    state: 'loading', // loading | ready | empty | error
    rows: [],
    hasMore: false,
  },
  onLoad() { this.load() },
  onShow() {
    this._navigating = false // 从详情/申诉返回时回读一次,阶段以服务端为准;兜底清跳转中标记
    if (this._loadedOnce) this.load(false, true)
  },
  onPullDownRefresh() { this.load(true) },
  retry() { this.load() },
  load(fromPull, quiet) {
    const that = this
    const st = { seq: (this._seq = (this._seq || 0) + 1), fromPull: !!fromPull, quiet: !!quiet,
      keep: this.data.state === 'ready' || this.data.state === 'empty', pending: 2, list: null, labels: null }
    if (!st.keep) this.setData({ state: 'loading' })
    app.sendRequest({
      url: '/api/community-trust/reports/mine', method: 'GET', hideLoading: true, silentError: true,
      success(res) { st.list = isBizOk(res) ? res.data : null; that._settle(st) },
      fail() { that._settle(st) },
      successStatusAbnormal() { that._settle(st) },
    })
    app.sendRequest({
      url: '/api/community-trust/reports/taxonomy', method: 'GET', hideLoading: true, silentError: true,
      success(res) { if (isBizOk(res)) st.labels = view.reasonLabelMap(res.data); that._settle(st) },
      fail() { that._settle(st) },
      successStatusAbnormal() { that._settle(st) },
    })
  },
  // 两路都回来后再落数据;举报列表失败 → 首屏失败态 / 已有数据时轻提示。原因文案读不到只用兜底名,不挡列表。
  _settle(st) {
    st.pending -= 1
    if (st.pending > 0) return
    if (st.fromPull) wx.stopPullDownRefresh()
    if (st.seq !== this._seq) return
    const all = st.list ? view.normalizeList(st.list, st.labels || {}) : null
    if (!all) {
      if (!st.keep) this.setData({ state: 'error' })
      else if (!st.quiet) cyToast('刷新失败，稍后再试')
      return
    }
    this._loadedOnce = true
    this._all = all
    if (!st.quiet) this._shown = view.PAGE_SIZE // 静默回读保留已展开条数
    this._sync()
  },
  _sync() {
    const filtered = view.filterRows(this._all, this.data.tab)
    const shown = Math.min(this._shown || view.PAGE_SIZE, filtered.length)
    this.setData({
      rows: filtered.slice(0, shown),
      hasMore: shown < filtered.length,
      state: (this._all || []).length ? 'ready' : 'empty',
    })
  },
  onTab(e) {
    const key = e && e.detail && e.detail.key
    if (!key || key === this.data.tab) return
    this._shown = view.PAGE_SIZE
    this.setData({ tab: key }, () => this._sync())
  },
  onReachBottom() { this.loadMore() },
  loadMore() {
    if (!this.data.hasMore) return
    this._shown = (this._shown || view.PAGE_SIZE) + view.PAGE_SIZE
    this._sync()
  },
  openDetail(e) {
    const id = view.positiveId(e && e.currentTarget && e.currentTarget.dataset.id)
    if (!id || this._navigating) return // 跳转中不响应连点,免得叠两层详情
    this._navigating = true; wx.navigateTo({ url: '/subpackageMember/report-detail/index?reportId=' + id, complete: () => { this._navigating = false } })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/member/index/index' })
  },
})
