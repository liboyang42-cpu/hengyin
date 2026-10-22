// cy-tag-sheet · 胶囊标签多选半屏弹窗(owner 2026-10-22 · L11,Figma「Edit Interests」)
//
// 形态:cy-sheet 底部变体 + 抓手;正文是横向翻页的原生 <swiper>,每页 pageRows × pageCols 个胶囊,
// 页数 = ceil(items / (pageRows × pageCols)),下面一排页码点;底部整宽黑色「保存」按钮。
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
const { buildPages, toggleValue, tagIconSrc } = require('./model.js');

Component({
  properties: {
    show: { type: Boolean, value: false },
    title: { type: String, value: '' },
    items: { type: Array, value: [] },      // [{ key, label, icon }]
    value: { type: Array, value: [] },      // 选中的 key
    max: { type: Number, value: 0 },        // 0 = 不限
    pageRows: { type: Number, value: 4 },
    pageCols: { type: Number, value: 2 },
    hint: { type: String, value: '' },      // 标题下一行灰字(可选)
    loading: { type: Boolean, value: false },
    error: { type: String, value: '' },     // 载入失败文案,配「重试」发 retry
    saving: { type: Boolean, value: false },
    confirmText: { type: String, value: '保存' },
  },
  data: {
    _sel: [],
    _pages: [],
    _current: 0,
    _swiperH: 0,
    _checkSrc: tagIconSrc('check'),   // 选中勾(Figma check.svg);未入库时不画
  },
  observers: {
    value(v) {
      this.setData({ _sel: (v || []).map(String) });
      this._rebuild();
    },
    'items, pageRows, pageCols'() {
      this._rebuild();
    },
    show(v) {
      if (v) this.setData({ _current: 0 });
    },
  },
  methods: {
    _rebuild() {
      const d = this.data;
      const pages = buildPages(d.items, d._sel, d.pageRows, d.pageCols);
      const rows = Math.max(1, Number(d.pageRows) || 4);
      this.setData({
        _pages: pages,
        // 胶囊 94rpx(47px)+ 行距 20rpx(10px),swiper 高按满页算,翻到不满的末页也不跳高
        _swiperH: rows * 94 + (rows - 1) * 20,
        _current: Math.min(d._current, Math.max(0, pages.length - 1)),
      });
    },
    onTap(e) {
      if (this.data.saving) return;
      const key = e.currentTarget.dataset.key;
      const next = toggleValue(this.data._sel, key, this.data.max);
      if (!next) { toast('最多选 ' + this.data.max + ' 个'); return; }
      this.setData({ _sel: next });
      this._rebuild();
      this.triggerEvent('change', { value: next.slice() });
    },
    onSwipe(e) {
      this.setData({ _current: e.detail.current });
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
