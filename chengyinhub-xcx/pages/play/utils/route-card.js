// 第九轮 m2 第 3 项:通关后的路线分享卡(原型 m-card2)。
// 路线在深色底上自己画一遍,停在这张卡:大数字(公里)、主题名、用时、站数、城市、主题小程序码。
// 路线只画在上半部的框里,永远压不到下面的大数字;首尾各 150 米不画,别把家门口露出来。
// 纯绘制:canvas 2D 上下文由调用方给,本文件不碰 wx,方便单测。

const W = 750;
const H = 1000;
const ROUTE_BOX = Object.freeze({ x: 72, y: 80, w: W - 144, h: 500 });
const NUMBER_BOX = Object.freeze({ x: 56, y: 620, w: W - 300, h: 300 });
const CODE = Object.freeze({ cx: W - 136, cy: 792, r: 92 });
const FUZZ_M = 150;
const C = Object.freeze({
  page: '#0B0B0C', grid: 'rgba(255,255,255,.06)', gridBold: 'rgba(255,255,255,.1)',
  route: '#F8F8F8', glow: 'rgba(248,248,248,.2)', text: '#F8F8F8', muted: 'rgba(248,248,248,.62)', ring: '#FFFFFF',
});

function distM(a, b) {
  const rad = Math.PI / 180;
  const x = (b.lng - a.lng) * rad * Math.cos(((a.lat + b.lat) / 2) * rad);
  const y = (b.lat - a.lat) * rad;
  return Math.sqrt(x * x + y * y) * 6371000;
}

function clean(points) {
  return (Array.isArray(points) ? points : []).map((p) => ({ lat: Number(p && p.lat), lng: Number(p && p.lng) }))
    .filter((p) => Number.isFinite(p.lat) && Number.isFinite(p.lng) && (p.lat !== 0 || p.lng !== 0));
}

function lengthM(points) {
  let m = 0;
  for (let i = 1; i < points.length; i++) m += distM(points[i - 1], points[i]);
  return m;
}

/** 从一端沿路线走 meters 米,把那一段剪掉(切点按比例插值) */
function cutHead(points, meters) {
  let left = meters;
  for (let i = 1; i < points.length; i++) {
    const d = distM(points[i - 1], points[i]);
    if (d >= left) {
      const t = d > 0 ? left / d : 0;
      const a = points[i - 1], b = points[i];
      return [{ lat: a.lat + (b.lat - a.lat) * t, lng: a.lng + (b.lng - a.lng) * t }].concat(points.slice(i));
    }
    left -= d;
  }
  return points.slice(-1);
}

/** 首尾各剪掉 150 米;整条不到 600 米时各剪四分之一,总还留一段能画 */
function fuzzEnds(points, meters) {
  const pts = clean(points);
  if (pts.length < 2) return pts;
  const total = lengthM(pts);
  const cut = Math.min(meters == null ? FUZZ_M : meters, total / 4);
  const head = cutHead(pts, cut);
  return cutHead(head.slice().reverse(), cut).reverse();
}

/** 等比投影进框里并居中(经度按纬度缩) */
function fitInto(points, box) {
  const pts = clean(points);
  if (!pts.length) return [];
  const k = Math.cos((pts[0].lat * Math.PI) / 180);
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  pts.forEach((p) => {
    const x = p.lng * k, y = -p.lat;
    minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
  });
  const spanX = Math.max(maxX - minX, 1e-9), spanY = Math.max(maxY - minY, 1e-9);
  const s = Math.min(box.w / spanX, box.h / spanY);
  const ox = box.x + (box.w - spanX * s) / 2, oy = box.y + (box.h - spanY * s) / 2;
  return pts.map((p) => ({ x: ox + (p.lng * k - minX) * s, y: oy + (-p.lat - minY) * s }));
}

function kmText(meters) {
  const km = Math.max(0, Number(meters) || 0) / 1000;
  return km >= 10 ? km.toFixed(0) : km.toFixed(1);
}

/** 卡片要的字段。distanceM 优先用真实走过的轨迹,没有就用调用方给的站间距离 */
function cardModel(input) {
  const i = input || {};
  const raw = clean(i.track);
  const route = raw.length >= 2 ? raw : clean(i.stops);
  const meters = raw.length >= 2 ? lengthM(raw) : (Number(i.fallbackKm) || 0) * 1000;
  return {
    route: fuzzEnds(route, FUZZ_M),
    big: kmText(meters),
    name: String(i.name || '').slice(0, 14),
    time: String(i.time || ''),
    stops: Number(i.doneCount) > 0 ? Number(i.doneCount) + ' 站' : '',
    city: String(i.city || '').replace(/市$/, '').slice(0, 8),
  };
}

function drawBase(g) {
  g.fillStyle = C.page; g.fillRect(0, 0, W, H);
  // 底纹:一组缓弯的「街道」,与城市无关,任何城市都一样
  g.lineCap = 'round';
  for (let i = 0; i < 8; i++) {
    g.strokeStyle = i % 2 ? C.grid : C.gridBold; g.lineWidth = i % 2 ? 2 : 6;
    const x = i * 108 - 30;
    g.beginPath(); g.moveTo(x, -20); g.bezierCurveTo(x + 40, 300, x - 40, 640, x + 18, H + 20); g.stroke();
  }
  for (let j = 0; j < 9; j++) {
    g.strokeStyle = j % 2 ? C.grid : C.gridBold; g.lineWidth = j % 2 ? 2 : 6;
    const y = j * 124 + 20;
    g.beginPath(); g.moveTo(-20, y); g.bezierCurveTo(240, y + 40, 500, y - 30, W + 20, y + 24); g.stroke();
  }
}

function strokePath(g, pts, upto) {
  if (pts.length < 2) return;
  const n = Math.max(2, Math.min(pts.length, upto));
  g.beginPath(); g.moveTo(pts[0].x, pts[0].y);
  for (let i = 1; i < n; i++) g.lineTo(pts[i].x, pts[i].y);
  g.stroke();
}

/** progress 0..1:路线画到哪儿;1 时文字与码一起出现 */
function drawCard(g, model, progress, codeImage) {
  const p = Math.max(0, Math.min(1, Number(progress) || 0));
  drawBase(g);
  const pts = fitInto(model.route, ROUTE_BOX);
  const upto = Math.round(2 + (pts.length - 2) * p);
  g.lineJoin = 'round'; g.lineCap = 'round';
  g.strokeStyle = C.glow; g.lineWidth = 26; strokePath(g, pts, upto);
  g.strokeStyle = C.route; g.lineWidth = 8; strokePath(g, pts, upto);
  if (p < 1) return;
  g.textAlign = 'left'; g.textBaseline = 'alphabetic';
  g.fillStyle = C.text; g.font = 'italic 800 176px -apple-system, "Helvetica Neue", sans-serif';
  g.fillText(model.big, NUMBER_BOX.x, NUMBER_BOX.y + 156);
  const bigW = g.measureText ? (g.measureText(model.big).width || 0) : 0;
  g.font = '600 34px -apple-system, sans-serif';
  g.fillText('公里', NUMBER_BOX.x + bigW + 14, NUMBER_BOX.y + 150);
  if (model.name) { g.font = '700 42px -apple-system, sans-serif'; g.fillText(model.name, NUMBER_BOX.x, NUMBER_BOX.y + 226); }
  const row = [model.time, model.stops, model.city].filter(Boolean);
  if (row.length) {
    g.fillStyle = C.muted; g.font = '500 28px -apple-system, sans-serif';
    let x = NUMBER_BOX.x;
    row.forEach((t) => { g.fillText(t, x, NUMBER_BOX.y + 284); x += (g.measureText ? g.measureText(t).width : t.length * 28) + 30; });
  }
  if (codeImage) {
    g.fillStyle = C.ring; g.beginPath(); g.arc(CODE.cx, CODE.cy, CODE.r, 0, Math.PI * 2); g.fill();
    g.save(); g.beginPath(); g.arc(CODE.cx, CODE.cy, CODE.r - 10, 0, Math.PI * 2); g.clip();
    const s = (CODE.r - 10) * 2;
    g.drawImage(codeImage, CODE.cx - s / 2, CODE.cy - s / 2, s, s);
    g.restore();
  }
}

module.exports = { W, H, ROUTE_BOX, NUMBER_BOX, CODE, FUZZ_M, fuzzEnds, fitInto, lengthM, cardModel, drawCard, kmText, distM };
