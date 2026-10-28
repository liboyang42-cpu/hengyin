const toast = require('../../../../utils/toast.js');
const app = getApp();
const merchantTheme = require('../../../../utils/merchant-theme.js');
const { memberId, failureStatus, retryLogin } = require('../../../../utils/session/login-reload.js');

function galleryIdentity() {
  const owner = memberId(app);
  const authorization = typeof app.getAuthorization === 'function' ? app.getAuthorization() : (app.globalData || {}).authorization;
  return owner && authorization ? JSON.stringify([owner, authorization]) : '';
}

function requestText(error, fallback) {
  if (app.getRequestErrorMessage) return app.getRequestErrorMessage(error, fallback);
  return error && error.msg ? String(error.msg) : fallback;
}

function classifyFailure(error, fallback) {
  const code = error && (error.statusCode !== undefined ? error.statusCode : error.code);
  const message = error && error.msg ? String(error.msg) : '';
  if (String(code) === '401' || String(code) === '403'
      || /仅.*商家|商家资格|无权限|没有权限|权限不足|审核通过.*商家/.test(message)) {
    return { kind: 'permission', text: '当前账号没有店铺编辑权限，请切换到已审核通过的商家账号。' };
  }
  const transportText = error && typeof error === 'object' ? String(error.errMsg || '') : '';
  if (code === undefined && /request:fail|timeout|network|网络|断网/i.test(transportText)) {
    return { kind: 'network', text: '网络异常，请重试。' };
  }
  return { kind: 'data', text: requestText(error, fallback) };
}

Page({
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    loadState: 'loading',
    refreshing: false,
    loadErrorKind: '',
    loadError: '',
    gallery: [],
    saving: false,
    saveErrorKind: '',
    saveError: '',
    saveReceipt: '',
    discardVisible:false,
    dirty: false
  },

  onLoad() {
    const sys = wx.getSystemInfoSync();
    this.setData({
      statusBarHeight: sys.statusBarHeight || 20,
      navBarHeight: app.globalData.navBarHeight || 44
    });
    this.load();
  },
  onShow() {
    this._hidden = false;
    merchantTheme.merchantPageShow && merchantTheme.merchantPageShow();
    const changed = this._syncIdentity();
    if (changed) {
      const gate = this.selectComponent && this.selectComponent('#cy-access-gate');
      if (gate && gate.check) gate.check();
    }
    if (this._saveOutcome) {
      const settle = this._saveOutcome; this._saveOutcome = null; settle();
    }
    if (this._galleryIdentity && (changed || this._resumeRead)) this.load();
  },
  onHide() {
    this._hidden = true;
    this._loginEpoch = (this._loginEpoch || 0) + 1;
    this._resumeRead = this._resumeRead || this._loadInFlight;
    this._loadEpoch = (this._loadEpoch || 0) + 1;
    this._loadInFlight = false;
    this.setData({ refreshing: false });
    merchantTheme.merchantPageRestore && merchantTheme.merchantPageRestore();
  },
  onUnload() {
    this._unloaded = true;
    this._loadEpoch = (this._loadEpoch || 0) + 1;
    this._saveEpoch = (this._saveEpoch || 0) + 1;
    this._loginEpoch = (this._loginEpoch || 0) + 1;
    this._loadInFlight = false;
    this._saveOutcome = null;
    if (this._successTimer) clearTimeout(this._successTimer);
    merchantTheme.merchantPageRestore && merchantTheme.merchantPageRestore();
  },

  _syncIdentity() {
    const owner = galleryIdentity();
    if (owner === this._galleryIdentity) return false;
    this._galleryIdentity = owner;
    this._identityEpoch = (this._identityEpoch || 0) + 1;
    this._dataIdentity = null;
    this._loadEpoch = (this._loadEpoch || 0) + 1;
    this._saveEpoch = (this._saveEpoch || 0) + 1;
    this._loginEpoch = (this._loginEpoch || 0) + 1;
    this._loadInFlight = false;
    this._resumeRead = false;
    this._saveOutcome = null;
    if (this._successTimer) clearTimeout(this._successTimer);
    this.setData({ gallery: [], dirty: false, saving: false, refreshing: false,
      loadState: owner ? 'loading' : 'login', loadErrorKind: '', loadError: '',
      saveErrorKind: '', saveError: '', saveReceipt: '', discardVisible: false });
    return true;
  },
  _currentIdentity(owner) {
    if (this._unloaded) return false;
    if (galleryIdentity() !== this._galleryIdentity) {
      this._syncIdentity();
      if (!this._hidden && this._galleryIdentity) this.load();
      return false;
    }
    return !!owner && owner === this._galleryIdentity;
  },
  _canEdit() {
    return this._currentIdentity(this._dataIdentity) && !this._hidden
      && this.data.loadState === 'ready' && !this.data.saving && !this.data.refreshing;
  },
  onLogin() {
    const epoch = this._loginEpoch || 0;
    retryLogin(this, app, () => { if (epoch === (this._loginEpoch || 0)) this.load(); });
  },

  keepEditing() { this.setData({discardVisible:false}); },
  discardChanges() { this.setData({dirty:false,discardVisible:false});this.onNavBack(); },
  onNavBack() {
    if(this.data.saving)return;
    if(this.data.dirty)return this.setData({discardVisible:true});
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.redirectTo({ url: '/pages/merchant/decor/index' });
  },

  load() {
    if (this._unloaded || this._hidden) return;
    this._syncIdentity();
    if (!this._galleryIdentity || this._loadInFlight || this.data.saving) return;
    this._resumeRead = false;
    const owner = this._galleryIdentity;
    const that = this;
    const epoch = (this._loadEpoch || 0) + 1;
    this._loadEpoch = epoch;
    this._loadInFlight = true;
    const hasOldContent = this.data.loadState === 'ready';
    const isCurrent = function () {
      if (epoch !== that._loadEpoch) return false;
      return !that._hidden && that._currentIdentity(owner);
    };
    const finish = function () {
      if (!isCurrent()) return false;
      that._loadInFlight = false;
      return true;
    };
    const fail = function (error, fallback) {
      if (!isCurrent()) return;
      const status = failureStatus(error);
      const failure = classifyFailure(error, fallback);
      if (status === 401 || status === 2 || failure.kind === 'permission') {
        if (!finish()) return;
        that._dataIdentity = null;
        that.setData({ refreshing: false, loadState: status === 401 || status === 2 ? 'login' : 'permission', loadErrorKind: '', loadError: '', gallery: [], dirty: false });
      } else if (hasOldContent) {
        if (!finish()) return;
        that.setData({ refreshing: false, loadState: 'ready', loadErrorKind: failure.kind, loadError: failure.text });
      } else {
        if (!finish()) return;
        that.setData({ refreshing: false, loadState: 'error', loadErrorKind: failure.kind, loadError: failure.text });
      }
    };
    this.setData({
      loadState: hasOldContent ? 'ready' : 'loading',
      refreshing: hasOldContent,
      loadErrorKind: '',
      loadError: '',
    });
    app.sendRequest({
      hideLoading: true, autoErrorToast: false, url: '/api/merchant/coop-profile', method: 'POST',
      success(res) {
        if (!isCurrent()) return;
        const ok = res.code === '200' || res.code === 200;
        if (!ok) return fail(res, '门店相册加载失败，请重试。');
        if (!res.data || typeof res.data !== 'object' || Array.isArray(res.data)) {
          return fail({ msg: '相册加载失败' }, '相册加载失败，请重试。');
        }
        const memberId = Number(res.data.memberId);
        if (!Number.isInteger(memberId) || memberId < 1) {
          return fail({ msg: '相册加载失败' }, '相册加载失败，请重试。');
        }
        let gallery = [];
        try { gallery = res.data.gallery ? JSON.parse(res.data.gallery) : []; } catch (e) {
          return fail({ msg: '相册加载失败' }, '相册加载失败');
        }
        if (!Array.isArray(gallery)) return fail({ msg: '相册加载失败' }, '相册加载失败');
        if (!finish()) return;
        that._dataIdentity = owner;
        that.setData({ refreshing: false, gallery: gallery, loadState: 'ready', loadErrorKind: '', loadError: '', dirty: false });
      },
      fail(error, statusCode) { fail(statusCode ? Object.assign({}, error, { code: statusCode }) : error, '门店相册加载失败，请重试。'); },
      successStatusAbnormal(error, statusCode) { fail(statusCode ? Object.assign({}, error, { code: statusCode }) : error, '门店相册加载失败，请重试。'); }
    });
  },

  retryLoad() { this.load(); },
  goApply() { wx.redirectTo({ url: '/pages/merchant/apply/index' }); },

  addGallery() {
    if (!this._canEdit()) return;
    const remain = 9 - this.data.gallery.length;
    if (remain <= 0) return toast('最多 9 张');
    const that = this;
    const owner = this._dataIdentity;
    const epoch = this._identityEpoch;
    app.chooseImage(function (images) {
      if (!that._currentIdentity(owner) || that._hidden || epoch !== that._identityEpoch
          || !images || !images.length || !that._canEdit()) return;
      that.setData({ gallery: that.data.gallery.concat(images).slice(0, 9), dirty: true });
    }, remain, { cropScale: '16:9' });
  },

  removeGallery(e) {
    if (!this._canEdit()) return;
    const rawIndex = e && e.currentTarget && e.currentTarget.dataset
      ? e.currentTarget.dataset.index
      : undefined;
    const index = Number(rawIndex);
    if (rawIndex === undefined || rawIndex === null || rawIndex === ''
        || !Number.isInteger(index) || index < 0 || index >= this.data.gallery.length) {
      this.setData({ saveErrorKind: 'data', saveError: '没有找到要删除的图片，请重新选择。' });
      return;
    }
    const gallery = this.data.gallery.slice();
    gallery.splice(index, 1);
    this.setData({ gallery: gallery, dirty: true });
  },

  save() {
    if (!this._canEdit() || !this.data.dirty) return;
    const owner = this._dataIdentity;
    if (this._loadInFlight) {
      this._loadEpoch = (this._loadEpoch || 0) + 1;
      this._loadInFlight = false;
    }
    const that = this;
    const epoch = (this._saveEpoch || 0) + 1;
    this._saveEpoch = epoch;
    const isCurrent = function () {
      if (epoch !== that._saveEpoch) return false;
      return that._currentIdentity(owner);
    };
    const settle = function (apply) {
      if (!isCurrent()) return;
      if (that._hidden) { that._saveOutcome = () => { if (isCurrent()) apply(); }; return; }
      apply();
    };
    const fail = function (error) {
      settle(() => {
        const failure = classifyFailure(error, '相册保存失败');
        const status = failureStatus(error);
        if (status === 401 || status === 2 || failure.kind === 'permission') {
          that._dataIdentity = null;
          that.setData({ gallery: [], dirty: false, saving: false,
            loadState: status === 401 || status === 2 ? 'login' : 'permission', saveErrorKind: '', saveError: '' });
          return;
        }
        that.setData({ saving: false, saveErrorKind: failure.kind, saveError: failure.text });
      });
    };
    this.setData({ saving: true, refreshing: false, saveErrorKind: '', saveError: '', saveReceipt: '' });
    app.sendRequest({
      url: '/api/merchant/decor/save', method: 'POST',
      data: JSON.stringify({ gallery: JSON.stringify(this.data.gallery) }),
      header: { 'Content-Type': 'application/json' },
      success(res) {
        if (!isCurrent()) return;
        if (res.code === '200' || res.code === 200) {
          settle(() => {
            that.setData({ saving: false, dirty: false, saveErrorKind: '', saveError: '', saveReceipt: '门店相册已保存' });
            toast.success('已保存');
            that._successTimer = setTimeout(function () {
              if (isCurrent() && !that._hidden) that.onNavBack();
            }, 400);
          });
        } else {
          fail(res);
        }
      },
      fail(error, statusCode) { fail(statusCode ? Object.assign({}, error, { code: statusCode }) : error); },
      successStatusAbnormal(error, statusCode) { fail(statusCode ? Object.assign({}, error, { code: statusCode }) : error); }
    });
  }
});
