// BL-100 商家触达任务记录(只读)。结构照 pages/merchant/verify-records:access/me 判权 → 列表四态。
// 列表 GET /api/merchant/crm/campaigns、详情 GET /api/merchant/crm/campaigns/{id},都要 MARKETING_READ。
// 不做创建/投递/重试(POST /campaigns、/dispatch、/retry)。
const app = getApp();
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
      fail: () => this.setData({ pageState: 'error', errorMessage: '网络连接失败，请稍后重试' }),
    });
  },

  retry() {
    if (this._merchantAccess && this._merchantAccess.canReadMarketing) this.loadList();
    else this.loadAccess();
  },

  loadList() {
    if (!this._merchantAccess || !this._merchantAccess.canReadMarketing) return;
    this.setData({ pageState: 'loading', errorMessage: '' });
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/merchant/crm/campaigns',
      method: 'GET',
      success: (res) => {
        const items = isSuccess(res) ? shapeCampaignList(res.data) : null;
        if (!items) {
          this.setData({ pageState: 'error', errorMessage: errorText(res, '触达记录加载失败') });
          return;
        }
        this.setData({ items, pageState: items.length ? 'ready' : 'empty', errorMessage: '' });
      },
      fail: () => this.setData({ pageState: 'error', errorMessage: '网络连接失败，请稍后重试' }),
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
        this.setData({ detailState: 'error', detailError: '网络连接失败，请稍后重试' });
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
