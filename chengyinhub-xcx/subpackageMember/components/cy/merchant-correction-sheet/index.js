// 商家信息纠错半屏(BL-67):读类型 → 选问题 → 写说明 → 提交 → 回执。结构照共用举报半屏。
// 只做纯文字问题上报;问题类型只取后端 taxonomy。网络结果未知时用同一幂等键重试
// (后端同键同内容重放返回同一条记录,不会重复受理)。
const app = getApp()
const { isBizOk } = require('../../../../utils/response-shape.js')
const model = require('./model.js')

const NET_FAIL = '网络不太稳定，暂时不知道纠错是否已提交。再试一次不会重复提交。'

Component({
  properties: {
    show: { type: Boolean, value: false },
    merchantId: { type: String, value: '' },
  },
  data: {
    stage: 'loading', // loading | loadfail | pick | form | busy | ok | fail | unknown
    issueLabels: [],
    issueLabel: '',
    description: '',
    descriptionError: '',
    maxLength: model.DESCRIPTION_MAX,
    receiptText: '',
    failText: '',
  },
  observers: {
    show(visible) { if (visible) this.reset() },
  },
  methods: {
    reset() {
      this._issues = null
      this._issueType = ''
      this._key = ''
      this._busy = false
      this.setData({ stage: 'loading', issueLabels: [], issueLabel: '', description: '', descriptionError: '', receiptText: '', failText: '' })
      this.loadTaxonomy()
    },
    loadTaxonomy() {
      if (!model.validMerchantId(this.properties.merchantId)) {
        this.setData({ stage: 'loadfail', failText: '商家信息不完整，暂时无法纠错' })
        return
      }
      this.setData({ stage: 'loading' })
      app.sendRequest({
        url: '/api/community-trust/contributions/taxonomy', method: 'GET', hideLoading: true, silentError: true,
        success: (res) => {
          const taxonomy = isBizOk(res) ? model.normalizeTaxonomy(res.data) : null
          if (!taxonomy) {
            this.setData({ stage: 'loadfail', failText: '纠错类型没有读取成功，请稍后再试' })
            return
          }
          this._issues = taxonomy.issues
          this.setData({ stage: 'pick', issueLabels: taxonomy.issues.map((item) => item.label) })
        },
        fail: () => { this.setData({ stage: 'loadfail', failText: '网络不太稳定，纠错类型没有读取成功' }) },
        successStatusAbnormal: () => { this.setData({ stage: 'loadfail', failText: '网络不太稳定，纠错类型没有读取成功' }) },
      })
    },
    onIssueSelect(e) {
      const index = Number(e.detail && e.detail.index)
      const issue = this._issues && this._issues[index]
      if (!issue) return
      if (issue.code !== this._issueType) this._key = ''
      this._issueType = issue.code
      this.setData({ stage: 'form', issueLabel: issue.label, descriptionError: '' })
    },
    onDescriptionInput(e) {
      const value = (e.detail && e.detail.value) || ''
      if (value !== this.data.description) this._key = ''
      this.setData({ description: value, descriptionError: '' })
    },
    backToPick() { if (!this._busy) this.setData({ stage: 'pick' }) },
    submit() {
      if (this._busy) return
      const checked = model.checkDescription(this.data.description)
      if (!checked.ok) { this.setData({ descriptionError: checked.error }); return }
      const merchantId = this.properties.merchantId
      if (!this._key) this._key = model.makeIdempotencyKey(merchantId)
      const payload = model.buildPayload({
        merchantId, issueType: this._issueType, description: checked.value, idempotencyKey: this._key,
      })
      this._busy = true
      this.setData({ stage: 'busy' })
      app.sendRequest({
        url: '/api/community-trust/contributions/submit', method: 'POST', hideLoading: true, silentError: true,
        data: JSON.stringify(payload),
        header: { 'Content-Type': 'application/json' },
        success: (res) => {
          this._busy = false
          if (!isBizOk(res)) {
            // 业务拒绝(已提交过该商家/类型不合法等):换键,改完再提交是新请求;原话展示。
            this._key = ''
            this.setData({ stage: 'fail', failText: (res && res.msg) || '纠错没有提交成功，请稍后再试' })
            return
          }
          this._key = ''
          const receipt = model.receiptOf(res.data)
          const parts = []
          if (receipt.id) parts.push('受理编号 ' + receipt.id)
          if (receipt.statusText) parts.push('当前状态：' + receipt.statusText)
          this.setData({ stage: 'ok', receiptText: parts.length ? parts.join('，') + '。' : '' })
        },
        fail: () => { this._busy = false; this.setData({ stage: 'unknown', failText: NET_FAIL }) },
        successStatusAbnormal: () => { this._busy = false; this.setData({ stage: 'unknown', failText: NET_FAIL }) },
      })
    },
    backToForm() { if (!this._busy) this.setData({ stage: 'form' }) },
    close() { if (!this._busy) this.triggerEvent('close') },
    // BL-184:成功态唯一次按钮,进入「我的纠错」看审核进度
    openMine() {
      this.triggerEvent('close')
      wx.navigateTo({ url: '/subpackageMember/correction-mine/index' })
    },
  },
})
