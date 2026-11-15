// S5 店铺小游戏(商家):在适合本店类型的小游戏池里挑选,数量受套餐限制(免费版一个)。
// 读 POST /api/merchant/shop-games,保存 POST /api/merchant/shop-games/save。平台锁定时只读。
const app = getApp();
const toast = require('../../../utils/toast.js');
const merchantTheme = require('../../../utils/merchant-theme.js');
const { inactiveAccess, normalizeMerchantAccess } = require('../../../utils/merchant-access-policy.js');
const { shapeShopGames, selectedCount, saveBody } = require('./view-model.js');

Page({
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    pageState: 'loading',
    errorMessage: '',
    view: null,
    canEdit: false,
    selected: 0,
    saving: false,
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
  onUnload() { merchantTheme.merchantPageRestore(); },

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
        if (!access.active) { this.setData({ pageState: 'no-permission' }); return; }
        this.load();
      },
      fail: () => this.setData({ pageState: 'error', errorMessage: '网络异常，请重试' }),
    });
  },

  retry() {
    if (this._merchantAccess && this._merchantAccess.active) this.load();
    else this.loadAccess();
  },

  load() {
    this._seq = (this._seq || 0) + 1;
    const seq = this._seq;
    this.setData({ pageState: 'loading', errorMessage: '' });
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/merchant/shop-games',
      method: 'POST',
      data: {},
      success: (res) => {
        if (seq !== this._seq) return;
        const view = successObject(res) ? shapeShopGames(res.data) : null;
        if (!view) { this.setData({ pageState: 'error', errorMessage: errorText(res, '店铺小游戏加载失败') }); return; }
        this.show(view);
      },
      fail: () => { if (seq === this._seq) this.setData({ pageState: 'error', errorMessage: '网络异常，请重试' }); },
    });
  },

  show(view) {
    const canEdit = view.editable && !!(this._merchantAccess && this._merchantAccess.canManageProjects);
    this.setData({ pageState: 'ready', view, canEdit, selected: selectedCount(view.pool) });
  },

  toggleGame(e) {
    if (!this.data.canEdit || this.data.saving) return;
    const key = e.currentTarget.dataset.key;
    const pool = this.data.view.pool.map(g => Object.assign({}, g));
    const game = pool.find(g => g.key === key);
    if (!game || !game.enabled) return;
    if (!game.selected && selectedCount(pool) >= this.data.view.allowance) {
      toast('当前套餐最多可选 ' + this.data.view.allowance + ' 个小游戏');
      return;
    }
    game.selected = !game.selected;
    this.setData({ 'view.pool': pool, selected: selectedCount(pool) });
  },

  pickTemplate(e) {
    const key = e.currentTarget.dataset.key;
    const index = Number(e.detail.value);
    const pool = this.data.view.pool.map(g => Object.assign({}, g));
    const game = pool.find(g => g.key === key);
    if (!game || !game.options[index]) return;
    game.optionIndex = index;
    game.templateId = game.options[index].id;
    this.setData({ 'view.pool': pool });
  },

  save() {
    if (!this.data.canEdit || this.data.saving) return;
    if (this.data.selected === 0) { toast('请至少选一个小游戏'); return; }
    this.setData({ saving: true });
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      write: true,
      url: '/api/merchant/shop-games/save',
      method: 'POST',
      data: saveBody(this.data.view.pool),
      success: (res) => {
        this.setData({ saving: false });
        const view = successObject(res) ? shapeShopGames(res.data) : null;
        if (!view) { toast(errorText(res, '保存失败，请重试')); return; }
        this.show(view);
        toast('已保存');
      },
      fail: () => { this.setData({ saving: false }); toast('网络异常，请重试'); },
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
