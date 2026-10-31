// 「我的 · 主页」头部:照 subpackageMember/components/cy/profile/index.wxml(本人玩家视角)。P-2 / P-3 共用。
(function () {
  var U = UI, I = U.icon;
  function head(o) {
    o = o || {};
    var avatar = o.avatar || 'assets/covers/old-movie.jpg';
    return '<view class="pc-hero-bg"><img class="pc-hero-bg-img" src="' + (o.hero || 'assets/img/mer1.jpg') + '"></view><view class="pc-page-fade"></view>' +
      '<view class="pc-statusbar" style="height:47px"></view><view class="pc-nav" style="height:40px"><view class="pc-nav-btn" data-toast="设置"><img class="pc-nav-ico" src="assets/img/icon_set.png"></view>' +
      '<view class="pc-nav-center"><text class="pc-nav-title cy-h2">主页</text></view><view class="pc-nav-side"></view></view>' +
      '<view class="pc-scroll"><view class="pc-body">' +
      (o.compact ? '' :
      '<view class="pc-idcard"><view class="pc-gamer"><view class="pc-avatar-wrap"' + (o.editGo ? ' data-go="' + o.editGo + '"' : '') + U.mk(o.mkAvatar) + '><view class="pc-avatar-ring"><img class="pc-avatar" src="' + avatar + '"></view>' +
      '<view class="pc-avatar-edit"><img class="pc-avatar-edit-ico" src="assets/img/icon_pen.svg"></view><view class="pc-avatar-dot"></view></view>' +
      '<view class="pc-gamer-main"><view class="pc-gamer-name">' + (o.name || '智能科技体验馆') + '</view><view class="pc-gamer-lv"><text>Lv.6 城市探索者</text></view><view class="pc-gamer-meta"><text class="pc-gamer-streak">连续探索：1天</text></view></view></view>' +
      '<view class="pc-stats"><view class="pc-statcol"><text class="pc-stat-n">0</text><text class="pc-stat-l">好友</text></view><view class="pc-stat-div"></view><view class="pc-statcol"><text class="pc-stat-n">1</text><text class="pc-stat-l">关注</text></view><view class="pc-stat-div"></view><view class="pc-statcol"><text class="pc-stat-n">13</text><text class="pc-stat-l">粉丝</text></view></view></view>' +
      '<view class="pc-actions"><view class="pc-primary pc-ticket" data-toast="票夹"><view class="pc-ticket-icon">' + I('coupon', 32) + '</view><text>票夹</text></view></view>') +
      U.tabs({ variant: 'fill', hairline: false, tabs: [{ key: 'projects', label: '我的', go: o.go && o.go.projects }, { key: 'posts', label: '帖文', go: o.go && o.go.posts }, { key: 'achievements', label: '成就', go: o.go && o.go.achievements }, { key: 'about', label: '关于', go: o.go && o.go.about }], active: o.tab || 'posts', cls: 'pc-tabs' });
  }
  window.PROFILE = { head: head };
})();
