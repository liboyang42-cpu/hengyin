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

// 测试沙箱等没有计时器的环境里不做动效(内容照常显示)
const hasTimers = () => typeof setTimeout === 'function' && typeof clearTimeout === 'function';

function enter(page, key) {
  const k = key || 'pmPre';
  if (!page || typeof page.setData !== 'function' || !hasTimers() || reduced(page)) return;
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

module.exports = { enter, countIn };
