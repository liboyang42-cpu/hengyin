// cy-frames:预渲染序列帧播放器(l-icons 2026-11-11,业主批准 proto/icons 落地方式 1)。
// 一张或多张 WebP 网格雪碧图(sheets:[{ src, n }],每张 cols 列、每帧 fw×fh px),用 image + transform 按帧切,
// 没有 @keyframes、没有 steps()(仓库 keyframes 棘轮固定 146)。同时只挂当前 / 下一张 / 再下一张三张图,大序列不吃内存。
// 进场 = attached 后加 is-on(缩放 + 透明度过渡);减少动态效果(宿主传 reducedMotion,或系统 + 应用内开关):直接停在 still 帧、不起计时器。
// 页面隐藏时暂停,回来接着播;detached 清计时器。
const { readReducedMotion } = require('../../../../utils/motion-preference.js');

const WINDOW = 3;   // 同时挂着的雪碧图张数

Component({
  options: { addGlobalClass: false },
  properties: {
    sheets: { type: Array, value: [], observer: '_reset' }, // [{ src, n }]
    cols: { type: Number, value: 6 },
    fw: { type: Number, value: 112 },      // 每帧 px(图片里的)
    fh: { type: Number, value: 112 },
    w: { type: Number, value: 224 },       // 显示尺寸 rpx
    h: { type: Number, value: 224 },
    fps: { type: Number, value: 12 },
    mode: { type: String, value: 'loop' }, // loop | once | intro-loop(播完一遍后从 loopFrom 起循环)
    loopFrom: { type: Number, value: 0 },
    still: { type: Number, value: -1 },    // 减少动态效果停在哪一帧;-1 = 最后一帧(once / intro-loop)或第 0 帧(loop)
    delay: { type: Number, value: 0 },     // 进场延迟 ms
    enter: { type: String, value: 'pop' }, // pop | none
    reducedMotion: { type: null, value: null },
  },
  data: { on: false, reduced: false, slots: [], pos: '', sheetW: 0, sheetH: 0 },
  lifetimes: {
    attached() { this._reset(); this._start(); },
    detached() { this._stop(); },
  },
  pageLifetimes: {
    show() { if (this._paused) { this._paused = false; this._tick(); } },
    hide() { if (this._timer) { this._paused = true; } this._stop(false); },
  },
  methods: {
    _isReduced() {
      const r = this.properties.reducedMotion;
      if (r === true || r === false) return r;
      try { return !!readReducedMotion(); } catch (e) { return false; }
    },
    /** 把 sheets 展开成帧表:第 i 帧在哪张图、第几格 */
    _reset() {
      const sheets = this.properties.sheets || [];
      const frames = [];
      sheets.forEach((sh, si) => { for (let k = 0; k < (sh.n | 0); k++) frames.push({ s: si, k }); });
      this._frames = frames;
      this._i = 0;
      const p = this.properties, scale = p.w / p.fw;
      this._scale = scale;
      this._rows = {};
      sheets.forEach((sh, si) => { this._rows[si] = Math.ceil((sh.n | 0) / Math.max(1, p.cols)); });
      this._paint(0, true);
    },
    _stillIndex() {
      const n = this._frames.length, st = this.properties.still;
      if (st >= 0 && st < n) return st;
      return this.properties.mode === 'loop' ? 0 : Math.max(0, n - 1);
    },
    _start() {
      this._stop();
      const reduced = this._isReduced();
      if (reduced) { this.setData({ reduced: true, on: true }); this._paint(this._stillIndex(), true); return; }
      this.setData({ reduced: false, on: false });
      this._i = 0; this._paint(0, true);
      // 两帧后再加 is-on,保证起点样式先落地(cy-illus 同一做法)
      this._enterT = setTimeout(() => { if (this._gone) return; this.setData({ on: true }); this._tick(); }, 34 + Math.max(0, this.properties.delay | 0));
    },
    _tick() {
      clearTimeout(this._timer);
      const n = this._frames.length;
      if (n < 2 || this.data.reduced || this._gone) return;
      const ms = Math.max(16, Math.round(1000 / Math.max(1, this.properties.fps)));
      this._timer = setTimeout(() => {
        let i = this._i + 1;
        if (i >= n) {
          if (this.properties.mode === 'once') { this._timer = 0; this.triggerEvent('end'); return; }
          i = this.properties.mode === 'intro-loop' ? Math.min(Math.max(0, this.properties.loopFrom | 0), n - 1) : 0;
          if (this.properties.mode === 'intro-loop' && !this._looped) { this._looped = true; this.triggerEvent('end'); }
        }
        this._paint(i, false);
        this._tick();
      }, ms);
    },
    /** 第 i 帧:挂 WINDOW 张图(当前 + 后面两张),当前这张按格位移 */
    _paint(i, force) {
      const f = this._frames[i]; if (!f) return;
      const p = this.properties, scale = this._scale || 1, sheets = p.sheets || [];
      const col = f.k % Math.max(1, p.cols), row = Math.floor(f.k / Math.max(1, p.cols));
      const pos = 'translate(' + (-col * p.w).toFixed(1) + 'rpx,' + (-row * p.h).toFixed(1) + 'rpx)';
      this._i = i;
      if (!force && f.s === this._cur) { this.setData({ pos }); return; }
      this._cur = f.s;
      const slots = [];
      for (let k = 0; k < WINDOW; k++) { const si = (f.s + k) % Math.max(1, sheets.length); if (sheets[si] && (k === 0 || si !== f.s)) slots.push({ si, src: sheets[si].src, w: (p.cols * p.w).toFixed(1), h: ((this._rows[si] || 1) * p.h).toFixed(1), cur: k === 0 }); }
      this.setData({ slots, pos });
    },
    _stop(clearPause) {
      clearTimeout(this._enterT); clearTimeout(this._timer); this._timer = 0;
      if (clearPause !== false) this._paused = false;
    },
  },
});
