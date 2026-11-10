// 屏定义:章节列表 → 故事编辑器(含画面风格面板 / 选择玩法 / 分支任务 sheet)→ 自己建一个(模板表单)→ 模板能力页。
(function () {
  var U = UI, esc = U.esc, ic = D.ic;
  function notes(list) { return '<ol class="notes">' + list.map(function (t) { return '<li data-n="' + t[0] + '">' + t[1] + '</li>'; }).join('') + '</ol>'; }
  function nav(title, right, light) {
    return '<div class="ed-nav' + (light === false ? ' ed-nav--dark' : '') + '"><span class="ed-nav__back" data-back>' + ic('back', 22, 2) + '</span><span class="ed-nav__t">' + title + '</span><span class="ed-nav__r">' + (right || '') + '</span></div>';
  }

  // ---------- 1. 章节列表 ----------
  function chList() {
    var s = D.styleOf(D.LOOK.style);
    return nav('主题内容', '') +
      '<div class="sv ed-sv ed-sv--plain"><div class="ed-page">' +
      '<div class="ed-row ed-row--look" data-sheet="lookSheet" data-detent="full"' + U.mk(1) + '><span class="ed-row__k">画面风格</span><span class="ed-row__v" id="chLookV"><i class="look-sw ' + s.sw + '"></i>' + s.label + ' · ' + palLabel() + '</span>' + ic('right', 16, 2) + '</div>' +
      '<div class="ed-sec">章节</div>' +
      D.CHAPTERS.map(function (ch, i) {
        var n = { p: 0, node: 0, img: 0, branch: 0 }; ch.blocks.forEach(function (b) { if (n[b.type] != null) n[b.type]++; });
        var cnt = ch.blocks.length ? ('段落 ' + n.p + ' · 玩法 ' + n.node + ' · 图片 ' + n.img + (n.branch ? ' · 分支 ' + n.branch : '')) : '还没有内容';
        return '<div class="ed-chcard" data-act="openChapter" data-i="' + i + '"' + (i === 0 ? U.mk(2) : '') + '><span class="ed-chcard__cv" style="background-image:url(' + ch.cover + ')"></span><span class="ed-chcard__m"><b>第 ' + (i + 1) + ' 章 · ' + esc(ch.name) + '</b><i>' + cnt + '</i></span>' + ic('right', 16, 2) + '</div>';
      }).join('') +
      '<div class="ed-dashed" data-toast="原型:新建第 4 章">' + ic('plus', 16, 2.2) + '添加章节</div>' +
      '<div class="ed-sec">主题弹窗</div><div class="ed-row" data-act="edPreview" data-stage="theme"' + U.mk(3) + '><span class="ed-row__k">预览主题开始弹窗</span>' + ic('right', 16, 2) + '</div>' +
      '<div style="height:120px"></div></div></div>' +
      '<div class="ed-foot"><span class="ed-btn ed-btn--ghost" data-toast="上一步">上一步</span><span class="ed-btn" data-toast="下一步:票务">下一步</span></div>';
  }
  function palLabel() { for (var i = 0; i < D.PALETTES.length; i++) if (D.PALETTES[i][0] === D.LOOK.palette) return D.PALETTES[i][1]; return '黑'; }

  // ---------- 2. 故事编辑器 ----------
  function editor() {
    return '<div class="ed-top"><div class="ed-nav"><span class="ed-nav__back" data-back>' + ic('back', 22, 2) + '</span><span class="ed-nav__state">草稿 · 已存本机</span><span class="ed-nav__r"><span class="ed-nav__txt" data-act="edPreview" data-stage="theme"' + U.mk(6) + '>预览</span><span class="ed-nav__pill" data-act="edDone">完成</span></span></div>' +
      '<div class="ed-add"' + U.mk(1) + '>' + window.ED_ADD.map(function (a) { return '<span class="ed-add__i" data-act="edAdd" data-type="' + a[0] + '">' + ic(a[1], 18, 1.9) + '<i>' + a[2] + '</i></span>'; }).join('') + '</div></div>' +
      '<div class="sv ed-sv"><div class="ed-list" id="edList"></div><div style="height:120px"></div></div>' +
      '<div class="ed-undo"></div>' +
      '<div class="ed-hint"' + U.mk(2) + '>长按任意块拖动 · 拖到段落中间会拆段 · 贴边自动滚动</div>';
  }

  // ---------- 3. 画面风格面板(与 look-sheet.wxml 同顺序) ----------
  window.LOOKSHEET = {
    html: function () {
      var L = D.LOOK, s = D.styleOf(L.style), c = D.colors();
      var chips = function (opts, on, act, extra) { return '<view class="look-chips">' + opts.map(function (o) { return '<view class="look-chip' + (o[0] === on ? ' is-on' : '') + '" data-act="' + act + '" data-value="' + o[0] + '"' + (extra || '') + '>' + o[1] + '</view>'; }).join('') + '</view>'; };
      var h = '<view class="node-sheet look-sheet"><view class="node-sheet__top"><view class="node-sheet__close" data-close>关闭</view><view class="node-sheet__done" data-close>完成</view></view>' +
        '<view class="node-sheet__titlebar"><text class="node-sheet__name node-sheet__name--static">画面风格</text></view>' +
        '<view class="look-pin"' + U.mk(1) + '>' + PV.mini() + '</view>' +
        '<view class="look-styles">' + D.STYLES.map(function (st) { return '<view class="look-style' + (st.value === L.style ? ' is-on' : '') + '" data-act="lkStyle" data-value="' + st.value + '"><view class="look-sw ' + st.sw + '"></view><text class="look-style__name">' + st.label + '</text></view>'; }).join('') + '</view>' +
        '<view class="node-card look-card">' + s.params.map(function (p) { return '<view class="look-row"><text class="look-row__k">' + p.label + '</text>' + chips(p.options, L.params[p.key], 'lkParam', ' data-key="' + p.key + '"') + '</view>'; }).join('') + '</view>';
      if (s.value === 'SURVIVAL') h += '<view class="node-card look-card">' + D.CHAPTERS.map(function (ch, i) { return '<view class="look-row"><text class="look-row__k look-row__k--chapter">第 ' + (i + 1) + ' 章</text>' + chips([['', '不设']].concat(D.TIMES), L.times[i] || '', 'lkTime', ' data-i="' + i + '"') + '</view>'; }).join('') + '</view>';
      h += '<view class="chapter-atmosphere look-palettes"' + U.mk(2) + '><view class="chapter-atmosphere__head"><text>主题配色</text></view><scroll-view class="chapter-atmosphere__list" scroll-x><view class="chapter-atmosphere__track look-pal--' + s.value.toLowerCase() + '">' +
        D.PALETTES.map(function (p) { return '<view class="chapter-atmosphere__item atmosphere-swatch--' + p[0].toLowerCase() + (p[0] === L.palette ? ' is-on' : '') + '" data-act="lkPal" data-value="' + p[0] + '"><text class="chapter-atmosphere__name">' + p[1] + '</text><text class="chapter-atmosphere__note">' + (p[0] === 'DEFAULT' ? '默认' : '') + '</text></view>'; }).join('') + '</view></scroll-view></view>';
      h += '<view class="node-card look-card"><view class="look-row"><text class="look-row__k">字体</text><view class="look-chips">' + D.FONTS.map(function (f) { return '<view class="look-chip look-font--' + f[0] + (f[0] === L.font ? ' is-on' : '') + '" data-act="lkFont" data-value="' + f[0] + '">' + f[1] + '</view>'; }).join('') + '</view></view>' +
        '<view class="look-row"><text class="look-row__k">文字动效</text>' + chips(D.TEXT_FX, L.textFx, 'lkFx') + '</view></view><view style="height:40px"></view></view>';
      return h;
    },
    refresh: function () {
      document.querySelectorAll('#lookSheet .body').forEach(function (b) { var top = b.scrollTop; b.innerHTML = LOOKSHEET.html(); b.scrollTop = top; });
      var v = document.getElementById('chLookV'); if (v) { var s = D.styleOf(D.LOOK.style); v.innerHTML = '<i class="look-sw ' + s.sw + '"></i>' + s.label + ' · ' + palLabel(); }
      if (window.PROTO) requestAnimationFrame(PROTO.placeMarks);
      PV.rerender();
    }
  };
  function sheet(id, body, cls) { return '<div class="ios-sheet ' + (cls || '') + '" id="' + id + '" data-detent="full"><div class="mask"></div><div class="panel"><div class="grab"></div><div class="body">' + body + '</div></div></div>'; }

  // ---------- 4. 选择玩法 = 选模板(公共模板 / 我的模板,都是配置好的);列表式,只有分类标签横滑 ----------
  var pk = { cat: '全部', sel: null, cb: null, mode: 'tpl' };
  function tplRow(t, from) {
    var g = D.gameOf(t.game), on = pk.sel && pk.sel.key === t.key;
    return '<div class="pk-row' + (on ? ' is-on' : '') + '" data-act="pkSel" data-key="' + t.key + '" data-from="' + from + '"><span class="blk__tile" style="--gc:' + g.color + '">' + ic(g.icon, 20, 1.9) + '</span><span class="pk-row__m"><b>' + esc(t.title) + '</b><i>' + esc(g.label) + ' · ' + esc(t.place) + (t.photos ? ' · 图 ' + t.photos : '') + '</i></span><span class="pk-row__mode">' + esc(t.status) + '</span><span class="pk-row__ck">' + ic('check', 14, 2.6) + '</span></div>';
  }
  window.PICKER = {
    open: function (cb, mode) { pk.cb = cb; pk.sel = null; pk.cat = '全部'; pk.mode = mode || 'tpl'; if (mode) pk.back = null; PICKER.refresh(); PROTO.openSheet('pkSheet', 'full'); },
    html: function () {
      var byCat = function (arr) { return arr.filter(function (g) { return pk.cat === '全部' || g.cat === pk.cat; }); };
      var body;
      if (pk.mode === 'game') {
        // 只在「自己建一个」的模板表单里用:模板内部选具体小游戏
        body = '<div class="pk-sec">小游戏类型(模板内部)</div><div class="pk-list">' + byCat(D.GAMES).map(function (g) { var on = pk.sel && pk.sel.key === g.key; return '<div class="pk-row' + (on ? ' is-on' : '') + '" data-act="pkSel" data-key="' + g.key + '"><span class="blk__tile" style="--gc:' + g.color + '">' + ic(g.icon, 20, 1.9) + '</span><span class="pk-row__m"><b>' + esc(g.label) + '</b><i>' + esc(g.sub) + '</i></span><span class="pk-row__mode">' + g.mode + '</span><span class="pk-row__ck">' + ic('check', 14, 2.6) + '</span></div>'; }).join('') + '</div>';
      } else {
        var pub = byCat(D.TPL_PUB).map(function (t) { return tplRow(t, '公共模板'); }).join('') || '<div class="pk-none">这一类暂时没有</div>';
        var mine = byCat(D.TPL_MINE).map(function (t) { return tplRow(t, '我的模板'); }).join('');
        body = '<div class="pk-sec">公共模板</div><div class="pk-list"' + U.mk(2) + '>' + pub + '</div>' +
          '<div class="pk-sec">我的模板</div><div class="pk-list"><div class="pk-row pk-row--new" data-go="tpl-form"' + U.mk(3) + '><span class="blk__tile blk__tile--new">' + ic('plus', 20, 2.2) + '</span><span class="pk-row__m"><b>自己建一个</b><i>打开玩法模板表单,配好后回来选用</i></span>' + ic('right', 16, 2) + '</div>' + mine + '</div>';
      }
      return '<div class="pk"><div class="pk-top"><span class="pk-top__l" data-act="pkBack">返回</span><span class="pk-top__t">' + (pk.mode === 'game' ? '选择小游戏' : '选择玩法模板') + '</span><span class="pk-top__r"></span></div>' +
        '<div class="pk-cats"' + U.mk(1) + '>' + D.CATS.map(function (c) { return '<span class="pk-cat' + (c === pk.cat ? ' is-on' : '') + '" data-act="pkCat" data-cat="' + c + '">' + c + '</span>'; }).join('') + '</div>' +
        '<div class="pk-body">' + body + '<div style="height:90px"></div></div>' +
        '<div class="pk-foot"' + U.mk(4) + '><span class="ed-btn' + (pk.sel ? '' : ' is-off') + '" data-act="pkOk">确定' + (pk.sel ? ' · ' + esc(pk.sel.title || pk.sel.label) : '') + '</span></div></div>';
    },
    refresh: function () { document.querySelectorAll('#pkSheet .body').forEach(function (b) { var t = b.querySelector('.pk-body'); var top = t ? t.scrollTop : 0; b.innerHTML = PICKER.html(); var t2 = b.querySelector('.pk-body'); if (t2) t2.scrollTop = top; }); if (window.PROTO) requestAnimationFrame(PROTO.placeMarks); }
  };

  // ---------- 4b. 节点弹窗(已通过的设计:取消 / 新建节点 / 完成;名称 + 地点一组;玩法模板;到达与引导 / 照片;删除) ----------
  var nd = { id: null, isNew: false, poi: false };
  window.NODE = {
    open: function (id, isNew) { nd.id = id; nd.isNew = !!isNew; nd.poi = false; NODE.refresh(); PROTO.openSheet('ndSheet', 'full'); },
    html: function () {
      var b = ED.byId(nd.id); if (!b) return '';
      var t = b.tpl ? D.tplOf(b.tpl) : null;
      var row = function (k, v, act, extra) { return '<div class="nd-row"' + (act ? ' data-act="' + act + '"' : '') + (extra || '') + '><span class="nd-row__k">' + k + '</span><span class="nd-row__v">' + v + '</span>' + (act ? ic('right', 16, 2) : '') + '</div>'; };
      var photos = (b.photos || []).map(function (src, i) { return '<div class="pgrid-cell" style="background-image:url(' + src + ')"><span class="pgrid-del" data-act="ndDelPhoto" data-i="' + i + '">' + ic('close', 10, 2.6) + '</span></div>'; }).join('') + ((b.photos || []).length < 9 ? '<div class="pgrid-cell pgrid-add" data-act="ndAddPhoto">' + ic('plus', 22, 2) + '</div>' : '');
      var poi = nd.poi ? '<div class="nd-poi">' + D.POIS.map(function (pp) { return '<div class="nd-poi__i" data-act="ndPoi" data-n="' + esc(pp[0]) + '" data-a="' + esc(pp[1]) + '">' + ic('pin', 14, 2) + '<span><b>' + esc(pp[0]) + '</b><i>' + esc(pp[1]) + '</i></span></div>'; }).join('') + '</div>' : '';
      return '<div class="pk nd"><div class="pk-top"><span class="pk-top__l" data-act="ndCancel">取消</span><span class="pk-top__t">' + (nd.isNew ? '新建节点' : '编辑节点') + '</span><span class="pk-top__r nd-done" data-close>完成</span></div><div class="pk-body">' +
        '<div class="nd-grp"' + U.mk(1) + '><div class="nd-row"><span class="nd-row__k">名称</span><input class="nd-in" data-nd="name" value="' + esc(b.name || '') + '" placeholder="给这个节点起个名字"></div>' +
        row('地点', b.place ? esc(b.place[0]) + ' · ' + esc(b.place[1]) : '<span class="is-ph">选择地点</span>', 'ndPickPoi') + poi + '</div>' +
        '<div class="nd-grp"' + U.mk(2) + '>' + row('玩法模板', t ? esc(t.title) : '<span class="is-ph">选择或创建玩法模板</span>', 'ndPickTpl') + (t ? '<div class="nd-sub">' + esc(D.gameOf(t.game).label) + ' · ' + esc(t.by === '我' ? '我的模板' : '公共模板') + ' · ' + esc(t.place) + '</div>' : '') + '</div>' +
        '<div class="nd-grp">' + row('到达与引导', '到达后开始', null, ' data-toast="原型:到达与引导"') + row('照片', (b.photos || []).length + '/' + (t ? t.photos : 9), null) + '<div class="pgrid">' + photos + '</div></div>' +
        (nd.isNew ? '' : '<div class="nd-grp nd-grp--danger" data-act="ndDelete">删除节点</div>') + '<div style="height:60px"></div></div></div>';
    },
    refresh: function () { document.querySelectorAll('#ndSheet .body').forEach(function (x) { x.innerHTML = NODE.html(); }); ED.render(); if (window.PROTO) requestAnimationFrame(PROTO.placeMarks); }
  };
  document.addEventListener('input', function (e) { var t = e.target; if (t.getAttribute && t.getAttribute('data-nd') === 'name') { var b = ED.byId(nd.id); if (b) b.name = t.value; } });

  // ---------- 4c. 图片组版式:一组图整体移动 ----------
  var il = { cb: null };
  window.IMGL = {
    open: function (cb) { il.cb = cb; document.querySelectorAll('#ilSheet .body').forEach(function (x) { x.innerHTML = IMGL.html(); }); PROTO.openSheet('ilSheet', 'half'); },
    html: function () {
      return '<div class="pk il"><div class="pk-top"><span class="pk-top__l" data-close>取消</span><span class="pk-top__t">图片</span><span class="pk-top__r"></span></div><div class="pk-body"><div class="il-grid"' + U.mk(1) + '>' +
        D.IMG_LAYOUTS.map(function (l) { var cells = ''; for (var k = 0; k < l[0]; k++) cells += '<i></i>'; return '<div class="il-opt" data-act="ilPick" data-n="' + l[0] + '"><div class="il-pic il-pic--' + l[0] + '">' + cells + '</div><span>' + l[1] + '</span></div>'; }).join('') + '</div></div></div>';
    }
  };

  // ---------- 5. 分支任务编辑 ----------
  var br = { id: null };
  var TOS = ['继续本章', '跳到第 2 章', '跳到第 3 章', '结束本章'];
  window.BRANCH = {
    open: function (id) { br.id = id; BRANCH.refresh(); PROTO.openSheet('brSheet', 'full'); },
    html: function () {
      var b = ED.byId(br.id); if (!b) return '';
      return '<div class="pk"><div class="pk-top"><span class="pk-top__l" data-close>关闭</span><span class="pk-top__t">分支任务</span><span class="pk-top__r" data-close>完成</span></div><div class="pk-body br">' +
        '<div class="br-k">问题</div><textarea class="br-ta" data-br="q" rows="2">' + esc(b.q) + '</textarea>' +
        '<div class="br-k">分支(2 到 3 条)</div>' + b.opts.map(function (o, i) {
          return '<div class="br-opt"' + (i === 0 ? U.mk(1) : '') + '><div class="br-opt__l"><span class="k">' + 'ABC'[i] + '</span><input class="br-in" data-br="t" data-i="' + i + '" value="' + esc(o.t) + '" placeholder="玩家看到的选项">' + (b.opts.length > 2 ? '<span class="br-x" data-act="brDel" data-i="' + i + '">' + ic('close', 12, 2.4) + '</span>' : '') + '</div>' +
            '<div class="br-to"><span class="br-to__k">去向</span>' + TOS.map(function (t) { return '<span class="look-chip' + (o.to === t ? ' is-on' : '') + '" data-act="brTo" data-i="' + i + '" data-to="' + t + '">' + t + '</span>'; }).join('') + '</div></div>';
        }).join('') + (b.opts.length < 3 ? '<div class="ed-dashed" data-act="brAdd">' + ic('plus', 16, 2.2) + '添加分支</div>' : '') +
        '<div class="br-pv" data-act="edPreview" data-stage="branch"' + U.mk(2) + '>' + ic('eye', 14, 2) + '预览玩家看到的分支弹窗</div><div style="height:60px"></div></div></div>';
    },
    refresh: function () { document.querySelectorAll('#brSheet .body').forEach(function (x) { x.innerHTML = BRANCH.html(); }); ED.render(); }
  };
  document.addEventListener('input', function (e) {
    var t = e.target, f = t.getAttribute && t.getAttribute('data-br'); if (!f) return;
    var b = ED.byId(br.id); if (!b) return;
    if (f === 'q') b.q = t.value; else b.opts[+t.getAttribute('data-i')].t = t.value;
    ED.render();
  });

  // ---------- 6. 自己建一个:玩法模板表单(结构照 pages/publish/temp / 修订稿 12) ----------
  function tplForm() {
    var sw = function (on) { return '<span class="tf-sw' + (on ? ' on' : '') + '" data-act="tfSw"></span>'; };
    var pick = function (v) { return '<span class="tf-pick"><span>' + v + '</span>' + ic('down', 16, 2) + '</span>'; };
    return nav('新建玩法模板', '') + '<div class="sv ed-sv ed-sv--plain"><div class="tf">' +
      '<div class="tf-hero"><div class="tf-cover">' + ic('image', 26, 1.6) + '<span>添加封面</span></div><div class="tf-hero__b"><input class="tf-title" placeholder="玩法名称"></div></div>' +
      '<div class="tf-sh">玩法看点<i>*</i></div><div class="tf-card"><textarea class="tf-ta" rows="2" placeholder="一句话说清玩家会做什么"></textarea></div>' +
      '<div class="tf-sh">投入物料</div><div class="tf-card"><input class="tf-in" placeholder="例:手机、一支笔"></div>' +
      '<div class="tf-sh">场地</div><div class="tf-card">' + pick('咖啡馆') + '</div>' +
      '<div class="tf-sh">类别<i>*</i></div><div class="tf-card"><div class="tf-chips">' + ['问答', '推理', '互动', '挑战', '决定'].map(function (c, i) { return '<span class="look-chip' + (i === 0 ? ' is-on' : '') + '" data-act="tfChip">' + c + '</span>'; }).join('') + '</div></div>' +
      '<div class="tf-sh">玩法规则<i>*</i></div><div class="tf-card"><div class="tf-step"><span class="n">1</span><textarea class="tf-ta" rows="1" placeholder="第一步玩家做什么"></textarea></div><div class="tf-step"><span class="n">2</span><textarea class="tf-ta" rows="1" placeholder="然后"></textarea></div><div class="ed-dashed" data-toast="原型:加一步">' + ic('plus', 16, 2.2) + '添加一步</div></div>' +
      '<div class="tf-sh">参与方式</div><div class="tf-card">' + pick('扫码参与') + '<div class="tf-row"><span>扫完回复</span>' + sw(false) + '</div></div>' +
      '<div class="tf-sh">玩法<i>*</i></div><div class="tf-card"' + U.mk(1) + '><div class="tf-row tf-row--game" data-act="tfPick"><span class="blk__tile" style="--gc:#FC3F3F">' + ic('check-circle', 20, 1.9) + '</span><span class="pk-row__m"><b>选项问答</b><i>2 至 4 个选项里选一个</i></span><span class="blk__act">更换</span></div>' +
      '<div class="tf-lab">题目</div><input class="tf-in" value="这家茶馆是哪一年开张的?"><div class="tf-lab">选项</div>' + ['1912 年', '1937 年', '1958 年'].map(function (o, i) { return '<div class="tf-opt"><span class="k">' + 'ABC'[i] + '</span><input class="tf-in" value="' + o + '"><span class="tf-ok' + (i === 1 ? ' on' : '') + '">' + ic('check', 14, 2.6) + '</span></div>'; }).join('') +
      '<div class="tf-row"><span>作答次数</span>' + pick('2 次') + '</div><div class="tf-row"><span>答完亮出正确答案</span>' + sw(true) + '</div></div>' +
      '<div class="tf-sh">结果反馈</div><div class="tf-card"><div class="tf-row"><span>成功反馈</span>' + sw(true) + '</div><textarea class="tf-ta" rows="2">掌柜笑了:「记性不错。」他给你续上了一杯。</textarea><div class="tf-row"><span>失败反馈</span>' + sw(false) + '</div></div>' +
      '<div class="tf-sh">完成奖励</div><div class="tf-card"><div class="tf-row"><span>优惠券</span>' + sw(true) + '</div>' + pick('饮品第二杯半价') + '<div class="tf-row"><span>勋章</span>' + sw(false) + '</div></div>' +
      '<div class="tf-sh">互动工具</div><div class="tf-card"><div class="tf-row"><span>背景音乐 · 老城茶馆</span><span class="blk__act">移除</span></div><div class="ed-dashed" data-toast="原型:从底部弹出工具列表">' + ic('plus', 16, 2.2) + '添加工具</div></div>' +
      '<div style="height:120px"></div></div></div>' +
      '<div class="ed-foot"' + U.mk(2) + '><span class="ed-btn ed-btn--ghost" data-toast="已存草稿">保存草稿</span><span class="ed-btn" data-act="tfDone">完成 · 回到选择玩法</span></div>';
  }

  // ---------- 7. 模板能力页:照 pages/templatedetail/topic-detail/index.wxml 的真实结构(xb-page / xb-head / xb-cta / cy-tabs / cap-sum / cap-list) ----------
  function tplCap() {
    var cover = 'assets/covers/old-movie.jpg';
    var stops = [['1', '人民广场站', '到站打卡', '到达广场后在指定位置打卡', '约 10 分钟'], ['2', '南京东路站', '站台问答', '在站台找到问题线索并作答', '约 15 分钟']];
    return '<view class="xb-page"><view class="xb-bg"><img class="xb-bg-img" src="' + cover + '"><view class="xb-bg-mask"></view></view>' +
      U.navBar({ overlay: true, pill: true }) +
      '<view class="xb-scroll xb-scroll--bleed sv"><view style="height:87px"></view>' +
      '<view class="xb-head"><view class="xb-head-cover"><img src="' + cover + '"></view><view class="xb-head-meta"><view class="xb-head-title ep2">城市定向样板</view><view class="xb-head-sub ep1">城瘾官方</view><text class="xb-head-badge">官方</text></view></view>' +
      '<view class="xb-cta"><view class="c-btn xb-cta-btn xb-cta-btn--share"><view class="btn btn--ghost" data-toast="原型:应用后进入编辑器配地址">应用</view></view><button class="xb-share" aria-label="分享主题模板">' + U.icon('share', 40) + '</button></view>' +
      '<view class="xb-tabs-wrap">' + U.tabs({ tabs: [{ key: 'details', label: '详情' }, { key: 'capabilities', label: '能力' }], active: 'capabilities', group: 'tdtab' }) + '</view>' +
      '<view data-pane="tdtab:details" hidden><view class="xb-caps"><view class="xb-caprow"><text class="xb-caprow-k">—</text></view></view></view>' +
      '<view data-pane="tdtab:capabilities">' +
      '<view class="cap-sum"' + U.mk(1) + '><text class="xb-chip">城市定向</text><text class="xb-chip">2 个玩法</text><text class="xb-chip">2 站</text><text class="xb-chip">约 40 分钟</text></view>' +
      '<view class="cap-list"' + U.mk(2) + '>' + stops.map(function (st) { return '<view class="cap-stop"><view class="cap-stop__no">' + st[0] + '</view><view class="cap-stop__main"><text class="cap-stop__name ep1">' + st[1] + '</text><text class="cap-stop__game">' + st[2] + '</text><text class="cap-stop__do">' + st[3] + '</text><view class="cap-stop__dur">' + U.icon('clock', 22) + '<text>' + st[4] + '</text></view></view></view>'; }).join('') + '</view>' +
      '</view><view class="xb-scroll-pad"></view><view style="height:60px"></view></view></view>';
  }

  var SCREENS = [
    ['ch-list', '章节列表', 'E · 主题编辑器', 'cyp theme-light pg-fabu ed', chList(), 'pages/publish/fabu/index.wxml(步骤 2 章节区)', notes([
      [1, '画面风格入口留在章节区上方,点开是已通过的画面风格面板(顺序:风格 → 参数 → 末世每章时间段 → 主题配色 → 字体 → 文字动效),面板顶部常驻一个玩家端弹窗小预览,改哪项颜色立刻变。'],
      [2, '每章一张卡:封面、第 N 章 + 名称、内容计数。点进去就是这一章的故事编辑器,章节开始 / 结束卡也在里面,不另开弹窗。'],
      [3, '主题开始弹窗单独预览入口(主题层,不属于任何一章)。']])],
    ['ed-1', '故事编辑器(可拖动)', 'E · 主题编辑器', 'cyp theme-light pg-fabu ed', editor(), 'pages/publish/fabu/index.wxml(故事流编辑)', notes([
      [1, 'v2 · 顶部「添加」栏:段落 / 图片 / 音频 / 玩法 / 分支 / 分割线。去掉了单独的「地点」:地点绑在玩法模板上,一个玩法一个地点,在节点弹窗里选。没有 B/I/S 等格式工具。'],
      [2, '每个块都能长按拖动(右侧六点也能更快抓起):抬起带阴影和轻微放大,下面的块滑开让位,目标位置是一条带底色的槽;拖到一段正文中间,槽会插进句子之间,放下就把这一段拆成两段。贴近上下边自动滚动,越靠边越快。拖动中每换一次位置,幽灵边上闪一圈当作震动的可视化。'],
      [3, 'v2 · 图片 = 图片组:点「图片」先选版式(单图 / 双图 / 三图 / 四图),再逐格选图;整组是一个块,拖动时整组一起走,不会被拆开。段落:点一下进入编辑(回车拆段,清空即删除)。'],
      [4, '章节开始 / 章节结束是固定在首尾的两张卡,不参与拖动:开始卡 = 封面 + 章节名 + 开场白;结束卡 = 结语 + 奖励 + 去向。各自有「预览弹窗」。'],
      [5, 'v2 · 「玩法」只指配置好的模板:点「玩法」插入一个节点块并打开已通过的节点弹窗(名称 / 地点 / 玩法模板 / 到达与引导 / 照片);「玩法模板」行打开模板列表(公共模板 / 我的模板 / 自己建一个),不再列模板里的具体小游戏。分支任务:问题 + 2 到 3 条分支,每条写去向。'],
      [6, '「预览」打开玩家端弹窗:主题开始 / 章节开始 / 章节结束 / 玩法开始 / 玩法结束(成功 · 失败)/ 分支选择,底部可切阶段、切四种风格和配色,弹窗配色随之变。'],
      [7, '底部的「已移到…/ 撤销」条:每次移动、插入、删除、拆段后出现 5 秒,点撤销回到上一步。']])],
    ['tpl-form', '自己建一个 · 玩法模板表单', 'E · 主题编辑器', 'cyp theme-light pg-fabu ed', tplForm(), 'pages/publish/temp(修订稿 12 结构)', notes([
      [1, '「自己建一个」就是原来的玩法模板表单;只有在这张表单里才选模板内部的具体小游戏(选项问答 / 猜数字……),主题里不配玩法细节。'],
      [2, '完成后回到「选择玩法」,新模板出现在「我的模板」里直接选用。']])],
    ['tpl-cap', '模板能力页', 'E · 主题编辑器', 'cyp theme-dark pg-td', tplCap(), 'pages/templatedetail/topic-detail/index.wxml(能力 tab,样式来自 templatedetail.wxss)', notes([
      [1, 'v3 · 页面结构与样式直接取自现有主题模板详情(xb-page:模糊封面底、方封面 + 标题 + 发布方、「应用」与分享同款、详情 / 能力 tab),上面一行概要 chips。'],
      [2, '能力 = 按站列玩法(序号、站名、玩法、玩家做什么、时长),与现有 cap-list 一致。没有「采用条件」卡,没有素材来源。']])]
  ];
  document.getElementById('screens').insertAdjacentHTML('beforeend', SCREENS.map(function (s) {
    return '<section class="scr ' + s[3] + '" data-id="' + s[0] + '" data-title="' + s[1] + '" data-group="' + s[2] + '" data-src="' + s[5] + '">' + s[4] + s[6] + '</section>';
  }).join(''));
  // 三个 sheet 挂在 viewport 层(shell 会把 #screens 下的 .scr 搬进手机;sheet 放到第一个屏里再搬)
  var host = document.querySelector('.scr[data-id="ed-1"]');
  host.insertAdjacentHTML('beforeend', sheet('lookSheet', LOOKSHEET.html(), 'c-sheet') + sheet('pkSheet', PICKER.html(), 'pk-sheet') + sheet('brSheet', '', 'pk-sheet') + sheet('ndSheet', '', 'pk-sheet') + sheet('ilSheet', '', 'pk-sheet'));
  var ch = document.querySelector('.scr[data-id="ch-list"]'); ch.insertAdjacentHTML('beforeend', sheet('lookSheet2', LOOKSHEET.html(), 'c-sheet').replace('id="lookSheet2"', 'id="lookSheet"'));
  var tf = document.querySelector('.scr[data-id="tpl-form"]'); tf.insertAdjacentHTML('beforeend', sheet('pkSheet2', PICKER.html(), 'pk-sheet').replace('id="pkSheet2"', 'id="pkSheet"'));

  window.ACT = Object.assign(window.ACT || {}, {
    init: function () {
      ED.mount();
      // 手机浏览器:没有左右栏,顶栏放一个屏选择器
      var bar = document.querySelector('.sh-bar'); if (!bar) return;
      var sel = document.createElement('select'); sel.className = 'sh-sel';
      [].forEach.call(document.querySelectorAll('.scr'), function (s) { var o = document.createElement('option'); o.value = s.getAttribute('data-id'); o.textContent = s.getAttribute('data-title'); sel.appendChild(o); });
      sel.onchange = function () { PROTO.show(sel.value); };
      bar.insertBefore(sel, bar.firstChild);
      window.ON_ENTER = function (id) { sel.value = id; };
      // 窄屏:按舞台实际高度重新算缩放,整部手机都看得到
      function fit2() { if (window.innerWidth > 760) return; var st = document.getElementById('shStage'), f = document.getElementById('shFit'); var s = Math.min((st.clientHeight - 12) / 868, (st.clientWidth - 12) / 414); f.style.transform = 'translate(-50%,-50%) scale(' + s + ')'; PROTO.placeMarks(); }
      window.addEventListener('resize', fit2); fit2(); setTimeout(fit2, 300); window.addEventListener('load', function () { setTimeout(fit2, 50); });
    },
    openChapter: function (el) { var i = +el.getAttribute('data-i'); ED.setChapter(i); PROTO.show('ed-1', true); },
    lkStyle: function (el) { D.setStyle(el.getAttribute('data-value')); LOOKSHEET.refresh(); },
    lkParam: function (el) { D.LOOK.params[el.getAttribute('data-key')] = el.getAttribute('data-value'); LOOKSHEET.refresh(); },
    lkTime: function (el) { D.LOOK.times[+el.getAttribute('data-i')] = el.getAttribute('data-value'); LOOKSHEET.refresh(); },
    lkPal: function (el) { D.LOOK.palette = el.getAttribute('data-value'); LOOKSHEET.refresh(); },
    lkFont: function (el) { D.LOOK.font = el.getAttribute('data-value'); LOOKSHEET.refresh(); },
    lkFx: function (el) { D.LOOK.textFx = el.getAttribute('data-value'); LOOKSHEET.refresh(); },
    pkCat: function (el) { pk.cat = el.getAttribute('data-cat'); PICKER.refresh(); },
    pkSel: function (el) { var k = el.getAttribute('data-key'); pk.sel = pk.mode === 'game' ? D.gameOf(k) : D.tplOf(k); pk.from = el.getAttribute('data-from'); PICKER.refresh(); },
    pkOk: function () { if (!pk.sel) { PROTO.toast('先选一个模板'); return; } var cb = pk.cb, s = pk.sel, f = pk.from; PROTO.closeSheets(document.querySelector('#pkSheet.open')); if (cb) cb(s.key, f); },
    ndPickTpl: function () { PROTO.closeSheets(); pk.back = function () { NODE.open(nd.id, nd.isNew); }; PICKER.open(function (key) { ED.setTpl(nd.id, key); NODE.open(nd.id, nd.isNew); }); },
    pkBack: function () { PROTO.closeSheets(); var b = pk.back; pk.back = null; if (b) b(); },
    ndPickPoi: function () { nd.poi = !nd.poi; NODE.refresh(); },
    ndPoi: function (el) { var b = ED.byId(nd.id); b.place = [el.getAttribute('data-n'), el.getAttribute('data-a')]; nd.poi = false; NODE.refresh(); },
    ndAddPhoto: function () { var b = ED.byId(nd.id); b.photos = b.photos || []; b.photos.push('assets/covers/' + ['life-backup', 'anomaly-coordinate', 'treasure-hunter'][b.photos.length % 3] + '.jpg'); NODE.refresh(); },
    ndDelPhoto: function (el) { var b = ED.byId(nd.id); b.photos.splice(+el.getAttribute('data-i'), 1); NODE.refresh(); },
    ndDelete: function () { PROTO.closeSheets(); ED.del(nd.id); },
    ndCancel: function () { var b = ED.byId(nd.id); PROTO.closeSheets(); if (nd.isNew && b && !b.tpl && !b.place) { ED.del(nd.id); ED.undo && ED.undoBar('已取消新建节点', false); } },
    ilPick: function (el) { var n = +el.getAttribute('data-n'); PROTO.closeSheets(); if (il.cb) il.cb(n); },
    brTo: function (el) { var b = ED.byId(br.id); b.opts[+el.getAttribute('data-i')].to = el.getAttribute('data-to'); BRANCH.refresh(); },
    brAdd: function () { var b = ED.byId(br.id); if (b.opts.length < 3) b.opts.push({ t: '选项 ' + 'ABC'[b.opts.length], to: '继续本章' }); BRANCH.refresh(); },
    brDel: function (el) { var b = ED.byId(br.id); if (b.opts.length > 2) b.opts.splice(+el.getAttribute('data-i'), 1); BRANCH.refresh(); },
    tfSw: function (el) { el.classList.toggle('on'); },
    tfChip: function (el) { el.classList.toggle('is-on'); },
    tfPick: function () { PICKER.open(function (key) { PROTO.toast('模板里换成 ' + D.gameOf(key).label); }, 'game'); },
    tfDone: function () { D.TPL_MINE.unshift({ key: 'm' + Date.now(), title: '新建的玩法模板', game: 'qaPick', cat: '问答', place: '咖啡馆 · 1 个地点', photos: 2, by: '我', status: '草稿' }); PROTO.toast('已存为我的模板'); PROTO.show('ed-1', true); if (nd.id && ED.byId(nd.id)) NODE.open(nd.id, nd.isNew); }
  });
})();
