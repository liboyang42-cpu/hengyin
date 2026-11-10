// 公共拼屏助手(H)+ 第一组:视觉系统与动态图标。所有游戏内元素只读 --lk-* 外观变量。
(function () {
  var esc = UI.esc;
  var SVG = {
    back: '<svg viewBox="0 0 24 24"><path d="M15 5l-7 7 7 7"/></svg>',
    x: '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg>',
    chev: '<svg viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg>',
    down: '<svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg>',
    more: '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.6" fill="currentColor"/><circle cx="12" cy="12" r="1.6" fill="currentColor"/><circle cx="19" cy="12" r="1.6" fill="currentColor"/></svg>',
    bag: '<svg viewBox="0 0 24 24"><path d="M6 8h12l1 12H5zM9 8V6a3 3 0 0 1 6 0v2"/></svg>',
    pin: '<svg viewBox="0 0 24 24"><path d="M12 21s-6-5.6-6-10a6 6 0 0 1 12 0c0 4.4-6 10-6 10z"/><circle cx="12" cy="11" r="2"/></svg>',
    share: '<svg viewBox="0 0 24 24"><path d="M4 12v7h16v-7M12 4v12M8 8l4-4 4 4"/></svg>',
    copy: '<svg viewBox="0 0 24 24"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/></svg>',
    plus: '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>',
    wechat: '<svg viewBox="0 0 24 24"><path d="M9 4C5 4 2 6.7 2 10c0 1.9 1 3.5 2.6 4.6L4 17l2.8-1.4c.7.2 1.4.3 2.2.3"/><path d="M15 9c3.9 0 7 2.5 7 5.5 0 1.6-.9 3-2.3 4L20 21l-2.5-1.2c-.8.2-1.6.3-2.5.3-3.9 0-7-2.5-7-5.5S11.1 9 15 9z"/></svg>',
    save: '<svg viewBox="0 0 24 24"><path d="M12 4v12M7 11l5 5 5-5M5 20h14"/></svg>',
    moments: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 3v9l6 3"/></svg>',
    star: '<svg viewBox="0 0 24 24"><path d="M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1.1 5.9L12 16.9l-5.3 2.8 1.1-5.9L3.5 9.7l5.9-.8z"/></svg>',
    check: '<svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
    nav: '<svg viewBox="0 0 24 24"><path d="M3 11l18-8-8 18-2-8z"/></svg>',
    shield: '<svg viewBox="0 0 24 24"><path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"/></svg>',
    clock: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>',
    repeat: '<svg viewBox="0 0 24 24"><path d="M4 12a8 8 0 0 1 14-5l2 2M20 12a8 8 0 0 1-14 5l-2-2M18 4v4h-4M6 20v-4h4"/></svg>',
    laurel: '<svg viewBox="0 0 36 72"><path d="M30 68c-14-6-22-20-22-36 0-10 3-20 10-28"/><path d="M10 18c-4 0-7 3-7 7 4 0 7-3 7-7zM8 30c-4 1-6 4-6 8 4-1 6-4 6-8zM9 42c-4 2-5 5-4 9 4-2 5-5 4-9zM13 52c-3 3-3 7-1 10 3-3 4-6 1-10zM19 60c-2 3-1 7 2 9 2-3 1-6-2-9zM14 10c-1-4-4-6-8-6 1 4 4 6 8 6z"/></svg>',
  };
  var SCREENS = [];
  function add(id, title, group, cls, html, src, notes) { SCREENS.push([id, title, group, cls, html, src || '', notes || '']); }
  function notes(list) { return '<ol class="notes">' + list.map(function (t) { return '<li data-n="' + t[0] + '">' + t[1] + '</li>'; }).join('') + '</ol>'; }
  function img(name) { return 'assets/covers/' + name + '.jpg'; }
  function top(title, o) {
    o = o || {};
    return '<div class="gm-top' + (o.cls ? ' ' + o.cls : '') + '"' + (o.mk ? ' data-mk="' + o.mk + '"' : '') + '>' + (o.back === false ? '' : '<span class="bk" ' + (o.backGo ? 'data-go="' + o.backGo + '"' : 'data-back') + '>' + SVG.back + '</span>') +
      (title ? '<span class="tt">' + esc(title) + '</span>' : '') + (o.right ? '<span class="ra" ' + (o.rightAttr || '') + '>' + o.right + '</span>' : '') + '</div>';
  }
  function hud(o) {
    o = o || {}; var p = o.hp == null ? .6 : o.hp;
    return '<div class="gm-hud' + (o.cls ? ' ' + o.cls : '') + '"' + (o.mk ? ' data-mk="' + o.mk + '"' : '') + '><span class="hud-ring" style="--p:' + p + '"><svg viewBox="0 0 30 30"><circle class="bgc" cx="15" cy="15" r="12"/><circle class="fg" cx="15" cy="15" r="12"/></svg><b>' + (o.hpN == null ? 3 : o.hpN) + '</b></span>' +
      '<span class="hud-it" data-go="gp-backpack">' + SVG.bag + '<b>' + (o.bag == null ? 2 : o.bag) + '</b></span>' +
      '<span class="gpill" style="--p:' + ((o.st || 2) / (o.of || 7)).toFixed(2) + '">第 ' + (o.st || 2) + ' / ' + (o.of || 7) + ' 站<i></i></span></div>';
  }
  function gb(label, o) {
    o = o || {};
    var a = ' class="gb gb--' + (o.kind || 'primary') + (o.cls ? ' ' + o.cls : '') + '"';
    if (o.go) a += ' data-go="' + o.go + '"'; if (o.back) a += ' data-back';
    if (o.pop) a += ' data-act="pop" data-id="' + o.pop + '"'; if (o.close) a += ' data-act="closePop"';
    if (o.act) a += ' data-act="' + o.act + '"'; if (o.sheet) a += ' data-sheet="' + o.sheet + '"' + (o.detent ? ' data-detent="' + o.detent + '"' : '');
    if (o.toast) a += ' data-toast="' + esc(o.toast) + '"'; if (o.mk) a += ' data-mk="' + o.mk + '"'; if (o.id) a += ' id="' + o.id + '"'; if (o.data) a += ' ' + o.data;
    return '<div' + a + '>' + label + '</div>';
  }
  function foot(inner, o) { o = o || {}; return '<div class="gm-foot' + (o.row ? ' gm-foot--row' : '') + (o.cls ? ' ' + o.cls : '') + '"' + (o.mk ? ' data-mk="' + o.mk + '"' : '') + '>' + inner + '</div>'; }
  // 居中弹窗:默认右上角关闭;close:false 的是「必须做出选择」的弹窗(没有无意义的 ×)
  function pop(id, o) {
    o = o || {};
    return '<div class="gm-pop' + (o.full ? ' gm-pop--full' : '') + (o.cls ? ' ' + o.cls : '') + '" id="' + id + '"' + (o.enter ? ' data-enter="' + o.enter + '"' : '') + '><div class="mask"' + (o.close === false ? '' : ' data-act="closePop"') + '></div><div class="card' + (o.center ? ' card--center' : '') + (o.full ? ' card--full' : '') + '"' + (o.mk ? ' data-mk="' + o.mk + '"' : '') + '>' +
      (o.title || o.close !== false ? '<div class="card__h"><span class="card__t">' + (o.title || '') + '</span>' + (o.close === false ? '' : '<span class="x" data-act="closePop">' + SVG.x + '</span>') + '</div>' : '') +
      '<div class="card__b">' + (o.body || '') + '</div>' + (o.foot ? '<div class="card__f">' + o.foot + '</div>' : '') + '</div></div>';
  }
  // 底部弹层:壳的 ios-sheet(peek / half / full),外观着色,关闭在右上角
  function sheet(id, o) {
    o = o || {};
    return '<div class="ios-sheet" id="' + id + '" data-detent="' + (o.detent || 'half') + '"><div class="mask"></div><div class="panel"' + (o.mk ? ' data-mk="' + o.mk + '"' : '') + '><div class="grab"></div><div class="gsheet"><div class="gsheet__h"><span class="gsheet__t">' + (o.title || '') + '</span><span class="x" data-close>' + SVG.x + '</span></div><div class="gsheet__b">' + (o.body || '') + '</div>' + (o.foot ? '<div class="gsheet__f">' + o.foot + '</div>' : '') + '</div></div></div>';
  }
  function qr(text, dark, light) {
    dark = dark || '#111'; light = light || '#fff';
    var n = 25, hh = 7; for (var i = 0; i < text.length; i++) hh = (hh * 31 + text.charCodeAt(i)) >>> 0;
    function r() { hh ^= hh << 13; hh >>>= 0; hh ^= hh >>> 17; hh ^= hh << 5; hh >>>= 0; return hh / 4294967296; }
    var c = ''; function f(x, y) { return '<rect x="' + x + '" y="' + y + '" width="7" height="7" fill="' + dark + '"/><rect x="' + (x + 1) + '" y="' + (y + 1) + '" width="5" height="5" fill="' + light + '"/><rect x="' + (x + 2) + '" y="' + (y + 2) + '" width="3" height="3" fill="' + dark + '"/>'; }
    for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) { var inF = (x < 8 && y < 8) || (x > n - 9 && y < 8) || (x < 8 && y > n - 9); if (!inF && r() > .52) c += '<rect x="' + x + '" y="' + y + '" width="1" height="1" fill="' + dark + '"/>'; }
    return '<svg viewBox="0 0 25 25" shape-rendering="crispEdges">' + c + f(0, 0) + f(18, 0) + f(0, 18) + '</svg>';
  }
  function dr(v, cls) { return '<span class="dr ' + (cls || '') + '" data-v="' + v + '">' + String(v).split('').map(function (d) { return '<span class="dr__col"><span class="dr__strip" style="transform:translateY(' + (-d * 10) + '%)">0<br>1<br>2<br>3<br>4<br>5<br>6<br>7<br>8<br>9</span></span>'; }).join('') + '</span>'; }
  function flush() {
    document.getElementById('screens').insertAdjacentHTML('beforeend', SCREENS.map(function (s) {
      return '<section class="scr ' + s[3] + '" data-id="' + s[0] + '" data-title="' + s[1] + '" data-group="' + s[2] + '" data-src="' + s[5] + '">' + s[4] + s[6] + '</section>';
    }).join(''));
    SCREENS.length = 0;
  }
  // 入场码色波:把码的 SVG 做成 mask
  var prevE = window.ON_ENTER; window.ON_ENTER = function (id) { if (prevE) prevE(id); [].forEach.call(document.querySelectorAll('.qrbig--xl:not([data-masked])'), function (q) { var svg = q.querySelector('svg'); if (!svg) return; q.setAttribute('data-masked', '1'); q.style.setProperty('--qrmask', 'url("data:image/svg+xml,' + encodeURIComponent(svg.outerHTML.replace('<svg', '<svg xmlns="http://www.w3.org/2000/svg"').replace(/fill="#fff"/g, 'fill="none"')) + '")'); }); };
  window.H = { SVG: SVG, add: add, notes: notes, img: img, top: top, hud: hud, gb: gb, foot: foot, pop: pop, sheet: sheet, qr: qr, dr: dr, flush: flush, esc: esc };

  // 公共动作
  window.ACT = Object.assign(window.ACT || {}, {
    pop: function (el) { G.openPop(el.getAttribute('data-id')); },
    closePop: function (el) { G.closePop(el); },
    go: function (el) { PROTO.show(el.getAttribute('data-to'), true); },
    // 带外观转场的跳页
    txgo: function (el) { var to = el.getAttribute('data-to'); G.tx(function () { PROTO.show(to, true); }); },
    replayIcons: function (el) { var root = el.closest('.scr'); [].forEach.call(root.querySelectorAll('.ic'), function (ic) { var c = ic.cloneNode(true); ic.parentNode.replaceChild(c, ic); }); },
    init: function () { LOOK.mount(); },
  });

  // ===== 1 · 视觉系统 =====
  var I = ICONS.img;
  var vsHtml = top('视觉系统', { back: false, cls: 'gm-top--bare' }) +
    '<div class="gm-body gm-body--nohud vs">' +
    '<div class="gk">外观 × 主题配色 · 顶栏切换,整屏跟着换</div>' +
    '<div class="sec">色板</div><div class="sw" data-mk="1"><div style="background:var(--lk-bg)">底</div><div style="background:var(--lk-ink);color:var(--lk-bg)">墨</div><div style="background:var(--lk-mute);color:var(--lk-bg)">次</div><div style="background:var(--lk-a);color:var(--lk-bg)">主色</div><div style="background:var(--lk-b);color:var(--lk-bg)">辅色</div></div>' +
    '<div class="sec">字</div><div class="typ" data-mk="2"><div class="gh1" style="margin:0">老城三封信</div><div class="line" style="margin:0">信封上只写着一个地址,没有名字。</div><div class="gp">第二站在邮政大楼。白天和夜里,它看起来不像同一栋楼。</div><div class="gnum">1,250 <span class="gk">分</span></div></div>' +
    '<div class="sec">弹窗面</div><div class="popdemo" data-mk="3" style="background:url(assets/covers/treasure-hunter.jpg) center/cover"><div class="card"><b>这是最终指认</b><span>古董店老板 · 钟楼对面</span><div class="row" style="margin-top:10px">' + gb('确认', { kind: 'primary', data: 'style="height:36px;flex:1;font-size:14px"' }) + gb('再想想', { kind: 'ghost', data: 'style="height:36px;flex:1;font-size:14px"' }) + '</div></div></div>' +
    '<div class="sec">按钮</div><div class="row" data-mk="4">' + gb('主动作', { kind: 'primary', toast: '主动作', data: 'style="flex:1"' }) + gb('强调', { kind: 'accent', toast: '强调动作', data: 'style="flex:1"' }) + '</div><div class="row" style="margin-top:8px">' + gb('次动作', { kind: 'ghost', toast: '次动作', data: 'style="flex:1"' }) + gb('完成', { kind: 'ok', toast: '成功态', data: 'style="flex:1"' }) + gb('文字', { kind: 'text', toast: '文字按钮' }) + '</div>' +
    '<div class="sec">标签 · 分段 · 药丸</div><div class="row" data-mk="5"><span class="gch on">全体</span><span class="gch">队内</span><span class="gch">线索 · 3</span><span class="gseg"><span class="it on">嵌入</span><span class="it">全屏</span></span></div>' +
    '<div class="sec">插图(空态同款画法)</div><div class="icons" data-mk="6">' + ['clue', 'badge', 'key', 'success', 'fail', 'wait', 'team', 'points'].map(function (k) { return '<div>' + I(k, { size: 48 }) + ICONS.names[k] + '</div>'; }).join('') + '</div>' +
    '<div class="sec">结算、通行证、票夹、收集册在主题之外,保持玩家纯黑 token,不跟外观走</div><div style="height:40px"></div></div>';
  add('vs-look', '视觉系统 · 四种外观', 'A · 视觉系统', 'cyp gm vs', vsHtml, 'pages/play/utils/theme-look.js · pages/play/index.wxss look--*', notes([
    [1, '色板五档,来自已上线的 theme-look 目录;游戏内每张弹窗、卡片、按钮只读这五档。'],
    [2, '字:标题用外观各自的展示字,故事正文用定稿的手写 / 楷体,数字等宽。'],
    [3, '弹窗面(这里压在一张照片上演示):星尘改成果冻一样的透光玻璃 —— 几乎全透明、只轻微模糊、四边一圈高光、斜向一道反光,底下的照片和星野透过来;末世是风化锈蚀的铁板;纸本、夜城不变。'],
    [4, '按钮四种;圆角随外观。'],
    [5, '标签、分段。'],
    [6, '插图改成我们自己画的一套,画法与你空态插图一致(白色有机底形 + 粗黑描边 + 白填充 + 柠檬绿点缀);每枚有进场 + 常驻动画,下一屏全部列出。']]));

  // ===== 2 · 3D 图标集 + 来源 =====
  var DESC = { clue: '气泡弹出,绿点闪', badge: '绶带轻摆', stamp: '盖下去', key: '转一下像开锁', success: '星芒慢转', fail: '闪电弹出、闪烁', wait: '放大镜左右扫', team: '两侧靠拢', seat: '轻浮', pass: '轻浮', points: '三枚叠落', seal: '压下去', trophy: '轻晃', compass: '摆针', backpack: '轻浮', bookmark: '轻摆', envelope: '封口呼吸', star: '闪烁', locked: '轻浮', unlocked: '锁扣弹开', hourglass: '翻转', gem: '闪烁', sparkles: '星芒慢转', question: '气泡轻浮', rays: '星芒慢转' };
  var icHtml = top('插图集', { back: false, cls: 'gm-top--bare', right: SVG.repeat, rightAttr: 'data-act="replayIcons"' }) +
    '<div class="gm-body gm-body--nohud"><div class="gk">点右上角重播 · 点任一张单独重播</div><div class="icongrid" data-mk="1">' +
    ICONS.list.map(function (k) { return '<div data-act="replayOne">' + I(k, { size: 72, n: '三' }) + '<b>' + ICONS.names[k] + '</b><span>' + DESC[k] + '</span></div>'; }).join('') +
    '</div><div class="gcard" data-mk="2" style="display:flex;gap:12px;align-items:center"><span style="position:relative;width:72px;height:70px;flex:none"><img src="assets/img/empty_state_bg.svg" style="position:absolute;inset:0;width:100%"><img src="assets/img/empty_state_figure.svg" style="position:absolute;left:21%;top:14%;width:58%"></span><div><div class="gh3">画法来源</div><div class="gp" style="font-size:13px;margin-top:4px">你的空态插图(empty_state_bg + figure):白色有机底形,一层粗黑描边的手绘物件,白填充,柠檬绿 #CDFF71 点缀,黑色实形。二十五枚全部按这个画,原创 SVG,无第三方素材。</div></div></div><div style="height:60px"></div></div>';
  add('vs-icons', '插图集 · 空态同款', 'A · 视觉系统', 'cyp gm', icHtml, 'icons.js(原创 SVG)· 画法来源 assets/img/empty_state_*.svg', notes([
    [1, '二十五枚自己画的插图:线索、勋章、邮戳、钥匙、成功、失败、调查中、队伍、席位、通行证、积分、章节印、名次、雷达、背包、任务、邀请、评价、锁 / 开锁、等待、藏品、闪光、提示、光芒。两层:白色底形先弹出并缓慢呼吸,物件再弹入并各有自己的常驻动作;减少动态效果时只留终态。3D 素材已全部删除。'],
    [2, '画法来源:你的空态插图,放在旁边对照。']]));
  window.ACT.replayOne = function (el) { var ic = el.querySelector('.i3d'); if (!ic) return; var c = ic.cloneNode(true); ic.parentNode.replaceChild(c, ic); };
  window.ACT.replayIcons = function (el) { var root = el.closest('.scr'); [].forEach.call(root.querySelectorAll('.i3d'), function (ic) { var c = ic.cloneNode(true); ic.parentNode.replaceChild(c, ic); }); };
  flush();
})();
