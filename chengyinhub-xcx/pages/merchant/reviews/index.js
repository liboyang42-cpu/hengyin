const toast = require('../../../utils/toast.js');
const app = getApp();
const { createReplyRecovery } = require('./reply-recovery');
const merchantTheme = require('../../../utils/merchant-theme.js');
const { ensureSession } = require('../utils/session/ensure-session.js');
const mediaPreview = require('../../../utils/media-preview.js');
const {
  buildCreateDraft,
  classifyLoadFailure,
  createReviewRequestId,
  fallbackRoute,
  fingerprint,
  MANAGE_REVIEW_FILTERS,
  normalizeManageFilter,
  parseReviewRouteOptions,
  decimalId: positiveId,
  shapePage,
} = require('./view-model.js');

Page({
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    mode: 'public',
    pageTitle: '真实到店评价',
    pageState: 'loading',
    errorMessage: '',
    items: [],
    filteredItems: [],
    total: 0,
    averageRating: '0.0',
    ratingStars: [
      { value: 1, filled: false },
      { value: 2, filled: false },
      { value: 3, filled: false },
      { value: 4, filled: false },
      { value: 5, filled: false },
    ],
    pendingReplyCount: 0,
    monthNewCount: 0,
    replyRatePct: 0,
    activeFilter: 'all',
    // 管理态:服务端全量计数(null = 后端未给,页面不显示数字);filterLoading = 切筛选后的重读中。
    filterCounts: null,
    filterLoading: false,
    hasMore: false,
    loadingMore: false,
    eligibility: { canCreate: false, reasonCode: 'UNAVAILABLE', registrationId: null },
    rating: 0,
    content: '',
    images: [],
    canSubmit: false,
    submitting: false,
    submitError: '',
    replyReviewId: null,
    replyEditing: false,
    replyContent: '',
    replySubmitting: false,
    replyError: '',
    replyPending: false,
    replyRequestId: '',
    reportReviewId: null,
    reportReason: '',
    reportSubmitting: false,
    reportError: '',
  },

  onLoad(options) {
    const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    const route = parseReviewRouteOptions(options);
    const mode = route.mode;
    this._merchantRowId = route.merchantRowId;
    this._pageNum = 1;
    this.setData({
      statusBarHeight: info.statusBarHeight || 20,
      navBarHeight: app.globalData.navBarHeight || 44,
      mode,
      pageTitle: '口碑',
      pageState: 'loading',
    });
    if (mode === 'public' && !this._merchantRowId) {
      // PR10 F-W1-12:缺商家参数不是「加载失败」(load-failure 留白),是缺参 —— 给可见说明和返回。
      this.setData({ pageState: 'missing', errorMessage: '商家信息不完整' });
      return;
    }
    // 管理态是商家页面:未登录先静默登录,失败只留页内错误态,不整屏拦。
    if (mode === 'manage' && !app.getUserID()) {
      this.enterManage();
      return;
    }
    this.loadReviews(false);
  },

  enterManage(authRetryUsed) {
    if (this._hidden || this._unloaded) return;
    const entryEpoch = this._reviewEntryEpoch = (this._reviewEntryEpoch || 0) + 1;
    this._authRetryUsed = authRetryUsed === true;
    this.setData({ pageState: 'loading', errorMessage: '' });
    const that = this;
    ensureSession(app).then(function (ok) {
      if (that._hidden || that._unloaded || entryEpoch !== that._reviewEntryEpoch) return;
      if (!ok || !app.getUserID()) {
        that.setData({ pageState: 'login-required', errorMessage: '登录失败，请重试' });
        return;
      }
      that.loadReviews(false);
    });
  },

  // 401:静默重登一次后自动重载;重登失败落页内错误态,不整屏「去登录」。
  recoverSession() {
    if (this._hidden || this._unloaded) return;
    if (this._authRetryUsed) {
      this.setData({ pageState: 'login-required', errorMessage: '登录已失效，请重新登录' });
      return;
    }
    this._authRetryUsed = true;
    const that = this;
    const entryEpoch = this._reviewEntryEpoch;
    const owner = String(app.getUserID() || '');
    ensureSession(app).then(function (ok) {
      if (that._hidden || that._unloaded || entryEpoch !== that._reviewEntryEpoch
          || (app.getUserID() && owner !== String(app.getUserID()))) return;
      if (!ok) {
        that.setData({ pageState: 'login-required', errorMessage: '登录失败，请重试' });
        return;
      }
      that.retry();
    });
  },

  onShow() {
    this._hidden = false;
    merchantTheme.merchantPageShow();
    this.syncReplyRecovery();
    if (this._replyRecovery) this._replyRecovery.resume();
    if (this._resumeReviewRead || (this._reviewOwner !== undefined && this._reviewOwner !== String(app.getUserID() || ''))) {
      this._resumeReviewRead = false;
      this.retry();
    }
  },
  onHide() {
    this._hidden = true;
    if (this._replyRecovery) this._replyRecovery.suspend();
    this._resumeReviewRead = this.data.pageState === 'loading' || this.data.loadingMore;
    this._reviewEntryEpoch = (this._reviewEntryEpoch || 0) + 1;
    this._reviewReadEpoch = (this._reviewReadEpoch || 0) + 1;
    merchantTheme.merchantPageRestore();
  },
  onUnload() {
    this._unloaded = true;
    if (this._replyRecovery) this._replyRecovery.suspend();
    this._reviewEntryEpoch = (this._reviewEntryEpoch || 0) + 1;
    this._reviewReadEpoch = (this._reviewReadEpoch || 0) + 1;
    merchantTheme.merchantPageRestore();
  },

  onNavBack() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else {
      const target = fallbackRoute(this.data.mode);
      if (target.method === 'switchTab') {
        wx.switchTab({ url: target.url, fail: () => wx.reLaunch({ url: target.url }) });
      } else {
        wx.reLaunch({ url: target.url });
      }
    }
  },

  // F-W1-12:登录失败不是「加载失败」,留可见的登录说明;重新登录走管理态入口的静默登录。
  retryLogin() {
    this.enterManage(false);
  },

  retry() {
    this._authRetryUsed = false;
    this._pageNum = 1;
    this.loadReviews(false);
  },

  syncReplyRecovery() {
    if (this.data.mode !== 'manage') return;
    const identity = String(app.getUserID() || '') + ':' + (typeof app.getAuthorization === 'function' ? app.getAuthorization() : '');
    if (this._replyUiIdentity !== undefined && this._replyUiIdentity !== identity) {
      this._replyRequestId = ''; this._replyFingerprint = ''; this._deleteReplyId = null;
      this.setData({ replyReviewId: null, replyEditing: false, replyContent: '', replyError: '' });
    }
    this._replyUiIdentity = identity;
    if (!this._replyRecovery) this._replyRecovery = createReplyRecovery({ app, storage: wx,
      changed: state => {
        this.setData({ replyPending: state.pending, replySubmitting: state.busy,
          replyRequestId: state.requestId, replyError: state.message });
        if (!state.busy && state.message !== '原回复已确认，正在回读评价') {
          const dc = this.selectComponent && this.selectComponent('#dc-reply-delete');
          if (dc) dc.close();
        }
      },
      applied: action => {
        if (action === 'MERCHANT_REVIEW_REPLY_DELETED') {
          const dc = this.selectComponent && this.selectComponent('#dc-reply-delete');
          if (dc) dc.done();
        }
        this._replyRequestId = ''; this._replyFingerprint = '';
        this.setData({ replyReviewId: null, replyEditing: false, replyContent: '' }); this.retry();
      } });
    this._replyRecovery.sync();
  },
  copyReplyRequestId() {
    this.syncReplyRecovery();
    const requestId = this.data.replyRequestId;
    if (this.data.mode !== 'manage' || !this.data.replyPending || !requestId) return;
    wx.setClipboardData({ data: requestId });
  },
  recoverReply() { this.syncReplyRecovery(); if (this._replyRecovery) this._replyRecovery.recover(); },

  loadReviews(append, keepShell) {
    if (this._hidden || this._unloaded) return;
    if (append && (this.data.loadingMore || !this.data.hasMore)) return;
    const mode = this.data.mode;
    const owner = String(app.getUserID() || '');
    if (this._reviewOwner !== undefined && this._reviewOwner !== owner) {
      this.setData({ items: [], filteredItems: [], total: 0, averageRating: '0.0', ratingStars: [],
        pendingReplyCount: 0, monthNewCount: 0, replyRatePct: 0, filterCounts: null, filterLoading: false,
        hasMore: false, loadingMore: false,
        eligibility: { canCreate: false, reasonCode: 'UNAVAILABLE', registrationId: null },
        replyReviewId: null, replyEditing: false, replyContent: '', replyError: '',
        reportReviewId: null, reportReason: '', reportError: '', content: '', images: [], rating: 0, canSubmit: false });
    }
    this._reviewOwner = owner;
    this.syncReplyRecovery();
    if (mode === 'manage' && !owner) { this.enterManage(false); return; }
    const readEpoch = this._reviewReadEpoch = (this._reviewReadEpoch || 0) + 1;
    const isCurrent = () => !this._hidden && !this._unloaded && readEpoch === this._reviewReadEpoch
      && owner === String(app.getUserID() || '');
    const pageNum = append ? this._pageNum + 1 : 1;
    const filter = normalizeManageFilter(this.data.activeFilter);
    if (append) this.setData({ loadingMore: true });
    else if (keepShell === true && this.data.pageState === 'ready') {
      // 切筛选:保留页头与统计,只清列表 —— 旧筛选的条目不能留在新 tab 下冒充结果。
      this.setData({ filterLoading: true, items: [], filteredItems: [], hasMore: false, errorMessage: '' });
    } else this.setData({ pageState: 'loading', errorMessage: '' });
    const requestOptions = {
      hideLoading: true,
      method: 'POST',
      success: (res) => {
        if (!isCurrent()) return;
        if (mode === 'manage' && isAuthenticationFailure(res)) {
          this.recoverSession();
          return;
        }
        if (!isSuccessObject(res)) {
          const failedState = classifyLoadFailure(mode, res);
          this.setData({
            pageState: append ? this.data.pageState : failedState,
            loadingMore: false,
            filterLoading: false,
            errorMessage: failedState === 'no-permission'
              ? '当前账号没有口碑管理权限'
              : app.getRequestErrorMessage(res, '评价加载失败'),
          });
          return;
        }
        const shaped = shapePage(res.data, mode, pageNum, 20, mode === 'manage' ? filter : undefined);
        if (!shaped) {
          this.setData({ pageState: 'error', loadingMore: false, filterLoading: false, errorMessage: '评价加载失败，请重试' });
          return;
        }
        const items = append ? this.data.items.concat(shaped.items) : shaped.items;
        this._pageNum = shaped.pageNum;
        // 管理态:列表已由服务端按筛选收窄;整页空态只属于「全部」,筛选为空走列表内的筛选空态。
        const empty = mode === 'manage' && items.length === 0 && filter === 'all';
        this.setData({
          pageState: empty ? 'empty' : 'ready',
          items,
          filteredItems: mode === 'manage' ? items : filterReviewItems(items, this.data.activeFilter),
          filterCounts: shaped.filterCounts,
          filterLoading: false,
          // 页头「N 条已核销评价」是全店口径:有服务端全量计数就用它,不用筛选后的 total。
          total: mode === 'manage' && shaped.filterCounts ? shaped.filterCounts.all
            : (mode === 'manage' && filter !== 'all' ? this.data.total : shaped.total),
          averageRating: shaped.averageRating,
          ratingStars: shaped.ratingStars,
          pendingReplyCount: shaped.pendingReplyCount,
          monthNewCount: shaped.monthNewCount,
          replyRatePct: shaped.replyRatePct,
          hasMore: shaped.hasMore,
          eligibility: shaped.eligibility,
          loadingMore: false,
          errorMessage: '',
        });
        this.refreshCreateState();
      },
      fail: (res, status) => {
        if (!isCurrent()) return;
        if (mode === 'manage' && isAuthenticationFailure(res, status)) {
          this.recoverSession();
          return;
        }
        this.setData({
          pageState: append ? this.data.pageState : 'error',
          loadingMore: false,
          filterLoading: false,
          errorMessage: '网络异常，请重试',
        });
      },
    };
    if (mode === 'manage') {
      app.sendRequest({
        hideLoading: requestOptions.hideLoading, autoErrorToast: false,
        url: `/api/merchant/reviews/manage?filter=${encodeURIComponent(filter)}&pageNum=${pageNum}&pageSize=20`,
        method: requestOptions.method,
        success: requestOptions.success,
        fail: requestOptions.fail,
        complete: requestOptions.complete,
      });
    } else {
      app.sendRequest({
        hideLoading: requestOptions.hideLoading, autoErrorToast: false,
        url: `/api/merchant/reviews/public?merchantRowId=${encodeURIComponent(this._merchantRowId)}&pageNum=${pageNum}&pageSize=20`,
        method: requestOptions.method,
        success: requestOptions.success,
        fail: requestOptions.fail,
        complete: requestOptions.complete,
      });
    }
  },

  loadMore() { this.loadReviews(true); },

  setReviewFilter(event) {
    const filter = String(event && event.currentTarget && event.currentTarget.dataset.filter || 'all');
    const manage = this.data.mode === 'manage';
    if (manage ? !MANAGE_REVIEW_FILTERS.includes(filter) : !['all', 'low', 'photo'].includes(filter)) return;
    if (filter === this.data.activeFilter && !(manage && this.data.filterLoading)) return;
    this.setData({
      activeFilter: filter,
      replyReviewId: null,
      replyEditing: false,
      replyContent: '',
      replyError: '',
      reportReviewId: null,
      reportReason: '',
      reportError: '',
    });
    if (manage) {
      // W10a #20:管理态筛选走服务端,重读第一页;计数随响应刷新。
      this._pageNum = 1;
      this.loadReviews(false, true);
      return;
    }
    // 公开态仍只在已加载评价里筛(没有公开态服务端筛选接口)。
    this.setData({ filteredItems: filterReviewItems(this.data.items, filter) });
  },

  selectRating(event) {
    const rating = Number(event && event.currentTarget && event.currentTarget.dataset.rating);
    if (!Number.isInteger(rating) || rating < 1 || rating > 5) return;
    this.setData({ rating, submitError: '' });
    this.refreshCreateState();
  },

  inputContent(event) {
    this.setData({ content: String((event && event.detail && event.detail.value) || ''), submitError: '' });
    this.refreshCreateState();
  },

  chooseImages() {
    const images = this.data.images || [];
    if (images.length >= 9) return;
    app.chooseImage((urls) => {
      const next = images.concat(Array.isArray(urls) ? urls : []).slice(0, 9);
      this.setData({ images: next, submitError: '' });
      this.refreshCreateState();
    }, 9 - images.length);
  },

  removeImage(event) {
    if (this.data.submitting) return;
    const index = Number(event && event.currentTarget && event.currentTarget.dataset.index);
    if (!Number.isInteger(index) || index < 0 || index >= this.data.images.length) return;
    const images = this.data.images.slice();
    images.splice(index, 1);
    this.setData({ images });
    this.refreshCreateState();
  },

  previewImage(event) {
    const reviewIndex = Number(event.currentTarget.dataset.reviewIndex);
    const imageIndex = Number(event.currentTarget.dataset.imageIndex);
    const review = this.data.filteredItems[reviewIndex];
    if (!review || !review.imageUrls[imageIndex]) return;
    mediaPreview.open(review.imageUrls, review.imageUrls[imageIndex]);
  },

  refreshCreateState() {
    const draft = buildCreateDraft(this.data);
    this.setData({
      canSubmit: this.data.eligibility.canCreate === true
        && this.data.submitting !== true && draft.valid === true,
    });
  },

  submitReview() {
    if (this.data.submitting) return;
    if (!this.data.eligibility.canCreate) return;
    const draft = buildCreateDraft(this.data);
    if (!draft.valid) {
      this.setData({ submitError: draft.error, canSubmit: false });
      return;
    }
    const payloadBase = Object.assign({}, draft.payload, {
      merchantRowId: this._merchantRowId,
      registrationId: this.data.eligibility.registrationId,
    });
    const currentFingerprint = fingerprint(payloadBase);
    if (!this._createRequestId || this._createFingerprint !== currentFingerprint) {
      this._sequence = (this._sequence || 0) + 1;
      this._createRequestId = createReviewRequestId('create', Date.now(), Math.random(), this._sequence);
      this._createFingerprint = currentFingerprint;
    }
    const payload = Object.assign({}, payloadBase, { requestId: this._createRequestId });
    this.setData({ submitting: true, canSubmit: false, submitError: '' });
    requestJson('/api/merchant/reviews/create', payload, (res) => {
      const result = res && res.data;
      const status = result && result.status;
      const knownStatus = status === 'PENDING_REVIEW' || status === 'VISIBLE' || status === 'HIDDEN';
      if (!isSuccessObject(res) || !positiveId(result && result.reviewId) || !knownStatus
          || (result.replayed !== true && status !== 'PENDING_REVIEW')) {
        this.setData({ submitting: false, submitError: app.getRequestErrorMessage(res, '评价提交失败') });
        this.refreshCreateState();
        return;
      }
      this._createRequestId = '';
      this._createFingerprint = '';
      this.setData({ submitting: false, rating: 0, content: '', images: [], submitError: '' });
      const title = status === 'VISIBLE' ? '该评价已通过复核并公开'
        : (status === 'HIDDEN' ? '该评价未通过平台复核' : '评价已提交，等待平台复核');
      toast(title);
      this.retry();
    }, () => {
      this.setData({ submitting: false, submitError: '网络异常；本次请求可安全重试' });
      this.refreshCreateState();
    });
  },

  openReply(event) {
    if (this.data.mode !== 'manage') return;
    this.syncReplyRecovery();
    if (this.data.replyPending || this.data.replySubmitting || this.data.reportSubmitting) return;
    const id = positiveId(event.currentTarget.dataset.id);
    if (!id) return;
    this._replyRequestId = '';
    this._replyFingerprint = '';
    this.setData({ replyReviewId: id, replyEditing: false, replyContent: '', replyError: '', reportReviewId: null });
  },

  openEditReply(event) {
    if (this.data.mode !== 'manage') return;
    this.syncReplyRecovery();
    if (this.data.replyPending || this.data.replySubmitting || this.data.reportSubmitting) return;
    const id = positiveId(event.currentTarget.dataset.id);
    const review = id ? findReview(this.data.items, id) : null;
    if (!review || !review.canEditReply || !review.merchantReply) return;
    this._replyRequestId = '';
    this._replyFingerprint = '';
    this.setData({
      replyReviewId: id,
      replyEditing: true,
      replyContent: review.merchantReply,
      replyError: '',
      reportReviewId: null,
    });
  },

  cancelEditReply() {
    if (this.data.mode !== 'manage') return;
    this.syncReplyRecovery();
    if (this.data.replyPending || this.data.replySubmitting) return;
    this.setData({ replyReviewId: null, replyEditing: false, replyContent: '', replyError: '' });
  },

  /** 删除是不可逆动作:先过 cy-danger-confirm 三段式确认,再发请求。 */
  askDeleteReply(event) {
    if (this.data.mode !== 'manage') return;
    this.syncReplyRecovery();
    if (this.data.replyPending || this.data.replySubmitting || this.data.reportSubmitting) return;
    const id = positiveId(event.currentTarget.dataset.id);
    const review = id ? findReview(this.data.items, id) : null;
    if (!review || !review.canEditReply) return;
    this._deleteReplyId = id;
    const dc = this.selectComponent && this.selectComponent('#dc-reply-delete');
    if (!dc) return;
    dc.open('merchant.review.reply.delete', {});
  },

  onConfirmDeleteReply() {
    if (this.data.mode !== 'manage') return;
    this.syncReplyRecovery();
    if (this.data.replyPending || this.data.replySubmitting) return;
    const dc = this.selectComponent && this.selectComponent('#dc-reply-delete');
    const review = findReview(this.data.items, positiveId(this._deleteReplyId));
    if (!review || !review.canEditReply) {
      if (dc) dc.close();
      return;
    }
    if (dc) dc.busyOn();
    const base = { reviewId: positiveId(review.id), expectedVersion: review.version };
    const currentFingerprint = fingerprint(base);
    if (!this._deleteRequestId || this._deleteFingerprint !== currentFingerprint) {
      this._sequence = (this._sequence || 0) + 1;
      this._deleteRequestId = createReviewRequestId('reply-del', Date.now(), Math.random(), this._sequence);
      this._deleteFingerprint = currentFingerprint;
    }
    const payload = Object.assign({}, base, { requestId: this._deleteRequestId });
    this.syncReplyRecovery();
    this._replyRecovery.submit('MERCHANT_REVIEW_REPLY_DELETED', payload);
  },

  tapReplySend(event) {
    const id = positiveId(event.currentTarget.dataset.id);
    if (!id) return;
    if (this.data.replyReviewId === id) {
      this.submitReply();
      return;
    }
    this.openReply(event);
  },

  inputReply(event) {
    if (this.data.mode !== 'manage') return;
    this.syncReplyRecovery();
    if (this.data.replyPending || this.data.replySubmitting) return;
    this.setData({ replyContent: String((event && event.detail && event.detail.value) || ''), replyError: '' });
  },

  submitReply() {
    if (this.data.mode !== 'manage') return;
    this.syncReplyRecovery();
    if (this.data.replyPending || this.data.replySubmitting) return;
    const editing = this.data.replyEditing === true;
    const review = findReview(this.data.items, this.data.replyReviewId);
    const content = String(this.data.replyContent || '').trim();
    const allowed = review && (editing ? review.canEditReply : review.canReply);
    if (!allowed || content.length < 1 || content.length > 500) {
      this.setData({ replyError: '回复内容需填写 1 到 500 字' });
      return;
    }
    const base = { reviewId: positiveId(review.id), content, expectedVersion: review.version };
    const currentFingerprint = fingerprint(base);
    if (!this._replyRequestId || this._replyFingerprint !== currentFingerprint) {
      this._sequence = (this._sequence || 0) + 1;
      this._replyRequestId = createReviewRequestId(editing ? 'reply-edit' : 'reply', Date.now(), Math.random(), this._sequence);
      this._replyFingerprint = currentFingerprint;
    }
    const payload = Object.assign({}, base, { requestId: this._replyRequestId });
    this.syncReplyRecovery();
    this._replyRecovery.submit(editing ? 'MERCHANT_REVIEW_REPLY_UPDATED' : 'MERCHANT_REVIEW_REPLIED', payload);
  },

  openReport(event) {
    if (this.data.reportSubmitting || this.data.replySubmitting) return;
    const id = positiveId(event.currentTarget.dataset.id);
    if (!id) return;
    this._reportRequestId = '';
    this._reportFingerprint = '';
    this.setData({ reportReviewId: id, reportReason: '', reportError: '', replyReviewId: null, replyEditing: false });
  },

  inputReport(event) {
    this.setData({ reportReason: String((event && event.detail && event.detail.value) || ''), reportError: '' });
  },

  cancelReport() {
    if (this.data.reportSubmitting) return;
    this.setData({ reportReviewId: null, reportReason: '', reportError: '' });
  },

  submitReport() {
    if (this.data.reportSubmitting) return;
    const review = findReview(this.data.items, this.data.reportReviewId);
    const reason = String(this.data.reportReason || '').trim();
    if (!review || !review.canReport || reason.length < 2 || reason.length > 500) {
      this.setData({ reportError: '举报原因需填写 2 到 500 字' });
      return;
    }
    const base = { reviewId: positiveId(review.id), expectedVersion: review.version, reason };
    const currentFingerprint = fingerprint(base);
    if (!this._reportRequestId || this._reportFingerprint !== currentFingerprint) {
      this._sequence = (this._sequence || 0) + 1;
      this._reportRequestId = createReviewRequestId('report', Date.now(), Math.random(), this._sequence);
      this._reportFingerprint = currentFingerprint;
    }
    const payload = Object.assign({}, base, { requestId: this._reportRequestId });
    this.setData({ reportSubmitting: true, reportError: '' });
    const onSuccess = (res) => {
      if (!isSuccessObject(res) || (res.data && res.data.status) !== 'PENDING_PLATFORM_REVIEW') {
        this.setData({ reportSubmitting: false, reportError: app.getRequestErrorMessage(res, '举报提交失败') });
        return;
      }
      this._reportRequestId = '';
      this._reportFingerprint = '';
      this.setData({ reportSubmitting: false, reportReviewId: null, reportReason: '', reportError: '' });
      toast('举报已提交，等待平台复核');
    };
    const onFail = () => this.setData({ reportSubmitting: false, reportError: '网络异常；本次举报可安全重试' });
    if (this.data.mode === 'manage') {
      requestJson('/api/merchant/reviews/manage/report', payload, onSuccess, onFail);
    } else {
      requestJson('/api/merchant/reviews/report', payload, onSuccess, onFail);
    }
  },
});

function requestJson(url, data, success, fail) {
  app.sendRequest({
    hideLoading: true,
    url,
    method: 'POST',
    data: JSON.stringify(data),
    header: { 'Content-Type': 'application/json' },
    success,
    fail,
  });
}

function findReview(items, id) {
  const target = positiveId(id);
  return target ? (items || []).find((item) => positiveId(item.id) === target) || null : null;
}

function isAuthenticationFailure(res, status) {
  const candidates = [status, res && res.statusCode, res && res.code];
  for (let index = 0; index < candidates.length; index += 1) {
    const code = String(candidates[index] == null ? '' : candidates[index]);
    if (code === '401' || code === '2') return true;
  }
  return false;
}

function isSuccessObject(res) {
  return !!(res && (res.code === 200 || res.code === '200')
    && res.data && typeof res.data === 'object' && !Array.isArray(res.data));
}

function filterReviewItems(items, filter) {
  const source = Array.isArray(items) ? items : [];
  if (filter === 'pending') return source.filter((item) => item && item.canReply === true && !item.merchantReply);
  if (filter === 'low') return source.filter((item) => item && item.rating <= 3);
  if (filter === 'photo') return source.filter((item) => item && item.imageUrls && item.imageUrls.length > 0);
  return source;
}
