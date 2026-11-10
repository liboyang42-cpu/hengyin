// §19/§27.10 个人分佣(qx-b5):收益明细页「分佣」页签的正文。
// 与 earnings-overview 同一做法:组件自己读本人分项,页面只挂组件(scene-twin 单一真源:页面不自绘列表、不自发请求)。
// 空列表 / 非参与人 ⇒ state=hidden,页面不出页签;读取失败 ⇒ state=error,只写「收益暂不可用」,不填 0。
const { isBizOk } = require('../../../utils/response-shape.js')
const { revenueView } = require('../../utils/revenue-view.js')
const app = getApp()

Component({
  properties: { active: { type: Boolean, value: false } },
  data: { rev: { state: 'hidden', rows: [] } },
  lifetimes: {
    attached() { this.loadRevenue() },
    detached() { this._revSeq = (this._revSeq || 0) + 1 }
  },
  methods: {
    loadRevenue() {
      const seq = (this._revSeq = (this._revSeq || 0) + 1)
      app.sendRequest({
        url: '/api/marketing/revenue/author/mine', method: 'GET', hideLoading: true, autoErrorToast: false,
        success: (res) => {
          if (seq !== this._revSeq) return
          if (res && res.reasonCode === 'NOT_PARTICIPANT') { this._set({ state: 'hidden', rows: [] }); return }
          const v = revenueView(isBizOk(res) ? res.data : null)
          this._set(v.state === 'empty' ? { state: 'hidden', rows: [] } : v)
        },
        fail: () => { if (seq === this._revSeq) this._set({ state: 'error', rows: [] }) }
      })
    },
    _set(rev) {
      this.setData({ rev })
      this.triggerEvent('state', { state: rev.state })
    }
  }
})
