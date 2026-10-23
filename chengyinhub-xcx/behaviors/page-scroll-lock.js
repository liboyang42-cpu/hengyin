// 弹层打开时锁住下方页面滚动的可选 behavior(页面 opt-in)。
// 页面 wxml 里需有 <page-meta page-style="{{pageStyle}}" />;lockPageScroll(true) 后页面 overflow:hidden。
// 组件内的弹层(cy-sheet/cy-modal/cy-dropdown)已自带 catchtouchmove,只有页面级自绘浮层才需要它。
module.exports = Behavior({
  data: { pageStyle: '' },
  methods: {
    lockPageScroll(locked) {
      const pageStyle = locked ? 'overflow: hidden;' : '';
      if (pageStyle !== this.data.pageStyle) this.setData({ pageStyle });
    },
  },
});
