// v2 · 第三组:主题购买(详情 → 现有购买弹层 → 确认订单)、通行证(我的会员)、结算四步(主题之外,纯黑底)、
// Spotify 式邀请页、票夹、收集册。每屏 notes 写明来源。
(function () {
  var S = H.SVG, I = ICONS.img, gb = H.gb, top = H.top, foot = H.foot, pop = H.pop, img = H.img, esc = H.esc;
  var THEME = '老城三封信';
  function ptop(title, o) { o = o || {}; return '<div class="topbar">' + (o.back === false ? '' : '<span class="bk" ' + (o.backGo ? 'data-go="' + o.backGo + '"' : 'data-back') + '>' + S.back + '</span>') + (title ? '<span class="tt">' + esc(title) + '</span>' : '') + (o.right ? '<span class="ra" ' + (o.rightAttr || '') + '>' + o.right + '</span>' : '') + (o.txt ? '<span class="txt" ' + (o.txtAttr || '') + '>' + o.txt + '</span>' : '') + '</div>'; }
  function pbtn(label, o) { o = o || {}; var a = ' class="pbtn' + (o.kind ? ' pbtn--' + o.kind : '') + (o.cls ? ' ' + o.cls : '') + '"'; if (o.go) a += ' data-go="' + o.go + '"'; if (o.sheet) a += ' data-sheet="' + o.sheet + '"' + (o.detent ? ' data-detent="' + o.detent + '"' : ''); if (o.act) a += ' data-act="' + o.act + '"'; if (o.toast) a += ' data-toast="' + esc(o.toast) + '"'; if (o.close) a += ' data-close'; if (o.mk) a += ' data-mk="' + o.mk + '"'; if (o.id) a += ' id="' + o.id + '"'; if (o.data) a += ' ' + o.data; return '<div' + a + '>' + label + '</div>'; }
  function psheet(id, o) { o = o || {}; return '<div class="ios-sheet" id="' + id + '" data-detent="' + (o.detent || 'half') + '"><div class="mask"></div><div class="panel"' + (o.mk ? ' data-mk="' + o.mk + '"' : '') + '><div class="grab"></div><div class="gsheet"><div class="gsheet__h"><span class="gsheet__t" style="font:800 21px -apple-system,sans-serif">' + (o.title || '') + '</span><span class="x" data-close style="background:var(--cy-color-bg-surface-strong);color:var(--cy-color-text-primary)">' + S.x + '</span></div><div class="gsheet__b">' + (o.body || '') + '</div>' + (o.foot ? '<div class="gsheet__f" style="background:var(--cy-color-bg-elevated)">' + o.foot + '</div>' : '') + '</div></div></div>'; }
  var COVERS = ['treasure-hunter', 'old-movie', 'time-pollution', 'flavor-tour', 'apocalypse-broadcast', 'midlife-day', 'prebuilt-life', 'life-backup', 'heat-escape', 'anomaly-coordinate', 'wildlife-observation', 'treasure-hunter'];
  var LIB = [['老城三封信', '城市定向 · 3 章'], ['夜航灯', '城市定向 · 2 章'], ['弄堂三封电报', '自由探索 · 4 站'], ['老街灯笼铺', '自由探索 · 5 店'], ['北外滩夜巡', '城市定向 · 3 章'], ['玻璃图书馆', '城市定向 · 2 章'], ['地铁站的柱子', '自由探索 · 3 站'], ['洗衣店暗号', '自由探索 · 2 店'], ['大剧院回廊', '城市定向 · 2 章'], ['博物馆的坐标', '城市定向 · 3 章'], ['湿地观鸟', '自由探索 · 4 站'], ['邮政大楼夜场', '城市定向 · 1 章']];

  // ===== C1 · 主题详情 + 现有购买弹层(来源 pages/topic/index/index.wxml .bsheet) =====
  var WIC = { one: '<svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.5"/><path d="M5 20c1-4 4-6 7-6s6 2 7 6"/></svg>', two: '<svg viewBox="0 0 24 24"><circle cx="9" cy="8" r="3"/><circle cx="16" cy="9" r="2.6"/><path d="M3 20c1-3.5 3.3-5.5 6-5.5s5 2 6 5.5M15 14.6c2.6 0 4.5 1.8 5.4 5"/></svg>', team: '<svg viewBox="0 0 24 24"><circle cx="7" cy="8" r="2.5"/><circle cx="17" cy="8" r="2.5"/><circle cx="12" cy="6" r="2.5"/><path d="M2.5 19c.8-3 2.4-4.5 4.5-4.5M21.5 19c-.8-3-2.4-4.5-4.5-4.5M7 20c.8-3.5 2.6-5.5 5-5.5s4.2 2 5 5.5"/></svg>' };
  var OPTS = [['one', '单人票', '¥68', 68, 1], ['two', '双人票', '¥120', 120, 2], ['team', '团队票', '¥240', 240, 4]];
  function buySheet(mk) {
    return psheet('sh-buy', { title: THEME + ' · 城市定向门票', detent: 'full', mk: mk, body:
      '<div class="sub" style="margin:-6px 0 0">可玩至 11 月 30 日,不用选场次</div>' +
      '<div class="bsheet"><div class="hticket"><img src="' + img('treasure-hunter') + '"><div class="hticket__in"><span class="hticket__k">城瘾 · 主题通行证</span><b class="hticket__b">' + THEME + '</b><span class="hticket__p" id="buyPr">¥68</span><span class="hticket__u" id="buyU">单人票</span><span class="hticket__holo"></span></div><span class="hticket__n hticket__n--t"></span><span class="hticket__n hticket__n--b"></span></div>' +
      '<div class="bseg" id="bseg">' + OPTS.map(function (o, i) { return '<div class="bseg__b' + (i ? '' : ' on') + '" data-act="buySel" data-i="' + i + '"><span class="bseg__ic">' + WIC[o[0]] + '</span><span class="bseg__t">' + o[1] + '</span><span class="bseg__s">' + o[2] + '</span></div>'; }).join('') + '<span class="bseg__u" id="bsegU"></span></div>' +
      '<div class="bchips"><span class="bchip">可玩至 11/30</span><span class="bchip">3 章 · 7 地点</span><span class="bchip">可分几天玩完</span><span class="bchip" id="buySeats">1 人 · 1 席位</span></div>' +
      '<div class="bsheet__note">期间可随时开玩本路线。请遵守安全须知,注意交通与人身安全。</div>' +
      '<div class="btotal"><span class="btotal__k">合计</span><span class="btotal__v" id="buyTot">¥68</span></div></div>',
      foot: pbtn('去结算', { go: 'ck-checkout' }) });
  }
  var detail = '<div class="sv" style="padding:0 0 120px"><div class="tpd"><div class="hero"><img src="' + img('treasure-hunter') + '"><div class="hero-text"><div class="k">城市定向 · 外滩源 · 3 章 · 7 地点 · 约 60 分钟</div><div class="t">' + THEME + '</div><div class="pills"><span class="pchip">可分几天玩完</span><span class="pchip">4.8 · 126 评价</span></div></div></div>' +
    '<div class="hero-cta" data-sheet="sh-buy" data-detent="full" data-mk="1">¥68 随时开玩</div>' +
    '<div style="padding:18px 16px 0"><div class="sec">故事</div><div class="pc"><div style="font-size:15px;line-height:1.7;color:var(--cy-color-text-secondary)">一封四十年前寄出的信,昨天才送到。寄信人没有留名字,只画了一座停在十一点的钟。三章、七个地点,从邮政大楼走到老书店。</div></div>' +
    '<div class="sec">地点</div><div class="pc">' + ['邮政大楼 · 北苏州路 250 号', '老书店 · 山西北路', '钟楼 · 外滩源'].map(function (t, i) { return '<div class="r"><span><b style="margin-right:8px">' + (i + 1) + '</b>' + t + '</span><span class="v">' + ['120 米', '400 米', '650 米'][i] + '</span></div>'; }).join('') + '</div></div></div></div>' +
    '<div class="foot">' + pbtn('¥68 随时开玩', { sheet: 'sh-buy', detent: 'full' }) + '</div>';
  H.add('tp-buy', '现有主题详情页 · 购买弹层(不重做)', 'C · 购买 · 通行证', 'cyp pl theme-dark', ptop('') + detail.replace(' data-mk="1"', '') + buySheet(1),
    '来源:pages/topic/index/index.wxml(.hero-cta → .bsheet / .hticket / .bseg,原样)', H.notes([[1, '主题详情页是现有的,这里只是示意底图,不重做。购买弹层按之前做好的那版:标题改成「老城三封信 · 城市定向门票」,副题「可玩至 … 不用选场次」,全息票卡,票种分段(单人 / 双人 / 团队 = 人数)用原来的白色线图标,四个标签片,须知,合计,「去结算」。没有 3D 图标。']]));

  // ===== C2 · 确认订单(来源 cy-self-play-checkout,Questo 卡结构;通行证持有者价格 0) =====
  function reveal() {
    return pop('pop-reveal', { close: false, center: true, cls: 'pl-pop', body: '<div class="kend">' + I('pass', { size: 96, glow: true }) + '<div class="kt" style="font:800 22px -apple-system,sans-serif">城瘾 · 城市定向通行证</div><div class="ks" style="font:14px -apple-system,sans-serif">' + THEME + '</div><div class="kd"><span>可玩至</span>11.30 <span style="margin-left:10px">剩余</span>19 天 <span style="margin-left:10px">票种</span><b id="rvSeat">单人票</b></div></div>', foot: pbtn('放进票夹', { go: 'wl-wallet' }) + pbtn('开始游戏', { kind: 'text', go: 'gp-start' }) });
  }
  H.add('ck-checkout', '确认订单(城市定向 / 自由探索)', 'C · 购买 · 通行证', 'cyp pl theme-dark ck2',
    ptop('确认订单', { txt: '<span class="pchip" data-act="ckMember" id="ckMemBtn" style="cursor:pointer">看持有通行证的样子</span>', txtAttr: 'style="right:106px"' }) +
    '<div class="sv" style="padding-top:104px;padding-bottom:150px">' +
    '<div class="memfree" id="memfree" hidden>' + I('pass', { size: 28 }) + '你的通行证覆盖这个主题 · 本单 ¥0</div>' +
    '<div class="sec">参与人信息</div><div class="pc who" data-mk="1"><span class="av">旅</span><span style="flex:1"><b>旅人</b><span>138****1234</span></span>' + S.chev.replace('<svg', '<svg style="width:16px;height:16px;stroke:currentColor;fill:none;stroke-width:2"') + '</div>' +
    '<div class="sec">主题信息</div><div class="pc item" data-mk="2"><img src="' + img('treasure-hunter') + '"><div style="flex:1"><b style="font-size:16px">' + THEME + '</b><div style="display:flex;gap:18px;margin-top:8px"><span><span class="lb">时间</span><br><span class="vv">可玩至 11 月 30 日</span></span><span><span class="lb">地点</span><br><span class="vv">外滩源 · 邮政大楼</span></span></div></div></div>' +
    '<div class="sec">费用明细</div><div class="pc" data-mk="3"><div class="r"><span>票种</span><span class="v ink" id="ckTk">单人票 × 1</span></div><div class="r"><span>订单金额</span><span class="v" id="ckAmt">¥68</span></div><div class="r" id="ckMemRow" hidden><span>通行证</span><span class="v" style="color:#22C55E">你已可直接游玩</span></div><div class="r"><span class="tot">合计</span><span class="v sum" id="ckSum">¥68</span></div></div>' +
    '<div class="trust" data-mk="4"><div>' + S.shield + '开玩前可退</div><div>' + S.clock + '可玩至 11.30</div><div>' + S.repeat + '可分几天玩完</div></div>' +
    '<div class="consent" data-mk="5"><i data-act="ckConsent" id="ckCons"><svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg></i><span>同意将姓名、手机号提供给主办方,用于报名核验</span></div><div class="err" id="ckErr" style="color:#E5484D;font-size:12px;margin:6px 2px;display:none">请先勾选同意</div></div>' +
    '<div class="ckbar" data-mk="6"><div class="tot"><span>合计</span><b id="barT">¥68</b></div>' + pbtn('立即支付', { act: 'ckPay', id: 'ckPay' }) + '</div>' + reveal(),
    '来源:pages/topic/components/cy/self-play-checkout(确认订单)+ Questo 结账的卡结构', H.notes([
      [1, '现有「确认订单」的四段照搬顺序,改成 Questo 式的分段卡:参与人信息(姓名 + 掩码手机,可改)。'],
      [2, '主题信息:封面 + 名称 + 时间 / 地点。'],
      [3, '费用明细:票种(从购买弹层带过来,× 人数)、订单金额、合计(斜体加粗)。没有城市通票 / 自玩票那些行 —— 通行证不在这里买。'],
      [4, '信任行三件事(可退 / 有效期 / 可分几天玩完)。'],
      [5, '同意勾选,不预勾;没勾点支付时红字落在下面。'],
      [6, '底栏合计 + 「立即支付」;持有通行证的人(顶上切「看持有通行证的样子」)这里价格是 ¥0,按钮变「免费领取」,明细多一行「通行证 · 你已可直接游玩」。支付确认后弹现有的 pass-reveal:「城瘾 · 城市定向通行证」+ 可玩至 / 剩余 / 票种 + 放进票夹 / 开始游戏。']]));

  // ===== C3 · 通行证(我的 → 通行证与礼品) =====
  H.add('ps-pass', '通行证(我的会员)', 'C · 购买 · 通行证', 'cyp pl theme-dark pass2',
    ptop('通行证', { right: S.more, rightAttr: 'data-toast="我的订单 / 礼品"' }) +
    '<div class="sv"><div class="hero" data-mk="1"><div style="position:relative;display:inline-block">' + I('pass', { size: 150, glow: true }) + '<span style="position:absolute;right:-26px;bottom:-6px">' + I('key', { size: 70, d: .5 }) + '</span></div>' +
    '<div class="cnt3">12<small>个主题 全部可玩</small></div><div class="lead">城市定向 · 自由探索 · 上海</div></div>' +
    '<div class="plan" data-mk="2"><div class="on" data-act="planSel" data-k="y"><span class="rib">最划算</span><b>¥299</b><span>年卡 · 每月不到 ¥25</span></div><div data-act="planSel" data-k="m"><b>¥39</b><span>月卡 · 随时停</span></div></div>' +
    '<div class="wpill" data-go="ps-success" data-mk="3">开通通行证</div><div class="terms">开通后期间内可玩主题价格为 ¥0 · 不自动续费</div>' +
    '<div class="sec" data-mk="4">可玩主题库 · 12 个</div><div class="lib">' + LIB.slice(0, 4).map(function (t, i) { return '<div class="it" data-go="tp-detail"><img src="' + img(COVERS[i]) + '"><span class="z">¥0</span><b>' + t[0] + '</b><span>' + t[1] + '</span></div>'; }).join('') + '</div>' +
    pbtn('进入游戏主题库 ›', { kind: 'text', go: 'ps-library' }) + '<div style="height:40px"></div></div>',
    '来源:subpackageMember/market/passes(通行证与礼品)· 视觉参考 refs4 pass-purchase', H.notes([
      [1, '改名「通行证」,放在我的 → 通行证与礼品。顶上按 X 参考的暗到暖橙渐变:新插图集的通行证 + 钥匙,大字「12 个主题 全部可玩」。'],
      [2, '年卡 / 月卡两张可选卡(角标「最划算」)。'],
      [3, '白药丸「开通通行证」,下面一行说明不自动续费。'],
      [4, '下面直接是可玩主题库:每个主题带 ¥0 角标,点开能看主题是什么;「查看全部」进整页库。与主题详情 / 结账完全分开。']]));
  H.add('ps-library', '游戏主题库(持有通行证 · 全部 ¥0)', 'C · 购买 · 通行证', 'cyp pl theme-dark',
    ptop('游戏主题库') + '<div class="sv"><div style="display:flex;gap:8px;margin:4px 0 14px"><span class="pchip" style="background:var(--cy-color-text-primary);color:var(--cy-color-bg-page)">全部</span><span class="pchip">城市定向</span><span class="pchip">自由探索</span></div>' +
    '<div class="lib2" data-mk="1">' + LIB.map(function (t, i) { return '<div class="pc2" style="--i:' + Math.min(i, 8) + '" data-go="tp-buy"><img class="cv" src="' + img(COVERS[i]) + '"><span class="gr"></span><div class="top"><span class="logo">城</span><span class="tag z">¥0 可玩</span><span class="city">' + S.pin + '上海</span></div><div class="sp"></div><div class="body"><div class="name">' + t[0] + '</div><div class="data">' + t[1] + ' · 约 ' + (40 + (i % 4) * 10) + ' 分钟</div><div class="bottom"><span class="tags"><span class="tag">' + t[1].split(' · ')[0] + '</span><span class="tag">' + ['外滩源', '北外滩', '弄堂', '豫园'][i % 4] + '</span></span>' + S.chev.replace('<svg', '<svg class="chev"') + '</div></div></div>'; }).join('') + '</div><div style="height:40px"></div></div>',
    '来源:components/cy/club-card + style/photo-card(俱乐部卡,一行一张,底部渐变)', H.notes([[1, '标题就叫「游戏主题库」,不写几个主题。卡片按俱乐部卡那套:一行一张、214px 高、22px 圆角、封面铺满、底部四段黑渐变;顶行是「城」标 + 「¥0 可玩」柠檬绿片 + 城市,底部是主题名、一行数据、标签片和右箭头。筛选 全部 / 城市定向 / 自由探索。']]));

  // ===== C4 · 通行证开通成功:钥匙开锁(参考视频里的钥匙) =====
  H.add('ps-success', '通行证开通成功 · 钥匙', 'C · 购买 · 通行证', 'cyp pl theme-dark psok',
    '<span class="skip" data-go="ps-library">跳过</span>' +
    '<div class="keyscene" data-mk="1"><div class="lockp" id="lockp"><span class="lk">' + I('locked', { size: 140 }) + '</span><span class="unl">' + I('unlocked', { size: 140 }) + '</span></div><div class="keyfly">' + I('key', { size: 180 }) + '</div>' +
    '<div class="coins">' + [[-120, 20, 2.9], [60, -10, 3.1], [-30, -60, 3.3], [110, 40, 3.5]].map(function (c) { return I('points', { size: 44, bare: true, d: c[2] }).replace('class="i3d', 'style="left:' + c[0] + 'px;top:' + c[1] + 'px;--s:44px;--ic-d:' + c[2] + 's" class="i3d'); }).join('') + '</div></div>' +
    '<div class="copy" data-mk="2" style="top:480px"><div class="k">通行证 · 已开通</div><div class="t">12 个主题,全部可玩</div><div class="s">到 2027 年 11 月 10 日 · 价格都是 ¥0</div><div class="thumbs">' + COVERS.slice(0, 5).map(function (c, i) { return '<img src="' + img(c) + '" style="animation-delay:' + (1.7 + i * .08) + 's">'; }).join('') + '</div></div>' +
    '<div class="foot" data-mk="3"><div class="pager"><i class="on"></i><i></i><i></i></div><div class="wpill" data-go="ps-library">进入可玩主题库</div></div>',
    '来源:refs4 pass-purchase-motion-frames(钥匙 / 开锁)· 图标 Fluent 3D', H.notes([
      [1, '按你发的视频:钥匙从左下飞进来,插进锁里转 90°,锁从「锁着」变「打开」,四枚硬币蹦出来 —— 全部是新插图集(空态同款画法)。'],
      [2, '文案三行 + 可玩主题封面依次升起。'],
      [3, '分页点 + 白药丸「进入可玩主题库」,右上「跳过」。']]));

  // ===== D · 结算四步(主题之外:纯黑底白字,与外观无关;来源 r2 已通过的 settle-* 截图 + 3D 图标) =====
  function steps(n) { return '<div class="steps">' + [1, 2, 3, 4].map(function (i) { return '<i class="' + (i <= n ? 'on' : '') + '"></i>'; }).join('') + '</div>'; }
  function back(go) { return '<span class="top" ' + (go ? 'data-go="' + go + '"' : 'data-back') + '>' + S.back + '</span>'; }
  H.add('st-overview', '结算 1 · 总览(纯黑)', 'D · 结算(主题之外)', 'cyp stl',
    steps(1) + back('gp-run-end') + '<div class="center" data-mk="1"><div style="position:relative">' + I('trophy', { size: 150, glow: true, d: .3 }) + I('sparkles', { size: 40, cls: 'spk a', d: .9 }) + I('sparkles', { size: 28, cls: 'spk b', d: 1.1 }) + '</div><div class="k">已通关</div><div class="t">' + THEME + '</div><div class="r">本关第 <b>1</b> 名</div>' +
    '<div class="two" data-mk="2"><div><b id="soScore" data-from="0">0</b><span>分数</span></div><div><b>7/7</b><span>节点</span></div></div></div>' +
    '<div class="foot" data-mk="3">' + pbtn('看排行榜', { go: 'st-rank' }) + pbtn('完成', { kind: 'text', toast: '回到票夹' }) + '</div>',
    '来源:r2 settle-overview-after(已通过的黑底版)+ 3D 奖杯', H.notes([
      [1, '结算回到上一版通过的样子:纯黑底、白字、整屏居中,与主题外观和配色无关(已经退出主题了)。顶上四段进度;金色对勾换成会动的 3D 奖杯 + 两点闪光 + 光晕。'],
      [2, '两格数据(分数 / 节点)同上一版,数字计数上来。'],
      [3, '「看排行榜」白钮 + 「完成」文字,同上一版。']]));
  // 月桂:细枝 + 小叶(参考 refs2 team-rank-laurel)
  function laurel(cls) { var N = 13, pts = [], leaves = '', k = 0; for (var i = 0; i <= N; i++) { var tt = i / N; pts.push([40 - Math.sin(tt * Math.PI) * 28, 108 - tt * 104]); } for (i = 1; i < N; i++) { var p = pts[i], q = pts[i + 1], ang = Math.atan2(q[1] - p[1], q[0] - p[0]) * 180 / Math.PI, L = 9.5 - (i / N) * 3; leaves += '<ellipse class="lf" style="--k:' + (k++) + ';--rot:' + (ang - 38).toFixed(0) + 'deg;--L:' + L.toFixed(1) + 'px" cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" rx="' + L.toFixed(1) + '" ry="2.6"/><ellipse class="lf" style="--k:' + (k++) + ';--rot:' + (ang + 38).toFixed(0) + 'deg;--L:' + (L - .6).toFixed(1) + 'px" cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" rx="' + (L - .6).toFixed(1) + '" ry="2.4"/>'; } return '<svg class="' + (cls || '') + '" viewBox="0 0 60 112"><path class="stem" d="M' + pts.map(function (p) { return p[0].toFixed(1) + ' ' + p[1].toFixed(1); }).join(' L') + '"/>' + leaves + '</svg>'; }
  var ROWS = [['周行', 1420], ['岚', 1380], ['旅人', 1250, 1], ['林野', 1210], ['阿岚', 1180], ['小桥', 990], ['陈', 940], ['Z', 880]];
  function lrows(list) { return list.map(function (r, i) { return '<div class="lrow' + (r[2] ? ' me' : '') + '" style="--i:' + i + '"><span class="no' + (i < 3 ? ' m' + (i + 1) : '') + '">' + (i + 1) + '</span><span class="av">' + r[0][0] + '</span><b>' + r[0] + (r[2] ? ' · 你' : '') + '</b><span class="sc">' + r[1] + '</span></div>'; }).join(''); }
  H.add('st-rank', '结算 2 · 排行榜(纯黑)', 'D · 结算(主题之外)', 'cyp stl',
    steps(2) + back('st-overview') + '<div class="sv"><div class="h">' + THEME + '</div><div style="display:flex;align-items:center;gap:8px" data-mk="1"><span class="pill" data-tab="rk:all" data-on-class="pill">全体</span><span class="pill pill--ghost" data-tab="rk:team" data-on-class="pill" style="margin-left:0">队内</span></div>' +
    '<div data-pane="rk:all"><div class="lau2" data-mk="2">' + laurel() + H.dr(1) + laurel('r') + '</div><div class="pct2">本主题 · 前 5%</div><div style="display:flex;justify-content:center;margin-top:10px"><span class="pseg"><span class="it on" data-tab="pd:all" data-on-class="on">累计</span><span class="it" data-tab="pd:week" data-on-class="on">本周</span></span></div>' +
    '<div class="lcard" data-mk="3"><div class="hd">完成节点数</div>' + lrows(ROWS) + '</div></div>' +
    '<div data-pane="rk:team" hidden><div class="lau2">' + laurel() + H.dr(2) + laurel('r') + '</div><div class="pct2">周末城门小队 · 前 20%</div><div class="lcard"><div class="hd">完成节点数</div>' + lrows([ROWS[0], ROWS[2], ROWS[1]]) + '</div></div></div>' +
    '<div class="foot">' + pbtn('评价与分享', { go: 'st-rate' }) + '</div>',
    '来源:r2 settle-rank-after / b3-p9(月桂名次 + 累计 / 本周 + 完成节点数卡)', H.notes([
      [1, '上一版的榜单页,纯黑;口径只留「全体 | 队内」两个药丸(城市 / 国家 / 世界收掉)。'],
      [2, '月桂重画(参考你发的 X):两枝细茎各九对小叶,进场时茎先画出、叶子依次弹出;中间 84px 超大等宽数字滚上来;下面一行「前 N%」。累计 / 本周分段同上一版。'],
      [3, '「完成节点数」列表卡:前三金银铜圆标,自己那行高亮。底部「评价与分享」。']]));
  H.add('st-rate', '结算 3 · 评价(纯黑)', 'D · 结算(主题之外)', 'cyp stl',
    steps(3) + back('st-rank') + '<div class="center center--top"><div class="t" style="animation:none">这一局怎么样?</div><div class="stars" id="stars" data-mk="1">' + [1, 2, 3, 4, 5].map(function (i) { return S.star.replace('<svg', '<svg data-act="rateStar" data-i="' + i + '"'); }).join('') + '</div>' +
    '<div class="tags" id="rateTags" data-mk="2"></div><textarea class="ta" id="rateTa" placeholder="喜欢哪一站?"></textarea><div class="cnt" style="width:100%">0 / 500</div><div class="err" id="rateErr" style="display:none;width:100%">写一句再提交</div></div>' +
    '<div class="foot">' + pbtn('提交', { act: 'rateSubmit', id: 'rateBtn' }) + pbtn('跳过', { kind: 'text', go: 'st-share' }) + '</div>' +
    pop('pop-rated', { close: false, center: true, cls: 'pl-pop', body: '<div class="kend">' + I('star', { size: 90, glow: true }) + '<div class="kt" style="font:800 22px -apple-system,sans-serif">谢谢</div><div class="ks" style="font:14px -apple-system,sans-serif">评价会公开,只显示昵称。</div></div>', foot: pbtn('去分享', { go: 'st-share' }) }),
    '来源:r2 settle-rate-error-after', H.notes([
      [1, '上一版通过的评价页:纯黑、白字、居中,五颗金星依次点亮,不跟主题配色。'],
      [2, '点星后出标签片;空提交红字「写一句再提交」。底部「提交」「跳过」。']]));
  H.add('st-share', '结算 4 · 分享(纯黑)', 'D · 结算(主题之外)', 'cyp stl',
    steps(4) + back('st-rate') + '<div class="center center--top" style="padding-top:112px"><span class="pseg" data-mk="1"><span class="it on" data-tab="sh:post" data-on-class="on">帖文</span><span class="it" data-tab="sh:story" data-on-class="on">故事</span></span>' +
    '<div data-pane="sh:post" style="margin-top:16px"><div class="scard2" data-mk="2"><img src="' + img('treasure-hunter') + '">' + I('badge', { size: 80, d: .3 }) + '<div class="k2">已通关</div><div class="t2">' + THEME + '</div><div class="m2">节点 7/7 · 1250 分 · 第 1 名</div><div class="logo2">城瘾</div></div></div>' +
    '<div data-pane="sh:story" hidden style="margin-top:16px"><div class="scard2 scard2--story"><img src="' + img('time-pollution') + '">' + I('badge', { size: 80, d: .3 }) + '<div class="k2">已通关</div><div class="t2">' + THEME + '</div><div class="m2">节点 7/7 · 1250 分 · 第 1 名</div><div class="logo2">城瘾</div></div></div></div>' +
    '<div class="foot foot--row" data-mk="3">' + pbtn('分享', { toast: '已发送' }) + pbtn('完成', { kind: 'ghost', toast: '回到票夹' }) + '</div>',
    '来源:r2 settle-share-post / settle-share-story', H.notes([
      [1, '上一版通过的分享页:纯黑,帖文 / 故事分段。'],
      [2, '卡片同上一版结构(封面淡入底、已通关、主题名、节点 / 分 / 名次、城瘾),金对勾换成会动的 3D 勋章。'],
      [3, '底部只有一行:「分享」+「完成」。']]));

  // ===== E · 邀请 = Spotify 式分享页(来源 components/cy/share-card) =====
  var SW = ['#7A2619', '#B8862F', '#2C2A26', '#121317'];
  H.add('sh-invite', '邀请 · Spotify 式分享页', 'E · 邀请 · 票夹 · 收集册', 'cyp pl theme-dark shc',
    '<div class="sc-top"><span class="sc-x" data-back>' + S.x + '</span><span class="sc-t">邀请好友</span></div>' +
    '<div class="sc-stage"><div class="sc-card" data-mk="1"><img class="sc-bgimg" id="scCover" src="' + img('treasure-hunter') + '"><span class="sc-shade"></span><div class="sc-eb">邀请 · 和我一起走这条路</div><div class="sc-big">' + THEME + '</div>' +
    '<div class="sc-line">' + ['邮政大楼', '老书店', '钟楼'].map(function (s, i) { return '<div class="sc-step"><span class="sc-dot">' + (i + 1) + '</span>' + s + '</div>'; }).join('') + '</div>' +
    '<div class="sc-inv">' + I('envelope', { size: 36 }) + '<span style="font-size:13px;opacity:.85">旅人 邀请你 · 还有 2 个席位</span></div>' +
    '<div class="sc-foot"><b>城瘾</b><span class="sc-qr">' + H.qr('cy-invite-A7K2') + '</span></div></div></div>' +
    '<div class="sc-sw" id="scSw" data-mk="2">' + SW.map(function (c, i) { return '<i style="background:' + c + '" data-act="scSw" data-c="' + c + '" class="' + (i ? '' : 'on') + '"></i>'; }).join('') + '</div>' +
    '<div class="sc-ch" data-mk="3">' + [['微信好友', S.wechat], ['朋友圈', S.moments], ['小红书', S.star], ['保存图片', S.save], ['复制文案', S.copy], ['更多', S.more]].map(function (a) { return '<div data-toast="' + a[0] + '"><i>' + a[1] + '</i>' + a[0] + '</div>'; }).join('') + '</div>',
    '来源:components/cy/share-card(cy-share-card,底色取自封面)', H.notes([
      [1, '卡片现在完整可见(上一版底图挤进了排版)。所有邀请统一用现有的 Spotify 式分享页:整页底色从封面取色,中间一张 280×400 的卡(封面压暗、眉题、大斜体主题名、竖向路线点、底部「城瘾」+ 小程序码),多了一行邀请人 + 剩余席位。'],
      [2, '四个色块可换底色(第一个是封面取色)。'],
      [3, '动作一行横滑:微信好友 / 朋友圈 / 小红书 / 保存图片 / 复制文案 / 更多,与现有组件一致。']]));

  // ===== 票夹 / 收集册(保留) =====
  var TK = [['treasure-hunter', THEME, '11 月 12 日 09:30 · 外滩源', '未开始'], ['apocalypse-broadcast', '夜航灯', '11 月 15 日 19:00 · 北外滩', '未开始'], ['flavor-tour', '老街灯笼铺', '11 月 20 日 · 豫园', '未开始']];
  H.add('wl-wallet', '票夹 · 点卡翻面 · 卷页换票 · 开始游戏', 'E · 邀请 · 票夹 · 收集册', 'cyp pl theme-dark wal wal3',
    TK.map(function (t, i) { return '<div class="bd' + (i === 0 ? ' on' : '') + '" style="background-image:url(' + img(t[0]) + ')"></div>'; }).join('') +
    '<div class="hd"><span class="flt" data-act="walFilter">票夹' + S.down + '</span><span class="ad" data-toast="兑换码 / 加票">+</span></div>' +
    '<div class="pop" id="walPop"><div class="on">全部</div><div>即将开始</div><div>已通关</div></div>' +
    '<div class="stage" id="walStage" data-mk="1">' + TK.map(function (t, i) { return '<div class="wc' + (i ? ' enter' : '') + '" data-i="' + i + '" data-act="walTap"><div class="wcf"><div class="face front"><span class="wc__go" style="background:#5AA9FF"><svg viewBox="0 0 24 24"><path d="M7 17L17 7M9 7h8v8"/></svg></span><div class="wc__t"><b class="wc__b">' + t[1] + '</b><span class="wc__s">城市定向 · ' + t[2] + '</span></div><span class="im"><img src="' + img(t[0]) + '"></span></div><div class="face back"><b class="wc__bn">' + t[1] + '</b><div class="qrbig qrbig--xl">' + H.qr(t[1]) + '</div><span class="wc__exp">可玩至 11 月 30 日 · 点一下翻回</span></div></div><span class="fold"></span><span class="spec"></span></div>'; }).join('') + '</div>' +
    '<div class="wl-start" id="wlStart" data-act="walStart" data-mk="2"><span class="fillx"></span><span class="shine"></span><span style="display:flex;align-items:center;gap:7px"><svg viewBox="0 0 24 24"><path d="M7 4.5v15l12.5-7.5z"/></svg>开始游戏</span></div>' + UI.tabbar(0, true),
    '来源:subpackageMember/signup(票夹 deck:.wc__flip 翻面 / .wl-start 开始游戏)· 换票动效参考 refs4 ticket-holder', H.notes([
      [1, '按现有票夹:一张票卡,点卡片翻到背面看入场码(现有 .wc__flip,.62s),再点翻回;下面没有左右切换钮。横向拖动换票:离开的那张像书页一样卷起来(3D 翻折 + 折痕暗面),卷面上有一道跟着手指走的镜面高光,卡下有倒影;下一张从右侧滑进。顶上筛选 + 加票,背景是当前票封面的模糊满铺。'],
      [2, '底部固定「▶ 开始游戏」白药丸(现有 .wl-start:进场后一道高光扫过,点击时灰底从左填满再进游戏)。']]));
  H.add('al-album', '收集册 · 开场(环装卡叠)', 'E · 邀请 · 票夹 · 收集册', 'cyp pl theme-dark alb',
    ptop('', { right: S.share, rightAttr: 'data-go="sh-invite"' }) +
    '<div class="hl" id="albHl">勋章<br><span style="font-size:18px;color:var(--cy-color-text-tertiary)">走过的路留下的东西</span></div>' +
    '<div class="ring-stage" id="albStage"><div class="pg p3 under3"><span class="lb">通关</span><span class="cnt2">4</span>' + I('trophy', { size: 150 }) + '</div><div class="pg p2 under2"><span class="lb">藏品</span><span class="cnt2">2</span>' + I('gem', { size: 150 }) + '</div><div class="pg p1 under1"><span class="lb">邮票</span><span class="cnt2">11</span>' + I('stamp', { size: 150 }) + '</div><div class="pg p0"><span class="lb">勋章</span><span class="cnt2">6</span>' + I('badge', { size: 150 }) + '</div><span class="ring"></span><span class="ring r2"></span></div>' +
    '<div class="ctl"><span class="dots" id="albDots"><i class="on"></i><i></i><i></i><i></i></span><span class="arrow" data-act="albNext">' + S.chev + '</span></div>',
    'subpackageP3 收集册入口', H.notes([[1, '环装卡叠开场保留(已通过);四张卡上的图换成新插图集(勋章 / 邮票 / 藏品 / 名次),空态同款画法。']]));

  H.flush();

  // ===== 行为 =====
  var buy = 0, member = false, consent = false, wal = 0, alb = 0, rate = 0;
  function ckRender() {
    var s = PROTO.cur(); if (!s || !s.querySelector('#ckSum')) return;
    var o = OPTS[buy], price = member ? 0 : o[3];
    s.querySelector('#ckTk').textContent = o[1] + ' × 1'; s.querySelector('#ckAmt').textContent = '¥' + o[3]; s.querySelector('#ckMemRow').hidden = !member; s.querySelector('#memfree').hidden = !member;
    s.querySelector('#ckSum').textContent = '¥' + price; s.querySelector('#barT').textContent = '¥' + price; s.querySelector('#ckPay').textContent = price <= 0 ? '免费领取' : '立即支付';
    s.querySelector('#ckMemBtn').textContent = member ? '看普通玩家的样子' : '看持有通行证的样子'; s.querySelector('#rvSeat').textContent = o[1];
    s.querySelector('#ckCons').classList.toggle('on', consent); s.querySelector('#ckErr').style.display = 'none';
    requestAnimationFrame(PROTO.placeMarks);
  }
  function walGo(d) {
    var s = PROTO.cur(), cards = s.querySelectorAll('.wc'), bds = s.querySelectorAll('.bd'), n = cards.length, from = wal, to = (wal + d + n) % n; if (to === from) return;
    var c0 = cards[from], c1 = cards[to]; c0.classList.remove('drag', 'flipped'); c0.style.transform = ''; c0.style.removeProperty('--sx');
    if (d > 0) { c0.classList.add('leave'); c1.classList.remove('leave'); c1.classList.add('enter'); void c1.offsetWidth; c1.classList.remove('enter'); }
    else { c0.classList.add('enter'); c1.classList.remove('enter'); c1.classList.add('leave'); void c1.offsetWidth; c1.classList.remove('leave'); }
    [].forEach.call(bds, function (b, i) { b.classList.toggle('on', i === to); });
    setTimeout(function () { if (wal === from) return; c0.style.transition = 'none'; c0.classList.remove('leave'); c0.classList.add('enter'); void c0.offsetWidth; c0.style.transition = ''; }, 460);
    wal = to; [].forEach.call(cards, function (c, i) { c.style.zIndex = i === to ? 3 : 1; });
  }
  Object.assign(window.ACT, {
    buySel: function (el) { buy = +el.getAttribute('data-i'); var s = PROTO.cur(); [].forEach.call(s.querySelectorAll('#bseg .bseg__b'), function (b, i) { b.classList.toggle('on', i === buy); }); s.querySelector('#bsegU').style.transform = 'translateX(' + buy * 100 + '%)'; var o = OPTS[buy]; s.querySelector('#buyPr').textContent = o[2]; s.querySelector('#buyU').textContent = o[1]; s.querySelector('#buyTot').textContent = o[2]; s.querySelector('#buySeats').textContent = o[4] + ' 人 · ' + o[4] + ' 席位'; },
    ckMember: function () { member = !member; ckRender(); },
    ckConsent: function () { consent = !consent; ckRender(); },
    ckPay: function (el) { var s = PROTO.cur(); if (!consent) { s.querySelector('#ckErr').style.display = 'block'; return; } el.textContent = '处理中…'; setTimeout(function () { el.classList.add('pbtn--ok'); el.textContent = member ? '已领取' : '已支付'; setTimeout(function () { G.openPop('pop-reveal'); el.classList.remove('pbtn--ok'); ckRender(); }, 700); }, 800); },
    planSel: function (el) { [].forEach.call(el.parentNode.children, function (x) { x.classList.toggle('on', x === el); }); },
    rateStar: function (el) { rate = +el.getAttribute('data-i'); var st = el.parentNode.querySelectorAll('svg'); [].forEach.call(st, function (x, i) { setTimeout(function () { x.classList.toggle('on', i < rate); }, i < rate ? i * 100 : 0); }); var s = PROTO.cur(), tg = s.querySelector('#rateTags'); if (!tg.children.length) tg.innerHTML = ['故事好', '地点选得好', '谜题有意思', '路太远', '提示不够', '太难了'].map(function (t, i) { return '<span class="gch" style="--i:' + i + '" data-act="tog">' + t + '</span>'; }).join(''); s.querySelector('#rateErr').style.display = 'none'; },
    tog: function (el) { el.classList.toggle('on'); },
    rateSubmit: function (el) { var s = PROTO.cur(); if (!rate) { s.querySelector('#rateErr').style.display = 'block'; return; } el.textContent = '已提交'; el.classList.add('pbtn--ok'); setTimeout(function () { G.openPop('pop-rated'); el.textContent = '提交'; el.classList.remove('pbtn--ok'); }, 700); },
    scSw: function (el) { PROTO.cur().style.setProperty('--sc-bg', el.getAttribute('data-c')); [].forEach.call(el.parentNode.children, function (x) { x.classList.toggle('on', x === el); }); },
    walFilter: function () { PROTO.cur().querySelector('#walPop').classList.toggle('open'); },
    walTap: function (el) { if (el.dataset.dragged) { delete el.dataset.dragged; return; } el.classList.toggle('flipped'); },
    walStart: function (el) { el.classList.add('go'); setTimeout(function () { el.classList.remove('go'); PROTO.show('gp-start', true); }, 600); },
    albNext: function () {
      var s = PROTO.cur(), pgs = [].slice.call(s.querySelectorAll('.pg')).reverse(), n = pgs.length, cur = pgs[alb % n]; cur.classList.add('flip');
      alb++; var nxt = alb % n;
      setTimeout(function () { pgs.forEach(function (p, i) { var d = ((i - nxt) % n + n) % n; p.className = 'pg p' + i + (d ? ' under' + d : ''); }); }, 500);
      var names = ['勋章', '邮票', '藏品', '通关'], subs = ['走过的路留下的东西', '每一站盖一个', '路上捡到的', '每一局的印'];
      s.querySelector('#albHl').innerHTML = names[nxt] + '<br><span style="font-size:18px;color:var(--cy-color-text-tertiary)">' + subs[nxt] + '</span>';
      [].forEach.call(s.querySelectorAll('#albDots i'), function (d, i) { d.classList.toggle('on', i === nxt); });
    },
  });
  var dragX = null, dragCard = null;
  document.addEventListener('pointerdown', function (e) { var st = e.target.closest('#walStage'); if (!st) return; dragX = e.clientX; dragCard = st.querySelectorAll('.wc')[wal]; }, true);
  document.addEventListener('pointermove', function (e) { if (dragX == null || !dragCard) return; var k = 390 / PROTO.cur().getBoundingClientRect().width, dx = (e.clientX - dragX) * k; if (Math.abs(dx) < 6) return; dragCard.dataset.dragged = '1'; var p = Math.max(0, Math.min(1, -dx / 300)); dragCard.classList.add('drag'); dragCard.style.transform = dx < 0 ? 'rotateY(' + (-78 * p) + 'deg) translateX(' + (-8 * p) + '%) skewY(' + (-4 * p) + 'deg)' : 'translateX(' + dx * .3 + 'px)'; dragCard.style.setProperty('--sx', (110 - p * 130) + '%'); dragCard.querySelector('.fold').style.opacity = p; }, true);
  document.addEventListener('pointerup', function (e) { if (dragX == null) return; var dx = e.clientX - dragX; dragX = null; var c = dragCard; dragCard = null; if (!c) return; c.classList.remove('drag'); c.querySelector('.fold').style.opacity = ''; if (dx < -90) { walGo(1); return; } if (dx > 90) { walGo(-1); return; } c.style.transform = ''; c.style.removeProperty('--sx'); setTimeout(function () { delete c.dataset.dragged; }, 50); }, true);

  // 封面取色(来源 utils/share-tint.js:饱和度加权平均,亮度钳到 .16–.30)
  function tint(imgEl, cb) {
    var im = new Image(); im.onload = function () { try { var c = document.createElement('canvas'); c.width = c.height = 24; var g = c.getContext('2d'); g.drawImage(im, 0, 0, 24, 24); var d = g.getImageData(0, 0, 24, 24).data, r = 0, gg = 0, b = 0, w = 0; for (var i = 0; i < d.length; i += 4) { var mx = Math.max(d[i], d[i + 1], d[i + 2]), mn = Math.min(d[i], d[i + 1], d[i + 2]), sat = mx ? (mx - mn) / mx : 0, k = .2 + sat; r += d[i] * k; gg += d[i + 1] * k; b += d[i + 2] * k; w += k; } r /= w; gg /= w; b /= w; var l = (Math.max(r, gg, b) + Math.min(r, gg, b)) / 510, target = Math.max(.16, Math.min(.30, l)), f = target / (l || .2); cb('rgb(' + Math.round(Math.min(255, r * f)) + ',' + Math.round(Math.min(255, gg * f)) + ',' + Math.round(Math.min(255, b * f)) + ')'); } catch (e) { cb(SW[0]); } }; im.src = imgEl.src;
  }
  G.on['ck-checkout'] = function () { ckRender(); };
  G.on['tp-buy'] = function () { setTimeout(function () { PROTO.openSheet('sh-buy', 'full'); }, 250); };
  G.on['st-overview'] = function (s) { setTimeout(function () { G.countUp(s.querySelector('#soScore'), 1250, 800, function (v) { return Math.round(v).toLocaleString(); }); }, 1100); };
  G.on['st-rank'] = function (s) { var r = s.querySelector('[data-pane="rk:all"] .dr'); if (r) { r.querySelectorAll('.dr__strip')[0].style.transform = 'translateY(-30%)'; setTimeout(function () { G.roll(r, 1); }, 500); } };
  G.on['st-rate'] = function (s) { rate = 0; [].forEach.call(s.querySelectorAll('#stars svg'), function (x) { x.classList.remove('on'); }); s.querySelector('#rateTags').innerHTML = ''; s.querySelector('#rateErr').style.display = 'none'; };
  G.on['sh-invite'] = function (s) { tint(s.querySelector('#scCover'), function (c) { s.style.setProperty('--sc-bg', c); var sw = s.querySelector('#scSw i'); if (sw) { sw.style.background = c; sw.setAttribute('data-c', c); } }); };
  G.on['ps-success'] = function (s) { var l = s.querySelector('#lockp'); l.classList.remove('open'); setTimeout(function () { l.classList.add('open'); }, 3000); };
  G.on['wl-wallet'] = function (s) { wal = 0; [].forEach.call(s.querySelectorAll('.wc'), function (c, i) { c.className = 'wc' + (i ? ' enter' : ''); c.style.zIndex = i ? 1 : 3; c.style.transform = ''; }); [].forEach.call(s.querySelectorAll('.bd'), function (b, i) { b.classList.toggle('on', i === 0); }); var w = s.querySelector('#wlStart .shine'); if (w) { var c2 = w.cloneNode(true); w.parentNode.replaceChild(c2, w); } };
  G.on['al-album'] = function (s) { alb = 0; var pgs = [].slice.call(s.querySelectorAll('.pg')).reverse(); pgs.forEach(function (p, i) { p.className = 'pg p' + i + (i ? ' under' + i : ''); }); [].forEach.call(s.querySelectorAll('#albDots i'), function (d, i) { d.classList.toggle('on', i === 0); }); s.querySelector('#albHl').innerHTML = '勋章<br><span style="font-size:18px;color:var(--cy-color-text-tertiary)">走过的路留下的东西</span>'; };
  document.addEventListener('click', function (e) { var t = e.target.closest('[data-tab^="rk:"]'); if (!t) return; var s = PROTO.cur(); [].forEach.call(s.querySelectorAll('[data-tab^="rk:"]'), function (p) { p.classList.toggle('pill--ghost', p !== t); }); setTimeout(function () { var pane = s.querySelector('[data-pane="' + t.getAttribute('data-tab') + '"] .dr'); if (pane) { pane.querySelectorAll('.dr__strip')[0].style.transform = 'translateY(-30%)'; requestAnimationFrame(function () { G.roll(pane, t.getAttribute('data-tab') === 'rk:all' ? 1 : 2); }); } }, 30); });
})();
