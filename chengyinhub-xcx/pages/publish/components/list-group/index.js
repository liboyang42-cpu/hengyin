// cy-list-group · 半屏里的分组列表(2026-10-28 文档二):上面一行小标题(可带右侧「编辑 / 完成」),
// 下面一块圆角白底,行用 cy-list-row / cy-swipe-row 往里放。不写脚注:10-28 起去掉次级说明。
Component({
  options: { addGlobalClass: true, multipleSlots: true },
  properties: {
    header: { type: String, value: '' },
    action: { type: String, value: '' },   // 小标题右侧文字按钮(编辑 / 完成)
    gap: { type: Boolean, value: false },  // 没有小标题时和上一组拉开距离
    bare: { type: Boolean, value: false }, // 只要小标题,内容自己画(如规则难度三张卡、预设药丸)
  },
  methods: {
    onAction() { this.triggerEvent('action'); },
  },
});
