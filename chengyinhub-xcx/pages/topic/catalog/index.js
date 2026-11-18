// F14c(PG-025,业主 2026-11-17 定下线):城市合集 / 合集详情两页已下线 —— 小程序里没有入口,iOS 也没有对应功能。
// 这里只留一个轻量重定向页接旧分享卡:带主题 id 的进主题页,其余回首页。旧的 catalog/detail 分享卡
// 由 app.onPageNotFound(utils/update-manager.js)按同一规则转走。保留 30 天后连同本页一起删。
const HOME = '/pages/index/index'

function redirectTarget(options) {
  const id = String((options && options.id) || '')
  return /^\d{1,19}$/.test(id) ? '/pages/topic/index/index?id=' + id : HOME
}

Page({
  onLoad(options) {
    const url = redirectTarget(options)
    if (url === HOME) wx.reLaunch({ url })
    else wx.redirectTo({ url, fail: () => wx.reLaunch({ url: HOME }) })
  }
})

if (typeof module !== 'undefined') module.exports = { redirectTarget }
