// 2026-10-28 业主:勋章改成珐琅徽章(参考 x.com/danywander/status/2097314310783390104)。
// 每枚 = 一块有形状的底板 + glyphs.js 的徽记;所有色块交界压一道凸起的金线,外缘一圈厚金边。
// 未解锁只留这些线(细线稿),不上色。
// 纯像素运算,不依赖平台:小程序 2D canvas 与浏览器原型共用这一份(getImageData / putImageData)。

var GLYPHS = require('./glyphs.js');

/* 每枚徽记的底板形状与底色。形状各不相同,一眼能分开;底色取徽记自己那套配色里最深的一档。 */
var PLATE = {
  arrival:   { shape: 'circle',  base: '#23408F' },
  route:     { shape: 'scallop', base: '#0E6B43' },
  proposal:  { shape: 'roundsq', base: '#F2EEE3' },
  founding:  { shape: 'octagon', base: '#3A2414' },
  joined:    { shape: 'hex',     base: '#2D4E86' },
  gathering: { shape: 'flower',  base: '#7A1426' },
  followed:  { shape: 'circle',  base: '#0B4A47' },
  companion: { shape: 'hexup',   base: '#1F3A8A' },
  alliance:  { shape: 'diamond', base: '#3A4152' },
  citylit:   { shape: 'octagon', base: '#14284A' },
  roam:      { shape: 'scallop', base: '#27324F' },
  DEFAULT:   { shape: 'circle',  base: '#23408F' }
};

var GOLD_HI = [255, 244, 200], GOLD_MID = [232, 184, 84], GOLD_LO = [128, 86, 24];

function poly(x, n, r, rot) {
  for (var i = 0; i < n; i++) {
    var a = rot + i * Math.PI * 2 / n;
    if (i) x.lineTo(Math.cos(a) * r, Math.sin(a) * r); else x.moveTo(Math.cos(a) * r, Math.sin(a) * r);
  }
  x.closePath();
}

/* 底板轮廓,±88 归一坐标 */
function platePath(x, shape) {
  x.beginPath();
  if (shape === 'hex') poly(x, 6, 88, 0);
  else if (shape === 'hexup') poly(x, 6, 88, -Math.PI / 2);
  else if (shape === 'octagon') poly(x, 8, 88, Math.PI / 8);
  else if (shape === 'diamond') poly(x, 4, 90, -Math.PI / 2);
  else if (shape === 'roundsq') { var r = 22, s = 76; x.moveTo(-s + r, -s); x.arcTo(s, -s, s, s, r); x.arcTo(s, s, -s, s, r); x.arcTo(-s, s, -s, -s, r); x.arcTo(-s, -s, s, -s, r); x.closePath(); }
  else if (shape === 'scallop' || shape === 'flower') {
    var n = shape === 'scallop' ? 14 : 8, amp = shape === 'scallop' ? 5 : 11;
    for (var i = 0; i <= 120; i++) {
      var a = i / 120 * Math.PI * 2, rr = 88 - amp + amp * Math.cos(a * n);
      if (i) x.lineTo(Math.cos(a) * rr, Math.sin(a) * rr); else x.moveTo(Math.cos(a) * rr, Math.sin(a) * rr);
    }
    x.closePath();
  } else x.arc(0, 0, 88, 0, Math.PI * 2);
}

/* 两遍 3-4 倒角距离变换:seed 处为 0,其余为到最近 seed 的近似像素距离 */
function distance(seed, w, h) {
  var d = new Float32Array(w * h), BIG = 1e9, x, y, i, v;
  for (i = 0; i < w * h; i++) d[i] = seed[i] ? 0 : BIG;
  for (y = 0; y < h; y++) for (x = 0; x < w; x++) {
    i = y * w + x; v = d[i];
    if (x > 0 && d[i - 1] + 1 < v) v = d[i - 1] + 1;
    if (y > 0) {
      if (d[i - w] + 1 < v) v = d[i - w] + 1;
      if (x > 0 && d[i - w - 1] + 1.4142 < v) v = d[i - w - 1] + 1.4142;
      if (x < w - 1 && d[i - w + 1] + 1.4142 < v) v = d[i - w + 1] + 1.4142;
    }
    d[i] = v;
  }
  for (y = h - 1; y >= 0; y--) for (x = w - 1; x >= 0; x--) {
    i = y * w + x; v = d[i];
    if (x < w - 1 && d[i + 1] + 1 < v) v = d[i + 1] + 1;
    if (y < h - 1) {
      if (d[i + w] + 1 < v) v = d[i + w] + 1;
      if (x < w - 1 && d[i + w + 1] + 1.4142 < v) v = d[i + w + 1] + 1.4142;
      if (x > 0 && d[i + w - 1] + 1.4142 < v) v = d[i + w - 1] + 1.4142;
    }
    d[i] = v;
  }
  return d;
}

function ridge(dist, width) { if (dist >= width) return 0; var t = dist / width; return Math.sqrt(1 - t * t); }

/**
 * 在 size×size 的 2D 画布上画一枚珐琅徽章(会清空画布)。
 * @param ctx  CanvasRenderingContext2D(小程序 canvas type=2d 或浏览器)
 * @param size 画布边长(物理像素)
 * @param key  glyphs.js 的徽记名;不认识的落 DEFAULT
 * @param locked true = 只画细线稿
 */
function render(ctx, size, key, locked) {
  var plate = PLATE[key] || PLATE.DEFAULT;
  var k = size / 2 / 96;
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.clearRect(0, 0, size, size);
  ctx.save();
  ctx.translate(size / 2, size / 2); ctx.scale(k, k);
  platePath(ctx, plate.shape);
  ctx.fillStyle = plate.base; ctx.fill();
  ctx.clip();
  ctx.scale(0.9, 0.9);
  GLYPHS.draw(ctx, key);
  ctx.restore();
  // 徽记里的 CUT 会把底板也挖穿:挖穿的地方补回底板浅色(当作白珐琅),不让徽章出现破洞
  ctx.save();
  ctx.globalCompositeOperation = 'destination-over';
  ctx.translate(size / 2, size / 2); ctx.scale(k, k);
  platePath(ctx, plate.shape);
  ctx.fillStyle = '#F4F1EA'; ctx.fill();
  ctx.restore();

  var img = ctx.getImageData(0, 0, size, size), px = img.data, n = size * size;
  var lab = new Int32Array(n), i, x, y;
  for (i = 0; i < n; i++) {
    var o = i * 4;
    lab[i] = px[o + 3] < 128 ? 0 : 1 + ((px[o] >> 4) << 8 | (px[o + 1] >> 4) << 4 | (px[o + 2] >> 4));
  }
  var inner = new Uint8Array(n), outer = new Uint8Array(n);
  for (y = 0; y < size; y++) for (x = 0; x < size; x++) {
    i = y * size + x;
    if (!lab[i]) continue;
    var nb = [x > 0 ? lab[i - 1] : 0, x < size - 1 ? lab[i + 1] : 0, y > 0 ? lab[i - size] : 0, y < size - 1 ? lab[i + size] : 0];
    for (var j = 0; j < 4; j++) {
      if (nb[j] === 0) outer[i] = 1;
      else if (nb[j] < lab[i]) inner[i] = 1;   // 只取交界的一侧当种子,金线宽 = 2 × wLine 居中
    }
  }
  var d1 = distance(inner, size, size), d2 = distance(outer, size, size);
  var wLine = size * 0.011, wRim = size * 0.05;

  var out = ctx.createImageData(size, size), q = out.data;
  if (locked) {
    // 线稿:只沿色块交界与外缘画一道细线,粗细按距离做抗锯齿
    var lw = Math.max(1, size * 0.006);
    for (i = 0; i < n; i++) {
      if (!lab[i]) continue;
      var dd = Math.min(d1[i], d2[i] * 0.7);
      var a = dd <= lw ? 1 : (dd <= lw + 1 ? lw + 1 - dd : 0);
      if (!a) continue;
      q[i * 4] = 255; q[i * 4 + 1] = 255; q[i * 4 + 2] = 255; q[i * 4 + 3] = Math.round(a * 92);
    }
    ctx.putImageData(out, 0, 0);
    return;
  }

  var hgt = new Float32Array(n);
  for (i = 0; i < n; i++) if (lab[i]) hgt[i] = Math.max(ridge(d1[i], wLine), ridge(d2[i], wRim));
  var L = [-0.45, -0.6, 0.66];
  for (y = 0; y < size; y++) for (x = 0; x < size; x++) {
    i = y * size + x;
    var o4 = i * 4;
    if (!lab[i]) continue;
    var h = hgt[i], r, g, b;
    if (h > 0) {
      var hx = (x < size - 1 ? hgt[i + 1] : h) - (x > 0 ? hgt[i - 1] : h);
      var hy = (y < size - 1 ? hgt[i + size] : h) - (y > 0 ? hgt[i - size] : h);
      var s = 2.4, nx = -hx * s, ny = -hy * s, nz = 1, nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
      var lam = Math.max(0, (nx * L[0] + ny * L[1] + nz * L[2]) / nl);
      var spec = Math.pow(lam, 10);
      var t = Math.min(1, lam * 1.15);
      r = GOLD_LO[0] + (GOLD_MID[0] - GOLD_LO[0]) * t; g = GOLD_LO[1] + (GOLD_MID[1] - GOLD_LO[1]) * t; b = GOLD_LO[2] + (GOLD_MID[2] - GOLD_LO[2]) * t;
      r += (GOLD_HI[0] - r) * spec; g += (GOLD_HI[1] - g) * spec; b += (GOLD_HI[2] - b) * spec;
    } else {
      // 珐琅:比金线略低,贴近金线处压一点暗;整体左上一层很淡的釉光
      var near = Math.min(d1[i] - wLine, d2[i] - wRim);
      var shade = 1 - 0.22 * Math.exp(-near / (size * 0.012));
      var gloss = 0.10 * Math.max(0, 1 - (x + y) / size);
      r = px[o4] * shade; g = px[o4 + 1] * shade; b = px[o4 + 2] * shade;
      r += (255 - r) * gloss; g += (255 - g) * gloss; b += (255 - b) * gloss;
    }
    // 外缘抗锯齿:沿用原图 alpha
    q[o4] = r; q[o4 + 1] = g; q[o4 + 2] = b; q[o4 + 3] = px[o4 + 3];
  }
  ctx.putImageData(out, 0, 0);
}

module.exports = { render: render, PLATE: PLATE };
