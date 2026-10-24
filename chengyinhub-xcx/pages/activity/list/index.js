const modal = require('../../../utils/modal.js'); const pageMotion = require('../../../utils/page-motion.js');
const cyToast = require('../../../utils/toast.js');
const { activityStatusMeta, activityStatusText } = require('../../../utils/activity-status');
const officialChannel = require('../../../utils/merchant-official-channel.js');
const app = getApp();
const { memberId, isLoginRequired, failureStatus, retryLogin, waitForInitialSession, recoverReadSession } = require('../../../utils/session/login-reload.js');

function authorization() {
  return typeof app.getAuthorization === 'function' ? app.getAuthorization()
    : typeof app.getToken === 'function' ? app.getToken() : app.globalData && app.globalData.authorization;
}
function hasSession() { return !!(memberId(app) && authorization()); }
function currentPrivate(page) {
  const epoch = page._readEpoch || 0, owner = memberId(app), token = authorization();
  return (res, statusCode) => !page._hidden && !page._unloaded && (page._readEpoch || 0) === epoch
    && ((owner === memberId(app) && token === authorization())
      || res && res.reason === 'sessionChanged' || failureStatus(res, statusCode) === 401 && !hasSession());
}

// 日期兼容:后端 Date 可能是毫秒数或 "yyyy-MM-dd HH:mm:ss" 字符串
function toTs(v) {
  if (v == null || v === '') return 0;
  if (typeof v === 'number') return v;
  const s = String(v).replace(/-/g, '/').replace('T', ' ');
  const t = new Date(s).getTime();
  return isNaN(t) ? 0 : t;
}
function timeText(e) {
  const now = Date.now();
  const st = toTs(e.activityStart), en = toTs(e.activityEnd);
  if (e.status == 1 && st) { const d = st - now; return d > 0 ? '距开始 ' + fmtDur(d) : '即将开始'; }
  if ((e.status == 2 || e.status == 3) && en) { const d = en - now; return d > 0 ? '距结束 ' + fmtDur(d) : '即将结束'; }
  if (e.status >= 5) return '已结束';
  return activityStatusText(e.status) || '—';   // 草稿=空、结算中=结算中,不再一律「报名中」
}
function fmtDur(ms) {
  const h = Math.floor(ms / 3600000);
  if (h >= 24) return Math.floor(h / 24) + ' 天';
  if (h >= 1) return h + ' 小时';
  return Math.max(1, Math.floor(ms / 60000)) + ' 分';
}

// 第三项 = cy-empty 的 kind。这里**只借它的字形**:本页恒显式传 title/sub,而 cy-empty
// 是 `title || d.title`(components/cy/empty/index.js:69),显式值优先,所以 kind 自带的
// 默认文案永远走不到。之前四个 tab 都没传 kind 也没传 icon,空态就是两行裸字 ——
// 全仓其余 6 处空态都有图,这页是唯一的例外。
// ⚠️ 不能用 kind='empty':那一档给的是位图 /images/no_data.svg,不是内置线性字形。
const EMPTY_COPY = {
  0: ['暂无进行中的活动', '', 'not-found'],
  1: ['暂无即将上线的活动', '', 'not-started'], // clock,与「还没开始」同一档
  2: ['暂无已结束的活动', '', 'not-found'],
  3: ['还没有参与的活动', '', 'not-found'],
};

// ===== 发起官方活动:类型 B 全屏 sheet(弹窗规范 5.1 阶段2 试点)=====
// 唯一入口是本页 FAB,能看到 FAB 就已经过 canPublish 校验,sheet 内不再重复查权限。
function toast(t) { cyToast(t); }

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    tab: 0,
    tabs: ['进行中', '即将上线', '已结束', '我的'],
    // cy-tabs(variant=chip,ccc 标准)要的 [{key,label}] 形状;key 用字符串化下标,
    // 不改 tab 本身的 number 语义,避免牵动 applyFilter 里一串 d.tab===N 的比较
    tabsCy: [{ key: '0', label: '进行中' }, { key: '1', label: '即将' }, { key: '2', label: '已结束' }, { key: '3', label: '我的' }],
    keyword: '',
    events: [],
    loading: true,
    mineAuthRequired: false,
    mineAccessDenied: false,
    curError: false,    // 当前 tab 数据源的错误(applyFilter 派生,防两接口串台)
    errorMsg: '',
    summary: '',
    emptyTitle: '',
    emptySub: '',
    emptyKind: 'not-found',
    canPublish: false,

    // 发起官方活动 sheet(类型 B 全屏)
  },

  onLoad() {
    this._hidden = false;
    this._unloaded = false;
    this._ownerId = memberId(app);
    this._authorization = authorization();
    this.fetchAll();
    this.checkPublish();
  },
  onShow() {
    const hidden = this._hidden;
    this._hidden = false;
    const changed = this._ownerId !== memberId(app) || this._authorization !== authorization();
    if (changed) {
      this.resetPrivate(!hasSession());
      this._ownerId = memberId(app);
      this._authorization = authorization();
    }
    if (hidden && (!this._loaded || this._publicLoading)) this.fetchAll();
    else if (this._loaded || changed || hidden) this.fetchMine();
    if (changed || hidden) this.checkPublish();
  },
  onHide() {
    this._hidden = true;
    this._readEpoch = (this._readEpoch || 0) + 1;
    this._allSeq = (this._allSeq || 0) + 1;
    this._mineSeq = (this._mineSeq || 0) + 1;
    this._publishSeq = (this._publishSeq || 0) + 1;
    this._mineLoading = false;
  },
  onUnload() { this.onHide(); this._unloaded = true; },

  resetPrivate(authRequired) {
    this._readEpoch = (this._readEpoch || 0) + 1;
    this._mineSeq = (this._mineSeq || 0) + 1;
    this._publishSeq = (this._publishSeq || 0) + 1;
    this._mineLoaded = false;
    this._loginRequired = !!authRequired;
    this._mine = [];
    this._mineLoading = false;
    this._mineError = !!authRequired;
    this._mineErrorMsg = '';
    this.setData({ mineAuthRequired: !!authRequired, mineAccessDenied: false, canPublish: false });
    this.applyFilter();
  },

  reloadPrivate() {
    this._ownerId = memberId(app);
    this._authorization = authorization();
    this.resetPrivate(!hasSession());
    this.fetchMine();
    this.checkPublish();
  },

  mineFailure(res, statusCode) {
    if (recoverReadSession(this, app, res, function () { this.resetPrivate(!hasSession()); }, this.reloadPrivate, statusCode)) return;
    if (isLoginRequired(app, res, statusCode) || !authorization()) { this.resetPrivate(true); return; }
    this._mineLoaded = true;
    const denied = failureStatus(res, statusCode) === 403;
    if (denied) this._mine = [];
    this._mineLoading = false;
    this._mineError = true;
    this._mineErrorMsg = app.getRequestErrorMessage(res, '活动列表暂时没能打开，请稍后重试');
    this.setData({ mineAuthRequired: false, mineAccessDenied: denied });
    this.applyFilter();
  },

  // 深链直达(栈深=1)时返回键无处可退,兜底回首页
  onNavBack() {
    if (getCurrentPages().length > 1) wx.navigateBack();
    else wx.reLaunch({ url: '/pages/index/index' });
  },

  checkPublish() {
    if (this._hidden || this._unloaded) return;
    this.setData({ canPublish: false });
    if (!hasSession() || this._loginRequired) return;
    const current = currentPrivate(this), seq = (this._publishSeq || 0) + 1;
    this._publishSeq = seq;
    const failed = (res, statusCode) => {
      if (!current(res, statusCode) || this._publishSeq !== seq) return;
      this.setData({ canPublish: false });
      if (res && res.reason === 'sessionChanged' || isLoginRequired(app, res, statusCode)) this.mineFailure(res, statusCode);
    };
    app.sendRequest({
      url: '/api/official/can-publish', method: 'GET', hideLoading: true,
      success: (res) => {
        if (!current(res) || this._publishSeq !== seq) return;
        if (res && (res.code == 200 || res.code == '200')) {
          this.setData({ canPublish: !!(res.data && res.data.canPublish) });
        } else failed(res);
      },
      successStatusAbnormal: failed,
      fail: failed,
    });
  },

  // 错误态由页面 cy-error 呈现:silentError 关自动 toast。
  // 终态只认 success / successStatusAbnormal(HTTP!=200)/ fail 三回调——request-client
  // 所有路径(含 401 重登失败→fail、重试再 401→statusAbnormal)必落其一;
  // 不用 complete:401 重登窗口它会先于终态开火,导致假空态/丢错误态。
  fetchAll() {
    if (this._hidden || this._unloaded) return;
    const seq = (this._allSeq || 0) + 1;
    this._allSeq = seq;
    this._publicLoading = true;
    this._loadError = false;
    this._loadErrorMsg = '';
    this.applyFilter();
    const done = (patch) => {
      if (this._hidden || this._unloaded || this._allSeq !== seq) return;
      this._publicLoading = false;
      this._loadError = patch.loadError === true;
      this._loadErrorMsg = patch.loadErrorMsg || '';
      if (patch._all) this._all = patch._all;
      this.applyFilter();
    };
    app.sendRequest({
      url: '/api/official/events', method: 'GET', data: {}, hideLoading: true, silentError: true,
      success: (res) => {
        if (res && (res.code == 200 || res.code == '200') && Array.isArray(res.data)) {
          if (this._hidden || this._unloaded || this._allSeq !== seq) return;
          this._loaded = true;
          done({ _all: res.data.filter(this._isCompleteEvent).map(this._decorate) });
        } else {
          done({ loadError: true, loadErrorMsg: app.getRequestErrorMessage(res, '网络异常，请重试') });
        }
      },
      successStatusAbnormal: (data) => {
        done({ loadError: true, loadErrorMsg: app.getRequestErrorMessage(data, '服务暂不可用，请稍后重试') });
      },
      fail: (res) => {
        done({ loadError: true, loadErrorMsg: app.getRequestErrorMessage(res, '网络异常，请重试') });
      },
    });
    this.fetchMine();
  },
  fetchMine() {
    if (this._hidden || this._unloaded) return;
    if (this._ownerId !== memberId(app) || this._authorization !== authorization()) {
      this.resetPrivate(!hasSession());
      this._ownerId = memberId(app);
      this._authorization = authorization();
    }
    if (!hasSession() || this._loginRequired) {
      if (!this.data.mineAuthRequired) this.resetPrivate(true);
      const epoch = this._readEpoch || 0;
      waitForInitialSession(this, app, function () {
        if ((this._readEpoch || 0) === epoch) this.reloadPrivate();
      });
      return;
    }
    if (this._mineLoading) return;
    const current = currentPrivate(this), seq = (this._mineSeq || 0) + 1;
    this._mineSeq = seq;
    this._mineLoading = true;
    this._mineError = false;
    this._mineErrorMsg = '';
    this.setData({ mineAuthRequired: false, mineAccessDenied: false });
    this.applyFilter();
    const failed = (res, statusCode) => {
      if (current(res, statusCode) && this._mineSeq === seq) this.mineFailure(res, statusCode);
    };
    app.sendRequest({
      url: '/api/official/my-events', method: 'GET', hideLoading: true, silentError: true,
      success: (res) => {
        if (!current(res) || this._mineSeq !== seq) return;
        if (res && (res.code == 200 || res.code == '200') && Array.isArray(res.data)) {
          this._mineLoaded = true;
          this._mine = res.data.filter(this._isCompleteEvent).map(this._decorate);
          this._mineError = false;
          this._mineLoading = false;
          this.applyFilter();
        } else failed(res);
      },
      successStatusAbnormal: failed,
      fail: failed,
    });
  },
  onRetry() {
    if (this.data.tab !== 3) { this.fetchAll(); return; }
    if (!this.data.mineAuthRequired) { this.fetchMine(); return; }
    const epoch = this._readEpoch || 0;
    return retryLogin(this, app, function () {
      if ((this._readEpoch || 0) === epoch) this.reloadPrivate();
    });
  },

  _decorate(e) {
    const m = activityStatusMeta(e.status);
    e._statusText = m.text;
    e._statusVariant = m.variant;
    e._live = m.live;
    e._timeText = timeText(e);
    e._pct = (e.collective && e.collective.enabled) ? e.collective.pct : -1;
    e.coverImg = e.coverImg || e.imgUrl || e.cover || '';
    // url() 加引号防 OSS 路径含空格/括号打断整条 style
    e._coverStyle = e.coverImg ? "background-image:url('" + String(e.coverImg).replace(/'/g, '%27') + "')" : '';
    // F21(2026-09-17 拍板 C):关键承接方缺失的活动标「信息不全」+原因,保留不撤下(公开列表口径)
    e._gapLabel = officialChannel.recruitmentGapLabel(e);
    e._gapText = officialChannel.recruitmentGapText(e);
    return e;
  },

  _isCompleteEvent(e) {
    const idType = e && typeof e.id;
    const titleType = e && typeof e.title;
    return !!(e
      && (idType === 'string' || idType === 'number')
      && String(e.id).trim()
      && (titleType === 'string' || titleType === 'number')
      && String(e.title).trim());
  },

  onTab(ev) {
    this.applyFilter({ tab: +ev.detail.key });
    if (this.data.tab === 3 && (!this._mineLoaded || !hasSession()
      || this._ownerId !== memberId(app) || this._authorization !== authorization())) this.fetchMine();
    wx.pageScrollTo({ scrollTop: 0, duration: 0 });
  },
  onSearchInput(ev) {
    this.applyFilter({ keyword: ev.detail.value });
  },

  // 筛选 = tab 分组 + 关键词(标题/副题/城市)本地过滤;
  // extra(如 {tab}/{keyword})并入同一次 setData,一次交互只过一次桥
  applyFilter(extra) {
    const d = extra ? Object.assign({}, this.data, extra) : this.data;
    let list;
    if (d.tab === 3) list = this._mine || [];
    else if (d.tab === 0) list = (this._all || []).filter(e => e._live);
    else if (d.tab === 1) list = (this._all || []).filter(e => e.status == 1);
    else list = (this._all || []).filter(e => e.status >= 5);

    const kw = d.keyword.trim().toLowerCase();
    if (kw) {
      list = list.filter(e =>
        String(e.title || '').toLowerCase().indexOf(kw) >= 0 ||
        String(e.subtitle || '').toLowerCase().indexOf(kw) >= 0 ||
        String(e.city || '').toLowerCase().indexOf(kw) >= 0
      );
    }

    let summary, emptyTitle, emptySub, emptyKind;
    if (kw) {
      summary = '「' + d.keyword.trim() + '」' + list.length + ' 个结果';
      emptyTitle = '没有找到相关活动';
      emptySub = '换个关键词，或看看其他分类';
      emptyKind = 'not-found';
    } else {
      summary = d.tabs[d.tab] + ' · 共 ' + list.length + ' 个活动';
      const c = EMPTY_COPY[d.tab];
      emptyTitle = c[0];
      emptySub = c[1];
      emptyKind = c[2];
    }
    pageMotion.enter(this, 'pmList'); this.setData(Object.assign({
      events: list, summary, emptyTitle, emptySub, emptyKind,
      curError: d.tab === 3 ? this._mineError === true : this._loadError === true,
      loading: d.tab === 3 ? this._mineLoading === true : this._publicLoading === true,
      errorMsg: (d.tab === 3 ? this._mineErrorMsg : this._loadErrorMsg) || '',
    }, extra));
  },

  openDetail(ev) {
    const id = ev.currentTarget.dataset.id;
    wx.navigateTo({ url: '/pages/activity/official-detail/index?id=' + id });
  },
  goMine() {
    wx.navigateTo({ url: '/pages/activity/official-mine/index' });
  },
  goInbox() {
    wx.navigateTo({ url: '/pages/activity/official-inbox/index' });
  },
});
