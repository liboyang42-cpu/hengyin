'use strict';
/* 页面动效套件(2026-10-23 业主:「其他页面也要有这些动画」,沿用 v13 已批准的动效语言)。
 *
 * 做法:页面 setData 之前先 enter(page, key):data[key] = 1(新内容先隐着渲染)→ 34ms 后 = 2(依次浮上来)→ 1.2s 后 = 0(静止)。
 * 模板只在容器上挂 style(--pm-x / --pm-x-d / --pm-x-s 三个变量),页面样式按既有类名用 translate 与 filter:opacity() 写,
 * 不碰 transform / opacity,按压反馈照常;静止时过渡时长为 0。
 * 于是新内容先隐着渲染,撤掉标记后依次浮上来;不改任何既有 class / setData 写法。
 * countTo:数字从 0 数到真实值(写到单独的显示字段,不碰业务字段)。
 * 减少动态效果时两者都不做动画。 */
const { readReducedMotion } = require('./motion-preference.js');
const { countUp, easeInOutCubic } = require('./motion.js');

function reduced(page) {
  return !!(page && page.data && page.data.reducedMotion) || readReducedMotion();
}

// 测试沙箱等没有计时器的环境里不做动效(内容照常显示)
const hasTimers = () => typeof setTimeout === 'function' && typeof clearTimeout === 'function';

// options.once:同一页面实例同一 key 只入场一次。刷新、点赞后重拉、切回 tab、翻页都不再把已在屏上的内容藏起来重播。
function enter(page, key, options) {
  const k = key || 'pmPre';
  if (!page || typeof page.setData !== 'function' || !hasTimers() || reduced(page)) return;
  if (options && options.once) {
    const seen = page._pmEntered || (page._pmEntered = {});
    if (seen[k]) return;
    seen[k] = true;
  }
  page.setData({ [k]: 1 });
  clearTimeout(page['_' + k + 'Timer']);
  const alive = () => !page._unloaded && !page._detached;
  page['_' + k + 'Timer'] = setTimeout(() => {
    if (!alive()) return;
    page.setData({ [k]: 2 });
    page['_' + k + 'Timer'] = setTimeout(() => { if (alive()) page.setData({ [k]: 0 }); }, 900);
  }, 34);
}

/* 数字从 0 数上去:page.data[key] 从 0 走到 1(先于真实数据落地置 0,数完置 null)。
 * 模板用 utils/wxs/count.wxs 的 f(真实值, key) 显示 —— 真实值不动,只是显示按比例缩放;拿不到数就原样显示。
 * 只在页面第一次拿到数据时调用,刷新不再重数。 */
function countIn(page, key, options) {
  if (!page || typeof page.setData !== 'function' || !hasTimers() || reduced(page)) return;
  const stops = page._pmCounters || (page._pmCounters = {});
  if (stops[key]) stops[key]();
  const alive = () => !page._unloaded && !page._detached;
  page.setData({ [key]: 0 });
  // 先停在 0 让卡片浮上来(约 150ms),再开始数 —— 否则数字大半在隐身时就数完了
  let stopCount = null;
  const hold = setTimeout(() => {
    stopCount = countUp(0, 1, (v) => { if (alive()) page.setData({ [key]: v }); },
      Object.assign({ duration: 600, decimals: 3, onDone: () => { if (alive()) page.setData({ [key]: null }); } }, options || {}));
  }, 150);
  stops[key] = () => { clearTimeout(hold); if (stopCount) stopCount(); };
}

/* 钱数落地:慢-快-慢,时长与商家首页累计收入一致。模板用 count.wxs 的 money(文字, key)。 */
function countMoney(page, key) {
  countIn(page, key, { duration: 1300, ease: easeInOutCubic });
}

/* 分段 / 循环动效用 transition 一段段重放(MOTION.md:不新增 @keyframes)。
 * frames:[[值, 停留毫秒], …],依次把 data[key] 设成各值;样式按值挂类,每段由该类的 transition 补间。
 * options.delay 首段前等待;options.times 播几遍(缺省一直循环,直到 stop() 或页面卸载);
 * options.rest:开播前与减少动态效果时停的值(缺省取第一帧)。返回 stop()。 */
// 循环计时器不该拖住测试进程(Node 有 unref;小程序计时器返回数字,无此方法)
function later(fn, ms) {
  const t = setTimeout(fn, ms);
  if (t && typeof t.unref === 'function') t.unref();
  return t;
}

function beats(page, key, frames, options) {
  const opts = options || {};
  const timer = '_' + key + 'Beat';
  const stop = () => { if (page) { clearTimeout(page[timer]); page[timer] = null; } };
  if (!page || typeof page.setData !== 'function' || !frames || !frames.length) return stop;
  stop();
  page.setData({ [key]: 'rest' in opts ? opts.rest : frames[0][0] });
  if (!hasTimers() || reduced(page)) return stop;
  const alive = () => !page._unloaded && !page._detached;
  let i = 0;
  let round = 0;
  const tick = () => {
    if (!alive()) return;
    if (i === frames.length) {
      i = 0;
      round += 1;
      if (opts.times && round >= opts.times) return;
    }
    const frame = frames[i++];
    page.setData({ [key]: frame[0] });
    page[timer] = later(tick, frame[1]);
  };
  page[timer] = later(tick, opts.delay || 0);
  return stop;
}

module.exports = { enter, countIn, countMoney, beats };
