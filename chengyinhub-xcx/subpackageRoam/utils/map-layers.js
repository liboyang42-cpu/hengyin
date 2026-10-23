'use strict';
/* 漫游地图三类点位 + 筛选 + 边缘缩小(2026-10-22 业主口述,docs/design/动效界面落地_20261022 D 段)。
 *
 * 三类:主题队伍(/api/team/nearby,头像点)· 地点玩法(/api/roam/hangout/nearby 的主题/活动照片点 +
 * /api/roam/pois type=1 地标)· 商家(/api/roam/pois type=2)。三类外观各不相同。
 * 筛选下拉的选项只从真实数据里来(POI 的 tags、本屏有没有主题/活动),不预置「戏剧节」之类的假分类。
 * 边缘:离中心超过可视半径 65% 的点缩小、去名牌;超过 120% 的点不画。「我」的点永远保留。
 * 纯函数,便于单测。 */

const POI_TAG = { 1: 6, 2: 7 };          // marker id = poiId*10+6(地标)/ +7(商家);1/2/3 主题活动、4 队伍、9 我
const SELF_MARKER_ID = 9;

function num(v) {
  if (v == null || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function text(v) {
  if (v == null) return '';
  const s = String(v).trim();
  return s === 'null' ? '' : s;
}

function splitTags(v) {
  return text(v).split(/[,，、;；|\s]+/).map((s) => s.trim()).filter(Boolean);
}

function poiKind(p) { return Number(p && p.type) === 2 ? 'shop' : 'place'; }

/** 两点直线距离(米) */
function distanceM(a, b) {
  const R = 6371000, rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad, dLng = (b.lng - a.lng) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** 筛选下拉的选项:地点 = 本屏有的主题/活动 + 地标 tags;商家 = 商家 tags。都带「全部」打头。 */
function filterOptions(pois, items) {
  const place = [], shop = [];
  const add = (arr, t) => { if (t && arr.indexOf(t) < 0) arr.push(t); };
  (items || []).forEach((it) => { if (it && it.kind === 'topic') add(place, '主题'); else if (it && it.kind === 'activity') add(place, '活动'); });
  (pois || []).forEach((p) => splitTags(p && p.tags).forEach((t) => add(poiKind(p) === 'shop' ? shop : place, t)));
  return { place: ['全部地点'].concat(place.slice(0, 12)), shop: ['全部商家'].concat(shop.slice(0, 12)) };
}

/** 当前筛选下某个点位留不留。layer: all | team | place | shop;sub: 下拉选中的那一项('' = 全部) */
function keepTeam(filter) { return !filter || filter.layer === 'all' || filter.layer === 'team'; }
function keepItem(it, filter) {
  if (!filter || filter.layer === 'all') return true;
  if (filter.layer !== 'place') return false;
  if (!filter.sub) return true;
  return (filter.sub === '主题' && it.kind === 'topic') || (filter.sub === '活动' && it.kind === 'activity');
}
function keepPoi(p, filter) {
  if (!filter || filter.layer === 'all') return true;
  if (filter.layer !== poiKind(p)) return false;
  return !filter.sub || splitTags(p.tags).indexOf(filter.sub) >= 0;
}

/** 地标 / 商家点:地标绿点、商家琥珀点,近处(≤300m)放大并挂名牌。icon 由页面按颜色生成的远距点提供。 */
function buildPoiMarkers(pois, center, dotIcons, colors, fallbackIcon) {
  const out = [];
  (pois || []).forEach((p) => {
    const lat = num(p && p.lat), lng = num(p && p.lng), id = num(p && p.id);
    if (lat == null || lng == null || id == null || id <= 0) return;
    const kind = poiKind(p);
    const color = colors[kind];
    const near = center ? distanceM(center, { lat, lng }) <= 300 : false;
    const size = near ? 22 : 16;
    const m = {
      id: id * 10 + POI_TAG[kind === 'shop' ? 2 : 1],
      latitude: lat, longitude: lng, width: size, height: size, anchor: { x: 0.5, y: 0.5 },
      zIndex: kind === 'shop' ? 7 : 6,
      iconPath: (dotIcons && dotIcons[color]) || fallbackIcon,
    };
    if (near && text(p.name)) m.callout = { content: text(p.name), fontSize: 11, borderRadius: 10, padding: 6, display: 'ALWAYS', color: colors.plateText, bgColor: color };
    out.push(m);
  });
  return out;
}

function parsePoiMarkerId(markerId) {
  const n = Number(markerId);
  if (!Number.isFinite(n) || n <= 0) return null;
  const tag = n % 10;
  if (tag !== 6 && tag !== 7) return null;
  return { kind: tag === 7 ? 'shop' : 'place', id: Math.floor(n / 10) };
}

/** 边缘缩小、远处隐藏:按离中心的距离与当前范围半径之比处理;「我」不动 */
function edgeLod(markers, center, radiusM) {
  if (!center || !(radiusM > 0)) return markers || [];
  const out = [];
  (markers || []).forEach((m) => {
    if (!m || m.id === SELF_MARKER_ID) { if (m) out.push(m); return; }
    const ratio = distanceM(center, { lat: m.latitude, lng: m.longitude }) / radiusM;
    if (ratio > 1.2) return;
    if (ratio > 0.65) {
      const k = 0.62;
      const shrunk = Object.assign({}, m, { width: Math.max(10, Math.round(m.width * k)), height: Math.max(10, Math.round(m.height * k)) });
      delete shrunk.callout;
      out.push(shrunk);
      return;
    }
    out.push(m);
  });
  return out;
}

/** 点位卡内容:地标 / 商家。只放后端给的字段。 */
function poiCard(p) {
  const kind = poiKind(p);
  return {
    kind,
    id: num(p.id),
    tag: kind === 'shop' ? '商家' : '地点',
    title: text(p.name) || (kind === 'shop' ? text(p.merchantName) : '') || '未命名地点',
    img: text(p.coverImg),
    desc: text(p.description),
    address: text(p.address),
    tags: splitTags(p.tags).slice(0, 4),
    found: p.found === true,
    lat: num(p.lat), lng: num(p.lng),
  };
}

module.exports = { POI_TAG, distanceM, splitTags, poiKind, filterOptions, keepTeam, keepItem, keepPoi, buildPoiMarkers, parsePoiMarkerId, edgeLod, poiCard };
