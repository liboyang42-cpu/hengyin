// 深链兼容壳。订单详情与支付/退款逻辑在 components/cy/scene-member-order-detail。
const { openScene } = require('../../utils/scene-entry.js');

Page({
  data: { id: '', navScrolled: false },
  onLoad(options) {
    this.setData({ id: (options && options.id) || '' });
  },
  // 透明导航只留给首屏;滚动后转实底,内容不再压到返回键和胶囊下面。
  onPageScroll(e) {
    const scrolled = !!(e && e.scrollTop > 60);
    if (scrolled !== this.data.navScrolled) this.setData({ navScrolled: scrolled });
  },
  // 组件在场景里发 back/close 是「回上一层场景」;深链壳里没有场景栈,退回页面导航。
  // 方法名与按钮字面一致 —— c04-r2 的「按钮字面必须为真」就是盯这条。
  backToOrderList(event) {
    // 详情组件将打开票夹等页面时，不再同时发起返回导航。
    if (event && event.detail && event.detail.navigating) return;
    const pages = getCurrentPages();
    const previous = pages.length > 1 ? pages[pages.length - 2] : null;
    if (previous && previous.route === 'subpackageMember/order/order') {
      wx.navigateBack({ delta: 1 });
      return;
    }
    wx.redirectTo({ url: '/subpackageMember/order/order' });
  },
  openChildScene(event) {
    const detail = (event && event.detail) || {};
    if (detail.id) openScene(detail.id, detail.params || {});
  },
});
