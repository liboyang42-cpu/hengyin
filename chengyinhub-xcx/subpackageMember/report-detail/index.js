// 举报进度 + 文字申诉。入口:系统消息里的举报进度通知(subpackageB/pages/im/list 系统 tab)。
//   GET  /api/community-trust/reports/{id}   举报人本人投影(含时间线);非本人后端 403
//   POST /api/community-trust/reports/appeal {reportId, reason, requestId}
// 申诉合同(CommunityTrustServiceImpl#appealReport):只有 RESOLVED/CLOSED 可申诉,成功后 CAS 到 APPEALED;
//   reason 1–1000 字;requestId 8–96 字符,同键同内容重放返回同一回执;请求体没有证据字段 —— 只做文字申诉。
// 申诉在 cy-sheet 内走「填写 → 确认 → 结果」;提交后回读时间线确认,结果未知时用同一 requestId 重试。
// 被举报方视图按 2026-10-15 用户决定不做。
const app = getApp()
const cyToast = require('../../utils/toast.js')
const { isBizOk, bizFailureMessage } = require('../../utils/response-shape.js')
const view = require('../report-common/view.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'loading', // loading | ready | error | no-permission | missing-param
    errorSub: '',
    report: null,
    nodes: [],
    sheet: '', // '' | form | confirm | busy | ok | fail | unknown
    reason: '',
    reasonError: '',
    failText: '',
    maxLength: view.APPEAL_MAX,
  },
  onLoad(options) {
    const reportId = view.positiveId(options && options.reportId)
    this._reportId = reportId
    if (!reportId) { this.setData({ state: 'missing-param' }); return }
    this.load()
  },
  onPullDownRefresh() { this.load(false, true) },
  retry() { this.load() },
  load(quiet, fromPull) {
    const that = this
    const id = this._reportId
    if (!id) return
    const st = { seq: (this._seq = (this._seq || 0) + 1), quiet: !!quiet, fromPull: !!fromPull,
      keep: this.data.state === 'ready', pending: 2, body: null, code: 0, msg: '', labels: null }
    if (!st.keep) this.setData({ state: 'loading' })
    app.sendRequest({
      url: `/api/community-trust/reports/${id}`, method: 'GET', hideLoading: true, silentError: true,
      success(res) {
        st.body = isBizOk(res) ? res.data : null
        st.code = Number(res && res.code) || 0
        st.msg = bizFailureMessage(res, '')
        that._settle(st)
      },
      fail() { that._settle(st) },
      successStatusAbnormal(res, status) { st.code = Number(status) || 0; that._settle(st) },
    })
    app.sendRequest({
      url: '/api/community-trust/reports/taxonomy', method: 'GET', hideLoading: true, silentError: true,
      success(res) { if (isBizOk(res)) st.labels = view.reasonLabelMap(res.data); that._settle(st) },
      fail() { that._settle(st) },
      successStatusAbnormal() { that._settle(st) },
    })
  },
  // 两路都回来后再落数据。403 = 不是本人的举报;其他失败给可重试的失败态。
  _settle(st) {
    st.pending -= 1
    if (st.pending > 0 || st.seq !== this._seq) return
    if (st.fromPull) wx.stopPullDownRefresh()
    const report = st.body ? view.normalizeReport(st.body, st.labels || {}) : null
    if (!report) {
      if (st.keep) { if (!st.quiet) cyToast('刷新失败，稍后再试'); return }
      if (st.code === 403) { this.setData({ state: 'no-permission' }); return }
      this.setData({ state: 'error', errorSub: st.code && st.code !== 200 ? st.msg : '' })
      return
    }
    this._beforeAppeals = view.appealEventCount(st.body.timeline)
    this.setData({ state: 'ready', report, nodes: view.timelineNodes(st.body.timeline, report.stage) })
  },

  // ---------- 申诉(cy-sheet) ----------
  openAppeal() {
    const report = this.data.report
    if (!report || !report.appealable || this.data.sheet) return
    this.setData({ sheet: 'form', reasonError: '', failText: '' })
  },
  onReasonInput(e) {
    this.setData({ reason: (e && e.detail && e.detail.value) || '', reasonError: '' })
  },
  toConfirm() {
    const checked = view.validateAppealReason(this.data.reason)
    if (!checked.ok) { this.setData({ reasonError: checked.error }); return }
    this.setData({ sheet: 'confirm' })
  },
  backToForm() { if (this.data.sheet === 'confirm') this.setData({ sheet: 'form' }) },
  submitAppeal() {
    // 在途闸:提交到回读结束前不接受第二次点击(sheet 切 busy 之前的同帧连点也挡住)
    if (this._appealing) return
    if (this.data.sheet !== 'confirm' && this.data.sheet !== 'unknown') return
    const checked = view.validateAppealReason(this.data.reason)
    if (!checked.ok) { this.setData({ sheet: 'form', reasonError: checked.error }); return }
    const that = this
    // 同一份说明的重试复用同一个幂等键;说明改了就换键
    if (!this._requestId || this._requestReason !== checked.reason) {
      this._requestId = view.makeRequestId(this._reportId)
      this._requestReason = checked.reason
    }
    this._appealing = true
    this.setData({ sheet: 'busy', failText: '' })
    app.sendRequest({
      url: '/api/community-trust/reports/appeal', method: 'POST', hideLoading: true, silentError: true,
      data: JSON.stringify({ reportId: this._reportId, reason: checked.reason, requestId: this._requestId }),
      header: { 'Content-Type': 'application/json' },
      success(res) {
        if (isBizOk(res)) { that.confirmByReadBack(true); return }
        that._appealing = false
        that.setData({ sheet: 'fail', failText: bizFailureMessage(res, '申诉没有提交成功，请稍后再试') })
      },
      fail() { that.confirmByReadBack(false) },
      successStatusAbnormal(res, status) {
        // 4xx 是明确失败;5xx/408/无状态码结果未知,先回读再下结论
        const code = Number(status) || 0
        if (code >= 400 && code < 500 && code !== 408) {
          that._appealing = false
          that.setData({ sheet: 'fail', failText: bizFailureMessage(res, '申诉没有提交成功，请稍后再试') })
          return
        }
        that.confirmByReadBack(false)
      },
    })
  },
  // accepted=服务端已返回成功回执;回读只用于确认与刷新页面,回读失败不推翻服务端回执
  confirmByReadBack(accepted) {
    const that = this
    const id = this._reportId
    app.sendRequest({
      url: `/api/community-trust/reports/${id}`, method: 'GET', hideLoading: true, silentError: true,
      success(res) { that._afterAppeal(accepted, isBizOk(res) ? res.data : null) },
      fail() { that._afterAppeal(accepted, null) },
      successStatusAbnormal() { that._afterAppeal(accepted, null) },
    })
  },
  _afterAppeal(accepted, raw) {
    this._appealing = false
    if (view.appealConfirmed(this._beforeAppeals, raw) || accepted) {
      this._requestId = ''
      const report = raw ? view.normalizeReport(raw, {}) : null
      if (report) {
        report.reasonText = this.data.report.reasonText
        this._beforeAppeals = view.appealEventCount(raw.timeline)
        this.setData({ sheet: 'ok', reason: '', report, nodes: view.timelineNodes(raw.timeline, report.stage) })
      } else {
        this.setData({ sheet: 'ok', reason: '' })
      }
      return
    }
    this.setData({ sheet: 'unknown' })
  },
  closeSheet() {
    const sheet = this.data.sheet
    if (sheet === 'busy') return
    this.setData({ sheet: '' })
    // 明确失败后阶段可能已被平台改变,回读一次以服务端为准
    if (sheet === 'fail' || sheet === 'unknown') this.load(true)
  },

  goMine() { wx.navigateTo({ url: '/subpackageMember/report-mine/index' }) },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.redirectTo({ url: '/subpackageMember/report-mine/index' })
  },
})
