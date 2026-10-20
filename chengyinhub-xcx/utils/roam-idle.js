// 漫游静止提示(BL-02):位移 < 3m 持续 60s 视为「原地停留中」。
// 只产出一个展示用布尔值,不参与计时/距离/点亮规则;纯函数式状态机,无 wx / 无 this。
const { distM } = require('./roam-geo.js');

const IDLE_RADIUS_M = 3;
const IDLE_HOLD_MS = 60 * 1000;

function createIdleDetector(opts) {
  const radius = (opts && opts.radiusM) || IDLE_RADIUS_M;
  const hold = (opts && opts.holdMs) || IDLE_HOLD_MS;
  let anchor = null;
  let since = 0;
  return {
    // point: { lat, lng };返回当前是否处于静止态。非法点/时间不改变状态。
    update(point, now) {
      if (!point || !Number.isFinite(point.lat) || !Number.isFinite(point.lng) || !Number.isFinite(now)) {
        return !!anchor && now - since >= hold;
      }
      if (!anchor || distM(anchor, point) >= radius || now < since) {
        anchor = { lat: point.lat, lng: point.lng };
        since = now;
        return false;
      }
      return now - since >= hold;
    },
    reset() { anchor = null; since = 0; },
  };
}

module.exports = { createIdleDetector, IDLE_RADIUS_M, IDLE_HOLD_MS };
