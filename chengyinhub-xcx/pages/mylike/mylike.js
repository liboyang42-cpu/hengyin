const communityApi = require('../../utils/community-post-api.js');
const { toPostView } = require('../../utils/community-post-view.js');
const modal = require('../../utils/modal.js');
const toast = require('../../utils/toast.js');
const app = getApp();
const { isRecordList } = require('../../utils/response-shape.js');
const policy = require('../../utils/identity/identity-policy.js');
const merchantTheme = require('../../utils/merchant-theme.js');

Page({
    communityClient() {
      const owner = String(app.getUserID() || '');
      if (!this._community || this._communityOwner !== owner) {
        if (this._community) this._community.dispose();
        this._community = communityApi.createClient(app, wx);
        this._communityOwner = owner;
        this._communityActions = Object.create(null);
      }
      return this._community;
    },

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
    if (this._communityOwner != null && this._communityOwner !== String(app.getUserID() || '')) {
      this.communityClient(); this._bookmarkEpoch = (this._bookmarkEpoch || 0) + 1;
      this._bookmarkCursor = null; this.setData({ loading: false, postList: [], pageConfig: { pageNum: 1, pageSize: 10 } });
    }
    this.syncViewTheme();
    if (!this._hasShown) {
      this._hasShown = true;
      return;
    }
    // 返回页面后刷新数据；首展交给 onLoad，避免同一页并发两次 page=1。
    this.refreshData();
  },

  onHide() { merchantTheme.merchantPageRestore(); },
  onUnload() { this._unloaded = true; if (this._community) this._community.dispose(); merchantTheme.merchantPageRestore(); },

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

  getPostFavoriteList: function(stopPullAfterComplete, loadMore) {
    this.communityClient();
    const owner = this._communityOwner, epoch = (this._bookmarkEpoch || 0) + 1;
    this._bookmarkEpoch = epoch;
    const cursor = loadMore ? this._bookmarkCursor : null;
    const current = () => !this._unloaded && this._bookmarkEpoch === epoch
      && String(app.getUserID() || '') === owner && this.data.activeFavoriteTab === 'posts';
    this.setData({ loading: true, refreshing: !loadMore && this.data.postList.length > 0 });
    const fail = res => {
      if (!current() || Number(res && res.code) === 499) return;
      const message = app.getRequestErrorMessage(res, '收藏帖文加载失败');
      this.setData({ errorMsg: message });
      if (this.data.postList.length) toast(message);
    };
    this.communityClient().bookmarks({ query: { limit: this.data.pageConfig.pageSize, cursor }, current, autoErrorToast: false,
      success: res => {
        if (res.code !== 200) { fail(res); return; }
        const rows = res.data.items.map(toPostView).map(row => Object.assign(row, { picList: row.pics ? row.pics.split(';') : [] }));
        const existing = loadMore ? this.data.postList : [], seen = new Set(existing.map(row => String(row.id)));
        this._bookmarkCursor = res.data.nextCursor;
        this.setData({ postList: existing.concat(rows.filter(row => !seen.has(String(row.id)))), hasMorePosts: res.data.hasMore, errorMsg: '' });
      }, fail,
      complete: () => {
        if (!current()) return;
        const stop = stopPullAfterComplete || this._stopPullWithCurrent;
        this._stopPullWithCurrent = false;
        this.setData({ loading: false, refreshing: false });
        if (stop && typeof wx.stopPullDownRefresh === 'function') wx.stopPullDownRefresh();
      }
    });
  },

  /**
   * 获取路线点赞列表
   */
  getTopicLikeList: function(stopPullAfterComplete) {
    const that = this;
    const pageConfig = that.data.pageConfig;
    const hasOldContent = that.data.ztList1.length > 0;
    const finishLoadError = function (res, fallback) {
      const message = app.getRequestErrorMessage(res, fallback);
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
        if (res && res.code == "200" && res.data && isRecordList(res.data.rows)) {
          let list = res.data.rows;
          
          // 处理日期格式
          list = that.processListData(list);
          
          // 如果是第一页，直接替换；否则追加
          if (pageConfig.pageNum === 1) {
            that.setData({ 
              ztList1: list,
              hasMore1: list.length >= pageConfig.pageSize,
              errorMsg: '',
            });
          } else {
            that.setData({ 
              ztList1: [...that.data.ztList1, ...list],
              hasMore1: list.length >= pageConfig.pageSize,
              errorMsg: '',
            });
          }
        } else {
          finishLoadError(res, '喜欢列表加载失败');
        }
      },
      fail: function (res) {
        finishLoadError(res, '喜欢列表加载失败');
      },
      successStatusAbnormal: function (res) {
        finishLoadError(res, '服务暂时不可用，请稍后再试');
      },
      complete: function () {
        const shouldStopPull = stopPullAfterComplete || that._stopPullWithCurrent;
        that._stopPullWithCurrent = false;
        that.setData({ loading: false, refreshing: false });
        if (shouldStopPull && typeof wx.stopPullDownRefresh === 'function') wx.stopPullDownRefresh();
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
    const index = e.currentTarget.dataset.index;
    const id = e.currentTarget.dataset.id;
    const requestKey = String(id);
    // A-07:后端 /api/topic/like 是「查无即插」的 toggle(type=0 被忽略),
    // 取消请求在途时再点一次会把刚删的收藏重新插回去。per-id in-flight 锁挡住第二次发送。
    that._unlikeRequests = that._unlikeRequests || Object.create(null);
    if (that._unlikeRequests[requestKey]) return;

    modal.show({
      title: '提示',
      content: '确定要取消点赞吗？',
      success: function (res) {
        if (res.confirm) {
          if (that._unlikeRequests[requestKey]) return;
          that._unlikeRequests[requestKey] = true;
          app.sendRequest({
            url: '/api/topic/like',
            method: "POST",
            data: {
              id: id,
              type: 0 // 0表示取消点赞
            },
            success: function (res) {
              if (res.code == "200") {
                toast.success('取消点赞成功');
                
                // 从列表中移除
                const ztList1 = [...that.data.ztList1];
                ztList1.splice(index, 1);
                that.setData({ ztList1 });
              } else {
                toast(res.msg || '操作失败');
              }
            },
            fail: function (res) {
              toast('网络错误，请重试');
            },
            complete: function () {
              delete that._unlikeRequests[requestKey];
            }
          });
        }
      }
    });
  },

  postUnlike: function(e) {
    if (this._communityOwner != null && this._communityOwner !== String(app.getUserID() || '')) { toast('登录身份已变化，请刷新'); return; }
    const item = this.data.postList[e.detail && e.detail.index];
    if (!item || !item.id) return;
    this.communityClient();
    const owner = this._communityOwner, id = String(item.id), epoch = this._bookmarkEpoch;
    const current = () => !this._unloaded && this._bookmarkEpoch === epoch && String(app.getUserID() || '') === owner;
    const key = 'BOOKMARK:' + id;
    const action = this._communityActions[key] || { requestId: 'bookmark-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) };
    if (action.busy && action.current && action.current()) return;
    action.current = current; action.busy = true; this._communityActions[key] = action;
    const fail = res => { if (current() && Number(res && res.code) !== 499) toast(app.getRequestErrorMessage(res, '取消收藏结果未确认，请重试')); };
    this.communityClient().removePostAction({ postId: item.id, actionType: 'BOOKMARK', command: { requestId: action.requestId }, current, autoErrorToast: false,
      success: res => {
        if (res.code !== 200) { fail(res); return; }
        const row = toPostView(res.data);
        if (row.isBookmarked !== false) { fail({ msg: '取消收藏结果未确认，请重试' }); return; }
        delete this._communityActions[key];
        this.setData({ postList: this.data.postList.filter(post => String(post.id) !== id) });
      }, fail, complete: () => { if (current()) action.busy = false; }
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
    
    if (that.data.activeFavoriteTab === 'posts') { that.getPostFavoriteList(false, true); return; }
    // 主题保留原页码合同
    pageConfig.pageNum += 1;
    that.setData({ pageConfig });
    that.loadLikeData();
  },

  /**
   * 页面相关事件处理函数--监听用户下拉动作
   */
  onPullDownRefresh() {
    this.refreshData(true);
  }
});
