// cy-segmented · 分段控件(2026-10-28 文档二):少量互斥选项(进度钟类型、格数)。
// options 可以是字符串数组,也可以是 [{label, value}];value 对不上任何一项时按下标 0 高亮。
Component({
  options: { addGlobalClass: true },
  properties: {
    options: { type: Array, value: [] },
    value: { type: null, value: '' },
    label: { type: String, value: '' },
    disabled: { type: Boolean, value: false },
  },
  data: { items: [], current: 0 },
  observers: {
    'options, value'(options, value) {
      const items = (options || []).map(o => (o && typeof o === 'object') ? { label: String(o.label), value: o.value } : { label: String(o), value: o });
      const found = items.findIndex(o => o.value === value);
      this.setData({ items, current: found < 0 ? 0 : found });
    },
  },
  methods: {
    onPick(e) {
      const index = Number(e.currentTarget.dataset.index);
      if (this.data.disabled || index === this.data.current) return;
      this.triggerEvent('change', { index, value: this.data.items[index].value });
    },
  },
});
