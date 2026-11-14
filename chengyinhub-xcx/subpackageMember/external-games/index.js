// 外部游戏目录(只读)。数据只来自 GET /api/external-game/catalog(匿名可读),游标翻页。
const app = getApp()
const cyToast = require('../../utils/toast.js')
const { isBizOk } = require('../../utils/response-shape.js')
const view = require('../utils/external-game-view.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'loading', // loading | ready | empty | error
    rows: [],
    hasMore: false,
    loadingMore: false,
  },
  onLoad() { this.load() },
  onPullDownRefresh() { this.load(true) },
  retry() { this.load() },
  load(fromPull) {
    const seq = (this._seq = (this._seq || 0) + 1)
    const keep = this.data.state === 'ready' || this.data.state === 'empty'
    if (!keep) this.setData({ state: 'loading' })
    this._fetch(null, (page) => {
      if (fromPull) wx.stopPullDownRefresh()
      if (seq !== this._seq) return
      if (!page) {
        if (!keep) this.setData({ state: 'error' })
        else cyToast('刷新失败，稍后再试')
        return
      }
      this._cursor = page.nextCursor
      this.setData({ rows: page.rows, hasMore: page.hasMore, state: page.rows.length ? 'ready' : 'empty' })
    })
  },
  onReachBottom() { this.loadMore() },
  loadMore() {
    if (!this.data.hasMore || this.data.loadingMore || !this._cursor) return
    const seq = this._seq
    this.setData({ loadingMore: true })
    this._fetch(this._cursor, (page) => {
      if (seq !== this._seq) return
      if (!page) { this.setData({ loadingMore: false }); return }
      this._cursor = page.nextCursor
      this.setData({ rows: view.appendRows(this.data.rows, page.rows), hasMore: page.hasMore, loadingMore: false })
    })
  },
  _fetch(cursor, done) {
    const that = this
    const data = { limit: view.PAGE_SIZE }
    if (cursor) data.cursor = cursor
    app.sendRequest({
      url: '/api/external-game/catalog', method: 'GET', data, hideLoading: true, silentError: true,
      success(res) {
        const page = isBizOk(res) ? view.normalizePage(res.data) : null
        if (page) done(page)
        else that._fetchFailed(cursor, done)
      },
      fail() { that._fetchFailed(cursor, done) },
      successStatusAbnormal() { that._fetchFailed(cursor, done) },
    })
  },
  openDetail(e) {
    const gameId = e && e.currentTarget && e.currentTarget.dataset.id
    if (!view.validGameId(gameId) || this._navigating) return
    this._navigating = true
    wx.navigateTo({ url: '/subpackageMember/external-game-detail/index?gameId=' + encodeURIComponent(gameId), complete: () => { this._navigating = false } })
  },
  // 读失败:翻页时就地轻提示;首屏/刷新由调用方切到失败态或提示。
  _fetchFailed(cursor, done) {
    if (cursor) cyToast('加载失败，稍后再试')
    done(null)
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/member/index/index' })
  },
})
