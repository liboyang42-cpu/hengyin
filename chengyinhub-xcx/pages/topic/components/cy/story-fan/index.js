/* 故事标签顶部的章节卡扇面(2026-10-22 业主,参考 spottedinprod 扇形卡):
   · 卡上文字只写在顶部,不描边;
   · 点一张:它走到中间放大并翻到背面,背面是这一章的一小段内容;再点收回;
   · 上滑时扇面收拢,滑出顶栏后通知页面在顶栏左侧放一叠迷你卡(dock 事件)。
   收拢进度用 IntersectionObserver 量,不跟 onPageScroll 逐帧 setData。 */
const STEPS = [0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1];

function poseOf(i, n, spread, focus) {
  const k = i - (n - 1) / 2;
  let x = k * 92 * spread, y = Math.abs(k) * 20 * spread, a = k * 14 * spread, s = 1, z = 10 - Math.abs(k), dim = 0;
  if (focus >= 0) {
    if (i === focus) { x = 0; y = -36; a = 0; s = 1.5; z = 40; }
    else { x = k * 112 + (i < focus ? -28 : 28); y = Math.abs(k) * 24 + 30; a = k * 16; dim = 1; }
  }
  return 'transform: translate(' + x.toFixed(0) + 'rpx,' + y.toFixed(0) + 'rpx) rotate(' + a.toFixed(1) + 'deg) scale(' + s + ');'
    + 'z-index:' + z + ';' + (dim ? 'opacity:.55;' : '');
}

Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    items: { type: Array, value: [] },
    navHeight: { type: Number, value: 88 },
    noun: { type: String, value: '这一章' }, // 自由探索传「这一页」
    reducedMotion: { type: Boolean, value: false },
  },
  data: { poses: [], focus: -1, caption: '', collapse: 0 },
  observers: {
    items() { this._spread = 1; this.setData({ focus: -1, caption: '' }); this._layout(); },
  },
  lifetimes: {
    attached() { this._spread = 1; this._layout(); },
    ready() { this._observe(); },
    detached() { if (this._io) this._io.disconnect(); },
  },
  methods: {
    onCard(e) {
      const i = Number(e.currentTarget.dataset.i);
      const it = this.data.items[i] || {};
      const focus = this.data.focus === i ? -1 : i;
      this.setData({ focus, caption: focus < 0 ? '' : ((it.label ? it.label + ' · ' : '') + (it.title || '')) });
      this._layout();
    },
    _layout() {
      const n = (this.data.items || []).length;
      const spread = this.data.focus >= 0 ? 1 : (this._spread == null ? 1 : this._spread);
      const poses = [];
      for (let i = 0; i < n; i++) poses.push(poseOf(i, n, spread, this.data.focus));
      this.setData({ poses });
    },
    _observe() {
      if (this._io) this._io.disconnect();
      const io = this.createIntersectionObserver({ thresholds: STEPS });
      this._io = io;
      io.relativeToViewport({ top: -this.data.navHeight }).observe('.sf', (res) => {
        const above = res.boundingClientRect && res.boundingClientRect.top < this.data.navHeight;
        const collapse = above ? 1 - (res.intersectionRatio || 0) : 0;
        const docked = collapse > 0.85;
        if (collapse > 0.3 && this.data.focus >= 0) this.setData({ focus: -1, caption: '' });
        this._spread = 1 - collapse;
        this.setData({ collapse: Number(collapse.toFixed(2)) });
        this._layout();
        if (docked !== this._docked) { this._docked = docked; this.triggerEvent('dock', { docked }); }
      });
    },
  },
});
