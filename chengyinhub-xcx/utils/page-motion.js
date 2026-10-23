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
const { countUp } = require('./motion.js');

function reduced(page) {
  return !!(page && page.data && page.data.reducedMotion) || readReducedMotion();
}

function enter(page, key) {
  const k = key || 'pmPre';
  if (!page || typeof page.setData !== 'function' || reduced(page)) return;
  page.setData({ [k]: 1 });
  clearTimeout(page['_' + k + 'Timer']);
  const alive = () => !page._unloaded && !page._detached;
  page['_' + k + 'Timer'] = setTimeout(() => {
    if (!alive()) return;
    page.setData({ [k]: 2 });
    page['_' + k + 'Timer'] = setTimeout(() => { if (alive()) page.setData({ [k]: 0 }); }, 1200);
  }, 34);
}

/* value 是数字或可解析成数字的字符串;小数位数跟随原值。拿不到数(null / 非数)就不数 */
function countTo(page, key, value, options) {
  if (!page || typeof page.setData !== 'function') return;
  const n = Number(value);
  if (value === null || value === undefined || value === '' || !Number.isFinite(n) || reduced(page) || n === 0) { page.setData({ [key]: null }); return; }
  const s = String(value);
  const decimals = s.indexOf('.') >= 0 ? s.length - s.indexOf('.') - 1 : 0;
  const stops = page._pmCounters || (page._pmCounters = {});
  if (stops[key]) stops[key]();
  stops[key] = countUp(0, n, (v) => {
    if (page._unloaded || page._detached) return;
    page.setData({ [key]: Number(v).toFixed(decimals) });
  }, Object.assign({ duration: 900, decimals, onDone: () => { if (!page._unloaded && !page._detached) page.setData({ [key]: null }); } }, options || {}));
}

module.exports = { enter, countTo };
