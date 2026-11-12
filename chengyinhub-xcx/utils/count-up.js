'use strict';
/* 读数卡上的数字滚上去,不是「啪」地出现 —— 逐条照原型 countUp() 抄
 * (2026-09-09 用户裁决:所有页面与 HTML 一模一样)。
 *
 * 原型原话:「视频 A 的探索百分比就是一直在跳」。
 * 规则一条不改:
 *   · 只滚「可选的正号 + 数字 + 可选后缀」这种形状,别的原样返回
 *   · 00:00 这种带冒号的时间**不滚**(滚出来是乱码,不是动效)
 *   · 0 不滚(从 0 滚到 0 什么也看不见,白占一帧)
 *   · 减少动态效果时直接落定
 *   · 760ms,缓出曲线 1-(1-p)³,小数位跟着原值走
 *
 * 抽成纯函数是为了能单测:它只算「第 t 帧该显示什么」,不碰 setData,也不碰计时器。
 */

const DURATION = 760;

/** 拆成 [正号, 数值, 后缀];不该滚的返回 null。 */
function parse(raw) {
  const text = String(raw == null ? '' : raw).trim();
  const m = text.match(/^([+]?)(\d+(?:[.:]\d+)?)(.*)$/);
  if (!m) return null;
  if (m[2].indexOf(':') >= 0) return null;      // 00:00 是时间,不滚
  const end = parseFloat(m[2]);
  if (!isFinite(end) || end === 0) return null; // 0 不滚
  const dot = m[2].split('.')[1];
  return { sign: m[1], end, dec: dot ? dot.length : 0, suffix: m[3] };
}

/** 第 elapsed 毫秒该显示的字符串。elapsed >= 760 即终值。 */
function frame(raw, elapsed) {
  const p = parse(raw);
  if (!p) return String(raw == null ? '' : raw);
  const t = Math.max(0, Math.min(1, elapsed / DURATION));
  const eased = 1 - Math.pow(1 - t, 3);         // 原型 1-Math.pow(1-p,3)
  return p.sign + (p.end * eased).toFixed(p.dec) + p.suffix;
}

/** 这个值该不该滚。调用方据此决定要不要起计时器。 */
function shouldCount(raw) { return parse(raw) !== null; }

/* 细节打磨 G4(2026-11-12):把一组读数一起滚上去。
 * facts 形如 { dist: '120 米', walk: '2', hours: '09–17' };逐项走 frame(),不该滚的原样。
 * 结果写进 page.data[key](整对象重写,与 roam._rollStat 同口径);减少动态效果 / 没有计时器直接落终值。
 * 同一页同一 key 新一轮会掐掉上一轮的计时器。返回 init —— 调用方把它放进同一次 setData,首帧就是 0 而不是闪一下终值。 */
function init(facts, reduced) {
  const out = {};
  Object.keys(facts || {}).forEach((k) => { out[k] = reduced ? String(facts[k] == null ? '' : facts[k]) : frame(facts[k], 0); });
  return out;
}

function roll(page, key, facts, options) {
  if (!page || typeof page.setData !== 'function') return;
  const opts = options || {};
  const timers = page._cuRollTimers || (page._cuRollTimers = {});
  if (timers[key]) { clearInterval(timers[key]); timers[key] = null; }
  const ks = Object.keys(facts || {});
  const reduced = opts.reduced === true || (page.data && page.data.reducedMotion === true);
  const put = (o) => { if (!page._unloaded && !page._detached) page.setData({ [key]: o }); };
  if (reduced || typeof setInterval !== 'function' || !ks.some((k) => shouldCount(facts[k]))) { put(init(facts, true)); return; }
  const t0 = Date.now();
  const delay = opts.delay > 0 ? opts.delay : 0;
  timers[key] = setInterval(() => {
    const elapsed = Math.max(0, Date.now() - t0 - delay);
    const o = {};
    ks.forEach((k) => { o[k] = frame(facts[k], elapsed); });
    put(o);
    if (elapsed >= DURATION) { clearInterval(timers[key]); timers[key] = null; }
  }, 40);
}

function clear(page) {
  const timers = (page && page._cuRollTimers) || {};
  Object.keys(timers).forEach((k) => { if (timers[k]) clearInterval(timers[k]); });
  if (page) page._cuRollTimers = {};
}

module.exports = { DURATION, frame, shouldCount, parse, init, roll, clear };
