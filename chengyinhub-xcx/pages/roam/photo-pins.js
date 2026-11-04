'use strict';
const DOT_SCALE = 14;
const PIN_SIZE = 40;
const PIN_SIZE_NEAR = 46;
const PIN_SIZE_FOCUS = 60;
const EDGE_SIZE = 26;
const DEMOTED_SIZE = 10;
const EDGE_INSET = 0.06;
const NEAR_SPAN = 0.6;
const MAX_PHOTO_PINS = 40;
const DOT_CELL_PX = 18;
const COUNT_MIN = 10;

function pinPhoto(p, placeholder) {
  const url = p && (p.photo || '');
  if (!url || (placeholder && url === placeholder)) return '';
  return String(url);
}

function isParticipating(p) {
  if (!p || p.demo) return false;
  if (p.cat === 'merchant' && (p.regId || p.nodeId)) return true;
  return !!(p.isCityNode && p.merchantId);
}

function regionBox(region) {
  const sw = region && region.southwest;
  const ne = region && region.northeast;
  if (!sw || !ne) return null;
  const s = Number(sw.latitude), w = Number(sw.longitude), n = Number(ne.latitude), e = Number(ne.longitude);
  if (![s, w, n, e].every(Number.isFinite) || n <= s || e <= w) return null;
  return { s, w, n, e, h: n - s, wd: e - w };
}

function placeInView(lat, lng, region) {
  const b = regionBox(region);
  if (!b) return { kind: 'in', lat, lng };
  if (lat >= b.s && lat <= b.n && lng >= b.w && lng <= b.e) return { kind: 'in', lat, lng };
  const padLat = b.h * NEAR_SPAN, padLng = b.wd * NEAR_SPAN;
  if (lat < b.s - padLat || lat > b.n + padLat || lng < b.w - padLng || lng > b.e + padLng) return { kind: 'far' };
  const insetLat = b.h * EDGE_INSET, insetLng = b.wd * EDGE_INSET;
  return {
    kind: 'edge',
    lat: Math.min(b.n - insetLat, Math.max(b.s + insetLat, lat)),
    lng: Math.min(b.e - insetLng, Math.max(b.w + insetLng, lng)),
  };
}

function densityMode(scale) {
  return Number(scale) > 0 && Number(scale) <= DOT_SCALE ? 'dots' : 'pins';
}

function bucketDots(points, region, viewport, cellPx) {
  const b = regionBox(region);
  const w = Number(viewport && viewport.width) || 390;
  const h = Number(viewport && viewport.height) || 844;
  const cell = cellPx || DOT_CELL_PX;
  const cells = new Map();
  (points || []).forEach((p) => {
    const lat = Number(p && p.lat), lng = Number(p && p.lng);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
    let gx = 0, gy = 0;
    if (b) {
      if (lat < b.s || lat > b.n || lng < b.w || lng > b.e) return;
      gx = Math.floor(((lng - b.w) / b.wd) * w / cell);
      gy = Math.floor(((b.n - lat) / b.h) * h / cell);
    } else {
      gx = Math.round(lng * 2000); gy = Math.round(lat * 2000);
    }
    const key = gx + ':' + gy;
    const c = cells.get(key) || { lat: 0, lng: 0, count: 0 };
    c.lat += lat; c.lng += lng; c.count += 1;
    cells.set(key, c);
  });
  return Array.from(cells.values()).map((c) => ({ lat: c.lat / c.count, lng: c.lng / c.count, count: c.count }));
}

// 经纬度 → 屏幕 px(视口内线性近似,够做图钉避让)
function toScreen(lat, lng, region, viewport) {
  const b = regionBox(region);
  if (!b) return null;
  const w = Number(viewport && viewport.width) || 390, h = Number(viewport && viewport.height) || 844;
  return { x: (lng - b.w) / b.wd * w, y: (b.n - lat) / b.h * h };
}

// 照片图钉避让:按离视野中心由近到远放,和已放下的照片重叠的,降成不挂照片的小深色圆
function declutter(items, region, viewport) {
  const placed = [];
  return items.filter((it) => {
    const s = toScreen(it.lat, it.lng, region, viewport);
    if (!s) return true;
    const r = it.size * 1.05;
    if (placed.some((q) => (q.x - s.x) * (q.x - s.x) + (q.y - s.y) * (q.y - s.y) < r * r)) return false;
    placed.push(s);
    return true;
  });
}

// 第九轮 m2 补充第 2 项:任意缩放下图钉互不重叠。
// 按相关度从高到低放:选中的 > 参与商铺 > 有照片 > 离视野中心近;和已放下的任何一枚(照片、形象、深色圆都算)
// 圆心距小于两者半径之和加间隙的,降成小圆点,拉近后自然会放出来。带店名贴纸的按更大的占位算。
const PIN_GAP = 6;
const LABEL_EXTRA = 14;
function footprint(c) {
  return (Number(c.size) || PIN_SIZE) / 2 + (c.label ? LABEL_EXTRA : 0);
}
function relevance(c) {
  return (c.focused ? 1e6 : 0) + (c.demo ? 5e5 : 0) + (c.participating ? 1e5 : 0) + (c.photo ? 1e4 : 0) - (Number(c.d) || 0);
}
/** 视野还没上报过(首帧)时,按中心与缩放估一个视野,避让照样能做 */
function fallbackRegion(center, scale, viewport) {
  const lat = Number(center && center.lat), lng = Number(center && center.lng), z = Number(scale);
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || !Number.isFinite(z)) return null;
  const w = Number(viewport && viewport.width) || 390, h = Number(viewport && viewport.height) || 844;
  const mPerPx = 156543.03392 * Math.cos(lat * Math.PI / 180) / Math.pow(2, z);
  const dLat = (h * mPerPx / 2) / 111320, dLng = (w * mPerPx / 2) / (111320 * Math.cos(lat * Math.PI / 180));
  return { southwest: { latitude: lat - dLat, longitude: lng - dLng }, northeast: { latitude: lat + dLat, longitude: lng + dLng } };
}
/** 返回放得下的候选(按相关度排好);没放下的由调用方降成小圆点 */
function layoutPins(cands, region, viewport, obstacles) {
  const sorted = (cands || []).slice().sort((a, b) => relevance(b) - relevance(a));
  if (!regionBox(region)) return sorted;
  const placed = [];
  // 固定占位(玩家自己的点):先放进去,任何图钉都不压它(rig 走查:店名贴纸压在蓝点上)
  (obstacles || []).forEach((o) => {
    const s0 = o && toScreen(o.lat, o.lng, region, viewport);
    if (s0) placed.push({ x: s0.x, y: s0.y, r: (Number(o.size) || 40) / 2 });
  });
  const kept = [];
  sorted.forEach((c) => {
    const s = toScreen(c.lat, c.lng, region, viewport);
    if (!s) { kept.push(c); return; }
    const r = footprint(c);
    const hit = placed.some((q) => {
      const min = q.r + r + PIN_GAP;
      return (q.x - s.x) * (q.x - s.x) + (q.y - s.y) * (q.y - s.y) < min * min;
    });
    if (hit) return;
    placed.push({ x: s.x, y: s.y, r });
    kept.push(c);
  });
  return kept;
}

/** 第九轮 m2 补充第 3 项:一格里叠了几个真实点 → 热度档与图标尺寸(比 m1 的 9/12px 略大,带柔光) */
function heatLevel(count) {
  const n = Number(count) || 0;
  if (n >= 8) return { level: 3, size: 34 };
  if (n >= 3) return { level: 2, size: 26 };
  return { level: 1, size: 18 };
}

function nearbyCountLabel(n) {
  const count = Number(n) || 0;
  return count >= COUNT_MIN ? '附近 ' + count + ' 个地点' : '';
}

function markerSignature(markers, pins) {
  const parts = [];
  (markers || []).forEach((m) => {
    parts.push(m.id + ',' + Number(m.latitude).toFixed(6) + ',' + Number(m.longitude).toFixed(6) + ','
      + m.width + ',' + (m.iconPath || '') + ',' + (m.rotate || 0) + ',' + (m.callout ? m.callout.content : '')
      + ',' + (m.customCallout ? m.customCallout.anchorY : ''));
  });
  (pins || []).forEach((p) => parts.push('p' + p.id + ',' + p.img + ',' + p.label + ',' + p.size + ',' + (p.edge ? 1 : 0) + ',' + (p.on ? 1 : 0)));
  return parts.join('|');
}

module.exports = {
  DOT_SCALE, PIN_SIZE, PIN_SIZE_NEAR, PIN_SIZE_FOCUS, EDGE_SIZE, DEMOTED_SIZE, MAX_PHOTO_PINS, COUNT_MIN,
  pinPhoto, isParticipating, placeInView, densityMode, bucketDots, nearbyCountLabel, markerSignature, regionBox, toScreen, declutter,
  layoutPins, fallbackRegion, footprint, PIN_GAP, heatLevel,
};
