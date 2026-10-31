const { resolveMenuChrome } = require('../../../../utils/nav-safe-area.js');

// 项目抽屉外壳。只管遮罩、面板、标题与底部动作。
// 内容由调用方用 slot 填,动作按 key 冒泡回去。
Component({
  options: { addGlobalClass: true, multipleSlots: false },
  properties: {
    show:    { type: Boolean, value: false },
    compact: { type: Boolean, value: false },
    // 走查 v2 R08(业主 2026-10-30):「更多」是普通底部弹窗 —— 贴底、标题居中、按内容撑高
    plain:   { type: Boolean, value: false },
    title:   { type: String,  value: '' },
    // 稿 468:960/1009/1086/1126:四套抽屉的标题下面都有一句「这一套为什么长这样」,
    // 商家一打开就知道现在这个状态能做什么、不能做什么,不用逐行试。
    subtitle: { type: String, value: '' },
    // CU-C-126 删掉了 headerVariant:'action' 档(取消/完成都只执行 onClose、面板内无待保存表单),
    // 所有抽屉统一走「标题 + 右上角 ✕」,主动作只在底部 actions 那一行。
    // 客户名单使用普通半屏，其余长列表避开微信导航。
    customerMode: { type: Boolean, value: false },
    // [{ key, label, ghost? }],最多两个并排
    actions: { type: Array,   value: [] },
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
    onClose() { this.triggerEvent('close'); },
    onAction(e) {
      this.triggerEvent('action', { key: e.currentTarget.dataset.key });
    },
  },
});
