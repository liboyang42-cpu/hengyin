'use strict'

const toast = require('../../../utils/toast.js');
const app = getApp()
const { recoverReadSession } = require('../../../utils/session/login-reload.js')
const roleGuard = require('../../../utils/roleGuard.js')
const motion = require('../../../utils/motion.js')
const { readReducedMotion } = require('../../../utils/motion-preference.js')

function identityKey() {
  const gd = app.globalData || {}
  const member = typeof app.getUserID === 'function' ? app.getUserID() : gd.user_id
  const role = typeof app.getUserRole === 'function' ? app.getUserRole() : gd.role
  const type = typeof app.getUserType === 'function' ? app.getUserType() : gd.user_type
  return [member == null ? '' : member, role || '', type == null ? '' : type].join('|')
}

function readBalance(raw) {
  const candidate = typeof raw === 'number' || (typeof raw === 'string' && raw.trim() !== '')
  if (!candidate) return null
  const value = Number(raw)
  return Number.isFinite(value) && value >= 0 ? value.toFixed(2) : null
}

/** 余额字段「缺失」:库列可空,新用户/还没收益时后端直接下发 null 或空串 —— 正常数据,不是错误。
 *  ⚠️ 只认缺失;负数/非数字这类坏值仍走 error,不摆伪 ¥0.00(见 readBalance)。 */
function isMissingBalance(raw) {
  return raw === null || raw === undefined || (typeof raw === 'string' && raw.trim() === '')
}

Component({
  properties: {
    theme: { type: String, value: 'player' },
  },
  data: {
    state: 'loading',
    balance: '',
    /* 金额滚动展示值:countUp 只写这里,balance 保持真值(空态判断/全部提现都读真值) */
    balanceDisplay: '',
    // 2026-10-28 G-9 Revolut 版:提现 / 明细 / 分析 / 更多;本月收入与我的资产由 cy-earnings-overview 自读
    moreShow: false,
  },
  lifetimes: {
    attached() { this._detached = false; this._hidden = false; this.load(false) },
    detached() {
      this._detached = true
      this._loadToken = (this._loadToken || 0) + 1
      if (this._stopCountUp) { this._stopCountUp(); this._stopCountUp = null }
      this._clearBalance()
    },
  },
  pageLifetimes: {
    show() {
      const refresh = this._hidden || this._loadIdentity !== identityKey()
      this._hidden = false
      if (refresh) this.load(true)
    },
    hide() {
      this._hidden = true
      this._loadToken = (this._loadToken || 0) + 1
      this._clearBalance()
    },
  },
  methods: {
    _clearBalance() {
      if (this._stopCountUp) { this._stopCountUp(); this._stopCountUp = null }
      this._dataIdentity = null
      this.setData({ balance: '', balanceDisplay: '' })
    },
    _recoverReadSession(res, statusCode, token) {
      if (this._detached || token !== this._loadToken) return false
      return recoverReadSession(this, app, res, function (anonymous) {
        this._loadToken++; this._clearBalance(); this.setData({ state: anonymous ? 'login' : 'loading' })
      }, () => this.load(true), statusCode)
    },
    _acceptRead(token) {
      if (!Number.isInteger(token) || token !== this._loadToken || this._detached || this._hidden) return false
      const current = identityKey()
      if (current === this._loadIdentity) return true
      const previousMember = this._loadIdentity.split('|')[0]
      const currentMember = current.split('|')[0]
      this._loadToken += 1
      this._clearBalance()
      this.setData({ state: 'error' })
      if ((!previousMember || previousMember === '0') && currentMember && currentMember !== '0') this.load(true)
      return false
    },
    _hasCurrentBalance() {
      if (this._detached || this._hidden) return false
      if (this._dataIdentity !== identityKey()) {
        if (this._dataIdentity != null) {
          this._loadToken = (this._loadToken || 0) + 1
          this._clearBalance()
          this.setData({ state: 'error' })
        }
        return false
      }
      return this.data.state === 'ready'
    },
    load(forceRemote) {
      const token = (this._loadToken || 0) + 1
      this._loadToken = token
      this._loadIdentity = identityKey()
      this._clearBalance()
      this.setData({ state: 'loading' })
      const userInfo = (app.globalData || {}).userInfo
      const member = this._loadIdentity.split('|')[0]
      if (!member || member === '0') { this.setData({ state: 'login' }); return }
      const ownedCache = userInfo && Number(member) > 0 && String(userInfo.id) === member
      const cached = !forceRemote && ownedCache ? readBalance(userInfo.balance) : null
      if (cached !== null && cached !== false) {
        this._showBalance(cached, token)
        return
      }
      app.sendRequest({
        hideLoading: true,
        silentError: true,
        url: '/api/user/info',
        method: 'POST',
        data: { member_id: app.getUserID() },
        success: (res) => {
          if (!this._acceptRead(token)) return
          // balance=null/空串是「新用户 / 商家还没有收益」的正常数据(ApiUmsMemberController 的
          // balance 列可空,直接下发 null),按 0.00 展示;只有请求真的失败(非 200 / 无 data)
          // 或余额是坏值,才进错误态。
          if (!res || res.code != '200' || !res.data) { this.setData({ state: this._readFailureState(res) }); return }
          const balance = readBalance(res.data.balance)
          if (balance !== null) this._showBalance(balance, token)
          else if (isMissingBalance(res.data.balance)) this._showBalance('0.00', token)
          else this.setData({ state: 'error' })
        },
        fail: (res, statusCode) => { if (!this._recoverReadSession(res, statusCode, token) && this._acceptRead(token)) this.setData({ state: this._readFailureState(res, statusCode) }) },
        successStatusAbnormal: (res, statusCode) => { if (!this._recoverReadSession(res, statusCode, token) && this._acceptRead(token)) this.setData({ state: this._readFailureState(res, statusCode) }) },
      })
    },
    /* 金额落定时从 0 滚到真值(220ms);末帧被 countUp 钉死在 to,不会显示错。
     * 减动效 / 非正数直接落定不滚。 */
    _showBalance(balance, token) {
      if (!this._acceptRead(token)) return
      this._dataIdentity = this._loadIdentity
      if (this._stopCountUp) { this._stopCountUp(); this._stopCountUp = null }
      const target = Number(balance)
      if (readReducedMotion() || !(target > 0)) {
        this.setData({ state: 'ready', balance, balanceDisplay: balance })
        return
      }
      /* 起点显式归零 —— 否则 refresh 时 state 转 ready 到首帧之间那一拍会闪出上次的金额 */
      this.setData({ state: 'ready', balance, balanceDisplay: '0.00' })
      this._stopCountUp = motion.countUp(0, target, (v) => {
        if (this._acceptRead(token)) this.setData({ balanceDisplay: v.toFixed(2) })
      }, { decimals: 2, onDone: () => { if (this._acceptRead(token)) this._stopCountUp = null } })
    },
    openAnalysis() { if (!this._hasCurrentBalance()) return; wx.navigateTo({ url: '/subpackageA/pages/assetcenter/analysis/index' + (this.data.theme === 'merchant' ? '?theme=merchant' : '') }) },
    openMore() { if (this._hasCurrentBalance()) this.setData({ moreShow: true }) },
    closeMore() { this.setData({ moreShow: false }) },
    openSettlement() { if (!this._hasCurrentBalance()) return; this.setData({ moreShow: false }); wx.navigateTo({ url: '/pages/merchant/ledger/index?view=settlement' }) },
    _readFailureState(res, statusCode) {
      const code = Number(statusCode || res && (res.code || res.statusCode))
      return code === 401 ? 'login' : code === 403 ? 'permission' : 'error'
    },
    refresh() {
      if (this.data.state !== 'login') { this.load(true); return }
      if (this._loginPending) return
      this._loginPending = true
      const member = identityKey().split('|')[0]
      const login = member && member !== '0' && typeof app.reLogin === 'function' ? app.reLogin()
        : typeof app.firstLogin === 'function' ? app.firstLogin() : null
      Promise.resolve(login).then(result => {
        this._loginPending = false
        if (!this._detached && !this._hidden && app.getUserID && app.getUserID() && !(result && result.ok === false)) this.load(true)
      }).catch(() => { this._loginPending = false })
    },
    // 2026-08-11 用户裁决:收益明细放到页面上,不再开弹层
    openIncomeDetail() { if (!this._hasCurrentBalance()) return; wx.navigateTo({ url: '/subpackageA/pages/assetcenter/income-detail/income-detail' }) },
    openWithdrawHistory() { if (!this._hasCurrentBalance()) return; this.triggerEvent('open', { id: 'member-withdraw-history' }) },
    openWithdraw() {
      if (!this._hasCurrentBalance()) return
      const token = this._loadToken
      roleGuard.load(() => {
        if (token !== this._loadToken || !this._hasCurrentBalance()) return
        if (!roleGuard.can('withdrawable')) {
          toast('暂无提现权限')
          return
        }
        this.triggerEvent('open', { id: 'member-withdraw' })
      })
    },
    openInvite() { if (!this._hasCurrentBalance()) return; this.triggerEvent('open', { id: 'member-invite-history' }) },
    goPublish() { if (!this._hasCurrentBalance()) return; wx.switchTab({ url: '/pages/template/index' }) },
  },
})
