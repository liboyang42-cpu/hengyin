const toast = require('../../../../utils/toast.js'); const pageMotion = require('../../../../utils/page-motion.js');
const { toTimestamp, chinaDateKey, chinaParts } = require('../../../../utils/datetime');
const { isRecordList, isBizOk } = require('../../../../utils/response-shape.js');
const { decodeDisplayText } = require('../../../../utils/display-text.js');
const { normalizeMerchantAccess } = require('../../../../utils/merchant-access-policy.js');
const { isLoginRequired, retryLogin } = require('../../../../utils/session/login-reload.js');
const { decorate: decorateInvites } = require('../../../../utils/coop-invite-view.js');
const { reader, sameReader } = require('../../../../utils/user-character.js');
const app = getApp();

Page({
  data: {
    communityPostRead: false,
    statusBarHeight: 20,
    navBarHeight: 44,
    capsuleRightGap: 96,
    loading: true,
    loaded: false,
    error: false,
    errorMsg: '',
    loginRequired: false,   // PR10 F-W1-12:读失败是未登录 ⇒ 登录引导
    refreshing: false,
    list: [],
    activeTab: 'in',   // 收件箱 = 全部会话 + 收到的邀请;发件箱 = 我发出的邀请
    // 竞猜待答入口:只给能结算竞猜的人看 —— 玩家看见它只会点进去撞一堵权限墙
    canSettlePredict: false,
    tabs: [],
    viewList: [],
    // 合作邀请(/api/coop/list):收到的进收件箱,我发出的进发件箱;读不到只局部提示,不影响会话列表
    invitesIn: [],
    invitesOut: [],
    inviteError: '',
  },

  onLoad() {
    const sys = wx.getSystemInfoSync ? wx.getSystemInfoSync() : {};
    let cap = (app.globalData && app.globalData.menuButtonInfo) || null;
    if (!cap && wx.getMenuButtonBoundingClientRect) {
      try { cap = wx.getMenuButtonBoundingClientRect(); } catch (e) { cap = null; }
    }
    const statusBarHeight = (app.globalData && app.globalData.statusBarHeight) || sys.statusBarHeight || 20;
    const navBarHeight = (app.globalData && app.globalData.navBarHeight) || 44;
    const capsuleRightGap = cap && cap.left ? Math.round((sys.windowWidth || 375) - cap.left + 12) : 96;
    this.setData({ statusBarHeight: statusBarHeight, navBarHeight: navBarHeight, capsuleRightGap: capsuleRightGap });
    // 左滑面板总宽 = 3 × 120rpx = 360rpx;rpx→px 换算随屏宽变,不能写死 px(P1-1:大屏机型上曾被写死值压掉)
    this._openPx = Math.round(360 * ((sys.windowWidth || 375) / 750));
    this.loadConversations();
    this.loadInvites();
  },
  onShow() {
    const resumed = this._hidden;
    this._hidden = false;
    this._navigating = false; // 从聊天页返回时刷新未读/最后一条;兜底清跳转中标记
    this._syncCommunityEntry();
    if (this.data.loaded || resumed || this._conversationReader && !sameReader(app, this._conversationReader)) {
      this.loadConversations(true);
      this.loadInvites();
    }
    this.loadMerchantEntry();
  },
  onHide() {
    this._hidden = true;
    this._inviteEpoch = (this._inviteEpoch || 0) + 1;
    this._conversationRequestEpoch = (this._conversationRequestEpoch || 0) + 1;
    this._merchantEntryRequestEpoch = (this._merchantEntryRequestEpoch || 0) + 1;
  },
  onUnload() { this._disposed = true; this.onHide(); },

  _isPageContextCurrent(memberId) {
    return !this._disposed && !this._hidden
      && (!this._conversationReader || sameReader(app, this._conversationReader)) && memberId == (app.getUserID ? app.getUserID() : 0)
      && (this._conversationMemberId === undefined || this._conversationMemberId == memberId);
  },

  loadConversations(silent) {
    const that = this;
    const memberId = app.getUserID ? app.getUserID() : 0;
    const capturedReader = reader(app);
    const identityChanged = this._conversationReader && !sameReader(app, this._conversationReader);
    const preserveConfirmed = !!silent && this.data.loaded && !identityChanged;
    this._conversationMemberId = memberId;
    this._conversationReader = capturedReader;
    if (identityChanged) {
      this.setData({ list: [], viewList: [], loaded: false, invitesIn: [], invitesOut: [], canSettlePredict: false });
      this._inviteEpoch = (this._inviteEpoch || 0) + 1;
      this._merchantEntryRequestEpoch = (this._merchantEntryRequestEpoch || 0) + 1;
      if (this._mutePending) this._mutePending.clear();
      this._pendingDelete = null;
      this.setData({ inviteError: '' });
      this.refreshView();
      const dc = this.selectComponent && this.selectComponent('#dc');
      if (dc) dc.close();
    }
    const requestEpoch = (this._conversationRequestEpoch || 0) + 1;
    this._conversationRequestEpoch = requestEpoch;
    const current = function () {
      return requestEpoch === that._conversationRequestEpoch && sameReader(app, capturedReader) && that._isPageContextCurrent(memberId);
    };
    if (preserveConfirmed) {
      this.setData({ refreshing: true });
    } else {
      this.setData({ loading: true, refreshing: false, error: false, errorMsg: '', loginRequired: false });
    }
    app.sendRequest({
      url: '/api/im/conversations',
      method: 'POST',
      hideLoading: true,
      silentError: true,
      success(res) {
        if (!current()) return;
        if (res && res.code == 200 && isRecordList(res.data)) {
          const list = res.data.map(function (c) {
            const cp = c.counterparty || {};
            return Object.assign({}, c, {
              counterparty: Object.assign({}, cp, {
                nickname: cp.nickname || (c.type == 2 ? '系统通知' : '城瘾消息'),
                avatar: cp.avatar || '',
              }),
              offset: 0,
              timeText: that.fmtTime(c.lastMsgAt),
              previewText: that.fmtPreview(c),
            });
          });
          that.setData({
            list: list,
            loading: false,
            refreshing: false,
            loaded: true,
            error: false,
            errorMsg: '',
          });
          that._conversationAppliedEpoch = requestEpoch;
          that.refreshView();
        } else {
          that._setConversationError(res, preserveConfirmed);
        }
      },
      fail(res, statusCode) {
        if (!current()) return;
        that._setConversationError(res, preserveConfirmed, statusCode);
      },
    });
  },

  _setConversationError(response, preserveConfirmed, statusCode) {
    const msg = app.getRequestErrorMessage
      ? app.getRequestErrorMessage(response, '消息加载失败，请重试')
      : ((response && (response.msg || response.errMsg)) || '消息加载失败，请重试');
    if (preserveConfirmed) {
      this.setData({ loading: false, refreshing: false, error: false });
      return;
    }
    // PR10 F-W1-12:未登录(无会话 / 401)给登录引导;其余失败仍按 load-failure 留白
    this.setData({ loading: false, refreshing: false, error: true, errorMsg: msg,
      loginRequired: isLoginRequired(app, response, statusCode) });
  },

  retryConversations() { this.loadConversations(false); },
  loginConversations() {
    retryLogin(this, app, function () { if (!this._disposed) this.loadConversations(false); });
  },

  // 合作邀请与 IM 会话是两套数据源:读不到只在列表上方局部报错,不影响会话。
  // 普通玩家返回两份空数组,收发件箱里就只有会话。
  loadInvites() {
    const that = this;
    const epoch = (this._inviteEpoch || 0) + 1;
    this._inviteEpoch = epoch;
    const capturedReader = reader(app);
    let settled = false;
    const finish = () => {
      if (settled || epoch !== this._inviteEpoch || this._disposed || this._hidden
          || !sameReader(app, capturedReader)) return false;
      settled = true;
      return true;
    };
    app.sendRequest({
      url: '/api/coop/list',
      method: 'POST',
      data: JSON.stringify({}),
      header: { 'Content-Type': 'application/json' },
      hideLoading: true,
      silentError: true,
      success(res) {
        if (!finish()) return;
        const d = res && res.code == 200 ? res.data : null;
        if (!d || !isRecordList(d.received) || !isRecordList(d.sent)) {
          that.setData({ invitesIn: [], invitesOut: [], inviteError: (res && res.msg) || '邀请暂时没读到' });
          that.refreshView();
          return;
        }
        const shape = function (rows) {
          return decorateInvites(rows, d.slots).filter(function (it) { return it.inviteId; });
        };
        that.setData({ invitesIn: shape(d.received), invitesOut: shape(d.sent), inviteError: '' });
        that.refreshView();
      },
      fail() {
        if (!finish()) return;
        that.setData({ inviteError: '邀请暂时没读到' });
      },
    });
  },

  openInvite(e) {
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    if (!ds.id || this._navigating) return;
    this._navigating = true;
    wx.navigateTo({ url: '/pages/coop/invite-detail/index?inviteId=' + ds.id + '&box=' + (ds.box === 'sent' ? 'sent' : 'received'),
      complete: () => { this._navigating = false; } });
  },

  // 会话归类:全部会话(单聊、城瘾小助手、官方客服)都在收件箱;发件箱只放我发出的邀请。
  // 返回 null = 不在小程序里展示。
  tabOf(c) {
    const t = parseInt(c.type) || 1;
    // 群聊(type=4)不在小程序露出:微信里已经有群,小程序内做群聊是和宿主抢用户,
    // 且小程序切后台 5 秒断长连接,实时聊天先天残废。群留给原生 app —— 后端 getOrCreateClubGroup
    // 等地基仍在,app 接手时直接调。这里必须显式排除,不能让它掉进 dm 兜底:
    // 两端共用 /api/im/conversations,app 一旦真建了群,靠兜底会让群会话冒进「私信」里。
    if (t === 4) return null;
    return 'in'; // 1 单聊 + 2 城瘾小助手 + 3 官方客服
  },

  // 重算 tabs(含未读角标)与当前 tab 的可见列表
  refreshView() {
    const list = this.data.list;
    const that = this;
    const count = function (key) {
      return list.reduce(function (n, c) {
        // 角标口径必须与全局红点一致:静音会话不计(T1 已让 sumUnread 排除 muted)
        if (that.tabOf(c) !== key || c.muted == 1) return n;
        return n + (parseInt(c.unread) || 0);
      }, 0);
    };
    const pendingIn = this.data.invitesIn.filter(function (it) { return it.status === 0; }).length;
    pageMotion.enter(this, 'pmList'); this.setData({
      tabs: [
        { key: 'in', label: '收件箱', badge: count('in') + pendingIn },
        { key: 'out', label: '发件箱', badge: 0 },
      ],
      viewList: list.filter(function (c) { return that.tabOf(c) === that.data.activeTab; })
        .map(function (c) { if (c._open || c.offset) { c.offset = 0; c._open = false; } return c; }),
    });
  },

  onTabChange(e) {
    this.setData({ activeTab: e.detail.key });
    this.refreshView();
  },

  goPredictInbox() { if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/merchant/predict/index', complete: () => { this._navigating = false; } }); },

  /**
   * 这一条只对**能结算竞猜的人**出现,所以问的是身份,不是待办条数。
   *
   * ⚠️ 不在这儿拉待办列表:那要静默吞掉「不是商家」这个正常回执,而同一段静默
   * 也会把真的网络失败一起吞掉 —— 商家就再也收不到提醒,且没有任何痕迹。
   * 身份问一次、条数留给页面自己说,这一行只负责「有这么个地方」。
   */
  loadMerchantEntry() {
    const that = this;
    const requestEpoch = (this._merchantEntryRequestEpoch || 0) + 1;
    this._merchantEntryRequestEpoch = requestEpoch;
    const memberId = app.getUserID ? app.getUserID() : 0;
    const current = function () {
      return requestEpoch === that._merchantEntryRequestEpoch && that._isPageContextCurrent(memberId);
    };
    app.sendRequest({
      hideLoading: true,
      url: '/api/merchant/access/me',
      method: 'POST',
      data: {},
      success(res) {
        if (!current()) return;
        const ok = res && (res.code == '200' || res.code === 200);
        const access = normalizeMerchantAccess(ok ? res.data : null);
        require('../../../../utils/predict-entry.js').showIfPending(that, app, access, current); // owner 10-22:无待答不出这一行
      },
      fail() { if (current()) that.setData({ canSettlePredict: false }); },
    });
  },

  // 空态 CTA:收件箱→广场(帖文已降级为非 tab 页面,改为 navigateTo)
  _syncCommunityEntry() {
    const memberId = app.getUserID && app.getUserID();
    const sync = () => {
      if (this._disposed || memberId !== (app.getUserID && app.getUserID())) return;
      this.setData({ communityPostRead: typeof app.canUseCommunity === 'function' && app.canUseCommunity(false) });
    };
    const pending = typeof app.loadFeatureFlags === 'function' ? app.loadFeatureFlags() : null;
    sync();
    if (pending && typeof pending.then === 'function') pending.then(sync);
  },
  onEmptyCta() {
    if (typeof app.canUseCommunity !== 'function' || !app.canUseCommunity(false)) return;
    const tab = this.data.activeTab;
    if (tab === 'in' && !this._navigating) { this._navigating = true; wx.navigateTo({ url: '/pages/square/list/index', complete: () => { this._navigating = false; } }); }
  },

  fmtPreview(c) {
    const t = c.lastMsgType;
    if (t == 2) return '[图片]';
    // 预览是纯文本:服务端过滤存下的 &lt; 等在这里解码一次
    if (t == 3) return decodeDisplayText(String(c.lastMsgText || '[系统卡片]').replace(/[\r\n]+/g, ' '));
    return decodeDisplayText(String(c.lastMsgText || '').replace(/[\r\n]+/g, ' '));
  },

  fmtTime(v) {
    if (!v) return '';
    // 时刻锚定 +08:00,日历判断与显示字段一律走中国时区,不用本地 getHours/toDateString——
    // 手机时区非 UTC+8 时,本地方法会把中国的"今天 14:30"显示成别的日期/时刻。
    const ts = toTimestamp(v);
    if (isNaN(ts)) return '';
    const nowTs = Date.now();
    const diff = (nowTs - ts) / 1000;
    if (diff < 60) return '刚刚';
    if (diff < 3600) return Math.floor(diff / 60) + '分钟前';
    const parts = chinaParts(ts);
    const pad = function (n) { return n < 10 ? '0' + n : '' + n; };
    if (chinaDateKey(ts) === chinaDateKey(nowTs)) return pad(parts.hours) + ':' + pad(parts.minutes);
    if (chinaDateKey(ts) === chinaDateKey(nowTs - 86400000)) return '昨天';
    if (diff < 7 * 86400) return ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][parts.weekday];
    return parts.month + '月' + parts.day + '日';
  },

  // ===== 左滑 =====
  onTouchStart(e) {
    this._sx = e.touches[0].clientX;
    this._sy = e.touches[0].clientY;
    this._moved = false;
  },
  onTouchMove(e) {
    const idx = e.currentTarget.dataset.index;
    const row = this.data.viewList[idx];
    if (!row) return;
    const dx = e.touches[0].clientX - this._sx;
    const dy = e.touches[0].clientY - this._sy;
    if (Math.abs(dx) < Math.abs(dy)) return; // 纵向滚动不处理
    this._moved = true;
    const base = row._open ? -this._openPx : 0;
    let off = base + dx;
    if (off > 0) off = 0;
    if (off < -this._openPx) off = -this._openPx;
    const patch = { ['viewList[' + idx + '].offset']: off };
    // 拖拽期间关掉 .conv 的 transition,避免每帧 setData 都被缓动重新补间(P1-2:追手指而非跟手指)。
    // 只在确认横向手势后置一次:点击与纵向滚动占绝大多数触摸,不该替它们付 setData 的钱。
    if (!row._dragging) patch['viewList[' + idx + ']._dragging'] = true;
    this.setData(patch);
  },
  // 同时挂在 touchend 与 touchcancel 上:触摸被系统事件/父级 scroll-view 打断时只发 touchcancel,
  // 少了它 _dragging 会永久卡 true(transition:none 常驻),且 refreshView 只重置 offset/_open、清不掉它。
  onTouchEnd(e) {
    const idx = e.currentTarget.dataset.index;
    const off = this.data.viewList[idx] ? this.data.viewList[idx].offset : 0;
    const open = off < -this._openPx / 2;
    this.setData({
      ['viewList[' + idx + '].offset']: open ? -this._openPx : 0,
      ['viewList[' + idx + ']._open']: open,
      ['viewList[' + idx + ']._dragging']: false,
    });
  },
  closeRow(id) {
    const idx = this.data.viewList.findIndex(c => c.conversationId === id);
    if (idx < 0) return;
    this.setData({ ['viewList[' + idx + '].offset']: 0, ['viewList[' + idx + ']._open']: false });
  },

  onRowRead(e) {
    const id = e.currentTarget.dataset.id;
    const that = this;
    const memberId = app.getUserID ? app.getUserID() : 0;
    if (!this._isPageContextCurrent(memberId)) return;
    const row = this.data.list.find(c => c.conversationId === id);
    if (!row) return;
    const current = () => that._isPageContextCurrent(memberId) && that.data.list.indexOf(row) >= 0;
    app.sendRequest({
      url: '/api/im/read', method: 'POST', hideLoading: true,
      data: { conversation_id: id },
      // ⚠️ success() 被调用不等于服务端做成了:HTTP 200 + 业务 code!=200 也走这里。
      // 不判 code 就直接把未读清零,是给用户一个假回执。
      success(res) {
        if (!current()) return;
        if (!isBizOk(res)) { app.tips && app.tips((res && res.msg) || '标记已读失败'); return; }
        const li = that.data.list.findIndex(function (c) { return c.conversationId === id; });
        if (li >= 0) that.setData({ ['list[' + li + '].unread']: 0 });
        that.closeRow(id);
        that.refreshView();
      },
      fail() { if (current()) app.tips && app.tips('操作失败'); },
    });
  },

  onRowDelete(e) {
    const id = e.currentTarget.dataset.id;
    const memberId = app.getUserID ? app.getUserID() : 0;
    if (!this._isPageContextCurrent(memberId)) return;
    this._pendingDelete = { id: id, memberId: memberId };
    const dc = this.selectComponent && this.selectComponent('#dc');
    if (dc) dc.open('im.conversation.delete');
  },

  onDangerConfirm(e) {
    if (e.detail.key !== 'im.conversation.delete') return;
    const pending = this._pendingDelete;
    if (!pending || pending.submitting || !this._isPageContextCurrent(pending.memberId)) return;
    pending.submitting = true;
    const that = this;
    const id = pending.id;
    const current = () => that._pendingDelete === pending && that._isPageContextCurrent(pending.memberId);
    const dc = this.selectComponent && this.selectComponent('#dc');
    if (dc) dc.busyOn();
    app.sendRequest({
      url: '/api/im/delete', method: 'POST', hideLoading: true,
      data: { conversation_id: id },
      // 同上:业务失败时会话其实还在,不能从列表里抹掉再报「已删除」。
      success(res) {
        pending.submitting = false;
        if (!current()) return;
        if (!isBizOk(res)) { if (dc) dc.failed((res && res.msg) || '删除失败'); return; }
        // 删除确认后，早于该确认发出的列表不能把旧会话及摘要带回来。
        that._conversationRequestEpoch = (that._conversationRequestEpoch || 0) + 1;
        const list = that.data.list.filter(function (c) { return c.conversationId !== id; });
        that.setData({ list: list, loading: false, refreshing: false });
        that.refreshView();
        that._pendingDelete = null;
        if (dc) dc.done();
      },
      fail() { pending.submitting = false; if (current() && dc) dc.failed('删除失败'); },
    });
  },

  onDangerCancel() {
    const pending = this._pendingDelete;
    this._pendingDelete = null;
    if (pending && this._isPageContextCurrent(pending.memberId)) this.closeRow(pending.id);
  },

  onRowMute(e) {
    const id = e.currentTarget.dataset.id;
    const idx = e.currentTarget.dataset.index;
    const that = this;
    const cur = this.data.viewList[idx];
    if (!cur || cur.conversationId !== id) return;
    const memberId = app.getUserID ? app.getUserID() : 0;
    if (!this._isPageContextCurrent(memberId)) return;
    const pending = this._mutePending || (this._mutePending = new Map());
    const pendingKey = memberId + ':' + id;
    if (pending.has(pendingKey)) return;
    const prevMuted = cur.muted; // 基本类型拷贝:viewList[idx] 与 list[li] 是同一引用(refreshView 的 filter/map 不克隆),setData 路径写法会就地污染 cur,不能再用 cur.muted 回滚
    const next = (prevMuted == 1) ? 0 : 1;
    const li = this.data.list.findIndex(function (c) { return c.conversationId === id; });
    if (li < 0) return;
    // 每次请求一个令牌:迟到/重复的旧回调不能释放新请求的锁。
    const operation = {};
    pending.set(pendingKey, operation);
    const startEpoch = this._conversationRequestEpoch || 0;
    // 本行已被替换:若当前列表是本次 mute 之前就发出的旧请求回包,仍按会话 ID 落结果;
    // 否则是新列表回读,以服务端回读为准,旧结果不再改行也不提示。
    const newerListRead = function () {
      const row = that.data.list.find(function (c) { return c.conversationId === id; });
      if (row === cur) return false;
      return !(that._conversationAppliedEpoch > 0 && that._conversationAppliedEpoch <= startEpoch);
    };
    const finish = function () {
      if (pending.get(pendingKey) !== operation) return false;
      pending.delete(pendingKey);
      return that._isPageContextCurrent(memberId) && !newerListRead();
    };
    const rollback = function () {
      // 请求期间列表可能重排或移除对象，回滚必须重新按会话 ID 定位。
      const currentIndex = that.data.list.findIndex(function (c) { return c.conversationId === id; });
      if (currentIndex < 0) return;
      that.setData({ ['list[' + currentIndex + '].muted']: prevMuted });
      that.refreshView();
    };
    // 乐观更新
    this.setData({ ['list[' + li + '].muted']: next });
    this.refreshView();
    this.closeRow(id);
    app.sendRequest({
      url: '/api/im/mute', method: 'POST', hideLoading: true,
      data: { conversation_id: id, muted: next },
      success(res) {
        if (!finish()) return;
        if (!(res && res.code == 200)) {
          // 回滚,不留假状态
          rollback();
          app.tips && app.tips((res && res.msg) || '操作失败');
          return;
        }
        // 确认后丢弃更早发出的列表回包;按会话 ID 重新定位,新对象替换该行,不就地改 viewList 共享引用。
        that._conversationRequestEpoch = (that._conversationRequestEpoch || 0) + 1;
        const list = that.data.list.map(function (c) {
          return c.conversationId === id ? Object.assign({}, c, { muted: next }) : c;
        });
        that.setData({ loading: false, refreshing: false, list: list });
        that.refreshView();
        toast(next ? '已开启免打扰' : '已关闭免打扰');
      },
      fail() {
        if (!finish()) return;
        rollback();
        app.tips && app.tips('操作失败');
      },
    });
  },

  onBack() { wx.navigateBack({ delta: 1, fail() { wx.switchTab({ url: '/pages/index/index' }); } }); },

  openChat(e) {
    if (this._moved) { this._moved = false; return; } // 滑动中不触发跳转
    if (this._navigating) return; const ds = e.currentTarget.dataset;
    this._navigating = true; wx.navigateTo({ complete: () => { this._navigating = false; },
      url: '/subpackageB/pages/im/chat/index?conversationId=' + ds.id +
        '&name=' + encodeURIComponent(ds.name || '') +
        '&avatar=' + encodeURIComponent(ds.avatar || '') +
        '&type=' + (ds.type || 1),
    });
  },
});
