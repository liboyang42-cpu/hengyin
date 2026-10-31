const modal = require('../../utils/modal.js'); const pageMotion = require('../../utils/page-motion.js');
const cyLoading = require('../../utils/loading.js');
const cyToast = require('../../utils/toast.js');
const { readPageStyle } = require('../../utils/font-scale.js');
const { toTimestamp } = require('../../utils/datetime');
const app = getApp();
const { applyTheme } = require('../../utils/theme.js');
const policy = require('../../utils/identity/identity-policy.js');
const indexFormat = require('../../utils/index/index-format.js');
const { decorateFeedCards } = require('../../utils/index/feed-card.js');
const { buildHomeActionBanners, openHomeActionBanner } = require('../../utils/index/home-action-banner.js');
const { readReducedMotion } = require('../../utils/motion-preference.js');
const { openScene } = require('../../utils/scene-entry.js');
const { parseDoorScene, pathForScanEntry } = require('../../utils/game-door-entry.js');

const { buildActivityShare } = require('../../utils/activity-share.js');
const { appendByIndex } = require('../../utils/list-append.js');
const { isRecordList } = require('../../utils/response-shape.js');
const { isValidMobile } = require('../../utils/form-state.js');
const phoneReminder = require('../../utils/phone-reminder.js');
const identitySync = require('../../utils/identity-sync.js');
const { avatarSrc } = identitySync;
const { VERSIONS } = require('../../utils/compliance/usage-consent.js');

function isHomeCityAgreement(row) {
  return !!row && row.docType === 'privacy_policy' && row.scene === 'home_city_location'
    && row.docVersion === VERSIONS.privacy_policy && row.eventType === 'AGREE'
    && typeof row.requestId === 'string' && !!row.requestId.trim()
    && row.scopeType == null && row.scopeId == null;
}

function boundPhoneFromResponse(res) {
  if (!res) return '';
  var candidates = [];
  if (typeof res.data === 'string') candidates.push(res.data);
  if (res.data && typeof res.data === 'object' && typeof res.data.phone === 'string') {
    candidates.push(res.data.phone);
  }
  if (typeof res.msg === 'string') candidates.push(res.msg);
  for (var i = 0; i < candidates.length; i++) {
    var phone = String(candidates[i]).trim();
    if (isValidMobile(phone)) return phone;
  }
  return '';
}

// A-01:邀请落地参数兼容两种键 —— 分享端现有两处都产 ?inviter=,但早期卡片是 ?id=,
// 老卡片仍在外面流通;读取端两个都认(只改分享端会让存量卡片继续丢邀请关系)。
function readInviterId(options) {
  if (!options) return '';
  return options.inviter || options.id || '';
}

// 视角分流快照:role(RBAC 单一真源)+ user_type(过渡兜底)+ debug_user_view(仅展示)。
function viewSnap() {
  return {
    role: wx.getStorageSync('role'),
    userType: wx.getStorageSync('user_type'),
    debugView: wx.getStorageSync('debug_user_view'),
  };
}

// 身份分流统一交给 identity-policy(镜像后端 resolveRole:role 优先、user_type==2 兜底;
// debug_user_view='user' 仅强制玩家展示)。三处分流(data 初始化/onLoad/onShow)同源,消除各页不一致。
function isMerchantView() {
  return policy.isMerchantView(viewSnap());
}

const MERCHANT_HOME = '/pages/merchant/index/index';

// 跳商家首页的唯一出口。四处身份分流(onLoad 同步 / onShow 同步 / 登录落地复判 /
// /api/user/info 兜底)共用:路由字符串只写一处,且失败不再静默 —— 原先四处 redirectTo
// 都没有 fail 回调,导航被框架丢掉时零信号。这条日志同时是诊断闸:若"先进玩家首页"
// 修完仍复现,它能分清是「没判出商家」还是「判出了却没跳成」。
function goMerchantHome() {
  wx.redirectTo({
    url: MERCHANT_HOME,
    fail() { console.error('[identity] 跳转商家首页失败'); },
  });
}

function normalizeMemberId(memberId) {
  return memberId || '';
}

function clearPrivateDisplay(page) {
  page.setData({
    userInfo: { avatar: '', nickname: '', point: '0' },
    showPhoneModal: false,
  });
}

function capturePrivateRequest(page, memberId) {
  const normalizedMemberId = normalizeMemberId(memberId);
  if (page._privateDataMemberId !== normalizedMemberId) {
    page._privateDataMemberId = normalizedMemberId;
    page._privateDataEpoch = (page._privateDataEpoch || 0) + 1;
    clearPrivateDisplay(page);
  }
  return page._privateDataEpoch || 0;
}

function isCurrentPrivateRequest(page, memberId, requestEpoch) {
  const currentMemberId = normalizeMemberId(app.getUserID());
  if (page._privateDataMemberId !== currentMemberId) {
    capturePrivateRequest(page, currentMemberId);
  }
  return (page._privateDataEpoch || 0) === requestEpoch
    && currentMemberId === normalizeMemberId(memberId);
}

function invalidatePrivateRequests(page, clearDisplay = true) {
  page._privateDataEpoch = (page._privateDataEpoch || 0) + 1;
  if (clearDisplay) clearPrivateDisplay(page);
}

function captureLocationRequest(page) {
  const scope = {
    epoch: (page._locationEpoch || 0) + 1,
    memberId: normalizeMemberId(app.getUserID()),
    authorization: (app.globalData || {}).authorization || '',
  };
  page._locationEpoch = scope.epoch;
  return scope;
}

function isCurrentLocationRequest(page, scope) {
  return !!scope
    && !page._unloaded && !page._homeLocationHidden
    && scope.epoch === (page._locationEpoch || 0)
    && scope.memberId === normalizeMemberId(app.getUserID())
    && scope.authorization === ((app.globalData || {}).authorization || '');
}

const homeCity = require('../../utils/index/home-city-location.js');
function showHomeCityLocationError(page, scope, kind) {
  hideLocationLoading(page, scope);
  if (!isCurrentLocationRequest(page, scope)) return;
  // V-01:冷启动/onShow 的自动定位失败不弹提示，保留上次城市，没有就露出手动选择；只有用户主动点定位才提示。
  if (scope.userInitiated) cyToast(homeCity.message(kind));
  else if (!page.data.city) page.setData({ city: homeCity.DEFAULT_CITY, cityManual: false });
}

function hideLocationLoading(page, scope) {
  if (!scope || page._locationLoadingEpoch !== scope.epoch) return;
  page._locationLoadingEpoch = null;
  cyLoading.hide();
}

// 即将上线倒计时格式化:>1天→还剩Nd,<1天→还剩Nh,已过→已开始(红)。不到分秒。
function fmtRemain(startDateStr) {
  if (!startDateStr) return { remainingTime: '', started: false };
  const diff = toTimestamp(String(startDateStr)) - Date.now();
  if (diff <= 0) return { remainingTime: '已开始', started: true };
  const days = Math.floor(diff / 86400000);
  if (days >= 1) return { remainingTime: `还剩${days}d`, started: false };
  const hours = Math.max(1, Math.floor(diff / 3600000));
  return { remainingTime: `还剩${hours}h`, started: false };
}

const RECO_INACTIVE_SCALE = 280 / 306;
const RECO_BLUR_PX = 8;

function clamp01(value) {
  return Math.max(0, Math.min(1, Number(value) || 0));
}

function recoCardStyle(focus) {
  const normalizedFocus = clamp01(focus);
  const deEmphasis = 1 - normalizedFocus;
  const scale = RECO_INACTIVE_SCALE + (1 - RECO_INACTIVE_SCALE) * normalizedFocus;
  return `--reco-card-scale:${scale.toFixed(4)};--reco-mask-opacity:${deEmphasis.toFixed(4)};--reco-blur:${(RECO_BLUR_PX * deEmphasis).toFixed(2)}px;`;
}

function buildRecoCardStyles(count, current, target, progress) {
  const normalizedProgress = clamp01(progress);
  return Array.from({ length: count }, function (_, index) {
    let focus = index === current ? 1 - normalizedProgress : 0;
    if (index === target) focus = normalizedProgress;
    return recoCardStyle(focus);
  });
}

// feed/卡片多图:发布页(publish/fabu)写入的 imgArr 与章节同为 "," 分隔，历史数据也有 ";"。
// 两种分隔符都吃，避免把整串 "a.jpg,b.jpg" 当成一个坏 URL。
function decorateImgs(list) {
  // 首页五类列表共用这条图片消费者链：
  // 统一拆分逗号/分号格式，避免整串落成一个坏 URL；
  // 统一补全后端相对路径，附近活动不再出现有数据无图；
  // feed 只拿前四张，并保留真实总数计算 +N；
  // nearby/upcoming 都从同一个 cover 取首图；
  // `decorateFeedCards` 无 wx 依赖，契约测试可直接驱动。
  const normalizeImage = function (src) {
    return app.getImgUrl(src);
  };

  // 页面入口只注入 URL 规则，不复制纯函数里的卡片判定。
  return decorateFeedCards(
    list,
    normalizeImage,
  );
}

Page({
  /**
   * 页面的初始数据
   */
  data: {
    // 下拉刷新转圈态(scroll-view refresher)
    refreshing: false,
    privacyGateShow: false,
    // 身份分流：商户在首页 tab 渲染前直接跳商户首页，避免玩家首页闪一帧
    // debug_user_view='user' 强制使用普通用户视角（调试用）
    _needsRedirect: isMerchantView(),
    city: '', cityManual: false, manualCityOptions: homeCity.CITY_OPTIONS,
    isAppReady: false,
    isLoading: true,
    userInfo: {
      avatar: '',
      nickname: '',
      point: '0',
    },

    // 列表加载失败标记(按 listName 索引)。错误与空态必须分开:
    // 失败时列表停在 [],光判 length 会渲染成「本来就没有」甚至「正在加载」。
    listErr: {},
    // 首次加载和重试都必须立即给反馈；否则网络慢时用户点“重试”看起来完全没生效。
    listLoading: {},

    // 分页相关数据
    bottomTopicPageNo: 1,
    bottomActivityPageNo: 1,
    hasMoreBottomTopic: false,
    hasMoreBottomActivity: false,
    isBottomLoading: false,
    // A-14-2:底部信息流加载失败的行内重试。存「失败的是第几页」,0 = 无错误;
    // wxml 据此在列表底部渲染 cy-inline-error,重试沿用失败页而不是从头再拉。
    bottomTopicFailedPage: 0,
    bottomActivityFailedPage: 0,
    pageSize:20,
    
    // 倒计时相关
    countdownTimer: null, // 倒计时定时器
    countdownInterval: 60000, // 更新间隔60秒（1分钟）
    
    // 其他数据保持不变
    recommendedTopicList: [],
    nearbyActivityList: [],
    upcomingActivityList: [],
    bottomTopicList: [],
    bottomActivityList: [],
    _navUp: false,  // 底部导航:首屏隐藏,向下滑动升起
    // 顶栏对齐胶囊 + 滚动区高度
    capsuleTop: 0,   // 状态栏高度(px),品牌行 padding-top → 和胶囊同高
    heroActionsTop: 52,
    heroActionsRight: 16,
    heroActionsReserve: 68,
    scrollH: 0,      // 主滚动区高度(px) = 全屏可视高
    heroBannerH: 480, // 启动占位；同步布局后固定为视口 60%，让附近任务在首屏露出
    reducedMotion: false,  // 读玩家路径共享的动效偏好;true 时 hero 入场不播
    heroBannerList: buildHomeActionBanners(),
    recoHero: null,
    recoCards: [],
    recoCurrent: 0,
    recoCardStyles: [],
    bannerCurrent: 0,
    activeTab: '0',
    showPhoneModal: false,
  },

  // 隐私授权闸:app.js 优先调这里(真弹窗),没有这个方法的页面才回退到 /pages/privacy 路由页。
  showPrivacyGate() {
    this.setData({ privacyGateShow: true });
  },
  onPrivacyGateSettled(event) {
    this.setData({ privacyGateShow: false });
    const authorization = (app.globalData || {}).authorization || '';
    if (!event || !event.detail || event.detail.event !== 'agree'
        || this._unloaded || this._homeLocationHidden
        || !app.getUsageConsent || !app.getUsageConsent().granted()
        || !(Number(app.getUserID()) > 0) || !authorization || authorization === 'dev-token') return;
    if (isMerchantView()) {
      this.setData({ _needsRedirect: true });
      goMerchantHome();
      return;
    }
    this.setData({ _needsRedirect: false, isAppReady: true });
    this.loadPageData();
    this.getHomeCityLocation();
  },

  /**
   * 生命周期函数--监听页面加载
   */
  onLoad(options) {
    const that = this;
    this._unloaded = false;
    // V-15 / G-5:个人资料换头像、改昵称保存成功即换掉问候区,不等 onShow 回读
    this._offIdentity = identitySync.onIdentityChange(function (evt) {
      if (evt.scope !== 'member' || that._unloaded) return;
      const userInfo = Object.assign({}, that.data.userInfo);
      if (evt.patch.avatar) userInfo.avatar = avatarSrc(evt.patch.avatar, app.getImgUrl && app.getImgUrl.bind(app));
      if (evt.patch.nickname) userInfo.nickname = evt.patch.nickname;
      that.setData({ userInfo: userInfo });
    });

    // 门口游戏码优先：没报名去买主题，已报名进游玩。不能先被商家分流吃掉 scene。
    if (this.consumeDoorScene(options)) return;

    // 身份分流：商户直接跳商户首页，不渲染玩家首页
    // 必须实时判断（isMerchantView 实时读 storage），不能用 data 里的静态 _needsRedirect（模块加载时求值，之后不变）
    if (isMerchantView()) {
      goMerchantHome();
      return;
    }

    // data 初值可能在旧角色快照下创建；玩家分支必须显式解除隐藏闸，
    // 否则从商家切回玩家时根节点仍停留在 wx:if="{{!_needsRedirect}}" 的黑空页。
    that.setData({ _needsRedirect: false });

    // 能力模型(2026-06-27):不再因 isClubLeader 自动跳转到俱乐部管理台。
    // 主理人默认仍是玩家首页;管理台由首页底Tab/入口按钮主动进入。

    // 处理分享链接中的邀请人ID:先存下,等 appReady(登录落地)后再发一次。
    // 典型被邀请人=新用户,onLoad 时还没有会话;当场调用会被 handleInviter 的登录闸挡掉,
    // 且此后无人重试 → 邀请关系永久丢失(A-01)。
    const inviterId = readInviterId(options);
    if (inviterId) that._pendingInviter = inviterId;

    app.waitForAppReady().then(() => {
      // 登录落地后才知道真实身份:冷缓存冷启动时上面那次同步判定手里根本没有角色快照
      // (restore() 拿不到、登录是异步的),商家会停在玩家首页直到下一次 onShow ——
      // 2026-08-10 用户真机(体验版)实测的正是这个。必须排在 loadPageData/getHomeCityLocation
      // 之前,否则商家先被弹一次定位授权框再被跳走。
      // 本页已不是栈顶(用户自己切走了)就别抢导航。
      if (that._unloaded) return;
      if (isMerchantView()) {
        const stack = getCurrentPages();
        if (stack[stack.length - 1] === that) {
          that.setData({ _needsRedirect: true });
          goMerchantHome();
          return;
        }
      }
      that.setData({ isAppReady: true });
      that.loadPageData();
      that.getHomeCityLocation();
      that.retryPendingInviter();
    }).catch(err => {
      if (that._unloaded) return;
      console.error('等待app初始化失败');
    });

    // 初始化关键位置（只执行一次）
    that._layoutChrome();   // 胶囊对齐 + 分屏高度(同步,首屏即正确)
    that.setData({ reducedMotion: readReducedMotion() });

    // 启动倒计时定时器
    that.startCountdownTimer();
  },

  consumeDoorScene(options) {
    const code = parseDoorScene(options && options.scene) || parseDoorScene(app.pendingDoorScene);
    app.pendingDoorScene = null;
    if (!code) return false;
    if (this._doorSceneLast === code) return !!this._doorSceneInFlight;
    this._doorSceneLast = code;
    this._doorSceneInFlight = true;
    const that = this;
    this.setData({ _needsRedirect: true });
    app.waitForAppReady().then(() => {
      if (that._unloaded) return;
      app.sendRequest({
        url: '/api/play/scan-entry',
        method: 'POST',
        data: { code },
        hideLoading: true,
        // 2026-10-24 游客可浏览:扫门口码 = 开始游玩(按本人进度分流),游客 401 时由请求层弹「登录后继续」,登录后重发一次。
        write: true, loginReason: 'play',
        success(res) {
          const path = pathForScanEntry(res && res.data);
          if ((res.code == 200 || res.code == '200') && path) {
            that._doorSceneInFlight = false;
            wx.redirectTo({
              url: path,
              fail() { wx.reLaunch({ url: path }); },
            });
            return;
          }
          cyToast((res && res.msg) || '这张码无法打开');
          that.finishDoorSceneFallback();
        },
        fail(res, status) {
          // 游客在登录弹窗里点了「暂不登录」:不报「码无法打开」,直接回到可浏览的首页。
          if (!(Number(status) === 401 && !app.getUserID())) cyToast('这张码无法打开');
          that.finishDoorSceneFallback();
        },
      });
    }).catch(() => {
      if (that._unloaded) return;
      cyToast('这张码无法打开');
      that.finishDoorSceneFallback();
    });
    return true;
  },

  finishDoorSceneFallback() {
    this._doorSceneInFlight = false;
    if (this._unloaded) return;
    if (isMerchantView()) {
      goMerchantHome();
      return;
    }
    this.setData({ _needsRedirect: false, isAppReady: true });
    this.loadPageData();
    this.getHomeCityLocation();
  },

  // A-01:登录落地后补发一次邀请关系(onLoad 时新用户还没有 currentUserId)。
  retryPendingInviter() {
    const inviterId = this._pendingInviter;
    if (!inviterId || this._unloaded) return;
    // 2026-10-24 游客可浏览:打开不再建号,被邀请的游客要等首次登录(写前登录弹窗)后才有身份 ——
    // 没身份时留着 pending,回到首页 onShow 再补发,别在游客态把邀请关系消费掉。
    if (!app.getUserID()) return;
    this._pendingInviter = '';
    this.handleInviter(inviterId);
  },

  /**
   * 处理邀请人逻辑
   */
  handleInviter: function(inviterId) {
    const that = this;
    const hasInviter = wx.getStorageSync('has_inviter');
    const currentUserId = app.getUserID();
    
    // 分享 path 里的 id 是字符串,getUserID() 是数字 —— 不归一化的话「自己点自己的卡片」判不出来
    if (currentUserId && !hasInviter && String(inviterId) !== String(currentUserId)) {
      app.sendRequest({
        url: '/api/user/setInviter',
        method: "POST",
        data: { inviter_id: inviterId },
        success: function (res) {
          if (res.code == "200") {
            wx.setStorageSync('has_inviter', true);
          }
        }
      });
    }
  },

  /**
   * 启动倒计时定时器
   */
  startCountdownTimer: function() {
    const that = this;
    
    // 清除已有的定时器
    if (that.data.countdownTimer) {
      clearInterval(that.data.countdownTimer);
    }
    
    // 每60秒更新一次倒计时（1分钟）
    const timer = setInterval(() => {
      that.updateUpcomingActivitiesCountdown();
    }, that.data.countdownInterval);
    
    that.setData({ countdownTimer: timer });
  },

  /**
   * 更新即将上线活动的倒计时
   */
  updateUpcomingActivitiesCountdown: function() {
    const that = this;
    const upcomingList = that.data.upcomingActivityList;
    
    if (!upcomingList || upcomingList.length === 0) {
      return;
    }
    
    let updated = false;

    // 更新每个活动的倒计时(缩写格式:还剩Nd / 还剩Nh / 已开始)
    const updatedList = upcomingList.map(item => {
      if (!item.startDate) return item;
      const r = fmtRemain(item.startDate);
      if (item.remainingTime !== r.remainingTime || item.started !== r.started) {
        updated = true;
      }
      return Object.assign({}, item, { remainingTime: r.remainingTime, started: r.started });
    });
    
    // 如果有更新，则设置数据
    if (updated) {
      that.setData({
        upcomingActivityList: updatedList
      });
    }
  },

  /**
   * 确保倒计时定时器正在运行
   */
  ensureCountdownTimerRunning: function() {
    const that = this;
    
    if (!that.data.countdownTimer) {
      that.startCountdownTimer();
    }
  },

  /**
   * 页面显示时 - 修复返回页面时的问题
   */
  onShow() {
    this.setData({ fontScaleStyle: readPageStyle() });
    const resumeHomeLocation = this._homeLocationHidden === true;
    const locationEpochAtShow = this._locationEpoch || 0;
    this._homeLocationHidden = false;
    wx.hideTabBar();

    const that = this;

    // 2026-10-24:游客在别处登录后回到首页,补发一次待发的邀请关系(A-01)。
    if (this._pendingInviter) this.retryPendingInviter();
    // 热启动扫小程序码只在 App.onShow 收下 pending。这里消费一次就清掉。
    if (this.consumeDoorScene()) return;
    if (this._doorSceneInFlight) return;

    // 身份分流：每次进首页 tab 都同步检查一次身份
    // (冷启动时登录可能还在途、此处判不出商家;那一路由 onLoad 的 waitForAppReady 复判兜住)
    if (isMerchantView()) {
      this.setData({ _needsRedirect: true });
      goMerchantHome();
      return;
    }

    // onShow 也要复位旧快照留下的隐藏闸，确保角色切换后页面可见。
    this.setData({ _needsRedirect: false });

    // 确认留在首页后,保持深色:状态栏/导航切深色(白字)。离开首页时 onHide 复位浅色。
    applyTheme('dark');

    // 兜底：拉一次 /api/user/info，覆盖跨设备升级商户的场景
    const roleMemberId = normalizeMemberId(app.getUserID());
    const roleRequestEpoch = capturePrivateRequest(this, roleMemberId);
    if (roleMemberId && wx.getStorageSync('debug_user_view') !== 'user') {
      app.sendRequest({
        hideLoading: true,
        url: '/api/user/info',
        data: { member_id: roleMemberId },
        method: 'POST',
        success(res) {
          if (!isCurrentPrivateRequest(that, roleMemberId, roleRequestEpoch)) return;
          if (res.code == '200' && res.data) {
            if (res.data.role) app.setUserRole(res.data); // 回填含能力快照(role+isClubLeader...)
            // 能力模型:不再因 isClubLeader 自动跳管理台;主理人仍走首页。
            if (policy.isMerchantView({ role: res.data.role, userType: res.data.userType })) {
              app.setUserType(2);
              goMerchantHome();
            }
          }
        }
      });
    }

    if (this._onShowRefreshTimer) clearTimeout(this._onShowRefreshTimer);
    this._onShowRefreshTimer = setTimeout(() => {
      this._onShowRefreshTimer = null;
      // 刷新用户数据（可选）
      if (that.data.isAppReady) {
        that.getUserData();
        if (resumeHomeLocation && (that._locationEpoch || 0) === locationEpochAtShow) that.getHomeCityLocation();
      }

      // 重新启动倒计时定时器
      if (that.data.upcomingActivityList && that.data.upcomingActivityList.length > 0) {
        that.startCountdownTimer();
      }
    }, 100);
  },

  /**
   * 页面隐藏时
   */
  onHide() {
    this._homeLocationHidden = true;
    this._locationEpoch = (this._locationEpoch || 0) + 1;
    if (this._locationLoadingEpoch != null) {
      this._locationLoadingEpoch = null;
      cyLoading.hide();
    }
    // 全站暗色默认:离开首页保持深色导航/状态栏(后续页同为暗底)。
    applyTheme('dark');

    if (this._onShowRefreshTimer) {
      clearTimeout(this._onShowRefreshTimer);
      this._onShowRefreshTimer = null;
    }

    // 清除倒计时定时器以节省资源
    if (this.data.countdownTimer) {
      clearInterval(this.data.countdownTimer);
      this.setData({ countdownTimer: null });
    }
  },

  /**
   * 页面卸载时 - 清除计时器
   */
  onUnload() {
    this._unloaded = true;
    if (this._offIdentity) { this._offIdentity(); this._offIdentity = null; }
    this._locationEpoch = (this._locationEpoch || 0) + 1;
    if (this._locationLoadingEpoch != null) {
      this._locationLoadingEpoch = null;
      cyLoading.hide();
    }
    if (this._onShowRefreshTimer) {
      clearTimeout(this._onShowRefreshTimer);
      this._onShowRefreshTimer = null;
    }
    if (this._recoTransitionUnlockTimer) {
      clearTimeout(this._recoTransitionUnlockTimer);
      this._recoTransitionUnlockTimer = null;
    }
    // 清除倒计时定时器
    if (this.data.countdownTimer) {
      clearInterval(this.data.countdownTimer);
      this.setData({ countdownTimer: null });
    }
    invalidatePrivateRequests(this, false);
    const listControls = this._listControls || {};
    // 先让全部旧回调失效，再 abort。微信 abort 可能同步触发 fail/complete；
    // 顺序反过来会在页面卸载过程中继续 setData。
    this._listEpoch = Object.create(null);
    this._bannerEpoch = (this._bannerEpoch || 0) + 1;
    Object.keys(listControls).forEach(function (key) {
      const control = listControls[key];
      if (control && typeof control.abort === 'function') control.abort();
    });
    this._listControls = Object.create(null);
  },

  /**
   * 切换标签页
   */
  switchTab(e) {
    const index = e.detail.key;
    const that = this;
    
    
    // 更新activeTab
    that.setData({ activeTab: index });
    
    if (index === '0') {
      // 切换到主题
      if (that.data.bottomTopicList.length === 0) {
        that.setData({
          bottomTopicPageNo: 1,
          hasMoreBottomTopic: false,
          bottomTopicList: []
        }, () => {
          that.loadBottomTopicList(1, true);
        });
      }
    } else if (index === '1') {
      // 切换到活动
      if (that.data.bottomActivityList.length === 0) {
        that.setData({
          bottomActivityPageNo: 1,
          hasMoreBottomActivity: false,
          bottomActivityList: []
        }, () => {
          that.loadBottomActivityList(1, true);
        });
      }
    }
  },

  goUser(e){
    wx.switchTab({
      url: '/pages/member/index/index',
    })
  },

  /* ⚠️ 首页主体是整屏 <scroll-view style="height:{{scrollH}}px">,**页面自身永远不滚动**,
   * app.json 的 enablePullDownRefresh 在这里点不着 —— 与 pages/merchant/index 同因,
   * 所以同样走 scroll-view 自带的 refresher。
   * 收圈时机:loadPageData 里那一批请求各自独立、都不返回 promise,给一个确定的收起时机,
   * 否则要么永远转圈,要么得改动七八个 load 方法的签名。 */
  onRefresh: function () {
    if (this.data.refreshing) return;
    this.setData({ refreshing: true });
    this.loadPageData();
    setTimeout(() => this.setData({ refreshing: false }), 800);
  },

  loadPageData: function () {
    const that = this;

    that.getUserData();


    // 固定行动 Banner 不再请求后台随机图。


    // 推荐主题只取人工推荐位，避免与底部全量主题重复。
    that.getListData('recommendedTopicList', '/api/topic/list', {
      pageNum: 1,
      pageSize: 5, is_recommend: 1,
    });

    // 即将上线
    that.getListData('upcomingActivityList', '/api/activity/list', {
      is_my:2,
      status: 2,
      pageNum: 1,
      pageSize: 4,
      sort_type: 2,
    });

    // 底部主题 - 初始化第一页（设置pageSize为2）
    that.setData({
      bottomTopicPageNo: 1,
      hasMoreBottomTopic: false,
      bottomTopicList: []
    }, () => {
      that.loadBottomTopicList(1, true);
    });

    // 底部活动 - 初始化第一页
    that.setData({
      bottomActivityPageNo: 1,
      hasMoreBottomActivity: false,
      bottomActivityList: []
    }, () => {
      that.loadBottomActivityList(1, true);
    });
    
    // 首页定位仅用于确定城市，不把精确坐标用于活动排序。
    that.getListData('nearbyActivityList', '/api/activity/list', {
      is_my: 0,
      sort_type: 2,
      pageNum: 1,
      pageSize: 4,
    });
  },
  
  onReady() {
    this._buildRecoHero();
    // heroBannerList 在 data 里已经是同一份冻结常量(见上方 data 初始化),
    // 这里再 setData 一次只是白渲染一帧。
  },

  // 计算固定顶栏对齐胶囊 + 滚动区高度
  _layoutChrome() {
    const sys = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    const winW = sys.windowWidth || 375;
    let capsuleTop = sys.statusBarHeight || 20;
    let heroActionsTop = 52;
    let heroActionsRight = 16;
    let heroActionsReserve = 68;
    try {
      const cap = wx.getMenuButtonBoundingClientRect();
      if (cap && cap.top) {
        capsuleTop = cap.top;
        heroActionsTop = Math.round(cap.top + cap.height + 8);
        heroActionsRight = Math.max(16, Math.round(winW - cap.right));
        heroActionsReserve = heroActionsRight + 52;
      }
    } catch (e) {}
    this._recoSnapPx = winW * 650 / 750;
    const winH = sys.screenHeight || sys.windowHeight;
    // 2026-07-31:banner 高度回归到改坏前的 6/7 屏(此前被误收到 60vh,见 git 历史)。
    const heroBannerH = Math.round(winH * 3 / 5);
    this.setData({
      capsuleTop,
      heroActionsTop, heroActionsRight, heroActionsReserve,
      scrollH: Math.round(winH),
      heroBannerH,
    });
  },

  // 首页行动 Banner：固定业务入口，不再由后台随机图覆盖。
  _buildHeroBanners() {
    this.setData({ heroBannerList: buildHomeActionBanners() });
  },

  // is_recommend 返回编辑精选，不代表个人兴趣或当前参加资格。
  _buildRecoHero() {
    const items = (this.data.recommendedTopicList || []).slice(0, 5);
    if (!items.length) {
      this.setData({ recoHero: null, recoCards: [], recoCurrent: 0, recoCardStyles: [] });
      return;
    }
    const cards = items.map(function (item) {
      const tags = (item.sysCategoryList || []).map(function (t) { return t.categoryName; }).filter(Boolean);
      const enroll = item.signupCount || item.enrollCount || item.joinCount || item.signUpCount;
      const metaParts = [];
      if (enroll) metaParts.push('🔥 ' + enroll + ' 人已报名');
      if (item.formattedDateTime) metaParts.push(item.formattedDateTime);
      return {
        id: item.id,
        name: item.name,
        cover: item.cover || item.imgUrl,
        tags: tags,
        clubLabel: item.clubName || item.merchantName || tags[0] || '',
        clubAvatar: item.clubAvatar || item.merchantAvatar || item.cover,
        statusLabel: '',
        betaFlag: item.betaFlag == 1 ? 1 : 0,
        metaLine: metaParts.join(' · ') || item.addressName || '',
      };
    });
    const current = Math.min(this.data.recoCurrent || 0, cards.length - 1);
    this.setData({
      recoHero: cards[0],
      recoCards: cards,
      recoCurrent: current,
      recoCardStyles: buildRecoCardStyles(cards.length, current, -1, 0),
    });
  },

  onHeroBannerTap(e) {
    const idx = e.currentTarget.dataset.inx;
    const item = (this.data.heroBannerList || [])[idx];
    if (!item) return;
    if (item._type === 'action') {
      openHomeActionBanner(wx, item);
    }
  },

  // banner 轮播切换
  onBannerChange(e) {
    const cur = (e.detail && e.detail.current) || 0;
    if (cur !== this.data.bannerCurrent) this.setData({ bannerCurrent: cur });
  },

  onRecoTransition(e) {
    const count = this.data.recoCards.length;
    if (count < 2 || this.data.reducedMotion || this._recoTransitionLocked) return;
    const dx = Number(e.detail && e.detail.dx) || 0;
    const progress = clamp01(Math.abs(dx) / (this._recoSnapPx || 1));
    const current = this.data.recoCurrent || 0;
    const direction = dx < 0 ? 1 : -1;
    const target = progress ? (current + direction + count) % count : -1;
    this.setData({ recoCardStyles: buildRecoCardStyles(count, current, target, progress) });
  },

  _lockRecoTransitionTail() {
    this._recoTransitionLocked = true;
    if (this._recoTransitionUnlockTimer) clearTimeout(this._recoTransitionUnlockTimer);
    this._recoTransitionUnlockTimer = setTimeout(() => {
      this._recoTransitionLocked = false;
      this._recoTransitionUnlockTimer = null;
    }, 360);
  },

  onRecoChange(e) {
    const count = this.data.recoCards.length;
    const current = Math.max(0, Math.min(count - 1, Number(e.detail && e.detail.current) || 0));
    this._lockRecoTransitionTail();
    this.setData({
      recoCurrent: current,
      recoCardStyles: buildRecoCardStyles(count, current, -1, 0),
    });
  },

  // 主滚动区
  onMainScroll(e) {
    const top = (e.detail && e.detail.scrollTop) || 0;
    this._lastTop = top;
    const navUp = top > 120;
    if (navUp !== this.data._navUp) this.setData({ _navUp: navUp });
  },

  // 滚动容器触底 → feed 分页加载
  onPagerLower() {
    this.onReachBottom();
  },

  // ========== /整屏分页 ==========

  onShareAppMessage: function (res) {
    if (res.from === 'button') {
      const dataset = res.target.dataset;
      const shareType = dataset.type;
      const id = dataset.id;
      const name = dataset.name;
      const imageUrl = dataset.img;
      
      let path = '';
      let title = '';
      
      if (shareType === 'topic') {
        path = `/pages/topic/index/index?id=${id}`;
        title = `推荐路线：${name}`;
      } else if (shareType === 'activity') {
        const activityShare = buildActivityShare({ id, name: `推荐场次：${name}`, imgUrl: imageUrl });
        path = activityShare.path;
        title = activityShare.title;
      }
      
      return {
        title: title,
        path: path,
        imageUrl: imageUrl,
        success: function (res) {
          cyToast.success('分享成功');
        },
        fail: function (res) {
          // 原文案「分享取消」把失败说成用户主动取消 —— 这是 fail 分支,不是 cancel。
          cyToast('没能分享出去，请重试');
        }
      };
    }
    
    return {
      title: '城瘾Hub',
      path: '/pages/index/index',
      success: function (res) {
        cyToast.success('分享成功');
      }
    };
  },

  goRecoHero: function (e) {
    const index = Number(e && e.currentTarget && e.currentTarget.dataset.index);
    const cards = this.data.recoCards || [];
    if (cards.length > 1 && Number.isInteger(index) && index !== this.data.recoCurrent) {
      this._lockRecoTransitionTail();
      this.setData({
        recoCurrent: index,
        recoCardStyles: buildRecoCardStyles(cards.length, index, -1, 0),
      });
      return;
    }
    const hero = cards[index] || this.data.recoHero;
    if (!hero || !hero.id) return;
    wx.navigateTo({ url: '/pages/topic/index/index?id=' + hero.id });
  },

  goActivity: function (e) {
    const id = e.currentTarget.dataset.id;
    if (id) openScene('play-activity-detail', { id });
  },

  goTopic: function (e) {
    wx.navigateTo({
      url: '/pages/topic/index/index?id=' + e.currentTarget.dataset.id,
    })
  },

  getPhoneNumber: function (e) {
    const that = this;
    
    if (e.detail.errMsg === "getPhoneNumber:ok") {
      const { code } = e.detail;
      const memberId = normalizeMemberId(app.getUserID());
      const requestEpoch = capturePrivateRequest(that, memberId);
      
      cyLoading.show('获取中...');
      
      app.sendRequest({
        url: '/api/getwxbindphone',
        method: "POST",
        data: { code: code },
        success: function (res) {
          cyLoading.hide();
          if (!isCurrentPrivateRequest(that, memberId, requestEpoch)) return;
          if (res.code == "200") {
            var phone = boundPhoneFromResponse(res);
            if (!phone) {
              cyToast(res.msg || '获取手机号失败');
              return;
            }
            that.setData({
              'userInfo.phone': phone,
              showPhoneModal: false
            });
          } else {
            cyToast(res.msg || '获取手机号失败');
          }
        },
        fail: function (res) {
          cyLoading.hide();
          if (!isCurrentPrivateRequest(that, memberId, requestEpoch)) return;
          cyToast('网络错误，请重试');
        }
      });
    } else {
      this.setData({ showPhoneModal: false });
      cyToast('您已取消授权');
    }
  },

  closePhoneModal() {
    phoneReminder.snooze(wx, normalizeMemberId(app.getUserID()));
    this.setData({ showPhoneModal: false });
  },

  getUserData: function () {
    const that = this;
    const memberId = normalizeMemberId(app.getUserID());
    const requestEpoch = capturePrivateRequest(that, memberId);
    if (!that._getUserDataRetryCount) that._getUserDataRetryCount = 0;
    if (!memberId) {
      // 游客是合法状态；登录管理器自己负责落地/重试，这里不能把“尚无身份”误报成过期。
      that._getUserDataRetryCount = 0;
      that.setData({ isLoading: false });
      return;
    }
    app.sendRequest({
      hideLoading: true,
      url: '/api/user/info',
      data: { member_id: memberId },
      method: "POST",
      success: function (res) {
        if (!isCurrentPrivateRequest(that, memberId, requestEpoch)) return;
        that._getUserDataRetryCount = 0;  // 成功后重置,避免下次调用从陈旧计数开始
        if (res && res.code == "200" && res.data && typeof res.data === 'object' && !Array.isArray(res.data)) {
          // F-L5C-04/F-W1-14:首页不再主动弹窗索取手机号,只在确需的业务动作里按需请求或由用户在个人资料页绑定。
          // V-15:头像过 getImgUrl(http 签名链接 / 相对路径真机拒载)
          const userInfo = Object.assign({}, res.data, { avatar: avatarSrc(res.data.avatar, app.getImgUrl && app.getImgUrl.bind(app)) });
          that.setData({ userInfo: userInfo, isLoading: false });
        } else if (res && res.code == "401") {
          that.firstLogin();
        } else {
          app.setOpenID(null);
          app.tips((res && res.msg) || '用户信息加载失败');
          that.setData({ isLoading: false });
        }
      },
      fail: function (res) {
        if (!isCurrentPrivateRequest(that, memberId, requestEpoch)) return;
        that.setData({ isLoading: false });
      }
    })
  },
  
  firstLogin: function (e) {
    // 委托单航班登录管理器(与 app.reLogin/sendRequest 401 共享在途锁),
    // 由 session-manager 统一解析双层 data 并对「无 data」做空值守卫,页面不再裸取 res.data.data。
    var that = this;
    invalidatePrivateRequests(that);
    app.reLogin(function (ok) {
      if (ok) {
        that.loadPageData();
      }
    });
  },

  // 重试:cy-error 的 retry 事件带 data-list=<listName>,回放该列表最后一次请求。
  retryList: function (e) {
    const listName = e.currentTarget.dataset.list;
    const req = this._listReq && this._listReq[listName];
    if (!req) return;
    this.getListData(listName, req.url, req.params, req.processor);
  },

  getListData: function (listName, url, params, dataProcessor = null) {
    const that = this;
    // 记下请求参数,失败后 cy-error 的重试才有的可重放
    that._listReq = that._listReq || {};
    that._listReq[listName] = { url: url, params: params, processor: dataProcessor };
    that._listControls = that._listControls || Object.create(null);
    that._listEpoch = that._listEpoch || Object.create(null);
    const requestEpoch = (that._listEpoch[listName] || 0) + 1;
    that._listEpoch[listName] = requestEpoch;
    const isCurrent = function () { return that._listEpoch[listName] === requestEpoch; };
    const previous = that._listControls[listName];
    if (previous && typeof previous.abort === 'function') previous.abort();
    delete that._listControls[listName];
    that.setData({
      ['listLoading.' + listName]: true,
      ['listErr.' + listName]: false,
    });

    // 失败标记:发起新请求时先清掉旧错误并显示 loading；失败/非 200 再进入 error。
    const markErr = function (why) {
      if (!isCurrent()) return;
      console.error('[index] 列表加载失败');
      that.setData({ ['listErr.' + listName]: true });
    };

    let settled = false;
    const control = app.sendRequest({
      hideLoading: true,
      url: url,
      method: "POST",
      auth: false,
      data: params,
      success: function (res) {
        if (!isCurrent()) return;
        if (res && res.code == "200" && res.data && isRecordList(res.data.rows)) {
          let list = res.data.rows;
          
          for (let i = 0; i < list.length; i++) {
            // 新增：格式化显示时间
            if (list[i]['startDate']) {
              // 获取开始日期时间
              const startDate = new Date(list[i]['startDate'].replace(/-/g, '/'));
              
              // 格式化日期显示
              list[i]['formattedDateTime'] = that.formatDateTimeForDisplay(startDate);
              
              // 保留原有的月份和日期字段
              const [datePart, timePart] = list[i]['startDate'].split(' ');
              if (datePart) {
                const [year, month, day] = datePart.split('-');
                list[i]['month'] = month;
                list[i]['day'] = day;
                
                let hourMinute = '00:00';
                if (timePart) {
                  const timeParts = timePart.split(':');
                  hourMinute = `${timeParts[0]}:${timeParts[1]}`;
                }
                list[i]['dateTime'] = `${month}.${day} ${hourMinute}`;
                list[i]['formattedStartDate'] = `${month}.${day} ${hourMinute}`;
              }
              
              // 初始化倒计时(缩写格式:还剩Nd / 还剩Nh / 已开始)
              const r = fmtRemain(list[i]['startDate']);
              list[i]['remainingTime'] = r.remainingTime;
              list[i]['started'] = r.started;
            }

            // 如果有结束日期，也进行格式化
            if (list[i]['endDate']) {
              const endDate = new Date(list[i]['endDate'].replace(/-/g, '/'));
              list[i]['formattedEndDateTime'] = that.formatDateTimeForDisplay(endDate);
              
              const [datePart, timePart] = list[i]['endDate'].split(' ');
              if (datePart) {
                const [year, month, day] = datePart.split('-');
                list[i]['endmonth'] = month;
                list[i]['endday'] = day;
                
                let hourMinute = '00:00';
                if (timePart) {
                  const timeParts = timePart.split(':');
                  hourMinute = `${timeParts[0]}:${timeParts[1]}`;
                }
                list[i]['endDateTime'] = `${month}.${day} ${hourMinute}`;
                list[i]['formattedEndDate'] = `${month}.${day} ${hourMinute}`;
              }
            }
            
            if (list[i]['distance']) {
              list[i]['distance'] = (list[i]['distance'] / 1000).toFixed(2) + 'km';
            }
          }
          
          if (dataProcessor && typeof dataProcessor === 'function') {
            list = dataProcessor(list);
          }
  
          list = that.processTopicDate(list);
          list = decorateImgs(list);   // 多图拆分 imgArr → imgList/cover

          const dataObj = {};
          dataObj[listName] = list;
          dataObj['listErr.' + listName] = false; pageMotion.enter(that, 'pm_' + listName); // 2026-10-23:列表到达时依次浮上来
          that.setData(dataObj, function () {
            if (listName === 'nearbyActivityList' || listName === 'recommendedTopicList') {
              that._buildRecoHero();
              that._buildHeroBanners();
            }
          });

          // 如果是即将上线列表，确保定时器正在运行
          if (listName === 'upcomingActivityList' && list.length > 0) {
            that.ensureCountdownTimerRunning();
          }
        } else {
          // 非 200 也是失败:原码直接落地不管,列表停在 [] → 渲染成空态/「正在加载」
          markErr(res && res.msg ? res.msg : '列表加载失败，请下拉刷新');
        }
      },
      // 原为空 fail:{} —— 列表停在 [] 且无任何标记,wxml 于是渲染
      // 「正在你周围搜寻可点亮的节点…」/「正在为你匹配专属主题…」,
      // 断网时页面永久假装自己在加载,既不报错也不给重试。
      fail: function (res) {
        markErr(res && res.errMsg);
      },
      complete: function () {
        settled = true;
        if (!isCurrent()) return;
        delete that._listControls[listName];
        that.setData({ ['listLoading.' + listName]: false });
      },
    });
    if (!settled && isCurrent() && control && typeof control.abort === 'function') {
      that._listControls[listName] = control;
    }
  },

  // 日期展示统一走 utils/index/index-format.js。
  formatDateTimeForDisplay: function(date) {
    return indexFormat.formatDateTimeForDisplay(date);
  },

  // 加载底部主题列表
loadBottomTopicList: function(pageNo, isRefresh = false) {
  const that = this;
  
  if (that.data.isBottomLoading && !isRefresh) return;
  
  that.setData({ isBottomLoading: true });
  
  app.sendRequest({
    hideLoading: isRefresh,
    url: '/api/topic/list',
    method: "POST",
    auth: false,
    data: {
      pageNum: pageNo,
      pageSize: that.data.pageSize, // 每页显示2条
      sort_type: 1,
    },
    success: function (res) {
      if (!(res && res.code == "200" && res.data && isRecordList(res.data.rows))) {
        // A-14-2:原来只弹一次 toast,信息流底部仍是空的、用户没有任何重试入口。
        // 失败落「失败页」标记,由列表底部的 cy-inline-error 承接并可原地重试。
        that.setData({ isBottomLoading: false, bottomTopicFailedPage: pageNo });
        return;
      }
      {
        let list = res.data.rows;
        const total = res.data.total || 0;
        
        
        // 处理日期格式
        list = that.processTopicDate(list);
        
        // 新增：为每个主题项添加 formattedDateTime 字段
        for (let i = 0; i < list.length; i++) {
          if (list[i]['startDate']) {
            try {
              const startDate = new Date(list[i]['startDate'].replace(/-/g, '/'));
              if (!isNaN(startDate.getTime())) {
                list[i]['formattedDateTime'] = that.formatDateTimeForDisplay(startDate);
              }
            } catch (e) {
              console.warn('主题开始日期格式化失败');
            }
          }
          
          if (list[i]['endDate']) {
            try {
              const endDate = new Date(list[i]['endDate'].replace(/-/g, '/'));
              if (!isNaN(endDate.getTime())) {
                list[i]['formattedEndDateTime'] = that.formatDateTimeForDisplay(endDate);
              }
            } catch (e) {
              console.warn('主题结束日期格式化失败');
            }
          }
        }
        
        // 计算总页数
        const totalPages = Math.ceil(total / that.data.pageSize);
        
        if (isRefresh) {
          pageMotion.enter(that, 'pmFeed'); // 刷新时替换数据(2026-10-23:新的一页依次浮上来)
          that.setData({
            bottomTopicList: decorateImgs(list),
            bottomTopicPageNo: pageNo,
            hasMoreBottomTopic: totalPages > pageNo
          });
        } else {
          // 加载更多只按数组路径回传新增行(P0-4:整表 concat 会把已加载行每次全量跨序列化线重发)
          that.setData(Object.assign(
            {
              bottomTopicPageNo: pageNo,
              hasMoreBottomTopic: totalPages > pageNo
            },
            appendByIndex('bottomTopicList', that.data.bottomTopicList.length, decorateImgs(list))
          ));
        }
        
        // 调试输出
        // console.log('设置后的状态:', {
//           pageNo: pageNo,
//           hasMore: totalPages > pageNo,
//           listLength: isRefresh ? list.length : that.data.bottomTopicList.length
//         });
      }
      that.setData({ isBottomLoading: false, bottomTopicFailedPage: 0 });
    },
    fail: function (res) {
      // A-14-2:网络失败同样落行内重试,不再只弹一次 toast。
      that.setData({ isBottomLoading: false, bottomTopicFailedPage: pageNo });
    }
  })
},
// 加载底部活动列表
loadBottomActivityList: function(pageNo, isRefresh = false) {
  const that = this;
  
  if (that.data.isBottomLoading && !isRefresh) return;
  
  that.setData({ isBottomLoading: true });
  
  app.sendRequest({
    hideLoading: isRefresh,
    url: '/api/activity/list',
    method: "POST",
    auth: false,
    data: {
      is_my: 0,
      pageNum: pageNo,
      pageSize: that.data.pageSize, // 每页显示10条
      sort_type: 1,
      status: 1,
    },
    success: function (res) {
      if (!(res && res.code == "200" && res.data && isRecordList(res.data.rows))) {
        // A-14-2:同主题列表,失败落行内重试。
        that.setData({ isBottomLoading: false, bottomActivityFailedPage: pageNo });
        return;
      }
      {
        let list = res.data.rows;
        const total = res.data.total || 0;
        
        
        // 处理日期格式
        for (let i = 0; i < list.length; i++) {
          if (list[i]['startDate']) {
            const originalStartDate = list[i]['startDate'];
            const [datePart, timePart] = originalStartDate.split(' ');
            
            // 新增：格式化显示时间
            try {
              const startDate = new Date(originalStartDate.replace(/-/g, '/'));
              if (!isNaN(startDate.getTime())) {
                list[i]['formattedDateTime'] = that.formatDateTimeForDisplay(startDate);
              }
            } catch (e) {
              console.warn('活动开始日期格式化失败');
            }
            
            if (datePart) {
              const [year, month, day] = datePart.split('-');
              let hourMinute = '00:00';
              if (timePart) {
                const timeParts = timePart.split(':');
                hourMinute = `${timeParts[0]}:${timeParts[1]}`;
              }
              list[i]['dateTime'] = `${month}.${day} ${hourMinute}`;
            }
          }
          
          // 如果有结束日期，也进行格式化
          if (list[i]['endDate']) {
            try {
              const endDate = new Date(list[i]['endDate'].replace(/-/g, '/'));
              if (!isNaN(endDate.getTime())) {
                list[i]['formattedEndDateTime'] = that.formatDateTimeForDisplay(endDate);
              }
            } catch (e) {
              console.warn('活动结束日期格式化失败');
            }
          }
        }
        
        // 计算总页数
        const totalPages = Math.ceil(total / that.data.pageSize);
        
        if (isRefresh) {
          pageMotion.enter(that, 'pmFeed'); // 刷新时替换数据(2026-10-23:新的一页依次浮上来)
          that.setData({
            bottomActivityList: decorateImgs(list),
            bottomActivityPageNo: pageNo,
            hasMoreBottomActivity: totalPages > pageNo
          });
        } else {
          // 加载更多只按数组路径回传新增行(P0-4:整表 concat 会把已加载行每次全量跨序列化线重发)
          that.setData(Object.assign(
            {
              bottomActivityPageNo: pageNo,
              hasMoreBottomActivity: totalPages > pageNo
            },
            appendByIndex('bottomActivityList', that.data.bottomActivityList.length, decorateImgs(list))
          ));
        }
      }
      that.setData({ isBottomLoading: false, bottomActivityFailedPage: 0 });
    },
    fail: function (res) {
      // A-14-2:网络失败同样落行内重试,不再只弹一次 toast。
      that.setData({ isBottomLoading: false, bottomActivityFailedPage: pageNo });
    }
  })
},
  /**
   * 页面上拉触底事件的处理函数
   */
  onReachBottom() {
    const that = this;

    
    if (that.data.activeTab === '0') {
      // 主题列表触底
      // console.log('主题分页状态:', {
//         hasMoreBottomTopic: that.data.hasMoreBottomTopic,
//         isBottomLoading: that.data.isBottomLoading,
//         pageNo: that.data.bottomTopicPageNo,
//         listLength: that.data.bottomTopicList.length
//       });
      
      if (that.data.hasMoreBottomTopic && !that.data.isBottomLoading) {
        const nextPage = that.data.bottomTopicPageNo + 1;
        that.loadBottomTopicList(nextPage);
      } else {
        // 只在确实没有更多数据时显示提示
        if (!that.data.hasMoreBottomTopic && that.data.bottomTopicList.length > 0) {
          cyToast('没有更多了', { duration: 1500 });
        }
      }
    } else if (that.data.activeTab === '1') {
      // 活动列表触底
      // console.log('活动分页状态:', {
//         hasMoreBottomActivity: that.data.hasMoreBottomActivity,
//         isBottomLoading: that.data.isBottomLoading,
//         pageNo: that.data.bottomActivityPageNo,
//         listLength: that.data.bottomActivityList.length
//       });
      
      if (that.data.hasMoreBottomActivity && !that.data.isBottomLoading) {
        const nextPage = that.data.bottomActivityPageNo + 1;
        that.loadBottomActivityList(nextPage);
      } else {
        // 只在确实没有更多数据时显示提示
        if (!that.data.hasMoreBottomActivity && that.data.bottomActivityList.length > 0) {
          cyToast('没有更多了', { duration: 1500 });
        }
      }
    }
  },

  // A-14-2:底部信息流行内重试。按失败页原样重来:第 1 页失败按刷新口径重拉(替换),
  // 第 N 页失败按翻页口径续拉(追加),不改变既有分页语义。
  retryBottomTopic() {
    const failedPage = this.data.bottomTopicFailedPage || 1;
    this.loadBottomTopicList(failedPage, failedPage === 1);
  },

  retryBottomActivity() {
    const failedPage = this.data.bottomActivityFailedPage || 1;
    this.loadBottomActivityList(failedPage, failedPage === 1);
  },

  processTopicDate: function (list) {
    return indexFormat.processTopicDate(list);
  },

  getHomeCityLocation(opts) {
    if (this._unloaded || this._homeLocationHidden) return;
    const scope = captureLocationRequest(this);
    scope.userInitiated = !!(opts && opts.userInitiated);
    if (!(Number(scope.memberId) > 0) || !scope.authorization || scope.authorization === 'dev-token') {
      if (!this.data.city) this.setData({ city: homeCity.readLastCity() || homeCity.DEFAULT_CITY, cityManual: false });
      return;
    }
    const that = this;
    this.setData({ city: this.data.city || homeCity.readLastCity(), cityManual: false });
    app.sendRequest({
      url: '/api/compliance/consents/latest',
      method: 'POST',
      data: JSON.stringify({ docType: 'privacy_policy', scene: 'home_city_location' }),
      header: { 'Content-Type': 'application/json' },
      hideLoading: true,
      autoErrorToast: false,
      success(res) {
        if (!isCurrentLocationRequest(that, scope)) return;
        if (!res || Number(res.code) !== 200) { showHomeCityLocationError(that, scope, 'consent'); return; }
        const previous = res.data;
        if (previous && previous.docType === 'privacy_policy' && previous.scene === 'home_city_location'
            && previous.eventType === 'REVOKE') return;
        if (isHomeCityAgreement(res.data)) {
          scope.consentRequestId = res.data.requestId;
          that.executeHomeCityLocation(scope);
        } else {
          that.requestHomeCityConsent(scope);
        }
      },
      successStatusAbnormal() { showHomeCityLocationError(that, scope, 'consent'); },
      fail() { showHomeCityLocationError(that, scope, 'consent'); }
    });
  },

  requestHomeCityConsent(scope) {
    const that = this;
    if (!isCurrentLocationRequest(this, scope)) return;
    modal.show({
      title: '首页城市定位',
      content: '同意使用精确位置并发送给城瘾以确定首页城市，后续自动定位且不用于附近排序吗？',
      confirmText: '同意定位',
      cancelText: '暂不定位',
      success(result) {
        if (!result.confirm || !isCurrentLocationRequest(that, scope)) return;
        Promise.resolve().then(() => {
          if (!isCurrentLocationRequest(that, scope)) return null;
          return app.recordConsent({ docType: 'privacy_policy', scene: 'home_city_location', eventType: 'AGREE' });
        }).then(saved => {
          if (!isCurrentLocationRequest(that, scope)) return;
          const row = saved && saved.response && saved.response.data;
          if (!saved || !saved.response || Number(saved.response.code) !== 200
              || saved.requestId !== (row && row.requestId) || !isHomeCityAgreement(row)) {
            throw new Error('城市定位同意记录未完成');
          }
          scope.consentRequestId = row.requestId;
          that.executeHomeCityLocation(scope);
        }).catch(() => {
          if (isCurrentLocationRequest(that, scope)) cyToast('定位同意记录未保存，请重试');
        });
      }
    });
  },

  executeHomeCityLocation(scope) {
    if (!isCurrentLocationRequest(this, scope) || !scope.consentRequestId) return;
    if (typeof wx.getLocation !== 'function') { showHomeCityLocationError(this, scope, 'device'); return; }
    const that = this;
    cyLoading.show('确定城市中...');
    this._locationLoadingEpoch = scope.epoch;
    wx.getLocation({
      type: 'gcj02',
      success(res) {
        hideLocationLoading(that, scope);
        if (!isCurrentLocationRequest(that, scope)) return;
        const latitude = res && res.latitude;
        const longitude = res && res.longitude;
        if (!Number.isFinite(latitude) || !Number.isFinite(longitude)
            || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
          showHomeCityLocationError(that, scope, 'device'); return;
        }
        that.reverseGeocode(latitude, longitude, scope);
      },
      fail(err) {
        const msg = String((err && err.errMsg) || '');
        if (/auth deny|authorize|permission|system permission|location service|频繁/i.test(msg)) offerLocationSettings(that, scope);
        else showHomeCityLocationError(that, scope, 'device');
      }
    });
  },

  reverseGeocode(latitude, longitude, scope) {
    const that = this;
    if (!isCurrentLocationRequest(this, scope) || !scope.consentRequestId) return;
    app.sendRequest({
      url: '/api/map/reverse-geocode',
      method: 'POST',
      hideLoading: true,
      autoErrorToast: false,
      data: { latitude: latitude, longitude: longitude },
      success(res) {
        if (!isCurrentLocationRequest(that, scope)) return;
        const data = res && Number(res.code) === 200 && res.data;
        const city = data && data.city;
        if (typeof city === 'string' && city.trim()) { that.setData({ city: city.trim(), cityManual: false }); homeCity.saveLastCity(city.trim()); return; }
        if (data && data.manualInputRequired) { offerManualCity(that, scope, data.reason); return; }
        showHomeCityLocationError(that, scope, 'empty');
      },
      successStatusAbnormal() { showHomeCityLocationError(that, scope, 'network'); },
      fail() { showHomeCityLocationError(that, scope, 'network'); }
    });
  },

  // 手动选择城市:仅本地展示,不上传位置、不写同意记录。
  onManualCityChange(e) {
    const idx = Number(e && e.detail && e.detail.value);
    const city = String(homeCity.CITY_OPTIONS[idx] || '').trim();
    if (city) { this.setData({ city: city, cityManual: false }); homeCity.saveLastCity(city); }
  },

  // V-01:用户主动点城市重新定位，失败时才提示。
  onTapHomeCity() {
    this.getHomeCityLocation({ userInitiated: true });
  },
})

// 服务端 MAP_UNAVAILABLE / MAP_RATE_LIMITED:不再死胡同,露出手动选城市入口。
function offerManualCity(page, scope, reason) {
  hideLocationLoading(page, scope);
  if (!isCurrentLocationRequest(page, scope)) return;
  if (!page.data.city) page.setData({ city: homeCity.DEFAULT_CITY, cityManual: false });
  if (scope.userInitiated) cyToast(reason === 'MAP_RATE_LIMITED' ? '定位请求较频繁，请手动选择城市' : '暂时无法自动识别城市，请手动选择');
}

// 权限被拒/系统定位关闭:只引导打开设置一次(本页面实例内)。
function offerLocationSettings(page, scope) {
  hideLocationLoading(page, scope);
  if (!isCurrentLocationRequest(page, scope)) return;
  if (!scope.userInitiated) { if (!page.data.city) page.setData({ city: homeCity.DEFAULT_CITY, cityManual: false }); return; }
  if (page._locationSettingsOffered) { cyToast(homeCity.message('device')); return; }
  page._locationSettingsOffered = true;
  modal.show({
    title: '需要定位权限',
    content: '无法获取位置。请确认已开启手机定位，并在设置中允许小程序使用位置信息。',
    confirmText: '去设置',
    cancelText: '暂不',
    success(result) {
      if (!result.confirm || !isCurrentLocationRequest(page, scope) || typeof wx.openSetting !== 'function') return;
      wx.openSetting({
        success(setting) {
          const granted = setting && setting.authSetting && setting.authSetting['scope.userLocation'];
          if (granted && isCurrentLocationRequest(page, scope)) page.getHomeCityLocation();
        }
      });
    }
  });
}
