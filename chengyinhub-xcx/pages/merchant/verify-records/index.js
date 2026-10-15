// BL-76 商家核销记录(只读)。结构照 pages/merchant/aftercare:access/me 判权 → 列表四态。
// 接口 POST /api/merchant/verification-records 要 VERIFY_RECORD_READ(核销员也有),一次全量、不分页。
const app = getApp();
const merchantTheme = require('../../../utils/merchant-theme.js');
const { inactiveAccess, normalizeMerchantAccess } = require('../../../utils/merchant-access-policy.js');
const {
  STATUS_TABS,
  shapeRecordList,
  countByStatus,
  groupRecords,
} = require('./view-model.js');

Page({
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    tabs: STATUS_TABS,
    bucket: 'VERIFIED',
    pageState: 'loading',
    errorMessage: '',
    items: [],
    groups: [],
  },

  onLoad() {
    const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    this._merchantAccess = inactiveAccess();
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
        if (!access.active || !access.canReadVerifyRecords) {
          this.setData({ pageState: 'no-permission', items: [], groups: [] });
          return;
        }
        this.loadList();
      },
      fail: () => this.setData({ pageState: 'error', errorMessage: '网络连接失败，请稍后重试' }),
    });
  },

  retry() {
    if (this._merchantAccess && this._merchantAccess.canReadVerifyRecords) this.loadList();
    else this.loadAccess();
  },

  loadList() {
    if (!this._merchantAccess || !this._merchantAccess.canReadVerifyRecords) return;
    this.setData({ pageState: 'loading', errorMessage: '' });
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/merchant/verification-records',
      method: 'POST',
      success: (res) => {
        const items = isSuccess(res) ? shapeRecordList(res.data) : null;
        if (!items) {
          this.setData({ pageState: 'error', errorMessage: errorText(res, '核销记录加载失败') });
          return;
        }
        this.applyItems(items, this.data.bucket);
      },
      fail: () => this.setData({ pageState: 'error', errorMessage: '网络连接失败，请稍后重试' }),
    });
  },

  switchBucket(event) {
    const bucket = event && event.detail && event.detail.key;
    if (!STATUS_TABS.some(item => item.key === bucket) || bucket === this.data.bucket) return;
    this.applyItems(this.data.items, bucket);
  },

  applyItems(items, bucket) {
    const counts = countByStatus(items);
    const groups = groupRecords(items, bucket);
    this.setData({
      items,
      bucket,
      tabs: STATUS_TABS.map(tab => ({ key: tab.key, label: `${tab.label} ${counts[tab.key] || 0}` })),
      groups,
      pageState: groups.length ? 'ready' : 'empty',
      errorMessage: '',
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
