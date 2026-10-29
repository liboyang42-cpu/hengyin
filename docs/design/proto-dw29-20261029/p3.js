// P-3 第二轮:收集册(V-17)、行图标两案(V-19)、主题勋章新方向(V-23b)。
// 收集册照 master 集邮册的浅色纸感(stamp-album 根节点就是 .theme-light),整本做成集邮册:勋章和邮票都装进齿孔邮票框,插在透明插袋里;
// 藏品 = object-cards 的抠图物件,用 master 的 object-pile.js(原文件包成 shared/object-pile.bundle.js)从上往下掉、堆起来。
(function () {
  var U = UI, I = U.icon, E = U.esc, TM = THEME_MEDALS, M2 = MEDALS2, PILE = OBJECT_PILE;
  var C = 'assets/covers/', D3 = 'assets/3d/', OB = 'assets/obj/';
  function notes(list) { return '<ol class="notes">' + list.map(function (t) { return '<li data-n="' + t[0] + '">' + t[1] + '</li>'; }).join('') + '</ol>'; }
  function canvasMedal(key, px, locked, cls) { return '<canvas class="' + (cls || '') + '" data-medal="' + key + '"' + (locked ? ' data-locked' : '') + ' width="' + px * 2 + '" height="' + px * 2 + '"></canvas>'; }

  // ===== 齿孔:邮票四边的半圆咬口,SVG mask 按实际尺寸算,孔距约 9pt =====
  function perf(w, h, r, step) {
    r = r || 3; step = step || 9;
    var holes = '';
    function edge(len, fn) { var n = Math.max(2, Math.round(len / step)), sp = len / n; for (var i = 0; i <= n; i++) holes += fn(i * sp); }
    edge(w, function (x) { return '<circle cx="' + x.toFixed(1) + '" cy="0" r="' + r + '"/><circle cx="' + x.toFixed(1) + '" cy="' + h + '" r="' + r + '"/>'; });
    edge(h, function (y) { return '<circle cx="0" cy="' + y.toFixed(1) + '" r="' + r + '"/><circle cx="' + w + '" cy="' + y.toFixed(1) + '" r="' + r + '"/>'; });
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="' + w + '" height="' + h + '"><defs><mask id="m"><rect width="' + w + '" height="' + h + '" fill="#fff"/><g fill="#000">' + holes + '</g></mask></defs><rect width="' + w + '" height="' + h + '" fill="#000" mask="url(#m)"/></svg>';
    var u = "url('data:image/svg+xml," + encodeURIComponent(svg).replace(/'/g, '%27') + "')";
    return 'width:' + w + 'px;height:' + h + 'px;-webkit-mask-image:' + u + ';mask-image:' + u + ';-webkit-mask-size:100% 100%;mask-size:100% 100%';
  }
  function stamp(w, h, inner, o) {
    o = o || {};
    return '<view class="stp-w' + (o.cls ? ' ' + o.cls : '') + '"' + (o.style ? ' style="' + o.style + '"' : '') + (o.attrs || '') + '><view class="stp' + (o.locked ? ' is-locked' : '') + '" style="' + perf(w, h, o.r, o.step) + '"><view class="stp-in"' + (o.bg ? ' style="background:' + o.bg + '"' : '') + '>' + inner + '</view></view></view>';
  }
  function tint(h, l) { return 'hsl(' + h + ',42%,' + (l || 86) + '%)'; }

  // ===== 数据 =====
  var THEME_HUE = { 't-ark': 205, 't-flavor': 32, 't-anomaly': 222, 't-time': 40, 't-movie': 24, 't-heat': 12 };
  var GOT = { 't-ark': '10月26日', 't-flavor': '10月21日', 't-anomaly': '10月20日', 't-time': '10月18日' };
  var STD = [['route', '完成一程', '10月26日'], ['arrival', '开始在场', '10月12日'], ['citylit', '点亮上海', '10月20日'], ['roam', '自由漫游', '', '漫游累计 10 公里'], ['companion', '同行者', '', '和好友组队完成一次主题'], ['founding', '创始成员', '', '创建一个俱乐部']];
  var STAMPS = [['old-movie', '老电影院门口', '10月26日'], ['flavor-tour', '光明邨的队伍', '10月21日'], ['treasure-hunter', '福州路旧书店', '10月20日'], ['anomaly-coordinate', '外白渡桥', '10月18日'], ['time-pollution', '钟楼', '10月12日'], ['heat-escape', '苏州河', '10月3日'], ['midlife-day', '武康路', '9月28日'], ['life-backup', '衡山路', '9月20日']];
  var CATS = ['全部', '电子产品', '服饰', '鞋包', '食物饮料', '书籍文具', '玩具摆件', '日用杂物', '其他'];
  var OBJS = [
    ['moon_cake', '杏花楼月饼', '食物饮料', '杏花楼 · 福州路'], ['dumpling', '小笼包', '食物饮料', '南翔馒头店'], ['camera', '旧相机', '电子产品', '城隍庙旧货'],
    ['hot_beverage', '热可可', '食物饮料', '武康路'], ['running_shoe', '跑鞋', '鞋包', '滨江步道'], ['teddy_bear', '小熊', '玩具摆件', '田子坊'],
    ['books', '旧书三本', '书籍文具', '福州路旧书店'], ['red_paper_lantern', '灯笼', '玩具摆件', '豫园'], ['croissant', '可颂', '食物饮料', '巨鹿路'],
    ['bubble_tea', '奶茶', '食物饮料', '南京东路'], ['umbrella', '雨伞', '日用杂物', '外滩'], ['headphone', '耳机', '电子产品', '徐家汇'],
    ['handbag', '手袋', '鞋包', '淮海中路'], ['old_key', '老钥匙', '日用杂物', '多伦路'], ['t-shirt', 'T 恤', '服饰', '安福路'],
    ['bento_box', '便当', '食物饮料', '静安寺'], ['pretzel', '椒盐卷', '食物饮料', '愚园路'], ['teacup_without_handle', '茶杯', '日用杂物', '湖心亭']
  ];
  var PILE_ITEMS = OBJS.concat(OBJS.slice(0, 12)).map(function (o, i) { return { key: o[0], title: o[1], category: o[2], place: o[3], n: i }; });

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
    var mini = stamp(54, 64, M2.svg('t-flavor', { cls: 'stp-medal' }), { bg: tint(THEME_HUE['t-flavor']), cls: 'bk-s1', step: 7, r: 2.4 }) +
      stamp(50, 62, '<img class="stp-photo" src="' + C + 'old-movie.jpg">', { cls: 'bk-s2', step: 7, r: 2.4 }) +
      '<img class="bk-s3 sticker-img" src="' + OB + 'moon_cake.png">';
    return '<view class="sv"><view class="pc-page pc-player pc-self">' + PROFILE.head({ tab: 'achievements', compact: true }) + achvHead() +
      '<view class="cj-book paper" data-go="p3-album"' + U.mk(1) + '><text class="cj-book__t">收集册</text><view class="cj-book__stamps">' + mini + '<view class="glassine"></view></view>' + I('arrow-right', 32, 'cj-book__arr') + '</view>' +
      medalList() + '<view style="height:120px"></view></view></view></view></view>' + U.tabbar(4);
  }

  // ===== 收集册(纸面集邮册)=====
  function medalStamp(key, name, meta, locked, theme) {
    var inner = theme ? M2.svg(key, { locked: locked, cls: 'stp-medal' }) : canvasMedal(key, 80, locked, 'stp-medal');
    return '<view class="sb-cell" data-act="view" data-kind="medal" data-key="' + key + '" data-name="' + E(name) + '" data-meta="' + E(meta) + '"' + (locked ? ' data-locked="1"' : '') + (theme ? ' data-theme="1"' : '') + '>' +
      stamp(104, 122, inner, { bg: locked ? '#E6E2DA' : (theme ? tint(THEME_HUE[key]) : '#F6F1E4'), locked: locked }) +
      '<text class="sb-name ep1">' + E(name) + '</text></view>';
  }
  function strips(cells) {
    var rows = [];
    for (var i = 0; i < cells.length; i += 3) rows.push('<view class="sb-row">' + cells.slice(i, i + 3).join('') + '<view class="glassine"></view></view>');
    return rows.join('');
  }
  function segRow(seg, filter) {
    return '<view class="sb-bar"' + U.mk(3) + '>' + U.tabs({ variant: 'chip', tabs: [{ key: 'medal', label: '勋章' }, { key: 'stamp', label: '邮票' }, { key: 'object', label: '藏品' }], active: seg, group: 'seg', cls: 'sb-seg' }) +
      '<view class="sb-tools" data-pane="seg:object"' + (seg === 'object' ? '' : ' hidden') + '>' +
      '<view class="sb-filter' + (filter && filter !== '全部' ? ' is-on' : '') + '" data-sheet="cat-sheet" data-detent="half"' + U.mk(5) + '>' + I('filter-lines', 28) + '<text>' + E(filter || '全部') + '</text></view>' +
      '<view class="sb-gather" data-act="gather"' + U.mk(6) + '></view></view></view>';
  }
  function album(seg, o) {
    seg = seg || 'medal'; o = o || {};
    var themes = M2.ORDER;
    var got = themes.filter(function (k) { return GOT[k]; }).length + 3;
    var medals = '<view class="sb-sum"><text class="al-n">' + got + '</text><text class="al-u">/ ' + (themes.length + STD.length) + '</text></view>' +
      '<text class="sb-h"' + U.mk(4) + '>主题勋章</text>' + strips(themes.map(function (k) { return medalStamp(k, M2.META[k].medal, GOT[k] || '', !GOT[k], 1); })) +
      '<text class="sb-h">城市身份</text>' + strips(STD.map(function (s) { return medalStamp(s[0], s[1], s[2] || s[3], !s[2]); }));
    var pos = [[10, 0, -4], [196, 16, 5], [24, 228, 3], [202, 246, -5], [6, 456, -2], [190, 470, 4], [30, 684, 5], [200, 694, -3]];
    var stamps = '<view class="sb-sum"><text class="al-n">8</text><text class="al-u">枚</text></view><view class="al-stage sb-collage">' +
      STAMPS.map(function (s, i) { var p = pos[i]; return stamp(150, 188, '<img class="stp-photo" src="' + C + s[0] + '.jpg">', { style: 'position:absolute;left:' + p[0] + 'px;top:' + p[1] + 'px;transform:rotate(' + p[2] + 'deg);z-index:' + i, attrs: ' data-act="view" data-kind="stamp" data-i="' + i + '"' + (i === 0 ? U.mk(7) : ''), step: 10, r: 3.4 }); }).join('') +
      '</view><view class="al-fab sb-fab" data-toast="打开集邮相机">' + I('plus', 40) + '</view>';
    var objs = '<view class="sb-pile"' + U.mk(8) + '><canvas class="sb-pile__cv" data-pile="' + (o.drop ? 'drop' : 'settled') + '" data-filter="' + (o.filter || '全部') + '"></canvas></view>';
    var panes = { medal: medals, stamp: stamps, object: objs };
    return '<view class="sb theme-light paper">' + U.navBar({ plain: true }) +
      '<view class="sb-head"><view class="c-page-title"><view class="pt pt--left"><view class="pt__title"' + U.mk(2) + '>收集册</view></view></view>' + segRow(seg, o.filter) + '</view>' +
      '<view class="sb-body' + (seg === 'object' ? ' is-object' : '') + '">' + ['medal', 'stamp', 'object'].map(function (k) { return '<view class="sb-pane sb-pane--' + k + '" data-pane="seg:' + k + '"' + (k === seg ? '' : ' hidden') + '>' + panes[k] + '</view>'; }).join('') + '</view>' +
      '</view>' + catSheet(o.filter) + viewer();
  }
  function catSheet(cur) {
    cur = cur || '全部';
    return '<view class="ios-sheet theme-light" id="cat-sheet" data-detent="half"><view class="mask"></view><view class="panel"><view class="grab"></view><view class="body">' +
      '<view class="sh-hd"><b>分类</b></view><view class="ig-group sh-list">' + CATS.map(function (c) {
        return '<view class="sh-row" data-act="pickCat" data-cat="' + c + '"><text>' + c + '</text>' + (c === cur ? I('check', 36, 'sh-ck') : '') + '</view>';
      }).join('') + '</view></view></view></view>';
  }

  // ===== 放大查看(全屏)=====
  function viewer() {
    return '<view class="vw" hidden><view class="vw-bg"></view><view class="vw-x" data-act="closeView">' + I('close', 36) + '</view>' +
      '<view class="vw-stage" data-mk="9"></view><view class="vw-name"></view><view class="vw-info"></view></view>';
  }
  function rows(list) { return '<view class="vw-group">' + list.map(function (r) { return '<view class="vw-row">' + (r[2] ? '<view class="oc-dot ' + r[2] + '">' + I(r[3], 24) + '</view>' : '') + '<text class="vw-k">' + r[0] + '</text><text class="vw-v">' + E(r[1]) + '</text></view>'; }).join('') + '</view>'; }
  function openViewer(scr, kind, d) {
    var vw = scr.querySelector('.vw'); if (!vw) return;
    var st = vw.querySelector('.vw-stage'), nm = vw.querySelector('.vw-name'), inf = vw.querySelector('.vw-info');
    vw.className = 'vw vw--' + kind + ' ios-sheet open'; vw.hidden = false;
    if (kind === 'medal') {
      var front = d.theme ? M2.svg(d.key, { locked: d.locked }) : '<canvas data-medal="' + d.key + '"' + (d.locked ? ' data-locked' : '') + ' width="520" height="520"></canvas>';
      var back = d.theme ? M2.back(d.key, { date: d.locked ? '' : '2026.' + (d.meta || '').replace(/月/, '.').replace(/日/, '') }) : '<view class="coin-back"></view>';
      st.innerHTML = '<view class="coin-3d" data-key="' + d.key + '"><view class="coin"><view class="face front">' + front + '</view><view class="face back">' + back + '</view></view></view>';
      TM.paintAll(st);
      nm.textContent = d.name;
      inf.innerHTML = rows([['获得时间', d.locked ? '未点亮' : d.meta], ['主题', d.theme ? M2.META[d.key].name : '—']]);
      bindSpin(st.querySelector('.coin-3d'));
    } else if (kind === 'stamp') {
      var s = STAMPS[d.i];
      st.innerHTML = '<view class="tilt">' + stamp(272, 340, '<img class="stp-photo" src="' + C + s[0] + '.jpg">', { step: 12, r: 4.4 }) + '</view>';
      nm.textContent = s[1];
      inf.innerHTML = rows([['拍下时间', s[2]], ['类型', '我的邮票']]);
      bindTilt(st.querySelector('.tilt'));
    } else {
      st.innerHTML = '<view class="tilt"><img class="vw-obj" src="' + OB + d.key + '.png"></view>';
      nm.textContent = d.title;
      inf.innerHTML = rows([['名称', d.title, 'oc-dot--name', 'edit'], ['分类', d.category, 'oc-dot--cat', 'list'], ['地点', d.place, 'oc-dot--place', 'gps']]);
      bindTilt(st.querySelector('.tilt'));
    }
    PROTO.placeMarks();
  }
  // 勋章:点一下转两圈,左右拖动(照 master badge-3d 的 pin-spin);拖过 90° 就看到背面
  function bindSpin(el) {
    var coin = el.querySelector('.coin'), ry = 0, down = null, moved = false;
    function set() { coin.style.transform = 'rotateY(' + ry + 'deg)'; }
    el.onpointerdown = function (e) { down = { x: e.clientX, r: ry }; moved = false; coin.style.transition = 'none'; el.setPointerCapture(e.pointerId); };
    el.onpointermove = function (e) { if (!down) return; var dx = e.clientX - down.x; if (Math.abs(dx) > 3) moved = true; ry = down.r + dx * .9; set(); };
    el.onpointerup = function () {
      if (!down) return; down = null;
      coin.style.transition = 'transform .9s cubic-bezier(.2,.8,.2,1)';
      if (!moved) ry += 720; else ry = Math.round(ry / 180) * 180;
      set();
    };
  }
  function bindTilt(el) {
    el.onpointermove = function (e) { var r = el.getBoundingClientRect(); var x = (e.clientX - r.left) / r.width - .5, y = (e.clientY - r.top) / r.height - .5; el.style.transform = 'rotateY(' + x * 18 + 'deg) rotateX(' + -y * 18 + 'deg)'; };
    el.onpointerleave = function () { el.style.transform = ''; };
  }

  // ===== 藏品堆:master object-pile.js 原样;物件图带一圈白色裁切边(贴纸)=====
  var IMG = {};
  function sticker(key, cb) {
    if (IMG[key]) return cb(IMG[key]);
    var img = new Image();
    img.onload = function () {
      var S = 160, pad = 9, c = document.createElement('canvas'); c.width = c.height = S + pad * 2;
      var x = c.getContext('2d'), k = S / Math.max(img.width, img.height), w = img.width * k, h = img.height * k, ox = pad + (S - w) / 2, oy = pad + (S - h) / 2;
      for (var a = 0; a < 24; a++) { var t = a / 24 * Math.PI * 2; x.drawImage(img, ox + Math.cos(t) * 7, oy + Math.sin(t) * 7, w, h); }
      x.globalCompositeOperation = 'source-in'; x.fillStyle = '#FFFFFF'; x.fillRect(0, 0, c.width, c.height);
      x.globalCompositeOperation = 'source-over'; x.drawImage(img, ox, oy, w, h);
      IMG[key] = c; cb(c);
    };
    img.src = OB + key + '.png';
  }
  var FLOOR_GAP = 56, active = null;
  function Pile(cv) {
    var box = cv.parentNode, W = box.clientWidth, H = box.clientHeight, dpr = 2;
    cv.width = W * dpr; cv.height = H * dpr; cv.style.width = W + 'px'; cv.style.height = H + 'px';
    var ctx = cv.getContext('2d'); ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    var world = { W: W, floor: H - FLOOR_GAP, cx: W / 2, cy: H * .42 };
    var self = { cv: cv, ctx: ctx, world: world, bodies: [], raf: 0 };
    self.build = function (fromTop, filter) {
      var list = PILE_ITEMS.filter(function (it) { return !filter || filter === '全部' || it.category === filter; });
      self.bodies = list.map(function (it, i) { return PILE.body(it, world, { size: 60, seed: i + 7, fromTop: fromTop }); });
      if (!fromTop) for (var k = 0; k < 600; k++) PILE.step(self.bodies, world, 1);
      self.draw();
    };
    self.draw = function () {
      ctx.clearRect(0, 0, W, H);
      var g = ctx.createLinearGradient(0, world.floor, 0, world.floor + 18);
      g.addColorStop(0, 'rgba(70,52,30,.10)'); g.addColorStop(1, 'rgba(70,52,30,0)');
      ctx.fillStyle = g; ctx.fillRect(18, world.floor, W - 36, 18);
      ctx.fillStyle = 'rgba(70,52,30,.28)'; ctx.fillRect(18, world.floor, W - 36, 1);
      ctx.fillStyle = '#6b5a47'; ctx.fillRect(W - 93, world.floor - 60, 5, 60);
      ctx.fillStyle = '#8a7660'; ctx.fillRect(W - 118, world.floor - 76, 56, 24);
      self.bodies.forEach(function (b) {
        var im = IMG[b.item.key]; ctx.save(); ctx.translate(b.x, b.y); ctx.rotate(b.rot);
        if (im) { ctx.shadowColor = 'rgba(60,40,20,.28)'; ctx.shadowBlur = 6; ctx.shadowOffsetY = 3; var s = b.size * 1.12; ctx.drawImage(im, -s / 2, -s / 2, s, s); }
        ctx.restore();
      });
    };
    self.run = function () {
      if (self.raf) return;
      var frame = function () { self.raf = 0; PILE.step(self.bodies, world, 1); self.draw(); if (world.ball || !PILE.settled(self.bodies)) self.raf = requestAnimationFrame(frame); };
      self.raf = requestAnimationFrame(frame);
    };
    self.stop = function () { cancelAnimationFrame(self.raf); self.raf = 0; };
    self.gather = function () {
      world.ball = !world.ball;
      self.bodies.forEach(function (b) { b.vx += (Math.random() - .5) * 2; if (world.ball) b.vy -= 2 + Math.random() * 3; });
      self.run(); return world.ball;
    };
    cv.onclick = function (e) {
      var r = cv.getBoundingClientRect(), s = r.width / W;
      var b = PILE.hit(self.bodies, (e.clientX - r.left) / s, (e.clientY - r.top) / s);
      if (b) openViewer(PROTO.cur(), 'object', b.item);
    };
    return self;
  }
  function startPile(scr, mode) {
    var cv = scr.querySelector('.sb-pile__cv'); if (!cv || cv.closest('[hidden]')) return;
    if (active) active.stop();
    var keys = OBJS.map(function (o) { return o[0]; }), left = keys.length;
    keys.forEach(function (k) { sticker(k, function () { if (--left === 0) go(); }); });
    function go() {
      active = Pile(cv);
      var m = mode || cv.getAttribute('data-pile'), f = cv.getAttribute('data-filter');
      active.build(m === 'drop', f);
      if (m === 'drop') active.run();
    }
  }

  // ===== 主题勋章样张(6 枚)=====
  function medalsSheet() {
    return '<view class="sv"><view class="cj-page"><view class="c-page-title"><view class="pt pt--left"><view class="pt__title">主题勋章</view></view></view>' +
      '<view class="tm-grid"' + U.mk(1) + '>' + M2.ORDER.map(function (k) {
        return '<view class="tm-cell" data-act="view" data-kind="medal" data-theme="1" data-key="' + k + '" data-name="' + E(M2.META[k].medal) + '" data-meta="10月26日">' + M2.svg(k) + '<text class="n">' + E(M2.META[k].medal) + '</text><text class="t">' + E(M2.META[k].name) + '</text></view>';
      }).join('') + '</view></view></view>' + U.navBar({}) + viewer();
  }
  function medalNotes() {
    var rows = M2.ORDER.map(function (k) {
      var d = M2.DEFS[k], m = M2.META[k];
      return '<tr><td>' + E(m.medal) + '</td><td>' + d.shapeZh + '</td><td>' + d.layers + '</td><td>' + d.breakout + '</td><td>' + m.style + ' → ' + M2.METAL_ZH[d.finish] + '</td></tr>';
    }).join('');
    return '<ol class="notes"><li data-n="1">第二版:每枚是一张小插画,外形就是这个主题故事里的核心物件,画面从封面场景拆成远 / 中 / 近三层,每枚都有一样东西伸出边框,所以轮廓一眼能分开。金属按风格,色板按封面。规则见 README「主题勋章的生成规则」,源文件 <code>shared/medals.js</code>。' +
      '<table style="margin-top:8px;border-collapse:collapse;font-size:11px;line-height:1.4"><tr><th align="left">勋章</th><th align="left">外形</th><th align="left">画面三层</th><th align="left">破框</th><th align="left">金属</th></tr>' + rows + '</table></li>' +
      '<li data-n="2">点任意一枚全屏放大:点一下转两圈,左右拖动,拖过去是刻字的背面。</li></ol>';
  }

  // ===== 关于 · 行图标两案 =====
  var ENTRY = [['我的优惠券', 'coupon', 'ticket_3d.png'], ['邀请好友', 'gift', 'wrapped_gift_3d.png']];
  var BEN = [['发布路线 · 上限 2', 1, 'round_pushpin_3d.png', 'route'], ['发布优惠券', 0, 'admission_tickets_3d.png', 'coupon'], ['到店核销', 0, 'check_mark_button_3d.png', 'scan'], ['分支 / 条件玩法', 0, 'interaction.png', 'switch-chapter'],
    ['博弈机制（竞速/对抗/协作）', 0, 'crossed_swords_3d.png', 'flag'], ['开放门店为城市节点', 0, 'convenience_store_3d.png', 'poi-shop'], ['勋章设计', 0, 'sports_medal_3d.png', 'star'], ['建群', 0, 'busts_in_silhouette_3d.png', 'tab-club'], ['我的收益', 1, 'money_bag_3d.png', 'finance', 1]];
  function aboutWrap(light, inner) {
    return '<view class="sv"><view class="pc-page ' + (light ? 'pc-merchant theme-merchant' : 'pc-player') + ' pc-self">' + PROFILE.head({ tab: 'about', compact: true }) +
      '<view class="c-cell pc-solo-cell"><view class="cell"><view class="cell__body"><view class="cell__title">加入时间</view></view><view class="cell__value">2025.11.11</view></view></view>' + inner +
      '<view style="height:120px"></view></view></view></view></view>' + U.tabbar(4, !light);
  }
  function aboutBefore() {
    var entry = ENTRY.map(function (e) { return '<view class="pc-benefit"><view class="pc-benefit-dot pc-benefit-dot--entry">' + I(e[1], 24) + '</view><text class="pc-benefit-text">' + e[0] + '</text>' + I('arrow-right', 32, 'pc-benefit-arrow') + '</view>'; }).join('');
    var ben = BEN.map(function (b) { var lock = !b[1]; return '<view class="pc-benefit' + (lock ? ' pc-benefit--lock' : '') + '"><view class="pc-benefit-dot">' + I(lock ? 'lock' : 'check', 24) + '</view><text class="pc-benefit-text">' + b[0] + '</text>' + (b[4] ? I('arrow-right', 32, 'pc-benefit-arrow') : '') + '</view>'; }).join('');
    return aboutWrap(false, '<view class="pc-sec-tit">探索与更多</view><view class="pc-benefits">' + entry + '</view><view class="pc-sec-tit">当前权益</view><view class="pc-benefits">' + ben + '</view>');
  }
  // A:3D 图标装进 40pt 浅色圆角方块,iOS 设置式分组列表
  function aboutA(light) {
    function row(t, img, lock, arrow, mk) {
      return '<view class="ig-row' + (lock ? ' is-lock' : '') + '"' + U.mk(mk) + '><view class="ig-tile"><img src="' + D3 + img + '"></view><text class="ig-t">' + t + '</text>' + (lock ? I('lock', 32, 'ig-lock') : (arrow ? I('chevron-right', 32, 'ig-arr') : '')) + '</view>';
    }
    var entry = ENTRY.map(function (e, i) { return row(e[0], e[2], 0, 1, i ? 0 : 1); }).join('');
    var ben = BEN.map(function (b, i) { return row(b[0], b[2], !b[1], b[4], i === 1 ? 2 : (i === 0 ? 3 : 0)); }).join('');
    return aboutWrap(light, (light ? '' : '<view class="pc-sec-tit">探索与更多</view><view class="ig-group">' + entry + '</view>') + '<view class="pc-sec-tit">当前权益</view><view class="ig-group">' + ben + '</view>');
  }
  // B:原来的线条图标,放大到 24pt,去掉圆底
  function aboutB(light) {
    function row(t, ic, lock, arrow, mk) {
      return '<view class="pc-benefit ln-row' + (lock ? ' pc-benefit--lock is-lock' : '') + '"' + U.mk(mk) + '>' + I(ic, 48, 'ln-ic') + '<text class="pc-benefit-text">' + t + '</text>' + (lock ? I('lock', 30, 'ln-lock') : (arrow ? I('arrow-right', 32, 'pc-benefit-arrow') : '')) + '</view>';
    }
    var entry = ENTRY.map(function (e, i) { return row(e[0], e[1], 0, 1, i ? 0 : 1); }).join('');
    var ben = BEN.map(function (b, i) { return row(b[0], b[3], !b[1], b[4], i === 1 ? 2 : (i === 0 ? 3 : 0)); }).join('');
    return aboutWrap(light, (light ? '' : '<view class="pc-sec-tit">探索与更多</view><view class="pc-benefits">' + entry + '</view>') + '<view class="pc-sec-tit">当前权益</view><view class="pc-benefits">' + ben + '</view>');
  }

  var N_ALBUM = [[2, '一个页面「收集册」,整本照 master 集邮册的浅色纸面(stamp-album 根节点本来就是 .theme-light):纸纹底、透明插袋。原 badge-wall / stamp-album / object-cards 三个路由保留,打开时落到对应分段。'],
    [3, '只有一行:勋章 / 邮票 / 藏品三段。藏品的分类不再另起一排 chip,收进这一行右边的「筛选」按钮(见藏品屏)。'],
    [4, '勋章段:每枚勋章装进一张齿孔邮票,三张一排插在透明插袋里;主题勋章用第二版插画勋章(见「主题勋章」),城市身份勋章照 master 珐琅渲染。没点亮的邮票灰掉、勋章灰阶。点任意一枚全屏放大。']];
  var N_OBJ = [[3, '同一行右边:「筛选」和 ✦。分类不占第二排。'], [5, '「筛选」显示当前分类,点开 60% 半屏选分类(单选、带勾),选完物件重新从上面落下来(master onFilter 的 fromTop)。'], [6, '✦ 照 master:聚成一团,再按一下落回去。'],
    [8, '藏品 = 拍物成卡时从照片里抠出来的那样东西(cutoutUrl),外面一圈白色裁切边,像贴纸。用 master 的 object-pile.js 原样计算:从屏幕上方掉下来、互相碰撞、堆在地面线上,点一件全屏放大。原型里用 Fluent 3D 物件图代替玩家照片的抠图(来源见 assets/obj)。']];

  var SCREENS = [
    ['p3-achv-before', '改前 · 成就 tab(master)', '成就入口', 'cyp theme-dark pg-profile', achvBefore(), 'subpackageMember/components/cy/profile/index.wxml「成就」', ''],
    ['p3-achv', '成就 tab · 收集册入口', '成就入口', 'cyp theme-dark pg-profile', achvAfter(), 'subpackageMember/components/cy/profile/index.wxml「成就」', notes([[1, '三行「勋章墙 / 集邮册 / 藏品册」合成一本「收集册」:入口本身就是一张集邮册的纸页,插袋里露出一枚勋章邮票、一张邮票、一件藏品贴纸。']])],
    ['p3-album', '收集册 · 勋章', '收集册', 'cyp theme-light pg-al pg-oc', album('medal'), 'subpackageP3/pages/stamp-album(纸面)+ badge-wall(勋章)', notes(N_ALBUM)],
    ['p3-album-stamp', '收集册 · 邮票', '收集册', 'cyp theme-light pg-al pg-oc', album('stamp'), 'subpackageP3/pages/stamp-album/index/index.wxml', notes([[3, '同一行三段。'], [7, '邮票段 = 原集邮册的散落拼贴和右下相机按钮,每张加上齿孔白边,真的像邮票;点一张全屏放大。']])],
    ['p3-album-object', '收集册 · 藏品(堆好了)', '收集册', 'cyp theme-light pg-al pg-oc', album('object'), 'subpackageP3/pages/object-cards + utils/object-pile.js', notes(N_OBJ)],
    ['p3-object-drop', '收集册 · 藏品(正在落下)', '收集册', 'cyp theme-light pg-al pg-oc', album('object', { drop: 1 }), 'subpackageP3/utils/object-pile.js(原样)', notes([[8, '进到藏品段时一件件从上面掉下来、弹一下、堆起来(这一屏每次进入都重放)。减弱动态效果时直接显示堆好的样子(master 同样处理)。']])],
    ['p3-object-filter', '藏品 · 选分类', '收集册', 'cyp theme-light pg-al pg-oc', album('object'), 'subpackageP3/pages/object-cards/index.js CATEGORIES', notes([[5, '分类半屏:全部 + master 的 8 个分类,单选,当前项打勾。']])],
    ['p3-object-food', '藏品 · 食物饮料', '收集册', 'cyp theme-light pg-al pg-oc', album('object', { drop: 1, filter: '食物饮料' }), 'subpackageP3/pages/object-cards/index.js onFilter', notes([[5, '选了「食物饮料」:按钮变实心显示分类名,堆清空后这一类从上面重新落下。']])],
    ['p3-view-medal', '放大 · 主题勋章', '放大查看', 'cyp theme-light pg-al pg-oc', album('medal'), 'subpackageP3/pages/badge-3d(pin-spin:点一下转两圈,左右拖动)', notes([[9, '点任何勋章 / 邮票 / 藏品都是全屏放大:深色模糊底,左上 ✕。勋章照 badge-3d 的 pin-spin:点一下转两圈,左右拖动;下面两行信息。']])],
    ['p3-view-medal-back', '放大 · 勋章背面', '放大查看', 'cyp theme-light pg-al pg-oc', album('medal'), 'subpackageP3/pages/badge-3d', notes([[9, '拖过去是背面:拉丝金属上刻主题名、获得日期和编号。']])],
    ['p3-view-stamp', '放大 · 邮票', '放大查看', 'cyp theme-light pg-al pg-oc', album('stamp'), 'subpackageP3/pages/stamp-album + scene-roam-stamp-album 详情', notes([[9, '邮票放大到整屏宽,手指移动时轻微倾斜;下面是 master 详情里已有的字段。']])],
    ['p3-view-object', '放大 · 藏品', '放大查看', 'cyp theme-light pg-al pg-oc', album('object'), 'subpackageP3/pages/object-cards 放大态 + cy-spin-card tilt', notes([[9, '藏品放大照 master 放大态:有转台就左右转(cy-spin-card),这里是抠图 + 倾斜;信息行照 master(名称可改名 / 分类 / 地点)。']])],
    ['p3-medals', '主题勋章 · 第二版(6 枚)', '主题勋章', 'cyp theme-dark pg-bw', medalsSheet(), 'shared/medals.js(新)', ''],
    ['p3-about-before', '改前 · 关于的行图标(master)', '行图标', 'cyp theme-dark pg-profile', aboutBefore(), 'subpackageMember/components/cy/profile/index.wxml「关于」', ''],
    ['p3-about-a', 'A · 3D 图标 + 圆角方块', '行图标', 'cyp theme-dark pg-profile', aboutA(false), 'subpackageMember/components/cy/profile/index.wxml「关于」', notes([[1, 'A 案:Fluent 3D 图标放大到 32pt,装进 40pt 的浅色圆角方块,方块和文字同一中线;整组做成 iOS 设置式的分组卡片,分隔线从文字起。'], [2, '没解锁:图标转灰,行尾一把锁。'], [3, '能进的行尾 ›,不能进的不放。']])],
    ['p3-about-a-lt', 'A · 浅色(商家)', '行图标', 'cyp theme-merchant pg-profile', aboutA(true), 'subpackageMember/components/cy/profile/index.wxml「关于」(商家)', notes([[2, '商家浅色同一套(商家不出「探索与更多」,照 master)。'], [3, '同深色。']])],
    ['p3-about-b', 'B · 线条图标放大', '行图标', 'cyp theme-dark pg-profile', aboutB(false), 'subpackageMember/components/cy/profile/index.wxml「关于」', notes([[1, 'B 案:还是项目原来的线条图标(cy-icon),从 12pt 放大到 24pt,去掉圆底,和文字同一中线;每行一个对应图标(原来权益行只有勾 / 锁)。'], [2, '没解锁:图标和文字变灰,行尾一把锁。'], [3, '列表结构照 master,不加卡片。']])],
    ['p3-about-b-lt', 'B · 浅色(商家)', '行图标', 'cyp theme-merchant pg-profile', aboutB(true), 'subpackageMember/components/cy/profile/index.wxml「关于」(商家)', notes([[2, '商家浅色同一套。'], [3, '同深色。']])]
  ];
  var host = document.getElementById('screens');
  host.innerHTML = SCREENS.map(function (s) {
    return '<section class="scr ' + s[3] + '" data-id="' + s[0] + '" data-title="' + s[1] + '" data-group="' + s[2] + '" data-src="' + s[5] + '"' + (s[0] === 'p3-object-filter' ? ' data-open="cat-sheet"' : '') + '>' + s[4] + (s[0] === 'p3-medals' ? medalNotes() : s[6]) + '</section>';
  }).join('');
  TM.paintAll(host);

  window.ACT = {
    view: function (el) {
      var k = el.getAttribute('data-kind');
      if (k === 'stamp') openViewer(PROTO.cur(), 'stamp', { i: +el.getAttribute('data-i') });
      else openViewer(PROTO.cur(), 'medal', { key: el.getAttribute('data-key'), name: el.getAttribute('data-name'), meta: el.getAttribute('data-meta'), locked: el.hasAttribute('data-locked'), theme: el.hasAttribute('data-theme') });
    },
    closeView: function (el) { var v = el.closest('.vw'); v.hidden = true; v.classList.remove('open'); },
    gather: function (el) { if (active) el.classList.toggle('is-on', active.gather()); },
    pickCat: function (el) {
      var c = el.getAttribute('data-cat'), s = PROTO.cur();
      PROTO.closeSheets(el);
      [].forEach.call(s.querySelectorAll('.sh-row'), function (r) { var ck = r.querySelector('.sh-ck'); if (ck) ck.remove(); if (r.getAttribute('data-cat') === c) r.insertAdjacentHTML('beforeend', I('check', 36, 'sh-ck')); });
      var f = s.querySelector('.sb-filter'); f.querySelector('text').textContent = c; f.classList.toggle('is-on', c !== '全部');
      var cv = s.querySelector('.sb-pile__cv'); cv.setAttribute('data-filter', c); startPile(s, 'drop');
    }
  };
  // 切到「藏品」段时开始掉落
  document.addEventListener('click', function (e) {
    var t = e.target.closest('[data-tab="seg:object"]'); if (t) setTimeout(function () { startPile(PROTO.cur(), 'drop'); }, 30);
  });
  var OPEN = {
    'p3-view-medal': function (s) { ACT.view(s.querySelector('.sb-cell[data-key="t-flavor"]')); },
    'p3-view-medal-back': function (s) { ACT.view(s.querySelector('.sb-cell[data-key="t-flavor"]')); var c = s.querySelector('.coin'); c.style.transition = 'none'; c.style.transform = 'rotateY(160deg)'; },
    'p3-view-stamp': function (s) { openViewer(s, 'stamp', { i: 1 }); },
    'p3-view-object': function (s) { openViewer(s, 'object', PILE_ITEMS[0]); }
  };
  window.ON_ENTER = function (id) {
    var s = PROTO.cur();
    [].forEach.call(s.querySelectorAll('.vw'), function (v) { v.hidden = true; v.classList.remove('open'); });
    if (active) { active.stop(); active = null; }
    setTimeout(function () { startPile(s); if (OPEN[id]) OPEN[id](s); }, 0);
  };
  // 截图钩子(tools/shoot.js):正在落下那两屏定格在第 64 帧,其余堆好再截
  window.SHOT = function (id) {
    return new Promise(function (res) {
      var s = PROTO.cur(), cv = s.querySelector('.sb-pile__cv');
      if (!cv || cv.closest('[hidden]')) return res();
      var t = setInterval(function () {
        if (!active || active.cv !== cv) return;
        clearInterval(t); active.stop();
        var drop = cv.getAttribute('data-pile') === 'drop';
        active.build(drop, cv.getAttribute('data-filter'));
        if (drop) for (var k = 0; k < 64; k++) PILE.step(active.bodies, active.world, 1);
        active.draw(); res();
      }, 30);
    });
  };
})();
