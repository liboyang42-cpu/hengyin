const sendRecovery = require('../../../../utils/im-send-recovery.js');
const { toTimestamp, chinaDateKey, chinaParts } = require('../../../../utils/datetime');
const { isRecordList, isBizOk } = require('../../../../utils/response-shape.js');
const { decodeDisplayText } = require('../../../../utils/display-text.js');
const { showPermissionGuide } = require('../../../../utils/location/location-manager.js');
const { auditDetailUrl, shapeAuditDetail, shouldLoadAuditDetail, isRetryableAuditError } = require('./audit-detail.js');
const { reportNoticeRows, makeReadRequestId } = require('../list/report-notices.js');
const systemCard = require('./system-card-target.js');
const roleGuard = require('../../../../utils/roleGuard.js');
const merchantTheme = require('../../../../utils/merchant-theme.js');
const app = getApp();
// 距底部不超过这个距离(px)视为「在看最新消息」,轮询到新消息时跟随滚动(U-142)
const NEAR_BOTTOM_PX = 120;

// 服务端可见顺序含微秒：不要转 JS Date 丢精度，也不要以客户端时钟推测审批时间。
function messageOrder(message) {
  const id = Number(message && message.id);
  const stamp = message && message.visibleOrderAt;
  return typeof stamp === 'string' && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{6}$/.test(stamp)
    ? { stamp, id } : id;
}
function compareOrder(a, b) {
  if (a && b && typeof a === 'object' && typeof b === 'object') {
    return a.stamp < b.stamp ? -1 : a.stamp > b.stamp ? 1 : a.id - b.id;
  }
  return (typeof a === 'object' ? a.id : a) - (typeof b === 'object' ? b.id : b);
}
function compareMessages(a, b) {
  const aId = Number(a.id), bId = Number(b.id);
  if (!Number.isSafeInteger(aId) || aId <= 0) return !Number.isSafeInteger(bId) || bId <= 0 ? 0 : 1;
  if (!Number.isSafeInteger(bId) || bId <= 0) return -1;
  return compareOrder(messageOrder(a), messageOrder(b));
}

Page({
  _closedReturnPath: '/subpackageB/pages/im/list/index',
  _blockedByMe: false,
  data: {
    // 城瘾小助手(系统会话)顶部的「我的举报」入口:有举报进度通知才出现,读不到也照常给入口
    reportEntry: false,
    reportUnread: 0,
    plusSheetShow: false,
    plusSheetItems: ['图片', '位置', '路线'],
    moreSheetShow: false,
    // CU-C-120:系统通知(type=2)没有可交流的对象,举报/拉黑的目标也是空的 ⇒ 面板只留真能做的隐藏会话。
    // 初始值按可回复的私信给,onLoad 里再按会话类型收窄(见 _moreItemsFor)。
    moreSheetItems: ['举报这条消息', '举报对方', '拉黑', '隐藏会话'],
    reportShow: false,
    reportPeerId: '',
    // 只读会话:能读不能写。判据只有「对方是不是一个可交流的人」,不是 loadState。
    readOnly: false,
    readOnlyTip: '系统通知不支持回复，有新动态会出现在这里',
    // M-01 / G-10:商家视角整页浅色;商家的系统通知会话标题进导航栏、去掉「更多」、通知改整宽卡片。
    isMerchantView: false,
    sysNotice: false,
    statusBarHeight: 20,
    navBarHeight: 44,
    headerH: 64,
    heroH: 0,
    inputH: 60,
    safeBottom: 0,
    conversationId: 0,
    name: '',
    // hero 上的会话身份:name 为空(坏链接/未传参)时按会话类型兜底,不留无名头像(截图 060)。
    // 与 name 分开存:气泡上方的发送人名仍必须是真名,不能被兜底串污染。
    heroName: '',
    avatar: '',
    // loading | ready | error | missing | closed —— 「还没有消息」只属于 ready。
    // 原来只有 success 分支:网络失败、会话不存在、真的没消息,三种状态全渲染成空对话。
    loadState: 'loading',
    loadErrorText: '',
    type: 1,
    myId: 0,
    myAvatar: '',
    msgs: [],
    reviewResult: null,
    reviewDetail: null,
    text: '',
    sending: false,
    hasMore: false,
    cursor: 0,
    loadingMore: false,
    loadMoreError: '',
    toView: '',
    disabled: false,
    disabledText: '',
    // 非 ready 时输入栏为什么点不动,按真实原因给一句话。做成静态映射(而不是每个 setData
    // 站点各写一次)是为了不漏:loadState 的落点很多,漏一处就又变成"没有原因的死输入栏"。
    sendBlockedMap: {
      loading: '消息加载中…',
      missing: '链接已失效，请返回重新进入',
      closed: '组局已结束，不能继续聊天',
      error: '消息加载失败，点上方「重试」再试',
    },
    topicCache: {},
    routePickerOpen: false,
    routeKeyword: '',
    routeList: [],
    routeLoading: false,
    routeError: '',
  },

  // 页内隐私弹窗:没有它 app.js 会回退到 navigateTo(/pages/privacy/index),

  // 把本页整个盖住 —— 审计里那批「route 回读为隐私页、节点数 0」就是这么来的。

  showPrivacyGate() {

    this.setData({ privacyGateShow: true });

  },

  onPrivacyGateSettled() {

    this.setData({ privacyGateShow: false });

  },


  onLoad(options) {
    this._reviewDetailEpoch = (this._reviewDetailEpoch || 0) + 1;
    this._cardNavEpoch = (this._cardNavEpoch || 0) + 1;
    this._reviewMessageId = null; this._reviewRecordKey = null; this._navigating = false;
    this.setData({ reviewResult: null, reviewDetail: null });
    this._disposed = false;
    this._hidden = false;
    this._pendingClear = null;
    this._pendingBlock = null;
    this._reportInFlight = null;
    this._pendingReportTip = null;
    this._pendingClearReturn = null;
    this._pollWatermark = null;
    this._pollInFlight = false;
    this._pendingReadConversationId = 0;
    this._pendingReadMemberId = 0;
    const sb = (app.globalData && app.globalData.statusBarHeight) || 20;
    const nav = (app.globalData && app.globalData.navBarHeight) || 44;
    let safe = 0;
    let winW = 375;
    try {
      const w = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      safe = (w.safeArea ? (w.screenHeight - w.safeArea.bottom) : 0);
      if (w.windowWidth) winW = w.windowWidth;
    } catch (e) {}
    const type = parseInt(options.type) || 1;
    const conversationId = parseInt(options.conversationId) || 0;
    const name = decodeURIComponent(options.name || '');
    // hero 区(顶栏下方居中会话名,不再放头像)高度:先按内容粗估作首屏占位,
    // onReady 里再用 boundingClientRect 实测覆正——避免手工凑 rpx 数字跟 wxss 实际布局对不齐。
    const heroGuessRpx = 96;
    // CU-C-120:系统通知是「平台→你」的单向推送,后端 counterparty.id 恒为 0。
    // 原来它和私信共用同一套输入栏/举报/拉黑:能打字、能点发送,发出去才红一个气泡,
    // 拉黑/举报的目标又不是任何人。可供性必须在进入前就收窄,不是等后端拒绝。
    const readOnly = type === 2;
    const isMerchantView = roleGuard.isMerchantView();
    const sysNotice = readOnly && isMerchantView;
    if (isMerchantView) merchantTheme.merchantPageShow();
    this.setData({
      isMerchantView: isMerchantView,
      sysNotice: sysNotice,
      statusBarHeight: sb,
      navBarHeight: nav,
      headerH: sb + nav,
      heroH: sysNotice ? 0 : Math.round(heroGuessRpx * winW / 750),
      safeBottom: safe,
      inputH: 60 + safe / 2,
      conversationId: conversationId,
      name: name,
      heroName: this._heroName(name, type),
      avatar: decodeURIComponent(options.avatar || ''),
      type: type,
      readOnly: readOnly,
      moreSheetItems: readOnly ? ['隐藏会话'] : ['举报这条消息', '举报对方', '拉黑', '隐藏会话'],
      myId: app.getUserID(),
      myAvatar: app.getAvatar(),
    });
    // 坏链接(没有 conversationId)不是「空对话」:不打后端,也不给可用输入栏。
    if (!conversationId) { this.setData({ loadState: 'missing' }); return; }
    /* CU-M-125:系统通知(type=2,后端 ImConversation.TYPE_SYSTEM)没有收件人,走查实测发出去
       只生成一条失败气泡、重试仍失败。这一页此前只按 loadState 渲染输入区,等于给了一条
       必然失败的私信输入栏 ⇒ 按只读渲染并说明原因。
       注:后端 ImServiceImpl.send 只有成员闸与群闸,没有按会话类型的只读闸;
       服务端要不要一并挡写入是后端的事,这里修的是用户看得见的那一半。
       type=3 官方客服按现状仍可写,不在这个闸里。 */
    if (type == 2) this.setData({ disabled: true, disabledText: '系统通知，不能回复' });
    this.loadMessages(true);
    if (type == 2) this.loadReportEntry();
  },

  // 系统消息只放在城瘾小助手:举报进度(社区治理通知)从这里进「我的举报」,不再在消息列表单列。
  loadReportEntry() {
    const that = this;
    const epoch = (this._reportEntryEpoch || 0) + 1;
    this._reportEntryEpoch = epoch;
    app.sendRequest({
      url: '/api/v1/community/notifications',
      method: 'GET',
      data: { limit: 50 },
      hideLoading: true,
      silentError: true,
      success(res) {
        if (epoch !== that._reportEntryEpoch) return;
        const rows = isBizOk(res) && res.data ? reportNoticeRows(res.data.items) : null;
        that._reportUnreadIds = (rows || []).filter(function (r) { return r.unread; }).map(function (r) { return r.notificationId; });
        // 读不到(rows 为 null)也给入口:「我的举报」页自己有加载失败态
        that.setData({ reportEntry: !rows || rows.length > 0, reportUnread: that._reportUnreadIds.length });
      },
      fail() {
        if (epoch !== that._reportEntryEpoch) return;
        that._reportUnreadIds = [];
        that.setData({ reportEntry: true, reportUnread: 0 });
      },
    });
  },

  openReportMine() {
    if (this._navigating) return;
    this._navigating = true;
    wx.navigateTo({ url: '/subpackageMember/report-mine/index', complete: () => { this._navigating = false; } });
    // 进入即标已读;回到本页 onShow 重读,未读数以服务端 read_at 为准
    (this._reportUnreadIds || []).forEach(function (id) {
      app.sendRequest({
        url: '/api/v1/community/notifications/' + id + '/read',
        method: 'POST',
        data: JSON.stringify({ requestId: makeReadRequestId(id) }),
        header: { 'Content-Type': 'application/json' },
        hideLoading: true,
      });
    });
    this._reportUnreadIds = [];
  },

  // 会话身份兜底:只按会话类型给通用称呼,绝不臆造对方昵称。
  _heroName(name, type) {
    if (name) return name;
    if (type == 2) return '系统通知';
    if (type == 3) return '官方消息';
    return '对话';
  },

  // 发送闸:守在**每个真发 /api/im/send 的出口**上,不只守入口。
  // (只挡 onPlus 的话,任何绕过动作面板的调用路径都能照旧把 conversation_id:0 发出去)
  _canSend() { return this.data.loadState === 'ready' && !this.data.disabled && !this.data.readOnly; },

  _viewGuard() {
    const owner = this.data.myId, conversation = this.data.conversationId;
    const epoch = this._viewEpoch || 0;
    const credential = app.globalData && app.globalData.authorization;
    const recoveryEpoch = sendRecovery.epoch();
    return () => recoveryEpoch === sendRecovery.epoch() && !this._disposed && !this._deleted && epoch === (this._viewEpoch || 0)
      && owner === this.data.myId && conversation === this.data.conversationId
      && (!app.getUserID || app.getUserID() == owner)
      && credential === (app.globalData && app.globalData.authorization);
  },

  _isReadContextCurrent(conversationId, memberId) {
    return !this._disposed
      && this.data.conversationId === conversationId
      && this.data.myId === memberId
      && (!app.getUserID || !memberId || app.getUserID() == memberId);
  },

  _markReadWhenVisible(conversationId, memberId) {
    if (!this._isReadContextCurrent(conversationId, memberId)) return;
    if (this._hidden) {
      this._pendingReadConversationId = conversationId;
      this._pendingReadMemberId = memberId;
      return;
    }
    this._pendingReadConversationId = 0;
    this._pendingReadMemberId = 0;
    this.markRead();
  },

  retryLoad() {
    this.setData({ loadState: 'loading', loadErrorText: '' });
    this.loadMessages(true);
  },

  onReady() {
    // 实测 hero 区真实高度,覆正 onLoad 里的粗估值,消息流让位量与之同步(见 wxml scroll-view top)
    const that = this;
    if (this.data.sysNotice) return; // 系统通知标题在导航栏里,没有 hero 区
    this.createSelectorQuery().select('.chat-hero').boundingClientRect(function (rect) {
      if (rect && rect.height) that.setData({ heroH: Math.round(rect.height) });
    }).exec();
  },

  loadMessages(toBottom) {
    const that = this;
    const viewCurrent = this._viewGuard();
    const conversationId = this.data.conversationId;
    const memberId = this.data.myId;
    const pollEpoch = (this._pollRequestEpoch || 0) + 1;
    this._pollRequestEpoch = pollEpoch;
    this._pollInFlight = true;
    const requestEpoch = (this._messageRequestEpoch || 0) + 1;
    this._messageRequestEpoch = requestEpoch;
    const current = function () {
      return viewCurrent() && requestEpoch === that._messageRequestEpoch
        && pollEpoch === that._pollRequestEpoch
        && that._isReadContextCurrent(conversationId, memberId);
    };
    app.sendRequest({
      url: '/api/im/messages', method: 'POST', hideLoading: true, silentError: true,
      data: { conversation_id: conversationId, cursor_id: 0, size: 30 },
      success(res) {
        if (!current()) return;
        that._pollInFlight = false;
        if (res && res.code == 200 && res.data && typeof res.data === 'object' && !Array.isArray(res.data)
            && (res.data.list == null || isRecordList(res.data.list))) {
          const list = that.decorate(Array.isArray(res.data.list) ? res.data.list : []);
          that._pollWatermark = list.reduce((max, message) => compareOrder(messageOrder(message), max) > 0 ? messageOrder(message) : max, 0);
          const pending = that.data.msgs.filter(m => m.sendPayload && !list.some(server =>
            server.clientMessageId === m.clientMessageId && server.senderId == m.senderId));
          const msgs = list.concat(pending);
          that.setData({ msgs, sending: msgs.some(m => m.sending), hasMore: !!res.data.hasMore, cursor: res.data.nextCursor || 0, loadState: 'ready', loadErrorText: '', loadMoreError: '' });
          // 拉黑只存服务端:重进会话按回包禁用输入框,不然能打字、一发就失败(3-22)
          const blockedByMe = res.data.blockedByMe === true;
          that._blockedByMe = blockedByMe;
          that.setData({ disabled: that.data.readOnly || res.data.blocked === true, disabledText: that.data.readOnly ? '系统通知不支持回复，有新动态会出现在这里' : res.data.blocked === true ? (blockedByMe ? '你已拉黑对方，无法发送消息' : '对方暂不可联系，无法发送消息') : '',
            moreSheetItems: that.data.readOnly ? ['隐藏会话'] : ['举报这条消息', '举报对方', blockedByMe ? '解除拉黑' : '拉黑', '隐藏会话'] });
          if (toBottom) that.scrollBottom();
          that._markReadWhenVisible(conversationId, memberId);
          that.recoverPending();
          return;
        }
        that._setMessageLoadError(res);
      },
      fail(res) {
        if (!current()) return;
        that._pollInFlight = false;
        that._setMessageLoadError(res);
      },
      successStatusAbnormal(res) {
        if (!current()) return;
        that._pollInFlight = false;
        that._setMessageLoadError(res);
      },
    });
  },

  _setMessageLoadError(response) {
    if (response && response.errorCode === 'HANGOUT_CLOSED') {
      this._closedReturnPath = response.returnPath || '/subpackageB/pages/im/list/index';
      this.setData({ loadState: 'closed', loadErrorText: response.msg || '该组局已结束，聊天已关闭' });
      return;
    }
    const msg = app.getRequestErrorMessage
      ? app.getRequestErrorMessage(response, '消息加载失败，请重试')
      : ((response && (response.msg || response.errMsg)) || '消息加载失败，请重试');
    this.setData({ loadState: 'error', loadErrorText: msg });
  },

  returnToMessageList() {
    const path = this._closedReturnPath || '/subpackageB/pages/im/list/index';
    wx.redirectTo({ url: path });
  },

  loadMore() {
    if (!this.data.hasMore || this.data.loadingMore) return;
    const that = this;
    const viewCurrent = this._viewGuard();
    const cursor = this.data.cursor;
    const conversationId = this.data.conversationId;
    const memberId = this.data.myId;
    const requestEpoch = (this._loadMoreRequestEpoch || 0) + 1;
    this._loadMoreRequestEpoch = requestEpoch;
    const current = function () {
      return viewCurrent() && requestEpoch === that._loadMoreRequestEpoch
        && that._isReadContextCurrent(conversationId, memberId);
    };
    this.setData({ loadingMore: true, loadMoreError: '' });
    app.sendRequest({
      url: '/api/im/messages', method: 'POST', hideLoading: true, silentError: true,
      data: { conversation_id: conversationId, cursor_id: cursor, size: 30 },
      success(res) {
        if (!current()) return;
        if (res && res.code == 200 && res.data && typeof res.data === 'object' && !Array.isArray(res.data)
            && (res.data.list == null || isRecordList(res.data.list))) {
          that._mergeMessages(Array.isArray(res.data.list) ? res.data.list : [], true);
          that.setData({ hasMore: !!res.data.hasMore, cursor: res.data.nextCursor || 0, loadingMore: false, loadMoreError: '' });
        } else {
          that._setLoadMoreError(res);
        }
      },
      fail(res) { if (current()) that._setLoadMoreError(res); },
      successStatusAbnormal(res) { if (current()) that._setLoadMoreError(res); },
    });
  },

  _setLoadMoreError(response) {
    const msg = app.getRequestErrorMessage
      ? app.getRequestErrorMessage(response, '更早消息加载失败，请重试')
      : ((response && (response.msg || response.errMsg)) || '更早消息加载失败，请重试');
    this.setData({ loadingMore: false, loadMoreError: msg });
  },

  retryLoadMore() { this.loadMore(); },

  // 加时间分隔 + 解析卡片(老卡片无 cardType → generic,走现有渲染路径)
  decorate(list) {
    let last = 0;
    const that = this;
    const decorated = list.map(function (m) {
      const t = m.createTime ? toTimestamp(String(m.createTime)) : 0;
      let divider = '';
      if (!last || (t - last) > 5 * 60 * 1000) divider = fmtAbs(t);
      last = t;
      let card = null;
      // 文字按纯文本展示:服务端过滤会把 x<y 存成 x&lt;y。content 保留原值(重排时会再过一遍 decorate,
      // 不能在原字段上反复解码),气泡读 displayContent。图片消息的 content 是地址,不动。
      const displayContent = m.msgType == 2 ? m.content : decodeDisplayText(m.content);
      if (m.msgType == 3) {
        try { card = JSON.parse(m.extraJson || '{}'); } catch (e) { card = {}; }
        if (!card || typeof card !== 'object' || Array.isArray(card)) card = {};
        if (!card.cardType) card.cardType = 'generic';
        if (card.cardType === 'generic' && !card.title) card.title = displayContent || '通知';
      }
      const projected = Object.assign({}, m, { card });
      const systemRecord = !!systemCard.recordOf(projected), currentOriginalUrl = systemCard.originalUrl(projected);
      const genericAction = systemCard.genericActionInfo(projected);
      const genericButtons = !systemRecord && Array.isArray(card && card.buttons) ? card.buttons.map((button, index) =>
        systemCard.genericActionInfo(projected, index) ? { index, text: button.text || '查看详情', type: button.type } : null).filter(Boolean) : [];
      const cardTarget = systemRecord || !!currentOriginalUrl || !!genericAction;
      // 对方气泡上方「名字·时间」一行用的名字:单聊/客服/系统统一用会话对方名(name)。
      // 小程序不露出群聊(见 list/index.js:tabOf),故无需逐条 senderName。
      return Object.assign({}, m, { timeDivider: divider, noticeTime: t ? fmtAbs(t) : '', card: card, cardTarget, systemRecord, currentOriginalUrl, genericAction, genericButtons, displayContent: displayContent,
        displayName: that.data.name || '' });
    });
    if (that._reviewMessageId && !that._reviewMatches(decorated.find(row => String(row.id) === String(that._reviewMessageId)))) that.closeReviewResult();
    that.fetchTopics(decorated);
    return decorated;
  },

  // route 卡现拉(spec 决策 8:永远最新)。去重 + 页面级缓存;
  // 一屏 30 条里路线卡通常 0-2 张,不值得为此加批量端点。
  fetchTopics(list) {
    const that = this;
    const cache = this.data.topicCache;
    const ids = [];
    list.forEach(function (m) {
      // route 与 signup 同构,共用现拉
      if (m.card && (m.card.cardType === 'route' || m.card.cardType === 'signup') && m.card.topicId) {
        const id = m.card.topicId;
        if (m.card.cardType === 'route' && m.content === '路线已不可用') {
          that.setData({ ['topicCache.' + id]: { name: '路线已不可用' } });
          return;
        }
        // 路线名称只来自消息当前投影；详情权限可能允许作者读私密正文。
        if (m.card.cardType === 'route' && typeof m.content === 'string' && m.content.startsWith('[路线]')) {
          that.setData({ ['topicCache.' + id]: { name: m.content.replace(/^\[路线\]\s*/, ''), imgUrl: m.card.imgUrl || '', totalMileage: m.card.totalMileage || 0, locationCount: m.card.locationCount || 0 } });
          return;
        }
        if (m.card.cardType === 'route') {
          that.setData({ ['topicCache.' + id]: { name: '路线已不可用' } });
          return;
        }
        // 必须 === undefined:三态里 null 是「已确认下架」的终态答案,不是「还没拉」。
        // 用真值判断会把 null 当没拉过,对已下架主题每批 decorate 重发一次请求。
        if (cache[id] === undefined && ids.indexOf(id) < 0) ids.push(id);
      }
    });
    if (!ids.length) return;
    const current = this._viewGuard();
    const topicEpoch = this._topicEpoch || 0;
    ids.forEach(function (id) {
      app.sendRequest({
        url: '/api/topic/info-to-user', method: 'POST', hideLoading: true,
        data: { id: id },
        success(res) {
          if (!current() || topicEpoch !== (that._topicEpoch || 0)) return;
          const d = (res && res.code == 200) ? res.data : null;
          // 拉不到 → 记 null,渲染层出泛称降级态「暂不可用」:网络失败同样是 null,不能说成已下架/不公开
          that.setData({ ['topicCache.' + id]: d || null });
        },
        fail() { if (current() && topicEpoch === (that._topicEpoch || 0)) that.setData({ ['topicCache.' + id]: null }); },
      });
    });
  },

  scrollBottom() {
    const that = this;
    const conversationId = this.data.conversationId;
    const memberId = this.data.myId;
    setTimeout(function () {
      if (that._isReadContextCurrent(conversationId, memberId)) {
        that.setData({ toView: 'bottom-anchor' });
      }
    }, 60);
  },

  // U-142:轮询到新消息时,只有读者本来就停在底部附近才跟随滚动;往上翻看历史时不强拉到底。
  // 在轮询这一刻读消息流的位置,不新增滚动事件绑定。
  followIfNearBottom() {
    const that = this;
    const query = typeof wx.createSelectorQuery === 'function' ? wx.createSelectorQuery() : null;
    const probe = query && query.select('.msgs');
    if (!probe || typeof probe.scrollOffset !== 'function' || typeof probe.boundingClientRect !== 'function') {
      this.scrollBottom();
      return;
    }
    query.select('.msgs').boundingClientRect();
    query.select('.msgs').scrollOffset();
    query.exec(function (res) {
      const rect = res && res[0], offset = res && res[1];
      if (rect && offset && offset.scrollHeight - offset.scrollTop - rect.height > NEAR_BOTTOM_PX) return;
      that.scrollBottom();
    });
  },

  onInput(e) { this.setData({ text: e.detail.value }); },

  onSend() {
    const content = (this.data.text || '').trim();
    if (!content || this.data.sending) return;
    if (!this._canSend()) return;
    if (this._disposed || (app.getUserID && app.getUserID() != this.data.myId)) return;
    if (this._sendMessage({ msg_type: 1, content })) this.setData({ text: '' });
  },

  _sendMessage(message, retryId) {
    if (!this._canSend() || this._disposed) return;
    const owner = this.data.myId;
    const conversationId = this.data.conversationId;
    if (app.getUserID && app.getUserID() != owner) return;
    const payload = Object.assign({}, message, { conversation_id: conversationId });
    if (!payload.client_message_id) {
      this._sendSequence = (this._sendSequence || 0) + 1;
      payload.client_message_id = 'im_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2) + '_' + this._sendSequence;
    }
    const receipt = sendRecovery.begin(owner, conversationId, payload.client_message_id);
    if (!receipt) { app.tips && app.tips('原消息请求未能保存，请稍后重试'); return false; }
    const tmpId = retryId || 'tmp_' + payload.client_message_id;
    let msgs = this.data.msgs.slice();
    if (retryId) {
      const index = msgs.findIndex(m => m.id === retryId && m.failed && !m.sending);
      if (index < 0) return;
      msgs[index] = Object.assign({}, msgs[index], { sending: true, failed: false });
    } else {
      const card = payload.msg_type === 3 ? JSON.parse(payload.extra_json) : null;
      msgs.push({ id: tmpId, senderId: owner, msgType: payload.msg_type, content: payload.content,
        extraJson: payload.extra_json, card, clientMessageId: payload.client_message_id,
        sendPayload: payload, sending: true, failed: false, timeDivider: '' });
    }
    this.setData({ msgs, sending: true });
    this.scrollBottom();
    const viewCurrent = this._viewGuard();
    const current = () => viewCurrent() && sendRecovery.current(receipt);
    const finish = (res) => {
      if (!current()) return;
      const list = this.data.msgs.slice();
      const index = list.findIndex(m => m.id === tmpId);
      if (index >= 0) {
        const id = res && res.data && res.data.id;
        const validId = (typeof id === 'number' || (typeof id === 'string' && /^[1-9]\d*$/.test(id)))
          && Number.isSafeInteger(Number(id)) && Number(id) > 0;
        if (isBizOk(res) && validId) {
          sendRecovery.complete(receipt);
          list.splice(index, 1);
          this.setData({ msgs: list });
          this._mergeMessages([res.data]);
        } else {
          list[index] = Object.assign({}, list[index], { sending: false, failed: true });
          this.setData({ msgs: list });
          // 200 但 data.id 不合法时 msg 是「操作成功」,不能当失败提示(2026-09-17 拍板)。
          if (!isBizOk(res) && res && res.msg) app.tips && app.tips(res.msg);
        }
      }
      this.setData({ sending: this.data.msgs.some(m => m.sending) });
    };
    app.sendRequest({
      url: '/api/im/send', method: 'POST', hideLoading: true,
      data: payload, success: finish, fail: finish, successStatusAbnormal: finish,
    });
    return true;
  },

  _mergeMessages(incoming, prepend) {
    // 新的不可见投影也使在途旧公开投影失效。
    if (incoming.some(m => m.content === '路线已不可用')) this._topicEpoch = (this._topicEpoch || 0) + 1;
    const list = prepend ? incoming.slice() : this.data.msgs.slice();
    const additions = prepend ? this.data.msgs : incoming;
    additions.forEach(message => {
      const index = list.findIndex(m => m.id == message.id || (message.clientMessageId
        && m.clientMessageId === message.clientMessageId && m.senderId == message.senderId));
      if (index >= 0) { if (!prepend) list[index] = message; }
      else list.push(message);
    });
    list.sort(compareMessages);
    this.setData({ msgs: this.decorate(list), sending: list.some(m => m.sending) });
  },

  onResend(e) {
    const idx = e.currentTarget.dataset.index;
    const m = this.data.msgs[idx];
    if (!m || !m.failed || m.sending || m.senderId != this.data.myId || !m.sendPayload) return;
    const current = this._viewGuard();
    const receipt = sendRecovery.pending(this.data.myId, this.data.conversationId)
      .find(r => r.id === m.clientMessageId);
    if (!receipt) return;
    this._readReceipt(receipt, result => {
      if (!current() || !sendRecovery.current(receipt)) return;
      if (result && result.found === true && result.message) {
        this._mergeMessages([result.message]); sendRecovery.complete(receipt);
      } else if (result && result.found === false) this._sendMessage(m.sendPayload, m.id);
    }, 'retry');
  },

  _readReceipt(receipt, done, purpose = 'recover') {
    const viewCurrent = this._viewGuard();
    const current = () => viewCurrent() && sendRecovery.current(receipt);
    if (!current()) return;
    const key = JSON.stringify([receipt.owner, receipt.conversation, receipt.id]);
    const reads = this._receiptReads || (this._receiptReads = new Map());
    const previous = reads.get(key);
    if (previous && previous.current()) {
      // 自动恢复和用户重试共享一次查询；同一用途只保留一个消费者。
      previous.handlers.set(purpose, done);
      return;
    }
    const flight = { current, handlers: new Map([[purpose, done]]) };
    reads.set(key, flight);
    let settled = false;
    const finish = res => {
      // 同一请求的重复回调，以及失效请求的迟到回调，都不能释放后继请求。
      if (settled) return;
      settled = true;
      if (reads.get(key) !== flight) return;
      try {
        if (!current()) return;
        const result = isBizOk(res) && res.data, message = result && result.message;
        const found = result && result.found === true && message && Number.isSafeInteger(Number(message.id)) && Number(message.id) > 0
          && message.senderId == receipt.owner && message.clientMessageId === receipt.id;
        if (result && (result.found === false || found)) {
          flight.handlers.forEach(handler => { if (current()) handler(result); });
        } else if (flight.handlers.has('retry')) app.tips('发送状态暂时无法确认，请稍后重试');
      } finally {
        if (reads.get(key) === flight) reads.delete(key);
      }
    };
    app.sendRequest({ url: '/api/im/send-receipt', method: 'POST', hideLoading: true, silentError: true,
      data: { conversation_id: receipt.conversation, client_message_id: receipt.id },
      success: finish, fail: () => finish(), successStatusAbnormal: () => finish() });
  },
  recoverPending() {
    sendRecovery.pending(this.data.myId, this.data.conversationId).forEach(receipt => {
      this._readReceipt(receipt, result => {
        if (result.found === true && result.message) {
          this._mergeMessages([result.message]); sendRecovery.complete(receipt);
        }
      });
    });
  },
  refreshRouteProjections() {
    const current = this._viewGuard();
    if (!current()) return;
    this._topicEpoch = (this._topicEpoch || 0) + 1;
    const targets = new Set(this.data.msgs.filter(m => m.card && m.card.cardType === 'route'
      && Number.isSafeInteger(Number(m.id)) && Number(m.id) > 0).map(m => String(m.id)));
    if (!targets.size) return;
    // 先清除消息本身与缓存的旧投影，其他消息合并也不能复活旧私密名称。
    const masked = this.data.msgs.map(m => targets.has(String(m.id)) ? Object.assign({}, m, {
      content: '路线已不可用', extraJson: JSON.stringify({ cardType: 'route', topicId: m.card.topicId })
    }) : m);
    this.setData({ msgs: this.decorate(masked) });
    // 历史游标依据可见时间排序，不能用 id+1 猜一条不存在的游标行。
    let projectionEpoch = this._topicEpoch;
    const visited = new Set();
    const read = cursor => {
      visited.add(cursor);
      app.sendRequest({ url: '/api/im/messages', method: 'POST', hideLoading: true, silentError: true,
        data: { conversation_id: this.data.conversationId, cursor_id: cursor, size: 50 },
        success: res => {
          if (!current() || projectionEpoch !== this._topicEpoch || !isBizOk(res)
              || !res.data || !isRecordList(res.data.list)) return;
          const projected = res.data.list.filter(m => targets.has(String(m.id)));
          projected.forEach(m => targets.delete(String(m.id)));
          if (projected.length) {
            this._mergeMessages(projected);
            projectionEpoch = this._topicEpoch;
          }
          const next = Number(res.data.nextCursor);
          if (targets.size && res.data.hasMore && Number.isSafeInteger(next) && next > 0
              && !visited.has(next) && res.data.list.some(m => Number(m.id) === next)) read(next);
        }, fail() {}, successStatusAbnormal() {} });
    };
    read(0);
  },

  /* 2026-09-02:原来这两处弹 wx.showActionSheet(系统弹层,设计体系外)。
     「+」与「更多」是**动作菜单**不是一次选择,所以用 cy-option-sheet 的 immediate 形态
     —— 点一行立刻执行,不画单选圆点也不加确认 CTA(套确认反而多一步)。 */
  onPlus() {
    if (!this._canSend()) return;
    this.setData({ plusSheetShow: true });
  },

  onPlusSelect(e) {
    const i = e.detail.index;
    this.setData({ plusSheetShow: false });
    if (i === 0) this.onPickImage();
    else if (i === 1) this.onSendLocation();
    else if (i === 2) this.openRoutePicker();
  },

  onPlusCancel() { this.setData({ plusSheetShow: false }); },

  onSendLocation() {
    const that = this;
    const conversationId = this.data.conversationId;
    const memberId = this.data.myId;
    const viewCurrent = this._viewGuard();
    const current = () => viewCurrent() && that._canSend() && that._isReadContextCurrent(conversationId, memberId);
    if (!current()) return;
    wx.chooseLocation({
      success(loc) {
        if (!current()) return;
        that.sendCard({
          cardType: 'location',
          name: loc.name || loc.address,
          address: loc.address || '',
          lat: loc.latitude,
          lng: loc.longitude,
        });
      },
      fail(e) {
        if (!current()) return;
        // 用户取消不提示;权限被拒才引导。F-R2S-10:给出「去设置」恢复入口,授权后回到选址。
        if (e && /auth (deny|denied)/.test(String(e.errMsg || ''))) {
          showPermissionGuide(null, () => { if (current()) that.onSendLocation(); });
        }
      },
    });
  },

  // 发卡片:content 由服务端重建,这里传什么都不算数(T2 净化)
  sendCard(card) {
    this._sendMessage({ msg_type: 3, content: '[卡片]', extra_json: JSON.stringify(card) });
  },

  openRoutePicker() {
    if (!this._canSend() || !this._isReadContextCurrent(this.data.conversationId, this.data.myId)) return;
    this.setData({ routePickerOpen: true, routeKeyword: '' });
    this.loadRoutes();
  },
  closeRoutePicker() {
    clearTimeout(this._routeTimer);
    this._routeSeq = (this._routeSeq || 0) + 1;
    this.setData({ routePickerOpen: false, routeList: [], routeLoading: false, routeError: '' });
  },

  onRouteSearch(e) {
    if (!this.data.routePickerOpen || !this._canSend()
        || !this._isReadContextCurrent(this.data.conversationId, this.data.myId)) return;
    const kw = (e.detail && e.detail.value) || '';
    this._routeSeq = (this._routeSeq || 0) + 1;
    this.setData({ routeKeyword: kw, routeList: [], routeLoading: true, routeError: '' });
    // 防抖:停止输入 300ms 后才发请求,避免每敲一个字打一次
    if (this._routeTimer) clearTimeout(this._routeTimer);
    const that = this;
    this._routeTimer = setTimeout(function () { that.loadRoutes(); }, 300);
  },

  loadRoutes() {
    const that = this;
    const viewCurrent = this._viewGuard();
    const conversationId = this.data.conversationId;
    const memberId = this.data.myId;
    if (!this.data.routePickerOpen || !this._canSend() || !this._isReadContextCurrent(conversationId, memberId)) return;
    // 竞态守卫:只认最后一次请求的响应,防止先发的旧关键词后到、覆盖新结果
    const seq = (this._routeSeq || 0) + 1;
    this._routeSeq = seq;
    const current = () => viewCurrent() && seq === that._routeSeq && that.data.routePickerOpen
      && that._canSend() && that._isReadContextCurrent(conversationId, memberId);
    this.setData({ routeLoading: true, routeError: '', routeList: [] });
    app.sendRequest({
      url: '/api/topic/list', method: 'POST', hideLoading: true, silentError: true,
      data: { is_my: 0, keyword: this.data.routeKeyword },
      success(res) {
        if (!current()) return;
        if (res && res.code == 200 && res.data && typeof res.data === 'object' && !Array.isArray(res.data)
            && isRecordList(res.data.rows)) {
          that.setData({ routeList: res.data.rows, routeLoading: false, routeError: '' });
          return;
        }
        that._setRouteError(res);
      },
      fail(res) {
        if (!current()) return;
        that._setRouteError(res);
      },
    });
  },

  _setRouteError(response) {
    const msg = app.getRequestErrorMessage
      ? app.getRequestErrorMessage(response, '路线加载失败，请重试')
      : ((response && (response.msg || response.errMsg)) || '路线加载失败，请重试');
    this.setData({ routeList: [], routeLoading: false, routeError: msg });
  },

  retryRoutes() { this.loadRoutes(); },

  onRoutePick(e) {
    const id = e.currentTarget.dataset.id;
    if (!id || !this.data.routePickerOpen || !this._canSend()
        || !this._isReadContextCurrent(this.data.conversationId, this.data.myId)
        || !this.data.routeList.some(route => route.id == id)) return;
    this.closeRoutePicker();
    // 只传 topicId —— 展示字段服务端不收、前端现拉(spec 决策 8)
    this.sendCard({ cardType: 'route', topicId: id });
  },

  onPickImage() {
    const that = this;
    if (!this._canSend() || !this._isReadContextCurrent(this.data.conversationId, this.data.myId)) return;
    const owner = this.data.myId;
    const conversationId = this.data.conversationId;
    app.chooseImage(function (urls) {
      if (!urls || !urls.length || !that._isReadContextCurrent(conversationId, owner)) return;
      that._sendMessage({ msg_type: 2, content: urls[0] });
    });
  },

  markRead() {
    if (!this._viewGuard()()) return;
    const latest = this.data.msgs.filter(m => Number.isSafeInteger(Number(m.id)) && Number(m.id) > 0)
      .reduce((last, m) => !last || compareMessages(m, last) > 0 ? m : last, null);
    app.sendRequest({ url: '/api/im/read', method: 'POST', hideLoading: true,
      data: { conversation_id: this.data.conversationId, read_to: latest ? latest.id : 0 } });
  },

  onMore() {
    if (!this._isReadContextCurrent(this.data.conversationId, this.data.myId)) return;
    this.setData({ moreSheetShow: true });
  },

  onMoreSelect(e) {
    if (!this._isReadContextCurrent(this.data.conversationId, this.data.myId)) return;
    const label = e.detail.label;
    this.setData({ moreSheetShow: false });
    if (label === '举报这条消息') this.doReport();
    else if (label === '举报对方') this.doReportPeer();
    else if (label === '拉黑') this.doBlock();
    else if (label === '解除拉黑') this.doBlock(true);
    else if (label === '隐藏会话') this.clearConversation();
  },

  clearConversation() {
    const id = this.data.conversationId;
    const memberId = this.data.myId;
    if (!this._isReadContextCurrent(id, memberId)) return;
    if (this._pendingClear && this._pendingClear.submitting
        && this._isReadContextCurrent(this._pendingClear.conversationId, this._pendingClear.memberId)) return;
    if (!id) { app.tips && app.tips('无法隐藏该会话'); return; }
    this._pendingBlock = null;
    this._pendingClear = { conversationId: id, memberId };
    const dc = this.selectComponent && this.selectComponent('#dc');
    if (dc) dc.open('im.conversation.delete');
  },

  onDangerCancel(e) {
    if (e.detail.key === 'im.conversation.delete') this._pendingClear = null;
    if (e.detail.key === 'im.block') this._pendingBlock = null;
  },

  onMoreCancel() { this.setData({ moreSheetShow: false }); },
  doReport() {
    const conversationId = this.data.conversationId;
    const memberId = this.data.myId;
    if (!this._isReadContextCurrent(conversationId, memberId)) return;
    if (this._reportInFlight
        && this._isReadContextCurrent(this._reportInFlight.conversationId, this._reportInFlight.memberId)) return;
    // 没有「选中某条」的交互 ⇒ 只举报对方发的最近一条,绝不报自己的消息(3-22)
    let last = null;
    for (let i = this.data.msgs.length - 1; i >= 0; i--) {
      const m = this.data.msgs[i];
      if (m.id && String(m.id).indexOf('tmp_') !== 0 && m.senderId && m.senderId != this.data.myId) { last = m; break; }
    }
    if (!last) { app.tips && app.tips('暂无可举报的消息'); return; }
    const pending = { conversationId, memberId };
    this._reportInFlight = pending;
    const finish = (res) => {
      if (this._reportInFlight !== pending) return;
      this._reportInFlight = null;
      if (!this._isReadContextCurrent(conversationId, memberId)) return;
      const text = isBizOk(res) ? '举报已提交' : ((res && res.msg) || '提交失败，请重试');
      if (this._hidden) this._pendingReportTip = { conversationId, memberId, text };
      else app.tips && app.tips(text);
    };
    app.sendRequest({
      url: '/api/im/report', method: 'POST', hideLoading: true, silentError: true,
      data: { message_id: last.id, reason: '用户举报' },
      success: finish,
      fail() { finish({ msg: '提交失败，请重试' }); },
    });
  },
  doBlock(unblock) {
    const conversationId = this.data.conversationId;
    const memberId = this.data.myId;
    if (!conversationId || !this._isReadContextCurrent(conversationId, memberId)) return;
    if (this._pendingBlock
        && this._isReadContextCurrent(this._pendingBlock.conversationId, this._pendingBlock.memberId)) return;
    if (unblock === true && !this._blockedByMe) return;
    const pending = { conversationId, memberId, submitting: true, unblock: unblock === true };
    this._pendingClear = null;
    this._pendingBlock = pending;
    const finish = (res) => {
      if (this._pendingBlock !== pending || !this._isReadContextCurrent(conversationId, memberId)) return;
      const rows = isBizOk(res) && isRecordList(res.data) ? res.data : [];
      const row = rows.find(item => String(item.conversationId) === String(conversationId));
      const id = row && Number(row.type) === 1 && row.counterparty ? row.counterparty.id : null;
      const targetId = typeof id === 'number' || (typeof id === 'string' && /^[1-9][0-9]*$/.test(id)) ? Number(id) : 0;
      pending.submitting = false;
      if (Number.isSafeInteger(targetId) && targetId > 0 && targetId !== Number(memberId)) pending.targetId = targetId;
      else pending.error = (res && !isBizOk(res) && res.msg) || '无法拉黑该会话，请重试';
      this._showBlockConfirmation(pending);
    };
    app.sendRequest({
      url: '/api/im/conversations', method: 'POST', hideLoading: true, silentError: true,
      success: finish,
      fail() { finish({ msg: '会话对象读取失败，请重试' }); },
    });
  },
  // BL-36:举报对方(社区信任举报 targetType=MEMBER),走共用举报半屏。
  // 本页拿不到会话对方 id,与「拉黑」同口径:取最近一条对方消息的 senderId。
  doReportPeer() {
    if (this.data.readOnly) return;
    let otherId = 0;
    for (let i = this.data.msgs.length - 1; i >= 0; i--) { if (this.data.msgs[i].senderId && this.data.msgs[i].senderId != this.data.myId) { otherId = this.data.msgs[i].senderId; break; } }
    if (!otherId) { app.tips && app.tips('对方还没发过消息，暂不能举报'); return; }
    this.setData({ reportShow: true, reportPeerId: String(otherId) });
  },
  onReportClose() { this.setData({ reportShow: false }); },

  _showBlockConfirmation(pending) {
    if (!pending || this._pendingBlock !== pending || pending.submitting || pending.opened || this._hidden
        || !this._isReadContextCurrent(pending.conversationId, pending.memberId)) return;
    const dc = this.selectComponent && this.selectComponent('#dc');
    if (pending.error || !dc && !pending.unblock) {
      this._pendingBlock = null;
      app.tips && app.tips(pending.error || '无法打开确认，请重试');
      return;
    }
    if (pending.unblock) {
      this._submitUnblock(pending);
      return;
    }
    pending.opened = true;
    dc.open('im.block');
  },

  _submitUnblock(pending) {
    if (!pending.targetId || pending.submitting || !this._blockedByMe) return;
    pending.submitting = true;
    const current = () => this._pendingBlock === pending
      && this._isReadContextCurrent(pending.conversationId, pending.memberId);
    const finish = (res) => {
      if (!current()) return;
      this._pendingBlock = null;
      if (!isBizOk(res)) { app.tips && app.tips((res && res.msg) || '解除拉黑失败，请重试'); return; }
      // 是否仍被对方拉黑由服务端回读，不以一次写成功擅自启用输入框。
      this.loadMessages(false);
    };
    app.sendRequest({ url: '/api/im/unblock', method: 'POST', hideLoading: true, silentError: true,
      data: { target_member_id: pending.targetId }, success: finish, successStatusAbnormal: finish,
      fail() { finish({ msg: '解除拉黑失败，请重试' }); } });
  },

  onDangerConfirm(e) {
    const key = e.detail.key;
    if (key !== 'im.conversation.delete' && key !== 'im.block') return;
    const clear = key === 'im.conversation.delete';
    const pending = clear ? this._pendingClear : this._pendingBlock;
    if (!pending || pending.submitting || (!clear && (!pending.targetId || !pending.opened))
        || !this._isReadContextCurrent(pending.conversationId, pending.memberId)) return;
    const viewCurrent = this._viewGuard();
    const current = () => viewCurrent() && (clear ? this._pendingClear : this._pendingBlock) === pending
      && this._isReadContextCurrent(pending.conversationId, pending.memberId);
    pending.submitting = true;
    const dc = this.selectComponent && this.selectComponent('#dc');
    if (dc) dc.busyOn();
    const failed = (message) => {
      if (!current()) return;
      pending.submitting = false;
      if (dc) dc.failed(message);
      else app.tips && app.tips(message);
    };
    if (clear) {
      app.sendRequest({
        url: '/api/im/delete', method: 'POST', hideLoading: true, silentError: true,
        data: { conversation_id: pending.conversationId },
        success: (res) => {
          if (!current()) return;
          if (!isBizOk(res)) { failed((res && res.msg) || '隐藏失败'); return; }
          sendRecovery.abandon(pending.memberId, pending.conversationId);
          this._deleted = true;
          this.stopPoll();
          this.setData({ msgs: [], topicCache: {}, sending: false });
          this._pendingClear = null;
          if (dc) dc.done();
          this._returnAfterClear(pending.conversationId, pending.memberId);
        },
        fail() { failed('隐藏失败，请重试'); },
      });
      return;
    }
    app.sendRequest({
      url: '/api/im/block', method: 'POST', hideLoading: true, silentError: true, data: { target_member_id: pending.targetId },
      // ⚠️ success() 被调用不等于服务端拉黑成功(HTTP 200 + 业务 code!=200 也走这里)。
      // 不判 code 就禁用输入框并弹「已拉黑」,用户会以为对方发不进来了,实际没有。
      success: (res) => {
        if (!current()) return;
        if (!isBizOk(res)) { failed((res && res.msg) || '拉黑失败，请重试'); return; }
        this._blockedByMe = true;
        this.setData({ disabled: true, disabledText: '你已拉黑对方，无法发送消息', moreSheetItems: ['举报这条消息', '举报对方', '解除拉黑', '隐藏会话'] });
        this._pendingBlock = null;
        if (dc) dc.done();
      },
      fail() { failed('拉黑失败，请重试'); },
    });
  },

  _returnAfterClear(conversationId, memberId) {
    if (!this._isReadContextCurrent(conversationId, memberId)) return;
    if (this._hidden) {
      this._pendingClearReturn = { conversationId, memberId };
      return;
    }
    wx.navigateBack({
      fail: () => {
        if (!this._hidden && !this._pendingClear && !this._pendingBlock
            && this._isReadContextCurrent(conversationId, memberId)) {
          wx.redirectTo({ url: '/subpackageB/pages/im/list/index' });
        }
      },
    });
  },

  // 位置卡:写死 openLocation,不读 extra_json 里的 action(spec §3.3 防钓鱼)
  onLocationTap(e) {
    const ds = e.currentTarget.dataset;
    wx.openLocation({
      latitude: Number(ds.lat),
      longitude: Number(ds.lng),
      name: ds.name || '',
      address: ds.address || '',
      scale: 16,
    });
  },

  // 路线卡:写死主题详情,不读 extra_json 里的 action
  onRouteTap(e) {
    const id = e.currentTarget.dataset.topicid;
    if (!id || this._navigating) return; // 跳转中不响应连点
    // 只在「确认下架」(null)时拦;加载中(undefined)放行 —— 详情页自己会加载
    if (this.data.topicCache[id] === null || this.data.topicCache[id] && this.data.topicCache[id].name === '路线已不可用') { app.tips && app.tips('该路线暂不可用'); return; }
    this._navigating = true; wx.navigateTo({ url: '/pages/topic/index/index?id=' + id, complete: () => { this._navigating = false; } });
  },

  _cardMessage(id) {
    if (this._disposed || this._hidden || ![2, '2'].includes(this.data.type) || !systemCard.positiveId(id)
        || app.getUserID && app.getUserID() != this.data.myId) return null;
    const message = this.data.msgs.find(row => String(row.id) === String(id));
    return message && (message.conversationId == null || String(message.conversationId) === String(this.data.conversationId)) ? message : null;
  },
  _reviewMatches(message) {
    const record = systemCard.recordOf(message);
    return !!record && JSON.stringify(record) === this._reviewRecordKey;
  },
  onCardTap(e) {
    if (this._navigating) return;
    const message = this._cardMessage(e.currentTarget.dataset.msg), result = systemCard.recordOf(message);
    if (!result) { this._openGenericCard(message); return; }
    if (this.data.reviewResult && this._reviewMessageId === message.id && this._reviewMatches(message)) return;
    this._reviewMessageId = message.id; this._reviewRecordKey = JSON.stringify(result);
    this.setData({ reviewResult: { taskId: systemCard.positiveId(result.taskId), bizId: systemCard.positiveId(result.bizId),
      outcome: result.outcome, reason: result.reason, followUp: result.followUp }, reviewDetail: null });
    if (shouldLoadAuditDetail(message.card)) this.loadReviewDetail(result.taskId);
  },
  onCurrentCard(e) { this._openCardOriginal(this._cardMessage(e.currentTarget.dataset.msg)); },
  _openGenericCard(message, index) {
    const target = systemCard.genericActionInfo(message, index);
    if (target) this._openCardTarget(target.url, target.bcId);
  },
  _openCardOriginal(message) {
    const url = systemCard.originalUrl(message);
    if (url) this._openCardTarget(url, systemCard.positiveId(message.card.bcId));
  },
  _openCardTarget(url, bcid) {
    if ((!url && !bcid) || this._navigating) return;
    const current = this._viewGuard(), epoch = this._cardNavEpoch = (this._cardNavEpoch || 0) + 1;
    this._navigating = true;
    const release = () => { if (current() && epoch === this._cardNavEpoch) this._navigating = false; };
    if (bcid) app.sendRequest({ url: '/api/official/broadcast/' + bcid + '/click', method: 'POST',
      hideLoading: true, data: { channel: 'inapp' }, success() {}, fail() {}, complete: () => { if (!url) release(); } });
    if (!url) return;
    wx.navigateTo({ url,
      fail: () => { if (current() && epoch === this._cardNavEpoch) app.tips && app.tips('原单暂时无法打开'); },
      complete: () => { if (current() && epoch === this._cardNavEpoch) this._navigating = false; } });
  },
  closeReviewResult() {
    this._reviewDetailEpoch = (this._reviewDetailEpoch || 0) + 1;
    this._reviewMessageId = null; this._reviewRecordKey = null;
    this.setData({ reviewResult: null, reviewDetail: null });
  },

  // 通知生成时记录与当前审核详情分开；详情仍只读既有 GET，权限由原接口判定。
  loadReviewDetail(taskId) {
    const auditTaskId = systemCard.positiveId(taskId), auditUrl = auditTaskId && auditDetailUrl(auditTaskId);
    if (!auditUrl || !this.data.reviewResult || !this._reviewMatches(this._cardMessage(this._reviewMessageId))) return;
    const epoch = this._reviewDetailEpoch = (this._reviewDetailEpoch || 0) + 1, viewCurrent = this._viewGuard();
    this.setData({ reviewDetail: { state: 'loading', statusLabel: '', timeline: [], error: '' } });
    const settle = detail => {
      if (epoch !== this._reviewDetailEpoch || !viewCurrent()
          || !this._reviewMatches(this._cardMessage(this._reviewMessageId))) return;
      this.setData({ reviewDetail: detail });
    };
    app.sendRequest({
      hideLoading: true, silentError: true, url: `/api/audit/${auditTaskId}/detail`, method: 'GET',
      success: res => {
        const ok = res && (res.code === 200 || res.code === '200');
        const task = ok && res.data && res.data.task;
        const shaped = task && systemCard.positiveId(task.id) === auditTaskId ? shapeAuditDetail(res.data) : null;
        if (shaped) { settle(Object.assign({ state: 'ready', error: '' }, shaped)); return; }
        settle({ state: 'error', statusLabel: '', timeline: [], retryable: isRetryableAuditError(res),
          error: ok ? '审核记录与当前通知不一致' : app.getRequestErrorMessage(res, '审核记录暂时无法读取') });
      },
      successStatusAbnormal: (res, status) => settle({ state: 'error', statusLabel: '', timeline: [],
        retryable: isRetryableAuditError({ code: status || res && res.code }), error: app.getRequestErrorMessage(res, '审核记录暂时无法读取') }),
      fail: res => settle({ state: 'error', statusLabel: '', timeline: [],
        retryable: isRetryableAuditError({ code: res && (res.statusCode || res.code) }), error: app.getRequestErrorMessage(res, '网络异常，审核记录没有加载') }),
    });
  },
  retryReviewDetail() {
    const detail = this.data.reviewDetail;
    if (this.data.reviewResult && detail && detail.retryable) this.loadReviewDetail(this.data.reviewResult.taskId);
  },
  onCardBtn(e) {
    const ds = e.currentTarget.dataset, message = this._cardMessage(ds.msg);
    if (ds.mode === 'original') { this.onCurrentCard(e); return; }
    if (systemCard.buttonUrl(message, ds.index)) this._openCardOriginal(message);
    else this._openGenericCard(message, ds.index);
  },

  // ===== 准实时:页面可见时轮询新消息 =====
  onShow() {
    this._hidden = false;
    if (this.data.isMerchantView) merchantTheme.merchantPageShow();
    this._navigating = false;
    if (this.data.type == 2 && this._reportEntryEpoch) this.loadReportEntry();
    const cleared = this._pendingClearReturn;
    const reported = this._pendingReportTip;
    this._pendingClearReturn = null;
    this._pendingReportTip = null;
    if (cleared && this._isReadContextCurrent(cleared.conversationId, cleared.memberId)) {
      this._returnAfterClear(cleared.conversationId, cleared.memberId);
      return;
    }
    if (reported && this._isReadContextCurrent(reported.conversationId, reported.memberId)) {
      app.tips && app.tips(reported.text);
    }
    this._showBlockConfirmation(this._pendingBlock);
    const pendingConversationId = this._pendingReadConversationId;
    const pendingMemberId = this._pendingReadMemberId;
    this._pendingReadConversationId = 0;
    this._pendingReadMemberId = 0;
    if (pendingConversationId && this._isReadContextCurrent(pendingConversationId, pendingMemberId)) {
      this.markRead();
    }
    this.refreshRouteProjections();
    this.startPoll();
  },
  onHide() {
    if (this.data.isMerchantView) merchantTheme.merchantPageRestore();
    if (this.data.reviewResult) this.closeReviewResult();
    this._hidden = true;
    if (this.data.routePickerOpen) this.closeRoutePicker();
    this._pollRequestEpoch = (this._pollRequestEpoch || 0) + 1;
    this._pollInFlight = false;
    this.stopPoll();
  },
  onUnload() {
    if (this.data.isMerchantView) merchantTheme.merchantPageRestore();
    this._reviewDetailEpoch = (this._reviewDetailEpoch || 0) + 1;
    this._reviewMessageId = null; this._reviewRecordKey = null;
    this._disposed = true;
    this._pendingBlock = null;
    this._pendingClearReturn = null;
    this._pendingReportTip = null;
    this._hidden = true;
    this._pollWatermark = null;
    this._pollInFlight = false;
    this._pendingReadConversationId = 0;
    this._pendingReadMemberId = 0;
    this._messageRequestEpoch = (this._messageRequestEpoch || 0) + 1;
    this._loadMoreRequestEpoch = (this._loadMoreRequestEpoch || 0) + 1;
    this._pollRequestEpoch = (this._pollRequestEpoch || 0) + 1;
    this.stopPoll();
    clearTimeout(this._routeTimer);
  },
  startPoll() {
    const that = this;
    this.stopPoll();
    // 坏链接(没有 conversationId)不许每 8s 拿 conversation_id:0 打后端 ——
    // 「不打后端」这条如果只在 onLoad 成立,onShow 一来就又破了。
    if (!this.data.conversationId
        || !this._isReadContextCurrent(this.data.conversationId, this.data.myId)) return;
    this._poll = setInterval(function () { that.pollNew(); }, 8000);
  },
  stopPoll() { if (this._poll) { clearInterval(this._poll); this._poll = null; } },
  pollNew() {
    const conversationId = this.data.conversationId;
    const memberId = this.data.myId;
    if (this._hidden || this._pollInFlight || !this._isReadContextCurrent(conversationId, memberId)) return;
    if (this._pollWatermark == null) {
      this.loadMessages(false);
      return;
    }
    this.refreshRouteProjections();
    const requestEpoch = (this._pollRequestEpoch || 0) + 1;
    this._pollRequestEpoch = requestEpoch;
    this._pollInFlight = true;
    this._pollNewPage({ conversationId, memberId, requestEpoch,
      messageRequestEpoch: this._messageRequestEpoch,
      watermark: this._pollWatermark, headId: this._pollWatermark }, 0, []);
  },

  _pollNewPage(context, cursorId, accumulated) {
    const viewCurrent = this._viewGuard();
    const current = () => viewCurrent() && context.requestEpoch === this._pollRequestEpoch
      && context.messageRequestEpoch === this._messageRequestEpoch
      && !this._hidden && this._isReadContextCurrent(context.conversationId, context.memberId);
    const fail = () => {
      if (context.requestEpoch === this._pollRequestEpoch) this._pollInFlight = false;
    };
    app.sendRequest({
      url: '/api/im/messages', method: 'POST', hideLoading: true,
      data: { conversation_id: context.conversationId, cursor_id: cursorId, size: 15 },
      success: (res) => {
        if (!current()) { fail(); return; }
        if (!(res && res.code == 200 && res.data && typeof res.data === 'object' && !Array.isArray(res.data)
            && isRecordList(res.data.list))) { fail(); return; }
        const list = res.data.list;
        const ids = list.map(message => Number(message.id));
        const visibleOrdering = list.some(message => message.visibleOrderAt != null);
        const orders = list.map(messageOrder);
        if (ids.some(id => !Number.isSafeInteger(id) || id <= 0)
            || visibleOrdering && orders.some(order => typeof order !== 'object')
            || !visibleOrdering && ids.some(id => cursorId && id >= cursorId)
            || visibleOrdering && orders.some((order, index) => index > 0 && compareOrder(order, orders[index - 1]) <= 0)
            || visibleOrdering && context.cursorOrder && orders.some(order => compareOrder(order, context.cursorOrder) >= 0)) { fail(); return; }
        if (!cursorId) context.headId = orders.reduce((max, order) => compareOrder(order, max) > 0 ? order : max, context.watermark);
        const fresh = list.filter(message => compareOrder(messageOrder(message), context.watermark) > 0);
        const all = fresh.concat(accumulated);
        if (res.data.hasMore && !orders.some(order => compareOrder(order, context.watermark) <= 0)) {
          const nextCursor = Number(res.data.nextCursor);
          if (!ids.length || !Number.isSafeInteger(nextCursor) || nextCursor <= 0
              || nextCursor !== (visibleOrdering ? ids[0] : Math.min(...ids))
              || !visibleOrdering && cursorId && nextCursor >= cursorId
              || context.visitedCursors && context.visitedCursors.includes(nextCursor)) { fail(); return; }
          context.visitedCursors = (context.visitedCursors || []).concat(nextCursor);
          context.cursorOrder = visibleOrdering ? orders[0] : null;
          this._pollNewPage(context, nextCursor, all);
          return;
        }
        // Advance only after bridging to the last complete server read, never from a local send.
        this._pollInFlight = false;
        if (this.data.loadState !== 'ready') this.setData({ loadState: 'ready' });
        if (all.length) this._mergeMessages(all);
        this._pollWatermark = context.headId;
        if (all.length) {
          this.followIfNearBottom();
          this._markReadWhenVisible(context.conversationId, context.memberId);
        }
      },
      fail,
      successStatusAbnormal: fail,
    });
  },


  onBack() { wx.navigateBack(); },
});

function fmtAbs(t) {
  if (!t) return '';
  // 今天/昨天是中国自然日概念,时刻与日历字段都走中国时区,不用本地 getHours/toDateString。
  const parts = chinaParts(t);
  if (!parts) return '';
  const pad = function (n) { return n < 10 ? '0' + n : '' + n; };
  const hm = pad(parts.hours) + ':' + pad(parts.minutes);
  const nowTs = Date.now();
  const key = chinaDateKey(t);
  if (key === chinaDateKey(nowTs)) return hm;
  if (key === chinaDateKey(nowTs - 86400000)) return '昨天 ' + hm;
  return parts.month + '月' + parts.day + '日 ' + hm;
}
