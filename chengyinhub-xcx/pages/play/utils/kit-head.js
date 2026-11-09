// P-09 第四轮(业主 2026-11-03):整屏猜数字的题目和返回钮、微信胶囊在同一行。
// 返回钮是 88rpx 的圆,和胶囊竖向居中对齐:顶 = 胶囊中线 - 半个钮高(px)。拿不到胶囊坐标时退回 actionTop。
function kitHeadChrome(chrome, windowInfo, menu) {
  const out = Object.assign({}, chrome);
  const width = Number((windowInfo || {}).windowWidth) || 375;
  const btn = 88 * width / 750;
  const top = Number(menu && menu.top);
  const height = Number(menu && menu.height);
  out.kitTop = Number.isFinite(top) && height > 0
    ? Math.max(0, Math.round(top + height / 2 - btn / 2))
    : out.actionTop;
  return out;
}

module.exports = { kitHeadChrome };
