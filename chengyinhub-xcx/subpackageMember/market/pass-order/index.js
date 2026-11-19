// 通行证订单详情(我的订单 → 通行证订单):待支付可回查 / 继续支付,已开通可申请退款并看进度。
// 状态文案只来自服务端订单;退款走 POST /api/platform-pass/orders/{orderNo}/refund-apply {reason}(后端 fix/pass-backend-20261113),进度读订单 refund.reviewStatus。
const app = getApp()
const cyToast = require('../../../utils/toast.js')
const modal = require('../../../utils/modal.js')
const subscribe = require('../../../utils/subscribe.js')
const { isBizOk } = require('../../../utils/response-shape.js')
const pp = require('../../utils/platform-pass.js')
const payFlow = require('../../utils/platform-pass-pay.js')

function request(opts) {
  return new Promise((resolve, reject) => {
    app.sendRequest(Object.assign({ hideLoading: true, autoErrorToast: false, header: { 'content-type': 'application/json' } }, opts, {
      success: (res) => resolve(res),
      successStatusAbnormal: (res) => resolve(res),
      fail: (e) => reject(e)
    }))
  })
}

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'loading', // loading | ready | error
    row: null,
    busy: false,
    notice: '',
    refunding: false, // 正在填退款原因
    reason: '',
    words: pp.WORDS,
    terms: []
  },
  onLoad(options) {
    this._orderNo = options && options.orderNo ? decodeURIComponent(options.orderNo) : ''
    try { this._canVirtual = !!(wx.canIUse && wx.canIUse('requestVirtualPayment')) } catch (e) { this._canVirtual = false }
    if (!this._orderNo) { this.setData({ state: 'missing' }); return }   // PG-041:缺单号落缺参态,不给「重试」空单号请求
    this.load()
  },
  onUnload() { this._unloaded = true },
  load() {
    if (!this._orderNo) { this.setData({ state: 'missing' }); return }
    const seq = (this._seq = (this._seq || 0) + 1)
    this.setData({ state: 'loading' })
    app.sendRequest({ url: '/api/platform-pass/orders/' + encodeURIComponent(this._orderNo), method: 'GET', hideLoading: true, autoErrorToast: false, success: (res) => this._onOrder(seq, res), successStatusAbnormal: (res) => this._onOrder(seq, res), fail: () => this._onOrder(seq, null) })
  },
  _onOrder(seq, res) {
    if (seq !== this._seq || this._unloaded) return
    const row = isBizOk(res) ? pp.orderRow(res.data) : null
    if (!row) { this.setData({ state: 'error' }); return }
    this.setData({ state: 'ready', row })
  },
  /** 待支付:回查;还是待支付就继续拉起支付。不确定时写「确认中」 */
  onResume() {
    if (this.data.busy) return
    this.setData({ busy: true, notice: '' })
    const platform = pp.platformOf(wx)
    const canPay = pp.channelState(platform, { xpay: { available: true } }, this._canVirtual).channel === 'XPAY'
    payFlow.resume({ request, wx }, this._orderNo, canPay).then((r) => {
      if (this._unloaded) return
      this.setData({ busy: false })
      if (r.state === 'cancelled') cyToast(pp.WORDS.PAY_CANCELLED)
      else if (r.state === 'closed') cyToast(pp.WORDS.CLOSED) // OR-010:订单已关闭/取消,不再拉起支付
      else if (r.state === 'pending') this.setData({ notice: pp.WORDS.CONFIRMING })
      else if (r.state === 'failed') this.setData({ notice: r.message || pp.WORDS.CONFIRMING })
      this.load()
    })
  },
  // OR-008:待付款单可取消;以服务端返回的订单状态为准
  onCancelOrder() {
    if (this.data.busy) return
    modal.show({
      dangerKey: 'pass.order.cancel',
      success: (r) => {
        if (!r || !r.confirm || this.data.busy || this._unloaded) return
        this.setData({ busy: true, notice: '' })
        request({ url: '/api/platform-pass/orders/' + encodeURIComponent(this._orderNo) + '/cancel', method: 'POST', write: true }).then((res) => {
          if (this._unloaded) return
          this.setData({ busy: false })
          if (!isBizOk(res)) { this.setData({ notice: (res && res.msg) || '取消失败，请稍后再试' }); return }
          if (res.data && res.data.status === 'CANCELLED') cyToast('订单已取消')
          else this.setData({ notice: pp.orderStateText(res.data) })
          this.load()
        }, () => { if (!this._unloaded) this.setData({ busy: false, notice: '取消失败，请稍后再试' }) })
      }
    })
  },
  copyOrderNo() {
    if (!this._orderNo) return
    wx.setClipboardData({ data: this._orderNo })
  },
  goAssistant() {
    if (this._navigating) return
    this._navigating = true
    wx.navigateTo({ url: '/subpackageB/pages/im/list/index', complete: () => { this._navigating = false } })
  },
  onRefund() {
    this.setData({ refunding: true, notice: '' })
    if (!this.data.terms.length) this._loadTerms()
  },
  // OR-024:退款须知与开通页同一来源(GET /api/platform-pass 的 terms;缺省固定两条)
  _loadTerms() {
    app.sendRequest({ url: '/api/platform-pass', method: 'GET', hideLoading: true, autoErrorToast: false,
      success: (res) => { if (!this._unloaded) this.setData({ terms: pp.termRows(isBizOk(res) ? res.data : null) }) },
      fail: () => { if (!this._unloaded) this.setData({ terms: pp.termRows(null) }) } })
  },
  onReason(e) { this.setData({ reason: (e && e.detail && e.detail.value) || '' }) },
  onRefundCancel() { this.setData({ refunding: false }) },
  onRefundSubmit() {
    const reason = String(this.data.reason || '').trim()
    if (this.data.busy) return
    if (!reason) { this.setData({ notice: '请填写退款原因' }); return }
    // OR-007:点提交时顺手请求退款结果订阅(须在点击手势内);审批后的站内信由服务端发
    subscribe.request(['refund'])
    this.setData({ busy: true, notice: '' })
    request({ url: '/api/platform-pass/orders/' + encodeURIComponent(this._orderNo) + '/refund-apply', method: 'POST', write: true, data: { reason } }).then((res) => {
      if (this._unloaded) return
      this.setData({ busy: false })
      if (!isBizOk(res)) { this.setData({ notice: (res && res.msg) || '申请失败，请稍后再试' }); return }
      this.setData({ refunding: false, reason: '' })
      // OR-006:按服务端回来的订单退款状态提示;已在处理中(409)走上面的 msg 分支
      const st = res.data && res.data.refund && res.data.refund.reviewStatus
      if (!st || st === 'PENDING') cyToast('已提交退款申请')
      else this.setData({ notice: pp.refundText(res.data) || pp.orderStateText(res.data) })
      this.load()
    }, () => { if (!this._unloaded) this.setData({ busy: false, notice: '申请失败，请稍后再试' }) })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.redirectTo({ url: '/subpackageMember/market/passes/index?tab=orders' })
  }
})
