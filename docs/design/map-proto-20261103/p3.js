// 进入大气层、路线与队伍、路线分享卡、卡片飞图钉、远近密度。全部按坐标生成,任何城市都能用。
(function () {
  var U = UI;
  function notes(list) { return '<ol class="notes">' + list.map(function (t) { return '<li data-n="' + t[0] + '">' + t[1] + '</li>'; }).join('') + '</ol>'; }
  function grid(w, h) {
    var s = '<svg class="mz-base" viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="xMidYMid slice" aria-hidden="true"><rect width="' + w + '" height="' + h + '" fill="#141416"/><path d="M300 -20 C350 160 410 220 420 420 L420 -20 Z" fill="#0A0B0D"/><path d="M12 430 C60 402 120 410 132 462 C140 500 80 540 28 520 C4 508 -6 450 12 430Z" fill="#1B1F1C"/>';
    for (var i = 0; i < 7; i++) s += '<path d="M' + (i * 70 - 20) + ' -20 C' + (i * 70 + 24) + ' 300 ' + (i * 70 - 24) + ' 560 ' + (i * 70 + 12) + ' 870" fill="none" stroke="' + (i % 2 ? '#232326' : '#303034') + '" stroke-width="' + (i % 2 ? 1.4 : 5) + '"/>';
    for (var j = 0; j < 8; j++) s += '<path d="M-20 ' + (j * 120 + 20) + ' C120 ' + (j * 120 + 46) + ' 260 ' + (j * 120 - 16) + ' 420 ' + (j * 120 + 30) + '" fill="none" stroke="' + (j % 2 ? '#232326' : '#303034') + '" stroke-width="' + (j % 2 ? 1.4 : 5) + '"/>';
    return s + '</svg>';
  }
  function img(n, cls) { return '<image class="' + (cls || 'mz-pin__img') + '" src="assets/covers/' + n + '.jpg"></image>'; }
  function route(d, color, w) {
    return '<path d="' + d + '" fill="none" stroke="' + color + '" stroke-opacity=".26" stroke-width="' + (w + 9) + '" stroke-linecap="round" stroke-linejoin="round"/><path d="' + d + '" fill="none" stroke="' + color + '" stroke-width="' + w + '" stroke-linecap="round" stroke-linejoin="round"/>';
  }

  // 1 进入大气层
  var atmos = '<view class="mz mz--dark mz-atm" data-atm>' + grid(390, 844) +
    '<view class="mz-atmpins">' + ['flavor-tour:150:212', 'old-movie:258:300', 'life-backup:82:356', 'time-pollution:226:438', 'heat-escape:112:492'].map(function (p) { var a = p.split(':'); return '<view class="mz-pin" style="left:' + a[1] + 'px;top:' + a[2] + 'px;--sz:40px">' + img(a[0]) + '</view>'; }).join('') + '<view class="mz-me" style="left:180px;top:372px"></view></view>' +
    '<canvas class="mz-atmcv"' + U.mk(1) + '></canvas><view class="mz-replay" data-act="atmPlay"' + U.mk(2) + '><text>再放一次</text></view></view>';

  // 2 同场队伍路线
  var R1 = 'M60 700 C90 600 70 520 130 460 C180 410 170 350 190 300';
  var R2 = 'M330 720 C300 640 330 560 280 500 C240 450 280 390 250 330';
  var R3 = 'M200 780 C190 700 220 640 180 600 C150 570 160 530 170 500';
  function teamPin(x, y, im, color, tag) { return '<view class="mz-tpin" style="left:' + x + 'px;top:' + y + 'px;--c:' + color + '">' + img(im) + '<text class="mz-tpin__tag">' + tag + '</text></view>'; }
  var team = '<view class="mz mz--dark">' + grid(390, 844) +
    '<svg class="mz-route" viewBox="0 0 390 844"' + U.mk(1) + '>' + route(R1, '#3DE08A', 4) + route(R2, '#FF7A45', 4) + route(R3, '#5AA9FF', 4) + '</svg>' +
    '<view class="mz-flag" style="left:230px;top:210px"' + U.mk(2) + '>' + '<text>3</text></view>' +
    teamPin(190, 300, 'life-backup', '#3DE08A', '第 2 站') + teamPin(250, 330, 'prebuilt-life', '#FF7A45', '第 2 站') + teamPin(170, 500, 'heat-escape', '#5AA9FF', '第 1 站') +
    '<view class="mz-rail"' + U.mk(3) + '>' + [['life-backup', '#3DE08A'], ['prebuilt-life', '#FF7A45'], ['heat-escape', '#5AA9FF']].map(function (t) { return '<view class="mz-rail__i" style="--c:' + t[1] + '" data-toast="地图跟随这一队">' + img(t[0]) + '</view>'; }).join('') + '</view>' +
    '</view>';
  var consent = '<view class="mz mz--dark">' + grid(390, 844) + '<view class="mz-scrim"></view>' +
    '<view class="mz-sheet"' + U.mk(1) + '><text class="mz-sheet__t">同场路线</text>' +
    '<view class="mz-sheet__row"><text>让同场队伍看到我的路线</text><view class="mz-sw is-on" data-act="swToggle"><view class="mz-sw__k"></view></view></view>' +
    '<view class="mz-sheet__row"' + U.mk(2) + '><text>只看我的队伍</text><view class="mz-sw" data-act="swToggle"><view class="mz-sw__k"></view></view></view>' +
    '<view class="mz-act mz-act--main" data-toast="开始"><text>开始</text></view></view></view>';

  // 3 路线分享卡
  var card = '<view class="mz mz--dark mz-rc"><view class="mz-rc__card"' + U.mk(1) + '>' + grid(390, 560) +
    '<svg class="mz-route mz-rc__route" viewBox="0 0 390 560"><path id="rcPath" d="M70 470 C110 380 90 320 170 280 C240 240 230 170 320 110" fill="none" stroke="#F8F8F8" stroke-width="4" stroke-linecap="round"/><path d="M70 470 C110 380 90 320 170 280 C240 240 230 170 320 110" fill="none" stroke="#F8F8F8" stroke-opacity=".22" stroke-width="13" stroke-linecap="round"/></svg>' +
    '<view class="mz-rc__stat"' + U.mk(2) + '><text class="mz-rc__big">4.0</text><text class="mz-rc__unit">公里</text><text class="mz-rc__name">海上方舟</text></view>' +
    '<view class="mz-rc__row"><text>1:26:54</text><text>7 站</text><text>上海</text></view><view class="mz-rc__code"' + U.mk(3) + '></view></view>' +
    '<view class="mz-rc__acts"><view class="mz-act" data-toast="保存到相册"' + U.mk(4) + '><text>保存图片</text></view><view class="mz-act mz-act--main" data-toast="发给朋友，打开就是这个主题"><text>分享</text></view></view></view>';

  // 4 卡片飞图钉
  var PL = [['flavor-tour', '城市画廊咖啡', '320 米', 150, 260], ['old-movie', '黑石书屋', '540 米', 258, 340], ['life-backup', '滨河公园', '780 米', 82, 400], ['time-pollution', '半坡展览', '1.1 公里', 226, 480]];
  var fly = '<view class="mz mz--dark mz-fly" data-fly><view class="mz-flymap">' + grid(390, 844) + PL.map(function (p, i) { return '<view class="mz-pin mz-flypin' + (i ? '' : ' is-on') + '" style="left:' + p[3] + 'px;top:' + p[4] + 'px;--sz:42px" data-i="' + i + '">' + img(p[0]) + '</view>'; }).join('') + '</view>' +
    '<view class="mz-search"><text>输入地址</text></view><view class="mz-cards"' + U.mk(1) + '>' + PL.map(function (p) { return '<view class="mz-c">' + img(p[0], 'mz-c__img') + '<view class="mz-c__b"><text class="mz-c__t">' + p[1] + '</text><text class="mz-c__d">' + p[2] + '</text></view></view>'; }).join('') + '</view></view>';

  // 5 远近密度
  var dots = '';
  for (var d = 0; d < 90; d++) { var a = d * 2.399, r = 8 + 13.5 * Math.sqrt(d); dots += '<view class="mz-dot" style="left:' + (195 + r * Math.cos(a)).toFixed(0) + 'px;top:' + (380 + r * Math.sin(a) * 1.15).toFixed(0) + 'px"></view>'; }
  var zoom = '<view class="mz mz--dark mz-z" data-z="far">' + grid(390, 844) + '<view class="mz-dots"' + U.mk(1) + '>' + dots + '</view>' +
    '<view class="mz-zpins">' + PL.map(function (p) { return '<view class="mz-pin" style="left:' + p[3] + 'px;top:' + p[4] + 'px;--sz:40px">' + img(p[0]) + '<text class="mz-tag">' + p[1] + '</text></view>'; }).join('') + '</view>' +
    '<view class="mz-count"' + U.mk(2) + '><text>附近 243 个地点</text></view>' +
    '<view class="mz-wxpick"' + U.mk(3) + '><view class="mz-wxopt is-on" data-act="zSet" data-z="far"><text>远</text></view><view class="mz-wxopt" data-act="zSet" data-z="near"><text>近</text></view></view></view>';

  var SCREENS = [
    ['m-atm', '进入大气层', '进入与路线', 'cyp theme-dark pg-roam', atmos, 'pages/roam/index.wxml', notes([
      [1, '第一次进漫游：地球在太空里转到中国，俯冲，穿过云层，云散开就是玩家所在的真实地图。任何城市都一样，云层遮住切换的那一刻。这是看节奏的预览，地球的画面还会再打磨。'],
      [2, '真机上按钮不存在。只在第一次进入时播放，可以点屏幕跳过；网络慢或开了减弱动态时直接进地图。']])],
    ['m-team', '同场队伍路线', '进入与路线', 'cyp theme-dark pg-roam', team, 'pages/play/index.wxml', notes([
      [1, '自己和每支队伍各一条带光晕的彩色路线（参考 jacobrodri_）。任何城市都能用。位置大约每 10–15 秒更新一次。'],
      [2, '下一站用同一个数字图钉，大家朝同一个目标跑。'],
      [3, '右侧头像一排：点一队，地图跟随这一队。']])],
    ['m-consent', '开始前确认', '进入与路线', 'cyp theme-dark pg-roam', consent, 'pages/play/index.wxml', notes([
      [1, '只有同一场的队伍互相可见，开始前每个人自己决定（你确认的三条限制）。默认打开还是关闭见待定问题。'],
      [2, '作者可以按主题关掉这项，关掉时这个页面不出现。']])],
    ['m-card2', '路线分享卡', '进入与路线', 'cyp theme-dark pg-roam', card, 'pages/play/index.wxml', notes([
      [1, '通关后，路线在深色地图上自己画一遍，停在这张卡（参考 rtvux）。'],
      [2, '大数字沿用现在的成绩风格。'],
      [3, '右下角是小程序码，别人扫码直接打开这个主题，这是主要的引流入口。不要求分享，也没有分享奖励。'],
      [4, '保存图片可以发朋友圈或别的平台。']])],
    ['m-fly', '卡片与地图联动', '进入与路线', 'cyp theme-dark pg-roam', fly, 'pages/roam/index.wxml', notes([
      [1, '在手机框里左右滑卡片：地图飞到对应地点，图钉放大（参考 paularambles）。']])],
    ['m-zoom', '远近密度', '进入与路线', 'cyp theme-dark pg-roam', zoom, 'pages/roam/index.wxml', notes([
      [1, '拉远时是一片小圆点，看得出哪里热闹；拉近变成照片图钉（参考 isabellacomelli）。'],
      [2, '拉远时显示真实的附近地点数。数字太小时不显示。'],
      [3, '这两个按钮只为审阅时切换，真机上是双指缩放。']])]
  ];
  var host = document.getElementById('screens');
  host.insertAdjacentHTML('beforeend', SCREENS.map(function (s) {
    return '<section class="scr ' + s[3] + '" data-id="' + s[0] + '" data-title="' + s[1] + '" data-group="' + s[2] + '" data-src="' + s[5] + '">' + s[4] + s[6] + '</section>';
  }).join(''));

  // 大气层:经纬度简化大陆 + 正射投影 + 云团
  var LAND = [
    [[-168, 66], [-140, 70], [-95, 72], [-65, 60], [-55, 50], [-80, 25], [-97, 18], [-105, 22], [-125, 45], [-150, 58]],
    [[-80, 10], [-50, 0], [-35, -8], [-48, -28], [-68, -55], [-74, -30], [-81, -5]],
    [[-10, 36], [0, 50], [10, 58], [30, 70], [70, 74], [110, 76], [140, 72], [170, 65], [140, 50], [122, 38], [121, 30], [110, 20], [105, 10], [98, 16], [80, 8], [72, 20], [55, 25], [35, 30], [10, 38]],
    [[-17, 32], [10, 35], [33, 30], [50, 12], [40, -15], [20, -34], [12, -18], [8, 4], [-15, 12]],
    [[114, -22], [130, -12], [145, -14], [153, -28], [145, -39], [116, -35]],
    [[130, 32], [136, 36], [141, 42], [137, 34]]
  ];
  var C = host.querySelector('[data-atm] .mz-atmcv'), g = C.getContext('2d'), W = 390, H = 844, raf = 0, t0 = 0;
  C.width = W * 2; C.height = H * 2; g.scale(2, 2);
  var stars = [], puffs = [];
  for (var i = 0; i < 90; i++) stars.push([Math.random() * W, Math.random() * H, Math.random() * 1.2 + .3]);
  for (var k = 0; k < 34; k++) { var an = k * 2.399; puffs.push({ a: an, d: 20 + 8 * Math.sqrt(k) * 6, r: 90 + (k % 5) * 26 }); }
  function ease(x) { return x < 0 ? 0 : x > 1 ? 1 : x * x * (3 - 2 * x); }
  function globe(lon0, R, cx, cy) {
    var lat0 = 22 * Math.PI / 180, l0 = lon0 * Math.PI / 180;
    var glow = g.createRadialGradient(cx, cy, R * .94, cx, cy, R * 1.22);
    glow.addColorStop(0, 'rgba(120,170,255,.65)'); glow.addColorStop(1, 'rgba(120,170,255,0)');
    g.fillStyle = glow; g.beginPath(); g.arc(cx, cy, R * 1.22, 0, 7); g.fill();
    var sea = g.createRadialGradient(cx - R * .3, cy - R * .35, R * .1, cx, cy, R);
    sea.addColorStop(0, '#2C6FBE'); sea.addColorStop(1, '#0B2547');
    g.fillStyle = sea; g.beginPath(); g.arc(cx, cy, R, 0, 7); g.fill();
    g.save(); g.beginPath(); g.arc(cx, cy, R, 0, 7); g.clip();
    LAND.forEach(function (poly) {
      g.beginPath(); var started = false;
      poly.forEach(function (p, idx) {
        var next = poly[(idx + 1) % poly.length];
        for (var s = 0; s < 8; s++) {
          var la = (p[1] + (next[1] - p[1]) * s / 8) * Math.PI / 180, lo = (p[0] + (next[0] - p[0]) * s / 8) * Math.PI / 180 - l0;
          var cos = Math.sin(lat0) * Math.sin(la) + Math.cos(lat0) * Math.cos(la) * Math.cos(lo);
          var x = cx + R * Math.cos(la) * Math.sin(lo), y = cy - R * (Math.cos(lat0) * Math.sin(la) - Math.sin(lat0) * Math.cos(la) * Math.cos(lo));
          if (cos < 0) { var n = Math.hypot(x - cx, y - cy) || 1; x = cx + (x - cx) / n * R; y = cy + (y - cy) / n * R; }
          if (!started) { g.moveTo(x, y); started = true; } else g.lineTo(x, y);
        }
      });
      g.closePath(); g.fillStyle = '#5DA869'; g.fill(); g.strokeStyle = 'rgba(214,226,170,.7)'; g.lineWidth = 1.4; g.stroke();
    });
    var shade = g.createRadialGradient(cx - R * .35, cy - R * .4, R * .3, cx, cy, R * 1.02);
    shade.addColorStop(0, 'rgba(0,0,0,0)'); shade.addColorStop(1, 'rgba(0,8,30,.62)');
    g.fillStyle = shade; g.fillRect(cx - R, cy - R, R * 2, R * 2); g.restore();
  }
  function puff(x, y, r, alpha) {
    var p = g.createRadialGradient(x - r * .25, y - r * .3, r * .08, x, y, r);
    p.addColorStop(0, 'rgba(255,255,255,' + alpha + ')');
    p.addColorStop(.62, 'rgba(236,234,248,' + alpha * .96 + ')');
    p.addColorStop(.86, 'rgba(198,200,226,' + alpha * .7 + ')');
    p.addColorStop(1, 'rgba(190,192,220,0)');
    g.fillStyle = p; g.beginPath(); g.arc(x, y, r, 0, 7); g.fill();
  }
  function draw(t) {
    g.clearRect(0, 0, W, H);
    var grow = ease((t - 1.3) / 1.2), inCloud = ease((t - 1.9) / .7), open = ease((t - 2.9) / 1.0);
    if (open < .02) {
      g.fillStyle = '#04050A'; g.fillRect(0, 0, W, H);
      stars.forEach(function (s) { g.fillStyle = 'rgba(255,255,255,.75)'; g.fillRect(s[0], s[1], s[2], s[2]); });
      globe(-40 + 161 * ease(t / 1.6), 150 + 900 * grow * grow, W / 2, H * .46 + 130 * grow);
    } else {
      // 云散时底下已经是地图:只留云团
    }
    if (inCloud > 0) {
      puffs.forEach(function (p, i) {
        var push = open * (260 + i * 11);
        var x = W / 2 + Math.cos(p.a) * (p.d + push), y = H / 2 + Math.sin(p.a) * (p.d * 1.5 + push * 1.25);
        var enter = ease((t - 1.9 - i * .012) / .55);
        puff(x, y + (1 - enter) * 240, p.r * (.5 + enter * .9) * (1 + open * .5), inCloud * (1 - open * .98));
      });
    }
  }
  function play() {
    cancelAnimationFrame(raf); t0 = 0;
    if (window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches) { g.clearRect(0, 0, W, H); return; }
    function step(ts) { if (!t0) t0 = ts; var t = (ts - t0) / 1000; draw(t); if (t < 4.1) raf = requestAnimationFrame(step); else g.clearRect(0, 0, W, H); }
    raf = requestAnimationFrame(step);
  }
  window.__atmDraw = draw;

  // 卡片飞图钉
  var flyBox = host.querySelector('[data-fly]'), flyCards = flyBox.querySelector('.mz-cards'), flyMap = flyBox.querySelector('.mz-flymap'), flyPins = flyBox.querySelectorAll('.mz-flypin');
  function flyTo(i) {
    var p = PL[i];
    flyMap.style.transform = 'translate(' + (195 - p[3]) + 'px,' + (340 - p[4]) + 'px)';
    Array.prototype.forEach.call(flyPins, function (el, n) { el.classList.toggle('is-on', n === i); });
  }
  var flyLast = 0;
  flyCards.addEventListener('scroll', function () { var i = Math.round(flyCards.scrollLeft / 256); if (i !== flyLast) { flyLast = i; flyTo(i); } });
  flyTo(0);

  window.ACT = Object.assign(window.ACT || {}, {
    atmPlay: play,
    swToggle: function (el) { el.classList.toggle('is-on'); },
    zSet: function (el) {
      var box = el.closest('.mz-z'); box.setAttribute('data-z', el.getAttribute('data-z'));
      Array.prototype.forEach.call(box.querySelectorAll('.mz-wxopt'), function (o) { o.classList.toggle('is-on', o === el); });
    }
  });
  // 切到这一屏时自动播一次
  var seen = false;
  new MutationObserver(function () {
    var sec = C.closest('.scr'); var on = sec && getComputedStyle(sec).display !== 'none';
    if (on && !seen) { seen = true; play(); } else if (!on) seen = false;
  }).observe(host, { attributes: true, subtree: true, attributeFilter: ['class', 'style'] });
  setTimeout(function () { var sec = C.closest('.scr'); if (sec && getComputedStyle(sec).display !== 'none' && !seen) { seen = true; play(); } }, 200);
})();
