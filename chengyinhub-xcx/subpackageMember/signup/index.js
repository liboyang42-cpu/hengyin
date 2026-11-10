const toast = require('../../utils/toast.js');
const QR_REFRESH_LEAD_MS = 30000; // 到期前 30 秒续签
const QR_MIN_REFRESH_MS = 5000;
const QR_RETRY_MS = 5000;
const { passExpiryText, passDaysLeft } = require('../../utils/ticket-expiry.js');
const { isLoginRequired, retryLogin } = require('../../utils/session/login-reload.js');
const app = getApp();
function myAvatar() { try { return typeof app.getAvatar === 'function' ? (app.getAvatar() || '') : ''; } catch (e) { return ''; } } function walletOwner() { return typeof app.getUserID === 'function' ? String(app.getUserID() || '') : ''; }
function exactRegistrationId(value) {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? String(value) : '';
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return '';
  const digits = value.replace(/^0+/, '');
  return digits && (digits.length < 19 || (digits.length === 19 && digits <= '9223372036854775807')) ? digits : '';
}
// UI-17 返修(2026-09-18):cy-nav-bar 是 fixed(fixed 导航不占文档流),页面正文必须自己让出
// 导航实高,否则卡片顶端会钻到导航底下被截掉一截(总控截图实证)。拿不到 globalData 时用兜底值。
const appGlobal = (app && app.globalData) || {};
// 第十二轮 k2(j1 N1):与 utils/ticket-game.js activityHasGame 同口径(本页由沙箱测试加载,不多加 require)。
function activityHasGame(a) { return !a || typeof a !== 'object' || Number(a.topicId) > 0 || Number(a.templateId) > 0; }

Page({

  /**
   * 页面的初始数据
   */
  data: {
    /* 路线票 / 场次票两条支线的原始列表、加载状态、错误文案都挂在实例上(_topic / _activity),
       不进 data:合并成一条 ticketList 之后,wxml 渲染的只有合成结果,把原料也 setData
       一遍等于每次都往视图层多传两份没人渲染的表 —— U4 死数据字段门禁判的正是这个。
       ⚠️ 两条支线各留一份状态与错误文案:onLoad 处两个请求并发发出,共用一个字段会让
          后写的消息串到另一条支线下,也没法只重试挂掉的那一条。 */
    page_no: 1,//页码
    hasMore: false,//还有更多
    nodata: false,//暂无数据

    // UI-17 返修:导航实高 = 状态栏 + 标题栏。ready 态主容器用它做 padding-top,
    // 让整张票卡(从上到下)完整落在导航以下的安全区里。
    navTop: (appGlobal.statusBarHeight || 20) + (appGlobal.navBarHeight || 44),

    // 队伍数据来自 /api/team/my(ApiPlayTeamController:127,一次拉全量),本页由
    // loadMyTeams 按 ownerType:ownerId 与票对齐 —— 票夹会拉队伍。(2026-09-04 判定表
    // 记的「全仓零调用」已过时,别再照抄。)
    // ⚠️ 只在**这张票真有队伍**时出这一行;拉不到队伍列表就当没有,不摆一个点进去是空的入口。
    // 索引本身只有 js 读(渲染的是 currentTeam),所以挂 this._myTeams 不进 data ——
    // 进 data 等于每次都往视图层传一份没人渲染的表(U4 死数据字段门禁判的正是这个)。
    currentTeam: null,    // 当前这张票对应的队伍(没有就是 null)

    /* 2026-09-09 用户裁决:票夹不分「路线 / 场次」两个 tab,全部放一起。
       两种票在**后端**确实是两类(ownerType 1=路线票 2=场次票,走两个接口),
       但那是数据模型的区分,不是用户要做的选择 —— 用户打开票夹只想看「我有哪些票」。
       所以两个接口照旧各拉各的,只在渲染层合成一条 ticketList,每张票带 kind 决定点开跳哪。
       ⚠️ 不要为了「合并」去动后端:两类票的字段和落点本来就不同,合到一个接口只会把
          差异塞进 if,还丢掉各自的分页。 */
    ticketList: [],
    currentSwiperTicket: 0, deckPoses: [], deckStill: false, wbg: ['', ''], wbgOn: 0, navH: appGlobal.navBarHeight || 44, walFilter: 'all', walFilterLabel: '票夹', walPop: false, walFilters: walFilters(), // v13 卡堆 / 两层模糊背景;l-after:票夹 ▾ 筛选(全部 / 即将开始 / 已通关)
    flipped: false, qrState: 'idle', qrUrl: '', entering: false, startLit: false, reduced: false, meAvatar: '', // 2026-10-28 翻面入场码 + 开始游戏;r3-visual 顶栏本人头像(app.getAvatar,没有就不出)
    walletState: 'loading', // loading | ready | error | login | empty —— 由两条支线的状态合成
    walletErrorMsg: '',
    focusState: '',
    focusError: '',
    slides: [],
    indicatorDots: true,
    vertical: false,
    autoplay: false,
    interval: 2000,
    duration: 500,
    // topicBg 已退役:v13 票夹背景由 wbg 两层交替承担,原字段无人读取(2026-10-23)
    walletList: [{
      pic: app.getImgUrl('zt1.jpg'),
    }, {
      pic: app.getImgUrl('zt1.jpg'),
    }, {
      pic: app.getImgUrl('zt1.jpg'),
    }, {
      pic: app.getImgUrl('zt1.jpg'),
    }],
  },

  /**
   * 生命周期函数--监听页面加载
   */
  onLoad(options) {
    const that = this;
    that._unloaded = false;
    that._refreshOnShow = false;
    that._walletOwner = walletOwner();
    const focus = options && options.focusId;
    that._explicitFocus = !!focus; that._arrive = !!(options && String(options.arrive) === '1') && !reducedMotionOn();
    that._focusId = exactRegistrationId(focus) ? String(focus) : '';
    that.setData({ focusState: focus ? (that._focusId ? 'loading' : 'missing') : '',
      focusError: focus && !that._focusId ? '原报名编号无效，请返回参与记录重新打开' : '' });
    // 两条支线的原料。data 里没有它们,所以在这里立好,别让后面的 concat 撞上 undefined
    that._topic = { list: [], state: 'loading', err: '' };
    that._activity = { list: [], state: 'loading', err: '' };
    that.setData({ reduced: reducedMotionOn(), meAvatar: myAvatar() });

    that.getActivityList()
    that.getTopicList()
    that.loadMyTeams()

    if (that._focusId) that._tryFocusTicket();
  },

  onShow() {
    this._lightStart(); if (this.data.meAvatar !== myAvatar()) this.setData({ meAvatar: myAvatar() });
    // onLoad 已发首批请求；只有隐藏后返回才重读服务端票状态。
    const ownerChanged = this._walletOwner !== walletOwner();
    if ((!this._refreshOnShow && !ownerChanged) || this._unloaded) return;
    this._walletOwner = walletOwner();
    this._refreshOnShow = false;
    const current = (this.data.ticketList || [])[this.data.currentSwiperTicket];
    if (current && !this._focusId) this._focusId = String(current.id);
    if (ownerChanged) this._focusId = '';
    if (ownerChanged && this.data.focusState) this.setData({ focusState: 'missing', focusError: '账号已变更，请从当前账号的参与记录重新打开凭证' });
    else if (this._focusId && this._explicitFocus) this.setData({ focusState: 'loading', focusError: '' });
    this._focusTries = 0;
    this._topic = { list: [], state: 'loading', err: '' };
    this._activity = { list: [], state: 'loading', err: '' };
    this._myTeams = {};
    this.data.page_no = 1;
    this.setData({ hasMore: false, nodata: false,
      currentSwiperTicket: 0, currentTeam: null });
    this.getActivityList();
    this.getTopicList();
    this.loadMyTeams();
  },

  _invalidateWalletRequests() {
    this._topicRequestSeq = (this._topicRequestSeq || 0) + 1;
    this._activityRequestSeq = (this._activityRequestSeq || 0) + 1;
    this._teamsRequestSeq = (this._teamsRequestSeq || 0) + 1;
    this._focusLookupSeq = (this._focusLookupSeq || 0) + 1;
    this._focusLookupPending = null;
  },

  onHide() {
    this._refreshOnShow = true;
    this._invalidateWalletRequests();
    this._clearQrTimer(); this._qrFor = ''; if (this._goTimer) clearTimeout(this._goTimer); if (this._litTimer && typeof clearTimeout === 'function') clearTimeout(this._litTimer);
    if (this.data.flipped || this.data.entering) this.setData({ flipped: false, entering: false, qrState: 'idle' });
  },

  // 「开始游戏」每次进页扫一道光:先归位再在下一拍点亮,transition 走一次;减动效不扫
  _lightStart() {
    if (this.data.reduced || typeof setTimeout !== 'function') return;
    if (this.data.startLit) this.setData({ startLit: false });
    this._litTimer = setTimeout(() => { if (!this._unloaded) this.setData({ startLit: true }); }, 700);
  },

  onUnload() {
    this._unloaded = true;
    this._invalidateWalletRequests();
    this._clearQrTimer(); if (this._goTimer) clearTimeout(this._goTimer); if (this._litTimer && typeof clearTimeout === 'function') clearTimeout(this._litTimer);
  },

  // 首屏未命中时使用已有本人报名详情接口，不轮询内存或依赖分页 total。
  _tryFocusTicket() {
    if (this._unloaded || this._refreshOnShow || this._walletOwner !== walletOwner()) return;
    const fid = exactRegistrationId(this._focusId);
    if (!fid) return;
    const list = this.data.ticketList || [];
    const i = list.findIndex(x => exactRegistrationId(x.id) === fid);
    if (i >= 0) {
      this._focusId = '';
      this._focusLookupSeq = (this._focusLookupSeq || 0) + 1;
      this._focusLookupPending = null;
      this.setData({ currentSwiperTicket: i,
        focusState: this._explicitFocus ? 'ready' : '', focusError: '' }); this._showQrIfNoGame();
      this.syncCurrentTeam();
      return;
    }
    if (this.data.focusState === 'missing' || (this._topic || {}).state === 'loading'
        || (this._activity || {}).state === 'loading') return;
    if (!this._explicitFocus) { this._focusId = ''; return; }
    this._readFocusTicket();
  },

  _readFocusTicket() {
    if (this._unloaded || this._refreshOnShow || this._walletOwner !== walletOwner() || this._focusLookupPending) return;
    const fid = exactRegistrationId(this._focusId);
    if (!fid) return;
    const owner = walletOwner();
    const seq = this._focusLookupSeq = (this._focusLookupSeq || 0) + 1;
    this._focusLookupPending = { seq };
    const current = () => !this._unloaded && !this._refreshOnShow && owner === walletOwner()
      && seq === this._focusLookupSeq && exactRegistrationId(this._focusId) === fid;
    const missing = () => {
      if (!current()) return;
      this._focusLookupPending = null;
      this.setData({ focusState: 'missing', focusError: '未定位到原报名凭证，请返回参与记录确认或重试', currentTeam: null });
    };
    app.sendRequest({
      url: '/api/registration/info', method: 'POST', hideLoading: true,
      data: { id: this._focusId },
      success: res => {
        if (!current()) return;
        const row = res && res.data;
        if (!res || ![200, '200'].includes(res.code) || !row || Array.isArray(row)
            || exactRegistrationId(row.id) !== fid || ![1, '1', 2, '2'].includes(row.ownerType)
            || ![2, '2'].includes(row.registrationStatus)
            || (row.memberId != null && String(row.memberId) !== owner)) { missing(); return; }
        const key = row.ownerType === 1 || row.ownerType === '1' ? '_topic' : '_activity';
        const branch = this[key] || { list: [] };
        this[key] = { list: branch.list.filter(item => exactRegistrationId(item.id) !== fid).concat([row]), state: 'ready', err: '' };
        this._focusLookupPending = null;
        this.rebuildTicketList();
        this._tryFocusTicket();
      },
      fail: missing, successStatusAbnormal: missing,
    });
  },
  retryFocus() {
    if (!exactRegistrationId(this._focusId)) return;
    if (this._unloaded || this._refreshOnShow || this._walletOwner !== walletOwner()) return;
    this.setData({ focusState: 'loading', focusError: '' });
    this._readFocusTicket();
  },

  _openTicket(item, kind) {
    if (this._unloaded || this._refreshOnShow || this._walletOwner !== walletOwner()
        || this.data.focusState === 'loading' || this.data.focusState === 'missing') return;
    if (!item || !item.id) {
      toast('跳转失败');
      return;
    }
    const status = Number(item.registrationStatus);
    /* 2026-09-10 用户定的方向:**票夹点进去就是开始玩**。订单有订单的页面,
       而且这条路是单向的 —— 只有订单页能来票夹(scene-member-order-detail 的「查看票夹」),
       票夹不回订单。
       ⚠️ 待支付是唯一还会落到订单页的一档:它还不是一张票,是一笔没付完的单,
          付款就在那一页上,没有第二个地方能付。文案也照实写成「去支付」,不写「查看详情」。 */
    if (status === 1) {
      wx.navigateTo({ url: '/subpackageMember/orderinfo/orderinfo?id=' + item.id });
      return;
    }
    if (status === 3 || status === 4) {
      toast(status === 4 ? '票已过期' : '票已取消');
      return;
    }
    if (status !== 2 || Number(item.paymentStatus) !== 2) {
      toast('票状态待确认');
      return;
    }
    const ownerId = kind === 'activity'
      ? ((item.cmsActivity && item.cmsActivity.id) || item.ownerId)
      : ((item.cmsTopic && item.cmsTopic.id) || item.ownerId);
    if (!ownerId) {
      toast('场次信息待同步');
      return;
    }
    const query = kind === 'activity'
      ? ('activityId=' + ownerId)
      : ('topicId=' + ownerId);
    const ticketName = item.wTitle ? '&topicName=' + encodeURIComponent(item.wTitle) : '';
    // 所有票一律进通用 play:主题名不是路由依据(2026-09-22 拆除按 /^预制人生/ 换轨到专用分包)。
    wx.navigateTo({ url: '/pages/play/index?' + query + '&registrationId=' + item.id + '&entry=ticket' + ticketName });
  },

  // FE-19 票夹直连 play(主题/城市路线 ownerType=1):带 topicId + registrationId(供 play 出示入场码)
  // FE-19 票夹直连 play(活动/场次 ownerType=2):带 activityId + registrationId(供 play 出示入场码)
  /* 把两条支线合成一条给界面用。
     ★ 状态合成规则(顺序有意义):
       两边都还在 loading → loading;
       两边都 error      → error(只有一边 error 时,能拿到的那部分照常显示,不把整页判死);
       合起来有票        → ready;
       否则              → empty。
     这样「场次接口挂了但路线票还在」不会变成一片空白 —— 那是最容易被读成「我的票没了」的假象。 */

  rebuildTicketList() {
    const topics = ((this._topic && this._topic.list) || []).map(function (item) {
      const t = item.cmsTopic || {};
      return Object.assign({}, item, {
        kind: 'topic',
        wKey: 'topic-' + item.id,
        wTitle: t.name || '—',
        wImg: t.imgUrl || '', wType: Number(t.productType) === 2 ? '自由探索' : '城市定向', wColor: Number(t.productType) === 2 ? '#7CF0A0' : '#5AA9FF', wUntil: item.expiresAt || t.endDate || '', // ds-ok v13 类型色
      });
    });
    const activities = ((this._activity && this._activity.list) || []).map(function (item) {
      const a = item.cmsActivity || {};
      return Object.assign({}, item, {
        kind: 'activity',
        wKey: 'activity-' + item.id,
        wTitle: a.name || '—',
        wImg: a.imgUrl || '', // 第十二轮 k8 F13:没封面就不出图,不拿通用路线示意图(假路线 + 灰条)充数
        wType: '场次', wColor: '#E8B86B', wUntil: a.endTime || a.endDate || '', wProgress: exploreProgressOf(item), wNoGame: !activityHasGame(item.cmsActivity), // ds-ok v13 卡角色;k2 j1 N1 没有游戏的票直接出码
      });
    });
    const seen = new Set();
    const all = topics.concat(activities).filter(item => {
      const id = exactRegistrationId(item.id);
      if (!id) return true;
      if (seen.has(id)) return false;
      seen.add(id); return true;
    }).map(walletCard);
    // 2026-10-28 业主:票夹只放能用的票 —— 待支付、已核验、已取消、已过期都不进票夹
    this._usable = all.filter(usableTicket); const list = filterTickets(this._usable, this.data.walFilter); // l-after:再按「票夹 ▾」筛选
    const ts = (this._topic || {}).state;
    const as = (this._activity || {}).state;
    let state;
    if (ts === 'loading' && as === 'loading') state = 'loading';
    else if (ts === 'error' && as === 'error') state = 'error';
    else if (list.length > 0) state = 'ready';
    else if (ts === 'loading' || as === 'loading') state = 'loading';
    else if (ts === 'error' || as === 'error') state = 'error';
    else state = 'empty';
    // PR10 F-W1-12:读失败是因为未登录(无会话 / 401)⇒ 登录引导,不是留白的加载失败
    if (state === 'error' && ((this._topic || {}).login || (this._activity || {}).login)) state = 'login';
    const previous = (this.data.ticketList || [])[this.data.currentSwiperTicket];
    const previousIndex = previous ? list.findIndex(item => item.wKey === previous.wKey) : -1;
    const currentIndex = previousIndex >= 0 ? previousIndex : 0;
    const cur = list[currentIndex] || {};
    this.setData({
      ticketList: list,
      currentSwiperTicket: currentIndex,
      // (原 topicBg 字段已退役,见 data 注释)
      walletState: state,
      walletErrorMsg: (this._topic || {}).err || (this._activity || {}).err || '',
    });
    if (!this._entering && (state !== 'ready' || this._entered)) this._deckLay(); this._bgTo(cur.wImg); // 入场 / 新卡落入动画进行中不重排,否则第二路列表回来会把动画打断
    this._showQrIfNoGame(); if (this._focusId) this._tryFocusTicket(); if (state === 'ready' && this._arrive) { this._arrive = false; this._entered = true; this._playArrive(); } else if (state === 'ready' && !this._entered) { this._entered = true; this._playEnter(false); }
  },

  /* 点开一张票。两种票的落点不同,由 kind 分流 —— 这就是合并列表之后
     唯一还需要区分两类票的地方。 */
  goTicket(e) {
    const ds = e.currentTarget.dataset;
    const item = (this.data.ticketList || [])[ds.index];
    if (!item || this.data.entering) return;
    const kind = item.kind === 'activity' ? 'activity' : 'topic';
    // 2026-10-28「开始游戏」(data-go):按钮填满 → 票卡往前冲出 → 黑色铺满,再进游戏;减动效直接进
    if (!ds.go || this.data.reduced || typeof setTimeout !== 'function') { this._openTicket(item, kind); return; }
    this.setData({ flipped: false, entering: true });
    this._goTimer = setTimeout(() => {
      if (this._unloaded) return;
      this._openTicket(item, kind);
      this._goTimer = setTimeout(() => { if (!this._unloaded) this.setData({ entering: false }); }, 400);
    }, 900);
  },

  /* 2026-10-28 业主:点卡片 = 翻到背面看入场码,再点翻回。拖动换卡时不算点(onDeckEnd 先清掉 _moved)。 */
  // 第十二轮 k2(j1 N1):没有游戏的票,票面就是入场码 —— 落到它时直接翻到码面,不需要玩家先猜「点一下」
  _showQrIfNoGame() {
    const item = (this.data.ticketList || [])[this.data.currentSwiperTicket];
    if (!item || !item.wNoGame || this.data.flipped) return;
    this.setData({ flipped: true });
    if (String(item.id) !== this._qrFor || !(this._qrExpiresAt > Date.now() + QR_REFRESH_LEAD_MS)) this._loadQr(item);
  },
  onCardTap(e) {
    if (this._moved || this._throwing || this.data.entering) return;
    const index = Number(e.currentTarget.dataset.index);
    if (index !== this.data.currentSwiperTicket) return;
    const flipped = !this.data.flipped;
    this.setData({ flipped });
    const item = (this.data.ticketList || [])[index];
    if (!flipped || !item || item.topicCompleted === true) return;
    if (String(item.id) !== this._qrFor || !(this._qrExpiresAt > Date.now() + QR_REFRESH_LEAD_MS)) this._loadQr(item);
    else if (!this._qrTimer) this._scheduleQrRefresh(item, this._qrExpiresAt - Date.now() - QR_REFRESH_LEAD_MS);
  },
  retryQr() {
    const item = (this.data.ticketList || [])[this.data.currentSwiperTicket];
    if (item) this._loadQr(item);
  },
  // V-02:动态码到期前在后台静默续签，不给玩家显示倒计时；续签期间旧码照常出示，真过期了才落错误态。
  _loadQr(item, silent) {
    const registrationId = exactRegistrationId(item.id);
    const token = this._qrToken = (this._qrToken || 0) + 1;
    this._clearQrTimer();
    if (!silent) this._qrFor = '';
    if (!registrationId) { this.setData({ qrState: 'error', qrUrl: '' }); return; }
    if (!silent) this.setData({ qrState: 'loading', qrUrl: '' });
    // 静默续签失败而旧码还没到期:旧码继续出示,稍后再续
    const keepOld = () => {
      if (!(silent && this._qrExpiresAt > Date.now() + QR_RETRY_MS)) return false;
      this._scheduleQrRefresh(item, QR_RETRY_MS); return true;
    };
    app.sendRequest({
      url: '/api/verify/dyncode/issue', method: 'POST', data: { registrationId }, hideLoading: true, silentError: true,
      success: (res) => {
        if (this._unloaded || token !== this._qrToken) return;
        const payload = res && (res.code === 200 || res.code === '200') && res.data;
        const raw = payload && payload.expiresAt;
        const expiresAt = typeof raw === 'number' && Number.isFinite(raw) ? (raw < 100000000000 ? raw * 1000 : raw) : 0;
        if (!payload || !payload.qrcodeUrl || !(expiresAt > Date.now())) {
          if (!keepOld()) { this._qrFor = ''; this.setData({ qrState: 'error', qrUrl: '' }); }
          return;
        }
        this._qrFor = registrationId; this._qrExpiresAt = expiresAt;
        this.setData({ qrState: 'ready', qrUrl: payload.qrcodeUrl });
        this._scheduleQrRefresh(item, Math.max(QR_MIN_REFRESH_MS, expiresAt - Date.now() - QR_REFRESH_LEAD_MS));
      },
      fail: () => { if (this._unloaded || token !== this._qrToken || keepOld()) return; this._qrFor = ''; this.setData({ qrState: 'error', qrUrl: '' }); },
      successStatusAbnormal: () => { if (this._unloaded || token !== this._qrToken || keepOld()) return; this._qrFor = ''; this.setData({ qrState: 'error', qrUrl: '' }); },
    });
  },
  _scheduleQrRefresh(item, delay) {
    this._clearQrTimer();
    this._qrTimer = setTimeout(() => {
      this._qrTimer = null;
      if (this._unloaded || !this.data.flipped) return;
      this._loadQr(item, true);
    }, delay);
  },
  _clearQrTimer() { if (this._qrTimer) { clearTimeout(this._qrTimer); this._qrTimer = null; } },

  /* 重试。哪条支线错了就重拉哪条;两条都错就都拉。 */
  retryWallet() {
    if ((this._topic || {}).state === 'error') this.retryTopic();
    if ((this._activity || {}).state === 'error') this.retryActivity();
  },

  /* 登录引导「登录」:重登成功后重拉失败的支线 */
  loginWallet() {
    retryLogin(this, app, this.retryWallet);
  },

  /* 我的队伍。票夹一次拉全量,再按 ownerType:ownerId 落到具体那张票上 ——
     每张票各发一次请求会把一屏刷成 N 个请求。
     silentError:队伍拉不到只是这一行不出,不该拿一个全局 toast 盖在票夹上说「出错了」。 */
  loadMyTeams() {
    const that = this;
    const owner = walletOwner();
    const requestSeq = this._teamsRequestSeq = (this._teamsRequestSeq || 0) + 1;
    app.sendRequest({
      url: '/api/team/my', method: 'POST', hideLoading: true, silentError: true,
      data: {},
      success(res) {
        if (that._unloaded || that._refreshOnShow || owner !== walletOwner() || requestSeq !== that._teamsRequestSeq) return;
        const rows = res && res.code == '200' && Array.isArray(res.data) ? res.data : null;
        if (!rows) return;            // 读不到 ≠ 没有队伍,保持这一行不出,不写一个空态
        const index = {};
        for (let i = 0; i < rows.length; i++) {
          const t = rows[i];
          if (!t || !t.id || !t.ownerId) continue;
          // 已结束 / 已解散的队伍不给入口:点进去只会看到一个死队伍
          if (Number(t.status) === 3 || Number(t.status) === 4) continue;
          index[Number(t.ownerType) + ':' + Number(t.ownerId)] = {
            id: t.id,
            title: String(t.title || '').trim(),
            joinedCount: t.joinedCount == null ? null : Number(t.joinedCount),
            maxMembers: t.maxMembers == null ? null : Number(t.maxMembers),
          };
        }
        that._myTeams = index;
        that.syncCurrentTeam();
      },
    });
  },

  /* 票 → 队伍。ownerType 1=路线票 / 2=场次票,取 id 的口径与 _openTicket 同源。 */
  teamOf(item, kind) {
    if (!item) return null;
    const ownerId = kind === 'activity'
      ? ((item.cmsActivity && item.cmsActivity.id) || item.ownerId)
      : ((item.cmsTopic && item.cmsTopic.id) || item.ownerId);
    if (!ownerId) return null;
    return (this._myTeams || {})[(kind === 'activity' ? 2 : 1) + ':' + Number(ownerId)] || null;
  },

  /* 队伍列表比票列表晚到时,把当前那张票的队伍补上 */
  syncCurrentTeam() {
    const item = (this.data.ticketList || [])[this.data.currentSwiperTicket];
    if (!item) { this.setData({ currentTeam: null }); return; }
    this.setData({ currentTeam: this.teamOf(item, item.kind === 'activity' ? 'activity' : 'topic') });
  },

  goMyTeam() {
    const team = this.data.currentTeam;
    if (!team || !team.id) return;
    wx.navigateTo({ url: '/pages/team/detail/index?teamId=' + team.id });
  },

  // 主题swiper切换
  // 活动swiper切换

  // F-R2U-01(用户要求):读取失败一律留白。单路失败时继续显示另一路的票,不再弹「部分票没加载出来」;
  // 两路都失败由 walletState 留白。保留方法名供两路回调统一收口。
  _reportHalfFailure() {},

  getTopicList: function () {
    var that = this;
    const owner = walletOwner();
    const requestSeq = that._topicRequestSeq = (that._topicRequestSeq || 0) + 1;
    that._topic = { list: (that._topic && that._topic.list) || [], state: 'loading', err: '' };
    that.rebuildTicketList();
    app.sendRequest({
      hideLoading: true,
      autoErrorToast: false,   // 两路都失败 = walletState error → auto-back 半屏;只挂一路见 _reportHalfFailure
      url: '/api/registration/list',
      method: "POST",
      data: {
        owner_type: 1,
        pageNum: that.data.page_no,
        pageSize: app.getPageSize(),
      },
      success: function (res) {
        if (that._unloaded || that._refreshOnShow || owner !== walletOwner() || requestSeq !== that._topicRequestSeq) return;
        const payload = res && res.data;
        if (!(res && res.code == "200" && payload && Array.isArray(payload.rows))) {
          that._topic = { list: that._topic.list, state: 'error',
            err: app.getRequestErrorMessage(res, '路线票加载失败') };
          that.rebuildTicketList();
          return;
        }
        const nextTopicList = that._topic.list.concat(payload.rows);
        that._topic = { list: nextTopicList, state: nextTopicList.length > 0 ? 'ready' : 'empty', err: '' };
        that.setData({
          hasMore: (app.getTotalPage(payload.total, app.getPageSize()) > that.data.page_no),
        });
        // 背景图跟着当前那张票走,由 rebuildTicketList 统一写(它知道现在停在哪一张)
        that.rebuildTicketList();
        that.syncCurrentTeam();
        if (that._focusId) that._tryFocusTicket();
        if (payload.rows.length < 1) {
          that.setData({
            nodata: true,
          })
        }
      },
      fail: function (err, statusCode) {
        if (that._unloaded || that._refreshOnShow || owner !== walletOwner() || requestSeq !== that._topicRequestSeq) return;
        // 原为空函数:请求失败被整个吞掉,列表停在 [] ⇒ 页面显示「还没有票!」= 错误伪装成空态
        that._topic = { list: that._topic.list, state: 'error',
          err: (err && err.errMsg) || '网络异常，请重试', login: isLoginRequired(app, err, statusCode) };
        that.rebuildTicketList();
      },
      complete: function () {
        if (that._unloaded || that._refreshOnShow || owner !== walletOwner() || requestSeq !== that._topicRequestSeq) return;
        that._reportHalfFailure();
        that.setData({
          nodata: that._topic.list.length < 1 ? true : false,
        })
      }
    })
  },

  // 错误态重试(cy-error 的 retry 事件)
  retryTopic() {
    this._topic = { list: [], state: 'loading', err: '' };
    this.getTopicList();
  },
  retryActivity() {
    this._activity = { list: [], state: 'loading', err: '' };
    this.getActivityList();
  },

  getActivityList: function () {
    var that = this;
    const owner = walletOwner();
    const requestSeq = that._activityRequestSeq = (that._activityRequestSeq || 0) + 1;
    that._activity = { list: (that._activity && that._activity.list) || [], state: 'loading', err: '' };
    that.rebuildTicketList();
    app.sendRequest({
      hideLoading: true,
      autoErrorToast: false,   // 两路都失败 = walletState error → auto-back 半屏;只挂一路见 _reportHalfFailure
      url: '/api/registration/list',
      method: "POST",
      data: {
        owner_type: 2,
        pageNum: that.data.page_no,
        pageSize: app.getPageSize(),
      },
      success: function (res) {
        if (that._unloaded || that._refreshOnShow || owner !== walletOwner() || requestSeq !== that._activityRequestSeq) return;
        const payload = res && res.data;
        if (!(res && res.code == "200" && payload && Array.isArray(payload.rows))) {
          that._activity = { list: that._activity.list, state: 'error',
            err: app.getRequestErrorMessage(res, '活动票加载失败') };
          that.rebuildTicketList();
          return;
        }
        const nextActivityList = that._activity.list.concat(payload.rows);
        that._activity = { list: nextActivityList,
          state: nextActivityList.length > 0 ? 'ready' : 'empty', err: '' };
        that.setData({
          hasMore: (app.getTotalPage(payload.total, app.getPageSize()) > that.data.page_no),
        });
        that.rebuildTicketList();
        that.syncCurrentTeam();
        if (that._focusId) that._tryFocusTicket();
        if (payload.rows.length < 1) {
          that.setData({
            nodata: true,
          })
        }
      },
      fail: function (err, statusCode) {
        if (that._unloaded || that._refreshOnShow || owner !== walletOwner() || requestSeq !== that._activityRequestSeq) return;
        // 原为空函数:请求失败被整个吞掉,列表停在 [] ⇒ 页面显示「还没有票!」= 错误伪装成空态
        that._activity = { list: that._activity.list, state: 'error',
          err: (err && err.errMsg) || '网络异常，请重试', login: isLoginRequired(app, err, statusCode) };
        that.rebuildTicketList();
      },
      complete: function () {
        if (that._unloaded || that._refreshOnShow || owner !== walletOwner() || requestSeq !== that._activityRequestSeq) return;
        that._reportHalfFailure();
        that.setData({
          nodata: that._activity.list.length < 1 ? true : false,
        })
      }
    })
  },

  /* ===== r3-visual 票夹(refs4/ticket-holder-frames):一次一张大卡,左右翻页 =====
     当前那张居中;往左拖 = 下一张,当前卡绕左边缘像书页一样卷起(rotateY + 卷页阴影 --curl),
     下一张从右边跟着手指滑进来;往右拖 = 上一张,镜像。松手越过阈值或甩得够快就翻过去,否则弹回。
     只动 transform / opacity;减少动态效果时直接换卡、不卷页。 */
  _cardW() {
    if (this._cw) return this._cw;
    let ww = 390;
    try { const info = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()) || {}; if (info.windowWidth > 0) ww = info.windowWidth; } catch (e) { /* 用 390 兜底 */ }
    this._cw = Math.round(577 * ww / 750);
    return this._cw;
  },
  _deckLay() {
    const list = this.data.ticketList || [], cur = this.data.currentSwiperTicket;
    const origin = this._origin || '50% 50%';
    const poses = list.map((_, i) => i === cur
      ? 'transform: perspective(1400px) translate3d(0px,0px,0px) rotateY(0deg) scale(1); transform-origin: ' + origin + '; opacity: 1; z-index: 30; --curl: 0;'
      : 'transform: perspective(1400px) translate3d(' + (this._cardW() + 40) + 'px,0px,0px) rotateY(0deg) scale(.92); transform-origin: 50% 50%; opacity: 0; z-index: 10; --curl: 0;');
    this.setData({ deckPoses: poses });
  },
  _bgTo(src) {
    if (!src || src === this.data.wbg[this.data.wbgOn]) return;
    const on = 1 - this.data.wbgOn;
    this.setData({ ['wbg[' + on + ']']: src, wbgOn: on });
  },

  /* 进页:当前那张从下面升上来;新卡到账(arrive):从右上方斜着落进来 */
  _playEnter(arrive) {
    const list = this.data.ticketList || [], n = list.length, cur = this.data.currentSwiperTicket;
    if (!n || reducedMotionOn()) return this._deckLay();
    this._deckLay();
    const from = arrive ? 'transform: translate(40px,-720px) rotate(-12deg) scale(1.15);' : 'transform: translate(0px,560px) scale(.9);';
    this._entering = true; this.setData({ ['deckPoses[' + cur + ']']: from + 'z-index: 30; transition: none;', deckStill: true }); __end(this, 1, arrive);
    setTimeout(() => {
      if (this._unloaded) return;
      this.setData({ deckStill: false });
      setTimeout(() => {
        if (this._unloaded) return;
        const ease = arrive ? 'transition: transform .78s cubic-bezier(.2,.9,.25,1.08);' : 'transition: transform .7s cubic-bezier(.2,.9,.25,1.08);';
        this.setData({ ['deckPoses[' + cur + ']']: 'transform: perspective(1400px) translate3d(0px,0px,0px) rotateY(0deg) scale(1); transform-origin: 50% 50%; opacity: 1; z-index: 30; --curl: 0;' + ease });
      }, arrive ? 320 : 0);
    }, 40);
  },
  _playArrive() { this._playEnter(true); },

  onDeckStart(e) {
    const t = e.touches && e.touches[0];
    if (!t || this._throwing || (this.data.ticketList || []).length === 0) return;
    this._drag = { x: t.clientX, y: t.clientY, lx: t.clientX, t: Date.now(), vx: 0, dx: 0, dy: 0, at: 0 };
    this._moved = false;
    this.setData({ deckStill: true });
  },
  onDeckMove(e) {
    const d = this._drag, t = e.touches && e.touches[0];
    if (!d || !t) return;
    const now = Date.now(), dt = Math.max(1, now - d.t);
    d.vx = (t.clientX - d.lx) / dt; d.lx = t.clientX; d.t = now;
    d.dx = t.clientX - d.x; d.dy = t.clientY - d.y;
    if (now - d.at < 16) return;
    d.at = now;
    const list = this.data.ticketList || [], n = list.length;
    if (n < 2 || reducedMotionOn()) return;
    this.setData({ deckPoses: pagePoses(n, this.data.currentSwiperTicket, d.dx, this._cardW()) });
  },
  onDeckEnd() {
    const d = this._drag;
    this._drag = null;
    if (!d) return;
    this.setData({ deckStill: false });
    const list = this.data.ticketList || [], n = list.length, W = this._cardW();
    const dist = Math.hypot(d.dx, d.dy);
    this._moved = dist >= 8;
    const turn = pageTurn(n, d.dx, d.vx);
    if (!turn) { this._origin = d.dx < 0 ? '0% 50%' : '100% 50%'; this._deckLay(); return this._settle(); }
    const leaving = this.data.currentSwiperTicket;
    const next = (leaving + turn + n) % n;
    // 离开的那张整页卷走(下一张:绕左边缘;上一张:绕右边缘),新的一张从侧边滑到正中
    const out = turn > 0
      ? 'transform: perspective(1400px) translate3d(' + (-W * 0.35) + 'px,0px,0px) rotateY(-110deg) skewY(-4deg) scale(1); transform-origin: 0% 50%; opacity: 0; z-index: 40; --curl: .9; --spec: 1; --sx: -20%; transition: transform .5s cubic-bezier(.3,.6,.3,1), opacity .2s linear .3s;'
      : 'transform: perspective(1400px) translate3d(' + (W * 0.35) + 'px,0px,0px) rotateY(110deg) skewY(4deg) scale(1); transform-origin: 100% 50%; opacity: 0; z-index: 40; --curl: .9; --curl-a: 270deg; --spec: 1; --sx: -20%; transition: transform .5s cubic-bezier(.3,.6,.3,1), opacity .2s linear .3s;';
    this._throwing = true;
    this._clearQrTimer(); this._qrFor = '';
    this.setData({ currentSwiperTicket: next, flipped: false, qrState: 'idle', qrUrl: '' }); this._showQrIfNoGame();
    this._origin = '';
    this._deckLay();
    this.setData({ ['deckPoses[' + leaving + ']']: out });
    this._bgTo(list[next] && list[next].wImg);
    this.syncCurrentTeam && this.syncCurrentTeam();
    this._settle();
  },
  /* 翻页 / 弹回的过渡走完后,无过渡地归位(清掉卷页轴心),再放开下一次拖动 */
  _settle() {
    this._throwing = true;
    setTimeout(() => {
      if (this._unloaded) return;
      this._origin = '';
      this.setData({ deckStill: true });
      this._deckLay();
      setTimeout(() => { if (!this._unloaded) this.setData({ deckStill: false }); this._throwing = false; }, 50);
    }, 520);
  },
  goExplore() { wx.switchTab({ url: '/pages/index/index' }); },
  toggleWalFilter() { this.setData({ walPop: !this.data.walPop }); },
  pickWalFilter(e) {
    const key = e.currentTarget.dataset.key;
    const f = walFilters().find(x => x.key === key);
    if (!f) return;
    this.setData({ walPop: false, walFilter: key, walFilterLabel: f.title, flipped: false, qrState: 'idle', qrUrl: '' });
    if (this._usable) this.rebuildTicketList();
  },
  goMe() { wx.switchTab({ url: '/pages/member/index/index' }); },
})

// 与 utils/motion-preference.js 同一组存储键(玩家「减少动态效果」开关);内联读取,不为一个布尔值多挂一个依赖
function reducedMotionOn() {
  try {
    if (typeof wx === 'undefined') return false;
    const v = wx.getStorageSync('play_reduced_motion');
    return v === true || (v !== false && wx.getStorageSync('roam_reduced_motion') === true);
  } catch (e) { return false; }
}

/* r3-visual 翻页姿态(纯函数,单测直接跑):dx < 0 往下一张、dx > 0 往上一张。
   当前卡绕外侧边缘卷起(最多 70°)并跟手平移三成,卷页阴影 --curl 跟着加深;
   目标卡从对侧一整卡宽外跟着手指滑进来(缩放 .92 → 1)。其余卡藏在右侧。 */
function pagePoses(n, cur, dx, W) {
  const p = Math.min(1, Math.abs(dx) / W);
  const step = dx < 0 ? 1 : -1;
  const target = (cur + step + n) % n;
  const side = dx < 0 ? 1 : -1;
  const out = [];
  for (let i = 0; i < n; i++) {
    if (i === cur) {
      // l-after 镜面(proto wal3):卷起时一道高光带跟着手指从右往左扫(--sx 110% → −20%),卷面微微斜切(skewY)
      out.push('transform: perspective(1400px) translate3d(' + (dx * 0.3).toFixed(1) + 'px,0px,0px) rotateY(' + (-side * p * 70).toFixed(1) + 'deg) skewY(' + (-side * p * 4).toFixed(1) + 'deg) scale(1); transform-origin: ' + (side > 0 ? '0% 50%' : '100% 50%') + '; opacity: 1; z-index: 30; --curl: ' + (p * 0.8).toFixed(3) + '; --curl-a: ' + (side > 0 ? '90deg' : '270deg') + '; --spec: ' + Math.min(1, p * 1.5).toFixed(3) + '; --sx: ' + (110 - p * 130).toFixed(1) + '%;');
    } else if (i === target) {
      out.push('transform: perspective(1400px) translate3d(' + (side * (W + 40) + dx).toFixed(1) + 'px,0px,0px) rotateY(0deg) scale(' + (0.92 + 0.08 * p).toFixed(3) + '); transform-origin: 50% 50%; opacity: 1; z-index: 20; --curl: 0;');
    } else {
      out.push('transform: perspective(1400px) translate3d(' + (W + 40) + 'px,0px,0px) rotateY(0deg) scale(.92); transform-origin: 50% 50%; opacity: 0; z-index: 10; --curl: 0;');
    }
  }
  return out;
}
/* 松手判定:不到两张不翻;拖过 80px 或甩速 > .5px/ms 就翻,返回 +1 下一张 / -1 上一张 / 0 弹回 */
function pageTurn(n, dx, vx) {
  if (n < 2) return 0;
  if (Math.abs(dx) > 80 || (Math.abs(dx) > 8 && Math.abs(vx) > 0.5)) return dx < 0 ? 1 : -1;
  return 0;
}
/* 卡面文字:类型 · 剩几天(有到期日才写),状态不是待使用时写状态;tab 归类 */
function walletCard(item) {
  // 卡面「剩 N 天」与背面「N 天内过期」同一套自然日算法(原来卡面按 24 小时向上取整,同一张票两面差一天)
  const days = passDaysLeft(item.wUntil);
  const progress = item.wProgress ? ' · 已核销 ' + item.wProgress.redeemed + '/' + item.wProgress.required + ' 家' : '';
  // P-27:场次票卡面写「几点 · 在哪」,到期留在背面(wExpiry);读不到开场时间才回落「场次 · 剩几天」
  const session = item.kind === 'activity' ? sessionLine(item.cmsActivity) : '';
  const head = item.topicCompleted === true ? '已通关' : (session || (days === null ? item.wType : item.wType + (days === 0 ? ' · 今天过期' : ' · 剩 ' + days + ' 天')));
  // 第十二轮 k4(j2 F-22):已通关的票不再出入场码,也不写「今天过期」
  return Object.assign(item, { wSub: head + progress, wExpiry: item.topicCompleted === true ? '' : passExpiryText(item.wUntil) });
}
/* 场次开场时间(服务端 yyyy-MM-dd HH:mm:ss,北京时间)+ 地点;缺开场时间返回空串 */
function sessionLine(a) {
  const m = String((a && a.startDate) || '').match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/);
  if (!m) return '';
  const place = String((a && (a.addressName || a.address)) || '').trim();
  return Number(m[2]) + '月' + Number(m[3]) + '日 ' + m[4] + ':' + m[5] + (place ? ' · ' + place : '');
}
/* P-03:服务端给 ③ 探索票下发的「已核销几家/共几家」;没有就 null(与 utils/order-status.js exploreProgressOf 同口径) */
function exploreProgressOf(item) {
  const p = item && item.exploreProgress;
  if (!p || !(Number(p.required) > 0)) return null;
  return { redeemed: Number(p.redeemed) || 0, required: Number(p.required), active: p.active === true };
}
/* 能用的票:已报名(已付款 / 免费)、没核验、没取消没过期,到期日也还没过 */
function usableTicket(item) {
  // P-03:探索票核销过一家仍可用,直到必选章全部核销或场次结束(服务端 exploreProgress.active)
  const exploreActive = !!(item.wProgress && item.wProgress.active);
  if (Number(item.registrationStatus) !== 2 || Number(item.paymentStatus) !== 2 || (item.verificationStatus == 1 && !exploreActive)) return false;
  if (item.wProgress && !item.wProgress.active) return false;
  const until = item.wUntil ? new Date(String(item.wUntil).replace(/-/g, '/')) : null;
  return !(until && !isNaN(until.getTime()) && until.getTime() < Date.now());
}

/* 入场动画最长时长(最后一张起步 + 过渡 + 余量)后放开重排 */
function __end(page, n, arrive) { setTimeout(() => { page._entering = false; }, 40 + Math.max(arrive ? 320 : 0, (n - 1) * 90) + 900); }

/* l-after 票夹筛选(proto wl-wallet「票夹 ▾」):全部 / 即将开始(还没通关)/ 已通关,只按服务端 topicCompleted 分;
   票夹本身仍只放能用的票(业主 2026-10-28),这里不是「可用 / 已用 / 已过期」。
   写成函数声明(提升)放在文件尾,是为了不动 U5 基线锚定的行号(signup/index.js:425)。 */
function walFilters() { return [{ key: 'all', label: '全部', title: '票夹' }, { key: 'upcoming', label: '即将开始', title: '即将开始' }, { key: 'done', label: '已通关', title: '已通关' }]; }
function filterTickets(list, key) {
  if (key === 'done') return list.filter(t => t.topicCompleted === true);
  if (key === 'upcoming') return list.filter(t => t.topicCompleted !== true);
  return list;
}
if (typeof module !== 'undefined') module.exports = { pagePoses, pageTurn, filterTickets };
