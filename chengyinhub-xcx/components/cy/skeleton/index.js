// 统一加载骨架屏:首屏 loading 期间占位,避免白屏→突现。
// type:旧档 list/card/detail/amount；新档 feed-card/map-card/merchant-metric/route-timeline/form-section/ticket。
// count: list/card 重复几条(默认 list=4 / card=2,由使用方传)
const reducedMotionBehavior = require('../../../behaviors/reduced-motion.js');
const roleGuard = require('../../../utils/roleGuard.js');

Component({
  options: { multipleSlots: false },
  behaviors: [reducedMotionBehavior],
  properties: {
    type: { type: String, value: 'list' },
    count: { type: Number, value: 4 },
    loadingLabel: { type: String, value: '正在加载内容' },
    // 超过这么久还在加载才画骨架(ms);快加载什么都不闪。0 = 立即画。
    delay: { type: Number, value: 400 },
  },
  observers: {
    count(n) {
      this.setData({ items: Array.from({ length: Math.max(1, n) }) });
    },
  },
  // light:G-10(owner 2026-10-27)商家视角骨架一律浅色,不随宿主页根节点主题变黑。
  data: { items: [], ready: false, light: false },
  lifetimes: {
    attached() {
      this.setData({ items: Array.from({ length: Math.max(1, this.data.count) }), light: roleGuard.isMerchantView() });
      const delay = Number(this.data.delay);
      if (!(delay > 0)) { this.setData({ ready: true }); return; }
      this._readyTimer = setTimeout(() => { this._readyTimer = null; this.setData({ ready: true }); }, delay);
    },
    detached() {
      if (this._readyTimer) { clearTimeout(this._readyTimer); this._readyTimer = null; }
    },
  },
});
