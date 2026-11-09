const app = getApp()
const merchantTheme = require('../../utils/merchant-theme.js')
const pageMotion = require('../../utils/page-motion.js')

// D23 深链壳：正文落页，不再套常驻半屏。记录正文仍与账户收益入口共用同一个 scene。
Page({
  data: { pmPage: 0 },
  // G-11(uixsiam09):进页时正文整块从下方推上来;只用过渡,减少动态效果时不做
  onLoad() { pageMotion.enter(this, 'pmPage', { once: true }) },
  onShow() { merchantTheme.merchantPageShow() },
  onHide() { merchantTheme.merchantPageRestore() },
  onUnload() { this._unloaded = true; clearTimeout(this._pmPageTimer); merchantTheme.merchantPageRestore() },
  onReachBottom() {
    const content = this.selectComponent('#inviteHistory')
    if (content && typeof content.loadMore === 'function') content.loadMore()
  },
  onClose() {
    if (getCurrentPages().length > 1) {
      wx.navigateBack()
      return
    }
    wx.redirectTo({ url: '/subpackageA/pages/assetcenter/earnings/index' })
  },
  onShareAppMessage() {
    return {
      title: '城瘾Hub',
      path: '/pages/index/index?inviter=' + app.getUserID(),
    }
  },
})
