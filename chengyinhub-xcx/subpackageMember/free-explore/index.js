const app = getApp();
const bundle = require('../../utils/free-explore-bundle.js');
const modal = require('../../utils/modal.js');
const empty = () => ({ review: null, reviewPending: null, reviewBusy: false, reviewContent: '', reviewRating: 5, detail: null, catalog: null, quote: null, exitOptions: null, swapPending: null, pending: null, loading: false, error: '', notice: '' });

Page({
  data: empty(),
  clearView() {
    this._writing = false;
    this.setData({
      review: null, reviewPending: null, reviewBusy: false, reviewContent: '', reviewRating: 5,
      detail: null, catalog: null, quote: null, exitOptions: null, swapPending: null, pending: null,
      loading: false, error: '', notice: '',
    });
  },
  onLoad(options) {
    this._registrationId = bundle.exactId(options && options.registrationId);
    this._topicId = bundle.exactId(options && options.topicId);
    this._purchaseRequestId = options && typeof options.requestId === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(options.requestId) ? options.requestId : '';
  },
  identity() { return bundle.exactId(app.getUserID()) + '|' + String(app.getAuthorization() || ''); },
  onShow() {
    this._epoch = (this._epoch || 0) + 1; this._hidden = false;
    this._confirming = false; this._storageBlocked = false;
    this._identity = this.identity(); this._owner = bundle.exactId(app.getUserID());
    this.clearView();
    if (!this._owner || !app.getAuthorization()) { this.setData({ error: '请登录后重新进入本人订单' }); return; }
    if (!this._registrationId && !this._topicId && !this._purchaseRequestId) { this.setData({ error: '订单或商品编号无法确认，请从原入口重新进入' }); return; }
    this.restorePending();
    this.reload();
  },
  restorePending() {
    if (!this._registrationId) return;
    try {
      const review = wx.getStorageSync(this.reviewKey());
      if (review) this.setData({ reviewPending: bundle.reviewIntent(review, this._owner, this._registrationId) });
      const swap = wx.getStorageSync(this.swapKey());
      if (swap) {
        if (swap.owner !== this._owner || swap.registrationId !== this._registrationId || !bundle.exactId(swap.unitId)
            || !bundle.exactId(swap.targetOfferId) || !/^[A-Za-z0-9_-]{1,64}$/.test(swap.idempotencyKey)) throw Error('invalid swap');
        this.setData({ swapPending: swap, notice: '换店决定结果待确认，原选择已保留' });
      }
      const row = wx.getStorageSync(this.key());
      if (row) {
        if (row.owner !== this._owner || row.registrationId !== this._registrationId || !bundle.exactId(row.reservationId)
            || typeof row.useWaiver !== 'boolean' || !row.requestId) throw Error('invalid pending');
        this.setData({ pending: row, notice: '上次取消结果待确认，原预约和豁免选择已保留' });
      }
    } catch (error) { this._storageBlocked = true; this.setData({ error: '待确认记录无法读取，暂不能提交新操作；仍可刷新订单历史' }); }
  },
  onHide() { this._hidden = true; this._epoch++; this.clearView(); },
  onUnload() { this.onHide(); this._unloaded = true; },
  swapKey() { return 'free_explore_swap_v1:' + this._owner + ':' + this._registrationId; },
  key() { return 'free_explore_cancel_v1:' + this._owner + ':' + this._registrationId; },
  current(epoch) {
    if (this._unloaded || this._hidden || this._epoch !== epoch) return false;
    if (this._identity !== this.identity()) { this.onShow(); return false; }
    return true;
  },
  apiRequest(url, method, data, done) {
    const epoch = this._epoch; let settled = false;
    const finish = (res, failed) => {
      if (settled || !this.current(epoch)) return;
      settled = true; done(!failed && res && Number(res.code) === 200, res);
    };
    try { app.sendRequest({ url, method,
      data: method === 'POST' ? JSON.stringify(data) : data, header: { 'Content-Type': 'application/json' },
      hideLoading: true, autoErrorToast: false, success: res => finish(res, false),
      fail: res => finish(res, true), successStatusAbnormal: res => finish(res, true) });
    } catch (error) { finish(null, true); }
  },
  ready() { return !this._hidden && !this._unloaded && this._owner && app.getAuthorization() && (this._registrationId || this._topicId || this._purchaseRequestId) && this.current(this._epoch); },
  reload() {
    if (!this.ready() || this.data.loading) return;
    if (!this._registrationId) { this.loadEntry(); return; }
    this.setData({ loading: true });
    this.apiRequest('/api/free-explore/bundle/order', 'GET', { registrationId: this._registrationId }, (ok, res) => {
      this.setData({ loading: false });
      if (!ok) { this.setData({ error: res && res.msg || '权益加载失败，请重试；待确认操作不会清除' }); return; }
      try {
        const detail = bundle.order(res.data, this._owner, this._registrationId);
        this.setData({ detail });
        const pending = this.data.pending;
        const row = pending && detail.reservations.find(item => item.id === pending.reservationId);
        // BOOKED/absent is not proof that the original transaction failed.
        if (row && row.status !== 'BOOKED') this.resolvePending(row);
        const swap = this.data.swapPending;
        if (swap && detail.exitCases.some(item => item.idempotencyKey === swap.idempotencyKey
            && item.unitId === swap.unitId && item.targetOfferId === swap.targetOfferId && item.decision === 'SWAP_CONSENTED')) this.resolveSwap();
      } catch (error) { this.setData({ detail: null, error: error.message }); }
    });
  },
  loadEntry() {
    const receipt = !!this._purchaseRequestId;
    this.setData({ loading: true });
    this.apiRequest(receipt ? '/api/free-explore/bundle/order/receipt' : '/api/free-explore/bundle/catalog', 'GET', receipt ? { requestId: this._purchaseRequestId } : { topicId: this._topicId }, (ok, res) => {
      this.setData({ loading: false });
      if (!ok) { this.setData({ error: res && res.msg || '回读失败，请稍后重试' }); return; }
      try {
        if (!receipt) { this.setData({ catalog: bundle.catalog(res.data, this._topicId) }); return; }
        const data = res.data;
        if (!data || data.requestId !== this._purchaseRequestId) throw Error('购买结果无法确认');
        if (data.receipt !== 'FOUND') { this.setData({ notice: '购买结果待确认，保留原请求号；当前未查到不代表未购买，请继续回读' }); return; }
        const registrationId = bundle.exactId(data.order && data.order.registrationId);
        if (!registrationId) throw Error('购买结果无法确认');
        const detail = bundle.order(data.order, this._owner, registrationId);
        this._registrationId = registrationId;
        this.setData({ detail, notice: '已回读原订单，请查看支付与报名状态' }); this.restorePending(); this.reload();
      } catch (error) { this.setData({ error: error.message }); }
    });
  },
  reviewKey() { return 'free_explore_review_v1:' + this._owner + ':' + this._registrationId; },
  loadReviews(event) {
    if (!this.ready() || !this.data.detail || this.data.reviewBusy) return;
    const memberId = bundle.exactId(event.currentTarget.dataset.memberId);
    if (!memberId || !this.data.detail.reservations.some(row => row.merchantId === memberId)
        && !this.data.detail.fulfilledMerchants.some(row => row.merchantId === memberId)) return;
    const sequence = this._reviewSequence = (this._reviewSequence || 0) + 1;
    this.setData({ review: null, reviewContent: '', reviewBusy: true, error: '' });
    this.apiRequest('/api/merchant/public-home', 'POST', { memberId }, (ok, res) => {
      if (sequence !== this._reviewSequence) return;
      if (!ok) { this.setData({ reviewBusy: false, error: res && res.msg || '商家不可见或映射失败，请重试' }); return; }
      try {
        const merchant = bundle.publicMerchant(res.data, memberId);
        this.fetchReviews(merchant, 1, sequence);
      } catch (error) { this.setData({ reviewBusy: false, error: error.message }); }
    });
  },
  fetchReviews(merchant, pageNum, sequence) {
    this.apiRequest(`/api/merchant/reviews/public?merchantRowId=${merchant.merchantRowId}&registrationId=${this._registrationId}&pageNum=${pageNum}&pageSize=20`, 'POST', {}, (ok, res) => {
      if (sequence !== this._reviewSequence) return;
      this.setData({ reviewBusy: false });
      if (!ok) { this.setData({ error: res && res.msg || '评价加载失败，请重试' }); return; }
      try {
        const result = bundle.reviews(res.data, this._registrationId);
        if (result.pageNum !== pageNum) throw Error('评价分页无法确认');
        const previous = pageNum > 1 && this.data.review ? this.data.review.items : [];
        const items = previous.concat(result.items).filter((row, index, all) => all.findIndex(x => x.id === row.id) === index);
        this.setData({ review: Object.assign({}, merchant, result, { items }) });
      } catch (error) { this.setData({ error: error.message }); }
    });
  },
  moreReviews() {
    if (!this.ready() || !this.data.review || !this.data.review.hasMore || this.data.reviewBusy) return;
    this.setData({ reviewBusy: true });
    this.fetchReviews(this.data.review, this.data.review.pageNum + 1, this._reviewSequence);
  },
  inputReview(event) { if (!this.data.reviewPending) this.setData({ reviewContent: String(event.detail.value || '') }); },
  rateReview(event) {
    const rating = Number(event.detail.value);
    if (!this.data.reviewPending && Number.isInteger(rating) && rating >= 1 && rating <= 5) this.setData({ reviewRating: rating });
  },
  submitReview() {
    if (!this.ready() || this._storageBlocked || this.data.reviewBusy || this.data.reviewPending
        || !this.data.review || !this.data.review.canCreate) return;
    let pending;
    try {
      pending = bundle.reviewIntent({ owner: this._owner, registrationId: this._registrationId,
        merchantRowId: this.data.review.merchantRowId, rating: this.data.reviewRating,
        content: this.data.reviewContent.trim(), requestId: 'fx-review-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12) }, this._owner, this._registrationId);
      wx.setStorageSync(this.reviewKey(), pending);
    } catch (error) { this.setData({ error: '正文需2～1000字，且须成功保存恢复记录；未发送评价' }); return; }
    this.setData({ reviewPending: pending }); this.resumeReview();
  },
  resumeReview() {
    if (!this.ready() || this._storageBlocked || this.data.reviewBusy || !this.data.reviewPending) return;
    const pending = bundle.reviewIntent(this.data.reviewPending, this._owner, this._registrationId);
    this.setData({ reviewBusy: true });
    // Existing create contract replays author+requestId with an identical fingerprint before eligibility checks.
    this.apiRequest('/api/merchant/reviews/create', 'POST', { merchantRowId: pending.merchantRowId,
      registrationId: pending.registrationId, rating: pending.rating, content: pending.content, imageUrls: [], requestId: pending.requestId }, (ok, res) => {
      this.setData({ reviewBusy: false });
      const row = res && res.data;
      if (ok && row && bundle.exactId(row.reviewId) && ['PENDING_REVIEW', 'VISIBLE', 'HIDDEN'].includes(row.status)) {
        try { wx.removeStorageSync(this.reviewKey()); }
        catch (error) { this.setData({ error: '已收到评价结果，但恢复记录未清除；请按原请求再次确认' }); return; }
        this.setData({ reviewPending: null, reviewContent: '', review: null,
          notice: row.status === 'PENDING_REVIEW' ? '评价已提交，等待平台复核，尚未公开' : '评价原请求已确认，状态：' + row.status });
        return;
      }
      // Even a later rejection or an empty public list cannot disprove an earlier committed submission.
      this.setData({ error: res && res.msg || '评价结果待确认；原内容和请求号已保留，请按原请求恢复' });
    });
  },
  loadQuote() {
    if (!this.ready() || !this._registrationId) return;
    this.setData({ quote: null });
    this.apiRequest('/api/free-explore/bundle/order/refund-quote', 'GET', { registrationId: this._registrationId }, (ok, res) => {
      if (!ok) { this.setData({ error: res && res.msg || '退款试算失败' }); return; }
      try { this.setData({ quote: bundle.quote(res.data) }); } catch (error) { this.setData({ error: error.message }); }
    });
  },
  cancelReservation(event) {
    if (!this.ready() || this._storageBlocked || this.data.pending || this.data.swapPending || this._writing || this._confirming) return;
    const id = bundle.exactId(event.currentTarget.dataset.id);
    const row = this.data.detail && this.data.detail.reservations.find(item => item.id === id && item.canCancel);
    if (!row) return;
    const useWaiver = event.currentTarget.dataset.waiver === true || event.currentTarget.dataset.waiver === 'true';
    const epoch = this._epoch; this._confirming = true;
    this.apiRequest('/api/free-explore/bundle/reservation/' + id + '/cancel-quote', 'GET', { useWaiver }, (ok, res) => {
      if (!ok) { this._confirming = false; this.setData({ error: res && res.msg || '取消试算失败，未发送取消' }); return; }
      let quote;
      try { quote = bundle.cancellationQuote(res.data, this._owner, this._registrationId, id, useWaiver); }
      catch (error) { this._confirming = false; this.setData({ error: error.message }); return; }
      if (!quote.canCancel) { this._confirming = false; this.setData({ error: '当前不可取消，请刷新预约状态' }); return; }
      modal.show({ title: useWaiver ? '使用本月豁免取消预约？' : '确认取消预约？',
        content: quote.message + ' 确认时若跨过免费取消边界或豁免月份，平台将拒绝并要求重新确认。',
        success: result => {
          if (!this.current(epoch)) return;
          this._confirming = false;
          if (!result.confirm || this.data.pending || this.data.swapPending) return;
          const pending = { owner: this._owner, registrationId: this._registrationId, reservationId: id, useWaiver,
            expectedOutcome: quote.expectedOutcome, requestId: 'cancel-' + id + '-' + Date.now() };
          try { wx.setStorageSync(this.key(), pending); } catch (error) { this.setData({ error: '无法保存恢复记录，未发送取消请求' }); return; }
          this._writing = true;
          this.setData({ pending, notice: '正在确认取消结果', error: '' });
          this.apiRequest('/api/free-explore/bundle/reservation/' + id + '/cancel', 'POST', { useWaiver, expectedOutcome: quote.expectedOutcome }, (ok, res) => {
            this._writing = false;
            if (ok) {
              try {
                const receipt = bundle.reservation(res.data, this._owner, this._registrationId);
                if (receipt.id === id && receipt.status !== 'BOOKED') { this.resolvePending(receipt); this.reload(); return; }
              } catch (error) { /* malformed positive responses remain UNKNOWN */ }
            }
            if (res && ['BUNDLE_WAIVER_USED', 'BUNDLE_RESERVATION_OFF', 'BUNDLE_NOT_FOUND', 'BUNDLE_CANCEL_QUOTE_CHANGED', 'BUNDLE_REFUND_PENDING'].includes(res.businessCode)) {
              this.clearPending(); this.setData({ notice: '', error: res.msg || '取消被拒绝，请重新确认' }); return;
            }
            this.setData({ notice: '取消结果待确认，请刷新预约历史；原预约和豁免选择保持不变' });
          });
        }, fail: () => { if (this.current(epoch)) this._confirming = false; } });
    });
  },
  loadExitOptions(event) {
    if (!this.ready() || !this._registrationId || this.data.pending || this.data.swapPending) return;
    const unitId = bundle.exactId(event.currentTarget.dataset.id);
    if (!this.data.detail || !this.data.detail.units.some(item => item.id === unitId)) return;
    this.setData({ exitOptions: null });
    const sequence = this._exitSequence = (this._exitSequence || 0) + 1;
    this.apiRequest('/api/free-explore/bundle/exit-options', 'GET', { registrationId: this._registrationId, unitId }, (ok, res) => {
      if (sequence !== this._exitSequence) return;
      if (!ok) { this.setData({ error: res && res.msg || '换店方案回读失败' }); return; }
      try { this.setData({ exitOptions: bundle.exitOptions(res.data, this._owner, this._registrationId, unitId) }); }
      catch (error) { this.setData({ error: error.message }); }
    });
  },
  consentSwap(event) {
    if (!this.ready() || this._storageBlocked || this.data.pending || this.data.swapPending || this._confirming) return;
    const options = this.data.exitOptions, targetId = bundle.exactId(event.currentTarget.dataset.id);
    const target = options && options.merchantExited && options.targets.find(item => item.offerId === targetId);
    if (!target) return;
    const epoch = this._epoch; this._confirming = true;
    modal.show({ title: '同意更换履约商家？', content: '同意改选' + target.merchantName + '，保留原权益有效期；此操作不会自动预约或退款。',
      success: result => {
        if (!this.current(epoch)) return;
        this._confirming = false;
        if (!result.confirm || this.data.pending || this.data.swapPending) return;
        const pending = { owner: this._owner, registrationId: this._registrationId, unitId: options.unitId,
          targetOfferId: targetId, idempotencyKey: 'fx-swap-' + Date.now() + '-' + Math.random().toString(36).slice(2, 14) };
        try { wx.setStorageSync(this.swapKey(), pending); } catch (error) { this.setData({ error: '无法保存换店恢复记录，未发送请求' }); return; }
        this._writing = true;
        this.setData({ swapPending: pending, error: '' });
        this.apiRequest('/api/free-explore/bundle/exit-decision', 'POST', { unitId: pending.unitId, decision: 'SWAP_CONSENTED', targetOfferId: targetId,
          idempotencyKey: pending.idempotencyKey }, (ok, res) => {
          this._writing = false;
          const row = res && res.data;
          if (ok && row && bundle.exactId(row.id) && bundle.exactId(row.holderMemberId) === this._owner
              && bundle.exactId(row.registrationId) === this._registrationId && bundle.exactId(row.unitId) === pending.unitId
              && bundle.exactId(row.targetOfferId) === targetId && row.decision === 'SWAP_CONSENTED'
              && row.idempotencyKey === pending.idempotencyKey) { this.resolveSwap(); this.reload(); return; }
          if (res && ['BUNDLE_MERCHANT_NOT_EXITED', 'BUNDLE_STORE_UNAVAILABLE', 'BUNDLE_SWAP_SHORTENS_VALIDITY',
            'BUNDLE_UNIT_UNAVAILABLE', 'BUNDLE_RESERVATION_OFF', 'BUNDLE_NOT_FOUND'].includes(res.businessCode)) {
            this.clearSwap(); this.setData({ exitOptions: null, error: res.msg || '换店被拒绝，请刷新' }); return;
          }
          this.setData({ notice: '换店结果待确认，请刷新历史；原目标和请求号已保留' });
        });
      }, fail: () => { if (this.current(epoch)) this._confirming = false; } });
  },
  clearSwap() {
    try { wx.removeStorageSync(this.swapKey()); this.setData({ swapPending: null }); }
    catch (error) { this._storageBlocked = true; this.setData({ error: '换店恢复记录未清除，请再次刷新' }); }
  },
  resolveSwap() { this.clearSwap(); this.setData({ exitOptions: null, notice: '平台已确认同意换店，未自动预约或退款' }); },
  clearPending() {
    try { wx.removeStorageSync(this.key()); this.setData({ pending: null }); }
    catch (error) { this._storageBlocked = true; this.setData({ error: '结果已回读，但本机恢复记录未清除；请再次刷新' }); }
  },
  resolvePending(row) { this.clearPending(); this.setData({ notice: '平台预约状态：' + row.statusText }); },
});
