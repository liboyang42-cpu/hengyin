// BL-184 我的纠错。GET /api/community-trust/contributions/mine:后端一次返回本人全部纠错,这里按 20 条一页展开。
// 结构照「我的举报」subpackageMember/report-mine;入口在商家纠错半屏的成功态。
const app = getApp()
const cyToast = require('../../utils/toast.js')
const { isBizOk } = require('../../utils/response-shape.js')
const view = require('../correction-common/view.js')

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
    this._navigating = false // 从详情/补证返回时回读一次,状态以服务端为准;兜底清跳转中标记
    if (this._loadedOnce) this.load(false, true)
  },
  onPullDownRefresh() { this.load(true) },
  retry() { this.load() },
  load(fromPull, quiet) {
    const that = this
    const st = { seq: (this._seq = (this._seq || 0) + 1), fromPull: !!fromPull, quiet: !!quiet,
      keep: this.data.state === 'ready' || this.data.state === 'empty' }
    if (!st.keep) this.setData({ state: 'loading' })
    app.sendRequest({
      url: '/api/community-trust/contributions/mine', method: 'GET', hideLoading: true, silentError: true,
      success(res) { that._settle(st, isBizOk(res) ? res.data : null) },
      fail() { that._settle(st, null) },
      successStatusAbnormal() { that._settle(st, null) },
    })
  },
  // 列表失败 → 首屏失败态 / 已有数据时轻提示
  _settle(st, list) {
    if (st.fromPull) wx.stopPullDownRefresh()
    if (st.seq !== this._seq) return
    const all = list ? view.normalizeList(list) : null
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
    this._navigating = true; wx.navigateTo({ url: '/subpackageMember/correction-detail/index?contributionId=' + id, complete: () => { this._navigating = false } })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/member/index/index' })
  },
})
