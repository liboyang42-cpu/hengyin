// §20.1 公开落地详情(UI-P3 合集里的单个作品):匿名只读 POST /api/public-catalog/info?id=&locale=。
// 只给章节名与首章前几站钩子;答案、付费正文、提示、核销码不进预览(服务端投影已裁)。
// 主按钮落到真实主题详情(原报名/购票链),这里不报价、不建单、不做到货提醒。
const app = getApp()
const catalog = require('../utils/public-catalog.js')

Page({
  data: {
    statusBarHeight: (app.globalData || {}).statusBarHeight || 20,
    navBarHeight: (app.globalData || {}).navBarHeight || 44,
    state: 'loading', // loading | ok | gone | error
    error: '',
    view: null
  },

  onLoad(options) {
    this._id = /^\d{1,19}$/.test(String(options.id || '')) ? String(options.id) : ''
    if (!this._id) { this.setData({ state: 'gone' }); return }
    this.load()
  },

  load() {
    const seq = this._seq = (this._seq || 0) + 1
    this.setData({ state: 'loading', error: '' })
    app.sendRequest({
      url: '/api/public-catalog/info', method: 'POST', data: { id: this._id, locale: 'zh-CN' }, hideLoading: true, silentError: true,
      success: (res) => this.settle(seq, res || {}),
      successStatusAbnormal: (res) => this.settle(seq, Object.assign({ httpFail: true }, res || {})),
      fail: () => this.settle(seq, { code: 'fail', netFail: true })
    })
  },

  settle(seq, res) {
    if (seq !== this._seq) return
    const state = catalog.detailState(res)
    const view = state === 'ok' ? catalog.detailView(res.data) : null
    if (state === 'ok' && view) { this.setData({ state: 'ok', view }); return }
    if (state === 'gone') { this.setData({ state: 'gone', view: null }); return }
    this.setData({ state: 'error', view: null, error: res.netFail ? '网络异常，请重试' : ((app.getRequestErrorMessage && app.getRequestErrorMessage(res, '作品加载失败')) || '作品加载失败') })
  },

  openTopic() {
    if (!this.data.view) return
    wx.navigateTo({ url: '/pages/topic/index/index?id=' + encodeURIComponent(this.data.view.id) })
  },

  openCity() {
    const v = this.data.view
    if (!v || !v.cityCode) return
    wx.navigateTo({ url: '/pages/topic/catalog/index?city_code=' + v.cityCode })
  },

  onShareAppMessage() {
    const v = this.data.view
    return { title: v ? v.title : '作品', path: '/pages/topic/catalog/detail?id=' + encodeURIComponent(this._id || '') }
  }
})
