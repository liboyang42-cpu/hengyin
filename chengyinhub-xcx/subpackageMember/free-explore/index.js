const app = getApp();
const bundle = require('../utils/free-explore-bundle.js');
const modal = require('../../utils/modal.js');
const empty = () => ({ review: null, reviewPending: null, reviewBusy: false, reviewContent: '', reviewRating: 5, detail: null, catalog: null, quote: null, exitOptions: null, swapPending: null, pending: null, reserveForm: null, reservePending: null, reserveBusy: false, loading: false, error: '', notice: '' });

// 自定义导航栏下的占位高度;缺了它标题和第一张卡会压在导航栏底下
const layout = () => ({ statusBarHeight: app.globalData.statusBarHeight || 44, navBarHeight: app.globalData.navBarHeight || 44 });

Page({
  data: Object.assign(empty(), layout()),
  clearView() {
    this._writing = false;
    this.setData({
      review: null, reviewPending: null, reviewBusy: false, reviewContent: '', reviewRating: 5,
      detail: null, catalog: null, quote: null, exitOptions: null, swapPending: null, pending: null,
      reserveForm: null, reservePending: null, reserveBusy: false,
      loading: false, error: '', notice: '',
      statusBarHeight: layout().statusBarHeight, navBarHeight: layout().navBarHeight,
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
    if (!this._owner || !app.getAuthorization()) { this.setData({ error: '请登录后查看' }); return; }
    if (!this._registrationId && !this._topicId && !this._purchaseRequestId) { this.setData({ error: '链接已失效，请从原入口重新进入' }); return; }
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
        this.setData({ swapPending: swap, notice: '换店结果确认中' });
      }
      const book = wx.getStorageSync(this.reserveKey());
      if (book) {
        if (book.owner !== this._owner || book.registrationId !== this._registrationId || !bundle.exactId(book.unitId)
            || !bundle.exactId(book.offerId) || !Number.isSafeInteger(book.startAt) || !/^[A-Za-z0-9_-]{1,64}$/.test(book.idempotencyKey)) throw Error('invalid reserve');
        this.setData({ reservePending: book, notice: '预约结果确认中' });
      }
      const row = wx.getStorageSync(this.key());
      if (row) {
        if (row.owner !== this._owner || row.registrationId !== this._registrationId || !bundle.exactId(row.reservationId)
            || typeof row.useWaiver !== 'boolean' || !row.requestId) throw Error('invalid pending');
        this.setData({ pending: row, notice: '取消结果确认中' });
      }
    } catch (error) { this._storageBlocked = true; this.setData({ error: '本机记录读取失败，暂不能操作，可刷新查看' }); }
  },
  onHide() { this._hidden = true; this._epoch++; this.clearView(); },
  onUnload() { this.onHide(); this._unloaded = true; },
  reserveKey() { return 'free_explore_reserve_v1:' + this._owner + ':' + this._registrationId; },
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
      // R11 p1 F-7:套票功能关着时刷新也不会变,不再摆「刷新」按钮
      if (!ok) { this.setData({ error: res && res.msg || '权益加载失败，请重试', featureOff: !!(res && res.businessCode === 'BUNDLE_LEDGER_OFF') }); return; }
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
      if (!ok) { this.setData({ error: res && res.msg || '加载失败，请稍后重试' }); return; }
      try {
        if (!receipt) { this.setData({ catalog: bundle.catalog(res.data, this._topicId) }); return; }
        const data = res.data;
        if (!data || data.requestId !== this._purchaseRequestId) throw Error('购买结果暂时读不到，请刷新');
        if (data.receipt !== 'FOUND') { this.setData({ notice: '购买结果确认中，请稍后刷新' }); return; }
        const registrationId = bundle.exactId(data.order && data.order.registrationId);
        if (!registrationId) throw Error('购买结果暂时读不到，请刷新');
        const detail = bundle.order(data.order, this._owner, registrationId);
        this._registrationId = registrationId;
        this.setData({ detail, notice: '' }); this.restorePending(); this.reload();
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
      if (!ok) { this.setData({ reviewBusy: false, error: res && res.msg || '商家信息加载失败，请重试' }); return; }
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
        if (result.pageNum !== pageNum) throw Error('评价暂时读不到，请刷新');
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
    } catch (error) { this.setData({ error: '评价需 2～1000 字' }); return; }
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
        catch (error) { this.setData({ error: '评价已提交，请刷新' }); return; }
        this.setData({ reviewPending: null, reviewContent: '', review: null,
          notice: row.status === 'PENDING_REVIEW' ? '评价已提交，审核通过后公开' : '评价已提交' });
        return;
      }
      // Even a later rejection or an empty public list cannot disprove an earlier committed submission.
      this.setData({ error: res && res.msg || '评价结果确认中，请点「重新确认」' });
    });
  },
  loadQuote() {
    if (!this.ready() || !this._registrationId) return;
    this.setData({ quote: null });
    this.apiRequest('/api/free-explore/bundle/order/refund-quote', 'GET', { registrationId: this._registrationId }, (ok, res) => {
      if (!ok) { this.setData({ error: res && res.msg || '可退金额加载失败' }); return; }
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
      if (!ok) { this._confirming = false; this.setData({ error: res && res.msg || '取消条件加载失败，请重试' }); return; }
      let quote;
      try { quote = bundle.cancellationQuote(res.data, this._owner, this._registrationId, id, useWaiver); }
      catch (error) { this._confirming = false; this.setData({ error: error.message }); return; }
      if (!quote.canCancel) { this._confirming = false; this.setData({ error: '当前不可取消' }); return; }
      modal.show({ title: useWaiver ? '使用本月豁免取消预约？' : '确认取消预约？',
        content: quote.message,
        success: result => {
          if (!this.current(epoch)) return;
          this._confirming = false;
          if (!result.confirm || this.data.pending || this.data.swapPending) return;
          const pending = { owner: this._owner, registrationId: this._registrationId, reservationId: id, useWaiver,
            expectedOutcome: quote.expectedOutcome, requestId: 'cancel-' + id + '-' + Date.now() };
          try { wx.setStorageSync(this.key(), pending); } catch (error) { this.setData({ error: '本机存储失败，未取消' }); return; }
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
              this.clearPending(); this.setData({ notice: '', error: res.msg || '取消未成功，请重试' }); return;
            }
            this.setData({ notice: '取消结果确认中，请稍后刷新' });
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
      if (!ok) { this.setData({ error: res && res.msg || '可换商家加载失败' }); return; }
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
    modal.show({ title: '更换商家？', content: '改选' + target.merchantName + '，有效期不变。',
      success: result => {
        if (!this.current(epoch)) return;
        this._confirming = false;
        if (!result.confirm || this.data.pending || this.data.swapPending) return;
        const pending = { owner: this._owner, registrationId: this._registrationId, unitId: options.unitId,
          targetOfferId: targetId, idempotencyKey: 'fx-swap-' + Date.now() + '-' + Math.random().toString(36).slice(2, 14) };
        try { wx.setStorageSync(this.swapKey(), pending); } catch (error) { this.setData({ error: '本机存储失败，未更换' }); return; }
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
            this.clearSwap(); this.setData({ exitOptions: null, error: res.msg || '更换未成功，请刷新' }); return;
          }
          this.setData({ notice: '换店结果确认中，请稍后刷新' });
        });
      }, fail: () => { if (this.current(epoch)) this._confirming = false; } });
  },
  openReserve(event) {
    if (!this.ready() || this._storageBlocked || this.data.reservePending || this._writing) return;
    const unitId = bundle.exactId(event.currentTarget.dataset.id);
    const unit = this.data.detail && this.data.detail.units.find(item => item.id === unitId && item.canReserve);
    const chapter = unit && this.data.detail.chapters.find(item => item.chapterId === unit.chapterId);
    if (!chapter) return;
    const d = new Date(Date.now() + 8 * 3600000), pad = n => (n < 10 ? '0' : '') + n;
    const today = d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate());
    this.setData({ reserveForm: { unitId, chapterName: chapter.name, stores: chapter.stores,
      offerId: chapter.stores.length === 1 ? chapter.stores[0].offerId : '', date: '', time: '', today }, error: '' });
  },
  pickStore(event) { if (this.data.reserveForm) this.setData({ 'reserveForm.offerId': bundle.exactId(event.currentTarget.dataset.id) }); },
  pickDate(event) { if (this.data.reserveForm) this.setData({ 'reserveForm.date': String(event.detail.value || '') }); },
  pickTime(event) { if (this.data.reserveForm) this.setData({ 'reserveForm.time': String(event.detail.value || '') }); },
  confirmReserve() {
    const form = this.data.reserveForm;
    if (!this.ready() || this._storageBlocked || !form || this.data.reservePending || this.data.reserveBusy) return;
    if (!form.offerId) { this.setData({ error: '请选择商家' }); return; }
    const startAt = Date.parse(form.date + 'T' + form.time + ':00+08:00');
    if (!form.date || !form.time || !Number.isSafeInteger(startAt) || startAt <= Date.now()) { this.setData({ error: '请选择之后的日期和时间' }); return; }
    const pending = { owner: this._owner, registrationId: this._registrationId, unitId: form.unitId, offerId: form.offerId,
      startAt, idempotencyKey: 'fx-book-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12) };
    try { wx.setStorageSync(this.reserveKey(), pending); } catch (error) { this.setData({ error: '本机存储失败，未预约' }); return; }
    this.setData({ reservePending: pending, reserveForm: null, error: '' });
    this.submitReserve();
  },
  // 同一幂等键重发：服务端按键重放，不会重复占用权益
  submitReserve() {
    const pending = this.data.reservePending;
    if (!this.ready() || !pending || this.data.reserveBusy) return;
    this.setData({ reserveBusy: true });
    this.apiRequest('/api/free-explore/bundle/reservation', 'POST', { registrationId: pending.registrationId, unitId: pending.unitId,
      offerId: pending.offerId, startAt: pending.startAt, idempotencyKey: pending.idempotencyKey }, (ok, res) => {
      this.setData({ reserveBusy: false });
      if (ok) {
        try {
          const row = bundle.reservation(res.data, this._owner, this._registrationId);
          if (row.unitId === pending.unitId) { this.clearReserve(); this.setData({ notice: '预约状态：' + row.statusText }); this.reload(); return; }
        } catch (error) { /* 回包读不懂：仍按结果未确认处理 */ }
      }
      // 409 + businessCode = 服务端明确拒绝；其余（超时、断网、回包异常）都不能断言没成功
      if (res && res.businessCode) { this.clearReserve(); this.setData({ notice: '', error: res.msg || '预约未成功，请重试' }); return; }
      this.setData({ notice: '预约结果确认中，请点「重新确认」' });
    });
  },
  clearReserve() {
    try { wx.removeStorageSync(this.reserveKey()); this.setData({ reservePending: null }); }
    catch (error) { this._storageBlocked = true; this.setData({ error: '请再次刷新' }); }
  },
  clearSwap() {
    try { wx.removeStorageSync(this.swapKey()); this.setData({ swapPending: null }); }
    catch (error) { this._storageBlocked = true; this.setData({ error: '请再次刷新' }); }
  },
  resolveSwap() { this.clearSwap(); this.setData({ exitOptions: null, notice: '已更换商家' }); },
  clearPending() {
    try { wx.removeStorageSync(this.key()); this.setData({ pending: null }); }
    catch (error) { this._storageBlocked = true; this.setData({ error: '请再次刷新' }); }
  },
  resolvePending(row) { this.clearPending(); this.setData({ notice: '预约状态：' + row.statusText }); },
});
