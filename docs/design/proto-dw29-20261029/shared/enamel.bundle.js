/* 自动生成 —— tools/build-medal.js 从 master subpackageP3/pages/badge-wall/index/{glyphs,enamel-pin}.js 包出,勿手改 */
(function () {
var GLYPHS_MOD = (function () { var module = { exports: {} }; // 城市身份卡徽记库 v2(Questify City Cards · PRD 2026-07-12 §6/§7.5)。
// v2 要点(对齐参考片"花能看出是花"):大面积实心有机形 + 每卡 2-3 层色区,
// 波点/字符的颜色由 shader 直接采样图案本色(engine uColorful 通道),配色照抄 PRD §6 逐卡定义。
// 接口不变:draw(ctx, key) —— 调用方已 translate/scale,±80 归一坐标;未知 key 落 DEFAULT。
// 图形语言仍是 坐标/轨迹/网格/门/回声/光点,禁 emoji/奖杯/皇冠/盾牌/旗帜。

function F(x, c, fn) { x.fillStyle = c; x.beginPath(); fn(); x.fill(); }
function ST(x, c, w, fn) { x.strokeStyle = c; x.lineWidth = w; x.beginPath(); fn(); x.stroke(); }
function CUT(x, fn) { x.globalCompositeOperation = 'destination-out'; x.fillStyle = '#fff'; x.beginPath(); fn(); x.fill(); x.globalCompositeOperation = 'source-over'; }
function DOT(x, c, px, py, r) { F(x, c, function () { x.arc(px, py, r, 0, Math.PI * 2); }); }
function RING(x, c, px, py, r, w, a0, a1) { ST(x, c, w, function () { x.arc(px, py, r, a0 || 0, a1 == null ? Math.PI * 2 : a1); }); }

var G = {

  /* 01 开始在场 ARRIVAL:大坐标针落地,波纹外扩。曜石黑 + 信号蓝 + 冷银 */
  arrival: function (x) {
    RING(x, '#8B93B8', 0, 30, 62, 6, Math.PI * 0.15, Math.PI * 0.85);
    RING(x, '#B9C2DE', 0, 30, 44, 7, Math.PI * 0.08, Math.PI * 0.92);
    F(x, '#3D7EFF', function () {            // 定位针本体:圆头 + 收尖
      x.arc(0, -18, 42, Math.PI * 0.82, Math.PI * 0.18);
      x.quadraticCurveTo(30, 22, 0, 56);
      x.quadraticCurveTo(-30, 22, -39.9, -4.5);
      x.closePath();
    });
    DOT(x, '#FFFFFF', 0, -16, 17);
    DOT(x, '#B8D7FF', 0, -16, 8);
  },

  /* 02 完成一程 ROUTE CLEARED:厚环轨迹闭合。炭黑 + 信号绿 + 雾白 */
  route: function (x) {
    F(x, '#14663F', function () { x.arc(0, 0, 58, 0, Math.PI * 2); });
    CUT(x, function () { x.arc(0, 0, 30, 0, Math.PI * 2); });
    x.lineCap = 'round';
    ST(x, '#35E08A', 15, function () { x.arc(0, 0, 44, -Math.PI * 0.5, Math.PI * 1.18); });
    DOT(x, '#B9F5D8', 0, -44, 7);            // 起点
    DOT(x, '#FFFFFF', Math.cos(Math.PI * 1.18) * 44, Math.sin(Math.PI * 1.18) * 44, 11);  // 终点闭合
    RING(x, '#EAF7F0', Math.cos(Math.PI * 1.18) * 44, Math.sin(Math.PI * 1.18) * 44, 19, 4);
  },

  /* 03 城市提案 CITY PROPOSAL:蓝图上的提案印章。档案纸白 + 高饱和蓝 + 石墨黑 */
  proposal: function (x) {
    x.save(); x.rotate(0.06);
    F(x, '#3478D4', function () { x.rect(-52, -52, 104, 104); });
    F(x, '#285FA8', function () { x.rect(-52, 20, 104, 32); });   // 折叠暗面
    CUT(x, function () { x.rect(-9, -34, 18, 68); });             // 白色"提案"加号(镂空)
    CUT(x, function () { x.rect(-34, -9, 68, 18); });
    x.restore();
    ST(x, '#EDF0FF', 6, function () { x.moveTo(-66, -66); x.lineTo(-42, -66); x.moveTo(-66, -66); x.lineTo(-66, -42); });
    ST(x, '#EDF0FF', 6, function () { x.moveTo(66, 66); x.lineTo(42, 66); x.moveTo(66, 66); x.lineTo(66, 42); });
    DOT(x, '#FFFFFF', 36, -36, 7);
  },

  /* 04 开门成社 FOUNDING CIRCLE:未闭合门环。深墨 + 温铜 + 信号蓝细线 */
  founding: function (x) {
    x.lineCap = 'butt';
    RING(x, '#3D7EFF', 0, -2, 66, 4, Math.PI * 1.18, Math.PI * 1.82);
    RING(x, '#D08A4A', 0, -2, 46, 20, Math.PI * 0.62, Math.PI * 2.38);   // 铜环,底部留门
    RING(x, '#8A5426', 0, -2, 30, 9);                                     // 内环暗铜
    F(x, '#E8B98A', function () { x.rect(-31, 32, 13, 32); });            // 门柱
    F(x, '#E8B98A', function () { x.rect(18, 32, 13, 32); });
    DOT(x, '#FFFFFF', 0, 50, 8);                                          // 门口光点
  },

  /* 05 同路相逢 JOINED THE CIRCLE:两条宽带轨迹汇合。雾灰 + 柔蓝 + 冰蓝 */
  joined: function (x) {
    x.lineCap = 'round';
    ST(x, '#8EC5FF', 19, function () { x.moveTo(-62, 54); x.quadraticCurveTo(-24, 22, 0, 0); x.quadraticCurveTo(26, -24, 60, -54); });
    ST(x, '#9FD8FF', 19, function () { x.moveTo(62, 54); x.quadraticCurveTo(24, 22, 0, 0); x.quadraticCurveTo(-26, -24, -60, -54); });
    DOT(x, '#FFFFFF', 0, 0, 14);
    RING(x, '#E6F2FF', 0, 0, 24, 4);
  },

  /* 06 一次召集 THE GATHERING:离散坐标向心收束,中心留白。酒红 + 朱砂橙 + 暗银 */
  gathering: function (x) {
    x.lineCap = 'round';
    RING(x, '#B23A4A', 0, 0, 68, 4, Math.PI * 0.1, Math.PI * 0.9);
    RING(x, '#B23A4A', 0, 0, 68, 4, Math.PI * 1.1, Math.PI * 1.9);
    for (var k = 0; k < 7; k++) {
      var a = -Math.PI / 2 + k * Math.PI * 2 / 7;
      var c = Math.cos(a), s = Math.sin(a);
      F(x, '#B23A4A', function () {                       // 向心楔
        x.moveTo(c * 46 - s * 7, s * 46 + c * 7);
        x.lineTo(c * 46 + s * 7, s * 46 - c * 7);
        x.lineTo(c * 22, s * 22);
        x.closePath();
      });
      DOT(x, '#FF7A45', c * 54, s * 54, 11);
    }
    RING(x, '#FFFFFF', 0, 0, 13, 6);
  },

  /* 07 有人循迹 FOLLOWED PATH:轨迹与回声。纯黑 + 激光青 + 电蓝 */
  followed: function (x) {
    x.lineCap = 'round';
    ST(x, '#0F5B5F', 26, function () { x.moveTo(-62, 42); x.bezierCurveTo(-20, 62, 20, -62, 62, -42); });  // 辉光垫底
    ST(x, '#3EE6E0', 14, function () { x.moveTo(-62, 42); x.bezierCurveTo(-20, 62, 20, -62, 62, -42); });
    ST(x, '#3D8DFF', 8,  function () { x.moveTo(-48, 62); x.bezierCurveTo(-8, 78, 34, -46, 72, -28); });   // 回声
    DOT(x, '#FFFFFF', -62, 42, 10);
    DOT(x, '#C9E5FF', 72, -28, 7);
  },

  /* 08 带来同行者 NEW COMPANION:日出与远坐标。午夜蓝 + 日出橙 + 冷白 */
  companion: function (x) {
    DOT(x, '#FFA24D', -20, 22, 34);                                   // 升起的橙圆
    DOT(x, '#FFD9AE', -20, 22, 16);
    F(x, '#33549E', function () {                                     // 地平线丘
      x.moveTo(-80, 80); x.quadraticCurveTo(0, 26, 80, 80); x.closePath();
    });
    x.lineCap = 'round';
    ST(x, '#F2F6FF', 5, function () { x.moveTo(-4, 6); x.quadraticCurveTo(22, -22, 44, -40); });  // 一束光
    DOT(x, '#FFFFFF', 50, -46, 8);                                    // 远处第二坐标
    RING(x, '#F2F6FF', 50, -46, 17, 4);
  },

  /* 09 共创发生 CITY ALLIANCE:两种几何体相遇,交叠生成第三形态。石墨 + 铂金 + 冷蓝光 */
  alliance: function (x) {
    x.save(); x.translate(-18, 2); x.rotate(0.16);
    F(x, '#AEB6C6', function () { x.rect(-34, -34, 68, 68); });
    x.restore();
    DOT(x, '#4296E8', 24, -2, 36);
    x.save();                                                          // 交叠区 = 发光的第三形态
    x.beginPath(); x.arc(24, -2, 36, 0, Math.PI * 2); x.clip();
    x.save(); x.translate(-18, 2); x.rotate(0.16);
    F(x, '#FFFFFF', function () { x.rect(-34, -34, 68, 68); });
    x.restore(); x.restore();
  },

  /* 10 点亮一隅 CITY LIT:城市网格中一格点亮。深黑 + 极光金 + 路线蓝 */
  citylit: function (x) {
    var P = [-64, -32, 0, 32], SZ = 32;
    ST(x, '#4D8FD8', 6, function () {
      for (var i = 0; i <= 4; i++) {
        x.moveTo(-64, -64 + i * 32); x.lineTo(64, -64 + i * 32);
        x.moveTo(-64 + i * 32, -64); x.lineTo(-64 + i * 32, 64);
      }
    });
    F(x, '#FFD866', function () { x.rect(P[2], P[1], SZ, SZ); });      // 点亮的一格
    RING(x, '#FFE9A8', P[2] + SZ / 2, P[1] + SZ / 2, 26, 5);
    DOT(x, '#FFFFFF', P[2] + SZ / 2, P[1] + SZ / 2, 7);
  },

  /* 11 初次漫游 ROAM:迷雾被走过的轨迹擦开一条道。雾银 + 路线蓝 + 信号绿。
     与另两枚轨迹卡刻意区分:route 是闭合厚环(走完一程,有终点),
     followed 是别人循着我的线;漫游是【开放的、前方敞开、没有终点】——
     所以轨迹不闭合、右端直接出画,末端只留一个光点。 */
  roam: function (x) {
    // 没有闭合的环 + 从缺口走出去的轨迹。与 02 route 是【designed pair】:
    // route 是闭合厚环(走完一程,收口了),这枚走到缺口没收口,而是走了出去——漫游没有终点。
    // 形制刻意同族、含义相反;靠配色(蓝银 vs 信号绿)与缺口/出走轨迹区分。
    // 只用大尺度+大缺口的形:引擎把徽记波点化,细结构一律糊(实测螺旋两版皆废)。
    var COL = ['#C5E1FF', '#99CCFF', '#6FB4F2', '#4D8AC4', '#3F6185', '#3A4058'];
    var A0 = Math.PI * 0.62, A1 = Math.PI * 2.15, i;
    x.lineCap = 'butt';
    RING(x, '#2E3448', 0, 0, 46, 30, A0, A1);                  // 雾:垫在轨迹两侧
    RING(x, '#6FB4F2', 0, 0, 46, 20, A0, A1);                  // 厚弧本体(照 founding 的 20 宽)
    RING(x, '#C5E1FF', 0, 0, 46, 7, A0, A1);                   // 亮芯
    for (i = 0; i <= 13; i++) {                                // 从缺口走出去:由粗到细,散进雾
      var t = i / 13, rr = 44 + t * 32, aa = Math.PI * 2.385 + t * 0.42;
      DOT(x, COL[Math.min(COL.length - 1, Math.floor(t * COL.length))],
          Math.cos(aa) * rr, Math.sin(aa) * rr, 12.5 * (1 - t) + 2);
    }
    DOT(x, '#30D158', Math.cos(A0) * 46, Math.sin(A0) * 46, 13);   // 第一步(EXPLORE 信号绿)
    DOT(x, '#FFFFFF', Math.cos(A0) * 46, Math.sin(A0) * 46, 6);    // 白色焦点
  }
};

/* 未知徽记兜底:一个坐标点(PRD 语言) */
G.DEFAULT = function (x) {
  RING(x, '#3D7EFF', 0, 0, 44, 10);
  DOT(x, '#FFFFFF', 0, 0, 14);
  ST(x, '#B9C2DE', 6, function () { x.moveTo(0, -68); x.lineTo(0, -52); });
  ST(x, '#B9C2DE', 6, function () { x.moveTo(0, 52); x.lineTo(0, 68); });
  ST(x, '#B9C2DE', 6, function () { x.moveTo(-68, 0); x.lineTo(-52, 0); });
  ST(x, '#B9C2DE', 6, function () { x.moveTo(52, 0); x.lineTo(52, 0); x.lineTo(68, 0); });
};

module.exports = {
  keys: Object.keys(G).filter(function (k) { return k !== 'DEFAULT'; }),
  draw: function (ctx, key) {
    ctx.lineJoin = 'round';
    (G[key] || G.DEFAULT)(ctx);
  }
};

; return module.exports; })();
var ENAMEL = (function () { var module = { exports: {} }; var require = function () { return GLYPHS_MOD; }; // 2026-10-28 业主:勋章改成珐琅徽章(参考 x.com/danywander/status/2097314310783390104)。
// 每枚 = 一块有形状的底板 + glyphs.js 的徽记;所有色块交界压一道凸起的金属线,外缘一圈厚边。
// 未解锁只留这些线(细线稿),不上色。
// 同一业主第二轮:要更精致、可以点着转、金属不限于金色 —— 金属面改成环境反射(matcap 式),
// 线条是平顶带倒角的凸棱,珐琅面加一层圆顶釉光;另出背面(金属底 + 刻字)与侧边,供 spin() 画旋转。
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

/* 金属成色:六档环境色,从「朝下的暗处」到「迎光的亮处」再回落。rainbow 另按法线方向转色相。 */
var FINISH = {
  gold:    { zh: '金',   env: [[64, 40, 8], [138, 94, 28], [214, 166, 70], [255, 232, 160], [250, 210, 120], [120, 80, 22]] },
  silver:  { zh: '银',   env: [[34, 38, 46], [98, 104, 116], [176, 182, 192], [252, 253, 255], [214, 218, 226], [78, 82, 92]] },
  rose:    { zh: '玫瑰金', env: [[62, 30, 26], [140, 82, 72], [214, 150, 130], [255, 226, 212], [236, 182, 162], [104, 56, 48]] },
  bronze:  { zh: '古铜', env: [[34, 20, 10], [92, 58, 30], [154, 106, 60], [214, 170, 112], [176, 128, 74], [62, 38, 18]] },
  black:   { zh: '黑镍', env: [[8, 8, 10], [26, 27, 31], [60, 62, 70], [176, 178, 188], [96, 98, 108], [16, 16, 19]] },
  rainbow: { zh: '幻彩', env: [[40, 30, 60], [90, 80, 140], [160, 170, 220], [240, 240, 255], [200, 200, 240], [70, 60, 110]], iridescent: true }
};

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

/* 平顶凸棱:中间是平台,两侧 bevel 宽的斜坡;用 smoothstep 让坡面过渡圆润 */
function plateau(dist, width, bevel) {
  if (dist >= width) return 0;
  var t = Math.min(1, (width - dist) / bevel);
  return t * t * (3 - 2 * t);
}

function hash(x, y) { var s = Math.sin(x * 127.1 + y * 311.7) * 43758.5453; return s - Math.floor(s); }

function envColor(fin, t, out) {
  var e = fin.env, f = Math.max(0, Math.min(0.9999, t)) * (e.length - 1), i = Math.floor(f), k = f - i;
  out[0] = e[i][0] + (e[i + 1][0] - e[i][0]) * k;
  out[1] = e[i][1] + (e[i + 1][1] - e[i][1]) * k;
  out[2] = e[i][2] + (e[i + 1][2] - e[i][2]) * k;
  return out;
}

function hue(h, out) {   // 幻彩:法线方向 → 色相(青-紫-金),亮度交给 env
  var r = 0.5 + 0.5 * Math.cos(6.2832 * (h + 0.0)), g = 0.5 + 0.5 * Math.cos(6.2832 * (h + 0.33)), b = 0.5 + 0.5 * Math.cos(6.2832 * (h + 0.67));
  out[0] = r; out[1] = g; out[2] = b; return out;
}

/* 金属着色:法线 → 反射方向 → 环境色;加一道斜向的宽光带与细颗粒,平顶也有金属感 */
function metal(fin, nx, ny, nz, u, v, grain, out) {
  var rx = 2 * nz * nx, ry = 2 * nz * ny;
  var t = 0.5 - 0.42 * ry - 0.2 * rx + 0.22 * (0.55 - (u * 0.6 + v * 0.4)) + grain;
  envColor(fin, t, out);
  if (fin.iridescent) {
    var c = hue(0.55 + 0.5 * nx + 0.35 * ny + 0.25 * u, [0, 0, 0]), m = 0.55;
    out[0] = out[0] * (1 - m) + out[0] * c[0] * 1.6 * m;
    out[1] = out[1] * (1 - m) + out[1] * c[1] * 1.6 * m;
    out[2] = out[2] * (1 - m) + out[2] * c[2] * 1.6 * m;
  }
  return out;
}

/* 画底板 + 徽记,返回 { lab, px }:lab 是色块标签(0 = 板外) */
function paint(ctx, size, key, withGlyph) {
  var plate = PLATE[key] || PLATE.DEFAULT, k = size / 2 / 96;
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.globalCompositeOperation = 'source-over';
  ctx.globalAlpha = 1;
  ctx.clearRect(0, 0, size, size);
  ctx.save();
  ctx.translate(size / 2, size / 2); ctx.scale(k, k);
  platePath(ctx, plate.shape);
  ctx.fillStyle = withGlyph ? plate.base : '#808080'; ctx.fill(); /* ds-ok 只用来取剪影遮罩,不上屏 */
  if (withGlyph) { ctx.clip(); ctx.scale(0.9, 0.9); GLYPHS.draw(ctx, key); }
  ctx.restore();
  if (withGlyph) {
    // 徽记里的 CUT 会把底板也挖穿:挖穿的地方补回白珐琅,不让徽章出现破洞
    ctx.save();
    ctx.globalCompositeOperation = 'destination-over';
    ctx.translate(size / 2, size / 2); ctx.scale(k, k);
    platePath(ctx, plate.shape);
    ctx.fillStyle = '#F4F1EA'; ctx.fill(); /* ds-ok 白珐琅:徽章本身的釉色,不随主题 */
    ctx.restore();
  }
  var img = ctx.getImageData(0, 0, size, size), px = img.data, n = size * size, lab = new Int32Array(n);
  for (var i = 0; i < n; i++) {
    var o = i * 4;
    lab[i] = px[o + 3] < 128 ? 0 : 1 + ((px[o] >> 4) << 8 | (px[o + 1] >> 4) << 4 | (px[o + 2] >> 4));
  }
  return { lab: lab, px: px };
}

function seeds(lab, size) {
  var n = size * size, inner = new Uint8Array(n), outer = new Uint8Array(n), x, y, i;
  for (y = 0; y < size; y++) for (x = 0; x < size; x++) {
    i = y * size + x;
    if (!lab[i]) continue;
    var a = x > 0 ? lab[i - 1] : 0, b = x < size - 1 ? lab[i + 1] : 0, c = y > 0 ? lab[i - size] : 0, d = y < size - 1 ? lab[i + size] : 0;
    if (!a || !b || !c || !d) outer[i] = 1;
    if ((a && a < lab[i]) || (b && b < lab[i]) || (c && c < lab[i]) || (d && d < lab[i])) inner[i] = 1;
  }
  return { inner: inner, outer: outer };
}

/* 由高度场算法线并着色金属;enamel 回调给非金属像素上色 */
function shade(ctx, size, lab, px, hgt, fin, enamel) {
  var out = ctx.createImageData(size, size), q = out.data, c = [0, 0, 0], S = size / 40;
  for (var y = 0; y < size; y++) for (var x = 0; x < size; x++) {
    var i = y * size + x, o = i * 4;
    if (!lab[i]) continue;
    var h = hgt[i], r, g, b;
    if (h > 0) {
      var hx = (x < size - 1 && lab[i + 1] ? hgt[i + 1] : 0) - (x > 0 && lab[i - 1] ? hgt[i - 1] : 0);
      var hy = (y < size - 1 && lab[i + size] ? hgt[i + size] : 0) - (y > 0 && lab[i - size] ? hgt[i - size] : 0);
      var nx = -hx * S, ny = -hy * S, nl = Math.sqrt(nx * nx + ny * ny + 1);
      metal(fin, nx / nl, ny / nl, 1 / nl, x / size, y / size, (hash(x, y) - 0.5) * 0.05, c);
      r = c[0]; g = c[1]; b = c[2];
      if (h < 0.999) { var a = 0.35 + 0.65 * h; r *= a; g *= a; b *= a; }   // 斜坡根部压暗,凸棱立起来
    } else {
      var e = enamel(i, x, y); r = e[0]; g = e[1]; b = e[2];
    }
    q[o] = r; q[o + 1] = g; q[o + 2] = b; q[o + 3] = px[o + 3];
  }
  ctx.putImageData(out, 0, 0);
}

/**
 * 正面:在 size×size 的 2D 画布上画一枚珐琅徽章(会清空画布)。
 * @param ctx  CanvasRenderingContext2D(小程序 canvas type=2d 或浏览器)
 * @param size 画布边长(物理像素);要按屏幕上实际显示的像素画,放大显示会糊
 * @param key  glyphs.js 的徽记名;不认识的落 DEFAULT
 * @param locked true = 只画细线稿
 * @param opts  { finish: 'gold'|'silver'|'rose'|'bronze'|'black'|'rainbow', enamel: 'hard'|'soft'|'relief' }
 */
function render(ctx, size, key, locked, opts) {
  opts = opts || {};
  var fin = FINISH[opts.finish] || FINISH.gold, soft = opts.enamel === 'soft', relief = opts.enamel === 'relief';
  var p = paint(ctx, size, key, true), lab = p.lab, px = p.px, n = size * size, i;
  var sd = seeds(lab, size), d1 = distance(sd.inner, size, size), d2 = distance(sd.outer, size, size);
  var wLine = size * 0.014, wRim = size * 0.058, bev = size * 0.01;

  if (locked) {
    // 线稿:只沿色块交界与外缘画一道细线,粗细按距离做抗锯齿
    var out = ctx.createImageData(size, size), q = out.data, lw = Math.max(1, size * 0.006);
    for (i = 0; i < n; i++) {
      if (!lab[i]) continue;
      var dd = Math.min(d1[i], d2[i] * 0.7);
      var al = dd <= lw ? 1 : (dd <= lw + 1 ? lw + 1 - dd : 0);
      if (!al) continue;
      q[i * 4] = 255; q[i * 4 + 1] = 255; q[i * 4 + 2] = 255; q[i * 4 + 3] = Math.round(al * 92);
    }
    ctx.putImageData(out, 0, 0);
    return;
  }

  var hgt = new Float32Array(n);
  for (i = 0; i < n; i++) if (lab[i]) hgt[i] = Math.max(plateau(d1[i], wLine, bev), plateau(d2[i], wRim, bev * 1.6));
  // 外缘最外 1.2px 一道暗线:徽章放在任何底色上轮廓都清楚
  for (i = 0; i < n; i++) if (lab[i] && d2[i] < size * 0.004) hgt[i] = 0.25;

  shade(ctx, size, lab, px, hgt, fin, function (idx, x, y) {
    var o = idx * 4, near = Math.min(d1[idx] - wLine, d2[idx] - wRim), u = x / size - 0.34, v = y / size - 0.28;
    var r = px[o], g = px[o + 1], b = px[o + 2];
    if (relief) {
      // 全金属浮雕:不上釉,色块变成高低不同的喷砂金属面(按原色明暗分层),像参考里的背面
      var lum = (r * 0.3 + g * 0.59 + b * 0.11) / 255, cc = envColor(fin, 0.22 + lum * 0.4 + (hash(x, y) - 0.5) * 0.04, [0, 0, 0]);
      var cv2 = 1 - 0.3 * Math.exp(-near / (size * 0.015));
      return [cc[0] * cv2, cc[1] * cv2, cc[2] * cv2];
    }
    if (soft) {
      // 软珐琅:釉面低于金属线,贴着金属处有一圈凹进去的暗,整体哑光
      var cav = 1 - 0.34 * Math.exp(-near / (size * 0.02));
      r *= cav * 0.94; g *= cav * 0.94; b *= cav * 0.94;
      return [r, g, b];
    }
    // 硬珐琅:与金属齐平、抛光;贴近金属一圈浅浅的阴影 + 左上一块圆顶高光 + 右下极淡的环境反光
    var sh = 1 - 0.16 * Math.exp(-near / (size * 0.01));
    r *= sh; g *= sh; b *= sh;
    var dome = Math.exp(-(u * u + v * v * 1.6) / 0.022) * 0.30;
    var rim = Math.max(0, (x + y) / size - 1.25) * 0.18;
    r += (255 - r) * (dome + rim); g += (255 - g) * (dome + rim); b += (255 - b) * (dome + rim);
    return [r, g, b];
  });
}

/**
 * 背面:同形金属底板,一圈厚边 + 同心拉丝。业主 2026-10-28:背面不放字。
 */
function renderBack(ctx, size, key, opts) {
  opts = opts || {};
  var fin = FINISH[opts.finish] || FINISH.gold, n = size * size, i;
  var p = paint(ctx, size, key, false), lab = p.lab, px = p.px;
  var sd = seeds(lab, size), d2 = distance(sd.outer, size, size), wRim = size * 0.058, bev = size * 0.01;
  var hgt = new Float32Array(n), cx = size / 2, cy = size / 2, ring = 360 / size;
  for (i = 0; i < n; i++) if (lab[i]) {
    var x = i % size, y = (i - x) / size, rr = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
    // 底面平台略低于外边(0.82);同心拉丝 = 按半径分环的细微高度起伏(环宽随画布缩放,大小图观感一致)
    hgt[i] = Math.max(plateau(d2[i], wRim, bev * 1.6), 0.82 + 0.006 * (hash(Math.floor(rr * ring * 2.2), 1) - 0.5));
    if (d2[i] < size * 0.004) hgt[i] = 0.25;
  }
  shade(ctx, size, lab, px, hgt, fin, function () { return [0, 0, 0]; });
}

/* 侧边:同形剪影,整块金属色(亮 / 暗两张交替叠,看起来像铣过的边) */
function renderEdge(ctx, size, key, dark, opts) {
  opts = opts || {};
  var fin = FINISH[opts.finish] || FINISH.gold;
  var p = paint(ctx, size, key, false), lab = p.lab, px = p.px, n = size * size;
  var out = ctx.createImageData(size, size), q = out.data, c = envColor(fin, dark ? 0.12 : 0.32, [0, 0, 0]);
  for (var i = 0; i < n; i++) {
    if (!lab[i]) continue;
    q[i * 4] = c[0]; q[i * 4 + 1] = c[1]; q[i * 4 + 2] = c[2]; q[i * 4 + 3] = px[i * 4 + 3];
  }
  ctx.putImageData(out, 0, 0);
}

/**
 * 画一帧旋转中的徽章(绕竖轴)。正面朝向时 theta=0,转到背面是 π。
 * faces = { front, back, edge, edgeDark }:四张可 drawImage 的图(小程序 canvas.createImage / 浏览器 Image 或 canvas)。
 * 剪影一层层叠出厚度,所以六边形、花边这些不是圆的形状转起来边也是对的。
 */
function spin(ctx, W, H, faces, theta, size) {
  var c = Math.cos(theta), s = Math.sin(theta), ac = Math.abs(c), half = size * 0.034;
  var cx = W / 2, top = (H - size) / 2, w = Math.max(1, size * ac), N = 14;
  ctx.clearRect(0, 0, W, H);
  for (var k = 0; k <= N; k++) {
    var z = -half + (2 * half * k) / N;
    ctx.drawImage(k % 2 ? faces.edge : faces.edgeDark, cx - w / 2 + z * s * 1, top, w, size);
  }
  var front = c >= 0, off = (front ? half : -half) * s;
  ctx.drawImage(front ? faces.front : faces.back, cx - w / 2 + off, top, w, size);
  if (ac < 0.98) {   // 越侧过去越暗一点
    ctx.globalAlpha = (1 - ac) * 0.45;
    ctx.drawImage(faces.edgeDark, cx - w / 2 + off, top, w, size);
    ctx.globalAlpha = 1;
  }
}

/* 样式预设:成色 + 釉面。业主在样张页里挑,勋章墙按 preset 名取 */
var PRESETS = {
  classic: { zh: '经典金', finish: 'gold', enamel: 'hard' },
  silver:  { zh: '亮银', finish: 'silver', enamel: 'hard' },
  rose:    { zh: '玫瑰金软珐琅', finish: 'rose', enamel: 'soft' },
  vintage: { zh: '古铜软珐琅', finish: 'bronze', enamel: 'soft' },
  noir:    { zh: '黑镍', finish: 'black', enamel: 'hard' },
  prism:   { zh: '幻彩', finish: 'rainbow', enamel: 'hard' },
  relief:  { zh: '银浮雕', finish: 'silver', enamel: 'relief' }
};

/* 2026-10-28 业主:「颜色你来定」—— 定为按类别分金属,同类同色,一面墙上有层次又不乱:
   探索 银 · 创造 金 · 组织 古铜 · 连接 玫瑰金 · 共创 黑镍。下标即勋章墙 CAT_META 的轨道(badge.rarity)。
   想整面墙统一成某一套,把 BADGE_PRESET 改成 PRESETS 里的名字即可。 */
var TRACK_FINISH = ['silver', 'gold', 'bronze', 'rose', 'black'];
var BADGE_PRESET = 'bycat';

/** 某一枚该用的成色与釉面 */
function optsFor(track) {
  var p = PRESETS[BADGE_PRESET];
  if (p) return p;
  return { finish: TRACK_FINISH[track] || 'gold', enamel: 'hard' };
}

module.exports = { BADGE_PRESET: BADGE_PRESET, TRACK_FINISH: TRACK_FINISH, optsFor: optsFor, PRESETS: PRESETS, render: render, renderBack: renderBack, renderEdge: renderEdge, spin: spin, PLATE: PLATE, FINISH: FINISH };

; return module.exports; })();
window.GLYPHS = GLYPHS_MOD; window.ENAMEL = ENAMEL;
})();
