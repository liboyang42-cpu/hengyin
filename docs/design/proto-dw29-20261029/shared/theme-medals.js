// 主题勋章:每个主题一枚,样式从主题数据推出来,画法复用 master 的珐琅渲染器(enamel.bundle.js = PR #653 原文件)。
// 推导规则(落代码时放进 badge-wall 的 PLATE / optsFor 旁边,数据取主题本身,不新增后台配置):
//   底板形状 ← 主题「类型」标签   剧情叙事=花边 scallop · 生存逃脱=竖六边 hexup · 解谜推理=八角 octagon · 城市探索=圆 circle · 生活实用=圆角方 roundsq · 治愈放松=花 flower
//   金属成色 ← 主题「风格」标签   科幻异常=银 · 怀旧人文=古铜 · 治愈放松=玫瑰金 · 其余=金;生存逃脱再暗一档=黑镍
//   釉色     ← 主题封面取色(art.js THEMES 里的 glow 色相):底 / 主 / 亮三档 + 一个对比色
//   徽记     ← 主题关键词对应的图形(船、碗、坐标、信号塔、钟、胶片、太阳、宝箱 …),没有对应词时落主题名首字
(function () {
  function hsl(h, s, l) { return 'hsl(' + ((h % 360) + 360) % 360 + ',' + s + '%,' + l + '%)'; }
  function pal(h) { return { base: hsl(h, 48, 18), deep: hsl(h, 55, 30), mid: hsl(h, 62, 48), light: hsl(h + 12, 70, 74), cream: '#F4F1EA', acc: hsl(h + 180, 70, 58), acc2: hsl(h + 150, 60, 40) }; }
  function F(x, c, fn) { x.fillStyle = c; x.beginPath(); fn(); x.fill(); }
  function ST(x, c, w, fn) { x.strokeStyle = c; x.lineWidth = w; x.lineCap = 'round'; x.beginPath(); fn(); x.stroke(); }
  function DOT(x, c, a, b, r) { F(x, c, function () { x.arc(a, b, r, 0, Math.PI * 2); }); }

  var MOTIF = {
    // 海上方舟:船身 + 桅杆 + 三角帆 + 浪
    ark: function (x, p) {
      F(x, p.deep, function () { x.rect(-90, 20, 180, 80); });
      ST(x, p.light, 9, function () { for (var i = -80; i <= 80; i += 40) { x.moveTo(i - 20, 44); x.quadraticCurveTo(i, 32, i + 20, 44); } });
      F(x, p.mid, function () { x.moveTo(-58, 6); x.lineTo(58, 6); x.lineTo(40, 30); x.lineTo(-40, 30); x.closePath(); });
      F(x, p.cream, function () { x.moveTo(4, -60); x.lineTo(4, 0); x.lineTo(46, 0); x.closePath(); });
      F(x, p.acc, function () { x.moveTo(-4, -46); x.lineTo(-4, 0); x.lineTo(-38, 0); x.closePath(); });
      ST(x, p.light, 5, function () { x.moveTo(0, -66); x.lineTo(0, 6); });
      DOT(x, p.acc, 0, -70, 6);
    },
    // 风味巡游:碗 + 两根筷子 + 热气
    bowl: function (x, p) {
      F(x, p.mid, function () { x.arc(0, 4, 58, 0, Math.PI); x.closePath(); });
      F(x, p.cream, function () { x.ellipse(0, 4, 58, 12, 0, 0, Math.PI * 2); });
      F(x, p.acc, function () { x.rect(-20, 58, 40, 10); });
      ST(x, p.light, 7, function () { x.moveTo(-14, -12); x.quadraticCurveTo(-24, -32, -12, -50); x.moveTo(14, -12); x.quadraticCurveTo(4, -34, 16, -56); });
      ST(x, p.acc2, 6, function () { x.moveTo(30, -60); x.lineTo(70, -8); x.moveTo(46, -66); x.lineTo(80, -18); });
    },
    // 异常事务局:十字准星 + 错位网格 + 坐标点
    coord: function (x, p) {
      ST(x, p.deep, 5, function () { for (var i = -60; i <= 60; i += 30) { x.moveTo(i, -80); x.lineTo(i + (i === 0 ? 10 : 0), 80); x.moveTo(-80, i); x.lineTo(80, i); } });
      F(x, p.mid, function () { x.arc(0, 0, 44, 0, Math.PI * 2); });
      F(x, p.base, function () { x.arc(0, 0, 30, 0, Math.PI * 2); });
      ST(x, p.light, 7, function () { x.moveTo(0, -62); x.lineTo(0, -20); x.moveTo(0, 20); x.lineTo(0, 62); x.moveTo(-62, 0); x.lineTo(-20, 0); x.moveTo(20, 0); x.lineTo(62, 0); });
      DOT(x, p.acc, 12, -8, 9);
    },
    // 末日广播:信号塔 + 三道电波
    tower: function (x, p) {
      F(x, p.mid, function () { x.moveTo(0, -40); x.lineTo(26, 72); x.lineTo(14, 72); x.lineTo(0, 10); x.lineTo(-14, 72); x.lineTo(-26, 72); x.closePath(); });
      ST(x, p.mid, 5, function () { x.moveTo(-18, 40); x.lineTo(18, 40); x.moveTo(-10, 12); x.lineTo(10, 12); });
      DOT(x, p.acc, 0, -44, 11);
      for (var i = 1; i <= 3; i++) ST(x, i === 2 ? p.light : p.cream, 6, function () { x.arc(0, -44, 14 + i * 16, -Math.PI * 0.82, -Math.PI * 0.18); });
    },
    // 时间污染:表盘缺一块 + 指针
    clock: function (x, p) {
      F(x, p.cream, function () { x.arc(0, 0, 60, 0, Math.PI * 2); });
      F(x, p.mid, function () { x.moveTo(0, 0); x.arc(0, 0, 60, -Math.PI * 0.1, Math.PI * 0.32); x.closePath(); });
      for (var i = 0; i < 12; i++) { var a = i * Math.PI / 6; DOT(x, p.deep, Math.cos(a) * 48, Math.sin(a) * 48, i % 3 ? 3 : 5); }
      ST(x, p.base, 7, function () { x.moveTo(0, 0); x.lineTo(0, -38); x.moveTo(0, 0); x.lineTo(26, 10); });
      DOT(x, p.acc, 0, 0, 7);
    },
    // 上海像一部旧电影:胶片盘
    reel: function (x, p) {
      F(x, p.light, function () { x.arc(0, 0, 62, 0, Math.PI * 2); });
      F(x, p.deep, function () { x.arc(0, 0, 16, 0, Math.PI * 2); });
      for (var i = 0; i < 5; i++) { var a = -Math.PI / 2 + i * Math.PI * 2 / 5; F(x, p.base, function () { x.arc(Math.cos(a) * 36, Math.sin(a) * 36, 14, 0, Math.PI * 2); }); }
      F(x, p.mid, function () { x.rect(40, 50, 50, 16); });
      DOT(x, p.cream, 0, 0, 6);
    },
    // 高温末日逃生:太阳 + 热浪
    sun: function (x, p) {
      for (var i = 0; i < 12; i++) { var a = i * Math.PI / 6; F(x, p.acc, function () { x.moveTo(Math.cos(a - .12) * 40, Math.sin(a - .12) * 40); x.lineTo(Math.cos(a) * 72, Math.sin(a) * 72); x.lineTo(Math.cos(a + .12) * 40, Math.sin(a + .12) * 40); x.closePath(); }); }
      F(x, p.light, function () { x.arc(0, 0, 38, 0, Math.PI * 2); });
      ST(x, p.mid, 6, function () { for (var k = -1; k <= 1; k++) { x.moveTo(-24, k * 14); x.quadraticCurveTo(-8, k * 14 - 8, 0, k * 14); x.quadraticCurveTo(8, k * 14 + 8, 24, k * 14); } });
    },
    // 城市藏宝猎人:宝箱 + 钥匙孔
    chest: function (x, p) {
      F(x, p.mid, function () { x.rect(-62, -6, 124, 62); });
      F(x, p.deep, function () { x.moveTo(-62, -6); x.quadraticCurveTo(0, -64, 62, -6); x.closePath(); });
      F(x, p.light, function () { x.rect(-62, -10, 124, 12); x.rect(-10, -34, 20, 90); });
      F(x, p.acc, function () { x.arc(0, 22, 14, 0, Math.PI * 2); });
      F(x, p.base, function () { x.arc(0, 18, 5, 0, Math.PI * 2); x.rect(-3, 18, 6, 14); });
    },
    // 首字兜底
    letter: function (x, p, t) {
      F(x, p.mid, function () { x.arc(0, 0, 60, 0, Math.PI * 2); });
      x.fillStyle = p.cream; x.font = '700 72px "PingFang SC","Noto Sans CJK SC",sans-serif'; x.textAlign = 'center'; x.textBaseline = 'middle'; x.fillText(t || '城', 0, 4);
    }
  };

  // 主题数据:名称 / 封面 / 色相(art.js THEMES 的 glow)/ 类型 / 风格 / 关键词 → 徽记
  var THEMES = [
    { key: 't-ark', name: '海上方舟', cover: 'old-movie.jpg', hue: 205, type: 'story', style: 'nostalgia', motif: 'ark', medal: '方舟见证人' },
    { key: 't-flavor', name: '风味巡游', cover: 'flavor-tour.jpg', hue: 355, type: 'explore', style: '', motif: 'bowl', medal: '老饕' },
    { key: 't-anomaly', name: '异常事务局：不存在的坐标', cover: 'anomaly-coordinate.jpg', hue: 216, type: 'puzzle', style: 'scifi', motif: 'coord', medal: '坐标校准员' },
    { key: 't-relay', name: '末日广播：最后一座中继站', cover: 'apocalypse-broadcast.jpg', hue: 264, type: 'survival', style: 'scifi', motif: 'tower', medal: '最后的信号' },
    { key: 't-time', name: '时间污染：被删除的今天', cover: 'time-pollution.jpg', hue: 36, type: 'puzzle', style: 'scifi', motif: 'clock', medal: '找回今天' },
    { key: 't-movie', name: '上海像一部旧电影', cover: 'old-movie.jpg', hue: 211, type: 'story', style: 'nostalgia', motif: 'reel', medal: '散场之后' },
    { key: 't-heat', name: '高温末日逃生', cover: 'heat-escape.jpg', hue: 18, type: 'survival', style: '', motif: 'sun', medal: '降温者' },
    { key: 't-treasure', name: '城市藏宝猎人', cover: 'treasure-hunter.jpg', hue: 19, type: 'puzzle', style: 'nostalgia', motif: 'chest', medal: '藏宝猎人' },
    { key: 't-midlife', name: '不惑之年', cover: 'midlife-day.jpg', hue: 97, type: 'calm', style: 'calm', motif: 'letter', letter: '惑', medal: '四十不惑' }
  ];
  var SHAPE = { story: 'scallop', survival: 'hexup', puzzle: 'octagon', explore: 'circle', life: 'roundsq', calm: 'flower' };
  var TYPE_ZH = { story: '剧情叙事', survival: '生存逃脱', puzzle: '解谜推理', explore: '城市探索', life: '生活实用', calm: '治愈放松' };
  var STYLE_ZH = { scifi: '科幻异常', nostalgia: '怀旧人文', calm: '治愈放松', '': '—' };
  function finishOf(t) { if (t.type === 'survival' && t.style !== 'scifi') return 'black'; return { scifi: 'silver', nostalgia: 'bronze', calm: 'rose' }[t.style] || 'gold'; }

  function install() {
    var E = window.ENAMEL, G = window.GLYPHS; if (!E || G._themed) return;
    var base = G.draw;
    var byKey = {};
    THEMES.forEach(function (t) { var p = pal(t.hue); byKey[t.key] = t; t.pal = p; E.PLATE[t.key] = { shape: SHAPE[t.type] || 'hex', base: p.base }; });
    G.draw = function (ctx, key) { var t = byKey[key]; if (!t) return base(ctx, key); ctx.lineJoin = 'round'; MOTIF[t.motif](ctx, t.pal, t.letter); };
    G._themed = true;
  }

  // 画到 <canvas>:size 是 CSS 像素,按 2 倍画
  function paint(canvas, key, locked) {
    install();
    var t = THEMES.filter(function (x) { return x.key === key; })[0];
    var size = canvas.width;
    var ctx = canvas.getContext('2d', { willReadFrequently: true });
    window.ENAMEL.render(ctx, size, key, !!locked, t ? { finish: finishOf(t), enamel: 'hard' } : window.ENAMEL.optsFor(0));
  }
  function paintAll(root) {
    [].forEach.call((root || document).querySelectorAll('canvas[data-medal]'), function (c) {
      if (c._done) return; c._done = 1; paint(c, c.getAttribute('data-medal'), c.hasAttribute('data-locked'));
    });
  }
  window.THEME_MEDALS = { THEMES: THEMES, SHAPE: SHAPE, TYPE_ZH: TYPE_ZH, STYLE_ZH: STYLE_ZH, finishOf: finishOf, paint: paint, paintAll: paintAll };
})();
