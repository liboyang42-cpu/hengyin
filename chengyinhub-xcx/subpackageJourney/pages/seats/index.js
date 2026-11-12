const api = require('../../utils/journey-api.js');
const view = require('../../utils/journey-view.js');
const modal = require('../../../utils/modal.js');
const { resolveTopicCover } = require('../../../utils/topic-share.js');
const illus = require('../../components/cy-illus/art.js');
const joinView = require('../../utils/join-view.js');
const lobby = require('../../utils/lobby-view.js');
const app = getApp();

/* P5 票内席位：仅购票人可见。数量、状态、有效期全读服务端；写动作只在服务端声明支持时出现。
 * 释放已领取席位按清单 §24.3 暂不开放（已结束局/并发保护仍在补证）。
 * r2-store 2026-11-10:按 refs3/STRUCTURE.md §2 重排成「开局大厅」—— 主题头 → 以谁身份游玩 → 一起玩的人 N/M →
 * 怎么玩(服务端 choices,≥2 席才出)→ 底部「去起点」;邀请收进半屏(发给微信好友 + 已邀请/撤回)。
 * 二维码、加入码、玩法选择写接口、同时开始/各自时间服务端都没有,不画。 */

const INVITE_KEY = 'journey-seat-invite:';

Page({
  data: { state: 'loading', stateKind: 'loading', stateTitle: '加载中', stateCta: '',
    actionError: '',
    lobby: null, head: null, me: null, tiles: [], inviteShow: false, inviteCard: null, playUrl: '', privacyGateShow: false },

  showPrivacyGate() { this.setData({ privacyGateShow: true }); },
  onPrivacyGateSettled() { this.setData({ privacyGateShow: false }); },

  onLoad(options) {
    this._registrationId = view.isId(options && options.registrationId) ? String(options.registrationId) : '';
    if (!this._registrationId) { this.setData({ state: 'missing', stateKind: 'missing-param', stateTitle: '链接已失效' }); return; }
    this._pendingInvite = !!this._pending(); // l-after:邀请页不再有「查询邀请结果」钮,待确认状态只在本页逻辑里用
    this.load();
  },

  _pending() { try { return wx.getStorageSync(INVITE_KEY + this._registrationId) || ''; } catch (e) { return ''; } },
  _setPending(id) { try { if (id) wx.setStorageSync(INVITE_KEY + this._registrationId, id); else wx.removeStorageSync(INVITE_KEY + this._registrationId); } catch (e) { /* 本页仍保留 */ } this._pendingId = id; },

  load() {
    const epoch = this._epoch = (this._epoch || 0) + 1;
    if (this.data.state !== 'ready') this.setData({ state: 'loading', stateKind: 'loading', stateTitle: '加载中', stateCta: '' });
    api.seats(app, this._registrationId).then(res => {
      if (epoch !== this._epoch) return;
      if (!api.ok(res) || !res.data) {
        const denied = Number(res && res.code) === 403 || (res && res.httpStatus === 403);
        this.setData({ state: denied ? 'denied' : 'error', stateKind: denied ? 'no-permission' : 'data',
          stateTitle: denied ? (res.msg || '席位管理仅购票人可看') : '席位暂未加载', stateCta: denied ? '' : '重新加载' });
        return;
      }
      this._summary = res.data;
      const seats = view.seatsView(res.data);
      this._seats = seats;
      this.setData({ state: 'ready', lobby: lobby.lobbyView(seats, res.data, this.data.me), playUrl: lobby.playUrl(seats) });
      if (!this._extrasRead) { this._extrasRead = true; this._loadExtras(seats); }
    });
  },

  // 主题头 / 本人昵称头像 / 怎么玩:三路只读,任一失败只是那一块不出,不挡大厅
  _loadExtras(seats) {
    if (seats.topicId) {
      app.sendRequest({ url: '/api/topic/info-to-user', method: 'POST', data: { id: seats.topicId }, hideLoading: true, autoErrorToast: false,
        success: res => {
          const d = res && String(res.code) === '200' && res.data;
          if (d && d.name) this.setData({ head: { name: d.name, cover: resolveTopicCover(d, path => app.getImgUrl(path)) } });
        } });
      const q = Number(seats.ownerType) === 2 && seats.ownerId ? { activityId: seats.ownerId } : { topicId: seats.topicId };
      api.choices(app, q).then(res => {
        if (!api.ok(res)) return;
        const m = joinView.modeTiles(res.data);
        if (m) this.setData({ tiles: m.tiles });
      });
    }
    app.sendRequest({ url: '/api/user/info', method: 'POST', hideLoading: true, autoErrorToast: false,
      success: res => {
        const u = res && String(res.code) === '200' && res.data;
        if (!u) return;
        const name = String(u.nickname || '').trim();
        const me = { name, avatar: u.avatar || '', initial: name ? name.slice(0, 1) : '我' };
        this.setData({ me, lobby: lobby.lobbyView(this._seats, this._summary, me) });
      } });
  },

  // l-after:邀请同行走统一的 Spotify 式邀请页(cy-share-card kind=invite):主题封面 + 名称、邀请人、还有 N 个席位、信封插图
  onOpenInvite() {
    const l = this.data.lobby;
    if (!l || !l.canInvite) return;
    const head = this.data.head || {};
    const me = this.data.me || {};
    const s = this._summary || {};
    this.setData({ inviteShow: true, inviteCard: {
      kind: 'invite', id: String(s.topicId || ''), title: head.name || '一起来玩', cover: head.cover || '',
      inviter: me.name || '', seatsLeft: l.openCount, art: illus.layers('envelope').fg
    } });
    if (!this._inviteToken && !this._busy) this.onInvite();
  },
  onSeatTap(e) { if (e.currentTarget.dataset.tappable) this.onOpenInvite(); },
  onCloseInvite() { this.setData({ inviteShow: false }); },
  onEditMe() { wx.navigateTo({ url: '/pages/gerenziliao/gerenziliao' }); },
  onGoStart() {
    if (!this.data.playUrl || this._navigating) return;
    this._navigating = true;
    wx.navigateTo({ url: this.data.playUrl, complete: () => { this._navigating = false; } });
  },

  onInvite() {
    if (this._busy) return;
    const requestId = this._pendingId || this._pending() || view.newRequestId('seatinv');
    this._setPending(requestId);
    this._busy = true; // 只防连点,不渲染
    this.setData({ actionError: '' });
    api.seatInvite(app, this._registrationId, requestId).then(res => {
      this._busy = false;
      if (res.netFail || res.code === 'fail' || Number(res.code) >= 500) {
        this._pendingInvite = true; this.setData({ actionError: '结果还没确认，请稍后查询' });
        return;
      }
      this._setPending('');
      this._pendingInvite = false;
      if (!api.ok(res) || !res.data || !res.data.token) { this.setData({ actionError: res.msg || '邀请失败' }); this.load(); return; }
      this._invite = res.data;
      this._inviteToken = res.data.token; // 只给分享路径用,不渲染
      this.load();
    });
  },

  onCancel(e) {
    const id = e.currentTarget.dataset.id;
    const seat = ((this._seats || {}).seats || []).find(s => s.invitationId === String(id));
    if (!seat || this._busy) return;
    modal.show({ dangerKey: 'seat.invite-cancel', dangerParams: { name: seat.no }, success: r => {
      if (!r || !r.confirm) return;
      const requestId = view.newRequestId('seatcxl');
      this._busy = true;
      this.setData({ actionError: '' });
      api.seatCancel(app, id, requestId).then(res => {
        this._busy = false;
        if (!api.ok(res) && !res.netFail) this.setData({ actionError: res.msg || '撤销失败' });
        if (this._invite && String(this._invite.invitationId) === String(id)) { this._invite = null; this._inviteToken = ''; }
        this.load();
      });
    } });
  },

  onShareAppMessage() {
    const s = this._summary || {};
    const target = Number(s.ownerType) === 2 ? 'aid=' + s.ownerId + '&tid=' + (s.topicId || '') : 'tid=' + (s.topicId || '');
    return {
      title: '一起来玩',
      path: '/subpackageJourney/pages/landing/index?k=s&t=' + encodeURIComponent(this._inviteToken) + '&' + target
    };
  }
});
