Page({
  data: {
    userId: '',
    // canonical 深链:tab=about 指定落地 tab。
    initialTab: '',
    missingUser: false,
    topicId: '',
    chapterId: '',
    topicName: '',
    operationScope: '',
    previewSelf: false
  },

  // 页内隐私弹窗:没有它 app.js 会回退到 navigateTo(/pages/privacy/index),

  // 把本页整个盖住 —— 审计里那批「route 回读为隐私页、节点数 0」就是这么来的。

  showPrivacyGate() {

    this.setData({ privacyGateShow: true });

  },

  onPrivacyGateSettled() {

    this.setData({ privacyGateShow: false });

  },


  onLoad: function (options) {
    options = options || {};
    if (typeof wx.hideShareMenu === 'function') wx.hideShareMenu({ menus: ['shareTimeline'], fail: function () {} });
    var userId = String(options.userId || options.id || '').trim();
    this.setData({
      userId: userId,
      initialTab: String(options.tab || ''),
      missingUser: !userId,
      topicId: String(options.topicId || ''),
      topicName: options.topicName ? decodeURIComponent(options.topicName) : '',
      operationScope: options.scope === 'MERCHANT' ? 'MERCHANT' : '',
      previewSelf: options.preview === '1'
    });
  },

  onBack: function () {
    wx.navigateBack({
      fail: function () { wx.switchTab({ url: '/pages/member/index/index' }); }
    });
  },

  goMemberHome: function () {
    wx.switchTab({ url: '/pages/member/index/index' });
  },

  // 第七轮 C 第 1 项:朋友圈只对「可公开商家主页」开放。默认关掉朋友圈菜单,资料组件确认被看者是商家后再打开;
  // 普通个人主页不进朋友圈。
  onSubjectMerchant: function () {
    if (typeof wx.showShareMenu === 'function') wx.showShareMenu({ menus: ['shareAppMessage', 'shareTimeline'], fail: function () {} });
  },

  onShareTimeline: function () {
    var profile = this.selectComponent('#profile');
    var payload = profile && typeof profile.getTimelinePayload === 'function' ? profile.getTimelinePayload() : null;
    return payload || { title: '城瘾 · 城市探索', query: this.data.userId ? 'userId=' + this.data.userId + '&tab=about' : '' };
  },

  onShareAppMessage: function (res) {
    var profile = this.selectComponent('#profile');
    return profile && typeof profile.getSharePayload === 'function' ? profile.getSharePayload(res || {}) : {
      title: '城瘾 · 城市探索',
      path: this.data.userId
        ? '/pages/userinfo/userinfo?userId=' + this.data.userId
        : '/pages/member/index/index'
    };
  }
});
