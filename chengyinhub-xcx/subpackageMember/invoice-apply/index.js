// 申请开票（X5 拆页之一，照抄结算页 baoming）：发票抬头 + 按开票单位分组的订单 + 开票金额 + 底栏提交。
// 原请求号、冻结订单与结果未知的处理原样取自 W8；新增 C4「全选本单位」，其他开票单位和未开通单位的订单整行置灰、点一下才提示。
const cyToast = require('../../utils/toast.js');
const { INVOICE_METHODS, baseData, invoice, titleLine, PAGES } = require('../invoice/base.js');

const MAX = 50;
const ORDER_CHANGED = ['INVOICE_ORDER_NOT_ELIGIBLE', 'INVOICE_ORDER_ALREADY_INVOICED', 'INVOICE_STATE_CONFLICT'];
const cents = value => { const m = /^(\d+)(?:\.(\d{1,2}))?$/.exec(String(value)); return m ? Number(m[1]) * 100 + Number((m[2] || '').padEnd(2, '0')) : 0; };

function backToList() {
  const stack = getCurrentPages(), index = stack.map(row => '/' + row.route).lastIndexOf(PAGES.list);
  if (index >= 0) wx.navigateBack({ delta: stack.length - 1 - index });
  else wx.redirectTo({ url: PAGES.list });
}

Page({
  ...INVOICE_METHODS,
  data: Object.assign(baseData(), { titles: [], title: null, groups: [], selectedCount: 0, total: '0', sheet: false,
    submit: '', submitSub: '', pending: false, requestId: '', titlesLoaded: false, ordersLoaded: false }),
  invoiceReset() {
    return { titles: [], title: null, groups: [], selectedCount: 0, total: '0', sheet: false,
    submit: '', submitSub: '', pending: false, requestId: '', titlesLoaded: false, ordersLoaded: false };
  },
  invoiceLoad() {
    // 选择只在本人本次进入期间保留：去管理抬头再回来不丢；换号清空。
    if (this._choiceOwner !== this._owner) { this._choiceOwner = this._owner; this._chosen = []; this._titleId = ''; }
    this._orders = []; this._frozen = [];
    try {
      const pending = this._workflow.pendingRequest(this.key('apply'));
      if (pending) {
        const frozen = wx.getStorageSync(this.contextKey());
        const valid = frozen && frozen.requestId === pending.requestId && invoice.exactId(frozen.titleId)
          && Array.isArray(frozen.orderIds) && frozen.orderIds.length && frozen.orderIds.length <= MAX && frozen.orderIds.every(id => invoice.exactId(id));
        this._chosen = valid ? frozen.orderIds.slice() : [];
        this._titleId = valid ? frozen.titleId : '';
        this._frozen = valid ? frozen.orderIds.slice() : [];
      }
      this.setData({ pending: !!pending, requestId: pending && pending.requestId || '', submit: pending ? 'unknown' : '' });
    } catch (error) {
      this.setData({ pending: true, submit: 'unknown' });
    }
    this.loadTitles(); this.loadOrders();
  },
  loadTitles() {
    this.read('titles', {}, data => this.rows(data, invoice.title), titles => {
      const rows = titles.map(row => Object.assign({}, row, { line: titleLine(row) }));
      const title = rows.find(row => row.id === this._titleId) || (!this.data.pending && (rows.find(row => row.isDefault) || rows[0])) || null;
      this._titleId = title ? title.id : '';
      this.setData({ titles: rows, title, titlesLoaded: true });
    }, 'loading');
  },
  loadOrders() {
    this.read('orders/eligible', {}, data => this.rows(data, invoice.order), orders => {
      this._orders = orders;
      if (!this.data.pending) this._chosen = this._chosen.filter(id => orders.some(row => row.orderId === id && row.issuerId));
      this.setData({ ordersLoaded: true });
      this.render();
    });
  },
  // 分组：已开通的开票单位各一组（按出现顺序），未开通的放最后一组「暂不可申请」。
  render() {
    const chosen = this._chosen, orders = this._orders;
    const first = orders.find(row => chosen.includes(row.orderId));
    const issuerId = first ? first.issuerId : '';
    const groups = [], byIssuer = {};
    let blocked = null, total = 0;
    orders.forEach(row => {
      const selected = chosen.includes(row.orderId);
      if (selected) total += cents(row.amount);
      const item = { orderId: row.orderId, name: row.orderTitle + (row.ticketName ? ' · ' + row.ticketName : ''), amount: row.amount, selected,
        disabled: !row.issuerId || !!issuerId && row.issuerId !== issuerId };
      if (!row.issuerId) { blocked = blocked || { key: 'blocked', blocked: true, rows: [] }; blocked.rows.push(item); return; }
      if (!byIssuer[row.issuerId]) { byIssuer[row.issuerId] = { key: row.issuerId, issuerId: row.issuerId, name: row.issuerName, rows: [] }; groups.push(byIssuer[row.issuerId]); }
      byIssuer[row.issuerId].rows.push(item);
    });
    if (blocked) groups.push(blocked);
    groups.forEach(group => { group.canPickAll = !group.blocked && (!issuerId || issuerId === group.issuerId); });
    this._issuerId = issuerId;
    this.setData({ groups, selectedCount: chosen.length, total: (total / 100).toFixed(2) });
  },
  reload() { this.setData({ error: '' }); this.loadTitles(); this.loadOrders(); },
  locked() { return !this.ready() || this.data.pending || this.data.writing; },
  pick(e) {
    if (this.locked()) return;
    const id = invoice.exactId(e.currentTarget.dataset.id), row = this._orders.find(item => item.orderId === id);
    if (!row) return;
    if (!row.issuerId) { cyToast('开票单位开通后可申请'); return; }
    if (this._issuerId && row.issuerId !== this._issuerId) { cyToast('不同开票单位需分开申请'); return; }
    const at = this._chosen.indexOf(id);
    if (at >= 0) this._chosen.splice(at, 1);
    else if (this._chosen.length >= MAX) { this.setData({ submit: 'max' }); return; }
    else this._chosen.push(id);
    if (this.data.submit === 'max' || this.data.submit === 'failed') this.setData({ submit: '' });
    this.render();
  },
  // C4：只选本单位可开票的订单；已全选时再点一下取消。
  pickAll(e) {
    if (this.locked()) return;
    const issuerId = invoice.exactId(e.currentTarget.dataset.issuer);
    if (!issuerId) return;
    if (this._issuerId && this._issuerId !== issuerId) { cyToast('不同开票单位需分开申请'); return; }
    const ids = this._orders.filter(row => row.issuerId === issuerId).map(row => row.orderId);
    if (ids.every(id => this._chosen.includes(id))) this._chosen = [];
    else if (ids.length > MAX) { this.setData({ submit: 'max' }); return; }
    else this._chosen = ids;
    if (this.data.submit === 'max' || this.data.submit === 'failed') this.setData({ submit: '' });
    this.render();
  },
  reselect() {
    if (this.locked()) return;
    this._chosen = [];
    this.setData({ submit: '', submitSub: '' });
    this.render();
    this.loadOrders();
  },
  openTitles() { if (!this.data.pending) this.setData({ sheet: true }); },
  closeTitles() { this.setData({ sheet: false }); },
  pickTitle(e) {
    if (this.locked()) return;
    const id = invoice.exactId(e.currentTarget.dataset.id), title = this.data.titles.find(row => row.id === id);
    if (!title) return;
    this._titleId = id;
    this.setData({ title, sheet: false });
  },
  manageTitles() { this.setData({ sheet: false }); this.go({ currentTarget: { dataset: { page: 'titles' } } }); },
  addTitle() { this.go({ currentTarget: { dataset: { page: 'edit' } } }); },
  apply() {
    if (this.locked() || this.data.submit) return;
    const titleId = this._titleId, orderIds = this._chosen.slice();
    if (!invoice.exactId(titleId) || !this.data.titles.some(row => row.id === titleId) || !orderIds.length) return;
    if (orderIds.length > MAX) { this.setData({ submit: 'max' }); return; }
    this.mutate('apply', { titleId, orderIds }, 'apply', (value, requestId) => {
      const row = invoice.application(value);
      if (row.requestId !== requestId || typeof value.replayed !== 'boolean'
          || row.orders.length !== orderIds.length || !orderIds.every(id => row.orders.some(order => order.orderId === id))) throw Error('查询结果与原申请不一致');
      return row;
    }, result => {
      if (result.status === 'success') {
        this.clearContext(result.value.requestId); this._chosen = [];
        this.setData({ pending: false, requestId: '', submit: '' });
        backToList(); return;
      }
      if (result.status === 'failed') {
        this.clearContext(result.requestId);
        const code = result.response && result.response.errorCode;
        this.setData({ pending: false, requestId: '', submit: code === 'INVOICE_ORDERS_TOO_MANY' ? 'max' : 'failed',
          submitSub: ORDER_CHANGED.includes(code) ? '订单有变化，请重新选择' : String(result.response && result.response.msg || '') });
        this.loadOrders();
        return;
      }
      this.setData({ pending: true, requestId: result.requestId || '', submit: 'unknown' });
    }, body => {
      this.setData({ pending: true, requestId: body.requestId });
      this._frozen = orderIds.slice();
      // 只持久化精确编号；完整税号、抬头名称和金额都不进入本机待办。
      const context = { requestId: body.requestId, titleId, orderIds: orderIds.slice() };
      try {
        wx.setStorageSync(this.contextKey(), context);
        if (JSON.stringify(wx.getStorageSync(this.contextKey())) !== JSON.stringify(context)) throw Error('not saved');
      } catch (error) { return false; }
      return true;
    });
  },
  // 「查询结果」：按原请求号回读，找到并核对原订单后回到我的发票。
  queryResult() {
    if (!this.ready() || !this.data.pending || this.data.writing || !this.data.requestId) return;
    const requestId = this.data.requestId, frozen = this._frozen || [];
    this.read('request', { requestId }, invoice.application, row => {
      if (row.requestId !== requestId) throw Error('申请详情与原请求不匹配');
      this.read('detail', { id: row.id }, invoice.application, detail => {
        if (detail.id !== row.id || detail.requestId !== requestId) throw Error('申请详情与原请求不匹配');
        if (frozen.length && (detail.orders.length !== frozen.length
            || !frozen.every(orderId => detail.orders.some(order => order.orderId === orderId)))) throw Error('原申请订单与冻结输入不匹配');
        if (!this._workflow.confirmRequest(this.key('apply'), requestId)) throw Error('本机原请求仍待确认，请勿重新申请');
        this.clearContext(requestId);
        this._chosen = [];
        backToList();
      });
    }, '', res => {
      if (res && res.errorCode === 'INVOICE_NOT_FOUND') { cyToast('暂未查到这次申请'); return true; }
      return false;
    });
  },
});
