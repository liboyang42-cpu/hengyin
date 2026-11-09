// cy-list-row · 分组列表的一行(2026-10-28 文档二):左名称、中间当前值、右箭头。
// 能往下走的给 chevron;点一下就地展开的给 fold(箭头随 open 转 90°)。右侧控件(开关 / 步进器 / 分段)放默认 slot。
// 点击发 press(不叫 tap:原生 tap 会穿出组件边界,同名会触发两次)。
// hr:和上一行之间的细线(第一行不传)。danger:居中红字(删除类)。add:前面一颗实心「+」、字加粗。
Component({
  options: { addGlobalClass: true, multipleSlots: true },
  properties: {
    title: { type: String, value: '' },
    value: { type: String, value: '' },
    strong: { type: String, value: '' },  // 值前面加粗的那一段(如「2」/ 5)
    dot: { type: String, value: '' },     // 左侧色点:传颜色 token 名(如 status-warning)
    chevron: { type: Boolean, value: false },
    fold: { type: Boolean, value: false },
    open: { type: Boolean, value: false },
    hr: { type: Boolean, value: false },
    danger: { type: Boolean, value: false },
    add: { type: Boolean, value: false },
    bad: { type: Boolean, value: false },     // 自动检查没过:红色叹号 + 红字
    ok: { type: Boolean, value: false },      // 自动检查通过:绿色勾
    disabled: { type: Boolean, value: false },
    tappable: { type: Boolean, value: true },
  },
  methods: {
    onTap() { if (!this.data.disabled && this.data.tappable) this.triggerEvent('press'); },
  },
});
