'use strict';
/* 活的细节(细节打磨二期 L6):主图标缓慢呼吸 scale 1→1.03,3 秒一来回;animate 接口,减少动态效果不起。 */
const { readReducedMotion } = require('./motion-preference.js');
const HALF_MS = 1500, PEAK = 1.03;
function frames(up) {
  const lo = { scale: [1, 1], ease: 'ease-in-out' }, hi = { scale: [PEAK, PEAK], ease: 'ease-in-out' };
  return up ? [Object.assign({ offset: 0 }, lo), Object.assign({ offset: 1 }, hi)] : [Object.assign({ offset: 0 }, hi), Object.assign({ offset: 1 }, lo)];
}
function start(ctx, selector) {
  if (!ctx || typeof ctx.animate !== 'function' || !selector || (ctx.data && ctx.data.reducedMotion) || readReducedMotion()) return false;
  let up = true;
  const token = ctx._lifeOn = (ctx._lifeOn || 0) + 1;
  const beat = () => { if (!ctx._lifeGone && ctx._lifeOn === token) ctx.animate(selector, frames(up), HALF_MS, () => { up = !up; beat(); }); };
  beat();
  return true;
}
function stop(ctx) { if (ctx) ctx._lifeGone = true; }
module.exports = { HALF_MS, PEAK, frames, start, stop };
