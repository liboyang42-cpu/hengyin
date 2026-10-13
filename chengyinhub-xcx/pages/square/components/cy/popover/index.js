// cy-popover · T5 胶囊弹窗(Figma 339:740)
//
// 锚点矩形由调用方传进来,不在组件内量 —— 触发点(帖文头像)长在 cy-post-card 里,
// 本组件挂在页面上,createSelectorQuery 跨不过去。调用方在 tap 回调里量一次即可。
//
// 稿子的组件说明把边界写死了:**不许居中、不许铺满宽度**(一居中就变成 T2 半屏 sheet)。
// 所以这里只算 left/top,绝不设 width,也绝不用 left+right 对拉。

const { resolveMenuChrome } = require('../../../../../utils/nav-safe-area.js');

// 面板贴近触发点；屏幕边缘沿用短弹窗的 8pt 窄边距。
const GAP_PT = 6;
const EDGE_PT = 8;

Component({
  options: { styleIsolation: 'isolated' },

  properties: {
    show: { type: Boolean, value: false },
    /* anchor:触发点的 boundingClientRect(px)。缺了就不开 —— 没有锚点的 T5 只能居中,
       而居中恰恰是稿子明令禁止的形态,宁可不显示也不显示成错的那一种。 */
    anchor: { type: Object, value: null },
    /* items:[{ key, label, icon? }]。icon 用 cy-icon 的名字(如 'plus')。 */
    items: { type: Array, value: [] },
  },

  data: {
    _render: false,
    panelStyle: '',
  },

  observers: {
    'show, anchor, items': function (show) {
      if (!show) { this.hide(); return; }
      this.place();
    },
  },

  lifetimes: {
    detached() { this._placeEpoch = (this._placeEpoch || 0) + 1; },
  },

  methods: {
    // 先量再开:面板是 fixed 层,位置只能来自实测,不能靠 CSS 相对定位。
    place() {
      const epoch = this._placeEpoch = (this._placeEpoch || 0) + 1;
      const a = this.data.anchor;
      if (!a || !Number.isFinite(a.left) || !Number.isFinite(a.top)) {
        // 没锚点不开。别退化成居中 —— 见文件头。
        this.setData({ _render: false });
        return;
      }
      const win = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      const winW = win.windowWidth || 375;
      const winH = win.windowHeight || 667;
      const pxPerPt = winW / 375;
      const gap = GAP_PT * pxPerPt;
      const edge = EDGE_PT * pxPerPt;
      const capsule = wx.getMenuButtonBoundingClientRect ? wx.getMenuButtonBoundingClientRect() : null;
      const minTop = resolveMenuChrome(win, capsule).sheetTop;
      const safeBottom = win.safeArea && Number(win.safeArea.bottom);
      const bottom = Math.min(winH - edge, safeBottom > minTop ? safeBottom : winH - edge);
      const maxHeight = Math.max(0, bottom - minTop);
      if (!maxHeight) { this.hide(); return; }
      const heightStyle = `max-height:${maxHeight}px;`;
      // 先隐藏渲染，再量实际宽高。长文案换行和字号变化都会改变尺寸。
      this.setData({
        _render: true,
        panelStyle: `${heightStyle}left:0;top:${minTop}px;visibility:hidden;`,
      }, () => {
        if (epoch !== this._placeEpoch || !this.data.show) return;
        this.createSelectorQuery().select('.pop__panel').boundingClientRect().exec((rects) => {
          if (epoch !== this._placeEpoch || !this.data.show) return;
          const panel = rects && rects[0];
          if (!panel || !(panel.width > 0) || !(panel.height > 0)) { this.hide(); return; }
          const anchorBottom = Number.isFinite(a.bottom) ? a.bottom : a.top + (a.height || 0);
          const below = anchorBottom + gap;
          const above = a.top - gap - panel.height;
          const flipUp = below + panel.height > bottom && above >= minTop;
          const top = Math.max(minTop, Math.min(flipUp ? above : below, bottom - panel.height));
          const left = Math.max(edge, Math.min(a.left, winW - panel.width - edge));
          this.setData({ panelStyle: `${heightStyle}left:${left}px;top:${top}px;` });
        });
      });
    },

    hide() {
      this._placeEpoch = (this._placeEpoch || 0) + 1;
      this.setData({ _render: false });
    },

    onPick(e) {
      const key = (e.currentTarget.dataset || {}).key;
      this.onClose();
      this.triggerEvent('pick', { key });
    },

    onClose() { this.triggerEvent('close'); },
    noop() {},
  },
});
