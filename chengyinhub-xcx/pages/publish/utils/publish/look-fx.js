// 故事流远景画布上的“风格层”：星尘只调现有浮尘；纸本换成稀疏纸屑；末世画会自己变的天气；夜城画电流。
// 每帧由 index.js 的粒子循环调用（远景那张画布），不 setData、不碰 DOM。参数来自 theme-look.js。

const NEON = { YELLOW_CYAN: ['243,230,0', '36,224,232'], CYAN_PINK: ['45,226,230', '255,59,107'], RED_CYAN: ['255,46,76', '45,226,230'] };
const TONE = { WHITE: null, WARM: [255, 214, 160], COOL: [190, 215, 255] };

/** 现有星尘的修正量：n = 显示比例，speed = 漂移倍数，col = 颜色覆盖（null 沿用场景色）。 */
function dustMod(look) {
  const p = (look && look.params) || {};
  switch (look && look.style) {
    case 'PAPER': return { n: 0.22, speed: 0.5, col: [214, 196, 160] };
    case 'SURVIVAL': return { n: 0, speed: 1, col: null };
    case 'CYBER': return { n: 0.12, speed: 0.6, col: null };
    default: return {
      n: { SPARSE: 0.5, NORMAL: 1, DENSE: 1.6 }[p.density] || 1,
      speed: { SLOW: 1, NORMAL: 1.8, FAST: 3 }[p.drift] || 1,
      col: TONE[p.tone] || null,
    };
  }
}

// ---------- 末世：天气按阶段自己变，振幅与节奏由“天气”参数决定 ----------
const CLIMATE = {
  WINTER: [{ t: 7, wind: 0.4, snow: 0.45 }, { t: 6, wind: 1.8, snow: 0.8, debris: 0.15 }, { t: 8, wind: 3.4, snow: 1, gust: 1 }, { t: 6, wind: -1.3, snow: 0.55 }, { t: 6, wind: 0.2, snow: 0.2 }],
  STORM: [{ t: 6, wind: 0.7, rain: 0.2 }, { t: 7, wind: 2.8, rain: 0.1, debris: 0.9, gust: 1 }, { t: 8, wind: 1.6, rain: 1 }, { t: 5, wind: 0.1, rain: 0.04 }, { t: 7, wind: -3, rain: 0.75, debris: 0.7, gust: 1 }, { t: 6, wind: -1, rain: 0.4 }],
  HEAT: [{ t: 7, wind: 0.3, dust: 0.2 }, { t: 6, wind: 1.9, dust: 0.7, debris: 0.35, gust: 1 }, { t: 8, wind: 3.2, dust: 1, debris: 0.5, gust: 1 }, { t: 5, wind: 0.9, dust: 0.25, rain: 0.4 }, { t: 5, wind: -0.4, dust: 0.12 }],
  BUNKER: [{ t: 7, drip: 0.5, wind: 0.15, dust: 0.25 }, { t: 6, drip: 0.8, wind: 0.2, dust: 0.3 }, { t: 7, drip: 0.35, wind: 0.1, dust: 0.2 }],
};
const VOL = { CALM: { amp: 0.5, len: 1.6 }, SHIFTING: { amp: 1, len: 1 }, VIOLENT: { amp: 1.4, len: 0.6 } };
const KEYS = ['wind', 'rain', 'snow', 'dust', 'debris', 'drip'];

function create(look, W, H) {
  const style = look && look.style;
  if (style === 'SURVIVAL') {
    const p = look.params, phases = CLIMATE[p.climate] || CLIMATE.STORM, vol = VOL[p.volatility] || VOL.SHIFTING;
    const cur = {}; KEYS.forEach((k) => { cur[k] = (phases[0][k] || 0) * vol.amp; });
    const A = (n, f) => Array.from({ length: n }, f);
    return { kind: 'survival', phases, vol, i: 0, until: 0, cur, g: 0, gv: 0, nextG: 0, t: 0,
      R: A(200, () => ({ x: Math.random() * W, y: Math.random() * H, v: 10 + Math.random() * 6, a: 0.12 + Math.random() * 0.2 })),
      S: A(150, () => ({ x: Math.random() * W, y: Math.random() * H, v: 0.5 + Math.random() * 0.9, a: 0.35 + Math.random() * 0.5, s: 0.8 + Math.random() * 1.8, t: Math.random() * 6 })),
      D: A(140, () => ({ x: Math.random() * W, y: Math.random() * H, a: 0.1 + Math.random() * 0.28, s: 0.5 + Math.random() * 1.2, t: Math.random() * 6 })),
      B: A(10, () => ({ on: 0 })), L: A(10, () => ({ on: 0 })),
      P: [0.1, 0.3, 0.58, 0.76, 0.92].map((x) => ({ x: x * W, y: -1, v: 0, wait: 0 })) };
  }
  if (style === 'CYBER') {
    const p = look.params;
    return { kind: 'cyber', col: NEON[p.neon] || NEON.YELLOW_CYAN, rate: { LOW: 0.25, MID: 0.6, HIGH: 1.2 }[p.current] || 0.6, bolts: [], pulses: [], next: 0,
      // 电路：贴着屏幕边缘与几条横线走的折线，电流脉冲沿着它跑
      circuits: [
        [[0, 0.18], [0.22, 0.18], [0.3, 0.24], [0.62, 0.24]], [[1, 0.42], [0.78, 0.42], [0.7, 0.36], [0.4, 0.36]],
        [[0, 0.7], [0.18, 0.7], [0.26, 0.78], [0.66, 0.78], [0.72, 0.84], [1, 0.84]], [[0.08, 1], [0.08, 0.88], [0.14, 0.82]], [[0.9, 0], [0.9, 0.1], [0.84, 0.16]],
      ].map((c) => c.map((pt) => [pt[0] * W, pt[1] * H])) };
  }
  return { kind: 'none' };
}

// 闪电：中点位移出锯齿折线，带一两条分叉
function bolt(x1, y1, x2, y2, rough, depth, out) {
  if (depth === 0) { out.push([x2, y2]); return; }
  const mx = (x1 + x2) / 2 + (Math.random() - 0.5) * rough, my = (y1 + y2) / 2 + (Math.random() - 0.5) * rough;
  bolt(x1, y1, mx, my, rough / 2, depth - 1, out); bolt(mx, my, x2, y2, rough / 2, depth - 1, out);
}

function drawCyber(s, ctx, W, H, now) {
  if (now > s.next) {
    s.next = now + (400 + Math.random() * 1600) / s.rate;
    // 一道电弧：从屏幕边缘某点打到附近另一点，持续 120–260ms
    const side = Math.random() < 0.5, x1 = side ? (Math.random() < 0.5 ? 0 : W) : Math.random() * W, y1 = side ? Math.random() * H : (Math.random() < 0.5 ? 0 : H);
    const x2 = x1 + (W / 2 - x1) * (0.2 + Math.random() * 0.35), y2 = y1 + (H / 2 - y1) * (0.1 + Math.random() * 0.3);
    const pts = [[x1, y1]]; bolt(x1, y1, x2, y2, 60, 5, pts);
    const branch = [pts[8] || pts[pts.length - 1]]; const b0 = branch[0]; bolt(b0[0], b0[1], b0[0] + (Math.random() - 0.5) * 90, b0[1] + (Math.random() - 0.5) * 90, 30, 4, branch);
    s.bolts.push({ pts, branch, t0: now, life: 120 + Math.random() * 140, c: s.col[Math.random() < 0.7 ? 1 : 0] });
    if (Math.random() < 0.7) s.pulses.push({ c: s.circuits[(Math.random() * s.circuits.length) | 0], t0: now, dur: 900 + Math.random() * 700, col: s.col[Math.random() < 0.5 ? 0 : 1] });
  }
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';
  // 电路底线：很淡，常亮
  ctx.globalAlpha = 0.07; ctx.strokeStyle = 'rgb(' + s.col[1] + ')'; ctx.lineWidth = 1;
  s.circuits.forEach((c) => { ctx.beginPath(); c.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1]))); ctx.stroke(); });
  // 电流脉冲：一段亮光沿电路跑过去
  s.pulses = s.pulses.filter((u) => now - u.t0 < u.dur);
  s.pulses.forEach((u) => {
    const segs = []; let total = 0;
    for (let i = 1; i < u.c.length; i++) { const l = Math.hypot(u.c[i][0] - u.c[i - 1][0], u.c[i][1] - u.c[i - 1][1]); segs.push(l); total += l; }
    const head = ((now - u.t0) / u.dur) * (total + 60), tail = head - 60;
    ctx.strokeStyle = 'rgb(' + u.col + ')'; ctx.lineWidth = 1.6; ctx.shadowBlur = 8; ctx.shadowColor = 'rgb(' + u.col + ')'; ctx.globalAlpha = 0.85;
    ctx.beginPath(); let acc = 0, started = false;
    for (let i = 1; i < u.c.length; i++) {
      const a = u.c[i - 1], b = u.c[i], l = segs[i - 1];
      const s0 = Math.max(tail, acc), s1 = Math.min(head, acc + l);
      if (s1 > s0) { const f0 = (s0 - acc) / l, f1 = (s1 - acc) / l;
        const p0 = [a[0] + (b[0] - a[0]) * f0, a[1] + (b[1] - a[1]) * f0], p1 = [a[0] + (b[0] - a[0]) * f1, a[1] + (b[1] - a[1]) * f1];
        if (!started) { ctx.moveTo(p0[0], p0[1]); started = true; } ctx.lineTo(p1[0], p1[1]); }
      acc += l;
    }
    ctx.stroke(); ctx.shadowBlur = 0;
  });
  // 电弧：亮一下就灭，期间抖一次形状
  s.bolts = s.bolts.filter((b) => now - b.t0 < b.life);
  s.bolts.forEach((b) => {
    const k = 1 - (now - b.t0) / b.life;
    [[b.pts, 1.4], [b.branch, 0.8]].forEach((pair) => {
      ctx.strokeStyle = 'rgb(' + b.c + ')'; ctx.lineWidth = pair[1]; ctx.shadowBlur = 12; ctx.shadowColor = 'rgb(' + b.c + ')'; ctx.globalAlpha = 0.9 * k * (Math.random() < 0.15 ? 0.3 : 1);
      ctx.beginPath(); pair[0].forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1]))); ctx.stroke();
    });
    ctx.shadowBlur = 0;
  });
  ctx.globalAlpha = 1;
}

function drawSurvival(s, ctx, W, H, now) {
  if (!s.t) { s.t = now; s.until = now + s.phases[0].t * 1000 * s.vol.len; s.nextG = now + 1500; }
  if (now > s.until) { s.i = (s.i + 1) % s.phases.length; s.until = now + s.phases[s.i].t * 1000 * s.vol.len * (0.85 + Math.random() * 0.3); }
  const T = s.phases[s.i], dt = Math.min(0.1, (now - s.t) / 1000), k = Math.min(1, dt * 0.55 / s.vol.len); s.t = now;
  KEYS.forEach((key) => { s.cur[key] += ((T[key] || 0) * (key === 'wind' ? s.vol.amp : Math.min(1, s.vol.amp)) - s.cur[key]) * k; });
  if (T.gust && now > s.nextG) { s.gv = (0.7 + Math.random() * 0.6) * s.vol.amp; s.nextG = now + (2200 + Math.random() * 3500) * s.vol.len; }
  s.g += (s.gv - s.g) * Math.min(1, dt * 4); s.gv *= Math.pow(0.2, dt);
  const c = s.cur, dir = c.wind < 0 ? -1 : 1, w = c.wind * (1 + s.g * 0.9) + dir * s.g * 0.8 * Math.min(1, Math.abs(c.wind)), aw = Math.abs(w);
  s.w = w;
  ctx.lineCap = 'round';
  // 雨：跟风斜
  const nR = Math.round(Math.min(1, c.rain) * s.R.length); ctx.strokeStyle = 'rgb(206,214,222)'; ctx.lineWidth = 0.8;
  for (let i = 0; i < nR; i++) { const q = s.R[i]; q.x += w * 2.4; q.y += q.v;
    if (q.y > H + 20 || q.x < -90 || q.x > W + 90) { q.y = -20 - Math.random() * 80; q.x = Math.random() * (W + 160) - 80 - w * 30; }
    ctx.globalAlpha = q.a * Math.min(1, c.rain * 1.6); ctx.beginPath(); ctx.moveTo(q.x, q.y); ctx.lineTo(q.x - w * 3.6, q.y - q.v * 1.5); ctx.stroke(); }
  // 雪：大风时拉成短线
  const nS = Math.round(Math.min(1, c.snow) * s.S.length); ctx.fillStyle = ctx.strokeStyle = 'rgb(235,244,255)';
  for (let i = 0; i < nS; i++) { const q = s.S[i]; q.t += 0.02; const vx = w * 1.15 + Math.sin(q.t) * 0.35, vy = q.v * (1 + aw * 0.15);
    q.x += vx; q.y += vy; if (q.y > H + 10) { q.y = -10; q.x = Math.random() * W; } if (q.x > W + 20) q.x = -20; if (q.x < -20) q.x = W + 20;
    ctx.globalAlpha = q.a;
    if (Math.abs(vx) > 2.4) { ctx.lineWidth = q.s * 1.2; ctx.beginPath(); ctx.moveTo(q.x, q.y); ctx.lineTo(q.x - vx * 2, q.y - vy * 2); ctx.stroke(); }
    else { ctx.beginPath(); ctx.arc(q.x, q.y, q.s, 0, 6.2832); ctx.fill(); } }
  // 沙尘 / 地堡里灯下的浮尘
  const nD = Math.round(Math.min(1, c.dust) * s.D.length); ctx.fillStyle = 'rgb(230,196,140)';
  for (let i = 0; i < nD; i++) { const q = s.D[i]; q.t += 0.015; q.x += w * 2.1 + 0.15 + Math.sin(q.t) * 0.2; q.y += Math.sin(q.t * 0.7) * 0.25;
    if (q.x > W + 20) { q.x = -20; q.y = Math.random() * H; } if (q.x < -20) { q.x = W + 20; q.y = Math.random() * H; }
    ctx.globalAlpha = q.a; ctx.beginPath(); ctx.arc(q.x, q.y, q.s, 0, 6.2832); ctx.fill(); }
  // 风线
  if (aw > 1.1 && Math.random() < (aw - 1) * 0.05) { const l = s.L.find((x) => !x.on); if (l) Object.assign(l, { on: 1, len: 80 + Math.random() * 160, y: 60 + Math.random() * (H - 120), v: w * (7 + Math.random() * 4), a: (0.035 + Math.random() * 0.05) * Math.min(1, (aw - 1) / 1.6), bend: (Math.random() - 0.5) * 14 }, { x: w > 0 ? -250 : W + 10 }); }
  ctx.strokeStyle = 'rgb(220,226,232)'; ctx.lineWidth = 0.8;
  s.L.forEach((l) => { if (!l.on) return; l.x += l.v; if (l.x > W + 20 || l.x < -l.len - 260) { l.on = 0; return; }
    ctx.globalAlpha = l.a; ctx.beginPath(); ctx.moveTo(l.x, l.y); ctx.quadraticCurveTo(l.x + l.len / 2, l.y + l.bend, l.x + l.len, l.y + l.bend * 0.3); ctx.stroke(); });
  // 碎片
  if (c.debris > 0.05 && Math.random() < c.debris * 0.045 * (1 + s.g)) { const b = s.B.find((x) => !x.on); if (b) Object.assign(b, { on: 1, x: w >= 0 ? -20 : W + 20, y: Math.random() * H * 0.9, w: 4 + Math.random() * 7, h: 2 + Math.random() * 4, r: Math.random() * 6, vr: (Math.random() - 0.5) * 0.3, a: 0.25 + Math.random() * 0.35, t: Math.random() * 6 }); }
  ctx.fillStyle = 'rgb(200,206,212)';
  s.B.forEach((b) => { if (!b.on) return; b.t += 0.05; b.x += w * 4.4 + (w >= 0 ? 1 : -1); b.y += Math.sin(b.t) * 1.4 + 0.35; b.r += b.vr * (aw * 0.5 + 0.3);
    if (b.x > W + 30 || b.x < -30 || b.y > H + 20) { b.on = 0; return; }
    ctx.save(); ctx.translate(b.x, b.y); ctx.rotate(b.r); ctx.globalAlpha = b.a; ctx.fillRect(-b.w / 2, -b.h / 2, b.w, b.h * Math.abs(Math.cos(b.t))); ctx.restore(); });
  // 地堡滴水
  if (c.drip > 0.05) { ctx.strokeStyle = 'rgb(220,200,190)'; ctx.lineWidth = 1.4;
    s.P.forEach((d) => { if (d.y < 0) { if (now > d.wait && Math.random() < c.drip * 0.04) { d.y = 46; d.v = 0; } return; }
      d.v += 0.3; d.y += d.v; ctx.globalAlpha = 0.5; ctx.beginPath(); ctx.moveTo(d.x, d.y - 4 - d.v * 0.8); ctx.lineTo(d.x, d.y); ctx.stroke();
      if (d.y > H - 46) { d.y = -1; d.wait = now + (1400 + Math.random() * 3200) / (c.drip + 0.2); } }); }
  ctx.globalAlpha = 1;
}

/** 每帧画一次（远景画布）。返回当前风速，给背景照片做风推的位移用。 */
function draw(state, ctx, W, H, now) {
  if (!state) return 0;
  if (state.kind === 'cyber') drawCyber(state, ctx, W, H, now);
  else if (state.kind === 'survival') { drawSurvival(state, ctx, W, H, now); return state.w || 0; }
  return 0;
}

module.exports = { dustMod, create, draw, CLIMATE, NEON };
