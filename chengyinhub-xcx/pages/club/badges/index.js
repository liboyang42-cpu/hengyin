// 俱乐部勋章(N2):主理人自建勋章 → 列表 → 颁给成员。
// 合同见 pages/club/utils/club-badge.js 顶部注释。权限只按服务端口径:
// create/grant 仅主理人(/api/club/detail 的 isOwner);其他人只读列表,不渲染任何管理控件。
const toast = require('../../../utils/toast.js')
const clubBadge = require('../utils/club-badge.js')
const app = getApp()

function jsonBody(data) { return JSON.stringify(data || {}) }
function jsonHeader() { return { 'Content-Type': 'application/json' } }
function isSuccess(res) { return !!res && (res.code === 200 || res.code === '200') }
function emptyForm() { return { name: '', desc: '', iconUrl: '' } }

Page({
  data: {
    statusBarHeight: (app.globalData || {}).statusBarHeight || 20,
    navBarHeight: (app.globalData || {}).navBarHeight || 44,
    clubId: null,
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
    iconUploading: false,
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
    if (!clubId) {
      this.setData({ state: 'missing-param' })
      return
    }
    this.setData({ clubId })
    this.loadAll()
  },

  onUnload() { this._unloaded = true },

  onPullDownRefresh() { this.loadAll() },

  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 })
    else wx.switchTab({ url: '/pages/talent/list/index' })
  },

  retryLoad() { this.loadAll() },

  // 主理人身份与勋章列表各取一次;身份读不到时按「非主理人」处理 —— 宁可少给控件也不多给。
  loadAll() {
    if (!this.data.clubId) return
    const cycle = (this._loadCycle || 0) + 1
    this._loadCycle = cycle
    const hadData = this.data.state === 'ready' || this.data.state === 'empty'
    this.setData({ refreshing: hadData, refreshError: '', state: hadData ? this.data.state : 'loading' })
    let pending = 2
    const finish = () => {
      pending -= 1
      if (pending === 0) wx.stopPullDownRefresh && wx.stopPullDownRefresh()
    }
    this.loadClub(cycle, finish)
    this.loadBadges(cycle, hadData, finish)
  },

  loadClub(cycle, done) {
    const that = this
    app.sendRequest({
      hideLoading: true,
      autoErrorToast: false,
      url: '/api/club/detail',
      method: 'POST',
      data: jsonBody({ id: this.data.clubId }),
      header: jsonHeader(),
      success(res) {
        if (that._unloaded || cycle !== that._loadCycle) return
        const club = isSuccess(res) && res.data && typeof res.data === 'object' ? res.data : null
        that.setData({
          isOwner: !!(club && club.isOwner === true),
          clubName: club && typeof club.name === 'string' ? club.name : that.data.clubName,
        })
      },
      fail() {
        if (that._unloaded || cycle !== that._loadCycle) return
        that.setData({ isOwner: false })
      },
      successStatusAbnormal() {
        if (that._unloaded || cycle !== that._loadCycle) return
        that.setData({ isOwner: false })
      },
      complete() { done() },
    })
  },

  loadBadges(cycle, hadData, done) {
    const that = this
    const onFailure = (kind, text) => {
      if (that._unloaded || cycle !== that._loadCycle) return
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
      data: jsonBody({ id: this.data.clubId }),
      header: jsonHeader(),
      success(res) {
        if (that._unloaded || cycle !== that._loadCycle) return
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
      },
      fail() { onFailure('network', '检查网络连接后重试') },
      successStatusAbnormal() { onFailure('data', '勋章列表暂时没读到') },
      complete() { done() },
    })
  },

  // ===== 创建 =====
  openCreate() {
    if (!this.data.isOwner) return
    this.setData({ createVisible: true, formErrors: {}, createError: '' })
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

  chooseIcon() {
    if (!this.data.isOwner || this.data.creating || this.data.iconUploading) return
    const that = this
    // 走既有上传通道:选图/裁剪取消时 app.chooseImage 不回调,所以「上传中」只在真正开始上传时置起
    app.chooseImage(null, 1, {
      crop: true,
      cropScale: '1:1',
      onUploadStart() { if (!that._unloaded) that.setData({ iconUploading: true, 'formErrors.icon': '' }) },
      onUploadDone(r) {
        if (that._unloaded) return true
        const url = r && r.ok && Array.isArray(r.results) && typeof r.results[0] === 'string' ? r.results[0] : ''
        that.setData({
          iconUploading: false,
          'form.iconUrl': url || that.data.form.iconUrl,
          'formErrors.icon': url ? '' : '图标没上传成功，可以重试或先不放图标',
        })
        return true
      },
    })
  },

  removeIcon() {
    if (this.data.creating) return
    this.setData({ 'form.iconUrl': '', 'formErrors.icon': '' })
  },

  submitCreate() {
    if (!this.data.isOwner || this.data.creating || this.data.iconUploading) return
    const result = clubBadge.validateCreateForm(this.data.form, this.data.clubId)
    if (!result.ok) {
      this.setData({ formErrors: result.errors })
      return
    }
    const that = this
    this.setData({ creating: true, createError: '' })
    app.sendRequest({
      hideLoading: true,
      autoErrorToast: false,
      url: '/api/club/badge/create',
      method: 'POST',
      data: jsonBody(result.payload),
      header: jsonHeader(),
      success(res) {
        if (that._unloaded) return
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
        if (that._unloaded) return
        // 网络断了结果未知:不当成失败让用户重提(会重复建),先回读列表
        that.setData({ creating: false, createError: '网络中断，结果未确认。已刷新列表，请先核对再决定是否重新创建' })
        that.loadAll()
      },
      successStatusAbnormal(_res, status) {
        if (that._unloaded) return
        const known = Number(status) >= 400 && Number(status) < 500
        that.setData({
          creating: false,
          createError: known ? '勋章没有创建成功' : '服务暂时没响应，结果未确认。已刷新列表，请先核对再决定是否重新创建',
        })
        if (!known) that.loadAll()
      },
    })
  },

  confirmCreatedVisible(badges) {
    const code = this._expectBadgeCode
    this._expectBadgeCode = null
    if (!code) return
    const found = badges.some(function (b) { return b.badgeCode === code })
    if (!found) this.setData({ refreshError: '新勋章已提交，但列表里暂时没看到，稍后下拉刷新核对' })
  },

  // ===== 颁发 =====
  openGrant(e) {
    if (!this.data.isOwner || this.data.granting) return
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
    const that = this
    const onFailure = (text) => {
      if (that._unloaded) return
      that.setData({ membersState: 'error', membersError: text })
    }
    this.setData({ membersState: 'loading', membersError: '' })
    app.sendRequest({
      hideLoading: true,
      autoErrorToast: false,
      url: '/api/club/members',
      method: 'POST',
      data: jsonBody({ clubId: this.data.clubId }),
      header: jsonHeader(),
      success(res) {
        if (that._unloaded) return
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
    const dc = this.selectComponent && this.selectComponent('#dc')
    if (dc) dc.busyOn()
    const that = this
    this.setData({ granting: true })
    const report = (ok, text) => {
      if (that._unloaded) return
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
      data: jsonBody({ clubId: this.data.clubId, memberId: pending.member.memberId, badgeCode: pending.badge.badgeCode }),
      header: jsonHeader(),
      success(res) {
        if (!isSuccess(res)) { report(false, (res && res.msg) || '颁发失败'); return }
        that._pendingGrant = null
        report(true, clubBadge.grantReceipt(res).text)
      },
      // 颁发服务端幂等(重复颁发回「已颁发过」),网络未知时让用户原地重试是安全的
      fail() { report(false, '网络中断，结果未确认；重试不会重复颁发') },
      successStatusAbnormal() { report(false, '服务暂时没响应，结果未确认；重试不会重复颁发') },
    })
  },

  onGrantDone() {
    this.setData({ memberPickerVisible: false, grantBadge: null })
  },

  noop() {},
})
