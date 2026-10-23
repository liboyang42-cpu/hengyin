/* 自玩通行证「确认订单」层(2026-10-22 业主:随时开玩不直接付款,有单独的结算页)。
   版式照报名结算页 pages/activity/baoming:参与人信息 → 主题信息 → 费用明细 → 单独同意 → 底栏合计 + 立即支付。
   本组件只负责展示和抛事件;同意落库、建单、支付全部仍由页面完成,合规口径不搬家。 */
Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    show: { type: Boolean, value: false },
    topInset: { type: Number, value: 88 },
    cover: { type: String, value: '' },
    name: { type: String, value: '' },
    validText: { type: String, value: '' },
    placeText: { type: String, value: '' },
    priceText: { type: String, value: '' },
    free: { type: Boolean, value: false },
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
    onConsent(e) { this.triggerEvent('consentchange', { checked: !!(e.detail && e.detail.checked) }); },
    onPay() { if (!this.data.loading) this.triggerEvent('pay'); },
    noop() {},
  },
});
