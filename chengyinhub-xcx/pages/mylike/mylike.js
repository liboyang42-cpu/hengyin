const modal = require('../../utils/modal.js');
const toast = require('../../utils/toast.js');
const app = getApp();
const community = require('../../utils/community-post-api.js').createClient(app, wx);
const { isRecordList } = require('../../utils/response-shape.js');
const policy = require('../../utils/identity/identity-policy.js');
const { isLoginRequired, retryLogin } = require('../../utils/session/login-reload.js');
const merchantTheme = require('../../utils/merchant-theme.js');

Page({
  /**
   * 页面的初始数据
   */
  data: {
    // 标题要紧接导航下沿，就得有个占位撑出导航高（与 search2 同一写法）。
    // 兜底 20/44 是 iOS 常见值，globalData 没算出来时不至于让标题压在导航下。
    statusBarHeight: (app.globalData || {}).statusBarHeight || 20,
    navBarHeight: (app.globalData || {}).navBarHeight || 44,
    isMerchantView: false,
    favoriteTabs: [{ key: 'posts', label: '帖文' }, { key: 'topics', label: '主题' }],
    activeFavoriteTab: 'posts',
    postList: [],
    postMoreShow: false,
    postMoreItems: ['取消收藏'],
    ztList1: [], // 路线列表
    loading: false,
    refreshing: false,
    errorMsg: '',
    loginRequired: false,   // PR10 F-W1-12:读失败是未登录(无会话 / 401)⇒ 登录引导,不与加载失败一起留白
    hasMore1: true,
    hasMorePosts: true,
    pageConfig: {
      pageNum: 1,
      pageSize: 10
    }
  },

  /**
   * 生命周期函数--监听页面加载
   */
  onLoad(options) {
    this.syncViewTheme();
    this.syncCommunityTab();
    this.loadLikeData();
  },

  // F-L5C-06:社区读闸关(含开关拉取失败)时收藏页只留「主题」,不请求也不展示社区帖文;
  // 开关稍后确认开放再把「帖文」页签补回来,不抢用户当前所在页签。
  syncCommunityTab() {
    const readable = () => typeof app.canUseCommunity === 'function' && app.canUseCommunity(false);
    if (readable()) return;
    this.setData({ favoriteTabs: [{ key: 'topics', label: '主题' }], activeFavoriteTab: 'topics' });
    const owner = String(app.getUserID ? app.getUserID() || '' : '');
    const pending = typeof app.loadFeatureFlags === 'function' ? app.loadFeatureFlags() : null;
    if (!pending || typeof pending.then !== 'function') return;
    pending.then(() => {
      if (owner !== String(app.getUserID ? app.getUserID() || '' : '') || !readable()) return;
      this.setData({ favoriteTabs: [{ key: 'posts', label: '帖文' }, { key: 'topics', label: '主题' }] });
    });
  },

  /**
   * 生命周期函数--监听页面显示
   */
  onShow() {
    this.syncViewTheme();
    if (this._syncFavoriteAccount()) { this.loadLikeData(); this._hasShown = true; return; }
    if (!this._hasShown) {
      this._hasShown = true;
      return;
    }
    // 返回页面后刷新数据；首展交给 onLoad，避免同一页并发两次 page=1。
    // 在途列表期间返回:排队一次刷新,由当前请求 complete 出队(刷新归属本次返回,不记在列表 handler 名下)。
    if (this.data.loading) {
      this._favoriteShowRefresh = { owner: this._favoriteOwner, generation: this._favoriteGeneration, run: () => this.refreshData() };
      return;
    }
    this.refreshData();
  },

  onHide() { merchantTheme.merchantPageRestore(); },
  onUnload() { this._favoriteShowRefresh = null; this._favoritesDisposed = true; this._favoriteGeneration = (this._favoriteGeneration || 0) + 1; merchantTheme.merchantPageRestore(); },

  _favoriteMember() { return String((app.getUserID && app.getUserID()) || ''); },
  _syncFavoriteAccount() {
    const member = this._favoriteMember();
    if (this._favoriteOwner === undefined) { this._favoriteOwner = member; return false; }
    if (this._favoriteOwner === member) return false;
    this._favoriteOwner = member;
    this._favoriteGeneration = (this._favoriteGeneration || 0) + 1;
    this._unlikeRequests = Object.create(null); this._postUnlikeRequests = Object.create(null);
    this._stopPullWithCurrent = false;
    this._favoriteShowRefresh = null;
    this._favoriteLoadedPages = Object.create(null);
    this._favoriteRemovals = { posts: Object.create(null), topics: Object.create(null) };
    this._unlikePrompts = Object.create(null);
    this.setData({ postList: [], ztList1: [], loading: false, refreshing: false, errorMsg: '', loginRequired: false,
      hasMore1: true, hasMorePosts: true, pageConfig: { pageNum: 1, pageSize: 10 } });
    return true;
  },
  _isFavoriteResponseCurrent(owner, generation) {
    return !this._favoritesDisposed && owner === this._favoriteMember()
      && generation === this._favoriteGeneration;
  },

  _flushFavoriteShowRefresh() {
    const pending = this._favoriteShowRefresh;
    this._favoriteShowRefresh = null;
    if (pending && this._isFavoriteResponseCurrent(pending.owner, pending.generation)) pending.run();
  },

  _recordFavoriteRemoval(type, id) {
    this._favoriteRemovalVersion = (this._favoriteRemovalVersion || 0) + 1;
    const removed = this._favoriteRemovals || (this._favoriteRemovals = { posts: Object.create(null), topics: Object.create(null) });
    removed[type][String(id)] = this._favoriteRemovalVersion;
  },
  _filterFavoriteRead(type, rows, version) {
    const removed = this._favoriteRemovals && this._favoriteRemovals[type];
    return removed ? rows.filter(row => !(removed[String(row.id)] > version)) : rows;
  },

  syncViewTheme() {
    const isMerchantView = policy.isMerchantView({
      role: wx.getStorageSync('role'),
      userType: wx.getStorageSync('user_type'),
      debugView: wx.getStorageSync('debug_user_view'),
    });
    this.setData({ isMerchantView });
    if (isMerchantView) merchantTheme.merchantPageShow();
    else merchantTheme.merchantPageRestore();
  },

  /**
   * 加载点赞数据
   */
  loadLikeData: function(fromPullDown) {
    this._syncFavoriteAccount();
    const that = this;
    const stopPullAfterComplete = fromPullDown === true;
    if (that.data.loading) {
      if (stopPullAfterComplete) that._stopPullWithCurrent = true;
      return;
    }
    const currentList = that.data.activeFavoriteTab === 'posts' ? that.data.postList : that.data.ztList1;
    const refreshing = that.data.pageConfig.pageNum === 1 && currentList.length > 0;
    that.setData({ loading: true, refreshing: refreshing, errorMsg: '', loginRequired: false });
    
    if (that.data.activeFavoriteTab === 'posts') that.getPostFavoriteList(stopPullAfterComplete);
    else that.getTopicLikeList(stopPullAfterComplete);
  },

  onFavoriteTabChange: function(e) {
    const key = e.detail && e.detail.key;
    if (!key || key === this.data.activeFavoriteTab || this.data.loading) return;
    this.setData({
      activeFavoriteTab: key,
      pageConfig: { pageNum: 1, pageSize: 10 },
      errorMsg: ''
    });
    this.loadLikeData();
  },

  getPostFavoriteList: function(stopPullAfterComplete) {
    const that = this;
    this._syncFavoriteAccount();
    const owner = this._favoriteOwner; const generation = this._favoriteGeneration;
    const requestId = (this._favoriteListRequestId || 0) + 1;
    this._favoriteListRequestId = requestId;
    const isCurrent = () => that._isFavoriteResponseCurrent(owner, generation)
      && requestId === that._favoriteListRequestId;
    const removalVersion = that._favoriteRemovalVersion || 0;
    const pageConfig = Object.assign({}, that.data.pageConfig);
    const loadedPages = that._favoriteLoadedPages || (that._favoriteLoadedPages = Object.create(null));
    const previousPage = loadedPages.posts || Math.max(1, pageConfig.pageNum - 1);
    const hasOldContent = that.data.postList.length > 0;
    const finishLoadError = function (res, fallback, statusCode) {
      const message = app.getRequestErrorMessage(res, fallback);
      that.setData({ pageConfig: { pageNum: previousPage, pageSize: pageConfig.pageSize } });
      that.setData({ refreshing: false, errorMsg: hasOldContent ? '' : message,
        loginRequired: !hasOldContent && isLoginRequired(app, res, statusCode) });
    };
    community.request({
      hideLoading: true,
      silentError: true,
      auth: true,
      url: '/api/creativesquare/list',
      method: 'POST',
      data: {
        favorite_only: 1,
        pageNum: pageConfig.pageNum,
        pageSize: pageConfig.pageSize
      },
      success: function (res) {
        if (!isCurrent()) return;
        if (res && res.code == '200' && res.data && isRecordList(res.data.rows)) {
          const rows = that._filterFavoriteRead('posts', res.data.rows, removalVersion).map(function (row) {
            row.picList = row.pics ? String(row.pics).split(';').filter(Boolean) : [];
            return row;
          });
          loadedPages.posts = pageConfig.pageNum;
          const postList = pageConfig.pageNum === 1 ? rows : that.data.postList.concat(rows);
          that.setData({ postList: postList, hasMorePosts: res.data.hasMore === true, errorMsg: '' });
        } else finishLoadError(res, '收藏帖文加载失败');
      },
      fail: function (res, statusCode) {
        if (!isCurrent()) return;
        finishLoadError(res, '收藏帖文加载失败', statusCode);
      },
      successStatusAbnormal: function (res, statusCode) {
        if (!isCurrent()) return;
        finishLoadError(res, '服务暂不可用，请稍后重试', statusCode);
      },
      complete: function () {
        if (!isCurrent()) return;
        const shouldStopPull = stopPullAfterComplete || that._stopPullWithCurrent;
        that._stopPullWithCurrent = false;
        that.setData({ loading: false, refreshing: false });
        if (shouldStopPull && typeof wx.stopPullDownRefresh === 'function') wx.stopPullDownRefresh();
        that._flushFavoriteShowRefresh();
      }
    });
  },

  /**
   * 获取路线点赞列表
   */
  getTopicLikeList: function(stopPullAfterComplete) {
    const that = this;
    this._syncFavoriteAccount();
    const owner = this._favoriteOwner; const generation = this._favoriteGeneration;
    const requestId = (this._favoriteListRequestId || 0) + 1;
    this._favoriteListRequestId = requestId;
    const isCurrent = () => that._isFavoriteResponseCurrent(owner, generation)
      && requestId === that._favoriteListRequestId;
    const removalVersion = that._favoriteRemovalVersion || 0;
    const pageConfig = Object.assign({}, that.data.pageConfig);
    const loadedPages = that._favoriteLoadedPages || (that._favoriteLoadedPages = Object.create(null));
    const previousPage = loadedPages.topics || Math.max(1, pageConfig.pageNum - 1);
    const hasOldContent = that.data.ztList1.length > 0;
    const finishLoadError = function (res, fallback, statusCode) {
      const message = app.getRequestErrorMessage(res, fallback);
      that.setData({ pageConfig: { pageNum: previousPage, pageSize: pageConfig.pageSize } });
      if (hasOldContent) {
        that.setData({
          refreshing: false,
          errorMsg: '',
        });
      } else {
        that.setData({
          refreshing: false,
          errorMsg: message,
          loginRequired: isLoginRequired(app, res, statusCode),
        });
      }
    };
    
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/topic/like_list',
      method: "POST",
      data: {
        pageNum: pageConfig.pageNum,
        pageSize: pageConfig.pageSize
      },
      success: function (res) {
        if (!isCurrent()) return;
        if (res && res.code == "200" && res.data && isRecordList(res.data.rows)) {
          let list = that._filterFavoriteRead('topics', res.data.rows, removalVersion);
          
          // 处理日期格式
          list = that.processListData(list);
          loadedPages.topics = pageConfig.pageNum;
          
          // 如果是第一页，直接替换；否则追加
          if (pageConfig.pageNum === 1) {
            that.setData({ 
              ztList1: list,
              hasMore1: res.data.rows.length >= pageConfig.pageSize,
              errorMsg: '',
            });
          } else {
            that.setData({ 
              ztList1: [...that.data.ztList1, ...list],
              hasMore1: res.data.rows.length >= pageConfig.pageSize,
              errorMsg: '',
            });
          }
        } else {
          finishLoadError(res, '喜欢列表加载失败');
        }
      },
      fail: function (res, statusCode) {
        if (!isCurrent()) return;
        finishLoadError(res, '喜欢列表加载失败', statusCode);
      },
      successStatusAbnormal: function (res, statusCode) {
        if (!isCurrent()) return;
        finishLoadError(res, '服务暂不可用，请稍后重试', statusCode);
      },
      complete: function () {
        if (!isCurrent()) return;
        const shouldStopPull = stopPullAfterComplete || that._stopPullWithCurrent;
        that._stopPullWithCurrent = false;
        that.setData({ loading: false, refreshing: false });
        if (shouldStopPull && typeof wx.stopPullDownRefresh === 'function') wx.stopPullDownRefresh();
        that._flushFavoriteShowRefresh();
      }
    });
  },

  /**
   * 处理列表数据（日期格式化等）
   */
  processListData: function(list) {
    for (let i = 0; i < list.length; i++) {
      if (list[i]['startDate']) {
        list[i]['startDate'] = list[i]['startDate'].split(' ')[0].replaceAll('-', '.');
      }
      if (list[i]['endDate']) {
        list[i]['endDate'] = list[i]['endDate'].split(' ')[0].replaceAll('-', '.');
      }
      // CU-M-141：卡面上的「基本类型」。接口给的是类别数组，封面文案只有一行，收成一行文本后交给 CSS 省略号截断；
      // 没配类别的主题 sysCategoryList 会缺，此时类型行为空，卡面只剩名称。
      list[i]['categoryText'] = (list[i]['sysCategoryList'] || [])
        .map((category) => (category && category.categoryName) || '')
        .filter(Boolean)
        .join(' · ');
    }
    return list;
  },

  // PC-180:推荐偏好设置与「不再推荐」尚未上线——说明只讲现状,不给任何会被理解成已能排除推荐的入口。
  showRecommendNote() {
    modal.show({
      title: '收藏与推荐',
      content: '推荐会参考你的浏览、点赞等行为。取消收藏只会把内容从这里移除，不会撤回已经记录的推荐兴趣。目前还不能设置推荐偏好，也不能把某个内容排除出推荐。',
      showCancel: false,
      confirmText: '知道了',
    });
  },

  // PC-024:取消收藏结果未知时按 like_list 逐页回读。读到该主题=没取消成功,放锁可再试;
  // 读完全部仍没有=已取消,按 ID 摘卡。任何一页失败/畸形或超出回读上限都保持未知锁,下次点击再核对。
  _readbackTopicUnlike(requestKey, request, owner, generation) {
    const that = this;
    const pageSize = 50;
    const maxPages = 10;
    const isCurrent = () => that._isFavoriteResponseCurrent(owner, generation)
      && that._unlikeRequests && that._unlikeRequests[requestKey] === request;
    const stillUnknown = () => { request.reading = false; toast('取消结果未确认，请稍后再点确认'); };
    request.reading = true;
    const readPage = function (pageNum) {
      app.sendRequest({
        hideLoading: true, silentError: true, url: '/api/topic/like_list', method: 'POST',
        data: { pageNum: pageNum, pageSize: pageSize },
        success: function (res) {
          if (!isCurrent()) return;
          const rows = res && res.code == '200' && res.data && isRecordList(res.data.rows) ? res.data.rows : null;
          if (!rows) return stillUnknown();
          if (rows.some(row => String(row.id) === requestKey)) {
            delete that._unlikeRequests[requestKey];
            toast('取消没成功，可以再试一次');
            return;
          }
          if (rows.length >= pageSize) {
            if (pageNum >= maxPages) return stillUnknown();
            return readPage(pageNum + 1);
          }
          delete that._unlikeRequests[requestKey];
          that._recordFavoriteRemoval('topics', requestKey);
          that.setData({ ztList1: that.data.ztList1.filter(topic => String(topic.id) !== requestKey) });
          toast('已取消收藏');
        },
        fail: function () { if (isCurrent()) stillUnknown(); },
        successStatusAbnormal: function () { if (isCurrent()) stillUnknown(); },
      });
    };
    readPage(1);
  },

  /**
   * 取消路线点赞
   */
  topicUnlike: function(e) {
    const that = this;
    this._syncFavoriteAccount();
    const owner = this._favoriteOwner; const generation = this._favoriteGeneration;
    const id = e.currentTarget.dataset.id;
    if (id == null || id === '') return;
    const requestKey = String(id);
    // A-07:后端 /api/topic/like 是「查无即插」的 toggle(type=0 被忽略),
    // 取消请求在途时再点一次会把刚删的收藏重新插回去。per-id in-flight 锁挡住第二次发送。
    that._unlikeRequests = that._unlikeRequests || Object.create(null);
    const pendingUnlike = that._unlikeRequests[requestKey];
    if (pendingUnlike) {
      // PC-024:结果未知时再点只回读 like_list,不再发 toggle(否则可能把已取消的收藏插回来)
      if (pendingUnlike.unknown && !pendingUnlike.reading) that._readbackTopicUnlike(requestKey, pendingUnlike, owner, generation);
      return;
    }
    const prompts = that._unlikePrompts || (that._unlikePrompts = Object.create(null));
    if (prompts[requestKey]) return;
    const prompt = {};
    prompts[requestKey] = prompt;
    const finishPrompt = () => {
      if (prompts[requestKey] !== prompt) return false;
      delete prompts[requestKey];
      return true;
    };

    modal.show({
      title: '提示',
      content: '确定要取消点赞吗？',
      success: function (res) {
        if (!finishPrompt() || !that._isFavoriteResponseCurrent(owner, generation)) return;
        if (res.confirm) {
          if (that._unlikeRequests[requestKey]) return;
          const request = {};
          that._unlikeRequests[requestKey] = request;
          const isCurrent = () => that._isFavoriteResponseCurrent(owner, generation)
            && that._unlikeRequests[requestKey] === request;
          app.sendRequest({
            url: '/api/topic/like',
            method: "POST",
            data: {
              id: id,
              type: 0 // 0表示取消点赞
            },
            success: function (res) {
              if (!isCurrent()) return;
              if (res.code == "200") {
                that._recordFavoriteRemoval('topics', requestKey);
                toast.success('取消点赞成功');
                
                // 回包顺序可能与点击顺序不同，按 ID 删除避免旧 index 误删其他收藏。
                const ztList1 = that.data.ztList1.filter(topic => String(topic.id) !== requestKey);
                that.setData({ ztList1 });
              } else {
                toast(res.msg || '操作失败');
              }
            },
            fail: function (res) {
              if (!isCurrent()) return;
              // 断网=结果未知:锁不放、原卡片不摘,经 like_list 回读后再决定
              request.unknown = true;
              toast('取消结果未确认，正在确认');
            },
            complete: function () {
              if (!isCurrent()) return;
              if (request.unknown) that._readbackTopicUnlike(requestKey, request, owner, generation);
              else delete that._unlikeRequests[requestKey];
            }
          });
        }
      },
      fail: finishPrompt,
      complete: finishPrompt
    });
  },

  // 「···」只记帖文 id:列表随时可能刷新,到选项回来时按 id 找回当前行再取消收藏。
  onPostMore(e) {
    const item = this.data.postList[e.detail && e.detail.index];
    if (!item || !item.id) return;
    this._postMoreId = String(item.id);
    this.setData({ postMoreShow: true });
  },
  closePostMore() {
    this._postMoreId = '';
    this.setData({ postMoreShow: false });
  },
  onPostMoreSelect(e) {
    const id = this._postMoreId;
    this.closePostMore();
    if (!id || !e || !e.detail || e.detail.index !== 0) return;
    const index = this.data.postList.findIndex(row => row && String(row.id) === id);
    if (index >= 0) this.postUnlike({ detail: { index: index } });
  },

  postUnlike: function(e) {
    this._syncFavoriteAccount();
    const owner = this._favoriteOwner; const generation = this._favoriteGeneration;
    const index = e.detail && e.detail.index;
    const item = this.data.postList[index];
    if (!item || !item.id) return;
    const requestKey = String(item.id);
    this._postUnlikeRequests = this._postUnlikeRequests || Object.create(null);
    if (this._postUnlikeRequests[requestKey]) return;
    const request = {};
    this._postUnlikeRequests[requestKey] = request;
    const isCurrent = () => this._isFavoriteResponseCurrent(owner, generation)
      && this._postUnlikeRequests[requestKey] === request;
    community.request({
      hideLoading: true,
      silentError: true,
      url: '/api/creativesquare/bookmark',
      method: 'POST',
      data: {
        id: item.id,
        bookmark: 0,
        request_id: 'bookmark-' + item.id + '-0-' + Date.now().toString(36)
      },
      success: (res) => {
        if (!isCurrent()) return;
        if (!res || res.code != '200') {
          toast((res && res.msg) || '操作失败');
          return;
        }
        this._recordFavoriteRemoval('posts', requestKey);
        const postList = this.data.postList.filter(post => String(post.id) !== requestKey);
        this.setData({ postList: postList });
      },
      fail: () => {
        if (!isCurrent()) return;
        toast('网络错误，请重试');
      },
      complete: () => {
        if (!isCurrent()) return;
        delete this._postUnlikeRequests[requestKey];
      }
    });
  },

  goPost: function(e) {
    const id = e.detail && e.detail.postId;
    if (id) wx.navigateTo({ url: '/pages/square/detail/index?id=' + id });
  },

  /**
   * 跳转到路线详情
   */
  goTopic: function(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({
      url: '/pages/topic/index/index?id=' + id,
    });
  },

  /**
   * 用户点击右上角分享
   */
  onShareAppMessage: function (res) {
    if (res.from === 'button') {
      const dataset = res.target.dataset;
      if (dataset.postId) {
        return {
          title: '分享一条城瘾动态',
          path: '/pages/square/detail/index?id=' + dataset.postId
        };
      }
      const id = dataset.id;
      const name = dataset.name;
      const imageUrl = dataset.img;
      
      return {
        title: `推荐路线：${name}`,
        path: `/pages/topic/index/index?id=${id}`,
        imageUrl: imageUrl,
        success: function (res) {
          toast.success('分享成功');
        },
        fail: function (res) {
          toast('分享取消');
        }
      };
    }
    
    return {
      title: '我的收藏',
      path: '/pages/mylike/mylike',
      success: function (res) {
        toast.success('分享成功');
      }
    };
  },

  // 登录引导「登录」:重登成功后从第一页重读当前页签
  loginFavorites: function() {
    retryLogin(this, app, this.refreshData);
  },

  /**
   * 刷新数据
   */
  refreshData: function(fromPullDown) {
    const stopPullAfterComplete = fromPullDown === true;
    if (this.data.loading) {
      if (stopPullAfterComplete) this._stopPullWithCurrent = true;
      return;
    }
    const pageConfig = {
      pageNum: 1,
      pageSize: 10
    };
    
    this.setData({ pageConfig, errorMsg: '' });

    this.loadLikeData(stopPullAfterComplete);
  },

  /**
   * 页面上拉触底事件的处理函数
   */
  onReachBottom() {
    const that = this;
    const pageConfig = that.data.pageConfig;
    
    if (that.data.loading) return;
    
    // 检查是否还有更多数据
    const hasMore = that.data.activeFavoriteTab === 'posts' ? that.data.hasMorePosts : that.data.hasMore1;
    if (!hasMore) return;
    
    // 加载下一页
    that.setData({ pageConfig: { pageNum: pageConfig.pageNum + 1, pageSize: pageConfig.pageSize } });
    that.loadLikeData();
  },

  /**
   * 页面相关事件处理函数--监听用户下拉动作
   */
  onPullDownRefresh() {
    this.refreshData(true);
  }
});
