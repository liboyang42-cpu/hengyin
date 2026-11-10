// l-pass 游戏主题库:官方开放自玩主题(服务端判定)。持有通行证(服务端 covered)才挂「¥0 可玩」。
const app = getApp()
const { isBizOk } = require('../../../utils/response-shape.js')
const pp = require('../../utils/platform-pass.js')

const FILTERS = [
  { key: 'all', label: '全部', type: null },
  { key: 'orienteering', label: '城市定向', type: 1 },
  { key: 'explore', label: '自由探索', type: 2 }
]

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    filters: FILTERS.map((f) => ({ key: f.key, label: f.label })),
    filter: 'all',
    state: 'loading',
    rows: [],
    covered: false
  },
  onLoad() { this.load() },
  onPullDownRefresh() { this.load() },
  onUnload() { this._unloaded = true },
  load() {
    const seq = (this._seq = (this._seq || 0) + 1)
    const f = FILTERS.find((x) => x.key === this.data.filter) || FILTERS[0]
    this.setData({ state: 'loading' })
    const data = { limit: 100 }
    if (f.type) data.productType = f.type
    app.sendRequest({
      url: '/api/platform-pass/library', method: 'GET', data, hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (seq !== this._seq || this._unloaded) return
        wx.stopPullDownRefresh()
        const d = isBizOk(res) && res.data
        if (!d) { this.setData({ state: 'error' }); return }
        if (d.enabled !== true) { this.setData({ state: 'off', rows: [] }); return }
        this.setData({ state: 'ready', covered: d.covered === true, rows: (Array.isArray(d.rows) ? d.rows : []).map(pp.libraryRow).filter(Boolean) })
      },
      fail: () => { if (seq === this._seq && !this._unloaded) { wx.stopPullDownRefresh(); this.setData({ state: 'error' }) } }
    })
  },
  onFilter(e) {
    const key = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.key
    if (!key || key === this.data.filter) return
    this.setData({ filter: key })
    this.load()
  },
  openTopic(e) {
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id
    if (!id || this._navigating) return
    this._navigating = true
    wx.navigateTo({ url: '/pages/topic/index/index?id=' + encodeURIComponent(id), complete: () => { this._navigating = false } })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/member/index/index' })
  }
})
