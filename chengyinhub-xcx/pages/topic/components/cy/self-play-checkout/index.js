/* 自玩通行证「确认订单」整屏层(2026-10-22 业主:随时开玩不直接付款,有单独的结算页)。
   r2-store 2026-11-10 按 refs3/STRUCTURE.md §1 重排:主题头 → 单次购买卡 → 覆盖本主题的通票 → 参与人 →
   明细 → 信任行 → 单独同意 → 底栏合计 + 支付。view 由 pages/topic/utils/checkout-view.js 只读服务端事实生成。
   本组件只负责展示和抛事件;同意落库、建单、支付全部仍由页面完成,合规口径不搬家。 */
const { readReducedMotion } = require('../../../../../utils/motion-preference.js');
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
    lkVars: { type: String, value: '' }, // 外观强调色行内变量(成功卡用)
    phase: { type: String, value: '' }, // '' | busy | done:页面在建单/支付/到账时给,只管呈现
  },
  data: { payW: 0, paidOut: false },
  observers: { phase(p) { this._phase(p); }, show(on) { if (on && this.data.paidOut) this.setData({ paidOut: false }); } },
  lifetimes: { detached() { this._spinOn = false; this.clearAnimation('.ck__spin', () => {}); } },
  methods: {
    onBack() { if (!this.data.loading) this.triggerEvent('back'); },
    onProfile() { this.triggerEvent('profile'); },
    onPass(e) { this.triggerEvent('pass', { id: e.currentTarget.dataset.id }); },
    onConsent(e) { this.triggerEvent('consentchange', { checked: !!(e.detail && e.detail.checked) }); },
    onPay() {
      const v = this.data.view || {};
      if (this.data.loading || v.loading || v.refused) return;
      this.triggerEvent('pay');
    },
    noop() {},
    /* 支付键原地收成圈:先量现宽,下一拍写目标宽,transition 才有起点;失败/取消回到原宽 */
    _phase(p) {
      const reduced = readReducedMotion();
      if (p === 'busy') {
        wx.createSelectorQuery().in(this).select('#ckpay').boundingClientRect((r) => {
          if (!r || this.data.phase !== 'busy') return;
          this.setData({ payW: r.width }, () => setTimeout(() => this.setData({ payW: 52 }), reduced ? 0 : 20));
          this._spin();
        }).exec();
      } else {
        this._stopSpin();
        this.setData({ payW: 0 });
        if (p === 'done') { this.setData({ paidOut: true }); this._check(reduced); }
      }
    },
    _stopSpin() { this._spinOn = false; if (this.clearAnimation) this.clearAnimation('.ck__spin', {}, () => {}); }, // 停表并清掉最后一帧残留
    _spin() {
      if (this._spinOn || readReducedMotion()) return;
      this._spinOn = true;
      const turn = () => { if (this._spinOn) this.animate('.ck__spin', [{ transform: 'rotate(0deg)' }, { transform: 'rotate(360deg)' }], 800, turn); };
      turn();
    },
    /* 勾按笔顺画出来(短笔 → 长笔);减少动态直接落终态 */
    _check(reduced) {
      const draw = (sel, at) => setTimeout(() => this.animate(sel, [{ transform: 'scaleX(0)' }, { transform: 'scaleX(1)' }], reduced ? 1 : 220, () => {}), at);
      draw('.ck__arm--a', reduced ? 0 : 260); draw('.ck__arm--b', reduced ? 0 : 420);
    },
  },
});
