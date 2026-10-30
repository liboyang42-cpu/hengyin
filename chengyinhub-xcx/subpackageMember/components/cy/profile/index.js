const { seatTarget, seatKey, captureLogin, loginCurrent, seatRecovery } = require('../../../../utils/registration-seat.js');
const { readPublisherAccess } = require('../../../utils/coop-publisher-access.js'); const pageMotion = require('../../../../utils/page-motion.js');
const { returnToCoopOrigin } = require('../../../utils/coop-invite-return.js');
const modal = require('../../../../utils/modal.js');
const cyLoading = require('../../../../utils/loading.js');
const cyToast = require('../../../../utils/toast.js');
const motion = require('../../../../utils/motion.js');
const { readReducedMotion } = require('../../../../utils/motion-preference.js');
const { toTimestamp } = require('../../../../utils/datetime');
const app = getApp();
const session = require('../../../../utils/session/login-reload.js');
function profileAuthorization() {
  const value = typeof app.getAuthorization === 'function' ? app.getAuthorization()
    : typeof app.getToken === 'function' ? app.getToken() : app.globalData && app.globalData.authorization;
  return typeof value === 'string' ? value.trim() : '';
}
const community = require('../../../../utils/community-post-api.js').createClient(app, wx);
const roleGuard = require('../../../../utils/roleGuard.js');
const policy = require('../../../../utils/identity/identity-policy.js');
const { applyTheme } = require('../../../../utils/theme.js');
const { resolveVerificationScan } = require('../../../../utils/verification-scan.js');
const { completionReceiptMessage, resolveVerificationResult, classifyVerificationOutcome, createVerificationWorkflow } = require('../../../../utils/merchant-verification.js');
const { getScene } = require('../../../../utils/scene-registry.js');
const { pushScene, popScene, currentScene, exitDecision } = require('../../../../utils/scene-stack.js');
const { offerVerificationReadback } = require('../../../../utils/verification-readback.js');
const { isRecord, isRecordList } = require('../../../../utils/response-shape.js');
const { inactiveAccess, normalizeMerchantAccess } = require('../../../../utils/merchant-access-policy.js');
const { isEndedProject, statusLabelOf } = require('../../../../utils/merchant-workbench.js');
const mediaPreview = require('../../../../utils/media-preview.js');
const { buildOrderPreviewList } = require('./order-preview.js');
const { shapeMerchantSafety } = require('./merchant-safety-summary.js');
const { shapeMerchantNode, readFavoriteToggle } = require('./merchant-node-favorite.js');
const identitySync = require('../../../../utils/identity-sync.js');
const { avatarSrc } = identitySync;
const tagLibrary = require('../../../../utils/tag-library.js');
const THEME_EMOJI = ['🍜', '❤️', '📚', '☕', '🌸', '🎮', '📷', '🏯'];
const MEDAL_EMOJI = {
  ramen: '🍜', coffee: '☕', book: '📚', history: '🏯', spring: '🌸',
  couple: '❤️', story: '🎮', photo: '📷', streak: '🔥', explore: '🧭'
};

const TAB_LABELS = {
  projects: '我的',
  posts: '帖文',
  achievements: '成就',
  about: '关于'
};
// cy-tabs 组件用:[{key,label}],由 TAB_LABELS 派生,保持 Tab 标签单一真源
const MEMBER_TABS = Object.keys(TAB_LABELS).map(function (k) { return { key: k, label: TAB_LABELS[k] }; });
const PUBLIC_TABS = MEMBER_TABS.filter(function (tab) { return tab.key !== 'projects'; });
function communityReadable() {
  return typeof app.canUseCommunity === 'function' && app.canUseCommunity(false);
}
function profileTabs(isSelf, merchant) {
  return (isSelf ? MEMBER_TABS : PUBLIC_TABS).filter(tab =>
    (tab.key !== 'posts' || communityReadable()) && (tab.key !== 'achievements' || !merchant));
}
const PROFILE_METRIC_SOURCES = ['user', 'growth', 'history', 'points'];

function buildBenefits(p) {
  p = p || {};
  var max = p.maxThemes;
  return [
    { text: '发布路线 · 上限 ' + (max == null ? '不限' : (max || 0)), unlocked: (max == null || max > 0) },
    { text: '发布优惠券', unlocked: p.canPublishCoupon === true },
    { text: '到店核销', unlocked: p.canRedeemCoupon === true },
    { text: '分支 / 条件玩法', unlocked: p.canBranch === true },
    { text: '博弈机制（竞速/对抗/协作）', unlocked: p.canGameMechanic === true },
    { text: '开放门店为城市节点', unlocked: p.canOpenAsNode === true },
    { text: '勋章设计', unlocked: p.canDesignMedal === true },
    { text: '建群', unlocked: p.canCreateGroup === true },
    { text: '我的收益', unlocked: p.withdrawable === true, route: '/subpackageA/pages/assetcenter/earnings/index' }
  ];
}

function req(url, data) {
  return new Promise(function (resolve) {
    community.request({
      url: url,
      method: 'POST',
      data: data || {},
      hideLoading: true,
      success: function (res) { resolve(res || {}); },
      fail: function (res, status) { resolve({ code: status || (res && res.code) || 'fail' }); }
    });
  });
}

function isAuthFailure(res) {
  return !!res && (res.code == 401 || res.code == 2);
}

// 商家公开资料里 gallery / tags 是字符串(JSON 串或逗号分隔),口径与旧
// pages/merchant/profile 的 parseDisplayList 保持同一套,别在这另起一份解析。
function displayText(value, keys) {
  if (value == null) return '';
  if (typeof value === 'string' || typeof value === 'number') return String(value).trim();
  if (typeof value !== 'object') return '';
  var candidates = keys || ['name', 'label', 'title', 'value', 'text'];
  for (var i = 0; i < candidates.length; i++) {
    var text = displayText(value[candidates[i]]);
    if (text) return text;
  }
  return '';
}

function parseDisplayList(raw, keys) {
  if (!raw) return [];
  var values = raw;
  if (typeof raw === 'string') {
    try { values = JSON.parse(raw); }
    catch (e) { values = raw.split(/[,，]/); }
  }
  if (!Array.isArray(values)) values = [values];
  return values.map(function (item) { return displayText(item, keys); }).filter(Boolean);
}

/**
 * 商家坐标 —— ★字段名是 locationLat / locationLng。
 *
 * 实体真源:MmsMerchant.locationLat / locationLng(Double),库列 location_lat /
 * location_lng。旧 pages/merchant/profile 读的 m.latitude / m.longitude 在实体上
 * **根本不存在** ⇒ 那处「点击地址导航」自上线起就在 `if (!m.latitude ...) return`
 * 静默早退,一次都没通过电。照抄它就是继承同一个哑弹,别抄。
 *
 * wx.openLocation 的入参才叫 latitude / longitude —— 那是 API 层,和实体字段名
 * 是两层东西,转换只在 goMerchantLocation 里做一次。
 *
 * 有效判据:必须是有限数、非 0、且落在合法经纬度范围内。null / 空串 / NaN 自不必说;
 * 0 也要挡 —— (0,0) 是几内亚湾不是店铺,放行等于给一条点下去跳大西洋的假链接。
 */
function toCoordinate(value, limit) {
  if (value === null || value === undefined || value === '') return null;
  var n = Number(value);
  if (!isFinite(n) || n === 0 || Math.abs(n) > limit) return null;
  return n;
}

function merchantCoordinate(merchant) {
  var m = merchant || {};
  var lat = toCoordinate(m.locationLat, 90);
  var lng = toCoordinate(m.locationLng, 180);
  if (lat === null || lng === null) return null;
  return { latitude: lat, longitude: lng };
}

/**
 * 招牌主推卡(4-12/R9-33):后端 public-home 的 featured 只会是活动(类型 1)或券(类型 2)
 * 的白名单卡,归属/公开态每次读取都重新核验过。判据与据点页
 * components/cy/scene-roam-poi-detail 同一份:类型必须是已知数字,活动还要有正整数 id ——
 * 两者缺一卡片仍显示(信息可信),但不标成可点,不给一个点下去没反应的假按钮。
 * 卡是可选装饰:坏数据只丢这张卡,不抛异常。
 */
function shapeFeaturedCard(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  var type = typeof raw.featuredType === 'number' ? raw.featuredType : null;
  var id = Number(raw.featuredId);
  var featuredId = isFinite(id) && id > 0 ? id : null;
  return {
    featuredType: type,
    featuredId: featuredId,
    titleText: displayText(raw.name) || displayText(raw.title) || '主推内容',
    imageUrl: displayText(raw.imgUrl) || displayText(raw.coverImg) || displayText(raw.img) || '',
    actionable: (type === 1 && featuredId !== null) || type === 2
  };
}

// initialTab 只接受**当前视角真实存在**的 tab key:他人视角没有 projects,
// 传了也不能激活一个渲染不出内容的 tab。非法值返回空串,由调用方走原默认规则。
function resolveInitialTab(raw, tabs) {
  var key = String(raw || '');
  for (var i = 0; i < tabs.length; i++) {
    if (tabs[i].key === key) return key;
  }
  return '';
}

function pct(done, total) {
  if (!total) return 0;
  return Math.min(100, Math.round((done || 0) * 100 / total));
}

function fmtKm(v) {
  var n = Number(v || 0);
  if (n >= 100) return Math.round(n);
  return n.toFixed(1);
}

function metricNumber(value) {
  if (value === null || value === undefined || value === '') return null;
  var number = Number(value);
  return isFinite(number) && number >= 0 ? number : null;
}

function resolveProfileMetricState(sourceStates, sourceSignals) {
  var hasLoading = false;
  for (var i = 0; i < PROFILE_METRIC_SOURCES.length; i++) {
    var source = PROFILE_METRIC_SOURCES[i];
    if (sourceStates[source] === 'error') return 'error';
    if (sourceStates[source] !== 'ready') hasLoading = true;
  }
  if (hasLoading) return 'loading';

  for (var key in sourceSignals) {
    if (!Object.prototype.hasOwnProperty.call(sourceSignals, key)) continue;
    var values = sourceSignals[key] || [];
    for (var j = 0; j < values.length; j++) {
      if (values[j] > 0) return 'ready';
    }
  }
  return 'empty';
}

function pickEmoji(name, list, idx) {
  if (!name) return list[idx % list.length];
  var code = 0;
  for (var i = 0; i < name.length; i++) code += name.charCodeAt(i);
  return list[code % list.length];
}

// 城市名只认服务端明确给的 city 字段。
// ⚠️ 原来在 city 缺失时会拿「工作地点」字段兜底，匹配不到城市名还会 slice(0, 6)
// 把那个地址的前 6 个字直接显示出来。自己看自己无妨，但这套逻辑统一到组件后
// 对他人主页也跑 —— 契约 member-public-contract 明令他人主页不消费工作地点。
// 组件是两个视角共用的，所以这里根本不去读那个字段：拿不到 city 就返回空，
// 由调用方决定显示什么。想从工作地点推城市的兜底，留在「只有自己能看」的页面里做。
// （注释里也刻意不写出那个字段名 —— 契约是纯文本匹配，写出来同样会判红。）
function parseCity(userInfo) {
  var u = userInfo || {};
  return u.city || '';
}

function formatJoinDate(str) {
  if (!str) return '—';
  return String(str).slice(0, 10).replace(/-/g, '.');
}

// 余额格式化。拿不到/不是数就返回 null,模板渲染「—」——
// 资金位上写 0.00 是在断言「你没有钱」,而没查到只说明这次没取到。
function formatBalance(value) {
  if (value === null || value === undefined || value === '') return null;
  var amount = Number(value);
  return isFinite(amount) ? amount.toFixed(2) : null;
}

function pickProjectCover(item) {
  if (!item) return '/images/route_free_cover.png';
  var raw = item.imgArr || item.imgUrl || item.pics || item.cover || '';
  if (Array.isArray(raw)) raw = raw[0] || '';
  // pics 用 ';' 分隔,imgArr 用 ',' 分隔(见 publish/fabu 的 _setImgArr),两者都要取首张
  if (raw) raw = String(raw).split(/[,;]/)[0];
  return raw || '/images/route_free_cover.png';
}

function fmtShortDate(str) {
  if (!str) return '';
  return String(str).slice(0, 10).replace(/-/g, '.');
}

function calcTimeProgress(startDate, endDate) {
  if (!startDate || !endDate) return { percent: 0, label: '未设置项目时间' };
  var start = toTimestamp(String(startDate));
  var end = toTimestamp(String(endDate));
  var label = fmtShortDate(startDate) + ' - ' + fmtShortDate(endDate);
  if (!start || !end || end <= start) return { percent: 0, label: label };
  var now = Date.now();
  if (now <= start) return { percent: 0, label: label };
  if (now >= end) return { percent: 100, label: label };
  return { percent: Math.min(100, Math.round((now - start) * 100 / (end - start))), label: label };
}

// F-W5-03:预览读 /api/topic/list?is_my=1,行上没有 /api/project/my 的归一 state。
// 按 ApiProjectController.lifecycleState 同一优先级派生:草稿 > 未通过 > 审核中 > 已下架 > 按档期;
// 字段缺失就不判,交给档期,不编状态。
function previewState(item) {
  if (item.state) return item.state;
  if (item.isDraft === true) return 'draft';
  if (item.status === null || item.status === undefined || item.status === '') return '';
  var status = Number(item.status);
  if (status === 2) return 'rejected';
  if (status === 0) return 'pending';
  if (item.userStatus !== null && item.userStatus !== undefined && item.userStatus !== '' && Number(item.userStatus) !== 1) return 'offline';
  return '';
}

// M-25:章节承接申请 → 「我的项目」卡。按主题合并(一店常承接同一主题多章),
// 状态:0 审核中 / 1 已通过(再按档期给 未开始/进行中/已结束)/ 2 未通过;已结束、未通过不进预览。
function buildUndertakePreview(rows, limit) {
  var byTopic = {};
  var order = [];
  (rows || []).forEach(function (row) {
    var topicId = row && Number(row.topicId);
    var status = row && Number(row.status);
    if (!topicId || (status !== 0 && status !== 1)) return;
    var key = String(topicId);
    var prev = byTopic[key];
    if (prev && !(prev.status === 0 && status === 1)) return;   // 同主题有已通过的章就按已通过展示
    if (!prev) order.push(key);
    byTopic[key] = { status: status, row: row };
  });
  return order.map(function (key) {
    var hit = byTopic[key];
    var row = hit.row;
    var time = calcTimeProgress(row.startDate, row.endDate);
    var statusText = hit.status === 0 ? '审核中' : statusLabelOf(row.startDate, row.endDate);
    return {
      id: Number(row.topicId),
      name: row.topicName || '未命名主题',
      cover: row.topicImgUrl ? String(row.topicImgUrl).split(/[,;]/)[0] : '/images/route_free_cover.png',
      timeLabel: time.label,
      statusDone: statusText === '已结束',
      statusText: statusText
    };
  }).filter(function (item) { return !item.statusDone; }).slice(0, limit || 8);
}

function normalizeProjectPreview(item) {
  item = Object.assign({}, item, { state: previewState(item) });
  var time = calcTimeProgress(item.startDate, item.endDate);
  // 2026-09-17 用户拍板第 38 条:状态章原来把「未开始」也写成「进行中」,与商家工作台
  // 的 statusLabelOf 不一致。状态文案与「已结束」判据共用同一真源(utils/merchant-workbench.js),
  // 档期未知时 statusLabelOf 返回空串 —— 宁可空着,也不编一个状态。
  var statusText = ({ draft: '草稿', pending: '审核中', rejected: '审核未通过', offline: '已下架' })[item.state] || statusLabelOf(item.startDate, item.endDate);
  var ended = statusText === '已结束';
  var sales = item.salesAmout != null ? Number(item.salesAmout) : 0;
  return {
    id: item.id,
    name: item.name || item.title || '未命名路线',
    cover: pickProjectCover(item),
    pointVal: isNaN(sales) ? 0 : Math.round(sales),
    viewCount: item.viewCount || 0,
    timePercent: time.percent,
    timeLabel: time.label,
    // 2026-08-20 项目卡 v2:卡上只渲染状态章(未开始/进行中/已结束,空态不渲染);
    // pointVal/viewCount/timePercent 字段保留给其他消费方,不随 UI 一起删。
    // 「已结束」与商家工作台同一判据(都出自 statusLabelOf,按日历日),不另算一套:
    // 否则结束当天(未被过滤、仍在预览里)工作台说进行中、这里却写已结束,两处口径打架。
    statusDone: ended,
    statusText: statusText
  };
}

// 主页「我的项目」只是预览:与商家工作台同一规则隐藏已结束项目(判据唯一真源 isEndedProject);
// 「管理全部」进去的 myproject 完整列表不过滤。
function isLiveProject(item) {
  return !isEndedProject(item && item.startDate, item && item.endDate);
}

Component({
  // app.wxss 的主题 token 与页面导入的 merchant-light-scope 必须能匹配组件内主题根节点。
  options: { styleIsolation: 'apply-shared' },
  properties: {
    viewer: { type: String, value: 'self' },
    userId: {
      type: String,
      value: '',
      observer: function () {
        if (this._profileAttached) this.initializeProfile();
      }
    },
    // canonical 深链 ?tab=about。变化时也要重新初始化 —— 组件被复用时
    // 不重算就会停在上一次的 tab 上。
    initialTab: {
      type: String,
      value: '',
      observer: function () {
        if (this._profileAttached) this.initializeProfile();
      }
    },
    topicId: { type: String, value: '' },
    chapterId: { type: String, value: '' },
    topicName: { type: String, value: '' },
    operationScope: { type: String, value: '' },
    // CU-M-09:商家从品牌中心「看公开主页」进来 = 以访客视角预览自己,不走本人的玩家探索态。
    previewSelf: {
      type: Boolean,
      value: false,
      observer: function () {
        if (this._profileAttached) this.initializeProfile();
      }
    }
  },

  data: {
    canInviteCoop: false,
    coopInviteShow: false,
    // [P0-2] ③ 选章核销面板:交集 ≥2 时装候选章节,商家选完调 scan_qr_code_chapter
    chapterSheet: { show: false, code: '', items: [] },
    stationSheet: { show: false, code: '', items: [] },
    statusBarHeight: getApp().globalData.statusBarHeight,
    navBarHeight: getApp().globalData.navBarHeight,
    heroImg: '/images/mer1.jpg',
    userInfo: {},
    categoryList: [],
    casePicsList: [],
    joinInfo: { id: 0 },

    // D-10 入口治理:未上线功能入口隐藏,功能就绪后置 true 恢复

    // 三份身份状态,真源各不相同,不许互相顶替(设计文档 §3.5):
    //  · hostSelf        宿主壳能力(tabBar / hideTabBar),由 viewer 属性决定;
    //  · isSelf          effectiveIsSelf = 当前登录 memberId == 目标 userId。
    //                    商家本人从 canonical 链接进来仍停在 userinfo 宿主,
    //                    只看 viewer 会把本人误判成他人 ⇒ 必须自己算;
    //  · viewerIsMerchant 观看者 RBAC 身份,不参与被看者身份判断;
    //  · subjectIsMerchant 被看者是不是商家(public-home 探测),决定「关于」渲染
    //                    哪一套 + 商家主题。URL 里的 tab/merchantId 只是查询提示,
    //                    绝不能当商家身份真源。
    hostSelf: true,
    isSelf: true,
    selfReadAccess: '',
    merchantAccess: inactiveAccess(),
    merchantAccessState: 'idle',
    merchantTeamEntryValue: '',
    _avatarSrc: '',
    _avatarInitial: '',
    // G-5:身份头名称的唯一出口(商家视角=商家名,玩家=昵称)。本人商家身份(access/me + merchant/info)
    // 放实例字段 this._selfMerchant —— 只给 js 算头像/名称/背景,不进 WXML,不走 setData。
    _displayName: '',
    // M-19:登录者就是被看者(含「看公开主页」预览)—— 本人不关注自己、不向自己发起合作
    viewingOwn: false,
    reportShow: false,
    reportTargetType: '',
    reportTargetId: '',
    reportTitle: '举报',
    correctionShow: false,
    correctionMerchantId: '',
    subjectIsMerchant: false,
    subjectMerchant: null,
    // 门店 NPC 对话:开关 + 面板可见性。
    // ★ 开关从 globalData.features 读(app.js 启动时拉 /api/config/features 覆盖,
    //   拉失败保持默认全关)。缺省 false —— 失败方向是关。
    merchantNpcChatOn: !!(getApp().globalData.features || {}).merchantNpcChat,
    merchantNpcChatShow: false,
    // loading / merchant / not-merchant / network-error 四态互斥。
    // 网络失败绝不回退成玩家 About 或「商家未完善」——那是把「没查到」伪装成业务事实。
    merchantState: 'not-merchant',
    merchantGallery: [],
    merchantTags: [],
    merchantCategories: [],
    // G-8:商家标签库渲染项 [{label, iconSrc}]
    merchantCategoryTags: [],
    merchantTagItems: [],
    merchantHasGeo: false,
    merchantSafety: null,
    merchantNode: null,
    merchantNodeBusy: false,
    // 招牌主推卡:null = 未设置/已下架/已易主/坏数据,整块不渲染
    featured: null,
    activeTab: 'projects',
    memberTabs: MEMBER_TABS,
    tabLabels: TAB_LABELS,

    isClubLeader: false,
    benefits: buildBenefits({}),
    pointsStat: { weekPoints: 0, rankPercentage: 0 },
    pointsSheet: { show: false, loading: false, loaded: false, failed: false, tasks: [], spendRules: [] },

    growthLevel: 1,
    streakDays: 0,
    exploreValue: 0,
    cityName: '上海',
    // 2026-09-18 用户走查 UI-04:数字统计没取到就显示 0,不再显示横杠「—」。
    // 仍不能是 null —— WXML 文本插值会把 null **原样打印成字面「null」**
    // (2026-08-19 A42 实拍:好友「—」、关注「null」、粉丝「null」)。0 是唯一既非空
    // 又不撒谎的初值:接口回来前/看别人主页走别的取数路径时,这三位是「暂无数字」。
    friendNum: 0,
    followNum: 0,
    fansNum: 0,
    profilePoint: null,
    profileMetricState: 'loading',
    profileMetricAuthError: false,
    slogan: '寻找下一条城市路线',
    primaryCta: '开始探索',
    followSubmitting: false,
    followReceipt: false,
    reducedMotion: readReducedMotion(),
    isMerchantView: false,

    identityCard: { shops: 0, themes: 0, routes: 0, km: '0', medals: 0, cities: 1 },
    rankDisplay: { no: 0, delta: '' },
    exploreStat: { shops: 0, weekExp: 0, km: '0.0' },
    themeProgress: [],
    medals: [],
    medalCount: 0,
    joinDate: '—',

    list: [],
    page_no: 1,
    hasMore: false,
    nodata: false,
    orderPreviewList: [], ordFly: '',   // ordFly:owner 10-22 订单卡每次打开都飞入(show 飞入 / hide 先藏好)
    projectPreviewList: [],

    // 订单未加载时不展示内容；只有成功返回空列表才显示暂无订单。
    orderErr: false,
    orderAuthError: false,
    projectErr: false,
    projectAuthError: false,
    feedErr: false,
    feedAuthError: false,
    medalErr: false,
    medalAuthError: false,
    sceneStack: [],
    sceneCurrent: null,
    sceneConfirm: { show: false, action: null, pending: null }
  },

  observers: {
    // 头像位真源:商家主体走 logo,没有就落店名首字;玩家走 avatar,没有才用默认头像那张图。
    // 首字在 js 里算 —— WXML 表达式不支持 charAt。
    // G-5(2026-10-27):本人商家视角同样只认商家名 / Logo(selfMerchant),不再挂会员昵称和个人头像。
    'subjectIsMerchant, subjectMerchant, userInfo, isSelf, isMerchantView': function () {
      var id = this._identityHead();
      this.setData({ _avatarSrc: id.avatar, _avatarInitial: id.initial, _displayName: id.name });
    },
    // 商家版没有「成就」tab(2026-08-20 用户拍板)。isMerchantView 有两个写入点
    // (被看者探测 probeSubjectMerchant / 观看者角色 syncMerchantView),observer 一处兜住全部。
    'topicId, operationScope': function () {
      if (this._profileAttached) this.refreshCoopPublisherAccess();
    },
    'isMerchantView, isSelf': function (isMerchant, isSelf) {
      if (this._profileAttached) this.refreshCoopPublisherAccess();
      var tabs = profileTabs(isSelf, isMerchant);
      // 写成对象字面量而不是累积 patch:U4 门禁要能静态看出写了哪些顶层字段,
      // 累积式 this.setData(patch) 它读不出来,会变成一条新的动态 setData 债务(棘轮只许变小)。
      var tabsChanged = JSON.stringify(tabs) !== JSON.stringify(this.data.memberTabs);
      var resetActive = !tabs.some(tab => tab.key === this.data.activeTab);
      if (tabsChanged && resetActive) this.setData({ memberTabs: tabs, activeTab: 'about' });
      else if (tabsChanged) this.setData({ memberTabs: tabs });
      else if (resetActive) this.setData({ activeTab: 'about' });
    },
  },

  lifetimes: {
    attached: function () {
      this._verificationGeneration = (this._verificationGeneration || 0) + 1;
      this._profileAttached = true;
      this._profileHidden = false;
      this._syncProfileReducedMotion();
      this._identityEpoch = identitySync.identityEpoch();
      this._offIdentity = identitySync.onIdentityChange(this.onIdentityChanged.bind(this));
      this.initializeProfile();
      this._syncCommunityProfile();
    },
    detached: function () {
      if (this._offIdentity) { this._offIdentity(); this._offIdentity = null; }
      this._verificationGeneration = (this._verificationGeneration || 0) + 1;
      this._profileAttached = false;
      this._profileReadEpoch = (this._profileReadEpoch || 0) + 1;
      this._coopAccessEpoch = (this._coopAccessEpoch || 0) + 1;
      this._following = false;
      this._followEpoch = (this._followEpoch || 0) + 1;
      if (this._verificationWorkflow) { this._verificationWorkflow.destroy(); this._verificationWorkflow = null; if (this._verificationLoadingOwners) { this._verificationLoadingOwners.forEach(() => cyLoading.hide()); this._verificationLoadingOwners.clear(); } }
    }
  },

  pageLifetimes: {
    show: function () {
      if (!this._profileAttached) return; this._navigating = false;
      this._profileHidden = false; var ordFly = readReducedMotion() ? '' : 'cy-fly-in'; if (this.data && typeof this.data.ordFly === 'string' && this.data.ordFly !== ordFly) this.setData({ ordFly: ordFly });
      const identityChanged = this._syncProfileReadIdentity();
      if (identityChanged || this._profileNeedsInitialize) {
        this._profileNeedsInitialize = false;
        this._profileKey = null;
        this.initializeProfile();
        this._syncCommunityProfile();
        return;
      }
      this._syncProfileReducedMotion();
      const firstShow = !this._profileShown;
      this._profileShown = true;
      // 登录态可能在页面存续期变化(登录 / 退出 / 换号)⇒ 每次 onShow 重算
      // effectiveIsSelf 与观看者身份;身份变了就整块重来。
      this.syncViewerIdentity();
      this._syncCommunityProfile();
      this.refreshCoopPublisherAccess();
      if (this.computeIsSelf() !== this.data.isSelf) {
        this.initializeProfile();
        return;
      }
      this.refreshDirtyPosts();
      // G-5:别处改了头像/Logo/封面/店名(本组件当时不在栈上或没收到广播)⇒ 丢掉身份缓存重拉
      if (this._identityEpoch !== identitySync.identityEpoch()) {
        this._identityEpoch = identitySync.identityEpoch();
        if (this.data.isSelf && this.data.isMerchantView) this.loadSelfMerchant();
        if (!this.data.isSelf && this.data.subjectIsMerchant) this.probeSubjectMerchant();
      }
      if (firstShow) return;
      if (this.data.hostSelf) {
        this.syncMerchantView();
        wx.hideTabBar();
        this.refreshAll(true);
        return;
      }
      // 他人宿主:主题由**被看者**定,不随观看者角色变色
      applyTheme(this.data.subjectIsMerchant ? 'light' : 'dark');
      if (this.data.isSelf) this.refreshAll(true);
      else this.refreshPublicProfile(true);
    },
    // 商家被看者会把原生导航栏切成日间;离开时必须还原,否则下一页(不自己
    // applyTheme 的那些)会顶着一条白导航栏 —— 旧 merchant/profile 页用
    // merchantPageRestore 处理的就是这件事,收编后这层责任跟着搬过来。
    hide: function () {
      this._profileHidden = true; if (this.data && this.data.orderPreviewList && this.data.orderPreviewList.length) this.setData({ ordFly: readReducedMotion() ? '' : 'cy-fly-wait' });
      this._profileReadEpoch = (this._profileReadEpoch || 0) + 1;
      if (this.data.pointsSheet.loading) this.setData({ 'pointsSheet.loading': false });
      this._profileShown = true;
      if (!this.data.hostSelf && this.data.subjectIsMerchant) applyTheme('dark');
    }
  },

  methods: {
    _clearSelfReadState: function (access) {
      this.setData({ selfReadAccess: access, userInfo: {}, categoryList: [], casePicsList: [], joinInfo: { id: 0 },
        merchantAccess: inactiveAccess(), merchantAccessState: 'idle', merchantTeamEntryValue: '', isMerchantView: false,
        isClubLeader: false, benefits: buildBenefits({}), balanceText: null,
        friendNum: 0, followNum: 0, fansNum: 0, growthLevel: 1, streakDays: 0, exploreValue: 0, profilePoint: null,
        pointsStat: { weekPoints: 0, rankPercentage: 0 }, rankDisplay: { no: 0, delta: '' },
        identityCard: { shops: 0, themes: 0, routes: 0, km: '0', medals: 0, cities: 1 },
        exploreStat: { shops: 0, weekExp: 0 }, themeProgress: [], medals: [], medalCount: 0,
        orderPreviewList: [], projectPreviewList: [], projectPendingCount: 0,
        orderErr: false, orderAuthError: false, projectErr: false, projectAuthError: false, medalErr: false, medalAuthError: false,
        list: [], page_no: 1, hasMore: false, nodata: false, feedErr: false, feedAuthError: false,
        pointsSheet: { show: false, loading: false, loaded: false, failed: false, tasks: [] },
        sceneStack: [], sceneCurrent: null, sceneConfirm: { show: false, action: null, pending: null } });
    },
    _syncProfileReadIdentity: function () {
      const owner = session.memberId(app), token = profileAuthorization();
      const changed = this._readProfileOwner !== undefined
        && (owner !== this._readProfileOwner || token !== this._readProfileToken);
      this._readProfileOwner = owner;
      this._readProfileToken = token;
      if (changed) {
        this._profileReadEpoch = (this._profileReadEpoch || 0) + 1;
        this._profileKey = null;
        if (this.data.isSelf) this._clearSelfReadState(owner && token ? '' : 'login');
      }
      return changed;
    },
    _captureProfileRead: function (privateOnly) {
      if (this._profileAttached === false || this._profileHidden || privateOnly && !this.data.isSelf) return null;
      this._syncProfileReadIdentity();
      const owner = session.memberId(app), token = profileAuthorization();
      if (this.data.isSelf && (!owner || !token)) { this._clearSelfReadState('login'); return null; }
      if (this.data.isSelf && this.data.selfReadAccess === 'login') return null;
      if (this._readProfileOwner === undefined) { this._readProfileOwner = owner; this._readProfileToken = token; }
      return { epoch: this._profileReadEpoch || 0, owner, token, target: String(this.data.userId || ''), self: this.data.isSelf };
    },
    _currentProfileRead: function (read, res, primary) {
      if (!read || this._profileAttached === false || this._profileHidden || read.epoch !== (this._profileReadEpoch || 0)
          || read.target !== String(this.data.userId || '') || read.self !== this.data.isSelf) return false;
      const owner = session.memberId(app), token = profileAuthorization();
      if (read.self && (!owner || !token)) {
        this._profileReadEpoch = (this._profileReadEpoch || 0) + 1;
        this._clearSelfReadState('login');
        return false;
      }
      if (read.owner !== owner || read.token !== token) { this._syncProfileReadIdentity(); return false; }
      if (read.self && isAuthFailure(res)) {
        this._profileReadEpoch = (this._profileReadEpoch || 0) + 1;
        this.setData({ selfReadAccess: 'login' });
        return false;
      }
      if (primary && read.self && res && res.code == 403) { this.setData({ selfReadAccess: 'permission' }); return false; }
      return true;
    },
    loginToReadProfile: function () {
      if (this._profileHidden || this._profileAttached === false) return;
      if (this.data.selfReadAccess === 'permission') { this._profileKey = null; this.initializeProfile(); return; }
      const epoch = this._profileReadEpoch || 0, owner = session.memberId(app);
      return session.retryLogin(this, app, function () {
        if (epoch !== (this._profileReadEpoch || 0) || (owner && owner !== session.memberId(app)) || !profileAuthorization()) return;
        this._profileKey = null;
        this.initializeProfile();
      });
    },
    refreshCoopPublisherAccess: function () {
      const read = this._captureProfileRead(false);
      if (!read) return;
      const epoch = this._coopAccessEpoch = (this._coopAccessEpoch || 0) + 1;
      this.setData({ canInviteCoop: false });
      if (!this.data.isMerchantView || this.data.isSelf) return;
      const memberId = String(app.getUserID() || '');
      readPublisherAccess(app, this.data, allowed => {
        if (!this._currentProfileRead(read) || !this._profileAttached || epoch !== this._coopAccessEpoch) return;
        this._coopPublisherMemberId = allowed ? memberId : '';
        this.setData({ canInviteCoop: allowed });
      });
    },
    _syncProfileReducedMotion: function () {
      var reducedMotion = readReducedMotion();
      if (this.data.reducedMotion !== reducedMotion) this.setData({ reducedMotion: reducedMotion });
    },

    _submitVerification: function (key, loadingTitle, options, callback) {
      var that = this;
      const owner = String(app.getUserID && app.getUserID() || '');
      const generation = this._verificationGeneration || 0;
      if (this._profileAttached === false) return false;
      const target = seatTarget(options);
      const login = captureLogin(app, wx);
      key = target ? seatKey(owner, target) : owner + ':' + key;
      const seatCurrent = () => loginCurrent(app, wx, login) && that._profileAttached !== false && generation === (that._verificationGeneration || 0);
      if (target && !seatCurrent()) return false;
      if (!this._verificationWorkflow) this._verificationWorkflow = createVerificationWorkflow({ deadlineMs: 15000, storage: wx, getOwner: () => app.getUserID && app.getUserID() });
      if (!target && this._verificationWorkflow.isBusy(key)) {
        cyToast('核销处理中，请勿重复提交');
        return false;
      }
      const loadingOwner = {};
      if (!target) {
        if (!this._verificationLoadingOwners) this._verificationLoadingOwners = new Set();
        this._verificationLoadingOwners.add(loadingOwner);
        cyLoading.show(loadingTitle);
      }
      const finishLoading = () => {
        if (that._verificationLoadingOwners && that._verificationLoadingOwners.delete(loadingOwner)) cyLoading.hide();
      };
      var submitted = this._verificationWorkflow.run(key, function (done) {
        return app.sendRequest(Object.assign({}, options, {
          autoErrorToast: false,
          hideLoading: !!target || options.hideLoading, silentError: !!target || options.silentError,
          success: function (res) { done(classifyVerificationOutcome(res, 'success', undefined, options)); },
          fail: function (res, status) { done(classifyVerificationOutcome(res, 'fail', status, options)); },
          successStatusAbnormal: function (res, status) {
            done(classifyVerificationOutcome(res, 'abnormal', status, options));
          }
        }));
      }, function (result) {
        finishLoading();
        if (target && !seatCurrent()) return;
        if (that._profileAttached === false || generation !== (that._verificationGeneration || 0)
            || owner !== String(app.getUserID && app.getUserID() || '')) return;
        if (result.status === 'unknown') {
          callback({ code: 202, msg: target ? '原席位结果待确认；再次扫描同一席位只查询结果，请勿改选席位重发' : '核销结果待确认，请勿重复核销；请先查看核销记录' });
          if (!target) offerVerificationReadback(key, that._verificationWorkflow);
          return;
        }
        callback(result.response);
      }, target ? seatRecovery(app, target, seatCurrent) : undefined);
      if (!submitted) finishLoading();
      return submitted;
    },
  // effectiveIsSelf:宿主 viewer 只是提示,真源是「当前登录 memberId == 目标 userId」。
  computeIsSelf: function () {
    if (this.data.viewer !== 'other') return true;
    if (this.data.previewSelf) return false;
    var loginId = String(app.getUserID() || '');
    var targetId = String(this.data.userId || '');
    return !!loginId && loginId === targetId;
  },

  // 观看者 RBAC 不参与被看者身份与主题。
  syncViewerIdentity: function () {
    var viewerIsMerchant = this.data.merchantAccessState === 'ready'
      ? this.data.merchantAccess.active
      : policy.isMerchantView({ role: app.getUserRole(), userType: app.getUserType() });
    this._viewerIsMerchant = viewerIsMerchant;
  },

  _syncCommunityProfile() {
    const read = this._captureProfileRead(false);
    if (!read) return;
    const owner = String(app.getUserID() || '');
    const target = String(this.data.userId || '');
    const epoch = (this._communityProfileEpoch || 0) + 1;
    this._communityProfileEpoch = epoch;
    const settle = () => {
      if (!this._currentProfileRead(read)) return;
      if (this._profileAttached === false || epoch !== this._communityProfileEpoch
          || owner !== String(app.getUserID() || '') || target !== String(this.data.userId || '')) return;
      const tabs = profileTabs(this.data.isSelf, this.data.isMerchantView);
      if (!communityReadable()) {
        this._postsRequestGeneration = (this._postsRequestGeneration || 0) + 1;
        this.setData({ memberTabs: tabs, list: [], hasMore: false, feedErr: false, feedAuthError: false,
          activeTab: this.data.activeTab === 'posts' ? 'about' : this.data.activeTab });
      } else {
        const activatePosts = this._pendingCommunityTab;
        this._pendingCommunityTab = false;
        this.setData({ memberTabs: tabs, activeTab: activatePosts ? 'posts' : this.data.activeTab });
        if (activatePosts && !this.data.list.length) this.getCreativesquareList();
      }
    };
    settle();
    const pending = typeof app.loadFeatureFlags === 'function' ? app.loadFeatureFlags() : null;
    if (pending && typeof pending.then === 'function') pending.then(settle);
  },

  initializeProfile: function () {
    if (this._profileHidden) { this._profileNeedsInitialize = true; return; }
    this._syncProfileReadIdentity();
    var hostSelf = this.data.viewer !== 'other';
    var isSelf = this.computeIsSelf();
    var userId = hostSelf ? String(app.getUserID() || '') : String(this.data.userId || '');
    var memberTabs = profileTabs(isSelf, this.data.isMerchantView);
    var initialTab = resolveInitialTab(this.data.initialTab, memberTabs);
    var profileKey = (isSelf ? 'self:' : 'other:') + userId + '|' + initialTab;
    if (this._profileKey === profileKey) return;
    this._profileKey = profileKey;
    this._pendingCommunityTab = !communityReadable() &&
      (this.data.initialTab === 'posts' || !isSelf && !initialTab);
    this._following = false;
    this._followEpoch = (this._followEpoch || 0) + 1;
    this._profileShown = false;
    this._viewerIsMerchant = policy.isMerchantView({ role: app.getUserRole(), userType: app.getUserType() });
    this._selfMerchant = null;
    this.setData({
      coopInviteShow: false,
      hostSelf: hostSelf,
      isSelf: isSelf,
      selfReadAccess: '',
      merchantAccess: inactiveAccess(),
      merchantAccessState: 'idle',
      merchantTeamEntryValue: '',
      isClubLeader: false,
      subjectIsMerchant: false,
      subjectMerchant: null,
      merchantState: hostSelf ? 'not-merchant' : 'loading',
      merchantGallery: [],
      merchantTags: [],
      merchantCategories: [],
      merchantCategoryTags: [],
      merchantTagItems: [],
      merchantHasGeo: false,
      merchantSafety: null,
      merchantNode: null,
      merchantNodeBusy: false,
      viewingOwn: !!String(app.getUserID() || '') && String(app.getUserID() || '') === userId,
      // 合法 initialTab 优先;非法值回退原规则(自己 projects、别人 posts)
      activeTab: initialTab || (isSelf ? 'projects' : (communityReadable() ? 'posts' : 'about')),
      memberTabs: memberTabs,
      isMerchantView: false,
      primaryCta: isSelf ? '开始探索' : '关注',
      followSubmitting: false,
      followReceipt: false,
      list: [],
      page_no: 1,
      hasMore: false,
      nodata: false,
      feedErr: false
    });
    if (!hostSelf && !userId) {
      this.setData({ merchantState: 'not-merchant' });
      return;
    }
    if (hostSelf) {
      this.syncMerchantView();
      wx.hideTabBar();
      this.loadBanner();
      this.refreshAll();
      return;
    }
    // 他人宿主:先按玩家夜间起手,商家探测回来再由**被看者**决定主题
    applyTheme('dark');
    this.loadBanner();
    this.probeSubjectMerchant();
    if (isSelf) this.refreshAll();
    else this.refreshPublicProfile();
  },

  /**
   * 被看者是不是商家 —— 唯一真源是 public-home 按 memberId 查得到一条可公开记录。
   * 三条分支必须分得开:成功 / 业务性「不是商家或未开放」/ 网络没查到。
   * 把第三种收敛进第二种,就是拿「没查到」冒充「他不是商家」。
   */
  probeSubjectMerchant: function () {
    const read = this._captureProfileRead(false);
    if (!read) return Promise.resolve();
    var that = this;
    var memberId = String(this.data.userId || '');
    if (!memberId) {
      this.setData({ merchantState: 'not-merchant' });
      return;
    }
    this.setData({ merchantState: 'loading' });
    community.request({
      url: '/api/merchant/public-home',
      method: 'POST',
      hideLoading: true,
      silentError: true,
      header: { 'Content-Type': 'application/json' },
      data: JSON.stringify({ memberId: Number(memberId) || memberId }),
      success: function (res) {
        if (!that._currentProfileRead(read)) return;
        if (res && res.code == '200' && res.data) {
          that.applySubjectMerchant(res.data);
          return;
        }
        if (res && res.msg === '商家不存在或未开放') {
          that.clearSubjectMerchant('not-merchant');
          return;
        }
        that.clearSubjectMerchant('network-error');
      },
      fail: function () { if (that._currentProfileRead(read)) that.clearSubjectMerchant('network-error'); }
    });
  },

  clearSubjectMerchant: function (state) {
    this.setData({
      merchantState: state,
      subjectIsMerchant: false,
      subjectMerchant: null,
      merchantGallery: [],
      merchantTags: [],
      merchantCategories: [],
      merchantCategoryTags: [],
      merchantTagItems: [],
      merchantHasGeo: false,
      merchantSafety: null,
      merchantNode: null,
      merchantNodeBusy: false,
      featured: null,
      isMerchantView: false
    });
    this._merchantSafetyEpoch = (this._merchantSafetyEpoch || 0) + 1;
    this._merchantNodeEpoch = (this._merchantNodeEpoch || 0) + 1;
    applyTheme('dark');
  },

  applySubjectMerchant: function (raw) {
    var m = Object.assign({}, raw);
    m.businessStatusText = m.businessStatus == null ? '—' : (m.businessStatus == 0 ? '已打烊' : '营业中');
    // capacity 是 Integer;拼单位在 JS 里做,模板不做「插值紧贴单位」的写法
    m.capacityText = (m.capacity == null || m.capacity === '') ? '' : (m.capacity + ' 人');
    var hasGeo = merchantCoordinate(m) !== null;
    this.setData({
      merchantState: 'merchant',
      subjectIsMerchant: true,
      subjectMerchant: m,
      merchantNpcChatOn: !!(getApp().globalData.features || {}).merchantNpcChat,
      merchantGallery: parseDisplayList(m.gallery, ['url', 'src', 'imgUrl', 'image']),
      merchantTags: parseDisplayList(m.tags),
      merchantCategories: m.sysCategoryList || [],
      // G-8 / M-12:品类与特色标签都走商家标签库(图标 + 实底小标签)
      merchantCategoryTags: tagLibrary.decorateTags((m.sysCategoryList || []).map(function (c) { return c && c.categoryName; }),
        'merchant', tagLibrary.MEMBER_ICON_DIR),
      merchantTagItems: tagLibrary.decorateTags(parseDisplayList(m.tags), 'merchant', tagLibrary.MEMBER_ICON_DIR),
      merchantHasGeo: hasGeo,
      merchantSafety: null,
      merchantNode: null,
      merchantNodeBusy: false,
      featured: shapeFeaturedCard(m.featured),
      // 商家公开身份接管身份头:品牌名 / 头像 / 主页封面 / 城市角色 / 品牌口号
      heroImg: m.coverImage || this.data.heroImg,
      isMerchantView: true
    });
    // 商家主题由被看者决定,与观看者角色无关
    applyTheme('light');
    this.loadMerchantSafety(m.id);
    this.loadMerchantNode(m.id);
  },

  // BL-66:安全感受摘要只读;样本不足/无数据/失败都不展示,不打扰主页
  loadMerchantSafety: function (merchantId) {
    var that = this;
    var id = Number(merchantId);
    var epoch = this._merchantSafetyEpoch = (this._merchantSafetyEpoch || 0) + 1;
    if (!Number.isInteger(id) || id <= 0) return;
    app.sendRequest({
      url: `/api/community-trust/safety/merchants/${id}`,
      method: 'GET',
      hideLoading: true,
      silentError: true,
      success: function (res) {
        if (epoch !== that._merchantSafetyEpoch || !that.data.subjectIsMerchant) return;
        if (!that.data.subjectMerchant || Number(that.data.subjectMerchant.id) !== id) return;
        that.setData({ merchantSafety: res && res.code == 200 ? shapeMerchantSafety(res.data) : null });
      },
      fail: function () {}
    });
  },

  // BL-69:商家在架据点。无节点(data 为空)/读取失败都不展示收藏按钮,不打扰主页
  loadMerchantNode: function (merchantId) {
    var that = this;
    var id = Number(merchantId);
    var epoch = this._merchantNodeEpoch = (this._merchantNodeEpoch || 0) + 1;
    if (this.data.isSelf || !Number.isInteger(id) || id <= 0) return;
    app.sendRequest({
      url: '/api/city/nodes/merchant/' + id,
      method: 'GET',
      hideLoading: true,
      silentError: true,
      success: function (res) {
        if (epoch !== that._merchantNodeEpoch || !that.data.subjectIsMerchant) return;
        if (!that.data.subjectMerchant || Number(that.data.subjectMerchant.id) !== id) return;
        that.setData({ merchantNode: res && res.code == 200 ? shapeMerchantNode(res.data) : null });
      },
      fail: function () {}
    });
  },

  // BL-69:收藏/取消收藏据点,以切换接口返回的 favorited 回读;失败如实提示,不改本地态
  toggleMerchantNodeFavorite: function () {
    var that = this;
    var node = this.data.merchantNode;
    if (this.data.merchantNodeBusy || !node || !node.poiId) return;
    var epoch = this._merchantNodeEpoch;
    var poiId = node.poiId;
    var settle = function (favorited, msg) {
      if (epoch !== that._merchantNodeEpoch || !that.data.merchantNode || that.data.merchantNode.poiId !== poiId) return;
      if (favorited === null) {
        that.setData({ merchantNodeBusy: false });
        cyToast(msg);
        return;
      }
      that.setData({ merchantNodeBusy: false, 'merchantNode.favorited': favorited });
      cyToast(favorited ? '已收藏节点' : '已取消收藏');
    };
    this.setData({ merchantNodeBusy: true });
    app.sendRequest({
      url: '/api/city/nodes/' + poiId + '/favorite',
      method: 'POST',
      hideLoading: true,
      silentError: true,
      success: function (res) {
        var favorited = readFavoriteToggle(res);
        settle(favorited, (res && res.msg) || '收藏状态没有更新，请重试');
      },
      fail: function () { settle(null, '网络异常，收藏状态没有更新'); },
      successStatusAbnormal: function () { settle(null, '收藏状态没有更新，请重试'); }
    });
  },

  retryMerchantProbe: function () { this.probeSubjectMerchant(); },

  refreshPublicProfile: function (silent) {
    var that = this;
    if (!silent) {
      this.setData({ page_no: 1, list: [], hasMore: false, nodata: false, feedErr: false });
    }
    return this.loadUserData().then(function () {
      if (!silent && that.data.activeTab === 'posts') that.getCreativesquareList();
    });
  },

  syncMerchantView: function () {
    var isMerchant = this.data.merchantAccessState === 'ready'
      ? this.data.merchantAccess.active
      : policy.isMerchantView({ role: app.getUserRole(), userType: app.getUserType() });
    this.setData({ isMerchantView: isMerchant });
    applyTheme(isMerchant ? 'light' : 'dark');
  },

  loadMerchantAccess: function () {
    const read = this._captureProfileRead(true);
    if (!read) return Promise.resolve();
    var that = this;
    if (!this.data.isSelf || !app.getUserID()) {
      this.setData({ merchantAccess: inactiveAccess(), merchantAccessState: 'ready', merchantTeamEntryValue: '' });
      return Promise.resolve();
    }
    this.setData({ merchantAccessState: 'loading' });
    return req('/api/merchant/access/me', {}).then(function (res) {
      if (!that._currentProfileRead(read, res)) return;
      if (res.code != '200' || !isRecord(res.data)) {
        that.setData({ merchantAccess: inactiveAccess(), merchantAccessState: 'error', merchantTeamEntryValue: '' });
        return;
      }
      var access = normalizeMerchantAccess(res.data);
      that._viewerIsMerchant = access.active;
      var viewChanged = access.active !== that.data.isMerchantView;
      // G-5:本人商家身份先用 access/me 里的店名/Logo 顶上,封面等 merchant/info 回读
      that._selfMerchant = access.active ? Object.assign({}, that._selfMerchant || {}, access.merchant) : null;
      that.setData({
        merchantAccess: access,
        merchantAccessState: 'ready',
        merchantTeamEntryValue: access.active
          ? (access.canManageOperators ? access.roleName + ' · 管理' : access.roleName + ' · 查看')
          : '',
        isMerchantView: access.active
      });
      if (that.data.hostSelf) applyTheme(access.active ? 'light' : 'dark');
      if (access.active) that.loadSelfMerchant();
      // M-25:视角在请求途中翻了 ⇒ 我的项目换成对应来源(商家=承接进度),订单段商家视角不出
      if (viewChanged) { that.loadProjectPreview(); if (!access.active) that.loadOrderPreview(); }
    });
  },

  // G-5 / M-17:本人商家视角的店名、Logo、主页背景(=店铺装修封面)只读 /api/merchant/info 这一个来源。
  loadSelfMerchant: function () {
    const read = this._captureProfileRead(true);
    if (!read) return Promise.resolve();
    var that = this;
    return req('/api/merchant/info', {}).then(function (res) {
      if (!that._currentProfileRead(read, res) || !that.data.isMerchantView) return;
      if (res.code != '200' || !isRecord(res.data)) return;
      var m = res.data;
      var prev = that._selfMerchant || {};
      var next = Object.assign({}, prev, {
        name: typeof m.name === 'string' && m.name.trim() ? m.name : prev.name || '',
        logo: typeof m.logo === 'string' && m.logo ? m.logo : prev.logo || '',
        coverImage: typeof m.coverImage === 'string' ? m.coverImage : ''
      });
      that._applySelfMerchant(next);
    });
  },

  // G-5:身份头按视角取唯一来源 —— 商家被看者走 subjectMerchant,本人商家视角走 this._selfMerchant,其余走会员资料。
  _identityHead: function () {
    var d = this.data;
    var selfAsMerchant = !d.subjectIsMerchant && d.isSelf && d.isMerchantView;
    var selfMerchant = this._selfMerchant;
    var id = identitySync.resolveSelfIdentity(d.subjectIsMerchant || selfAsMerchant,
      d.subjectIsMerchant ? d.subjectMerchant : selfMerchant, d.userInfo);
    // 本人商家身份还没回来时先不落「商家」两个字,沿用会员昵称占位一帧,避免闪成默认名
    if (selfAsMerchant && !(selfMerchant && selfMerchant.name)) id.name = (d.userInfo && d.userInfo.nickname) || id.name;
    // V-15:后端头像是 http 签名链接 / 相对路径时真机拒载,显示前过 getImgUrl
    id.avatar = avatarSrc(id.avatar, app.getImgUrl && app.getImgUrl.bind(app));
    return id;
  },

  // 本人商家身份落地:头像/名称/背景(=店铺封面,M-17)一次写齐
  _applySelfMerchant: function (next) {
    this._selfMerchant = next;
    var id = this._identityHead();
    this.setData({ _avatarSrc: id.avatar, _avatarInitial: id.initial, _displayName: id.name,
      heroImg: (next && next.coverImage) || this.data.heroImg });
  },

  // G-5:品牌中心 / 个人资料保存成功后的全局广播。只先把旧图旧名换掉,随后仍以接口回读为准。
  onIdentityChanged: function (evt) {
    this._identityEpoch = evt.epoch;
    if (!this.data.isSelf) {
      if (evt.scope === 'merchant' && this.data.subjectIsMerchant && this.data.viewingOwn) this.probeSubjectMerchant();
      return;
    }
    if (evt.scope === 'merchant' && this.data.isMerchantView) {
      this._applySelfMerchant(Object.assign({}, this._selfMerchant || {}, evt.patch));
      this.loadSelfMerchant();
      return;
    }
    if (evt.scope === 'member') {
      var u = Object.assign({}, this.data.userInfo || {});
      if (evt.patch.avatar) u.avatar = evt.patch.avatar;
      if (evt.patch.nickname) u.nickname = evt.patch.nickname;
      this.setData({ userInfo: u });
    }
  },

  resetProfileMetricState: function () {
    this._profileMetricSourceStates = {};
    this._profileMetricSignals = {};
    this._profileMetricAuthErrors = {};
    for (var i = 0; i < PROFILE_METRIC_SOURCES.length; i++) {
      this._profileMetricSourceStates[PROFILE_METRIC_SOURCES[i]] = 'loading';
    }
    this.setData({ profileMetricState: 'loading', profileMetricAuthError: false });
  },

  recordProfileMetricSource: function (source, status, signals) {
    if (!this._profileMetricSourceStates || !this._profileMetricSignals) {
      this.resetProfileMetricState();
    }
    this._profileMetricSourceStates[source] = status;
    this._profileMetricSignals[source] = status === 'ready' ? (signals || []) : [];
    this._profileMetricAuthErrors[source] = status === 'error' && isAuthFailure(signals);
    var next = resolveProfileMetricState(this._profileMetricSourceStates, this._profileMetricSignals);
    var authErrors = this._profileMetricAuthErrors;
    this.setData({
      profileMetricState: next,
      profileMetricAuthError: PROFILE_METRIC_SOURCES.some(function (key) { return authErrors[key]; })
    });
  },

  refreshAll: function (silent) {
    const read = this._captureProfileRead(true);
    if (!read) return Promise.resolve();
    var that = this;
    if (!silent) this.setData({ page_no: 1, list: [], hasMore: false, nodata: false, feedErr: false });
    this.resetProfileMetricState();
    // ⚠️ 按视角分流，不能无条件拉全部：下面这些接口要么用 app.getUserID()（当前登录用户）、
    // 要么只对「自己」有意义（订单/项目/积分/加入信息）。他人主页调它们既白跑一轮请求，
    // 拿回来的还是**自己的**数据 —— 契约 member-public-contract 盯的就是这件事。
    var shared = [
      this.loadUserData(),
      this.loadRoleInfo(),
      this.loadGrowthBundle(),
      this.loadThemes(),
      this.loadMedals()
    ];
    var selfOnly = this.data.isSelf ? [
      this.loadMerchantAccess(),
      this.loadJoinData(),
      this.loadPointsStat(),
      this.loadOrderPreview(),
      this.loadProjectPreview(),
      this.loadProjectPending()
    ] : [];
    // 返回 Promise:宿主页(我的 tab)要把下拉刷新的收圈绑在真实完成上,而不是定时器。
    return Promise.all(shared.concat(selfOnly)).then(function () {
      that.buildDerivedState();
      if (!silent && that.data.activeTab === 'posts') that.getCreativesquareList();
    });
  },

  loadBanner: function () {
    const read = this._captureProfileRead(false);
    if (!read) return Promise.resolve();
    var that = this;
    // M-17:本人商家视角的背景就是店铺装修封面(loadSelfMerchant 写入),不读玩家自选背景、也不拿运营 banner 顶替
    if (this.data.isSelf && this.data.isMerchantView) {
      var cover = this._selfMerchant && this._selfMerchant.coverImage;
      if (cover) this.setData({ heroImg: cover });
      return;
    }
    var local = this.data.isSelf ? wx.getStorageSync('member_hero_bg') : '';
    if (local) {
      that.setData({ heroImg: local });
      return;
    }
    req('/api/common/banner', { showType: 2 }).then(function (res) {
      if (!that._currentProfileRead(read, res)) return;
      if (res.code == '200' && isRecordList(res.data) && res.data.length) {
        that.setData({ heroImg: res.data[0].picUrl || that.data.heroImg });
      }
    });
  },

  loadUserData: function () {
    const read = this._captureProfileRead(false);
    if (!read) return Promise.resolve();
    var that = this;
    if (that._profileAttached === false) return Promise.resolve();
    var loginMemberId = app.getUserID();
    var requestId = that._userDataRequestId = (that._userDataRequestId || 0) + 1;
    var memberId = this.data.isSelf ? app.getUserID() : this.data.userId;
    var followEpoch = this._followEpoch || 0;
    var followStateVersion = this._followStateVersion || 0;
    var followMutationPending = Boolean(this._following);
    // 他人主页走匿名可读的公开白名单接口:/api/user/info 会先强制登录,历史分享
    // 冷启动就只剩一个没有头信息的空壳。判据用 effectiveIsSelf 而不是宿主传入的
    // viewer —— 否则本人从 canonical 进来会误走公开分支。
    var url = this.data.isSelf ? '/api/user/info' : '/api/user/public-info';
    return req(url, { member_id: memberId }).then(function (res) {
      if (!that._currentProfileRead(read, res, true)) return;
      if (that._profileAttached === false || requestId !== that._userDataRequestId
          || loginMemberId !== app.getUserID()) return;
      // 换了被看者后，旧资料响应整包作废；同一被看者若在请求期间完成关注写入，
      // 资料仍可补齐，但不能用请求发出前的 isFollow 把服务端已确认回执倒灌回去。
      if (followEpoch !== (that._followEpoch || 0)) return;
      if (res.code != '200' || !res.data || typeof res.data !== 'object' || Array.isArray(res.data)) {
        if (that.data.isSelf) that.recordProfileMetricSource('user', 'error', res);
        return;
      }
      if (that.data.isSelf) {
        app.setUserRole(res.data.role);
      }
      var pics = [];
      if (typeof res.data.casePics === 'string' && res.data.casePics.trim()) {
        pics = res.data.casePics.split(';').filter(function (u) { return u.trim(); });
      }
      var followNum = metricNumber(res.data.followNum);
      var fansNum = metricNumber(res.data.fansNum);
      var topicNum = metricNumber(res.data.topicNum);
      var activityNum = metricNumber(res.data.activityNum);
      var likeNum = metricNumber(res.data.likeNum);
      var friendNum = metricNumber(res.data.friendNum);
      // role 不在这里写:它与 loadRoleInfo 同在一个 Promise.all 里,两处都写 data.role
      // 谁后返回谁赢 = 俱乐部入口显示与否不确定。roleGuard 是权限单一事实源(utils/roleGuard.js
      // 头注释),role 只由 loadRoleInfo 写;app 级角色仍由上面的 app.setUserRole 同步。
      var followStateChanged = !that.data.isSelf
        && (followMutationPending || followStateVersion !== (that._followStateVersion || 0));
      var responseUserInfo = res.data;
      if (followStateChanged) {
        responseUserInfo = Object.assign({}, res.data, {
          isFollow: that.data.userInfo && that.data.userInfo.isFollow
        });
      }
      var profilePatch = {
        userInfo: responseUserInfo,
        // 余额来自 /api/user/info 自己看自己那条分支(返回整个 UmsMember,balance 无 @JsonIgnore),
        // 不是新增请求 —— 「探索与更多」那句「钱包余量在现有接口里没有字段」对余额而言不成立。
        // 拿不到就给 null 让模板显示「—」:资金位上 0.00 是个断言,没查到不等于没有钱。
        balanceText: formatBalance(res.data.balance),
        cityName: parseCity(res.data) || '—',
        categoryList: isRecordList(res.data.sysCategoryList) ? res.data.sysCategoryList : [],
        casePicsList: pics,
        joinDate: formatJoinDate(res.data.createTime),
        // F-W5-09:/api/user/info 本人分支按 active 双向关注下发 friendNum。缺字段按 UI-04(2026-09-18 用户走查)落 0,
        // 不再落「—」。⚠️ metricNumber 缺值返回 null,而 WXML 的文本插值会把 null **原样
        // 打印成字面「null」**(2026-08-18 实测 A37/A42),所以展示层兜底必须给具体值。
        // 下面 userSignals 用的仍是数值原值,兜底只发生在展示层,不影响 ready/error 判定。
        friendNum: friendNum == null ? 0 : friendNum,
        followNum: followNum == null ? 0 : followNum,
        fansNum: fansNum == null ? 0 : fansNum
      };
      if (!that.data.isSelf && !followStateChanged) {
        profilePatch.growthLevel = res.data.levelId || 1;
        profilePatch.primaryCta = res.data.isFollow == 1 ? '已关注' : '关注';
      }
      that.setData(profilePatch);
      if (that.data.isSelf) {
        var userSignals = [followNum, fansNum, topicNum, activityNum, likeNum];
        var userSourceReady = userSignals.every(function (value) { return value != null; });
        that.recordProfileMetricSource('user', userSourceReady ? 'ready' : 'error', userSignals);
      }
    });
  },

  loadRoleInfo: function () {
    const read = this._captureProfileRead(true);
    if (!read) return Promise.resolve();
    var that = this;
    return new Promise(function (resolve) {
      roleGuard.load(function (data) {
        if (!that._currentProfileRead(read)) { resolve(); return; }
        if (data && data.role) {
          var isClubLeader = data.isClubLeader === true || data.role === 'club';
          // P-53(2026-10-27):「创建俱乐部/俱乐部」入口已删,role 与自有俱乐部数不再渲染,不写 data 也不再拉 /api/club/my
          that.setData({
            isClubLeader: isClubLeader,
            benefits: buildBenefits(data.permission || {})
          });
        }
        resolve();
      });
    });
  },

  loadGrowthBundle: function () {
    const read = this._captureProfileRead(true);
    if (!read) return Promise.resolve();
    var that = this;
    if (that._profileAttached === false) return Promise.resolve();
    var memberId = app.getUserID();
    var requestId = that._growthRequestId = (that._growthRequestId || 0) + 1;
    var profileEpoch = that._followEpoch || 0;
    return Promise.all([req('/api/play/growth', {}), req('/api/growth/center', {})]).then(function (arr) {
      if (!that._currentProfileRead(read, arr.find(isAuthFailure))) return;
      if (that._profileAttached === false || requestId !== that._growthRequestId
          || memberId !== app.getUserID() || profileEpoch !== (that._followEpoch || 0)) return;
      var playOk = arr[0].code == '200' && arr[0].data;
      var centerOk = arr[1].code == '200' && arr[1].data && arr[1].data.growth;
      if (!playOk || !centerOk) {
        that.recordProfileMetricSource('growth', 'error', arr.find(isAuthFailure));
        return;
      }
      var play = arr[0].data;
      var center = arr[1].data;
      var growth = center.growth;
      var level = metricNumber(play.level != null ? play.level : growth.levelNo);
      var exp = metricNumber(growth.expValue);
      var points = metricNumber(center.points);
      var shops = metricNumber(play.totalCheckins);
      var mileage = metricNumber(play.totalMileage);
      var streak = metricNumber(play.streakDays);
      if ([level, exp, points, shops, mileage, streak].some(function (value) { return value == null; })) {
        that.recordProfileMetricSource('growth', 'error');
        return;
      }
      var km = fmtKm(mileage);
      that.setData({
        growthLevel: Math.max(1, Math.round(level)),
        streakDays: streak,
        exploreValue: exp,
        profilePoint: points,
        identityCard: {
          shops: shops,
          themes: that.data.identityCard.themes,
          routes: (that.data.userInfo.topicNum || 0) + (that.data.userInfo.activityNum || 0),
          km: km,
          medals: that.data.identityCard.medals, // BE-01 勋章单真源:勋章数只由 loadMedals(/api/medal/wall)写,growth 不再返 badgeCount
          cities: 1
        },
        'exploreStat.shops': shops, 'exploreStat.km': km // BL-95: 累计里程补进现有「累计探索」块(同行写,不挪动基线行号)
      });
      // 是否开始过只看历史参与信号(totalCheckins / my-completed),绝不拿里程是否为 0 判断。
      // 这样已完成节点但真实里程为 0.0km 的老用户仍进入 ready,不会被错藏成首访。
      that.recordProfileMetricSource('growth', 'ready', [shops, exp, points]);
    });
  },

  loadJoinData: function () {
    const read = this._captureProfileRead(true);
    if (!read) return Promise.resolve();
    var that = this;
    return req('/api/registration/join_info', { member_id: app.getUserID() }).then(function (res) {
      if (!that._currentProfileRead(read, res)) return;
      if (res.code == '200') that.setData({ joinInfo: res.data || { id: 0 } });
    });
  },

  loadThemes: function () {
    const read = this._captureProfileRead(true);
    if (!read) return Promise.resolve();
    var that = this;
    if (that._profileAttached === false) return Promise.resolve();
    var memberId = app.getUserID();
    var requestId = that._themesRequestId = (that._themesRequestId || 0) + 1;
    var profileEpoch = that._followEpoch || 0;
    return req('/api/play/my-completed', {}).then(function (res) {
      if (!that._currentProfileRead(read, res)) return;
      if (that._profileAttached === false || requestId !== that._themesRequestId
          || memberId !== app.getUserID() || profileEpoch !== (that._followEpoch || 0)) return;
      if (res.code != '200' || !isRecordList(res.data)) {
        that.recordProfileMetricSource('history', 'error', res);
        return;
      }
      var completed = res.data;
      var rows = completed.slice(0, 8).map(function (item, idx) {
        var total = item.total || 0;
        var done = item.doneCount || total;
        return {
          id: item.activityId,
          topicId: item.topicId,
          name: item.name || '城市主题',
          emoji: pickEmoji(item.name, THEME_EMOJI, idx),
          doneCount: done,
          total: total,
          percent: pct(done, total),
          completed: true
        };
      });
      that.setData({ themeProgress: rows, 'identityCard.themes': completed.length });
      // my-completed 还覆盖「已核销但没有节点打卡」的历史参与,补足 totalCheckins 的盲区。
      that.recordProfileMetricSource('history', 'ready', [completed.length]);
    });
  },

  loadMedals: function () {
    const read = this._captureProfileRead(true);
    if (!read) return Promise.resolve();
    var that = this;
    return req('/api/medal/wall', {}).then(function (res) {
      if (!that._currentProfileRead(read, res)) return;
      // 原码失败即 return,medals 停在 [] → 渲染「完成城市节点即可点亮勋章」= 把失败说成没勋章
      if (res.code != '200' || !isRecord(res.data)
          || (res.data.medals != null && !isRecordList(res.data.medals))) {
        that.setData({ medalErr: true, medalAuthError: isAuthFailure(res) });
        return;
      }
      var medals = (res.data.medals || []).map(function (m) {
        var code = (m.badgeCode || m.medalCode || '').toLowerCase();
        return {
          templateId: m.templateId || m.medalId || m.badgeCode,
          medalName: m.medalName || m.badgeName || '城市勋章',
          medalImg: m.medalImg || m.iconUrl,
          emoji: MEDAL_EMOJI[code] || '🏅'
        };
      });
      that.setData({
        medalErr: false,
        medalAuthError: false,
        medals: medals,
        medalCount: res.data.count || medals.length,
        'identityCard.medals': res.data.count || medals.length
      });
    });
  },

  loadPointsStat: function () {
    const read = this._captureProfileRead(true);
    if (!read) return Promise.resolve();
    var that = this;
    if (that._profileAttached === false) return Promise.resolve();
    var memberId = app.getUserID();
    var requestId = that._pointsStatRequestId = (that._pointsStatRequestId || 0) + 1;
    var profileEpoch = that._followEpoch || 0;
    return Promise.all([
      req('/api/user/points/statistics', {}),
      req('/api/growth/leaderboard', { metric: 'exp', period: 'week', limit: 1 })
    ]).then(function (arr) {
      if (!that._currentProfileRead(read, arr.find(isAuthFailure))) return;
      if (that._profileAttached === false || requestId !== that._pointsStatRequestId
          || memberId !== app.getUserID() || profileEpoch !== (that._followEpoch || 0)) return;
      var res = arr[0];
      var weekPoints = res.code == '200' && isRecord(res.data) ? metricNumber(res.data.weekPoints) : null;
      var rankPercentage = isRecord(res.data) ? res.data.rankPercentage : null;
      var pointsOk = weekPoints != null && typeof rankPercentage === 'string' && !!rankPercentage.trim();
      if (pointsOk) {
        var hasRank = rankPercentage.trim() !== 'TOP0%' && metricNumber(res.data.userRank) !== 0;
        that.setData({
          pointsStat: { weekPoints: weekPoints, rankPercentage: rankPercentage },
          rankDisplay: {
            no: hasRank ? rankPercentage : '—',
            delta: hasRank && res.data.rankDelta ? ('上升 ' + res.data.rankDelta) : ''
          }
        });
      }
      // 「本周经验」取经验周榜里本人的 score，不能拿可抵现积分(weekPoints)冒充经验。
      var board = arr[1];
      var me = isRecord(board.data) && isRecord(board.data.me) ? board.data.me : null;
      var expOk = board.code == '200' && me && board.data.metric === 'exp' && board.data.period === 'week'
        && String(me.memberId) === String(memberId) && Number.isSafeInteger(me.score) && me.score >= 0;
      if (expOk) that.setData({ 'exploreStat.weekExp': me.score });
      that.recordProfileMetricSource('points', pointsOk && expOk ? 'ready' : 'error',
        pointsOk && expOk ? [weekPoints, me.score] : arr.find(isAuthFailure));
    });
  },

  loadOrderPreview: function () {
    const read = this._captureProfileRead(true);
    if (!read) return Promise.resolve();
    var that = this;
    if (that._profileAttached === false) return Promise.resolve();
    // M-25:商家视角不报名游玩、没有订单段,不去拉玩家订单
    if (that.data.isMerchantView) {
      that._orderPreviewRequestId = (that._orderPreviewRequestId || 0) + 1;
      that.setData({ orderPreviewList: [], orderErr: false, orderAuthError: false });
      return Promise.resolve();
    }
    var memberId = app.getUserID();
    var requestId = that._orderPreviewRequestId = (that._orderPreviewRequestId || 0) + 1;
    var profileEpoch = that._followEpoch || 0;
    if (that._orderPreviewMemberId !== undefined && that._orderPreviewMemberId !== memberId) {
      that.setData({ orderPreviewList: [], orderErr: false, orderAuthError: false });
    }
    that._orderPreviewMemberId = memberId;
    return req('/api/registration/list', {
      pageNum: 1,
      pageSize: 8,
      owner_type: 3,
      // 2026-10-22 owner:只要还要用的单。status '2' = 已付款未核销(后端 registrationStatus=2 且未完成),
      // is_online=1 = 未结束;退款/过期等再由 buildOrderPreviewList 按订单页同一读模型筛掉。
      status: '2',
      is_online: 1
    }).then(function (res) {
      if (!that._currentProfileRead(read, res)) return;
      if (that._profileAttached === false || requestId !== that._orderPreviewRequestId
          || memberId !== app.getUserID() || profileEpoch !== (that._followEpoch || 0)) return;
      // 原码把失败和「200 但没有订单」都收敛成 list=[] → 渲染「暂无订单」
      if (res.code != '200' || !res.data) {
        that.setData({ orderErr: true, orderAuthError: isAuthFailure(res) });
        return;
      }
      var rows = res.data.rows || [];
      if (!isRecordList(rows)) {
        that.setData({ orderErr: true, orderAuthError: false });
        return;
      }
      var list = buildOrderPreviewList(rows, function (u) { return u && typeof app.getImgUrl === 'function' ? app.getImgUrl(u) : u; }, Date.now(), 8);
      that.setData({ orderErr: false, orderAuthError: false, orderPreviewList: list });
    });
  },

  // UI-7:预览原来只取第一页 8 条,这 8 条全结束时预览就是空的 —— 第二页的进行中项目
  // 用户永远看不到。接口(is_my=1)没有「未结束」过滤参数,所以这里按页取够:只在
  // 「本页零进行中且还有下一页」时继续翻,拿到 8 条进行中或翻完为止(接口口径不变)。
  // MAX_PAGES 是兜底:预览不值得为一个老账号翻几十页,5 页内没有进行中的就如实收起。
  loadProjectPreview: function () {
    const read = this._captureProfileRead(true);
    if (!read) return Promise.resolve();
    var that = this;
    if (that._profileAttached === false) return Promise.resolve();
    var memberId = app.getUserID();
    var requestId = that._projectPreviewRequestId = (that._projectPreviewRequestId || 0) + 1;
    var profileEpoch = that._followEpoch || 0;
    if (that._projectPreviewMemberId !== undefined && that._projectPreviewMemberId !== memberId) {
      that.setData({ projectPreviewList: [], projectErr: false, projectAuthError: false });
    }
    that._projectPreviewMemberId = memberId;
    // M-25(2026-10-27):商家视角的「我的项目」= 作为承接方报名的主题进度(章节承接申请),不是自己发布的路线
    if (that.data.isMerchantView) {
      return req('/api/merchant/chapter-application/mine', {}).then(function (res) {
        if (!that._currentProfileRead(read, res)) return;
        if (that._profileAttached === false || requestId !== that._projectPreviewRequestId
            || memberId !== app.getUserID() || !that.data.isMerchantView) return;
        if (res.code != '200' || !isRecordList(res.data)) {
          that.setData({ projectErr: true, projectAuthError: isAuthFailure(res) });
          return;
        }
        that.setData({ projectErr: false, projectAuthError: false, projectPreviewList: buildUndertakePreview(res.data, 8) });
      });
    }
    var PAGE_SIZE = 8;
    var MAX_PAGES = 5;
    var live = [];
    var fetched = 0;

    function render() {
      var list = live.slice(0, PAGE_SIZE).map(normalizeProjectPreview);
      // 全部结束时收起预览，不拿演示数据顶替。
      that.setData({
        projectErr: false,
        projectAuthError: false,
        projectPreviewList: list
      });
    }

    function fetchPage(pageNum) {
      return req('/api/topic/list', {
        is_my: 1,
        pageNum: pageNum,
        pageSize: PAGE_SIZE
      }).then(function (res) {
        if (!that._currentProfileRead(read, res)) return;
        if (that._profileAttached === false || requestId !== that._projectPreviewRequestId
            || memberId !== app.getUserID() || profileEpoch !== (that._followEpoch || 0)) return;
        // 读取失败保留旧列表,不能当作本人没有项目。
        if (res.code != '200' || !res.data) {
          that.setData({ projectErr: true, projectAuthError: isAuthFailure(res) });
          return;
        }
        var rows = res.data.rows || [];
        if (!isRecordList(rows)) {
          that.setData({ projectErr: true, projectAuthError: false });
          return;
        }
        fetched += 1;
        rows.forEach(function (item) {
          if (live.length >= PAGE_SIZE) return;
          if (isLiveProject(item)) live.push(item);
        });
        var total = Number(res.data.total);
        var hasNextPage = rows.length >= PAGE_SIZE
          && (Number.isFinite(total) ? fetched * PAGE_SIZE < total : true);
        if (live.length >= PAGE_SIZE || !hasNextPage || fetched >= MAX_PAGES) {
          render();
          return;
        }
        return fetchPage(pageNum + 1);
      });
    }

    return fetchPage(1);
  },

  buildDerivedState: function () {
    var join = this.data.joinInfo || {};
    var activeName = '';

    if (join.id > 0) {
      var topic = join.ownerType == 1 ? join.cmsTopic : join.cmsActivity;
      activeName = topic && topic.name ? topic.name : '';
      var actId = join.ownerType == 2 && join.cmsActivity ? join.cmsActivity.id : join.activityId;
      var existing = this.data.themeProgress.slice();
      if (activeName && !existing.some(function (t) { return t.id == actId; })) {
        existing.unshift({
          id: actId,
          name: activeName,
          emoji: pickEmoji(activeName, THEME_EMOJI, 0),
          doneCount: 0,
          total: 0,
          percent: 0,
          completed: false
        });
      }
      this.setData({ themeProgress: existing });
    }

    this.setData({
      primaryCta: this.data.isMerchantView ? '核销' : '开始探索'
    });
  },

  // cy-tabs 切换:组件 change 事件带 { key }
  onTabChange: function (e) {
    var tab = e.detail.key;
    this._pendingCommunityTab = false;
    if (!tab || tab === this.data.activeTab || tab === 'posts' && !communityReadable()) return;
    pageMotion.enter(this, 'pmTab'); this.setData({ activeTab: tab }); // 2026-10-23:切标签内容浮上来
    if (tab === 'posts') {
      this.refreshDirtyPosts();
      if (this._squareListDirty || this._postsDirtyRefreshPromise) return;
    }
    if (tab === 'posts' && this.data.list.length === 0) {
      this.setData({ page_no: 1 });
      this.getCreativesquareList();
    }
  },

  // 积分任务弹窗:静态说明文案升级为动态任务列表(/api/points/result_list,后台积分规则真源)
  onPointsHelp: function () {
    this.setData({ 'pointsSheet.show': true });
    this.openScene('points-tasks');
    this.loadPointsTasks();
  },
  goPointsHistory: function () { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageMember/points-history/index', complete: () => { this._navigating = false; } }); },
  onPointsSheetClose: function () {
    this.closeScene();
  },
  // 出参口径:value=每次得分;pointsNum 被后端复写为「我已获得次数」(见 ApiPointsController.resultList)
  loadPointsTasks: function () {
    const read = this._captureProfileRead(true);
    if (!read) return Promise.resolve();
    var that = this;
    var sheet = that.data.pointsSheet;
    if (sheet.loading) return;
    that.setData({ 'pointsSheet.loading': !sheet.loaded, 'pointsSheet.failed': false });
    req('/api/points/result_list', {}).then(function (res) {
      if (!that._currentProfileRead(read, res)) return;
      // G4:规则整理收进同目录 points-rules.js(纯函数,有单测)。
      //   · 赚分任务与花分规则(eventType=14 解锁提示)都取自后台积分规则配置,客户端不写死分值;
      //     原先这里把 14 直接过滤掉,用户看不到花分口径,现在单列为「积分消耗」。
      //   · 回包不是数组 / 含非对象行 → 按失败处理,不伪装成「暂无积分任务」。
      //   · 积分抵扣现金的汇率是服务端常量,没有只读接口,这里不展示也不编造。
      //     (常量在 CmsRegistrationServiceImpl.POINT_DEDUCT_RATE;规则本身归 Codex B03,本处只读展示)
      // ⚠️ require 写在这里而不是文件顶部:本文件的 UI-GATE 基线按行号登记,顶部加一行会让后面
      //   所有登记项整体漂移;就地 require 由运行时缓存,不重复加载。
      var rules = res.code == '200' ? require('./points-rules.js').splitPointsRules(res.data) : null;
      if (!rules) {
        // 首次加载失败给显式失败态(可点重试);已有列表则保留旧数据不打扰
        that.setData({ 'pointsSheet.loading': false, 'pointsSheet.failed': !that.data.pointsSheet.loaded });
        return;
      }
      that.setData({
        'pointsSheet.tasks': rules.earn,
        'pointsSheet.spendRules': rules.spend,
        'pointsSheet.loading': false,
        'pointsSheet.loaded': true
      });
    });
  },

  onPrimaryCta: function () {
    if (!this.data.isSelf) {
      this.toggleFollow();
      return;
    }
    if (this.data.isMerchantView) {
      this.goScanQR();
      return;
    }
    // 玩家主按钮"开始探索" = 直接进漫游(2026-07-31:和下方"游玩"分组功能重复,已删该分组)
    this.goRoam();
  },

  goSignup: function () {
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageMember/signup/index', complete: () => { this._navigating = false; } });
  },

  toggleFollow: function () {
    var that = this;
    // UI-12(2026-09-18 用户定):公开视角主按钮统一为「关注」,被看者是商家也照常关注,
    // 不再挡 subjectIsMerchant;本人视角不渲染这颗钮,进不到这里。
    if (that._following) return;
    if (that.data.previewSelf && String(app.getUserID() || '') === String(that.data.userId || '')) {
      cyToast('预览中，不能关注自己');
      return;
    }
    if (!that.data.userId) {
      if (app.tips) app.tips('用户不存在');
      return;
    }
    that._following = true;
    // 请求发出前冻结这次操作的目标；期间任何资料读回都不能改变成功回调的方向。
    var intendedFollow = that.data.userInfo && that.data.userInfo.isFollow == 1 ? 0 : 1;
    that._followStateVersion = (that._followStateVersion || 0) + 1;
    var followEpoch = that._followEpoch || 0;
    that.setData({ followSubmitting: true, followReceipt: false });
    req('/api/user/follow/action', { follow_member_id: that.data.userId, follow: intendedFollow }).then(function (res) {
      if (followEpoch !== (that._followEpoch || 0)) return;
      that._following = false;
      if (res.code != '200') {
        that.setData({ followSubmitting: false, followReceipt: false });
        cyToast(res.msg || '操作失败');
        return;
      }
      var nextFollow = intendedFollow;
      // CU-M-36:成功后同屏粉丝数跟着 ±1(原来要退出重进才变),与按钮状态同一时刻更新。
      var fans = Math.max(0, (Number(that.data.userInfo && that.data.userInfo.fansNum) || 0) + (nextFollow ? 1 : -1));
      that.setData({
        'userInfo.isFollow': nextFollow,
        'userInfo.fansNum': fans,
        fansNum: fans,
        primaryCta: nextFollow ? '已关注' : '关注',
        followSubmitting: false,
        followReceipt: nextFollow === 1
      });
      cyToast(nextFollow ? '关注成功' : '已取消关注');
    });
  },

  onStartChat: function () {
    var that = this;
    if (that.data.subjectIsMerchant) return;
    var target = this.data.userId;
    if (!target) {
      if (app.tips) app.tips('用户不存在');
      return;
    }
    if (that._navigating) return; that._navigating = true; community.request({ // 请求在途/跳转中不响应连点
      url: '/api/im/start',
      method: 'POST',
      hideLoading: true,
      data: { target_member_id: target },
      success: function (res) {
        if (res && res.code == 200 && res.data && res.data.conversationId) {
          var ui = that.data.userInfo || {};
          wx.navigateTo({
            url: '/subpackageB/pages/im/chat/index?conversationId=' + res.data.conversationId +
              '&name=' + encodeURIComponent(ui.nickname || '') +
              '&avatar=' + encodeURIComponent(ui.avatar || '') + '&type=1', complete: function () { that._navigating = false; }
          });
          return;
        }
        that._navigating = false; if (app.tips) app.tips((res && res.msg) || '无法发起会话');
      },
      fail: function () {
        that._navigating = false; if (app.tips) app.tips('网络异常，请重试');
      }, successStatusAbnormal: function (res) { that._navigating = false; if (app.tips) app.tips((res && res.msg) || '无法发起会话'); }
    });
  },

  goScanQR: function () {
    var that = this;
    const login = captureLogin(app, wx), scanEpoch = this._verificationGeneration || 0;
    const scanCurrent = () => loginCurrent(app, wx, login) && scanEpoch === (that._verificationGeneration || 0);
    wx.scanCode({
      success: function (res) {
        if (!scanCurrent()) return;
        var scan = resolveVerificationScan(res.result);
        if (scan.kind === 'invalid') {
          cyToast(scan.message);
          return;
        }
        that._submitVerification(scan.url + ':' + scan.code, scan.loadingTitle, {
          url: scan.url,
          data: scan.data,
          method: 'POST',
          // [P0-2] 本页自己接管错误提示,压掉 request-client 对 code!=200 的自动 toast:
          // 选章那条返的就是 error(核销确实没发生),不压会在弹出选章面板前先闪一条失败 toast。
          // 真错误照旧由下面的 else 分支弹,信息不丢。
        }, function (r) {
            var d = r.data || {};
            // ★ [P0-2] 判据是**载荷**不是 code —— 后端对「需要选章」永久返 error,
            //   因为选章没完成 = 核销没发生。这里若改判 code,会把选章当成普通失败弹掉,
            //   商家就永远核销不了(而这正是修复前的形态:只判 code==200 ⇒ 弹「验票成功」)。
            if (d.needChapterChoice) {
              that.openChapterSheet(scan.code, d);
              return;
            }
            if (d.needStationChoice) {
              that.openStationSheet(scan.code, d);
              return;
            }
            if (r.code == '200') {
              // P3-2:重复核销不是成功新动作,口径与商家工作台一致(重复核销,非成功图标)。
              var verdict = resolveVerificationResult(r, scan.successTitle || '核销');
              if (verdict.state === 'duplicate') {
                cyToast(verdict.title + '：' + verdict.message, { icon: 'none' });
                return;
              }
              cyToast.success(completionReceiptMessage(r, scan.successTitle));
            } else {
              cyToast(r.msg || '核销失败');
            }
        });
      }
    });
  },

  /**
   * [P0-2] 交集 ≥2 ⇒ 让商家自己选章。
   * 一商家在同一路线承接多章是**常态**(uk_offer_merchant_chapter 只约束一商家一章一行,
   * 不阻止一商家多章)⇒ 这不是边界情况。后端绝不默默取第一个:选错章不可逆。
   */
  openChapterSheet: function (code, d) {
    // chapters 带名字(后端 describeChapters)。回落 chapterIds 是为了「后端还没部署到位」的
    // 那段窗口:宁可显示「章节 101」也不能给个空面板 —— 空面板商家会以为卡死。
    var items = (Array.isArray(d.chapters) && d.chapters.length)
      ? d.chapters
      : (Array.isArray(d.chapterIds) ? d.chapterIds : []).map(function (id) { return { id: id, name: '章节 ' + id }; });
    if (!items.length) {
      cyToast('没有可核销的章节');
      return;
    }
    this.setData({ chapterSheet: { show: true, code: code, items: items } });
    this.openScene('member-chapter-picker');
  },

  onChapterSheetClose: function () {
    this.closeScene();
  },

  onChapterPick: function (e) {
    var id = e.currentTarget.dataset.id;
    var code = this.data.chapterSheet.code;
    this.closeScene();
    this._submitVerification('/chapter:' + code + ':' + id, '核销中...', {
      url: '/api/registration/scan_qr_code_chapter',
      data: { code: code, chapterId: id },
      method: 'POST',
    }, function (r) {
        var verdict = resolveVerificationResult(r, '核销');
        if (verdict.state === 'duplicate') { cyToast(verdict.title + '：' + verdict.message, { icon: 'none' }); return; }
        var ok = r.code == '200';
        if (ok) motion.haptic({ type: 'medium', reducedMotion: readReducedMotion() });
        cyToast(ok ? completionReceiptMessage(r, '核销成功') : (r.msg || '核销失败'), { icon: ok ? 'success' : 'none' });
    });
  },

  openStationSheet: function (code, d) {
    var items = Array.isArray(d.stations) ? d.stations : [];
    if (!items.length) {
      cyToast('没有可核销的节点');
      return;
    }
    this.setData({ stationSheet: { show: true, code: code, items: items } });
    this.openScene('member-station-picker');
  },

  onStationSheetClose: function () {
    this.closeScene();
  },

  onStationPick: function (e) {
    var registrationMerchantId = e.currentTarget.dataset.registrationMerchantId;
    var code = this.data.stationSheet.code;
    this.closeScene();
    this._submitVerification('/station:' + code + ':' + registrationMerchantId, '核销中...', {
      url: '/api/registration/scan_qr_code_station',
      data: { code: code, registrationMerchantId: registrationMerchantId },
      method: 'POST',
    }, function (r) {
        var ok = r.code == '200';
        if (ok) motion.haptic({ type: 'medium', reducedMotion: readReducedMotion() });
        cyToast(ok ? completionReceiptMessage(r, '核销成功') : (r.msg || '核销失败'), { icon: ok ? 'success' : 'none' });
    });
  },

  // AI 推荐「开始探索」保留探索语义:有进行中的场次→游玩,否则回探索首页
  onExplore: function () {
    // FE-19 D-4:有进行中场次 → 去票夹开始游玩;否则回探索首页(play 统一从票夹进入)
    var join = this.data.joinInfo || {};
    if (join.id > 0) {
      wx.navigateTo({ url: '/subpackageMember/signup/index' });
      return;
    }
    wx.switchTab({ url: '/pages/index/index' });
  },

  goRoam: function () { wx.switchTab({ url: '/pages/roam/index' }); },

  onThemeTap: function (e) {
    // FE-19 D-4:游玩统一从票夹进入
    wx.navigateTo({ url: '/subpackageMember/signup/index' });
  },

  onAiStart: function () { this.onExplore(); },

  onCompare: function () {
    wx.navigateTo({ url: '/subpackageP3/pages/growthcenter/index/index' });
  },

  openScene: function (id, params) {
    var next = getScene(id, params || {});
    if (id === 'share-invite') next.theme = this.data.isMerchantView ? 'merchant' : 'player';
    if (exitDecision(this.data.sceneStack, 'close') === 'confirm') {
      this.setData({ sceneConfirm: { show: true, action: 'replace', pending: next } });
      return false;
    }
    var sceneStack = pushScene(this.data.sceneStack, next);
    this.setData({ sceneStack: sceneStack, sceneCurrent: currentScene(sceneStack), sceneConfirm: { show: false, action: null, pending: null } });
    return true;
  },
  backScene: function () {
    if (exitDecision(this.data.sceneStack, 'back') === 'confirm') {
      this.setData({ sceneConfirm: { show: true, action: 'back', pending: null } });
      return;
    }
    var sceneStack = popScene(this.data.sceneStack);
    this.setData({ sceneStack: sceneStack, sceneCurrent: currentScene(sceneStack), sceneConfirm: { show: false, action: null, pending: null } });
  },
  closeScene: function () {
    this.setData({ sceneStack: [], sceneCurrent: null, sceneConfirm: { show: false, action: null, pending: null } });
  },
  requestSceneClose: function () {
    if (exitDecision(this.data.sceneStack, 'close') === 'confirm') {
      this.setData({ sceneConfirm: { show: true, action: 'close', pending: null } });
      return;
    }
    this.closeScene();
  },
  confirmSceneDiscard: function () {
    var sceneStack;
    if (this.data.sceneConfirm.action === 'back') {
      sceneStack = popScene(this.data.sceneStack);
      this.setData({ sceneStack: sceneStack, sceneCurrent: currentScene(sceneStack), sceneConfirm: { show: false, action: null, pending: null } });
      return;
    }
    if (this.data.sceneConfirm.action === 'replace' && this.data.sceneConfirm.pending) {
      sceneStack = pushScene(this.data.sceneStack, this.data.sceneConfirm.pending);
      this.setData({ sceneStack: sceneStack, sceneCurrent: currentScene(sceneStack), sceneConfirm: { show: false, action: null, pending: null } });
      return;
    }
    this.closeScene();
  },
  cancelSceneDiscard: function () {
    this.setData({ sceneConfirm: { show: false, action: null, pending: null } });
  },
  setSceneDirty: function (dirty) {
    if (!this.data.sceneStack.length) return;
    var sceneStack = this.data.sceneStack.slice();
    sceneStack[sceneStack.length - 1] = Object.assign({}, sceneStack[sceneStack.length - 1], { dirty: dirty === true });
    this.setData({ sceneStack: sceneStack, sceneCurrent: currentScene(sceneStack) });
  },
  onSceneDirtyChange: function (event) {
    this.setSceneDirty(event.detail && event.detail.dirty);
  },
  openChildScene: function (event) {
    var detail = event.detail || {};
    if (detail.id) this.openScene(detail.id, detail.params || {});
  },
  submitSceneForm: function () {
    var content = this.selectComponent('#sceneRouteContent');
    if (content && typeof content.submitForm === 'function') content.submitForm();
  },
  openStampCamera: function () {
    this.closeScene();
    wx.navigateTo({ url: '/subpackageP3/pages/stamp-camera/index/index' });
  },
  blockSceneTouch: function () {},

  openMerchantNpcChat: function () {
    // 再判一次开关:data 里的值是组件创建时的快照,而 features 是启动时异步拉的 ——
    // 组件先于拉取完成创建时快照是 false,拉完不会自动回填。这里读实时值。
    if (!(getApp().globalData.features || {}).merchantNpcChat) return;
    this.setData({ merchantNpcChatShow: true });
  },

  closeMerchantNpcChat: function () {
    this.setData({ merchantNpcChatShow: false });
  },

  openMoreMenu: function () { this.openScene('member-more-menu'); },
  closeMoreMenu: function () { this.closeScene(); },

  onReportMember: function () {
    this.closeMoreMenu();
    this.setData({ reportShow: true, reportTargetType: 'MEMBER', reportTargetId: String(this.data.userId || ''), reportTitle: '举报这位用户' });
  },

  onReportMerchant: function () {
    var merchantId = this.data.subjectMerchant && this.data.subjectMerchant.id;
    this.closeMoreMenu();
    if (merchantId == null || merchantId === '') return;
    this.setData({ reportShow: true, reportTargetType: 'MERCHANT', reportTargetId: String(merchantId), reportTitle: '举报这家商家' });
  },

  onReportClose: function () {
    this.setData({ reportShow: false });
  },

  // BL-67:商家公开主页「信息纠错」(问题上报,纯文字),走纠错半屏
  onMerchantCorrection: function () {
    var merchantId = this.data.subjectMerchant && this.data.subjectMerchant.id;
    this.closeMoreMenu();
    if (merchantId == null || merchantId === '') return;
    this.setData({ correctionShow: true, correctionMerchantId: String(merchantId) });
  },

  onCorrectionClose: function () {
    this.setData({ correctionShow: false });
  },

  onShareProfile: function () {
    this.closeMoreMenu();
    cyToast('点击右上角分享主页');
  },


  onChangeHeroBg: function () {
    var that = this;
    this.closeMoreMenu();
    app.chooseImage(function (urls) {
      if (!urls || !urls.length) return;
      var url = urls[0];
      wx.setStorageSync('member_hero_bg', url);
      that.setData({ heroImg: url });
      cyToast.success('背景已更新');
    }, 1, { crop: true, cropScale: '16:9' });
  },

  onMoreSetting: function () {
    this.closeMoreMenu();
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/shezhi/shezhi', complete: () => { this._navigating = false; } });
  },

  // CU-M-43(2026-09-23 用户裁决 B):onBlock 随「屏蔽」入口一起摘掉 —— 原来只弹一句
  //「已屏蔽」,没有请求也没有持久化,重进无状态变化。接上写路径前不带这条假成功。

  markSquareListDirty: function () {
    this._squareListDirty = true;
    this._squareListDirtyVersion = (this._squareListDirtyVersion || 0) + 1;
  },

  refreshDirtyPosts: function () {
    const feedVersion = app.globalData && app.globalData.squareListDirtyVersion || 0;
    if (feedVersion > (this._communityFeedVersion || 0)) {
      this._communityFeedVersion = feedVersion;
      this.markSquareListDirty();
    }
    if (!this._squareListDirty || this.data.activeTab !== 'posts' || this._postsDirtyRefreshPromise) return;
    const version = this._squareListDirtyVersion;
    this.setData({ page_no: 1 });
    const pending = this.getCreativesquareList().then((success) => {
      if (success && version === this._squareListDirtyVersion) this._squareListDirty = false;
      return success;
    });
    this._postsDirtyRefreshPromise = pending;
    const complete = () => {
      if (this._postsDirtyRefreshPromise === pending) this._postsDirtyRefreshPromise = null;
    };
    pending.then(complete, complete);
    return pending;
  },

  getCreativesquareList: function () {
    const read = this._captureProfileRead(false);
    if (!read) return Promise.resolve(false);
    var that = this;
    if (!communityReadable()) return Promise.resolve(false);
    const pageNum = that.data.page_no;
    if (pageNum === 1) that._postsRequestGeneration = (that._postsRequestGeneration || 0) + 1;
    const generation = that._postsRequestGeneration;
    var data = {
      is_my: that.data.isSelf ? 1 : 0,
      pageNum: pageNum,
      pageSize: app.getPageSize()
    };
    if (!that.data.isSelf) data.user_id = that.data.userId;
    return req('/api/creativesquare/list', data).then(function (res) {
      if (!that._currentProfileRead(read, res)) return false;
      if (generation !== that._postsRequestGeneration) return false;
      // 原码把失败显式写成 nodata=true → 渲染「还没有动态,发布一次城市发现吧」
      if (res.code != '200') {
        that.setData({ hasMore: false, feedErr: true, feedAuthError: isAuthFailure(res) });
        return false;
      }
      var result = isRecord(res.data) ? res.data : {};
      var newlist = result.rows || [];
      if (!isRecordList(newlist)) {
        that.setData({ hasMore: false, feedErr: true, feedAuthError: false });
        return false;
      }
      newlist.forEach(function (row) {
        row.source = 'community'; // 帖文卡:社区帖文不出转发,点赞走 creativesquare;作者头像/昵称缺时用主页头的(V-15 同口径)
        if (!row.memberAvatar) row.memberAvatar = that.data._avatarSrc;
        if (!row.memberNickname) row.memberNickname = that.data.userInfo && that.data.userInfo.nickname;
        if (row.pics) row.picList = String(row.pics).split(';').filter(function (pic) { return pic; });
        if (row.createTime) row.formattedCreateTime = that.formatTimeDifference(row.createTime);
      });
      // P0-4:翻页只按数组路径回传新增行(旧写法整表 concat 全量跨序列化线重发)。
      // 就地内联不引 utils/list-append:本组件被 unexpected-require 白名单沙箱单测装载
      // (tests/unit/merchant-operator-ui-contract),多一个跨文件 require 会连坐测试装置。
      if (pageNum !== 1) {
        const oldLen = that.data.list.length;
        const patch = {};
        for (let i = 0; i < newlist.length; i++) patch['list[' + (oldLen + i) + ']'] = newlist[i];
        that.setData(Object.assign({
          feedErr: false,
          feedAuthError: false,
          hasMore: result.hasMore === true,
          nodata: oldLen + newlist.length < 1
        }, patch));
        return true;
      }
      that.setData({
        feedErr: false,
        feedAuthError: false,
        list: newlist,
        hasMore: result.hasMore === true,
        nodata: newlist.length < 1
      });
      return true;
    });
  },

  // cy-error 的重试:各自只重拉自己那条链路,不整页刷新(别的区块的数据没坏,不该被连坐)
  reloadOrders: function () { this.loadOrderPreview(); },
  reloadProjects: function () { this.loadProjectPreview(); },
  reloadProfileMetrics: function () {
    this.resetProfileMetricState();
    return Promise.all([
      this.loadUserData(),
      this.loadGrowthBundle(),
      this.loadThemes(),
      this.loadPointsStat()
    ]);
  },

  // C7:我的项目入口待处理角标(未通过+审核中;/api/publish/home projectSummary 无草稿档)
  loadProjectPending: function () {
    const read = this._captureProfileRead(true);
    if (!read) return Promise.resolve();
    var that = this;
    if (that._profileAttached === false) return Promise.resolve();
    var memberId = app.getUserID();
    var requestId = that._projectPendingRequestId = (that._projectPendingRequestId || 0) + 1;
    var profileEpoch = that._followEpoch || 0;
    if (that._projectPendingMemberId !== undefined && that._projectPendingMemberId !== memberId) that.setData({ projectPendingCount: 0 });
    that._projectPendingMemberId = memberId;
    return req('/api/publish/home', {}).then(function (res) {
      if (!that._currentProfileRead(read, res)) return;
      if (that._profileAttached === false || requestId !== that._projectPendingRequestId
          || memberId !== app.getUserID() || profileEpoch !== (that._followEpoch || 0)) return;
      if (res.code != '200' || !res.data || !res.data.projectSummary) return;
      var s = res.data.projectSummary;
      that.setData({ projectPendingCount: (s.pending || 0) + (s.rejected || 0) });
    });
  },
  reloadMedals: function () { this.loadMedals(); },
  reloadFeed: function () {
    this.setData({ page_no: 1 });
    this.getCreativesquareList();
  },

  onFeedReachBottom: function () {
    if (this._squareListDirty || this._postsDirtyRefreshPromise) return;
    if (!this.data.hasMore || this.data.activeTab !== 'posts') return;
    this.setData({ page_no: this.data.page_no + 1 });
    this.getCreativesquareList();
  },

  lickClick: function (e) {
    var that = this;
    var index = e.currentTarget.dataset.index;
    var type = e.currentTarget.dataset.type;
    var item = that.data.list[index];
    if (!item) return;
    req('/api/creativesquare/like', { id: item.id, type: type }).then(function (res) {
      if (res.code != '200') {
        cyToast(app.getRequestErrorMessage(res, '操作失败'));
        return;
      }
      var pos = that.data.list.findIndex(function (row) { return row && String(row.id) === String(item.id); });
      if (pos < 0) return;
      var current = Object.assign({}, that.data.list[pos], { isLiked: res.data.isLiked, likeCount: res.data.likeCount, version: res.data.version });
      that.setData({ ['list[' + pos + ']']: current });
    });
  },

  favoriteClick: function (e) {
    var that = this;
    var index = e.currentTarget.dataset.index;
    var item = that.data.list[index];
    if (!item) return;
    var requestKey = String(item.id);
    that._favoriteRequests = that._favoriteRequests || Object.create(null);
    if (that._favoriteRequests[requestKey]) return;
    that._favoriteRequests[requestKey] = true;
    var next = item.isBookmarked == 1 ? 0 : 1;
    req('/api/creativesquare/bookmark', {
      id: item.id,
      bookmark: next,
      request_id: 'bookmark-' + item.id + '-' + next + '-' + Date.now().toString(36)
    }).then(function (res) {
      delete that._favoriteRequests[requestKey];
      if (res.code != '200') {
        cyToast(app.getRequestErrorMessage(res, '操作失败'));
        return;
      }
      var pos = that.data.list.findIndex(function (row) { return row && String(row.id) === String(item.id); });
      if (pos < 0) return;
      that.setData({ ['list[' + pos + ']']: Object.assign({}, that.data.list[pos], { isBookmarked: res.data.isBookmarked }) });
    });
  },

  goBack: function () {
    var pages = getCurrentPages();
    if (pages.length > 1) wx.navigateBack({ delta: 1 });
    else wx.switchTab({ url: '/pages/index/index' });
  },
  onAvatarTap: function () {
    if (this.data.isSelf) this.goUserInfo();
  },
  goSetting: function () { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/shezhi/shezhi', complete: () => { this._navigating = false; } }); },
  // 直达个人资料真页。原来跳的是 `shezhi?scene=settings-profile` —— 那会让「设置」一进去就
  // 盖一层弹窗(2026-08-08 用户提「点到设置不要弹这个弹窗」),而弹窗里是 scene-route-content
  // 的通用表单,只有昵称+邮箱,pages/gerenziliao 那 390 行的头像/介绍/标签全没了。
  // 2026-08-20 用户定:商家视角个人资料与店铺装修合并,编辑头像/资料一律进店铺装修。
  goUserInfo: function () {
    if (this.data.isMerchantView) { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/merchant/decor/index', complete: () => { this._navigating = false; } }); return; }
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/gerenziliao/gerenziliao', complete: () => { this._navigating = false; } });
  },
  // CU-M-28:这一块显示的是会员本人的 introduction,编辑必须进个人资料;
  // goUserInfo 在商家视角会去品牌中心,那里没有「个人介绍」字段。
  goEditIntro: function () { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/gerenziliao/gerenziliao?field=introduction', complete: () => { this._navigating = false; } }); },
  // FE-14 商家店铺资料收编进「我的」更多菜单(原 shanghuziliao 页保留即重定向)
  goShop: function () { this.closeScene(); if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/merchant/decor/index', complete: () => { this._navigating = false; } }); },
  // 访客看商家主页的底部「发起合作」:与 club/detail onInviteMerchant 同链路(coop/invite type=0,
  // toId=memberId)。userId 在商家主页语境下就是对方 memberId。
  onCoopInvite() {
    if (!this.data.canInviteCoop || this._coopPublisherMemberId !== String(app.getUserID() || '')) return;
    if (!this.data.userId) { cyToast('该商家暂不可对接'); return; }
    this.setData({ coopInviteShow: true });
  },
  closeCoopInvite() { this.setData({ coopInviteShow: false }); },
  onCoopInviteSent(e) {
    this.closeCoopInvite();
    returnToCoopOrigin(e.detail.topicId);
  },
  // M-25:商家视角没有订单页可看,任何进订单的入口都改去「我的项目」
  goOrder: function () { if (this.data.isMerchantView) { this.goProject(); return; } if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageMember/order/order', complete: () => { this._navigating = false; } }); },
  goProject: function () { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageA/pages/myproject/index' + (this.data.isMerchantView ? '?scope=MERCHANT' : ''), complete: () => { this._navigating = false; } }); },
  onOrderPreviewTap: function (e) {
    if (this.data.isMerchantView) { this.goProject(); return; }
    var id = e.currentTarget.dataset.id;
    // 订单详情是「订单」二级页的子场景;一级「我的」先进入父页,由父页托管返回关系。
    if (id) { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageMember/order/order?detailId=' + encodeURIComponent(String(id)), complete: () => { this._navigating = false; } }); }
    else this.goOrder();
  },
  onProjectPreviewTap: function (e) {
    // projectPreviewList 源为 /api/topic/list?is_my=1(仅 topic),故单卡直达主题详情;无 id 兜底进 myproject
    var id = e.currentTarget.dataset.id;
    if (id) { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/topic/index/index?id=' + id, complete: () => { this._navigating = false; } }); }
    else this.goProject();
  },
  goMyJoin: function () { wx.navigateTo({ url: '/subpackageMember/mycanyu/mycanyu' }); },
  goGrowthCenter: function () { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageP3/pages/growthcenter/index/index', complete: () => { this._navigating = false; } }); },
  goWall: function () { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageP3/pages/badge-wall/index/index', complete: () => { this._navigating = false; } }); },
  goAlbum: function () { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageP3/pages/stamp-album/index/index', complete: () => { this._navigating = false; } }); },
  goObjectCards: function () { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageP3/pages/object-cards/index/index', complete: () => { this._navigating = false; } }); },
  // 当前权益里可点的行(收益提现→提现页;承接原 role/center 对赚钱角色 role!==player 的提现入口,按 withdrawable 权限门控)
  onBenefitTap: function (e) {
    var ds = e.currentTarget.dataset;
    if (ds.route && ds.unlocked) { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: ds.route, complete: () => { this._navigating = false; } }); }
  },
  goInvite: function () { this.openScene('share-invite'); },
  goEarnings: function () { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageA/pages/assetcenter/earnings/index', complete: () => { this._navigating = false; } }); },
  goMerchantTeam: function () { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/merchant/team/index', complete: () => { this._navigating = false; } }); },
  goClubWorkbench: function () { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/club/detail/index?owner=1&tab=manage', complete: () => { this._navigating = false; } }); },
  goCoupon: function () { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageMember/coupon-wallet/index', complete: () => { this._navigating = false; } }); },
  // 帖文第三轮:帖文卡的点正文 / 点评论进详情,点赞走原来的 creativesquare 点赞(type=1);数据下标来自卡片事件
  onPostCardDetail: function (e) {
    this.jumpDetail({ currentTarget: { dataset: { index: e.detail && e.detail.index } } });
  },
  onPostCardLike: function (e) {
    this.lickClick({ currentTarget: { dataset: { index: e.detail && e.detail.index, type: 1 } } });
  },

  jumpDetail: function (e) {
    var activity = this.data.list[e.currentTarget.dataset.index];
    if (activity) { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/square/detail/index?id=' + activity.id, complete: () => { this._navigating = false; } }); }
  },

  previewImage: function (e) {
    var picList = e.currentTarget.dataset.piclist;
    var index = e.currentTarget.dataset.index;
    mediaPreview.open(picList, picList && picList[index]);
  },

  previewMerchantGallery: function (e) {
    var src = e.currentTarget.dataset.src;
    if (src) mediaPreview.open(this.data.merchantGallery || [src], src);
  },

  goMerchantLocation: function () {
    var m = this.data.subjectMerchant || {};
    // 现算不用 merchantHasGeo:那个只喂样式,导航要的是坐标本身,两者从同一个
    // 函数出,不存在「样式说可点、这里取不到值」的错位。
    var geo = merchantCoordinate(m);
    if (!geo) return;
    // 左边是 wx.openLocation 的入参名,右边是实体字段换算出来的值 —— 别把两层混了
    wx.openLocation({
      latitude: geo.latitude,
      longitude: geo.longitude,
      name: m.name || '商家',
      address: m.address || '',
      scale: 18
    });
  },

  // 招牌主推卡落点(4-12):活动进活动详情,券进券夹 —— 与据点页
  // (scene-roam-poi-detail 的 play-activity-detail / game-coupon-wallet)同一目标。
  // 卡片不可点(actionable=false)时不动,不给假跳转。
  goFeatured: function () {
    var f = this.data.featured || {};
    if (f.featuredType === 1 && f.featuredId) {
      if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/activity/detail/index?id=' + f.featuredId, complete: () => { this._navigating = false; } });
      return;
    }
    if (f.featuredType === 2) { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageMember/coupon-wallet/index', complete: () => { this._navigating = false; } }); }
  },

  goMerchantReviews: function () {
    var merchantRowId = Number(this.data.subjectMerchant && this.data.subjectMerchant.id);
    if (!Number.isFinite(merchantRowId) || merchantRowId <= 0) {
      cyToast('该商家评价暂不可查看');
      return;
    }
    if (this._navigating) return; this._navigating = true; wx.navigateTo({
      url: '/pages/merchant/reviews/index?merchantRowId=' + encodeURIComponent(merchantRowId), complete: () => { this._navigating = false; }
    });
  },

  previewWork: function (e) {
    var url = e.currentTarget.dataset.url;
    mediaPreview.open(this.data.casePicsList, url);
  },

  formatTimeDifference: function (createTimeStr) {
    if (!createTimeStr) return '';
    var diff = Date.now() - toTimestamp(createTimeStr);
    var days = Math.floor(diff / 86400000);
    var hours = Math.floor((diff % 86400000) / 3600000);
    var minutes = Math.floor((diff % 3600000) / 60000);
    if (days > 0) return days + '天前';
    if (hours > 0) return hours + '时' + minutes + '分前';
    if (minutes > 0) return minutes + '分前';
    return '刚刚';
  },

  getSharePayload: function (res) {
    if (res.from === 'button' && res.target && res.target.dataset && res.target.dataset.shareKind === 'invite') {
      return {
        title: '一起探索城市里的新玩法',
        path: '/pages/index/index?inviter=' + app.getUserID()
      };
    }
    if (res.from === 'button') {
      return { title: '城瘾 · 城市探索', path: '/pages/square/detail/index?id=' + res.target.dataset.id };
    }
    if (!this.data.isSelf) {
      return {
        title: (this.data.userInfo.nickname || '探索者') + ' 的城市主页',
        path: '/pages/userinfo/userinfo?userId=' + this.data.userId
      };
    }
    return {
      title: (this.data.userInfo.nickname || '我') + ' 的城市身份卡',
      path: '/pages/member/index/index'
    };
  }
  }
});
