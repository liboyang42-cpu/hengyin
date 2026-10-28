// cy-sheet-nav · 半屏里的 iOS 导航栏(2026-10-28 文档二)。
// 第一层:左「取消」/ 中标题 / 右「完成」;下一层:左「‹ 上一层名字」/ 中标题 / 右是这一层的主要动作。
// 右侧动作没填好时传 action-disabled,灰掉且不发事件。纯选择的一层不传 action,右边留空。
// 按钮外观照 fabu 的 node-sheet__close / node-sheet__done,不另起一套。
Component({
  options: { addGlobalClass: true },
  properties: {
    title: { type: String, value: '' },
    left: { type: String, value: '取消' },
    back: { type: String, value: '' },          // 有值 = 下一层,左上显示「‹ back」
    action: { type: String, value: '' },        // 右上动作文字;空 = 不显示
    actionPlain: { type: Boolean, value: false }, // 文字按钮(编辑 / 添加),不是实心「完成」
    actionDisabled: { type: Boolean, value: false },
  },
  methods: {
    onLeft() { this.triggerEvent(this.data.back ? 'back' : 'cancel'); },
    onAction() { if (!this.data.actionDisabled) this.triggerEvent('action'); },
  },
});
