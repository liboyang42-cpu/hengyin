const { readPageStyle } = require('../../../utils/font-scale.js');
const session = require('../../../utils/session/login-reload.js');
const app = getApp();
function authorization() {
  const value = typeof app.getAuthorization === 'function' ? app.getAuthorization()
    : typeof app.getToken === 'function' ? app.getToken() : app.globalData && app.globalData.authorization;
  return typeof value === 'string' ? value.trim() : '';
}

Page({
  data: { privacyGateShow: false, fontScaleStyle: '', profileMounts: [] },
  syncProfileSession() {
    const owner = session.memberId(app), token = authorization();
    const changed = owner !== this._profileOwner || token !== this._profileToken;
    if (changed) {
      this._profileEpoch = (this._profileEpoch || 0) + 1;
      this._profileOwner = owner;
      this._profileToken = token;
      this.setData({ profileMounts: owner && token ? [{ id: this._profileEpoch }] : [] });
    }
    if (!owner || !token) {
      const epoch = this._profileEpoch || 0;
      session.waitForInitialSession(this, app, function () {
        if (epoch === this._profileEpoch) this.syncProfileSession();
      });
    }
    return changed;
  },
  loginToRead() {
    if (this._hidden || this._unloaded) return;
    const epoch = this._profileEpoch || 0, owner = session.memberId(app);
    return session.retryLogin(this, app, function () {
      if (epoch !== this._profileEpoch || (owner && owner !== session.memberId(app)) || !authorization()) return;
      this.syncProfileSession();
    });
  },
  onHide() { this._hidden = true; this._profileEpoch = (this._profileEpoch || 0) + 1; },
  onUnload() { this.onHide(); this._unloaded = true; },
  /* Dynamic Type:放 onShow 而非 onLoad —— 用户可能切到微信设置改完字号再切回来 */
  onShow() {
    this.setData({ fontScaleStyle: readPageStyle() });
    this._hidden = false;
    this.syncProfileSession();
  },
  // 一级 tab 页原本一个都不能下拉刷新。本页正文全在 cy-profile 里,
  // 收圈绑 refreshAll 返回的 Promise,不用定时器猜。
  onPullDownRefresh() {
    const changed = this.syncProfileSession();
    if (changed || !this.data.profileMounts.length) { wx.stopPullDownRefresh(); return; }
    var profile = this.selectComponent('#profile');
    if (!profile || typeof profile.refreshAll !== 'function') { wx.stopPullDownRefresh(); return; }
    var done = profile.refreshAll(true);
    if (done && typeof done.then === 'function') {
      done.then(function () { wx.stopPullDownRefresh(); },
                function () { wx.stopPullDownRefresh(); });
    } else {
      wx.stopPullDownRefresh();
    }
  },
  showPrivacyGate() {
    this.setData({ privacyGateShow: true });
  },
  onPrivacyGateSettled() {
    this.setData({ privacyGateShow: false });
  },
  onShareAppMessage: function (res) {
    var profile = this.selectComponent('#profile');
    return profile && typeof profile.getSharePayload === 'function' ? profile.getSharePayload(res || {}) : {
      title: '城瘾 · 城市探索',
      path: '/pages/member/index/index'
    };
  }
});
