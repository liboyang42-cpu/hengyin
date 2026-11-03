'use strict';
const DOT_SCALE = 14;
const PIN_SIZE = 40;
const PIN_SIZE_NEAR = 46;
const PIN_SIZE_FOCUS = 60;
const EDGE_SIZE = 26;
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
  DOT_SCALE, PIN_SIZE, PIN_SIZE_NEAR, PIN_SIZE_FOCUS, EDGE_SIZE, MAX_PHOTO_PINS, COUNT_MIN,
  pinPhoto, isParticipating, placeInView, densityMode, bucketDots, nearbyCountLabel, markerSignature, regionBox,
};
