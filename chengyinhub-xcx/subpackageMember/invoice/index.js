const app = getApp();
const invoice = require('../../utils/user-invoice.js');
const { createVerificationWorkflow } = require('../../utils/verification-readback.js');
const { createWriteActionWorkflow } = require('../../utils/write-action-workflow.js');
const { retryLogin, waitForInitialSession } = require('../../utils/session/login-reload.js');
const modal = require('../../utils/modal.js');

const draft = () => ({ id: '', titleType: 'PERSONAL', titleName: '', taxNo: '', isDefault: false });
const empty = () => ({ titles: [], orders: [], records: [], selectedTitle: '', selectedCount: 0,
  detail: null, editing: false, draft: draft(), pending: false, requestId: '', frozenTitleId: '', frozenOrderIds: [], titlePending: false,
  titleRequestId: '', titleRetryable: false,
  cancelPending: false, writing: false, loading: false, recovering: false, error: '', notice: '', unavailable: false, login: false });

Page({
  data: Object.assign({ tab: 'apply' }, empty()),
  onLoad(options) {
    this._hidden = true;
    this._initialDetail = invoice.exactId(options && options.id);
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
    try {
      const pending = this._workflow.pendingRequest(this.key('apply'));
      const titleRequest = this._workflow.pendingRequest(this.key('title-save'));
      const titlePending = !!titleRequest;
      let frozen = null;
      if (pending) {
        frozen = wx.getStorageSync(this.contextKey());
        if (!frozen || frozen.requestId !== pending.requestId || !invoice.exactId(frozen.titleId)
            || !Array.isArray(frozen.orderIds) || !frozen.orderIds.length || frozen.orderIds.length > 50
            || frozen.orderIds.some(id => !invoice.exactId(id))) frozen = null;
      }
      this.setData({ pending: !!pending, requestId: pending && pending.requestId || '', titlePending,
        titleRequestId: titleRequest && titleRequest.requestId || '',
        frozenTitleId: frozen ? frozen.titleId : '', frozenOrderIds: frozen ? frozen.orderIds : [],
        selectedTitle: frozen ? frozen.titleId : '',
        notice: titlePending ? '上次抬头保存结果待确认，请查询原保存结果，勿重复新建。' : '' });
    } catch (error) {
      this.setData({ pending: true, titlePending: true, error: '本机待办无法读取，请检查存储并联系平台核对，勿重复提交' });
    }
    this.reload();
    if (this.data.titlePending) this.queryTitleSave();
    if (this._initialDetail) { this.openDetailId(this._initialDetail); this._initialDetail = ''; }
  },
  stop() {
    this._titleIntent = null;
    if (this._titleRetry) this._titleRetry.destroy();
    this._titleRetry = null;
    this._epoch = (this._epoch || 0) + 1;
    if (this._workflow) this._workflow.destroy();
    this._workflow = null;
    (this._tasks || []).forEach(task => { try { if (task && task.abort) task.abort(); } catch (error) {} });
    this._tasks = [];
  },
  onHide() {
    this._hidden = true; this.stop();
    this._reset();
  },
  onUnload() { this.onHide(); this._unloaded = true; },
  ready() {
    if (this._hidden || this._unloaded) return false;
    if (this._identity !== this.identity()) { this._hidden = true; this.onShow(); return false; }
    return !this.data.login && !this.data.unavailable && !!this._workflow;
  },
  _reset() {
    this.setData({ titles: [], orders: [], records: [], selectedTitle: '', selectedCount: 0,
      detail: null, editing: false, draft: draft(), pending: false, requestId: '', frozenTitleId: '', frozenOrderIds: [],
      titlePending: false, titleRequestId: '', titleRetryable: false, cancelPending: false, writing: false, loading: false, recovering: false,
      error: '', notice: '', unavailable: false, login: false });
  },
  _readLoading(flag, value) {
    if (flag === 'loading') this.setData({ loading: value });
    if (flag === 'recovering') this.setData({ recovering: value });
  },
  reload() {
    if (!this.ready()) return;
    this.setData({ error: '' });
    this.loadTitles(); this.loadOrders(); this.loadRecords();
  },
  login() { return retryLogin(this, app, function () { this._hidden = true; this.onShow(); }); },
  off() {
    this.stop();
    this._reset(); this.setData({ unavailable: true });
  },
  read(path, data, decode, received, flag) {
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
        this.stop(); this._reset(); this.setData({ login: true, error: '请重新登录后查看本人发票' }); return;
      }
      if (!transportFailure && (!status || Number(status) === 200) && res && Number(res.code) === 200) {
        try { received(decode(res.data)); return; } catch (error) {
          this.setData({ error: error.message || '回读数据无法确认，请稍后刷新' }); return;
        }
      }
      this.setData({ error: res && res.msg || '发票数据加载失败，请重试；待确认申请仍保留原请求号' });
    };
    try { const task = invoice.send(app, path, { method: 'POST', data: JSON.stringify(data || {}),
      header: { 'Content-Type': 'application/json' }, hideLoading: true, autoErrorToast: false,
      success: res => finish(res, 200, false), fail: (res, status) => finish(res, status, true),
      successStatusAbnormal: (res, status) => finish(res, status, true) });
    this._tasks.push(task);
    } catch (error) { finish(null, undefined, true); }
  },
  rows(data, decode) { if (!Array.isArray(data)) throw Error('列表数据无法确认'); return data.map(decode); },
  loadTitles() {
    this.read('titles', {}, data => this.rows(data, invoice.title), titles => {
      const selected = titles.find(row => row.id === this.data.selectedTitle) || titles.find(row => row.isDefault);
      this.setData({ titles, selectedTitle: this.data.pending ? this.data.frozenTitleId : selected ? selected.id : '' });
    }, 'loading');
  },
  loadOrders() {
    this.read('orders/eligible', {}, data => this.rows(data, invoice.order), orders => {
      orders.forEach(row => { row.selected = this.data.pending ? this.data.frozenOrderIds.includes(row.orderId)
        : this.data.orders.some(old => old.orderId === row.orderId && old.selected); });
      this.setData({ orders, selectedCount: orders.filter(row => row.selected).length });
    });
  },
  loadRecords() {
    this.read('list', {}, data => this.rows(data, invoice.applicationSummary), records => {
      this.setData({ records });
      if (!this.data.pending) return;
      const found = records.find(row => row.requestId === this.data.requestId);
      if (found) this.openDetailId(found.id, this.data.requestId);
      else this.lookupOriginal();
    }, 'recovering');
  },
  lookupOriginal() {
    if (!this.ready() || !this.data.pending || this.data.writing || !this.data.requestId) return;
    const requestId = this.data.requestId;
    this.setData({ notice: '正在按原请求号核对；暂未查到不代表未提交，请勿重新申请。' });
    this.read('request', { requestId }, invoice.application, row => {
      if (!this.data.pending || this.data.requestId !== requestId) return;
      if (row.requestId !== requestId) throw Error('申请详情与原请求不匹配');
      this.openDetailId(row.id, requestId);
    }, 'recovering');
  },
  queryOriginal() { this.lookupOriginal(); },
  tab(e) {
    if (!this.ready()) return;
    const tab = e.currentTarget.dataset.tab;
    if (['apply', 'records', 'titles'].includes(tab)) {
      this._reads.detail = (this._reads.detail || 0) + 1;
      this.setData({ tab, detail: null, editing: false, draft: draft(), error: '' });
    }
  },
  pickTitle(e) {
    if (!this.ready() || this.data.pending || this.data.writing) return;
    const id = invoice.exactId(e.currentTarget.dataset.id);
    if (this.data.titles.some(row => row.id === id)) this.setData({ selectedTitle: id });
  },
  pickOrder(e) {
    if (!this.ready() || this.data.pending || this.data.writing) return;
    const id = invoice.exactId(e.currentTarget.dataset.id), orders = this.data.orders.map(row => Object.assign({}, row));
    const row = orders.find(item => item.orderId === id);
    if (!row) return;
    if (!row.selected && this.data.selectedCount >= 50) { this.setData({ error: '一次最多合并50笔订单' }); return; }
    if (!row.selected && row.issuerId && orders.some(item => item.selected && item.issuerId && item.issuerId !== row.issuerId)) {
      this.setData({ error: '不同开票主体的订单须分开申请' }); return;
    }
    row.selected = !row.selected;
    this.setData({ orders, selectedCount: orders.filter(item => item.selected).length });
  },
  mutate(path, payload, action, decode, received) {
    if (!this.ready() || this.data.writing) return;
    const epoch = this._epoch, key = this.key(action);
    this.setData({ writing: true, error: '', notice: '' });
    this._workflow.run(key, (done, identity) => {
      const body = ['apply', 'titles/save-request'].includes(path) ? Object.assign({}, payload, { requestId: identity.requestId }) : payload;
      if (path === 'titles/save-request') {
        // Sensitive input stays only in this visible page's memory, never in wx storage.
        this._titleIntent = Object.freeze(Object.assign({}, body));
        this.setData({ titleRequestId: identity.requestId, titleRetryable: true });
      }
      if (path === 'apply') {
        this.setData({ pending: true, requestId: identity.requestId, frozenTitleId: payload.titleId, frozenOrderIds: payload.orderIds.slice() });
        // 只持久化精确编号；完整税号、抬头名称和金额都不进入本机待办。
        const context = { requestId: identity.requestId, titleId: payload.titleId, orderIds: payload.orderIds.slice() };
        try {
          wx.setStorageSync(this.contextKey(), context);
          if (JSON.stringify(wx.getStorageSync(this.contextKey())) !== JSON.stringify(context)) throw Error('not saved');
        } catch (error) { done({ status: 'unknown' }); return null; }
      }
      return invoice.send(app, path, { method: 'POST',
        header: { 'Content-Type': 'application/json' }, data: JSON.stringify(body), hideLoading: true, autoErrorToast: false,
        success: res => done(this.current(epoch) ? invoice.classify(res, 200, value => decode(value, identity.requestId)) : { status: 'unknown' }),
        fail: (res, status) => done(this.current(epoch) ? invoice.classify(res, status || 0, value => decode(value, identity.requestId), true) : { status: 'unknown' }),
        successStatusAbnormal: (res, status) => done(this.current(epoch) ? invoice.classify(res, status, value => decode(value, identity.requestId), true) : { status: 'unknown' }) });
    }, result => {
      if (!this.current(epoch)) return;
      this.setData({ writing: false });
      if (result.status === 'success') {
        // A pre-receipt read may still contain the old state. Only fresh reads
        // may replace a confirmed write, including after a cancellation.
        Object.keys(this._reads).forEach(path => { this._reads[path] += 1; });
        this.setData({ loading: false, recovering: false });
        received(result.value); return;
      }
      if (result.response && result.response.errorCode === 'INVOICE_FEATURE_OFF') { this.off(); return; }
      if (path === 'apply') {
        this.setData({ pending: result.status !== 'failed', requestId: result.requestId || '' });
        if (result.status === 'failed') this.clearContext(result.requestId);
      }
      if (path === 'titles/save-request') {
        if (result.status !== 'unknown') this._titleIntent = null;
        this.setData({ titlePending: result.status === 'unknown', titleRequestId: result.requestId || '',
          titleRetryable: !!this._titleIntent, draft: Object.assign({}, this.data.draft, { taxNo: '' }) });
      }
      if (path === 'cancel') this.setData({ cancelPending: result.status === 'unknown' });
      this.setData({ error: result.status === 'unknown' ? '操作结果待确认，请核对本人记录；未出现记录不能证明未提交，请勿重复操作。'
        : result.response && result.response.msg || '本次未提交，请检查本机存储和登录后再试' });
    });
  },
  apply() {
    if (!this.ready() || this.data.writing || this.data.pending) return;
    const titleId = this.data.selectedTitle, orderIds = this.data.orders.filter(row => row.selected).map(row => row.orderId);
    if (!invoice.exactId(titleId) || !this.data.titles.some(row => row.id === titleId) || !orderIds.length || orderIds.length > 50) {
      this.setData({ error: '请选择发票抬头和1至50笔可开票订单' }); return;
    }
    this.mutate('apply', { titleId, orderIds }, 'apply', (value, requestId) => {
      const row = invoice.application(value);
      if (row.requestId !== requestId || typeof value.replayed !== 'boolean'
          || row.orders.length !== orderIds.length || !orderIds.every(id => row.orders.some(order => order.orderId === id))) throw Error('查询结果与原申请不一致');
      return row;
    }, row => {
      this.clearContext(row.requestId);
      this.setData({ pending: false, requestId: '', selectedCount: 0, selectedTitle: '', orders: [], tab: 'records', detail: row, notice: '申请已提交' });
      this.loadRecords(); this.loadOrders();
    });
  },
  editTitle(e) {
    if (!this.ready() || this.data.writing || this.data.titlePending) return;
    const id = invoice.exactId(e && e.currentTarget.dataset.id), row = this.data.titles.find(item => item.id === id);
    this.setData({ editing: true, draft: row ? { id: row.id, titleType: row.titleType,
      titleName: row.titleName, taxNo: '', isDefault: row.isDefault } : draft(), error: '' });
  },
  closeEditor() { if (!this.data.writing) this.setData({ editing: false, draft: draft() }); },
  input(e) {
    if (!this.ready() || this.data.writing || this.data.titlePending) return;
    const field = e.currentTarget.dataset.field;
    if (!['titleName', 'taxNo'].includes(field)) return;
    this.setData({ draft: Object.assign({}, this.data.draft, { [field]: String(e.detail.value || '') }) });
  },
  titleType(e) {
    if (!this.ready() || this.data.writing || this.data.titlePending) return;
    const type = e.currentTarget.dataset.type;
    if (['PERSONAL', 'COMPANY'].includes(type)) this.setData({ draft: Object.assign({}, this.data.draft, { titleType: type, taxNo: '' }) });
  },
  defaultTitle(e) {
    if (this.ready() && !this.data.writing && !this.data.titlePending) this.setData({ draft: Object.assign({}, this.data.draft, { isDefault: e.detail.value === true }) });
  },
  saveTitle() {
    if (!this.ready() || this.data.titlePending || this.data.writing) return;
    let payload;
    try { payload = invoice.titlePayload(this.data.draft); } catch (error) { this.setData({ error: error.message }); return; }
    this.mutate('titles/save-request', payload, 'title-save', (value, requestId) => invoice.titleSaveReceipt(value, requestId, payload.id), row => this.titleSaved(row));
  },
  titleSaved(row) {
    this._titleIntent = null;
    if (this._titleRetry) this._titleRetry.destroy();
    this._titleRetry = null;
    this._reads['titles/request'] = (this._reads['titles/request'] || 0) + 1;
    this.setData({ editing: false, draft: draft(), selectedTitle: row.titleId, titlePending: false, writing: false,
      titleRequestId: '', titleRetryable: false, notice: '原抬头保存已确认，当前抬头信息以刷新结果为准' });
    this.loadTitles();
  },
  confirmTitleSave(row) {
    if (!this.data.titlePending || row.requestId !== this.data.titleRequestId) throw Error('原保存结果已变化');
    if (!this._workflow.confirmRequest(this.key('title-save'), row.requestId)) throw Error('本机原保存待办仍未清除，请继续核对');
    this.titleSaved(row);
  },
  queryTitleSave() {
    if (!this.ready() || this.data.writing || !this.data.titlePending || !this.data.titleRequestId) return;
    const requestId = this.data.titleRequestId;
    const titleId = this._titleIntent && this._titleIntent.id;
    this.read('titles/request', { requestId }, value => invoice.titleSaveReceipt(value, requestId, titleId), row => this.confirmTitleSave(row));
  },
  retryTitleSave() {
    if (!this.ready() || this.data.writing || !this.data.titlePending || !this._titleIntent
        || this._titleIntent.requestId !== this.data.titleRequestId) return;
    const body = this._titleIntent, epoch = this._epoch;
    if (this._titleRetry) this._titleRetry.destroy();
    this._titleRetry = createWriteActionWorkflow();
    this.setData({ writing: true, error: '' });
    this._titleRetry.run('title-retry', done => invoice.send(app, 'titles/save-request', {
      method: 'POST', header: { 'Content-Type': 'application/json' }, data: JSON.stringify(body),
      hideLoading: true, autoErrorToast: false,
      success: res => done(invoice.classify(res, 200, value => invoice.titleSaveReceipt(value, body.requestId, body.id))),
      fail: () => done({ status: 'unknown' }), successStatusAbnormal: () => done({ status: 'unknown' }),
    }), result => {
      if (!this.current(epoch)) return;
      this.setData({ writing: false });
      if (result.response && result.response.errorCode === 'INVOICE_FEATURE_OFF') { this.off(); return; }
      if (result.status === 'success') {
        try { this.confirmTitleSave(result.value); return; } catch (error) { this.setData({ error: error.message }); return; }
      }
      this.setData({ error: '原保存结果仍待确认，已保留原请求及冻结输入；请继续查询结果。' });
    });
  },
  deleteTitle(e) {
    if (!this.ready() || this.data.writing) return;
    const id = invoice.exactId(e.currentTarget.dataset.id), epoch = this._epoch;
    if (!this.data.titles.some(row => row.id === id)) return;
    modal.show({ title: '删除发票抬头', content: '删除后不影响已提交的申请，确认删除？', confirmText: '删除',
      success: result => {
        if (!result.confirm || !this.current(epoch)) return;
        this.mutate('titles/delete', { id }, 'title-delete:' + id, value => {
          if (value != null) throw Error('删除结果无法确认'); return null;
        }, () => { this.setData({ notice: '抬头已删除' }); this.loadTitles(); });
      } });
  },
  openDetail(e) { this.openDetailId(invoice.exactId(e.currentTarget.dataset.id)); },
  openDetailId(id, originalRequestId) {
    if (!id || !this.ready()) return;
    this.read('detail', { id }, invoice.application, row => {
      if (row.id !== id || originalRequestId && row.requestId !== originalRequestId) throw Error('申请详情与原请求不匹配');
      if (originalRequestId) {
        const frozen = this.data.frozenOrderIds;
        if (frozen.length && (row.orders.length !== frozen.length
            || !frozen.every(orderId => row.orders.some(order => order.orderId === orderId)))) throw Error('原申请订单与冻结输入不匹配');
        if (!this._workflow.confirmRequest(this.key('apply'), originalRequestId)) throw Error('本机原请求仍待确认，请勿重新申请');
        this.clearContext(originalRequestId);
        this._reads.request = (this._reads.request || 0) + 1;
        this.setData({ pending: false, requestId: '', notice: '已找到原申请' });
      }
      const key = this.key('cancel:' + id), pending = this._workflow.pendingRequest(key);
      if (pending && row.status === 'CANCELLED') this._workflow.confirmRequest(key, pending.requestId);
      this.setData({ detail: row, tab: 'records', cancelPending: !!this._workflow.pendingRequest(key) });
    });
  },
  closeDetail() {
    if (this.data.writing) return;
    this._reads.detail = (this._reads.detail || 0) + 1;
    this.setData({ detail: null, cancelPending: false });
  },
  prepareReapplication() {
    if (!this.ready() || this.data.writing || this.data.pending || this.data.titlePending
        || this.data.cancelPending || !this.data.detail || this.data.detail.status !== 'REJECTED') return;
    Object.keys(this._reads).forEach(path => { this._reads[path] += 1; });
    this.setData({ tab: 'titles', detail: null, editing: false, draft: draft(), titles: [], orders: [],
      selectedTitle: '', selectedCount: 0, error: '',
      notice: '原申请记录保留。请核对或修改抬头，再到申请页选择当前可开票订单，确认后提交新申请。' });
    this.loadTitles(); this.loadOrders();
  },
  refreshDetail() { if (this.data.detail && !this.data.writing) this.openDetailId(this.data.detail.id); },
  cancel() {
    if (!this.ready() || this.data.writing || this.data.cancelPending || !this.data.detail || this.data.detail.status !== 'SUBMITTED') return;
    const id = this.data.detail.id, epoch = this._epoch;
    modal.show({ title: '撤回发票申请', content: '仅未开具的申请可以撤回，确认后订单可重新申请。', confirmText: '撤回申请',
      success: result => {
        if (!result.confirm || !this.current(epoch)) return;
        this.mutate('cancel', { id }, 'cancel:' + id, value => {
          const row = invoice.application(value);
          if (row.id !== id || row.status !== 'CANCELLED') throw Error('撤回结果无法确认'); return row;
        }, row => { this.setData({ detail: row, cancelPending: false, notice: '申请已撤回' }); this.loadRecords(); this.loadOrders(); });
      } });
  },
  onBack() {
    if (this.data.editing) { this.closeEditor(); return; }
    if (this.data.detail) { this.closeDetail(); return; }
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 });
    else wx.switchTab({ url: '/pages/member/index/index' });
  },
});
