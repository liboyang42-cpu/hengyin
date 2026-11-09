const toast = require('../../../utils/toast.js');
const { exactId, reader, sameReader } = require('../../../utils/user-character.js');
const { createRecovery } = require('../../../utils/team-event-command.js');
const app = getApp();

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    commandRecovery: null, recoveryShown: false,
    team: null,
    joined: false,
    loading: true,
    refreshing: false,
    joining: false,
    joinError: '',
    ticketRequired: false,
    error: '',
    errorKind: 'network',
    errorSub: '',
    errorAction: '重新加载'
  },

  onLoad(options) {
    this._disposed = false;
    this._hidden = false;
    this.inviteCode = String(options.code || '');
    this.legacyTeamId = String(options.teamId || '');
    this._openRecovery();
    this.load();
  },

  onShow() {
    const refresh = this._hidden || this._reader && !sameReader(app, this._reader);
    this._hidden = false;
    if (refresh) { this._openRecovery(); this.load(); }
  },
  onHide() { this._loginContinuationEpoch = (this._loginContinuationEpoch || 0) + 1; if (this._recovery) this._recovery.dispose(); this._hidden = true; this._epoch = (this._epoch || 0) + 1; this._inviteLoading = false; },
  onUnload() { this._disposed = true; this.onHide(); },

  _openRecovery() {
    this._recoveryReader = reader(app);
    if (this._recovery) this._recovery.dispose(); this._freshCommand = false;
    this._recovery = createRecovery({ app, platform: wx, domain: 'TEAM', settle: true,
      scopeKey: 'invite:' + this.inviteCode, targetId: null,
      // 第十一轮 g2(j4 F1,接 j2 F-11):终态回执由共享层静默归档;恢复卡只留给结果未知(card)。
      // 本页刚发出的请求在途时不弹卡(按钮自带忙态)。被拒时后端理由贴在加入按钮上方。
      changed: value => { const fresh = this._freshCommand; if (!value.busy) this._freshCommand = false;
        const rejected = value.outcome && value.outcome.state === 'REJECTED' ? value.outcome.message || '暂时无法加入该队伍' : '';
        this.setData({ commandRecovery: value, recoveryShown: !!(value.card || value.busy) && !(fresh && value.busy), joining: value.busy,
          joinError: value.busy ? '' : rejected,
          // 第十二轮 k9(p1 N-14):没有这一场的票被拒时,按钮换成去买票,不留一个灰掉的「接受邀请并加入」
          ticketRequired: !value.busy && !!rejected && value.outcome.errorCode === 'TICKET_REQUIRED' }); },
      terminal: () => {
        this._epoch = (this._epoch || 0) + 1;
        this._inviteLoading = false;
        // Membership can have changed since this receipt; always display a fresh server projection.
        this.load();
      } });
  },
  queryCommand() { this._recovery.query(); },
  retryCommand() { this._recovery.retry(); },
  cancelCommand() { this._recovery.cancel(); },
  acknowledgeCommand() { this._recovery.acknowledge(); },

  _context() { return { code: this.inviteCode, reader: reader(app) }; },
  _current(context) {
    if (this._hidden || this._disposed || context.code !== this.inviteCode) return false;
    if (!sameReader(app, context.reader)) {
      if (this._reader && (this._reader.id !== context.reader.id || this._reader.token !== context.reader.token)) return false;
      this.setData({ team: null, joined: false, joining: false });
      this.teamId = '';
      return false;
    }
    return true;
  },

  load() {
    const that = this;
    if (this._hidden || this._disposed) return;
    if (this._reader && !sameReader(app, this._reader)) {
      this._inviteLoading = false;
      this.teamId = '';
      this.setData({ team: null, joined: false, joining: false, joinError: '' });
    }
    this._reader = reader(app);
    if (!this.inviteCode) {
      this.setData({
        loading: false,
        refreshing: false,
        error: this.legacyTeamId ? '邀请链接已升级' : '邀请链接不完整',
        errorKind: 'missing-param',
        errorSub: this.legacyTeamId ? '请让队长重新分享新的邀请链接' : '请从队长分享的邀请重新进入',
        errorAction: '返回上一页'
      });
      return;
    }
    if (this._inviteLoading) return;
    this._inviteLoading = true;
    const epoch = this._epoch = (this._epoch || 0) + 1;
    const context = this._context();
    const current = () => epoch === this._epoch && this._current(context);
    const hasTeam = !!this.data.team;
    this.setData({
      loading: !hasTeam,
      refreshing: hasTeam,
      error: '',
      errorSub: '',
      errorAction: '重新加载'
    });
    app.sendRequest({
      url: '/api/team/info', method: 'POST', hideLoading: true, autoErrorToast: false,
      data: JSON.stringify({ inviteCode: this.inviteCode }),
      header: { 'Content-Type': 'application/json' },
      success(res) {
        if (!current()) return;
        that._inviteLoading = false;
        if (res && res.code == '200' && res.data && res.data.team && exactId(res.data.team.id)) {
          that.teamId = exactId(res.data.team.id);
          that._recovery.setTarget(that.teamId);
          that.setData({
            loading: false,
            refreshing: false,
            team: res.data.team,
            joined: !!res.data.joined,
            error: '',
            errorSub: ''
          });
        } else {
          that.teamId = '';
          that.setData({ team: null, joined: false });
          const authDenied = String(res && res.code) === '401' || String(res && res.code) === '403';
          // F-W1-12:静默重登后仍 401/403 是身份问题,留可见的登录/权限说明,不按加载失败留白。
          // j4 F2:服务端明确拒绝(邀请已失效等)是业务结论,不是加载失败:写出理由并给返回出路,不留白。
          const refused = !authDenied && !!res && res.errorCode === 'TEAM_INFO_REFUSED';
          that.setData({
            loading: false,
            refreshing: false,
            error: authDenied ? '登录后查看队伍邀请' : refused ? app.getRequestErrorMessage(res, '邀请已失效') : hasTeam ? '邀请状态暂未更新' : '邀请暂时不可用',
            errorKind: authDenied ? 'no-permission' : refused ? 'missing-param' : 'data',
            errorSub: authDenied ? '请重新登录后再打开邀请' : refused ? '请让队长重新分享' : app.getRequestErrorMessage(res, hasTeam ? '已保留上次确认的邀请信息' : '请让队长重新分享后再试'),
            errorAction: refused ? '返回上一页' : '重新加载'
          });
        }
      },
      fail() {
        if (!current()) return;
        that._inviteLoading = false;
        that.setData({
          loading: false,
          refreshing: false,
          error: hasTeam ? '邀请状态暂未更新' : '网络没连上',
          errorKind: 'network',
          errorSub: hasTeam ? '已保留上次确认的邀请信息' : '检查网络连接后重试',
          errorAction: '重新加载'
        });
      }
    });
  },

  onErrorAction() {
    if (this.data.errorKind === 'missing-param') {
      wx.navigateBack({ delta: 1, fail() { wx.switchTab({ url: '/pages/talent/list/index' }); } });
      return;
    }
    this.load();
  },

  join() {
    if (app.ensureLoginFor && !app.ensureLoginFor('team', () => this.join(), { owner: this, target: () => [this.inviteCode] })) return; // 2026-10-24 游客写前登录
    if (this.data.joining || this.data.joinError || this._hidden || this._disposed || !this.inviteCode) return;
    if (!this._recoveryReader || !sameReader(app, this._recoveryReader)) this._openRecovery();
    if (!this._recovery || this._recovery.pending() || !exactId(this.teamId)) return;
    this._recovery.setTarget(this.teamId);
    this._freshCommand = true; return this._recovery.start('join', { inviteCode: this.inviteCode });
  },

  goBuyTicket() {
    const team = this.data.team;
    const activityId = team && Number(team.ownerType) === 2 && exactId(team.ownerId) ? team.ownerId : null;
    if (!activityId || this._hidden || this._disposed) return;
    wx.navigateTo({ url: '/pages/activity/baoming/baoming?activityId=' + activityId });
  },

  goDetail() {
    if (!exactId(this.teamId) || this._hidden || this._disposed) return;
    const pages = getCurrentPages();
    const previous = pages.length > 1 ? pages[pages.length - 2] : null;
    if (previous && previous.route === 'pages/team/detail/index') {
      wx.navigateBack({ delta: 1 });
      return;
    }
    wx.redirectTo({ url: '/pages/team/detail/index?teamId=' + this.teamId });
  }
});
