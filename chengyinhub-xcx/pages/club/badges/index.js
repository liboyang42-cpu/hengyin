// 俱乐部勋章(N2):主理人自建勋章 → 列表 → 颁给成员。
// 合同见 pages/club/utils/club-badge.js 顶部注释。权限只按服务端口径:
// create/grant 仅主理人(/api/club/detail 的 isOwner);其他人只读列表,不渲染任何管理控件。
const toast = require('../../../utils/toast.js')
const clubBadge = require('../utils/club-badge.js')
const { isBizOk } = require('../../../utils/response-shape.js')
const app = getApp()

function jsonBody(data) { return JSON.stringify(data || {}) }
function jsonHeader() { return { 'Content-Type': 'application/json' } }
function isSuccess(res) { return isBizOk(res) }
function emptyForm() { return { name: '', desc: '' } }
// 账号世代:同一页面实例里换号(A 登出 → B 登录)时,旧身份/名单/弹层/草稿与迟到响应都不能带给 B。
function accountKey() { return String((app.getUserID && app.getUserID()) || '') }

Page({
  data: {
    statusBarHeight: (app.globalData || {}).statusBarHeight || 20,
    navBarHeight: (app.globalData || {}).navBarHeight || 44,
    clubName: '',
    isOwner: false,
    // loading | ready | empty | error | missing-param
    state: 'loading',
    errorKind: 'data',
    errorText: '',
    refreshing: false,
    refreshError: '',
    badges: [],
    nameMax: clubBadge.NAME_MAX,
    descMax: clubBadge.DESC_MAX,
    // 创建
    createVisible: false,
    form: emptyForm(),
    formErrors: {},
    creating: false,
    createError: '',
    // 创建结果未知(断网/5xx):后端 /badge/create 无幂等键,未回读确认前禁止再次提交
    createUnknown: false,
    // 颁发
    grantBadge: null,
    memberPickerVisible: false,
    membersState: 'idle',   // idle | loading | ready | empty | error
    membersError: '',
    members: [],
    granting: false,
  },

  onLoad(options) {
    const clubId = clubBadge.positiveId(options && (options.clubId || options.id))
    this._unloaded = false
    this._account = accountKey()
    if (!clubId) {
      this.setData({ state: 'missing-param' })
      return
    }
    this._clubId = clubId
    this.loadAll()
  },

  onUnload() { this._unloaded = true },

  onShow() {
    if (this._clubId && this._account !== undefined && accountKey() !== this._account) this.switchAccount()
  },

  /** 响应回来时账号已变:丢弃该响应;若本页还没按新账号重置过,就地重置并重读身份。 */
  accountStale(acct) {
    if (acct === this._account && acct === accountKey()) return false
    if (accountKey() !== this._account) this.switchAccount()
    return true
  },

  switchAccount() {
    this._account = accountKey()
    this._loadCycle = (this._loadCycle || 0) + 1
    this._expectBadgeCode = null
    this._createUnknown = null
    this._pendingGrant = null
    this.closeDangerConfirm()
    this.setData({
      isOwner: false,
      state: 'loading',
      refreshing: false,
      refreshError: '',
      badges: [],
      createVisible: false,
      form: emptyForm(),
      formErrors: {},
      creating: false,
      createError: '',
      createUnknown: false,
      grantBadge: null,
      memberPickerVisible: false,
      membersState: 'idle',
      membersError: '',
      members: [],
      granting: false,
    })
    this.loadAll()
  },

  closeDangerConfirm() {
    const dc = this.selectComponent && this.selectComponent('#dc')
    if (dc && dc.close) dc.close()
  },

  /** 不再是主理人(刷新后失权/身份读不到):收起所有管理弹层并清掉待执行的颁发。 */
  applyOwnership(isOwner, clubName) {
    if (clubName !== undefined) this.setData({ clubName })
    if (isOwner) {
      this.setData({ isOwner: true })
      return
    }
    this._pendingGrant = null
    this.closeDangerConfirm()
    this.setData({ isOwner: false, createVisible: false, memberPickerVisible: false, grantBadge: null })
  },

  onPullDownRefresh() { if (this._clubId) this.loadAll(); else wx.stopPullDownRefresh() },

  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 })
    else wx.switchTab({ url: '/pages/talent/list/index' })
  },

  retryLoad() { this.loadAll() },

  // 主理人身份与勋章列表各取一次;身份读不到时按「非主理人」处理 —— 宁可少给控件也不多给。
  loadAll() {
    if (!this._clubId) return
    const cycle = (this._loadCycle || 0) + 1
    this._loadCycle = cycle
    const acct = this._account
    const hadData = this.data.state === 'ready' || this.data.state === 'empty'
    this.setData({ refreshing: hadData, refreshError: '', state: hadData ? this.data.state : 'loading' })
    // 选人半屏没开、也不在颁发中:作废成员名单,下次「颁发」重读(新入团的能选到、已退团的不再出现)
    if (!this.data.memberPickerVisible && !this.data.granting) {
      this._membersSeq = (this._membersSeq || 0) + 1
      this.setData({ membersState: 'idle', members: [] })
    }
    let pending = 2
    const finish = () => {
      pending -= 1
      if (pending === 0) wx.stopPullDownRefresh && wx.stopPullDownRefresh()
    }
    this.loadClub(cycle, acct, finish)
    this.loadBadges(cycle, acct, hadData, finish)
  },

  loadClub(cycle, acct, done) {
    const that = this
    const stale = () => that._unloaded || that.accountStale(acct) || cycle !== that._loadCycle
    app.sendRequest({
      hideLoading: true,
      autoErrorToast: false,
      url: '/api/club/detail',
      method: 'POST',
      data: jsonBody({ id: this._clubId }),
      header: jsonHeader(),
      success(res) {
        if (stale()) return
        const club = isSuccess(res) && res.data && typeof res.data === 'object' ? res.data : null
        that.applyOwnership(!!(club && club.isOwner === true),
          club && typeof club.name === 'string' ? club.name : undefined)
      },
      fail() {
        if (stale()) return
        that.applyOwnership(false)
      },
      successStatusAbnormal() {
        if (stale()) return
        that.applyOwnership(false)
      },
      complete() { done() },
    })
  },

  loadBadges(cycle, acct, hadData, done) {
    const that = this
    const stale = () => that._unloaded || that.accountStale(acct) || cycle !== that._loadCycle
    const onFailure = (kind, text) => {
      if (stale()) return
      if (hadData) {
        that.setData({ refreshing: false, refreshError: text })
      } else {
        that.setData({ state: 'error', errorKind: kind, errorText: text, refreshing: false })
      }
    }
    app.sendRequest({
      hideLoading: true,
      autoErrorToast: false,
      url: '/api/club/badge/list',
      method: 'POST',
      data: jsonBody({ id: this._clubId }),
      header: jsonHeader(),
      success(res) {
        if (stale()) return
        if (!isSuccess(res) || !Array.isArray(res.data)) {
          onFailure('data', (res && res.msg) || '勋章列表暂时没读到')
          return
        }
        const badges = clubBadge.normalizeBadges(res.data)
        that.setData({
          badges,
          state: badges.length ? 'ready' : 'empty',
          refreshing: false,
          refreshError: '',
        })
        if (that._expectBadgeCode) that.confirmCreatedVisible(badges)
        if (that._createUnknown) that.reconcileUnknownCreate(badges)
      },
      fail() { onFailure('network', '检查网络连接后重试') },
      successStatusAbnormal() { onFailure('data', '勋章列表暂时没读到') },
      complete() { done() },
    })
  },

  // ===== 创建 =====
  openCreate() {
    if (!this.data.isOwner) return
    // 结果未知时保留提示,用户回来仍看得到「先核对」
    this.setData({ createVisible: true, formErrors: {}, createError: this.data.createUnknown ? this.data.createError : '' })
  },

  closeCreate() {
    if (this.data.creating) return
    this.setData({ createVisible: false })
  },

  onFormInput(e) {
    const field = e.currentTarget.dataset.field
    const value = e.detail.value
    if (field === 'name') this.setData({ 'form.name': value, 'formErrors.name': '' })
    else if (field === 'desc') this.setData({ 'form.desc': value, 'formErrors.desc': '' })
  },

  submitCreate() {
    // createUnknown:上一次提交结果未知,回读列表唯一确认或用户明确核对前一律拒绝,防重复建章
    if (!this.data.isOwner || this.data.creating || this.data.createUnknown) return
    const result = clubBadge.validateCreateForm(this.data.form, this._clubId)
    if (!result.ok) {
      this.setData({ formErrors: result.errors })
      return
    }
    const that = this
    const acct = this._account
    const knownCodes = this.data.badges.map(function (b) { return b.badgeCode })
    const markUnknown = (text) => {
      that._createUnknown = { name: result.payload.badgeName, knownCodes }
      that.setData({ creating: false, createUnknown: true, createError: text })
      that.loadAll()
    }
    this.setData({ creating: true, createError: '' })
    app.sendRequest({
      hideLoading: true,
      autoErrorToast: false,
      url: '/api/club/badge/create',
      method: 'POST',
      data: jsonBody(result.payload),
      header: jsonHeader(),
      success(res) {
        if (that._unloaded || that.accountStale(acct)) return
        if (!isSuccess(res)) {
          // 服务端原话(「只有主理人可创建勋章」/能力位未解锁等)直接给用户,不改写成泛化文案
          that.setData({ creating: false, createError: (res && res.msg) || '勋章没有创建成功' })
          return
        }
        const code = res.data && typeof res.data.badgeCode === 'string' ? res.data.badgeCode : ''
        that._expectBadgeCode = code || null
        that.setData({ creating: false, createVisible: false, form: emptyForm(), formErrors: {} })
        // 回读:重拉列表并核对新 badgeCode 在不在
        that.loadAll()
      },
      fail() {
        if (that._unloaded || that.accountStale(acct)) return
        // 网络断了结果未知:不当成失败让用户重提(会重复建),锁住提交并回读列表
        markUnknown('网络中断，结果未确认，正在刷新列表，确认前不能再次提交')
      },
      successStatusAbnormal(_res, status) {
        if (that._unloaded || that.accountStale(acct)) return
        const known = Number(status) >= 400 && Number(status) < 500
        if (known) {
          that.setData({ creating: false, createError: '勋章没有创建成功' })
          return
        }
        markUnknown('服务暂不可用，结果未确认，正在刷新列表，确认前不能再次提交')
      },
    })
  },

  /**
   * 结果未知后的回读:列表里出现「提交前没有、且名字相同」的新勋章 ⇒ 已建成,收起表单;
   * 否则保持锁定,只有用户明确点「我已核对」才解锁重提(请求可能仍在服务端处理中,不凭一次没看到就放行)。
   */
  reconcileUnknownCreate(badges) {
    const pending = this._createUnknown
    if (!pending) return
    const created = badges.filter(function (b) {
      return pending.knownCodes.indexOf(b.badgeCode) === -1 && b.badgeName === pending.name
    })
    if (created.length > 0) {
      this._createUnknown = null
      this.setData({ createUnknown: false, createVisible: false, form: emptyForm(), formErrors: {}, createError: '' })
      return
    }
    this.setData({ createError: '列表里还没看到这枚勋章。可下拉刷新再确认；确认确实没建成后，点「我已确认」再提交' })
  },

  confirmCreateChecked() {
    if (!this.data.createUnknown) return
    this._createUnknown = null
    this.setData({ createUnknown: false, createError: '' })
  },

  confirmCreatedVisible(badges) {
    const code = this._expectBadgeCode
    this._expectBadgeCode = null
    if (!code) return
    const found = badges.some(function (b) { return b.badgeCode === code })
    if (!found) this.setData({ refreshError: '新勋章已提交，但列表里暂时没看到，稍后下拉刷新确认' })
  },

  // ===== 颁发 =====
  openGrant(e) {
    if (!this.data.isOwner || this.data.granting) return
    if (this.accountStale(this._account)) return
    const code = e.currentTarget.dataset.code
    const badge = this.data.badges.find(function (b) { return b.badgeCode === code })
    if (!badge) { toast('勋章状态已变化，请刷新'); return }
    this.setData({ grantBadge: badge, memberPickerVisible: true })
    if (this.data.membersState !== 'ready' && this.data.membersState !== 'loading') this.loadMembers()
  },

  closeMemberPicker() {
    if (this.data.granting) return
    this.setData({ memberPickerVisible: false })
  },

  loadMembers() {
    if (!this.data.isOwner) return
    const that = this
    const acct = this._account
    const seq = (this._membersSeq || 0) + 1
    this._membersSeq = seq
    const stale = () => that._unloaded || that.accountStale(acct) || seq !== that._membersSeq
    const onFailure = (text) => {
      if (stale()) return
      that.setData({ membersState: 'error', membersError: text })
    }
    this.setData({ membersState: 'loading', membersError: '' })
    app.sendRequest({
      hideLoading: true,
      autoErrorToast: false,
      url: '/api/club/members',
      method: 'POST',
      data: jsonBody({ clubId: this._clubId }),
      header: jsonHeader(),
      success(res) {
        if (stale()) return
        if (!isSuccess(res) || !Array.isArray(res.data)) {
          onFailure((res && res.msg) || '成员名单暂时没读到')
          return
        }
        const members = clubBadge.normalizeMembers(res.data)
        that.setData({ members, membersState: members.length ? 'ready' : 'empty' })
      },
      fail() { onFailure('网络没连上，检查后重试') },
      successStatusAbnormal() { onFailure('成员名单暂时没读到') },
    })
  },

  pickMember(e) {
    if (!this.data.isOwner || this.data.granting || !this.data.grantBadge) return
    const memberId = clubBadge.positiveId(e.currentTarget.dataset.memberId)
    const member = this.data.members.find(function (m) { return m.memberId === memberId })
    if (!member) return
    this._pendingGrant = { badge: this.data.grantBadge, member }
    const dc = this.selectComponent && this.selectComponent('#dc')
    if (dc) dc.open('club.badge.grant', { name: this.data.grantBadge.badgeName, member: member.nickname })
  },

  onConfirmGrant(e) {
    if (!e || !e.detail || e.detail.key !== 'club.badge.grant') return
    const pending = this._pendingGrant
    if (!pending || !this.data.isOwner || this.data.granting) return
    if (this.accountStale(this._account)) return
    const dc = this.selectComponent && this.selectComponent('#dc')
    if (dc) dc.busyOn()
    const that = this
    const acct = this._account
    this.setData({ granting: true })
    const report = (ok, text) => {
      if (that._unloaded || that.accountStale(acct)) return
      that.setData({ granting: false })
      if (!dc) { toast(ok ? '已颁发' : (text || '颁发失败')); return }
      if (ok) dc.done(text)
      else dc.failed(text)
    }
    app.sendRequest({
      hideLoading: true,
      autoErrorToast: false,
      url: '/api/club/badge/grant',
      method: 'POST',
      data: jsonBody({ clubId: this._clubId, memberId: pending.member.memberId, badgeCode: pending.badge.badgeCode }),
      header: jsonHeader(),
      success(res) {
        if (!isSuccess(res)) { report(false, (res && res.msg) || '颁发失败'); return }
        that._pendingGrant = null
        report(true, clubBadge.grantReceipt(res).text)
      },
      // 结果未知:服务端对顺序重复颁发会回「已颁发过」,但并发/5xx 不保证读回确定结果,文案只承诺可核实的部分
      fail() { report(false, '网络中断，结果未确认；重试时平台会提示是否已颁发') },
      successStatusAbnormal() { report(false, '服务暂不可用，结果未确认；重试时平台会提示是否已颁发') },
    })
  },

  onGrantDone() {
    this.setData({ memberPickerVisible: false, grantBadge: null })
  },

  noop() {},
})
