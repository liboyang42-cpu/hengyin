'use strict'

const app = getApp()
const toast = require('../../../utils/toast.js')
const { captureLogin, loginCurrent, seatChoice, issuedSeat, seatNumber } = require('../../../utils/registration-seat.js')
const { passExpiryText } = require('../../../utils/ticket-expiry.js')
// V-02(2026-10-29 业主):与票夹背面同一版式与规则 —— 只有标题 + 码(码心城瘾 logo)，7 天内到期才写「N 天内过期」；
// 不显示倒计时、原始码串和说明，动态码到期前 30 秒后台静默续签，续签期间旧码照常出示。
const REFRESH_LEAD_MS = 30000
const RETRY_MS = 5000

Component({
  properties: {
    registrationId: { type: String, value: '' },
    title: { type: String, value: '' },
    until: { type: null, value: '' },
  },
  data: {
    state: 'loading',
    qr: '',
    errorText: '',
    expiry: '',
    seatNo: '', seatInput: '', seatCount: 0, seatVerified: false,
  },
  observers: {
    registrationId(value) {
      if (!this._attached) return
      if (!value) {
        this._loadedRegistrationId = ''
        this._loadToken = (this._loadToken || 0) + 1
        this.clearTimer()
        this.setData({ state: 'error', qr: '', errorText: '缺少报名记录，无法生成入场码' })
        return
      }
      if (value !== this._loadedRegistrationId) this.load()
    },
    until(value) { this.setData({ expiry: passExpiryText(value) }) },
  },
  lifetimes: {
    attached() {
      this._attached = true
      this.setData({ expiry: passExpiryText(this.data.until) })
      if (this.data.registrationId) this.load()
      else this.setData({ state: 'error', errorText: '缺少报名记录，无法生成入场码' })
    },
    detached() {
      this._attached = false
      this._loadToken = (this._loadToken || 0) + 1
      this.clearTimer()
    },
  },
  methods: {
    clearTimer() {
      if (this._timer) { clearInterval(this._timer); this._timer = null }
      this.stopRedeemWatch()
    },
    stopRedeemWatch() {
      if (this._redeemPoll) { clearInterval(this._redeemPoll); this._redeemPoll = null }
    },
    /** 码亮着时轻轮询探店完局面:商家一扫,玩家这边当场看到「已核销」并让游玩页重读进度(实走 B6)。
     *  非探店票这个接口直接拒,第一次失败就停,不打扰别的票种。 */
    watchRedeem(registrationId) {
      let baseline = null
      const poll = () => {
        if (this._ticketCurrent && !this._ticketCurrent()) { this.clearTimer(); return }
        app.sendRequest({
          url: '/api/registration/explore-completion', method: 'POST', data: { id: registrationId },
          hideLoading: true, silentError: true,
          // 轮询只是增强:非探店票被拒 / 断网都停掉轮询,码照常可扫,不打扰玩家
          fail: () => this.stopRedeemWatch(),
          successStatusAbnormal: () => this.stopRedeemWatch(),
          success: (res) => {
            if (!(res && (res.code === 200 || res.code === '200') && res.data)) { this.stopRedeemWatch(); return }
            const signature = JSON.stringify(res.data)
            if (baseline === null) { baseline = signature; return }
            if (signature !== baseline) {
              baseline = signature
              toast('商家已核销')
              this.triggerEvent('redeemed', { registrationId })
            }
          },
        })
      }
      poll()
      this._redeemPoll = setInterval(poll, 4000)
    },
    load(silent) {
      const registrationId = this.data.registrationId
      if (!registrationId) {
        this.setData({ state: 'error', errorText: '缺少报名记录，无法生成入场码' })
        return
      }
      if (!this._ticketLogin) this._ticketLogin = captureLogin(app, wx)
      if (!loginCurrent(app, wx, this._ticketLogin)) {
        this.clearTimer()
        this.setData({ state: 'error', qr: '', errorText: '登录状态已变化，请重新打开票券' })
        return
      }
      if (String(this._loadedRegistrationId) !== String(registrationId)) {
        this.setData({ seatNo: '', seatInput: '', seatCount: 0, seatVerified: false })
      }
      this._loadedRegistrationId = registrationId
      const seatNo = this.data.seatNo
      const token = (this._loadToken || 0) + 1
      this._loadToken = token
      const current = () => this._attached !== false && token === this._loadToken
        && String(this.data.registrationId) === String(registrationId) && this.data.seatNo === seatNo
        && loginCurrent(app, wx, this._ticketLogin)
      this._ticketCurrent = current
      // 静默续签:旧码与到期计时都留着;续签没成功而旧码还没到期就稍后再试,真过期由 tick 落错误态。
      const keepOld = () => {
        if (!silent || !(this._expiresAt > Date.now())) return false
        this._refreshing = false; this._retryAt = Date.now() + RETRY_MS
        return true
      }
      if (silent) this._refreshing = true
      else {
        this.clearTimer(); this._refreshing = false
        this.setData({ state: 'loading', qr: '', errorText: '' })
      }
      app.sendRequest({
        url: '/api/verify/dyncode/issue',
        method: 'POST',
        data: seatNo ? { registrationId, seatNo } : { registrationId },
        hideLoading: true,
        silentError: true,
        success: (res) => {
          if (!current()) return
          if (!(res && (res.code === 200 || res.code === '200') && res.data)) {
            if (keepOld()) return
            this.setData({ state: 'error', errorText: (res && res.msg) || '入场码生成失败' })
            return
          }
          const payload = res.data
          if (silent && (seatChoice(payload, registrationId) || !payload.qrcodeUrl) && keepOld()) return
          if (seatChoice(payload, registrationId)) {
            this.setData({ state: 'choose', seatCount: Number(payload.seatCount), qr: '', errorText: '' })
            return
          }
          if (seatNo && !issuedSeat(payload, registrationId, seatNo)) {
            this.setData({ state: 'error', qr: '', errorText: '原席位身份无法确认，请重新打开票券' })
            return
          }
          if (!payload.qrcodeUrl) {
            this.setData({ state: 'error', errorText: '入场码生成失败' })
            return
          }
          const rawExpiresAt = payload.expiresAt
          const expiresAt = typeof rawExpiresAt === 'number' && Number.isFinite(rawExpiresAt)
            ? (rawExpiresAt < 100000000000 ? rawExpiresAt * 1000 : rawExpiresAt)
            : 0
          if (!(expiresAt > Date.now())) {
            if (keepOld()) return
            this.setData({ state: 'error', qr: '', errorText: '入场码有效期暂时无法确认，请重新获取' })
            return
          }
          this.clearTimer()
          this._expiresAt = expiresAt; this._refreshing = false; this._retryAt = 0
          this.setData({ state: 'ready', qr: payload.qrcodeUrl, seatVerified: payload.seatStatus === 1 })
          this._timer = setInterval(() => this.tick(), 1000)
          this.watchRedeem(registrationId)
        },
        fail: () => {
          if (current() && !keepOld()) this.setData({ state: 'error', errorText: '网络没连上，请重试' })
        },
        successStatusAbnormal: () => {
          if (current() && !keepOld()) this.setData({ state: 'error', errorText: '入场码服务暂不可用，请重试' })
        },
      })
    },
    inputSeat(event) { this.setData({ seatInput: String(event.detail.value || ''), errorText: '' }) },
    confirmSeat() {
      if (this.data.state !== 'choose' || !loginCurrent(app, wx, this._ticketLogin)) return
      const value = this.data.seatInput
      if (!seatNumber(value) || Number(value) > this.data.seatCount) {
        this.setData({ errorText: '请输入 1 至 ' + this.data.seatCount + ' 的席位号' })
        return
      }
      this.setData({ seatNo: Number(value) })
      this.load()
    },
    changeSeat() {
      if (!this.data.seatCount || !loginCurrent(app, wx, this._ticketLogin)) return
      this._loadToken = (this._loadToken || 0) + 1
      this.clearTimer()
      this.setData({ state: 'choose', seatNo: '', seatInput: '', qr: '', errorText: '' })
    },
    tick() {
      if (this._ticketCurrent && !this._ticketCurrent()) { this.clearTimer(); return }
      const now = Date.now()
      const left = Math.max(0, (this._expiresAt || 0) - now)
      if (!left) {
        this.clearTimer()
        this.setData({ state: 'error', qr: '', errorText: '入场码已过期，请重新获取' })
        return
      }
      if (left <= REFRESH_LEAD_MS && !this._refreshing && now >= (this._retryAt || 0)) this.load(true)
    },
    retry() { this.load() },
    close() { this.triggerEvent('close') },
  },
})
