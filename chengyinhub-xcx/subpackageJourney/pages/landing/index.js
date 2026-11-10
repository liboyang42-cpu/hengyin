const api = require('../../utils/journey-api.js');
const view = require('../../utils/journey-view.js');
const join = require('../../utils/join-view.js');
const app = getApp();

/* P1 推荐/赠送/同行落地 + P4 玩法模式 + P5 领取席位。
 * 打开链接只读：不自动领取、不建单、不建局、不归因。领取用原接口与 requestId；
 * 结果未知时用同一 requestId 查原回执，不重新领一份。切账号后重新判资格。 */

const STORE = 'journey-claim:';

function storeKey(kind, token) { return STORE + kind + ':' + String(token).slice(-24); }
function savedRequestId(kind, token) {
  try { return wx.getStorageSync(storeKey(kind, token)) || ''; } catch (e) { return ''; }
}
function saveRequestId(kind, token, id) { try { wx.setStorageSync(storeKey(kind, token), id); } catch (e) { /* 存不下就只在本页保留 */ } }
function dropRequestId(kind, token) { try { wx.removeStorageSync(storeKey(kind, token)); } catch (e) { /* 无需处理 */ } }

Page({
  data: {
    state: 'loading', stateKind: 'loading', stateTitle: '加载中', stateCta: '',
    inviter: null, headline: '', title: '', tiles: [], activeMode: '', activeTile: null, seatNo: '', facts: [], primary: '', busy: false, actionError: ''
  },

  onLoad(options) {
    const parsed = view.landingKind(options);
    if (!parsed) { this.setData({ state: 'missing', stateKind: 'missing-param', stateTitle: '链接已失效' }); return; }
    this._kind = parsed.kind;
    this._token = parsed.token;
    this._activityId = view.isId(options.aid) ? String(options.aid) : '';
    this._topicId = view.isId(options.tid) ? String(options.tid) : '';
    this._owner = String(app.getUserID && app.getUserID() || '');
    this.load();
  },

  onShow() {
    // 切账号后旧结果不覆盖当前身份：重新读一次。
    const owner = String(app.getUserID && app.getUserID() || '');
    if (this._kind && this._owner !== owner) { this._owner = owner; this._claimed = null; this.load(); }
  },

  load() {
    const epoch = this._epoch = (this._epoch || 0) + 1;
    this._buyOwn = false;
    this.setData({ state: 'loading', stateKind: 'loading', stateTitle: '加载中', stateCta: '', actionError: '' });
    if (this._kind === 'seat') { this._renderSeat(epoch); return; }
    const read = this._kind === 'gift' ? api.gift(app, this._token) : api.referral(app, this._token);
    read.then(res => {
      if (epoch !== this._epoch) return;
      if (!api.ok(res) || !res.data) { this.setData({ state: 'error', stateKind: 'data', stateTitle: '链接暂未读取', stateCta: '重试' }); return; }
      if (this._kind === 'gift') this._renderGift(view.giftView(res.data));
      else this._renderReferral(view.referralView(res.data), epoch);
    });
  },

  onStateCta() { if (this.data.state === 'error') this.load(); },

  _renderReferral(ref, epoch) {
    if (!ref.valid) { this.setData({ state: 'invalid', stateKind: 'missing-param', stateTitle: ref.stateText }); return; }
    this._target = { type: ref.targetType, id: ref.targetId };
    const facts = [];
    if (ref.expires) facts.push({ k: '邀请', v: ref.expires });
    if (ref.closed) facts.push({ k: '报名', v: '已关闭' });
    this.setData({ state: 'ready', inviter: ref.inviter, headline: ref.headline, title: ref.title, facts,
      primary: ref.canView ? '去看看' : '' });
    this._loadModes(ref.targetType === 2 ? { activityId: ref.targetId } : { topicId: ref.targetId }, epoch);
  },

  _renderGift(gift) {
    if (!gift.valid) { this.setData({ state: 'invalid', stateKind: gift.stateText === '礼物已被领取' ? 'success' : 'missing-param', stateTitle: gift.stateText }); return; }
    this._target = { type: 1, id: gift.topicId };
    this.setData({ state: 'ready', inviter: null, headline: gift.headline, title: gift.title,
      facts: gift.expires ? [{ k: '领取', v: gift.expires }] : [], primary: gift.canClaim ? '领取' : '' });
  },

  // 同行席位码没有匿名读口：页面只说明来意，领取由服务端判定。
  _renderSeat(epoch) {
    const pending = savedRequestId('seat', this._token);
    this.setData({ state: 'ready', inviter: null, headline: '朋友邀请你同行', title: '', facts: [],
      primary: pending ? '查询领取结果' : '领取席位' });
    if (this._activityId || this._topicId) this._loadModes({ activityId: this._activityId, topicId: this._activityId ? '' : this._topicId }, epoch);
  },

  _loadModes(query, epoch) {
    api.choices(app, query).then(res => {
      if (epoch !== this._epoch || !api.ok(res)) return;
      const play = join.modeTiles(res.data);
      if (play) this.setData({ tiles: play.tiles, activeMode: play.active, activeTile: play.tiles.find(t => t.key === play.active) || null });
    });
  },

  // 模式卡只切换下方规则说明，不写服务端：真正的玩法仍按开局时服务端冻结的配置。
  onMode(e) {
    const key = e.currentTarget.dataset.key;
    const tile = this.data.tiles.find(t => t.key === key);
    if (!tile || key === this.data.activeMode) return;
    if (wx.vibrateShort) wx.vibrateShort({ type: 'light', fail() {} });
    this.setData({ activeMode: key, activeTile: tile });
  },

  onPrimary() {
    if (this.data.busy) return;
    if (this._claimed) { this._enter(); return; }
    if (this._buyOwn) { this._goOwn(); return; }
    if (this._kind === 'referral') { this._goTarget(); return; }
    const owner = String(app.getUserID && app.getUserID() || '');
    const run = () => (this._kind === 'gift' ? this._claimGift() : this._claimSeat());
    if (owner) { run(); return; }
    app.requireLogin({ reason: this._kind === 'gift' ? '登录后领取礼物' : '登录后领取席位' }).then(ok => {
      if (!ok) return;
      this._owner = String(app.getUserID && app.getUserID() || '');
      run();
    });
  },

  _goOwn() {
    const url = this._activityId ? '/pages/activity/detail/index?id=' + this._activityId : '/pages/topic/index/index?id=' + this._topicId;
    wx.navigateTo({ url });
  },

  _goTarget() {
    const t = this._target || {};
    if (!view.isId(t.id)) return;
    wx.navigateTo({ url: t.type === 2 ? '/pages/activity/detail/index?id=' + t.id : '/pages/topic/index/index?id=' + t.id });
  },

  _claimSeat() {
    const requestId = savedRequestId('seat', this._token) || view.newRequestId('seat');
    saveRequestId('seat', this._token, requestId);
    this.setData({ busy: true, actionError: '' });
    api.seatClaim(app, this._token, requestId).then(res => {
      this.setData({ busy: false });
      if (res.netFail || Number(res.code) >= 500 || res.code === 'fail') {
        this.setData({ primary: '查询领取结果', actionError: '结果还没确认，请稍后查询' });
        return;
      }
      dropRequestId('seat', this._token);
      // r2-store:席位已被领 / 邀请过期 / 已撤回都是服务端 409 原话;给一个「自己购买」出口回到原活动或主题
      if (!api.ok(res) || !res.data) {
        this._buyOwn = !!(this._activityId || this._topicId);
        this.setData({ primary: this._buyOwn ? '自己购买' : '', actionError: res.msg || '领取失败' });
        return;
      }
      this._claimed = res.data;
      this.setData({ headline: '已领取同行席位', seatNo: String(res.data.seatNo || ''), facts: [{ k: '席位', v: '第 ' + res.data.seatNo + ' 席' }],
        primary: this._activityId || this._topicId ? '进入本局' : '' });
    });
  },

  _claimGift() {
    const requestId = savedRequestId('gift', this._token) || view.newRequestId('gift');
    saveRequestId('gift', this._token, requestId);
    this.setData({ busy: true, actionError: '' });
    api.giftClaim(app, this._token, requestId).then(res => {
      this.setData({ busy: false });
      if (res.netFail || Number(res.code) >= 500 || res.code === 'fail') {
        this.setData({ primary: '查询领取结果', actionError: '结果还没确认，请稍后查询' });
        return;
      }
      dropRequestId('gift', this._token);
      if (!api.ok(res)) { this.setData({ primary: '', actionError: res.msg || '领取失败' }); return; }
      this._claimed = res.data || {};
      this.setData({ headline: '礼物已领取', primary: view.isId((this._target || {}).id) ? '去看看' : '' });
    });
  },

  // 回到同一局：带原活动/主题进入游玩页，准入仍由游玩页向服务端核验。
  _enter() {
    if (this._kind === 'gift') { this._goTarget(); return; }
    const q = this._activityId ? 'activityId=' + this._activityId + (this._topicId ? '&topicId=' + this._topicId : '') : 'topicId=' + this._topicId;
    wx.navigateTo({ url: '/pages/play/index?' + q });
  }
});
