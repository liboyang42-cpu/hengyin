// 发票 5 页共用的会话、读写与待确认恢复（10-28 X5：W8 单页拆成 我的发票 / 发票详情 / 申请开票 / 发票抬头 / 抬头编辑）。
// 读写、原请求号恢复与身份切换的逻辑原样取自 W8 subpackageMember/invoice/index.js，只按页拆开；
// 待确认申请 / 抬头保存都存在按会员隔离的本机待办里，任一页打开都读同一份。
const app = getApp();
const invoice = require('../utils/user-invoice.js');
const { createVerificationWorkflow } = require('../../utils/verification-readback.js');
const { retryLogin, waitForInitialSession } = require('../../utils/session/login-reload.js');
const modal = require('../../utils/modal.js');

const PAGES = {
  list: '/subpackageMember/invoice/index',
  detail: '/subpackageMember/invoice-detail/index',
  apply: '/subpackageMember/invoice-apply/index',
  titles: '/subpackageMember/invoice-titles/index',
  edit: '/subpackageMember/invoice-title-edit/index',
};
// C2：后台的待开具 / 已认领 / 处理中 / 结果待确认，对玩家统一叫「开具中」。
const PLAYER_STATUS = { SUBMITTED: 'ing', CLAIMED: 'ing', PROCESSING: 'ing', ISSUE_UNKNOWN: 'ing', PAUSED: 'paused',
  ISSUED: 'issued', REJECTED: 'rejected', CANCELLED: 'cancelled' };
const STATUS_TEXT = { ing: '开具中', paused: '退款核对中', issued: '已开具', rejected: '已驳回', cancelled: '已撤回' };

function pad(n) { return n < 10 ? '0' + n : String(n); }
// 10月27日 10:30（术语表日期写法）；读不出就不显示。
function timeText(value) {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/.exec(String(value || ''));
  if (m) return Number(m[2]) + '月' + Number(m[3]) + '日 ' + m[4] + ':' + m[5];
  const date = typeof value === 'number' ? new Date(value) : null;
  if (!date || isNaN(date.getTime())) return '';
  return (date.getMonth() + 1) + '月' + date.getDate() + '日 ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
}
function view(row) {
  const key = PLAYER_STATUS[row.status];
  return Object.assign({}, row, { statusKey: key, playerStatus: STATUS_TEXT[key], createText: timeText(row.createTime),
    issuedText: timeText(row.issuedAt) });
}
function titleLine(row) {
  return [row.titleType === 'COMPANY' ? '单位' : '个人', row.taxNoMasked, row.isDefault ? '默认' : ''].filter(Boolean).join(' · ');
}
// 跳到另一张发票页：已在栈里就退回去，免得同一页叠两层。
function go(page, query) {
  const url = PAGES[page] + (query ? '?' + query : '');
  const stack = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
  const index = stack.map(row => '/' + String(row && row.route || '')).lastIndexOf(PAGES[page]);
  if (index >= 0 && index < stack.length - 1 && !query) { wx.navigateBack({ delta: stack.length - 1 - index }); return; }
  wx.navigateTo({ url });
}

// 各页 Page({ ...INVOICE_METHODS, ... }) 混入；页面自己提供 invoiceReset()（离页/换号时清掉的字段）、
// invoiceLoad()（登录且就绪后读数据），可选 invoiceOnLoad(options) / invoiceStop()。
const baseData = () => ({ writing: false, loading: false, error: '', unavailable: false, login: false });
const INVOICE_METHODS = {
  onLoad(options) {
    this._hidden = true; this._options = options || {};
    if (this.invoiceOnLoad) this.invoiceOnLoad(this._options);
  },
  owner() { return invoice.exactId(app.getUserID()); },
  identity() { return this.owner() + '|' + String(app.getAuthorization && app.getAuthorization() || ''); },
  current(epoch) {
    if (this._hidden || this._unloaded || epoch !== this._epoch) return false;
    if (this._identity !== this.identity()) { this._hidden = true; this.onShow(); return false; }
    return true;
  },
  key(action) { return this._owner + ':user-invoice:' + action; },
  contextKey() { return 'user_invoice_apply_v1:' + this._owner; },
  clearContext(requestId) {
    try {
      const row = wx.getStorageSync(this.contextKey());
      if (row && row.requestId === requestId) wx.removeStorageSync(this.contextKey());
    } catch (error) { /* 正向回执已确认，旧上下文不能创建或重放申请。 */ }
  },
  onShow() {
    if (this._unloaded || !this._hidden && this._identity === this.identity()) return;
    this.stop();
    this._hidden = false;
    this._owner = this.owner(); this._identity = this.identity(); this._reads = {}; this._tasks = [];
    this._reset();
    if (!this._owner || !app.getAuthorization || !app.getAuthorization()) {
      this.setData({ login: true });
      waitForInitialSession(this, app, function () { this._hidden = true; this.onShow(); });
      return;
    }
    this._workflow = createVerificationWorkflow({ storage: wx, requestIdentity: true, getOwner: () => this.owner() });
    this.invoiceLoad();
  },
  stop() {
    if (this.invoiceStop) this.invoiceStop();
    this._epoch = (this._epoch || 0) + 1;
    if (this._workflow) this._workflow.destroy();
    this._workflow = null;
    (this._tasks || []).forEach(task => { try { if (task && task.abort) task.abort(); } catch (error) {} });
    this._tasks = [];
  },
  onHide() { this._hidden = true; this.stop(); this._reset(); },
  onUnload() { this.onHide(); this._unloaded = true; },
  ready() {
    if (this._hidden || this._unloaded) return false;
    if (this._identity !== this.identity()) { this._hidden = true; this.onShow(); return false; }
    return !this.data.login && !this.data.unavailable && !!this._workflow;
  },
  _reset() { this.setData(Object.assign(baseData(), this.invoiceReset())); },
  _readLoading(flag, value) { if (flag) this.setData({ [flag]: value }); },
  login() { return retryLogin(this, app, function () { this._hidden = true; this.onShow(); }); },
  off() { this.stop(); this._reset(); this.setData({ unavailable: true }); },
  // received 抛错 = 回读对不上；failed(res) 收后端失败，返回 false 时仍按读失败处理。
  read(path, data, decode, received, flag, failed) {
    if (!this.ready()) return;
    const epoch = this._epoch, sequence = this._reads[path] = (this._reads[path] || 0) + 1;
    const current = () => this.current(epoch) && this._reads[path] === sequence;
    this._readLoading(flag, true);
    let settled = false;
    const finish = (res, status, transportFailure) => {
      if (settled || !current()) return;
      settled = true;
      this._readLoading(flag, false);
      if (res && res.errorCode === 'INVOICE_FEATURE_OFF') { this.off(); return; }
      if (Number(status || res && res.code) === 401 || Number(res && res.code) === 2) {
        this.stop(); this._reset(); this.setData({ login: true }); return;
      }
      if (!transportFailure && (!status || Number(status) === 200) && res && Number(res.code) === 200) {
        try { received(decode(res.data)); return; } catch (error) { this.setData({ error: 'read' }); return; }
      }
      if (failed && failed(res, transportFailure) !== false) return;
      this.setData({ error: 'read' });
    };
    try { const task = invoice.send(app, path, { method: 'POST', data: JSON.stringify(data || {}),
      header: { 'Content-Type': 'application/json' }, hideLoading: true, autoErrorToast: false,
      success: res => finish(res, 200, false), fail: (res, status) => finish(res, status, true),
      successStatusAbnormal: (res, status) => finish(res, status, true) });
    this._tasks.push(task);
    } catch (error) { finish(null, undefined, true); }
  },
  rows(data, decode) { if (!Array.isArray(data)) throw Error('列表数据无法确认'); return data.map(decode); },
  // done(result) 由各页处理 success / failed / unknown；before(body) 返回 false = 本机待办存不下，按结果未知处理且不发请求。
  mutate(path, payload, action, decode, done, before) {
    if (!this.ready() || this.data.writing) return;
    const epoch = this._epoch, key = this.key(action);
    this.setData({ writing: true });
    this._workflow.run(key, (settle, identity) => {
      const body = ['apply', 'titles/save-request'].includes(path) ? Object.assign({}, payload, { requestId: identity.requestId }) : payload;
      if (before && before(body) === false) { settle({ status: 'unknown' }); return null; }
      return invoice.send(app, path, { method: 'POST',
        header: { 'Content-Type': 'application/json' }, data: JSON.stringify(body), hideLoading: true, autoErrorToast: false,
        success: res => settle(this.current(epoch) ? invoice.classify(res, 200, value => decode(value, identity.requestId)) : { status: 'unknown' }),
        fail: (res, status) => settle(this.current(epoch) ? invoice.classify(res, status || 0, value => decode(value, identity.requestId), true) : { status: 'unknown' }),
        successStatusAbnormal: (res, status) => settle(this.current(epoch) ? invoice.classify(res, status, value => decode(value, identity.requestId), true) : { status: 'unknown' }) });
    }, result => {
      if (!this.current(epoch)) return;
      this.setData({ writing: false });
      if (result.status === 'success') {
        // A pre-receipt read may still contain the old state. Only fresh reads
        // may replace a confirmed write, including after a cancellation.
        Object.keys(this._reads).forEach(name => { this._reads[name] += 1; });
        this.setData({ loading: false });
      }
      if (result.response && result.response.errorCode === 'INVOICE_FEATURE_OFF') { this.off(); return; }
      done(result);
    });
  },
  // 撤回只对开具中（SUBMITTED）的申请；列表与详情同一份确认框。
  cancelApplication(id, received) {
    const epoch = this._epoch;
    modal.show({ title: '撤回发票申请', content: '撤回后，订单可重新申请开票。', confirmText: '撤回申请', danger: true,
      success: result => {
        if (!result.confirm || !this.current(epoch)) return;
        this.mutate('cancel', { id }, 'cancel:' + id, value => {
          const row = invoice.application(value);
          if (row.id !== id || row.status !== 'CANCELLED') throw Error('撤回结果无法确认'); return row;
        }, received);
      } });
  },
  go(e) {
    const data = e && e.currentTarget && e.currentTarget.dataset || {};
    const id = invoice.exactId(data.id);
    if (PAGES[data.page]) go(data.page, id ? 'id=' + id : '');
  },
  onBack() {
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 });
    else wx.switchTab({ url: '/pages/member/index/index' });
  },
};

module.exports = { INVOICE_METHODS, baseData, invoice, view, titleLine, timeText, go, PAGES };
