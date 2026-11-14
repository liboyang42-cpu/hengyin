// 主题奖励:catalog(该主题当前提供的奖励) → offer(本人可否领取) → 领取。
// 入口:?topicId=。领取只在 offer 没有 errorCode 时可点(POLICY_CLOSED 即开关未开);
// 领取先把 requestId 记到本地,回包丢失时下次进入按原 requestId 回读(claims/recover),不换号重领。
const app = getApp()
const cyToast = require('../../utils/toast.js')
const { isBizOk } = require('../../utils/response-shape.js')
const view = require('../utils/noncash-reward-view.js')

const JSON_HEADER = { 'Content-Type': 'application/json' }

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'loading', // loading | ready | none | error | invalid
    offer: null,
    claiming: false,
    claimed: false,
    claimError: '',
  },
  onLoad(options) {
    const topicId = options && options.topicId
    if (!view.validTopicId(topicId)) { this.setData({ state: 'invalid' }); return }
    this._topicId = String(topicId)
    this.load()
  },
  retry() { this.load() },
  load() {
    const that = this
    const seq = (this._seq = (this._seq || 0) + 1)
    this.setData({ state: 'loading', claimError: '' })
    app.sendRequest({
      url: '/api/rewards/noncash/subjects/' + this._topicId, method: 'GET', hideLoading: true, silentError: true,
      success(res) {
        if (seq !== that._seq) return
        if (res && Number(res.code) === 404) { that.setData({ state: 'none' }); return }
        const catalog = isBizOk(res) ? view.normalizeCatalog(res.data) : null
        if (!catalog) { that.setData({ state: 'error' }); return }
        that._loadOffer(seq, catalog.versionId)
      },
      fail() { if (seq === that._seq) that.setData({ state: 'error' }) },
      successStatusAbnormal() { if (seq === that._seq) that.setData({ state: 'error' }) },
    })
  },
  _loadOffer(seq, versionId) {
    const that = this
    app.sendRequest({
      url: '/api/rewards/noncash/versions/' + encodeURIComponent(versionId), method: 'GET',
      data: { branch: view.BRANCH, subjectId: this._topicId }, hideLoading: true, silentError: true,
      success(res) {
        if (seq !== that._seq) return
        const offer = isBizOk(res) ? view.normalizeOffer(res.data) : null
        if (!offer) { that.setData({ state: res && Number(res.code) === 404 ? 'none' : 'error' }); return }
        that.setData({ state: 'ready', offer })
        that._resumePending(offer.versionId)
      },
      fail() { if (seq === that._seq) that.setData({ state: 'error' }) },
      successStatusAbnormal() { if (seq === that._seq) that.setData({ state: 'error' }) },
    })
  },
  // 上次领取的回包没收到:按原 requestId 回读,结果以服务端为准。
  _resumePending(versionId) {
    const key = view.pendingKey(versionId, this._topicId)
    let requestId = ''
    try { requestId = wx.getStorageSync(key) || '' } catch (e) { requestId = '' }
    if (!requestId) return
    this._post('/api/rewards/noncash/claims/recover', { requestId }, key)
  },
  claim() {
    const offer = this.data.offer
    if (!offer || !offer.claimable || this.data.claiming || this.data.claimed) return
    if (!app.ensureLoginFor('登录后领取奖励', () => this.claim())) return
    const key = view.pendingKey(offer.versionId, this._topicId)
    let requestId = ''
    try { requestId = wx.getStorageSync(key) || '' } catch (e) { requestId = '' }
    if (!requestId) {
      requestId = view.newRequestId()
      try { wx.setStorageSync(key, requestId) } catch (e) { /* 存不下也照常领取;服务端按业务键去重 */ }
    }
    this._post('/api/rewards/noncash/claims',
      { requestId, versionId: offer.versionId, branch: view.BRANCH, subjectId: this._topicId }, key)
  },
  _post(url, body, key) {
    const that = this
    this.setData({ claiming: true, claimError: '' })
    app.sendRequest({
      url, method: 'POST', header: JSON_HEADER, data: JSON.stringify(body), hideLoading: true, silentError: true,
      success(res) {
        if (!isBizOk(res)) {
          // 业务拒绝(4xx 码)是终态:清掉本地 requestId;msg 是契约码,转成文案。
          try { wx.removeStorageSync(key) } catch (e) {}
          that.setData({ claiming: false, claimError: view.offerBlockText(res && res.errorCode) || '领取未成功' })
          return
        }
        const outcome = view.claimOutcome(res.data)
        if (outcome.kind === 'awarded') {
          try { wx.removeStorageSync(key) } catch (e) {}
          that.setData({ claiming: false, claimed: true })
          cyToast('已领取，可在我的优惠券使用')
        } else if (outcome.kind === 'refused') {
          try { wx.removeStorageSync(key) } catch (e) {}
          that.setData({ claiming: false, claimError: outcome.text })
        } else {
          that.setData({ claiming: false, claimError: '结果确认中，稍后重新进入查看' })
        }
      },
      fail() { that.setData({ claiming: false, claimError: '网络异常，结果未确认，稍后重新进入查看' }) },
      successStatusAbnormal() { that.setData({ claiming: false, claimError: '结果未确认，稍后重新进入查看' }) },
    })
  },
  goRewards() {
    if (this._navigating) return
    this._navigating = true
    wx.navigateTo({ url: '/subpackageMember/rewards/index', complete: () => { this._navigating = false } })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/member/index/index' })
  },
})
