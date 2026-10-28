// 发票详情（X5 拆页之一，照抄「订单详情」正文）：状态卡 + 发票信息 + 包含订单；只给发票号（C3），不显示申请号。
const cyToast = require('../../utils/toast.js');
const { INVOICE_METHODS, baseData, invoice, view, go } = require('../invoice/base.js');

Page({
  ...INVOICE_METHODS,
  data: Object.assign(baseData(), { detail: null, cancelPending: false }),
  invoiceReset() {
    return { detail: null, cancelPending: false };
  },
  invoiceLoad() { this.refresh(); },
  refresh() {
    const id = invoice.exactId(this._options.id);
    if (!id) { this.setData({ error: 'read' }); return; }
    this.setData({ error: '' });
    this.read('detail', { id }, invoice.application, row => {
      if (row.id !== id) throw Error('申请详情与请求不匹配');
      const key = this.key('cancel:' + id), pending = this._workflow.pendingRequest(key);
      if (pending && row.status === 'CANCELLED') this._workflow.confirmRequest(key, pending.requestId);
      this.setData({ detail: view(row), cancelPending: !!this._workflow.pendingRequest(key) });
    }, 'loading');
  },
  cancel() {
    const detail = this.data.detail;
    if (!this.ready() || this.data.writing || this.data.cancelPending || !detail || detail.status !== 'SUBMITTED') return;
    this.cancelApplication(detail.id, result => {
      if (result.status === 'success') { this.setData({ detail: view(result.value), cancelPending: false }); return; }
      this.setData({ cancelPending: result.status === 'unknown' });
      if (result.status !== 'unknown') cyToast(result.response && result.response.msg, { fallback: '撤回未完成' });
    });
  },
  reapply() { if (this.data.detail && this.data.detail.status === 'REJECTED') go('apply'); },
});
