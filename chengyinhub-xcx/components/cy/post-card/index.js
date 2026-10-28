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

Component({
  options: { multipleSlots: true },
  properties: {
    post: { type: Object, value: {} },
    index: { type: Number, value: 0 },
    userId: { type: null, value: '' },
    showFollow: { type: Boolean, value: false },
    showMore: { type: Boolean, value: false },
    showShare: { type: Boolean, value: false },
    showFavorite: { type: Boolean, value: false },
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
  data: { textExpanded: false, textLong: false, galleryIdx: 0, likePop: false, reducedMotion: false },
  lifetimes: {
    // 减少动态效果:根节点挂 post-card--reduced,点亮弹跳等位移动画整段不播
    attached() { const reducedMotion = readReducedMotion(); if (reducedMotion) this.setData({ reducedMotion }); },
    detached() { clearTimeout(this._likePopTimer); },
  },
  observers: {
    'post.id': function () { this.setData({ textExpanded: false, galleryIdx: 0 }); },
    // 收起态夹 2 行:超过约 40 字,或换行把正文分成 3 段以上(短句多段的公告/规则),都要给「展开」。
    'post.contents': function (contents) {
      const text = typeof contents === 'string' ? contents : '';
      this.setData({ textLong: text.length > 40 || text.split(/\r?\n/).length > 2 });
    },
  },
  methods: {
    toggleText() { this.setData({ textExpanded: !this.data.textExpanded }); },
    // 图廊页码(稿 211:326):每张 600rpx + 间距 20rpx,按滚动距离算当前第几张;只在张数变化时 setData,横滑不刷屏。
    onGalleryScroll(e) {
      if (!this._slotPx) {
        let w = 375;
        try { w = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()).windowWidth || 375; } catch (err) { /* 取不到按 375 */ }
        this._slotPx = 620 * w / 750;
      }
      const total = ((this.data.post || {}).picList || []).length;
      const idx = Math.max(0, Math.min(total - 1, Math.round(((e && e.detail && e.detail.scrollLeft) || 0) / this._slotPx)));
      if (idx !== this.data.galleryIdx) this.setData({ galleryIdx: idx });
    },
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
    emitFollow() { this.triggerEvent('follow', this.eventDetail()); },
    emitMore() { this.triggerEvent('more', this.eventDetail()); },
    emitLike() {
      // 点亮时心形弹一下(只在「从未赞到赞」的这一下播,进页时已赞的不播)
      if (!(this.data.post && this.data.post.isLiked == 1)) {
        this.setData({ likePop: true });
        clearTimeout(this._likePopTimer);
        this._likePopTimer = setTimeout(() => this.setData({ likePop: false }), 460);
      }
      this.triggerEvent('like', Object.assign(this.eventDetail(), { type: 1 }));
    },
    emitFavorite() { this.triggerEvent('favorite', this.eventDetail()); },
    emitComment() { this.triggerEvent('comment', this.eventDetail()); },
    emitPreview(e) {
      if (!this.data.interactiveMedia) {
        this.emitDetail();
        return;
      }
      this.triggerEvent('preview', {
        ...this.eventDetail(),
        picList: this.data.post.picList || [],
        picIndex: Number(e.currentTarget.dataset.picindex) || 0,
      });
    },
  },
});
