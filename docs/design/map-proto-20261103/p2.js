// 拖动动效与实时天气(业主批注 q~4:「主要就是拖动的动效,下雨也要有,根据实时天气最好」)。
(function () {
  var U = UI;
  function notes(list) { return '<ol class="notes">' + list.map(function (t) { return '<li data-n="' + t[0] + '">' + t[1] + '</li>'; }).join('') + '</ol>'; }
  var PINS = [
    { x: 150, y: 212, img: 'flavor-tour', name: '城市画廊咖啡' }, { x: 258, y: 300, img: 'old-movie', name: '黑石书屋' },
    { x: 82, y: 356, img: 'life-backup' }, { x: 226, y: 438, img: 'time-pollution', name: '半坡展览' }, { x: 112, y: 492, img: 'heat-escape' },
    { x: -70, y: 176, img: 'midlife-day' }, { x: 460, y: 412, img: 'treasure-hunter' }, { x: -90, y: 560, img: 'prebuilt-life' }, { x: 520, y: 150, img: 'flavor-tour' }, { x: -240, y: 300, img: 'old-movie' }
  ];
  function worldMap() {
    var lines = '';
    for (var i = -8; i < 20; i++) lines += '<path d="M' + (i * 90 - 40) + ' -600 C' + (i * 90 + 30) + ' 200 ' + (i * 90 - 30) + ' 700 ' + (i * 90 + 20) + ' 1500" fill="none" stroke="' + (i % 3 ? '#232326' : '#303034') + '" stroke-width="' + (i % 3 ? 1.4 : 5) + '"/>';
    for (var j = -6; j < 20; j++) lines += '<path d="M-800 ' + (j * 110) + ' C0 ' + (j * 110 + 30) + ' 500 ' + (j * 110 - 30) + ' 1300 ' + (j * 110 + 10) + '" fill="none" stroke="' + (j % 3 ? '#232326' : '#303034') + '" stroke-width="' + (j % 3 ? 1.4 : 5) + '"/>';
    return '<svg class="mz-world" width="2100" height="2100" viewBox="-800 -600 2100 2100" aria-hidden="true"><rect x="-800" y="-600" width="2100" height="2100" fill="#141416"/><path d="M300 -600 C350 160 420 220 700 500 L1300 500 L1300 -600 Z" fill="#0A0B0D"/><path d="M12 430 C60 402 120 410 132 462 C140 500 80 540 28 520 C4 508 -6 450 12 430Z" fill="#1B1F1C"/>' + lines + '</svg>';
  }
  function pinHtml(p, i) {
    return '<view class="mz-dpin" data-i="' + i + '"><image class="mz-pin__img" src="assets/covers/' + p.img + '.jpg"></image>' + (p.name ? '<text class="mz-tag">' + p.name + '</text>' : '') + '</view>';
  }
  var drag = '<view class="mz mz--dark mz-drag" data-drag>' + worldMap() + '<view class="mz-dpins"' + U.mk(1) + '>' + PINS.map(pinHtml).join('') + '</view>' +
    '<view class="mz-drift mz-drift--a"' + U.mk(2) + '></view><view class="mz-drift mz-drift--b"></view>' +
    '<view class="mz-search"><text>输入地址</text></view></view>';
  var rain = '<view class="mz mz--dark mz-wx" data-rain>' + worldMap() + '<view class="mz-dpins mz-dpins--still">' + PINS.slice(0, 5).map(function (p, i) { return '<view class="mz-dpin" style="transform:translate(' + (p.x - 20) + 'px,' + (p.y - 20) + 'px)">' + pinHtml(p, i).replace(/^<view[^>]*>/, ''); }).join('') + '</view>' +
    '<canvas class="mz-rain"' + U.mk(1) + '></canvas>' +
    '<view class="mz-wxpick"' + U.mk(2) + '><view class="mz-wxopt is-on" data-act="wxSet" data-w="rain"><text>下雨</text></view><view class="mz-wxopt" data-act="wxSet" data-w="snow"><text>下雪</text></view><view class="mz-wxopt" data-act="wxSet" data-w="fog"><text>起雾</text></view><view class="mz-wxopt" data-act="wxSet" data-w="clear"><text>晴</text></view></view>' +
    '<view class="mz-wxfog"></view><view class="mz-search"><text>输入地址</text></view></view>';
  var SCREENS = [
    ['m-drag', '拖动地图', '地图', 'cyp theme-dark pg-roam', drag, 'components/cy/free-map/index.wxml', notes([
      [1, '在手机框里拖动地图试试。图钉跟着地图走；移到屏幕外的缩小贴边，松手后回到原来的大小。这是真机地图能做到的：微信只在开始和松手时通知我们，所以贴边图钉在松手瞬间轻轻归位，不能逐帧跟手。'],
      [2, '上层的薄云比地图慢一点，拖动时有前后层次（参考 Nal_uh）。']])],
    ['m-rain', '实时天气', '地图', 'cyp theme-dark pg-roam', rain, 'pages/roam/index.wxml', notes([
      [1, '按玩家所在位置的实时天气，地图上层飘雨、雪或雾。只是一层画面，不挡手势。减弱动态时不播。'],
      [2, '这排按钮只为审阅时切换，真机上没有。天气读不到时就是晴天，不出提示。']])]
  ];
  document.getElementById('screens').insertAdjacentHTML('beforeend', SCREENS.map(function (s) {
    return '<section class="scr ' + s[3] + '" data-id="' + s[0] + '" data-title="' + s[1] + '" data-group="' + s[2] + '" data-src="' + s[5] + '">' + s[4] + s[6] + '</section>';
  }).join(''));

  // 拖动
  var box = document.querySelector('[data-drag]');
  var world = box.querySelector('.mz-world'), dpins = box.querySelectorAll('.mz-dpin');
  var driftA = box.querySelector('.mz-drift--a'), driftB = box.querySelector('.mz-drift--b');
  var ox = 0, oy = 0, sx = 0, sy = 0, down = false;
  function place(settling) {
    world.style.transform = 'translate(' + (ox - 800) + 'px,' + (oy - 600) + 'px)';
    driftA.style.transform = 'translate(' + ox * 0.35 + 'px,' + oy * 0.35 + 'px)';
    driftB.style.transform = 'translate(' + ox * 0.55 + 'px,' + oy * 0.55 + 'px)';
    Array.prototype.forEach.call(dpins, function (el) {
      var p = PINS[+el.getAttribute('data-i')];
      var x = p.x + ox, y = p.y + oy, out = x < 0 || x > 390 || y < 100 || y > 820;
      var far = x < -200 || x > 590 || y < -100 || y > 1000;
      if (settling && out) { x = Math.min(366, Math.max(24, x)); y = Math.min(800, Math.max(120, y)); }
      el.classList.toggle('is-edge', !!(settling && out));
      el.style.opacity = far ? 0 : 1;
      el.style.transition = settling ? 'transform .28s cubic-bezier(.2,.7,.2,1),opacity .2s' : 'none';
      el.style.transform = 'translate(' + (x - 20) + 'px,' + (y - 20) + 'px)' + (settling && out ? ' scale(.62)' : '');
    });
  }
  box.addEventListener('pointerdown', function (e) { down = true; sx = e.clientX - ox; sy = e.clientY - oy; try { box.setPointerCapture(e.pointerId); } catch (x) {} });
  box.addEventListener('pointermove', function (e) { if (!down) return; ox = e.clientX - sx; oy = e.clientY - sy; place(false); });
  ['pointerup', 'pointercancel'].forEach(function (t) { box.addEventListener(t, function () { if (!down) return; down = false; place(true); }); });
  place(true);

  // 天气
  var host = document.querySelector('[data-rain]'), cv = host.querySelector('.mz-rain'), fogLayer = host.querySelector('.mz-wxfog');
  var ctx = cv.getContext('2d'), mode = 'rain', parts = [];
  var reduce = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  cv.width = 390; cv.height = 844;
  for (var k = 0; k < 170; k++) parts.push({ x: Math.random() * 480, y: Math.random() * 844, v: 0.6 + Math.random() * 0.7, r: Math.random() });
  function frame() {
    ctx.clearRect(0, 0, 390, 844);
    if (mode === 'rain') {
      ctx.strokeStyle = 'rgba(205,216,235,.42)'; ctx.lineWidth = 1; ctx.beginPath();
      parts.forEach(function (p) { p.y += 15 * p.v; p.x -= 2.6 * p.v; if (p.y > 844) { p.y = -20; p.x = Math.random() * 480; } ctx.moveTo(p.x, p.y); ctx.lineTo(p.x - 2.4, p.y + 13 * p.v); });
      ctx.stroke();
    } else if (mode === 'snow') {
      ctx.fillStyle = 'rgba(240,244,252,.72)';
      parts.forEach(function (p) { p.y += 1.3 * p.v; p.x += Math.sin(p.y / 40 + p.r * 6) * 0.5; if (p.y > 844) { p.y = -6; p.x = Math.random() * 390; } ctx.beginPath(); ctx.arc(p.x, p.y, 1 + p.r * 1.6, 0, 7); ctx.fill(); });
    }
    if (!reduce) requestAnimationFrame(frame);
  }
  frame();
  window.ACT = Object.assign(window.ACT || {}, {
    wxSet: function (el) {
      mode = el.getAttribute('data-w');
      Array.prototype.forEach.call(host.querySelectorAll('.mz-wxopt'), function (o) { o.classList.toggle('is-on', o === el); });
      fogLayer.classList.toggle('is-on', mode === 'fog');
      if (reduce) frame();
    }
  });
})();
