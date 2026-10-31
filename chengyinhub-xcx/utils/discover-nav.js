// 2026-10-29 业主:帖文流只剩三处 —— 发现(全站)、俱乐部详情、我的。原来跳「动态」二级页
// (pages/square/list)的入口都改成去发现页的帖文 tab。switchTab 不能带参数,
// 用 globalData 上的一次性标记,发现页 onShow 时消费。调用方先 markDiscoverFeed 再自己
// wx.switchTab({ url: '/pages/talent/list/index' }) —— 跳转写在调用点,返回栈审计和路由溯源才看得见。
function markDiscoverFeed(app) {
  if (app && app.globalData) app.globalData.discoverFeedTab = true;
}

function consumeDiscoverFeed(app) {
  const g = app && app.globalData;
  if (!g || g.discoverFeedTab !== true) return false;
  g.discoverFeedTab = false;
  return true;
}

module.exports = { markDiscoverFeed, consumeDiscoverFeed };
