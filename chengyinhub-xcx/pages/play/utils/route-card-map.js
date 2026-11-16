// 路线卡底图:真实地图快照(include-points + polyline),样式随主题发布时的外观。纯函数,不碰 wx。
const mapStyle = require('../../../utils/map-style.js');

const LINE = Object.freeze({ color: '#F8F8F8F2', width: 6, borderColor: '#0B0B0C99', borderWidth: 2 }); /* ds-ok 原生地图 polyline 不解析 CSS 变量，沿用路线卡描边色 */

/** 主题外观 → 底图样式与叠加层配色(同 utils/map-style.js kindForLook) */
function styleFor(look, style) {
  const kind = mapStyle.kindForLook(look);
  const s = mapStyle.mapStyleFor(kind, style);
  return { kind, subkey: s.subkey, layerStyle: s.layerStyle, tone: mapStyle.mapToneFor(kind, style) };
}

/** 路线点 → include-points;不足两个有效点返回空(调用方按失败处理,不画假图) */
function includePoints(route) {
  const pts = (Array.isArray(route) ? route : []).map((p) => ({ latitude: Number(p && p.lat), longitude: Number(p && p.lng) }))
    .filter((p) => Number.isFinite(p.latitude) && Number.isFinite(p.longitude));
  return pts.length >= 2 ? pts : [];
}

function polyline(route) {
  const points = includePoints(route);
  return points.length ? [Object.assign({ points }, LINE)] : [];
}

/** 把 MapContext.takeSnapshot 包成 Promise;失败一律 reject,不返回占位图 */
function takeSnapshot(ctx) {
  return new Promise((resolve, reject) => {
    if (!ctx || typeof ctx.takeSnapshot !== 'function') { reject(new Error('snapshot-unavailable')); return; }
    try {
      ctx.takeSnapshot({
        type: 'file', format: 'jpg', quality: 'high',
        success: (r) => (r && r.tempImagePath ? resolve(r.tempImagePath) : reject(new Error('snapshot-empty'))),
        fail: (e) => reject(new Error((e && e.errMsg) || 'snapshot-fail')),
      });
    } catch (e) { reject(e); }
  });
}

module.exports = { styleFor, includePoints, polyline, takeSnapshot };
