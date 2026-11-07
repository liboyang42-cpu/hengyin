// cy-chips · 预设药丸(2026-10-28 文档二):「点一下就加上」。只列还没加的由父页过滤好再传。
// add 有值时末尾多一颗描边「+ add」,点了原地变成输入框 +「添加」;taken 里有同名时输入框变红、
// 下面一句 duplicateText,「添加」灰掉。输入框空着失焦就收回药丸。
Component({
  options: { addGlobalClass: true },
  properties: {
    items: { type: Array, value: [] },        // [{label, dot}],dot 为颜色 token 名,可空
    plus: { type: Boolean, value: false },    // 预设药丸前面带「+」(特质)
    add: { type: String, value: '' },
    placeholder: { type: String, value: '' },
    maxlength: { type: Number, value: 6 },
    taken: { type: Array, value: [] },
    duplicateText: { type: String, value: '' },
    disabled: { type: Boolean, value: false },
  },
  data: { naming: false, text: '', dup: false },
  methods: {
    onPick(e) {
      if (this.data.disabled) return;
      const index = Number(e.currentTarget.dataset.index);
      this.triggerEvent('pick', { index, label: this.data.items[index].label });
    },
    onAddTap() { if (!this.data.disabled) this.setData({ naming: true, text: '', dup: false }); },
    onInput(e) {
      const text = e.detail.value;
      const v = text.trim();
      this.setData({ text, dup: !!v && (this.data.taken || []).indexOf(v) >= 0 });
    },
    onBlur() { if (!this.data.text.trim()) this.setData({ naming: false, dup: false }); },
    onConfirm() {
      const v = this.data.text.trim();
      if (!v || this.data.dup) return;
      this.setData({ naming: false, text: '', dup: false });
      this.triggerEvent('add', { value: v });
    },
  },
});
