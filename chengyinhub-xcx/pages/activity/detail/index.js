// 深链兼容壳。活动取数、票务与评价只在 scene-play-activity-detail 维护。
const app = getApp()
const analytics = require('../../../utils/analytics.js')
const { activityShareFromEvent, buildActivityShare } = require('../../../utils/activity-share.js')
const { openScene } = require('../../../utils/scene-entry.js')
const policy = require('../../../utils/identity/identity-policy.js')
const merchantTheme = require('../../../utils/merchant-theme.js')
const referral = require('../../../utils/commerce/referral.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    id: '',
    isMerchantViewer: false,
    shareInfo: {},
  },

  onLoad(options) {
    referral.capture(options) // §18 I:落地只存来源凭据,不显示推荐人、不参与报价
    this.setData({ id: String((options && options.id) || '') })
    this.syncViewerTheme()
  },

  onShow() { this.syncViewerTheme() },
  onHide() { merchantTheme.merchantPageRestore() },
  onUnload() { merchantTheme.merchantPageRestore() },

  syncViewerTheme() {
    const isMerchantViewer = policy.isMerchantView({
      role: app.getUserRole(),
      userType: app.getUserType(),
      debugView: wx.getStorageSync('debug_user_view'),
    })
    this.setData({ isMerchantViewer })
    if (isMerchantViewer) merchantTheme.merchantPageShow()
    else merchantTheme.merchantPageRestore()
  },

  onSceneLoaded(event) {
    this.setData({ shareInfo: event.detail || {} })
  },

  openChildScene(event) {
    const detail = (event && event.detail) || {}
    if (detail.id) openScene(detail.id, detail.params || {})
  },

  goBack() {
    wx.navigateBack({ delta: 1, fail: () => wx.switchTab({ url: '/pages/index/index' }) })
  },

  onShareAppMessage(event) {
    const payload = activityShareFromEvent(event) || buildActivityShare({ ...this.data.shareInfo, id: this.data.id })
    if (payload && (!event || event.from !== 'button')) analytics.track('content_share', { bizType: 'activity', bizId: this.data.id })
    if (!payload) return { title: '精彩活动', path: '/pages/index/index' }
    // §18 I:异步换推广来源凭据并拼进 ref;失败静默降级为普通分享
    return Object.assign({}, payload, {
      promise: referral.sharePathPromise(getApp(), 2, this.data.id, payload.path).then((r) => Object.assign({}, payload, r))
    })
  },
})
