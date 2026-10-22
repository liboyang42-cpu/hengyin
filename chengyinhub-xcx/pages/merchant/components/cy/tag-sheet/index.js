// cy-tag-sheet · 胶囊标签多选半屏弹窗(owner 2026-10-22 · L11,Figma「Edit Interests」)
//
// 形态:cy-sheet 底部变体 + 抓手;正文是一整片横向自由滚动的错落胶囊(默认 3 行,不分页、
// 无页码点 —— 2026-10-22 owner:「不是翻页,是直接左右滑」);底部整宽黑色「保存」按钮。
//
// 排行只在 items 变化 / 弹窗打开时算一次(_layout 记每行的 key),点选只改勾选态,
// 胶囊不会因为多出一个勾而在行间跳动。
//
// 选中态归本组件持有(_sel),外部 value 变化时同步进来 —— 父页面不回写 value 也能正常多选。
//   change  每次点选后发 { value: 选中 key 数组 }
//   confirm 点保存发 { value }
//   close   遮罩 / 下拖关闭
// max>0 时到上限再点未选中的胶囊:不改选中,弹全站 toast。
//
// 商家白底档:颜色是 Figma 定值,不读随系统深色翻转的语义 token;cy-sheet 的角色档在根节点钉成
// 商家白(变量继承穿过 isolated 边界),手机深色模式下外观不变。
const toast = require('../../../../../utils/toast.js');
const { buildRows, toggleValue, tagIconSrc } = require('./model.js');

Component({
  properties: {
    show: { type: Boolean, value: false },
    title: { type: String, value: '' },
    items: { type: Array, value: [] },      // [{ key, label, icon }]
    value: { type: Array, value: [] },      // 选中的 key
    max: { type: Number, value: 0 },        // 0 = 不限
    rows: { type: Number, value: 3 },       // 错落行数
    hint: { type: String, value: '' },      // 标题下一行灰字(可选)
    hintError: { type: Boolean, value: false }, // hint 是失败文案时标红(保存失败回显)
    loading: { type: Boolean, value: false },
    error: { type: String, value: '' },     // 载入失败文案,配「重试」发 retry
    saving: { type: Boolean, value: false },
    confirmText: { type: String, value: '保存' },
  },
  data: {
    _sel: [],
    _rows: [],
    _scrollLeft: 0,
    _checkSrc: tagIconSrc('check'),   // 选中勾(Figma check.svg);未入库时不画
  },
  observers: {
    value(v) {
      this.setData({ _sel: (v || []).map(String) });
      this._paint();
    },
    'items, rows'() {
      this._layoutRows();
    },
    show(v) {
      if (v) { this._layoutRows(); this.setData({ _scrollLeft: 0 }); }
    },
  },
  methods: {
    // 排行(只在列表变化 / 打开时)
    _layoutRows() {
      const d = this.data;
      const rows = buildRows(d.items, d._sel, d.rows);
      this._layout = rows.map(r => r.map(t => t.key));
      this.setData({ _rows: rows });
    },
    // 只刷新勾选态,不重新排行
    _paint() {
      if (!this._layout) { this._layoutRows(); return; }
      const sel = this.data._sel;
      const rows = this.data._rows.map(r => r.map(t => Object.assign({}, t, { checked: sel.indexOf(t.key) >= 0 })));
      this.setData({ _rows: rows });
    },
    onTap(e) {
      if (this.data.saving) return;
      const key = e.currentTarget.dataset.key;
      const next = toggleValue(this.data._sel, key, this.data.max);
      if (!next) { toast('最多选 ' + this.data.max + ' 个'); return; }
      this.setData({ _sel: next });
      this._paint();
      this.triggerEvent('change', { value: next.slice() });
    },
    onConfirm() {
      if (this.data.saving || this.data.loading) return;
      this.triggerEvent('confirm', { value: this.data._sel.slice() });
    },
    onClose() {
      if (this.data.saving) return;
      this.triggerEvent('close');
    },
    onRetry() {
      this.triggerEvent('retry');
    },
  },
});
