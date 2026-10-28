const app = getApp();
const character = require('../../utils/user-character.js');
const { requireLogin } = require('../../utils/auth/require-login.js');

const STATES = {
  loading: { kind: 'loading', title: '正在读取角色卡', sub: '', action: '' },
  login: { kind: 'no-permission', title: '登录后查看本人角色卡', sub: '角色卡仅对本人开放', action: '微信登录' },
  forbidden: { kind: 'no-permission', title: '无权查看角色卡', sub: '只能查看本人在该主题下的角色卡', action: '重新加载' },
  unavailable: { kind: 'not-found', title: '暂时无法查看该主题', sub: '请返回参与详情确认主题', action: '' },
  'missing-param': { kind: 'missing-param', title: '缺少有效主题', sub: '请从参与详情重新进入', action: '' },
  'session-changed': { kind: 'no-permission', title: '登录状态已变更', sub: '请重新加载本人角色卡', action: '重新加载' },
  upgrade: { kind: 'not-started', title: '请更新小程序', sub: '更新后重新进入查看角色卡', action: '' },
  error: { kind: 'data', title: '角色卡暂时无法读取', sub: '请稍后重试', action: '重新加载' },
};

Page({
  data: {
    statusBarHeight: (app.globalData || {}).statusBarHeight || 20,
    navBarHeight: (app.globalData || {}).navBarHeight || 44,
    state: 'loading', status: STATES.loading, card: null, privacyGateShow: false,
  },
  onLoad(options) {
    this._disposed = false;
    this._visible = false;
    this._invalidate();
    this._topicId = character.exactId(options && options.topicId);
    this.setData({ card: null });
  },
  onShow() {
    this._visible = true;
    this.loadCharacter();
  },
  onHide() {
    this._visible = false;
    this._invalidate();
    this.setData({ card: null, state: 'loading', status: STATES.loading });
  },
  onUnload() { this._disposed = true; this._visible = false; this._invalidate(); },
  _invalidate() {
    this._epoch = (this._epoch || 0) + 1;
    if (this._request && typeof this._request.abort === 'function') this._request.abort();
    this._request = null;
  },
  _status(state) {
    this.setData({ state: STATES[state] ? state : 'error', status: STATES[state] || STATES.error, card: null });
  },
  loadCharacter() {
    if (this._disposed || !this._visible) return;
    this._invalidate();
    const topicId = character.exactId(this._topicId);
    if (!topicId) { this._status('missing-param'); return; }
    const owner = character.reader(app);
    if (!owner.id || !owner.token || owner.token === app.DEV_TOKEN) { this._status('login'); return; }
    this._status('loading');
    const epoch = this._epoch;
    let settled = false;
    const current = () => {
      if (this._disposed || !this._visible || this._epoch !== epoch || this._topicId !== topicId || settled) return false;
      if (!character.sameReader(app, owner)) { settled = true; this._status('session-changed'); return false; }
      return true;
    };
    const failed = (res, status) => {
      if (!current()) return;
      settled = true;
      this._status(character.failure(res, status));
    };
    try {
      this._request = app.sendRequest({
        url: '/api/play/character', method: 'GET', data: { topicId },
        hideLoading: true,
        success: res => {
          if (!current()) return;
          if (character.responseCode(res && res.code) !== 200) { failed(res); return; }
          const card = character.projectCard(res.data, topicId);
          if (!card) { failed(null); return; }
          settled = true;
          this.setData({ card, state: card.created ? 'ready' : 'template' });
        },
        fail: (res, status) => failed(res, status),
        successStatusAbnormal: (res, status) => failed(res, status),
      });
    } catch (error) { failed(null); }
  },
  onStateAction() {
    if (this.data.state !== 'login') { this.loadCharacter(); return; }
    const epoch = this._epoch;
    requireLogin({ reason: '登录后查看本人角色卡' }).then(ok => {
      if (ok && !this._disposed && this._visible && epoch === this._epoch) this.loadCharacter();
    });
  },
  backToParticipation() {
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 });
    else wx.redirectTo({ url: '/subpackageMember/mycanyu/mycanyu' });
  },
  showPrivacyGate() { this.setData({ privacyGateShow: true }); },
  onPrivacyGateSettled() { this.setData({ privacyGateShow: false }); },
});
