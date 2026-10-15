const app = getApp();
const { LEVEL_LABELS, levelTabs, buildRatingBody, receiptText } = require('../../../../../utils/safety-rating.js');

// BL-89:到访安全感受评价半屏。merchantId 是商家会员 ID(节点下发的 merchantId),
// 提交前经 /api/merchant/public-home {memberId} 换成 mms_merchant.id(后端 MerchantSafetyRatingRequest.merchantId)。
Component({
  properties: {
    show: { type: Boolean, value: false },
    merchantMemberId: { type: String, value: '' },
  },
  data: {
    stage: 'form',
    state: 'idle',
    error: '',
    levelTabs: [],
    level: '',
    reason: '',
    canSubmit: false,
    submitting: false,
    submitError: '',
    receiptText: '',
  },
  observers: {
    show(value) { if (value) this.open(); },
  },
  methods: {
    open() {
      this._epoch = (this._epoch || 0) + 1;
      this._requestId = '';
      this._merchantId = null;
      this.setData({ stage: 'form', level: '', reason: '', canSubmit: false, submitting: false, submitError: '', receiptText: '' });
      this.load();
    },

    load() {
      const epoch = this._epoch;
      this.setData({ state: 'loading', error: '' });
      app.sendRequest({
        hideLoading: true,
        silentError: true,
        url: '/api/community-trust/safety/taxonomy',
        method: 'GET',
        success: (res) => {
          if (epoch !== this._epoch) return;
          const ok = res && (res.code === 200 || res.code === '200');
          const tabs = ok ? levelTabs(res.data) : [];
          if (!tabs.length) {
            this.setData({ state: 'error', error: ok ? '评价选项暂时不可用' : app.getRequestErrorMessage(res, '评价选项暂时不可用') });
            return;
          }
          this.setData({ state: 'ready', levelTabs: tabs });
        },
        fail: (res) => {
          if (epoch !== this._epoch) return;
          this.setData({ state: 'error', error: app.getRequestErrorMessage(res, '网络异常，评价选项没有加载') });
        },
      });
    },

    onLevel(e) {
      const level = String((e.detail && e.detail.key) || '');
      if (!LEVEL_LABELS[level]) return;
      this._requestId = '';
      this.setData({ level, submitError: '' }, () => this.refreshCanSubmit());
    },

    onReason(e) {
      this._requestId = '';
      this.setData({ reason: String((e.detail && e.detail.value) || ''), submitError: '' }, () => this.refreshCanSubmit());
    },

    refreshCanSubmit() {
      const reason = this.data.reason.trim();
      this.setData({ canSubmit: !!LEVEL_LABELS[this.data.level] && reason.length > 0 && reason.length <= 1000 && !this.data.submitting });
    },

    submit() {
      if (!this.data.canSubmit || this.data.submitting) return;
      const epoch = this._epoch;
      this.setData({ submitting: true, canSubmit: false, submitError: '' });
      const fail = (message) => {
        if (epoch !== this._epoch) return;
        this.setData({ submitting: false, submitError: message }, () => this.refreshCanSubmit());
      };
      this.resolveMerchantId((merchantId, message) => {
        if (epoch !== this._epoch) return;
        if (!merchantId) { fail(message); return; }
        // 同一次填写的重试复用同一个幂等键;改了档位或原因后端会按 payloadHash 判冲突。
        if (!this._requestId) this._requestId = 'safety-' + merchantId + '-' + Date.now().toString(36) + '-' + Math.floor(Math.random() * 0xFFFFFF).toString(36);
        const body = buildRatingBody(merchantId, this.data.level, this.data.reason, this._requestId);
        app.sendRequest({
          hideLoading: true,
          silentError: true,
          url: '/api/community-trust/safety/ratings',
          method: 'POST',
          data: JSON.stringify(body),
          header: { 'Content-Type': 'application/json' },
          success: (res) => {
            if (epoch !== this._epoch) return;
            const ok = res && (res.code === 200 || res.code === '200') && res.data && res.data.ratingId;
            if (!ok) { fail(app.getRequestErrorMessage(res, '评价没有提交成功，请稍后重试')); return; }
            this.setData({ submitting: false, stage: 'done', receiptText: receiptText(res.data) });
          },
          fail: (res) => fail(app.getRequestErrorMessage(res, '网络异常，评价没有提交成功')),
        });
      });
    },

    resolveMerchantId(done) {
      if (this._merchantId) { done(this._merchantId); return; }
      const memberId = Number(this.data.merchantMemberId);
      if (!Number.isSafeInteger(memberId) || memberId <= 0) { done(null, '没有找到这家商家'); return; }
      app.sendRequest({
        hideLoading: true,
        silentError: true,
        url: '/api/merchant/public-home',
        method: 'POST',
        data: JSON.stringify({ memberId }),
        header: { 'Content-Type': 'application/json' },
        success: (res) => {
          const ok = res && (res.code === 200 || res.code === '200');
          const id = ok && res.data ? Number(res.data.id) : NaN;
          if (Number.isSafeInteger(id) && id > 0) { this._merchantId = id; done(id); return; }
          done(null, ok ? '没有找到这家商家' : app.getRequestErrorMessage(res, '商家信息没有加载'));
        },
        fail: (res) => done(null, app.getRequestErrorMessage(res, '网络异常，商家信息没有加载')),
      });
    },

    onClose() {
      this._epoch = (this._epoch || 0) + 1;
      this.triggerEvent('close');
    },
  },
});
