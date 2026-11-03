// 地图视觉提案:底图是按腾讯个性化样式的能力画的预览,真机效果以绑定样式后的 subkey 为准。
// 结构沿用 pages/roam/index.wxml 的 cy-map-address-search、.pcard、.pstat。照片全部是示例图。
(function () {
  var U = UI;
  function notes(list) { return '<ol class="notes">' + list.map(function (t) { return '<li data-n="' + t[0] + '">' + t[1] + '</li>'; }).join('') + '</ol>'; }

  var ROADS = [
    'M-20 120 C80 150 190 110 420 170', 'M-20 300 C90 280 230 340 420 300', 'M-20 520 C120 500 260 560 420 510',
    'M-20 700 C100 680 280 730 420 690', 'M60 -20 C80 180 40 420 90 870', 'M210 -20 C190 200 240 500 200 870', 'M340 -20 C360 260 310 560 350 870'
  ];
  var LANES = [
    'M-20 60 L420 90', 'M-20 210 L420 235', 'M-20 400 L420 420', 'M-20 610 L420 600', 'M-20 790 L420 770',
    'M140 -20 L120 870', 'M280 -20 L300 870', 'M20 -20 L10 870', 'M90 210 L210 235', 'M210 420 L350 400', 'M90 610 L200 600'
  ];
  var LABELS = [[100, 142, '南京东路'], [242, 322, '河南中路'], [60, 508, '福州路'], [262, 548, '延安东路'], [104, 700, '人民大道']];

  function basemap(c) {
    var s = '<svg class="mz-base" viewBox="0 0 390 844" preserveAspectRatio="xMidYMid slice" aria-hidden="true"><rect width="390" height="844" fill="' + c.land + '"/>';
    s += '<path d="M300 -20 C350 160 410 220 420 420 L420 -20 Z" fill="' + c.water + '"/>';
    s += '<path d="M12 430 C60 402 120 410 132 462 C140 500 80 540 28 520 C4 508 -6 450 12 430Z" fill="' + c.park + '"/>';
    LANES.forEach(function (d) { s += '<path d="' + d + '" fill="none" stroke="' + c.lane + '" stroke-width="1.4"/>'; });
    ROADS.forEach(function (d) { s += '<path d="' + d + '" fill="none" stroke="' + c.road + '" stroke-width="5"/>'; });
    LABELS.forEach(function (l) { s += '<text x="' + l[0] + '" y="' + l[1] + '" font-size="10" fill="' + c.text + '" letter-spacing="1">' + l[2] + '</text>'; });
    return s + '</svg>';
  }
  var DARK = { land: '#141416', water: '#0A0B0D', park: '#1B1F1C', lane: '#232326', road: '#303034', text: '#6F6F76' };
  var LIGHT = { land: '#F1EFEA', water: '#BFDDF2', park: '#CFE7C2', lane: '#FFFFFF', road: '#FBFBF8', text: '#8A8A8A' };

  function photo(n) { return 'assets/covers/' + n + '.jpg'; }
  function pin(x, y, img, opt) {
    opt = opt || {};
    var sz = opt.size || 40;
    return '<view class="mz-pin' + (opt.edge ? ' mz-pin--edge' : '') + '" style="left:' + x + 'px;top:' + y + 'px;--sz:' + sz + 'px"' +
      (opt.mk ? U.mk(opt.mk) : '') + (opt.go ? ' data-go="' + opt.go + '"' : '') + '>' +
      (opt.label ? '<text class="mz-tag">' + opt.label + '</text>' : '') +
      '<image class="mz-pin__img" src="' + photo(img) + '"></image></view>';
  }
  function me(x, y) { return '<view class="mz-me" style="left:' + x + 'px;top:' + y + 'px"></view>'; }

  function search() { return '<view class="mz-search"><text>输入地址</text></view>'; }
  function pcard(mk) {
    return '<view class="pcard mz-pcard"' + (mk ? U.mk(mk) : '') + '><view class="pcard__head pcard__head--goal"><view class="pcard__goal-copy"><text class="pcard__title">继续行走，点亮这片街区</text></view></view>' +
      '<view class="pcard__row"><view class="pstat"><text class="pstat__v">18:42</text><text class="pstat__l">用时</text></view><view class="pstat"><text class="pstat__v">12%</text><text class="pstat__l">地盘</text></view><view class="pstat"><text class="pstat__v">1.26</text><text class="pstat__l">距离</text></view></view></view>';
  }

  function pinsDark(mkPins, mkTag, mkEdge, go) {
    return pin(150, 212, 'flavor-tour', { size: 46, label: '咖啡', mk: mkTag, go: go }) +
      pin(258, 300, 'old-movie', { label: '书店' }) +
      pin(82, 356, 'life-backup', { mk: mkPins }) +
      pin(226, 438, 'time-pollution', { label: '展览' }) +
      pin(112, 492, 'heat-escape') +
      pin(4, 176, 'midlife-day', { edge: 1, size: 26, mk: mkEdge }) +
      pin(360, 412, 'treasure-hunter', { edge: 1, size: 26 }) +
      pin(4, 560, 'prebuilt-life', { edge: 1, size: 26 }) + me(180, 372);
  }

  var before = '<view class="mz">' + basemap(LIGHT) +
    '<view class="mz-old" style="left:150px;top:220px"><text>城市画廊咖啡</text></view><view class="mz-old" style="left:220px;top:310px"><text>黑石书屋</text></view><view class="mz-old" style="left:70px;top:400px"><text>滨河公园</text></view>' +
    me(180, 372) + search() + pcard() + '</view>';

  var dark = '<view class="mz mz--dark">' + '<view' + U.mk(1) + ' class="mz-basewrap">' + basemap(DARK) + '</view>' + pinsDark(2, 3, 4, 'm-card') + search() + pcard() + '</view>';

  var card = '<view class="mz mz--dark">' + basemap(DARK) + pinsDark(0, 0, 0) + '<view class="mz-scrim"></view>' +
    '<view class="mz-card"' + U.mk(1) + '><image class="mz-card__img" src="' + photo('flavor-tour') + '"></image>' +
    '<view class="mz-card__shade"></view><text class="mz-card__title">城市画廊咖啡</text>' +
    '<view class="mz-card__body"><text class="mz-card__addr">虹口区山阴路 128 号</text>' +
    '<view class="mz-card__row"><text class="mz-card__rate">4.8</text><text class="mz-card__dist">320 米</text></view>' +
    '<view class="mz-card__acts"><view class="mz-act mz-act--main"' + U.mk(2) + ' data-toast="调起微信地图导航"><text>导航</text></view>' +
    '<view class="mz-act"' + U.mk(3) + ' data-toast="分享给朋友，打开就是这个地点"><text>分享</text></view></view></view>' +
    '<view class="mz-card__close" data-back></view></view></view>';

  var clouds = '<view class="mz mz--dark mz-zoom" id="mzZoom">' + '<view class="mz-zoomwrap">' + basemap(DARK) + pinsDark(0, 0, 0) + '</view>' +
    '<view class="mz-cloud mz-cloud--a"' + U.mk(1) + '></view><view class="mz-cloud mz-cloud--b"></view><view class="mz-cloud mz-cloud--c"></view>' +
    search() + pcard() + '<view class="mz-play" data-act="playClouds"' + U.mk(2) + '><text>播放</text></view></view>';

  var fog = '<view class="mz mz--dark">' + basemap(DARK) + pinsDark(0, 0, 0) +
    '<view class="mz-fog"' + U.mk(1) + '></view>' + me(180, 372) + search() + pcard() + '</view>';

  var play = '<view class="mz mz--dark">' + '<view' + U.mk(1) + ' class="mz-basewrap">' + basemap(DARK) + '</view>' +
    '<svg class="mz-route" viewBox="0 0 390 844"><path d="M82 620 C120 520 90 440 180 372 C240 330 240 260 300 180" fill="none" stroke="#F8F8F8" stroke-width="3" stroke-linecap="round" stroke-dasharray="1 9"/></svg>' +
    '<view class="mz-stn mz-stn--done" style="left:68px;top:606px"' + U.mk(2) + '><text>1</text></view><view class="mz-stn mz-stn--done" style="left:166px;top:358px"><text>2</text></view><view class="mz-stn" style="left:286px;top:166px"><text>3</text></view>' +
    '<view class="mz-navbtn"' + U.mk(3) + ' data-toast="调起微信地图导航到第 3 站"><text>导航到第 3 站</text></view></view>';

  var SCREENS = [
    ['m-before', '改前 · 现在的地图', '地图', 'cyp theme-dark pg-roam', before, 'pages/roam/index.wxml', ''],
    ['m-dark', '深色地图与照片图钉', '地图', 'cyp theme-dark pg-roam', dark, 'pages/roam/index.wxml', notes([
      [1, '底图换成黑灰单色：路是灰线，水更暗，地名压低。这一层用你的 key 和腾讯样式实现。这里是按样式能力画的预览，真机要等你绑定样式后才能对照。'],
      [2, '地点从文字气泡换成圆形照片图钉（参考 rybinandy）。'],
      [3, '只有放大或被选中的地点带一枚小标签，其余只留照片，避免满屏文字。'],
      [4, '靠近屏幕边的地点缩小贴边，不消失；更远的不显示（你 10-22 定的规则）。']])],
    ['m-card', '点图钉打开地点卡', '地图', 'cyp theme-dark pg-roam', card, 'subpackageRoam/poi-detail', notes([
      [1, '点图钉，地图把地点移到中间，卡片从图钉处展开：照片在上，地址、评分、距离在下。'],
      [2, '导航：直接调起微信地图，不用复制地址。'],
      [3, '分享：发给朋友，对方打开就落在这个地点；朋友没登录也能看。']])],
    ['m-clouds', '云层转场', '地图', 'cyp theme-dark pg-roam', clouds, 'pages/roam/index.wxml', notes([
      [1, '从远到近放大时，云层从屏幕中间向四周散开（参考 Nal_uh）。云画在地图上层，不是地图本身。'],
      [2, '点「播放」看过程。系统设置了减弱动态时直接到位，不播云层。']])],
    ['m-fog', '深色地图上的迷雾', '地图', 'cyp theme-dark pg-roam', fog, 'pages/roam/index.wxml', notes([
      [1, '沿用现在的屏幕迷雾，只换颜色和边缘：走过的地方清楚，边缘柔和地融进雾里。地名一起被遮住。']])],
    ['m-play', '游玩页地图', '地图', 'cyp theme-dark pg-roam', play, 'components/cy/free-map/index.wxml', notes([
      [1, '游玩页和漫游用同一套深色样式，切换时不会一亮一暗。'],
      [2, '站点是数字图钉，走过的变暗。'],
      [3, '导航到下一站，调起微信地图。']])]
  ];
  document.getElementById('screens').insertAdjacentHTML('beforeend', SCREENS.map(function (s) {
    return '<section class="scr ' + s[3] + '" data-id="' + s[0] + '" data-title="' + s[1] + '" data-group="' + s[2] + '" data-src="' + s[5] + '">' + s[4] + s[6] + '</section>';
  }).join(''));
  window.ACT = Object.assign(window.ACT || {}, {
    playClouds: function (el) {
      var z = el.closest('.mz-zoom');
      z.classList.remove('is-run');
      void z.offsetWidth;
      z.classList.add('is-far');
      setTimeout(function () { z.classList.remove('is-far'); z.classList.add('is-run'); }, 450);
    }
  });
})();
