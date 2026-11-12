'use strict';
/* 入场节奏(细节打磨 G7 / L4,2026-11-12):页面或半屏出现时,已有的块按文档顺序错开 40ms,
 * 各自「浮上来 12px + 淡入」420ms,主操作最后。只用微信 animate 接口(this.animate),不新增 @keyframes。
 *
 * 计划(plan)是纯函数,执行(play)很薄 —— 与 utils/motion.js 同一做法,计划能在 node 里单测。
 * 减少动态效果(页面 data.reducedMotion 或系统偏好)时一概不播:内容原样在位,不隐不藏。
 * 注意:animate 的补间从 opacity 0 起、落到 1 并 clearAnimation 前保持终态;不改任何布局。 */
const { readReducedMotion } = require('./motion-preference.js');

const DURATION = 420;
const STAGGER = 40;
const RISE_RPX = 24;                 // 12px ≈ 24rpx(390 宽下 1rpx = .5px)
const EASE = 'cubic-bezier(.32,.72,0,1)';
const MAX_STEPS = 12;                // 封顶:长列表后面的块不再继续排队

function plan(selectors, options) {
  const o = options || {};
  const list = (selectors || []).filter(Boolean).slice(0, MAX_STEPS);
  const stagger = o.stagger >= 0 ? o.stagger : STAGGER;
  const base = o.delay > 0 ? o.delay : 0;
  return list.map((selector, i) => ({
    selector,
    delay: base + i * stagger,
    duration: o.duration > 0 ? o.duration : DURATION,
    frames: [
      { offset: 0, opacity: 0, translateY: RISE_RPX, ease: EASE },
      { offset: 1, opacity: 1, translateY: 0 },
    ],
  }));
}

function reduced(ctx) {
  return !!(ctx && ctx.data && ctx.data.reducedMotion) || readReducedMotion();
}

/* ctx:页面或组件实例(有 animate / clearAnimation)。selectors 按文档顺序,最后一个放主操作。 */
function play(ctx, selectors, options) {
  if (!ctx || typeof ctx.animate !== 'function' || typeof setTimeout !== 'function' || reduced(ctx)) return [];
  const steps = plan(selectors, options);
  steps.forEach((s) => {
    const run = () => {
      if (ctx._unloaded || ctx._detached) return;
      ctx.animate(s.selector, s.frames, s.duration, () => {
        if (ctx._unloaded || ctx._detached) return;
        if (typeof ctx.clearAnimation === 'function') ctx.clearAnimation(s.selector, { opacity: true, translateY: true }, () => {});
      });
    };
    if (s.delay > 0) setTimeout(run, s.delay); else run();
  });
  return steps;
}

module.exports = { DURATION, STAGGER, RISE_RPX, EASE, plan, play };
