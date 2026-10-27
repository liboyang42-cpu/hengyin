const toast = require('../../utils/toast.js');
const modal = require('../../utils/modal.js');
const { VERSIONS } = require('../../utils/compliance/usage-consent.js');
const app = getApp();
const presencePrivacy = require('../../utils/roam-presence-privacy.js');
const bgTracker = require('../../utils/location/bg-tracker.js');
const { reader, sameReader } = require('../../utils/user-character.js');

Page({
  _mediaBeforeId: '',
  data: {
    submitting: false,
    homeCityBusy: false,
    homeCityError: '',
    mediaRows: [],
    mediaLoading: false,
    mediaHasMore: false, mediaOpen: false, // 设置态「我的待审媒体」默认收起
    mediaBusyId: '',
    mediaError: '',
    mediaAuthRequired: false,
    mediaAccessDenied: false,
    mediaPreviewUrl: '',
    mediaPreviewType: '',

    serviceAgreed: false, // 进入弹窗:一行勾选同意两份文件后才能点「同意并继续」
    showServiceSheet: false,
    submitError: '',
    failedAction: '',
    roamCollectionText: '',
    pendingAuthorization: false,
    scrollInto: '',
    toc: [
      { id: 'sec-collect', title: '我们如何收集与使用信息' },
      { id: 'sec-official', title: '用户隐私保护指引' },
      { id: 'sec-roam', title: '漫游定位' },
      { id: 'sec-choice', title: '你的选择' },
    ],
  },

  onLoad() {
    this.setData({ pendingAuthorization: app.hasPendingPrivacyAuthorization() });
  },

  onShow() {
    this._roamRevokeHidden = false;
    if (this._roamRevokeReader && (this._roamRevokeInFlight || this.data.failedAction === 'revoke')
        && !sameReader(app, this._roamRevokeReader)) {
      this._roamRevokeEpoch = (this._roamRevokeEpoch || 0) + 1;
      this._roamRevokeReader = null;
      this._roamRevokeInFlight = false;
      this.data.failedAction = '';
      this.setData({ submitting: false, submitError: '' });
    } else if (this._roamRevokeInFlight) {
      this._roamRevokeEpoch = (this._roamRevokeEpoch || 0) + 1;
      this._roamRevokeInFlight = false;
      this.data.failedAction = 'revoke';
      this.setData({ submitting: false, submitError: '本机定位已停，撤回记录待确认，请重试' });
    }
    this.syncRoamCollection();
    this._homeCityConsentEpoch = (this._homeCityConsentEpoch || 0) + 1;
    this.setData({ homeCityBusy: false, homeCityError: '' });
    this._mediaHidden = false;
    if (!this.data.pendingAuthorization) this.loadOwnMedia();
  },
  onHide() { this.invalidateRoamRevoke(); this.clearMediaState(); },

  invalidateRoamRevoke() {
    this._roamRevokeHidden = true;
    this._roamRevokeEpoch = (this._roamRevokeEpoch || 0) + 1;
  },

  clearMediaState() {
    this._mediaHidden = true;
    this._mediaEpoch = (this._mediaEpoch || 0) + 1;
    if (this._mediaDownload && this._mediaDownload.abort) this._mediaDownload.abort();
    this._mediaDownload = null;
    this._mediaRowsScope = null;
    this._mediaBeforeId = '';
    this.setData({ mediaRows: [], mediaLoading: false, mediaHasMore: false, mediaBusyId: '', mediaError: '', mediaPreviewUrl: '', mediaPreviewType: '' });
  },
  mediaScope() {
    return { epoch: this._mediaEpoch || 0, owner: String(app.getUserID ? app.getUserID() || '' : ''),
      token: app.getAuthorization ? app.getAuthorization() || '' : '' };
  },
  mediaScopeLive(scope) {
    const now = this.mediaScope();
    return !this._mediaHidden && scope.epoch === now.epoch && scope.owner === now.owner && scope.token === now.token;
  },
  requestOwnMedia(url, method, scope, done) {
    let settled = false;
    const receive = (res, status) => {
      if (settled) return;
      if (!this.mediaScopeLive(scope)) {
        if (!this._mediaHidden && scope.epoch === (this._mediaEpoch || 0)) {
          this.clearMediaState(); this._mediaHidden = false;
          this.setData({ mediaAuthRequired: !Number(this.mediaScope().owner) || !this.mediaScope().token });
        }
        return;
      }
      settled = true;
      const code = Number(status == null ? res && res.code : status);
      if (code === 401 || code === 2 || code === 403) {
        this._mediaEpoch = (this._mediaEpoch || 0) + 1;
        this._mediaBeforeId = '';
        this.setData({ mediaRows: [], mediaLoading: false, mediaHasMore: false, mediaBusyId: '', mediaPreviewUrl: '', mediaPreviewType: '',
          mediaAuthRequired: code !== 403, mediaAccessDenied: code === 403,
          mediaError: code === 403 ? '暂时无权查看媒体，请刷新重试' : '请重新登录查看本人媒体' });
        return;
      }
      done(res && Number(res.code) === 200 ? null : new Error('媒体信息加载失败，请重试'), res && res.data);
    };
    try { app.sendRequest({ url, method, hideLoading: true, silentError: true,
      success: receive, fail: (res, status) => {
        receive(res, status);
        if (this.mediaScopeLive(scope)) this.setData({ mediaError: '媒体请求失败，请刷新后重试' });
      } }); }
    catch (error) { receive({ code: 500 }); }
  },
  loadOwnMedia() {
    const scope = this.mediaScope();
    if (this._mediaHidden || this.data.pendingAuthorization) return;
    this._mediaEpoch = (this._mediaEpoch || 0) + 1;
    scope.epoch = this._mediaEpoch;
    if (!Number(scope.owner) || !scope.token) {
      this._mediaBeforeId = '';
      this.setData({ mediaRows: [], mediaLoading: false, mediaHasMore: false, mediaAuthRequired: true, mediaAccessDenied: false });
      return;
    }
    this._mediaBeforeId = '';
    this.setData({ mediaRows: [], mediaLoading: true, mediaHasMore: false, mediaError: '', mediaAuthRequired: false, mediaAccessDenied: false,
      mediaPreviewUrl: '', mediaPreviewType: '' });
    this.requestOwnMedia('/api/media/asset/list', 'GET', scope, (error, rows) => {
      this.receiveOwnMediaPage(error, rows, false, '', scope);
    });
  },
  validMediaId(id) {
    const value = String(id);
    return /^[1-9][0-9]{0,18}$/.test(value) && (value.length < 19 || value <= '9223372036854775807');
  },
  mediaIdBefore(id, cursor) {
    return id.length < cursor.length || (id.length === cursor.length && id < cursor);
  },
  receiveOwnMediaPage(error, rows, append, cursor, scope) {
    if (error || !Array.isArray(rows) || rows.length > 50 || rows.some((row, index) => {
      const id = String(row && row.assetId);
      return !row || (typeof row.assetId === 'number' && !Number.isSafeInteger(row.assetId)) || !this.validMediaId(id) || !['HOLD', 'APPROVED', 'REJECTED', 'UNAVAILABLE'].includes(row.reviewState)
        || (append && !this.mediaIdBefore(id, cursor)) || (index > 0 && !this.mediaIdBefore(id, String(rows[index - 1].assetId)));
    })) {
      this.setData({ mediaLoading: false, mediaError: '媒体列表加载失败，请重试' }); return;
    }
    this._mediaRowsScope = scope;
    const labels = { HOLD: '待审核', APPROVED: '已通过', REJECTED: '未通过', UNAVAILABLE: '审核暂不可用' };
    const types = { IMAGE: '图片', AUDIO: '音频', MODEL3D: '3D 模型' };
    const mapped = rows.map(row => ({ assetId: String(row.assetId), reviewState: row.reviewState,
      reviewReason: String(row.reviewReason || ''), stateLabel: labels[row.reviewState], typeLabel: types[row.mediaType] || '媒体',
      mediaType: row.mediaType }));
    this._mediaBeforeId = mapped.length ? mapped[mapped.length - 1].assetId : cursor;
    this.setData({ mediaLoading: false, mediaRows: append ? this.data.mediaRows.concat(mapped) : mapped,
      mediaHasMore: rows.length === 50 });
  },
  loadOlderMedia() {
    if (this._mediaHidden || this.data.pendingAuthorization || this.data.mediaLoading || this.data.mediaBusyId
        || !this.data.mediaHasMore || !this.validMediaId(this._mediaBeforeId)) return;
    if (!this._mediaRowsScope || !this.mediaScopeLive(this._mediaRowsScope)) {
      this.clearMediaState(); this._mediaHidden = false; this.loadOwnMedia(); return;
    }
    const scope = this.mediaScope();
    if (!Number(scope.owner) || !scope.token) { this.clearMediaState(); this._mediaHidden = false; this.setData({ mediaAuthRequired: true }); return; }
    const cursor = this._mediaBeforeId;
    this.setData({ mediaLoading: true, mediaError: '' });
    this.requestOwnMedia('/api/media/asset/list?beforeId=' + encodeURIComponent(cursor), 'GET', scope, (error, rows) => {
      this.receiveOwnMediaPage(error, rows, true, cursor, scope);
    });
  },
  onMediaLogin() {
    if (this._mediaLoginPending) return;
    const epoch = this._mediaEpoch || 0;
    this._mediaLoginPending = true;
    let login;
    try { login = Number(app.getUserID()) > 0 ? app.reLogin() : app.firstLogin(); }
    catch (error) { this._mediaLoginPending = false; return; }
    Promise.resolve(login).then(result => {
      this._mediaLoginPending = false;
      if (!this._mediaHidden && epoch === (this._mediaEpoch || 0) && !(result && result.ok === false)) this.loadOwnMedia();
    }).catch(() => { this._mediaLoginPending = false; });
  },
  ownMediaFromEvent(event) {
    const id = String(event && event.currentTarget && event.currentTarget.dataset.id || '');
    return this.data.mediaRows.find(row => row.assetId === id);
  },
  retryOwnMedia(event) {
    const row = this.ownMediaFromEvent(event);
    if (!row || this.data.mediaBusyId || !['REJECTED', 'UNAVAILABLE'].includes(row.reviewState)) return;
    const scope = this.mediaScope();
    this.setData({ mediaBusyId: row.assetId, mediaError: '' });
    this.requestOwnMedia('/api/media/asset/' + row.assetId + '/retry', 'POST', scope, (error, result) => {
      this.setData({ mediaBusyId: '' });
      if (error || !result || result.reviewState !== 'HOLD') { this.setData({ mediaError: '重新提审失败，请重试' }); return; }
      this.loadOwnMedia();
    });
  },
  checkOwnMedia(event) {
    const row = this.ownMediaFromEvent(event);
    if (!row || this.data.mediaBusyId) return;
    const scope = this.mediaScope();
    this.setData({ mediaBusyId: row.assetId, mediaError: '' });
    this.requestOwnMedia('/api/media/asset/' + row.assetId, 'GET', scope, (error, result) => {
      this.setData({ mediaBusyId: '' });
      if (error || !result || !['HOLD', 'APPROVED', 'REJECTED', 'UNAVAILABLE'].includes(result.reviewState)) {
        this.setData({ mediaError: '状态查询失败，请重试' }); return;
      }
      this.loadOwnMedia();
    });
  },
  previewOwnMedia(event) {
    const row = this.ownMediaFromEvent(event);
    if (!row || this.data.mediaBusyId) return;
    const scope = this.mediaScope();
    this.setData({ mediaBusyId: row.assetId, mediaError: '', mediaPreviewUrl: '', mediaPreviewType: '' });
    this.requestOwnMedia('/api/media/asset/' + row.assetId + '/preview', 'GET', scope, (error, result) => {
      this.setData({ mediaBusyId: '' });
      const url = result && result.authorPreviewUrl;
      if (error || typeof url !== 'string' || !/^https?:\/\//.test(url)) {
        this.setData({ mediaError: '本人预览加载失败，请重试' }); return;
      }
      this.setData({ mediaPreviewUrl: url, mediaPreviewType: row.mediaType });
    });
  },
  mediaPreviewFailed() {
    if (this._mediaHidden) return;
    this.setData({ mediaPreviewUrl: '', mediaPreviewType: '', mediaError: '预览失败，请刷新后重试；文件仍仅本人可见' });
  },
  rotateMediaModel() {
    const viewer = this.selectComponent('#authorMediaModel');
    if (viewer && typeof viewer.rotate === 'function') viewer.rotate();
  },
  closeMediaPreview() { this.setData({ mediaPreviewUrl: '', mediaPreviewType: '' }); }, toggleMediaPanel() { this.setData({ mediaOpen: !this.data.mediaOpen }); },
  copyApprovedMedia(event) {
    const row = this.ownMediaFromEvent(event);
    if (!row || row.reviewState !== 'APPROVED' || this.data.mediaBusyId) return;
    const scope = this.mediaScope();
    this.setData({ mediaBusyId: row.assetId, mediaError: '' });
    this.requestOwnMedia('/api/media/asset/' + row.assetId, 'GET', scope, (error, result) => {
      this.setData({ mediaBusyId: '' });
      if (error || !result || result.reviewState !== 'APPROVED' || typeof result.url !== 'string' || !/^https?:\/\//.test(result.url)) {
        this.setData({ mediaError: '已审链接暂不可用，请查询状态或重试' }); return;
      }
      wx.setClipboardData({ data: result.url });
    });
  },

  // ⚠️ 这是一个「伪弹窗」——它是被 navigateTo 压进来的独立页面,不是页内组件。
  // 后果:页面上那个「待决策态不给关闭叉号」的强制闸只挡得住叉号,挡不住**系统返回**
  // (左滑 / 物理返回键 / 微信胶囊返回)。系统退页时既不走 onClose 也不走同意按钮,
  // 于是 app._privacyResolvers 里的 resolve 一个都不会被调用 ⇒ 那个 Promise 永不 settle
  // ⇒ 之后所有需要隐私授权的接口静默挂起,无报错无提示。
  // 页内真弹窗没有这个出口,所以不存在这个洞;在迁成真弹窗之前,这里必须兜底。
  onUnload() {
    this.invalidateRoamRevoke();
    this.clearMediaState();
    if (!app.hasPendingPrivacyAuthorization()) return;
    // 用户用系统返回离开了授权闸 = 没有作出同意。按「不同意」结算,让调用方拿到确定结果,
    // 而不是永远等下去。不同意不是猜测,是这个动作唯一安全的解释。
    app.declineUsageConsent();
    app.resolvePrivacyAuthorization({ event: 'disagree', reason: 'page_unload' });
  },

  onTocTap(e) {
    const id = e.currentTarget.dataset.id;
    if (!id) return;
    this.setData({ scrollInto: '' }, () => {
      this.setData({ scrollInto: id });
    });
  },

  // 撤回态弹窗右上角关闭:等价于原来 cy-nav-bar 默认返回箭头的行为,不触发任何同意/撤回记录
  onClose() {
    wx.navigateBack({
      fail() { wx.reLaunch({ url: '/pages/shezhi/shezhi' }); },
    });
  },

  toggleService(event) { this.setData({ serviceAgreed: !!event.detail.value.length }); },
  openService() { this.setData({ showServiceSheet: true }); },
  closeService() { this.setData({ showServiceSheet: false }); },
  openPrivacyContract() {
    wx.openPrivacyContract({
      fail() {
        toast('隐私保护指引暂不可用');
      },
    });
  },

  agreePrivacy(event) {
    if (this.data.submitting) return;
    this._lastPrivacyButtonId = event && event.detail && event.detail.buttonId;
    this.data.failedAction = '';
    this.setData({ submitting: true, submitError: '' });
    app.agreeUsageConsent(event, this.data.serviceAgreed).then(() => {
      wx.navigateBack({
        fail() { wx.switchTab({ url: '/pages/index/index' }); },
      });
    }).catch(() => {
      // F-L5C-10:同 cy-privacy-gate —— 本机已记下明确同意时,留痕待登录后自动补传,不把用户卡在本页。
      if (app.getUsageConsent && app.getUsageConsent().granted()) {
        toast('已同意，联网后会自动补记同意记录');
        wx.navigateBack({
          fail() { wx.switchTab({ url: '/pages/index/index' }); },
        });
        return;
      }
      Object.assign(this.data, { failedAction: 'agree' });
      this.setData({
        submitting: false,
        submitError: '同意记录失败，请检查网络后重试',
      });
    });
  },

  disagreePrivacy() {
    // 防重复:按钮收编 cy-btn 后视觉 disabled 不拦点击,提交中须早退(原靠原生 <button disabled> 拦)
    if (this.data.submitting) return;
    app.declineUsageConsent();
    app.resolvePrivacyAuthorization({ event: 'disagree' });
    wx.navigateBack({
      fail() { wx.switchTab({ url: '/pages/index/index' }); },
    });
  },

  enableHomeCityLocation() { this.setHomeCityLocation(true); },
  disableHomeCityLocation() { this.setHomeCityLocation(false); },
  setHomeCityLocation(enabled) {
    if (this._mediaHidden || this.data.pendingAuthorization || this.data.homeCityBusy || this.data.submitting) return;
    const scope = this.mediaScope();
    if (!(Number(scope.owner) > 0) || !scope.token || scope.token === 'dev-token') {
      toast('请先登录再设置首页定位'); return;
    }
    const epoch = this._homeCityConsentEpoch || 0;
    const current = () => {
      const now = this.mediaScope();
      return !this._mediaHidden && !this.data.pendingAuthorization
        && epoch === (this._homeCityConsentEpoch || 0)
        && scope.owner === now.owner && scope.token === now.token;
    };
    const finish = () => {
      if (!this._mediaHidden && epoch === (this._homeCityConsentEpoch || 0)) this.setData({ homeCityBusy: false });
    };
    const failed = () => {
      if (current()) this.setData({ homeCityError: '首页城市定位设置未保存，请重试' });
      finish();
    };
    const eventType = enabled ? 'AGREE' : 'REVOKE';
    const save = () => {
      Promise.resolve().then(() => {
        if (!current()) return null;
        return app.recordConsent({ docType: 'privacy_policy', scene: 'home_city_location', eventType });
      }).then(saved => {
        if (!current()) return;
        const row = saved && saved.response && saved.response.data;
        if (!row || !saved.response || Number(saved.response.code) !== 200
            || typeof saved.requestId !== 'string' || !saved.requestId.trim() || saved.requestId !== row.requestId
            || row.scopeType != null || row.scopeId != null
            || row.docType !== 'privacy_policy' || row.scene !== 'home_city_location'
            || row.docVersion !== VERSIONS.privacy_policy || row.eventType !== eventType) {
          throw new Error('首页定位同意记录未完成');
        }
        toast(enabled ? '已开启首页城市自动定位' : '已关闭首页城市自动定位');
      }).catch(failed).finally(finish);
    };
    this.setData({ homeCityBusy: true, homeCityError: '' });
    if (!enabled) { save(); return; }
    try {
      modal.show({
        title: '首页城市定位',
        content: '同意使用精确位置并发送给城瘾以确定首页城市，后续自动定位且不用于附近排序吗？',
        confirmText: '同意定位',
        cancelText: '暂不定位',
        success: result => { if (result.confirm && current()) save(); else finish(); },
        fail: failed,
      });
    } catch (error) { failed(); }
  },

  disablePresenceSharing() {
    if (this.data.submitting) return;
    this.setData({ submitting: true });
    presencePrivacy.setSharing(app, null, false)
      .then(() => toast('已关闭附近的人位置分享'))
      .catch(error => toast(error.message))
      .finally(() => this.setData({ submitting: false }));
  },

  // PC-168:只读本机按用途的持有状态(bg-tracker),不改撤回核心(#439)。
  syncRoamCollection() {
    const text = bgTracker.hasOwner('roam') ? '本机正在为漫游采集位置'
      : bgTracker.isRunning() ? '漫游已停止采集；其他已开启的功能仍在使用定位'
        : '本机未在为漫游采集位置';
    this.setData({ roamCollectionText: text });
  },

  withdrawRoamLocationConsent() {
    if (this.data.submitting) return;
    this.data.failedAction = '';
    this.setData({ submitting: true, submitError: '' });
    const captured = this._roamRevokeReader = reader(app);
    this._roamRevokeInFlight = true;
    const epoch = this._roamRevokeEpoch = (this._roamRevokeEpoch || 0) + 1;
    const current = () => !this._roamRevokeHidden && epoch === this._roamRevokeEpoch
      && sameReader(app, captured);
    // 撤回先作用于本机用途；审计失败仍由 consent-client 保留原账号/requestId 补传。
    bgTracker.revoke('roam');
    this.syncRoamCollection();
    let audit;
    try {
      audit = current() ? app.recordConsent({ docType: 'privacy_policy', scene: 'roam_location', eventType: 'REVOKE' })
        : Promise.reject(new Error('账号已变化'));
    } catch (error) { audit = Promise.reject(error); }
    Promise.resolve(audit).then(() => {
      if (!current()) return;
      toast('已撤回漫游定位同意');
      this.data.failedAction = '';
      this.setData({ submitError: '' });
    }).catch(() => {
      if (!current()) return;
      Object.assign(this.data, { failedAction: 'revoke' });
      this.setData({
        submitError: '本机定位已停，撤回记录待确认，请重试',
      });
    }).finally(() => {
      if (current()) {
        this._roamRevokeInFlight = false;
        this.setData({ submitting: false });
      }
    });
  },

  retrySubmit() {
    if (this.data.submitting) return;
    if (this.data.failedAction === 'agree') {
      this.agreePrivacy({ detail: { buttonId: this._lastPrivacyButtonId } });
      return;
    }
    if (this.data.failedAction === 'revoke') this.withdrawRoamLocationConsent();
  },
});
