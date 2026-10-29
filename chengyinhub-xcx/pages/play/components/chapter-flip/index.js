// 章节翻转过场(2026-10-28 业主定稿原型 claude.ai/artifact/KKQJxLNAL3JSb5pYDF1xtb)。
// gate:上一章做完时先由星点拼出方块章节号 + 本章成绩,再升起三棱锥转到这一章那面;没有上一章就直接出三棱锥。
// finale:文字全部隐去 → 三棱锥越转越快、停在金色面 → 炸成碎片 + 银色方块波 → 方块拼成银色城瘾标志 → 才出主题名、合计与按钮。
const E = require('./engine');

const AW = 360, AH = 378;                 // 面图分辨率(与 250×262 的面同比例)
const NOISE = '░▒▓█▚▞■□';
const LOGO_SRC = '/images/cy_logo_mark.png';

function chars(text) { return Array.from(String(text || '')); }

Component({
  properties: {
    mode: { type: String, value: 'gate' },          // gate | finale
    faces: { type: Array, value: [] },              // [{ cover, style:'riso'|'engrave'|'gold', label, photos:[] }],三面
    endInfo: { type: Object, value: null },         // 上一章完成:{ number:'01', kicker, title, stats:[{v,k}] }
    info: { type: Object, value: {} },              // 这一章 / 通关:{ kicker, title, desc, stats:[{v,k}] }
    reducedMotion: { type: Boolean, value: false },
  },
  data: { copy: null, quiet: false, cta: false, out: false },
  lifetimes: {
    ready() {
      this.createSelectorQuery().select('#cf').fields({ node: true, size: true }).exec((res) => {
        const item = res && res[0];
        if (this._gone || !item || !item.node) return;
        const info = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()) || {};
        const dpr = Math.min(2, Number(info.pixelRatio) || 2);
        const W = item.width, H = item.height, cv = item.node;
        cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
        this._cv = cv; this._ctx = cv.getContext('2d'); this._dpr = dpr;
        this._W = W; this._H = H; this._S = W / 390;
        this._cx = W / 2; this._cy = H * 0.333;
        this._t0 = Date.now();
        this._initWorld();
        this._loadFaces();
        if (this.data.mode === 'finale') this._finale(); else this._gate();
        if (!this.data.reducedMotion) this._loop();
        else this._paint(Date.now());
      });
    },
    detached() { this._gone = true; this._clear(); },
  },
  methods: {
    noop() {},
    _clear() {
      (this._timers || []).forEach(clearTimeout); this._timers = [];
      clearInterval(this._decodeTimer); this._decodeTimer = 0;
      if (this._cv && this._raf) this._cv.cancelAnimationFrame(this._raf);
      this._raf = 0;
    },
    _at(fn, ms) { (this._timers = this._timers || []).push(setTimeout(() => { if (!this._gone) fn(); }, this.data.reducedMotion ? 0 : ms)); },

    // ---------- 世界状态 ----------
    _initWorld() {
      const W = this._W, H = this._H;
      this._stars = Array.from({ length: 120 }, () => ({ x: Math.random() * W, y: 40 + Math.random() * (H - 80), r: 0.5 + Math.random() * 1.1, p: Math.random() * 6.28, s: 0.6 + Math.random() * 1.6 }));
      this._tiles = []; this._sparks = []; this._shards = []; this._waves = []; this._confetti = [];
      this._rot = 0; this._turn = null; this._scene = { a: 0, k: 0.86, from: 0, t0: 0, show: false };
      this._shineT = 0; this._flash = 0; this._flare = 0; this._shakeT = 0; this._confettiUntil = 0;
      this._art = []; this._sprites = [];
    },
    _off(w, h) { return wx.createOffscreenCanvas({ type: '2d', width: w, height: h }); },

    /** 面图:章节封面 → 灰度 → 印刷处理 → 外框/内框/标签。封面缺失或加载失败时用渐变灰度,面仍是设计过的样子。 */
    _loadFaces() {
      (this.data.faces || []).slice(0, 3).forEach((f, i) => {
        const style = E.STYLES[f.style] ? f.style : 'riso';
        const render = (img) => {
          if (this._gone) return;
          const oc = this._off(AW, AH), g = oc.getContext('2d');
          let G;
          if (img) {
            const s = Math.max(AW / img.width, AH / img.height);
            g.drawImage(img, (AW - img.width * s) / 2, (AH - img.height * s) / 2, img.width * s, img.height * s);
            G = E.grayOf(g.getImageData(0, 0, AW, AH).data, AW * AH);
          } else {
            G = new Float32Array(AW * AH); for (let p = 0; p < G.length; p++) G[p] = 0.25 + 0.6 * ((p / AW) / AH);
          }
          E.STYLES[style].paint(g, G, AW, AH);
          E.paintFrame(g, AW, AH, E.STYLES[style].edge, f.label || '');
          this._art[i] = { canvas: oc, style };
        };
        const host = this._off(4, 4);
        if (!f.cover) { render(null); return; }
        const img = host.createImage();
        img.onload = () => render(img);
        img.onerror = () => render(null);
        img.src = f.cover;
        // 线刻面上漂浮的老照片:这一章节点自己的照片,最多 3 张
        if (style === 'engrave') (f.photos || []).slice(0, 3).forEach((url, k) => {
          const ph = host.createImage();
          ph.onload = () => { (this._sprites[i] = this._sprites[i] || []).push({ img: ph, k }); };
          ph.src = url;
        });
      });
    },

    // ---------- 流程 ----------
    _gate() {
      const end = this.data.endInfo;
      if (end && end.number) {
        this._stage = 'end';
        this._rot = 0;
        this._at(() => this._assemble({ text: end.number }), 250);
        this._decode(end, 700);
        this._at(() => this._toFlip(true), 3000);
      } else {
        this._rot = 120;
        this._toFlip(false);
      }
    },
    _toFlip(fromEnd) {
      if (this._stage === 'flip' || this._gone) return;
      this._stage = 'flip';
      if (fromEnd) {
        this._scatter();
        this._at(() => this._showScene(), 140);
        this._at(() => this._turnTo(120, 640, E.easeInOutBack), 520);
      } else {
        this._showScene();
      }
      this._decode(this.data.info, fromEnd ? 700 : 300);
      this._at(() => this._continue(), fromEnd ? 3600 : 3000);
    },
    _finale() {
      this._stage = 'finale';
      if (this.data.reducedMotion) {      // 减弱动态:不转不炸,直接落到终态
        this._assembleLogo(true);
        this.setData({ cta: true });
        this._decode(this.data.info, 0);
        return;
      }
      this.setData({ quiet: true });
      this._rot = 120;
      this._showScene();
      this._at(() => this._turnTo(this._rot + 1080, 1500, E.easeInCubic, () => {
        this._turnTo(1320, 560, E.easeOutBack, () => {
          this._rot = 240; this._flare = Date.now();
          this._at(() => this._explode(), 420);
        });
      }), 420);
    },
    _explode() {
      const q = E.faceQuad(2, 240, this._cx, this._cy, this._S);
      this._scene.show = false; this._scene.a = 0;
      this._flash = Date.now();
      this._makeShards(q);
      for (let w = 0; w < 3; w++) this._waves.push({ x: this._cx, y: this._cy, life: -w * 7, max: 44 });
      for (let i = 0; i < 90; i++) { const a = Math.random() * 6.283, s = 2 + Math.random() * 7; this._sparks.push({ x: this._cx, y: this._cy, vx: Math.cos(a) * s, vy: Math.sin(a) * s - 2, life: 0, max: 50 + Math.random() * 70, g: 1 }); }
      this._burstConfetti(70);
      this._confettiUntil = Date.now() + 2600;
      this._at(() => this._assembleLogo(false), 700);
      this._at(() => { this.setData({ quiet: false, cta: true }); this._decode(this.data.info, 0); }, 3000);
    },
    _continue() {
      if (this._left || this.data.mode !== 'gate') return;
      this._left = true;
      this._clear();
      this.setData({ out: true });
      this._at(() => this.triggerEvent('continue'), 320);
    },
    /** 点任意处:章节完成页 → 立刻翻到下一章;下一章 → 进正文。通关过场中途不响应,等按钮出来。 */
    onTap() {
      if (this.data.mode !== 'gate') return;
      if (this._stage === 'end') { (this._timers || []).forEach(clearTimeout); this._timers = []; this._toFlip(true); return; }
      this._continue();
    },
    onSave() { this.triggerEvent('save'); },
    onClose() { this.triggerEvent('continue'); },

    // ---------- 文字逐字解码(三棱锥转动时,标题、说明、数据从左到右一格格换上) ----------
    _decode(info, delay) {
      if (!info) return;
      const fields = [['k', info.kicker, 22], ['t', info.title, 38], ['d', info.desc, 14]];
      const stats = (info.stats || []).filter((s) => s && s.v !== '' && s.v != null);
      const plan = { k: [], t: [], d: [], stats: stats.map(() => ({ v: [], k: [] })) };
      const jobs = [];
      fields.forEach(([key, text, step], f) => chars(text).forEach((c, i) => jobs.push({ set: (v) => { plan[key][i] = v; }, c, at: delay + f * 60 + i * step })));
      stats.forEach((s, j) => {
        chars(s.v).forEach((c, i) => jobs.push({ set: (v) => { plan.stats[j].v[i] = v; }, c, at: delay + 280 + j * 90 + i * 40 }));
        chars(s.k).forEach((c, i) => jobs.push({ set: (v) => { plan.stats[j].k[i] = v; }, c, at: delay + 320 + j * 90 + i * 30 }));
      });
      clearInterval(this._decodeTimer);
      const start = Date.now(), reduced = this.data.reducedMotion;
      const step = () => {
        const t = reduced ? 1e9 : Date.now() - start;
        let pending = 0;
        jobs.forEach((j) => {
          if (t < j.at) { j.set({ c: '', n: 1 }); pending++; }
          else if (t < j.at + 150 && j.c !== ' ') { j.set({ c: NOISE[Math.random() * NOISE.length | 0], n: 1 }); pending++; }
          else j.set({ c: j.c, n: 0 });
        });
        this.setData({ copy: plan });
        if (!pending) { clearInterval(this._decodeTimer); this._decodeTimer = 0; }
      };
      step();
      if (!reduced) this._decodeTimer = setInterval(step, 45);
    },

    // ---------- 方块(参考 2:星点飞拢锁进方格) ----------
    _mask(w, h, draw) {
      const oc = this._off(w, h), g = oc.getContext('2d');
      g.fillStyle = '#fff';   /* ds-ok 取样遮罩,不上屏 */
      draw(g, w, h);
      return g.getImageData(0, 0, w, h).data;
    },
    _assemble({ text, silver, cell, tile, w, h, top, draw }) {
      const S = this._S;
      w = w || 330; h = h || 250; cell = cell || 9;
      const mask = this._mask(w, h, draw || ((g) => {
        g.font = '800 238px sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(text, w / 2, h / 2 + 10);
      }));
      const ox = this._cx - (w * S) / 2, oy = (top != null ? top : this._cy - h / 2 - 20) ;
      const pts = E.gridPoints(mask, w, h, cell, 0, 0).map((p) => ({ x: ox + p.x * S, y: oy + p.y * S, row: p.row }));
      const now = Date.now(), M = silver ? E.PAL.silver : E.PAL.gold;
      while (this._tiles.length < pts.length) { const st = this._stars[Math.random() * this._stars.length | 0]; this._tiles.push({ x: st.x, y: st.y, vx: 0, vy: 0, sz: 1.4 }); }
      this._tiles.length = pts.length;
      this._tiles.forEach((t, i) => {
        const p = pts[i], shade = (silver ? 1.04 - p.row * 0.34 : 1 - p.row * 0.22) + (Math.random() - 0.5) * (silver ? 0.16 : 0.12);
        t.loose = 0; t.tx = p.x; t.ty = p.y; t.tsz = (tile || 7.4) * S; t.silver = !!silver;
        t.c = 'rgb(' + Math.min(255, M[0] * shade | 0) + ',' + Math.min(255, M[1] * shade | 0) + ',' + Math.min(255, M[2] * shade * (silver ? 1.02 : 0.9) | 0) + ')';
        t.delay = Math.random() * 380 + Math.abs(p.x - this._cx) * 0.9; t.born = now;
      });
      this._squashT = now;
      this._shineT = silver ? now + 1300 : 0;
    },
    _assembleLogo(instant) {
      if (!this._logo) {
        const img = this._off(4, 4).createImage();
        img.onload = () => { this._logo = img; this._assembleLogo(instant); };
        img.src = LOGO_SRC;
        return;
      }
      const logo = this._logo;
      this._assemble({ silver: true, cell: 5, tile: 4.3, w: 280, h: 280, top: this._cy - 140 * this._S - 10,
        draw: (g, w, h) => g.drawImage(logo, 10, 10, w - 20, h - 20) });
      if (instant) this._tiles.forEach((t) => { t.x = t.tx; t.y = t.ty; t.sz = t.tsz; t.delay = 0; });
    },
    _scatter() {
      const W = this._W, H = this._H;
      this._tiles.forEach((t) => {
        const a = Math.atan2(t.y - this._cy, t.x - this._cx) + (Math.random() - 0.5) * 0.9, sp = 6 + Math.random() * 9;
        t.vx = Math.cos(a) * sp; t.vy = Math.sin(a) * sp - 3; t.tx = Math.random() * W; t.ty = 40 + Math.random() * (H - 80);
        t.tsz = 1.2; t.delay = 0; t.born = Date.now(); t.loose = 1;
      });
      for (let i = 0; i < 26; i++) this._sparks.push({ x: this._cx + (Math.random() - 0.5) * 220 * this._S, y: this._cy + (Math.random() - 0.5) * 200 * this._S, life: 0, max: 50 + Math.random() * 50 });
    },

    // ---------- 三棱锥 ----------
    _showScene() { Object.assign(this._scene, { show: true, t0: Date.now(), from: this._scene.a }); },
    _turnTo(to, dur, ease, done) {
      if (this.data.reducedMotion) { this._rot = to % 360; if (done) done(); return; }
      this._turn = { from: this._rot, to, t0: Date.now(), dur, ease, done };
    },
    _drawPyramid(ctx, now) {
      const sc = this._scene;
      const k = this.data.reducedMotion ? 1 : E.clamp01((now - sc.t0) / 600);
      sc.a = sc.show ? Math.max(sc.from, E.easeOutCubic(k)) : 0;
      if (sc.a <= 0.01) return;
      const scale = this._S * (0.86 + 0.14 * E.easeOutBack(k));
      let cx = this._cx, cy = this._cy + (1 - k) * 16 * this._S;
      if (this._shakeT && now - this._shakeT < 420) { const s = (1 - (now - this._shakeT) / 420) * 5 * this._S; cx += (Math.random() - 0.5) * s * 2; cy += (Math.random() - 0.5) * s; }
      const idle = this._turn || this.data.reducedMotion ? 0 : Math.sin(now / 1400) * 2.2;
      const rot = this._rot + idle, dpr = this._dpr;
      ctx.globalAlpha = sc.a;
      for (let i = 0; i < 3; i++) {
        const art = this._art[i];
        const q = E.faceQuad(i, rot, cx, cy, scale);
        if (!E.facing(q)) continue;
        const rel = E.faceRel(i, rot);
        ctx.save();
        ctx.beginPath(); ctx.moveTo(q[0][0], q[0][1]); ctx.lineTo(q[1][0], q[1][1]); ctx.lineTo(q[2][0], q[2][1]); ctx.closePath(); ctx.clip();
        const m = E.affine(AW, AH, q);
        ctx.setTransform(m[0] * dpr, m[1] * dpr, m[2] * dpr, m[3] * dpr, m[4] * dpr, m[5] * dpr);
        // 景深:底图逆着转向漂,前景随着转向漂
        const drift = -rel / 60 * 5 * (AW / 250);
        if (art) ctx.drawImage(art.canvas, drift - AW * 0.03, -AH * 0.02, AW * 1.06, AH * 1.04);
        else { ctx.fillStyle = 'rgba(255,255,255,.08)'; ctx.fillRect(0, 0, AW, AH); }
        this._drawLayers(ctx, i, art ? art.style : '', rel, now);
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        // 背光一侧压暗;正对时一道斜光扫过;侧边扫过视线时亮一下彩虹边
        ctx.fillStyle = 'rgba(0,0,0,' + (Math.min(1, Math.abs(rel) / 90) * 0.62).toFixed(3) + ')';
        ctx.fill();
        const sx = q[1][0] + (q[2][0] - q[1][0]) * (0.5 - rel / 70);
        const sheen = ctx.createLinearGradient(sx - 60 * this._S, q[0][1], sx + 60 * this._S, q[1][1]);
        sheen.addColorStop(0, 'rgba(255,255,255,0)'); sheen.addColorStop(0.5, 'rgba(255,255,255,.18)'); sheen.addColorStop(1, 'rgba(255,255,255,0)');
        ctx.fillStyle = sheen; ctx.fill();
        if (i === 2 && this._flare) { const f = E.clamp01((now - this._flare) / 350); ctx.fillStyle = 'rgba(255,248,230,' + (0.85 * f).toFixed(3) + ')'; ctx.fill(); }
        ctx.restore();
        const gl = Math.max(0, 1 - Math.abs(Math.abs(rel) - 40) / 30);
        if (gl > 0.02) {
          const e0 = q[0], e1 = rel > 0 ? q[1] : q[2];
          const rb = ctx.createLinearGradient(e0[0], e0[1], e1[0], e1[1]);
          E.PAL.rainbow.forEach((c, n) => rb.addColorStop(n / 3, c));
          ctx.save(); ctx.globalAlpha = sc.a * gl; ctx.strokeStyle = rb; ctx.lineWidth = 3 * this._S;
          ctx.beginPath(); ctx.moveTo(e0[0], e0[1]); ctx.lineTo(e1[0], e1[1]); ctx.stroke(); ctx.restore();
        }
      }
      ctx.globalAlpha = 1;
    },
    /** 面内前景(纹理坐标,250×262 设计稿换算到 AW×AH):丝网面一条虚线路线,线刻面漂浮的老照片,金面上升的光点 */
    _drawLayers(ctx, i, style, rel, now) {
      const u = AW / 250, par = (d) => rel / 60 * d * u;
      if (style === 'riso') {
        ctx.save(); ctx.translate(par(10), 0);
        ctx.setLineDash([2 * u, 5 * u]); ctx.lineWidth = 1.6 * u; ctx.strokeStyle = E.PAL.ink; ctx.globalAlpha *= 0.7;
        ctx.beginPath(); ctx.moveTo(40 * u, 210 * u); ctx.bezierCurveTo(90 * u, 150 * u, 150 * u, 190 * u, 215 * u, 120 * u); ctx.stroke();
        ctx.setLineDash([]); ctx.fillStyle = E.PAL.verm; ctx.beginPath(); ctx.arc(215 * u, 120 * u, 3.5 * u, 0, 6.283); ctx.fill();
        ctx.restore();
      } else if (style === 'engrave') {
        const spots = [[62, 150, 40, 46, -8, 14], [140, 158, 34, 40, 4, 24], [100, 92, 36, 42, -3, 36]];
        (this._sprites[i] || []).forEach((s) => {
          const [x, y, w, h, r, d] = spots[s.k], bob = Math.sin(now / 900 + s.k * 1.7) * 4.5 * u;
          ctx.save(); ctx.translate((x + w / 2) * u + par(d), (y + h / 2) * u + bob); ctx.rotate((r + Math.sin(now / 1100 + s.k) * 5) * Math.PI / 180);
          ctx.fillStyle = E.PAL.print; ctx.fillRect(-w / 2 * u - 3 * u, -h / 2 * u - 3 * u, w * u + 6 * u, h * u + 12 * u);
          const im = s.img, side = Math.min(im.width / w, im.height / h);
          ctx.drawImage(im, (im.width - w * side) / 2, (im.height - h * side) / 2, w * side, h * side, -w / 2 * u, -h / 2 * u, w * u, h * u);
          ctx.restore();
        });
      } else if (style === 'gold') {
        ctx.save(); ctx.translate(par(30), 0); ctx.fillStyle = E.PAL.mote;
        for (let n = 0; n < 12; n++) {
          const p = ((now / 1000) / (4 + (n % 4)) + n * 0.137) % 1;
          ctx.globalAlpha = (p < 0.15 ? p / 0.15 : 1 - p) * 0.9;
          ctx.beginPath(); ctx.arc((70 + (n * 37) % 110) * u, (242 - p * 190) * u, 1.6 * u, 0, 6.283); ctx.fill();
        }
        ctx.restore();
      }
    },

    // ---------- 通关:碎片、方块波、彩纸 ----------
    _makeShards(q) {
      const art = this._art[2];
      let tris = [q];
      const jit = (a, b) => [(a[0] + b[0]) / 2 + (Math.random() - 0.5) * 8 * this._S, (a[1] + b[1]) / 2 + (Math.random() - 0.5) * 8 * this._S];
      for (let d = 0; d < 4; d++) {
        const nx = [];
        tris.forEach((t) => { const ab = jit(t[0], t[1]), bc = jit(t[1], t[2]), ca = jit(t[2], t[0]); nx.push([t[0], ab, ca], [ab, t[1], bc], [ca, bc, t[2]], [ab, bc, ca]); });
        tris = nx;
      }
      const m = E.affine(AW, AH, q);
      this._shards = tris.map((t) => {
        const c = [(t[0][0] + t[1][0] + t[2][0]) / 3, (t[0][1] + t[1][1] + t[2][1]) / 3];
        const a = Math.atan2(c[1] - this._cy, c[0] - this._cx), dist = Math.hypot(c[0] - this._cx, c[1] - this._cy), sp = 3 + Math.random() * 7 + dist * 0.02;
        return { c, t, x: c[0], y: c[1], vx: Math.cos(a) * sp, vy: Math.sin(a) * sp - 3.5 - Math.random() * 3, r: 0, vr: (Math.random() - 0.5) * 0.22, f: 0, vf: 0.05 + Math.random() * 0.16, life: 0, max: 85 + Math.random() * 50, m, art };
      });
    },
    _burstConfetti(n) {
      for (let i = 0; i < n; i++) this._confetti.push({ x: this._cx + (Math.random() - 0.5) * 60, y: this._cy, vx: (Math.random() - 0.5) * 12, vy: -6 - Math.random() * 8, r: Math.random() * 6, vr: (Math.random() - 0.5) * 0.3, s: 4 + Math.random() * 5, c: E.PAL.confetti[i % 5], f: Math.random() * 6, life: 0 });
    },

    // ---------- 主循环 ----------
    _loop() {
      if (this._gone) return;
      this._raf = this._cv.requestAnimationFrame(() => { this._paint(Date.now()); this._loop(); });
    },
    _paint(now) {
      const ctx = this._ctx, W = this._W, H = this._H, dpr = this._dpr;
      if (!ctx) return;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, W, H);
      if (this._turn) {
        const tr = this._turn, p = E.clamp01((now - tr.t0) / tr.dur);
        this._rot = tr.from + (tr.to - tr.from) * tr.ease(p);
        if (p >= 1) { this._turn = null; this._rot = tr.to % 360; if (tr.done) tr.done(); }
      }
      this._stars.forEach((s) => { ctx.globalAlpha = 0.18 + 0.32 * (0.5 + 0.5 * Math.sin(now / 1000 * s.s + s.p)); ctx.fillStyle = E.PAL.star; ctx.fillRect(s.x, s.y, s.r, s.r); });
      this._drawTiles(ctx, now);
      this._drawPyramid(ctx, now);
      this._drawShards(ctx);
      this._drawWaves(ctx);
      this._drawParticles(ctx, now);
      if (this._flash) { const f = 1 - (now - this._flash) / 700; if (f > 0) { ctx.globalAlpha = 0.9 * f; ctx.fillStyle = E.PAL.flashTint; ctx.fillRect(0, 0, W, H); } else this._flash = 0; }
      ctx.globalAlpha = 1;
    },
    _drawTiles(ctx, now) {
      const sq = this._squashT ? Math.sin(E.clamp01((now - this._squashT) / 900) * Math.PI) * 0.05 : 0;
      const band = this._shineT ? (now - this._shineT) * 0.5 - 40 : -1e9;
      if (this._shineT && band > 520 * this._S) this._shineT = now + 900;
      this._tiles.forEach((t) => {
        if (now - t.born < t.delay) { ctx.globalAlpha = 0.4; ctx.fillStyle = E.PAL.dust; ctx.fillRect(t.x, t.y, t.sz, t.sz); return; }
        if (this.data.reducedMotion) { t.x = t.tx; t.y = t.ty; t.sz = t.tsz; }
        else {
          const damp = t.loose ? 0.93 : 0.82;
          t.vx = (t.vx + (t.tx - t.x) * 0.045) * damp; t.vy = (t.vy + (t.ty - t.y) * 0.045) * damp;
          t.x += t.vx; t.y += t.vy; t.sz += (t.tsz - t.sz) * 0.12;
        }
        const y = t.y + (t.loose ? 0 : (t.y - this._cy - 120 * this._S) * sq);
        const lit = !t.loose && Math.abs((t.x - 60 * this._S) + (t.y - 150 * this._S) * 0.6 - band) < 26 * this._S;
        ctx.globalAlpha = t.loose ? Math.max(0.35, Math.min(1, t.sz / 3)) : 1;
        ctx.fillStyle = t.loose ? E.PAL.dust : (lit ? (t.silver ? E.PAL.white : E.PAL.goldLit) : t.c);
        ctx.fillRect(t.x - t.sz / 2, y - t.sz / 2, t.sz, t.sz);
        if (!t.loose && t.sz > 4) { ctx.globalAlpha = 0.22; ctx.fillStyle = E.PAL.white; ctx.fillRect(t.x - t.sz / 2, y - t.sz / 2, t.sz, 1); }
      });
      // 拼好的图形周围零星闪光
      if (this._tiles.length && !this._tiles[0].loose && Math.random() < 0.05) this._sparks.push({ x: this._cx + (Math.random() - 0.5) * 300 * this._S, y: this._cy + (Math.random() - 0.5) * 240 * this._S, life: 0, max: 40 + Math.random() * 30 });
    },
    _drawShards(ctx) {
      const dpr = this._dpr;
      this._shards = this._shards.filter((s) => {
        s.life++; if (s.life > s.max) return false;
        s.x += s.vx; s.y += s.vy; s.vy += 0.16; s.vx *= 0.985; s.r += s.vr; s.f += s.vf;
        const fl = Math.cos(s.f);
        ctx.save();
        ctx.globalAlpha = s.life < s.max - 30 ? 1 : (s.max - s.life) / 30;
        ctx.translate(s.x, s.y); ctx.rotate(s.r); ctx.scale(Math.max(0.08, Math.abs(fl)), 1); ctx.translate(-s.c[0], -s.c[1]);
        ctx.beginPath(); ctx.moveTo(s.t[0][0], s.t[0][1]); ctx.lineTo(s.t[1][0], s.t[1][1]); ctx.lineTo(s.t[2][0], s.t[2][1]); ctx.closePath();
        ctx.save(); ctx.clip();
        if (fl > 0 && s.art) { ctx.transform(s.m[0], s.m[1], s.m[2], s.m[3], s.m[4], s.m[5]); ctx.drawImage(s.art.canvas, 0, 0, AW, AH); }
        else { ctx.fillStyle = E.PAL.shardBack; ctx.fill(); }
        ctx.restore();
        ctx.strokeStyle = 'rgba(255,243,208,' + (0.5 + 0.5 * Math.abs(fl)).toFixed(2) + ')'; ctx.lineWidth = 1; ctx.stroke();
        ctx.restore();
        return true;
      });
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    },
    /** 方块波:与章节数字同一套方格,菱形向外扩,银色 */
    _drawWaves(ctx) {
      const S = this._S;
      this._waves = this._waves.filter((r) => {
        r.life++; if (r.life < 0) return true; if (r.life > r.max) return false;
        const q = r.life / r.max, e = E.easeOutCubic(q), R = (20 + e * 300) * S, step = 11 * S, sz = 7 * (1 - q * 0.6) * S;
        ctx.fillStyle = E.PAL.waveTile;
        for (let s = -R; s <= R; s += step) {
          [[s, -R], [s, R], [-R, s], [R, s]].forEach((p) => {
            if (Math.random() < 0.18) return;
            ctx.globalAlpha = (1 - q) * (0.55 + Math.random() * 0.45);
            const c = Math.SQRT1_2, x = (p[0] - p[1]) * c, y = (p[0] + p[1]) * c;
            ctx.fillRect(r.x + x - sz / 2, r.y + y - sz / 2, sz, sz);
          });
        }
        return true;
      });
    },
    _drawParticles(ctx, now) {
      const H = this._H;
      if (now < this._confettiUntil && Math.random() < 0.6) {
        for (let i = 0; i < 2; i++) this._confetti.push({ x: Math.random() * this._W, y: -10, vx: (Math.random() - 0.5) * 1.2, vy: 1.5 + Math.random() * 2, r: Math.random() * 6, vr: (Math.random() - 0.5) * 0.2, s: 3 + Math.random() * 4, c: E.PAL.confetti[i % 5], f: Math.random() * 6, life: 0 });
      }
      this._confetti = this._confetti.filter((c) => {
        c.life++; c.x += c.vx + Math.sin(c.life / 14 + c.f) * 0.5; c.y += c.vy; c.vy = Math.min(c.vy + 0.12, 2.6); c.vx *= 0.99; c.r += c.vr;
        if (c.y > H + 20) return false;
        ctx.save(); ctx.globalAlpha = Math.min(1, (H - c.y) / 120); ctx.translate(c.x, c.y); ctx.rotate(c.r); ctx.scale(Math.cos(c.life / 9 + c.f), 1);
        ctx.fillStyle = c.c; ctx.beginPath(); ctx.moveTo(0, -c.s); ctx.lineTo(c.s * 0.87, c.s * 0.5); ctx.lineTo(-c.s * 0.87, c.s * 0.5); ctx.closePath(); ctx.fill(); ctx.restore();
        return true;
      });
      this._sparks = this._sparks.filter((p) => {
        p.life++; if (p.vx != null) { p.x += p.vx; p.y += p.vy; p.vy += 0.06; p.vx *= 0.985; }
        const q = p.life / p.max, a = q < 0.2 ? q / 0.2 : 1 - (q - 0.2) / 0.8;
        if (a <= 0) return false;
        ctx.globalAlpha = a; ctx.fillStyle = p.g ? E.PAL.ember : E.PAL.spark;
        const r = p.g ? 2 : 2.6;
        ctx.fillRect(p.x - r / 2, p.y - r / 2, r, r);
        if (!p.g) { ctx.fillRect(p.x - 0.5, p.y - r * 1.6, 1, r * 3.2); ctx.fillRect(p.x - r * 1.6, p.y - 0.5, r * 3.2, 1); }
        return true;
      });
    },
  },
});
