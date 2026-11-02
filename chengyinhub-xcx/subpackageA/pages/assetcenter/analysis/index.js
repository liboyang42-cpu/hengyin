// 账户收益 · 分析:近 6 个月收入 / 提现的柱图 + 本月收入来源。只读余额流水,不新增接口。
const app = getApp()
const earningsView = require('../../../../utils/earnings-view.js')

function asWithdraw(rows) {
  // 提现 = eventType 3 的支出;换成「收入」形状复用同一套按月聚合
  return (rows || []).filter((r) => r && Number(r.eventType) === 3 && Number(r.changeType) === 2)
    .map((r) => Object.assign({}, r, { changeType: 1 }))
}

Page({
  data: {
    statusBarHeight: (app.globalData || {}).statusBarHeight || 20,
    navBarHeight: (app.globalData || {}).navBarHeight || 44,
    merchantView: false,
    mode: 'income',
    state: 'loading',
    series: [],
    current: { label: '', totalText: '0.00' },
    sources: [],
  },
  onLoad(options) {
    this.setData({ merchantView: !!(options && options.theme === 'merchant') })
    this.load()
  },
  onUnload() { this._token = (this._token || 0) + 1 },
  onShow() {
    if (this._owner !== undefined && this._owner !== String(app.getUserID && app.getUserID() || '')) this.load()
  },
  goBack() { wx.navigateBack({ fail: () => wx.redirectTo({ url: '/subpackageA/pages/assetcenter/earnings/index' }) }) },
  switchMode(e) {
    const mode = e.currentTarget.dataset.mode
    if (mode === this.data.mode) return
    this.setData({ mode })
    this._render()
  },
  load() {
    const token = this._token = (this._token || 0) + 1
    const owner = this._owner = String(app.getUserID && app.getUserID() || '')
    const current = () => token === this._token && owner === String(app.getUserID && app.getUserID() || '')
    this._rows = null
    this.setData({ state: 'loading', series: [], sources: [], current: { label: '', totalText: '0.00' } })
    const rows = [], ids = new Set()
    let total = null
    const fail = () => { if (current()) this.setData({ state: 'error' }) }
    const readPage = (pageNum) => app.sendRequest({
      url: '/api/user/balance/list', method: 'POST', data: { pageNum, pageSize: 200 }, hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (!current()) return
        const data = res && res.code == '200' ? res.data : null
        const page = data && data.rows
        const count = data && Number(data.total)
        if (!Array.isArray(page) || data.total == null || !Number.isSafeInteger(count) || count < 0
            || total !== null && total !== count || !page.length && rows.length < count) { fail(); return }
        total = count
        for (const row of page) {
          const id = row && String(row.id || '')
          if (!id || ids.has(id)) { fail(); return }
          ids.add(id); rows.push(row)
        }
        if (rows.length > total) { fail(); return }
        if (rows.length < total) { readPage(pageNum + 1); return }
        this._rows = rows
        this.setData({ state: 'ready' })
        this._render()
      },
      fail,
      successStatusAbnormal: fail,
    })
    readPage(1)
  },
  _render() {
    if (this.data.state !== 'ready' || !Array.isArray(this._rows)) return
    const now = new Date()
    const rows = this.data.mode === 'income' ? this._rows : asWithdraw(this._rows)
    const series = earningsView.monthlySeries(rows, now, 6)
    this.setData({
      state: 'ready',
      series,
      current: series[series.length - 1] || { label: '', totalText: '0.00' },
      sources: this.data.mode === 'income' ? earningsView.sourceBreakdown(this._rows, now) : [],
    })
  },
})
