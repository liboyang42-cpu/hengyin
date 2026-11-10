// §17.1 / §17.2 / P6 通行证列表。可购买只来自服务端在售目录;订单、礼品只读本人记录。
const app = getApp()
const cyToast = require('../../../utils/toast.js')
const { isBizOk } = require('../../../utils/response-shape.js')
const mv = require('../../utils/market-view.js')

const TABS = [
  { key: 'shop', label: '可购买' },
  { key: 'orders', label: '我的订单' },
  { key: 'gifts', label: '礼品' }
]

function get(url, data) {
  return new Promise((resolve) => {
    app.sendRequest({
      url, method: 'GET', data: data || {}, hideLoading: true, autoErrorToast: false,
      success: (res) => resolve(isBizOk(res) && Array.isArray(res.data) ? res.data : null),
      fail: () => resolve(null)
    })
  })
}

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    tabs: TABS,
    tab: 'shop',
    state: 'loading', // loading | ready | error
    products: [],
    orders: [],
    giftsBought: [],
    giftsReceived: [],
    workshopReason: ''
  },
  onLoad(options) {
    const tab = options && TABS.some((t) => t.key === options.tab) ? options.tab : 'shop'
    this.setData({ tab })
    this.load()
    this.loadWorkshop()
  },
  // U07-03:付费模板只看服务端能力;微信通道不可用时写原因,可用时本端仍未接安装流程,不出入口
  loadWorkshop() {
    app.sendRequest({
      url: '/api/workshop/capabilities', method: 'GET', hideLoading: true, autoErrorToast: false,
      success: (res) => {
        const cap = isBizOk(res) && res.data && res.data.wechatApp
        this.setData({ workshopReason: cap && cap.available === false ? mv.workshopReasonText(cap.reason) : '' })
      }
    })
  },
  onShow() { if (this._loaded) this.load(true) },
  onPullDownRefresh() { this.load(true) },
  load(quiet) {
    const seq = (this._seq = (this._seq || 0) + 1)
    if (!quiet) this.setData({ state: 'loading' })
    Promise.all([
      get('/api/marketing/product/list', { limit: 20 }),
      get('/api/marketing/product/orders', { limit: 20 }),
      get('/api/marketing/product/gifts/bought', { limit: 20 }),
      get('/api/marketing/product/gifts/received', { limit: 20 })
    ]).then(([products, orders, bought, received]) => {
      if (seq !== this._seq) return
      wx.stopPullDownRefresh()
      // 在售目录读取失败 ⇒ 整页错误,不显示「暂无在售」冒充空
      if (!products || !orders || !bought || !received) {
        if (quiet && this._loaded) { cyToast('刷新失败，稍后再试'); return }
        this.setData({ state: 'error' })
        return
      }
      this._loaded = true
      const cards = products.map(mv.productCard).filter(Boolean)
      const nameById = {}
      cards.forEach((c) => { nameById[c.id] = c.name })
      this.setData({
        state: 'ready',
        products: cards,
        orders: orders.map((o) => mv.orderRow(o, nameById)).filter(Boolean),
        giftsBought: bought.map((g) => mv.giftRow(g, 'bought')).filter(Boolean),
        giftsReceived: received.map((g) => mv.giftRow(g, 'received')).filter(Boolean)
      })
    })
  },
  onTab(e) {
    const key = e && e.detail && e.detail.key
    if (key && key !== this.data.tab) this.setData({ tab: key })
  },
  openProduct(e) {
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id
    if (!id || this._navigating) return
    this._navigating = true
    wx.navigateTo({ url: '/subpackageMember/market/pass/index?id=' + encodeURIComponent(id), complete: () => { this._navigating = false } })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/member/index/index' })
  }
})
