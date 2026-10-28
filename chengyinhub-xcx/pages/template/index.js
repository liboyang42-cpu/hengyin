const { readReducedMotion } = require('../../utils/motion-preference.js');
const cyToast = require('../../utils/toast.js');
const { readPageStyle } = require('../../utils/font-scale.js');
const app = getApp();
const policy = require('../../utils/identity/identity-policy.js');
const merchantTheme = require('../../utils/merchant-theme.js');
const merchantAccessPolicy = require('../../utils/merchant-access-policy.js');

// 2026-10-28 业主定稿原型 v21:货架(主题 / 玩法 tab、精选、标签、货架、分类页)整块移进
// templatedetail 分包的 tpl-shelf 组件，本页只留顶栏、发布入口与底栏。
// 主题点开 → topic-preview(卡堆预览 +「用这个模板」);玩法点开 → 既有 templatedetail。
Page({
  data: {
    reducedMotion: false,
    pageMetaStyle: '',
    operationScope: '',
    merchantAccessLoaded: false,
    canManageMerchantProjects: false,
    // 发布广场 —— 发布能力卡(/api/publish/home)
    pub: {
      role: 'player',
      roleText: '玩家',
      isMerchant: false,
      permission: {},
      quota: { maxThemes: null, themesOnline: 0, themesRemaining: null },
      projectSummary: { total: 0, pending: 0, online: 0, offline: 0, rejected: 0 }
    },
    pubLoaded: false,
    publishSheetShow: false,

    statusBarHeight: getApp().globalData.statusBarHeight,
    navBarHeight: getApp().globalData.navBarHeight,
    menuButtonInfo: getApp().globalData.menuButtonInfo,
    smButtonOffset: -40,
    tabBarRaised: true,

    // 胶囊安全区：胶囊底部 + 额外间距，整体页面从此线开始
    capsuleBottom: 0,
    safeTop: 0,
    titBarHeight: 0,
    headerRightInset: 0
  },

  onLoad(options) {
    options = options || {};
    const mi = getApp().globalData.menuButtonInfo || {};
    const sh = this.data.statusBarHeight;
    const capsuleBottom = mi.bottom || (sh + this.data.navBarHeight);
    const safeTop = capsuleBottom + 8;  // 胶囊底 + 8px 呼吸空间
    const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    const windowWidth = Number(windowInfo.windowWidth) || 375;
    const capsuleLeft = Number(mi.left) || 0;
    const headerRightInset = capsuleLeft > 0 && capsuleLeft < windowWidth
      ? windowWidth - capsuleLeft + 8
      : windowWidth * 32 / 750;
    this.setData({
      operationScope: (options.mode === 'merchant' || options.scope === 'MERCHANT') ? 'MERCHANT' : '',
      capsuleBottom: capsuleBottom,
      safeTop: safeTop,
      titBarHeight: safeTop - sh,     // titBar = 胶囊底到状态栏底的距离
      headerRightInset: headerRightInset
    });
  },

  onShow() {
    this.setData({ reducedMotion: readReducedMotion() });
    this.setData({ pageMetaStyle: readPageStyle() });
    wx.hideTabBar();
    // 收敛:商家玩家共用发布广场,底部 tabBar 按本地身份立即渲染(避免等 /api/publish/home 异步返回时闪 consumer)
    // pub.role 同步用本地快照预填:发布弹窗的活动卡锁态在接口返回前也判得对(权威值随 getPublishHome 覆盖)
    const merchantView = this.data.operationScope === 'MERCHANT'
      || policy.isMerchantView({ role: app.getUserRole(), userType: app.getUserType() });
    if (merchantView) merchantTheme.merchantPageShow();
    else merchantTheme.merchantPageRestore();
    this.setData({
      tabBarRaised: true,
      isMerchant: merchantView,
      'pub.role': app.getUserRole()
    });
    if (this.data.operationScope === 'MERCHANT') this.loadMerchantAccess();
    this.getPublishHome();
  },

  loadMerchantAccess() {
    const that = this;
    app.sendRequest({
      hideLoading: true,
      url: '/api/merchant/access/me',
      method: 'POST',
      data: {},
      success(res) {
        const access = merchantAccessPolicy.normalizeMerchantAccess(
          res && res.code == '200' ? res.data : null
        );
        that.applyMerchantPublishAccess(access.canManageProjects);
      },
      fail() { that.applyMerchantPublishAccess(false); }
    });
  },

  applyMerchantPublishAccess(canManage) {
    const permission = Object.assign({}, (this.data.pub && this.data.pub.permission) || {}, {
      canSimplePublish: !!canManage,
      canProPublish: !!canManage,
      canCreateTemplate: !!canManage
    });
    this.setData({
      merchantAccessLoaded: true,
      canManageMerchantProjects: !!canManage,
      'pub.role': 'merchant',
      'pub.isMerchant': true,
      'pub.permission': permission
    });
  },
  // ===== 发布广场:发布能力卡 =====
  getPublishHome() {
    const that = this;
    const ROLE_TEXT = { player: '玩家', club: '俱乐部主理人', merchant: '商家' };
    app.sendRequest({
      hideLoading: true,
      url: '/api/publish/home',
      method: 'POST',
      data: {},
      success(res) {
        if (res.code == '200' && res.data) {
          const d = res.data;
          const q = d.quota || {};
          const merchantScoped = that.data.operationScope === 'MERCHANT';
          const permission = merchantScoped
            ? Object.assign({}, d.permission || {}, {
              canSimplePublish: that.data.canManageMerchantProjects,
              canProPublish: that.data.canManageMerchantProjects,
              canCreateTemplate: that.data.canManageMerchantProjects
            })
            : (d.permission || {});
          that.setData({
            pub: {
              role: merchantScoped ? 'merchant' : (d.role || 'player'),
              roleText: merchantScoped ? '商家' : (ROLE_TEXT[d.role] || '玩家'),
              isMerchant: merchantScoped || !!d.isMerchant,
              permission: permission,
              quota: {
                maxThemes: q.maxThemes,
                themesOnline: q.themesOnline || 0,
                themesRemaining: q.themesRemaining
              },
              projectSummary: d.projectSummary || {}
            },
            pubLoaded: true
          });
        }
      },
      fail() { /* 能力卡失败不阻断页面 */ }
    });
  },

  // ===== 头部入口:搜索(货架组件里的搜索框)、铃铛=站内收件箱 =====
  // 2026-10-22 owner 裁决:模板页搜索入口统一走通用搜索(pages/search2),模板作为结果类型出现在那里。
  goSearch() { wx.navigateTo({ url: '/pages/search2/index' }); },
  goInbox() { wx.navigateTo({ url: '/subpackageB/pages/im/list/index' }); },
  onShelfToast(e) { cyToast(((e && e.detail) || {}).text || ''); },

  // ===== 发布弹窗(cy-publish-sheet,唯一发布触发) =====
  openPublishSheet() {
    if (this.data.operationScope && !this.data.canManageMerchantProjects) {
      cyToast('当前岗位没有项目发布权限');
      return;
    }
    this.setData({ publishSheetShow: true });
  },
  closePublishSheet() { this.setData({ publishSheetShow: false }); },

  onUnload() {
    if (this._scrollStopTimer) clearTimeout(this._scrollStopTimer);
    merchantTheme.merchantPageRestore();
  },

  onPageScroll(e) {
    // _lastScrollTop/_scrollStopTimer 不进 data:wxml 从不读,逐帧 setData 是白付的跨线序列化(2026-09-19 性能批3)
    const currentScrollTop = e.scrollTop || 0;
    const isScrollingDown = currentScrollTop > (this._lastScrollTop || 0);

    if (this._scrollStopTimer) {
      clearTimeout(this._scrollStopTimer);
    }

    if (isScrollingDown && currentScrollTop > 50 && this.data.tabBarRaised) {
      this.setData({ tabBarRaised: false });
    }

    this._scrollStopTimer = setTimeout(() => {
      if (!this.data.tabBarRaised) {
        this.setData({ tabBarRaised: true });
      }
    }, 300);

    this._lastScrollTop = currentScrollTop;
  },

  onHide() {
    merchantTheme.merchantPageRestore();
  }
});
