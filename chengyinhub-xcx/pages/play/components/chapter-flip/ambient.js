'use strict';
/* 章节门的环境细节(细节打磨 S2 / S3,2026-11-12):只加在已有画面「后面」,不改任何布局 / 文案 / 配色。
 *   · 光晕:章节号(S2)或通关卡(S3)背后一团外观强调色的柔光,3 秒一呼吸(scale 1→1.12,opacity .4→1);
 *   · 星尘:6–10 颗外观色小点缓缓上飘,每颗 ≤10% 不透明度;
 *   · 光芒(只通关):强调色放射光,极低不透明度,缓慢转。
 * 全部走 this.animate,没有新增 @keyframes;减少动态效果 / 应用内「动态效果」关闭时一概不起。 */
const HALO_HALF_MS = 1500;
const SPARKLES = 8;
const SPARK_MAX_OPACITY = 0.1;

function haloFrames(up) {
  const a = { opacity: 0.4, scale: [1, 1], ease: 'ease-in-out' };
  const b = { opacity: 1, scale: [1.12, 1.12], ease: 'ease-in-out' };
  return up ? [Object.assign({ offset: 0 }, a), Object.assign({ offset: 1 }, b)] : [Object.assign({ offset: 0 }, b), Object.assign({ offset: 1 }, a)];
}

function sparkFrames(i) {
  const dx = (i % 2 ? 1 : -1) * (14 + (i * 7) % 22);
  return [
    { offset: 0, opacity: 0, translateX: 0, translateY: 0, ease: 'ease-out' },
    { offset: 0.4, opacity: SPARK_MAX_OPACITY, translateX: dx / 2, translateY: -50, ease: 'ease-in' },
    { offset: 1, opacity: 0, translateX: dx, translateY: -120 },
  ];
}

/* cx / cy:光晕中心(px)。rays:通关才有放射光。 */
function start(comp, opts) {
  if (!comp || comp._gone || comp.data.reducedMotion || typeof comp.animate !== 'function') return;
  const o = opts || {};
  const cx = Number(o.cx) || 0, cy = Number(o.cy) || 0;
  const sparks = [];
  for (let i = 0; i < SPARKLES; i++) {
    sparks.push({ i, left: Math.round(8 + ((i * 37 + 11) % 84)), top: Math.round(cy ? (cy - 140 + ((i * 53) % 280)) : 200 + i * 40) });
  }
  comp.setData({ ambient: { on: true, rays: !!o.rays, haloStyle: 'left:' + (cx - 150) + 'px;top:' + (cy - 150) + 'px;', sparks } }, () => {
    if (comp._gone) return;
    let up = true;
    const pulse = () => {
      if (comp._gone || !comp.data.ambient || !comp.data.ambient.on) return;
      comp.animate('.cf__halo', haloFrames(up), HALO_HALF_MS, () => { up = !up; pulse(); });
    };
    pulse();
    sparks.forEach((s) => {
      const loop = () => {
        if (comp._gone || !comp.data.ambient || !comp.data.ambient.on) return;
        comp.animate('.cf__spl-' + s.i, sparkFrames(s.i), 3600 + (s.i % 3) * 700, () => { comp._ambTimers = (comp._ambTimers || []); comp._ambTimers.push(setTimeout(loop, 200 + s.i * 120)); });
      };
      comp._ambTimers = (comp._ambTimers || []); comp._ambTimers.push(setTimeout(loop, s.i * 260));
    });
    if (o.rays) {
      const spin = () => {
        if (comp._gone || !comp.data.ambient || !comp.data.ambient.on) return;
        comp.animate('.cf__rays', [{ offset: 0, rotate: 0 }, { offset: 1, rotate: 360, ease: 'linear' }], 40000, spin);
      };
      spin();
    }
  });
}

function stop(comp) {
  (comp._ambTimers || []).forEach(clearTimeout); comp._ambTimers = [];
  if (comp.data && comp.data.ambient && comp.data.ambient.on && !comp._gone) comp.setData({ ambient: { on: false } });
}

module.exports = { HALO_HALF_MS, SPARKLES, SPARK_MAX_OPACITY, haloFrames, sparkFrames, start, stop };
