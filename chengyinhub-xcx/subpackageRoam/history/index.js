// 深链兼容壳。RunCard 与汇总正文只在 scene-roam-history 维护。
const app = getApp()

Page({
  // 漫游记录里的那年今日 / 月度回顾 / 导出(2026-10-14 随漫游上线):弹窗组件只发事件,跳转在宿主页
  openRoamTool(e) {
    const key = e && e.detail && e.detail.key;
    if (key === 'anniversary') wx.navigateTo({ url: '/subpackageTools/anniversary/index' });
    else if (key === 'recap') wx.navigateTo({ url: '/subpackageTools/monthly-recap/index' });
    else if (key === 'export') wx.navigateTo({ url: '/subpackageTools/exploration-export/index' });
  },
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
  },
  openSession(event) {
    const detail = event.detail || {}
    wx.navigateTo({ url: `/subpackageRoam/session/index?ts=${encodeURIComponent(detail.params && detail.params.ts || '')}&sessionId=${encodeURIComponent(detail.params && detail.params.sessionId || '')}` })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/roam/index' })
  },
})
