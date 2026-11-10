// v2 · 第二组:游玩界面与阶段弹窗。改动来源逐条写在 notes 里(「来源:…」)。
(function () {
  var S = H.SVG, I = ICONS.img, gb = H.gb, top = H.top, foot = H.foot, pop = H.pop, sheet = H.sheet, img = H.img, fx = G.fxLine;
  var THEME = '老城三封信';

  // ---- 现有游玩页顶栏(来源:pages/play/index.wxml play-topbar + a1-v3-13-14 截图):左上雷达小图,体力 / 背包药丸,右侧进度 ----
  function hud(o) {
    o = o || {}; var p = o.hp == null ? .6 : o.hp;
    return '<div class="gm-hud gm-hud--v2' + (o.cls ? ' ' + o.cls : '') + '"' + (o.mk ? ' data-mk="' + o.mk + '"' : '') + '>' +
      '<span class="radar" data-toast="放大地图"><svg viewBox="0 0 84 84"><circle cx="42" cy="42" r="40"/><path class="st" d="M6 30 Q40 26 78 44 M18 70 Q40 40 62 10 M30 78 L54 6 M10 52 L74 60"/><circle class="me" cx="42" cy="42" r="4"/><path class="cone" d="M42 42 L26 12 A34 34 0 0 1 58 12 Z"/><text x="42" y="14" text-anchor="middle">N</text></svg></span>' +
      '<span class="hud-it hud-hp"><i class="hp" style="--p:' + p + '"><svg viewBox="0 0 24 24"><path d="M12 20s-7-4.6-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.4-7 10-7 10z"/></svg></i>体力 <b>' + (o.hpN == null ? 3 : o.hpN) + '</b>/5</span>' +
      '<span class="hud-it" data-go="gp-backpack">' + S.bag + '<b>' + (o.bag == null ? 2 : o.bag) + '</b>/6</span>' +
      '<span class="gpill" style="--p:' + ((o.st || 2) / (o.of || 7)).toFixed(2) + '">' + (o.st || 1) + '/' + (o.of || 7) + '<i></i></span></div>';
  }
  function menuSheet() {
    return sheet('sh-menu', { title: '本局', detent: 'half', body:
      '<div class="grow" data-go="gp-qr">入场码<span class="v">' + S.chev + '</span></div>' +
      '<div class="grow" data-go="gp-backpack">背包<span class="v ink">2 件</span></div>' +
      '<div class="grow" data-toast="背景音 · 开">背景音<span class="v ink">开</span></div>' +
      '<div class="grow" style="color:#FF5A5F" data-toast="已暂停,票夹里可以回来">暂停离开</div>' });
  }

  // ===== 主题开始(保留) =====
  H.add('gp-start', '主题开始 · 标题卡', 'B · 游玩与阶段弹窗', 'cyp gm',
    '<div class="gm-bg" style="background-image:url(' + img('treasure-hunter') + ');opacity:.55;filter:blur(2px) saturate(.5)"></div><div class="gm-tex"></div>' +
    top('', { right: S.more, rightAttr: 'data-sheet="sh-menu"' }) +
    '<div class="tstart"><div class="gk">城市定向 · 外滩源</div><div class="tt">' + THEME + '</div><div class="ts">一封四十年前寄出的信,昨天才送到。寄信人没有留名字,只画了一座停在十一点的钟。</div><div class="tm"><span><b>3</b>章</span><span><b>7</b>地点</span><span><b>1.2</b>公里</span><span><b>60</b>分钟</span></div></div>' +
    foot(gb('开始', { act: 'txgo', data: 'data-to="gp-chapter-start"' }) + gb('先看地图', { kind: 'text', toast: '打开大地图' })) + menuSheet(),
    'pages/play/index.wxml(封面层)', H.notes([[1, '上一轮通过,未动。']]));

  // ===== 章节开始 · 波点(像素方块)章节数字 =====
  H.add('gp-chapter-start', '章节开始 · 波点数字', 'B · 游玩与阶段弹窗', 'cyp gm',
    '<div class="gm-tex"></div><canvas class="tiles-cv" id="tilesStart" width="780" height="1688"></canvas>' + top('', { right: S.more, rightAttr: 'data-sheet="sh-menu"' }) +
    '<div class="cstart cstart--v2" data-mk="1"><div class="cstart__gap"></div><div class="gk">第一章 · 共三章</div><div class="gh1">邮局旧址</div><div class="gp">三个地点 · 约 400 米</div></div>' +
    foot(gb('读下去', { act: 'txgo', data: 'data-to="gp-story"' })) + menuSheet(),
    '来源:定稿 KKQJxLNAL3JSb5pYDF1xtb「章节翻转」的像素方块数字(星点飞拢锁成方格网)+ cy-chapter-flip', H.notes([
      [1, '章节开始回到之前做过的那版:满屏星点飞拢,锁成方块像素的章节数字「01」,金色格子带高光,下面章名和地点数逐字解码出现;点「读下去」数字炸散回星点。来源:KKQJxL 定稿原型的 tiles 模式(sample → assemble → scatter 同一套参数:格 9px、方块 7.4px),没有再画立方体。']]));

  // ===== 故事流(顶栏按现有游玩页) =====
  function storyBody(opts) {
    opts = opts || {};
    return '<div class="gm-body" id="' + (opts.id || 'storyBody') + '">' +
      '<div class="gk">第一章 · 邮局旧址</div>' +
      fx('信是昨天到的。邮戳上的日期,是四十年前。') + fx('寄信人没有留名字,只画了一座钟楼,钟面停在十一点。') + fx('第一站在邮政大楼。白天和夜里,它看起来不像同一栋楼。') +
      '<div class="stn" data-go="gp-place"' + (opts.mkStn ? ' data-mk="' + opts.mkStn + '"' : '') + '><img class="cov" src="' + img('treasure-hunter') + '"><div class="stn__b"><span class="stn__n">1</span><span class="stn__t"><b>邮政大楼</b><span>地点 · 距你 120 米 · 1 个玩法</span></span><span class="stn__a">到这里了 ›</span></div></div>' +
      fx('柜台后的老人把第二封信推过来。信封比第一封厚,里面有东西在响。') +
      (opts.kit !== false ? '<div class="kitc" data-act="pop" data-id="pop-kitstart"' + (opts.mkKit ? ' data-mk="' + opts.mkKit + '"' : '') + '><img src="' + img('old-movie') + '"><span><b>谁在钟楼留下了钥匙</b><span>模板 · 判案 · 绑定 邮政大楼</span></span>' + I('clue', { size: 40 }) + '</div>' : '') +
      (opts.after || '') +
      fx('钟楼的方向传来一声钟响。十一点整。') +
      '<div class="kitc" data-act="pop" data-id="pop-branch"' + (opts.mkBr ? ' data-mk="' + opts.mkBr + '"' : '') + '>' + I('key', { size: 44 }) + '<span><b>岔路</b><span>往钟楼走,还是先回头找报童</span></span><span class="stn__a">选 ›</span></div>' +
      '<div class="extrow" data-act="pop" data-id="pop-ext"' + (opts.mkExt ? ' data-mk="' + opts.mkExt + '"' : '') + '>' + I('bookmark', { size: 30 }) + '<b>延伸任务</b><span class="n">3</span>' + S.chev.replace('<svg', '<svg style="width:14px;height:14px;stroke:currentColor;fill:none;stroke-width:2"') + '</div>' +
      '<div style="height:40px"></div></div>';
  }
  function kitStartPop(mk) {
    return pop('pop-kitstart', { title: '玩法', mk: mk, body: '<div class="kstart"><img class="cov" src="' + img('old-movie') + '"><div class="meta"><span class="gch">模板 · 判案</span><span class="gch">地点 · 邮政大楼</span><span class="gch">约 5 分钟</span></div><div class="gh2" style="margin:10px 0 4px">谁在钟楼留下了钥匙</div><div class="gp">三个人那晚都在钟楼附近。听完证词,指认一个人。</div><div class="grow" style="margin-top:8px"><span>绑定地点</span><span class="v ink">邮政大楼 · 已到</span></div><div class="kmode" id="kmode"><div class="on" data-act="kmode" data-m="embed"><b>嵌入故事流</b>答完接着往下读</div><div data-act="kmode" data-m="full"><b>全屏</b>进入整屏玩法</div></div></div>',
      foot: gb('开始', { act: 'kitGo', id: 'kitGo' }) });
  }
  function branchPop(mk) {
    return pop('pop-branch', { title: '岔路', close: false, mk: mk, body: '<div class="gp" style="margin-bottom:6px">钟声停了。你手里只有一把钥匙的时间。</div><div class="branch" id="branch">' +
      '<div class="br" data-act="branchPick" data-i="0"><span class="k">A</span><span><b>直接去钟楼</b><span class="cq"><span class="m">体力 −1</span><span>时间 +0</span></span></span>' + I('key', { size: 34 }) + '</div>' +
      '<div class="br" data-act="branchPick" data-i="1"><span class="k">B</span><span><b>回头找报童问路</b><span class="cq"><span class="p">解锁线索</span><span class="m">时间 +10 分</span></span></span>' + I('clue', { size: 34 }) + '</div>' +
      '<div class="br" data-act="branchPick" data-i="2"><span class="k">C</span><span><b>先回邮局翻账本</b><span class="cq"><span class="p">积分 +15</span><span class="m">错过钟楼</span></span></span>' + I('scroll', { size: 34 }) + '</div></div>' +
      '<div class="gerr" id="brNote" style="color:var(--lk-dim)"></div>', foot: gb('就这么走', { act: 'branchGo', id: 'brGo', cls: 'is-off' }) });
  }
  // 延伸任务:翻页卡(一次一张,点卡或箭头翻到下一张)
  var EXT = [['prebuilt-life', '地铁站的第三根柱子', '找到柱子上 1930 年代的瓷砖编号,拍一张。', '240 米', '+30 分', '今天内'], ['life-backup', '弄堂口的洗衣店', '老板娘手里有第三封信的复印件,换一句暗号。', '400 米', '线索 ×1', '20:00 前'], ['flavor-tour', '老街的灯笼铺', '数清门楣上的灯笼,数字就是密码的一半。', '650 米', '+20 分', '不限']];
  function extPop(mk) {
    return pop('pop-ext', { title: '延伸任务', mk: mk, body: '<div class="flipdeck" id="extdeck">' + EXT.map(function (c, i) { return '<div class="fcard' + (i === 0 ? ' on' : '') + '" data-i="' + i + '" data-act="flipNext" data-deck="extdeck"><img class="cov" src="' + img(c[0]) + '"><div class="fcard__b"><div class="gk">' + (i + 1) + ' / 3</div><b>' + c[1] + '</b><p>' + c[2] + '</p><div class="extc__i"><span>距离<b>' + c[3] + '</b></span><span>奖励<b>' + c[4] + '</b></span><span>时限<b>' + c[5] + '</b></span></div></div>' + gb('去做', { kind: 'primary', toast: '已加入路线:' + c[1], data: 'style="margin:0 14px 14px;height:42px;font-size:14px"' }) + '</div>'; }).join('') + '</div>' +
      '<div class="flipctl"><span class="dots" id="extdots"><i class="on"></i><i></i><i></i></span><span class="arrow" data-act="flipNext" data-deck="extdeck">' + S.chev + '</span></div>' });
  }
  function hintPop(mk) {
    return pop('pop-hint', { title: '提示', mk: mk, body: '<div class="gk" style="margin-bottom:6px">本局剩余免费 1 次</div>' +
      '<div class="hint-row" data-act="hintText">文字提示<span class="v free">免费</span></div><div class="hint-body" id="hintBody" hidden>钥匙不在人身上。想想谁有机会碰到账本。</div>' +
      '<div class="hint-row">首字<span class="v">需先看上一层</span></div><div class="hint-row">答案<span class="v">需先看上一层</span></div>' +
      '<div class="hint-row danger" data-toast="已跳过,本题 0 分">跳过本题</div><div class="hint-row" data-toast="分数明细">分数明细<span class="v">' + S.down + '</span></div>' });
  }
  function kitEndPop(ok) {
    return pop(ok ? 'pop-kend-ok' : 'pop-kend-no', { close: false, center: true, body: '<div class="kend">' + I(ok ? 'success' : 'fail', { size: 110, glow: ok }) + '<div class="kt">' + (ok ? '答对了' : '差一点') + '</div><div class="ks">' + (ok ? '钥匙在账本夹层里。老人把它推给你。' : '账本里什么都没有。门外的脚步声近了。') + '</div><div class="kd">' + (ok ? '<span>分</span>+20 <span style="margin-left:10px">用时</span>0:42' : '<span>体力</span>−1') + '</div></div>',
      foot: ok ? gb('继续', { close: true }) + gb('看线索', { kind: 'text', go: 'gp-backpack' }) : gb('再试一次', { close: true }) + gb('看提示', { kind: 'text', act: 'closeThenHint' }) });
  }
  H.add('gp-story', '故事流 · 顶栏 · 站点卡', 'B · 游玩与阶段弹窗', 'cyp gm',
    '<div class="gm-tex"></div>' + top(THEME, { right: S.more, rightAttr: 'data-sheet="sh-menu"' }) + hud({ hp: .6, hpN: 3, bag: 2, st: 1, of: 7, mk: 1 }) +
    storyBody({ mkStn: 3, mkKit: 4, mkBr: 5, mkExt: 6 }).replace('<div class="gk">第一章 · 邮局旧址</div>', '<div class="gk" data-mk="2">第一章 · 邮局旧址</div>') + kitStartPop() + branchPop() + extPop() + hintPop() + menuSheet(),
    'pages/play/index.wxml 故事流 + play-topbar', H.notes([
      [1, '顶部数据行回到现有游玩页的那一套(来源:pages/play/index.wxml 顶栏 + a1-v3-13-14 截图):左上雷达小地图(街道线 + 我的位置 + 朝向扇形 + N),右边「体力 3/5」(心形随体力填充)、「背包 2/6」、「1/7」进度药丸。地图放大时整行隐去。'],
      [2, '文字出现方式改回定稿动效(来源:BRgHjPbXZfe9xhd6QBCwfP v3 = theme-look.js STYLE_TEXT):标题 / 选项各固定一种,正文按段在外观的池子里轮换、相邻不重复 —— 星尘 聚星 / 浮现 / 呼吸光,纸本 打字机 / 墨晕 / 落定,末世 风吹 / 信号 / 雾散 / 落定,夜城 解码 / 霓虹 / 扫描 / 擦出。往下滑才播。'],
      [3, '站点卡(上一轮通过,未动)。'],
      [4, '玩法卡:模板 · 判案 · 绑定 邮政大楼;图标换成统一的 3D 套。'],
      [5, '岔路入口(未动,图标换 3D)。'],
      [6, '延伸任务入口(未动);打开后的卡片改成翻页式,见后面那屏。']]));

  // ===== 地点卡:整屏照片 + 玻璃面板,无分享 =====
  H.add('gp-place', '地点卡 · 整屏照片 + 玻璃面板', 'B · 游玩与阶段弹窗', 'cyp gm place place--v2',
    '<div class="photo" data-mk="1" style="background-image:url(' + img('treasure-hunter') + ')"></div><div class="gm-tex"></div>' + top('') +
    '<span class="chip">' + S.pin.replace('<svg', '<svg style="width:12px;height:12px;stroke:currentColor;fill:none;stroke-width:2.2"') + '第 1 站 · 共 7 站</span>' +
    '<div class="glass-panel" id="placePanel" data-mk="2"><div class="gk">地点</div><div class="gh1" style="font-size:30px">邮政大楼</div><div class="addr">' + S.pin + '北苏州路 250 号 · 钟楼下的侧门</div><p class="gp">1924 年落成,楼顶的钟一度是全城对时的地方。侧门进去是老营业厅,柜台还是当年的样子。</p>' +
    '<div class="dist"><div><b>120</b><span>米</span></div><div><b>2</b><span>分钟步行</span></div><div><b>09–17</b><span>开放</span></div></div>' +
    '<div class="kitc" data-act="pop" data-id="pop-kitstart" style="margin:4px 0 12px"><img src="' + img('old-movie') + '"><span><b>谁在钟楼留下了钥匙</b><span>模板 · 判案 · 到站后开始</span></span>' + I('clue', { size: 36 }) + '</div>' +
    '<div class="gm-foot gm-foot--row" style="position:static;padding:0;background:none" data-mk="3">' + gb('导航', { kind: 'ghost', toast: '打开导航' }) + gb('到了', { act: 'arrive', id: 'arriveBtn' }) + '</div></div>' + kitStartPop(),
    'pages/play/index.wxml(节点弹层)→ 整屏', H.notes([
      [1, '照片铺满整屏(不再模糊、不再磨砂),站序号药丸在顶上;右上角的分享去掉了,只有返回。'],
      [2, '信息放在一块覆盖在照片上的玻璃面板里:星尘是透光的果冻玻璃(轻模糊 + 边缘高光,照片透过来),其他外观用各自的弹窗面 92% 不透明。面板里:名称、地址、一段介绍、三个数字、这一站绑定的那一个玩法模板。'],
      [3, '「导航」「到了」就在面板底部。']]));

  // ===== 玩法 · 嵌入(无框:题 + 四张大图直接在故事背景上) =====
  var qa = '<div class="qa-bare" id="qaBlock" data-mk="2"><div class="gk">问答 · 2 / 3</div>' + fx('信封里响的是什么?', 'gh3', 'title').replace('class="gh3 fx"', 'class="gh3 fx" style="margin:8px 0 14px"') +
    '<div class="imgopts imgopts--bare" id="imgopts">' + [['A', 'old-movie', '一把黄铜钥匙'], ['B', 'anomaly-coordinate', '一枚铜板'], ['C', 'heat-escape', '一串念珠'], ['D', 'midlife-day', '一截粉笔']].map(function (o) { return '<div class="imgopt" data-act="qaPick" data-k="' + o[0] + '"><img src="' + img(o[1]) + '"><span class="lb"><i>' + o[0] + '</i>' + o[2] + '</span></div>'; }).join('') + '</div>' +
    '<div class="gerr" id="qaErr">不是铜板。老人说,它比钱重要。</div><div style="display:flex;gap:8px;margin-top:6px">' + gb('提示', { kind: 'text', pop: 'pop-hint', mk: 3 }) + gb('跳过', { kind: 'text', toast: '本题 0 分' }) + '</div></div>';
  H.add('gp-kit-embed', '玩法 · 嵌入(无框大图)', 'B · 游玩与阶段弹窗', 'cyp gm',
    '<div class="gm-tex"></div>' + top(THEME, { right: S.more, rightAttr: 'data-sheet="sh-menu"' }) + hud({ hp: .6, hpN: 3, bag: 2, st: 1, of: 7 }) +
    '<div class="gm-body" id="embedBody"><div class="gk">第一章 · 邮局旧址</div>' + fx('柜台后的老人把第二封信推过来。信封比第一封厚,里面有东西在响。') + '<div data-mk="1"></div>' + qa + fx('老人没有说话,把信封往你这边又推了一寸。') + '<div style="height:40px"></div></div>' +
    kitEndPop(true) + kitEndPop(false) + hintPop() + menuSheet(),
    'pages/play/components/playkit-qa', H.notes([
      [1, '去掉了框和「玩法开始 · 嵌入」那行:玩法就在故事背景上,前后的剧情紧接着。'],
      [2, '一道题 + 四张大图,两列、没有底、没有卡;字母和文案压在图下沿;选中上浮,答对描边变绿弹小结,答错描边变红 + 红字落在题下。选 A 看答对。'],
      [3, '「提示」「跳过」是两个文字按钮。']]));

  // ===== 玩法 · 全屏(逐字猜词,系统键盘) =====
  var WORD = 'KEEPER';
  H.add('gp-kit-full', '玩法 · 全屏(逐字猜词 · 系统键盘)', 'B · 游玩与阶段弹窗', 'cyp gm',
    '<div class="gm-tex"></div>' + top('逐字猜词', { backGo: 'gp-story', right: S.more, rightAttr: 'data-act="pop" data-id="pop-hint"' }) +
    '<div class="gm-body gm-body--nohud"><div class="hang" data-mk="1"><div class="gk">模板 · 逐字猜词 · 邮政大楼</div><div class="hang__q">钟楼的看守人,在信里被叫作什么?</div>' +
    '<label class="tiles" id="tiles" for="hangIn">' + WORD.split('').map(function (ch, i) { return '<span class="tile' + (i === 0 ? ' pre' : i === 1 ? ' cur' : '') + '" data-i="' + i + '">' + (i === 0 ? ch : '') + '</span>'; }).join('') + '</label>' +
    '<input class="hang__in" id="hangIn" data-mk="2" type="text" autocapitalize="characters" autocomplete="off" maxlength="5" placeholder="点格子,用系统键盘输入" data-act="noop">' +
    '<div class="gerr" id="hangErr">不对。再想想钟楼里最常出现的那个词。</div></div></div>' +
    foot(gb('提交', { act: 'hangSubmit', id: 'hangSubmit', cls: 'is-off', mk: 3 })) + kitEndPop(true) + hintPop() + menuSheet(),
    'pages/play/components/playkit-hangman', H.notes([
      [1, '整屏玩法,格子居中;预填格深一档,当前格描边主色。'],
      [2, '不再画自定义键盘:点格子唤起系统键盘,输入框就在格子下面,输入什么格子就填什么,每填一格弹一下。'],
      [3, '「提交」固定底部,填满才亮;答对格子依次变绿再弹小结。']]));

  // ===== 玩法 · 判案:线索可打开 + 嫌疑人翻页卡 =====
  var SUS = [['古', '古董店老板', '钟楼对面', '那晚十点我在店里盘点,账本可以作证。', 'wrong', 'old-movie'], ['守', '守夜人', '钟楼值班', '十一点我在楼下巡逻,看见有人从侧门出来。', 'right', 'time-pollution'], ['报', '报童', '广场街角', '我只是在卖报,钥匙的事我不清楚。', 'wait', 'flavor-tour']];
  var CLUES = [['十一点 · 钟停了', '钟楼的钟停在十一点整,发条完好,是有人把它停下的。', 'treasure-hunter'], ['侧门没有锁', '侧门的锁芯里有新的划痕,钥匙是从外面插进去的。', 'prebuilt-life'], ['账本少一页', '十一月十日那一页被整齐地裁掉,边上还有一点蜡。', 'midlife-day']];
  H.add('gp-kit-accuse', '玩法 · 判案(线索可开 · 翻页嫌疑人)', 'B · 游玩与阶段弹窗', 'cyp gm',
    '<div class="gm-tex"></div>' + top('判案', { backGo: 'gp-story', right: S.more, rightAttr: 'data-act="pop" data-id="pop-hint"' }) +
    '<div class="gm-body gm-body--nohud"><div class="gk">模板 · 判案 · 邮政大楼</div>' +
    '<div class="board" data-mk="1"><svg><line x1="70" y1="40" x2="190" y2="86"/><line x1="190" y1="86" x2="300" y2="40"/></svg>' + CLUES.map(function (c, i) { return '<span class="pin" data-act="pop" data-id="pop-clue-' + i + '" style="' + ['left:22px;top:22px;--rot:-4deg', 'left:140px;top:70px;--rot:3deg', 'left:240px;top:20px;--rot:-2deg'][i] + '">' + c[0] + '</span>'; }).join('') + '</div>' +
    '<div class="gp">点线索看详情;嫌疑人一次一张,翻到下一个。</div>' +
    '<div class="flipdeck flipdeck--sus" id="susdeck" data-mk="2">' + SUS.map(function (s, i) { return '<div class="fcard' + (i === 0 ? ' on' : '') + '" data-i="' + i + '"><img class="cov" src="' + img(s[5]) + '"><div class="fcard__b"><span class="av">' + s[0] + '</span><b>' + s[1] + '</b><span class="role">' + s[2] + '</span><div class="testi show">「' + s[3] + '」</div></div></div>'; }).join('') + '</div>' +
    '<div class="flipctl"><span class="dots" id="susdots"><i class="on"></i><i></i><i></i></span><span class="arrow" data-act="flipNext" data-deck="susdeck">' + S.chev + '</span></div><div style="height:30px"></div></div>' +
    foot(gb('再想想', { kind: 'ghost', act: 'flipNext', data: 'data-deck="susdeck"' }) + gb('指认 古董店老板', { act: 'accuse', id: 'accuseBtn', data: 'data-i="0"' }), { row: true }) +
    CLUES.map(function (c, i) { return pop('pop-clue-' + i, { title: '线索', body: '<img class="cov" src="' + img(c[2]) + '" style="height:160px;border-radius:calc(var(--lk-r) - 2px)"><div class="gh3" style="margin:12px 0 6px">' + c[0] + '</div><div class="gp">' + c[1] + '</div>', foot: gb('收进背包', { close: true, kind: 'ghost' }) }); }).join('') +
    pop('pop-accuse', { close: false, center: true, body: '<div class="kend"><span class="av" id="accAv" style="display:flex;width:64px;height:64px;border-radius:50%;margin:0 auto 10px;background:var(--lk-ctl);align-items:center;justify-content:center;font:700 24px var(--lk-fd)"></span><div class="kt" id="accT">这是最终指认</div><div class="ks" id="accS"></div></div>', foot: gb('确认指认', { act: 'accuseConfirm' }) + gb('再想想', { kind: 'text', close: true }) }) +
    '<div class="verdict" id="verdict" data-mk="3"><div id="vIcon"></div><div class="vt" id="vT"></div><div class="vs" id="vS"></div><div class="vd" id="vD"></div>' + foot(gb('继续', { act: 'verdictClose' })) + '</div>' + hintPop() + menuSheet(),
    'pages/play/components/playkit-accusation + play-verdict', H.notes([
      [1, '线索板上的三张便签都能点开:一张线索弹窗,有图、有一句描述,「收进背包」。'],
      [2, '嫌疑人改成翻页卡片:一次一张(照片 + 头像字 + 名字 + 身份 + 证词),点右下箭头或「再想想」翻到下一张(绕竖轴翻页),底部「指认 X」跟着当前这张变。'],
      [3, '结果整屏:正常结案 / 指认失败 / 结算确认中,图标换成统一 3D 套(礼花 / 撞击 / 侦探),等待两秒后自动转结果。指认守夜人看成功,古董店老板看失败,报童看等待。']]));

  // ===== 分支 / 延伸任务 / 提示 =====
  H.add('gp-branch', '分支任务 · 选择弹窗', 'B · 游玩与阶段弹窗', 'cyp gm', '<div class="gm-tex"></div>' + top(THEME, { right: S.more, rightAttr: 'data-sheet="sh-menu"' }) + hud({ hp: .6, hpN: 3, bag: 2, st: 2, of: 7 }) + storyBody({ id: 'storyBr', kit: false }) + branchPop(1) + extPop() + kitStartPop() + menuSheet(),
    'pages/play/components/playkit-branch', H.notes([[1, '上一轮通过;只换了 3D 图标。']]));
  H.add('gp-ext', '延伸任务 · 翻页卡', 'B · 游玩与阶段弹窗', 'cyp gm', '<div class="gm-tex"></div>' + top(THEME, { right: S.more, rightAttr: 'data-sheet="sh-menu"' }) + hud({ hp: .6, hpN: 3, bag: 2, st: 2, of: 7 }) + storyBody({ id: 'storyExt', kit: false }) + extPop(1) + branchPop() + kitStartPop() + menuSheet(),
    'pages/play/index.wxml(延伸任务弹层)', H.notes([[1, '三张任务卡不再横滑,是翻页:一次一张(图 + 说明 + 距离 / 奖励 / 时限 + 去做),点卡片或右下箭头,这张绕竖轴翻过去、下一张翻进来;圆点显示第几张。']]));
  H.add('gp-hint', '提示弹窗(已通过)', 'B · 游玩与阶段弹窗', 'cyp gm', '<div class="gm-tex"></div>' + top(THEME, { right: S.more, rightAttr: 'data-sheet="sh-menu"' }) + hud({ hp: .6, hpN: 3, bag: 2, st: 1, of: 7 }) + '<div class="gm-body" id="hintBodyScr"><div class="gk">第一章 · 邮局旧址</div>' + fx('柜台后的老人把第二封信推过来。') + qa.replace(/id="qaBlock"/, 'id="qaBlock2"').replace(/ data-mk="\d"/g, '').replace('id="imgopts"', '').replace('id="qaErr"', '') + '</div>' + hintPop(1) + kitEndPop(true) + kitEndPop(false) + menuSheet(),
    'pages/play/index.wxml(tb-hint)', H.notes([[1, '上一轮通过,未动。']]));

  // ===== 背包页(通过,未动;图标换 3D) =====
  H.add('gp-backpack', '背包(新页面)', 'B · 游玩与阶段弹窗', 'cyp gm',
    '<div class="gm-tex"></div>' + top('背包', { backGo: 'gp-story' }) +
    '<div class="gm-body gm-body--nohud"><div class="gk">背包装载</div><div style="display:flex;align-items:baseline;gap:6px;margin-top:6px"><span class="gnum" style="font-size:34px">4</span><span class="gk">/ 12 格</span></div>' +
    '<div class="bagcap">' + Array.from({ length: 12 }, function (_, i) { return '<i class="' + (i < 4 ? 'used' : '') + '"></i>'; }).join('') + '</div>' +
    '<div class="gseg"><span class="it on" data-tab="bag:items" data-on-class="on">物品</span><span class="it" data-tab="bag:clues" data-on-class="on">线索</span><span class="it" data-tab="bag:tasks" data-on-class="on">任务</span></div>' +
    '<div data-pane="bag:items"><div class="bag" data-mk="1"><div class="bagi"><img src="' + img('old-movie') + '"><span class="ov">黄铜钥匙</span><span class="n" style="color:#fff">×1</span></div><div class="bagi">' + I('stamp', { size: 72 }) + '<b>邮戳 · 1924</b><span class="n">×2</span></div><div class="bagi">' + I('envelope', { size: 72 }) + '<b>第二封信</b><span class="n">×1</span></div><div class="bagi empty"></div><div class="bagi empty"></div><div class="bagi empty"></div></div></div>' +
    '<div data-pane="bag:clues" hidden><div class="bag"><div class="bagi">' + I('clue', { size: 72 }) + '<b>侧门没有锁</b></div><div class="bagi">' + I('scroll', { size: 72 }) + '<b>账本少一页</b></div><div class="bagi empty"></div><div class="bagi empty"></div></div></div>' +
    '<div data-pane="bag:tasks" hidden><div class="bag"><div class="bagi">' + I('bookmark', { size: 72 }) + '<b>地铁站的柱子</b></div><div class="bagi empty"></div></div></div><div style="height:60px"></div></div>',
    'pages/play/utils/theme-backpack.js', H.notes([[1, '上一轮通过;物品图标换成统一 3D 套。']]));

  // ===== 入场码:动态翻面(来源:subpackageMember/signup 票夹 .wc__flip)+ 更大 =====
  H.add('gp-qr', '入场码 · 翻面 · 更大', 'B · 游玩与阶段弹窗', 'cyp gm',
    '<div class="gm-tex"></div>' + top(THEME, { right: S.more, rightAttr: 'data-sheet="sh-menu"' }) + hud({ hp: .6, hpN: 3, bag: 2, st: 1, of: 7 }) + storyBody({ id: 'storyQr', kit: false }) +
    sheet('sh-qr', { title: '入场码', detent: 'full', mk: 1, body: '<div class="qrflip" id="qrflip" data-act="qrFlip"><div class="qrflip__in"><div class="face front"><img src="' + img('treasure-hunter') + '"><div class="cap2"><div class="gk" style="color:rgba(255,255,255,.75)">城市定向 · 外滩源</div><b>' + THEME + '</b><span>11 月 12 日 09:30 · 到 11 月 30 日</span></div><span class="tapflip">点一下翻面</span></div><div class="face back"><div class="qrbig qrbig--xl">' + H.qr('cy-entry-7721') + '</div><div class="gk" style="text-align:center;margin-top:10px">把码给工作人员扫 · 点一下翻回</div></div></div></div>' }) + menuSheet() + extPop() + branchPop() + kitStartPop(),
    '来源:subpackageMember/signup(票夹 .wc / .wc__flip 翻面)', H.notes([[1, '入场码回到之前做过的动态效果:一张票卡,打开后自动绕竖轴翻面,背面是入场码;码块 300×300 白底,比上一版再大一圈;点一下翻回正面。来源:票夹 deck 的翻面(.wc__flip)。']]));

  // ===== 章节结束 · 整屏 + 成功动画 + 粉尘消融 =====
  function chapterScreen(n, title, sub, next) {
    return '<div class="chfull" id="chfull' + n + '"' + (n === 2 ? ' style="visibility:hidden"' : '') + '><img class="chfull__img" src="' + img(n === 1 ? 'treasure-hunter' : 'time-pollution') + '"><div class="chfull__fade"></div>' +
      '<div class="chfull__body"><div class="chfull__ok">' + (n === 1 ? I('success', { size: 120, glow: true, d: .4 }) + I('sparkles', { size: 44, cls: 'spk a', d: .9 }) + I('sparkles', { size: 32, cls: 'spk b', d: 1.1 }) : '') + '</div>' +
      '<div class="gk">' + (n === 1 ? '第一章完成' : '第二章') + '</div><div class="gh1">' + title + '</div><div class="gp">' + sub + '</div>' +
      (n === 1 ? '<div class="chstats"><div><b id="chTime" data-from="0">00:00</b><span>用时</span></div><div><b id="chScore" data-from="0">0</b><span>本章得分</span></div><div><b>2 / 3</b><span>线索</span></div></div>' : '') + '</div>' +
      foot(n === 1 ? gb('下一站:老书店', { act: 'dissolve', id: 'dissolveBtn' }) : gb('继续', { act: 'txgo', data: 'data-to="gp-story"' })) + '</div>';
  }
  H.add('gp-chapter-end', '章节结束 · 整屏 · 粉尘消融', 'B · 游玩与阶段弹窗', 'cyp gm',
    '<div class="gm-tex"></div><canvas class="tiles-cv" id="tilesEnd" width="780" height="1688"></canvas>' + top(THEME, { right: S.more, rightAttr: 'data-sheet="sh-menu"' }) +
    chapterScreen(1, '邮局旧址', '三个地点都到过了') .replace('class="chfull"', 'class="chfull" data-mk="1"') + chapterScreen(2, '老书店', '两个地点 · 约 500 米') +
    '<canvas class="dis-cv" id="disCv" width="780" height="1688" data-mk="2"></canvas>' + menuSheet(),
    '来源:KKQJxL 定稿「第 N 章完成」整屏(方块数字 + 数据逐字解码)+ refs2/chapter-end-dissolve', H.notes([
      [1, '章节完成改回整屏(来源:KKQJxL 的 tiles 步骤):上面是本章照片渐入底色,星点锁成方块数字「01」,中间一个成功动画(3D 礼花 + 两点闪光弹出),「第一章完成 · 邮局旧址」,三格数据滚上来,底部「下一站」。没有卡片。'],
      [2, '点「下一站」:整屏照片和文字从右上角开始化成细粉尘(每粒 1–2px,带湍流,像被风吹散),不是方块;同时方块数字炸散回星点;下一章的整屏跟着浮现,星点再锁成「02」。参考 refs2/chapter-end-dissolve-frames.jpg。']]));

  // ===== 本局完成(绿;图标换 3D 勋章) =====
  H.add('gp-run-end', '本局完成 · 绿 · 动画奖励', 'B · 游玩与阶段弹窗', 'cyp gm runend',
    '<div class="gm-tex"></div><div class="glow"></div><div class="conf" id="conf"></div>' + top('', { back: false, right: S.share, rightAttr: 'data-go="st-share"' }) +
    '<div class="reward" data-mk="1"><div style="position:relative">' + I('badge', { size: 170, glow: true, d: .4 }) + I('sparkles', { size: 40, cls: 'spk a', d: 1 }) + I('sparkles', { size: 28, cls: 'spk b', d: 1.2 }) + '<span class="ring"></span></div><div class="gk rk">本局完成 · 获得勋章</div><div class="gh1 rt" style="margin:6px 0">邮差的钥匙</div><div class="gp rs">' + THEME + ' · 7 / 7 站 · 58 分钟</div><span class="rpts">' + I('points', { size: 22 }) + '+<b id="rePts" data-from="0">0</b> 积分</span></div>' +
    foot(gb('看结算', { go: 'st-overview' }) + gb('完成', { kind: 'text', toast: '回到票夹' })),
    'pages/play/index.wxml(.ending)', H.notes([[1, '结构上一轮通过;勋章换成统一 3D 套(落下 + 轻摆 + 光晕 + 闪光),一次彩纸。']]));

  H.flush();

  // ===== 像素方块数字(来源:KKQJxL tiles:sample → assemble → scatter) =====
  var TILES = {};
  function tilesStart(cv, txt, color, topY) {
    if (!cv) return; tilesStop(cv);
    var cx = cv.getContext('2d'), PW = 390, PH = 844; cx.setTransform(2, 0, 0, 2, 0, 0);
    var ink = getComputedStyle(cv.closest('.scr')).color, acc = color || ink;
    var st = { cv: cv, cx: cx, stars: [], tiles: [], raf: 0, shapeT: 0, squash: 0, ink: ink, acc: acc, shineT: -1e9 };
    for (var i = 0; i < 130; i++) st.stars.push({ x: Math.random() * PW, y: 60 + Math.random() * (PH - 120), r: .5 + Math.random() * 1.1, p: Math.random() * 6.28, s: .6 + Math.random() * 1.6 });
    TILES[cv.id] = st;
    function sample(t) { var cell = 9, W = 330, Hh = 250, TOP = topY || 150, o = document.createElement('canvas'); o.width = W; o.height = Hh; var g = o.getContext('2d'); g.fillStyle = '#fff'; g.font = '800 238px -apple-system,"Helvetica Neue",Arial,sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(t, W / 2, Hh / 2 + 10); var d = g.getImageData(0, 0, W, Hh).data, pts = []; for (var y = 0; y < Hh; y += cell) for (var x = 0; x < W; x += cell) { if (d[((y + cell / 2 | 0) * W + (x + cell / 2 | 0)) * 4 + 3] > 140) pts.push({ x: (PW - W) / 2 + x, y: TOP + y, row: y / Hh }); } return pts; }
    st.assemble = function (t) { var pts = sample(t); while (st.tiles.length < pts.length) { var s0 = st.stars[Math.random() * st.stars.length | 0]; st.tiles.push({ x: s0.x, y: s0.y, vx: 0, vy: 0, sz: 1.4 }); } st.tiles.length = pts.length;
      st.tiles.forEach(function (tl, i) { var p = pts[i]; tl.loose = 0; tl.tx = p.x; tl.ty = p.y; tl.tsz = 7.4; tl.shade = 1 - p.row * .22 + (Math.random() - .5) * .12; tl.delay = Math.random() * 380 + Math.abs(p.x - PW / 2) * .9; tl.born = performance.now(); }); st.shapeT = performance.now(); st.squash = 1; st.shineT = performance.now() + 1300; };
    st.scatter = function () { st.tiles.forEach(function (tl) { var a = Math.atan2(tl.y - 280, tl.x - PW / 2) + (Math.random() - .5) * .9, sp = 6 + Math.random() * 9; tl.vx = Math.cos(a) * sp; tl.vy = Math.sin(a) * sp - 3; tl.tx = Math.random() * PW; tl.ty = 60 + Math.random() * (PH - 120); tl.tsz = 1.2; tl.delay = 0; tl.born = performance.now(); tl.loose = 1; }); };
    function tick(now) {
      if (!cv.isConnected || TILES[cv.id] !== st) return;
      cx.clearRect(0, 0, PW, PH);
      st.stars.forEach(function (s0) { cx.globalAlpha = .18 + .32 * (.5 + .5 * Math.sin(now / 1000 * s0.s + s0.p)); cx.fillStyle = ink; cx.fillRect(s0.x, s0.y, s0.r, s0.r); });
      var sq = st.squash > 0 ? Math.sin(Math.min(1, (now - st.shapeT) / 900) * Math.PI) * .05 * st.squash : 0, band = (now - st.shineT) * .5 - 40;
      st.tiles.forEach(function (t) {
        if (now - t.born < t.delay) { cx.globalAlpha = .4; cx.fillStyle = ink; cx.fillRect(t.x, t.y, t.sz, t.sz); return; }
        if (G.reduce) { t.x = t.tx; t.y = t.ty; t.sz = t.tsz; } else { var ax = (t.tx - t.x) * .045, ay = (t.ty - t.y) * .045; t.vx = (t.vx + ax) * (t.loose ? .93 : .82); t.vy = (t.vy + ay) * (t.loose ? .93 : .82); t.x += t.vx; t.y += t.vy; t.sz += (t.tsz - t.sz) * .12; }
        var y = t.y + (t.loose ? 0 : (t.y - 400) * sq), lit = !t.loose && Math.abs((t.x - 60) + (t.y - 150) * .6 - band) < 26;
        cx.globalAlpha = t.loose ? Math.max(.35, Math.min(1, t.sz / 3)) : 1; cx.fillStyle = t.loose ? ink : (lit ? '#fff' : acc);
        if (!t.loose && !lit) { cx.globalAlpha = Math.min(1, t.shade); }
        cx.fillRect(t.x - t.sz / 2, y - t.sz / 2, t.sz, t.sz);
        if (!t.loose && t.sz > 4) { cx.globalAlpha = .22; cx.fillStyle = '#fff'; cx.fillRect(t.x - t.sz / 2, y - t.sz / 2, t.sz, 1); }
      });
      if (band > 520) st.shineT = now + 900;
      cx.globalAlpha = 1; st.raf = requestAnimationFrame(tick);
    }
    st.raf = requestAnimationFrame(tick);
    st.assemble(txt);
    return st;
  }
  function tilesStop(cv) { var st = TILES[cv.id]; if (st) { cancelAnimationFrame(st.raf); delete TILES[cv.id]; } }

  // ===== 粉尘消融(参考 refs2:细粉尘、带湍流、往右上飘散) =====
  function dustDissolve(scr, img0, rectEl, done) {
    var cv = scr.querySelector('#disCv'), c = cv.getContext('2d'); c.setTransform(2, 0, 0, 2, 0, 0); c.clearRect(0, 0, 390, 844);
    var sr = scr.getBoundingClientRect(), k = 390 / sr.width, r = rectEl.getBoundingClientRect(), x0 = (r.left - sr.left) * k, y0 = (r.top - sr.top) * k, w = r.width * k, h = r.height * k;
    var cols = 130, rows = Math.round(cols * h / w), off = document.createElement('canvas'); off.width = cols; off.height = rows; var oc = off.getContext('2d');
    try { oc.drawImage(img0, 0, 0, cols, rows); } catch (e) { oc.fillStyle = '#888'; oc.fillRect(0, 0, cols, rows); }
    var px = oc.getImageData(0, 0, cols, rows).data, P = [], cw = w / cols, chh = h / rows;
    for (var j = 0; j < rows; j++) for (var i = 0; i < cols; i++) { if (Math.random() < .35) continue; var q = (j * cols + i) * 4; P.push({ x: x0 + i * cw + Math.random() * cw, y: y0 + j * chh + Math.random() * chh, col: 'rgb(' + px[q] + ',' + px[q + 1] + ',' + px[q + 2] + ')', seed: Math.random() * 6.28, sp: .6 + Math.random() * 1.4, d: (1 - i / cols) * 420 + (j / rows) * 300 + Math.random() * 160, s: .9 + Math.random() * 1.3 }); }
    var t0 = performance.now(), D = 1300;
    (function f(now) { var el = now - t0, alive = false; c.clearRect(0, 0, 390, 844);
      for (var n = 0; n < P.length; n++) { var p = P[n], t = (el - p.d) / D; if (t < 0) { c.globalAlpha = 1; c.fillStyle = p.col; c.fillRect(p.x, p.y, p.s, p.s); alive = true; continue; } if (t > 1) continue; alive = true;
        var e = 1 - Math.pow(1 - t, 2), wob = Math.sin(p.seed + el / 180) * 6 * t, dx = (26 + p.sp * 60) * e + wob, dy = -(14 + p.sp * 40) * e + Math.cos(p.seed * 2 + el / 220) * 5 * t;
        c.globalAlpha = Math.max(0, 1 - t * 1.05); c.fillStyle = p.col; var s = p.s * (1 - t * .5); c.fillRect(p.x + dx, p.y + dy, s, s); }
      c.globalAlpha = 1; if (alive && cv.isConnected) requestAnimationFrame(f); else { c.clearRect(0, 0, 390, 844); done && done(); } })(t0);
  }

  // ===== 翻页卡组 =====
  function flipNext(deckId) {
    var deck = PROTO.cur().querySelector('#' + deckId); if (!deck || deck.dataset.busy) return;
    var cards = [].slice.call(deck.querySelectorAll('.fcard')), i = cards.findIndex(function (c) { return c.classList.contains('on'); }), nx = (i + 1) % cards.length;
    deck.dataset.busy = '1'; cards[i].classList.add('out'); cards[nx].classList.add('in');
    setTimeout(function () { cards[i].classList.remove('on', 'out'); cards[nx].classList.add('on'); cards[nx].classList.remove('in'); delete deck.dataset.busy; }, G.reduce ? 10 : 520);
    var dots = PROTO.cur().querySelector('#' + deckId.replace('deck', 'dots')); if (dots) [].forEach.call(dots.children, function (d, k) { d.classList.toggle('on', k === nx); });
    if (deckId === 'susdeck') { var b = PROTO.cur().querySelector('#accuseBtn'); b.textContent = '指认 ' + SUS[nx][1]; b.setAttribute('data-i', nx); }
    requestAnimationFrame(PROTO.placeMarks);
  }

  var kitMode = 'embed';
  function showErr(id, on) { var e = document.getElementById(id); if (!e) return; e.classList.toggle('show', !!on); }
  function confetti(root) {
    var c = root.querySelector('#conf'); if (!c) return; c.innerHTML = ''; if (G.reduce) return;
    var cols = ['var(--lk-a)', 'var(--lk-ink)', 'var(--lk-ok)', 'var(--lk-b)'];
    for (var i = 0; i < 26; i++) { var s = document.createElement('i'); s.style.cssText = '--x:' + (Math.random() * 100).toFixed(1) + '%;--c:' + cols[i % 4] + ';--d:' + (0.9 + Math.random() * 1.2).toFixed(2) + 's;transform:rotate(' + (Math.random() * 180) + 'deg)'; c.appendChild(s); }
  }
  function runVerdict(kind) {
    var v = document.getElementById('verdict'), s = PROTO.cur();
    v.className = 'verdict open' + (kind === 'fail' ? ' bad' : '');
    v.querySelector('#vIcon').innerHTML = I(kind === 'fail' ? 'fail' : kind === 'wait' ? 'wait' : 'success', { size: 150, glow: kind === 'ok' }) + (kind === 'ok' ? I('sparkles', { size: 44, cls: 'spk a', d: .6 }) + I('sparkles', { size: 30, cls: 'spk b', d: .9 }) : '');
    v.querySelector('#vT').textContent = kind === 'fail' ? '指认失败' : kind === 'wait' ? '结算确认中' : '正常结案';
    v.querySelector('#vS').textContent = kind === 'fail' ? '他趁夜色离开了小城。账本上那一页,再也找不回来。' : kind === 'wait' ? '主持人在核对证词' : '钥匙就在守夜人的值班室里。钟楼的秘密到此揭开。';
    v.querySelector('#vD').textContent = kind === 'fail' ? '体力 −1' : kind === 'wait' ? '' : '+40 分 · 线索 +1';
    v.querySelector('.gm-foot').style.visibility = kind === 'wait' ? 'hidden' : 'visible';
    if (kind === 'wait') setTimeout(function () { if (v.classList.contains('open') && PROTO.cur() === s) runVerdict('ok'); }, 2600);
  }
  function syncHang(val) {
    var tiles = [].slice.call(document.querySelectorAll('#tiles .tile:not(.pre)')), v = (val || '').toUpperCase().replace(/[^A-Z]/g, '').slice(0, 5);
    tiles.forEach(function (t, i) { var ch = v[i] || ''; if (t.textContent !== ch) { t.textContent = ch; t.classList.remove('fill'); if (ch) { void t.offsetWidth; t.classList.add('fill'); } } t.classList.toggle('cur', i === v.length); });
    document.getElementById('hangSubmit').classList.toggle('is-off', v.length < 5); showErr('hangErr', false);
  }
  Object.assign(window.ACT, {
    noop: function () {},
    kmode: function (el) { kitMode = el.getAttribute('data-m'); [].forEach.call(el.parentNode.children, function (x) { x.classList.toggle('on', x === el); }); },
    kitGo: function (el) { el.closest('.gm-pop').classList.remove('open'); var to = kitMode === 'embed' ? 'gp-kit-embed' : 'gp-kit-accuse'; G.tx(function () { PROTO.show(to, true); }); },
    arrive: function (el) { el.textContent = '已到达'; el.classList.add('gb--ok'); setTimeout(function () { PROTO.toast('第 1 站 · 已到达'); PROTO.show('gp-story', true); }, 700); },
    qaPick: function (el) {
      var box = el.parentNode, k = el.getAttribute('data-k'); [].forEach.call(box.children, function (x) { x.classList.remove('sel', 'ok', 'no'); x.classList.toggle('dim', x !== el); }); el.classList.add('sel');
      var err = box.parentNode.querySelector('.gerr');
      setTimeout(function () {
        if (k === 'A') { el.classList.add('ok'); if (err) err.classList.remove('show'); setTimeout(function () { G.openPop('pop-kend-ok'); }, 500); }
        else { el.classList.add('no'); if (err) err.classList.add('show'); setTimeout(function () { G.openPop('pop-kend-no'); }, 900); setTimeout(function () { [].forEach.call(box.children, function (x) { x.classList.remove('dim', 'no', 'sel'); }); }, 2400); }
      }, 350);
    },
    closeThenHint: function (el) { G.closePop(el); setTimeout(function () { G.openPop('pop-hint'); }, 120); },
    hintText: function (el) { var b = el.parentNode.querySelector('#hintBody'); b.hidden = !b.hidden; el.querySelector('.v').textContent = b.hidden ? '免费' : '已用 · 剩 0 次'; },
    hangSubmit: function () {
      var inp = document.getElementById('hangIn'), tiles = [].slice.call(document.querySelectorAll('#tiles .tile')), w = tiles.map(function (t) { return t.textContent; }).join('');
      if (w === WORD) { tiles.forEach(function (t, i) { setTimeout(function () { t.classList.add('ok'); }, i * 70); }); setTimeout(function () { G.openPop('pop-kend-ok'); }, 700); }
      else { showErr('hangErr', true); inp.value = ''; syncHang(''); }
    },
    flipNext: function (el) { flipNext(el.getAttribute('data-deck')); },
    accuse: function (el) { var i = +el.getAttribute('data-i'); document.getElementById('accAv').textContent = SUS[i][0]; document.getElementById('accT').textContent = '这是最终指认:' + SUS[i][1]; document.getElementById('accS').textContent = SUS[i][2] + ' · 指认后不能更改'; G.openPop('pop-accuse'); },
    accuseConfirm: function (el) { G.closePop(el); var i = +document.getElementById('accuseBtn').getAttribute('data-i'); runVerdict(SUS[i][4] === 'right' ? 'ok' : SUS[i][4] === 'wait' ? 'wait' : 'fail'); },
    verdictClose: function () { document.getElementById('verdict').classList.remove('open'); PROTO.show('gp-story', true); },
    branchPick: function (el) { var i = +el.getAttribute('data-i'); [].forEach.call(el.parentNode.children, function (x) { x.classList.toggle('sel', x === el); x.classList.toggle('dim', x !== el); }); var n = el.closest('.card').querySelector('#brNote'); n.textContent = ['直接去钟楼:体力 −1,十一点前能到。', '找报童:多花十分钟,但他会给你一条线索。', '翻账本:+15 分,钟楼那边的事就只能听别人说了。'][i]; n.classList.add('show'); var g = el.closest('.card').querySelector('#brGo'); g.classList.remove('is-off'); g.setAttribute('data-i', i); },
    branchGo: function (el) { var i = +el.getAttribute('data-i'); G.closePop(el); var s = PROTO.cur(), hp = s.querySelector('.hud-hp'); if (i === 0 && hp) { hp.querySelector('.hp').style.setProperty('--p', .4); hp.querySelector('b').textContent = '2'; PROTO.toast('体力 −1 · 往钟楼去'); } else PROTO.toast(i === 1 ? '线索 +1 · 时间 +10 分' : '积分 +15'); },
    qrFlip: function (el) { el.classList.toggle('flipped'); },
    dissolve: function () {
      var s = PROTO.cur(), a = s.querySelector('#chfull1'), b = s.querySelector('#chfull2'), st = TILES.tilesEnd;
      if (st) st.scatter();
      if (G.reduce) { a.style.visibility = 'hidden'; b.style.visibility = 'visible'; if (st) setTimeout(function () { st.assemble('02'); }, 100); return; }
      var body = a.querySelector('.chfull__body'), ft = a.querySelector('.gm-foot'); body.classList.add('blowaway'); ft.classList.add('blowaway');
      dustDissolve(s, a.querySelector('.chfull__img'), a.querySelector('.chfull__img'), null);
      setTimeout(function () { a.querySelector('.chfull__img').style.visibility = 'hidden'; }, 60);
      setTimeout(function () { a.style.visibility = 'hidden'; b.style.visibility = 'visible'; b.classList.add('arrive'); if (st) st.assemble('02'); }, 1100);
    },
  });
  document.addEventListener('input', function (e) { if (e.target && e.target.id === 'hangIn') syncHang(e.target.value); });
  G.on['gp-story'] = function (s) { G.playFx(s.querySelector('#storyBody')); };
  G.on['gp-kit-embed'] = function (s) { G.playFx(s.querySelector('#embedBody')); };
  G.on['gp-kit-full'] = function (s) { var i = s.querySelector('#hangIn'); i.value = ''; syncHang(''); };
  G.on['gp-kit-accuse'] = function (s) { var cards = s.querySelectorAll('#susdeck .fcard'); [].forEach.call(cards, function (c, i) { c.className = 'fcard' + (i ? '' : ' on'); }); [].forEach.call(s.querySelectorAll('#susdots i'), function (d, i) { d.classList.toggle('on', !i); }); var b = s.querySelector('#accuseBtn'); b.textContent = '指认 ' + SUS[0][1]; b.setAttribute('data-i', 0); };
  G.on['gp-branch'] = function (s) { G.playFx(s.querySelector('#storyBr')); setTimeout(function () { G.openPop('pop-branch'); }, 300); };
  G.on['gp-ext'] = function (s) { G.playFx(s.querySelector('#storyExt')); setTimeout(function () { G.openPop('pop-ext'); }, 300); };
  G.on['gp-hint'] = function (s) { G.playFx(s.querySelector('#hintBodyScr')); setTimeout(function () { G.openPop('pop-hint'); }, 300); };
  G.on['gp-qr'] = function (s) { G.playFx(s.querySelector('#storyQr')); var f = s.querySelector('#qrflip'); f.classList.remove('flipped'); setTimeout(function () { PROTO.openSheet('sh-qr', 'full'); }, 250); setTimeout(function () { f.classList.add('flipped'); }, 1100); };
  G.on['gp-chapter-start'] = function (s) { tilesStart(s.querySelector('#tilesStart'), '01', getComputedStyle(s).getPropertyValue('--lk-a').trim() || null); };
  G.on['gp-chapter-end'] = function (s) {
    var a = s.querySelector('#chfull1'), b = s.querySelector('#chfull2'); a.style.visibility = 'visible'; a.querySelector('.chfull__img').style.visibility = ''; a.querySelector('.chfull__body').classList.remove('blowaway'); a.querySelector('.gm-foot').classList.remove('blowaway'); b.style.visibility = 'hidden'; b.classList.remove('arrive');
    tilesStart(s.querySelector('#tilesEnd'), '01', getComputedStyle(s).getPropertyValue('--lk-a').trim() || null, 96);
    setTimeout(function () { G.countUp(s.querySelector('#chTime'), 754, 900, function (v) { v = Math.round(v); return String(Math.floor(v / 60)).padStart(2, '0') + ':' + String(v % 60).padStart(2, '0'); }); G.countUp(s.querySelector('#chScore'), 180, 900); }, 600);
  };
  G.on['gp-run-end'] = function (s) { confetti(s); setTimeout(function () { G.countUp(s.querySelector('#rePts'), 120, 700); }, 2000); };
  // 离开章节屏时停掉画布
  var prevEnter = window.ON_ENTER; window.ON_ENTER = function (id) { if (prevEnter) prevEnter(id); Object.keys(TILES).forEach(function (k) { var st = TILES[k]; if (!st.cv.closest('.scr').classList.contains('on')) tilesStop(st.cv); }); };
})();
