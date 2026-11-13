// 俱乐部主个人收款绑定:状态 / 绑定 / 解除。
// 入口:俱乐部结算页(带 clubId)与「我的资产」更多菜单(mine=1,取本人第一个俱乐部)。
// 绑定只传一次性 wx.login code 与同意标记,微信身份由服务端按登录会话取,端上不碰。
const app = getApp();
const toast = require('../../../utils/toast.js');
const modal = require('../../../utils/modal.js');
const { shapeStatus, errorText } = require('./view-model.js');

function jsonBody(data) { return JSON.stringify(data || {}); }
function jsonHeader() { return { 'Content-Type': 'application/json' }; }
function ok(res) { return !!res && (res.code === 200 || res.code === '200'); }
function positiveId(value) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

Page({
  data: {
    statusBarHeight: (app.globalData || {}).statusBarHeight || 44,
    navBarHeight: (app.globalData || {}).navBarHeight || 44,
    state: 'loading',
    errorText: '',
    view: null,
    busy: false,
  },

  onLoad(options) {
    this._mine = !!(options && options.mine === '1');
    this._clubId = positiveId(options && (options.clubId || options.id));
    this.load();
  },

  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.reLaunch({ url: '/pages/index/index' });
  },

  onPullDownRefresh() { this.load(true); },

  load(fromPull) {
    this._seq = (this._seq || 0) + 1;
    const seq = this._seq;
    if (!fromPull) this.setData({ state: 'loading', errorText: '' });
    const done = () => { if (fromPull) wx.stopPullDownRefresh(); };
    if (!this._clubId) {
      if (!this._mine) { done(); this.setData({ state: 'error', errorText: '缺少俱乐部信息' }); return; }
      this.resolveClub(seq, done);
      return;
    }
    this.loadStatus(seq, done);
  },

  // mine=1:收款身份按人而不是按俱乐部,取本人名下第一个俱乐部做归属校验即可。
  resolveClub(seq, done) {
    app.sendRequest({
      hideLoading: true, silentError: true,
      url: '/api/club/settlement/mine', method: 'POST',
      data: jsonBody({}), header: jsonHeader(),
      success: (res) => {
        if (seq !== this._seq) return;
        const clubs = ok(res) && Array.isArray(res.data) ? res.data : null;
        if (!clubs) { done(); this.setData({ state: 'error', errorText: errorText(res && res.msg, '俱乐部信息加载失败') }); return; }
        const first = clubs.map((row) => positiveId(row && row.clubId)).find(Boolean);
        if (!first) { done(); this.setData({ state: 'no-club' }); return; }
        this._clubId = first;
        this.loadStatus(seq, done);
      },
      fail: () => { if (seq !== this._seq) return; done(); this.setData({ state: 'error', errorText: '网络异常，请重试' }); },
    });
  },

  loadStatus(seq, done) {
    app.sendRequest({
      hideLoading: true, silentError: true,
      url: '/api/club/personal-receiver/status', method: 'GET',
      data: { clubId: this._clubId },
      success: (res) => {
        if (seq !== this._seq) return;
        done();
        const shaped = ok(res) ? shapeStatus(res.data) : null;
        if (!shaped) { this.setData({ state: 'error', errorText: errorText(res && res.msg, '个人收款状态暂不可用') }); return; }
        this.setData({ state: 'ready', view: shaped });
      },
      fail: () => { if (seq !== this._seq) return; done(); this.setData({ state: 'error', errorText: '网络异常，请重试' }); },
    });
  },

  onBind() {
    if (this.data.busy) return;
    modal.show({
      title: '绑定个人收款',
      content: '收入将分账到你本人的微信，绑定后可随时解除。',
      confirmText: '同意并绑定',
      success: (r) => { if (r && r.confirm) this.submitBind(); },
    });
  },

  submitBind() {
    this.setData({ busy: true });
    wx.login({
      success: (login) => {
        if (!login || !login.code) { this.setData({ busy: false }); toast('微信登录失败，请重试'); return; }
        app.sendRequest({
          hideLoading: true, silentError: true,
          url: '/api/club/personal-receiver/bind', method: 'POST',
          data: jsonBody({ clubId: this._clubId, jsCode: login.code, consent: true }),
          header: jsonHeader(),
          success: (res) => {
            this.setData({ busy: false });
            if (!ok(res)) { toast(errorText(res && res.msg, '绑定失败，请重试')); return; }
            toast('已绑定');
            this.load();
          },
          fail: () => { this.setData({ busy: false }); toast('网络异常，请重试'); },
        });
      },
      fail: () => { this.setData({ busy: false }); toast('微信登录失败，请重试'); },
    });
  },

  onUnbind() {
    if (this.data.busy) return;
    const dc = this.selectComponent && this.selectComponent('#dc');
    if (dc) dc.open('club.personal-receiver.unbind', {});
  },

  onConfirmUnbind() {
    const dc = this.selectComponent && this.selectComponent('#dc');
    if (dc) dc.busyOn();
    this.setData({ busy: true });
    app.sendRequest({
      hideLoading: true, silentError: true,
      url: '/api/club/personal-receiver/unbind', method: 'POST',
      data: jsonBody({ clubId: this._clubId }), header: jsonHeader(),
      success: (res) => {
        this.setData({ busy: false });
        if (!ok(res)) { if (dc) dc.failed(errorText(res && res.msg, '解除失败，请重试')); return; }
        if (dc) dc.done();
        this.load();
      },
      fail: () => { this.setData({ busy: false }); if (dc) dc.failed('网络异常，请重试'); },
    });
  },
});
