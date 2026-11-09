// cy-choice-sheet · 底部小选择卡(2026-10-28 文档二,照 Threads):几颗大选项,选中的填满强调色、没有勾。
// 点一项立即发 change 并收起;点暗处也收起。带 stay 的选项(如「自定义」)点了不收,
// 下面 slot 里的内容(开始 / 结束 +「完成」)由父页按 value 自己显示。外壳复用 cy-sheet(bottom + 抓手)。
const CLOSE_DELAY = 220; // 先让选中态亮一下再收,和原型一致

Component({
  options: { addGlobalClass: true },
  properties: {
    show: { type: Boolean, value: false },
    title: { type: String, value: '' },
    options: { type: Array, value: [] },   // [{value, label, sub, glyph(any|day|night|clock), stay}]
    value: { type: null, value: '' },
    error: { type: String, value: '' },
  },
  lifetimes: {
    detached() { if (this._t) { clearTimeout(this._t); this._t = null; } },
  },
  methods: {
    onPick(e) {
      const opt = this.data.options[Number(e.currentTarget.dataset.index)];
      if (!opt) return;
      this.triggerEvent('change', { value: opt.value });
      if (opt.stay) return;
      if (this._t) clearTimeout(this._t);
      this._t = setTimeout(() => { this._t = null; this.triggerEvent('close'); }, CLOSE_DELAY);
    },
    onClose() { this.triggerEvent('close'); },
  },
});
