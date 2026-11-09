const pageMotion = require('../../../utils/page-motion.js');
const app = getApp();
const merchantTheme = require('../../../utils/merchant-theme.js');
const { normalizeRelationHome } = require('../utils/merchant-aggregate.js');
const { ensureSession } = require('../utils/session/ensure-session.js'); const { retryLogin, waitForInitialSession } = require('../../../utils/session/login-reload.js');
// CU-M-61:品类名取法与发现页共用一份(utils/category-icon.js),两页不再各写一套。
const { firstCategoryName } = require('../../../utils/category-icon.js');
// 帖子流与 pages/talent/list 的「帖文」tab 同一套投影/判定,别写第二份。
const discoverFeed = require('../../../utils/discover-feed.js');
const postFeedActions = require('../../../utils/post-feed-actions.js'); const toast = require('../../../utils/toast.js');
const FEED_PAGE_SIZE = 20;
const { isRecord, isRecordList } = require('../../../utils/response-shape.js');

function textOrDash(value, fallback) {
  if (value === null || value === undefined || value === '') return fallback || '—';
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  return fallback || '—';
}

function assetUrl(value) {
  return typeof value === 'string' && value.trim() ? app.getImgUrl(value.trim()) : '';
}

function hasPositiveId(value) {
  const number = Number(value);
  return (typeof value === 'number' || typeof value === 'string')
    && Number.isInteger(number) && number > 0;
}

function resolveAccessFailure(res, status) {
  const candidates = [status, res && res.statusCode, res && res.code];
  for (let index = 0; index < candidates.length; index += 1) {
    const code = String(candidates[index] == null ? '' : candidates[index]);
    if (code === '401' || code === '2') return 'anonymous';
    if (code === '403') return 'denied';
  }
  return '';
}

function normalizeDiscoveryItem(item, typeName) {  // typeName 现在只会是「俱乐部」(2026-10-22:商家不再看到其他商家)
  if (!item || typeof item !== 'object' || !hasPositiveId(item.id)) return null;
  const displayName = typeof item.name === 'string' ? item.name.trim() : textOrDash(item.name);
  if (!displayName || displayName === '—') return null;
  // 2026-08-20 卡片重设计:distance 后端回米;俱乐部 logo/cover 与 talent/list 同口径走 getImgUrl
  let distanceText = '';
  if (item.distance != null && isFinite(item.distance) && Number(item.distance) >= 0) {
    distanceText = item.distance < 1000
      ? Math.round(item.distance) + 'm'
      : (item.distance / 1000).toFixed(1) + 'km';
  }
  const displayStatus = item.businessStatus === 0 || item.businessStatus === '0'
    ? 'closed' : (item.businessStatus === 1 || item.businessStatus === '1' ? 'open' : 'none');
  const displayLocationVerified = item.locationVerified === 1 || item.locationVerified === '1'
    ? 1 : (item.locationVerified === 0 || item.locationVerified === '0' ? 0 : null);
  // CU-M-61:品类名以前读 item.categoryName —— 这一路后端只给 categoryId(数字外键),那个键永远是 undefined,
  // 于是无封面的商家卡不分书店花店,全显示同一只咖啡杯。现在按发现页同一份取法读 sysCategoryList。
  const categoryName = firstCategoryName(item);
  return Object.assign({}, item, {
    displayName,
    categoryName,
    displayDescription: textOrDash(item.address || categoryName || item.city, `查看${typeName}资料`),
    distanceText,
    displayStatus,
    displayLocationVerified,
    logoUrl: assetUrl(item.logo),
    coverUrl: assetUrl(item.cover),
  });
}

function normalizeDiscoveryList(items, typeName) {
  const rows = items.map(item => normalizeDiscoveryItem(item, typeName)).filter(Boolean);
  return {
    rows,
    error: rows.length === items.length ? '' : `部分${typeName}资料暂无法显示`,
  };
}

Page({
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,   // 胶囊行高:cy-nav-bar 是 fixed,页面按 status+nav 让位
    refreshing: false,
    pageState: 'idle',
    error: '',
    clubError: '',
    // 2026-10-22 owner:合作 tab 看帖子,商家不看其他商家;帖子在前并为默认
    tabs: [{ key: 'feed', label: '帖文' }, { key: 'club', label: '俱乐部' }],
    activeTab: 'feed',
    clubs: [],
    feed: [],
    feedState: 'idle',   // idle | loading | ready | empty | error | stale-error
    feedSlow: false,     // 加载超过 400ms 才显示骨架,避免闪屏
    feedError: '',
    feedEmptyTitle: '还没有帖文',
    // W2-17:全站流空态只写标题,不再有「合作过的俱乐部」副行
    // 从项目详情「找俱乐部」进来时带着主题:一路带到俱乐部主页,
    // 在那儿发起合作时主题已经定了,不用再选一遍
    topicId: '',
    topicName: '',
  },

  onLoad(options) {
    this._chapterId = String(options && options.chapterId || '');
    this._destroyed = false;
    this._requestEpoch = 0;
    this._relationLoading = false;
    this._hasLoaded = false;
    const sys = wx.getSystemInfoSync();
    this.setData({
      statusBarHeight: sys.statusBarHeight || 20,
      navBarHeight: (getApp().globalData && getApp().globalData.navBarHeight) || 44,
      activeTab: (options && options.tab === 'club') ? 'club' : 'feed',
      topicId: (options && options.topicId) || '',
      topicName: (options && options.topicName) ? decodeURIComponent(options.topicName) : '',
    });
    this.enterRelation(false);
  },

  // 入口:未登录先静默登录,成功自动加载;失败只留页内错误态(重试走 loadRelationHome)。
  enterRelation(authRetryUsed) {
    this._authRetryUsed = authRetryUsed === true;
    if (app.getUserID()) { if (this.data.guestLogin) this.setData({ guestLogin: false });
      if (this.data.activeTab === 'feed') this.loadFeed();
      this.loadRelationHome();
      return;
    }
    this.setData({ // owner 2026-10-24(app.js:142-144):游客不在打开页面时静默建号(j6 F1),点「登录」才登录
      guestLogin: true,
      pageState: 'idle',
      error: '',
      clubs: [],
    });
    waitForInitialSession(this, app, function () { this.enterRelation(false); });
  },
  onGuestLogin() {
    retryLogin(this, app, function () { this.enterRelation(false); });
  },

  // 页内错误态上的「重新加载」:用户主动重试要重置自动重登次数,再走一遍入口链路。
  retryRelation() {
    this._authRetryUsed = false;
    this.enterRelation(false);
  },

  // 401:静默重登一次后自动重载;重登失败落页内错误态,不整屏「去登录」。
  recoverSession() {
    if (this._destroyed) return;
    if (this._authRetryUsed) {
      this.setData({ pageState: 'error', error: '登录已失效，请重新登录', clubs: [] });
      return;
    }
    this._authRetryUsed = true;
    const that = this;
    ensureSession(app, true).then(function (ok) {
      if (that._destroyed) return;
      if (!ok) {
        that.setData({ pageState: 'error', error: '登录失败，请重试', clubs: [] });
        return;
      }
      that._hasLoaded = false;
      if (that.data.activeTab === 'feed' && that.data.feedState === 'error') that.loadFeed();
      that.loadRelationHome();
    });
  },

  onTabChange(e) {
    const key = e.detail && e.detail.key;
    if (key !== 'feed' && key !== 'club') return;
    if (key === this.data.activeTab) return;
    this.setData({ activeTab: key });
    if (key === 'feed' && this.data.feedState === 'idle') this.loadFeed();
  },

  loadRelationHome() {
    if (this._destroyed || this._relationLoading) return;
    const that = this;
    const hasLoaded = this._hasLoaded;
    const epoch = ++this._requestEpoch;
    this._relationLoading = true;
    that.setData({
      refreshing: hasLoaded,
      pageState: hasLoaded ? 'ready' : 'loading',
      error: '',
    });
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/merchant/relation-home',
      method: 'POST',
      data: JSON.stringify({ limit: 10 }),
      header: { 'Content-Type': 'application/json' },
      success(res) {
        if (that._destroyed || epoch !== that._requestEpoch) return;
        const home = (res && (res.code === '200' || res.code === 200))
          ? normalizeRelationHome(res.data) : null;
        if (!home) {
          that.applyLoadError(res);
          return;
        }
        const clubResult = normalizeDiscoveryList(home.clubs, '俱乐部');
        that._hasLoaded = true; pageMotion.enter(that, 'pmList');
        that.setData({
          clubs: clubResult.rows,
          clubError: clubResult.error,
          pageState: 'ready',
          error: '',
        });
      },
      fail(res, status) {
        if (that._destroyed || epoch !== that._requestEpoch) return;
        that.applyLoadError(res, status);
      },
      successStatusAbnormal(res, status) {
        if (that._destroyed || epoch !== that._requestEpoch) return;
        that.applyLoadError(res, status);
      },
      complete() {
        if (epoch !== that._requestEpoch) {
          if (that._destroyed && that._pullRefreshing) {
            that._pullRefreshing = false;
            wx.stopPullDownRefresh();
          }
          return;
        }
        that._relationLoading = false;
        if (that._pullRefreshing) {
          that._pullRefreshing = false;
          wx.stopPullDownRefresh();
        }
        if (that._destroyed) {
          return;
        }
        that.setData({ refreshing: false });
      },
    });
  },

  // 走查 v2 W2-17(业主):「合作」改「发现」,帖文看全站 —— 与玩家发现页同一条 scope=all 全站流,商家可点赞、评论。
  // 回到本页(如从俱乐部返回)不重拉也不换 tab;有旧内容时刷新失败保留旧内容。loadMore 时 beforeId 取上一页最后一条。
  loadFeed(loadMore) {
    if (this._destroyed || this._feedLoading) return;
    const that = this, more = loadMore === true;
    const hasSnapshot = more || ['ready', 'empty', 'stale-error'].indexOf(this.data.feedState) >= 0;
    const requestId = (this._feedRequestId || 0) + 1;
    this._feedRequestId = requestId;
    this._feedLoading = true;
    if (!hasSnapshot) {
      this.setData({ feedState: 'loading', feedSlow: false, feedError: '' });
      this._feedSlowTimer = setTimeout(function () {
        if (that._feedLoading && !that._destroyed) that.setData({ feedSlow: true });
      }, 400);
    }
    function isCurrent() { return !that._destroyed && requestId === that._feedRequestId; }
    function finish() { clearTimeout(that._feedSlowTimer); that._feedLoading = false; }
    function fail(res) {
      if (!isCurrent()) return;
      finish();
      if (more) return;   // 翻页失败不出错误文案,游标不动,下次触底重试(与玩家发现页同口径)
      that.setData({ feedState: hasSnapshot ? 'stale-error' : 'error', feedSlow: false,
        feedError: app.getRequestErrorMessage(res, '帖文加载失败，请重试') });
    }
    const body = { scope: 'all', limit: FEED_PAGE_SIZE };
    if (more && this._feedCursor) body.beforeId = this._feedCursor;
    app.sendRequest({
      hideLoading: true, silentError: true,
      url: '/api/club/post/feed', method: 'POST',
      data: JSON.stringify(body),
      header: { 'Content-Type': 'application/json' },
      success(res) {
        if (!isCurrent()) return;
        if (!res || (res.code != 200 && res.code != '200') || !isRecord(res.data) || !isRecordList(res.data.rows)
            || typeof res.data.hasMore !== 'boolean') return fail(res);
        const rows = res.data.rows.map(discoverFeed.normalizeClubPost);
        finish();
        that._feedHasMore = res.data.hasMore;
        if (rows.length) that._feedCursor = rows[rows.length - 1].id;
        const feed = more ? that.data.feed.concat(rows) : rows;
        that.setData({ feed: feed, feedState: feed.length ? 'ready' : 'empty', feedSlow: false, feedError: '' });
      },
      fail,
    });
  },

  onReachBottom() {
    if (this.data.activeTab === 'feed' && this._feedHasMore) this.loadFeed(true);
  },

  // 点赞与玩家发现页同一套(utils/post-feed-actions):先改卡面,失败回滚并原样显示后端提示
  onLikePost(e) {
    postFeedActions.toggleLike({ page: this, listKey: 'feed', index: e.detail && e.detail.index, app: app, toast: toast, apply: (next) => this.setData({ feed: next }) });
  },

  onPostTap(e) {
    const clubId = e.detail && e.detail.clubId;
    const postId = e.detail && e.detail.postId;
    if (clubId && postId) wx.navigateTo({ url: '/pages/club/detail/index?id=' + clubId + '&postId=' + postId + '&scope=MERCHANT' });
  },

  onPostClub(e) {
    const clubId = e.detail && e.detail.clubId;
    if (clubId) wx.navigateTo({ url: '/pages/club/detail/index?id=' + clubId + '&scope=MERCHANT' });
  },

  onPostReference(e) {
    const post = this.data.feed[e.detail && e.detail.index];
    if (!post || !post.sportTopicId) return this.onPostTap(e);
    wx.navigateTo({ url: '/pages/topic/index/index?id=' + post.sportTopicId });
  },

  applyLoadError(res, status) {
    const accessFailure = resolveAccessFailure(res, status);
    if (accessFailure === 'anonymous') {
      this._hasLoaded = false;
      this.recoverSession();
      return;
    }
    if (accessFailure === 'denied') {
      this._hasLoaded = false;
      this.setData({ clubs: [], clubError: '' });
      // 没有商家身份的账号本没有合作入口;深链误入就静默回会员中心,不摆整屏闸。
      wx.switchTab({ url: '/pages/member/index/index' });
      return;
    }
    // 有旧内容时刷新失败静默降级:退回 ready 让旧列表留在屏上,不挂横幅、不切整页错误。
    if (this._hasLoaded) {
      this.setData({
        pageState: 'ready',
      });
      return;
    }
    this.setData({
      pageState: 'error',
      error: app.getRequestErrorMessage(res, '合作数据加载失败'),
      clubs: [],
      clubError: '',
    });
  },

  onPullDownRefresh() {
    if (this.data.activeTab === 'feed') {
      this.loadFeed();
      wx.stopPullDownRefresh();
      return;
    }
    if (this._relationLoading) {
      wx.stopPullDownRefresh();
      return;
    }
    this._pullRefreshing = true;
    this.loadRelationHome();
  },

  goClub(e) {
    const id = e.currentTarget.dataset.id;
    if (!id) return;
    let url = '/pages/club/detail/index?id=' + id;
    if (this.data.topicId) {
      url += '&topicId=' + this.data.topicId
        + '&topicName=' + encodeURIComponent(this.data.topicName || '')
        + (this._chapterId ? '&chapterId=' + this._chapterId : '');
    }
    wx.navigateTo({ url: url + '&scope=MERCHANT' });
  },

  onShow() { merchantTheme.merchantPageShow(); },
  onHide() { merchantTheme.merchantPageRestore(); },
  onUnload() {
    this._destroyed = true;
    this._requestEpoch += 1;
    this._feedRequestId = (this._feedRequestId || 0) + 1;
    clearTimeout(this._feedSlowTimer);
    this._relationLoading = false;
    merchantTheme.merchantPageRestore();
  },
});
