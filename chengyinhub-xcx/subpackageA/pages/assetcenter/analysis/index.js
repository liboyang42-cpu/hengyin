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
  goBack() { wx.navigateBack({ fail: () => wx.redirectTo({ url: '/subpackageA/pages/assetcenter/earnings/index' }) }) },
  switchMode(e) {
    const mode = e.currentTarget.dataset.mode
    if (mode === this.data.mode) return
    this.setData({ mode })
    this._render()
  },
  load() {
    const token = this._token = (this._token || 0) + 1
    this.setData({ state: 'loading' })
    app.sendRequest({
      url: '/api/user/balance/list', method: 'POST', data: { pageNum: 1, pageSize: 200 }, hideLoading: true, silentError: true,
      success: (res) => {
        if (token !== this._token) return
        const data = res && res.code == '200' ? res.data : null
        const rows = data && (data.rows || data.list || data)
        if (!Array.isArray(rows)) { this.setData({ state: 'error' }); return }
        this._rows = rows
        this._render()
      },
      fail: () => { if (token === this._token) this.setData({ state: 'error' }) },
      successStatusAbnormal: () => { if (token === this._token) this.setData({ state: 'error' }) },
    })
  },
  _render() {
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
