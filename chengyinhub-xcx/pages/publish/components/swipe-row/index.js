// cy-swipe-row · 列表编辑的一行(2026-10-28 文档二):左滑露出红色「删除」;editing 时左边红色减号、右边三条线拖动排序。
// 内容(一般是 cy-list-row)放默认 slot;就地展开的内容放 slot="xp",跟着这一行一起滑。
// 「一次只开一行」归父页:open 受控,左滑 / 点减号发 swipe {open},父页决定哪一行开。
// 拖动排序只算步数:松手发 move {from, to},数组怎么挪由父页做 —— 组件不碰数据。
const OPEN_X = 156;   // rpx,= 删除按钮宽
const TRIGGER = 40;   // px,滑过这么多算「要开 / 要关」

Component({
  options: { addGlobalClass: true, multipleSlots: true },
  properties: {
    index: { type: Number, value: 0 },
    count: { type: Number, value: 0 },
    open: { type: Boolean, value: false },
    editing: { type: Boolean, value: false },
    hr: { type: Boolean, value: false },
    deleteText: { type: String, value: '删除' },
    label: { type: String, value: '' },     // 读屏:这一行叫什么
    disabled: { type: Boolean, value: false },
  },
  data: { _dx: 0, _dy: 0, _dragging: false, _swiping: false },
  methods: {
    onStart(e) {
      if (this.data.disabled || this.data.editing) return;
      const t = e.touches[0];
      this._sw = { x0: t.clientX, y0: t.clientY, axis: '' };
    },
    onMove(e) {
      const s = this._sw;
      if (!s) return;
      const t = e.touches[0];
      const dx = t.clientX - s.x0, dy = t.clientY - s.y0;
      if (!s.axis) {
        if (Math.abs(dx) < 6 && Math.abs(dy) < 6) return;
        s.axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y';
      }
      if (s.axis !== 'x') return;
      s.dx = dx;
      const base = this.data.open ? -OPEN_X / 2 : 0;  // rpx→px 粗算:按 2 倍屏
      this.setData({ _swiping: true, _dx: Math.max(-OPEN_X / 2 - 20, Math.min(0, base + dx)) });
    },
    onEnd() {
      const s = this._sw;
      this._sw = null;
      if (!s || s.axis !== 'x') return;
      const open = this.data.open ? s.dx < TRIGGER : s.dx < -TRIGGER;
      this.setData({ _swiping: false, _dx: 0 });
      if (open !== this.data.open) this.triggerEvent('swipe', { index: this.data.index, open });
    },
    onMinus() { if (!this.data.disabled) this.triggerEvent('swipe', { index: this.data.index, open: !this.data.open }); },
    onDelete() { if (!this.data.disabled) this.triggerEvent('delete', { index: this.data.index }); },

    onGripStart(e) {
      if (this.data.disabled) return;
      this._drag = { y0: e.touches[0].clientY, h: 0 };
      this.createSelectorQuery().select('.sw').boundingClientRect((r) => {
        if (this._drag && r) this._drag.h = r.height;
      }).exec();
      this.setData({ _dragging: true, _dy: 0 });
    },
    onGripMove(e) {
      if (!this._drag) return;
      this.setData({ _dy: e.touches[0].clientY - this._drag.y0 });
    },
    onGripEnd() {
      const d = this._drag;
      this._drag = null;
      if (!d) return;
      const steps = d.h ? Math.round(this.data._dy / d.h) : 0;
      this.setData({ _dragging: false, _dy: 0 });
      const max = Math.max(0, (this.data.count || 1) - 1);
      const to = Math.max(0, Math.min(max, this.data.index + steps));
      if (to !== this.data.index) this.triggerEvent('move', { from: this.data.index, to });
    },
  },
});
