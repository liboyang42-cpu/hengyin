const app = getApp();
const { resolveMenuChrome } = require('../../../utils/nav-safe-area.js');

Component({
  options: { multipleSlots: true },
  properties: {
    show: { type: Boolean, value: false },
    variant: { type: String, value: 'full' }, // full(T3) | half(T1)
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
    noop() {},
    onBack() { this.triggerEvent('back'); },
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
});
