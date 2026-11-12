'use strict';
/* 右上角 ⋯ 工具菜单的展开(细节打磨 S9,2026-11-12):菜单从按钮所在的右上角缩放展开,420ms,cubic-bezier(.32,.72,0,1);
 * 行的逐行错开(30ms)在样式里用既有的 cy-rise-in 做,这里只管整块的缩放。
 * 走 ctx.animate,不新增 @keyframes;减少动态效果时不缩放(菜单照常出现)。 */
const { readReducedMotion } = require('../../../utils/motion-preference.js');

const DURATION = 420;
const FROM_SCALE = 0.9;
const EASE = 'cubic-bezier(.32,.72,0,1)';

function frames() {
  return [
    { offset: 0, opacity: 0, scale: [FROM_SCALE, FROM_SCALE], ease: EASE },
    { offset: 1, opacity: 1, scale: [1, 1] },
  ];
}

function open(ctx, selector) {
  if (!ctx || typeof ctx.animate !== 'function') return false;
  if ((ctx.data && ctx.data.reducedMotion) || readReducedMotion()) return false;
  ctx.animate(selector || '.tools--corner', frames(), DURATION, () => {
    if (typeof ctx.clearAnimation === 'function') ctx.clearAnimation(selector || '.tools--corner', { opacity: true, scale: true }, () => {});
  });
  return true;
}

module.exports = { DURATION, FROM_SCALE, EASE, frames, open };
