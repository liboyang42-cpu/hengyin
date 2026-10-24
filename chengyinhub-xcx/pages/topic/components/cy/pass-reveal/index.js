/* 通行证到账动效(2026-10-22 业主,逐帧对照 insporadesign「Data Collect」视频后定稿):
   1. 付款所在的页面**原地模糊**(背景色就是页面自己的颜色,不另画色层);
   2. 屏幕边缘一圈琥珀色光,上面一个白、一个洋红亮点绕边顺时针跑;
   3. 「通行证已到账」在左上逐字由糊变清;底部升起一团紫光;
   4. 白色光球呼吸 → 镭射通行证从光球里长出、旋转两圈,边光由银到金升级,3D 烟花从眼前大块旋转飞远;
   5. 落定后整屏渐回黑,可玩期限逐字出现,再出按钮。
   「放进票夹」:卡片先放大、再飞走,然后交给页面去票夹(新卡从上方落到最前)。
   只做展示;购买与跳转都由页面处理。减少动效时直接给落定后的终态。 */
const TIERS = ['#cfd6e0', '#9b6bff', '#2fd27a', '#F5B301']; // 银 → 紫 → 翠 → 金,只是边光颜色,不声称稀有度

function chars(text) { return String(text || '').split('').map((c, i) => ({ c: c === ' ' ? ' ' : c, d: i * 45 })); }

Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    show: { type: Boolean, value: false },
    cover: { type: String, value: '' },
    name: { type: String, value: '' },
    kindText: { type: String, value: '' },
    untilText: { type: String, value: '' },
    daysText: { type: String, value: '' },
    skuText: { type: String, value: '自玩通行证' }, // 自玩通行证不分人数,不写「单人」
    durationLine: { type: String, value: '' },
    reducedMotion: { type: Boolean, value: false },
  },
  data: { phase: '', title: [], sub: [], tierColor: TIERS[0], btns: false, fly: false },
  observers: {
    show(v) { if (v) this._start(); else this._stop(); },
  },
  lifetimes: { detached() { this._stop(); } },
  methods: {
    noop() {},
    _at(ms, fn) { const t = setTimeout(fn, this.data.reducedMotion ? 0 : ms); (this._timers = this._timers || []).push(t); },
    _stop() { (this._timers || []).forEach(clearTimeout); this._timers = []; this._fxOn = false; this._parts = []; },
    _start() {
      this._stop();
      const title = chars('通行证已到账');
      if (this.data.reducedMotion) {
        this.setData({ phase: 'card landed dark', title, sub: chars(this.data.durationLine), tierColor: TIERS[3], btns: true, fly: false });
        return;
      }
      this.setData({ phase: 'blur', title: [], sub: [], tierColor: TIERS[0], btns: false, fly: false });
      this._at(60, () => this.setData({ phase: 'blur edge', title }));
      this._at(1200, () => this.setData({ phase: 'blur edge orb' }));
      this._at(2600, () => { this.setData({ phase: 'blur edge card spin' }); this._initFx(); this._burst(0); });
      [600, 1200, 1800].forEach((t, i) => this._at(2600 + t, () => { this.setData({ tierColor: TIERS[i + 1] }); this._burst(i + 1); }));
      this._at(5100, () => this.setData({ phase: 'blur card landed dark', sub: chars(this.data.durationLine) }));
      this._at(6300, () => this.setData({ btns: true }));
    },
    onWallet() {
      if (this.data.fly) return;
      this.setData({ fly: true, btns: false });
      this._at(900, () => this.triggerEvent('wallet'));
    },
    onPlay() { this.triggerEvent('play'); },

    /* —— 3D 烟花:碎片从「眼前」(z 很小 → 画得很大)出发,翻转着飞远、越来越小 —— */
    _initFx() {
      if (this._canvas) { this._fxOn = true; this._loop(); return; }
      this.createSelectorQuery().select('#prfx').fields({ node: true, size: true }).exec((res) => {
        const r = res && res[0];
        if (!r || !r.node) return;
        const canvas = r.node, ctx = canvas.getContext('2d');
        let dpr = 2;
        try { dpr = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()).pixelRatio || 2; } catch (e) {}
        canvas.width = r.width * dpr; canvas.height = r.height * dpr; ctx.scale(dpr, dpr);
        this._canvas = canvas; this._ctx = ctx; this._w = r.width; this._h = r.height;
        this._fxOn = true; this._loop();
      });
    },
    _burst(tier) {
      const n = 26 + tier * 14, col = [TIERS[tier], '#ffffff', TIERS[tier]];
      const cx = (this._w || 375) / 2, cy = (this._h || 700) * 0.46;
      this._parts = this._parts || [];
      for (let i = 0; i < n; i++) {
        const a = Math.random() * Math.PI * 2, sp = 0.6 + Math.random() * 2.6;
        this._parts.push({ x: cx, y: cy, vx: Math.cos(a) * sp * 3, vy: Math.sin(a) * sp * 3 - 1.2, z: 0.18 + Math.random() * 0.25,
          vz: 0.016 + Math.random() * 0.02, r: Math.random() * 6.3, vr: (Math.random() - 0.5) * 0.35, ry: Math.random() * 6.3,
          vry: 0.08 + Math.random() * 0.2, w: 5 + Math.random() * 7, h: 3 + Math.random() * 5, c: col[i % 3], cx, cy });
      }
    },
    _loop() {
      if (!this._fxOn || !this._ctx) return;
      const g = this._ctx, w = this._w, h = this._h;
      g.clearRect(0, 0, w, h);
      this._parts = (this._parts || []).filter((p) => p.z < 2.4);
      for (const p of this._parts) {
        p.x += p.vx; p.y += p.vy; p.vy += 0.04; p.vx *= 0.99; p.z += p.vz; p.r += p.vr; p.ry += p.vry;
        const k = 1 / p.z, sx = p.cx + (p.x - p.cx) * k * 0.55, sy = p.cy + (p.y - p.cy) * k * 0.55;
        const pw = p.w * k, ph = p.h * k * Math.abs(Math.cos(p.ry)) + 0.6;
        g.save(); g.translate(sx, sy); g.rotate(p.r); g.globalAlpha = Math.max(0, Math.min(1, 1.35 - p.z * 0.55));
        g.fillStyle = p.c; g.fillRect(-pw / 2, -ph / 2, pw, ph); g.restore();
      }
      g.globalAlpha = 1;
      if (!this._parts.length && /landed/.test(this.data.phase)) { this._fxOn = false; return; }
      this._canvas.requestAnimationFrame(() => this._loop());
    },
  },
});
