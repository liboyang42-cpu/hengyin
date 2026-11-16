// S3a 商家套餐(小程序侧,T6 2026-11-15)。access/me 判 BASIC_READ → POST /api/merchant/plan。
// 页面:本店当前套餐 → 各套餐与包含的内容(顺序与文案同 App)。
// 购买只在服务端说开售(salesState OPEN,资金开关 merchantMoney 开)且是店主时出现;
// 走后端已有的 POST /api/merchant/plan/checkout:只记一笔待确认订单,平台确认收款后开通,不调任何支付渠道。
// 开关关着(生产默认)时按钮写「即将开放」,与今天一样不能买。
const app = getApp();
const toast = require('../../../utils/toast.js');
const modal = require('../../../utils/modal.js');
const merchantTheme = require('../../../utils/merchant-theme.js');
const { inactiveAccess, normalizeMerchantAccess } = require('../../../utils/merchant-access-policy.js');
const { shapePage, shapeOrder, newRequestId } = require('./view-model.js');

Page({
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    pageState: 'loading',
    errorMessage: '',
    page: null,
    isOwner: false,
    buying: '',
    order: null,
  },

  onLoad() {
    const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    this._merchantAccess = inactiveAccess();
    this.setData({
      statusBarHeight: info.statusBarHeight || 20,
      navBarHeight: (app.globalData && app.globalData.navBarHeight) || 44,
    });
    this.loadAccess();
  },

  onShow() { merchantTheme.merchantPageShow(); },
  onHide() { merchantTheme.merchantPageRestore(); },
  onUnload() { this._unloaded = true; merchantTheme.merchantPageRestore(); },

  onNavBack() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.reLaunch({ url: '/pages/merchant/index/index' });
  },

  loadAccess() {
    this.setData({ pageState: 'loading', errorMessage: '' });
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/merchant/access/me',
      method: 'POST',
      success: (res) => {
        if (!successObject(res)) {
          this.setData({ pageState: 'error', errorMessage: errorText(res, '经营身份加载失败') });
          return;
        }
        const access = normalizeMerchantAccess(res.data);
        this._merchantAccess = access;
        if (!access.active || !access.canReadBasic) {
          this.setData({ pageState: 'no-permission', page: null });
          return;
        }
        this.setData({ isOwner: access.roleCode === 'MERCHANT_OWNER' });
        this.loadPlans();
      },
      fail: () => this.setData({ pageState: 'error', errorMessage: '网络异常，请重试' }),
    });
  },

  retry() {
    if (this._merchantAccess && this._merchantAccess.canReadBasic) this.loadPlans();
    else this.loadAccess();
  },

  onPullDownRefresh() {
    const canRead = !!(this._merchantAccess && this._merchantAccess.canReadBasic);
    if (canRead && this.data.pageState !== 'loading') { this.loadPlans(true); return; }
    wx.stopPullDownRefresh();
  },

  loadPlans(fromPull) {
    this._seq = (this._seq || 0) + 1;
    const seq = this._seq;
    if (!fromPull) this.setData({ pageState: 'loading', errorMessage: '' });
    const failed = (res) => {
      if (fromPull) wx.stopPullDownRefresh();
      if (seq !== this._seq) return;
      if (fromPull && this.data.pageState === 'ready') { toast('刷新失败，稍后再试'); return; }
      this.setData({ pageState: 'error', errorMessage: res ? errorText(res, '套餐加载失败') : '网络异常，请重试' });
    };
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/merchant/plan',
      method: 'POST',
      header: { 'content-type': 'application/json' },
      data: {},
      success: (res) => {
        const shaped = successObject(res) ? shapePage(res.data) : null;
        if (!shaped) { failed(res); return; }
        if (fromPull) wx.stopPullDownRefresh();
        if (seq !== this._seq) return;
        this.setData({ pageState: 'ready', page: shaped });
      },
      successStatusAbnormal: (res, status) => {
        if (status === 403) { if (seq === this._seq) this.setData({ pageState: 'no-permission', page: null }); return; }
        failed(res);
      },
      fail: () => failed(null),
    });
  },

  onBuy(e) {
    const code = e.currentTarget.dataset.code;
    const page = this.data.page;
    const plan = page && page.plans.filter(p => p.code === code)[0];
    if (!plan || !plan.buyable || !page.salesOpen || !this.data.isOwner || this.data.buying) return;
    modal.show({
      title: '开通' + plan.name,
      content: plan.priceText + '。提交后由平台确认收款，确认后为本店开通。',
      showCancel: true, cancelText: '再想想', confirmText: '提交',
      success: (res) => { if (res && res.confirm) this.checkout(plan); },
    });
  },

  checkout(plan) {
    if (this.data.buying || this._unloaded) return;
    // 同一套餐的重试沿用同一个幂等号,网络不确定时不会记两笔
    if (!this._requestIds) this._requestIds = {};
    const requestId = this._requestIds[plan.code] || (this._requestIds[plan.code] = newRequestId());
    this.setData({ buying: plan.code });
    const done = (order, message, closed) => {
      if (this._unloaded) return;
      this.setData({ buying: '' });
      if (order) {
        delete this._requestIds[plan.code];
        this.setData({ order });
        toast.success('已提交');
        return;
      }
      if (closed) { this.loadPlans(true); toast('现在还不能购买套餐'); return; }
      toast(message || '没有提交成功，请稍后再试');
    };
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/merchant/plan/checkout',
      method: 'POST',
      header: { 'content-type': 'application/json' },
      data: { planCode: plan.code, months: 1, requestId },
      success: (res) => {
        const order = successObject(res) ? shapeOrder(res.data) : null;
        done(order, order ? '' : errorText(res, ''), !!(res && res.errorCode === 'PLAN_SALES_CLOSED'));
      },
      successStatusAbnormal: (res) => {
        done(null, errorText(res, ''), !!(res && res.errorCode === 'PLAN_SALES_CLOSED'));
      },
      fail: () => done(null, '网络异常，请稍后再试'),
    });
  },
});

function isSuccess(res) {
  return !!(res && (res.code === 200 || res.code === '200'));
}

function successObject(res) {
  return !!(isSuccess(res) && res.data && typeof res.data === 'object' && !Array.isArray(res.data));
}

function errorText(res, fallback) {
  return (app.getRequestErrorMessage && app.getRequestErrorMessage(res, fallback)) || fallback;
}
