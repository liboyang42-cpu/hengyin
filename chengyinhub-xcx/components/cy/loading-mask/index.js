// cy-loading-mask · 全屏阻塞式加载遮罩(替代 wx.showLoading,支付/核销链路专用)
//
// 一页一个实例,id 固定 "cy-loading",由 utils/loading.js 找到它。show() 计数式:嵌套 show 两次要 hide 两次才消失,
// 避免「A 的 hide 把 B 的遮罩提前关掉」——这是原生 showLoading 的老坑。
const reducedMotionBehavior = require('../../../behaviors/reduced-motion.js');
const roleGuard = require('../../../utils/roleGuard.js');

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    // 遮罩立刻挡触摸,但超过这么久才显出来(ms);快请求不闪。
    delay: { type: Number, value: 400 },
  },
  // light:G-10(owner 2026-10-27)商家视角遮罩与面板一律浅色,不随宿主页根节点主题变黑。
  data: { visible: false, entered: false, title: '', light: false },
  lifetimes: { detached() { this._depth = 0; if (this._enterTimer) clearTimeout(this._enterTimer); if (this._exitTimer) clearTimeout(this._exitTimer); } },
  methods: {
    noop() {},   // catchtouchmove 吃掉遮罩下的滚动/点击
    show(options) {
      const opts = options || {};
      this._depth = (this._depth || 0) + 1;
      if (this._exitTimer) { clearTimeout(this._exitTimer); this._exitTimer = null; }
      const title = String(opts.title || '加载中');
      if (this.data.visible) {
        // 嵌套 show:只换文案,是否已显出由第一次 show 的延时决定
        // 退场淡出期间又 show:撤掉退场,直接回到已显出
        const reviving = !this.data.entered && !this._enterTimer;
        if (reviving) this.setData({ title, entered: true });
        else this.setData({ title });
        return;
      }
      // 先挂上(透明,吃触摸),延时到了再显出
      this.setData({ visible: true, entered: false, title, light: roleGuard.isMerchantView() });
      const delay = Number(this.data.delay);
      if (!(delay > 0)) { this.setData({ entered: true }); return; }
      this._enterTimer = setTimeout(() => { this._enterTimer = null; this.setData({ entered: true }); }, delay);
    },
    hide(force) {
      this._depth = force ? 0 : Math.max(0, (this._depth || 0) - 1);
      if (this._depth !== 0 || !this.data.visible) return;
      const pending = !!this._enterTimer;   // 还没显出来:直接撤掉,不留淡出
      if (this._enterTimer) { clearTimeout(this._enterTimer); this._enterTimer = null; }
      if (pending || this.data.reducedMotion) { this.setData({ visible: false, entered: false }); return; }
      this.setData({ entered: false });
      // 140ms = --cy-motion-fast 的 JS 镜像,与 .lm 的 transition 时长一致
      this._exitTimer = setTimeout(() => { this._exitTimer = null; this.setData({ visible: false }); }, 140);
    },
  },
});
