// §17.1 / §17.2 / §17.3 / §17.4 / P6 通行证详情与购买。
// 金额、期限、可买与否只读服务端:先读详情,再按当前「给谁用」向服务端要报价;
// 购买按钮只来自报价 availablePayModes。下单只回传 quoteSign/quoteExpiresAt,不提交金额。
// 同一次购买意图复用 requestId(网络未知可原样重发);报价变了才换新号。
const app = getApp()
const cyToast = require('../../../utils/toast.js')
const modal = require('../../../utils/modal.js')
const { isBizOk } = require('../../../utils/response-shape.js')
const mv = require('../../utils/market-view.js')
const pu = require('../../utils/pass-unlock-view.js')
const { readReducedMotion } = require('../../../utils/motion-preference.js')

function newRequestId() {
  return 'mkp_' + Date.now() + '_' + Math.random().toString(36).slice(2, 10)
}

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'loading', // loading | ready | error
    errorTitle: '加载失败',
    card: { name: '', topics: [] },
    forTabs: [{ key: 'self', label: '自己用' }, { key: 'gift', label: '送给好友' }],
    forGift: false,
    buy: { state: 'unavailable', reason: '', payModes: [] },
    checkout: [],
    buying: false,
    buyingMode: 0,
    buyError: '',
    giftSheet: false,
    // r3-pass:票面拼合动效与到账分步(只来自服务端商品详情与已付订单)
    heroState: 'scattered',
    heroFace: '',
    reducedMotion: false,
    unlock: { show: false, steps: [] }
  },
  onLoad(options) {
    this._id = options && options.id
    this.setData({ reducedMotion: readReducedMotion() })
    this.load()
  },
  load() {
    if (!this._id) { this.setData({ state: 'error', errorTitle: '商品不存在或已下架' }); return }
    const seq = (this._seq = (this._seq || 0) + 1)
    this.setData({ state: 'loading' })
    app.sendRequest({
      url: '/api/marketing/product/' + encodeURIComponent(this._id), method: 'GET', hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (seq !== this._seq) return
        const card = isBizOk(res) ? mv.productCard(res.data) : null
        if (!card) {
          this.setData({ state: 'error', errorTitle: res && res.reasonCode ? mv.reasonText(res.reasonCode) : '加载失败' })
          return
        }
        this._product = res.data
        this.setData({ state: 'ready', card, forGift: card.giftOnly ? true : this.data.forGift, heroFace: pu.faceText(res.data), heroState: 'scattered' })
        // 下一帧再挂拼好的类,碎片才会飞回来
        clearTimeout(this._heroT)
        this._heroT = setTimeout(() => this.setData({ heroState: 'assembled' }), this.data.reducedMotion ? 0 : 80)
        if (card.isExtraSeat) {
          this.setData({ buy: mv.purchaseView({ purchaseRoute: mv.ROUTE.EXTRA_SEAT }) })
          return
        }
        this.quote()
      },
      fail: () => { if (seq === this._seq) this.setData({ state: 'error', errorTitle: '加载失败' }) }
    })
  },
  quote() {
    const seq = (this._quoteSeq = (this._quoteSeq || 0) + 1)
    const fulfillmentKind = this.data.forGift ? mv.FULFILLMENT.GIFT : mv.FULFILLMENT.SELF
    this._quote = null
    this.setData({ buy: { state: 'loading', reason: '', payModes: [] }, buyError: '', checkout: mv.checkoutRows(null, this.data.card) })
    app.sendRequest({
      url: '/api/marketing/product/quote', method: 'POST', header: { 'Content-Type': 'application/json' },
      data: JSON.stringify({ productId: Number(this._id), quantity: 1, fulfillmentKind }),
      hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (seq !== this._quoteSeq) return
        if (isBizOk(res) && res.data) {
          if (!this._quote || this._quote.quoteSign !== res.data.quoteSign) this._reqId = null
          this._quote = res.data
          this.setData({ buy: mv.purchaseView(res.data), checkout: mv.checkoutRows(res.data, this.data.card) })
        } else {
          // 报价被拒:只说原因,不出购买按钮(加座/渠道关闭等走这里)
          const checkout = res && res.checkout
          this.setData({ buy: checkout
            ? mv.purchaseView({ purchaseRoute: mv.ROUTE.TICKET, checkout })
            : { state: 'unavailable', reason: mv.reasonText(res && res.reasonCode), payModes: [] } })
        }
      },
      fail: () => {
        if (seq !== this._quoteSeq) return
        this.setData({ buy: { state: 'unavailable', reason: '报价暂不可用，请稍后重试', payModes: [] } })
      }
    })
  },
  onFor(e) {
    const key = e && e.detail && e.detail.key
    const forGift = key === 'gift'
    if (forGift === this.data.forGift) return
    this.setData({ forGift })
    this.quote()
  },
  onBuy(e) {
    const mode = Number(e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.mode)
    if (!mode || this.data.buying || !this._quote) return
    const label = (this.data.buy.payModes.find((m) => m.mode === mode) || {}).label || ''
    modal.show({
      title: '确认购买', content: this.data.card.name + ' · ' + label, confirmText: '确认',
      success: (r) => { if (r && r.confirm) this._submit(mode) }
    })
  },
  _submit(mode) {
    if (!this._reqId || this._reqMode !== mode) { this._reqId = newRequestId(); this._reqMode = mode }
    const body = mv.orderRequest(Number(this._id), this._quote, mode,
      this.data.forGift ? mv.FULFILLMENT.GIFT : mv.FULFILLMENT.SELF, this._reqId)
    this.setData({ buying: true, buyingMode: mode, buyError: '' })
    app.sendRequest({
      url: '/api/marketing/product/order', method: 'POST', header: { 'Content-Type': 'application/json' },
      data: JSON.stringify(body), hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (isBizOk(res) && res.data) { this._afterOrder(res.data); return }
        const code = res && res.reasonCode
        // 有 reasonCode 按码说;没有码时原样显示后端提示,不改写
        // r2-join:去掉给开发看的内部编号(topicId=…),其余原文不改
        this.setData({ buying: false, buyError: code ? mv.reasonText(code) : (mv.playerText(res && res.msg) || mv.reasonText(null)) })
        if (code === 'QUOTE_EXPIRED' || code === 'QUOTE_STALE') this.quote()
      },
      // 结果未知:保留同一 requestId,再点即原样重发,由服务端重放原单
      fail: () => this.setData({ buying: false, buyError: '结果待确认，请再点一次查询原订单' })
    })
  },
  _afterOrder(order) {
    this._reqId = null
    const token = order.gift && order.gift.claimToken
    if (order.statusLabel === 'PAID') {
      this.setData({ buying: false })
      if (token) { this._giftToken = token; this.setData({ giftSheet: true }); return }
      // r3-pass:服务端回 PAID 才放到账分步;步数全由真实字段决定,没有的事实没有那一步
      const steps = pu.unlockSteps(this._product, order)
      if (steps.length) { this.setData({ unlock: { show: true, steps } }); return }
      this._toOrders()
      return
    }
    if (order.statusLabel === 'PENDING_CASH') { this._payCash(order); return }
    this.setData({ buying: false, buyError: mv.orderStateText(order) })
  },
  // 现金:预下单拿支付参数 → 微信支付 → 只以服务端订单状态为准(SDK 成功 ≠ 已付)
  _payCash(order) {
    const blocked = order.payment && order.payment.blockedReason
    if (blocked) { this.setData({ buying: false, buyError: mv.reasonText(blocked) }); return }
    app.sendRequest({
      url: '/api/marketing/product/orders/' + encodeURIComponent(order.orderNo) + '/pay', method: 'POST',
      hideLoading: true, autoErrorToast: false,
      success: (res) => {
        const p = isBizOk(res) && res.data && res.data.payParams
        if (!p) { this.setData({ buying: false, buyError: mv.reasonText(res && res.reasonCode) }); return }
        wx.requestPayment(Object.assign({}, p, {
          complete: () => this._verify(order.orderNo, 0)
        }))
      },
      fail: () => this.setData({ buying: false, buyError: '结果待确认，请到我的订单查看' })
    })
  },
  _verify(orderNo, attempt) {
    app.sendRequest({
      url: '/api/marketing/product/orders', method: 'GET', data: { limit: 20 }, hideLoading: true, autoErrorToast: false,
      success: (res) => {
        const row = isBizOk(res) && Array.isArray(res.data) ? res.data.find((o) => o && o.orderNo === orderNo) : null
        if (row && row.statusLabel === 'PAID') { this._afterOrder(row); return }
        if (attempt < 2) { setTimeout(() => this._verify(orderNo, attempt + 1), 1500); return }
        this.setData({ buying: false, buyError: row ? mv.orderStateText(row) : '支付结果确认中，请到我的订单查看' })
      },
      fail: () => this.setData({ buying: false, buyError: '支付结果确认中，请到我的订单查看' })
    })
  },
  onUnlockDone() {
    this.setData({ 'unlock.show': false })
    this._toOrders()
  },
  _toOrders() {
    cyToast('已购买')
    wx.redirectTo({ url: '/subpackageMember/market/passes/index?tab=orders' })
  },
  onUnload() { clearTimeout(this._heroT) },
  goCheckout() {
    const c = this.data.buy && this._quote ? this._quote.checkout : null
    const checkout = c || (this.data.buy && this.data.buy.checkout)
    if (!checkout || !checkout.activityId) { cyToast('该商品需在活动报名中购买'); return }
    wx.navigateTo({ url: '/pages/activity/baoming/baoming?activityId=' + encodeURIComponent(checkout.activityId)
      + (checkout.ticketId ? '&ticketId=' + encodeURIComponent(checkout.ticketId) : '') })
  },
  closeGiftSheet() {
    this.setData({ giftSheet: false })
    wx.redirectTo({ url: '/subpackageMember/market/passes/index?tab=gifts' })
  },
  onShareAppMessage() {
    const token = this._giftToken
    if (!token) return { title: this.data.card.name, path: '/subpackageMember/market/pass/index?id=' + encodeURIComponent(this._id) }
    return { title: '送你一份「' + this.data.card.name + '」', path: '/subpackageMember/market/gift/index?token=' + encodeURIComponent(token) }
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.redirectTo({ url: '/subpackageMember/market/passes/index' })
  }
})
