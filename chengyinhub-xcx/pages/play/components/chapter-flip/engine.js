// 章节翻转过场的画布引擎(2026-10-28 业主定稿原型 claude.ai/artifact/KKQJxLNAL3JSb5pYDF1xtb)。
// 一张 2d 画布画全部动效:星点、方块数字/标志、三棱锥、碎片、方块波、三角彩纸。
// 文字与按钮不在这里,由组件 WXML 负责。纯函数部分(几何、取样、缓动)可在 node 里单测。

const PAL = {
  gold: [241, 201, 75],
  silver: [226, 230, 236],
  star: '#D9DCF0',
  dust: '#EFE6C2',
  spark: '#FFF4C8',
  ember: '#F6D67A',
  waveTile: '#E9EDF3',
  paper0: '#F1E2BF',
  paper1: '#DCC596',
  ink: '#2A1A0F',
  verm: '#C2552B',
  edgeRiso: '#2A1A0F',
  edgeMint: '#BFF0E6',
  edgeGold: '#FFE3A3',
  confetti: ['#F1C94B', '#FFF4C8', '#7FC8BC', '#EDE6D6', '#E9C47C'],
  rainbow: ['#FF6BD6', '#FFE36B', '#6BFFD2', '#6B8BFF'],
  print: '#F3EEE2',
  shardBack: '#5A3B0E',
  flashTint: '#FFF8E6',
  white: '#FFFFFF',
  goldLit: '#FFF6D8',
  mote: '#FFF3D0',
};

// 三棱锥尺寸(设计稿 390 宽下的点):底面正三角形边长 W,侧面斜高 L
const PYR = { W: 250, L: 262, D: 900, TILT: -6 };
PYR.R = PYR.W / (2 * Math.sqrt(3));             // 底面内切圆半径 = 面中心到轴
PYR.H = Math.sqrt(PYR.L * PYR.L - PYR.R * PYR.R); // 锥高

function clamp01(v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
function easeInOutBack(t) {
  const c2 = 1.2 * 1.525;
  return t < 0.5 ? (Math.pow(2 * t, 2) * ((c2 + 1) * 2 * t - c2)) / 2
    : (Math.pow(2 * t - 2, 2) * ((c2 + 1) * (t * 2 - 2) + c2) + 2) / 2;
}
function easeInCubic(t) { return t * t * t; }
function easeOutBack(t) { const c1 = 1.4, c3 = c1 + 1; return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2); }
function easeOutCubic(t) { return 1 - Math.pow(1 - t, 3); }

/** 面 i 相对观者转过的角度(-180..180,0 = 正对)。物体整体转 rot 度时,第 i 面在 -i*120 处。 */
function faceRel(i, rot) { return ((((-i * 120 + rot) % 360) + 540) % 360) - 180; }

/** 三棱锥第 i 面投影到屏幕上的三个点:顶点、左底、右底(cx,cy 是锥体中心)。 */
function faceQuad(i, rot, cx, cy, scale) {
  const psi = faceRel(i, rot) * Math.PI / 180;
  const nx = Math.sin(psi), nz = -Math.cos(psi);         // 面外法线(z 朝里为正)
  const dx = Math.cos(psi), dz = Math.sin(psi);          // 底边方向
  const half = PYR.W / 2, by = PYR.H / 2, ty = -PYR.H / 2;
  const pts = [[0, ty, 0], [nx * PYR.R - dx * half, by, nz * PYR.R - dz * half], [nx * PYR.R + dx * half, by, nz * PYR.R + dz * half]];
  const t = PYR.TILT * Math.PI / 180, ct = Math.cos(t), st = Math.sin(t);
  return pts.map(([x, y, z]) => {
    const y2 = y * ct - z * st, z2 = y * st + z * ct;      // 整体微微俯视
    const k = PYR.D / (PYR.D + z2 * scale);
    return [cx + x * scale * k, cy + y2 * scale * k];
  });
}
/** 屏幕上顺时针 = 正面朝外;背面不画。 */
function facing(q) {
  return (q[1][0] - q[0][0]) * (q[2][1] - q[0][1]) - (q[1][1] - q[0][1]) * (q[2][0] - q[0][0]) < 0;
}
/** 纹理三角形 (tw,th) → 屏幕三角形 q 的仿射矩阵 [a,b,c,d,e,f](setTransform 参数顺序)。 */
function affine(tw, th, q) {
  const [p0, p1, p2] = q;
  // 纹理:顶 (tw/2,0)、左 (0,th)、右 (tw,th)
  const a = (p2[0] - p1[0]) / tw, b = (p2[1] - p1[1]) / tw;
  const c = (p1[0] - p0[0] + a * tw / 2) / th, d = (p1[1] - p0[1] + b * tw / 2) / th;
  return [a, b, c, d, p0[0] - a * tw / 2, p0[1] - b * tw / 2];
}

/** 把遮罩(alpha>140 的格心)取成方块格点;mask 是 ImageData.data,w/h 是遮罩尺寸。 */
function gridPoints(mask, w, h, cell, ox, oy) {
  const pts = [];
  for (let y = 0; y < h; y += cell) {
    for (let x = 0; x < w; x += cell) {
      const px = Math.min(w - 1, Math.floor(x + cell / 2)), py = Math.min(h - 1, Math.floor(y + cell / 2));
      if (mask[(py * w + px) * 4 + 3] > 140) pts.push({ x: ox + x, y: oy + y, row: y / h });
    }
  }
  return pts;
}

// ---------- 面的印刷处理:由章节封面生成,不是贴照片 ----------
function grayOf(data, n) {
  const g = new Float32Array(n); let lo = 1, hi = 0;
  for (let i = 0; i < n; i++) { const v = (data[i * 4] * 0.3 + data[i * 4 + 1] * 0.59 + data[i * 4 + 2] * 0.11) / 255; g[i] = v; if (v < lo) lo = v; if (v > hi) hi = v; }
  for (let i = 0; i < n; i++) g[i] = (g[i] - lo) / (hi - lo || 1);
  return g;
}
function sampler(G, W, H) { return (x, y) => G[(y < 0 ? 0 : y >= H ? H - 1 : y) * W + (x < 0 ? 0 : x >= W ? W - 1 : x)]; }

/** 双色丝网印:深墨半调网点 + 朱红套印,轻微错版 */
function paintRiso(ctx, G, W, H) {
  const at = sampler(G, W, H), C = 6;
  const bg = ctx.createLinearGradient(0, 0, W, H); bg.addColorStop(0, PAL.paper0); bg.addColorStop(1, PAL.paper1);
  ctx.fillStyle = bg; ctx.fillRect(0, 0, W, H);
  ctx.globalCompositeOperation = 'multiply';
  for (let pass = 0; pass < 2; pass++) {
    ctx.fillStyle = pass ? PAL.verm : PAL.ink;
    for (let y = 0, row = 0; y < H; y += C * 0.87, row++) {
      for (let x = (row % 2) * C / 2; x < W; x += C) {
        const L = at(x | 0, y | 0);
        const r = pass ? Math.max(0, 0.75 - Math.abs(L - 0.45) * 2.2) * C * 0.42 : Math.pow(1 - L, 1.25) * C * 0.6;
        if (r > 0.35) { ctx.beginPath(); ctx.arc(x + (pass ? 1.4 : 0), y + (pass ? 1 : 0), r, 0, 6.283); ctx.fill(); }
      }
    }
  }
  ctx.globalCompositeOperation = 'source-over';
}
/** 线刻:轮廓线 + 调子排线,薄荷墨印在深青底上 */
function paintEngrave(ctx, G, W, H) {
  const at = sampler(G, W, H), img = ctx.createImageData(W, H), p = img.data;
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const gx = at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1) - at(x - 1, y - 1) - 2 * at(x - 1, y) - at(x - 1, y + 1);
      const gy = at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1) - at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1);
      const m = Math.min(1, Math.sqrt(gx * gx + gy * gy) * 1.6), L = at(x, y);
      const hatch = (y % 4 === 0 && L > 0.35) ? (L - 0.35) * 0.55 : 0;
      const a = Math.max(m > 0.28 ? m : 0, hatch), i = (y * W + x) * 4;
      p[i] = 11 + 180 * a; p[i + 1] = 32 + 208 * a; p[i + 2] = 31 + 199 * a; p[i + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
}
/** 金色浮雕 */
function paintGold(ctx, G, W, H) {
  const at = sampler(G, W, H), img = ctx.createImageData(W, H), p = img.data;
  const stops = [[0, [40, 24, 6]], [0.35, [122, 80, 24]], [0.62, [214, 168, 82]], [0.82, [246, 216, 140]], [1, [255, 246, 214]]];
  const lut = (v) => {
    for (let s = 1; s < stops.length; s++) {
      if (v <= stops[s][0]) { const a = stops[s - 1], b = stops[s], t = (v - a[0]) / (b[0] - a[0]); return [0, 1, 2].map((k) => a[1][k] + (b[1][k] - a[1][k]) * t); }
    }
    return stops[4][1];
  };
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const e = at(x + 2, y + 2) - at(x - 2, y - 2), L = at(x, y);
      const c = lut(clamp01(0.48 + e * 2.4 + (L - 0.5) * 0.3 + (1 - y / H) * 0.08)), i = (y * W + x) * 4;
      p[i] = c[0]; p[i + 1] = c[1]; p[i + 2] = c[2]; p[i + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
}
/** 外框 + 内缩一圈的细三角 + 底部小标签,画进面图里 */
function paintFrame(ctx, W, H, edge, label) {
  ctx.lineWidth = 2.4; ctx.strokeStyle = edge; ctx.globalAlpha = 0.75;
  ctx.beginPath(); ctx.moveTo(W / 2, 1); ctx.lineTo(W - 1, H - 1); ctx.lineTo(1, H - 1); ctx.closePath(); ctx.stroke();
  const inset = 0.11;
  ctx.lineWidth = 1.2; ctx.globalAlpha = 0.55;
  ctx.beginPath(); ctx.moveTo(W / 2, H * inset); ctx.lineTo(W * (1 - inset * 0.9), H * 0.94); ctx.lineTo(W * inset * 0.9, H * 0.94); ctx.closePath(); ctx.stroke();
  if (label) {
    ctx.globalAlpha = 0.85; ctx.fillStyle = edge; ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
    ctx.font = '600 ' + Math.round(W * 0.034) + 'px sans-serif';
    ctx.fillText(label.split('').join(' '), W / 2, H * 0.905);
  }
  ctx.globalAlpha = 1;
}
const STYLES = { riso: { paint: paintRiso, edge: PAL.edgeRiso }, engrave: { paint: paintEngrave, edge: PAL.edgeMint }, gold: { paint: paintGold, edge: PAL.edgeGold } };

module.exports = {
  PAL, PYR, clamp01, easeInOutBack, easeInCubic, easeOutBack, easeOutCubic,
  faceRel, faceQuad, facing, affine, gridPoints, grayOf, paintFrame, STYLES,
};
