/** Shared coin/dice shake detector; wx acceleration is supplied in g. */
const SHAKE_MAG = 26 / 9.80665; // Convert the web prototype's m/s^2 threshold to g.
const SHAKE_GAP_MS = 900;  // 两次之间至少隔这么久,否则一次晃动会被读成好几次

/** 三轴读数 → 强度。用绝对值之和不用平方和开根:同一套判据要和原型完全一致。 */
function magnitude(acc) {
  if (!acc) return 0;
  return Math.abs(acc.x || 0) + Math.abs(acc.y || 0) + Math.abs(acc.z || 0);
}

/** Feed wx accelerometer readings in g; preserve the original 900ms cooldown. */
function createShakeDetector(onShake, opts) {
  const o = opts || {};
  const mag = o.magnitude || SHAKE_MAG;
  const gap = o.gapMs || SHAKE_GAP_MS;
  const now = o.now || (() => Date.now());
  /* -Infinity 而不是 0:第一次摇必须永远算数。写 0 的话,当 now() 返回的是
     一个小数(单测里注入的假时钟、或某些环境的 performance.now)时,首摇会被
     防抖当成「刚摇过」直接吞掉 —— 生产里 Date.now() 很大所以碰不到,
     但那是运气不是设计。 */
  let last = -Infinity;
  return function feed(acc) {
    if (magnitude(acc) <= mag) return false;
    const t = now();
    if (t - last <= gap) return false;
    last = t;
    if (typeof onShake === 'function') onShake();
    return true;
  };
}

/* f-play V3-04:加速度计是整个小程序一份的。之前每个玩法各自 start / stop:
   故事流里的内嵌硬币 / 骰子,在玩法开始弹窗选「全屏」后会同时挂着两份(流里一份、整屏一份),
   摇一下两份一起抛 / 掷;整屏关掉时它的 stopAccelerometer 又把流里那份的传感器一并关死,回到流里再摇没反应。
   这里按 wx 实例共用一个监听:第一个持有者开传感器、最后一个放手才关;读数只给最上面(最后持有)的那一个 ——
   整屏盖在流上时只有整屏在摇,收回后流里那份接着听。 */
const hubs = typeof WeakMap === 'function' ? new WeakMap() : null;
function hubOf(api) {
  if (!hubs || !api) return null;
  let hub = hubs.get(api);
  if (!hub) { hub = { stack: [], tick: null }; hubs.set(api, hub); }
  return hub;
}
function sensorReady(api) {
  return !!api && ['startAccelerometer', 'stopAccelerometer', 'onAccelerometerChange', 'offAccelerometerChange']
    .every((k) => typeof api[k] === 'function');
}
/** 持有传感器。返回放手函数(幂等);拿不到传感器返回 null(按钮是退路)。 */
function holdAccelerometer(api, listener) {
  const hub = hubOf(api);
  if (!hub || !sensorReady(api) || typeof listener !== 'function') return null;
  const entry = { listener };
  hub.stack.push(entry);
  if (!hub.tick) {
    // 每一段「开着」用一个新的监听函数:放手后旧函数即使被排队回调也不再送读数
    const tick = (res) => {
      if (hub.tick !== tick) return;
      const top = hub.stack[hub.stack.length - 1];
      if (top) top.listener(res);
    };
    hub.tick = tick;
    try { api.onAccelerometerChange(tick); api.startAccelerometer({ interval: 'game', fail() {} }); } catch (e) { /* 按钮仍可用 */ }
  }
  return function release() {
    const i = hub.stack.indexOf(entry);
    if (i < 0) return;
    hub.stack.splice(i, 1);
    if (hub.stack.length || !hub.tick) return;
    const tick = hub.tick;
    hub.tick = null;
    try { api.offAccelerometerChange(tick); } catch (e) { /* inert */ }
    try { api.stopAccelerometer({ fail() {} }); } catch (e) { /* best effort */ }
  };
}

module.exports = { magnitude, createShakeDetector, holdAccelerometer, SHAKE_MAG, SHAKE_GAP_MS };
