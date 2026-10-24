// BL-76 商家核销记录(只读)。结构照 pages/merchant/aftercare:access/me 判权 → 列表四态。
// 接口 POST /api/merchant/verification-records 要 VERIFY_RECORD_READ(核销员也有),一次全量、不分页。
const app = getApp();
const toast = require('../../../utils/toast.js'); const pageMotion = require('../../../utils/page-motion.js');
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
          this._items = [];
          this.setData({ pageState: 'no-permission', groups: [] });
          return;
        }
        this.loadList();
      },
      fail: () => this.setData({ pageState: 'error', errorMessage: '网络异常，请重试' }),
    });
  },

  retry() {
    if (this._merchantAccess && this._merchantAccess.canReadVerifyRecords) this.loadList();
    else this.loadAccess();
  },

  // 下拉:已有列表时静默重读,失败只轻提示并保留列表;无权限/失败态走 retry
  onPullDownRefresh() {
    const state = this.data.pageState;
    const canRead = !!(this._merchantAccess && this._merchantAccess.canReadVerifyRecords);
    if (canRead && (state === 'ready' || state === 'empty')) { this.loadList(true); return; }
    wx.stopPullDownRefresh();
    if (state !== 'loading') this.retry();
  },

  loadList(fromPull) {
    if (!this._merchantAccess || !this._merchantAccess.canReadVerifyRecords) {
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
      url: '/api/merchant/verification-records',
      method: 'POST',
      success: (res) => {
        if (!settle()) return;
        const items = isSuccess(res) ? shapeRecordList(res.data) : null;
        if (!items) {
          if (keep) toast('刷新失败，稍后再试');
          else this.setData({ pageState: 'error', errorMessage: errorText(res, '核销记录加载失败') });
          return;
        }
        this.applyItems(items, this.data.bucket);
      },
      fail: () => {
        if (!settle()) return;
        if (keep) toast('刷新失败，稍后再试');
        else this.setData({ pageState: 'error', errorMessage: '网络异常，请重试' });
      },
    });
  },

  switchBucket(event) {
    const bucket = event && event.detail && event.detail.key;
    if (!STATUS_TABS.some(item => item.key === bucket) || bucket === this.data.bucket) return;
    this.applyItems(this._items || [], bucket);
  },

  applyItems(items, bucket) {
    const counts = countByStatus(items);
    const groups = groupRecords(items, bucket);
    // 全量行只给分段切换用,不上屏,不走 setData(U4)
    this._items = items;
    pageMotion.enter(this, 'pmList'); this.setData({
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
