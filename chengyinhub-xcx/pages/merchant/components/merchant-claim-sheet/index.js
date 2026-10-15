const claim = require('../../../../utils/merchant-claim.js')

const isOk = (res) => !!res && (res.code === 200 || res.code === '200')

Component({
  properties: {
    show: { type: Boolean, value: false },
  },
  data: {
    // loading | error | ready(按 view.state 渲染四态)
    phase: 'loading',
    loadError: '',
    view: { state: 'none', title: '', desc: '', reason: '', merchantId: null },
    keyword: '',
    searching: false,
    searched: false,
    candidates: [],
    picked: null,
    contactName: '',
    contactPhone: '',
    statement: '',
    proofUrls: [],
    submitting: false,
    formError: '',
  },
  observers: {
    show(v) { if (v) this.loadStatus() },
  },
  lifetimes: {
    detached() { this._epoch = (this._epoch || 0) + 1 },
  },
  methods: {
    loadStatus() {
      const epoch = this._epoch = (this._epoch || 0) + 1
      this.setData({ phase: 'loading', loadError: '' })
      getApp().sendRequest({
        url: '/api/merchant/claims/status', method: 'GET', hideLoading: true, silentError: true,
        success: (res) => {
          if (epoch !== this._epoch) return
          if (!isOk(res) || !Array.isArray(res.data)) {
            this.setData({ phase: 'error', loadError: (res && res.msg) || '认领状态暂时读不到，请重试' })
            return
          }
          this.setData({ phase: 'ready', view: claim.describe(res.data) })
        },
        fail: () => { if (epoch === this._epoch) this.setData({ phase: 'error', loadError: '网络异常，请重试' }) },
        successStatusAbnormal: (res) => {
          if (epoch === this._epoch) this.setData({ phase: 'error', loadError: (res && res.msg) || '认领状态暂时读不到，请重试' })
        },
      })
    },
    onClose() { this.triggerEvent('close') },
    onKeyword(e) { this.setData({ keyword: e.detail.value }) },
    onContactName(e) { this.setData({ contactName: e.detail.value, formError: '' }) },
    onContactPhone(e) { this.setData({ contactPhone: e.detail.value, formError: '' }) },
    onStatement(e) { this.setData({ statement: e.detail.value, formError: '' }) },

    // 公开门店搜索(与发现页同一接口,只返回已审核通过的门店)
    search() {
      const kw = String(this.data.keyword || '').trim()
      if (!kw || this.data.searching) return
      const epoch = this._searchEpoch = (this._searchEpoch || 0) + 1
      this.setData({ searching: true, searched: false })
      const done = (candidates) => {
        if (epoch !== this._searchEpoch) return
        this.setData({ searching: false, searched: true, candidates })
      }
      getApp().sendRequest({
        url: '/api/merchant/list', method: 'POST', data: JSON.stringify({ name: kw }),
        header: { 'Content-Type': 'application/json' }, hideLoading: true, silentError: true,
        success: (res) => {
          const rows = res && res.data && Array.isArray(res.data.rows) ? res.data.rows : (res && Array.isArray(res.data) ? res.data : [])
          done(isOk(res) ? rows.filter((r) => r && r.id).slice(0, 10).map((r) => ({ id: r.id, name: r.name || '未命名门店' })) : [])
        },
        fail: () => done([]),
        successStatusAbnormal: () => done([]),
      })
    },
    pick(e) {
      const id = e.currentTarget.dataset.id
      const hit = this.data.candidates.filter((c) => String(c.id) === String(id))[0]
      if (!hit) return
      this._requestId = claim.newRequestId()
      this.setData({ picked: hit, formError: '' })
    },
    clearPick() { this.setData({ picked: null }) },
    addProof() {
      const left = claim.MAX_PROOFS - this.data.proofUrls.length
      if (left <= 0) return
      getApp().chooseImage((urls) => {
        if (urls && urls.length) this.setData({ proofUrls: this.data.proofUrls.concat(urls).slice(0, claim.MAX_PROOFS), formError: '' })
      }, Math.min(left, 6))
    },
    removeProof(e) {
      const arr = this.data.proofUrls.slice()
      arr.splice(e.currentTarget.dataset.i, 1)
      this.setData({ proofUrls: arr })
    },
    restart() { this.setData({ view: Object.assign({}, this.data.view, { state: 'none' }) }) },
    retry() { this.loadStatus() },

    submit() {
      if (this.data.submitting) return
      const d = this.data
      if (!this._requestId) this._requestId = claim.newRequestId()
      const form = { merchantId: d.picked && d.picked.id, requestId: this._requestId, contactName: d.contactName, contactPhone: d.contactPhone, statement: d.statement, proofUrls: d.proofUrls }
      const problem = claim.validate(form)
      if (problem) { this.setData({ formError: problem }); return }
      this.setData({ submitting: true, formError: '' })
      const settle = (patch) => this.setData(Object.assign({ submitting: false }, patch))
      getApp().sendRequest({
        url: '/api/merchant/claims/submit', method: 'POST', data: JSON.stringify(claim.buildSubmitBody(form)),
        header: { 'Content-Type': 'application/json' }, hideLoading: true, silentError: true,
        success: (res) => {
          if (isOk(res)) { this._requestId = ''; settle({}); this.loadStatus(); return }
          settle({ formError: claim.submitErrorMessage(res) })
        },
        fail: () => settle({ formError: '网络异常，请重试（同一份申请不会重复提交）' }),
        successStatusAbnormal: (res) => settle({ formError: claim.submitErrorMessage(res) }),
      })
    },
  },
})
