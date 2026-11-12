'use strict';
/* 活的细节(细节打磨二期 L6,2026-11-12):主图标 / 插画缓慢呼吸(scale 1→1.03,3 秒一个来回)。
 * 只用微信 animate 接口,不新增 @keyframes;减少动态效果(实例 data.reducedMotion 或系统偏好)一概不起。
 * 计划(frames)是纯函数,可在 node 里单测;start / stop 很薄。 */
const { readReducedMotion } = require('./motion-preference.js');

const HALF_MS = 1500;
const PEAK = 1.03;

function frames(up) {
  const lo = { scale: [1, 1], ease: 'ease-in-out' };
  const hi = { scale: [PEAK, PEAK], ease: 'ease-in-out' };
  return up ? [Object.assign({ offset: 0 }, lo), Object.assign({ offset: 1 }, hi)] : [Object.assign({ offset: 0 }, hi), Object.assign({ offset: 1 }, lo)];
}

function start(ctx, selector) {
  if (!ctx || typeof ctx.animate !== 'function' || !selector) return false;
  if ((ctx.data && ctx.data.reducedMotion) || readReducedMotion()) return false;
  let up = true;
  ctx._lifeOn = (ctx._lifeOn || 0) + 1;
  const token = ctx._lifeOn;
  const beat = () => {
    if (ctx._lifeGone || ctx._lifeOn !== token) return;
    ctx.animate(selector, frames(up), HALF_MS, () => { up = !up; beat(); });
  };
  beat();
  return true;
}

function stop(ctx) {
  if (!ctx) return;
  ctx._lifeGone = true;
}

module.exports = { HALF_MS, PEAK, frames, start, stop };
