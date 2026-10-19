// 商家「已接待团」(X034):回读本店扫团码登记过的接待记录,结算开始前可撤销误扫/扫错团。
// 权限与扫码同口径(merchant:verify),归属与时限只由服务端判定,页面只做展示与入口收敛。
const app = getApp();
const modal = require('../../../utils/modal.js');
const toast = require('../../../utils/toast.js');
const merchantTheme = require('../../../utils/merchant-theme.js');
const merchantAccessPolicy = require('../../../utils/merchant-access-policy.js');
const { normalizeReceptions, checkRevokeReason } = require('../../../utils/group-reception.js');

function isOk(res) {
  return !!res && (res.code === '200' || res.code === 200);
}

Page({
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    accessState: 'loading', // loading | ready | error | no-permission
    accessErrorText: '',
    listState: 'loading', // loading | ready | error
    listErrorText: '',
    items: [],
    revokingId: 0,
  },

  onLoad() {
    const sys = wx.getSystemInfoSync();
    this.setData({
      statusBarHeight: sys.statusBarHeight || 20,
      navBarHeight: app.globalData.navBarHeight || 44,
    });
  },

  onShow() {
    merchantTheme.merchantPageShow();
    this.loadAccess();
  },

  onHide() { merchantTheme.merchantPageRestore(); },
  onUnload() { merchantTheme.merchantPageRestore(); },

  onNavBack() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.reLaunch({ url: '/pages/merchant/index/index' });
  },

  nextEpoch() {
    this._epoch = (this._epoch || 0) + 1;
    return this._epoch;
  },

  loadAccess() {
    const that = this;
    const epoch = this.nextEpoch();
    this.setData({ accessState: 'loading', accessErrorText: '' });
    app.sendRequest({
      url: '/api/merchant/access/me', method: 'POST', hideLoading: true, silentError: true,
      success(res) {
        if (epoch !== that._epoch) return;
        if (!isOk(res)) {
          that.setData({ accessState: 'error', accessErrorText: (res && res.msg) || '商家权限没能读取' });
          return;
        }
        const access = merchantAccessPolicy.normalizeMerchantAccess(res.data);
        if (!access.active || !access.canVerify) {
          that.setData({ accessState: 'no-permission' });
          return;
        }
        that.setData({ accessState: 'ready' });
        that.loadList(epoch);
      },
      fail() {
        if (epoch !== that._epoch) return;
        that.setData({ accessState: 'error', accessErrorText: '网络异常，商家权限没能读取' });
      },
      successStatusAbnormal() {
        if (epoch !== that._epoch) return;
        that.setData({ accessState: 'error', accessErrorText: '商家权限没能读取' });
      },
    });
  },

  loadList(epoch) {
    const that = this;
    const current = epoch || this._epoch;
    this.setData({ listState: 'loading', listErrorText: '' });
    app.sendRequest({
      url: '/api/verify/groupcode/receptions', method: 'GET', hideLoading: true, silentError: true,
      success(res) {
        if (current !== that._epoch) return;
        const items = isOk(res) ? normalizeReceptions(res.data) : null;
        if (!items) {
          that.setData({ listState: 'error', listErrorText: (res && res.msg) || '接待记录没能加载' });
          return;
        }
        that.setData({ listState: 'ready', items });
      },
      fail() {
        if (current !== that._epoch) return;
        that.setData({ listState: 'error', listErrorText: '网络异常，接待记录没能加载' });
      },
      successStatusAbnormal(res) {
        if (current !== that._epoch) return;
        that.setData({ listState: 'error', listErrorText: (res && res.msg) || '接待记录没能加载' });
      },
    });
  },

  retry() {
    if (this.data.accessState !== 'ready') this.loadAccess();
    else this.loadList();
  },

  onRevoke(e) {
    const id = Number(e.currentTarget.dataset.id);
    const item = this.data.items.find((it) => it.id === id);
    if (!item || !item.canRevoke || this.data.revokingId) return;
    const that = this;
    modal.show({
      title: '撤销这条接待记录？',
      content: '撤销后该团不计入本店分账；如确实接待过，可请领队重新出码再扫。',
      editable: true,
      placeholderText: '撤销原因，如：误扫、扫错了团',
      confirmText: '继续',
      cancelText: '保留',
      danger: true,
      validate: checkRevokeReason,
      success(r) {
        if (!r || !r.confirm) return;
        const reason = String(r.content || '').trim();
        modal.show({
          dangerKey: 'merchant.group-reception.revoke',
          cancelText: '保留',
          success(confirmation) {
            if (confirmation && confirmation.confirm) that.submitRevoke(id, reason);
          },
        });
      },
    });
  },

  submitRevoke(id, reason) {
    const that = this;
    const epoch = this._epoch;
    this.setData({ revokingId: id });
    app.sendRequest({
      url: '/api/verify/groupcode/revoke', method: 'POST', hideLoading: true, silentError: true,
      data: { verifyId: id, reason },
      success(res) {
        if (epoch !== that._epoch) return;
        that.setData({ revokingId: 0 });
        if (isOk(res)) {
          toast('已撤销该团的接待记录');
          that.loadList();
          return;
        }
        // 服务端拒绝(已进入结算 / 已被撤销)时以服务端为准,重新回读列表状态。
        toast((res && res.msg) || '撤销没有成功，请稍后重试');
        that.loadList();
      },
      fail() {
        if (epoch !== that._epoch) return;
        that.setData({ revokingId: 0 });
        toast('网络异常，请刷新后确认');
      },
      successStatusAbnormal(res) {
        if (epoch !== that._epoch) return;
        that.setData({ revokingId: 0 });
        toast((res && res.msg) || '撤销没有成功，请稍后重试');
      },
    });
  },
});
