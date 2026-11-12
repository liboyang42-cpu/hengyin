'use strict';
/* 头图视差(细节打磨 S5,2026-11-12):页面滚动时头图以 0.5× 的速度跟着走 —— 视觉上「更远」。
 * 不 setData:每次滚动只给视图层下一条 animate 指令(时长 1ms 的定位帧),不跨线程拷贝页面数据。
 * 只在 0 ≤ scrollTop ≤ limit 内动,iOS 下拉回弹(负值)与滚出头图之后都不处理;减少动态效果直接不动。
 * 原有的导航栏折叠 / 吸顶逻辑完全不碰。 */
const { readReducedMotion } = require('../../../utils/motion-preference.js');

const FACTOR = 0.5;
const MIN_GAP_MS = 16;

/* 纯函数:这次滚动该把头图往下挪多少 px;null = 不动。 */
function offsetFor(scrollTop, options) {
  const o = options || {};
  const t = Number(scrollTop);
  if (!isFinite(t) || t < 0) return null;
  const limit = o.limit > 0 ? o.limit : 600;
  return Math.round(Math.min(t, limit) * (o.factor > 0 ? o.factor : FACTOR) * 10) / 10;
}

/* anchor:头图容器的选择器。头图在页面中间(上面还有一屏)时,视差只从头图顶边滚到屏幕顶之后才开始 ——
 * 否则把图往下挪会在头图顶边露出一条底色。首次滚动时量一次(绝对位置 = 容器顶 + 当前滚动)。 */
function follow(page, selector, scrollTop, options) {
  if (!page || typeof page.animate !== 'function') return false;
  const anchor = options && options.anchor;
  if (anchor && page._pxTop === undefined) {
    if (page._pxMeasuring || typeof wx === 'undefined' || typeof wx.createSelectorQuery !== 'function') return false;
    page._pxMeasuring = true;
    wx.createSelectorQuery().in(page).select(anchor).boundingClientRect((r) => {
      page._pxMeasuring = false;
      page._pxTop = r ? Math.max(0, r.top + (Number(scrollTop) || 0)) : 0;
    }).exec();
    return false;
  }
  if (anchor) scrollTop = Number(scrollTop) - (page._pxTop || 0);
  if (page._pxOff === undefined) page._pxOff = readReducedMotion() || !!(page.data && page.data.reducedMotion);
  if (page._pxOff) return false;
  const now = Date.now();
  if (page._pxAt && now - page._pxAt < MIN_GAP_MS) return false;
  let y = offsetFor(scrollTop, options);
  if (y === null && page._pxY > 0) y = 0;   // 滚回头图上方:放回原位,不留位移
  if (y === null || y === page._pxY) return false;
  page._pxAt = now; page._pxY = y;
  page.animate(selector, [{ offset: 0, translateY: y }, { offset: 1, translateY: y }], 1, () => {});
  return true;
}

module.exports = { FACTOR, offsetFor, follow };
