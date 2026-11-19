// F11 自由探索结算（参考）：店铺(kind=merchant)看本店各场参考金额并可提异议；
// 场次收款人(kind=payer)看本人各场并「标记已结清」。钱不经平台，双方线下结款。
const app = getApp();
const modal = require('../../../utils/modal.js');
const toast = require('../../../utils/toast.js');
const { referenceView, disputeNoteError, voucherError } = require('./view-model.js');

Component({
  properties: {
    kind: { type: String, value: 'merchant' },
  },
  data: { state: 'loading', view: null, busy: false },
  observers: { kind() { if (this._attached) this.load(); } },
  lifetimes: {
    attached() { this._attached = true; this.load(); },
    detached() { this._attached = false; this._epoch = (this._epoch || 0) + 1; },
  },
  pageLifetimes: { show() { if (this._attached && !this.data.busy) this.load(); } },
  methods: {
    load() {
      const epoch = this._epoch = (this._epoch || 0) + 1;
      const memberId = String(app.getUserID());
      const kind = this.properties.kind;
      this.setData({ state: 'loading', view: null });
      if (!['merchant', 'payer'].includes(kind) || !Number(memberId)) { this.setData({ state: 'error' }); return; }
      const current = () => this._attached && epoch === this._epoch && memberId === String(app.getUserID());
      const showLoadError = () => { if (current()) this.setData({ state: 'error', view: null }); };
      app.sendRequest({
        hideLoading: true, silentError: true,
        url: kind === 'payer' ? '/api/fe/reference-settlement/payer' : '/api/fe/reference-settlement/merchant',
        method: 'GET',
        success: (res) => {
          if (!current()) return;
          const view = referenceView(res, kind);
          this.setData({ state: view ? 'ready' : 'error', view });
        },
        fail: () => showLoadError(), successStatusAbnormal: showLoadError,
      });
    },

    // 收款人：本场待结的参考行一次标记已线下结清；凭证号选填。
    onSettle(event) {
      const key = event.currentTarget.dataset.key;
      const group = this.data.view && this.data.view.groups.find((item) => item.key === key);
      if (this.properties.kind !== 'payer' || !group || !group.openIds.length || this.data.busy) return;
      modal.show({
        title: '标记已线下结清？',
        content: '',
        editable: true,
        placeholderText: '转账凭证号（选填）',
        confirmText: '标记已结清',
        validate: voucherError,
        success: (res) => {
          if (!res || !res.confirm || !this._attached) return;
          const voucher = typeof res.content === 'string' ? res.content.trim() : '';
          this.submit('/api/fe/reference-settlement/settle',
            Object.assign({ ids: group.openIds }, voucher ? { voucherNo: voucher } : {}), '已标记结清');
        },
      });
    },

    // 店铺：对某一行提出异议(如主办方标了已结清但没收到)。
    onDispute(event) {
      const id = event.currentTarget.dataset.id;
      const found = this.data.view && this.data.view.groups.some((group) => group.rows.some((row) => row.id === id && row.canDispute));
      if (this.properties.kind !== 'merchant' || !found || this.data.busy) return;
      modal.show({
        title: '对这笔结算有异议？',
        content: '',
        editable: true,
        multiline: true,
        placeholderText: '写明原因，主办方会看到',
        confirmText: '提交异议',
        validate: disputeNoteError,
        success: (res) => {
          if (!res || !res.confirm || !this._attached) return;
          this.submit('/api/fe/reference-settlement/dispute', { id, note: String(res.content || '').trim() }, '已提交异议');
        },
      });
    },

    submit(url, body, doneText) {
      const memberId = String(app.getUserID());
      const current = () => this._attached && memberId === String(app.getUserID());
      this.setData({ busy: true });
      app.sendRequest({
        hideLoading: true,
        url,
        method: 'POST',
        data: JSON.stringify(body),
        header: { 'Content-Type': 'application/json' },
        success: (res) => {
          if (!current()) return;
          this.setData({ busy: false });
          if (res && (res.code === 200 || res.code === '200')) { toast(doneText); this.load(); }
        },
        fail: () => { if (current()) this.setData({ busy: false }); },
        successStatusAbnormal: () => {
          if (!current()) return;
          this.setData({ busy: false });
          toast('结果未确认，请刷新后查看');
          this.load();
        },
      });
    },
  },
});
