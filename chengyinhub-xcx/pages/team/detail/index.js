const cyToast = require('../../../utils/toast.js');
const { isLoginRequired, retryLogin } = require('../../../utils/session/login-reload.js');
const { exactId, reader, sameReader } = require('../../../utils/user-character.js');
const { createRecovery } = require('../../../utils/team-event-command.js');
const app = getApp();

// Preserve numeric IDs for existing callers, but never round a Long string.
const requestId = value => {
  const id = exactId(value);
  return id && Number.isSafeInteger(Number(id)) ? Number(id) : id;
};

const STATUS_TEXT = ['招募中', '已满员', '进行中', '已结束', '已解散'];

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    commandRecovery: null,
    board: null,
    boardState: '',
    boardMessage: '',
    boardRanks: [],
    rankingState: '',
    team: null,
    members: [],
    joined: false,
    leader: false,
    // C-02b:队长切换公开招募/仅邀请的回显与提交中状态。
    joinModePublic: false,
    joinModeSaving: false,
    statusText: '',
    expireText: '',
    loading: true,
    refreshing: false,
    error: '',
    errorKind: 'network',
    errorSub: '',
    errorAction: '重新加载',
    reportShow: false,
    reportTeamId: ''
  },

  onLoad(options) {
    this._hidden = false;
    this._disposed = false;
    this._resetReads();
    this.teamId = exactId(options.teamId);
    if (!this.teamId) {
      this.setData({
        loading: false,
        refreshing: false,
        error: '队伍链接不完整',
        errorKind: 'missing-param',
        errorSub: '请返回重新进入',
        errorAction: '返回上一页'
      });
    }
  },
  onShow() { this._hidden = false; if (this.teamId) { this._openRecovery(); this.load(); } },
  onHide() { this._hidden = true; if (this._recovery) this._recovery.dispose(); this._resetReads(); },
  onUnload() { this._disposed = true; if (this._recovery) this._recovery.dispose(); this._resetReads(); },

  _openRecovery() {
    if (this._recovery) this._recovery.dispose();
    this._recovery = createRecovery({ app, platform: wx, domain: 'TEAM', targetId: this.teamId,
      changed: value => this.setData({ commandRecovery: value, joinModeSaving: value.busy || value.state === 'UNKNOWN' }),
      terminal: (result, command) => {
        this._resetReads();
        if (result.state === 'APPLIED' && ['quit', 'disband'].includes(command.action)) this._clearMembership();
        this.load();
      } });
  },
  queryCommand() { if (this._recovery) this._recovery.query(); },
  retryCommand() { if (this._recovery) this._recovery.retry(); },
  cancelCommand() { if (this._recovery) this._recovery.cancel(); },
  acknowledgeCommand() { if (this._recovery) this._recovery.acknowledge(); },

  _resetReads() {
    this._readEpoch = (this._readEpoch || 0) + 1;
    this._teamLoading = false;
    this.setData({ board: null, boardState: '', boardMessage: '', boardRanks: [], rankingState: '' });
  },
  _clearMembership() {
    const team = this.data.team ? Object.assign({}, this.data.team, { inviteCode: '' }) : null;
    this.setData({ team, members: [], joined: false, leader: false, joinModeSaving: false,
      board: null, boardRanks: [], rankingState: '' });
  },
  _writeCurrent(context) {
    if (this._hidden || this._disposed || context.teamId !== this.teamId) return false;
    if (!sameReader(app, context.reader)) {
      if (this._readContext && (this._readContext.reader.id !== context.reader.id
          || this._readContext.reader.token !== context.reader.token)) return false;
      this._resetReads();
      this._clearMembership();
      this.setData({ team: null });
      return false;
    }
    return true;
  },
  _readCurrent(context) {
    if (this._hidden || this._disposed || context.epoch !== this._readEpoch
        || context.teamId !== this.teamId) return false;
    if (!sameReader(app, context.reader)) {
      if (this._readContext && (this._readContext.reader.id !== context.reader.id
          || this._readContext.reader.token !== context.reader.token)) return false;
      this._resetReads();
      this.setData({ team: null, members: [], joined: false, leader: false, loading: false,
        error: '账号状态已变化，请重新加载队伍', errorAction: '重新加载' });
      return false;
    }
    return true;
  },
  onPullDownRefresh() { this.load(true); },
  openReport() { this.setData({ reportTeamId: this.teamId, reportShow: true }); },
  closeReport() { this.setData({ reportShow: false }); },

  load(fromPull) {
    const that = this;
    if (!this.teamId) {
      if (fromPull) wx.stopPullDownRefresh();
      this.onLoad({});
      return;
    }
    if (this._hidden || this._disposed) return;
    if (this._readContext && (!sameReader(app, this._readContext.reader) || this._readContext.teamId !== this.teamId)) {
      this._resetReads();
      this._mutation = null;
      this._joinModeSaving = false;
      this._joinModeContext = null;
      this._clearMembership();
      this.setData({ team: null });
    }
    if (this._teamLoading) { if (fromPull) wx.stopPullDownRefresh(); return; }
    this._resetReads();
    const context = { epoch: this._readEpoch, teamId: this.teamId, reader: reader(app) };
    this._readContext = context;
    let settled = false;
    const current = () => !settled && this._readCurrent(context);
    this._teamLoading = true;
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
      data: JSON.stringify({ teamId: this.teamId }),
      header: { 'Content-Type': 'application/json' },
      success(res) {
        if (!current()) return;
        settled = true;
        that._teamLoading = false;
        if (fromPull) wx.stopPullDownRefresh();
        if (res && res.code == '200' && res.data && res.data.team && exactId(res.data.team.id) === context.teamId) {
          const team = res.data.team;
          that.setData({
            loading: false,
            refreshing: false,
            team: team,
            members: res.data.members || [],
            joined: !!res.data.joined,
            leader: !!res.data.leader,
            statusText: STATUS_TEXT[team.status] || '状态未知',
            expireText: that.formatTime(team.expireTime),
            joinModePublic: team.joinMode == 2,
            error: '',
            errorSub: ''
          });
          that._loadBoard(context, team, res.data.joined === true);
        } else {
          // PR10 F-W1-12:未登录(无会话 / 401)的按钮是「登录」(走 retryLogin),不是只会再撞一次的「重新加载」。
          const needLogin = isLoginRequired(app, res);
          const authDenied = needLogin || String(res && res.code) === '403';
          that._clearMembership();
          if (authDenied) that.setData({ team: null });
          // F-W1-12:静默重登后仍 401/403 是身份问题,留可见的登录/权限说明,不按加载失败留白。
          that.setData({
            loading: false,
            refreshing: false,
            error: authDenied ? '登录后查看队伍' : hasTeam ? '队伍状态暂未更新' : '队伍暂时不可用',
            errorKind: authDenied ? 'no-permission' : 'data',
            errorSub: authDenied ? '请重新登录后再打开这支队伍' : app.getRequestErrorMessage(res, hasTeam ? '已保留上次确认的队伍信息' : '请稍后重新加载'),
            boardState: needLogin ? 'login' : 'error',
            boardMessage: '队伍成员资格暂未确认，请重新加载',
            errorAction: needLogin ? '登录' : '重新加载'
          });
        }
      },
      fail(res, statusCode) {
        if (!current()) return;
        settled = true;
        that._teamLoading = false;
        if (fromPull) wx.stopPullDownRefresh();
        // PR10 F-W1-12:静默重登失败回到这里(401),或根本没有会话 ⇒ 登录引导,不按网络失败留白
        if (isLoginRequired(app, res, statusCode)) {
          that._clearMembership();
          that.setData({ team: null });
          that.setData({
            loading: false,
            refreshing: false,
            error: '登录后查看队伍',
            errorKind: 'no-permission',
            errorSub: '',
            boardState: 'login', boardMessage: '请重新登录后查看定向进度',
            errorAction: '登录'
          });
          return;
        }
        that.setData({
          loading: false,
          refreshing: false,
          boardState: 'error', boardMessage: '队伍成员资格暂未确认，请重新加载',
          error: hasTeam ? '队伍状态暂未更新' : '网络没连上',
          errorKind: 'network',
          errorSub: hasTeam ? '已保留上次确认的队伍信息' : '检查网络连接后重试',
          errorAction: '重新加载'
        });
      }
    });
  },

  // disabled / not-orient / not-member 不适用看板,页面整段隐藏,不给提示文案。
  _boardFailure(res, status) {
    const code = res && res.errorCode;
    if (code === 'ORIENT_DISABLED') return ['disabled', ''];
    if (code === 'ORIENT_NOT_ORIENTEERING') return ['not-orient', ''];
    if (code === 'ORIENT_TEAM_NOT_MEMBER') return ['not-member', ''];
    if (code === 'ORIENT_LOGIN_REQUIRED' || isLoginRequired(app, res, status)) return ['login', '请重新登录后查看定向进度'];
    return ['error', '定向队伍数据暂不可用，请重新加载'];
  },
  _loadBoard(context, team, joined) {
    if (!joined) {
      this.setData({ boardState: 'not-member', boardMessage: '' });
      return;
    }
    if (!context.reader.id || !context.reader.token) {
      this.setData({ boardState: 'login', boardMessage: '请重新登录后查看定向进度' });
      return;
    }
    const ownerId = exactId(team.ownerId);
    const ownerType = team.ownerType;
    if (!ownerId || ![1, 2, '1', '2'].includes(ownerType)) {
      this.setData({ boardState: 'error', boardMessage: '队伍关联信息不完整，请重新加载' });
      return;
    }
    this.setData({ boardState: 'loading' });
    let settled = false;
    const finish = () => {
      if (settled || !this._readCurrent(context)) return false;
      settled = true;
      return true;
    };
    const reject = (res, status) => {
      const [state, message] = this._boardFailure(res, status);
      if (state === 'not-member' || state === 'login') this._clearMembership();
      this.setData({ board: null, boardState: state, boardMessage: message, boardRanks: [], rankingState: '' });
    };
    app.sendRequest({
      url: '/api/orient/team/detail', method: 'GET', data: { teamId: context.teamId },
      hideLoading: true, autoErrorToast: false,
      success: res => {
        if (!finish()) return;
        if (!res || res.code != '200') { reject(res); return; }
        const d = res.data;
        const count = n => Number.isSafeInteger(n) && n >= 0;
        if (!d || !d.team || exactId(d.team.teamId) !== context.teamId
            || exactId(d.team.ownerId) !== ownerId || String(d.team.ownerType) !== String(ownerType)
            || !d.threshold || !count(d.threshold.required) || !count(d.threshold.activeCount)
            || typeof d.threshold.ratio !== 'number' || !Number.isFinite(d.threshold.ratio)
            || d.threshold.ratio < 0 || d.threshold.ratio > 1
            || !d.summary || !count(d.summary.teamDoneCount) || !count(d.summary.totalTasks)
            || !Array.isArray(d.tasks) || !Array.isArray(d.members)
            || d.tasks.some(t => !t || !exactId(t.taskId) || !count(t.completedCount) || typeof t.teamDone !== 'boolean')
            || d.members.some(m => !m || !exactId(m.memberId) || !Array.isArray(m.doneTaskIds) || m.doneTaskIds.some(id => !exactId(id)))) {
          reject(null); return;
        }
        // The matrix and visible roster must use this same membership snapshot.
        if (!d.members.some(m => exactId(m.memberId) === context.reader.id)) {
          reject({ errorCode: 'ORIENT_TEAM_NOT_MEMBER' }); return;
        }
        const self = d.members.find(m => exactId(m.memberId) === context.reader.id);
        this.setData({ members: d.members.map(m => ({ memberId: exactId(m.memberId),
          memberName: m.name || '城瘾玩家', memberAvatar: m.avatar || '', role: m.role })),
          'team.joinedCount': d.threshold.activeCount,
          joined: true, leader: self.role == null ? this.data.leader : self.role == 1 });
        // Only project server completion facts; never infer team completion from member counts.
        const tasks = d.tasks.map(t => ({ taskId: exactId(t.taskId), name: t.name || '未命名任务',
          completedCount: t.completedCount, teamDone: t.teamDone,
          members: d.members.map(m => ({ memberId: exactId(m.memberId), name: m.name || '城瘾玩家',
            done: m.doneTaskIds.some(id => exactId(id) === exactId(t.taskId)) })) }));
        this.setData({ boardState: 'ready', boardMessage: '',
          board: { threshold: { required: d.threshold.required, activeCount: d.threshold.activeCount, ratio: d.threshold.ratio },
            summary: { teamDoneCount: d.summary.teamDoneCount, totalTasks: d.summary.totalTasks }, tasks,
            // 矩阵按成员成行:每格就是上面 tasks[].members[].done 的同一份服务端事实
            rows: d.members.map((m, mi) => ({ memberId: exactId(m.memberId), name: m.name || '城瘾玩家',
              cells: tasks.map(t => ({ taskId: t.taskId, done: t.members[mi].done })) })) } });
        this._loadRanking(context, ownerType, ownerId);
      },
      fail: (res, status) => { if (finish()) reject(res, status); }
    });
  },
  _loadRanking(context, ownerType, ownerId) {
    this.setData({ rankingState: 'loading', boardRanks: [] });
    let settled = false;
    const finish = () => {
      if (settled || !this._readCurrent(context)) return false;
      settled = true;
      return true;
    };
    const reject = (res, status) => {
      const [state, message] = this._boardFailure(res, status);
      if (state === 'not-member' || state === 'login') this._clearMembership();
      if (state === 'error') {
        this.setData({ rankingState: state, boardRanks: [] });
      } else {
        this.setData({ rankingState: state, boardRanks: [],
          board: null, boardState: state, boardMessage: message });
      }
    };
    app.sendRequest({
      url: '/api/orient/team/ranking', method: 'GET', data: { ownerType, ownerId },
      hideLoading: true, autoErrorToast: false,
      success: res => {
        if (!finish()) return;
        if (!res || res.code != '200') { reject(res); return; }
        const rows = res.data;
        if (!Array.isArray(rows) || rows.some(r => !r || !exactId(r.teamId)
            || !Number.isSafeInteger(r.rank) || r.rank < 1
            || !Number.isSafeInteger(r.teamDoneCount) || r.teamDoneCount < 0
            || !Number.isSafeInteger(r.totalTasks) || r.totalTasks < 0)) { reject(null); return; }
        this.setData({ rankingState: 'ready', boardRanks: rows.map(r => ({ teamId: exactId(r.teamId),
          title: r.title || '玩家队伍', rank: r.rank, teamDoneCount: r.teamDoneCount, totalTasks: r.totalTasks, mine: r.mine === true })) });
      },
      fail: (res, status) => { if (finish()) reject(res, status); }
    });
  },
  retryBoard() {
    if (this.data.boardState === 'login') { retryLogin(this, app, function () { this.load(); }); return; }
    this.load();
  },

  onErrorAction() {
    if (this.data.errorKind === 'missing-param') {
      wx.navigateBack({ delta: 1, fail() { wx.switchTab({ url: '/pages/talent/list/index' }); } });
      return;
    }
    if (this.data.errorAction === '登录') { retryLogin(this, app, function () { this.load(); }); return; }
    this.load();
  },

  // C-02b:队长改「公开招募 / 仅邀请」。后端 POST /api/team/join-mode 只认队长 +
  // joinMode∈{1,2}(没有满员/进行中的状态闸),所以 UI 只在队伍还在招募中(status=0)时
  // 给这一行:满员/已开始/已结束的队伍本就不在「附近的队伍」里,切换对任何界面都没有效果。
  // 提交失败按服务端文案回滚开关,不本地假设成功。
  onJoinModeChange(e) {
    const that = this;
    const team = this.data.team || {};
    const prevMode = team.joinMode == 2 ? 2 : 1;
    const nextMode = (e && e.detail && e.detail.value) ? 2 : 1;
    if (!this.data.leader || team.status !== 0 || this._joinModeSaving || this._mutation || this._hidden || this._disposed) {
      this.setData({ joinModePublic: prevMode === 2 });
      return;
    }
    if (nextMode === prevMode) return;
    if (!this._recovery) this._openRecovery();
    this.setData({ joinModePublic: prevMode === 2 });
    if (this._recovery.pending()) return;
    return this._recovery.start('join-mode', { joinMode: nextMode });
  },

  formatTime(value) {
    if (!value) return '';
    // ISO(带 T 与时区)直接解析;只有「yyyy-MM-dd HH:mm」才换成斜杠给 iOS。原先一律换斜杠,ISO 会解析失败、原样露出
    const raw = String(value);
    const date = new Date(raw.indexOf('T') > 0 ? raw : raw.replace(/-/g, '/'));
    if (isNaN(date.getTime())) return String(value);
    const pad = n => n < 10 ? '0' + n : String(n);
    return (date.getMonth() + 1) + '月' + date.getDate() + '日 ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
  },

  goJoin() {
    if (!this.data.team || !this.data.team.inviteCode) {
      cyToast('邀请已失效');
      return;
    }
    wx.navigateTo({ url: '/pages/team/join/index?code=' + encodeURIComponent(this.data.team.inviteCode) });
  },

  // 三段式第一段:三个危险动作都先过确认弹窗。文案(含后果与「此操作不可撤销」)
  // 在 utils/danger-actions.js,这里只给 key。
  quit() {
    this._confirmationReader = reader(app);
    const dc = this.selectComponent && this.selectComponent('#dcQuit');
    if (dc) dc.open('team.quit', {});
  },

  kick(e) {
    this._confirmationReader = reader(app);
    const dc = this.selectComponent && this.selectComponent('#dcKick');
    if (dc) dc.open('team.kick', { name: e.currentTarget.dataset.name || '这名队员', id: requestId(e.currentTarget.dataset.id) });
  },

  disband() {
    this._confirmationReader = reader(app);
    const dc = this.selectComponent && this.selectComponent('#dcDisband');
    if (dc) dc.open('team.disband', {});
  },

  // 三段式第二段:一个危险动作一个确认组件实例、一个确认回调。
  // 不做「一个 onDangerConfirm 按 key 派发」—— 那会让一个控件同时写三个接口,
  // 动作台账(scripts/uiaudit)没法把控件和它真正写的那个接口对上。
  onConfirmQuit() { return this.action('/api/team/quit', { teamId: requestId(this.teamId) }, '#dcQuit'); },
  onConfirmKick(e) { return this.action('/api/team/kick', { teamId: requestId(this.teamId), memberId: e.detail.params.id }, '#dcKick'); },
  onConfirmDisband() { return this.action('/api/team/disband', { teamId: requestId(this.teamId) }, '#dcDisband'); },

  /** 解散的更轻替代:改成只退出,队伍留给最早加入的队员(Revolut 185 的 Freeze 位)。 */
  onDisbandAlt() { this.quit(); },

  action(url, data, dcId) {
    if (this._confirmationReader && !sameReader(app, this._confirmationReader)) return;
    if (!exactId(data.teamId) || (data.memberId != null && !exactId(data.memberId)) || this._hidden || this._disposed) return;
    if (!this._recovery) this._openRecovery();
    if (this._recovery.pending()) return;
    const action = url.substring(url.lastIndexOf('/') + 1);
    const dc = this.selectComponent && this.selectComponent(dcId);
    // The durable result panel owns recovery after confirmation, including re-entry.
    if (dc) dc.close();
    return this._recovery.start(action, data.memberId == null ? {} : { memberId: exactId(data.memberId) });
  },

  onShareAppMessage() {
    if (!this.data.team || !this.data.team.inviteCode) {
      return { title: '队伍邀请已失效', path: '/pages/index/index' };
    }
    return {
      title: this.data.team.title || '来和我一起组队出发',
      path: '/pages/team/join/index?code=' + encodeURIComponent(this.data.team.inviteCode)
    };
  }
});
