// 故事流画布上的“风格层”：星尘画几颗彩色小方块 / 三角(漂浮的东西只在星尘里)；末世画会自己变的天气；夜城画电流；纸本只有纸纹理。
// 每帧由 index.js 的粒子循环调用：draw 画在字后面那张(远景),drawFront 画在字前面那张(近景,让字处在环境里)。
// 不 setData、不碰 DOM;雷暴打闪时只记 flashAt,由页面决定要不要把字照亮。参数来自 theme-look.js。

const NEON = { YELLOW_CYAN: ['243,230,0', '36,224,232'], CYAN_PINK: ['45,226,230', '255,59,107'], RED_CYAN: ['255,46,76', '45,226,230'] };
const TONE = { WHITE: null, WARM: [255, 214, 160], COOL: [190, 215, 255] };
// 星尘小星的颜色:光色 = 白时各色混着,暖 / 冷时偏一边;白底用同色系的深色
const STAR_COL = { WHITE: ['255,255,255', '255,214,160', '190,215,255', '255,182,206', '170,240,210', '214,196,255'], WARM: ['255,214,160', '255,190,150', '255,236,200', '255,182,206'], COOL: ['190,215,255', '170,240,230', '214,196,255', '235,244,255'] };
const STAR_INK = { WHITE: ['40,44,56', '176,110,40', '50,90,170', '180,60,110', '30,130,100', '100,70,170'], WARM: ['176,110,40', '170,80,50', '120,84,40', '180,60,110'], COOL: ['50,90,170', '30,120,130', '100,70,170', '40,44,56'] };

/** 浅底(主题配色选了白)时粒子画深色,否则白底上看不见(2026-10-30 业主)。 */
function isLight(look) { return !!look && look.palette === 'WHITE'; }
const INK = [24, 26, 32];

/** 现有星尘的修正量：n = 显示比例，speed = 漂移倍数，col = 颜色覆盖（null 沿用场景色）；still = 不跟场景的风走。 */
function dustMod(look) {
  const m = dustBase(look);
  if (isLight(look)) m.col = INK;
  return m;
}
function dustBase(look) {
  const p = (look && look.params) || {};
  // 老主题没配过风格(legacy):原来的浮尘一点不变
  if (!look || look.legacy) return { n: 1, speed: 1, col: null };
  switch (look.style) {
    // 2026-10-30 业主:漂浮的东西只在星尘里出现,其它风格不撒浮尘
    case 'PAPER': case 'SURVIVAL': case 'CYBER': return { n: 0, speed: 1, col: null };
    // 星尘:场景浮尘与场景光都收掉(背景干净),小星由 pieces 画(字后几颗、字前几颗)
    default: return { n: 0, speed: 1, col: null, clean: true };
  }
}

// ---------- 末世：天气按阶段自己变，振幅与节奏由“变化”参数决定 ----------
// 2026-10-30 业主:天气四种 —— 晴 / 雨 / 雷暴 / 沙尘暴;bolt = 这一段会打闪
const CLIMATE = {
  CLEAR: [{ t: 8, wind: 0.25, dust: 0.06 }, { t: 7, wind: 0.7, dust: 0.1 }, { t: 7, wind: 0.15 }],
  RAIN: [{ t: 7, wind: 0.4, rain: 0.55 }, { t: 7, wind: 1.2, rain: 0.9 }, { t: 6, wind: 0.2, rain: 0.35 }, { t: 7, wind: -0.8, rain: 0.75 }],
  STORM: [{ t: 6, wind: 0.9, rain: 0.6, bolt: 1 }, { t: 7, wind: 2.8, rain: 0.8, debris: 0.6, gust: 1, bolt: 1 }, { t: 8, wind: 1.6, rain: 1, bolt: 1 }, { t: 6, wind: -2.4, rain: 0.85, debris: 0.4, gust: 1, bolt: 1 }],
  SAND: [{ t: 7, wind: 0.6, dust: 0.45 }, { t: 6, wind: 1.9, dust: 0.8, debris: 0.35, gust: 1 }, { t: 8, wind: 3.2, dust: 1, debris: 0.5, gust: 1 }, { t: 6, wind: 1, dust: 0.55 }],
};
const VOL = { CALM: { amp: 0.5, len: 1.6 }, SHIFTING: { amp: 1, len: 1 }, VIOLENT: { amp: 1.4, len: 0.6 } };
const KEYS = ['wind', 'rain', 'dust', 'debris'];

function create(look, W, H) {
  const style = look && look.style;
  if (style === 'SURVIVAL') {
    const p = look.params, phases = CLIMATE[p.climate] || CLIMATE.RAIN, vol = VOL[p.volatility] || VOL.SHIFTING;
    const cur = {}; KEYS.forEach((k) => { cur[k] = (phases[0][k] || 0) * vol.amp; });
    const A = (n, f) => Array.from({ length: n }, f);
    return { kind: 'survival', light: isLight(look), phases, vol, i: 0, until: 0, cur, g: 0, gv: 0, nextG: 0, t: 0, nextBolt: 0, strike: null, flashAt: 0,
      R: A(200, () => ({ x: Math.random() * W, y: Math.random() * H, v: 10 + Math.random() * 6, a: 0.12 + Math.random() * 0.2 })),
      D: A(140, () => ({ x: Math.random() * W, y: Math.random() * H, a: 0.1 + Math.random() * 0.28, s: 0.5 + Math.random() * 1.2, t: Math.random() * 6 })),
      B: A(10, () => ({ on: 0 })), L: A(10, () => ({ on: 0 })),
      // 字前面:玻璃上的水珠(雨 / 雷暴)、贴脸飞过的沙(沙尘暴)、几缕近处的雨
      G: A(16, () => ({ x: Math.random() * W, y: 40 + Math.random() * (H - 80), r: 3 + Math.random() * 6, born: 0, run: 0 })),
      F: A(24, () => ({ x: Math.random() * W, y: Math.random() * H, v: 22 + Math.random() * 10, a: 0.06 + Math.random() * 0.1 })),
      Z: A(7, (v, i) => ({ x: Math.random() * W, y: (i + 0.5) * H / 7, r: 120 + Math.random() * 120, a: 0.5 + Math.random() * 0.5 })) };
  }
  if (style === 'STARDUST' && !look.legacy) {
    // 星尘:近处几颗大而虚的星从字前面慢慢飘过,另有几块漂浮物(远的在字后,近的在字前)
    const A = (n, f) => Array.from({ length: n }, f);
    const p = look.params || {}, k = { SPARSE: 0.6, NORMAL: 1, DENSE: 1.6 }[p.density] || 1, sp = { SLOW: 1, NORMAL: 1.8, FAST: 3 }[p.drift] || 1;
    // 2026-10-30 业主:背景只放几颗小星,用方块 / 三角这种简单形状;漂浮的小东西颜色要多样,都要小
    const pal = (isLight(look) ? STAR_INK : STAR_COL)[p.tone] || (isLight(look) ? STAR_INK : STAR_COL).WHITE;
    const piece = (front) => ({ x: Math.random() * W, y: Math.random() * H, s: front ? 2.6 + Math.random() * 1.8 : 1.2 + Math.random() * 1.6, shape: Math.random() < 0.5 ? 0 : 1,
      c: pal[(Math.random() * pal.length) | 0], a: front ? 0.55 + Math.random() * 0.3 : 0.35 + Math.random() * 0.45, tw: Math.random() < 0.4 ? 0.6 + Math.random() * 1.4 : 0, ph: Math.random() * 6.28,
      vx: (Math.random() - 0.5) * 0.06 * sp, vy: -(0.02 + Math.random() * 0.05) * sp * (front ? 1.6 : 1), rot: Math.random() * 6.28, vr: (Math.random() - 0.5) * 0.006 });
    return { kind: 'stardust', back: A(Math.round(34 * k), () => piece(false)), front: A(Math.round(7 * k), () => piece(true)) };
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
  const nR = Math.round(Math.min(1, c.rain) * s.R.length); ctx.strokeStyle = s.light ? 'rgb(60,70,84)' : 'rgb(206,214,222)'; ctx.lineWidth = 0.8;
  for (let i = 0; i < nR; i++) { const q = s.R[i]; q.x += w * 2.4; q.y += q.v;
    if (q.y > H + 20 || q.x < -90 || q.x > W + 90) { q.y = -20 - Math.random() * 80; q.x = Math.random() * (W + 160) - 80 - w * 30; }
    ctx.globalAlpha = q.a * Math.min(1, c.rain * 1.6); ctx.beginPath(); ctx.moveTo(q.x, q.y); ctx.lineTo(q.x - w * 3.6, q.y - q.v * 1.5); ctx.stroke(); }
  // 沙尘
  const nD = Math.round(Math.min(1, c.dust) * s.D.length); ctx.fillStyle = s.light ? 'rgb(120,84,40)' : 'rgb(230,196,140)';
  for (let i = 0; i < nD; i++) { const q = s.D[i]; q.t += 0.015; q.x += w * 2.1 + 0.15 + Math.sin(q.t) * 0.2; q.y += Math.sin(q.t * 0.7) * 0.25;
    if (q.x > W + 20) { q.x = -20; q.y = Math.random() * H; } if (q.x < -20) { q.x = W + 20; q.y = Math.random() * H; }
    ctx.globalAlpha = q.a; ctx.beginPath(); ctx.arc(q.x, q.y, q.s, 0, 6.2832); ctx.fill(); }
  // 风线
  if (aw > 1.1 && Math.random() < (aw - 1) * 0.05) { const l = s.L.find((x) => !x.on); if (l) Object.assign(l, { on: 1, len: 80 + Math.random() * 160, y: 60 + Math.random() * (H - 120), v: w * (7 + Math.random() * 4), a: (0.035 + Math.random() * 0.05) * Math.min(1, (aw - 1) / 1.6), bend: (Math.random() - 0.5) * 14 }, { x: w > 0 ? -250 : W + 10 }); }
  ctx.strokeStyle = s.light ? 'rgb(70,78,90)' : 'rgb(220,226,232)'; ctx.lineWidth = 0.8;
  s.L.forEach((l) => { if (!l.on) return; l.x += l.v; if (l.x > W + 20 || l.x < -l.len - 260) { l.on = 0; return; }
    ctx.globalAlpha = l.a; ctx.beginPath(); ctx.moveTo(l.x, l.y); ctx.quadraticCurveTo(l.x + l.len / 2, l.y + l.bend, l.x + l.len, l.y + l.bend * 0.3); ctx.stroke(); });
  // 碎片
  if (c.debris > 0.05 && Math.random() < c.debris * 0.045 * (1 + s.g)) { const b = s.B.find((x) => !x.on); if (b) Object.assign(b, { on: 1, x: w >= 0 ? -20 : W + 20, y: Math.random() * H * 0.9, w: 4 + Math.random() * 7, h: 2 + Math.random() * 4, r: Math.random() * 6, vr: (Math.random() - 0.5) * 0.3, a: 0.25 + Math.random() * 0.35, t: Math.random() * 6 }); }
  ctx.fillStyle = s.light ? 'rgb(70,78,90)' : 'rgb(200,206,212)';
  s.B.forEach((b) => { if (!b.on) return; b.t += 0.05; b.x += w * 4.4 + (w >= 0 ? 1 : -1); b.y += Math.sin(b.t) * 1.4 + 0.35; b.r += b.vr * (aw * 0.5 + 0.3);
    if (b.x > W + 30 || b.x < -30 || b.y > H + 20) { b.on = 0; return; }
    ctx.save(); ctx.translate(b.x, b.y); ctx.rotate(b.r); ctx.globalAlpha = b.a; ctx.fillRect(-b.w / 2, -b.h / 2, b.w, b.h * Math.abs(Math.cos(b.t))); ctx.restore(); });
  // 雷暴:打闪 —— 一道锯齿光从天上劈下来,整屏亮一下;页面读 flashAt 把字照亮
  if (T.bolt && now > s.nextBolt) {
    if (s.nextBolt) { const x = W * (0.15 + Math.random() * 0.7), pts = [[x, 0]]; bolt(x, 0, x + (Math.random() - 0.5) * W * 0.4, H * (0.45 + Math.random() * 0.2), 90, 6, pts); s.strike = { pts, t0: now }; s.flashAt = now; }
    s.nextBolt = now + (3500 + Math.random() * 5500) * s.vol.len;
  }
  if (s.strike && now - s.strike.t0 < 900) {
    const k = 1 - (now - s.strike.t0) / 900;
    ctx.globalAlpha = 0.5 * k * k; ctx.fillStyle = s.light ? 'rgb(255,255,255)' : 'rgb(225,232,255)'; ctx.fillRect(0, 0, W, H);
    if (now - s.strike.t0 < 220) { ctx.globalAlpha = 0.95; ctx.strokeStyle = s.light ? 'rgb(40,46,60)' : 'rgb(240,244,255)'; ctx.lineWidth = 2; ctx.shadowBlur = 16; ctx.shadowColor = 'rgb(200,212,255)';
      ctx.beginPath(); s.strike.pts.forEach((pt, i) => (i ? ctx.lineTo(pt[0], pt[1]) : ctx.moveTo(pt[0], pt[1]))); ctx.stroke(); ctx.shadowBlur = 0; }
  }
  ctx.globalAlpha = 1;
}

// 星尘:一颗方块或三角的小星,慢慢漂、慢慢转,一部分会闪
function pieces(list, ctx, W, H, now) {
  const t = now / 1000;
  for (const q of list) {
    q.x += q.vx; q.y += q.vy; q.rot += q.vr;
    if (q.y < -8) { q.y = H + 8; q.x = Math.random() * W; } if (q.x < -8) q.x = W + 8; if (q.x > W + 8) q.x = -8;
    ctx.globalAlpha = q.a * (q.tw ? 0.45 + 0.55 * Math.max(0, Math.sin(t * q.tw + q.ph)) : 1);
    ctx.fillStyle = 'rgb(' + q.c + ')';
    ctx.save(); ctx.translate(q.x, q.y); ctx.rotate(q.rot);
    if (q.shape === 0) ctx.fillRect(-q.s / 2, -q.s / 2, q.s, q.s);
    else { ctx.beginPath(); ctx.moveTo(0, -q.s * 0.6); ctx.lineTo(q.s * 0.55, q.s * 0.4); ctx.lineTo(-q.s * 0.55, q.s * 0.4); ctx.closePath(); ctx.fill(); }
    ctx.restore();
  }
  ctx.globalAlpha = 1;
}

/** 字前面那张画布:让字处在环境里 —— 星尘的近星与近处漂浮物、玻璃上的水珠、贴脸的沙、近处的雨。 */
function drawFront(state, ctx, W, H, now) {
  if (!state) return;
  if (state.kind === 'stardust') {
    pieces(state.front, ctx, W, H, now);
    return;
  }
  if (state.kind !== 'survival') return;
  const c = state.cur, light = state.light;
  // 近处的雨:粗、虚、快
  if (c.rain > 0.2) {
    ctx.strokeStyle = light ? 'rgb(60,70,84)' : 'rgb(220,228,236)'; ctx.lineWidth = 1.8; ctx.lineCap = 'round';
    state.F.forEach((q) => { q.y += q.v; q.x += (state.w || 0) * 3; if (q.y > H + 60) { q.y = -60; q.x = Math.random() * W; }
      ctx.globalAlpha = q.a * Math.min(1, c.rain); ctx.beginPath(); ctx.moveTo(q.x, q.y); ctx.lineTo(q.x - (state.w || 0) * 5, q.y - 46); ctx.stroke(); });
  }
  // 玻璃上的水珠:雨越大越多;大的偶尔顺着玻璃滑下去,身后留一道湿痕。
  // 水珠是透明的 —— 只画上沿一道细高光、下沿一道暗边,中间几乎不填色(不要白点)
  const nG = Math.round(Math.min(1, c.rain * 1.1) * state.G.length);
  const hi = light ? '255,255,255' : '235,242,250', lo = light ? '40,48,60' : '0,0,0';
  state.G.forEach((d, i) => {
    if (i >= nG) { d.born = 0; return; }
    if (!d.born) { d.born = now; d.run = 0; d.trail = 0; d.x = Math.random() * W; d.y = 40 + Math.random() * (H - 80); }
    const a = Math.min(1, (now - d.born) / 1200);
    if (!d.run && d.r > 6 && Math.random() < 0.002) { d.run = now; d.y0 = d.y; }
    if (d.run) { d.y += 1.2 + (now - d.run) / 500; d.x += Math.sin((now - d.run) / 180) * 0.25; if (d.y > H + 20) d.born = 0; }
    const rx = d.r * (d.run ? 0.8 : 1), ry = d.r * (d.run ? 1.35 : 1.1);
    if (d.run) {
      const g = ctx.createLinearGradient(d.x, d.y0, d.x, d.y);
      g.addColorStop(0, 'rgba(' + hi + ',0)'); g.addColorStop(1, 'rgba(' + hi + ',' + 0.18 * a + ')');
      ctx.globalAlpha = 1; ctx.fillStyle = g; ctx.fillRect(d.x - rx * 0.35, d.y0, rx * 0.7, d.y - d.y0);
    }
    ctx.globalAlpha = a;
    ctx.fillStyle = 'rgba(' + lo + ',' + (light ? 0.05 : 0.12) + ')'; ctx.beginPath(); ctx.ellipse(d.x, d.y, rx, ry, 0, 0, 6.2832); ctx.fill();
    ctx.lineWidth = Math.max(0.8, d.r * 0.16);
    ctx.strokeStyle = 'rgba(' + lo + ',' + (light ? 0.28 : 0.45) + ')'; ctx.beginPath(); ctx.ellipse(d.x, d.y, rx, ry, 0, 0.25, 2.9); ctx.stroke();
    ctx.strokeStyle = 'rgba(' + hi + ',' + (light ? 0.9 : 0.6) + ')'; ctx.beginPath(); ctx.ellipse(d.x, d.y, rx * 0.82, ry * 0.82, 0, 3.6, 5.4); ctx.stroke();
    ctx.fillStyle = 'rgba(' + hi + ',' + (light ? 0.95 : 0.75) + ')'; ctx.beginPath(); ctx.arc(d.x - rx * 0.35, d.y - ry * 0.4, Math.max(0.6, d.r * 0.14), 0, 6.2832); ctx.fill();
  });
  // 沙尘暴:大团沙雾从字前面卷过去,字时隐时现
  if (c.dust > 0.3) {
    const k = Math.min(1, (c.dust - 0.3) / 0.6), w = state.w || 0.5;
    state.Z.forEach((z) => { z.x += 0.6 + w * 1.6; if (z.x - z.r > W) { z.x = -z.r; z.y = Math.random() * H; }
      const g = ctx.createRadialGradient(z.x, z.y, 0, z.x, z.y, z.r);
      const sc = light ? '214,186,140' : '176,128,70';
      g.addColorStop(0, 'rgba(' + sc + ',' + (0.78 * z.a * k) + ')'); g.addColorStop(1, 'rgba(' + sc + ',0)');
      ctx.globalAlpha = 1; ctx.fillStyle = g; ctx.fillRect(z.x - z.r, z.y - z.r, z.r * 2, z.r * 2); });
  }
  ctx.globalAlpha = 1;
}

/** 每帧画一次（远景画布）。返回当前风速，给背景照片做风推的位移用。 */
function draw(state, ctx, W, H, now) {
  if (!state) return 0;
  if (state.kind === 'cyber') drawCyber(state, ctx, W, H, now);
  else if (state.kind === 'stardust') pieces(state.back, ctx, W, H, now);
  else if (state.kind === 'survival') { drawSurvival(state, ctx, W, H, now); return state.w || 0; }
  return 0;
}

module.exports = { dustMod, create, draw, drawFront, isLight, CLIMATE, NEON };
