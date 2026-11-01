// 工作台叠层:与 project-drawer 同一套面板,只是顶端比下一层低一点;展示组件,返回从 bind:back 冒泡。
const { resolveMenuChrome } = require('../../../../utils/nav-safe-area.js');

Component({
  options: { multipleSlots: true, addGlobalClass: true },
  properties: {
    show:  { type: Boolean, value: false },
    title: { type: String, value: '' },
    // 2 = 叠在抽屉上,3 = 再叠一层;每深一层顶端再低 12px
    depth: { type: Number, value: 2 },
    // 上面还有一层时自己缩小压暗
    under: { type: Boolean, value: false },
  },
  data: { panelTop: 69 },
  lifetimes: {
    attached() {
      try {
        const win = (wx.getWindowInfo && wx.getWindowInfo()) || wx.getSystemInfoSync();
        const capsule = wx.getMenuButtonBoundingClientRect && wx.getMenuButtonBoundingClientRect();
        this.setData({ panelTop: resolveMenuChrome(win, capsule).sheetTop });
      } catch (e) { /* 保留导航坐标缺失时的兜底。 */ }
    },
  },
  methods: {
    noop() {},
    onBack() { this.triggerEvent('back'); },
  },
});
