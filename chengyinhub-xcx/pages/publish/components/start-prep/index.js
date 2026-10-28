// 开局前准备(文档二 L1,2026-10-28):主题详情的下一层。列表左滑删除、「编辑」拖动排序,
// 最后一行直接输入添加,下面「建议」点一下就加入;满 6 项时添加行和建议都不出现。
// 和原型一样即改即生效:每次改动发 change {items},主题详情那边写回 journeyRules。
const d20 = require('../../fabu/theme-d20.js');

Component({
  options: { addGlobalClass: true },
  properties: {
    show: { type: Boolean, value: false },
    items: { type: Array, value: [] },
    readOnly: { type: Boolean, value: false },
  },
  data: { editing: false, swipe: -1, text: '', suggestions: [], max: d20.MAX_PREP },
  observers: {
    items(list) {
      const have = list || [];
      this.setData({ suggestions: d20.PREP_SUGGESTIONS.filter(s => have.indexOf(s) < 0).slice(0, 3) });
    },
    show(v) { if (v) this.setData({ editing: false, swipe: -1, text: '' }); },
  },
  methods: {
    emit(items) { this.triggerEvent('change', { items }); },
    onBack() { this.triggerEvent('close'); },
    toggleEdit() { this.setData({ editing: !this.data.editing, swipe: -1 }); },
    onSwipe(e) { this.setData({ swipe: e.detail.open ? e.detail.index : -1 }); },
    onDelete(e) {
      if (this.data.readOnly) return;
      this.setData({ swipe: -1 });
      this.emit(this.data.items.filter((_, i) => i !== e.detail.index));
    },
    onMove(e) { if (!this.data.readOnly) this.emit(d20.move(this.data.items, e.detail.from, e.detail.to)); },
    put(text) {
      const v = String(text || '').trim();
      if (!v || this.data.readOnly || this.data.items.length >= d20.MAX_PREP || this.data.items.indexOf(v) >= 0) return false;
      this.emit(this.data.items.concat(v));
      return true;
    },
    onInput(e) { this.setData({ text: e.detail.value }); },
    onAdd() { if (this.put(this.data.text)) this.setData({ text: '' }); },
    onSuggest(e) { this.put(e.currentTarget.dataset.text); },
  },
});
