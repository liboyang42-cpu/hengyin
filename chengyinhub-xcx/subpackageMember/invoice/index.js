// 我的发票（X5 拆页之一，照抄「我的订单」）：申请记录 + 上次提交结果未确认时的原请求恢复。
// 结果未知的申请只按原请求号回读（/api/invoice/request），不显示请求号；查到后核对原订单再结算本机待办。
const cyToast = require('../../utils/toast.js');
const { INVOICE_METHODS, baseData, invoice, view, go } = require('./base.js');

const TABS = [
  { key: 'all', label: '全部' },
  { key: 'ing', label: '处理中' },
  { key: 'issued', label: '已开具' },
  { key: 'rejected', label: '已驳回' },
  { key: 'cancelled', label: '已撤回' },
];
const IN_TAB = { all: () => true, ing: key => key === 'ing' || key === 'paused', issued: key => key === 'issued',
  rejected: key => key === 'rejected', cancelled: key => key === 'cancelled' };

Page({
  ...INVOICE_METHODS,
  data: Object.assign(baseData(), { records: [], rows: [], pending: false, requestId: '', recovery: '', recovering: false, loaded: false }, { tabs: TABS, tab: 'all' }),
  invoiceReset() {
    return { records: [], rows: [], pending: false, requestId: '', recovery: '', recovering: false, loaded: false };
  },
  invoiceOnLoad(options) {
    // W8 深链 ?id= 仍可用：直接落到发票详情。
    const id = invoice.exactId(options.id);
    if (id) go('detail', 'id=' + id);
  },
  invoiceLoad() {
    try {
      const pending = this._workflow.pendingRequest(this.key('apply'));
      let frozen = null;
      if (pending) {
        frozen = wx.getStorageSync(this.contextKey());
        if (!frozen || frozen.requestId !== pending.requestId || !Array.isArray(frozen.orderIds)) frozen = null;
      }
      this._frozen = frozen ? frozen.orderIds : [];
      this.setData({ pending: !!pending, requestId: pending && pending.requestId || '', recovery: pending ? 'unknown' : '' });
    } catch (error) {
      this.setData({ pending: true, recovery: 'unknown' });
    }
    this.loadRecords();
  },
  loadRecords() {
    this.setData({ error: '' });
    this.read('list', {}, data => this.rows(data, invoice.applicationSummary), records => {
      this.setData({ records: records.map(view), loaded: true });
      this.filter();
      if (!this.data.pending || !this.data.requestId) return;
      const found = records.find(row => row.requestId === this.data.requestId);
      if (found) this.confirmOriginal(found.id, this.data.requestId);
    }, 'loading');
  },
  filter() {
    const keep = IN_TAB[this.data.tab] || IN_TAB.all;
    this.setData({ rows: this.data.records.filter(row => keep(row.statusKey)) });
  },
  onTab(e) {
    const key = e && e.detail && e.detail.key;
    if (!IN_TAB[key] || key === this.data.tab) return;
    this.setData({ tab: key }); this.filter();
  },
  // 「查询结果」/「再查一次」：只按原请求号回读；查不到不代表没提交，订单保持占用。
  queryOriginal() {
    if (!this.ready() || !this.data.pending || this.data.writing || !this.data.requestId) return;
    const requestId = this.data.requestId;
    this.read('request', { requestId }, invoice.application, row => {
      if (!this.data.pending || this.data.requestId !== requestId) return;
      if (row.requestId !== requestId) throw Error('申请详情与原请求不匹配');
      this.confirmOriginal(row.id, requestId);
    }, 'recovering', res => {
      if (res && res.errorCode === 'INVOICE_NOT_FOUND') { this.setData({ recovery: 'notfound' }); return true; }
      return false;
    });
  },
  // 回读到原申请后核对冻结订单，才结算本机待办。
  confirmOriginal(id, requestId) {
    this.read('detail', { id }, invoice.application, row => {
      if (row.id !== id || row.requestId !== requestId) throw Error('申请详情与原请求不匹配');
      const frozen = this._frozen || [];
      if (frozen.length && (row.orders.length !== frozen.length
          || !frozen.every(orderId => row.orders.some(order => order.orderId === orderId)))) throw Error('原申请订单与冻结输入不匹配');
      if (!this._workflow.confirmRequest(this.key('apply'), requestId)) throw Error('本机原请求仍待确认，请勿重新申请');
      this.clearContext(requestId);
      this.setData({ pending: false, requestId: '', recovery: '' });
      this.loadRecords();
    });
  },
  goApply() { go('apply'); },
  goTitles() { go('titles'); },
  cancel(e) {
    const id = invoice.exactId(e && e.currentTarget.dataset.id);
    const row = this.data.records.find(item => item.id === id);
    if (!this.ready() || this.data.writing || !row || row.status !== 'SUBMITTED') return;
    this.cancelApplication(id, result => {
      if (result.status === 'success') { this.loadRecords(); return; }
      if (result.status === 'unknown') cyToast('撤回结果未确认，请刷新查看');
      else cyToast(result.response && result.response.msg, { fallback: '撤回未完成' });
    });
  },
});
