// 订单中心深链入口；报名详情继续委托原场景组件。
const app = getApp();
const login = require('../../../../utils/auth/require-login.js');
const { failureStatus, retryLogin } = require('../../../../utils/session/login-reload.js');
const { exactId, reader, sameReader } = require('../../../../utils/user-character.js');

function revision() {
  const session = app.getSession && app.getSession();
  return session && session.getRevision ? session.getRevision() : null;
}
function decimalId(value) { return typeof value === 'string' && exactId(value) === value; }
function summaryOf(value) {
  if (!value || !['REGISTRATION', 'CLM', 'COMMERCE', 'DEP'].includes(value.kind)
    || !decimalId(value.id) || !['orderNo', 'title', 'statusText'].every(key =>
      typeof value[key] === 'string' && value[key].trim() && value[key].length <= 256)
    || typeof value.amountText !== 'string' || !/^\d{1,16}(\.\d{1,2})?$/.test(value.amountText)) return null;
  const summary = { kind: value.kind, id: value.id, orderNo: value.orderNo,
    title: value.title, statusText: value.statusText, amountText: value.amountText };
  if (typeof value.createdAtText === 'string' && value.createdAtText.length <= 64) summary.createdAtText = value.createdAtText;
  return summary;
}

Component({
  data: { id: '', summary: null, entryState: 'loading', entryTitle: '加载中', entryAction: '',
    navTop: ((app.globalData || {}).statusBarHeight || 20) + ((app.globalData || {}).navBarHeight || 44) },
  lifetimes: {
    attached() { this._attached = true; },
    detached() {
      this._attached = false;
      this._unloaded = true;
      this._loginContinuationEpoch = (this._loginContinuationEpoch || 0) + 1;
      this._entrySeq = (this._entrySeq || 0) + 1;
      this._outTradeNo = null;
      this._entryReader = null;
      this._entryRevision = null;
      this.data.id = '';
      this.data.summary = null;
    },
  },
  pageLifetimes: {
    show() {
      this._hidden = false;
      if (this._initialized && (this._resumeEntry || this._entryReader && !this._sameEntrySession())) {
        this._resumeEntry = false;
        this.reloadEntry();
      }
    },
    hide() {
      this._hidden = true;
      this._loginContinuationEpoch = (this._loginContinuationEpoch || 0) + 1;
      this._resumeEntry = true;
      if (!this._initialized) return;
      this._clearEntry();
    },
  },
  methods: {
    initialize(outTradeNo) {
      this._outTradeNo = outTradeNo;
      this._initialized = true;
      this.reloadEntry();
    },
    _clearEntry() {
      this._entrySeq = (this._entrySeq || 0) + 1;
      this.setData({ id: '', summary: null, entryState: 'loading', entryTitle: '加载中', entryAction: '' });
    },
    _entryError(state, title, action) {
      this.setData({ id: '', summary: null, entryState: state, entryTitle: title, entryAction: action || '' });
    },
    _sameEntrySession() {
      return this._entryReader && sameReader(app, this._entryReader) && this._entryRevision === revision();
    },
    reloadEntry() {
      if (!this._initialized || this._unloaded || this._hidden) return;
      this._clearEntry();
      if (typeof this._outTradeNo !== 'string' || !/^[0-9A-Za-z_|*\-]{6,32}$/.test(this._outTradeNo)) {
        this._entryError('missing-param', '订单链接无效', '返回');
        return;
      }
      if (!this._entryReady || app.isAppReady === false) {
        if (this._readyPending) return;
        this._readyPending = true;
        let pending;
        try { pending = app.waitForAppReady(); } catch (error) { pending = Promise.reject(error); }
        Promise.resolve(pending).then(() => {
          this._readyPending = false;
          if (this._unloaded) return;
          this._entryReady = app.isAppReady !== false;
          if (!this._hidden && this._entryReady) this.reloadEntry();
          else if (!this._hidden) this._entryError('network', '初始化未完成', '重试');
        }, () => {
          this._readyPending = false;
          if (!this._unloaded && !this._hidden) this._entryError('network', '初始化失败', '重试');
        });
        return;
      }
      if (!login.hasSession(app)) { this._entryError('no-permission', '登录后查看订单', '登录'); return; }
      this._entryReader = reader(app);
      this._entryRevision = revision();
      const seq = this._entrySeq;
      const current = () => {
        if (this._unloaded || this._hidden || seq !== this._entrySeq) return false;
        if (this._sameEntrySession()) return true;
        this.reloadEntry();
        return false;
      };
      const failed = (res, statusCode) => {
        if (!current()) return;
        const status = failureStatus(res, statusCode);
        if (res && res.reason === 'sessionChanged') { this.reloadEntry(); return; }
        if (status === 401 || status === 2) this._entryError('no-permission', '登录后查看订单', '登录');
        else if (status === 403) this._entryError('forbidden', '当前账号无权查看此订单', '返回');
        else if (status === 404) this._entryError('not-found', '未找到订单', '重试');
        else if (status === 400) this._entryError('missing-param', '订单链接无效', '返回');
        else this._entryError('network', '订单暂未加载成功', '重试');
      };
      app.sendRequest({
        url: '/api/order-center/detail', method: 'POST', write: false,
        hideLoading: true, autoErrorToast: false, data: { outTradeNo: this._outTradeNo },
        success: res => {
          if (!current()) return;
          if (!res || Number(res.code) !== 200) { failed(res); return; }
          const summary = summaryOf(res.data);
          if (!summary) { this._entryError('data', '订单信息暂不可用', '重试'); return; }
          this.setData({ id: summary.kind === 'REGISTRATION' ? summary.id : '',
            summary: summary.kind === 'REGISTRATION' ? null : summary, entryState: 'ready', entryAction: '' });
        },
        fail: failed,
        successStatusAbnormal: failed,
      });
    },
    onEntryAction() {
      if (this._unloaded || this._hidden) return;
      if (this.data.entryAction === '返回') { this.backToOrderList(); return; }
      if (this.data.entryAction !== '登录') { this.reloadEntry(); return; }
      if (login.hasSession(app)) return retryLogin(this, app, this.reloadEntry);
      return login.withLogin({ reason: '登录后即可查看订单', owner: this,
        target: () => [this._outTradeNo] }, () => this.reloadEntry());
    },
    backToOrderList(event) {
      this.triggerEvent('back', event && event.detail || {});
    },
  },
});
