const toast = require('../../utils/toast.js');
const QR_REFRESH_LEAD_MS = 30000; // 到期前 30 秒续签
const QR_MIN_REFRESH_MS = 5000;
const QR_RETRY_MS = 5000;
const { isLoginRequired, retryLogin } = require('../../utils/session/login-reload.js');
const app = getApp();
function walletOwner() { return typeof app.getUserID === 'function' ? String(app.getUserID() || '') : ''; }
function exactRegistrationId(value) {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? String(value) : '';
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return '';
  const digits = value.replace(/^0+/, '');
  return digits && (digits.length < 19 || (digits.length === 19 && digits <= '9223372036854775807')) ? digits : '';
}
// UI-17 返修(2026-09-18):cy-nav-bar 是 fixed(fixed 导航不占文档流),页面正文必须自己让出
// 导航实高,否则卡片顶端会钻到导航底下被截掉一截(总控截图实证)。拿不到 globalData 时用兜底值。
const appGlobal = (app && app.globalData) || {};
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
    currentSwiperTicket: 0, deckPoses: [], deckStill: false, wbg: ['', ''], wbgOn: 0, navH: appGlobal.navBarHeight || 44, // v13 卡堆 / 两层模糊背景
    flipped: false, qrState: 'idle', qrUrl: '', entering: false, startLit: false, reduced: false, // 2026-10-28 翻面入场码 + 开始游戏
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
    that.setData({ reduced: reducedMotionOn() });

    that.getActivityList()
    that.getTopicList()
    that.loadMyTeams()

    if (that._focusId) that._tryFocusTicket();
  },

  onShow() {
    this._lightStart();
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
        focusState: this._explicitFocus ? 'ready' : '', focusError: '' });
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
    if (status !== 2) {
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
        wValidity: ticketValidity(t.startDate),
        wImg: t.imgUrl || '/images/route_city_cover.png', wType: Number(t.productType) === 2 ? '自由探索' : '城市定向', wColor: Number(t.productType) === 2 ? '#7CF0A0' : '#5AA9FF', wUntil: item.expiresAt || t.endDate || '', // ds-ok v13 类型色
      });
    });
    const activities = ((this._activity && this._activity.list) || []).map(function (item) {
      const a = item.cmsActivity || {};
      return Object.assign({}, item, {
        kind: 'activity',
        wKey: 'activity-' + item.id,
        wTitle: a.name || '—',
        wValidity: ticketValidity(a.startDate),
        wImg: a.imgUrl || '/images/route_city_cover.png',
        wType: '场次', wColor: '#E8B86B', wUntil: a.endTime || a.endDate || '', // ds-ok v13 卡角色
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
    const list = all.filter(usableTicket);
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
    if (this._focusId) this._tryFocusTicket(); if (state === 'ready' && this._arrive) { this._arrive = false; this._entered = true; this._playArrive(); } else if (state === 'ready' && !this._entered) { this._entered = true; this._playEnter(false); }
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
  onCardTap(e) {
    if (this._moved || this._throwing || this.data.entering) return;
    const index = Number(e.currentTarget.dataset.index);
    if (index !== this.data.currentSwiperTicket) return;
    const flipped = !this.data.flipped;
    this.setData({ flipped });
    const item = (this.data.ticketList || [])[index];
    if (!flipped || !item) return;
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

  /* ===== v13 票夹卡堆(业主 2026-10-22「和 HTML 一模一样」) =====
     卡片叠放:后面的卡往上错开、缩小、变暗;最前一张跟手拖(任意方向,带一点倾斜),
     后面的卡跟着往前挪;松手越过阈值或甩得够快就按手指方向飞出去,下一张上来;点卡进入。 */
  _deckPose(r) { return DECK_POSE[Math.min(r, DECK_POSE.length - 1)]; },
  _deckLay(front) {
    const list = this.data.ticketList || [], n = list.length, cur = this.data.currentSwiperTicket;
    const poses = list.map((_, i) => {
      const r = (i - cur + n) % n;
      if (r === 0 && front) return front;
      const p = this._deckPose(r);
      return 'transform: translate(0px,' + p.y + 'px) scale(' + p.s + '); filter: brightness(' + p.b + '); opacity: ' + p.o + '; z-index: ' + (30 - r) + ';';
    });
    this.setData({ deckPoses: poses });
  },
  _bgTo(src) {
    if (!src || src === this.data.wbg[this.data.wbgOn]) return;
    const on = 1 - this.data.wbgOn;
    this.setData({ ['wbg[' + on + ']']: src, wbgOn: on });
  },

  /* 进页 / 新卡到账:其余卡从下面依次升上来;新到的那张从右上方斜着落到最前 */
  _playEnter(arrive) {
    const list = this.data.ticketList || [], n = list.length, cur = this.data.currentSwiperTicket;
    if (!n || reducedMotionOn()) return this._deckLay();
    const from = list.map((_, i) => {
      const r = (i - cur + n) % n;
      return (arrive && r === 0 ? 'transform: translate(40px,-720px) rotate(-12deg) scale(1.15);' : 'transform: translate(0px,560px) scale(.9);') + 'z-index: ' + (30 - r) + '; transition: none;';
    });
    this._entering = true; this.setData({ deckPoses: from, deckStill: true }); __end(this, n, arrive);
    setTimeout(() => {
      if (this._unloaded) return;
      this.setData({ deckStill: false });
      const order = list.map((_, i) => (i - cur + n) % n);
      order.forEach((r, i) => {
        const delay = arrive && r === 0 ? 320 : (n - 1 - r) * 90;
        setTimeout(() => {
          if (this._unloaded) return;
          const p = this._deckPose(r);
          const ease = arrive && r === 0 ? 'transition: transform .78s cubic-bezier(.2,.9,.25,1.08);' : 'transition: transform .7s cubic-bezier(.2,.9,.25,1.08);';
          this.setData({ ['deckPoses[' + i + ']']: 'transform: translate(0px,' + p.y + 'px) scale(' + p.s + '); filter: brightness(' + p.b + '); opacity: ' + p.o + '; z-index: ' + (30 - r) + ';' + ease });
        }, delay);
      });
    }, 40);
  },
  _playArrive() { this._playEnter(true); },

  onDeckStart(e) {
    const t = e.touches && e.touches[0];
    if (!t || this._throwing || (this.data.ticketList || []).length === 0) return;
    this._drag = { x: t.clientX, y: t.clientY, lx: t.clientX, ly: t.clientY, t: Date.now(), vx: 0, vy: 0, dx: 0, dy: 0, at: 0 };
    this._moved = false;
    this.setData({ deckStill: true });
  },
  onDeckMove(e) {
    const d = this._drag, t = e.touches && e.touches[0];
    if (!d || !t) return;
    const now = Date.now(), dt = Math.max(1, now - d.t);
    d.vx = (t.clientX - d.lx) / dt; d.vy = (t.clientY - d.ly) / dt; d.lx = t.clientX; d.ly = t.clientY; d.t = now;
    d.dx = t.clientX - d.x; d.dy = t.clientY - d.y;
    if (now - d.at < 16) return;
    d.at = now;
    const list = this.data.ticketList || [], n = list.length, cur = this.data.currentSwiperTicket;
    const pr = Math.min(1, Math.hypot(d.dx, d.dy) / 220);
    const lerp = (a, b) => a + (b - a) * pr;
    const poses = list.map((_, i) => {
      const r = (i - cur + n) % n;
      if (r === 0) return 'transform: translate(' + d.dx + 'px,' + d.dy + 'px) rotate(' + (d.dx * 0.06).toFixed(2) + 'deg); z-index: 30;';
      const a = this._deckPose(r), b = this._deckPose(r - 1);
      return 'transform: translate(0px,' + lerp(a.y, b.y) + 'px) scale(' + lerp(a.s, b.s) + '); filter: brightness(' + lerp(a.b, b.b) + '); opacity: ' + lerp(a.o, b.o) + '; z-index: ' + (30 - r) + ';';
    });
    this.setData({ deckPoses: poses });
  },
  onDeckEnd() {
    const d = this._drag;
    this._drag = null;
    if (!d) return;
    this.setData({ deckStill: false });
    const list = this.data.ticketList || [], n = list.length;
    const dist = Math.hypot(d.dx, d.dy), sp = Math.hypot(d.vx, d.vy);
    this._moved = dist >= 8;
    if (dist < 8 || n < 2 || !(dist > 90 || sp > 0.6)) return this._deckLay();
    const fx = d.dx + d.vx * 80, fy = d.dy + d.vy * 80, L = Math.hypot(fx, fy) || 1;
    const out = 'transform: translate(' + (fx / L * 620) + 'px,' + (fy / L * 620) + 'px) rotate(' + (fx / L * 20) + 'deg); opacity: 0; z-index: 40; transition: transform .5s cubic-bezier(.2,.7,.3,1), opacity .3s .18s;';
    const thrown = this.data.currentSwiperTicket;
    const next = (thrown + 1) % n;
    this._throwing = true;
    this._clearQrTimer(); this._qrFor = '';
    this.setData({ currentSwiperTicket: next, flipped: false, qrState: 'idle', qrUrl: '' });
    this._deckLay();
    this.setData({ ['deckPoses[' + thrown + ']']: out });
    this._bgTo(list[next] && list[next].wImg);
    this.syncCurrentTeam && this.syncCurrentTeam();
    setTimeout(() => {
      if (this._unloaded) return;
      this.setData({ deckStill: true });
      this._deckLay();
      setTimeout(() => { if (!this._unloaded) this.setData({ deckStill: false }); this._throwing = false; }, 50);
    }, 520);
  },
})

// 与 utils/motion-preference.js 同一组存储键(玩家「减少动态效果」开关);内联读取,不为一个布尔值多挂一个依赖
function reducedMotionOn() {
  try {
    if (typeof wx === 'undefined') return false;
    const v = wx.getStorageSync('play_reduced_motion');
    return v === true || (v !== false && wx.getStorageSync('roam_reduced_motion') === true);
  } catch (e) { return false; }
}

// v13 卡堆每一层的位置:往上错开 / 缩小 / 变暗,第 4 张以后藏在最后
const DECK_POSE = [{ y: 0, s: 1, b: 1, o: 1 }, { y: -26, s: 0.93, b: 0.5, o: 1 }, { y: -48, s: 0.86, b: 0.32, o: 1 }, { y: -62, s: 0.8, b: 0.2, o: 1 }, { y: -62, s: 0.8, b: 0.2, o: 0 }];
/* 卡面文字:类型 · 剩几天(有到期日才写),状态不是待使用时写状态;tab 归类 */
/* V-02:票面背面写票本身的有效期 —— 有活动时间写时间，没有就是长期开放(与首页卡同一格式) */
function ticketValidity(startDate) {
  const date = startDate ? new Date(String(startDate).replace(/-/g, '/')) : null;
  if (!date || isNaN(date.getTime())) return '长期开放';
  const pad = n => String(n).padStart(2, '0');
  return date.getFullYear() + '年' + (date.getMonth() + 1) + '月' + date.getDate() + '日 ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
}
function walletCard(item) {
  const until = item.wUntil ? new Date(String(item.wUntil).replace(/-/g, '/')) : null;
  const days = until && !isNaN(until.getTime()) ? Math.ceil((until.getTime() - Date.now()) / 86400000) : null;
  return Object.assign(item, { wSub: days !== null && days >= 0 ? item.wType + ' · 剩 ' + days + ' 天' : item.wType });
}
/* 能用的票:已报名(已付款 / 免费)、没核验、没取消没过期,到期日也还没过 */
function usableTicket(item) {
  if (Number(item.registrationStatus) !== 2 || item.verificationStatus == 1) return false;
  const until = item.wUntil ? new Date(String(item.wUntil).replace(/-/g, '/')) : null;
  return !(until && !isNaN(until.getTime()) && until.getTime() < Date.now());
}

/* 入场动画最长时长(最后一张起步 + 过渡 + 余量)后放开重排 */
function __end(page, n, arrive) { setTimeout(() => { page._entering = false; }, 40 + Math.max(arrive ? 320 : 0, (n - 1) * 90) + 900); }
