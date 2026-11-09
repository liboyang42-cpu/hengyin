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

module.exports = { magnitude, createShakeDetector, SHAKE_MAG, SHAKE_GAP_MS };
