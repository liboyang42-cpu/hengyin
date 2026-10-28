// cy-stepper · 数值步进器(2026-10-28 文档二):左边当前数,右边「− | +」。到上下限那一颗变灰、点不动 ——
// 上下限由控件挡住,不靠报错。互相挡(最小值到不了最大值)由父页把 min / max 传成对方的值。
Component({
  options: { addGlobalClass: true },
  properties: {
    value: { type: Number, value: 0 },
    min: { type: Number, value: 0 },
    max: { type: Number, value: 99 },
    step: { type: Number, value: 1 },
    label: { type: String, value: '' },   // 读屏用
    disabled: { type: Boolean, value: false },
  },
  methods: {
    onStep(e) {
      if (this.data.disabled) return;
      const d = Number(e.currentTarget.dataset.d) * this.data.step;
      const v = Math.min(this.data.max, Math.max(this.data.min, this.data.value + d));
      if (v !== this.data.value) this.triggerEvent('change', { value: v });
    },
  },
});
