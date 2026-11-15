'use strict';

// 把真实 <map> 截成图,画进分享 canvas(canvas 不能直接截原生 map)。
// 截不到时带 mapSnapshot 标记；调用方可绘制真实足迹回退，不伪造底图。
const READY_MS = 4000;
const STEP_MS = 80;

function failure(message, cause) {
  const error = new Error(message);
  error.mapSnapshot = true;
  if (cause) error.cause = cause;
  return error;
}

function waitFor(check, timeoutMs, stepMs) {
  const limit = timeoutMs == null ? READY_MS : timeoutMs;
  const step = stepMs || STEP_MS;
  return new Promise((resolve) => {
    let waited = 0;
    const tick = () => {
      if (check()) { resolve(true); return; }
      if (waited >= limit) { resolve(false); return; }
      waited += step;
      setTimeout(tick, step);
    };
    tick();
  });
}

/** ctx = wx.createMapContext(...);isReady 返回地图已渲染(bindupdated 到过)。成功得到本地图片路径。 */
function takeMapSnapshot(ctx, isReady, timeoutMs) {
  if (!ctx || typeof ctx.takeSnapshot !== 'function') return Promise.reject(failure('map context unavailable'));
  return waitFor(isReady || (() => true), timeoutMs).then((ready) => {
    if (!ready) throw failure('map not rendered');
    return new Promise((resolve, reject) => {
      ctx.takeSnapshot({
        type: 'file',
        quality: 'high',
        success: (res) => {
          const path = res && res.tempImagePath;
          if (typeof path === 'string' && path.trim()) resolve(path);
          else reject(failure('empty map snapshot'));
        },
        fail: (e) => reject(failure('map snapshot failed', e)),
      });
    });
  });
}

function loadImage(canvas, path) {
  return new Promise((resolve, reject) => {
    const image = canvas.createImage();
    image.onload = () => resolve(image);
    image.onerror = (e) => reject(failure('map snapshot image load failed', e));
    image.src = path;
  });
}

/** 把 srcW×srcH 的图按 cover 铺进 (x,y,w,h):居中裁剪,不拉伸。 */
function coverRect(boxW, boxH, srcW, srcH) {
  if (!(boxW > 0 && boxH > 0 && srcW > 0 && srcH > 0)) return null;
  const k = Math.max(boxW / srcW, boxH / srcH);
  const sw = boxW / k;
  const sh = boxH / k;
  return { sx: (srcW - sw) / 2, sy: (srcH - sh) / 2, sw, sh };
}

function drawCover(g, image, x, y, w, h) {
  const r = coverRect(w, h, image.width, image.height);
  if (!r) throw failure('map snapshot has no size');
  g.drawImage(image, r.sx, r.sy, r.sw, r.sh, x, y, w, h);
}

/** 路线卡完整保留截图，宽高不同时留边，避免 cover 裁掉轨迹首尾。 */
function drawContain(g, image, x, y, w, h) {
  const scale = Math.min(w / image.width, h / image.height);
  if (!(scale > 0 && Number.isFinite(scale))) throw failure('map snapshot has no size');
  const width = image.width * scale, height = image.height * scale;
  g.drawImage(image, x + (w - width) / 2, y + (h - height) / 2, width, height);
}

/** 截图不可用时只画真实足迹；与回放卡共用投影，不补造街道。 */
function drawRoute(g, geo, x, y, w, h) {
  const { buildRouteGeometry } = require('./roam-route-geo.js');
  const track = geo && geo.polyline && geo.polyline[0] && geo.polyline[0].points || [];
  const route = buildRouteGeometry({ track: track.map(p => ({ lat: p.latitude, lng: p.longitude })) });
  if (!route.segs.length) return false;
  const scale = Math.min(w / 670, h / 520);
  const ox = x + (w - 670 * scale) / 2, oy = y + (h - 520 * scale) / 2;
  g.strokeStyle = geo.polyline[0].color; g.lineWidth = 4;
  g.beginPath();
  route.segs.forEach((s, i) => {
    const angle = s.deg * Math.PI / 180;
    if (!i) g.moveTo(ox + s.x * scale, oy + s.y * scale);
    g.lineTo(ox + (s.x + Math.cos(angle) * s.len) * scale, oy + (s.y + Math.sin(angle) * s.len) * scale);
  });
  g.stroke();
  return true;
}

module.exports = { takeMapSnapshot, loadImage, coverRect, drawCover, drawContain, drawRoute, waitFor, failure, READY_MS };
