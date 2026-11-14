/* 自玩通行证「确认订单」整屏层(2026-10-22 业主:随时开玩不直接付款,有单独的结算页)。
   r2-store 2026-11-10 按 refs3/STRUCTURE.md §1 重排:主题头 → 单次购买卡 → 覆盖本主题的通票 → 参与人 →
   明细 → 信任行 → 单独同意 → 底栏合计 + 支付。view 由 pages/topic/utils/checkout-view.js 只读服务端事实生成。
   本组件只负责展示和抛事件;同意落库、建单、支付全部仍由页面完成,合规口径不搬家。 */
Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    show: { type: Boolean, value: false },
    topInset: { type: Number, value: 88 },
    cover: { type: String, value: '' },
    name: { type: String, value: '' },
    placeText: { type: String, value: '' },
    view: { type: Object, value: { pills: [], single: {}, passes: [], bill: [], trust: [], totalText: '—', payLabel: '支付' } },
    profileName: { type: String, value: '' },
    profilePhone: { type: String, value: '' },
    consentChecked: { type: Boolean, value: false },
    consentHint: { type: String, value: '' },
    consentError: { type: String, value: '' },
    loading: { type: Boolean, value: false },
  },
  methods: {
    onBack() { if (!this.data.loading) this.triggerEvent('back'); },
    onProfile() { this.triggerEvent('profile'); },
    onPass(e) { this.triggerEvent('pass', { id: e.currentTarget.dataset.id }); },
    onConsent(e) { this.triggerEvent('consentchange', { checked: !!(e.detail && e.detail.checked) }); },
    onPay() {
      const v = this.data.view || {};
      if (this.data.loading || v.loading || v.refused) return;
      if (v.failed) { this.triggerEvent('requote'); return; }
      this.triggerEvent('pay');
    },
    noop() {},
  },
});
