// 举报申诉。POST /api/community-trust/reports/appeal {reportId, reason, requestId}
// 服务端合同(CommunityTrustServiceImpl#appealReport):
//   · 举报人本人或被举报用户均可申诉(actorType REPORTER / SUBJECT 由服务端按归属判定,前端不传);
//   · 只有 RESOLVED / CLOSED 可申诉,成功后阶段 CAS 到 APPEALED;
//   · reason 1–1000 字;requestId 8–96 字符,同键同内容重放返回同一回执;
//   · 请求体没有证据字段 —— 本页只做文字申诉,不上传、不提交任何图片 URL。
// 提交前先回读确认仍可申诉;提交后再回读确认时间线出现申诉事件。结果未知时用同一 requestId 重试。
const app = getApp()
const modal = require('../../utils/modal.js')
const { isBizOk, bizFailureMessage } = require('../../utils/response-shape.js')
const view = require('../report-common/view.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    mode: 'mine',
    state: 'loading', // loading | form | closed | error | no-permission | missing-param
    report: null,
    reason: '',
    reasonError: '',
    maxLength: view.APPEAL_MAX,
    phase: 'idle', // idle | busy | ok | fail | unknown
    failText: '',
  },
  onLoad(options) {
    const reportId = view.positiveId(options && options.reportId)
    this._reportId = reportId
    this.setData({ mode: options && options.view === 'subject' ? 'subject' : 'mine' })
    if (!reportId) { this.setData({ state: 'missing-param' }); return }
    this.load()
  },
  retry() { this.load() },
  readBack(callback) {
    const mode = this.data.mode
    const id = this._reportId
    app.sendRequest({
      url: mode === 'subject' ? '/api/community-trust/reports/' + id + '/subject' : '/api/community-trust/reports/' + id,
      method: 'GET', hideLoading: true, silentError: true,
      success(res) {
        const report = isBizOk(res) ? view.normalizeReport(res.data, {}, mode) : null
        callback(report ? res.data : null, report, Number(res && res.code) || 0)
      },
      fail() { callback(null, null, 0) },
      successStatusAbnormal(res, status) { callback(null, null, Number(status) || 0) },
    })
  },
  load() {
    const that = this
    this.setData({ state: 'loading' })
    this.readBack(function (raw, report, code) {
      if (!report) { that.setData({ state: code === 403 ? 'no-permission' : 'error' }); return }
      that._beforeAppeals = view.appealEventCount(raw.timeline)
      that.setData({ report, state: report.appealable ? 'form' : 'closed' })
    })
  },
  onReasonInput(e) {
    const value = (e && e.detail && e.detail.value) || ''
    this.setData({ reason: value, reasonError: '' })
  },
  submit() {
    if (this.data.phase === 'busy') return
    const checked = view.validateAppealReason(this.data.reason)
    if (!checked.ok) { this.setData({ reasonError: checked.error }); return }
    const that = this
    modal.show({
      title: '提交申诉？',
      content: '提交后举报会进入申诉复核，申诉说明提交后不能修改。',
      confirmText: '确认提交',
      cancelText: '再想想',
      success(result) { if (result && result.confirm) that.send(checked.reason) },
    })
  },
  send(reason) {
    const that = this
    // 同一份说明的重试复用同一个幂等键;说明改了就换键
    if (!this._requestId || this._requestReason !== reason) {
      this._requestId = view.makeRequestId(this._reportId)
      this._requestReason = reason
    }
    this.setData({ phase: 'busy', failText: '' })
    app.sendRequest({
      url: '/api/community-trust/reports/appeal', method: 'POST', hideLoading: true, silentError: true,
      data: JSON.stringify({ reportId: this._reportId, reason, requestId: this._requestId }),
      header: { 'Content-Type': 'application/json' },
      success(res) {
        if (isBizOk(res)) { that.confirmByReadBack(true); return }
        that.setData({ phase: 'fail', failText: bizFailureMessage(res, '申诉没有提交成功，请稍后再试') })
      },
      fail() { that.confirmByReadBack(false) },
      successStatusAbnormal(res, status) {
        // 4xx 是明确失败;5xx/408/无状态码结果未知,先回读再下结论
        const code = Number(status) || 0
        if (code >= 400 && code < 500 && code !== 408) {
          that.setData({ phase: 'fail', failText: bizFailureMessage(res, '申诉没有提交成功，请稍后再试') })
          return
        }
        that.confirmByReadBack(false)
      },
    })
  },
  // accepted=服务端已返回成功回执;回读只用于确认,回读失败不推翻服务端回执
  confirmByReadBack(accepted) {
    const that = this
    this.readBack(function (raw) {
      if (view.appealConfirmed(that._beforeAppeals, raw) || accepted) {
        that._requestId = ''
        that.setData({ phase: 'ok' })
        return
      }
      that.setData({ phase: 'unknown' })
    })
  },
  onResultDone() {
    this.setData({ phase: 'idle' })
    this.goBack()
  },
  onFailClose() {
    this.setData({ phase: 'idle' })
    // 阶段可能已被平台改变(如已转入复核),回读一次以服务端为准
    this.load()
  },
  onUnknownRetry() {
    const checked = view.validateAppealReason(this.data.reason)
    if (checked.ok) this.send(checked.reason)
  },
  onUnknownLater() { this.setData({ phase: 'idle' }) },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.redirectTo({ url: '/subpackageMember/report-detail/index?reportId=' + this._reportId
      + (this.data.mode === 'subject' ? '&view=subject' : '') })
  },
})
