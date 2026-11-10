// D28 收益明细页。2026-08-11 用户裁决「放到页面上,不要做弹窗」——
// 原来这页一进来就永久弹一个 cy-sheet,页面标题与弹层标题同屏重复。现在正文直接落在页上,
// 列表与筛选仍只有 scene 组件那一份实现(本页不复制正文)。
const merchantTheme = require('../../../../utils/merchant-theme.js')

Page({
  data: {
    merchantView: false,
    revTabs: [{ key: 'income', label: '收益明细' }, { key: 'revenue', label: '分佣' }],
    revTab: 'income',
    revState: 'hidden'
  },
  // G-10 / M-35:商家从账户收益进来带 theme=merchant,整页浅色;玩家仍是 theme-dark
  onLoad(options) {
    const merchantView = !!(options && options.theme === 'merchant')
    this.setData({ merchantView })
  },
  // §19/§27.10 个人分佣:读取与正文都在 cy-revenue-allocations;空列表/非参与人不出页签,失败照出页签写「收益暂不可用」
  onRevState(e) {
    const state = (e && e.detail && e.detail.state) || 'hidden'
    if (state === 'hidden') this.setData({ revState: 'hidden', revTab: 'income' })
    else this.setData({ revState: state })
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
