// 我的队伍(2026-10-14 漫游原型)。读的接口与地图 P6 同源:
//   已加入 /api/team/my(leaderMemberId = 我 → 带队)、申请中/被拒 /api/team/my-applications、附近招募 /api/team/nearby。
// 写操作只有「撤回申请」,通过地图同一原请求回执协议恢复;进队伍 → 既有队伍详情页。
// 原型 B T-31:从主题详情组队卡带 ownerType/ownerId 进来时,第二格叫「本场招募」,读 /api/orient/team/lobby(不要定位),
//   满员队伍不列出(B3);已在队里的显示「已加入」,其余「申请加入」走与地图同一条 apply 原请求协议。
const T = require('../utils/map-team.js');
const modal = require('../../utils/modal');
const cyToast = require('../../utils/toast.js');
const { readReducedMotion } = require('../../utils/motion-preference.js');
const app = getApp();
const teamOperation = require('../utils/roam-team-operation.js');
const teamRecovery = require('../utils/roam-team-recovery.js');
const JSON_HEADER = { 'Content-Type': 'application/json' };
const TEAM_PAGE = '/pages/team/detail/index';
const RADIUS = 3000;

// 队伍名与队伍详情页同一口径(title,缺省「一起出发的玩家队伍」);场次名放到副标题,不顶替队名
function nameOf(t) { return String((t && t.title) || '').trim() || '一起出发的玩家队伍'; }
// 队伍头像叠层:有真头像用真头像,不够的用默认 Memoji 补到成员数(最多 3 个),不再用首字
const FACE = '/images/d_profile.png';
function avatarsOf(list, count) { const real = (list || []).filter(Boolean).slice(0, 3); const n = Math.max(1, Math.min(3, Math.max(real.length, Number(count) || 1))); const out = real.slice(); while (out.length < n) out.push(FACE); return out.map((src, k) => ({ k, src })); }
function members(t) { const j = Number(t && t.joinedCount) || 0; const m = Number(t && t.maxMembers) || 0; return m ? '已组 ' + j + '/' + m : '已组 ' + j + ' 人'; }
function dateText(v) { if (!v) return ''; const s = String(v).replace('T', ' '); return s.slice(5, 16).replace('-', '/'); }

Page({
  afterTeamCommandTerminal() {
    this._teamScopeEpoch = (this._teamScopeEpoch || 0) + 1;
    this.setData({ openLoading: false, openLoaded: false });
    this.reload();
  },
  reloadTeamCommands() { teamRecovery.mount(this, app, wx); },
  onTeamCommandAction(e) {
    const ds = e && e.currentTarget && e.currentTarget.dataset || {};
    teamRecovery.manager(this, app, wx).act(ds.teamId, ds.action);
  },

  data: { lobby: null, teamCommandRows: [], teamCommandError: '', reducedMotion: false, privacyGateShow: false, tab: 'mine', loading: true, mineError: false, groups: [], stats: [], openRows: [], openLoading: false, openLoaded: false, openError: '' },

  onLoad(options) {
    this._seq = 0; this.setData({ reducedMotion: readReducedMotion() });
    const o = options || {};
    const ownerId = teamOperation.teamId(o.ownerId);
    if (ownerId && ['1', '2'].includes(String(o.ownerType))) {
      let scope = '';
      try { scope = decodeURIComponent(o.scope || ''); } catch (e) { scope = String(o.scope || ''); }
      this.setData({ lobby: { ownerType: Number(o.ownerType), ownerId, scope } });
    }
    if (o.tab === 'open') this.setData({ tab: 'open' });
  },
  onShow() {
    this._hidden = false; this.reloadTeamCommands();
    const owner = teamOperation.identity(app);
    if (this._owner !== owner) {
      this._teamScopeEpoch = (this._teamScopeEpoch || 0) + 1;
      this._busy = false; this._rows = {};
      this.setData({ groups: [], stats: [], openRows: [], openLoading: false, openLoaded: false });
    }
    this._owner = owner; this.reload();
  },
  onHide() {
    this._hidden = true;
    this._teamScopeEpoch = (this._teamScopeEpoch || 0) + 1;
    this.setData({ openLoading: false, openLoaded: false });
    teamRecovery.dispose(this);
  },
  onUnload() { this._unloaded = true; teamRecovery.dispose(this); },
  // 隐私授权闸:app.js 优先调页内弹窗(定位会触发系统隐私授权),没有这个方法才回退到整页隐私页
  showPrivacyGate() { this.setData({ privacyGateShow: true }); },
  onPrivacyGateSettled() { this.setData({ privacyGateShow: false }); },
  onPullDownRefresh() { this.reload(() => wx.stopPullDownRefresh()); },

  reload(done) {
    const seq = this._seq = (this._seq || 0) + 1; const that = this;
    const current = teamOperation.capture(this, app);
    let joined = null; let apps = null; let left = 2;
    this.setData({ loading: true, mineError: false });
    const settle = () => {
      if (--left || seq !== that._seq || !current()) return;
      if (joined == null && apps == null) { that.setData({ loading: false, mineError: true, groups: [] }); if (done) done(); return; }
      that._render(joined || [], apps || []);
      if (joined == null || apps == null) that.setData({ mineError: true });
      if (done) done();
    };
    app.sendRequest({ url: '/api/team/my', method: 'POST', hideLoading: true, silentError: true, data: JSON.stringify({}), header: JSON_HEADER,
      success(res) { if (res && res.code == 200 && Array.isArray(res.data)) joined = res.data; settle(); }, fail() { settle(); }, successStatusAbnormal() { settle(); } });
    app.sendRequest({ url: '/api/team/my-applications', method: 'POST', hideLoading: true, silentError: true, data: JSON.stringify({}), header: JSON_HEADER,
      success(res) { if (res && res.code == 200 && Array.isArray(res.data)) apps = res.data; settle(); }, fail() { settle(); }, successStatusAbnormal() { settle(); } });
  },

  _render(joined, apps) {
    const me = String(app.getUserID() || '');
    const lead = []; const ok = []; const wait = []; const bad = []; const seen = {};
    joined.forEach((t) => {
      if (!t || !t.id || Number(t.status) === 3 || Number(t.status) === 4 || seen[t.id]) return;
      seen[t.id] = true;
      const isLead = me && String(t.leaderMemberId) === me;
      const row = { key: 'j' + t.id, teamId: t.id, name: nameOf(t), avatars: avatarsOf([], t.joinedCount), members: members(t),
        sub: (t.expireTime ? dateText(t.expireTime) + ' 开始' : '') || (isLead ? '你是队长' : '已在队伍里'),
        badge: isLead ? '队长' : '已通过', tone: isLead ? 'lead' : 'ok', action: { text: '进入队伍', kind: 'primary', act: 'enter' } };
      (isLead ? lead : ok).push(row);
    });
    apps.forEach((a) => {
      if (!a || !a.teamId || seen[a.teamId]) return;
      seen[a.teamId] = true;
      const base = { teamId: a.teamId, name: nameOf(a), avatars: avatarsOf([a.leaderAvatar], a.joinedCount), members: members(a) };
      if (a.applyStatus === 'PENDING') wait.push(Object.assign(base, { key: 'p' + a.teamId, sub: a.activityName || ('队长 ' + (a.leaderName || '玩家')), badge: '审核中', tone: 'wait', action: { text: '撤回申请', kind: 'secondary', act: 'withdraw' } }));
      else if (a.applyStatus === 'REJECTED') bad.push(Object.assign(base, { key: 'r' + a.teamId, sub: '不能再申请这支队伍', badge: '未通过', tone: 'bad', action: { text: '看附近队伍', kind: 'secondary', act: 'nearby' } }));
    });
    const groups = [['lead', '我带的队', lead], ['ok', '已通过', ok], ['wait', '审核中', wait], ['bad', '未通过', bad]]
      .filter((g) => g[2].length).map((g) => ({ key: g[0], title: g[1], rows: g[2] }));
    this._rows = { lead, ok, wait, bad };
    this.setData({ loading: false, groups, stats: [{ k: '已通过', v: ok.length }, { k: '审核中', v: wait.length }, { k: '带队', v: lead.length }, { k: '未通过', v: bad.length }] });
    // 没有任何队伍时,「我的」下面直接接附近招募,不让页面停在空态
    if (!groups.length || this.data.tab === 'open') this.loadOpen();
  },
  _joinedIds() { const r = this._rows || {}; const ids = {}; (r.lead || []).concat(r.ok || []).forEach((x) => { ids[String(x.teamId)] = true; }); return ids; },

  switchTab(e) { const tab = e.currentTarget.dataset.tab; if (tab === this.data.tab) return; this.setData({ tab }); if (tab === 'open' && !this.data.openLoaded) this.loadOpen(); },

  loadOpen() {
    if (this.data.openLoading) return;
    if (this.data.lobby) { this.loadLobby(); return; }
    const that = this;
    const current = teamOperation.capture(this, app);
    this.setData({ openLoading: true, openError: '' });
    wx.getLocation({ type: 'gcj02',
      success(loc) {
        if (!current()) return;
        app.sendRequest({ url: '/api/team/nearby', method: 'GET', hideLoading: true, silentError: true, data: { lat: loc.latitude, lng: loc.longitude, radius: RADIUS },
          success(res) {
            if (!current()) return;
            if (!(res && res.code == 200 && Array.isArray(res.data))) { that.setData({ openLoading: false, openError: '附近的队伍获取失败' }); return; }
            const rows = res.data.filter((t) => t && (t.viewerStatus || 'NONE') === 'NONE').map((t) => {
              const d = T.decorateTeam(t);
              return { key: 'n' + d.teamId, teamId: d.teamId, name: nameOf(t), avatars: avatarsOf(d.faces, t.joinedCount), members: members(t), sub: d.sub || '', badge: d.card && d.card.mode === 'buy' ? '需购票' : '可申请' };
            });
            that.setData({ openLoading: false, openLoaded: true, openRows: rows });
          },
          fail() { if (!current()) return; that.setData({ openLoading: false, openError: '附近的队伍获取失败' }); },
          successStatusAbnormal() { if (!current()) return; that.setData({ openLoading: false, openError: '附近的队伍获取失败' }); } });
      },
      fail() { if (!current()) return; that.setData({ openLoading: false, openError: '需要定位才能看附近的队伍' }); } });
  },

  loadLobby() {
    const that = this; const lobby = this.data.lobby;
    const current = teamOperation.capture(this, app);
    this.setData({ openLoading: true, openError: '' });
    const failed = () => { if (current()) that.setData({ openLoading: false, openError: '加载失败' }); };
    app.sendRequest({ url: '/api/orient/team/lobby', method: 'GET', hideLoading: true, autoErrorToast: false, data: { ownerType: lobby.ownerType, ownerId: lobby.ownerId },
      success(res) {
        if (!current()) return;
        if (!(res && res.code == 200 && Array.isArray(res.data))) { failed(); return; }
        const mine = that._joinedIds();
        const rows = res.data.filter((t) => t && teamOperation.teamId(t.teamId)
            && !(Number(t.maxMembers) > 0 && Number(t.joinedCount) >= Number(t.maxMembers)))
          .map((t) => {
            const joined = !!mine[String(t.teamId)];
            return { key: 'l' + t.teamId, teamId: String(t.teamId), name: nameOf(t), avatars: avatarsOf([t.leaderAvatar], t.joinedCount), members: members(t),
              sub: '队长 ' + (t.leaderName || '玩家'), badge: joined ? '已加入' : '可申请', tone: joined ? 'ok' : 'plain', joined, go: joined ? '进入队伍' : '申请加入' };
          });
        that.setData({ openLoading: false, openLoaded: true, openRows: rows });
      }, fail: failed, successStatusAbnormal: failed });
  },
  openLobbyTeam(e) {
    const row = this.data.openRows[Number(e.currentTarget.dataset.index)];
    if (!row || this._busy) return;
    if (row.joined) { wx.navigateTo({ url: TEAM_PAGE + '?teamId=' + encodeURIComponent(row.teamId) }); return; }
    const that = this;
    const teamId = teamOperation.teamId(row.teamId);
    if (!teamId) { cyToast('队伍标识无效，请刷新'); return; }
    const current = teamOperation.capture(this, app);
    modal.show({ title: '申请加入这支队伍？', content: '队长同意后会通知你。', confirmText: '申请', cancelText: '取消', success(r) {
      if (!r.confirm || !current() || that._busy || that._hidden) return;
      return teamRecovery.manager(that, app, wx).start(teamId, 'apply', { message: null });
    } });
  },
  _row(e) { const ds = e.currentTarget.dataset; const g = (this._rows || {})[ds.group] || []; return g[Number(ds.index)]; },
  openRow(e) { const row = this._row(e); if (!row) return; if (row.tone === 'bad') { this.openMap(); return; } wx.navigateTo({ url: TEAM_PAGE + '?teamId=' + encodeURIComponent(row.teamId) }); },
  onRowAction(e) {
    const row = this._row(e); if (!row || this._busy) return;
    if (row.action.act === 'enter') { wx.navigateTo({ url: TEAM_PAGE + '?teamId=' + encodeURIComponent(row.teamId) }); return; }
    if (row.action.act === 'nearby') { this.openMap(); return; }
    const that = this;
    const teamId = teamOperation.teamId(row.teamId);
    if (!teamId) { cyToast('队伍标识无效，请刷新'); return; }
    const current = teamOperation.capture(this, app);
    modal.show({ title: '撤回申请？', content: '撤回后可以重新申请。', confirmText: '撤回', cancelText: '不撤回', danger: true, success(r) {
      if (!r.confirm || !current() || that._busy) return;
      if (that._hidden) return;
      return teamRecovery.manager(that, app, wx).start(teamId, 'withdraw', {});
    } });
  },
  openNearbyTeam(e) { const row = this.data.openRows[Number(e.currentTarget.dataset.index)]; if (row) wx.navigateTo({ url: '/subpackageRoam/nearby/index?radius=' + RADIUS + '&teamId=' + encodeURIComponent(row.teamId) }); },
  // 地图带上本页的 3 km,两边看到的是同一片范围(地图默认 1 km)
  openMap() { wx.navigateTo({ url: '/subpackageRoam/nearby/index?radius=' + RADIUS }); },
});
