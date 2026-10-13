const reportSheet = require('../../../utils/community-report-sheet.js');
const modal = require('../../../utils/modal.js');
const toast = require('../../../utils/toast.js');
const { toTimestamp } = require('../../../utils/datetime');
const app = getApp();
const communityApi = require('../../../utils/community-post-api.js');
const communityView = require('../../../utils/community-post-view.js');
/* 结果面板终态停留时长。发帖不跳页,收掉就留在广场。 */
const RESULT_SHEET_MS = 2000;
const analytics = require('../../../utils/analytics.js');
const { pickLocation, getCurrentLocation } = require('../../../utils/location/location-manager.js');
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
const { geohash9 } = require('../../../utils/roam-geo.js');

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
  communityClient() {
    const owner = String(app.getUserID() || '');
    if (!this._community || this._communityOwner !== owner) {
      const replacing = Boolean(this._community);
      if (this._community) this._community.dispose();
      this._community = communityApi.createClient(app, wx);
      this._communityOwner = owner;
      this._communityCommands = Object.create(null);
      this._actionRequests = Object.create(null);
      this._followRequests=Object.create(null);this._followRequestInFlight=false;
      this._commentLikeRequests = Object.create(null);
      this._commentDeleteRequests = Object.create(null);
      this._commentFollowRequests = Object.create(null);
      this._commentSubmitting = this._replySubmitting = this._deleting = this._deletingPost = this._submitting = false;
      if (replacing) {
        this._feedRequestId = (this._feedRequestId || 0) + 1;
        this._detailRequestId = (this._detailRequestId || 0) + 1;
        this._commentsRequestId = (this._commentsRequestId || 0) + 1;
        this._feedRequestInFlight = this._detailRequestInFlight = this._commentsRequestInFlight = false;
      }
    }
    return this._community;
  },

  communityCurrent() {
    this.communityClient();
    const owner = String(app.getUserID() || '');
    return () => !this._unloaded && String(app.getUserID() || '') === owner;
  },

  communityCommand(key, payload) {
    this._communityCommands = this._communityCommands || Object.create(null);
    const owner = String(app.getUserID() || '');
    const fingerprint = JSON.stringify([owner, payload]);
    const previous = this._communityCommands[key];
    if (!previous || previous.fingerprint !== fingerprint) this._communityCommands[key] = {
      fingerprint, requestId: 'square-' + Date.now() + '-' + Math.random().toString(36).slice(2)
    };
    return this._communityCommands[key].requestId;
  },

  postView(bundle) {
    const row = communityView.toPostView(bundle);
    row.picList = String(row.pics || '').split(';').filter(Boolean);
    row.formattedCreateTime = row.createTime ? this.formatTimeDifference(row.createTime) : '';
    row.playCover = resolveFeedPlayCover(row);
    row.hasPlayCover = hasFeedPlayCover(row);
    row.isCompletionShare = isCompletedShare(row);
    row.isRoamResultShare = isRoamResultShare(row);
    row.completionImage = resolveCompletionShareImage(row);
    if (row.sportName) row.playSubtitle = buildFeedPlaySubtitle(row);
    return row;
  },
  data: {
    reportReasonShow: false,
    reportReasonItems: [],
    resultSheet: { show: false, kind: 'success', title: '', sub: '', meta: '', pill: '',
      why: '', duration: RESULT_SHEET_MS },
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

    avatar: '',
    nickname: '',

    postAudience: 'PUBLIC',
    address: '',
    longitude: '',
    latitude: '',
    valCont: '',
    composeShow: false,      // 新建帖文弹窗
    avatarPopShow: false,    // 点头像的 T5 胶囊
    avatarPopAnchor: null,
    avatarPopItems: [],
    composePrefill: '',      // 展开时把行内已写的正文带进弹窗
    canSubmit: false,
    submitting: false,
    submitError: '',
    submitErrorKind: 'data',
    switch1Checked: false, // 是否参与众筹投票，默认参与
    picList: [],
    fileList: [],
    // 关联运动feed:选中的已通关活动
    selectedActivity: null,
    activityPickerShow: false,

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
    const item = this.data.list[feedIndexFromEvent(e)];
    const type = (e.detail && e.detail.type) || (e.currentTarget && e.currentTarget.dataset.type);
    if (!item || !item.id) return;
    if (String(type) === '1' && typeof item.isLiked !== 'boolean') { toast('互动状态未确认，请刷新'); return; }
    this.changePostAction(item, String(type) === '1' ? 'LIKE' : 'NOT_INTERESTED', String(type) === '1' && item.isLiked);
  },

  favoriteClick(e) {
    const item = this.data.list[feedIndexFromEvent(e)];
    if (!item || !item.id) return;
    if (typeof item.isBookmarked !== 'boolean') { toast('收藏状态未确认，请刷新'); return; }
    this.changePostAction(item, 'BOOKMARK', item.isBookmarked);
  },

  changePostAction(item, actionType, removing) {
    this.communityClient();
    const key = String(item.id) + ':' + actionType;
    this._actionRequests = this._actionRequests || Object.create(null);
    if (this._actionRequests[key]) return;
    this._actionRequests[key] = true;
    const current = this.communityCurrent();
    const commandKey = 'post:' + key;
    const requestId = this.communityCommand(commandKey, [item.id, actionType, Boolean(removing)]);
    const options = { postId: item.id, actionType, command: { requestId, actionType }, current, autoErrorToast: false,
      success: res => {
        if (res.code != 200) { if (res.code !== 499) toast(res.msg || '操作未确认，请刷新重试'); return; }
        const list = this.data.list.slice(), index = list.findIndex(row => String(row.id) === String(item.id));
        if (index < 0) return;
        if (actionType === 'NOT_INTERESTED') list.splice(index, 1);
        else { const authority=this.postView(res.data);
          list[index]=Object.assign({},list[index],actionType==='LIKE'?{isLiked:authority.isLiked,likeCount:authority.likeCount}:{isBookmarked:authority.isBookmarked,bookmarkCount:authority.bookmarkCount}); }
        this.setData({ list, nodata: list.length < 1 });
        delete this._communityCommands[commandKey];
        analytics.track('content_like', { bizType: 'square', bizId: item.id, properties: { actionType } });
      },
      fail: () => toast('结果未确认，请重试同一操作或刷新'),
      complete: () => { delete this._actionRequests[key]; }
    };
    if (removing) this.communityClient().removePostAction(options);
    else this.communityClient().addPostAction(options);
  },

  followClick(e) {
    var that = this
    const index = feedIndexFromEvent(e);
    let topicList = that.data.list
    let item = topicList[index]
    if (!item || !item.memberId) return;
    if (![true,false,0,1].includes(item.isFollowTheUser)) { toast('关注状态未确认，请刷新'); return; }
    const requestKey = String(item.memberId);
    that._followRequests = that._followRequests || Object.create(null);
    if (that._followRequests[requestKey]) return;
    that._followRequests[requestKey] = true;
    // R10-07:带上「期望状态」,丢响应后重试同一意图不会反转服务端关系。
    const current = this.communityCurrent();
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
        if (!current()) return;
        if (res.code == "200") {
          const nextFollowState = intendedFollow;
          const nextList = topicList.slice();
          nextList[index] = Object.assign({}, topicList[index], { isFollowTheUser: nextFollowState });
          that.setData({ list: nextList });

          toast(nextFollowState ? '关注成功' : '已取消关注');
        } else {
          toast(res.msg || '操作失败');
        }
      },
      fail: function (res) {
        if (!current()) return;
        toast('网络错误，请重试');
      },
      complete: function () {
        delete that._followRequests[requestKey];
      }
    })
  },

  // 清除位置
  clearLocation: function () {
    this.setData({
      address: '',
      longitude: '',
      latitude: ''
    });
  },

  // 关联运动feed:打开/选择/清除「已通关活动」
  openActivityPicker: function () {
    this.setData({ activityPickerShow: true });
  },
  onActivityPickerClose: function () {
    this.setData({ activityPickerShow: false });
  },
  onActivitySelect: function (e) {
    const activity = e.detail.activity || null;
    if (activity) {
      const preview=e.detail.routePreviewImg || '';
      if(preview){
        try{const receipt=e.detail.routePreviewReceipt;if(!receipt || receipt.url!==preview)throw new Error('路线预览上传未完成，请重新选择活动');this.communityClient().captureUpload(receipt);}
        catch(error){toast(error.msg || error.message || '路线预览状态无法确认，请重新选择');return;}
      }
      activity.routePreviewImg=preview;
    }
    this.setData({
      selectedActivity: activity,
      activityPickerShow: false
    });
  },
  clearActivity: function () {
    this.setData({ selectedActivity: null });
  },

  // 显示操作弹层(卡片 more 入口)。行数据当场解析成 id / owner 交给组件,
  // 组件不认 index —— 列表随时可能刷新,index 到发请求时可能已经指错行。
  showAction: function (e) {
    const index = feedIndexFromEvent(e);
    const item = this.data.list[index] || {};
    this._actionIndex = index;
    this.setData({
      postActionShow: true,
      currentActionId: item.id == null ? '' : String(item.id),
      currentActionSummary: item.contents || '',
      currentActionOwner: item.viewerIsAuthor === true && item.lifecycle !== 'REMOVED'
    });
  },

  // 关闭操作弹层
  closePostActions: function () {
    this._actionIndex = -1;
    this.setData({
      postActionShow: false,
      currentActionId: '',
      currentActionSummary: '',
      currentActionOwner: false
    });
  },

  // H050/H051:三合一弹层的分发。delete 到这里时确认闸已在组件内部过完,不再自建第二道。
  onPostAction: function (e) {
    const action = ((e || {}).detail || {}).action;
    if (action === 'edit') { this.editCurrentSquarePost(); return; }
    if (action === 'delete') { this.deleteCurrentSquarePost(); return; }
    if (action === 'report') { this.reportSquare(); return; }
  },

  // 删除(H051):软删 + 归属校验都在后端;成功后本地摘掉这一行(与举报同一口径)。
  deleteCurrentSquarePost: function () {
    this.communityClient();
    const item = this.data.list[this._actionIndex];
    if (!item || !item.id || this._deletingPost) return;
    const current = this.communityCurrent();
    const pa = this.selectComponent && this.selectComponent('#post-actions');
    this._deletingPost = true;
    const key = 'delete:' + item.id;
    this.communityClient().deletePost({ postId: item.id, current, autoErrorToast: false,
      command: { expectedVersion: item.version, requestId: this.communityCommand(key, [item.id, item.version]) },
      success: res => {
        if (res.code == 200) {
          if (pa) pa.deleteDone();
          const list = this.data.list.filter(row => String(row.id) !== String(item.id));
          this.setData({ list, nodata: list.length < 1 });
          delete this._communityCommands[key];
          this.closePostActions();
        } else if (pa && res.code !== 499) pa.deleteFailed(res.msg || '删除未确认，请刷新后重试');
      },
      fail: () => { if (pa) pa.deleteFailed('删除结果未确认，请刷新后重试'); },
      complete: () => { this._deletingPost = false; }
    });
  },

  // 编辑(H050):行数据直接进新建面板的编辑态,正文预填、关联带上(不发图片/地点,后端保留原值)
  editCurrentSquarePost: function () {
    const index = this._actionIndex;
    const item = this.data.list[index];
    if (!item || !item.id) { this.closePostActions(); return; }
    this.closePostActions();
    // editPost 先落地、show 后翻:组件的 show 观察器要读到编辑数据(两次 setData 顺序确定)
    this.setData({ composeEdit: {
      id: String(item.id),
      contents: item.contents || '',
      dataId: item.dataId,
      dataType: item.dataType,
      version: item.version, lifecycle: item.lifecycle
    } });
    this.setData({ composeShow: true });
  },

  onReportReasonSelect(e) { reportSheet.select(this, e); },
  onReportReasonCancel() { reportSheet.cancel(this); },

  reportSquare: function () {
    const that = this;
    const owner = String(app.getUserID() || '');
    const current = () => !that._unloaded && String(app.getUserID() || '') === owner;
    if (!current()) return;
    const index = that._actionIndex;
    const item = that.data.list[index];
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
        that.communityClient().request({
          hideLoading: true,
          autoErrorToast: false,
          url: '/api/creativesquare/report',
          selectReportReason: (items, task) => current() ? reportSheet.open(that, items, task)
            : Promise.reject({ code: 499, msg: '已取消举报' }),
          method: 'POST',
          data: {
            id: item.id
          },
          success(resp) {
            if (!current() || Number(resp && resp.code) === 499) return;
            if (resp.code == '200') {
              const list = that.data.list.slice();
              list.splice(index, 1);
              that.setData({
                list: list,
                nodata: list.length < 1
              });
              toast('已提交举报');
            } else {
              toast(resp.msg || '举报失败');
            }
          },
          fail(resp) {
            if (!current() || Number(resp && resp.code) === 499) return;
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

  onUnload() {
    this._unloaded = true;
    if (this._community) this._community.dispose();
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

  goUserInfo(id) {
    wx.navigateTo({
      url: '/pages/userinfo/userinfo?userId=' + id,
      fail: () => toast('打不开这个主页'),
    });
  },

  /**
   * 预览图片
   */
  previewImage: function (e) {
    const detail = e && e.detail;
    const picList = detail && Array.isArray(detail.picList) ? detail.picList : e.currentTarget.dataset.piclist;
    const index = detail && detail.picIndex !== undefined ? detail.picIndex : e.currentTarget.dataset.index;
    wx.previewImage({
      current: picList[index], // 当前显示图片的http链接
      urls: picList // 需要预览的图片http链接列表
    });
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
    if (!item || !item.dataId) return;
    const query = String(item.dataType) === '1'
      ? ('activityId=' + item.dataId)
      : ('topicId=' + item.dataId);
    wx.navigateTo({ url: '/pages/play/index?' + query });
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
    wx.navigateTo({ url: '/pages/topic/index/index?id=' + item.sportTopicId });
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
    // 传递活动数据到详情页
    wx.navigateTo({
      url: '/pages/square/detail/index?id=' + activity.id
    });
  },
  jumpDetail2: function (e) {
    const index = feedIndexFromEvent(e);
    const activity = this.data.list[index];
    // 传递活动数据到详情页
    wx.navigateTo({
      url: '/pages/square/detail/index?comment=1&id=' + activity.id
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
    this._unloaded = false;
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
    that.getList()
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
    if (!id) return;
    wx.navigateTo({ url: '/pages/activity/official-detail/index?id=' + id });
  },

  // 为节点选择地点
  choosePoiForNode() {
    if (this._unloaded) return;
    const that = this;
    const current = this.communityCurrent();
    const epoch = (this._poiEpoch || 0) + 1;
    this._poiEpoch = epoch;
    if (!current()) return;
    pickLocation({
      onPick(poi) {
        if (!current() || that._poiEpoch !== epoch) return;
        that.setData({
          address: poi.name,
          longitude: poi.longitude,
          latitude: poi.latitude
        });
      }
    });
  },

  bindTextAreaBlur: function (e) {
    const value = e.detail.value || '';
    this.setData({
      valCont: value,
      canSubmit: value.trim().length > 0,
      submitError: ''
    });
  },

  onResultSheetClose() {
    this.setData({ 'resultSheet.show': false });
  },

  resetForm: function (e) {
    var that = this
    that.setData({
      postAudience: 'PUBLIC',
      address: '',
      longitude: '',
      latitude: '',
      valCont: '',
      canSubmit: false,
      picList: [],
      fileList: [],
      selectedActivity: null,
      popOrig: false,
      popShow: false,
      submitError: '',
      submitErrorKind: 'data'
    });
  },

  // 完整发布意图由共享 client 持久化，未知结果保留原输入和命令。
  submitFormData(recoveryOptions) {
    this.communityClient();
    const content = String(this.data.valCont || '').trim();
    if (!content) { toast('请输入内容'); return; }
    if (this._submitting) return;
    const current = this.communityCurrent();
    this._submitting = true;
    this.setData({ submitting: true, submitError: '', submitErrorKind: 'data' });
    let submittedAudience = this.data.postAudience;
    const snapshot = JSON.stringify([this.data.valCont, this.data.picList, this.data.fileList, this.data.address,
      this.data.longitude, this.data.latitude, this.data.selectedActivity, this.data.switch1Checked]);
    const form = { body: content, mediaPaths: this.data.picList.slice(), audience: this.data.postAudience, locationPrecision: 'NONE' };
    const activity = this.data.selectedActivity;
    if (activity) {
      const referenceId = activity.dataId != null && activity.dataId !== '' ? activity.dataId : activity.activityId;
      if(activity.routePreviewImg && !form.mediaPaths.includes(activity.routePreviewImg))form.mediaPaths.push(activity.routePreviewImg);
      form.references = [{ referenceType: String(activity.dataType || 1) === '2' ? 'TOPIC' : 'ACTIVITY', referenceId }];
    }
    const fail = res => {
      if (!current() || Number(res && res.code) === 499) return;
      if(res && (res.requiresRecovery===true || (res.intentDiscarded===true && res.requiresRestart===true))){
        const restart=res.intentDiscarded===true && res.requiresRestart===true;
        if(recoveryOptions && (restart?recoveryOptions.restartDiscarded:recoveryOptions.recoverPrevious)) {
          this.setData({submitError:res.msg || '仍未确认，内容已保留'});return;
        }
        const confirmSnapshot=JSON.stringify([this.data.valCont,this.data.picList,this.data.fileList,this.data.selectedActivity,
          this.data.postAudience,this.data.address,this.data.latitude,this.data.longitude]);
        modal.show({title:restart?'原提交已删除':'先核对上次提交',
          content:restart?'保留当前内容作为新帖重新提交？原提交不会复活。':'上次结果还未确认。先核对原提交，本次正文、图片和可见范围仍保留，核对后再提交本次修改。',
          confirmText:restart?'作为新帖提交':'核对上次',cancelText:'取消',success:result=>{
            if(!result.confirm || !current() || confirmSnapshot!==JSON.stringify([this.data.valCont,this.data.picList,this.data.fileList,this.data.selectedActivity,
              this.data.postAudience,this.data.address,this.data.latitude,this.data.longitude]))return;
            this.submitFormData(restart?{restartDiscarded:true}:{recoverPrevious:true});
          }});
        return;
      }
      this.setData({ submitError: app.getRequestErrorMessage(res, '发布结果未确认，内容已保留，请重试'),
        submitErrorKind: res && res.transportFailure ? 'network' : 'data' });
    };
    const send = () => {
      if (!current()) return;
      this.communityClient().publishNew({ scope: 'square:list', form, current, autoErrorToast: false,
        recoverPrevious:Boolean(recoveryOptions && recoveryOptions.recoverPrevious===true),restartDiscarded:Boolean(recoveryOptions && recoveryOptions.restartDiscarded===true),
        selectGuideline: (document, task) => new Promise((resolve, reject) => modal.show({ title: '社区发布规范',
          content: document.rules.map(rule => rule.title + (rule.content ? '：' + rule.content : '')).join('\n'),
          confirmText: '同意并提交', cancelText: '取消',
          success: result => current() && !task.aborted ? resolve(result.confirm === true) : reject({ code:499,msg:'已取消提交' }),
          fail: reject })),
        success: res => {
          if (res.code !== 200) { fail(res); return; }
          const post = res.data.post;
          const unchanged = res.recoveredPrevious!==true && snapshot === JSON.stringify([this.data.valCont, this.data.picList, this.data.fileList, this.data.address,
            this.data.longitude, this.data.latitude, this.data.selectedActivity, this.data.switch1Checked]) && submittedAudience === this.data.postAudience;
          if (unchanged) this.resetForm();
          this.setData({ resultSheet: { show:true,kind:'success',title:res.recoveredPrevious===true?'上次提交已确认':post.lifecycle === 'CHECKING' ? '已提交审核' : '提交成功',
            sub: post.lifecycle === 'CHECKING' ? '审核通过后按可见范围展示。' : post.audience === 'PRIVATE' ? '仅自己可见，未公开到广场。' : '帖文已发布。',
            meta:res.recoveredPrevious===true?'本次修改尚未提交，内容已保留':unchanged ? '' : '后续输入已保留',pill:'',why:'',duration:RESULT_SHEET_MS } });
          this.refreshList();
        }, fail,
        complete: () => { this._submitting = false; this.setData({ submitting:false }); }
      });
    };
    if (this.data.fileList.length || this.data.switch1Checked) {
      fail({msg:'当前附件或众筹选项尚未接通，内容已保留，请移除该选项后重试'});
      this._submitting = false; this.setData({submitting:false}); return;
    }
    const hasLocation = this.data.longitude !== '' || this.data.latitude !== '' || Boolean(this.data.address);
    if (hasLocation) {
      let grid;
      try { grid = geohash9(this.data.latitude, this.data.longitude); }
      catch (error) { fail(error); this._submitting = false; this.setData({submitting:false}); return; }
      if(this.data.postAudience==='PRIVATE'){Object.assign(form,{audience:'PRIVATE',locationPrecision:'EXACT',approximateGeohash:grid,cityCode:null});send();return;}
      modal.show({ title:'精确地点仅自己可见', content:'保留这个精确地点将作为私密帖文提交。之后清除地点也会保持仅自己可见。',
        confirmText:'仅自己可见',cancelText:'取消', success: result => {
          if (!current()) return;
          if (!result.confirm) { this._submitting = false; this.setData({submitting:false}); return; }
          submittedAudience = 'PRIVATE';
          this.setData({postAudience:'PRIVATE'});
          Object.assign(form,{audience:'PRIVATE',locationPrecision:'EXACT',approximateGeohash:grid,cityCode:null}); send();
        }, fail: error => { if (!current()) return; fail(error); this._submitting=false;this.setData({submitting:false}); }
      });
    } else send();
  },

  retrySubmit() {
    this.submitFormData();
  },

  // 只保存正常上传接口签发的回执；发布阶段再按原命令注册媒体。
  uploadPic: function () {
    const current = this.communityCurrent(), client = this.communityClient();
    const count = Math.max(0, 6 - this.data.picList.length);
    if (!count) return;
    app.chooseImage(rows => {
      if (!current()) return;
      this.setData({ picList: this.data.picList.concat(rows).slice(0,6) });
    }, count, {bizType:'COMMUNITY_POST',mapResult: data => client.captureUpload(data)});
  },

  // 删除图片
  delPic: function (e) {
    var that = this;
    let arr = that.data.picList;
    arr.splice(e.currentTarget.dataset.inx, 1);
    that.setData({
      'picList': arr,
    })
  },

  // A-05:详情页删/改成功后给本页置脏标(跨页通过页面栈实例调用),回页只刷一次首屏。
  markSquareListDirty() {
    this._squareListDirty = true;
  },

  /**
   * 生命周期函数--监听页面显示
   */
  onShow() {
    wx.hideTabBar(); // 重试一次
    // 广场属于玩家内容域：身份只影响能力，不改变页面/原生 chrome 的深色归属。
    merchantTheme.merchantPageRestore();
    // A-05:从详情返回且详情删/改过 → 刷一次首屏,别让用户看到旧卡;没脏标就不动,不每次 onShow 重拉。
    // A-RPT-5:分享直开详情时,栈里没有列表实例、实例置脏落空,详情侧同时写了 app 级脏标,这里一并消费。
    const globalDirty = !!(app.globalData && app.globalData.squareListDirty);
    if (this._squareListDirty || globalDirty) {
      this._squareListDirty = false;
      if (app.globalData) app.globalData.squareListDirty = false;
      this.refreshList();
    }
    // 倒计时不挂定时器,靠回前台重算:否则后台放一晚回来还写着「3 小时后开始」。
    if (this._skipInitialShow) this._skipInitialShow = false;
    else this.getUpcoming();
  },

  onHide() { merchantTheme.merchantPageRestore(); },

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

  // UI-15(2026-09-18 用户定):行内发布区的「展开」口和「发布」文字一起删除,
  // 新建帖不再走完整编辑弹窗(行内 composer 已支持配图/地点/活动与长文本次发布);
  // cy-post-compose 只剩「编辑已有帖」这一条入口(由 onPostAction edit 打开)。
  onComposeClose: function () {
    this.setData({ composeShow: false, composeEdit: null });
  },

  // 弹窗发布/保存成功 —— 同页,直接刷,不必再靠 onShow 猜。
  onComposePublished: function (event) {
    const detail = event && event.detail || {};
    if (this._unloaded || (detail.ownerId != null && String(detail.ownerId) !== String(app.getUserID() || ''))) return;
    if (detail.keepOpen !== true) {
      this.setData({ valCont: '', canSubmit: false, composePrefill: '', composeEdit: null });
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
      let path = `/pages/square/detail/index?id=${id}`;
      analytics.track('content_share', {
        bizType: 'square',
        bizId: id
      });
      return {
        title: '城瘾Hub',
        path: path,
        success: function (res) {
          toast.success('分享成功');
        },
        fail: function (res) {
          toast('分享取消');
        }
      };
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
    this.communityClient();
    var that = this;
    const pageNum = requestedPage || (isRefresh ? 1 : that.data.page_no || 1);
    const isLoadMore = pageNum > 1 && !isRefresh;
    if (isLoadMore && that.data.isLoadingMore) return;
    if (!isRefresh && !isLoadMore && that._feedRequestInFlight) return;
    if (isRefresh && that._feedRequestTask && typeof that._feedRequestTask.abort === 'function') {
      that._feedRequestTask.abort();
    }
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
    const current = that.communityCurrent();
    that._feedRequestTask = that.communityClient().feed({
      current: () => current() && requestId === that._feedRequestId,
      autoErrorToast: false,
      query: { cursor: isLoadMore ? that._nextCursor : null, limit: app.getPageSize() },
      success: function (res) {
        if (requestId !== that._feedRequestId) return;
        if (res && res.code == "200" && res.data && Array.isArray(res.data.items)) {
          that._nextCursor = res.data.nextCursor;
          let newlist = res.data.items.map(bundle => that.postView(bundle))
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
              newlist[i]['playSubtitle'] = buildFeedPlaySubtitle(newlist[i]);
            }
          }

          if (isRefresh || pageNum === 1) {
            that.setData({
              list: newlist,
              page_no: pageNum,
              hasMore: res.data.hasMore,
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
                hasMore: res.data.hasMore,
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
        setRequestError(res, '网络错误，请检查连接后重试');
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
