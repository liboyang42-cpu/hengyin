'use strict'
// R6b money-payout(2026-11-15):提现申请 → 平台审核 → 经既有微信商家转账打到微信零钱。
// 只从「提现申请」页在资金开关「用户提现」打开时进入;服务端说关闭时,本页如实说明,不提交任何申请。
// 提交申请不动钱;只有平台审核通过、真正打款时才从余额扣除。
const cyToast = require('../../utils/toast.js')
const moneyPayout = require('../../utils/money-payout.js')

function money(value) { return Number(value || 0).toFixed(2) }

Page({
  data: {
    loaded: false,
    loadErr: '',
    open: false,
    view: null,
    amount: '',
    busy: false,
    notice: '',
  },

  onShow() {
    this._unloaded = false
    this.load()
  },

  onUnload() { this._unloaded = true },

  load() {
    const that = this
    moneyPayout.overview(function (err, data) {
      if (that._unloaded) return
      if (err || !data) { that.setData({ loaded: true, loadErr: err || '暂时取不到，请稍后再试', view: null }); return }
      const pending = (data.requests || []).find(function (r) { return r.status === 'PENDING' || r.status === 'PAYING' }) || null
      that.setData({
        loaded: true,
        loadErr: '',
        open: data.withdrawalOpen === true,
        view: {
          available: money(data.available),
          minAmount: String(data.minAmount || ''),
          maxAmount: String(data.maxAmount || ''),
          wechatReady: data.wechatReady === true,
          pendingId: pending ? pending.id : null,
          pendingAmount: pending ? money(pending.amount) : '',
          pendingStatus: pending ? pending.status : '',
          pendingText: pending ? moneyPayout.statusText(pending.status) : '',
          last: (data.requests || []).slice(0, 10).map(function (r) {
            const note = r.status === 'REJECTED' ? (r.reviewNote || '') : (r.status === 'FAILED' ? (r.failReason || '') : '')
            return { id: r.id, amount: money(r.amount), text: moneyPayout.statusText(r.status) + (note ? '：' + note : '') }
          })
        }
      })
    })
  },

  onAmount(e) { this.setData({ amount: e.detail.value, notice: '' }) },

  submit() {
    const that = this
    const view = this.data.view
    if (this.data.busy || !view || !this.data.open || view.pendingId || !view.wechatReady) return
    const amount = moneyPayout.normalizeAmount(this.data.amount)
    if (!amount) { this.setData({ notice: '请输入正确的提现金额' }); return }
    this.setData({ busy: true, notice: '' })
    moneyPayout.submit(amount, function (err) {
      if (that._unloaded) return
      that.setData({ busy: false, notice: err || '', amount: err ? that.data.amount : '' })
      if (!err) cyToast('提现申请已提交，等待审核')
      that.load()
    })
  },

  cancel() {
    const that = this
    const view = this.data.view
    if (this.data.busy || !view || !view.pendingId || view.pendingStatus !== 'PENDING') return
    this.setData({ busy: true, notice: '' })
    moneyPayout.cancel(view.pendingId, function (err) {
      if (that._unloaded) return
      that.setData({ busy: false, notice: err || '' })
      that.load()
    })
  },
})
