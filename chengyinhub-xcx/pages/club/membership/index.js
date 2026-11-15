// K1 俱乐部付费会员:成员看会员状态、方案与权益,加入/续费;主理人(manage=1)看收款状态并开关会费。
// 只在资金开关「门票」(GET /api/app/money-switch 的 tickets)为 true 时允许缴费;读不到一律按关闭,
// 页面如实显示「在线缴费即将开放」。会费是否走俱乐部自己的收款账户由服务端决定,端上只展示结果。
const app = getApp();
const toast = require('../../../utils/toast.js');
const { shapeOverview, shapePlan, shapeCollection, planRequest, planForm, errorText } = require('./view-model.js');

function jsonBody(data) { return JSON.stringify(data || {}); }
function jsonHeader() { return { 'Content-Type': 'application/json' }; }
function ok(res) { return !!res && (res.code === 200 || res.code === '200'); }
function closedFeature(res) { return !!res && (res.code === 404 || res.code === '404'); }
function positiveId(value) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}
function newId(prefix) {
  return prefix + '-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
}

Page({
  data: {
    statusBarHeight: (app.globalData || {}).statusBarHeight || 44,
    navBarHeight: (app.globalData || {}).navBarHeight || 44,
    state: 'loading',
    errorText: '',
    manage: false,
    view: null,
    collection: null,
    setting: null,
    plans: [],
    form: null,
    formError: '',
    busy: false,
  },

  onLoad(options) {
    this._clubId = positiveId(options && (options.clubId || options.id));
    this.setData({ manage: !!(options && options.manage === '1') });
    this.load();
  },

  onShow() { if (this._paid) { this._paid = false; this.load(); } },

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
    if (!this._clubId) { done(); this.setData({ state: 'error', errorText: '缺少俱乐部信息' }); return; }
    this.readMoneySwitch((moneyOpen) => {
      if (seq !== this._seq) return;
      this._moneyOpen = moneyOpen;
      if (this.data.manage) this.loadManage(seq, done);
      else this.loadOverview(seq, done);
    });
  },

  // 资金开关:任何失败都按关闭。
  readMoneySwitch(cb) {
    let finished = false;
    const finish = (value) => { if (finished) return; finished = true; cb(value === true); };
    try {
      app.sendRequest({
        url: '/api/app/money-switch', method: 'GET', hideLoading: true, silentError: true, auth: false, timeout: 6000,
        success: (res) => finish(ok(res) && res.data && res.data.tickets === true),
        fail: () => finish(false),
        successStatusAbnormal: () => finish(false),
      });
    } catch (e) { finish(false); }
  },

  post(url, body, cb) {
    app.sendRequest({
      hideLoading: true, silentError: true, url, method: 'POST', data: jsonBody(body), header: jsonHeader(),
      success: (res) => cb(res),
      fail: () => cb(null),
      successStatusAbnormal: (res) => cb(res || null),
    });
  },

  loadOverview(seq, done) {
    this.post('/api/club/membership/overview', { clubId: this._clubId }, (res) => {
      if (seq !== this._seq) return;
      done();
      if (closedFeature(res)) { this.setData({ state: 'soon' }); return; }
      const view = ok(res) ? shapeOverview(res.data, this._moneyOpen, false) : null;
      if (!view) { this.setData({ state: 'error', errorText: errorText(res && res.msg, res ? '会员信息暂不可用' : '网络异常，请重试') }); return; }
      if (!view.enabled) { this.setData({ state: 'off', view }); return; }
      this.setData({ state: 'ready', view });
    });
  },

  loadManage(seq, done) {
    this.post('/api/club/membership/setting', { clubId: this._clubId }, (res) => {
      if (seq !== this._seq) return;
      if (closedFeature(res)) { done(); this.setData({ state: 'soon' }); return; }
      if (!ok(res)) { done(); this.setData({ state: 'error', errorText: errorText(res && res.msg, res ? '会费设置暂不可用' : '网络异常，请重试') }); return; }
      const setting = res.data && typeof res.data === 'object' ? res.data : {};
      this.post('/api/club/membership/collection', { clubId: this._clubId }, (col) => {
        if (seq !== this._seq) return;
        done();
        const collection = ok(col) ? shapeCollection(col.data, this._moneyOpen) : null;
        if (!collection) { this.setData({ state: 'error', errorText: errorText(col && col.msg, '收款状态暂不可用') }); return; }
        this.post('/api/club/membership/plans/manage', { clubId: this._clubId }, (pl) => {
          if (seq !== this._seq) return;
          const plans = ok(pl) && Array.isArray(pl.data) ? pl.data.map(shapePlan).filter(Boolean) : [];
          this.setData({
            state: 'ready',
            setting: { enabled: setting.enabled === true, version: Number.isInteger(setting.version) ? setting.version : 0 },
            collection,
            plans,
            form: null,
          });
        });
      });
    });
  },

  // 主理人:新建 / 编辑会员方案(不收钱;成员缴费仍要等收款与资金开关)。
  onNewPlan() { this.setData({ form: planForm(null), formError: '' }); },
  onEditPlan(e) {
    const id = positiveId(e && e.currentTarget && e.currentTarget.dataset.id);
    const plan = this.data.plans.find((p) => p.id === id);
    if (plan) this.setData({ form: planForm(plan.raw), formError: '' });
  },
  onCancelPlan() { this.setData({ form: null, formError: '' }); },
  onFormInput(e) {
    const key = e && e.currentTarget && e.currentTarget.dataset.key;
    if (!key || !this.data.form) return;
    const value = e.detail && e.detail.value;
    this.setData({ ['form.' + key]: key === 'durationDays' ? Number(value) : value, formError: '' });
  },
  onFormSwitch(e) {
    const key = e && e.currentTarget && e.currentTarget.dataset.key;
    if (!key || !this.data.form) return;
    this.setData({ ['form.' + key]: !!(e.detail && e.detail.value) });
  },
  onSavePlan() {
    if (this.data.busy || !this.data.form) return;
    const built = planRequest(this.data.form, this._clubId, newId('cmp'));
    if (built.error) { this.setData({ formError: built.error }); return; }
    this.setData({ busy: true });
    this.post('/api/club/membership/plan/save', built.body, (res) => {
      this.setData({ busy: false });
      if (!ok(res)) { this.setData({ formError: errorText(res && res.msg, '保存失败，请重试') }); return; }
      toast('已保存');
      this.load();
    });
  },

  onToggleEnabled() {
    if (this.data.busy || !this.data.setting) return;
    const next = !this.data.setting.enabled;
    this.setData({ busy: true });
    this.post('/api/club/membership/setting/update', {
      clubId: this._clubId, enabled: next, version: this.data.setting.version, requestId: newId('cms'),
    }, (res) => {
      this.setData({ busy: false });
      if (!ok(res)) { toast(errorText(res && res.msg, '保存失败，请重试')); return; }
      toast(next ? '已开启付费会员' : '已关闭付费会员');
      this.load();
    });
  },

  onJoin(e) {
    const planId = positiveId(e && e.currentTarget && e.currentTarget.dataset.id);
    const view = this.data.view;
    if (!planId || this.data.busy || !view) return;
    if (!view.canPay) { toast(view.blockedText || '在线缴费即将开放'); return; }
    this.setData({ busy: true });
    this.post('/api/club/membership/renew', {
      clubId: this._clubId, planId, idempotencyKey: newId('clmk'), requestId: newId('clmr'),
    }, (res) => this.pay(res));
  },

  onResume(e) {
    const id = positiveId(e && e.currentTarget && e.currentTarget.dataset.id);
    if (!id || this.data.busy) return;
    if (!this.data.view || !this.data.view.canPay) { toast((this.data.view && this.data.view.blockedText) || '在线缴费即将开放'); return; }
    this.setData({ busy: true });
    this.post('/api/club/membership/invoice/resume', { clubId: this._clubId, id, requestId: newId('clmr') },
      (res) => this.pay(res));
  },

  pay(res) {
    if (!ok(res)) { this.setData({ busy: false }); toast(errorText(res && res.msg, res ? '暂时不能缴费，请稍后再试' : '网络异常，请重试')); return; }
    const data = res.data || {};
    const p = data.payParams || {};
    if (data.paymentState === 'PAID') { this.setData({ busy: false }); toast('已缴费'); this.load(); return; }
    if (!p.timeStamp || !p.nonceStr || !p.package || !p.paySign) {
      this.setData({ busy: false });
      toast('支付结果确认中，请稍后在账单里继续');
      this.load();
      return;
    }
    wx.requestPayment({
      timeStamp: p.timeStamp, nonceStr: p.nonceStr, package: p.package, signType: p.signType || 'RSA', paySign: p.paySign,
      success: () => { this.setData({ busy: false }); toast('已提交，正在确认'); this._paid = true; this.load(); },
      fail: (err) => {
        this.setData({ busy: false });
        const cancelled = err && err.errMsg === 'requestPayment:fail cancel';
        toast(cancelled ? '已取消支付，可在账单里继续' : '支付没有完成，可在账单里继续');
        this.load();
      },
    });
  },
});
