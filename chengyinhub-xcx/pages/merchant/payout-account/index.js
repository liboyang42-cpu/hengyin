// l-payout 商家收款账户(只读)。access/me 判 FINANCE_READ → POST /api/merchant/payout-account。
// 进件资料由平台运营在后台提交;这里只展示服务端事实,需要本人签约/验证时给出可复制的链接。
const app = getApp();
const toast = require('../../../utils/toast.js');
const merchantTheme = require('../../../utils/merchant-theme.js');
const { inactiveAccess, normalizeMerchantAccess } = require('../../../utils/merchant-access-policy.js');
const { shapeAccount } = require('./view-model.js');

Page({
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    pageState: 'loading',
    errorMessage: '',
    account: null,
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
        if (!access.active || !access.canReadFinance) {
          this.setData({ pageState: 'no-permission', account: null });
          return;
        }
        this.loadAccount();
      },
      fail: () => this.setData({ pageState: 'error', errorMessage: '网络异常，请重试' }),
    });
  },

  retry() {
    if (this._merchantAccess && this._merchantAccess.canReadFinance) this.loadAccount();
    else this.loadAccess();
  },

  onPullDownRefresh() {
    const canRead = !!(this._merchantAccess && this._merchantAccess.canReadFinance);
    if (canRead && this.data.pageState !== 'loading') { this.loadAccount(true); return; }
    wx.stopPullDownRefresh();
  },

  loadAccount(fromPull) {
    this._seq = (this._seq || 0) + 1;
    const seq = this._seq;
    if (!fromPull) this.setData({ pageState: 'loading', errorMessage: '' });
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/merchant/payout-account',
      method: 'POST',
      success: (res) => {
        if (fromPull) wx.stopPullDownRefresh();
        if (seq !== this._seq) return;
        const shaped = successObject(res) ? shapeAccount(res.data) : null;
        if (!shaped) {
          if (fromPull && this.data.pageState === 'ready') { toast('刷新失败，稍后再试'); return; }
          this.setData({ pageState: 'error', errorMessage: errorText(res, '收款账户加载失败') });
          return;
        }
        this.setData({ pageState: shaped.pageState, account: shaped.pageState === 'ready' ? shaped : null });
      },
      fail: () => {
        if (fromPull) wx.stopPullDownRefresh();
        if (seq !== this._seq) return;
        if (fromPull && this.data.pageState === 'ready') { toast('刷新失败，稍后再试'); return; }
        this.setData({ pageState: 'error', errorMessage: '网络异常，请重试' });
      },
    });
  },

  copySignUrl() {
    const url = this.data.account && this.data.account.signUrl;
    if (!url) return;
    wx.setClipboardData({ data: url, success: () => toast('已复制') });
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
