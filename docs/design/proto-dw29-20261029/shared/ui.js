// 组件渲染:输出与 master 组件 WXML 相同的结构和类名(样式来自 cy.css),页面脚本拼屏用。
// 尺寸参数沿用 WXML 里的 rpx 数,这里统一 ×0.52 换成 390 宽下的 px。
(function () {
  var K = 390 / 750;
  function px(rpx) { return +(rpx * K).toFixed(2) + 'px'; }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function mk(n) { return n ? ' data-mk="' + n + '"' : ''; }

  // <cy-icon name size>
  function icon(name, size, cls) {
    var s = px(size || 48);
    return '<view class="c-icon ' + (cls || '') + '" style="display:inline-flex;line-height:0;vertical-align:middle"><view class="cyi cyi--' + name + '" style="width:' + s + ';height:' + s + '"></view></view>';
  }

  // <cy-tabs variant tabs active>;group 用来接 shell 的 data-tab 切换
  function tabs(o) {
    var items = o.tabs.map(function (t) {
      var on = t.key === o.active;
      return '<view class="cy-tabs__item' + (on ? ' cy-tabs__item--on' : '') + '"' + (o.group ? ' data-tab="' + o.group + ':' + t.key + '" data-on-class="cy-tabs__item--on"' : '') + (t.go ? ' data-go="' + t.go + '"' : '') + mk(t.mk) + '><view class="cy-tabs__label">' + esc(t.label) + '</view></view>';
    }).join('');
    var mods = ['cy-tabs'];
    if (o.variant) mods.push('cy-tabs--' + o.variant);
    if (o.wide) mods.push('cy-tabs--wide');
    if (o.hairline === false) mods.push('cy-tabs--noline');
    var inner = o.variant === 'fill'
      ? '<scroll-view class="' + mods.join(' ') + '" scroll-x><view class="cy-tabs__track">' + items + '</view></scroll-view>'
      : '<view class="' + mods.join(' ') + '">' + items + '</view>';
    return '<view class="c-tabs ' + (o.cls || '') + '"' + mk(o.mk) + '>' + inner + '</view>';
  }

  // <cy-nav-bar title overlay pill>:状态栏 47 + 导航 40(胶囊 top 51 / 高 32 推出来的微信导航高)
  function navBar(o) {
    o = o || {};
    var mode = o.plain ? 'nav--plain' : (o.overlay ? 'nav--overlay' : 'nav--solid');
    return '<view class="c-nav-bar"' + mk(o.mk) + '><view class="nav ' + mode + (o.tint ? ' nav--tint-' + o.tint : '') + '" style="padding-top:47px;">' +
      '<view class="nav__bar" style="height:40px;">' +
      (o.back === false ? '' : '<view class="nav__back" data-back><view class="nav__back-inner' + (o.pill ? ' nav__back-inner--pill' : '') + '">' + icon('back', 48, 'nav__back-icon') + '</view></view>') +
      (o.title ? '<view class="nav__title" style="left:96px;right:96px;">' + esc(o.title) + '</view>' : '') +
      '<view class="nav__actions" style="right:102px;">' + (o.actions || '') + '</view></view></view></view>';
  }

  // <cy-empty title sub cta>
  function empty(o) {
    return '<view class="c-empty ' + (o.cls || '') + '"' + mk(o.mk) + '><view class="cy-empty' + (o.fill ? ' cy-empty--fill' : '') + (o.size === 'lg' ? ' cy-empty--lg' : (o.size === 'sm' ? ' cy-empty--sm' : '')) + '">' +
      (o.art ? '<view class="cy-empty-art"><img class="cy-empty-art__bg" src="assets/img/empty_state_bg.svg"><img class="cy-empty-art__figure" src="assets/img/empty_state_figure.svg"></view>' : '') +
      '<view class="cy-empty-title">' + esc(o.title) + '</view>' +
      (o.sub ? '<view class="cy-empty-sub">' + esc(o.sub) + '</view>' : '') +
      (o.cta ? '<view class="cy-empty-cta"' + (o.ctaGo ? ' data-go="' + o.ctaGo + '"' : ' data-toast="' + esc(o.cta) + '"') + '>' + esc(o.cta) + '</view>' : '') + '</view></view>';
  }

  // <cy-btn variant>
  function btn(label, o) {
    o = o || {};
    return '<view class="c-btn ' + (o.cls || '') + '"' + mk(o.mk) + (o.style ? ' style="' + o.style + '"' : '') + '><view class="btn btn--' + (o.variant || 'primary') + '"' + (o.go ? ' data-go="' + o.go + '"' : '') + (o.act ? ' data-act="' + o.act + '"' : '') + (o.toast ? ' data-toast="' + esc(o.toast) + '"' : '') + '>' + label + '</view></view>';
  }

  // <tabBar dark monochrome>(C 端五栏)
  var TABS = [['icon_f1', 'icon_f01d'], ['icon_roam', 'icon_roam_d'], ['icon_f3', 'icon_f03d'], ['icon_f4', 'icon_f04d'], ['icon_f5', 'icon_f05d']];
  function tabbar(active, dark) {
    var mono = dark !== false;
    return '<view class="c-tabbar" style="position:absolute;left:0;right:0;bottom:0;z-index:50"><view class="tabbar' + (dark !== false ? ' dark' : '') + (mono ? ' monochrome' : ' merchant monochrome') + '"><view class="kg"></view><view class="list flex-cb"><view class="ul flex-cb">' +
      TABS.map(function (t, i) { return '<view class="li' + (i === active ? ' active' : '') + '"><view class="icon flex-cc"><img class="tabbar__icon" src="assets/img/' + t[0] + '.png"></view></view>'; }).join('') +
      '</view></view></view></view>';
  }

  // <cy-avatar size src>
  function avatar(src, size, cls) {
    var s = px(size);
    return '<view class="c-avatar ' + (cls || '') + '"><view class="av" style="--av-size:' + s + ';width:' + s + ';height:' + s + '"><view class="av__visual"><view class="av__inner"><img class="av__img" src="' + (src || 'assets/img/d_profile.png') + '"></view></view></view></view>';
  }

  window.UI = { px: px, esc: esc, mk: mk, icon: icon, tabs: tabs, navBar: navBar, empty: empty, btn: btn, tabbar: tabbar, avatar: avatar };
})();
