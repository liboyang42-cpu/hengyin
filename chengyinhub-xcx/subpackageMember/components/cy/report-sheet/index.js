// 共用举报半屏(BL-32 起;BL-33..36 复用):读原因 → 选原因 → 写说明 → 提交 → 回执。
// 只做纯文字举报;原因与规则版本只取后端 taxonomy,不在客户端写死。
// 网络结果未知时用同一 requestId 重试(后端同键同内容重放返回同一条举报,不会重复受理)。
const app = getApp()
const { isBizOk } = require('../../../../utils/response-shape.js')
const model = require('./model.js')

const NET_FAIL = '网络异常，暂时不知道举报是否已提交。再试一次不会重复提交。'

Component({
  properties: {
    show: { type: Boolean, value: false },
    targetType: { type: String, value: '' }, // MEMBER | MERCHANT | CLUB | ACTIVITY
    targetId: { type: String, value: '' },
    title: { type: String, value: '举报' },
  },
  data: {
    stage: 'loading', // loading | loadfail | pick | form | busy | ok | fail | unknown
    reasonLabels: [],
    reasonLabel: '',
    reasonResponseText: '',
    description: '',
    descriptionError: '',
    maxLength: model.DESCRIPTION_MAX,
    receipt: '',
    failText: '',
  },
  observers: {
    show(visible) { if (visible) this.reset() },
  },
  methods: {
    reset() {
      this._reasons = null
      this._reasonCode = ''
      this._requestId = ''
      this._busy = false
      this.setData({ stage: 'loading', reasonLabels: [], reasonLabel: '', reasonResponseText: '', description: '', descriptionError: '', receipt: '', failText: '' })
      this.loadTaxonomy()
    },
    loadTaxonomy() {
      // 关掉重开或重试后,先发后到的旧回包一律丢弃,不冲掉正在填的表单(BL-218)。
      const seq = this._taxSeq = (this._taxSeq || 0) + 1
      if (!model.validTarget(this.properties.targetType, this.properties.targetId)) {
        this.setData({ stage: 'loadfail', failText: '举报对象信息不完整，暂时无法举报' })
        return
      }
      this.setData({ stage: 'loading' })
      app.sendRequest({
        url: '/api/community-trust/reports/taxonomy', method: 'GET', hideLoading: true, silentError: true,
        success: (res) => {
          if (seq !== this._taxSeq) return
          const taxonomy = isBizOk(res) ? model.normalizeTaxonomy(res.data) : null
          if (!taxonomy) {
            this.setData({ stage: 'loadfail', failText: '举报原因没有读取成功，请稍后再试' })
            return
          }
          this._reasons = taxonomy.reasons
          this._policyVersion = taxonomy.policyVersion
          this.setData({ stage: 'pick', reasonLabels: taxonomy.reasons.map((r) => r.label) })
        },
        fail: () => { if (seq === this._taxSeq) this.setData({ stage: 'loadfail', failText: '网络异常，举报原因没有读取成功' }) },
        successStatusAbnormal: () => { if (seq === this._taxSeq) this.setData({ stage: 'loadfail', failText: '网络异常，举报原因没有读取成功' }) },
      })
    },
    onReasonSelect(e) {
      const index = Number(e.detail && e.detail.index)
      const reason = this._reasons && this._reasons[index]
      if (!reason) return
      if (reason.code !== this._reasonCode) this._requestId = ''
      this._reasonCode = reason.code
      this.setData({ stage: 'form', reasonLabel: reason.label, reasonResponseText: reason.responseText || '', descriptionError: '' })
    },
    onDescriptionInput(e) {
      const value = (e.detail && e.detail.value) || ''
      if (value !== this.data.description) this._requestId = ''
      this.setData({ description: value, descriptionError: '' })
    },
    backToPick() { if (!this._busy) this.setData({ stage: 'pick' }) },
    submit() {
      if (app.ensureLoginFor && !app.ensureLoginFor('report', () => this.submit())) return; // 2026-10-24 游客写前登录
      if (this._busy) return
      const checked = model.checkDescription(this.data.description)
      if (!checked.ok) { this.setData({ descriptionError: checked.error }); return }
      const targetType = this.properties.targetType
      const targetId = this.properties.targetId
      if (!this._requestId) this._requestId = model.makeRequestId(targetType, targetId)
      const payload = model.buildPayload({
        targetType, targetId, reasonCode: this._reasonCode, policyVersion: this._policyVersion,
        description: checked.value, requestId: this._requestId,
      })
      this._busy = true
      this.setData({ stage: 'busy' })
      app.sendRequest({
        url: '/api/community-trust/reports/submit', method: 'POST', hideLoading: true, silentError: true,
        data: JSON.stringify(payload),
        header: { 'Content-Type': 'application/json' },
        success: (res) => {
          this._busy = false
          if (!isBizOk(res)) {
            // 业务拒绝(原因/规则版本/不能举报自己等):换键,改完再提交是新请求。
            this._requestId = ''
            this.setData({ stage: 'fail', failText: (res && res.msg) || '举报没有提交成功，请稍后再试' })
            return
          }
          this._requestId = ''
          this.setData({ stage: 'ok', receipt: model.receiptId(res.data) })
        },
        fail: () => { this._busy = false; this.setData({ stage: 'unknown', failText: NET_FAIL }) },
        successStatusAbnormal: () => { this._busy = false; this.setData({ stage: 'unknown', failText: NET_FAIL }) },
      })
    },
    backToForm() { if (!this._busy) this.setData({ stage: 'form' }) },
    close() { if (!this._busy) { this._taxSeq = (this._taxSeq || 0) + 1; this.triggerEvent('close') } },
  },
})
