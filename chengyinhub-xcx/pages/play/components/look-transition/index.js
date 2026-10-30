// 主题画面风格的转场层（pages/play/utils/theme-look.js 的 TRANSITIONS）。
// 全部是两段式：盖上 → 在完全盖住的那一刻换内容（run 的回调）→ 揭开。
// 只用 CSS transition（仓库 keyframes 有只减不增的棘轮）：is-pre 起点 → is-in 盖住 → is-out 揭开后的终点。
// 减动效时直接换内容，不出这一层。
const COVER_MS = 560;   // 含错峰类（像素 / 百叶 / 故障）最长的延迟
const REVEAL_MS = 660;

// 像素块的出现顺序：固定伪随机，避免每次 setData 都重排
const TILES = Array.from({ length: 48 }, (_, i) => ({ k: i, d: (i * 37) % 48 * 6 }));
const SLATS = Array.from({ length: 8 }, (_, i) => ({ k: i, d: i * 30 }));
const BANDS = Array.from({ length: 6 }, (_, i) => ({ k: i, d: ((i * 5) % 6) * 22 }));

Component({
  properties: {
    kind: { type: String, value: 'IRIS' },
    reducedMotion: { type: Boolean, value: false },
  },
  data: { phase: '', k: 'iris', tiles: TILES, slats: SLATS, bands: BANDS },
  lifetimes: {
    detached() { this._clear(); },
  },
  methods: {
    _clear() { clearTimeout(this._t0); clearTimeout(this._t1); clearTimeout(this._t2); },
    /** 盖上 → swap() → 揭开。重复调用时上一轮的回调立即执行，不丢换内容这一步。 */
    run(swap) {
      const go = typeof swap === 'function' ? swap : () => {};
      if (this.properties.reducedMotion) { go(); return; }
      if (this._pending) { this._clear(); const prev = this._pending; this._pending = null; prev(); }
      this._pending = go;
      this.setData({ phase: 'is-pre', k: String(this.properties.kind || 'IRIS').toLowerCase() });
      this._t0 = setTimeout(() => this.setData({ phase: 'is-in' }), 34);
      this._t1 = setTimeout(() => {
        const fn = this._pending; this._pending = null;
        if (fn) fn();
        this.setData({ phase: 'is-out' });
        this._t2 = setTimeout(() => this.setData({ phase: '' }), REVEAL_MS);
      }, 34 + COVER_MS);
    },
    noop() {},
  },
});
