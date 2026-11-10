// 玩家端弹窗预览:主题开始 / 章节开始 / 章节结束 / 玩法开始 / 玩法结束 / 分支选择,按当前画面风格(D.LOOK)着色。
// 编辑器里的「预览」和画面风格面板顶部的常驻小预览都走这里。
(function () {
  var esc = UI.esc, ic = D.ic;
  var STAGES = [['theme', '主题开始'], ['chStart', '章节开始'], ['chEnd', '章节结束'], ['gStart', '玩法开始'], ['gEnd', '玩法结束'], ['branch', '分支选择']];
  var FONT = { HAND: '"Kaiti SC","STKaiti","KaiTi",cursive', SONG: '"Songti SC","STSong","Noto Serif SC",serif', KAI: '"Kaiti SC","STKaiti","KaiTi",serif', HEI: '-apple-system,"PingFang SC",sans-serif', BOLD: '-apple-system,"PingFang SC",sans-serif' };

  // 把正文按动效拆字(逐字类)或整段
  function fxText(text, fx) {
    if (!D.fxChars(fx)) return '<span class="pv-fx pv-fx--' + fx + '">' + esc(text) + '</span>';
    var out = '', chars = Array.from(text);
    for (var i = 0; i < chars.length; i++) out += '<span class="pv-ch" style="--i:' + Math.min(i, 40) + '">' + esc(chars[i]) + '</span>';
    return '<span class="pv-fx pv-fx--' + fx + ' pv-fx--chars">' + out + '</span>';
  }
  function chapter() { return (window.ED && ED.chapter()) || D.CHAPTERS[0]; }
  function gameBlock() { var ch = chapter(); for (var i = 0; i < ch.blocks.length; i++) if (ch.blocks[i].type === 'node' && ch.blocks[i].tpl) { var t = D.tplOf(ch.blocks[i].tpl), g = D.gameOf(t.game); return Object.assign({}, g, { label: t.title, sub: g.label + ' · ' + g.sub }); } return D.GAMES[0]; }
  function branchBlock() { var ch = chapter(); for (var i = 0; i < ch.blocks.length; i++) if (ch.blocks[i].type === 'branch') return ch.blocks[i]; return { q: '先去哪一处?', opts: [{ t: '直接去邮局' }, { t: '先回旧书店' }] }; }

  // 弹窗卡片内容(同一套结构,风格靠 data-skin 换皮)
  function card(stage, o) {
    var fx = D.textFx(), ch = chapter(), c = D.colors(), s = D.styleOf(D.LOOK.style);
    var idx = D.CHAPTERS.indexOf(ch) + 1; if (idx < 1) idx = 1;
    var seal = s.value === 'PAPER' ? '<span class="pv-seal">' + ({ VERIFIED: '已核实', VISITED: '到此一游', SEAL: '印' }[D.LOOK.params.stamp] || '已核实') + '</span>' : '';
    var H = function (t) { return '<div class="pv-title">' + esc(t) + '</div>'; };
    var K = function (t) { return '<div class="pv-kick">' + esc(t) + '</div>'; };
    var B = function (t, cls) { return '<div class="pv-go ' + (cls || '') + '">' + esc(t) + '</div>'; };
    var body;
    if (stage === 'theme') body = K('城瘾 · 城市剧情') + H(D.THEME.title) + '<div class="pv-body">' + fxText(D.THEME.sub, fx) + '</div><div class="pv-meta">' + D.THEME.chapters + ' 章 · 约 ' + D.THEME.minutes + ' 分钟 · ' + s.label + '</div>' + B('开始');
    else if (stage === 'chStart') body = K('第 ' + idx + ' 章') + H(ch.name || '未命名章节') + '<div class="pv-body">' + fxText(ch.open || '(章节开场白为空)', fx) + '</div>' + B('进入本章');
    else if (stage === 'chEnd') body = K('本章完成') + H(ch.name || '未命名章节') + '<div class="pv-body">' + fxText(ch.end || '(章节结语为空)', fx) + '</div>' +
      '<div class="pv-reward"><span class="pv-glyph">' + ic('medal', 36, 1.3) + '<i></i></span><span class="pv-reward__t">' + esc(ch.reward || '线索 · 纸条上的地址') + '</span></div>' +
      '<div class="pv-stats"><span><b>12</b>分钟</span><span><b>860</b>步</span><span><b>2</b>线索</span></div>' + seal + B(ch.next || '下一章');
    else if (stage === 'gStart') { var g = gameBlock(); body = K('玩法 · ' + g.mode) + '<div class="pv-gtile" style="--gc:' + g.color + '">' + ic(g.icon, 30, 1.9) + '</div>' + H(g.label) + '<div class="pv-body">' + fxText(g.sub, fx) + '</div>' + B('开始'); }
    else if (stage === 'gEnd') { var ok = !(o && o.fail); var g2 = gameBlock(); body = K(ok ? '成功' : '失败') + '<div class="pv-mark ' + (ok ? 'is-ok' : 'is-bad') + '">' + ic(ok ? 'check' : 'close', 34, 2) + '<i></i></div>' + H(ok ? '答对了' : '再想想') + '<div class="pv-body">' + fxText(ok ? '吧台后面的人把钥匙推了过来。获得线索:半枚邮戳。' : '掌柜摇摇头:「再看看门口那块牌匾。」还剩 1 次机会。', fx) + '</div>' + B(ok ? '继续' : '再试一次'); }
    else { var br = branchBlock(); body = K('分支任务') + '<div class="pv-q">' + esc(br.q) + '</div><div class="pv-opts">' + br.opts.map(function (op, i) { return '<div class="pv-opt' + (i === 0 ? ' is-sel' : '') + '"><span class="k">' + 'ABC'[i] + '</span><span>' + esc(op.t) + '</span></div>'; }).join('') + '</div>'; }
    return '<div class="pv-card pv-card--' + stage + '" data-skin="' + c.skin + '"><i class="pv-deco pv-deco--a"></i><i class="pv-deco pv-deco--b"></i><div class="pv-in">' + body + '</div></div>';
  }
  function vars() {
    var c = D.colors(), L = D.LOOK;
    return '--bg:' + c.bg + ';--ink:' + c.ink + ';--mute:' + c.mute + ';--acc:' + c.acc + ';--acc2:' + (c.acc2 || c.acc) + ';--card:' + (c.card || '') + ';--cink:' + (c.cink || c.ink) + ';--fs:' + FONT[L.font] + ';' + (L.font === 'BOLD' ? '--fw:900;--fi:italic;' : '');
  }
  // 整屏预览层(编辑器「预览」):底是该风格的场景,中间是弹窗,底部一条切换阶段
  function overlay(stage, o) {
    var c = D.colors(), s = D.styleOf(D.LOOK.style), ch = chapter();
    return '<div class="pvl" data-skin="' + c.skin + '" data-dark="' + (c.dark ? 1 : 0) + '" style="' + vars() + '">' +
      '<div class="pvl__scene" style="background-image:url(' + ch.cover + ')"></div><div class="pvl__tex"></div><div class="pvl__tex2"></div><div class="pvl__dim"></div>' +
      '<div class="pvl__top"><span class="pvl__x" data-act="pvClose" aria-label="关闭预览">' + ic('close', 20, 2) + '</span><span class="pvl__ttl">' + esc(s.label) + ' · ' + esc(stageLabel(stage)) + '</span></div>' +
      '<div class="pvl__mid">' + card(stage, o) + '</div>' +
      (stage === 'gEnd' ? '<div class="pvl__seg"><span class="' + (!(o && o.fail) ? 'on' : '') + '" data-act="pvStage" data-stage="gEnd">成功</span><span class="' + ((o && o.fail) ? 'on' : '') + '" data-act="pvStage" data-stage="gEnd" data-fail="1">失败</span></div>' : '') +
      '<div class="pvl__strip">' + STAGES.map(function (x) { return '<span class="' + (x[0] === stage ? 'on' : '') + '" data-act="pvStage" data-stage="' + x[0] + '">' + x[1] + '</span>'; }).join('') + '</div>' +
      '<div class="pvl__looks">' + D.STYLES.map(function (st) { return '<span class="' + (st.value === D.LOOK.style ? 'on' : '') + '" data-act="pvLook" data-style="' + st.value + '"><i class="look-sw ' + st.sw + '"></i>' + st.label + '</span>'; }).join('') +
      '</div><div class="pvl__pals"><span class="pvl__pals__k">主题配色</span>' + D.PALETTES.map(function (p) { var pc = D.PAL[c.skin][p[0]]; return '<i class="' + (p[0] === D.LOOK.palette ? 'on' : '') + '" data-act="pvPal" data-pal="' + p[0] + '" style="background:' + pc.bg + '" title="' + p[1] + '"></i>'; }).join('') + '</div>' +
      '</div>';
  }
  function stageLabel(k) { for (var i = 0; i < STAGES.length; i++) if (STAGES[i][0] === k) return STAGES[i][1]; return ''; }
  // 画面风格面板顶部的常驻小预览(缩小的章节开始弹窗)
  function mini() {
    var c = D.colors(), ch = chapter();
    return '<div class="pvm" data-skin="' + c.skin + '" style="' + vars() + '"><div class="pvl__scene" style="background-image:url(' + ch.cover + ')"></div><div class="pvl__tex"></div><div class="pvl__tex2"></div><div class="pvl__dim"></div><div class="pvm__fit">' + card('chStart') + '</div></div>';
  }

  var cur = { stage: 'theme', o: null, host: null };
  function open(stage, o) {
    var scr = PROTO.cur(); cur.stage = stage || 'theme'; cur.o = o || null; cur.host = scr;
    var old = scr.querySelector('.pvl'); if (old) old.remove();
    scr.insertAdjacentHTML('beforeend', overlay(cur.stage, cur.o));
    requestAnimationFrame(function () { var l = scr.querySelector('.pvl'); if (l) { l.classList.add('is-on'); setTimeout(function () { l.classList.add('is-play'); }, 60); } });
  }
  function rerender() { if (!cur.host) return; var l = cur.host.querySelector('.pvl'); if (!l) return; l.outerHTML = overlay(cur.stage, cur.o); var n = cur.host.querySelector('.pvl'); n.classList.add('is-on'); requestAnimationFrame(function () { n.classList.add('is-play'); }); }
  window.ACT = Object.assign(window.ACT || {}, {
    pvClose: function () { var l = PROTO.cur().querySelector('.pvl'); if (l) l.remove(); cur.host = null; },
    pvStage: function (el) { cur.stage = el.getAttribute('data-stage'); cur.o = el.getAttribute('data-fail') ? { fail: 1 } : null; rerender(); },
    pvLook: function (el) { D.setStyle(el.getAttribute('data-style')); rerender(); if (window.LOOKSHEET) LOOKSHEET.refresh(); },
    pvPal: function (el) { D.LOOK.palette = el.getAttribute('data-pal'); rerender(); if (window.LOOKSHEET) LOOKSHEET.refresh(); }
  });
  window.PV = { open: open, mini: mini, card: card, vars: vars, STAGES: STAGES, rerender: rerender };
})();
