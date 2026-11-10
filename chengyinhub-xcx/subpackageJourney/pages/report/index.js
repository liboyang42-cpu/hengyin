const api = require('../../utils/journey-api.js');
const view = require('../../utils/journey-view.js');
const app = getApp();

/* P7 本局战报：真实结束回执后进入，「我的记录」可按 runId 重开同一局。
 * 只读：读取不补完局、不发奖、不建局。名次(P9/§23.4)与回忆(P8)用同页面板承接。 */

function landingPath(token) {
  return '/subpackageJourney/pages/landing/index?k=r&t=' + encodeURIComponent(token);
}

Page({
  data: {
    state: 'loading', stateKind: 'loading', stateTitle: '加载中', stateCta: '',
    report: null, eyebrow: '', headLines: [], registrationId: '',
    rankOpen: false, rankTabs: [], rankScope: 'quest', rankPeriod: 'all', rankState: '', rankError: '', rank: null,
    memoryOpen: false, memoryState: '', memoryError: '', memory: null
  },

  onLoad(options) {
    const o = options || {};
    this._query = {
      activityId: view.isId(o.activityId) ? o.activityId : '',
      topicId: view.isId(o.topicId) ? o.topicId : '',
      runId: view.isId(o.runId) ? o.runId : ''
    };
    this._topicName = o.topicName ? decodeURIComponent(o.topicName) : '';
    this._cityCode = o.cityCode || '';
    this._countryCode = o.countryCode || '';
    this._clubId = view.isId(o.clubId) ? o.clubId : '';
    this.setData({ registrationId: view.isId(o.registrationId) ? o.registrationId : '' });
    if (!this._query.activityId && !this._query.topicId) {
      this.setData({ state: 'missing', stateKind: 'missing-param', stateTitle: '链接已失效', stateCta: '' });
      return;
    }
    this.reload();
  },

  reload() {
    const epoch = this._epoch = (this._epoch || 0) + 1;
    if (this.data.state !== 'ready') this.setData({ state: 'loading', stateKind: 'loading', stateTitle: '加载中', stateCta: '' });
    return api.report(app, this._query).then(res => {
      if (epoch !== this._epoch) return;
      if (!api.ok(res) || !res.data) { this._fail(res); return; }
      const report = view.reportView(res.data);
      // 显式局号读的是这一局；服务端回的局号不同就不展示，避免把别的局当成这一局。
      if (this._query.runId && report.runId && report.runId !== String(this._query.runId)) { this._fail({ reasonCode: 'RUN_SCOPE_MISMATCH' }); return; }
      this._report = res.data;
      const eyebrow = [report.dateLabel, this._topicName].filter(Boolean).join(' · ');
      this.setData({ state: 'ready', report, eyebrow, headLines: view.heroLines(report.legacy ? '主题记录' : '本局战报'),
        rankTabs: view.rankScopes({ topicId: report.topicId, cityCode: this._cityCode, countryCode: this._countryCode, clubId: this._clubId })
          .map(tab => ({ key: tab.key, label: tab.label })) });
      this._loadTeam(report);
    });
  },

  _fail(res) {
    const code = res && (res.httpStatus === 401 || res.code == 401) ? 401 : Number(res && res.code);
    if (code === 401) { this.setData({ state: 'auth', stateKind: 'no-permission', stateTitle: '登录后查看战报', stateCta: '登录' }); return; }
    if (res && (res.reasonCode === 'RUN_NOT_FOUND' || res.reasonCode === 'RUN_SCOPE_MISMATCH')) {
      this.setData({ state: 'gone', stateKind: 'not-found', stateTitle: '这一局不存在或无权查看', stateCta: '' }); return;
    }
    if (code === 403) { this.setData({ state: 'denied', stateKind: 'no-permission', stateTitle: (res && res.msg) || '暂时不能查看', stateCta: '' }); return; }
    this.setData({ state: 'error', stateKind: 'data', stateTitle: '战报暂未加载', stateCta: '重新加载' });
  },

  onStateCta() {
    if (this.data.state === 'auth') {
      app.requireLogin({ reason: '登录后查看战报' }).then(ok => { if (ok) this.reload(); });
      return;
    }
    this.reload();
  },

  // P9 队内：只有服务端说本局是组队共享并给出队伍时才出「队内」页签。
  _loadTeam(report) {
    if (report.legacy) return;
    api.choices(app, { activityId: this._query.activityId, topicId: this._query.activityId ? '' : this._query.topicId }).then(res => {
      if (!api.ok(res)) return;
      const choices = view.choicesView(res.data);
      if (!choices || !choices.teamId) return;
      this._teamId = choices.teamId;
      this.setData({ rankTabs: view.rankScopes({ topicId: report.topicId, cityCode: this._cityCode, countryCode: this._countryCode,
        clubId: this._clubId, teamId: choices.teamId }).map(tab => ({ key: tab.key, label: tab.label })) });
    });
  },

  openRank() { this.setData({ rankOpen: true }); this.loadRank(); },
  closeRank() { this.setData({ rankOpen: false }); },
  onRankScope(e) { this.setData({ rankScope: e.detail.key }); this.loadRank(); },
  onRankPeriod(e) { this.setData({ rankPeriod: e.detail.key }); this.loadRank(); },

  loadRank() {
    const epoch = this._rankEpoch = (this._rankEpoch || 0) + 1;
    const scope = this.data.rankScope;
    const tab = view.rankScopes({ topicId: this.data.report.topicId, cityCode: this._cityCode, countryCode: this._countryCode,
      clubId: this._clubId, teamId: this._teamId }).find(t => t.key === scope);
    if (!tab) return;
    this.setData({ rankState: 'loading', rankError: '' });
    if (scope === 'team') {
      api.teamDetail(app, tab.scopeId).then(res => {
        if (epoch !== this._rankEpoch) return;
        if (!api.ok(res)) { this.setData({ rankState: 'error', rankError: res && Number(res.code) === 403 ? '队内榜仅本队成员可看' : '队内榜暂未加载' }); return; }
        const rank = view.teamRankView(res.data);
        this.setData({ rank, rankState: rank && rank.hasRank ? 'ready' : 'empty' });
      });
      return;
    }
    const requested = { scope, scopeId: tab.scopeId, period: this.data.rankPeriod };
    api.rank(app, { scope, scopeId: tab.scopeId, period: this.data.rankPeriod, limit: 50 }).then(res => {
      if (epoch !== this._rankEpoch) return;
      if (!api.ok(res)) { this.setData({ rankState: 'error', rankError: (res && res.msg) || '榜单暂未加载' }); return; }
      const rank = view.rankView(res.data, requested);
      if (!rank || rank.mismatch) { this.setData({ rankState: 'error', rankError: '榜单暂未加载' }); return; }
      this.setData({ rank, rankState: rank.entries.length ? 'ready' : 'empty' });
    });
  },

  openMemory() { this.setData({ memoryOpen: true }); this.loadMemory(); },
  closeMemory() { this.setData({ memoryOpen: false }); },

  loadMemory() {
    const epoch = this._memoryEpoch = (this._memoryEpoch || 0) + 1;
    this.setData({ memoryState: 'loading', memoryError: '' });
    api.memory(app, this._query).then(res => {
      if (epoch !== this._memoryEpoch) return;
      if (!api.ok(res)) { this.setData({ memoryState: 'error', memoryError: (res && res.msg) || '回忆暂未加载' }); return; }
      const memory = view.memoryView(res.data);
      const runId = res.data && res.data.runId != null ? String(res.data.runId) : '';
      if (this.data.report.runId && runId !== this.data.report.runId) { this.setData({ memoryState: 'error', memoryError: '回忆暂未加载' }); return; }
      this.setData({ memory, memoryState: memory && (memory.moments.length || memory.participants) ? 'ready' : 'empty' });
    });
  },

  openSeats() {
    wx.navigateTo({ url: '/subpackageJourney/pages/seats/index?registrationId=' + this.data.registrationId });
  },

  // 分享：点分享才签发推广凭据；不带答案、同行信息或路线。签发失败就分享公开主题详情。
  onShareAppMessage() {
    const report = this.data.report || {};
    const title = (this._topicName ? this._topicName + ' · ' : '') + (report.shareTitle || '一起来玩');
    const targetType = report.activityId ? 2 : 1;
    const targetId = report.activityId || report.topicId;
    const fallback = '/pages/topic/index/index?id=' + report.topicId;
    return {
      title,
      path: fallback,
      promise: api.issueReferral(app, targetType, targetId).then(res => ({
        title,
        path: api.ok(res) && res.data && res.data.token ? landingPath(res.data.token) : fallback
      }))
    };
  }
});
