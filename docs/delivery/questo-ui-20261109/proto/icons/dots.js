// 风格 C · 波点 / 点阵:把每枚图标的实心剪影采样到一张点阵网格上,用圆点拼出来;
// 进场时圆点从四周飞入落位(翻版章节数字的像素块,只是把方块换成圆点),常驻时一道亮波从左上扫到右下。
// 颜色只读外观变量 --lk-a / --lk-ink,所以四种外观 × 五种配色都自动跟着变。
(function () {
  // 剪影:120×120 画布上的实心路径(evenodd),只要轮廓,不要细节 —— 点阵自己会把它说清楚
  var SIL = {
    clue: ['M50 20a30 30 0 1 1 0 60 30 30 0 0 1 0-60zm0 12a18 18 0 1 0 0 36 18 18 0 0 0 0-36z', 'M70 64l10-10 26 26-10 10z', 'M54 54m-7 0a7 7 0 1 0 14 0 7 7 0 1 0-14 0'],
    badge: ['M60 18a30 30 0 1 1 0 60 30 30 0 0 1 0-60z', 'M44 70l14 4-6 38-10-10-12 4z', 'M76 70l-14 4 6 38 10-10 12 4z'],
    key: ['M38 14a26 26 0 1 1 0 52 26 26 0 0 1 0-52zm0 16a10 10 0 1 0 0 20 10 10 0 0 0 0-20z', 'M50 56l10-10 48 48-10 10z', 'M78 68l10-10 12 12-10 10zM90 80l10-10 10 10-10 10z'],
    success: ['M60 12a48 48 0 1 1 0 96 48 48 0 0 1 0-96zm0 14a34 34 0 1 0 0 68 34 34 0 0 0 0-68z', 'M34 60l10-10 12 12 22-24 10 10-32 34z'],
    fail: ['M60 12a48 48 0 1 1 0 96 48 48 0 0 1 0-96zm0 14a34 34 0 1 0 0 68 34 34 0 0 0 0-68z', 'M42 50l8-8 10 10 10-10 8 8-10 10 10 10-8 8-10-10-10 10-8-8 10-10z'],
    wait: ['M30 14h60v12H30z', 'M30 94h60v12H30z', 'M38 26h44c0 16-10 26-18 34 8 8 18 18 18 34H38c0-16 10-26 18-34-8-8-18-18-18-34zm10 6c2 10 8 16 12 22 4-6 10-12 12-22z', 'M60 70l12 20H48z'],
    team: ['M38 36a12 12 0 1 1 0 24 12 12 0 0 1 0-24z', 'M82 36a12 12 0 1 1 0 24 12 12 0 0 1 0-24z', 'M14 96c0-14 10-22 24-22s24 8 24 22z', 'M58 96c0-14 10-22 24-22s24 8 24 22z', 'M60 30a16 16 0 1 1 0 32 16 16 0 0 1 0-32z', 'M30 104c0-18 13-30 30-30s30 12 30 30z'],
    pass: ['M26 22h68v30a8 8 0 0 0 0 16v30H26V68a8 8 0 0 0 0-16zm10 10v12h48V32zm0 26v6h30v-6zm0 14v6h20v-6z', 'M52 10h16v8H52z'],
    points: ['M24 80c0-8 16-14 36-14s36 6 36 14v10c0 8-16 14-36 14S24 98 24 90z', 'M24 60c0-8 16-14 36-14s36 6 36 14v8H24z', 'M24 40c0-8 16-14 36-14s36 6 36 14v8H24zm36-6c-12 0-20 3-20 6s8 6 20 6 20-3 20-6-8-6-20-6z'],
    seal: ['M46 12h28v30H46z', 'M28 42h64v18H28z', 'M30 78h60v30H30zm10 10v10h40V88z'],
  };
  var NAMES = { clue: '线索', badge: '勋章', key: '钥匙', success: '成功', fail: '失败', wait: '调查中', team: '队伍', pass: '通行证', points: '积分', seal: '章节印' };
  var LIST = ['clue', 'badge', 'key', 'success', 'fail', 'wait', 'team', 'pass', 'points', 'seal'];
  var cache = {};
  // 采样:返回 [{x,y,row,col}],cell 为网格间距(120 盒)
  function sample(name, cell) {
    var k = name + '@' + cell; if (cache[k]) return cache[k];
    var o = document.createElement('canvas'); o.width = o.height = 120; var g = o.getContext('2d'); g.fillStyle = '#fff';
    SIL[name].forEach(function (d) { g.fill(new Path2D(d), 'evenodd'); });
    var d = g.getImageData(0, 0, 120, 120).data, pts = [], n = Math.floor(120 / cell), off = (120 - n * cell) / 2 + cell / 2;
    for (var r = 0; r < n; r++) for (var c = 0; c < n; c++) { var x = off + c * cell, y = off + r * cell; if (d[((y | 0) * 120 + (x | 0)) * 4 + 3] > 120) pts.push({ x: x, y: y, row: r, col: c, n: n }); }
    return (cache[k] = pts);
  }
  // SVG 点阵图标(任意尺寸都清晰;列表小尺寸也用它)
  function img(name, o) {
    o = o || {}; var sz = o.size || 64, cell = o.cell || 8, pts = sample(name, cell), r = cell * .43;
    var cs = pts.map(function (p, i) {
      var ang = Math.random() * 6.28, dist = 40 + Math.random() * 70, hi = (p.col + p.row) < p.n * .75 && ((p.col * 7 + p.row * 13) % 5 === 0);
      return '<circle class="d' + (hi ? ' hi' : '') + '" cx="' + p.x.toFixed(1) + '" cy="' + p.y.toFixed(1) + '" r="' + r.toFixed(2) + '" style="--dx:' + (Math.cos(ang) * dist).toFixed(0) + 'px;--dy:' + (Math.sin(ang) * dist).toFixed(0) + 'px;--dd:' + ((p.col + p.row) * 28 + Math.random() * 120).toFixed(0) + 'ms;--w:' + ((p.col + p.row) / (p.n * 2)).toFixed(2) + '"/>';
    }).join('');
    return '<span class="idot idot--' + name + (o.cls ? ' ' + o.cls : '') + '" style="width:' + sz + 'px;height:' + sz + 'px;--ic-d:' + (o.d || 0) + 'ms' + (o.color ? ';--dot-c:' + o.color : '') + '"' + (o.mk ? ' data-mk="' + o.mk + '"' : '') + '><svg viewBox="0 0 120 120">' + cs + '</svg></span>';
  }

  // ---- 章节开场:整屏画布,数字 + 章节印都用圆点拼 ----
  var RUN = {};
  function textPts(txt, cell, W, Hh, font) {
    var o = document.createElement('canvas'); o.width = W; o.height = Hh; var g = o.getContext('2d'); g.fillStyle = '#fff'; g.font = font; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(txt, W / 2, Hh / 2 + Hh * .04);
    var d = g.getImageData(0, 0, W, Hh).data, pts = [];
    for (var y = cell / 2; y < Hh; y += cell) for (var x = cell / 2; x < W; x += cell) if (d[((y | 0) * W + (x | 0)) * 4 + 3] > 140) pts.push({ x: x, y: y, row: y / Hh });
    return pts;
  }
  function chapter(cv, o) {
    if (!cv) return; stop(cv); o = o || {};
    var cx = cv.getContext('2d'), PW = 390, PH = 844; cx.setTransform(2, 0, 0, 2, 0, 0);
    var scr = cv.closest('.scr'), cs = getComputedStyle(scr), ink = cs.color, acc = cs.getPropertyValue('--lk-a').trim() || ink, bg = cs.getPropertyValue('--lk-bg').trim();
    var st = { cv: cv, dots: [], raf: 0, t0: performance.now(), ink: ink, acc: acc, reduce: G.reduce };
    RUN[cv.id] = st;
    // 数字:大点阵;章节印:下方一枚圆章(外环点阵 + 中间一个「章」字点阵),缓慢自转
    var num = textPts(o.num || '02', 11, 330, 260, '800 236px -apple-system,"Helvetica Neue",Arial,sans-serif'), TOPN = 150;
    num.forEach(function (p, i) { st.dots.push(mk((PW - 330) / 2 + p.x, TOPN + p.y, 4.6, 'num', p.row, i)); });
    var SC = { x: PW / 2, y: 600 }, ring = [];
    for (var i = 0; i < 44; i++) { var a = i / 44 * 6.283; ring.push(mk(SC.x + Math.cos(a) * 62, SC.y + Math.sin(a) * 62, 3, 'ring', 0, i)); ring[ring.length - 1].a = a; }
    for (var j = 0; j < 28; j++) { var a2 = j / 28 * 6.283 + .11; ring.push(mk(SC.x + Math.cos(a2) * 46, SC.y + Math.sin(a2) * 46, 1.8, 'ring2', 0, j)); ring[ring.length - 1].a = a2; }
    var ch = textPts(o.seal || '章', 7, 100, 100, '700 64px "Songti SC","STSong","Noto Serif SC",serif');
    ch.forEach(function (p, i) { st.dots.push(mk(SC.x - 50 + p.x, SC.y - 50 + p.y, 2.8, 'glyph', p.row, i)); });
    st.ring = ring; st.dots = st.dots.concat(ring); st.SC = SC;
    function mk(tx, ty, r, kind, row, i) {
      var ang = Math.random() * 6.283, d = 160 + Math.random() * 420;
      return { tx: tx, ty: ty, x: tx + Math.cos(ang) * d, y: ty + Math.sin(ang) * d, vx: 0, vy: 0, r: 0, tr: r, kind: kind, row: row, delay: kind === 'num' ? Math.abs(tx - PW / 2) * 1.6 + Math.random() * 300 : 900 + i * 14 + Math.random() * 200, shade: 1 - row * .25 + (Math.random() - .5) * .1 };
    }
    function tick(now) {
      if (!cv.isConnected || RUN[cv.id] !== st) return;
      var t = now - st.t0; cx.clearRect(0, 0, PW, PH);
      var spin = t / 9000 * 6.283, band = ((t - 1800) % 2600) / 2600 * 900 - 150;
      st.dots.forEach(function (d) {
        if (d.kind === 'ring' || d.kind === 'ring2') { var R = d.kind === 'ring' ? 62 : 46, dir = d.kind === 'ring' ? 1 : -1; d.tx = SC.x + Math.cos(d.a + spin * dir) * R; d.ty = SC.y + Math.sin(d.a + spin * dir) * R; }
        if (t < d.delay) return;
        if (st.reduce) { d.x = d.tx; d.y = d.ty; d.r = d.tr; } else { d.vx = (d.vx + (d.tx - d.x) * .05) * .80; d.vy = (d.vy + (d.ty - d.y) * .05) * .80; d.x += d.vx; d.y += d.vy; d.r += (d.tr - d.r) * .14; }
        var lit = d.kind === 'num' && Math.abs((d.x - 30) + (d.y - 150) * .7 - band) < 22;
        cx.globalAlpha = lit ? 1 : Math.min(1, d.shade); cx.fillStyle = lit ? ink : (d.kind === 'num' || d.kind === 'ring' ? acc : ink);
        cx.beginPath(); cx.arc(d.x, d.y, Math.max(0, d.r), 0, 6.283); cx.fill();
        if (d.kind === 'num' && d.r > 3) { cx.globalAlpha = .28; cx.fillStyle = '#fff'; cx.beginPath(); cx.arc(d.x - d.r * .3, d.y - d.r * .3, d.r * .3, 0, 6.283); cx.fill(); }
      });
      cx.globalAlpha = 1; st.raf = requestAnimationFrame(tick);
    }
    st.raf = requestAnimationFrame(tick);
    return st;
  }
  function stop(cv) { var st = RUN[cv.id]; if (st) { cancelAnimationFrame(st.raf); delete RUN[cv.id]; } }
  window.DOTS = { img: img, names: NAMES, list: LIST, chapter: chapter, stop: stop, sample: sample };
})();
