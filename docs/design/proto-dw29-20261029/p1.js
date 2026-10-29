// P-1 帖文样式总表:数据 + 屏。卡片两套 —— masterCard 照 components/cy/post-card/index.wxml 原样(改前对照),
// threadsCard 是本次提案(Threads 版)。俱乐部页 / 俱乐部帖文流 / 主页壳子照各自 master WXML。
(function () {
  var U = UI, I = U.icon, E = U.esc;
  var C = 'assets/covers/';
  var ME = { nick: '智能科技体验馆', avatar: C + 'old-movie.jpg' };
  var PEOPLE = {
    lin: { nick: '林小满', avatar: C + 'flavor-tour.jpg', lv: 6 },
    zhou: { nick: '周末城市漫步', avatar: C + 'treasure-hunter.jpg', lv: 4 },
    he: { nick: '何以解忧', avatar: C + 'midlife-day.jpg', lv: 3 },
    me: { nick: ME.nick, avatar: ME.avatar, lv: 6 }
  };
  var POSTS = {
    text: { who: 'lin', time: '2小时', club: '上海城市定向俱乐部', likes: 24, comments: 6, liked: true,
      text: '周六下午走完了「上海像一部旧电影」。第三站的老唱片店老板还记得九十年代的进货单，一张张翻给我们看。下次带朋友再来一次。' },
    long: { who: 'zhou', time: '5小时', club: '周末暴走团', likes: 8, comments: 2,
      text: '给新人的几条经验：\n1. 先在票夹里看好集合时间，地铁口出来就是起点。\n2. 第二站要拍照验证，阴天也能过，别担心光线。\n3. 中途可以暂停，第二天接着走，进度会保存。\n4. 结束后记得去漫游记录里领勋章。\n5. 带一个充电宝，地图一直开着很耗电。\n6. 遇到店家问「你们在玩什么」，可以把主题分享给他。' },
    one: { who: 'he', time: '昨天', club: '上海城市定向俱乐部', likes: 56, comments: 12, text: '武康大楼下的黄昏。', pics: [['old-movie.jpg', 'l']] },
    onep: { who: 'lin', time: '昨天', likes: 31, comments: 4, text: '这家小馄饨是第四站的验证点，汤底真的好。', pics: [['flavor-tour.jpg', 'p']], loc: '黄浦区 · 山西南路' },
    multi: { who: 'zhou', time: '3天', club: '周末暴走团', likes: 102, comments: 18, text: '今天五个人一起通关，用时 2 小时 14 分。', pics: [['treasure-hunter.jpg', 'p'], ['old-movie.jpg', 'l'], ['time-pollution.jpg', 'p']] },
    many: { who: 'he', time: '10月26日', likes: 77, comments: 9, text: '一组提篮桥的街景，走「海上方舟」的时候拍的。', loc: '虹口区 · 提篮桥',
      pics: ['anomaly-coordinate', 'apocalypse-broadcast', 'flavor-tour', 'heat-escape', 'life-backup', 'midlife-day', 'old-movie', 'prebuilt-life', 'time-pollution', 'treasure-hunter', 'wildlife-observation', 'flavor-tour'].map(function (n, i) { return [n + '.jpg', i % 3 === 1 ? 'l' : 'p']; }) },
    theme: { who: 'lin', time: '4小时', club: '上海城市定向俱乐部', likes: 15, comments: 3, text: '这个主题很适合周末，两个小时刚好。',
      play: { title: '风味巡游', sub: '4 个节点 · 约 2 小时', cover: C + 'flavor-tour.jpg' } },
    activity: { who: 'zhou', time: '1天', club: '周末暴走团', likes: 9, comments: 1, text: '11 月 2 日早上九点，人民广场集合，一起走。',
      play: { title: '城市藏宝猎人 · 11月2日场', sub: '11月2日 09:00 · 人民广场', cover: C + 'treasure-hunter.jpg' } },
    repost: { who: 'he', time: '6小时', reposter: '林小满', likes: 40, comments: 5, text: '一组提篮桥的街景，走「海上方舟」的时候拍的。', pics: [['old-movie.jpg', 'l'], ['anomaly-coordinate.jpg', 'p']] },
    own: { who: 'me', time: '刚刚', likes: 0, comments: 0, own: true, text: '测试一下新的发帖样式。' },
    ownAudit: { who: 'me', time: '296天前', likes: 0, comments: 0, own: true, audit: '未通过审核 · 仅自己可见', text: '123123123' }
  };

  function person(p) { return PEOPLE[p.who]; }

  // —— 改前:master post-card 原样 ——
  function masterCard(p, f) {
    var u = person(p); f = f || {};
    var pics = (p.pics || []).map(function (x) { return '<img class="post-card__media-pic" src="' + C + x[0] + '">'; }).join('');
    return '<view class="c-post-card"><view class="post-card' + (f.light ? ' post-card--light' : '') + (p.play ? ' post-card--compact' : '') + '">' +
      '<view class="post-card__avatar"><img class="post-card__avatar-img" src="' + u.avatar + '"></view>' +
      '<view class="post-card__main"><view class="post-card__head"><view class="post-card__identity"><view class="post-card__name"><text class="post-card__nick">' + E(u.nick) + '</text>' +
      (u.lv ? '<text class="post-card__badge">Lv.' + u.lv + '</text>' : '') + '</view>' +
      '<view class="post-card__meta">' + (p.club && f.ctx ? '<text class="post-card__club">' + E(p.club) + '</text><text> · </text>' : '') + '<text>' + E(p.time) + (/[天时]$/.test(p.time) ? '前' : '') + '</text></view></view>' +
      '<view class="post-card__more">' + I('more', 44) + '</view></view>' +
      '<view class="post-card__body"><view class="post-card__text">' + E(p.text) + '</view>' + (p.text.length > 40 ? '<text class="post-card__expand">展开</text>' : '') + '</view>' +
      (p.play ? playCard(p.play) : '') +
      (pics ? '<view class="post-card__gallery"><scroll-view class="post-card__media" scroll-x><view class="post-card__media-row">' + pics + '</view></scroll-view>' + (p.pics.length > 1 ? '<view class="post-card__count">1/' + p.pics.length + '</view>' : '') + '</view>' : '') +
      '<view class="post-card__actions"><view class="post-card__actions-left">' +
      '<view class="post-card__action post-card__action--like' + (p.liked ? ' is-on' : '') + '">' + I(p.liked ? 'heart-filled' : 'heart', 44, 'post-card__heart') + '<text class="post-card__action-text">' + (p.likes || '') + '</text></view>' +
      '<view class="post-card__action">' + I('comment', 44, 'post-card__comment-icon') + '<text class="post-card__action-text">' + (p.comments || '') + '</text></view>' +
      '<view class="post-card__action post-card__action--favorite">' + I('star', 44) + '</view></view>' +
      '<button class="post-card__share"><img class="post-card__share-icon" src="assets/img/icon_share.png"></button></view></view></view></view>';
  }

  function playCard(pl) {
    return '<view class="c-feed-play-card"><view class="feed-play-card" data-toast="打开「' + E(pl.title) + '」"><img class="feed-play-card__cover" src="' + pl.cover + '"><view class="feed-play-card__scrim"></view>' +
      '<view class="feed-play-card__body"><text class="feed-play-card__title">' + E(pl.title) + '</text><text class="feed-play-card__subtitle">' + E(pl.sub) + '</text>' +
      '<view class="feed-play-card__actions"><view class="feed-play-card__btn feed-play-card__btn--ghost">看看模板</view><view class="feed-play-card__btn feed-play-card__btn--solid">试玩</view></view></view></view></view>';
  }

  // —— 提案:Threads 版 ——
  // f.ctx 显示「› 俱乐部名」(跨俱乐部的帖文流);f.mk 给每个部位打改动号;f.sheet 是 ⋯ 打开的半屏 id
  function threadsCard(p, f) {
    var u = person(p); f = f || {};
    var seen = f.seen || (f.seen = {});
    var m = function (n) { if (!f.mk || !n || seen[n]) return ''; seen[n] = 1; return U.mk(n); };
    var media = '';
    if (p.pics && p.pics.length === 1) {
      var r = p.pics[0][1] === 'p' ? '4 / 5' : '16 / 10';
      media = '<view class="tpc__media"' + m(4) + '><img class="tpc__one" src="' + C + p.pics[0][0] + '" style="aspect-ratio:' + r + '" data-toast="查看大图"></view>';
    } else if (p.pics && p.pics.length) {
      media = '<view class="tpc__media tpc__mwrap"' + m(5) + '><view class="tpc__strip">' + p.pics.map(function (x) { return '<img class="' + x[1] + '" src="' + C + x[0] + '" data-toast="查看大图">'; }).join('') + '</view>' +
        (p.pics.length > 4 ? '<view class="tpc__count">1/' + p.pics.length + '</view>' : '') + '</view>';
    }
    var more = f.sheet ? ' data-sheet="' + f.sheet + (p.own ? '-own' : '') + '" data-detent="peek"' : '';
    return '<view class="tpc"' + (f.id ? ' id="' + f.id + '"' : '') + '>' +
      (p.reposter ? '<view class="tpc__repost"' + m(9) + '>' + I('switch-chapter', 26) + '<text>' + E(p.reposter) + ' 转发了</text></view>' : '') +
      '<view class="tpc__rail"' + m(1) + '><img class="tpc__avatar" src="' + u.avatar + '" data-toast="打开' + E(u.nick) + '的主页">' +
      (!p.own && f.follow ? '<view class="tpc__follow">' + I('plus', 18) + '</view>' : '') + '</view>' +
      '<view class="tpc__main"><view class="tpc__head"' + m(2) + '><view class="tpc__who"><text class="tpc__nick">' + E(u.nick) + '</text>' +
      (p.club && f.ctx ? '<text class="tpc__ctx-sep">›</text><text class="tpc__ctx" data-toast="打开' + E(p.club) + '">' + E(p.club) + '</text>' : '') +
      '<text class="tpc__time">' + E(p.time) + '</text></view>' +
      '<view class="tpc__more"' + more + m(f.mk ? 10 : 0) + '>' + I('more', 40) + '</view></view>' +
      '<view class="tpc__text"' + m(3) + '>' + E(p.text) + '</view>' +
      (p.text.length > 120 ? '<text class="tpc__more-text" data-act="expand">更多</text>' : '') +
      (p.audit ? '<view class="tpc__audit">' + E(p.audit) + '</view>' : '') +
      media +
      (p.loc ? '<view class="tpc__loc"' + m(6) + '>' + I('pin', 26) + '<text>' + E(p.loc) + '</text></view>' : '') +
      (p.play ? '<view class="tpc__att"' + m(7) + '>' + playCard(p.play) + '</view>' : '') +
      '<view class="tpc__actions"' + m(8) + '>' +
      '<view class="tpc__act' + (p.liked ? ' is-on' : '') + '" data-act="like">' + I(p.liked ? 'heart-filled' : 'heart', 40) + '<text>' + (p.likes || '') + '</text></view>' +
      '<view class="tpc__act" data-toast="打开评论">' + I('comment', 40) + '<text>' + (p.comments || '') + '</text></view>' +
      '<view class="tpc__act" data-act="fav">' + I('star', 40) + '</view>' +
      '<view class="tpc__act" data-toast="转发给微信好友"><img class="cy-light-ico" src="assets/img/icon_share.png"></view>' +
      '</view></view></view>';
  }

  function composeEntry(light, club, n) {
    var me = ME;
    return '<view class="tpc-compose" data-sheet="compose-' + (light ? 'lt' : 'dk') + '" data-detent="full"' + U.mk(n) + '>' +
      '<img class="tpc__avatar" src="' + me.avatar + '"><view><view class="tpc-compose__nick">' + me.nick + '</view><view class="tpc-compose__ph">有什么新鲜好玩的分享吗？</view>' +
      '<view class="tpc-compose__tools">' + I('image', 44) + (club ? I('route', 44) : I('pin', 44) + I('flag', 44)) + '</view></view></view>';
  }

  // ⋯ 半屏(Apple 风格分组按钮,peek 30%)
  function actionSheets(prefix, light) {
    var cls = 'ios-sheet' + (light ? ' theme-merchant' : '');
    return '<view class="' + cls + '" id="' + prefix + '" data-detent="peek"><view class="mask"></view><view class="panel"><view class="grab"></view><view class="body">' +
      '<view class="ios-group"' + U.mk(10) + '><view class="ios-row" data-close data-toast="已收藏">收藏</view><view class="ios-row" data-close data-toast="链接已复制">复制链接</view></view>' +
      '<view class="ios-group"><view class="ios-row danger" data-close data-toast="已提交举报">举报</view></view></view></view></view>' +
      '<view class="' + cls + '" id="' + prefix + '-own" data-detent="peek"><view class="mask"></view><view class="panel"><view class="grab"></view><view class="body">' +
      '<view class="ios-group"' + U.mk(10) + '><view class="ios-row" data-close data-sheet="compose-' + (light ? 'lt' : 'dk') + '" data-detent="full">编辑</view><view class="ios-row" data-close data-toast="链接已复制">复制链接</view></view>' +
      '<view class="ios-group"><view class="ios-row danger" data-close data-toast="帖文已删除">删除</view></view></view></view></view>';
  }

  // 新建帖文:master cy-post-compose 原样,放进 iOS 大号半屏(80%)
  function composeSheet(light) {
    var id = 'compose-' + (light ? 'lt' : 'dk');
    return '<view class="ios-sheet' + (light ? ' theme-merchant' : '') + '" id="' + id + '" data-detent="full"><view class="mask"></view><view class="panel"><view class="grab"></view><view class="body">' +
      '<view class="c-post-compose" style="height:100%"><view class="pc ' + (light ? 'theme-merchant' : 'theme-dark') + '" style="background:transparent;height:100%"><view class="pc-panel" style="min-height:0;height:100%;display:flex;flex-direction:column;background:transparent;padding:0 16px 34px">' +
      '<view class="cmp-bar"><view class="cmp-cancel" data-close>取消</view><view class="cmp-bar-gap"></view><view class="cmp-bar-title">新建帖文</view><view class="cmp-bar-gap"></view><view class="cmp-bar-acts"><view class="cmp-act" data-toast="草稿箱">' + I('draft', 48) + '</view></view></view>' +
      '<view class="cmp-hr"></view><view class="cmp-editor"><view class="cmp-rail"><img class="cmp-avatar" src="' + ME.avatar + '"><view class="cmp-thread"></view></view>' +
      '<view class="cmp-col"><view class="cmp-name ep1">' + ME.nick + '</view><textarea class="cmp-input" rows="3" placeholder="有什么新鲜好玩的分享吗？"></textarea>' +
      '<view class="cmp-tools"' + U.mk(11) + '><view class="cmp-tool">' + I('image', 44) + '</view><view class="cmp-tool">' + I('pin', 44) + '</view><view class="cmp-tool">' + I('flag', 44) + '</view></view></view></view>' +
      '<view class="cmp-fill" style="flex:1"></view><view class="cmp-foot"><img class="cmp-foot-avatar" src="' + ME.avatar + '"><view class="cmp-count">0/8000</view><view class="cmp-bar-gap"></view><view class="cmp-send cmp-send--off">发布</view></view>' +
      '</view></view></view></view></view></view>';
  }

  function notes(list) { return '<ol class="notes">' + list.map(function (t, i) { return '<li data-n="' + (Array.isArray(t) ? t[0] : i + 1) + '">' + (Array.isArray(t) ? t[1] : t) + '</li>'; }).join('') + '</ol>'; }

  var CARD_NOTES = [
    [1, '头像从 44pt 缩到 36pt,单独占左栏(Threads 版式);正文、图、附件、动作都缩进到头像右侧,不再整宽铺开。'],
    [2, '昵称和时间同一行:「昵称 › 俱乐部 时间」。时间不再写「前」(2小时 / 昨天 / 10月26日)。俱乐部只在跨俱乐部的帖文流里出现,点它进俱乐部。'],
    [3, '正文 15pt / 行高 1.45,最多 6 行,超出显示「更多」;原来 2 行就折叠。'],
    [4, '单图按原比例显示(竖图最高 4:5,横图最矮 16:10),8pt 圆角,不再裁成固定 300×230。'],
    [5, '多图横滑、等高 220pt、各按比例定宽,一直滑到屏幕右缘;5 张以上右上角显示「1/12」。'],
    [6, '带地点:正文下一行「📍 地点」,小一号灰字,点开地图。'],
    [7, '主题 / 活动附件沿用 master 的 cy-feed-play-card,只是放进右栏、和正文对齐。'],
    [8, '动作行 4 个图标靠左排(赞、评论、收藏、转发),图标 20pt,数字 13pt;转发不再单独甩到最右边。'],
    [9, '转发:头像上方一行「↻ 某某 转发了」,下面是原帖。'],
    [10, '「⋯」打开 iOS 半屏(30%):别人的帖文 = 收藏 / 复制链接 / 举报;自己的 = 编辑 / 复制链接 / 删除。俱乐部卡片下面那排「编辑 / 置顶 / 历史 / 删除 / 举报」文字按钮收进这里(管理员另有置顶)。']
  ];

  // —— 屏:总表 ——
  function sheet(light) {
    var f = { mk: true, ctx: true, follow: true, sheet: 'act-' + (light ? 'lt' : 'dk') };
    var order = [['纯文字', 'text'], ['长文 · 6 行后「更多」', 'long'], ['单图 · 横', 'one'], ['单图 · 竖 + 地点', 'onep'], ['2–4 张', 'multi'], ['9 张以上 + 地点', 'many'],
      ['带主题', 'theme'], ['带活动', 'activity'], ['转发', 'repost'], ['自己的', 'own'], ['自己的 · 未通过审核', 'ownAudit']];
    return '<view class="sv">' + '<view style="height:87px"></view>' +
      order.map(function (o) { return '<text class="proto-lab">' + o[0] + '</text>' + threadsCard(POSTS[o[1]], f); }).join('') +
      '<view style="height:60px"></view></view>' +
      U.navBar({ title: '帖文样式', back: false }) + actionSheets('act-' + (light ? 'lt' : 'dk'), light) + composeSheet(light);
  }

  function talent(light) {
    var f = { mk: true, ctx: true, sheet: 'act-' + (light ? 'lt' : 'dk') };
    return '<view class="sv"><view class="talent-v2 ' + (light ? 'theme-merchant' : 'theme-dark') + '" style="min-height:100%">' +
      '<view class="nav-wrap"><view class="nav-status" style="height:47px"></view><view class="nav-bar" style="height:40px"><view class="nav-left"><view class="nav-icon nav-msg"><img src="assets/img/icon_bell.svg"></view></view><text class="nav-title">俱乐部</text></view></view>' +
      '<view style="height:87px"></view>' +
      U.tabs({ variant: 'fill', wide: true, tabs: [{ key: 'feed', label: '帖文' }, { key: 'club', label: '俱乐部' }], active: 'feed', cls: 'top-tabs' }) +
      '<view class="tab-body tab-body--feed">' + composeEntry(light, false, 11) +
      ['text', 'multi', 'theme', 'onep', 'repost'].map(function (k) { return threadsCard(POSTS[k], f); }).join('') +
      '<view class="safe-bottom" style="height:120px"></view></view></view></view>' +
      U.tabbar(3, !light) + actionSheets('act-' + (light ? 'lt' : 'dk'), light) + composeSheet(light);
  }

  function clubHead(light) {
    return '<view class="cover-wrap"><view class="cover-bg"><img class="cover-img" src="' + C + 'treasure-hunter.jpg"></view></view>' +
      '<view class="profile"><view class="profile-id"><view class="profile-logo-wrap"><img class="profile-logo" src="' + C + 'old-movie.jpg"></view>' +
      '<view class="profile-id-main"><view class="profile-name">上海城市定向俱乐部</view></view><button class="profile-share">' + I('share', 36) + '</button></view>' +
      '<view class="profile-meta"><view class="profile-meta-chip">' + I('tab-club', 38, 'profile-meta-ico') + '<text class="profile-meta-text">兴趣社群 · 128 名成员</text></view>' +
      '<view class="profile-meta-row"><view class="profile-meta-chip">' + I('check', 38, 'profile-meta-ico') + '<text class="profile-meta-text">公开加入</text></view><text class="profile-quit">退出</text></view></view></view>' +
      U.tabs({ tabs: [{ key: 'posts', label: '帖子' }, { key: 'ev', label: '活动' }, { key: 'ov', label: '概览' }], active: 'posts', cls: 'club-view-tabs' }) +
      '<view class="feed-divider"></view>';
  }

  function club(light, isEmpty) {
    var f = { mk: true, sheet: 'act-' + (light ? 'lt' : 'dk') };
    var body = isEmpty
      ? composeEntry(light, true, 11) + U.empty({ title: '暂无帖文', cls: 'p1-empty', mk: 12 })
      : composeEntry(light, true, 11) + ['text', 'one', 'theme', 'own', 'multi'].map(function (k) { return threadsCard(POSTS[k], f); }).join('');
    return '<view class="sv"><view class="' + (light ? 'theme-merchant' : 'theme-dark') + '"><view class="page club-page">' +
      '<view class="club-nav-spacer" style="height:87px"></view>' + clubHead(light) +
      '<view class="feed feed--plain">' + body + '<view style="height:80px"></view></view></view></view></view>' +
      U.navBar({}) + actionSheets('act-' + (light ? 'lt' : 'dk'), light) + composeSheet(light);
  }

  function profileHead() {
    return '<view class="pc-hero-bg"><img class="pc-hero-bg-img" src="assets/img/mer1.jpg"></view><view class="pc-page-fade"></view>' +
      '<view class="pc-statusbar" style="height:47px"></view><view class="pc-nav" style="height:40px"><view class="pc-nav-btn"><img class="pc-nav-ico" src="assets/img/icon_set.png"></view>' +
      '<view class="pc-nav-center"><text class="pc-nav-title cy-h2">主页</text></view><view class="pc-nav-side"></view></view>' +
      '<view class="pc-scroll"><view class="pc-body"><view class="pc-idcard"><view class="pc-gamer"><view class="pc-avatar-wrap"><view class="pc-avatar-ring"><img class="pc-avatar" src="' + ME.avatar + '"></view>' +
      '<view class="pc-avatar-edit"><img class="pc-avatar-edit-ico" src="assets/img/icon_pen.svg"></view><view class="pc-avatar-dot"></view></view>' +
      '<view class="pc-gamer-main"><view class="pc-gamer-name">' + ME.nick + '</view><view class="pc-gamer-lv"><text>Lv.6 城市探索者</text></view><view class="pc-gamer-meta"><text class="pc-gamer-streak">连续探索：1天</text></view></view></view>' +
      '<view class="pc-stats"><view class="pc-statcol"><text class="pc-stat-n">0</text><text class="pc-stat-l">好友</text></view><view class="pc-stat-div"></view><view class="pc-statcol"><text class="pc-stat-n">1</text><text class="pc-stat-l">关注</text></view><view class="pc-stat-div"></view><view class="pc-statcol"><text class="pc-stat-n">13</text><text class="pc-stat-l">粉丝</text></view></view></view>' +
      '<view class="pc-actions"><view class="pc-primary pc-ticket"><view class="pc-ticket-icon">' + I('coupon', 32) + '</view><text>票夹</text></view></view>' +
      U.tabs({ variant: 'fill', hairline: false, tabs: [{ key: 'projects', label: '我的' }, { key: 'posts', label: '帖文' }, { key: 'achievements', label: '成就' }, { key: 'about', label: '关于' }], active: 'posts', cls: 'pc-tabs' });
  }

  function profile(light) {
    var f = { mk: true, sheet: 'act-' + (light ? 'lt' : 'dk') };
    return '<view class="sv"><view class="pc-page ' + (light ? 'pc-merchant theme-merchant' : 'pc-player') + ' pc-self">' + profileHead() +
      '<view class="pc-feed" style="padding:0;margin:0 calc(-1 * var(--cy-page-x))">' + composeEntry(light, false, 11) +
      ['own', 'onep', 'ownAudit'].map(function (k) { var p = Object.assign({}, POSTS[k], { who: 'me', own: true }); return threadsCard(p, f); }).join('') +
      '</view><view style="height:120px"></view></view></view></view></view>' + U.tabbar(4, !light) + actionSheets('act-' + (light ? 'lt' : 'dk'), light) + composeSheet(light);
  }

  function before() {
    return '<view class="sv"><view style="height:87px"></view>' +
      [['纯文字', 'text'], ['单图', 'one'], ['多图', 'multi'], ['带主题', 'theme']].map(function (o) { return '<text class="proto-lab">' + o[0] + '(master)</text>' + masterCard(POSTS[o[1]], { ctx: true }); }).join('') +
      '<view style="height:60px"></view></view>' + U.navBar({ title: '改前 · master 帖文卡', back: false });
  }

  var MKCARD = notes(CARD_NOTES);
  var SCREENS = [
    ['p1-sheet-dk', '总表 · 深色(玩家)', '总表', 'cyp theme-dark', sheet(false), 'components/cy/post-card/index.wxml', MKCARD],
    ['p1-sheet-lt', '总表 · 浅色(商家)', '总表', 'cyp theme-merchant', sheet(true), 'components/cy/post-card/index.wxml + .theme-merchant', MKCARD],
    ['p1-before', '改前 · master 帖文卡', '总表', 'cyp theme-dark', before(), 'components/cy/post-card/index.wxml', ''],
    ['p1-talent-dk', '俱乐部 › 帖文 · 深色', '场景', 'cyp theme-dark pg-talent', talent(false), 'pages/talent/list/index.wxml', notes(CARD_NOTES.concat([[11, '帖文流顶上加同一个发帖入口(头像 + 占位 + 图片 / 地点 / 活动),点开就是新建帖文半屏。'], [12, 'V-08:这里就是全站动态。原来的「动态」二级页(pages/square/list)删除,结束漫游改去漫游记录。']]))],
    ['p1-talent-lt', '俱乐部 › 帖文 · 浅色(商家)', '场景', 'cyp theme-merchant pg-talent', talent(true), 'pages/talent/list/index.wxml', MKCARD],
    ['p1-club-dk', '俱乐部详情 · 帖子 · 深色', '场景', 'cyp theme-dark pg-club', club(false), 'pages/club/detail/index.wxml', notes(CARD_NOTES.concat([[11, 'V-04:发帖入口第一个图标原来是一个空心方块(.post-composer-sq,画出来像正方形),换成 cy-icon「image」;第二个从实心图钉换成线性「route」(俱乐部帖带游玩记录,没有地点),三个图标同一套线性。']]))],
    ['p1-club-lt', '俱乐部详情 · 帖子 · 浅色(商家)', '场景', 'cyp theme-merchant pg-club', club(true), 'pages/club/detail/index.wxml', MKCARD],
    ['p1-club-empty-dk', '俱乐部详情 · 空态 · 深色', '空态', 'cyp theme-dark pg-club', club(false, true), 'pages/club/detail/index.wxml', notes([[11, '发帖入口与有帖子时同一行(V-04 图标修正)。'], [12, '空态「暂无帖文」照 master cy-empty,不套卡片;位置随 V-05 lane 的统一结果。']])],
    ['p1-club-empty-lt', '俱乐部详情 · 空态 · 浅色', '空态', 'cyp theme-merchant pg-club', club(true, true), 'pages/club/detail/index.wxml', notes([[11, '同深色。'], [12, '同深色。']])],
    ['p1-profile-dk', '主页 › 帖文 · 深色', '场景', 'cyp theme-dark pg-profile', profile(false), 'subpackageMember/components/cy/profile/index.wxml', notes(CARD_NOTES.concat([[11, 'V-16:主页帖文不再用自己那套 pc-post(上下箭头 + 黑圆头像),改用同一张帖文卡;顶上加发帖入口。']]))],
    ['p1-profile-lt', '主页 › 帖文 · 浅色(商家)', '场景', 'cyp theme-merchant pg-profile', profile(true), 'subpackageMember/components/cy/profile/index.wxml', MKCARD]
  ];

  var host = document.getElementById('screens');
  host.innerHTML = SCREENS.map(function (s) {
    return '<section class="scr ' + s[3] + '" data-id="' + s[0] + '" data-title="' + s[1] + '" data-group="' + s[2] + '" data-src="' + s[5] + '">' + s[4] + s[6] + '</section>';
  }).join('');
  // 新建帖文与 ⋯ 单列为屏,方便截图
  var extra = [['p1-compose-dk', '新建帖文 · 深色', 'compose-dk', false, '#p1-club-dk'], ['p1-compose-lt', '新建帖文 · 浅色', 'compose-lt', true], ['p1-more-dk', '⋯ 别人的帖文', 'act-dk', false], ['p1-more-own-dk', '⋯ 自己的帖文', 'act-dk-own', false], ['p1-more-lt', '⋯ 浅色', 'act-lt', true]];
  extra.forEach(function (x) {
    var s = document.createElement('section');
    s.className = 'scr cyp pg-club ' + (x[3] ? 'theme-merchant' : 'theme-dark');
    s.setAttribute('data-id', x[0]); s.setAttribute('data-title', x[1]); s.setAttribute('data-group', '半屏'); s.setAttribute('data-open', x[2]);
    s.setAttribute('data-src', x[2].indexOf('compose') === 0 ? 'pages/square/components/cy/post-compose/index.wxml(放进 iOS 80% 半屏)' : 'components/cy/post-card ⋯ → cy-post-actions');
    s.innerHTML = club(x[3]) + (x[2].indexOf('compose') === 0
      ? notes([[11, '新建帖文沿用 master 的 cy-post-compose,换成 iOS 大号半屏(80%,下拉或点「取消」收起);工具行和入口同一套图标。']])
      : notes([CARD_NOTES[9]]));
    host.appendChild(s);
  });

  window.ACT = {
    like: function (el) { el.classList.toggle('is-on'); var t = el.querySelector('text'); var n = +(t.textContent || 0); t.textContent = el.classList.contains('is-on') ? n + 1 : Math.max(0, n - 1) || ''; el.querySelector('.cyi').className = 'cyi cyi--' + (el.classList.contains('is-on') ? 'heart-filled' : 'heart'); },
    fav: function (el) { var i = el.querySelector('.cyi'); var on = /star-filled/.test(i.className); i.className = 'cyi cyi--' + (on ? 'star' : 'star-filled'); PROTO.toast(on ? '已取消收藏' : '已收藏'); },
    expand: function (el) { var t = el.previousElementSibling; t.classList.toggle('is-open'); el.textContent = t.classList.contains('is-open') ? '收起' : '更多'; }
  };
})();
