/* v13 通关勋章页。order = 当前从前到后的勋章下标;poses 由 order 与拖动量算出。
   统计数字进场时从 0 数上去(减少动效时直接给终值)。 */
const POSE = [{ y: 0, s: 1, b: 1, o: 1 }, { y: -18, s: 0.86, b: 0.6, o: 1 }, { y: -32, s: 0.74, b: 0.4, o: 1 }, { y: -42, s: 0.66, b: 0.3, o: 1 }, { y: -42, s: 0.66, b: 0.3, o: 0 }];
const SPARKS = [[60, 160, 14, 0], [310, 180, 20, 0.6], [80, 300, 10, 1.1], [300, 300, 12, 0.3], [200, 110, 16, 0.9], [130, 240, 8, 1.5]];

function poseStyle(depth, dx, n, still) {
  if (depth === 0) return 'z-index:' + (n + 1) + ';transform:translate(' + dx + 'px,0) rotate(' + (dx * 0.05).toFixed(2) + 'deg);' + (still ? 'transition:none;' : '');
  const p = POSE[Math.min(depth, POSE.length - 1)];
  return 'z-index:' + (n - depth) + ';opacity:' + p.o + ';filter:brightness(' + p.b + ');transform:translate(0,' + p.y + 'px) scale(' + p.s + ');';
}

/* 数字从 0 数到终值;「2:46:12」这种时长按秒数,「5/5」只数分子,其余按小数位数 */
function frame(v, k) {
  const s = String(v);
  let m = s.match(/^(\d+):(\d{2}):(\d{2})$/);
  if (m) { const t = Math.round((+m[1] * 3600 + +m[2] * 60 + +m[3]) * k); return Math.floor(t / 3600) + ':' + String(Math.floor(t / 60) % 60).padStart(2, '0') + ':' + String(t % 60).padStart(2, '0'); }
  m = s.match(/^(\d+):(\d{2})$/);
  if (m) { const t = Math.round((+m[1] * 60 + +m[2]) * k); return Math.floor(t / 60) + ':' + String(t % 60).padStart(2, '0'); }
  m = s.match(/^(\d+)\/(\d+)$/);
  if (m) return Math.round(+m[1] * k) + '/' + m[2];
  m = s.match(/^(\d+)(\.(\d+))?$/);
  if (m) { const d = m[3] ? m[3].length : 0; return (+s * k).toFixed(d); }
  return s;
}

Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    show: { type: Boolean, value: false },
    items: { type: Array, value: [] },
    title: { type: String, value: '' },
    sub: { type: String, value: '' },
    stats: { type: Array, value: [] },
    record: { type: null, value: null },
    primaryText: { type: String, value: '分享战报' },
    secondaryText: { type: String, value: '查看记录' },
    backTop: { type: Number, value: 48 },
    reducedMotion: { type: Boolean, value: false },
  },
  data: { on: false, dropped: false, order: [], poses: [], shown: [], sparks: [] },
  observers: {
    'show, items'(show, list) {
      this._order = (list || []).map((_, i) => i);
      this._paint(0, false);
      if (!show) { this.setData({ on: false }); return; }
      this.setData({
        on: false, dropped: !!this.data.reducedMotion, shown: [],
        sparks: SPARKS.map((s) => 'left:' + (s[0] / 390 * 100).toFixed(1) + '%;top:' + (s[1] * 750 / 390).toFixed(0) + 'rpx;width:' + (s[2] * 750 / 390).toFixed(0) + 'rpx;height:' + (s[2] * 750 / 390).toFixed(0) + 'rpx;animation-delay:' + s[3] + 's;'),
      });
      clearTimeout(this._onTimer); clearTimeout(this._dropTimer);
      this._dropTimer = setTimeout(() => this.setData({ dropped: true }), 40);
      const wait = this.data.reducedMotion ? 0 : 500 + (list || []).length * 300;
      this._onTimer = setTimeout(() => { this.setData({ on: true }); this._countUp(); }, wait);
    },
  },
  lifetimes: { detached() { clearTimeout(this._onTimer); clearTimeout(this._dropTimer); clearInterval(this._countTimer); } },
  methods: {
    noop() {},
    _paint(dx, still) {
      const order = this._order || [];
      const poses = [];
      order.forEach((idx, depth) => { poses[idx] = poseStyle(depth, depth === 0 ? dx : 0, order.length, still); });
      this.setData({ order: order.slice(), poses });
    },
    _next() {
      const order = this._order || [];
      if (order.length < 2) return this._paint(0, false);
      order.push(order.shift());
      this._paint(0, false);
    },
    _countUp() {
      clearInterval(this._countTimer);
      const stats = this.data.stats || [];
      if (this.data.reducedMotion || !stats.length) { this.setData({ shown: stats.map((s) => String(s.v)) }); return; }
      const t0 = Date.now(), dur = 1400;
      this._countTimer = setInterval(() => {
        const k0 = Math.min(1, (Date.now() - t0) / dur), k = k0 < 0.5 ? 2 * k0 * k0 : 1 - Math.pow(-2 * k0 + 2, 2) / 2;
        this.setData({ shown: stats.map((s) => frame(s.v, k)) });
        if (k0 >= 1) clearInterval(this._countTimer);
      }, 40);
    },
    onTap() { if (!this._moved) this._next(); },
    onStart(e) { const t = e.touches && e.touches[0]; this._x0 = t ? t.clientX : 0; this._dx = 0; this._moved = false; },
    onMove(e) {
      const t = e.touches && e.touches[0];
      if (!t || (this._order || []).length < 2) return;
      this._dx = t.clientX - this._x0;
      if (Math.abs(this._dx) > 6) this._moved = true;
      const now = Date.now();
      if (this._at && now - this._at < 16) return;
      this._at = now;
      this._paint(this._dx, true);
    },
    onEnd() {
      if (!this._moved) return;
      if (Math.abs(this._dx || 0) > 50) this._next(); else this._paint(0, false);
      setTimeout(() => { this._moved = false; }, 50);
    },
    onClose() { this.triggerEvent('close'); },
    onPrimary() { this.triggerEvent('primary'); },
    onSecondary() { this.triggerEvent('secondary'); },
  },
});
