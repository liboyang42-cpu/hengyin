// 深链兼容壳。编辑表单与保存逻辑在 components/cy/scene-club-edit。
const toast = require('../../../utils/toast.js');
const app = getApp();

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    clubId: '',
    routeReady: false,
    dirty: false,
    confirm: { show: false },
    backGuardOff: false,
  },
  onUnload() { this._destroyed = true; },
  onShow() { this._navigating = false; }, // 从治理页返回时兜底清跳转锁
  onLoad(options) { this.setData({ clubId: (options && (options.id || options.clubId)) || '', routeReady: true }); },
  onDirtyChange(event) { this.setData({ dirty: !!(event.detail && event.detail.dirty) }); },
  onSaved() { this.setData({ dirty: false }); },
  onDissolved() {
    this.setData({ dirty: false });
    toast.success('俱乐部已解散');
    setTimeout(function () {
      wx.navigateBack({ delta: 1, fail() { wx.switchTab({ url: '/pages/talent/list/index' }); } });
    }, 500);
  },
  // 解散确认框的 alt「改为转让主理人」→ 治理页(转让链路真源)。原来事件没人接,点了只关框。
  goGovernance() {
    if (!this.data.clubId) return;
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/club/governance/index?clubId=' + this.data.clubId, complete: () => { this._navigating = false; } });
  },
  requestBack() {
    if (this.data.dirty) {
      this.setData({ confirm: { show: true } });
      return;
    }
    this.doBack();
  },
  confirmBack() {
    this.setData({ dirty: false, confirm: { show: false } });
    this.doBack();
  },
  cancelBack() { this.setData({ confirm: { show: false } }); },
  // BL-18:page-container 消费了一次物理返回/侧滑。确认框开着 ⇒ 当作「继续编辑」;否则有改动 ⇒ 弹确认。
  // 仍需守卫时先撤再挂,下一次返回才会再被拦住。
  onNativeBackGuard() {
    if (this.data.confirm.show) this.cancelBack();
    else if (this.data.dirty) this.requestBack();
    if (this.data.dirty || this.data.confirm.show) {
      this.setData({ backGuardOff: true });
      setTimeout(() => { if (!this._destroyed) this.setData({ backGuardOff: false }); }, 50);
    }
  },
  doBack() { wx.navigateBack({ fail() { wx.switchTab({ url: '/pages/talent/list/index' }); } }); },
});
