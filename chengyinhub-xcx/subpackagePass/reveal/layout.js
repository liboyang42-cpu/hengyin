// 钥匙开场全屏版式(2026-11-19 业主「通行证要金色、页面不是全屏」)。
// 背景与渐变铺满整个视口(含状态栏和透明导航后面);序列帧画框保持烤帧的 3:4,
// 按机型算尺寸并居中在文字区上方的「钥匙区」里:矮屏缩小、高屏不放大,钥匙始终完整可见。
// 纯函数,单测直接喂窗口尺寸。

const FRAME_W = 750;     // 烤帧画框原尺寸(rpx)
const FRAME_H = 1000;
const KEY_ZONE = 0.78;   // 视口上方这一段留给钥匙;下面是眉题 / 标题 / 正文 / 按钮

function stageBox(win) {
  const ww = Number(win && win.windowWidth) || 375;
  const wh = Number(win && win.windowHeight) || 667;
  const vh = Math.round(wh * 750 / ww);
  const zone = vh * KEY_ZONE;
  // 高取 8 的倍数:宽 = 高 × 3/4 是偶数,帧切片与左右留白都是整 rpx
  const h = Math.max(8, Math.floor(Math.min(FRAME_H, zone) / 8) * 8);
  const w = h * 3 / 4;
  return {
    w,
    h,
    left: Math.round((FRAME_W - w) / 2),
    top: Math.max(0, Math.round((zone - h) / 2)),
    vh,
  };
}

module.exports = { stageBox, KEY_ZONE, FRAME_W, FRAME_H };
