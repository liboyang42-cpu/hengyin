// P-3 成就合页(V-17)、「关于」行图标(V-19)、主题勋章(V-23)。
(function () {
  var U = UI, I = U.icon, E = U.esc, TM = THEME_MEDALS;
  var C = 'assets/covers/', D3 = 'assets/3d/';
  function notes(list) { return '<ol class="notes">' + list.map(function (t) { return '<li data-n="' + t[0] + '">' + t[1] + '</li>'; }).join('') + '</ol>'; }
  function medal(key, px, locked, cls) { return '<canvas class="' + (cls || '') + '" data-medal="' + key + '"' + (locked ? ' data-locked' : '') + ' width="' + px * 2 + '" height="' + px * 2 + '"></canvas>'; }

  // ===== 成就 tab =====
  function achvHead() {
    return '<view class="pc-achievement-metrics"><view class="pc-gs-row"><view class="pc-gs-main"><text class="pc-gs-icon">G</text><text class="pc-gs-num">1230</text></view><view class="pc-gs-link">如何获取积分</view></view>' +
      '<view class="pc-rank"><text class="pc-rank-title">总积分排名</text><view class="pc-rank-body"><view class="pc-rank-left"><text class="pc-rank-no">TOP20%</text></view><view class="pc-rank-sep"></view>' +
      '<view class="pc-rank-right"><text class="pc-rank-k">累计探索</text><text class="pc-rank-v">28家店 · 0.0km</text><text class="pc-rank-exp">本周 +5942 EXP</text></view></view>' +
      '<view class="pc-compare"><text>查看详情</text>' + I('arrow-right', 28) + '</view></view>';
  }
  function medalList() {
    return '<view class="pc-sec-tit">城市勋章 · 5</view><view class="pc-card pc-achv-list">' +
      ['完成一程', '方舟见证人', '老饕', '坐标校准员', '最后的信号'].map(function (n) { return '<view class="pc-achv"><view class="pc-achv-ico pc-achv-ico--fallback">' + I('star', 40) + '</view><view class="pc-achv-main"><view class="pc-achv-name">' + n + '</view></view>' + I('check', 32, 'pc-achv-ok') + '</view>'; }).join('') + '</view></view>';
  }
  function achvBefore() {
    function wall(t, s) { return '<view class="pc-wall"><view class="pc-wall-l"><text class="pc-wall-t">' + t + '</text><text class="pc-wall-s">' + s + '</text></view>' + I('arrow-right', 32, 'pc-wall-arrow') + '</view>'; }
    return '<view class="sv"><view class="pc-page pc-player pc-self">' + PROFILE.head({ tab: 'achievements', compact: true }) + achvHead() +
      wall('勋章墙', '实时数字装置 · 查看全部勋章') + wall('集邮册', '上街拍到的城市 · 查看全部邮票') + wall('藏品册', '玩的时候拍下的东西 · 查看全部藏品卡') +
      medalList() + '<view style="height:120px"></view></view></view></view></view>' + U.tabbar(4);
  }
  function achvAfter() {
    return '<view class="sv"><view class="pc-page pc-player pc-self">' + PROFILE.head({ tab: 'achievements', compact: true }) + achvHead() +
      '<view class="pc-wall cj-entry" data-go="p3-album"' + U.mk(1) + '><view class="pc-wall-l"><text class="pc-wall-t">收集册</text></view>' +
      '<view class="cj-prev">' + medal('t-ark', 40) + '<img class="st" src="' + C + 'old-movie.jpg"><img class="ob" src="' + C + 'flavor-tour.jpg"></view>' + I('arrow-right', 32, 'pc-wall-arrow') + '</view>' +
      medalList() + '<view style="height:120px"></view></view></view></view></view>' + U.tabbar(4);
  }

  // ===== 收集册合页 =====
  var STD = [['route', '完成一程', '10月26日'], ['arrival', '开始在场', '10月12日'], ['citylit', '点亮上海', '10月20日'], ['roam', '自由漫游', '', '漫游累计 10 公里'], ['companion', '同行者', '', '和好友组队完成一次主题'], ['founding', '创始成员', '', '创建一个俱乐部']];
  function cell(key, name, meta, locked, theme) {
    return '<view class="bw-cell" data-act="detail" data-key="' + key + '" data-name="' + E(name) + '" data-meta="' + E(meta) + '"' + (locked ? ' data-locked="1"' : '') + (theme ? ' data-theme="' + E(theme) + '"' : '') + '>' +
      '<view class="bw-medal' + (locked ? ' is-locked' : '') + '">' + medal(key, 112, locked, 'bw-medal__pin') + '</view>' +
      '<text class="bw-cell__name ep1">' + E(name) + '</text><text class="bw-cell__meta ep1">' + E(meta) + '</text></view>';
  }
  function album(seg) {
    seg = seg || 'medal';
    var themes = TM.THEMES;
    var medals = '<view class="cj-sum"><text class="al-n" style="font-size:40px;font-weight:300">' + (themes.length - 2 + 3) + '</text><text class="al-u">/ ' + (themes.length + STD.length) + '</text></view>' +
      '<view class="bw-list"><text class="bw-list-h"' + U.mk(4) + '>主题勋章</text><view class="bw-grid">' +
      themes.map(function (t, i) { return cell(t.key, t.medal, i < themes.length - 2 ? ['10月26日', '10月21日', '10月20日', '10月18日', '10月12日', '10月3日', '9月28日'][i] : '完成「' + t.name + '」', i >= themes.length - 2, t.name); }).join('') + '</view>' +
      '<text class="bw-list-h">城市身份</text><view class="bw-grid">' + STD.map(function (s) { return cell(s[0], s[1], s[2] || s[3], !s[2]); }).join('') + '</view></view>';
    var pos = [[6, 0, -5], [176, 20, 4], [20, 230, 3], [190, 250, -4], [8, 460, -2], [182, 480, 5], [30, 690, 4], [196, 700, -3]];
    var covers = ['old-movie', 'flavor-tour', 'treasure-hunter', 'anomaly-coordinate', 'time-pollution', 'heat-escape', 'midlife-day', 'life-backup'];
    var stamps = '<view class="cj-pane-al"><view class="al-hd" style="padding:8px var(--cy-page-x) 16px"><text class="al-n">8</text><text class="al-u">枚</text></view><view class="al-stage" style="height:920px">' +
      pos.map(function (p, i) { return '<view class="al-item" style="left:' + p[0] + 'px;top:' + p[1] + 'px;width:150px;height:200px;transform:rotate(' + p[2] + 'deg);z-index:' + i + '"><img class="al-img" src="' + C + covers[i] + '.jpg" style="object-fit:cover"></view>'; }).join('') +
      '</view><view class="al-fab" style="position:absolute" data-toast="打开集邮相机">' + I('plus', 40) + '</view></view>';
    var pile = [[10, 300, -14], [104, 318, 9], [196, 296, -6], [40, 196, 7], [150, 206, -11], [96, 98, 4]];
    var objs = '<view class="oc-bar" style="padding:0 var(--cy-page-x)">' + U.tabs({ variant: 'chip', tabs: [{ key: 'a', label: '全部' }, { key: 'b', label: '食物' }, { key: 'c', label: '物件' }, { key: 'd', label: '招牌' }], active: 'a', cls: 'oc-chips', group: 'ocf' }) + '</view>' +
      '<view class="cj-pile"><view class="ground"></view>' + pile.map(function (p, i) { return '<img src="' + C + covers[i] + '.jpg" style="left:' + p[0] + 'px;top:' + p[1] + 'px;transform:rotate(' + p[2] + 'deg)" data-toast="放大看">'; }).join('') + '</view>';
    var panes = { medal: medals, stamp: stamps, object: objs };
    return '<view class="sv"><view class="cj-page"><view class="c-page-title"><view class="pt pt--left"><view class="pt__title"' + U.mk(2) + '>收集册</view></view></view>' +
      '<view class="cj-seg"' + U.mk(3) + '>' + U.tabs({ variant: 'chip', tabs: [{ key: 'medal', label: '勋章' }, { key: 'stamp', label: '邮票' }, { key: 'object', label: '藏品' }], active: seg, group: 'seg' }) + '</view>' +
      ['medal', 'stamp', 'object'].map(function (k) { return '<view data-pane="seg:' + k + '"' + (k === seg ? '' : ' hidden') + '>' + panes[k] + '</view>'; }).join('') +
      '</view></view>' + U.navBar({}) + detailSheet();
  }
  function detailSheet() {
    return '<view class="ios-sheet" id="medal-dt" data-detent="half"><view class="mask"></view><view class="panel"><view class="grab"></view><view class="body"><view class="cj-dt">' +
      '<canvas id="dtCanvas" width="400" height="400" data-toast="进入 3D 转台(badge-3d),可拖动旋转"></canvas><view class="nm" id="dtName"></view><view class="rar" id="dtRar"></view>' +
      '<view class="ds" id="dtDesc"></view><view class="meta"><view><text>获得时间</text><text id="dtTime"></text></view><view id="dtThemeRow"><text>主题</text><text id="dtTheme"></text></view></view>' +
      '<view class="three" data-toast="进入 3D 转台(badge-3d)">查看 3D 徽章 ›</view></view></view></view></view>';
  }

  // ===== 主题勋章样张 =====
  function themeSheet() {
    return '<view class="sv"><view class="cj-page"><view class="c-page-title"><view class="pt pt--left"><view class="pt__title">主题勋章</view></view></view>' +
      '<view class="tm-grid"' + U.mk(1) + '>' + TM.THEMES.map(function (t) {
        return '<view class="tm-cell" data-act="detail" data-key="' + t.key + '" data-name="' + E(t.medal) + '" data-meta="10月26日" data-theme="' + E(t.name) + '">' + medal(t.key, 132) + '<text class="n">' + E(t.medal) + '</text><text class="t">' + E(t.name) + '</text></view>';
      }).join('') + '</view></view></view>' + U.navBar({}) + detailSheet();
  }
  function mappingNotes() {
    var rows = TM.THEMES.map(function (t) {
      return '<tr><td>' + E(t.name) + '</td><td>' + TM.TYPE_ZH[t.type] + ' → ' + TM.SHAPE[t.type] + '</td><td>' + (TM.STYLE_ZH[t.style] || '—') + ' → ' + window.ENAMEL.FINISH[TM.finishOf(t)].zh + '</td><td>' + t.hue + '°</td><td>' + t.motif + '</td></tr>';
    }).join('');
    return '<ol class="notes"><li data-n="1">每个主题一枚,形状、金属、釉色、徽记都从主题数据推出来(规则见 <code>shared/theme-medals.js</code> 头注释),画法用 master 的珐琅渲染器(PR #653,原文件)。不新增后台字段;主题作者以后想换,再加一个「徽记」选择。' +
      '<table style="margin-top:8px;border-collapse:collapse;font-size:11px;line-height:1.4"><tr><th align="left">主题</th><th align="left">类型→形状</th><th align="left">风格→金属</th><th>色相</th><th>徽记</th></tr>' + rows + '</table></li>' +
      '<li data-n="2">点一枚打开详情半屏(60%),大图可进 3D 转台。</li></ol>';
  }

  // ===== 我的 · 关于 的行图标 =====
  var ENTRY = [['我的优惠券', 'coupon', 'ticket_3d.png'], ['邀请好友', 'gift', 'wrapped_gift_3d.png']];
  var BENEFITS = [['发布路线 · 上限 2', 1, 'round_pushpin_3d.png'], ['发布优惠券', 0, 'admission_tickets_3d.png'], ['到店核销', 0, 'check_mark_button_3d.png'], ['分支 / 条件玩法', 0, 'interaction.png'],
    ['博弈机制（竞速/对抗/协作）', 0, 'crossed_swords_3d.png'], ['开放门店为城市节点', 0, 'convenience_store_3d.png'], ['勋章设计', 0, 'sports_medal_3d.png'], ['建群', 0, 'busts_in_silhouette_3d.png'], ['我的收益', 1, 'money_bag_3d.png', 1]];
  function aboutRows(after, light) {
    var entry = ENTRY.map(function (e, i) {
      return after
        ? '<view class="pc-benefit pc-benefit--3d"' + (i ? '' : U.mk(1)) + '><img class="pc-benefit-3d" src="' + D3 + e[2] + '"><text class="pc-benefit-text">' + e[0] + '</text>' + I('arrow-right', 32, 'pc-benefit-arrow') + '</view>'
        : '<view class="pc-benefit"><view class="pc-benefit-dot pc-benefit-dot--entry">' + I(e[1], 24) + '</view><text class="pc-benefit-text">' + e[0] + '</text>' + I('arrow-right', 32, 'pc-benefit-arrow') + '</view>';
    }).join('');
    var ben = BENEFITS.map(function (b, i) {
      var lock = !b[1];
      return after
        ? '<view class="pc-benefit pc-benefit--3d' + (lock ? ' pc-benefit--lock' : '') + '"' + (i === 1 ? U.mk(2) : (i === 0 ? U.mk(3) : '')) + '><img class="pc-benefit-3d" src="' + D3 + b[2] + '"><text class="pc-benefit-text">' + b[0] + '</text>' + (lock ? I('lock', 28, 'pc-benefit-lock') : (b[3] ? I('arrow-right', 32, 'pc-benefit-arrow') : '')) + '</view>'
        : '<view class="pc-benefit' + (lock ? ' pc-benefit--lock' : '') + '"><view class="pc-benefit-dot">' + I(lock ? 'lock' : 'check', 24) + '</view><text class="pc-benefit-text">' + b[0] + '</text>' + (b[3] ? I('arrow-right', 32, 'pc-benefit-arrow') : '') + '</view>';
    }).join('');
    return '<view class="sv"><view class="pc-page ' + (light ? 'pc-merchant theme-merchant' : 'pc-player') + ' pc-self">' + PROFILE.head({ tab: 'about', compact: true }) +
      '<view class="c-cell pc-solo-cell"><view class="cell"><view class="cell__body"><view class="cell__title">加入时间</view></view><view class="cell__value">2025.11.11</view></view></view>' +
      (light ? '' : '<view class="pc-sec-tit">探索与更多</view><view class="pc-benefits">' + entry + '</view>') +
      '<view class="pc-sec-tit">当前权益</view><view class="pc-benefits">' + ben + '</view><view style="height:120px"></view></view></view></view></view>' + U.tabbar(4, !light);
  }

  var SCREENS = [
    ['p3-achv-before', '改前 · 成就 tab(master)', '成就合页', 'cyp theme-dark pg-profile', achvBefore(), 'subpackageMember/components/cy/profile/index.wxml「成就」', ''],
    ['p3-achv', '成就 tab · 一个入口', '成就合页', 'cyp theme-dark pg-profile', achvAfter(), 'subpackageMember/components/cy/profile/index.wxml「成就」', notes([[1, '三行「勋章墙 / 集邮册 / 藏品册」(带说明小字)合成一行「收集册」,右边直接露出三样东西:一枚勋章、一张邮票、一件藏品。原来藏品册入口看不出来,这里一眼就能看到。']])],
    ['p3-album', '收集册 · 勋章', '成就合页', 'cyp theme-dark pg-bw pg-al pg-oc', album('medal'), 'subpackageP3/pages/badge-wall(列表档)+ stamp-album + object-cards', notes([[2, '一个页面:标题「收集册」。原三个页面的路由保留,打开时直接落到对应分段(badge-wall → 勋章、stamp-album → 邮票、object-cards → 藏品)。'], [3, '顶部三段:勋章 / 邮票 / 藏品(cy-tabs chip,同一页内切换)。'], [4, '勋章段沿用勋章墙列表档(一行两枚、未点亮只留线稿);主题勋章排在最前,每个主题一枚,各不相同(见「主题勋章」)。']])],
    ['p3-album-stamp', '收集册 · 邮票', '成就合页', 'cyp theme-dark pg-bw pg-al pg-oc', album('stamp'), 'subpackageP3/pages/stamp-album/index/index.wxml', notes([[3, '邮票段 = 原集邮册,散落拼贴与右下角相机按钮不变。']])],
    ['p3-album-object', '收集册 · 藏品', '成就合页', 'cyp theme-dark pg-bw pg-al pg-oc', album('object'), 'subpackageP3/pages/object-cards/index/index.wxml', notes([[3, '藏品段 = 原藏品册:分类 chip + 落下来的堆(这里用静态图示意 canvas 物理堆)。']])],
    ['p3-medal-dt', '勋章详情 · 半屏', '主题勋章', 'cyp theme-dark pg-bw pg-al pg-oc', album('medal'), 'subpackageP3/pages/badge-wall 详情 sheet', notes([[4, '点勋章打开 iOS 半屏(60%):大图、名字、类别、获得时间、所属主题;「查看 3D 徽章」进 badge-3d 转台。']])],
    ['p3-theme-medals', '主题勋章 · 样张(9 枚)', '主题勋章', 'cyp theme-dark pg-bw', themeSheet(), 'shared/theme-medals.js + master enamel-pin.js', ''],
    ['p3-about-before', '改前 · 关于的行图标(master)', '行图标', 'cyp theme-dark pg-profile', aboutRows(false), 'subpackageMember/components/cy/profile/index.wxml「关于」', ''],
    ['p3-about', '关于 · 3D 行图标', '行图标', 'cyp theme-dark pg-profile', aboutRows(true), 'subpackageMember/components/cy/profile/index.wxml「关于」', notes([[1, 'V-19:白色圆底去掉,每行换成自己的 Fluent Emoji 3D 图标(MIT,来源见 assets/3d/README.md),28pt、与文字同一中线;文字从 12pt 提到 14pt。'], [2, '没解锁的权益:图标转灰半透明,行尾一把锁;原来用「锁」圆点区分。'], [3, '已解锁、不能点的行尾不放箭头;能进的(我的收益)保留箭头。']])],
    ['p3-about-lt', '关于 · 3D 行图标 · 浅色(商家)', '行图标', 'cyp theme-merchant pg-profile', aboutRows(true, true), 'subpackageMember/components/cy/profile/index.wxml「关于」(商家)', notes([[2, '商家视角同一套图标(商家不出「探索与更多」,照 master)。'], [3, '同深色。']])]
  ];
  var host = document.getElementById('screens');
  host.innerHTML = SCREENS.map(function (s) {
    return '<section class="scr ' + s[3] + '" data-id="' + s[0] + '" data-title="' + s[1] + '" data-group="' + s[2] + '" data-src="' + s[5] + '">' + s[4] + (s[0] === 'p3-theme-medals' ? '' : s[6]) + '</section>';
  }).join('');
  host.querySelector('[data-id="p3-theme-medals"]').insertAdjacentHTML('beforeend', mappingNotes());
  TM.paintAll(host);

  window.ACT = {
    detail: function (el) {
      var s = PROTO.cur(), key = el.getAttribute('data-key'), locked = el.hasAttribute('data-locked');
      var c = s.querySelector('#dtCanvas'); TM.paint(c, key, locked);
      var t = TM.THEMES.filter(function (x) { return x.key === key; })[0];
      s.querySelector('#dtName').textContent = el.getAttribute('data-name');
      s.querySelector('#dtRar').textContent = t ? '主题勋章 · ' + window.ENAMEL.FINISH[TM.finishOf(t)].zh : '城市身份';
      s.querySelector('#dtDesc').textContent = t ? (locked ? '完成「' + t.name + '」后点亮。' : '走完「' + t.name + '」全部节点时获得。') : (locked ? el.getAttribute('data-meta') : '在城市里留下的第一份记录。');
      s.querySelector('#dtTime').textContent = locked ? '未点亮' : el.getAttribute('data-meta');
      s.querySelector('#dtTheme').textContent = t ? t.name : '—';
      PROTO.openSheet('medal-dt', 'half');
    }
  };
  // 详情屏:进屏即打开第一枚
  window.ON_ENTER = function (id) {
    if (id === 'p3-medal-dt') ACT.detail(PROTO.cur().querySelector('.bw-cell'));
  };
})();
