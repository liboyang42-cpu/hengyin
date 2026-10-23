/* 故事标签顶部的章节卡扇面(2026-10-22 业主,参考 spottedinprod 扇形卡):
   · 卡上文字只写在顶部,不描边;
   · 点一张:它走到中间放大并翻到背面,背面是这一章的一小段内容;再点收回;
   · 上滑时扇面收拢,滑出顶栏后通知页面在顶栏左侧放一叠迷你卡(dock 事件)。
   收拢进度用 IntersectionObserver 量,不跟 onPageScroll 逐帧 setData。 */
const STEPS = [0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1];

/* 位姿照 v13 原型(px × 1.923 = rpx):展开时每张横移 46px、下沉 10px、转 14°;
   上滑收拢 c:整体下沉 60px、缩到 55%,收拢到七成以后淡出;
   点开一张:它居中上提 22px 放大 1.55 倍,其余往两边让开(横移 56px ± 14px、下沉 12px + 18px、转 16°)。 */
function poseOf(i, n, spread, focus) {
  const k = i - (n - 1) / 2, c = 1 - spread;
  let x = k * 88 * spread, y = Math.abs(k) * 19 * spread + c * 115, a = k * 14 * spread, s = 1 - 0.45 * c, z = 10 - Math.abs(k);
  const fade = c <= 0.7 ? 1 : Math.max(0, 1 - (c - 0.7) / 0.3);
  if (focus >= 0) {
    if (i === focus) { x = 0; y = -42; a = 0; s = 1.55; z = 40; }
    else { x = k * 108 + (i < focus ? -27 : 27); y = Math.abs(k) * 23 + 35; a = k * 16; }
  }
  return 'transform: translate(' + x.toFixed(0) + 'rpx,' + y.toFixed(0) + 'rpx) rotate(' + a.toFixed(1) + 'deg) scale(' + s.toFixed(3) + ');'
    + 'z-index:' + z + ';' + (fade < 1 ? 'opacity:' + fade.toFixed(2) + ';' : '');
}

Component({
  options: { styleIsolation: 'isolated' },
  properties: {
    items: { type: Array, value: [] },
    navHeight: { type: Number, value: 88 },
    noun: { type: String, value: '这一章' }, // 自由探索传「这一页」
    reducedMotion: { type: Boolean, value: false },
  },
  data: { poses: [], focus: -1, caption: '', collapse: 0, entering: false },
  observers: {
    items() { this._spread = 1; this.setData({ focus: -1, caption: '' }); this._layout(); this._enter(); },
  },
  lifetimes: {
    attached() { this._spread = 1; this._layout(); this._enter(); },
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
    /* 进标签时扇形卡从下方一张张飞入(原型 stagger 70ms,从 translateY(60px) scale(.6) 起) */
    _enter() {
      if (this.data.reducedMotion || !(this.data.items || []).length) return;
      this.setData({ entering: true });
      clearTimeout(this._enterTimer);
      this._enterTimer = setTimeout(() => this.setData({ entering: false }), 30);
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
