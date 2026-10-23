/* 完成页勋章堆(2026-10-22 业主:多枚勋章叠放、点按或拖动切换;统计用字形图标,不用圆章)。
   order = 当前从前到后的下标顺序;poses 由 order 和拖动量算出每枚的变换。 */
function poseOf(depth, dx, n) {
  if (depth === 0) {
    return 'z-index:' + (n + 1) + ';transform:translate(' + dx + 'px,0) rotate(' + (dx * 0.05).toFixed(2) + 'deg);';
  }
  const d = Math.min(depth, 3);
  return 'z-index:' + (n - depth) + ';opacity:' + (depth > 2 ? 0 : 1) + ';transform:translate(' + (d * 26) + 'rpx,' + (d * -18) + 'rpx) scale(' + (1 - d * 0.07) + ');';
}

Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    items: { type: Array, value: [] },
    reducedMotion: { type: Boolean, value: false },
  },
  data: { order: [], poses: [] },
  observers: {
    items(list) { this._order = (list || []).map((_, i) => i); this._paint(0, false); },
  },
  methods: {
    _paint(dx, dragging) {
      const order = this._order || [];
      const poses = [];
      order.forEach((idx, depth) => { poses[idx] = poseOf(depth, depth === 0 ? dx : 0, order.length) + (dragging && depth === 0 ? 'transition:none;' : ''); });
      this.setData({ order: order.slice(), poses });
    },
    _next() {
      const order = this._order || [];
      if (order.length < 2) { this._paint(0, false); return; }
      order.push(order.shift());
      this._paint(0, false);
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
      if (Math.abs(this._dx || 0) > 60) this._next(); else this._paint(0, false);
      setTimeout(() => { this._moved = false; }, 50);
    },
  },
});
