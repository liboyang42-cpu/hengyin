'use strict';
/* 入场节奏(细节打磨 G7):已有块按文档顺序错开 40ms,浮上 12px 淡入 420ms;plan 纯函数,play 走 animate;减少动态效果不播。 */
const { readReducedMotion } = require('./motion-preference.js');
const DURATION = 420, STAGGER = 40, RISE_RPX = 24, EASE = 'cubic-bezier(.32,.72,0,1)', MAX_STEPS = 12;
function plan(selectors, o) {
  o = o || {};
  const st = o.stagger >= 0 ? o.stagger : STAGGER, base = o.delay > 0 ? o.delay : 0;
  return (selectors || []).filter(Boolean).slice(0, MAX_STEPS).map((selector, i) => ({
    selector, delay: base + i * st, duration: o.duration > 0 ? o.duration : DURATION,
    frames: [{ offset: 0, opacity: 0, translateY: RISE_RPX, ease: EASE }, { offset: 1, opacity: 1, translateY: 0 }],
  }));
}
function play(ctx, selectors, options) {
  if (!ctx || typeof ctx.animate !== 'function' || typeof setTimeout !== 'function' || (ctx.data && ctx.data.reducedMotion) || readReducedMotion()) return [];
  const gone = () => ctx._unloaded || ctx._detached;
  const steps = plan(selectors, options);
  steps.forEach((s) => {
    const run = () => {
      if (gone()) return;
      ctx.animate(s.selector, s.frames, s.duration, () => { if (!gone() && ctx.clearAnimation) ctx.clearAnimation(s.selector, { opacity: true, translateY: true }, () => {}); });
    };
    if (s.delay > 0) setTimeout(run, s.delay); else run();
  });
  return steps;
}
module.exports = { DURATION, STAGGER, RISE_RPX, EASE, plan, play };
