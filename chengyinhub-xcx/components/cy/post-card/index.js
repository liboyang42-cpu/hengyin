// 与 utils/motion-preference.js 的 readReducedMotion 同一口径(同两个存储键)。本组件源码会被单测放进
// 无 require 的沙箱里跑,所以这里内联读,不引模块。
function readReducedMotion() {
  try {
    if (typeof wx === 'undefined' || typeof wx.getStorageSync !== 'function') return false;
    const current = wx.getStorageSync('play_reduced_motion');
    if (current === true || current === false) return current;
    return wx.getStorageSync('roam_reduced_motion') === true;
  } catch (e) { return false; }
}

// 2026-10-30 走查:同一张帖文卡在发现 / 俱乐部 / 我的三处各写一种时间(09-30 04:13 / 2026年10月26日 于 13:53 / 30天前)。
// 卡片自己按 createTime 统一成一种:一分钟内「刚刚」、一小时内「N 分钟前」、一天内「N 小时前」、七天内「N 天前」、
// 今年「M月D日」、往年「YYYY年M月D日」。拿不到 createTime 才用页面给的文字。
function postTimeLabel(createTime, now) {
  const m = String(createTime || '').match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?/);
  if (!m) return '';
  const t = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0)).getTime();
  const diff = Math.max(0, (now || Date.now()) - t);
  if (diff < 60000) return '刚刚';
  if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
  if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
  if (diff < 7 * 86400000) return Math.floor(diff / 86400000) + ' 天前';
  const year = new Date(now || Date.now()).getFullYear();
  return (+m[1] === year ? '' : m[1] + '年') + (+m[2]) + '月' + (+m[3]) + '日';
}

Component({
  options: { multipleSlots: true },
  properties: {
    post: { type: Object, value: {} },
    index: { type: Number, value: 0 },
    userId: { type: null, value: '' },
    showFollow: { type: Boolean, value: false },
    showMore: { type: Boolean, value: false },
    showShare: { type: Boolean, value: false },
    showRepost: { type: Boolean, value: false },
    expandableText: { type: Boolean, value: false },
    interactiveActions: { type: Boolean, value: false },
    interactiveUser: { type: Boolean, value: false },
    interactiveClub: { type: Boolean, value: false },
    interactiveMedia: { type: Boolean, value: false },
    // 2026-09-03 rebase 保留:俱乐部详情以商家身份查看时整页是浅色,卡面文字要跟着翻。
    // master 的 Figma 297:1827 重排没有这个开关,而 pages/club/detail 一直在传它 ——
    // 只嫁接这一个属性 + 5 条 --light 覆写,不动 master 的排版。
    light: { type: Boolean, value: false },
    accessibilityLabel: { type: String, value: '查看帖文' },
  },
  data: {
    textExpanded: false, textLong: false, likePop: false, likeRoll: '', burst: '', reducedMotion: false,
    followDone: false, followGone: false, bigHeart: '', refLabel: '', refIcon: 'route', timeLabel: '',
  },
  lifetimes: {
    // 减少动态效果:根节点挂 post-card--reduced,点亮弹跳等位移动画整段不播
    attached() { const reducedMotion = readReducedMotion(); if (reducedMotion) this.setData({ reducedMotion }); },
    detached() { [this._likePopTimer, this._likeGoTimer, this._tapTimer, this._heartTimer, this._heartInTimer, this._heartOutTimer, this._followTimer].forEach(clearTimeout); },
  },
  observers: {
    'post.id': function () { this.setData({ textExpanded: false, followDone: false, followGone: false, bigHeart: '' }); },
    'post.createTime, post.formattedCreateTime, post.timeText': function (createTime, formatted, text) {
      const timeLabel = postTimeLabel(createTime) || formatted || text || '';
      if (timeLabel !== this.data.timeLabel) this.setData({ timeLabel });
    },
    // 帖文第三轮(Threads 版):收起态最多 6 行,超过约 120 字或 6 段以上才给「展开」。
    'post.contents': function (contents) {
      const text = typeof contents === 'string' ? contents : '';
      this.setData({ textLong: text.length > 120 || text.split(/\r?\n/).length > 6 });
    },
    // 分享卡卡顶那一行类型:完赛按漫游 / 通关分,主题卡按模板 / 主题分;页面给了 typeLabel 就用页面的
    'post.isCompletionShare, post.isRoamResultShare, post.hasPlayCover, post.isTopicTemplate, post.typeLabel': function (done, roam, play, tpl, label) {
      const refLabel = label || (done ? (roam ? '漫游' : '通关') : (play ? (tpl ? '主题模板' : '主题') : ''));
      const refIcon = done ? (roam ? 'route' : 'flag') : 'map';
      if (refLabel !== this.data.refLabel || refIcon !== this.data.refIcon) this.setData({ refLabel, refIcon });
    },
  },
  methods: {
    toggleText() { this.setData({ textExpanded: !this.data.textExpanded }); },
    eventDetail() {
      const post = this.data.post || {};
      return { index: this.data.index, postId: post.id, memberId: post.memberId, clubId: post.clubId };
    },
    emitDetail() { this.triggerEvent('detail', this.eventDetail()); },
    // 点头像不再直接跳主页,改成拉起 T5 胶囊(Figma 339:740)。锚点矩形必须在这里量:
    // 弹窗挂在页面上,createSelectorQuery 跨不进本组件内部。量不到就把 anchor 传 null,
    // 由调用方决定退回直接跳转 —— 不让它退化成居中弹窗(稿子明令禁止的形态)。
    emitUser() {
      const detail = this.eventDetail();
      const q = this.createSelectorQuery();
      q.select('.post-card__avatar-img').boundingClientRect();
      q.exec((res) => {
        this.triggerEvent('user', Object.assign(detail, { anchor: (res && res[0]) || null }));
      });
    },
    emitClub() { this.triggerEvent('club', this.eventDetail()); },
    // Roam / Template 正文块的三个动作原样转出去:页面拿到的还是 {index},
    // 与它们原先直接绑在子组件上时一模一样(feedIndexFromEvent 读的就是 e.detail.index)。
    emitPlayDetail() { this.triggerEvent('playdetail', this.eventDetail()); },
    emitRemix() { this.triggerEvent('remix', this.eventDetail()); },
    emitPlay() { this.triggerEvent('play', this.eventDetail()); },
    // 「+」点了先变成勾,0.9 秒后收起;关注请求由页面发,失败时页面把 isFollowTheUser 留在 0,换帖后「+」回来
    emitFollow() {
      if (this.data.followDone) return;
      this.setData({ followDone: true });
      clearTimeout(this._followTimer);
      this._followTimer = setTimeout(() => this.setData({ followGone: true }), this.data.reducedMotion ? 0 : 900);
      this.triggerEvent('follow', this.eventDetail());
    },
    emitMore() { this.triggerEvent('more', this.eventDetail()); },
    emitLike() {
      // 点亮时心形弹一下(只在「从未赞到赞」的这一下播,进页时已赞的不播)
      // 数字跟着滚一格:点亮往上滚,取消往下滚
      const liking = !(this.data.post && this.data.post.isLiked == 1);
      clearTimeout(this._likePopTimer); clearTimeout(this._likeGoTimer);
      if (this.data.reducedMotion) { this.triggerEvent('like', Object.assign(this.eventDetail(), { type: 1 })); return; }
      // 两段过渡:先摆到起点(无过渡),下一帧放开;数字从下面(取消从上面)滚进来
      this.setData({ likePop: liking, burst: liking ? 'is-start' : '', likeRoll: liking ? 'is-up' : 'is-down' });
      this._likeGoTimer = setTimeout(() => this.setData({ burst: liking ? 'is-go' : '', likeRoll: (liking ? 'is-up' : 'is-down') + ' is-roll' }), 34);
      this._likePopTimer = setTimeout(() => this.setData({ likePop: false, burst: '', likeRoll: '' }), 560);
      this.triggerEvent('like', Object.assign(this.eventDetail(), { type: 1 }));
    },
    emitRepost() { this.triggerEvent('repost', this.eventDetail()); },
    emitComment() { this.triggerEvent('comment', this.eventDetail()); },
    // 点图:单击全屏看大图(在大图里继续左右滑);280ms 内再点一下是双击点赞,中间飘出大心。
    // 只有开着 interactive-actions 的页面才能双击点赞;已赞的双击不取消(照 Threads / Instagram)。
    onPicTap(e) {
      const picIndex = Number(e.currentTarget.dataset.picindex) || 0;
      const now = Date.now();
      if (this._lastTap && now - this._lastTap < 280 && this.data.interactiveActions) {
        this._lastTap = 0;
        clearTimeout(this._tapTimer);
        if (!(this.data.post && this.data.post.isLiked == 1)) this.emitLike();
        if (!this.data.reducedMotion) {
          clearTimeout(this._heartTimer); clearTimeout(this._heartOutTimer); clearTimeout(this._heartInTimer);
          this.setData({ bigHeart: 'is-mount' });
          this._heartInTimer = setTimeout(() => this.setData({ bigHeart: 'is-in' }), 34);
          this._heartOutTimer = setTimeout(() => this.setData({ bigHeart: 'is-out' }), 560);
          this._heartTimer = setTimeout(() => this.setData({ bigHeart: '' }), 840);
        }
        return;
      }
      this._lastTap = now;
      clearTimeout(this._tapTimer);
      this._tapTimer = setTimeout(() => { this._lastTap = 0; this.previewPics(picIndex); }, this.data.interactiveActions ? 280 : 0);
    },
    previewPics(picIndex) {
      const picList = (this.data.post && this.data.post.picList) || [];
      // 俱乐部详情自己接管看大图(带它的图片地址修正),其余页面在卡片里直接打开全屏预览
      if (this.data.interactiveMedia) {
        this.triggerEvent('preview', Object.assign(this.eventDetail(), { picList, picIndex }));
        return;
      }
      if (!picList.length || typeof wx === 'undefined' || typeof wx.previewImage !== 'function') return;
      wx.previewImage({ current: picList[picIndex] || picList[0], urls: picList });
    },
  },
});
