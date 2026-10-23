// BL-100 商家触达任务记录(只读)。结构照 pages/merchant/verify-records:access/me 判权 → 列表四态。
// 列表 GET /api/merchant/crm/campaigns、详情 GET /api/merchant/crm/campaigns/{id},都要 MARKETING_READ。
// 不做创建/投递/重试(POST /campaigns、/dispatch、/retry)。
const app = getApp();
const toast = require('../../../utils/toast.js'); const pageMotion = require('../../../utils/page-motion.js');
const merchantTheme = require('../../../utils/merchant-theme.js');
const { inactiveAccess, normalizeMerchantAccess } = require('../../../utils/merchant-access-policy.js');
const { shapeCampaignList, shapeCampaignDetail } = require('./view-model.js');

Page({
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    pageState: 'loading',
    errorMessage: '',
    items: [],
    detailOpen: false,
    detailState: 'idle',
    detailError: '',
    detail: null,
  },

  onLoad() {
    const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    this._merchantAccess = inactiveAccess();
    this._detailSeq = 0;
    this.setData({
      statusBarHeight: info.statusBarHeight || 20,
      navBarHeight: (app.globalData && app.globalData.navBarHeight) || 44,
    });
    this.loadAccess();
  },

  onShow() { merchantTheme.merchantPageShow(); },
  onHide() { merchantTheme.merchantPageRestore(); },
  onUnload() { merchantTheme.merchantPageRestore(); },

  onNavBack() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.reLaunch({ url: '/pages/merchant/index/index' });
  },

  loadAccess() {
    this.setData({ pageState: 'loading', errorMessage: '' });
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/merchant/access/me',
      method: 'POST',
      success: (res) => {
        if (!successObject(res)) {
          this.setData({ pageState: 'error', errorMessage: errorText(res, '经营身份加载失败') });
          return;
        }
        const access = normalizeMerchantAccess(res.data);
        this._merchantAccess = access;
        if (!access.active || !access.canReadMarketing) {
          this.setData({ pageState: 'no-permission', items: [] });
          return;
        }
        this.loadList();
      },
      fail: () => this.setData({ pageState: 'error', errorMessage: '网络异常，请重试' }),
    });
  },

  retry() {
    if (this._merchantAccess && this._merchantAccess.canReadMarketing) this.loadList();
    else this.loadAccess();
  },

  // 下拉:已有列表时静默重读,失败只轻提示并保留列表;无权限/失败态走 retry
  onPullDownRefresh() {
    const state = this.data.pageState;
    const canRead = !!(this._merchantAccess && this._merchantAccess.canReadMarketing);
    if (canRead && (state === 'ready' || state === 'empty')) { this.loadList(true); return; }
    wx.stopPullDownRefresh();
    if (state !== 'loading') this.retry();
  },

  loadList(fromPull) {
    if (!this._merchantAccess || !this._merchantAccess.canReadMarketing) {
      if (fromPull) wx.stopPullDownRefresh();
      return;
    }
    // 过期回包丢弃:retry 与首读/下拉并发时只认最后一次
    this._listSeq = (this._listSeq || 0) + 1;
    const seq = this._listSeq;
    const keep = !!fromPull;
    if (!keep) this.setData({ pageState: 'loading', errorMessage: '' });
    const settle = () => {
      if (fromPull) wx.stopPullDownRefresh();
      return seq === this._listSeq;
    };
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/merchant/crm/campaigns',
      method: 'GET',
      success: (res) => {
        if (!settle()) return;
        const items = isSuccess(res) ? shapeCampaignList(res.data) : null;
        if (!items) {
          if (keep) toast('刷新失败，稍后再试');
          else this.setData({ pageState: 'error', errorMessage: errorText(res, '触达记录加载失败') });
          return;
        }
        pageMotion.enter(this, 'pmList'); this.setData({ items, pageState: items.length ? 'ready' : 'empty', errorMessage: '' });
      },
      fail: () => {
        if (!settle()) return;
        if (keep) toast('刷新失败，稍后再试');
        else this.setData({ pageState: 'error', errorMessage: '网络异常，请重试' });
      },
    });
  },

  openDetail(event) {
    const id = Number(event && event.currentTarget && event.currentTarget.dataset.id);
    if (!Number.isSafeInteger(id) || id <= 0) return;
    this._detailId = id;
    this.setData({ detailOpen: true });
    this.loadDetail();
  },

  retryDetail() { this.loadDetail(); },

  closeDetail() {
    this._detailSeq += 1;
    this.setData({ detailOpen: false, detailState: 'idle', detailError: '', detail: null });
  },

  loadDetail() {
    const id = this._detailId;
    if (!id) return;
    this._detailSeq += 1;
    const seq = this._detailSeq;
    this.setData({ detailState: 'loading', detailError: '', detail: null });
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: `/api/merchant/crm/campaigns/${id}`,
      method: 'GET',
      success: (res) => {
        if (seq !== this._detailSeq) return;
        const detail = successObject(res) ? shapeCampaignDetail(res.data) : null;
        if (!detail) {
          this.setData({ detailState: 'error', detailError: errorText(res, '触达明细加载失败') });
          return;
        }
        this.setData({ detail, detailState: 'ready' });
      },
      fail: () => {
        if (seq !== this._detailSeq) return;
        this.setData({ detailState: 'error', detailError: '网络异常，请重试' });
      },
    });
  },
});

function isSuccess(res) {
  return !!(res && (res.code === 200 || res.code === '200'));
}

function successObject(res) {
  return !!(isSuccess(res) && res.data && typeof res.data === 'object' && !Array.isArray(res.data));
}

function errorText(res, fallback) {
  return (app.getRequestErrorMessage && app.getRequestErrorMessage(res, fallback)) || fallback;
}
