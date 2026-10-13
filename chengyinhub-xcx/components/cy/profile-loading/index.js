const policy = require('../../../utils/identity/identity-policy.js');
const app = getApp();

Component({
  options: { styleIsolation: 'apply-shared' },
  properties: { viewer: { type: String, value: 'self' } },
  data: { navMode: 'consumer', failed: false, loading: false },
  lifetimes: {
    attached() {
      this._active = true;
      this.syncNavigation();
      this._onLoadError = (res) => {
        if (this._active && res && res.type === 'subpackage' &&
            Array.isArray(res.subpackage) && res.subpackage.indexOf('subpackageMember') >= 0) {
          this.setData({ failed: true, loading: false });
        }
      };
      if (typeof wx.onLazyLoadError === 'function' && typeof wx.offLazyLoadError === 'function') {
        wx.onLazyLoadError(this._onLoadError);
      }
      this.retryLoad();
    },
    detached() {
      this._active = false;
      this._request = (this._request || 0) + 1;
      if (typeof wx.offLazyLoadError === 'function') wx.offLazyLoadError(this._onLoadError);
    }
  },
  pageLifetimes: { show() { this.syncNavigation(); } },
  methods: {
    syncNavigation() {
      this.setData({ navMode: policy.isMerchantView({
        role: app.getUserRole(), userType: app.getUserType()
      }) ? 'merchant' : 'consumer' });
    },
    retryLoad() {
      if (!this._active || this.data.loading) return;
      const request = this._request = (this._request || 0) + 1;
      this.setData({ failed: false, loading: true });
      const finish = (failed) => {
        if (this._active && request === this._request) this.setData({ failed, loading: false });
      };
      try {
        require('../../../subpackageMember/profile-load.js', () => finish(false), () => finish(true));
      } catch (err) {
        finish(true);
      }
    },
    goBack() {
      wx.navigateBack({ fail() { wx.switchTab({ url: '/pages/member/index/index' }); } });
    }
  }
});
