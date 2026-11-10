const api = require('../../utils/journey-api.js');
const view = require('../../utils/journey-view.js');
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

const CARD_ICON = { gift: 'gift', seat: 'connect', referral: 'route' };

Page({
  data: {
    state: 'loading', stateKind: 'loading', stateTitle: '加载中', stateCta: '',
    inviter: null, title: '', modes: [], primary: '', busy: false, actionError: '',
    headLines: [], factLine: '', cardIcon: 'route', modeIndex: 0
  },

  onLoad(options) {
    const parsed = view.landingKind(options);
    if (!parsed) { this.setData({ state: 'missing', stateKind: 'missing-param', stateTitle: '链接已失效' }); return; }
    this._kind = parsed.kind;
    this.setData({ cardIcon: CARD_ICON[parsed.kind] || 'route' });
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

  // P4 分页卡：只跟随滑动更新分段进度，不提交模式。
  onModeChange(e) {
    const i = Number(e && e.detail && e.detail.current);
    if (Number.isInteger(i) && i >= 0 && i !== this.data.modeIndex) this.setData({ modeIndex: i });
  },

  // 次要文字动作：不领取、不建单，回首页逛逛。
  onBrowse() { wx.switchTab({ url: '/pages/index/index' }); },

  _renderReferral(ref, epoch) {
    if (!ref.valid) { this.setData({ state: 'invalid', stateKind: 'missing-param', stateTitle: ref.stateText }); return; }
    this._target = { type: ref.targetType, id: ref.targetId };
    const facts = [];
    if (ref.expires) facts.push({ k: '邀请', v: ref.expires });
    if (ref.closed) facts.push({ k: '报名', v: '已关闭' });
    this.setData({ state: 'ready', inviter: ref.inviter, headLines: view.heroLines(ref.headline), title: ref.title,
      factLine: view.factLine(facts), primary: ref.canView ? '去看看' : '' });
    this._loadModes(ref.targetType === 2 ? { activityId: ref.targetId } : { topicId: ref.targetId }, epoch);
  },

  _renderGift(gift) {
    if (!gift.valid) { this.setData({ state: 'invalid', stateKind: gift.stateText === '礼物已被领取' ? 'success' : 'missing-param', stateTitle: gift.stateText }); return; }
    this._target = { type: 1, id: gift.topicId };
    const facts = gift.expires ? [{ k: '领取', v: gift.expires }] : [];
    this.setData({ state: 'ready', inviter: null, headLines: view.heroLines(gift.headline), title: gift.title,
      factLine: view.factLine(facts), primary: gift.canClaim ? '领取' : '' });
  },

  // 同行席位码没有匿名读口：页面只说明来意，领取由服务端判定。
  _renderSeat(epoch) {
    const pending = savedRequestId('seat', this._token);
    this.setData({ state: 'ready', inviter: null, headLines: view.heroLines('朋友邀请你同行'), title: '', factLine: '',
      primary: pending ? '查询领取结果' : '领取席位' });
    if (this._activityId || this._topicId) this._loadModes({ activityId: this._activityId, topicId: this._activityId ? '' : this._topicId }, epoch);
  },

  _loadModes(query, epoch) {
    api.choices(app, query).then(res => {
      if (epoch !== this._epoch || !api.ok(res)) return;
      const choices = view.choicesView(res.data);
      if (choices && !choices.unavailable) this.setData({ modes: choices.modes, modeIndex: 0 });
    });
  },

  onPrimary() {
    if (this.data.busy) return;
    if (this._claimed) { this._enter(); return; }
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
      if (!api.ok(res) || !res.data) { this.setData({ primary: '', actionError: res.msg || '领取失败' }); return; }
      this._claimed = res.data;
      const facts = [{ k: '席位', v: '第 ' + res.data.seatNo + ' 席' }];
      this.setData({ headLines: view.heroLines('已领取同行席位'), factLine: view.factLine(facts),
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
      this.setData({ headLines: view.heroLines('礼物已领取'), primary: view.isId((this._target || {}).id) ? '去看看' : '' });
    });
  },

  // 回到同一局：带原活动/主题进入游玩页，准入仍由游玩页向服务端核验。
  _enter() {
    if (this._kind === 'gift') { this._goTarget(); return; }
    const q = this._activityId ? 'activityId=' + this._activityId + (this._topicId ? '&topicId=' + this._topicId : '') : 'topicId=' + this._topicId;
    wx.navigateTo({ url: '/pages/play/index?' + q });
  }
});
