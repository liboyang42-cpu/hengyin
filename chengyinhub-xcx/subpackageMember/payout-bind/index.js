'use strict'
// R6b money-payout(2026-11-15):App 里显示的收款绑定二维码,用微信扫一扫打开这里。
// 本页只证明「是这个微信」:绑定码 + 新鲜的 wx.login code 发给服务端;真正的绑定要回到 App 里确认。
// 资金开关关闭时服务端会拒绝,本页如实说明,不绑定任何东西。
const moneyPayout = require('../../utils/money-payout.js')

Page({
  data: {
    code: '',
    codeInput: '',
    busy: false,
    done: false,
    notice: '',
  },

  onLoad(options) {
    this.setData({ code: moneyPayout.bindCodeFromOptions(options) })
  },

  onUnload() { this._unloaded = true },

  onCodeInput(e) { this.setData({ codeInput: e.detail.value, notice: '' }) },

  claim() {
    const that = this
    if (this.data.busy || this.data.done) return
    const code = this.data.code || moneyPayout.normalizeCode(this.data.codeInput)
    if (!code) { this.setData({ notice: '绑定码不对，请在 App 里重新生成后再扫码' }); return }
    this.setData({ busy: true, notice: '' })
    wx.login({
      success(res) {
        if (that._unloaded) return
        if (!res || !res.code) { that.setData({ busy: false, notice: '微信登录失败，请重试' }); return }
        moneyPayout.claimBinding(code, res.code, function (err) {
          if (that._unloaded) return
          that.setData({ busy: false, done: !err, notice: err || '' })
        })
      },
      fail() {
        if (that._unloaded) return
        that.setData({ busy: false, notice: '微信登录失败，请重试' })
      }
    })
  },

  exitPage() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.reLaunch({ url: '/pages/index/index' })
  },
})
