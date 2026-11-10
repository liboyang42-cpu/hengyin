'use strict';
/**
 * utils/motion-spring.js —— 弹簧的两种表达(r4-motion,2026-11-10,MOTION.md §2.6)
 * ① 页面侧 curve()/releaseToTransition():预解算的 CSS 近似(时长 + cubic-bezier),写进 transition 让合成器跑帧。
 *    四条曲线与 tokens.wxss 的 --cy-ease-roll / -spring-soft / -spring / -settle 同值(JS 读不到 WXSS 变量,各留一份)。
 * ② step():半隐式欧拉单帧,给手势松手回弹用;WXS 不能 require,要用时在 gesture.wxs 逐行镜像,这里是真源与单测对象。
 * 纯函数:不碰 wx / DOM,不读减动效(减动效时调用方直接写终态)。
 */

// 预设:(刚度 k, 阻尼 c, 质量 1) + 预解算的 CSS 近似(时长 ms + 缓动)
const PRESETS = Object.freeze({
  // 快速到位,几乎不过冲:数字滚柱、小位移吸附。== --cy-ease-roll / --cy-motion-roll 以下一档(320)
  snappy: Object.freeze({ k: 420, c: 34, dur: 320, ease: 'cubic-bezier(.2,.9,.2,1)' }),
  // 轻微过冲:列表项、chip、头像堆叠。== --cy-ease-spring-soft
  soft: Object.freeze({ k: 300, c: 28, dur: 420, ease: 'cubic-bezier(.34,1.3,.64,1)' }),
  // 明显过冲:徽章落章、庆祝元素。== --cy-ease-spring
  firm: Object.freeze({ k: 380, c: 22, dur: 480, ease: 'cubic-bezier(.2,1.4,.5,1)' }),
  // 大元素落位,长尾收敛:结果卡、桂冠、大数字。== --cy-ease-settle / --cy-motion-reveal(450)
  settle: Object.freeze({ k: 260, c: 32, dur: 450, ease: 'cubic-bezier(.16,1,.3,1)' }),
});

const DEFAULT_PRESET = 'settle';

// 松手速度 → 时长缩放的夹取范围(MOTION.md §2.6:clamp(|v|/2, .6, 1.4))
const RELEASE_SCALE_MIN = 0.6;
const RELEASE_SCALE_MAX = 1.4;

// 积分的稳定步长:dt 超过这个值就拆成多步,否则刚度大时欧拉会发散
const MAX_SUB_STEP_MS = 16;
// 收敛判据(MOTION.md §2.6):|v| < 0.01 px/ms 且 |x - target| < 0.1 px
const SETTLE_V = 0.01;
const SETTLE_X = 0.1;

function preset(name) {
  return PRESETS[name] || PRESETS[DEFAULT_PRESET];
}

/** CSS 近似:`transition: transform ${duration}ms ${easing}`;未知预设回落到 settle */
function curve(name) {
  const p = preset(name);
  return { duration: p.dur, easing: p.ease };
}

/** 积分一帧(不改入参)。state = { x, v, target, k, c }:px / px·ms⁻¹,k、c 按秒标定;dt 为 ms,>16 自动拆子步。
 *  返回新 state;settled=true 时 x 钉在 target、v 归零。 */
function step(state, dt) {
  const s = state || {};
  const p = preset(s.preset);
  const k = s.k > 0 ? s.k : p.k;
  const c = s.c >= 0 ? s.c : p.c;
  const target = Number(s.target) || 0;
  let x = Number(s.x) || 0;
  let v = Number(s.v) || 0;
  let remaining = dt > 0 ? dt : MAX_SUB_STEP_MS;
  while (remaining > 0) {
    const h = remaining > MAX_SUB_STEP_MS ? MAX_SUB_STEP_MS : remaining;
    remaining -= h;
    const hs = h / 1000;               // k / c 按秒标定,v 用 px/ms ⇒ 先换成 px/s 再积分
    const vs = v * 1000;
    const a = -k * (x - target) - c * vs;
    const nvs = vs + a * hs;
    x += nvs * hs;
    v = nvs / 1000;
  }
  const settled = Math.abs(v) < SETTLE_V && Math.abs(x - target) < SETTLE_X;
  return {
    x: settled ? target : x,
    v: settled ? 0 : v,
    target,
    k,
    c,
    preset: s.preset,
    settled,
  };
}

/** 松手落位:时长按 |v|/2 夹在 [.6, 1.4] 倍缩放(快甩落得快);v 为 px/ms(gesture-physics.velocity) */
function releaseToTransition(x, v, target, name) {
  const p = preset(name);
  const speed = Math.abs(Number(v) || 0);
  let scale = speed / 2;
  if (!(scale > RELEASE_SCALE_MIN)) scale = RELEASE_SCALE_MIN;
  if (scale > RELEASE_SCALE_MAX) scale = RELEASE_SCALE_MAX;
  const distance = (Number(target) || 0) - (Number(x) || 0);
  // 已经在目标上:不需要过渡时长(调用方可以直接写终态)
  if (Math.abs(distance) < SETTLE_X) return { duration: 0, easing: p.ease, distance: 0 };
  return { duration: Math.round(p.dur / scale), easing: p.ease, distance };
}

module.exports = {
  PRESETS,
  DEFAULT_PRESET,
  RELEASE_SCALE_MIN,
  RELEASE_SCALE_MAX,
  SETTLE_V,
  SETTLE_X,
  curve,
  step,
  releaseToTransition,
};
