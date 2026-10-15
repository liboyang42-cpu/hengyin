const modal = require('../../utils/modal.js');
const toast = require('../../utils/toast.js');
const app = getApp();
const { isRecordList } = require('../../utils/response-shape.js');
const policy = require('../../utils/identity/identity-policy.js');
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
    ztList1: [], // 路线列表
    loading: false,
    refreshing: false,
    errorMsg: '',
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
    this.loadLikeData();
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
    if (this.data.loading) {
      this._favoriteShowRefresh = { owner: this._favoriteOwner, generation: this._favoriteGeneration };
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
    this.setData({ postList: [], ztList1: [], loading: false, refreshing: false, errorMsg: '',
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
    if (pending && this._isFavoriteResponseCurrent(pending.owner, pending.generation)) this.refreshData();
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
    that.setData({ loading: true, refreshing: refreshing, errorMsg: '' });
    
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
    const finishLoadError = function (res, fallback) {
      const message = app.getRequestErrorMessage(res, fallback);
      that.setData({ pageConfig: { pageNum: previousPage, pageSize: pageConfig.pageSize } });
      that.setData({ refreshing: false, errorMsg: hasOldContent ? '' : message });
    };
    app.sendRequest({
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
          that.setData({ postList: postList, hasMorePosts: res.data.rows.length >= pageConfig.pageSize, errorMsg: '' });
        } else finishLoadError(res, '收藏帖文加载失败');
      },
      fail: function (res) {
        if (!isCurrent()) return;
        finishLoadError(res, '收藏帖文加载失败');
      },
      successStatusAbnormal: function (res) {
        if (!isCurrent()) return;
        finishLoadError(res, '服务暂时不可用，请稍后再试');
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
    const finishLoadError = function (res, fallback) {
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
      fail: function (res) {
        if (!isCurrent()) return;
        finishLoadError(res, '喜欢列表加载失败');
      },
      successStatusAbnormal: function (res) {
        if (!isCurrent()) return;
        finishLoadError(res, '服务暂时不可用，请稍后再试');
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
    if (that._unlikeRequests[requestKey]) return;
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
                
                // 从列表中移除
                const ztList1 = that.data.ztList1.filter(item => String(item.id) !== requestKey);
                that.setData({ ztList1 });
              } else {
                toast(res.msg || '操作失败');
              }
            },
            fail: function (res) {
              if (!isCurrent()) return;
              toast('网络错误，请重试');
            },
            complete: function () {
              if (!isCurrent()) return;
              delete that._unlikeRequests[requestKey];
            }
          });
        }
      },
      fail: finishPrompt,
      complete: finishPrompt
    });
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
    app.sendRequest({
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
