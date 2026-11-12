// 动效 v2 · 章节开始(时刻 1)与通关(时刻 3)的纯函数:版面几何、时间轴、缓动取样、读数滚动。
// 规格 = docs/delivery/questo-ui-20261109/proto/motion-v2/index.html;尺寸按 390 宽稿给 px,组件里 ×K 转 rpx。
// WeChat animate() 的缓动只有 linear/ease/ease-in/ease-out/ease-in-out,所以原型里的 cubic-bezier 在这里
// 取样成带 offset 的多帧(sampleFrames),设备与浏览器里走同一条曲线。
var K = 750 / 390;

var EASE = {
  ease: [0.25, 0.1, 0.25, 1],
  easeOut: [0, 0, 0.58, 1],
  nav: [0.32, 0.72, 0, 1],
  spring: [0.34, 1.56, 0.64, 1],
  out: [0.16, 1, 0.3, 1],
  gather: [0.55, 0, 0.8, 0.2],
  charge: [0.5, 0, 0.75, 0],
};

// 毫秒。start = 章节开始,finale = 通关;数值逐字来自原型 run() 里的 sleep / delay / duration。
var START = {
  dotGrow: 420, ringAt: 300, ringStagger: 14, ringDur: 700,
  digitAt: 350, digitStagger: 32, digitDur: 380,
  dotFadeAt: 500, dotFadeDur: 300,
  haloAt: 1100, haloDur: 900, raysDur: 1600, spinDur: 60000,
  kickAt: 1300, wordStep: 180, wordSpan: 720, statsGap: 250, ctaGap: 500,
  countDur: [800, 700, 800],
  autoHold: 2800,
};
var FINALE = {
  chargeDur: 900, particleStagger: 12, particleCount: 40,
  flashAt: 900, flashDur: 600, orbOutDur: 240, ringxDur: 1100,
  raysAt: 1100, raysDur: 900, raysSpinDelay: 900, raysSpinDur: 40000,
  medalAt: 1100, medalDur: 700, glowAt: 1800, glowDur: 3000,
  kickAt: 1700, ttlAt: 1850, rowAt: 2150, actsAt: 2550,
};

function bezier(x1, y1, x2, y2) {
  var cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
  var cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
  function sx(t) { return ((ax * t + bx) * t + cx) * t; }
  function sy(t) { return ((ay * t + by) * t + cy) * t; }
  function dx(t) { return (3 * ax * t + 2 * bx) * t + cx; }
  return function (x) {
    if (x <= 0) return 0;
    if (x >= 1) return 1;
    var t = x, i, d;
    for (i = 0; i < 8; i++) { d = sx(t) - x; if (Math.abs(d) < 1e-6) return sy(t); var s = dx(t); if (Math.abs(s) < 1e-6) break; t -= d / s; }
    var lo = 0, hi = 1; t = x;
    for (i = 0; i < 24; i++) { d = sx(t); if (Math.abs(d - x) < 1e-6) break; if (d < x) lo = t; else hi = t; t = (lo + hi) / 2; }
    return sy(t);
  };
}

/** props = { opacity: [[0,0],[1,1]], scale: [[0,.4],[1,1.1]], rotate, translateX, translateY };
 *  easing = EASE.xxx 的四元数组(整段时间线整形,和 WAAPI 的 easing 一致)。返回 wx animate 的 keyframes(带 offset)。 */
function sampleFrames(props, easing, n) {
  var f = easing ? bezier(easing[0], easing[1], easing[2], easing[3]) : function (x) { return x; };
  var steps = Math.max(2, n || 8), out = [], i, k;
  function at(list, u) {
    if (u <= list[0][0]) return list[0][1];
    for (var j = 1; j < list.length; j++) {
      if (u <= list[j][0]) { var a = list[j - 1], b = list[j]; return a[1] + (b[1] - a[1]) * ((u - a[0]) / ((b[0] - a[0]) || 1)); }
    }
    return list[list.length - 1][1];
  }
  for (i = 0; i <= steps; i++) {
    var p = i / steps, u = f(p), fr = { offset: Math.round(p * 1000) / 1000, ease: 'linear' };
    for (k in props) if (Object.prototype.hasOwnProperty.call(props, k)) {
      var v = at(props[k], u);
      if (k === 'scale') fr.scale = [v, v]; else fr[k] = v;
    }
    out.push(fr);
  }
  return out;
}

// ---------- 点阵数字(5×7,格 12 · 点半径 4.6,和原型 dotDigits 同几何) ----------
var DIGITS = {
  0: '01110100011001110101110011000101110', 1: '00100011000010000100001000010001110', 2: '01110100010000100010001000100011111',
  3: '11110000010000101110000010000111110', 4: '00010001100101010010111110001000010', 5: '11111100001111000001000011000101110',
  6: '00110010001000011110100011000101110', 7: '11111000010001000100010000100001000', 8: '01110100011000101110100011000101110',
  9: '01110100011000101111000010001001100',
};
var SEAL_BOX = 220;     // .seal 220×220
var NUM_BOX = { w: 120, h: 90 };  // svg 120×90,viewBox 按数字个数

/** 返回 { dots:[{x,y,r,o}] }:x/y 是点心在 220 方框内的 px,r 半径 px,o 是亮起次序(x+y*2,和原型 data-o 一致)。 */
function dotMatrix(text) {
  var ds = String(text == null ? '' : text).split('').filter(function (c) { return DIGITS[c]; });
  if (!ds.length) return { dots: [], scale: 1 };
  var vbw = ds.length * 6 * 12 - 12, vbh = 84;
  var s = Math.min(NUM_BOX.w / vbw, NUM_BOX.h / vbh);
  var ox = (SEAL_BOX - vbw * s) / 2, oy = (SEAL_BOX - vbh * s) / 2, dots = [];
  ds.forEach(function (d, n) {
    for (var y = 0; y < 7; y++) for (var x = 0; x < 5; x++) {
      if (DIGITS[d][y * 5 + x] !== '1') continue;
      var gx = n * 6 + x;
      dots.push({ x: round2(ox + (gx * 12 + 6) * s), y: round2(oy + (y * 12 + 6) * s), r: round2(4.6 * s), o: gx + y * 2 });
    }
  });
  return { dots: dots, scale: s };
}

/** 章节印外环:28 个 7px 点,沿半径 104px 排开(从中心 translateY(0) 推到 -104)。 */
function ringDots(n, radius) {
  var count = n || 28, out = [];
  for (var i = 0; i < count; i++) out.push({ a: round2(i * 360 / count), delay: i * START.ringStagger, r: radius || 104 });
  return out;
}

function round2(v) { return Math.round(v * 100) / 100; }

/** 章名逐字(词)写出:按「·」「空格」切,保留分隔符作为独立词;每段超过 3 字按 3 字上限均分(四字词 → 二二)。 */
function splitTitle(title) {
  var raw = String(title == null ? '' : title).trim();
  if (!raw) return [];
  var out = [];
  raw.split(/(\s*[·・|]\s*)/).forEach(function (seg) {
    if (!seg) return;
    if (/^\s*[·・|]\s*$/.test(seg)) { out.push(' ' + seg.trim() + ' '); return; }
    seg.split(/\s+/).forEach(function (w, i) {
      if (!w) return;
      if (i > 0) out.push(' ');
      var chars = Array.from(w);
      if (chars.length <= 3 || !/[\u3400-\u9fff]/.test(w)) { out.push(w); return; }
      var parts = Math.ceil(chars.length / 3), base = Math.floor(chars.length / parts), extra = chars.length % parts, pos = 0;
      for (var p = 0; p < parts; p++) { var n = base + (p < extra ? 1 : 0); out.push(chars.slice(pos, pos + n).join('')); pos += n; }
    });
  });
  return out;
}

/** 词亮起的时刻(相对时间线 0):kickAt 起,每词 wordStep;词多了压缩到 wordSpan 内。 */
function wordTimes(count) {
  var step = count > 4 ? Math.max(40, Math.floor(START.wordSpan / count)) : START.wordStep, out = [];
  for (var i = 0; i < count; i++) out.push(START.kickAt + (i + 1) * step);
  return out;
}

/** 起点时间表:统计块、按钮出现时刻。 */
function startTimes(wordCount) {
  var wt = wordTimes(wordCount), last = wt.length ? wt[wt.length - 1] : START.kickAt;
  var stats = last + START.statsGap;
  return { kick: START.kickAt, words: wt, stats: stats, cta: stats + START.ctaGap, done: stats + START.ctaGap };
}

// ---------- 读数滚动 ----------
function pad2(n) { return (n < 10 ? '0' : '') + n; }
/** 解析一个读数:'356'→{kind:'int'};'06:20'→mmss;'2/4'→frac;'1.2 km'→num(带小数、前后缀);其余原样不滚。 */
function parseStat(v) {
  var s = String(v == null ? '' : v), m;
  if ((m = /^(\d+):(\d{2})$/.exec(s))) return { kind: 'mmss', to: Number(m[1]) * 60 + Number(m[2]) };
  if ((m = /^(\d+)\/(\d+)$/.exec(s))) return { kind: 'frac', to: Number(m[1]), of: m[2] };
  if ((m = /^(\D*?)(\d+(?:\.\d+)?)(\D*)$/.exec(s)) && !/^\d{1,3}(,\d{3})+$/.test(s)) {
    var dec = (m[2].split('.')[1] || '').length;
    return { kind: 'num', to: Number(m[2]), dec: dec, pre: m[1], post: m[3] };
  }
  if ((m = /^(\d{1,3}(?:,\d{3})+)$/.exec(s))) return { kind: 'int', to: Number(m[1].replace(/,/g, '')), group: true };
  return { kind: 'static', text: s };
}
/** e ∈ [0,1] 为已缓动的进度;e=1 必须等于原文。 */
function statAt(spec, e, raw) {
  if (e >= 1) return String(raw);
  switch (spec.kind) {
    case 'mmss': { var sec = Math.round(spec.to * e); return pad2(Math.floor(sec / 60)) + ':' + pad2(sec % 60); }
    case 'frac': return Math.round(spec.to * e) + '/' + spec.of;
    case 'num': return spec.pre + (spec.to * e).toFixed(spec.dec) + spec.post;
    case 'int': { var n = String(Math.round(spec.to * e)); return spec.group ? n.replace(/\B(?=(\d{3})+(?!\d))/g, ',') : n; }
    default: return spec.text;
  }
}
function countEase(p) { return 1 - Math.pow(1 - Math.min(1, Math.max(0, p)), 3); }
/** 起点读数(第 0 帧):每格从 0 起。 */
function statStart(raw) { return statAt(parseStat(raw), 0, raw); }

// ---------- 通关粒子:确定性伪随机,同一份数据每次同样的轨迹 ----------
function rng(seed) { var s = seed >>> 0 || 1; return function () { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; }; }
function particles(count, seed) {
  var r = rng(seed || 20261112), out = [];
  for (var i = 0; i < (count || FINALE.particleCount); i++) {
    var a = r() * Math.PI * 2, d = 180 + r() * 120;
    out.push({ x: round2(Math.cos(a) * d), y: round2(Math.sin(a) * d), delay: i * FINALE.particleStagger });
  }
  return out;
}

// ---------- 视图模型 ----------
function rpx(px) { return round2(px * K); }

function buildStart(info) {
  info = info || {};
  var m = dotMatrix(info.number);
  var words = splitTitle(info.title);
  var stats = (info.stats || []).filter(function (s) { return s && s.v !== '' && s.v != null; }).slice(0, 3);
  var t = startTimes(words.length);
  return {
    kick: info.kicker || '',
    words: words.map(function (w, i) { return { t: w, at: t.words[i] }; }),
    digits: m.dots.map(function (d) {
      var sz = d.r * 2;
      return { s: 'left:' + rpx(d.x - d.r) + 'rpx;top:' + rpx(d.y - d.r) + 'rpx;width:' + rpx(sz) + 'rpx;height:' + rpx(sz) + 'rpx;transition-delay:' + (START.digitAt + d.o * START.digitStagger) + 'ms' };
    }),
    ring: ringDots().map(function (d) { return { s: '--a:' + d.a + 'deg;transition-delay:' + d.delay + 'ms' }; }),
    stats: stats.map(function (s, i) { return { k: s.k, v: String(s.v), shown: statStart(s.v), spec: parseStat(s.v), dur: START.countDur[i] || 800 }; }),
    times: t,
  };
}

function buildFinaleView(info) {
  info = info || {};
  var stats = (info.stats || []).filter(function (s) { return s && s.v !== '' && s.v != null; }).slice(0, 3);
  return {
    kick: info.kicker || '已通关',
    title: info.title || '',
    tiles: stats.map(function (s) { return { k: s.k, v: String(s.v) }; }),
    particles: particles(),
  };
}

module.exports = {
  K: K, EASE: EASE, START: START, FINALE: FINALE, bezier: bezier, sampleFrames: sampleFrames,
  DIGITS: DIGITS, dotMatrix: dotMatrix, ringDots: ringDots, splitTitle: splitTitle, wordTimes: wordTimes, startTimes: startTimes,
  parseStat: parseStat, statAt: statAt, statStart: statStart, countEase: countEase, particles: particles,
  buildStart: buildStart, buildFinaleView: buildFinaleView, rpx: rpx,
};
