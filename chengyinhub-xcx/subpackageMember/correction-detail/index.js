// BL-184 纠错进度 + 文字补证。入口:「我的纠错」列表。结构照「举报进度」subpackageMember/report-detail。
//   GET  /api/community-trust/contributions/{id}      本人纠错(MerchantContributionVO);非本人后端 403
//   POST /api/community-trust/contributions/supplement {contributionId, expectedVersion, description, requestId}
// 补证合同(CommunityTrustServiceImpl#supplementContribution):canSupplement 且 NEEDS_EVIDENCE/REJECTED 才可补;
//   description 1–2000 字;requestId 8–96 字符,同键同内容重放返回同一记录;本批拒绝附件 —— 只做文字补证。
// 补证在 cy-sheet 内走「填写 → 结果」;提交后回读确认,结果未知时用同一 requestId 重试。
const app = getApp()
const cyToast = require('../../utils/toast.js')
const { isBizOk, bizFailureMessage } = require('../../utils/response-shape.js')
const view = require('../correction-common/view.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'loading', // loading | ready | error | no-permission | missing-param
    errorSub: '',
    item: null,
    sheet: '', // '' | form | busy | ok | fail | unknown
    supplement: '',
    supplementError: '',
    failText: '',
    maxLength: view.SUPPLEMENT_MAX,
  },
  onLoad(options) {
    const id = view.positiveId(options && options.contributionId)
    this._id = id
    if (!id) { this.setData({ state: 'missing-param' }); return }
    this.load()
  },
  onPullDownRefresh() { if (this._id) this.load(false, true); else wx.stopPullDownRefresh() },
  retry() { this.load() },
  load(quiet, fromPull) {
    const that = this
    const id = this._id
    if (!id) return
    const st = { seq: (this._seq = (this._seq || 0) + 1), quiet: !!quiet, fromPull: !!fromPull,
      keep: this.data.state === 'ready' }
    if (!st.keep) this.setData({ state: 'loading' })
    app.sendRequest({
      url: `/api/community-trust/contributions/${id}`, method: 'GET', hideLoading: true, silentError: true,
      success(res) {
        that._settle(st, isBizOk(res) ? res.data : null, Number(res && res.code) || 0, bizFailureMessage(res, ''))
      },
      fail() { that._settle(st, null, 0, '') },
      successStatusAbnormal(res, status) { that._settle(st, null, Number(status) || 0, '') },
    })
  },
  // 403 = 不是本人的纠错;其他失败给可重试的失败态
  _settle(st, body, code, msg) {
    if (st.fromPull) wx.stopPullDownRefresh()
    if (st.seq !== this._seq) return
    const item = body ? view.normalizeContribution(body) : null
    if (!item) {
      if (st.keep) { if (!st.quiet) cyToast('刷新失败，稍后再试'); return }
      if (code === 403) { this.setData({ state: 'no-permission' }); return }
      this.setData({ state: 'error', errorSub: code && code !== 200 ? msg : '' })
      return
    }
    this.setData({ state: 'ready', item })
  },

  // ---------- 补证(cy-sheet) ----------
  openSupplement() {
    const item = this.data.item
    if (!item || !item.canSupplement || this.data.sheet) return
    this.setData({ sheet: 'form', supplementError: '', failText: '' })
  },
  onSupplementInput(e) {
    this.setData({ supplement: (e && e.detail && e.detail.value) || '', supplementError: '' })
  },
  submitSupplement() {
    // 在途闸:提交到回读结束前不接受第二次点击
    if (this._submitting) return
    if (this.data.sheet !== 'form' && this.data.sheet !== 'unknown') return
    const item = this.data.item
    if (!item || !item.canSupplement) return
    const checked = view.validateSupplement(this.data.supplement)
    if (!checked.ok) { this.setData({ sheet: 'form', supplementError: checked.error }); return }
    const that = this
    // 同一份说明的重试复用同一个幂等键;说明改了就换键
    if (!this._requestId || this._requestText !== checked.description) {
      this._requestId = view.makeRequestId(this._id)
      this._requestText = checked.description
    }
    this._submitting = true
    this._beforeVersion = item.version
    this.setData({ sheet: 'busy', failText: '' })
    app.sendRequest({
      url: '/api/community-trust/contributions/supplement', method: 'POST', hideLoading: true, silentError: true,
      data: JSON.stringify(view.buildSupplement(item, checked.description, this._requestId)),
      header: { 'Content-Type': 'application/json' },
      success(res) {
        if (isBizOk(res)) { that.confirmByReadBack(true); return }
        that._submitting = false
        that._requestId = ''
        that.setData({ sheet: 'fail', failText: bizFailureMessage(res, '补充说明没有提交成功，请稍后再试') })
      },
      fail() { that.confirmByReadBack(false) },
      successStatusAbnormal(res, status) {
        // 4xx 是明确失败;5xx/408/无状态码结果未知,先回读再下结论
        const code = Number(status) || 0
        if (code >= 400 && code < 500 && code !== 408) {
          that._submitting = false
          that._requestId = ''
          that.setData({ sheet: 'fail', failText: bizFailureMessage(res, '补充说明没有提交成功，请稍后再试') })
          return
        }
        that.confirmByReadBack(false)
      },
    })
  },
  // accepted=服务端已返回成功;回读只用于确认与刷新页面,回读失败不推翻服务端结果
  confirmByReadBack(accepted) {
    const that = this
    app.sendRequest({
      url: `/api/community-trust/contributions/${this._id}`, method: 'GET', hideLoading: true, silentError: true,
      success(res) { that._afterSupplement(accepted, isBizOk(res) ? res.data : null) },
      fail() { that._afterSupplement(accepted, null) },
      successStatusAbnormal() { that._afterSupplement(accepted, null) },
    })
  },
  _afterSupplement(accepted, raw) {
    this._submitting = false
    if (view.supplementConfirmed(this._beforeVersion, raw) || accepted) {
      this._requestId = ''
      const item = raw ? view.normalizeContribution(raw) : null
      if (item) this.setData({ sheet: 'ok', supplement: '', item })
      else this.setData({ sheet: 'ok', supplement: '' })
      return
    }
    this.setData({ sheet: 'unknown' })
  },
  closeSheet() {
    const sheet = this.data.sheet
    if (sheet === 'busy') return
    this.setData({ sheet: '' })
    // 成功/失败/未知后状态都可能已变,回读一次以服务端为准
    if (sheet === 'ok' || sheet === 'fail' || sheet === 'unknown') this.load(true)
  },

  // 从列表点进来时返回列表,不叠页;从系统消息/成功态进来时照旧打开列表
  goMine() {
    const pages = getCurrentPages()
    const prev = pages.length > 1 ? pages[pages.length - 2] : null
    if (prev && prev.route === 'subpackageMember/correction-mine/index') wx.navigateBack({ fail: () => wx.redirectTo({ url: '/subpackageMember/correction-mine/index' }) })
    else wx.navigateTo({ url: '/subpackageMember/correction-mine/index' })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.redirectTo({ url: '/subpackageMember/correction-mine/index' })
  },
})
