const reportSheet = require('../utils/community-report-sheet.js'); const pageMotion = require('../../../utils/page-motion.js');
const modal = require('../../../utils/modal.js');
const toast = require('../../../utils/toast.js');
const { toTimestamp } = require('../../../utils/datetime');
const app = getApp();
const community = require('../../../utils/community-post-api.js').createClient(app, wx);
const analytics = require('../../../utils/analytics.js');
const { getCurrentLocation } = require('../../../utils/location/location-manager.js');
const merchantTheme = require('../../../utils/merchant-theme.js');
const { appendByIndex } = require('../../../utils/list-append.js');
const {
  buildFeedPlaySubtitle,
  resolveFeedPlayCover,
  hasFeedPlayCover,
  resolveCompletionShareImage,
  isRoamResultShare,
  isCompletedShare,
} = require('../../../utils/feed-play-card.js');
const { remixTopicTemplate } = require('../utils/topic-template-remix.js');
const { isRecordList } = require('../../../utils/response-shape.js');
const mediaPreview = require('../../../utils/media-preview.js');

// 开场倒计时文案:天/小时/分三档取整,不到 1 分钟按「即将开始」收口。
// 与 activity/list 的 fmtDur 同一套档位,避免两处对同一活动给出不同口径。
function fmtCountdown(ms) {
  // 不足 1 分钟说「马上开始」而不是「即将开始」:横幅左上角的 kicker already 写着
  // 「即将开始」,同一行重复两遍读起来像渲染出错。
  if (!(ms > 0)) return '马上开始';
  var h = Math.floor(ms / 3600000);
  if (h >= 24) return Math.floor(h / 24) + ' 天后开始';
  if (h >= 1) return h + ' 小时后开始';
  var m = Math.floor(ms / 60000);
  return m >= 1 ? m + ' 分钟后开始' : '马上开始';
}

function feedIndexFromEvent(e) {
  if (e && e.detail && e.detail.index !== undefined) return e.detail.index;
  return e && e.currentTarget && e.currentTarget.dataset
    ? e.currentTarget.dataset.index
    : undefined;
}

Page({
  data: {
    reportReasonShow: false,
    reportReasonItems: [],
    list: [],//列表数组
    page_no: 1,//页码
    hasMore: false,//还有更多
    nodata: false,//暂无数据
    loadError: false,
    loadMoreError: '',
    isLoadingMore: false,
    listLoading: true,// 首屏取列表中:骨架屏用。默认 true —— onLoad 到首个响应之间也是加载中,
                      // 默认 false 会让首帧闪一下空白再出内容(2026-08-18 A07 拍到的就是那一屏纯黑)
    isSticky: false,
    userId: 0,

    postActionShow: false,   // H050/H051 三合一操作弹层(编辑/删除/举报)
    // 当前点击的列表项索引 2026-09-16 起是实例属性 this._actionIndex(三合一弹层不渲染它,
    // 只作行定位用的内部状态;setData 死数据字段门禁对 A2「只当内部状态」判红)
    currentActionId: '', currentActionSummary: '',   // 当前操作帖的 id / 正文前段(给 cy-post-actions;摘要用于 CU-C-107 的删除确认)
    currentActionOwner: false, // 当前操作帖是不是本人的(决定编辑/删除行是否出现)
    currentActionBookmarked: false, // 「···」里收藏行的文案(收藏 / 取消收藏)

    avatar: '',
    nickname: '',

    communityPostRead: false,
    communityPostWrite: false,
    composeShow: false,      // 新建帖文弹窗
    avatarPopShow: false,    // 点头像的 T5 胶囊
    avatarPopAnchor: null,
    avatarPopItems: [],

    // 「即将开始」聚合横幅:null = 当前没有临近活动 ⇒ 整块不渲染(不留空标题)
    upcoming: null,
    upcomingCards: [],

    // 地图抽屉（浮在帖文上层，上滑收起）
    pageH: 667,
    tabBarH: 100,
    drawerPeekH: 80,
    drawerCapsuleH: 88,
    drawerDockBarH: 80,
    drawerDockH: 100,
    drawerExpandedH: 600,
    drawerH: 600,
    drawerCollapsed: false,
    drawerSnapping: false,
    statusBarHeight: 44,
    navBarHeight: 44,
    mapLatitude: 31.2304,
    mapLongitude: 121.4737,
    mapScale: 14,

    // 新增：页面是否正在刷新
    isRefreshing: false
  },

  // 新增：图片加载完成事件
  onImageLoad: function(e) {
    const itemIndex = e.currentTarget.dataset.itemIndex;
    const picIndex = e.currentTarget.dataset.picIndex;
    const { width, height } = e.detail;
    
    // 计算宽高比
    const aspectRatio = width / height;
    
    // 计算基于固定高度的宽度（固定高度600rpx）
    const fixedHeight = 600; // 固定高度600rpx
    let calculatedWidth = fixedHeight * aspectRatio;
    
    // 限制最大宽度，避免过宽
    const maxWidth = 1000;
    const finalWidth = calculatedWidth > maxWidth ? maxWidth : calculatedWidth;
    
    // 确保最小宽度
    const minWidth = 200;
    const finalWidthWithMin = finalWidth < minWidth ? minWidth : finalWidth;
    
    // 获取当前列表项
    const list = this.data.list;
    if (itemIndex >= 0 && itemIndex < list.length) {
      const item = list[itemIndex];
      
      // 初始化imageWidths和imageHeights数组
      if (!item.imageWidths) item.imageWidths = [];
      if (!item.imageHeights) item.imageHeights = [];
      
      // 更新宽度和高度
      item.imageWidths[picIndex] = finalWidthWithMin;
      item.imageHeights[picIndex] = fixedHeight;
      
      // 更新列表数据
      const key = `list[${itemIndex}]`;
      this.setData({
        [key]: item
      });
    }
  },

  lickClick(e) {
    if (app.ensureLoginFor && !app.ensureLoginFor('like', () => this.lickClick(e), { owner: this, target: () => [this.data.list[feedIndexFromEvent(e)] && this.data.list[feedIndexFromEvent(e)].id] })) return; // 2026-10-24 游客写前登录
    var that = this
    let index = feedIndexFromEvent(e);
    let type = (e.detail && e.detail.type) || e.currentTarget.dataset.type;
    let item = that.data.list[index]
    if (!item || !item.id) return;
    let id = item['id']
    const requestKey = String(id);
    that._likeRequests = that._likeRequests || Object.create(null);
    if (that._likeRequests[requestKey]) return;
    that._likeRequests[requestKey] = true;
    
    community.request({
      hideLoading: true,
      silentError: true,
      url: '/api/creativesquare/like',
      method: "POST",
      data: {
        id: id,
        type: type
      },
      success: function (res) {
        if (res.code != "200") {
          toast(res.msg || '操作失败');
          return;
        }
        // 赞/取消赞在途时列表可能已重拉换序:按 id 找回此刻位置,帖子已不在列表就丢弃回包,不按旧下标串写到别的帖子
        let newlist = that.data.list || []
        let currentIndex = newlist.findIndex(row => row && String(row.id) === requestKey); if (currentIndex < 0) return;
        let currentItem = Object.assign({}, newlist[currentIndex], { isLiked: res.data.isLiked, likeCount: res.data.likeCount, version: res.data.version });

        // 更新列表数据
        const key = `list[${currentIndex}]`;
        if (currentIndex >= 0) that.setData({
          [key]: currentItem
        });
        analytics.track('content_like', {
          bizType: 'square',
          bizId: id,
          properties: {
            actionType: type,
            isLiked: currentItem.isLiked
          }
        });
      },
      fail: function (res) {
        toast('网络错误，请重试');
      },
      complete: function () {
        delete that._likeRequests[requestKey];
      }
    })
  },

  favoriteClick(e) {
    if (app.ensureLoginFor && !app.ensureLoginFor('like', () => this.favoriteClick(e), { owner: this, target: () => [this.data.list[feedIndexFromEvent(e)] && this.data.list[feedIndexFromEvent(e)].id] })) return; // 2026-10-24 游客写前登录
    const index = feedIndexFromEvent(e);
    const item = this.data.list[index];
    if (!item || !item.id) return;
    const requestKey = String(item.id);
    this._favoriteRequests = this._favoriteRequests || Object.create(null);
    if (this._favoriteRequests[requestKey]) return;
    this._favoriteRequests[requestKey] = true;
    const next = item.isBookmarked == 1 ? 0 : 1;
    community.request({
      hideLoading: true,
      silentError: true,
      url: '/api/creativesquare/bookmark',
      method: 'POST',
      data: {
        id: item.id,
        bookmark: next,
        request_id: 'bookmark-' + item.id + '-' + next + '-' + Date.now().toString(36)
      },
      success: (res) => {
        if (!res || res.code != '200') {
          toast((res && res.msg) || '操作失败');
          return;
        }
        const current = this.data.list[index];
        if (!current || String(current.id) !== String(item.id)) return;
        const list = this.data.list.slice();
        current.isBookmarked = res.data.isBookmarked;
        list[index] = current;
        this.setData({ list: list });
      },
      fail: () => toast('网络错误，请重试'),
      complete: () => { delete this._favoriteRequests[requestKey]; }
    });
  },

  followClick(e) {
    if (app.ensureLoginFor && !app.ensureLoginFor('like', () => this.followClick(e), { owner: this, target: () => [this.data.list[feedIndexFromEvent(e)] && this.data.list[feedIndexFromEvent(e)].id, this.data.list[feedIndexFromEvent(e)] && this.data.list[feedIndexFromEvent(e)].memberId] })) return; // 2026-10-24 游客写前登录
    var that = this
    const index = feedIndexFromEvent(e);
    let topicList = that.data.list
    let item = topicList[index]
    if (!item || !item.memberId) return;
    const requestKey = String(item.memberId);
    that._followRequests = that._followRequests || Object.create(null);
    if (that._followRequests[requestKey]) return;
    that._followRequests[requestKey] = true;
    // R10-07:带上「期望状态」,丢响应后重试同一意图不会反转服务端关系。
    const intendedFollow = item.isFollowTheUser ? 0 : 1;
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/user/follow/action',
      method: "POST",
      data: {
        follow_member_id: item['memberId'],
        follow: intendedFollow,
      },
      success: function (res) {
        if (res.code == "200") {
          const nextFollowState = intendedFollow;
          const pos = that.data.list.findIndex(function (row) { return row && String(row.id) === String(item.id); });
          if (pos >= 0) {
            const nextList = that.data.list.slice();
            nextList[pos] = Object.assign({}, nextList[pos], { isFollowTheUser: nextFollowState });
            that.setData({ list: nextList });
          }

          toast(nextFollowState ? '关注成功' : '已取消关注');
        } else {
          toast(res.msg || '操作失败');
        }
      },
      fail: function (res) {
        toast('网络错误，请重试');
      },
      complete: function () {
        delete that._followRequests[requestKey];
      }
    })
  },

  // 显示操作弹层(卡片 more 入口)。行数据当场解析成 id / owner 交给组件,
  // 组件不认 index —— 列表随时可能刷新,index 到发请求时可能已经指错行。
  showAction: function (e) {
    const index = feedIndexFromEvent(e);
    const item = this.data.list[index] || {};
    this._actionIndex = index;
    this._actionContext = { id: String(item.id || ''), owner: String(app.getUserID() || '') };
    this.setData({
      postActionShow: true,
      currentActionId: item.id == null ? '' : String(item.id),
      currentActionSummary: item.contents || '',
      currentActionOwner: !!(item.memberId && String(item.memberId) === String(this.data.userId)),
      currentActionBookmarked: item.isBookmarked == 1
    });
  },

  // 关闭操作弹层
  closePostActions: function () {
    this._actionIndex = -1;
    this.setData({
      postActionShow: false,
      currentActionId: '',
      currentActionSummary: '',
      currentActionOwner: false,
      currentActionBookmarked: false
    });
  },

  // H050/H051:三合一弹层的分发。delete 到这里时确认闸已在组件内部过完,不再自建第二道。
  onPostAction: function (e) {
    const detail = ((e || {}).detail || {}), context = this._actionContext;
    if (!context || !detail.id || String(detail.id) !== context.id || context.owner !== String(app.getUserID() || '') || this._unloaded) return;
    if (detail.action === 'edit') { this.editCurrentSquarePost(detail.id); return; }
    if (detail.action === 'delete') { this.deleteCurrentSquarePost(detail.id); return; }
    if (detail.action === 'report') { this.reportSquare(detail.id); return; }
    // 收藏(2026-10-29 从动作行挪进「···」):按 id 找回当前行,走原来的收藏请求
    if (detail.action === 'bookmark') {
      const index = this.data.list.findIndex(row => row && String(row.id) === String(detail.id));
      if (index >= 0) this.favoriteClick({ detail: { index: index } });
      return;
    }
  },

  // 删除(H051):软删 + 归属校验都在后端;成功后本地摘掉这一行(与举报同一口径)。
  deleteCurrentSquarePost: function (id = this.data.currentActionId) {
    const item = this.data.list.find(row => String(row.id) === String(id));
    if (!item || !item.id || this._deletingPost || String(item.memberId) !== String(app.getUserID() || '')) return;
    const that = this;
    const owner = String(app.getUserID() || ''), current = () => !that._unloaded && owner === String(app.getUserID() || '');
    const pa = this.selectComponent && this.selectComponent('#post-actions');
    this._deletingPost = true;
    community.request({
      hideLoading: true,
      url: '/api/creativesquare/delete',
      method: 'POST',
      data: { id: item.id },
      success(res) {
        if (!current()) return;
        that._deletingPost = false;
        if (res && res.code == '200') {
          if (pa) pa.deleteDone();
          const list = that.data.list.filter(function (row) { return !row || String(row.id) !== String(item.id); });
          if (list.length !== that.data.list.length) that.setData({ list: list, nodata: list.length < 1 });
          that.closePostActions();
        } else if (pa) {
          pa.deleteFailed((res && res.msg) || '删除失败');
        }
      },
      fail() {
        if (!current()) return;
        that._deletingPost = false;
        if (pa) pa.deleteFailed('网络异常，请重试');
      }
    });
  },

  // 编辑(H050):行数据直接进新建面板的编辑态,正文预填、关联带上(不发图片/地点,后端保留原值)
  editCurrentSquarePost: function (id = this.data.currentActionId) {
    if (typeof app.canUseCommunity !== 'function' || !app.canUseCommunity(true)) return;
    const item = this.data.list.find(row => String(row.id) === String(id));
    if (!item || !item.id || String(item.memberId) !== String(app.getUserID() || '')) { this.closePostActions(); return; }
    this.closePostActions();
    // editPost 先落地、show 后翻:组件的 show 观察器要读到编辑数据(两次 setData 顺序确定)
    this.setData({ composeEdit: {
      id: String(item.id),
      contents: item.contents || '',
      dataId: item.dataId,
      dataType: item.dataType
    } });
    this.setData({ composeShow: true });
  },

  onReportReasonSelect(e) { reportSheet.select(this, e); },
  onReportReasonCancel() { reportSheet.cancel(this); },

  reportSquare: function (id = this.data.currentActionId) {
    if (app.ensureLoginFor && !app.ensureLoginFor('report', () => this.reportSquare(id), { owner: this, target: () => [id, this.data.list.some(row => String(row.id) === String(id))] })) return; // 2026-10-24 游客写前登录
    const that = this;
    const owner = String(app.getUserID() || ''), current = () => !that._unloaded && owner === String(app.getUserID() || '');
    const item = that.data.list.find(row => String(row.id) === String(id));
    if (!item || !item.id) {
      that.closePostActions();
      return;
    }
    modal.show({
      title: '举报内容',
      content: '确认举报这条内容？举报后将提交平台审核。',
      confirmText: '举报',
      success(res) {
        if (!res.confirm || !current()) {
          return;
        }
        community.request({
          hideLoading: true,
          url: '/api/creativesquare/report',
          current,
          selectReportReason: (items, task) => reportSheet.open(that, items, task),
          method: 'POST',
          data: {
            id: item.id
          },
          success(resp) {
            if (!current() || Number(resp && resp.code) === 499) return;
            if (resp.code == '200') {
              const list = that.data.list.filter(function (row) { return !row || String(row.id) !== String(item.id); });
              if (list.length !== that.data.list.length) {
                that.setData({
                  list: list,
                  nodata: list.length < 1
                });
              }
              toast('已提交举报');
            } else {
              toast(resp.msg || '举报失败');
            }
          },
          fail() {
            if (!current()) return;
            toast('网络异常，请重试');
          },
          complete() {
            if (!current()) return;
            that.closePostActions();
          }
        });
      }
    });
  },

  onReady() {
    // 使用 createSelectorQuery 获取滚动节点，这里以页面根节点为例
    const query = wx.createSelectorQuery().in(this);
    query.selectViewport().scrollOffset((res) => {
      if (res.scrollTop > 0) {
        this.setData({ isSticky: true });
      } else {
        this.setData({ isSticky: false });
      }
    }).exec();
  },

  // 帖文 scroll-view 滚动：地图继续上收
  // (2026-09-19 性能批3:原「tabBar 起落」整段删除 —— 本页 WXML 从不渲染 tabBar 组件,
  //  原生条在 onShow 里 wx.hideTabBar(),raised 是从写到读都不成立的死账)
  onFeedScroll(e) {
    const currentScrollTop = e.detail.scrollTop || 0;
    const expandedH = this.data.drawerExpandedH;
    const peekH = this.data.drawerPeekH;
    const capsuleH = this.data.drawerCapsuleH;

    if (this.data.drawerH < expandedH - 4) {
      var nextH = peekH - currentScrollTop;
      if (nextH < capsuleH) nextH = capsuleH;
      if (nextH > peekH) nextH = peekH;
      if (nextH !== this.data.drawerH) {
        this.setData({
          drawerH: nextH,
          drawerSnapping: false,
          drawerCollapsed: nextH <= peekH + 6
        });
      }
    }
  },

  noop: function () {},

  onDrawerTouchStart: function (e) {
    this._dragStartY = e.touches[0].clientY;
    this._dragStartH = this.data.drawerH;
    this.setData({ drawerSnapping: false });
  },

  onDrawerTouchMove: function (e) {
    var dy = e.touches[0].clientY - this._dragStartY;
    var peekH = this.data.drawerPeekH;
    var capsuleH = this.data.drawerCapsuleH;
    var expandedH = this.data.drawerExpandedH;
    var minH = this._dragStartH <= peekH + 8 ? capsuleH : peekH;
    var h = this._dragStartH + dy;
    if (h < minH) h = minH;
    if (h > expandedH) h = expandedH;
    this.setData({
      drawerH: h,
      drawerSnapping: false,
      drawerCollapsed: h <= peekH + 6
    });
  },

  onDrawerTouchEnd: function (e) {
    var endY = e.changedTouches && e.changedTouches[0] ? e.changedTouches[0].clientY : this._dragStartY;
    if (Math.abs(endY - this._dragStartY) < 8) {
      return;
    }
    var peekH = this.data.drawerPeekH;
    var capsuleH = this.data.drawerCapsuleH;
    var expandedH = this.data.drawerExpandedH;

    if (this._dragStartH <= peekH + 8) {
      var midCapsule = capsuleH + (peekH - capsuleH) * 0.4;
      var toCapsule = this.data.drawerH < midCapsule;
      this.setData({
        drawerSnapping: true,
        drawerH: toCapsule ? capsuleH : peekH,
        drawerCollapsed: !toCapsule
      });
      return;
    }

    var mid = peekH + (expandedH - peekH) * 0.45;
    var collapsed = this.data.drawerH < mid;
    this.setData({
      drawerSnapping: true,
      drawerH: collapsed ? peekH : expandedH,
      drawerCollapsed: collapsed
    });
  },

  collapseDrawer: function () {
    this.setData({
      drawerSnapping: true,
      drawerH: this.data.drawerPeekH,
      drawerCollapsed: true
    });
  },

  toggleDrawer: function () {
    if (this.data.drawerH < this.data.drawerExpandedH - 4) {
      this.expandDrawer();
    } else {
      this.collapseDrawer();
    }
  },

  expandDrawer: function () {
    this.setData({
      drawerSnapping: true,
      drawerH: this.data.drawerExpandedH,
      drawerCollapsed: false
    });
  },

  onUnload() { this._loginContinuationEpoch = (this._loginContinuationEpoch || 0) + 1;
    this._unloaded = true;
    reportSheet.cancel(this);
    this._feedRequestId = (this._feedRequestId || 0) + 1;
    if (this._feedRequestTask && typeof this._feedRequestTask.abort === 'function') {
      this._feedRequestTask.abort();
    }
    this._feedRequestInFlight = false;
    this._feedRequestTask = null;
    merchantTheme.merchantPageRestore();
  },

  // 点头像 → T5 胶囊(Figma 339:740):关注 / 访问个人主页。
  // 量不到锚点(老路径、或 selectorQuery 失手)就退回原来的直接跳转 ——
  // 稿子写死了「不许居中」,与其显示成错的形态,不如退回一个正确的跳转。
  onAvatarTap(e) {
    const d = (e && e.detail) || {};
    const id = d.memberId || (e.currentTarget && e.currentTarget.dataset.id);
    if (!id) return;
    if (!d.anchor) { this.goUserInfo(id); return; }
    const index = feedIndexFromEvent(e);
    const item = (this.data.list || [])[index] || {};
    const items = [];
    // 已关注 / 自己的帖就不给「关注」这一项 —— 给了点了也没意义。
    if (item.isFollowTheUser == 0 && item.memberId != this.data.userId) {
      items.push({ key: 'follow', label: '关注', icon: 'plus' });
    }
    items.push({ key: 'profile', label: '访问个人主页' });
    // memberId / index 只在 js 里读,不进 wxml —— 走 setData 会被 U4 死数据字段门禁判红,
    // 而且它说得对:setData 是给渲染层用的,内部状态挂实例上就行。
    this._avatarPop = { memberId: id, index };
    this.setData({ avatarPopShow: true, avatarPopAnchor: d.anchor, avatarPopItems: items });
  },

  onAvatarPopClose() { this.setData({ avatarPopShow: false }); },

  onAvatarPopPick(e) {
    const key = ((e || {}).detail || {}).key;
    const pop = this._avatarPop || {};
    if (key === 'profile') { this.goUserInfo(pop.memberId); return; }
    if (key === 'follow') {
      // 复用既有关注链路,不另造一条 —— 两条分叉过一次就会出现「卡上能关、菜单里关不上」。
      this.followClick({ detail: { index: pop.index } });
    }
  },

  goUserInfo(id) { if (!id || this._navigating) return; this._navigating = true; // 跳转中不响应连点
    wx.navigateTo({
      url: '/pages/userinfo/userinfo?userId=' + id,
      fail: () => toast('无法打开这个主页'), complete: () => { this._navigating = false; },
    });
  },

  /**
   * 预览图片
   */
  previewImage: function (e) {
    const detail = e && e.detail;
    const picList = detail && Array.isArray(detail.picList) ? detail.picList : e.currentTarget.dataset.piclist;
    const index = detail && detail.picIndex !== undefined ? detail.picIndex : e.currentTarget.dataset.index;
    mediaPreview.open(picList, picList && picList[index]);
  },

  /**
   * 跳转到详情页
   */
  // 「开始玩」:data_type=1 时 dataId 是活动 id,=2 时是主题 id。
  // 两者都落在 cms_topic 内(ProductType 只有 1 经典定向 / 2 自由定向),恒为 play 模式;
  // 漫游是独立栈(ProductType 注释:ROAM_FREE 不进 cms_topic,故无对应值)且漫游发帖刻意不挂
  // 关联(roam/index.js data_id/data_type 留空),所以这里【不存在】跳 roam 的分支,不写死分支。
  goPlay: function (e) {
    const item = this.data.list[feedIndexFromEvent(e)];
    if (!item || !item.dataId || this._navigating) return; this._navigating = true; // 跳转中不响应连点
    const query = String(item.dataType) === '1'
      ? ('activityId=' + item.dataId)
      : ('topicId=' + item.dataId);
    wx.navigateTo({ url: '/pages/play/index?' + query, complete: () => { this._navigating = false; } });
  },

  // 「看详情」:两种关联类型后端都回填了 sportTopicId(活动取其所属 topicId、主题即自身),
  // 故统一跳主题详情页;拿不到 topicId 时退回贴文详情,不做死按钮。
  // ⚠️ 路由跟 square/detail 的 goSportTopic 保持同一条:sportTopicId 是 cms_topic 的真实主题 id,
  // 要跳 /pages/topic/index/index;不能把主题 id 喂给任何【模板】详情页,否则会取错对象。
  goPlayTopicDetail: function (e) {
    const index = feedIndexFromEvent(e);
    const item = this.data.list[index];
    if (!item) return;
    if (!item.sportTopicId) return this.jumpDetail(e);
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/pages/topic/index/index?id=' + item.sportTopicId, complete: () => { this._navigating = false; } });
  },

  // 只有后端明确回填 isTopicTemplate=true 才允许改编；普通主题仍走“看详情”。
  // /use 会复制出当前玩家自己的草稿，成功后进入既有编辑页，不把原模板直接改掉。
  goRemixTemplate: function (e) {
    const item = this.data.list[feedIndexFromEvent(e)];
    if (!item || !item.isTopicTemplate || !item.sportTopicId || this._remixingTemplate) return;
    this._remixingTemplate = true;
    remixTopicTemplate(app, item.sportTopicId).then(() => { this._remixingTemplate = false; });
  },

  jumpDetail: function (e) {
    const index = feedIndexFromEvent(e);
    const activity = this.data.list[index];
    if (!activity || !activity.id || this._navigating) return; this._navigating = true; // 传递活动数据到详情页;跳转中不响应连点
    wx.navigateTo({
      url: '/pages/square/detail/index?id=' + activity.id, complete: () => { this._navigating = false; }
    });
  },
  jumpDetail2: function (e) {
    const index = feedIndexFromEvent(e);
    const activity = this.data.list[index];
    if (!activity || !activity.id || this._navigating) return; this._navigating = true; // 传递活动数据到详情页;跳转中不响应连点
    wx.navigateTo({
      url: '/pages/square/detail/index?comment=1&id=' + activity.id, complete: () => { this._navigating = false; }
    });
  },
  

  /**
   * 生命周期函数--监听页面加载
   */
  goBack() {
    wx.navigateBack({
      delta: 1,
      fail() { wx.switchTab({ url: '/pages/index/index' }); }
    });
  },

  onLoad(options) {
    var that = this
    this._skipInitialShow = true;
    var app = getApp();
    var sys = wx.getSystemInfoSync();
    var safeBottom = sys.safeAreaInsets ? sys.safeAreaInsets.bottom : 0;
    var tabBarPx = 100 + safeBottom;
    // 收起停在黑色半透明顶（400rpx）；600rpx 是含「开始」区的 st-bottom，会多露出一段
    var dockBarPx = Math.round(400 * sys.windowWidth / 750 + safeBottom);
    var bottomZonePx = Math.round(600 * sys.windowWidth / 750 + safeBottom);
    var peekPx = dockBarPx;
    var expandedPx = sys.windowHeight - tabBarPx;
    var menuBtn = wx.getMenuButtonBoundingClientRect ? wx.getMenuButtonBoundingClientRect() : null;
    var capsulePx = menuBtn ? Math.round(menuBtn.bottom + 6) : Math.round((sys.statusBarHeight || 44) + 44);
    that.setData({
      avatar: app.getAvatar(),
      nickname: app.getNickname(),
      userId: app.getUserID(),
      statusBarHeight: app.globalData.statusBarHeight || sys.statusBarHeight || 44,
      navBarHeight: app.globalData.navBarHeight || 44,
      pageH: sys.windowHeight,
      tabBarH: tabBarPx,
      drawerPeekH: peekPx,
      drawerCapsuleH: capsulePx,
      drawerDockBarH: dockBarPx,
      drawerDockH: bottomZonePx,
      drawerExpandedH: expandedPx,
      drawerH: expandedPx,
      drawerCollapsed: false
    });
    that._syncCommunityAccess()
    that.getUpcoming()
  },

  // 「即将开始」聚合横幅:官方活动开场前的临近提醒,放在页标题与发布框之间。
  //
  // 三个筛选条件缺一不可,顺序就是它们各自挡掉的东西:
  //   1. bannerEnabled —— 复用运营既有的「首页城市事件 banner 开关」,不另造一套推广位判断;
  //   2. status 1(即将开始)/ 2(报名中) —— 已开赛/已结束的不是「即将」;
  //   3. 开始时刻在 [现在, 现在+24h) —— 「临近」的量化。已过开始时刻的落在窗口外。
  // 一条都不剩就把 upcoming 置回 null ⇒ WXML 整块不渲染(不留空标题)。
  //
  // 倒计时按分/时/天取整(后端 baseVo 明确「倒计时交前端按时间戳算」),
  // 所以不挂每秒定时器:进页/回前台各算一次就够,也不必动 onUnload 的清理契约。
  getUpcoming: function () {
    var that = this;
    app.sendRequest({
      hideLoading: true,
      silentError: true,
      url: '/api/official/events',
      method: 'GET',
      data: {},
      success: function (res) {
        if (res.code != '200' || !res.data) { that.setData({ upcoming: null, upcomingCards: [] }); return; }
        var rows = Array.isArray(res.data) ? res.data : (res.data.rows || res.data.list || []);
        var now = Date.now();
        var WINDOW = 24 * 60 * 60 * 1000;
        var near = [];
        for (var i = 0; i < rows.length; i++) {
          var e = rows[i];
          if (!e || Number(e.bannerEnabled) !== 1) continue;
          if (Number(e.status) !== 1 && Number(e.status) !== 2) continue;
          // toTimestamp 按 +08:00 锚定:CI/真机时区不同也算出同一个瞬时
          var st = toTimestamp(e.activityStart);
          if (isNaN(st)) continue;
          var delta = st - now;
          if (delta < 0 || delta >= WINDOW) continue;
          near.push({ e: e, st: st, delta: delta });
        }
        if (!near.length) { that.setData({ upcoming: null, upcomingCards: [] }); return; }
        near.sort(function (a, b) { return a.st - b.st; });
        var upcomingCards = near.map(function (candidate) {
          var event = candidate.e;
          var rawAvatars = Array.isArray(event.participantAvatars)
            ? event.participantAvatars.filter(function (avatar) { return typeof avatar === 'string' && avatar; })
            : [];
          var avatars = rawAvatars.slice(0, 3);
          var rawCount = event.participants === undefined ? event.participantCount : event.participants;
          var participantCount = Number(rawCount);
          var knownCount = Number.isFinite(participantCount) && participantCount >= 0
            ? Math.max(participantCount, rawAvatars.length)
            : rawAvatars.length;
          return {
            id: event.id,
            title: event.title || '',
            sub: event.subtitle || event.city || '',
            countdown: fmtCountdown(candidate.delta),
            avatars: avatars,
            avatarOverflow: avatars.length ? Math.max(0, knownCount - avatars.length) : 0,
          };
        });
        var head = upcomingCards[0];
        that.setData({
          upcoming: Object.assign({}, head, { more: upcomingCards.length - 1 }),
          upcomingCards: upcomingCards,
        });
      },
      fail: function () { that.setData({ upcoming: null, upcomingCards: [] }); },
    });
  },

  // 点横幅进官方活动详情
  goUpcoming: function (event) {
    var id = event && event.currentTarget && event.currentTarget.dataset
      ? event.currentTarget.dataset.id
      : null;
    var up = this.data.upcoming;
    id = id || (up && up.id);
    if (!id || this._navigating) return; var self = this; self._navigating = true; // 跳转中不响应连点
    wx.navigateTo({ url: '/pages/activity/official-detail/index?id=' + id, complete: function () { self._navigating = false; } });
  },

  // A-05:详情页删/改成功后给本页置脏标(跨页通过页面栈实例调用),回页只刷一次首屏。
  markSquareListDirty() {
    this._squareListDirty = true;
  },

  /**
   * 生命周期函数--监听页面显示
   */
  onShow() {
    // 换号先清上一身份缓存再同步社区权限:_syncCommunityAccess 可能同步拉首屏并改写 _feedAccount。
    const accountId = String(app.getUserID() || 0);
    if (this._feedAccount !== undefined && this._feedAccount !== accountId) {
      // 原接口含本人待审/驳回与个人态；换号不能继续展示上一身份的缓存。
      this._feedRequestId = (this._feedRequestId || 0) + 1;
      this._feedRequestInFlight = false;
      this.setData({ list: [], page_no: 1, hasMore: false, isRefreshing: false,
        isLoadingMore: false, userId: app.getUserID() || 0 });
      this.refreshList();
    }
    this._syncCommunityAccess();
    wx.hideTabBar(); this._navigating = false; // 重试一次
    // 广场属于玩家内容域：身份只影响能力，不改变页面/原生 chrome 的深色归属。
    merchantTheme.merchantPageRestore();
    // A-05:从详情返回且详情删/改过 → 刷一次首屏,别让用户看到旧卡;没脏标就不动,不每次 onShow 重拉。
    // A-RPT-5:分享直开详情时,栈里没有列表实例、实例置脏落空,详情侧同时写了 app 级脏标,这里一并消费。
    const globalDirty = !!(app.globalData && app.globalData.squareListDirty);
    const feedVersion = app.globalData && app.globalData.squareListDirtyVersion || 0;
    if ((this._squareListDirty || globalDirty || feedVersion > (this._communityFeedVersion || 0)) && !this.data.isRefreshing) {
      this._communityFeedVersion = feedVersion;
      this._squareListDirty = false;
      if (app.globalData) app.globalData.squareListDirty = false;
      this.refreshList();
    }
    // 倒计时不挂定时器,靠回前台重算:否则后台放一晚回来还写着「3 小时后开始」。
    if (this._skipInitialShow) this._skipInitialShow = false;
    else this.getUpcoming();
  },

  _syncCommunityAccess() {
    const owner = String(app.getUserID() || '');
    const epoch = (this._communityAccessEpoch || 0) + 1;
    this._communityAccessEpoch = epoch;
    const settle = () => {
      if (this._unloaded || epoch !== this._communityAccessEpoch || owner !== String(app.getUserID() || '')) return;
      const read = typeof app.canUseCommunity === 'function' && app.canUseCommunity(false);
      const write = typeof app.canUseCommunity === 'function' && app.canUseCommunity(true);
      const wasReadable = this.data.communityPostRead;
      this.setData({ communityPostRead: read, communityPostWrite: write });
      if (!write) this.setData({ composeShow: false, composeEdit: null });
      if (!read) {
        this._feedRequestId = (this._feedRequestId || 0) + 1;
        this._feedRequestInFlight = false;
        if (this._feedRequestTask && typeof this._feedRequestTask.abort === 'function') this._feedRequestTask.abort();
        this.setData({ list: [], hasMore: false, nodata: false, listLoading: false, loadError: false,
          isRefreshing: false, isLoadingMore: false, loadMoreError: '', composeShow: false });
      } else if (!wasReadable || this.data.listLoading && !this.data.list.length) this.getList(true, 1);
    };
    const pending = typeof app.loadFeatureFlags === 'function' ? app.loadFeatureFlags() : null;
    if (pending && typeof pending.then === 'function') pending.then(settle);
    else settle();
  },

  onHide() { this._loginContinuationEpoch = (this._loginContinuationEpoch || 0) + 1; merchantTheme.merchantPageRestore(); },

  initMapLocation: function () {
    var that = this;
    getCurrentLocation({
      onSuccess: function (res) {
        that.setData({
          mapLatitude: res.latitude,
          mapLongitude: res.longitude
        });
      }
    });
  },

  recenterMap: function () {
    var that = this;
    getCurrentLocation({
      onSuccess: function (res) {
        that.setData({
          mapLatitude: res.latitude,
          mapLongitude: res.longitude,
          mapScale: 16
        });
      }
    });
  },

  goSearchMap: function () {
    wx.navigateTo({ url: '/pages/searchmap/index' });
  },

  onAddRoute: function () {
    wx.navigateTo({ url: '/pages/publish/fabu/index' });
  },

  // 2026-10-27 P-20 owner:发帖入口全站统一成同一个全屏发帖弹窗(带草稿)。
  // 取代 UI-15(2026-09-18)「新建走行内 composer、弹窗只管编辑」—— 行内 textarea 发布框已撤,
  // 新建与编辑都走 cy-post-compose;composeEdit 为空即新建。
  openCompose: function () {
    if (typeof app.canUseCommunity !== 'function' || !app.canUseCommunity(true)) {
      toast('当前账号未开放广场发布');
      return;
    }
    this.setData({ composeEdit: null });
    this.setData({ composeShow: true });
  },

  onComposeClose: function () {
    this.setData({ composeShow: false, composeEdit: null });
  },

  // 弹窗发布/保存成功 —— 同页,直接刷,不必再靠 onShow 猜。
  onComposePublished: function (e) {
    if (!(e && e.detail && e.detail.keepOpen)) {
      this.setData({ composeEdit: null });
    }
    this.refreshList();
  },

  /**
   * 用户点击右上角分享
   */
  onShareAppMessage: function (res) {
    // 如果是从分享按钮触发
    if (res.from === 'button') {
      const dataset = res.target.dataset;
      const id = dataset.id;
      // U-050:转发卡只给公开且已发布的帖子带正文摘要/封面/详情链接;
      // 私密/仅关注者/未发布的帖子退回广场通用卡,卡片上不带正文与图片。
      const post = (this.data.list || []).find(function (row) { return row && String(row.id) === String(id); });
      analytics.track('content_share', {
        bizType: 'square',
        bizId: id
      });
      return Object.assign(require('../utils/square-share.js').buildSquareShare(post), {
        // U-171 分享回执:只有老基础库会回调 success/fail,新基础库不回调 —— 能拿到时如实记一笔。
        success: function (res) {
          analytics.track('square_share_complete', { bizType: 'square', bizId: id });
          toast.success('分享成功');
        },
        fail: function (res) {
          analytics.track('square_share_fail', { bizType: 'square', bizId: id, properties: { reason: 'cancelled' } });
          toast('分享取消');
        }
      });
    }

    // 默认分享（非按钮触发）
    return {
      title: '城瘾Hub',
      path: '/pages/square/list/index',
      success: function (res) {
        toast.success('分享成功');
      }
    };
  },

  /**
   * 页面上拉触底事件的处理函数
   */
  onReachBottom() {
    if (!this.data.hasMore || this.data.isLoadingMore || this.data.isRefreshing) return;
    this.getList(false, this.data.page_no + 1);
  },

  /**
   * 页面相关事件处理函数--监听用户下拉动作
   * 新增：处理原生下拉刷新
   */
  onPullDownRefresh() {
    this.refreshList();
  },

  // 新增：处理touch组件的发布成功回调
  onPublishSuccess() {
    this.refreshList();
  },

  // 新增：刷新列表函数
  refreshList() {
    var that = this;
    if (that.data.isRefreshing) return;
    that.setData({
      isRefreshing: true,
      loadMoreError: ''
    });
    that.getList(true, 1);
  },

  // 修改：获取列表数据，增加isRefresh参数区分刷新还是加载更多
  getList: function (isRefresh = false, requestedPage) {
    var that = this;
    if (typeof app.canUseCommunity !== 'function' || !app.canUseCommunity(false)) {
      that.setData({ listLoading: false, isRefreshing: false, nodata: false });
      return;
    }
    const pageNum = requestedPage || (isRefresh ? 1 : that.data.page_no || 1);
    const isLoadMore = pageNum > 1 && !isRefresh;
    if (isLoadMore && that.data.isLoadingMore) return;
    if (!isRefresh && !isLoadMore && that._feedRequestInFlight) return;
    if (isRefresh && that._feedRequestTask && typeof that._feedRequestTask.abort === 'function') {
      that._feedRequestTask.abort();
    }
    const requestAccount = String(app.getUserID() || 0);
    that._feedAccount = requestAccount;
    const requestId = (that._feedRequestId || 0) + 1;
    that._feedRequestId = requestId;
    that._feedRequestInFlight = true;
    // 只有「首屏/刷新后列表还空着」才铺骨架:加载更多时列表已有内容,铺骨架反而把已读内容顶掉。
    if (!that.data.list.length && !isRefresh) {
      that.setData({ listLoading: true, loadError: false });
    }
    if (isLoadMore) that.setData({ isLoadingMore: true, loadMoreError: '' });
    const setRequestError = function (res, fallback) {
      if (requestId !== that._feedRequestId) return;
      const message = app.getRequestErrorMessage(res, fallback);
      if (isLoadMore) {
        that.setData({ loadMoreError: message });
      } else if (isRefresh && that.data.list.length) {
        // 有旧内容时刷新失败:静默降级,不切整页错误。
      } else {
        that.setData({ loadError: true });
      }
    };
    that._feedRequestTask = community.request({
      hideLoading: true,
      silentError: true,
      url: '/api/creativesquare/list',
      method: "POST",
      // 登录则带 token 让后端识别本人(放行自己的 status=0/2 待审/驳回帖 → 徽章生效);登出保持匿名不变。
      // 实时读 getUserID 而非 onLoad 快照,避免"进页后再登录"时身份丢失。
      auth: app.getUserID() > 0 ? true : false,
      data: {
        is_my: 0,
        user_id: 0,
        pageNum: pageNum,
        pageSize: app.getPageSize(),
      },
      success: function (res) {
        if (requestId !== that._feedRequestId || String(app.getUserID() || 0) !== requestAccount) return;
        if (res && res.code == "200" && res.data && isRecordList(res.data.rows)) {
          const seen = Object.create(null);
          if (isLoadMore) that.data.list.forEach(function (item) { seen[String(item.id)] = true; });
          let newlist = res.data.rows.filter(function (item) {
            if (item.id === null || item.id === undefined) return false;
            const key = String(item.id);
            if (seen[key]) return false;
            seen[key] = true;
            return true;
          });
          for (let i = 0; i < newlist.length; i++) {
            if (newlist[i]['pics'] != null) {
              // R10-06:清空后的 '' 不得被解析成一个空图片位
              newlist[i]['picList'] = String(newlist[i]['pics']).split(';').filter(function (pic) { return pic; })

              // 初始化图片宽度和高度数组
              const picCount = newlist[i]['picList'].length;
              newlist[i]['imageWidths'] = new Array(picCount).fill(200); // 默认宽度
              newlist[i]['imageHeights'] = new Array(picCount).fill(600); // 默认高度600rpx
            }
            // 格式化 createTime
            if (newlist[i]['createTime']) {
              newlist[i]['formattedCreateTime'] = that.formatTimeDifference(newlist[i]['createTime']);
            }
            newlist[i]['playCover'] = resolveFeedPlayCover(newlist[i]);
            newlist[i]['hasPlayCover'] = hasFeedPlayCover(newlist[i]);
            newlist[i].isCompletionShare = isCompletedShare(newlist[i]);
            newlist[i].isRoamResultShare = isRoamResultShare(newlist[i]);
            newlist[i].completionImage = resolveCompletionShareImage(newlist[i]);
            // 可玩卡副标题:把原来单独一行 cy-metric 三联(完成节点/状态/时间)收进封面卡一句话。
            // sportName 非空 = 该贴关联了活动(data_type=1)或主题(data_type=2),后端 enrichSportFeedBatch 回填。
            if (newlist[i]['sportName']) {
              newlist[i]['playSubtitle'] = newlist[i].sportSubtitle || buildFeedPlaySubtitle(newlist[i]);
            }
          }

          if (isRefresh || pageNum === 1) {
            pageMotion.enter(that, 'pmList'); that.setData({
              list: newlist,
              page_no: pageNum,
              hasMore: res.data.hasMore === true,
              loadError: false,
              loadMoreError: '',
              nodata: newlist.length < 1
            });
          } else {
            // 加载更多只按数组路径回传新增行(P0-4:整表 concat 会把全部已加载行跨序列化线重发)
            const oldLen = that.data.list.length;
            that.setData(Object.assign(
              {
                page_no: pageNum,
                hasMore: res.data.hasMore === true,
                loadError: false,
                loadMoreError: '',
                nodata: oldLen + newlist.length < 1
              },
              appendByIndex('list', oldLen, newlist)
            ));
          }
        } else {
          setRequestError(res, isLoadMore ? '更多内容加载失败，请重试' : '动态加载失败，请重试');
        }
      },
      fail: function (res) {
        setRequestError(res, '网络异常，请检查连接后重试');
      },
      complete: function () {
        if (requestId !== that._feedRequestId) return;
        that._feedRequestInFlight = false;
        that._feedRequestTask = null;
        that.setData({
          // 加载失败不算"没有内容",由错误态+重试接管
          nodata: !that.data.loadError && that.data.list.length < 1 ? true : false,
          isRefreshing: false,
          isLoadingMore: false,
          listLoading: false
        });
        if (isRefresh) wx.stopPullDownRefresh();
      }
    });
  },

  // 加载失败重试(cy-error retry 事件)
  onLoadRetry: function () {
    this.setData({ loadError: false, listLoading: true });
    this.getList(false, 1);
  },

  retryLoadMore: function () {
    if (!this.data.hasMore || this.data.isLoadingMore) return;
    this.getList(false, this.data.page_no + 1);
  },

  // 新增：格式化时间差为 xx天xx时xx分
  formatTimeDifference: function (createTimeStr) {
    if (!createTimeStr) return '';

    const createTime = new Date(toTimestamp(createTimeStr));
    const now = new Date();
    const diffTime = now.getTime() - createTime.getTime();

    // 计算天数
    const days = Math.floor(diffTime / (1000 * 60 * 60 * 24));
    // 计算剩余的小时数
    const hours = Math.floor((diffTime % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
    // 计算剩余的分钟数
    const minutes = Math.floor((diffTime % (1000 * 60 * 60)) / (1000 * 60));

    let result = '';
    if (days > 0) {
      result += days + '天';
    } else {
      if (hours > 0) {
        result += hours + '时';
      }
      if (minutes > 0) {
        result += minutes + '分';
      }

      if (hours == 0 && minutes == 0) {
        result = '刚刚';
      }
    }
    if(result=='刚刚'){
      return result;
    }else{
      return result + '前';
    }
  }
})
