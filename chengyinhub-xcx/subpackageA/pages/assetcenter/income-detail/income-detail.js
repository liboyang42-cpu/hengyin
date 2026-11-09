// D28 收益明细页。2026-08-11 用户裁决「放到页面上,不要做弹窗」——
// 原来这页一进来就永久弹一个 cy-sheet,页面标题与弹层标题同屏重复。现在正文直接落在页上,
// 列表与筛选仍只有 scene 组件那一份实现(本页不复制正文)。
const merchantTheme = require('../../../../utils/merchant-theme.js')
const { isBizOk } = require('../../../../utils/response-shape.js')
const { revenueView } = require('../../../utils/revenue-view.js')
const app = getApp()

Page({
  data: {
    merchantView: false,
    revTabs: [{ key: 'income', label: '收益明细' }, { key: 'revenue', label: '分佣' }],
    revTab: 'income',
    rev: { state: 'hidden', rows: [] }
  },
  // G-10 / M-35:商家从账户收益进来带 theme=merchant,整页浅色;玩家仍是 theme-dark
  onLoad(options) {
    const merchantView = !!(options && options.theme === 'merchant')
    this.setData({ merchantView })
    if (!merchantView) this.loadRevenue()
  },
  // §19/§27.10 个人分佣:空列表不出入口;读取失败出入口但只写「收益暂不可用」,不填 0
  loadRevenue() {
    const seq = (this._revSeq = (this._revSeq || 0) + 1)
    app.sendRequest({
      url: '/api/marketing/revenue/author/mine', method: 'GET', hideLoading: true, silentError: true, autoErrorToast: false,
      success: (res) => {
        if (seq !== this._revSeq) return
        if (res && res.reasonCode === 'NOT_PARTICIPANT') { this.setData({ rev: { state: 'hidden', rows: [] } }); return }
        const v = revenueView(isBizOk(res) ? res.data : null)
        this.setData({ rev: v.state === 'empty' ? { state: 'hidden', rows: [] } : v })
      },
      fail: () => { if (seq === this._revSeq) this.setData({ rev: { state: 'error', rows: [] } }) }
    })
  },
  onRevTab(e) {
    const key = e && e.detail && e.detail.key
    if (key && key !== this.data.revTab) this.setData({ revTab: key })
  },
  // 玩家视角正文根是 theme-dark(D33 玩家资金域),原生壳必须同档;否则浅色状态条压深色内容
  onShow() { if (this.data.merchantView) merchantTheme.merchantPageShow(); else merchantTheme.merchantPageRestore() },
  onHide() { merchantTheme.merchantPageRestore() },
  onUnload() { merchantTheme.merchantPageRestore() },
  goBack() {
    wx.navigateBack({
      delta: 1,
      fail() { wx.redirectTo({ url: '/subpackageA/pages/assetcenter/earnings/index' }) },
    })
  },
  onReachBottom() {
    const content = this.selectComponent('#incomeDetail')
    if (content && typeof content.loadMore === 'function') content.loadMore()
  },
})
