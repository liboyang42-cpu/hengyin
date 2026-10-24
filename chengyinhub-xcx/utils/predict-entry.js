// 消息页「竞猜待答」入口(owner 2026-10-22:没有待答时这一行不该出现)。
//
// 先按身份判(能结算竞猜的商家才有资格看),再问一次待答列表:
//   · 有待答 → 出;没有待答 → 不出(常驻的「0 条」入口等于每天骗一次点击);
//   · 待答列表读失败 → 照样出(宁可多一行入口,也不能让 48 小时期限的提醒悄悄消失)。
// 身份已经确认是商家,所以这里的失败不会和「不是商家」的正常回执混在一起被吞掉。

function showIfPending(page, app, access, isCurrent) {
  if (!(access && access.active && access.canManageProjects)) {
    page.setData({ canSettlePredict: false });
    return;
  }
  app.sendRequest({
    hideLoading: true,
    silentError: true,
    url: '/api/merchant/predict/inbox',
    method: 'POST',
    success(res) {
      if (!isCurrent()) return;
      const ok = res && (res.code == '200' || res.code === 200) && Array.isArray(res.data);
      page.setData({ canSettlePredict: ok ? res.data.length > 0 : true });
    },
    fail() {
      if (!isCurrent()) return;
      page.setData({ canSettlePredict: true });
    },
  });
}

module.exports = { showIfPending };
