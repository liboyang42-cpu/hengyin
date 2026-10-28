// 营销消息与优惠券(P-55,业主 2026-10-27 p96:「不能是弹窗,要点进去,页面也要重新设计」)。
// 读写逻辑仍在 pages/shezhi/components/marketing-consent,本页只是承载它的正常页面。
const app = getApp();
const policy = require('../../../utils/identity/identity-policy.js');
const merchantTheme = require('../../../utils/merchant-theme.js');

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight || 20,
    navBarHeight: app.globalData.navBarHeight || 44,
    isMerchantView: false,
    ready: false,
  },

  onLoad() {
    this.syncViewTheme();
    this.setData({ ready: true });
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

  onBack() {
    wx.navigateBack({
      fail() { wx.redirectTo({ url: '/pages/shezhi/shezhi' }); },
    });
  },
});
