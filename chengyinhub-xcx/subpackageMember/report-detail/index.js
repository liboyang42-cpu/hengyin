// 举报详情 + 进度时间线。两种投影,由服务端按归属裁决:
//   view=mine(默认) → GET /api/community-trust/reports/{id}          举报人本人,含本人描述
//   view=subject    → GET /api/community-trust/reports/{id}/subject  被举报方白名单投影,无举报人/描述/证据
// 非本人/非被举报方后端返回 403,这里显示「暂时不能查看」,不猜测原因。
const app = getApp()
const cyToast = require('../../utils/toast.js')
const { isBizOk, bizFailureMessage } = require('../../utils/response-shape.js')
const view = require('../report-common/view.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    mode: 'mine',
    state: 'loading', // loading | ready | error | no-permission | missing-param
    errorSub: '',
    report: null,
    nodes: [],
  },
  onLoad(options) {
    const reportId = view.positiveId(options && options.reportId)
    const mode = options && options.view === 'subject' ? 'subject' : 'mine'
    this._reportId = reportId
    this.setData({ mode })
    if (!reportId) { this.setData({ state: 'missing-param' }); return }
    this.load()
  },
  onShow() {
    if (this._loadedOnce) this.load(true)
  },
  onPullDownRefresh() { this.load(false, true) },
  retry() { this.load() },
  load(quiet, fromPull) {
    const that = this
    const id = this._reportId
    if (!id) return
    const st = { seq: (this._seq = (this._seq || 0) + 1), quiet: !!quiet, fromPull: !!fromPull,
      keep: this.data.state === 'ready', mode: this.data.mode, pending: 2, body: null, code: 0, msg: '', labels: null }
    if (!st.keep) this.setData({ state: 'loading' })
    app.sendRequest({
      url: st.mode === 'subject' ? '/api/community-trust/reports/' + id + '/subject' : '/api/community-trust/reports/' + id,
      method: 'GET', hideLoading: true, silentError: true,
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
  // 两路都回来后再落数据。403 = 既非举报人也非被举报方;其他失败给可重试的失败态。
  _settle(st) {
    st.pending -= 1
    if (st.pending > 0 || st.seq !== this._seq) return
    if (st.fromPull) wx.stopPullDownRefresh()
    const report = st.body ? view.normalizeReport(st.body, st.labels || {}, st.mode) : null
    if (!report) {
      if (st.keep) { if (!st.quiet) cyToast('刷新失败，稍后再试'); return }
      if (st.code === 403) { this.setData({ state: 'no-permission' }); return }
      this.setData({ state: 'error', errorSub: st.code && st.code !== 200 ? st.msg : '' })
      return
    }
    this._loadedOnce = true
    this.setData({ state: 'ready', report, nodes: view.timelineNodes(st.body.timeline, report.stage) })
  },
  goAppeal() {
    const report = this.data.report
    if (!report || !report.appealable) return
    wx.navigateTo({
      url: '/subpackageMember/report-appeal/index?reportId=' + report.id
        + (this.data.mode === 'subject' ? '&view=subject' : ''),
    })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.redirectTo({ url: '/subpackageMember/report-mine/index' })
  },
})
