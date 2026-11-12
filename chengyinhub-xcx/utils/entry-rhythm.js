const { readReducedMotion } = require('./motion-preference.js');
const F = [{ offset: 0, opacity: 0, translateY: 12, ease: 'cubic-bezier(.32,.72,0,1)' }, { offset: 1, opacity: 1, translateY: 0 }];
function play(ctx, sels, delay) {
  if (!ctx || !ctx.animate || (ctx.data && ctx.data.reducedMotion) || readReducedMotion()) return;
  sels.filter(Boolean).forEach((sel, i) => setTimeout(() => {
    if (!ctx._unloaded && !ctx._detached) ctx.animate(sel, F, 420, () => ctx.clearAnimation && ctx.clearAnimation(sel, { opacity: true, translateY: true }));
  }, (delay || 0) + i * 40));
}
module.exports = { play };
