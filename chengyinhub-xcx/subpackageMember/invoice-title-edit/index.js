// 新增 / 编辑发票抬头（X5 拆页之一，照抄参与人信息表单 pages/addressinfo）。
// 完整税号只留在本页内存（W8 同口径）：不进本机存储，离页即清；编辑单位抬头必须重填完整税号。
// 保存结果未知时「查询结果」按原请求号回读；仍查不到且本页还留着原输入，就用同一请求号重发（服务端按原请求号去重）。
const app = getApp();
const cyToast = require('../../utils/toast.js');
const { createWriteActionWorkflow } = require('../../utils/write-action-workflow.js');
const { INVOICE_METHODS, baseData, invoice } = require('../invoice/base.js');

const draft = () => ({ id: '', titleType: 'COMPANY', titleName: '', taxNo: '', isDefault: false });
const TAX_NO = /^[0-9A-Z]{15,20}$/;
function canSubmit(row) {
  const name = String(row.titleName || '').trim();
  return !!name && name.length <= 100 && (row.titleType === 'PERSONAL' || TAX_NO.test(String(row.taxNo || '').trim().toUpperCase()));
}

Page({
  ...INVOICE_METHODS,
  data: Object.assign(baseData(), { draft: draft(), canSubmit: false, formReady: false, titlePending: false, titleRequestId: '', saveError: '' }),
  invoiceReset() {
    return { draft: draft(), canSubmit: false, formReady: false, titlePending: false, titleRequestId: '', saveError: '' };
  },
  invoiceStop() {
    this._titleIntent = null;
    if (this._titleRetry) this._titleRetry.destroy();
    this._titleRetry = null;
  },
  invoiceLoad() {
    let pending = null;
    try { pending = this._workflow.pendingRequest(this.key('title-save')); } catch (error) { pending = { requestId: '' }; }
    this.setData({ titlePending: !!pending, titleRequestId: pending && pending.requestId || '' });
    const id = invoice.exactId(this._options.id);
    if (!id) { this.setData({ formReady: true }); return; }
    this.read('titles', {}, data => this.rows(data, invoice.title), titles => {
      const row = titles.find(item => item.id === id);
      if (!row) throw Error('抬头不存在');
      const next = { id: row.id, titleType: row.titleType, titleName: row.titleName, taxNo: '', isDefault: row.isDefault };
      this.setData({ draft: next, canSubmit: canSubmit(next), formReady: true });
    }, 'loading');
  },
  reloadTitle() { this._hidden = true; this.onShow(); },
  locked() { return !this.ready() || this.data.writing || this.data.titlePending; },
  patch(fields) {
    const next = Object.assign({}, this.data.draft, fields);
    this.setData({ draft: next, canSubmit: canSubmit(next), saveError: '' });
  },
  input(e) {
    if (this.locked()) return;
    const field = e.currentTarget.dataset.field;
    if (['titleName', 'taxNo'].includes(field)) this.patch({ [field]: String(e.detail.value || '') });
  },
  titleType(e) {
    if (this.locked()) return;
    const type = e.currentTarget.dataset.type;
    if (['PERSONAL', 'COMPANY'].includes(type) && type !== this.data.draft.titleType) this.patch({ titleType: type, taxNo: '' });
  },
  defaultTitle(e) { if (!this.locked()) this.patch({ isDefault: !!(e.detail && e.detail.value === true) }); },
  saveTitle() {
    if (this.locked()) return;
    let payload;
    try { payload = invoice.titlePayload(this.data.draft); } catch (error) { this.setData({ saveError: error.message }); return; }
    this.mutate('titles/save-request', payload, 'title-save', (value, requestId) => invoice.titleSaveReceipt(value, requestId, payload.id), result => {
      if (result.status === 'success') { this.saved(); return; }
      if (result.status !== 'unknown') this._titleIntent = null;
      this.setData({ titlePending: result.status === 'unknown', titleRequestId: result.requestId || '',
        saveError: result.status === 'unknown' ? '' : String(result.response && result.response.msg || ''),
        draft: Object.assign({}, this.data.draft, { taxNo: '' }), canSubmit: false });
    }, body => {
      // Sensitive input stays only in this visible page's memory, never in wx storage.
      this._titleIntent = Object.freeze(Object.assign({}, body));
      this.setData({ titleRequestId: body.requestId });
    });
  },
  saved() {
    this._titleIntent = null;
    if (this._titleRetry) this._titleRetry.destroy();
    this._titleRetry = null;
    this.setData({ titlePending: false, titleRequestId: '', writing: false, draft: draft(), canSubmit: false });
    if (getCurrentPages().length > 1) wx.navigateBack({ delta: 1 });
    else wx.redirectTo({ url: '/subpackageMember/invoice-titles/index' });
  },
  confirmTitleSave(row) {
    if (!this.data.titlePending || row.requestId !== this.data.titleRequestId) throw Error('原保存结果已变化');
    if (!this._workflow.confirmRequest(this.key('title-save'), row.requestId)) throw Error('本机原保存待办仍未清除，请继续核对');
    this.saved();
  },
  queryTitleSave() {
    if (!this.ready() || this.data.writing || !this.data.titlePending || !this.data.titleRequestId) return;
    const requestId = this.data.titleRequestId, titleId = this._titleIntent && this._titleIntent.id;
    this.read('titles/request', { requestId }, value => invoice.titleSaveReceipt(value, requestId, titleId), row => this.confirmTitleSave(row), '', res => {
      if (!(res && res.errorCode === 'INVOICE_NOT_FOUND')) return false;
      if (this._titleIntent && this._titleIntent.requestId === requestId) this.retryTitleSave();
      else cyToast('暂未查到这次保存');
      return true;
    });
  },
  retryTitleSave() {
    if (!this.ready() || this.data.writing || !this.data.titlePending || !this._titleIntent
        || this._titleIntent.requestId !== this.data.titleRequestId) return;
    const body = this._titleIntent, epoch = this._epoch;
    if (this._titleRetry) this._titleRetry.destroy();
    this._titleRetry = createWriteActionWorkflow();
    this.setData({ writing: true });
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
        try { this.confirmTitleSave(result.value); } catch (error) { /* 原待办未清，保持结果未确认 */ }
      }
    });
  },
});
