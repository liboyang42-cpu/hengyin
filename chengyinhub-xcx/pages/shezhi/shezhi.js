const sendRecovery = require('../../utils/im-send-recovery.js');
const loading = require('../../utils/loading.js');
const toast = require('../../utils/toast.js');
const app = getApp();
const policy = require('../../utils/identity/identity-policy.js');
const roleGuard = require('../../utils/roleGuard.js');
const merchantTheme = require('../../utils/merchant-theme.js');
const { getScene } = require('../../utils/scene-registry.js');
const { pushScene, popScene, currentScene, exitDecision } = require('../../utils/scene-stack.js');
const { clearStoragePreservingPlayerRoamMemory } = require('../../utils/roam-player-memory.js');
Page({

  data: {
    privacyGateShow: false,
    userInfo: {},
    settingsAuthRequired: false,
    settingsAccessDenied: false,
    // PR10 F-L5C-06:社区读闸关(含开关拉取失败)时「我的收藏」只剩主题,副标题不再提帖文
    communityPostRead: false,
    isMerchantView: false,
    settingsTheme: 'player',
    statusBarHeight: app.globalData.statusBarHeight || 44,
    navBarHeight: app.globalData.navBarHeight || 44,
    // 协议(全屏)/ 隐私与定位(半屏)两个弹窗的开关
    showAgreementSheet: false,
    showPrivacySheet: false,
    showPostGovernance: false,
    sceneStack: [],
    sceneCurrent: null,
    sceneConfirm: { show: false, action: null, pending: null }
  },

  // 隐私授权闸:app.js 优先调这里(真弹窗),没有这个方法的页面才回退到 /pages/privacy 路由页。
  showPrivacyGate() {
    this.setData({ privacyGateShow: true });
  },
  onPrivacyGateSettled() {
    this.setData({ privacyGateShow: false });
  },

  onLoad(options) {
    this._skipInitialShow = true;
    this.syncCommunityEntry();
    this.syncViewStateAndTheme();
    this.getUserData();
    if (options && options.scene) this.openScene(options.scene, { id: options.id });
  },

  onShow() {
    this._hidden = false;
    this.syncViewStateAndTheme();
    if (this._skipInitialShow) this._skipInitialShow = false;
    else { this.syncCommunityEntry(); this.getUserData(); }
  },

  // 与 IM 列表 _syncCommunityEntry、收藏页 syncCommunityTab 同一判据:app.canUseCommunity(false),开关稍后到达再补一次
  syncCommunityEntry() {
    const memberId = app.getUserID && app.getUserID();
    const sync = () => {
      if (this._unloaded || memberId !== (app.getUserID && app.getUserID())) return;
      this.setData({ communityPostRead: typeof app.canUseCommunity === 'function' && app.canUseCommunity(false) });
    };
    const pending = typeof app.loadFeatureFlags === 'function' ? app.loadFeatureFlags() : null;
    sync();
    if (pending && typeof pending.then === 'function') pending.then(sync);
  },

  onHide() { this._hidden = true; this._userInfoEpoch = (this._userInfoEpoch || 0) + 1; merchantTheme.merchantPageRestore(); },
  onUnload() { this._unloaded = true; this._userInfoEpoch = (this._userInfoEpoch || 0) + 1; merchantTheme.merchantPageRestore(); },

  syncViewStateAndTheme() {
    const isMerchantView = this.updateViewState();
    if (isMerchantView) merchantTheme.merchantPageShow();
    else merchantTheme.merchantPageRestore();
  },

  updateViewState() {
    // 视角身份统一交给 identity-policy(role 优先 + debug_user_view 仅影响展示),与首页同源。
    const isMerchantView = policy.isMerchantView({
      role: wx.getStorageSync('role'),
      userType: wx.getStorageSync('user_type'),
      debugView: wx.getStorageSync('debug_user_view'),
    });
    // 能力模型:club 是能力不是默认视角,用 isClubLeader 代替 role==='club' 判等
    const isClubView = roleGuard.isClubLeader();
    this.setData({
      isMerchantView,
      isClubView,
      settingsTheme: isMerchantView ? 'merchant' : 'player'
    });
    return isMerchantView;
  },

  // custom 导航返回:空页面栈(进程回收后直达本页)时兜底回"我的"tab,与 achievements/im-list 同款
  onBack() {
    wx.navigateBack({ fail() { wx.switchTab({ url: '/pages/member/index/index' }); } });
  },

  goPinpai() {
    wx.navigateTo({ url: '/pages/merchant/decor/index' });
  },

  // 注册商户/俱乐部 → 选择身份
  // 身份选择器走场景栈,不再用独立 show 位(否则能和 sceneCurrent 同屏渲两个面板)。
  openRegPicker() { this.openScene('settings-identity-picker'); },
  closeRegPicker() { this.closeScene(); },
  pickClub() {
    if (this._clubTargetLoading) return;
    this._clubTargetLoading = true;
    const that = this;
    this.closeScene();
    // 按状态分流:不是主理人→申请;已是主理人无俱乐部→创建;已有俱乐部(含满2个)→管理台。
    loading.show('加载中');
    app.sendRequest({
      url: '/api/club/my', method: 'POST', hideLoading: true, silentError: true,
      success(res) {
        loading.hide();
        that._clubTargetLoading = false;
        if (!res || res.code != '200' || !res.data || res.data.owned == null) {
          toast(app.getRequestErrorMessage(res, '俱乐部信息加载失败'));
          return;
        }
        let owned = res.data.owned;
        if (owned && !Array.isArray(owned)) owned = [owned];
        if (owned.length >= 1) {
          wx.navigateTo({ url: '/pages/club/detail/index?owner=1&tab=manage' });
        } else if (roleGuard.isClubLeader()) {
          wx.navigateTo({ url: '/pages/club/create/index' });
        } else {
          wx.navigateTo({ url: '/pages/club/apply/index' });
        }
      },
      fail(res) {
        that._clubTargetLoading = false;
        loading.hide();
        toast(app.getRequestErrorMessage(res, '网络异常，俱乐部信息加载失败'));
      },
      complete() { that._clubTargetLoading = false; }
    });
  },
  pickMerchant() {
    this.closeScene();
    wx.navigateTo({ url: '/pages/merchant/apply/index' });
  },
  // 兼容旧入口
  goRuzhu() { this.openRegPicker(); },

  goInfomation() {
    wx.navigateTo({ url: '/subpackageA/pages/infomation/infomation' });
  },

  // 2026-08-20 用户定:商家侧「个人资料」与「店铺装修」合并 —— 商户从这里直接落店铺装修
  // (头像=Logo、昵称=店铺名称、签名=slogan、作品=门店相册,gerenziliao 对商户全是重复字段)。
  goUserInfo() {
    if (this.data.settingsAuthRequired || this.data.settingsAccessDenied) { this.onLoginSettings(); return; }
    if (this.data.isMerchantView) {
      wx.navigateTo({ url: '/pages/merchant/decor/index' });
      return;
    }
    wx.navigateTo({ url: '/pages/gerenziliao/gerenziliao' });
  },

  // 编辑按钮按身份分流:商户→店铺装修;非商户→个人资料详情(二者不并存)
  goEdit() {
    if (this.data.isMerchantView) {
      wx.navigateTo({ url: '/pages/merchant/decor/index' });
    } else if (this.data.isClubView) {
      wx.navigateTo({ url: '/pages/club/detail/index?owner=1&tab=manage' });
    } else {
      wx.navigateTo({ url: '/pages/gerenziliao/gerenziliao' });
    }
  },

  goMylike() {
    wx.navigateTo({ url: '/pages/mylike/mylike' });
  },

  // 隐私与定位 / 用户服务协议:原来都是 navigateTo 整页跳走,现在原地开弹窗,不离开设置页。
  // pages/privacy 与 pages/agreement 两个路由页都保留(深链、分享、以及微信隐私授权回调仍走它们)。
  goPrivacy() {
    this.setData({ showPrivacySheet: true });
  },

  closePrivacySheet() {
    this.setData({ showPrivacySheet: false });
  },

  // P-55(2026-10-27 业主 p96):营销消息与优惠券「不能是弹窗,要点进去」⇒ 独立页面
  openMarketingConsent() {
    if (this._navigating) return;
    this._navigating = true;
    wx.navigateTo({ url: '/pages/shezhi/marketing/index', complete: () => { this._navigating = false; } });
  },

  openPostGovernance() {
    this.setData({ showPostGovernance: true });
  },

  closePostGovernance() {
    this.setData({ showPostGovernance: false });
  },

  goUserAgreement() {
    this.setData({ showAgreementSheet: true });
  },

  closeAgreementSheet() {
    this.setData({ showAgreementSheet: false });
  },

  goDeregister() {
    this.openScene('settings-deregister');
  },

  goAppeals() {
    wx.navigateTo({ url: '/subpackageMember/appeal-mine/index' });
  },
  goInvoice() {
    wx.navigateTo({ url: '/subpackageMember/invoice/index' });
  },

  goAbout() {
    wx.navigateTo({ url: '/pages/shezhi/about/index' });
  },

  openScene(id, params = {}) {
    if (id === 'settings-profile' && this.data.isMerchantView) {
      this.goUserInfo();
      return false;
    }
    let next;
    try {
      next = getScene(id, params);
    } catch (e) {
      // A-10:深链 ?scene=不存在 时 getScene 会 throw,onLoad 里没有 try 会让页面卡在半初始化态。
      // 与 scene-deep-link 同一口径:toast + 留在原页(兜底返回)。
      toast('场景无法打开');
      return false;
    }
    if (String(id).indexOf('settings-') === 0) next.theme = this.data.settingsTheme;
    if (exitDecision(this.data.sceneStack, 'close') === 'confirm') {
      this.setData({ sceneConfirm: { show: true, action: 'replace', pending: next } });
      return false;
    }
    const sceneStack = pushScene(this.data.sceneStack, next);
    this.setData({ sceneStack, sceneCurrent: currentScene(sceneStack), sceneConfirm: { show: false, action: null, pending: null } });
    return true;
  },
  backScene() {
    if (exitDecision(this.data.sceneStack, 'back') === 'confirm') {
      this.setData({ sceneConfirm: { show: true, action: 'back', pending: null } });
      return;
    }
    const sceneStack = popScene(this.data.sceneStack);
    this.setData({ sceneStack, sceneCurrent: currentScene(sceneStack), sceneConfirm: { show: false, action: null, pending: null } });
  },
  closeScene() { this.setData({ sceneStack: [], sceneCurrent: null, sceneConfirm: { show: false, action: null, pending: null } }); },
  requestSceneClose() {
    if (exitDecision(this.data.sceneStack, 'close') === 'confirm') {
      this.setData({ sceneConfirm: { show: true, action: 'close', pending: null } });
      return;
    }
    this.closeScene();
  },
  confirmSceneDiscard() {
    if (this.data.sceneConfirm.action === 'back') {
      const sceneStack = popScene(this.data.sceneStack);
      this.setData({ sceneStack, sceneCurrent: currentScene(sceneStack), sceneConfirm: { show: false, action: null, pending: null } });
      return;
    }
    if (this.data.sceneConfirm.action === 'replace' && this.data.sceneConfirm.pending) {
      const sceneStack = pushScene(this.data.sceneStack, this.data.sceneConfirm.pending);
      this.setData({ sceneStack, sceneCurrent: currentScene(sceneStack), sceneConfirm: { show: false, action: null, pending: null } });
      return;
    }
    this.closeScene();
  },
  cancelSceneDiscard() { this.setData({ sceneConfirm: { show: false, action: null, pending: null } }); },
  setSceneDirty(dirty) {
    if (!this.data.sceneStack.length) return;
    const sceneStack = this.data.sceneStack.slice();
    sceneStack[sceneStack.length - 1] = { ...sceneStack[sceneStack.length - 1], dirty: dirty === true };
    this.setData({ sceneStack, sceneCurrent: currentScene(sceneStack) });
  },
  onSceneDirtyChange(event) { this.setSceneDirty(event.detail && event.detail.dirty); },
  openChildScene(event) {
    const detail = event.detail || {};
    if (detail.id) this.openScene(detail.id, detail.params || {});
  },
  submitSceneForm() {
    const content = this.selectComponent('#sceneRouteContent');
    if (content && typeof content.submitForm === 'function') content.submitForm();
  },
  blockSceneTouch() {},

  logout() {
    // 三段式第一段:确认(文案在 utils/danger-actions.js)
    const dc = this.selectComponent && this.selectComponent('#dc');
    if (dc) dc.open('account.logout');
  },

  onDangerConfirm(e) {
    if (e.detail.key !== 'account.logout') return;
    const dc = this.selectComponent && this.selectComponent('#dc');
    if (dc) dc.busyOn();
    const clearLocalLogin = function () {
      if (!clearStoragePreservingPlayerRoamMemory(wx)) return false;
      sendRecovery.invalidate();
      app.globalData.authorization = '';
      app.globalData.open_id = '';
      app.globalData.user_id = '';
      app.globalData.user_type = '';
      app.globalData.avatar = '';
      app.globalData.nickname = '';
      app.globalData.role = ''; // 退出时一并清角色,避免内存残留导致身份错乱
      roleGuard.clear(); // 本地账号态清理成功后再清模块级权限缓存，失败可留在弹窗重试
      wx.reLaunch({ url: '/pages/index/index' });
      return true;
    };
    const loginExpired = (res, status) => {
      const code = Number(status == null ? res && res.code : status);
      return code === 401 || code === 2;
    };
    // 服务端确认注销或登录失效才清理；网络故障和其他业务失败仍保留登录态。
    // 2026-09-17 拍板 #16:先等服务端确认 token 已撤销,再清本地登录态。
    // 旧行为 success/fail 都清本机 —— 弱网下服务端没收到请求,token 仍然有效,
    // 界面却假装退出了。现在失败留在确认弹窗里给重试(cy-danger-confirm 的 error 态),
    // 本地登录态原样保留,重试复用的是同一个仍有效的 token。
    app.sendRequest({
      hideLoading: true,
      url: '/api/logout',
      method: 'POST',
      success(res) {
        if (res && (res.code == 200 || loginExpired(res))) {
          if (!clearLocalLogin() && dc) dc.failed('本机登录信息清理失败，请重试');
          return;
        }
        const message = (res && res.msg) || '退出没有完成，请重试';
        if (dc) dc.failed(message);
      },
      fail(res, status) {
        if (loginExpired(res, status)) {
          if (!clearLocalLogin() && dc) dc.failed('本机登录信息清理失败，请重试');
          return;
        }
        if (dc) dc.failed('退出没有完成，请重试');
      }
    });
  },

  onLoginSettings() {
    if (this.data.settingsAccessDenied) { this.getUserData(); return; }
    if (this._settingsLoginPending) return;
    const epoch = this._userInfoEpoch || 0;
    this._settingsLoginPending = true;
    let login;
    try {
      login = Number(app.getUserID()) > 0 && typeof app.reLogin === 'function'
        ? app.reLogin() : typeof app.firstLogin === 'function' ? app.firstLogin() : null;
    } catch (error) { this._settingsLoginPending = false; return; }
    Promise.resolve(login).then(result => {
      this._settingsLoginPending = false;
      if (epoch === (this._userInfoEpoch || 0) && !(result && result.ok === false) && Number(app.getUserID()) > 0) this.getUserData();
    }).catch(() => { this._settingsLoginPending = false; });
  },

  getUserData() {
    const that = this;
    const epoch = this._userInfoEpoch = (this._userInfoEpoch || 0) + 1;
    const identity = () => {
      const gd = app.globalData || {};
      return [app.getUserID(), gd.role || '', gd.user_type || '', gd.authorization || ''].join('|');
    };
    const requestedIdentity = identity();
    if (!(Number(app.getUserID()) > 0)) {
      this.setData({ userInfo: {}, settingsAuthRequired: true, settingsAccessDenied: false });
      this._settingsReadOwner = requestedIdentity;
      return;
    }
    if (this._settingsReadOwner && this._settingsReadOwner !== requestedIdentity) this.setData({ userInfo: {} });
    this._settingsReadOwner = requestedIdentity;
    const handleAuth = (res, statusCode) => {
      if (epoch !== that._userInfoEpoch || that._unloaded) return true;
      if (requestedIdentity !== identity() || res && res.reason === 'sessionChanged') {
        that._userInfoEpoch += 1;
        that.setData({ userInfo: {}, settingsAuthRequired: !(Number(app.getUserID()) > 0), settingsAccessDenied: false });
        if (!that._hidden && Number(app.getUserID()) > 0) that.getUserData();
        return true;
      }
      const code = Number(statusCode || res && (res.statusCode || res.code));
      if (code !== 401 && code !== 403) return false;
      that.setData({ userInfo: {}, settingsAuthRequired: code === 401, settingsAccessDenied: code === 403 });
      return true;
    };
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/user/info',
      data: {
        member_id: app.getUserID()
      },
      method: "POST",
      success(res) {
        if (handleAuth(res)) return;
        if (res && res.code == "200" && res.data && typeof res.data === 'object' && !Array.isArray(res.data)) {
          app.setUserRole(res.data.role); // RBAC:缓存角色
          that.syncViewStateAndTheme();
          const data = res.data || {};
          // 手机号脱敏展示:11 位掐中间四位,非 11 位原样(replace 不匹配即原样)
          data.phoneMasked = String(data.phone || '').replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2');
          that.setData({ userInfo: data, settingsAuthRequired: false, settingsAccessDenied: false });
        }
      },
      fail(res, code) { handleAuth(res, code); },
      successStatusAbnormal(res, code) { handleAuth(res, code); }
    });
  },
});
