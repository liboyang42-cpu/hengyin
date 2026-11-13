// l-pass 通行证页(原型 ps-pass / ps-success)。套餐、有效期、可玩主题数、渠道全部来自 GET /api/platform-pass;
// 开通只认服务端订单 PAID;成功动效只在 PAID 后出现。安卓 / iOS 只走虚拟支付(见 utils/platform-pass)。
const app = getApp()
const cyToast = require('../../../utils/toast.js')
const { isBizOk } = require('../../../utils/response-shape.js')
const { readReducedMotion } = require('../../../utils/motion-preference.js')
const pp = require('../../utils/platform-pass.js')
const payFlow = require('../../utils/platform-pass-pay.js')
const roleGuard = require('../../../utils/roleGuard.js')

const REVEAL = '/subpackagePass/reveal/index'
const PASS_ORDER = '/subpackageMember/market/pass-order/index'

function request(opts) {
  return new Promise((resolve, reject) => {
    app.sendRequest(Object.assign({ hideLoading: true, autoErrorToast: false, header: { 'content-type': 'application/json' } }, opts, {
      success: (res) => resolve(res),
      successStatusAbnormal: (res) => resolve(res),
      fail: (e) => reject(e)
    }))
  })
}

function get(url, data) {
  return new Promise((resolve) => {
    app.sendRequest({
      url, method: 'GET', data: data || {}, hideLoading: true, autoErrorToast: false,
      success: (res) => resolve(res),
      successStatusAbnormal: (res) => resolve(res),
      fail: () => resolve(null)
    })
  })
}

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'loading',
    scrolled: false,
    view: { enabled: false },
    buying: false,
    buyError: '',
    confirmOrderNo: '',
    reduced: false
  },
  onLoad() {
    let reduced = false
    try { reduced = !!readReducedMotion() } catch (e) { reduced = false }
    this.setData({ reduced })
    this._platform = pp.platformOf(wx)
    // 设备是否支持虚拟支付按真实能力判断;不支持时页面给原因,不退回普通支付
    try { this._canVirtual = !!(wx.canIUse && wx.canIUse('requestVirtualPayment')) } catch (e) { this._canVirtual = false }
    // l-icons:商家没有通行证(业主 2026-11-11),商家账号进来只给一句原因,不读套餐、不出购买
    let merchant = false
    try { merchant = !!roleGuard.isMerchantView() } catch (e) { merchant = false }
    if (merchant) { this.setData({ state: 'merchant' }); return }
    this.load()
  },
  onUnload() { this._unloaded = true },
  onPageScroll(e) {
    const s = !!(e && e.scrollTop > 40)
    if (s !== this.data.scrolled) this.setData({ scrolled: s })
  },
  load() {
    const seq = (this._seq = (this._seq || 0) + 1)
    this.setData({ state: 'loading' })
    // qx-final2:主题封面只在旧的开通成功层 / 主题网格里用,两者合并后都已退场;主题数取概览的 libraryCount
    get('/api/platform-pass').then((ov) => {
      if (seq !== this._seq || this._unloaded) return
      if (!isBizOk(ov) || !ov.data) { this.setData({ state: 'error' }); return }
      this._overview = ov.data
      this.setData({ state: 'ready', view: pp.passView(ov.data, this._selected || '', this._platform, this._canVirtual) })
    })
  },
  onPlan(e) {
    const code = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.code
    if (!code || this.data.buying) return
    if (code !== this._selected) this._intent = null // 换套餐是新的购买意图
    this._selected = code // 选中态由 view 渲染,这里只记住
    this.setData({ view: pp.passView(this._overview, code, this._platform, this._canVirtual) })
  },
  onBuy() {
    const v = this.data.view
    if (this.data.buying || !v || v.buyOff || !v.channel) return
    this.setData({ buying: true, buyError: '', confirmOrderNo: '' })
    // 同一次购买意图(套餐不变)重试沿用同一个 requestId,服务端按它幂等,不会重复下单
    if (!this._intent || this._intent.plan !== v.selected) this._intent = { plan: v.selected, requestId: payFlow.newRequestId() }
    payFlow.purchase({ request, wx }, v.selected, v.channel, { requestId: this._intent.requestId }).then((r) => {
      if (this._unloaded) return
      this.setData({ buying: false })
      if (r.state === 'paid') { this._intent = null; this._celebrate(r.order); return }
      // 取消支付只是这次没付:不动已有会员,订单留在我的订单里
      if (r.state === 'cancelled') { cyToast('已取消支付'); return }
      if (r.state === 'pending') { this.setData({ buyError: '确认中', confirmOrderNo: r.orderNo || '' }); return }
      this.setData({ buyError: r.message || '开通失败，请稍后再试' })
    })
  },
  /** 确认中 → 回到同一张订单继续查 */
  goOrder() {
    const no = this.data.confirmOrderNo
    if (!no || this._navigating) return
    this._navigating = true
    wx.navigateTo({ url: PASS_ORDER + '?orderNo=' + encodeURIComponent(no), complete: () => { this._navigating = false } })
  },
  /** 开通成功(服务端订单 PAID):去钥匙开场页播完整版(l-icons,proto ps-key),文案里的数字只带服务端给过的值 */
  _celebrate(order) {
    const untilText = pp.dateText(order && order.validTo)
    const count = this.data.view && this.data.view.count !== null && this.data.view.count !== undefined ? String(this.data.view.count) : ''
    const q = ['mode=paid']
    if (/^\d+$/.test(count)) q.push('count=' + count)
    if (untilText) q.push('until=' + encodeURIComponent(untilText))
    wx.redirectTo({ url: REVEAL + '?' + q.join('&'), fail: () => { this.load() } })
  },
  openTopic(e) {
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id
    if (!id || this._navigating) return
    this._navigating = true
    wx.navigateTo({ url: '/pages/topic/index/index?id=' + encodeURIComponent(id), complete: () => { this._navigating = false } })
  },
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
