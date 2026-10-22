'use strict';
// 创建玩法引导页 · 漂浮圆圈(owner 2026-10-22 定稿原型 KGxHWjCsqbYUKt5m8SYJM2 v10)
//
// 运动规则(逐帧追踪参考视频后与 owner 逐条确认):
//   · 一串等距圆圈走一条标准半圆:圆心在屏幕左缘、与左侧文案同高;从左下进屏,沿右侧往上走,从左上出屏。
//   · 永不停:慢速匀速漂移;约每 2.65s 快转一次 —— 一下子加速、0.6s 内收住,正好多走一格。
//   · 大小只在快转时变,慢速漂移时不变;每次快转把大小带到下一档(变小 → 变大 → 再变大 → 不变 → 一下变小),
//     并且两颗两颗地变,沿行进方向依次传过去(最下面一对先变)。
//   · 屏幕左右边缘是一圈玻璃:滑进边缘的部分被往外拉伸,同时映出旁边圆圈的镜像。
// 本文件只放纯计算(单测直接测),画布绘制在 index.js。尺寸按 390 宽设计稿给,运行时按屏宽缩放。

const N = 8;                 // 圆圈数
const SLOT = 142;            // 相邻两颗沿弧线的间距(设计稿 px)
const RADIUS = 312;          // 半圆半径
const SPAN = N * SLOT / RADIUS;
const A0 = -SPAN / 2;
const A1 = SPAN / 2;
const DRIFT = 38 / SLOT;     // 慢速漂移:格/秒
const TAU = 0.2;             // 快转衰减时间常数
const EVERY = 2.65;          // 快转间隔
const JUMP = 1 / TAU;        // 以 TAU 衰减时正好多走 1 格
const RMAX = 48;             // 最大半径
const LEVELS = [1, 0.7, 0.85, 1, 1, 0.6];
const PAIR_STAGGER = 0.17;   // 每一对比前一对晚多少(按快转行程计)
const PAIR_SPAN = 0.49;      // 每一对自己变完要多少快转行程
const RIM = 46;              // 玻璃边宽度

function createOrbit() {
  return { P: 0, vel: DRIFT, kick: 0, kick0: 1, kickDist: 1, sinceKick: 1.4, lvl: 0, scFrom: 1, scTo: 1, pairs: null };
}

/** 第 i 颗当前所在的格(0 = 左下入口,N = 左上出口)。 */
function slotOf(o, i) { return (((o.P + i) % N) + N) % N; }

/** 发起一次快转(自动或松手甩出);k = 额外速度(格/秒)。 */
function startSpin(o, k) {
  o.pairs = [];
  for (let i = 0; i < N; i += 1) o.pairs.push(Math.floor(slotOf(o, i) / 2));
  o.kick = k; o.kick0 = k; o.kickDist = 0;
  o.scFrom = scaleNow(o); o.lvl = (o.lvl + 1) % LEVELS.length; o.scTo = LEVELS[o.lvl];
}

function scaleNow(o) { return o.scFrom + (o.scTo - o.scFrom) * o.kickDist; }

/** 推进 dt 秒;dragging 时速度由手指决定(调用方写 o.vel)。 */
function step(o, dt, dragging) {
  o.sinceKick += dt;
  if (!dragging && o.sinceKick > EVERY) { o.sinceKick = 0; startSpin(o, JUMP); }
  o.kick *= Math.exp(-dt / TAU);
  if (!dragging) o.vel = DRIFT + o.kick;
  o.P += o.vel * dt;
  // 大小只跟着快转自己的行程走:慢速漂移那部分不推进
  o.kickDist = Math.min(1, o.kickDist + (o.kick * dt) / (o.kick0 * TAU));
}

/** 第 i 颗的位置与半径(设计稿坐标,cx/cy = 半圆圆心)。 */
function place(o, i, cx, cy) {
  const u = slotOf(o, i);
  const a = A1 - (A1 - A0) * (u / N);   // 从下往上
  const pair = o.pairs ? o.pairs[i] : 0;
  const lp = Math.max(0, Math.min(1, (o.kickDist - pair * PAIR_STAGGER) / PAIR_SPAN));
  return { u, x: cx + RADIUS * Math.cos(a), y: cy + RADIUS * Math.sin(a), r: RMAX * (o.scFrom + (o.scTo - o.scFrom) * lp) };
}

/** 半圆圆心:x 让最右一颗刚好贴进右侧玻璃边,y 与文案中线对齐。 */
function center(width, copyMidY) { return { cx: width + 4 - RMAX - RADIUS, cy: copyMidY }; }

module.exports = {
  N, SLOT, RADIUS, DRIFT, TAU, EVERY, JUMP, RMAX, LEVELS, RIM,
  createOrbit, startSpin, step, place, slotOf, center, scaleNow,
};
