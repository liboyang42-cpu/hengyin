'use strict'
// 提现渠道与原申请只读卡：渠道/可提现额/授权版本/最近申请都以 /api/withdrawal/summary 为准，
// 不在前端拿总余额冒充可提现额，也不提供任何提现写入口。
const app = getApp()
const { summaryView } = require('./view-model.js')

Component({
  data: { state: 'loading', view: null },
  lifetimes: {
    attached() { this._attached = true; this.load() },
    detached() { this._attached = false; this._epoch = (this._epoch || 0) + 1 },
  },
  methods: {
    load() {
      const epoch = this._epoch = (this._epoch || 0) + 1
      const memberId = String(app.getUserID())
      this.setData({ state: 'loading', view: null })
      if (!Number(memberId)) { this.setData({ state: 'error' }); return }
      const current = () => this._attached && epoch === this._epoch && memberId === String(app.getUserID())
      const showError = () => { if (current()) this.setData({ state: 'error', view: null }) }
      app.sendRequest({
        hideLoading: true,
        silentError: true,
        url: '/api/withdrawal/summary',
        method: 'POST',
        success: (res) => {
          if (!current()) return
          const view = summaryView(res)
          this.setData({ state: view ? 'ready' : 'error', view })
        },
        fail: () => showError(),
        successStatusAbnormal: showError,
      })
    },
  },
})
