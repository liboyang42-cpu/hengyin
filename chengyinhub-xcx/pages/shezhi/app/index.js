// R11 growth-loops(GRO-05):小程序 → App。
// 1) 从 App 打开的小程序(场景 1069)可以用 <button open-type="launchApp"> 直接回 App;
// 2) 其他场景微信不允许直接拉起 App,只能复制通用链接页,在 Safari 打开:装了 App 由 iOS 直接打开 App,没装则网页给 App Store 入口。
// 通用链接与 App Store 地址由服务端配置(GET /api/growth-loops/app-handoff);App Store 地址未配置时如实写「即将上架」。
const app = getApp();
const toast = require('../../../utils/toast.js');
const policy = require('../../../utils/identity/identity-policy.js');
const merchantTheme = require('../../../utils/merchant-theme.js');

const LAUNCH_APP_SCENES = [1069];
const FALLBACK_LINK = 'https://www.chengyinhub.com/door';

function launchScene() {
  try {
    const opts = wx.getEnterOptionsSync ? wx.getEnterOptionsSync() : wx.getLaunchOptionsSync();
    return Number(opts && opts.scene) || 0;
  } catch (e) {
    return 0;
  }
}

function withSource(link) {
  return link + (link.indexOf('?') >= 0 ? '&' : '?') + 'scene=mp-handoff';
}

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight || 20,
    navBarHeight: app.globalData.navBarHeight || 44,
    isMerchantView: false,
    canLaunchApp: false,
    universalLink: withSource(FALLBACK_LINK),
    appStoreUrl: '',
    loaded: false,
  },

  onLoad() {
    this.syncViewTheme();
    this.setData({ canLaunchApp: LAUNCH_APP_SCENES.indexOf(launchScene()) >= 0 });
    this.load();
  },

  onShow() { this.syncViewTheme(); },
  onHide() { merchantTheme.merchantPageRestore(); },
  onUnload() { merchantTheme.merchantPageRestore(); },

  syncViewTheme() {
    const isMerchantView = policy.isMerchantView({
      role: wx.getStorageSync('role'),
      userType: wx.getStorageSync('user_type'),
      debugView: wx.getStorageSync('debug_user_view'),
    });
    this.setData({ isMerchantView });
    if (isMerchantView) merchantTheme.merchantPageShow();
    else merchantTheme.merchantPageRestore();
  },

  load() {
    app.sendRequest({
      hideLoading: true,
      url: '/api/growth-loops/app-handoff',
      method: 'GET',
      success: (res) => {
        const data = res && Number(res.code) === 200 && res.data ? res.data : {};
        const link = typeof data.universalLink === 'string' && data.universalLink.indexOf('https://') === 0 ? data.universalLink : FALLBACK_LINK;
        const store = typeof data.appStoreUrl === 'string' && data.appStoreUrl.indexOf('https://apps.apple.com/') === 0 ? data.appStoreUrl : '';
        this.setData({ universalLink: withSource(link), appStoreUrl: store, loaded: true });
      },
      fail: () => this.setData({ loaded: true }),
    });
  },

  onLaunchAppError(e) {
    // 没装 App、版本太旧或场景不允许时微信回 errMsg;降级为复制链接。
    this.setData({ canLaunchApp: false });
    toast('没能打开，请复制链接');
  },

  copyLink() {
    wx.setClipboardData({ data: this.data.universalLink });
  },

  copyStore() {
    if (!this.data.appStoreUrl) return;
    wx.setClipboardData({ data: this.data.appStoreUrl });
  },

  onBack() {
    wx.navigateBack({
      fail() { wx.redirectTo({ url: '/pages/shezhi/shezhi' }); },
    });
  },
});
