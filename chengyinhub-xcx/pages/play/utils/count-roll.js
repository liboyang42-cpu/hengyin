'use strict';
/* 读数一起滚上去(细节打磨 G4):facts 形如 { dist, walk, hours },逐项走 count-up.frame。仅玩家页用,放分包。 */
const { DURATION, frame, shouldCount } = require('../../../utils/count-up.js');

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

module.exports = { init, roll, clear };
