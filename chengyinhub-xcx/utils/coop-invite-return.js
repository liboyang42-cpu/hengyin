// 导览台发起的邀约回原弹窗；首页发起的邀约留在对象主页。
function returnToCoopOrigin(topicId) {
  const pages = getCurrentPages();
  for (let i = pages.length - 2; i >= 0; i--) {
    const page = pages[i];
    if (page.route !== 'pages/topic/merchantinfo/merchantinfo' || String(page.data.topicId) !== String(topicId)) continue;
    if (!((page.data.clubSheet || {}).show || (page.data.merchantSheet || {}).show)) continue;
    page.loadProjectHome();
    wx.navigateBack({ delta: pages.length - 1 - i });
    return true;
  }
  return false;
}
module.exports = { returnToCoopOrigin };
