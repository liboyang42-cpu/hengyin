// BL-76 商家核销记录(只读)。结构照 pages/merchant/aftercare:access/me 判权 → 列表四态。
// 接口 POST /api/merchant/finance/redemptions(与「核销与退款」同源)要 VERIFY_RECORD_READ(核销员也有),分页读全。
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
const PAGE_SIZE = 50;
const MAX_PAGES = 20;

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
    const collected = [];
    const failed = (res) => {
      if (keep) toast('刷新失败，稍后再试');
      else this.setData({ pageState: 'error', errorMessage: res ? errorText(res, '核销记录加载失败') : '网络异常，请重试' });
    };
    // 与「核销与退款」同源分页读取;服务端每页最多 50 条,读满 total 或到上限为止。
    const readPage = (pageNum) => app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/merchant/finance/redemptions',
      method: 'POST',
      // 服务端按 @RequestBody 收 JSON(与核销与退款页同一写法)
      data: JSON.stringify({ filter: 'all', pageNum, pageSize: PAGE_SIZE }),
      header: { 'Content-Type': 'application/json' },
      success: (res) => {
        if (seq !== this._listSeq) { if (fromPull) wx.stopPullDownRefresh(); return; }
        const items = isSuccess(res) ? shapeRecordList(res.data) : null;
        if (!items) { if (settle()) failed(res); return; }
        collected.push.apply(collected, items);
        const total = Number(res.data.total) || 0;
        if (items.length === PAGE_SIZE && collected.length < total && pageNum < MAX_PAGES) { readPage(pageNum + 1); return; }
        if (!settle()) return;
        this.applyItems(collected, this.data.bucket);
      },
      fail: () => {
        if (!settle()) return;
        failed(null);
      },
    });
    readPage(1);
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
