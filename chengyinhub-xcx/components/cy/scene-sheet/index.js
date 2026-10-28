const app = getApp();
const { resolveMenuChrome } = require('../../../utils/nav-safe-area.js');
const exitMotion = require('../../../behaviors/exit-motion.js');
const { withNativeBackGuard, onBackGuardLeave } = require('../../../utils/native-back-guard.js');

/* 2026-10-23 业主定:场景弹窗也要退场动画(原来 wx:if 直连 show,关闭瞬间消失),并按档位定速、
 * 参与 exit-motion 的交接(同一时刻只有一个弹层在动)。half 档 300 / 250,full 档 340 / 280,
 * 退场计时器是 --cy-motion-sheet-*-out 的 JS 镜像。进出场走 transition(_in 开关),不占 keyframes 棘轮。 */
const LEVEL_EXIT_MS = { half: 250, full: 280 };

/* G-2(2026-10-27):物理返回 / 侧滑先落在弹层上 —— 有上一步就退一步,否则走 ✕ 同一条关闭出口。
 * closable=false 的场景没有给用户退出口,返回键只被吞掉,不替宿主关。 */
function onNativeBack(vm) {
  if (vm.data._closing) return;
  if (vm.data.canBack) { vm.onBack(); return; }
  if (vm.data.variant === 'page' || vm.data.closable) vm._requestClose('back');
}

Component(withNativeBackGuard({
  options: { multipleSlots: true },
  behaviors: [exitMotion(250)],
  properties: {
    // G-2:页面已有自己的 page-container 覆盖这个弹层时传 false 让位(一页只能有一个容器)
    backGuard: { type: Boolean, value: true },
    show: { type: Boolean, value: false },
    // full(T3) | half(T1) | page(G-1:无遮罩无把手,导航栏 + 大标题,返回 = 原生返回)
    variant: { type: String, value: 'full' },
    // page 档专用:页面自己有头图(订单详情 Figma D07)⇒ 导航栏悬浮在图上、不出大标题
    bare: { type: Boolean, value: false },
    theme: { type: String, value: 'player' }, // player | merchant
    title: { type: String, value: '' },
    canBack: { type: Boolean, value: false },
    closable: { type: Boolean, value: true },
    dirty: { type: Boolean, value: false },
    maskClosable: { type: Boolean, value: false },
    footer: { type: Boolean, value: false },
    zIndex: { type: Number, value: 0 }, // 未指定时使用共用 sheet 层级，让确认弹窗显示在其上
    reducedMotion: { type: Boolean, value: false },
  },
  data: {
    navTop: 0,
    navRight: 0,
    closeLabel: '关闭当前场景',
    panelTopPx: 0,
    _in: false,
  },
  observers: {
    title(value) {
      this.setData({ closeLabel: value ? '关闭' + value : '关闭当前场景' });
    },
  },
  lifetimes: {
    attached() {
      const globalData = (app && app.globalData) || {};
      const menuButtonInfo = globalData.menuButtonInfo;
      const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      const windowWidth = windowInfo.windowWidth || windowInfo.screenWidth || 375;
      const navTop = globalData.statusBarHeight || windowInfo.statusBarHeight || 20;
      const navRight = menuButtonInfo && menuButtonInfo.left
        ? Math.max(24, Math.round(windowWidth - menuButtonInfo.left + 12))
        : 100;
      const panelTopPx = resolveMenuChrome(windowInfo, menuButtonInfo).sheetTop;
      this.setData({
        navTop,
        navRight,
        panelTopPx,
        closeLabel: this.data.title ? '关闭' + this.data.title : '关闭当前场景',
      });
    },
  },
  methods: {
    // G-2 返回守卫 page-container 的 afterleave(判定逻辑在 utils/native-back-guard.js)
    _onBackGuardLeave() { onBackGuardLeave(this); },
    // exit-motion 钩子:挂载时落在起点态,渲染完成后置 _in 让 transition 起跑
    _enterData() {
      // page 档与 full 同速(wxss 里 .ss--page 也换成 full 档时长)
      this._exitMs = LEVEL_EXIT_MS[this.data.variant === 'half' ? 'half' : 'full'];
      return { _in: false };
    },
    _afterEnter() { this.setData({ _in: true }); },
    noop() {},
    onBack() { this.triggerEvent('back'); },
    // page 档左上角是导航栏返回:有上一步退一步,首层等同关闭(深链壳里就是退页)
    onPageBack() {
      if (this.data.canBack) this.onBack();
      else this._requestClose('back');
    },
    onMask() {
      if (!this.data.maskClosable) return;
      this._requestClose('mask');
    },
    onClose() { this._requestClose('close'); },
    _requestClose(reason) {
      this.triggerEvent('requestclose', { reason });
      if (!this.data.dirty) this.triggerEvent('close', { reason });
    },
  },
}, onNativeBack));
