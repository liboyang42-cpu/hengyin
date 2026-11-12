// 章节翻转过场。
// gate:上一章做完 → 整屏「第 N 章完成」(本章照片渐入底色、圆点章节号、成功插图、三格数据、底部「下一站」),
//   点「下一站」(或停留后)照片与文字化成细粉尘往右上吹散,随后进入章节开始。
//   章节开始 = 动效 v2 时刻 1,由 components/chapter-moment 负责(DOM + animate(),规格 proto/motion-v2);这里只管完成屏的画布。
// finale:通关 = 动效 v2 时刻 3,同样由 chapter-moment 负责(粒子汇聚 → 闪光 → 勋章升起 → 已通关 → 读数 → 保存 · 分享 · 查看结算)。
//   原来的三棱锥 / 碎片 / 银色城瘾标志 / 彩纸与标题残影已整体移除,只留一个主角:勋章。
const E = require('./engine');
const LC = require('../../utils/look-colors.js');

const END_HOLD_MS = 5200;                 // r2-finish:章节完成屏停多久自己溶解(点「下一站」可提前)
const START_AUTO_MS = 5600;               // 2026-09-24 业主:章节门不必每章都点,停一下自己进;动效 v2 序列 2.8s 播完后再停 2.8s,「读下去」可提前
const DUST_MS = 1300;                     // l-play:细粉尘吹散时长(refs2/chapter-end-dissolve)
const COVER_H = 330;                      // 完成屏顶部照片高度(390 宽稿 px,与 .cf__cover 同值)
const STAT_ROLL_MS = 900;                 // 三格数据滚上来
const DOT = { cell: 11, r: 4.6, w: 330, h: 260, font: '800 236px sans-serif' };   // l-icons:圆点章节号(原型 dots.js chapter)
// x-fix2 L-3:离屏画布字形取样拿不到点(开发者工具 / 旧基础库的 OffscreenCanvas 不画字或 getImageData 失败)时,
// 章节号退回这套内置 5×7 点阵数字;否则屏上只剩章节印两圈橙点 = 验收看到的「一个糊掉的橙色圆环」。
const DIGITS = { 0: '01110100011001110101110011000101110', 1: '00100011000010000100001000010001110', 2: '01110100010000100010001000100011111',
  3: '11110000010000101110000010000111110', 4: '00010001100101010010111110001000010', 5: '11111100001111000001000011000101110',
  6: '00110010001000011110100011000101110', 7: '11111000010001000100010000100001000', 8: '01110100011000101110100011000101110',
  9: '01110100011000101111000010001001100' };
/** 内置点阵数字 → 与 gridPoints 同形的点({x,y,row}),在 w×h 的格子里居中,单元 = cell */
function digitPoints(text, w, h, cell) {
  const ds = String(text || '').split('').filter((c) => DIGITS[c]);
  if (!ds.length) return [];
  const cols = Math.floor(w / cell), rows = Math.floor(h / cell), span = ds.length * 6 - 1;
  const k = Math.max(1, Math.min(Math.floor(cols / span), Math.floor(rows / 7)));
  const x0 = Math.floor((cols - span * k) / 2), y0 = Math.floor((rows - 7 * k) / 2), out = [];
  ds.forEach((d, n) => {
    for (let r = 0; r < 7 * k; r++) for (let c = 0; c < 5 * k; c++) {
      if (DIGITS[d][Math.floor(r / k) * 5 + Math.floor(c / k)] !== '1') continue;
      const y = (y0 + r) * cell;
      out.push({ x: (x0 + n * 6 * k + c) * cell, y, row: y / h });
    }
  });
  return out;
}

/** 数据格怎么滚:纯整数从 0 滚;mm:ss 按秒滚;a/b 只滚 a;别的(例如「—」)不滚,直接写。 */
function statStart(v) { return statAt(v, 0); }
function statAt(v, e) {
  let m;
  if ((m = /^(\d+)$/.exec(v))) return String(Math.round(Number(m[1]) * e));
  if ((m = /^(\d+):(\d{2})$/.exec(v))) { const sec = Math.round((Number(m[1]) * 60 + Number(m[2])) * e); return String(Math.floor(sec / 60)).padStart(2, '0') + ':' + String(sec % 60).padStart(2, '0'); }
  if ((m = /^(\d+)\/(\d+)$/.exec(v))) return Math.round(Number(m[1]) * e) + '/' + m[2];
  return v;
}

Component({
  properties: {
    mode: { type: String, value: 'gate' },          // gate | finale
    faces: { type: Array, value: [] },              // [{ cover, style:'riso'|'engrave'|'gold', label, photos:[] }],三面
    endInfo: { type: Object, value: null },         // 上一章完成:{ number:'01', kicker, title, stats:[{v,k}] }
    info: { type: Object, value: {} },              // 这一章 / 通关:{ kicker, title, desc, stats:[{v,k}] }
    reducedMotion: { type: Boolean, value: false },
    look: { type: Object, value: null },            // l-icons:{ style, palette, legacy },圆点章节号 / 章节印的颜色
  },
  data: { out: false, endCard: null, endIn: false, endOut: false, endStats: [], start: false },
  lifetimes: {
    ready() {
      if (this.data.mode === 'finale') return;   // 通关全在 chapter-moment,不要画布
      this.createSelectorQuery().select('#cf').fields({ node: true, size: true }).exec((res) => {
        const item = res && res[0];
        if (this._gone || !item || !item.node) return;
        const info = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()) || {};
        const dpr = Math.min(2, Number(info.pixelRatio) || 2);
        const W = item.width, H = item.height, cv = item.node;
        cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
        this._cv = cv; this._ctx = cv.getContext('2d'); this._dpr = dpr;
        this._W = W; this._H = H; this._S = W / 390;
        this._safeB = info.safeArea && info.screenHeight ? Math.max(0, info.screenHeight - info.safeArea.bottom) : 0;
        this._cx = W / 2; this._cy = H * 0.333;
        this._t0 = Date.now();
        this._initWorld();
        this._gate();
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
      clearInterval(this._statTimer); this._statTimer = 0;
      if (this._cv && this._raf) this._cv.cancelAnimationFrame(this._raf);
      this._raf = 0;
    },
    _at(fn, ms) { (this._timers = this._timers || []).push(setTimeout(() => { if (!this._gone) fn(); }, this.data.reducedMotion ? 0 : ms)); },

    // ---------- 世界状态 ----------
    _initWorld() {
      const W = this._W, H = this._H;
      this._stars = Array.from({ length: 120 }, () => ({ x: Math.random() * W, y: 40 + Math.random() * (H - 80), r: 0.5 + Math.random() * 1.1, p: Math.random() * 6.28, s: 0.6 + Math.random() * 1.6 }));
      this._tiles = []; this._sparks = [];
      this._fine = []; this._covers = [];
      this._dotShineT = 0;
    },
    _off(w, h) { return wx.createOffscreenCanvas({ type: '2d', width: w, height: h }); },

    // ---------- 流程 ----------
    _gate() {
      const end = this.data.endInfo;
      if (end && end.number) {
        // 上一章完成 = 整屏(照片 + 方块章节号 + 成功插图 + 三格数据 + 下一站),停一会儿(或点「下一站」)后化成粉尘,再浮现下一章开场。
        this._stage = 'end';
        const rows = (end.rows || []).slice(0, 3);
        this.setData({
          endCard: { kicker: end.kicker || '', title: end.title || '', cover: end.cover || '', next: end.next || '' },
          endStats: rows.map((r) => ({ k: r.k, v: String(r.v), shown: this.data.reducedMotion ? String(r.v) : statStart(String(r.v)) })),
        });
        this._at(() => this.setData({ endIn: true }), 40);
        if (this._ctx) this._assemble({ text: end.number, top: 96 * this._S, dots: true });
        this._at(() => this._rollStats(), 600);
        // 停留时长不随减弱动态归零:完成屏要看得到;减弱动态只去掉溶解动画本身。
        (this._timers = this._timers || []).push(setTimeout(() => { if (!this._gone) this._dissolve(); }, END_HOLD_MS));
      } else {
        this._toStart(false);
      }
    },
    /** 章节开始屏(动效 v2 时刻 1):交给 chapter-moment 播;这里只管挂上去、停一下自己进。画布到此为止,不再画。 */
    _toStart(fromEnd) {
      if (this._stage === 'start' || this._gone) return;
      this._stage = 'start';
      this._fxOff = true;
      this.setData({ start: true });
      // 自己进的计时不随减弱动态归零:终态要看得到
      (this._timers = this._timers || []).push(setTimeout(() => { if (!this._gone) this._continue(); }, fromEnd ? START_AUTO_MS + 300 : START_AUTO_MS));
    },
    /** 三格数据滚上来(只滚拿得到的真读数;mm:ss 按秒滚,a/b 只滚 a;其余直接写)。 */
    _rollStats() {
      const stats = this.data.endStats || [];
      if (this.data.reducedMotion || !stats.length) return;
      const t0 = Date.now();
      clearInterval(this._statTimer);
      this._statTimer = setInterval(() => {
        if (this._gone) { clearInterval(this._statTimer); return; }
        const p = E.clamp01((Date.now() - t0) / STAT_ROLL_MS), e = E.easeOutCubic(p);
        this.setData({ endStats: stats.map((s) => Object.assign({}, s, { shown: statAt(s.v, e) })) });
        if (p >= 1) { clearInterval(this._statTimer); this._statTimer = 0; }
      }, 40);
    },
    /** 「读下去」/ 动画播完后点任意处:整屏淡出,交回正文(通关过场不走这里)。 */
    _continue() {
      if (this._left || this.data.mode !== 'gate') return;
      this._left = true;
      (this._timers || []).forEach(clearTimeout); this._timers = [];
      this.setData({ out: true });
      (this._timers = this._timers || []).push(setTimeout(() => { if (!this._gone) this.triggerEvent('continue'); }, this.data.reducedMotion ? 0 : 420));
    },
    onMomentGo() { this._continue(); },
    /** 点任意处:章节完成屏 → 立刻吹散并浮现下一章开场。开场屏的点按由 chapter-moment 接(第一下跳到终态,之后再点进正文)。 */
    onTap() {
      if (this.data.mode !== 'gate') return;
      if (this._stage === 'end') { (this._timers || []).forEach(clearTimeout); this._timers = []; this._dissolve(); return; }
      if (this._stage === 'dissolve' || this._stage === 'start') return;
      this._continue();
    },
    /** 完成屏化成细粉尘:照片按像素取色成 1–2px 的粉尘,从右上角开始、带湍流往右上吹散;文字同步吹走;方块炸回星点。减弱动态时直接切。 */
    _dissolve() {
      if (this._stage !== 'end' || this._gone) return;
      this._stage = 'dissolve';
      clearInterval(this._statTimer); this._statTimer = 0;
      const go = () => { this._stage = 'end-done'; this.setData({ endCard: null, endOut: false, endStats: [] }); this._toStart(true); };
      if (this.data.reducedMotion) { go(); return; }
      this._spawnFineDust();
      this._scatter();
      this.setData({ endOut: true });
      this._at(go, DUST_MS);
    },
    /** 粉尘:照片缩到 cols×rows 取色(没有照片时用底色上的灰),每格一粒,延迟按「右上先走」排;每粒 1–2px,带正弦湍流,边飞边淡边变小。 */
    _spawnFineDust() {
      const W = this._W || 390, S = this._S || 1, h = COVER_H * S, img = (this._covers || [])[0];
      const level = Number(((typeof wx !== 'undefined' && wx.getDeviceInfo) ? wx.getDeviceInfo() : {}).benchmarkLevel);
      const cols = level > 0 && level < 20 ? 36 : 56, rows = Math.max(8, Math.round(cols * h / W));
      let px = null;
      if (img) {
        try {
          const oc = this._off(cols, rows), g = oc.getContext('2d');
          const sc = Math.max(cols / img.width, rows / img.height);
          g.drawImage(img, (cols - img.width * sc) / 2, (rows - img.height * sc) / 2, img.width * sc, img.height * sc);
          px = g.getImageData(0, 0, cols, rows).data;
        } catch (e) { px = null; }
      }
      const cw = W / cols, ch = h / rows, now = Date.now(), P = [];
      for (let j = 0; j < rows; j++) for (let i = 0; i < cols; i++) {
        if (Math.random() < 0.35) continue;
        const q = (j * cols + i) * 4;
        const col = px ? 'rgb(' + px[q] + ',' + px[q + 1] + ',' + px[q + 2] + ')' : E.PAL.dissolve[(i + j) % E.PAL.dissolve.length];
        P.push({ x: i * cw + Math.random() * cw, y: j * ch + Math.random() * ch, c: col, seed: Math.random() * 6.28, sp: 0.6 + Math.random() * 1.4,
          born: now + (1 - i / cols) * 420 + (j / rows) * 300 + Math.random() * 160, s: (0.9 + Math.random() * 1.3) * S });
      }
      this._fine = P;
    },
    _drawFineDust(ctx, now) {
      if (!this._fine.length) return;
      this._fine = this._fine.filter((p) => {
        const t = (now - p.born) / DUST_MS;
        if (t < 0) { ctx.globalAlpha = 1; ctx.fillStyle = p.c; ctx.fillRect(p.x, p.y, p.s, p.s); return true; }
        if (t > 1) return false;
        const e = 1 - Math.pow(1 - t, 2), el = now - p.born;
        const wob = Math.sin(p.seed + el / 180) * 6 * t * this._S;
        const dx = (26 + p.sp * 60) * e * this._S + wob, dy = -(14 + p.sp * 40) * e * this._S + Math.cos(p.seed * 2 + el / 220) * 5 * t * this._S;
        ctx.globalAlpha = Math.max(0, 1 - t * 1.05); ctx.fillStyle = p.c;
        const s = p.s * (1 - t * 0.5);
        ctx.fillRect(p.x + dx, p.y + dy, s, s);
        return true;
      });
      ctx.globalAlpha = 1;
    },
    onSave() { this.triggerEvent('save'); },
    onClose() { this.triggerEvent('continue'); },

    // ---------- 方块(参考 2:星点飞拢锁进方格) ----------
    _mask(w, h, draw) {
      const oc = this._off(w, h), g = oc.getContext('2d');
      g.fillStyle = '#fff';   /* ds-ok 取样遮罩,不上屏 */
      draw(g, w, h);
      return g.getImageData(0, 0, w, h).data;
    },
    _assemble({ text, cell, tile, w, h, top, draw, dots }) {
      const S = this._S;
      if (dots) { w = w || DOT.w; h = h || DOT.h; cell = cell || DOT.cell; tile = tile || DOT.r * 2; }
      w = w || 330; h = h || 250; cell = cell || 9;
      let mask = null;
      try {
        mask = this._mask(w, h, draw || ((g) => {
          g.font = dots ? DOT.font : '800 238px sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(text, w / 2, h / 2 + (dots ? h * 0.04 : 10));
        }));
      } catch (e) { mask = null; }
      const ox = this._cx - (w * S) / 2, oy = (top != null ? top : this._cy - h / 2 - 20) ;
      let grid = mask ? E.gridPoints(mask, w, h, cell, 0, 0) : [];
      if (!grid.length && dots) grid = digitPoints(text, w, h, cell);   // x-fix2 L-3
      const pts = grid.map((p) => ({ x: ox + p.x * S, y: oy + p.y * S, row: p.row }));
      const now = Date.now(), M = E.PAL.gold, lk = dots ? this._colors() : null;
      while (this._tiles.length < pts.length) { const st = this._stars[Math.random() * this._stars.length | 0]; this._tiles.push({ x: st.x, y: st.y, vx: 0, vy: 0, sz: 1.4 }); }
      this._tiles.length = pts.length;
      this._tiles.forEach((t, i) => {
        const p = pts[i], shade = dots ? 1 - p.row * 0.25 + (Math.random() - 0.5) * 0.1 : 1 - p.row * 0.22 + (Math.random() - 0.5) * 0.12;
        t.loose = 0; t.tx = p.x; t.ty = p.y; t.tsz = (tile || 7.4) * S; t.dot = !!dots;
        if (dots) { t.c = lk.a; t.a = Math.min(1, shade); }
        else { t.c = 'rgb(' + Math.min(255, M[0] * shade | 0) + ',' + Math.min(255, M[1] * shade | 0) + ',' + Math.min(255, M[2] * shade * 0.9 | 0) + ')'; t.a = 1; }
        t.delay = dots ? Math.abs(p.x - this._cx) * 1.6 + Math.random() * 300 : Math.random() * 380 + Math.abs(p.x - this._cx) * 0.9; t.born = now;
      });
      this._squashT = dots ? 0 : now;
      this._dotShineT = dots && !this.data.reducedMotion ? now + 1800 : 0;
    },
    /** 圆点章节号 / 章节印的颜色:外观主色 --lk-a 与墨色 --lk-ink 的 JS 镜像;老主题回落原型金 / 米白。 */
    _colors() { return LC.colorsFor(this.data.look); },
    _scatter() {
      const W = this._W, H = this._H;
      this._tiles.forEach((t) => {
        const a = Math.atan2(t.y - this._cy, t.x - this._cx) + (Math.random() - 0.5) * 0.9, sp = 6 + Math.random() * 9;
        t.vx = Math.cos(a) * sp; t.vy = Math.sin(a) * sp - 3; t.tx = Math.random() * W; t.ty = 40 + Math.random() * (H - 80);
        t.tsz = 1.2; t.delay = 0; t.born = Date.now(); t.loose = 1;
      });
      for (let i = 0; i < 26; i++) this._sparks.push({ x: this._cx + (Math.random() - 0.5) * 220 * this._S, y: this._cy + (Math.random() - 0.5) * 200 * this._S, life: 0, max: 50 + Math.random() * 50 });
    },

    // ---------- 主循环 ----------
    _loop() {
      if (this._gone || this._fxOff) return;
      this._raf = this._cv.requestAnimationFrame(() => { this._paint(Date.now()); this._loop(); });
    },
    _paint(now) {
      const ctx = this._ctx, W = this._W, H = this._H, dpr = this._dpr;
      if (!ctx) return;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, W, H);
      this._stars.forEach((s) => { ctx.globalAlpha = 0.18 + 0.32 * (0.5 + 0.5 * Math.sin(now / 1000 * s.s + s.p)); ctx.fillStyle = E.PAL.star; ctx.fillRect(s.x, s.y, s.r, s.r); });
      this._drawTiles(ctx, now);
      this._drawParticles(ctx, now);
      this._drawFineDust(ctx, now);
      ctx.globalAlpha = 1;
    },
    _drawTiles(ctx, now) {
      // 圆点的亮波(原型 dots.js:1.8s 起每 2.6s 从左上扫到右下一次);减少动态效果没有
      const dband = this._dotShineT && now > this._dotShineT ? (((now - this._dotShineT) % 2600) / 2600) * 900 * this._S - 150 * this._S : -1e9;
      const lk = this._tiles.length && this._tiles[0].dot ? this._colors() : null;
      this._tiles.forEach((t) => {
        if (now - t.born < t.delay) { if (t.dot) return; ctx.globalAlpha = 0.4; ctx.fillStyle = E.PAL.dust; ctx.fillRect(t.x, t.y, t.sz, t.sz); return; }
        if (this.data.reducedMotion) { t.x = t.tx; t.y = t.ty; t.sz = t.tsz; }
        else if (t.dot && !t.loose) {
          t.vx = (t.vx + (t.tx - t.x) * 0.05) * 0.80; t.vy = (t.vy + (t.ty - t.y) * 0.05) * 0.80;
          t.x += t.vx; t.y += t.vy; t.sz += (t.tsz - t.sz) * 0.14;
        } else {
          const damp = t.loose ? 0.93 : 0.82;
          t.vx = (t.vx + (t.tx - t.x) * 0.045) * damp; t.vy = (t.vy + (t.ty - t.y) * 0.045) * damp;
          t.x += t.vx; t.y += t.vy; t.sz += (t.tsz - t.sz) * 0.12;
        }
        if (t.dot) {
          const r = Math.max(0, t.sz / 2), lit = !t.loose && Math.abs((t.x - 30 * this._S) + (t.y - 150 * this._S) * 0.7 - dband) < 22 * this._S;
          ctx.globalAlpha = t.loose ? Math.max(0.3, Math.min(1, t.sz / 3)) : (lit ? 1 : t.a);
          ctx.fillStyle = t.loose ? E.PAL.dust : (lit && lk ? lk.ink : t.c);
          ctx.beginPath(); ctx.arc(t.x, t.y, r, 0, 6.283); ctx.fill();
          if (!t.loose && r > 3) { ctx.globalAlpha = 0.28; ctx.fillStyle = E.PAL.white; ctx.beginPath(); ctx.arc(t.x - r * 0.3, t.y - r * 0.3, r * 0.3, 0, 6.283); ctx.fill(); }
          return;
        }
      });
      // 拼好的图形周围零星闪光
      if (this._tiles.length && !this._tiles[0].loose && Math.random() < 0.05) this._sparks.push({ x: this._cx + (Math.random() - 0.5) * 300 * this._S, y: this._cy + (Math.random() - 0.5) * 240 * this._S, life: 0, max: 40 + Math.random() * 30 });
    },
    _drawParticles(ctx, now) {
      const H = this._H;
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
