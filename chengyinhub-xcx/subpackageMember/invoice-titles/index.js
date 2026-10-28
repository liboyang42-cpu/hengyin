// 发票抬头（X5 拆页之一，照抄「参与人信息」列表 pages/address）。
// 上次保存结果未知时只按原请求号查询，确认前不能新增或修改（W8 同一份本机待办）。
const cyToast = require('../../utils/toast.js');
const modal = require('../../utils/modal.js');
const { INVOICE_METHODS, baseData, invoice, titleLine, go } = require('../invoice/base.js');

Page({
  ...INVOICE_METHODS,
  data: Object.assign(baseData(), { titles: [], loaded: false, titlePending: false, titleRequestId: '', deleting: '' }),
  invoiceReset() {
    return { titles: [], loaded: false, titlePending: false, titleRequestId: '', deleting: '' };
  },
  invoiceLoad() {
    try {
      const pending = this._workflow.pendingRequest(this.key('title-save'));
      this.setData({ titlePending: !!pending, titleRequestId: pending && pending.requestId || '' });
    } catch (error) {
      this.setData({ titlePending: true });
    }
    this.loadTitles();
  },
  loadTitles() {
    this.setData({ error: '' });
    this.read('titles', {}, data => this.rows(data, invoice.title), titles => {
      this.setData({ titles: titles.map(row => Object.assign({}, row, { line: titleLine(row) })), loaded: true });
    }, 'loading');
  },
  queryTitleSave() {
    if (!this.ready() || this.data.writing || !this.data.titlePending || !this.data.titleRequestId) return;
    const requestId = this.data.titleRequestId;
    this.read('titles/request', { requestId }, value => invoice.titleSaveReceipt(value, requestId), row => {
      if (!this._workflow.confirmRequest(this.key('title-save'), row.requestId)) throw Error('本机原保存待办仍未清除');
      this.setData({ titlePending: false, titleRequestId: '' });
      this.loadTitles();
    }, '', res => {
      if (res && res.errorCode === 'INVOICE_NOT_FOUND') { cyToast('暂未查到这次保存'); return true; }
      return false;
    });
  },
  edit(e) {
    if (!this.ready() || this.data.writing || this.data.titlePending) return;
    const id = invoice.exactId(e && e.currentTarget.dataset.id);
    if (id) go('edit', 'id=' + id); else wx.navigateTo({ url: '/subpackageMember/invoice-title-edit/index' });
  },
  remove(e) {
    if (!this.ready() || this.data.writing) return;
    const id = invoice.exactId(e.currentTarget.dataset.id), epoch = this._epoch;
    if (!this.data.titles.some(row => row.id === id)) return;
    modal.show({ title: '删除发票抬头', content: '删除后不影响已提交的申请。', confirmText: '删除', danger: true,
      success: result => {
        if (!result.confirm || !this.current(epoch)) return;
        this.setData({ deleting: id });
        this.mutate('titles/delete', { id }, 'title-delete:' + id, value => {
          if (value != null) throw Error('删除结果无法确认'); return null;
        }, result => {
          this.setData({ deleting: '' });
          if (result.status === 'success') { this.loadTitles(); return; }
          if (result.status === 'unknown') cyToast('删除结果未确认，请刷新查看');
          else cyToast(result.response && result.response.msg, { fallback: '删除未完成' });
        });
      } });
  },
});
