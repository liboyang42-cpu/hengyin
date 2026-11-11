// l-pass 通行证页(原型 ps-pass / ps-success)。套餐、有效期、可玩主题数、渠道全部来自 GET /api/platform-pass;
// 开通只认服务端订单 PAID;成功动效只在 PAID 后出现。iOS 走虚拟支付,其他走 JSAPI(见 utils/platform-pass)。
const app = getApp()
const cyToast = require('../../../utils/toast.js')
const { isBizOk } = require('../../../utils/response-shape.js')
const { readReducedMotion } = require('../../../utils/motion-preference.js')
const pp = require('../../utils/platform-pass.js')
const payFlow = require('../../utils/platform-pass-pay.js')
const roleGuard = require('../../../utils/roleGuard.js')

const REVEAL = '/subpackagePass/reveal/index'

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
    library: [],
    selected: '',
    buying: false,
    buyError: '',
    reduced: false
  },
  onLoad() {
    let reduced = false
    try { reduced = !!readReducedMotion() } catch (e) { reduced = false }
    this.setData({ reduced })
    this._platform = pp.platformOf(wx)
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
    Promise.all([
      get('/api/platform-pass'),
      get('/api/platform-pass/library', { limit: 12 })
    ]).then(([ov, lib]) => {
      if (seq !== this._seq || this._unloaded) return
      if (!isBizOk(ov) || !ov.data) { this.setData({ state: 'error' }); return }
      this._overview = ov.data
      const rows = isBizOk(lib) && lib.data && Array.isArray(lib.data.rows) ? lib.data.rows.map(pp.libraryRow).filter(Boolean) : []
      this.setData({ state: 'ready', view: pp.passView(ov.data, this.data.selected, this._platform), library: rows })
    })
  },
  onPlan(e) {
    const code = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.code
    if (!code || this.data.buying) return
    this.setData({ selected: code, view: pp.passView(this._overview, code, this._platform) })
  },
  onBuy() {
    const v = this.data.view
    if (this.data.buying || !v || v.buyOff || !v.channel) return
    this.setData({ buying: true, buyError: '' })
    payFlow.purchase({ request, wx }, v.selected, v.channel).then((r) => {
      if (this._unloaded) return
      this.setData({ buying: false })
      if (r.state === 'paid') { this._celebrate(r.order); return }
      if (r.state === 'cancelled') { cyToast('已取消支付'); return }
      if (r.state === 'pending') { this.setData({ buyError: '支付结果确认中，稍后在订单里查看' }); return }
      this.setData({ buyError: r.message || '开通失败，请稍后再试' })
    })
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
