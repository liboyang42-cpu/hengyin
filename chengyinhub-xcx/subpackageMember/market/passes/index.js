// §17.1 / §17.2 / P6 通行证列表。可购买只来自服务端在售目录;订单、礼品只读本人记录。
const app = getApp()
const cyToast = require('../../../utils/toast.js')
const { isBizOk } = require('../../../utils/response-shape.js')
const mv = require('../../utils/market-view.js')
const pp = require('../../utils/platform-pass.js')
const roleGuard = require('../../../utils/roleGuard.js')
const login = require('../../../utils/auth/require-login.js')
const session = require('../../../utils/session/login-reload.js')

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
    passOrders: [], // 平台通行证订单(/api/platform-pass/orders);读不到不影响其他订单
    giftsBought: [],
    giftsReceived: [],
    member: null, // l-pass 通行证入口;服务端未开放为 null,不出
    pointBalance: null // r2-store:/api/user/info.point;读不到为 null,整卡不出(不写 0)
  },
  onLoad(options) {
    const tab = options && TABS.some((t) => t.key === options.tab) ? options.tab : 'shop'
    this.setData({ tab: login.hasSession(app) ? tab : 'shop' })
    if (tab !== 'shop' && !login.hasSession(app)) this.onTab({ detail: { key: tab } })
    this.load()
    this.loadPoints()
    this.loadMember()
  },
  // l-pass:通行证入口只看服务端 enabled;有效期 / 可玩主题数只写服务端给的值
  loadMember() {
    const owner = session.identity(app)
    // l-icons:商家没有通行证(业主 2026-11-11),商家账号不出通行证卡、不出主题库行
    let merchant = false
    try { merchant = !!roleGuard.isMerchantView() } catch (e) { merchant = false }
    if (merchant) { this.setData({ member: null }); return }
    app.sendRequest({
      url: '/api/platform-pass', method: 'GET', hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (this._unloaded || owner !== session.identity(app)) return
        const d = isBizOk(res) && res.data
        if (!d || d.enabled !== true) { this.setData({ member: null }); return }
        const v = pp.passView(d, '', pp.platformOf(wx))
        // x-fix4 L2-2:没有有效期时把在售方案价格摆在入口上(支付通道不可用也照样列出,不当「暂无在售」)
        const planSide = (v.plans || []).map((c) => c.price).filter(Boolean).join(' · ')
        const side = v.active && v.untilText ? '有效期至 ' + v.untilText : (planSide || (v.count !== null ? v.count + ' 个主题全部可玩' : ''))
        this.setData({ member: { side } })
      }
    })
  },
  goMembership() {
    if (this._navigating) return
    this._navigating = true
    // l-icons:先进钥匙开场(第一次完整版,之后待机循环),「开始」再到通行证页
    wx.navigateTo({ url: '/subpackagePass/reveal/index', complete: () => { this._navigating = false } })
  },
  // r2-store 积分余额卡:只读服务端余额;积分只能挣、不卖,这里不出充值包
  loadPoints() {
    if (!login.hasSession(app)) { this.setData({ pointBalance: null }); return }
    const owner = session.identity(app)
    app.sendRequest({
      url: '/api/user/info', method: 'POST', hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (owner !== session.identity(app)) return
        const p = isBizOk(res) && res.data ? res.data.point : null
        this.setData({ pointBalance: p !== null && p !== undefined && p !== '' && Number.isFinite(Number(p)) ? Number(p) : null })
      }
    })
  },
  goPoints() {
    if (this._navigating) return
    this._navigating = true
    wx.navigateTo({ url: '/subpackageMember/points-history/index', complete: () => { this._navigating = false } })
  },
  onHide() { this._hidden = true; this._loginContinuationEpoch = (this._loginContinuationEpoch || 0) + 1 },
  onUnload() { this._unloaded = true; this._seq = (this._seq || 0) + 1 },
  onShow() { this._hidden = false; if (this._loaded) { this.load(true); this.loadPoints(); this.loadMember() } },
  onPullDownRefresh() { this.load(true) },
  load(quiet) {
    const owner = session.identity(app), signedIn = login.hasSession(app)
    if (owner !== this._readOwner) {
      this._loaded = false
      this.setData({ orders: [], passOrders: [], giftsBought: [], giftsReceived: [], pointBalance: null, member: null, tab: signedIn ? this.data.tab : 'shop' })
    }
    this._readOwner = owner
    const seq = (this._seq = (this._seq || 0) + 1)
    if (!quiet) this.setData({ state: 'loading' })
    Promise.all([
      get('/api/marketing/product/list', { limit: 20 }),
      signedIn ? get('/api/marketing/product/orders', { limit: 20 }) : Promise.resolve([]),
      signedIn ? get('/api/marketing/product/gifts/bought', { limit: 20 }) : Promise.resolve([]),
      signedIn ? get('/api/marketing/product/gifts/received', { limit: 20 }) : Promise.resolve([]),
      signedIn ? get('/api/platform-pass/orders', { limit: 20 }) : Promise.resolve([])
    ]).then(([products, orders, bought, received, passOrders]) => {
      if (seq !== this._seq || owner !== session.identity(app)) return
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
        passOrders: (passOrders || []).map(pp.orderRow).filter(Boolean),
        orders: orders.map((o) => mv.orderRow(o, nameById)).filter(Boolean),
        giftsBought: bought.map((g) => mv.giftRow(g, 'bought')).filter(Boolean),
        giftsReceived: received.map((g) => mv.giftRow(g, 'received')).filter(Boolean)
      })
    })
  },
  onTab(e) {
    const key = e && e.detail && e.detail.key
    this._privateTabIntent = key
    if (key !== 'shop' && app.ensureLoginFor && !app.ensureLoginFor('登录后查看自己的订单和礼品',
      () => this.onTab(e), { owner: this, target: () => [this._privateTabIntent] })) return
    if (key && key !== this.data.tab) this.setData({ tab: key })
    if (this._readOwner !== session.identity(app)) this.load()
  },
  openProduct(e) {
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id
    if (!id || this._navigating) return
    this._navigating = true
    wx.navigateTo({ url: '/subpackageMember/market/pass/index?id=' + encodeURIComponent(id), complete: () => { this._navigating = false } })
  },
  openPassOrder(e) {
    const no = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.no
    if (!no || this._navigating) return
    this._navigating = true
    wx.navigateTo({ url: '/subpackageMember/market/pass-order/index?orderNo=' + encodeURIComponent(no), complete: () => { this._navigating = false } })
  },
  // l-after:整页游戏主题库(一行一张俱乐部式卡)
  goLibrary() {
    if (this._navigating) return
    this._navigating = true
    wx.navigateTo({ url: '/subpackageMember/market/library/index', complete: () => { this._navigating = false } })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/member/index/index' })
  }
})
