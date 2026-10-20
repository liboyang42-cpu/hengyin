'use strict';
// 退避轮询:setTimeout 链代替 setInterval。
//  - 上一次 run 还没回来就不再叠发(定时器到点时顺延一个 interval)。
//  - run 返回 promise;reject 或 resolve(false) 记一次失败,间隔按 interval×2^n 退避并封顶 maxInterval
//    (默认 4s→8s→10s);成功一次即回到 interval。
//  - start()/stop() 幂等;stop 后在途的 run 回来也不会再排下一次。
// 平台 timer 使用正的有符号 32 位毫秒值；越界不能依赖平台截断为短延迟。
const MAX_TIMER_DELAY = 2147483647;
function normalizeDelay(value, fallback) {
  const numeric = (typeof value === 'number' || typeof value === 'string') ? Number(value) : NaN;
  const delay = Number.isFinite(numeric) && numeric > 0 ? numeric : fallback;
  return Math.min(MAX_TIMER_DELAY, Math.max(1, delay));
}

function createPoller(opts) {
  const run = opts.run;
  const interval = normalizeDelay(opts.interval, 4000);
  const maxInterval = Math.max(interval, normalizeDelay(opts.maxInterval, interval * 2.5));
  const setT = opts.setTimeout || setTimeout;
  const clearT = opts.clearTimeout || clearTimeout;
  let timer = null;
  let active = false;
  let inflight = false;
  let fails = 0;
  let gen = 0;

  const delay = () => Math.min(maxInterval, interval * Math.pow(2, fails));

  function arm() {
    if (!active || timer != null) return;
    timer = setT(tick, delay());
  }
  function tick() {
    timer = null;
    if (!active) return;
    if (inflight) { arm(); return; }
    inflight = true;
    const myGen = gen;
    let p;
    try { p = Promise.resolve(run()); } catch (e) { p = Promise.reject(e); }
    p.then((ok) => ok !== false, () => false).then((ok) => {
      inflight = false;
      if (myGen !== gen) return;   // 期间 stop 过(甚至又 start 过),这一发不再影响节奏
      fails = ok ? 0 : fails + 1;
      arm();
    });
  }

  return {
    start() { if (active) return; active = true; gen++; fails = 0; arm(); },
    stop() { if (!active) return; active = false; gen++; if (timer != null) { clearT(timer); timer = null; } },
    isActive: () => active,
  };
}

module.exports = { createPoller };
