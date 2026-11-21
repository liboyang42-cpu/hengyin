// §17.2 礼品领取。同一次领取复用 requestId:网络未知时再点即原样重发,服务端返回原授权。
const app = getApp()
const { isBizOk } = require('../../../utils/response-shape.js')
const mv = require('../../utils/market-view.js')
const { enter, beats } = require('../../../utils/page-motion.js')
const { safeDecode } = require('../../../utils/safe-decode.js')

// 领取码缺失或无法识别 = 链接本身坏了,与其它缺参页同一个缺参态
const MISSING_LINK_CODES = { TOKEN_REQUIRED: true, GIFT_TOKEN_INVALID: true }

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'ready', // ready | claimed | failed | unknown | missing
    reason: '',
    claiming: false
  },
  onLoad(options) {
    this._token = options && options.token ? safeDecode(options.token) : ''
    if (!this._token) this.setData({ state: 'missing' })
    // 徽章弹入、光圈循环外扩:transition 分段重放,减少动态效果时都不播(光圈停在隐身)
    enter(this, 'gfIn')
    this._glow = beats(this, 'gfGlow', [[0, 34], [1, 2366]], { rest: 1 })
  },
  onUnload() { this._unloaded = true; if (this._glow) this._glow() },
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
          this.setData({ claiming: false, state: 'claimed' })
          return
        }
        const code = res && res.reasonCode
        if (MISSING_LINK_CODES[code]) { this.setData({ claiming: false, state: 'missing' }); return }
        this.setData({ claiming: false, state: 'failed', reason: mv.claimReasonText(code) })
      },
      fail: () => this.setData({ claiming: false, state: 'unknown' })
    })
  },
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
