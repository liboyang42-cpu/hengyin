// §17.2 礼品领取。同一次领取复用 requestId:网络未知时再点即原样重发,服务端返回原授权。
const app = getApp()
const { isBizOk } = require('../../../utils/response-shape.js')
const mv = require('../../utils/market-view.js')
const pageMotion = require('../../../utils/page-motion.js')
const morph = require('../../../utils/morph-success.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'ready', // ready | claimed | failed | unknown
    reason: '',
    claiming: false,
    claimDone: false,   // r4-motion:服务端确认后「领取」先收成勾(M7),停一拍再换成已领取
    pmHero: 0           // r4-motion:礼物圆章入场(page-motion --pm 变量)
  },
  onLoad(options) {
    this._token = options && options.token ? decodeURIComponent(options.token) : ''
    pageMotion.enter(this, 'pmHero', { once: true })
    if (!this._token) this.setData({ state: 'failed', reason: mv.claimReasonText('TOKEN_REQUIRED') })
  },
  claim() {
    if (this.data.claiming || !this._token) return
    if (!this._reqId) this._reqId = 'gift_' + Date.now() + '_' + Math.random().toString(36).slice(2, 10)
    this.setData({ claiming: true })
    app.sendRequest({
      url: '/api/marketing/product/gifts/claim', method: 'POST', header: { 'Content-Type': 'application/json' },
      data: JSON.stringify({ token: this._token, requestId: this._reqId }),
      hideLoading: true, autoErrorToast: false,
      success: (res) => {
        if (isBizOk(res) && res.data) {
          this._registrationId = res.data.registrationId || null
          morph.settle(this, 'claimDone', () => this.setData({ state: 'claimed' }), { with: { claiming: false } })
          return
        }
        this.setData({ claiming: false, state: 'failed', reason: mv.claimReasonText(res && res.reasonCode) })
      },
      fail: () => this.setData({ claiming: false, state: 'unknown' })
    })
  },
  onUnload() { morph.cancel(this) },
  goOrder() {
    if (this._registrationId) {
      wx.redirectTo({ url: '/subpackageMember/orderinfo/orderinfo?id=' + encodeURIComponent(this._registrationId) })
    } else {
      wx.redirectTo({ url: '/subpackageMember/market/passes/index?tab=gifts' })
    }
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/member/index/index' })
  }
})
