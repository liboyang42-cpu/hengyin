'use strict';
/* 自由探索卡包的活细节(细节打磨 S4,2026-11-12):都挂在卡包「里面」的元素上,不碰 .fx-pack 自己的旋转 / 抖动 / 撕开,
 *   · 斜向流光:一道斜光 3 秒从左扫到右,循环;
 *   · 悬浮:上下 ±6px,3 秒一个来回;
 *   · 点按挤压:先缩到 .96 再弹到 1.04 再回 1,和开包的抖动同时起;
 *   · 发牌后六宫格卡的小过冲由 index.wxss 的 .is-dealt 过渡接(曲线换成过冲型)。
 * 走 page.animate,不新增 @keyframes;减少动态效果、包已开完时一概不起。 */
const SHEEN_MS = 3000;
const FLOAT_HALF_MS = 1500;
const FLOAT_PX = 6;

function sheenFrames() {
  return [{ offset: 0, translateX: -260, skew: [-18, 0], opacity: 0.9 }, { offset: 1, translateX: 560, skew: [-18, 0], opacity: 0.9, ease: 'ease-in-out' }];
}
function floatFrames(up) {
  const a = { translateY: FLOAT_PX, ease: 'ease-in-out' }, b = { translateY: -FLOAT_PX, ease: 'ease-in-out' };
  return up ? [Object.assign({ offset: 0 }, a), Object.assign({ offset: 1 }, b)] : [Object.assign({ offset: 0 }, b), Object.assign({ offset: 1 }, a)];
}
function squashFrames() {
  return [{ offset: 0, scale: [1, 1], ease: 'ease-out' }, { offset: 0.35, scale: [0.96, 0.96], ease: 'ease-out' }, { offset: 0.75, scale: [1.04, 1.04], ease: 'ease-in-out' }, { offset: 1, scale: [1, 1] }];
}

function alive(page) {
  return page && !page._unloaded && !page._detached && page.data && !page.data.reducedMotion && !page.data.packOpen;
}

function ensure(page) {
  if (!alive(page) || page._packDetailOn || typeof page.animate !== 'function') return;
  const d = page.data;
  if (d.mode !== 2 || !(d.seatTiles || []).length) return;
  page._packDetailOn = true;
  let up = true;
  const sheen = () => {
    if (!alive(page)) { page._packDetailOn = false; return; }
    page.animate('.fx-pack__sheen', sheenFrames(), SHEEN_MS, sheen);
  };
  const bob = () => {
    if (!alive(page)) { page._packDetailOn = false; return; }
    page.animate('.fx-pack__body', floatFrames(up), FLOAT_HALF_MS, () => { up = !up; bob(); });
  };
  sheen(); bob();
}

function squash(page) {
  if (!alive(page) || typeof page.animate !== 'function') return;
  page.animate('.fx-pack__body', squashFrames(), 260, () => {});
}

module.exports = { SHEEN_MS, FLOAT_PX, sheenFrames, floatFrames, squashFrames, ensure, squash };
