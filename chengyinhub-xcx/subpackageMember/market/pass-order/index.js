// 通行证订单详情(我的订单 → 通行证订单):待支付可回查 / 继续支付,已开通可申请退款并看进度。
// 状态文案只来自服务端订单;退款走 POST /api/platform-pass/orders/{orderNo}/refund-apply {reason}(后端 fix/pass-backend-20261113),进度读订单 refund.reviewStatus。
const app = getApp()
const cyToast = require('../../../utils/toast.js')
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
    reason: ''
  },
  onLoad(options) {
    this._orderNo = options && options.orderNo ? decodeURIComponent(options.orderNo) : ''
    try { this._canVirtual = !!(wx.canIUse && wx.canIUse('requestVirtualPayment')) } catch (e) { this._canVirtual = false }
    if (!this._orderNo) { this.setData({ state: 'error' }); return }
    this.load()
  },
  onUnload() { this._unloaded = true },
  load() {
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
      if (r.state === 'cancelled') cyToast('已取消支付')
      else if (r.state === 'pending') this.setData({ notice: '确认中' })
      else if (r.state === 'failed') this.setData({ notice: r.message || '确认中' })
      this.load()
    })
  },
  onRefund() { this.setData({ refunding: true, notice: '' }) },
  onReason(e) { this.setData({ reason: (e && e.detail && e.detail.value) || '' }) },
  onRefundCancel() { this.setData({ refunding: false }) },
  onRefundSubmit() {
    const reason = String(this.data.reason || '').trim()
    if (this.data.busy) return
    if (!reason) { this.setData({ notice: '请填写退款原因' }); return }
    this.setData({ busy: true, notice: '' })
    request({ url: '/api/platform-pass/orders/' + encodeURIComponent(this._orderNo) + '/refund-apply', method: 'POST', data: { reason } }).then((res) => {
      if (this._unloaded) return
      this.setData({ busy: false })
      if (!isBizOk(res)) { this.setData({ notice: (res && res.msg) || '申请失败，请稍后再试' }); return }
      this.setData({ refunding: false, reason: '' })
      cyToast('已提交退款申请')
      this.load()
    }, () => { if (!this._unloaded) this.setData({ busy: false, notice: '申请失败，请稍后再试' }) })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.redirectTo({ url: '/subpackageMember/market/passes/index?tab=orders' })
  }
})
