// §18 A/B 视频悬赏作者端。我的投稿/播放与应计只读;投稿表单只在带 campaignKey+topicId 的活动链接里出现
// (玩家侧没有活动列表接口,活动由运营下发)。
const app = getApp()
const cyToast = require('../../../utils/toast.js')
const modal = require('../../../utils/modal.js')
const { isBizOk } = require('../../../utils/response-shape.js')
const vb = require('../../utils/video-bounty-view.js')

function get(url, data) {
  return new Promise((resolve) => {
    app.sendRequest({
      url, method: 'GET', data: data || {}, hideLoading: true, silentError: true, autoErrorToast: false,
      success: (res) => resolve(isBizOk(res) ? res.data : null),
      fail: () => resolve(null)
    })
  })
}
function post(url, body) {
  return new Promise((resolve) => {
    app.sendRequest({
      url, method: 'POST', header: { 'Content-Type': 'application/json' }, data: JSON.stringify(body || {}),
      hideLoading: true, silentError: true, autoErrorToast: false,
      success: (res) => resolve(res || {}),
      fail: () => resolve(null)
    })
  })
}

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    tabs: [{ key: 'subs', label: '我的投稿' }, { key: 'accruals', label: '播放与应计' }],
    tab: 'subs',
    platforms: vb.PLATFORMS,
    campaignKey: '',
    topicId: '',
    form: { platform: '', sourceUrl: '', originGroupKey: '' },
    fieldError: { field: '', text: '' },
    submitError: '',
    submitting: false,
    subs: { state: 'loading', rows: [] },
    accruals: { state: 'loading', rows: [] }
  },
  onLoad(options) {
    this.setData({
      campaignKey: (options && options.campaignKey) ? decodeURIComponent(options.campaignKey) : '',
      topicId: (options && options.topicId) ? String(options.topicId) : ''
    })
    this.load()
  },
  onPullDownRefresh() { this.load() },
  load() {
    const seq = (this._seq = (this._seq || 0) + 1)
    Promise.all([get('/api/video-bounty/submission/mine'), get('/api/video-bounty/accrual/mine')]).then(([subs, acc]) => {
      if (seq !== this._seq) return
      wx.stopPullDownRefresh()
      this.setData({ subs: vb.listView(subs, vb.submissionRow), accruals: vb.listView(acc, vb.accrualRow) })
    })
  },
  onTab(e) { const k = e && e.detail && e.detail.key; if (k) this.setData({ tab: k }) },
  onPlatform(e) { const k = e && e.detail && e.detail.key; if (k) this.setData({ 'form.platform': k, fieldError: { field: '', text: '' } }) },
  onUrl(e) { this.setData({ 'form.sourceUrl': e.detail.value, fieldError: { field: '', text: '' }, submitError: '' }) },
  onGroup(e) { this.setData({ 'form.originGroupKey': e.detail.value }) },
  submit() {
    if (this.data.submitting) return
    const err = vb.formError(this.data.form)
    if (err) { this.setData({ fieldError: err }); return }
    this.setData({ submitting: true, submitError: '' })
    post('/api/video-bounty/submission', {
      campaignKey: this.data.campaignKey,
      topicId: Number(this.data.topicId),
      platform: this.data.form.platform,
      sourceUrl: String(this.data.form.sourceUrl).trim(),
      originGroupKey: String(this.data.form.originGroupKey || '').trim() || null
    }).then((res) => {
      if (res && isBizOk(res)) {
        this.setData({ submitting: false, form: { platform: '', sourceUrl: '', originGroupKey: '' }, tab: 'subs' })
        cyToast('已提交')
        this.load()
        return
      }
      this.setData({ submitting: false, submitError: res ? vb.reasonText(res.reasonCode, res.msg) : '结果待确认，请刷新我的投稿后再提交' })
    })
  },
  appeal(e) {
    const id = e.currentTarget.dataset.id
    modal.show({
      title: '申诉', editable: true, multiline: true, placeholderText: '说明理由', confirmText: '提交',
      validate: (v) => (String(v || '').trim() ? '' : '请填写申诉说明'),
      success: (r) => {
        if (!r || !r.confirm) return
        post('/api/video-bounty/submission/' + encodeURIComponent(id) + '/appeal', { note: String(r.content).trim() }).then((res) => {
          if (res && isBizOk(res)) { cyToast('已提交申诉'); this.load() } else cyToast(res ? vb.reasonText(res.reasonCode, res.msg) : '结果待确认，请刷新')
        })
      }
    })
  },
  cancel(e) {
    const id = e.currentTarget.dataset.id
    modal.show({
      title: '取消投稿', content: '取消后这条投稿不再参与悬赏', confirmText: '取消投稿', cancelText: '保留', danger: true,
      success: (r) => {
        if (!r || !r.confirm) return
        post('/api/video-bounty/submission/' + encodeURIComponent(id) + '/cancel', {}).then((res) => {
          if (res && isBizOk(res)) { cyToast('已取消'); this.load() } else cyToast(res ? vb.reasonText(res.reasonCode, res.msg) : '结果待确认，请刷新')
        })
      }
    })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.redirectTo({ url: '/subpackageA/pages/assetcenter/earnings/index' })
  }
})
