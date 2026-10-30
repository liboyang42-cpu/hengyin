// P-7 商家工作台(业主 10-30 第二轮批注 R03–R12)。页面照 master WXML 类名拼(cy.css),弹层按 10-23 弹层规范。
(function () {
  var U = UI, I = U.icon, E = U.esc, mk = U.mk;
  var C = 'assets/covers/', IMG = 'assets/img/', D3 = 'assets/3d/';
  function notes(list) { return '<ol class="notes">' + list.map(function (t) { return '<li data-n="' + t[0] + '">' + t[1] + '</li>'; }).join('') + '</ol>'; }
  function scr(id, title, group, src, body, nt) { return '<section class="scr cyp theme-merchant" data-id="' + id + '" data-title="' + title + '" data-group="' + group + '" data-src="' + src + '">' + body + '<view class="os-layer"></view><view class="toast7"><i></i><text></text></view>' + (nt ? notes(nt) : '') + '</section>'; }
  var S = [];

  // ---------------- 数据 ----------------
  var CLUBS = [
    { n: '城市定向免费测试俱乐部', m: '兴趣社群 · 86 人 · 1.2km', img: 'old-movie.jpg', st: 'ok' },
    { n: '夜跑上海', m: '运动 · 214 人 · 3.4km', img: 'heat-escape.jpg', st: 'wait' },
    { n: '旧书与胶片', m: '文化 · 40 人 · 2.1km', img: 'midlife-day.jpg', st: '' },
    { n: '周末城市漫步团', m: '城市探索 · 132 人 · 4.0km', img: 'treasure-hunter.jpg', st: '' }];
  var STATIONS = [
    { n: '1933老场坊', img: 'old-movie.jpg', who: '星巴克咖啡厅', kind: 'self' },
    { n: '上海邮政博物馆', img: 'flavor-tour.jpg', who: '', kind: '', apply: 1 },
    { n: '四行仓库抗战纪念馆', img: 'time-pollution.jpg', who: '', kind: '' },
    { n: 'M50创意园', img: 'anomaly-coordinate.jpg', who: '城市运动装备馆', kind: 'mer' }];
  var CHAPTERS = [
    { n: '苏河北岸', nodes: ['四行仓库', '上海邮政博物馆', '河滨大楼'], img: 'time-pollution.jpg', kind: 'self' },
    { n: '老城厢', nodes: ['豫园老街', '城隍庙小吃'], img: 'flavor-tour.jpg', kind: '', apply: 1 },
    { n: '外滩源', nodes: ['圆明园路', '光陆大楼', '亚洲文会'], img: 'old-movie.jpg', kind: '' }];
  // 每个工作台屏的模式:city 主办城市定向 / free 主办自由探索 / jn 承办节点 / jc 承办章节
  var MODE = { 'ws-free': 'free', 'ws-free-ok': 'free', 'join-node': 'jn', 'join-chapter': 'jc' };
  function mode() { return MODE[cur().getAttribute('data-id')] || 'city'; }
  var CLUB_SETS = {
    'ws-free': CLUBS.map(function (c) { return Object.assign({}, c, { st: '' }); }),
    'ws-free-ok': CLUBS.map(function (c, i) { return Object.assign({}, c, { st: i ? '' : 'ok' }); }) };
  function clubs() { return CLUB_SETS[cur().getAttribute('data-id')] || CLUBS; }
  var NEAR = [{ n: '苏河茶室', m: '茶饮 · 320m', img: 'midlife-day.jpg' }, { n: '一尺花园', m: '咖啡 · 480m', img: 'flavor-tour.jpg' }, { n: '老码头书店', m: '书店 · 760m', img: 'time-pollution.jpg' }];
  var APPLY = [{ n: '苏河茶室', st: '上海邮政博物馆', msg: '我们离邮政博物馆 300 米,可以接待 20 人/场,提供一杯招牌茶。', img: 'midlife-day.jpg' }];
  var CUST = [['阿乔', '11-02 14:00 场', 'wait'], ['小林', '11-02 15:30 场', 'wait'], ['Mia', '11-03 10:00 场', 'wait'], ['周周', '10-30 场 · 16:12 核销', 'ok'], ['Kai', '10-30 场 · 11:40 核销', 'ok']];

  // ---------------- 工作台页面(project-host / project-join 原结构)----------------
  function hero(status) {
    return '<view class="hero"><img class="hero-img" src="' + C + 'old-movie.jpg"><view class="hero-scrim"></view><view class="hero-status"><view class="hero-status-dot state-ok"></view><text>' + status + '</text></view></view>';
  }
  function steps(list) {
    return list.map(function (s, i) {
      var c = s.st;
      return '<view class="pg7-row ' + c + (s.lock ? ' lock' : '') + '" data-act="stepOpen" data-l="' + s.label + '" data-s="' + c + '"><view class="pg7-rail"><view class="pg7-dot ' + c + (s.lock ? ' lock' : '') + '">' + (s.lock ? I('lock', 20) : '') + '</view><view class="pg7-line ' + (c === 'done' ? 'done' : '') + '"></view></view>' +
        '<view class="pg7-m"><view class="pg7-t"><text class="lab">' + s.label + '<i class="pg7-chev"></i></text>' + (s.lock ? '<text class="pg7-ask">' + s.lock + '</text>' : s.tag ? (s.st === 'run' && !s.tagKind ? '<text class="pg7-stat">' + s.tag + '</text>' : '<text class="chip7 chip7--' + (s.tagKind || 'warn') + '">' + s.tag + '</text>') : (s.time ? '<text class="pg7-time">' + s.time + '</text>' : '')) + '</view>' +
        (s.sub ? '<view class="pg7-sub">' + s.sub.map(function (x) { return '<view class="pg7-si ' + (x[1] || '') + '"><i></i><text>' + x[0] + '</text>' + (x[2] ? '<text class="go" data-act="' + x[2] + '">' + (x[3] || '去处理') + ' ›</text>' : '') + '</view>'; }).join('') + '</view>' : '') +
        '</view></view>';
    }).join('');
  }
  var HOST_STATE = {
    draft: { status: '草稿', cur: 1 }, recruit: { status: '招商中', cur: 2 }, price: { status: '待配票务', cur: 3 }, sale: { status: '售票中', cur: 4 }, end: { status: '已结束', cur: 6 } };
  function hostSteps(k) {
    var cur = HOST_STATE[k].cur;
    var L = [
      { label: '配置主题', time: '10-26' },
      { label: '发布', sub: [['剧情、站点已填好 · 票务可以之后再配', 'ok'], ['发布后先开始招商', 'now', 'publish', '去发布']] },
      { label: '招商', sub: [['俱乐部 1 家已接受 · 1 家等回复', 'ok', 'dw-club', '看俱乐部'], ['商家 2/4 站 · 1 个申请待处理', 'now', 'dw-recruit', '去处理']] },
      { label: '配置票务', sub: [['合作方已就位 · 2/4 站 + 1 家俱乐部', 'ok'], ['票种和票价在主题配置的票务里设', 'now', 'goTicket', '去配置']] },
      { label: '售票', sub: [['已售 36 份 · 11-01 起开放', 'ok'], ['还没到店 22 位', 'now', 'dw-cust', '看客户']] },
      { label: '核销接待', sub: [['已核销 14 位', 'ok'], ['还剩 22 位待核销', 'now', 'scan', '去扫码']] },
      { label: '结算', sub: [['每月 5 日结上月', ''], ['本主题已入账 ¥420.00', 'ok', 'dw-profit', '分润明细']] }];
    return L.map(function (s, i) {
      var o = Object.assign({}, s);
      o.st = i < cur ? 'done' : (i === cur ? 'run' : 'todo');
      if (o.st === 'run') o.tag = ['', '待发布', '待邀约', '待配置', '待核销', '待核销', '待结算'][i];
      if (o.st !== 'run') { o.sub = i < cur ? null : null; }
      if (i === 6 && o.st === 'todo') o.lock = '每月 5 日';
      if (i < cur && !o.time) o.time = ['10-26', '10-27', '10-29', '10-30', '11-01', '11-30', ''][i];
      return o;
    });
  }
  function joinSteps() {
    return [{ label: '报名已提交', time: '10-27 10:20', st: 'done' }, { label: '主办审核', time: '10-28 09:12', st: 'done' },
      { label: '补供给', st: 'run', tag: '待补齐', sub: [['门店照片', 'ok'], ['接待时段', 'ok'], ['到店权益说明', 'now', 'supply', '去补']] },
      { label: '接待核销', st: 'todo', time: '11-01 起' }, { label: '结算', st: 'todo', lock: '每月 5 日' }];
  }
  function freeSteps(ok) {
    return [{ label: '配置主题', time: '10-29', st: 'done' }, { label: '发布', time: '10-30', st: 'done' },
      ok ? { label: '俱乐部承办', time: '10-31', st: 'done' }
         : { label: '邀请俱乐部承办', st: 'run', tag: '待邀约', sub: [['有 1 家俱乐部接受承办,才能开始售票', 'now', 'dw-club', '去邀请']] },
      ok ? { label: '招商', st: 'run', tag: '待邀约', sub: [['承办:城市定向免费测试俱乐部', 'ok', 'dw-club', '看看'], ['商家 1/3 章 · 1 个申请待处理', 'now', 'dw-recruit', '去处理']] }
         : { label: '招商', st: 'todo' },
      { label: '配置票务', st: 'todo', time: ok ? '' : '合作方就位后' }, { label: '售票', st: 'todo' }, { label: '核销接待', st: 'todo' }, { label: '结算', st: 'todo', lock: '每月 5 日' }];
  }
  function chapterJoinSteps() {
    return [{ label: '申请承接', time: '10-27 10:20', st: 'done' }, { label: '主办确认', time: '10-28 09:12', st: 'done' },
      { label: '填写供给', st: 'run', tag: '待补齐', sub: [['接待时段', 'ok'], ['节点地址 2/3', 'now', 'supply', '去补'], ['到店权益说明', 'now', 'supply', '去补']] },
      { label: '接待核销', st: 'todo', time: '11-01 起' }, { label: '结算', st: 'todo', lock: '每月 5 日' }];
  }
  function mineBlock(m) {
    if (m === 'jn') return '<view class="sec"><view class="sec-head"><text class="sec-title">我承接的节点</text></view></view><view class="mine7"' + mk(2) + '>' +
      '<view class="st7"><img class="st7-img" src="' + C + 'flavor-tour.jpg"><view class="mr-m"><view class="mr-n">第 2 站 · 上海邮政博物馆</view><view class="mr-s">北苏州路 250 号 · 引流档</view></view><text class="chip7 chip7--ok">已通过</text></view></view>';
    return '<view class="sec"><view class="sec-head"><text class="sec-title">我承接的章节</text></view></view><view class="mine7"' + mk(2) + '>' +
      '<view class="st7"><img class="st7-img" src="' + C + 'time-pollution.jpg"><view class="mr-m"><view class="mr-n">第 1 章 · 苏河北岸</view><view class="mr-s">3 个节点 · 体验档</view></view><text class="chip7 chip7--ok">已确认</text></view>' +
      CHAPTERS[0].nodes.map(function (n, i) { return '<view class="nd7"><i>' + (i + 1) + '</i><text>' + n + '</text><span>' + (i === 2 ? '待填地址' : '已填') + '</span></view>'; }).join('') + '</view>';
  }
  function workbench(o) {
    var host = o.role !== 'join', free = o.free, m = o.m;
    if (!host) return joinBench(m);
    var qa = host ? [['dw-club', '俱乐部', 'icon_qa_club.svg'], ['dw-recruit', '招商', 'icon_qa_store.svg'], ['dw-cust', '客户', 'icon_qa_person.svg'], ['themeQr', '二维码', 'i:qr'], ['dw-more', '更多', 'icon_qa_edit.svg']]
      : [['scan', '扫码', 'icon_qa_scan.svg'], ['dw-club', '俱乐部', 'icon_qa_club.svg'], ['dw-cust', '客户', 'icon_qa_person.svg'], ['dw-more', '更多', 'icon_qa_edit.svg']];
    var stList = free ? freeSteps(o.ok) : hostSteps(o.state || 'recruit');
    return '<view class="sv"><view class="c-project-host"><view class="page theme-merchant">' + hero(free ? (o.ok ? '招商中' : '待俱乐部承办') : HOST_STATE[o.state || 'recruit'].status) +
      '<view class="head"><view class="head-title">上海像一部旧电影</view><view class="head-meta"><view class="meta-chip">' + I('calendar', 26) + '<text>11-01 至 11-30</text></view><view class="chip head-role">' + (free ? '主办 · 自由探索' : '主办 · 城市定向') + '</view></view></view>' +
      (free && !o.ok ? '<view class="gate7"' + mk(2) + '><text>邀请一个俱乐部承办后才能开始售票</text><view class="pill7" data-act="dw-club">去邀请</view></view>' : '') +
      (o.state === 'price' ? '<view class="gate7"' + mk(2) + '><text>合作方已就位 · 配好票务就开售</text><view class="pill7" data-act="goTicket">配置票务</view></view>' : '') +
      qaRow(qa) +
      '<view class="sec"><view class="sec-head"><text class="sec-title">售卖</text><view class="sec-action" data-act="dw-profit"><text>结算</text>' + I('arrow-right', 24) + '</view></view>' +
      '<view class="metrics"><view class="metric"><text class="metric-n">' + (o.state === 'draft' || o.state === 'price' || free ? 0 : 36) + '</text><text class="metric-l">已售份数</text></view><view class="metric"><text class="metric-n">' + (free ? (o.ok ? '1/3' : '0/3') : '2/4') + '</text><text class="metric-l">' + (free ? '已招章节' : '已招站点') + '</text></view><view class="metric"><text class="metric-n">' + (free && !o.ok ? 0 : 1) + '</text><text class="metric-l">' + (free ? '承办俱乐部' : '合作俱乐部') + '</text></view></view></view>' +
      '<view class="pg7"' + mk(1) + '><view class="pg7-h"><b>进度</b><span>' + (free ? '自由探索 · 主办' : '城市定向 · 主办') + '</span></view>' + steps(stList) + '</view>' +
      '<view style="height:40px"></view></view></view></view>' + U.navBar({ overlay: true, pill: true, tint: 'light' });
  }
  function qaRow(qa) { return '<view class="qa">' + qa.map(function (q) { return '<view class="qa-btn" data-act="' + q[0] + '"><view class="qa-ico">' + (q[2].indexOf('i:') === 0 ? I(q[2].slice(2), 40) : '<img class="qa-ico-img" src="' + IMG + q[2] + '">') + '</view><text class="qa-label">' + q[1] + '</text></view>'; }).join('') + '</view>'; }
  function joinBench(m) {
    var node = m === 'jn';
    return '<view class="sv"><view class="c-project-host"><view class="page theme-merchant">' + hero(node ? '承办 · 待补供给' : '承办 · 待补供给') +
      '<view class="head"><view class="head-title">' + (node ? '上海像一部旧电影' : '苏州河慢慢走') + '</view><view class="head-meta"><view class="meta-chip">' + I('calendar', 26) + '<text>11-01 至 11-30</text></view><view class="chip head-role">' + (node ? '承办 · 第 2 站' : '承办 · 第 1 章') + '</view></view></view>' +
      '<view' + mk(1) + '>' + qaRow([['scan', '扫码', 'icon_qa_scan.svg'], ['themeQr', '二维码', 'i:qr'], ['dw-cust', '客户', 'icon_qa_person.svg']]) + '</view>' +
      '<view class="sec"><view class="sec-head"><text class="sec-title">本' + (node ? '站' : '章') + '</text></view><view class="metrics"><view class="metric"><text class="metric-n">22</text><text class="metric-l">已预约</text></view><view class="metric"><text class="metric-n">14</text><text class="metric-l">已核销</text></view><view class="metric"><text class="metric-n">8</text><text class="metric-l">待核销</text></view></view></view>' +
      mineBlock(m) +
      '<view class="pg7"><view class="pg7-h"><b>进度</b><span>' + (node ? '城市定向 · 承办' : '自由探索 · 承办') + '</span></view>' + steps(node ? joinSteps() : chapterJoinSteps()) + '</view>' +
      '<view class="cta7"' + mk(3) + '><view class="os-btn" data-act="contactHost">联系主办</view></view>' +
      '<view style="height:24px"></view></view></view></view>' + U.navBar({ overlay: true, pill: true, tint: 'light' });
  }
  var WS_NOTES = [[1, '页面最下面加「进度」(参考 shedsgns:做完是实心圆 + 勾、当前这步空心圆带呼吸点、连线逐段填满;子项列出这一步还差什么,右边直接点进去处理)。主办:配置 → 发布 → 招商 → 售票 → 核销接待 → 结算;承接:报名 → 审核 → 补供给 → 接待核销 → 结算。四个入口点开都是底部弹窗,不过微信导航(胶囊下沿 +5 封顶)。底部「查看收到的申请」删掉。']];
  S.push(scr('ws-recruit', '主办 · 招商中', '主题工作台', 'pages/topic/components/project-host/index.wxml', workbench({ state: 'recruit' }), WS_NOTES));
  S.push(scr('ws-draft', '主办 · 待发布', '主题工作台', 'project-host', workbench({ state: 'draft' }), WS_NOTES));
  S.push(scr('ws-sale', '主办 · 售票中', '主题工作台', 'project-host', workbench({ state: 'sale' }), WS_NOTES));
  S.push(scr('ws-price', '主办 · 待配票务', '主题工作台', 'project-host → pages/publish/fabu(票务区)', workbench({ state: 'price' }), [[2, '顺序改成:先配主题、发布、招商 —— 这时可以不配票务;邀约都被接受、合作方就位后,工作台出「配好票务就能开售」+「配置票务」,进度里多一步「配置票务」。点了直接进现有的主题配置页并定位到票务区(票种、票价),不另做新页面。']]));
  var JOIN_NOTES = [[1, '承办只有「扫码」「客户」两格:俱乐部、商家、更多都不给(那些是主办的事)。客户弹窗里没有定向广播。'], [2, '页面下面列出我承接的那一份:城市定向是一个节点,自由探索是一整章(连同章里的节点)。'], [3, '最底下「联系主办」:打电话、发消息、看主办主页。号码不上屏,沿用玩家端「联系主办」那一套(/api/play/contact)。']];
  S.push(scr('join-node', '承办 · 城市定向(承接节点)', '主题工作台', 'pages/topic/components/project-join/index.wxml', workbench({ role: 'join', m: 'jn' }), JOIN_NOTES));
  S.push(scr('join-chapter', '承办 · 自由探索(承接章节)', '主题工作台', 'pages/topic/components/project-join/index.wxml', workbench({ role: 'join', m: 'jc' }), JOIN_NOTES));
  var FREE_NOTES = [[1, '商家发布自由探索后直接落到这一页(发布成功弹层文案改成「邀请一个俱乐部承办后才能开始售票」)。'], [2, '还没有俱乐部承办时,顶部一条提示 + 「去邀请」;进度停在「邀请俱乐部承办 · 待邀约」,售票及以后都灰着。后端开售闸本来就要求唯一承办俱乐部(SALE_EXECUTING_CLUB_UNRESOLVED),这里只是把它摆到页面上。'], [3, '自由探索按「章」招商,城市定向按「站」招商。']];
  S.push(scr('ws-free', '主办 · 自由探索 · 待俱乐部承办', '主题工作台', 'pages/topic/components/project-host/index.wxml', workbench({ free: true }), FREE_NOTES));
  S.push(scr('ws-free-ok', '主办 · 自由探索 · 俱乐部已承办', '主题工作台', 'project-host', workbench({ free: true, ok: true }), [[1, '俱乐部接受后:提示条消失,进度里「俱乐部承办」打勾,开始按章招商,售票随后开放。']]));

  // ---------------- 推入页:正式俱乐部主页(落在「概览」)/ 商家主页(按店铺装修字段)----------------
  S.push(scr('club-home', '俱乐部主页 · 概览', '主题工作台', 'pages/club/detail(activeTab=overview)',
    '<view class="ph7"><img class="ph7-img" src="assets/pages/club-top.jpg">' +
    '<view class="ct7"><text>帖子</text><text>活动</text><text class="on">概览</text></view>' +
    '<view class="ov7"' + mk(1) + '><view class="ov7-intro">在上海老建筑里走城市定向。每周六下午一场,4–6 人一队,走完一起吃饭。</view>' +
    '<view class="ov7-facts">' + [['林小满', '主理人'], ['兴趣社群', '俱乐部类型'], ['上海', '所在城市']].map(function (f) { return '<view><b>' + f[0] + '</b><span>' + f[1] + '</span></view>'; }).join('') + '</view>' +
    '<view class="ov7-row"><text>俱乐部勋章</text><view class="tl-chev"></view></view>' +
    '<view class="ov7-h"><b>成员 128</b></view><view class="ov7-strip">' + [['林小满', '主理人', 'treasure-hunter.jpg'], ['何以解忧', '管理员', 'old-movie.jpg'], ['阿乔', '', 'flavor-tour.jpg'], ['Mia', '', 'midlife-day.jpg'], ['周周', '', 'heat-escape.jpg']].map(function (m) { return '<view class="ov7-m"><img src="' + C + m[2] + '"><text>' + m[0] + '</text>' + (m[1] ? '<span>' + m[1] + '</span>' : '') + '</view>'; }).join('') + '</view>' +
    '<view class="ov7-h"><b>正在进行</b></view><view class="st7" style="border:0"><img class="st7-img" src="' + C + 'old-movie.jpg"><view class="mr-m"><view class="mr-n">上海像一部旧电影</view><view class="mr-s">11-01 起 · 4 站</view></view></view>' +
    '<view style="height:40px"></view></view></view>' +
    '<view style="position:absolute;left:0;top:47px;width:60px;height:44px;z-index:70" data-back></view>',
    [[1, '「主页」打开正式俱乐部主页,默认落在「概览」(简介、主理人 / 类型 / 城市、勋章、成员、正在进行),不是帖子流。左上返回,回到工作台,刚才那层弹窗还开着。']]));

  function qrCanvas() { return '<canvas class="qr7" data-qr="1" width="120" height="120"></canvas>'; }
  // 商家主页 = 店铺装修(pages/merchant/decor)里商家自己配的那些字段,原样排出来给别人看
  S.push(scr('mer-home', '商家主页', '主题工作台', 'pages/merchant/decor 字段:coverImage / logo / name / cityRole / description / gallery / perks / ai-npc / businessTime / address / phone / coop-setting',
    '<view class="ph7 mh7">' +
    '<view class="mh7-cover"><img src="' + C + 'midlife-day.jpg"><view class="mh7-shade"></view></view>' +
    '<view class="mh7-head"' + mk(1) + '><img class="mh7-logo" src="' + C + 'flavor-tour.jpg"><view class="mh7-id"><view class="mh7-name">苏河茶室</view><view class="mh7-role">苏州河边的老派茶馆</view></view></view>' +
    '<view class="mh7-tags"><text class="on">营业中 · 10:00–22:00</text><text>茶饮</text><text>可接团</text><text>320m</text></view>' +
    '<view class="mh7-sec"><b>品牌故事</b><p>1998 年开在北苏州路,三代人守着一口老铜壶。窗外就是邮政博物馆,很多走城市路线的人在这里歇脚。</p></view>' +
    '<view class="mh7-sec"' + mk(2) + '><b>店里</b><view class="mh7-gal">' + ['time-pollution.jpg', 'old-movie.jpg', 'treasure-hunter.jpg', 'heat-escape.jpg'].map(function (g) { return '<img src="' + C + g + '">'; }).join('') + '</view></view>' +
    '<view class="mh7-sec"><b>到店权益</b><view class="mh7-perk"><img src="' + D3 + 'hot_beverage_3d.png"><view class="mr-m"><view class="mr-n">招牌茉莉冷泡一杯</view><view class="mr-s">持本主题门票到店核销</view></view></view></view>' +
    '<view class="mh7-sec"><b>店铺角色</b><view class="mh7-npc"><img src="' + D3 + 'detective.png"><view class="mh7-bub">走累了吧?先坐,茶马上好。</view></view></view>' +
    '<view class="mh7-sec"' + mk(3) + '><b>合作偏好</b><view class="kv7"><text>想要的合作</text><span>城市定向节点 · 周末带团</span></view><view class="kv7" style="border:0"><text>能接待</text><span>每场 20 人以内</span></view></view>' +
    '<view class="mh7-sec"><b>联系</b><view class="kv7"><text>北苏州路 250 号</text><span class="link7">导航</span></view><view class="mh7-qr">' + qrCanvas() + '<view class="mr-m"><view class="mr-n">门店微信</view><view class="mr-s">suhe-tea · 长按识别二维码</view><view class="mh7-acts"><view class="pill7 pill7--g" data-toast7="已复制微信号">复制微信号</view><view class="pill7" data-toast7="打开和苏河茶室的对话">发消息</view></view></view></view></view>' +
    '<view style="height:40px"></view></view>' +
    '<view style="position:absolute;left:0;top:47px;width:60px;height:44px;z-index:70" data-back></view>' + U.navBar({ overlay: true, pill: true, tint: 'light' }),
    [[1, '封面、Logo、店名、城市角色一句话,都是店铺装修里商家自己配的。'], [2, '下面依次是品牌故事、店内相册、到店权益、AI 店铺角色招呼语。'], [3, '合作偏好(合作设置页)和联系方式:地址、门店微信二维码、复制微信号、发消息。']]));

  // ---------------- 消息 · 协作邀请(收件箱 / 发件箱 + 票根式邀请卡 + 层层点进主题)----------------
  // 主题数据:邀请卡点进去能一路看到主题 → 章节 → 节点,受邀商家知道到底要接什么
  var THEMES = {
    suhe: { name: '苏州河慢慢走', kind: 'free', cover: 'time-pollution.jpg', date: '11-01 至 11-30', host: '城瘾官方', intro: '沿苏州河从四行仓库走到外滩源,三章自由探索,玩家按自己的节奏一站站解锁老上海的河岸故事。',
      chapters: [
        { n: '苏河北岸', tier: '体验档 ¥68', img: 'time-pollution.jpg', story: '1937 年的四行仓库,河对岸的灯一盏盏亮起。', nodes: [['四行仓库', '光复路 1 号', '找出墙上的弹孔数', 'time-pollution.jpg'], ['上海邮政博物馆', '北苏州路 250 号', '寄一张盖了今天邮戳的明信片', 'flavor-tour.jpg'], ['河滨大楼', '北苏州路 340 号', '在楼梯间听一段老住户的录音', 'old-movie.jpg']] },
        { n: '老城厢', tier: '引流档 ¥0', img: 'flavor-tour.jpg', story: '从城隍庙出发,找一条只剩名字的老街。', nodes: [['豫园老街', '旧校场路', '按地图找到三块老路牌', 'flavor-tour.jpg'], ['城隍庙小吃', '方浜中路 249 号', '尝一口并说出它的老名字', 'midlife-day.jpg']] },
        { n: '外滩源', tier: '体验档 ¥68', img: 'old-movie.jpg', story: '最后一章,在亚洲文会大楼里收尾。', taken: '已有商家承接', nodes: [['圆明园路', '圆明园路 97 号', '拍下五种不同的门环', 'old-movie.jpg'], ['光陆大楼', '虎丘路 146 号', '解开放映厅的谜题', 'heat-escape.jpg'], ['亚洲文会', '虎丘路 20 号', '拼出最后一页手记', 'treasure-hunter.jpg']] }] },
    movie: { name: '上海像一部旧电影', kind: 'city', cover: 'old-movie.jpg', date: '11-01 至 11-30', host: '副本人生', intro: '一条城市定向路线,4 站都在苏州河沿岸,跟着一部老电影的镜头走。',
      chapters: [{ n: '第一章 · 胶片', tier: '引流档 ¥0', img: 'old-movie.jpg', story: '放映机响了,画面从一座老仓库开始。', nodes: [['1933老场坊', '沙泾路 10 号', '找到放映机的位置', 'old-movie.jpg'], ['上海邮政博物馆', '北苏州路 250 号', '寄出电影里那封信', 'flavor-tour.jpg'], ['四行仓库', '光复路 1 号', '数出银幕上的弹孔', 'time-pollution.jpg'], ['M50创意园', '莫干山路 50 号', '在墙上找到片尾字幕', 'anomaly-coordinate.jpg']] }] } };
  var INV = [
    { box: 'in', st: 'pending', who: '城瘾官方', av: 'old-movie.jpg', time: '今天 14:20', theme: 'suhe', target: { ch: 0 }, msg: '想请你们承接第 1 章「苏河北岸」,三个节点都在你店 500 米内,玩家到店打卡、你们提供一份到店权益。' },
    { box: 'in', st: 'pending', who: '周末城市漫步团', av: 'treasure-hunter.jpg', time: '昨天 19:05', theme: 'suhe', target: null, msg: '我们在招自由探索的章节商家,还有两章空着,你们挑一章就行。' },
    { box: 'in', st: 'accepted', who: '副本人生', av: 'heat-escape.jpg', time: '10-12', theme: 'movie', target: { ch: 0, node: 1 }, msg: '第 2 站在上海邮政博物馆旁边,想请你们承接这一站,引流档。' },
    { box: 'out', st: 'pending', who: '旧书与胶片', av: 'midlife-day.jpg', time: '10-29', theme: 'movie', target: { ch: 0, node: 2 }, msg: '想请你们带团走第 3 站,周末每场 20 人以内。' }];
  function targetText(v) {
    var t = THEMES[v.theme];
    if (!v.target) return '任选一章 · ' + t.chapters.filter(function (c) { return !c.taken; }).length + ' 章可选';
    var c = t.chapters[v.target.ch];
    if (v.target.node == null) return '第 ' + (v.target.ch + 1) + ' 章 · ' + c.n + ' · ' + c.nodes.length + ' 个节点';
    return '第 ' + (v.target.node + 1) + ' 站 · ' + c.nodes[v.target.node][0];
  }
  function invCard(v) {
    var i = INV.indexOf(v), t = THEMES[v.theme], out = v.box === 'out';
    var acts = out ? '<view class="ic7-acts"><view class="ic7-b" data-act="invReply" data-i="' + i + '">发消息</view><view class="ic7-b" data-toast7="已撤回">撤回</view></view>'
      : v.st === 'pending' ? '<view class="ic7-acts"><view class="ic7-b" data-act="invReply" data-i="' + i + '">回复</view><view class="ic7-b" data-act="invNo" data-i="' + i + '">拒绝</view><view class="ic7-b ic7-b--on" data-act="invYes" data-i="' + i + '">同意</view></view>'
      : '<view class="ic7-acts"><view class="ic7-b" data-act="invReply" data-i="' + i + '">回复</view><view class="ic7-b ic7-b--on ic7-b--wide" data-go="' + (t.kind === 'free' ? 'join-chapter' : 'join-node') + '">进入工作台</view></view>';
    var stTxt = out ? '<text class="tk7-st w">等对方回复</text>' : v.st === 'pending' ? '<text class="tk7-st n">待回复</text>' : '<text class="tk7-st ok">已同意</text>';
    return '<view class="tk7"' + (i ? '' : mk(1)) + '>' +
      '<view class="tk7-top"><img class="tk7-av" src="' + C + v.av + '"><view class="mr-m"><view class="tk7-who">' + (out ? '你 → ' + v.who : v.who) + '</view><view class="mr-s">' + v.time + '</view></view>' + stTxt + '</view>' +
      '<view class="tk7-body" data-act="invOpen" data-i="' + i + '"><img class="tk7-cv" src="' + C + t.cover + '"><view class="tk7-m"><text class="tk7-k">' + (t.kind === 'free' ? '自由探索' : '城市定向') + '</text><view class="tk7-n">' + t.name + '</view><view class="tk7-t">' + I('pin', 22) + '<text>' + targetText(v) + '</text></view></view><i class="tk7-chev"></i></view>' +
      '<view class="tk7-perf"><i></i><i></i></view>' +
      '<view class="tk7-msg">' + v.msg + '</view>' + acts + '</view>';
  }
  var MSGS = { in: [['城瘾小助手', '你的主题「上海像一部旧电影」招商截止还有 2 天', '14:32', 'old-movie.jpg', 1], ['苏河茶室', '好的,周六下午我们留两张桌子', '13:05', 'midlife-day.jpg', 2], ['副本人生', '第 2 站的打卡照片收到了,很好看', '昨天', 'heat-escape.jpg', 0], ['旧书与胶片', '下周三可以碰一下吗?', '10-27', 'treasure-hunter.jpg', 0]],
    out: [['旧书与胶片', '你:想请你们带团走第 3 站', '10-29', 'treasure-hunter.jpg', 0], ['一尺花园', '你:章节邀约已发出,等你们回复', '10-26', 'flavor-tour.jpg', 0]] };
  function msgList(box) { return MSGS[box].map(function (m) { return '<view class="ms7-r"><img src="' + C + m[3] + '"><view class="mr-m"><view class="mr-n">' + m[0] + '</view><view class="mr-s">' + m[1] + '</view></view><view class="ms7-e"><text>' + m[2] + '</text>' + (m[4] ? '<b>' + m[4] + '</b>' : '') + '</view></view>'; }).join(''); }
  function invList(box) {
    var rows = INV.filter(function (v) { return v.box === box; });
    return rows.map(invCard).join('');
  }
  S.push(scr('msg', '消息 · 协作邀请', '消息', 'subpackageB/pages/im/list/index.wxml + pages/coop/invite-detail',
    '<view class="sv"><view class="theme-merchant pg-im" style="background:#F3F4F4;min-height:844px"><view style="height:96px"></view>' +
    '<view class="bx7"' + mk(2) + '><i class="bx7-pill"></i><view class="bx7-t on" data-act="box" data-b="in">收件箱<b>2</b></view><view class="bx7-t" data-act="box" data-b="out">发件箱</view></view>' +
    '<view class="ic7-h">协作邀请<span id="invN">2 条待回复</span></view><scroll-view scroll-x class="tk7-row" id="invList">' + invList('in') + '</scroll-view>' +
    '<view class="ic7-h" style="margin-top:6px">消息</view><view class="ms7" id="msgList">' + msgList('in') + '</view>' +
    '<view style="height:40px"></view></view></view>' + U.navBar({ title: '消息' }),
    [[1, '协作邀请和消息放在同一页:上面一排邀请函横滑,下面是消息会话列表。邀请卡做成一张「邀请函票根」:上面是谁邀请的、什么时候、状态;中间票面是主题封面 + 类型 + 主题名,下面一行用定位图标写清邀请你接的是哪一章 / 哪一站(或「任选一章」);撕线下面是邀请语和按钮。点票面 → 邀约详情弹窗 → 「查看主题」再叠一层看整个主题 → 点章节看节点 → 点节点看玩法和地点,受邀的那一章 / 站标着「邀请你承接」。'], [2, '收件箱 / 发件箱换成胶囊分段:白色滑块带弹性滑过去,未处理数用小黑圆标;发件箱的卡是「你 → 对方」,按钮换成发消息 / 撤回。']]));

  // 邀约详情 → 主题 → 章节 → 节点
  function themeSheet(key, hl) {
    var t = THEMES[key];
    open({ id: 'theme', title: t.name, back: true, level: 'full', fixed: true, body:
      '<view class="th7-cv"><img src="' + C + t.cover + '"><view class="th7-cvt"><span>' + (t.kind === 'free' ? '自由探索' : '城市定向') + ' · ' + t.date + '</span><b>' + t.name + '</b></view></view>' +
      '<view class="th7-intro">' + t.intro + '</view>' + '<view class="kv7"><text>主办</text><span>' + t.host + '</span></view>' +
      '<view class="lbl7"><text>' + (t.kind === 'free' ? '章节' : '路线') + '</text><text>' + t.chapters.length + ' 章</text></view>' +
      t.chapters.map(function (c, k) {
        var mine = hl && hl.ch === k && hl.node == null;
        return '<view class="th7-ch' + (mine ? ' hl' : '') + '" data-act="chOpen" data-t="' + key + '" data-c="' + k + '"><img src="' + C + c.img + '"><view class="mr-m"><view class="mr-n">' + (t.kind === 'free' ? '第 ' + (k + 1) + ' 章 · ' : '') + c.n + '</view><view class="mr-s">' + c.nodes.length + ' 个节点 · ' + c.tier + (c.taken ? ' · ' + c.taken : '') + '</view></view>' + (mine ? '<text class="hl7">邀请你承接</text>' : '') + '<i class="tk7-chev"></i></view>';
      }).join('') });
    themeSheet._hl = hl;
  }
  function chapterSheet(key, k) {
    var t = THEMES[key], c = t.chapters[k], hl = themeSheet._hl || {};
    open({ id: 'chap', title: (t.kind === 'free' ? '第 ' + (k + 1) + ' 章 · ' : '') + c.n, back: true, level: 'full', fixed: true, body:
      '<view class="th7-story">' + c.story + '</view><view class="kv7"><text>档位</text><span>' + c.tier + '</span></view>' +
      '<view class="lbl7"><text>节点</text><text>' + c.nodes.length + ' 个</text></view>' +
      c.nodes.map(function (n, j) {
        var mine = hl.ch === k && hl.node === j;
        return '<view class="th7-nd' + (mine ? ' hl' : '') + '" data-act="ndOpen" data-t="' + key + '" data-c="' + k + '" data-n="' + j + '"><i>' + (j + 1) + '</i><view class="mr-m"><view class="mr-n">' + n[0] + '</view><view class="mr-s">' + n[1] + '</view></view>' + (mine ? '<text class="hl7">邀请你承接</text>' : '') + '<i class="tk7-chev"></i></view>';
      }).join('') });
  }
  function nodeSheet(key, k, j) {
    var n = THEMES[key].chapters[k].nodes[j];
    open({ id: 'node', title: n[0], back: true, level: 'full', fixed: true, body:
      '<view class="th7-cv" style="height:150px"><img src="' + C + n[3] + '"></view>' +
      '<view class="kv7"><text>地点</text><span>' + n[1] + '</span></view>' +
      '<view class="lbl7"><text>这一站的玩法</text></view><view class="th7-play"><img src="' + D3 + 'performing_arts_3d.png"><view class="mr-m"><view class="mr-n">' + n[2] + '</view><view class="mr-s">约 8 分钟 · 到店完成</view></view></view>' +
      '<view class="lbl7"><text>承接的商家要做什么</text></view>' + kvs([['接待', '玩家到店出示入场码,你扫码核销'], ['到店权益', '你自己定,从店铺装修的权益里选'], ['每场人数', '你自己定上限']]) });
  }

  // ---------------- 账户收益 · 更多(Threads)----------------
  // ---------------- 账户收益(参考 60fps.design:Circle 余额滚动、Revolut 分段 + 列表伸缩、CRED 叠卡展开)----------------
  var EARN_REC = [['上海像一部旧电影 · 第 2 站', '今天 · 核销 3 份', '+21.00', 'old-movie.jpg', 7], ['苏州河慢慢走 · 第 1 章', '10-28 · 核销 14 份', '+147.00', 'time-pollution.jpg', 7], ['提现到微信零钱', '10-25 · 已到账', '−500.00', '', 30], ['十月结算', '10-05 · 九月核销', '+1,000.00', 'heat-escape.jpg', 30], ['邀请奖励 · 苏河茶室', '09-18', '+20.00', '', 0], ['提现到微信零钱', '09-02 · 已到账', '−300.00', '', 0]];
  var EARN_SRC = [['承接分润', '你承接的站或章节,按核销分账', 'money_bag_3d.png', '¥1,147.00', '#E8F6EE'], ['到店核销', '玩家到店核销后按月结给你', 'ticket_3d.png', '¥113.00', '#FFF3E0'], ['邀请奖励', '邀请的商家完成首单', 'wrapped_gift_3d.png', '¥20.00', '#F1ECFF']];
  function earnRows(win) {
    return EARN_REC.filter(function (r) { return win === 0 || (r[4] && r[4] <= win); }).map(function (r) {
      var up = r[2][0] === '+';
      return '<view class="wr7"><view class="wr7-ic">' + (r[3] ? '<img src="' + C + r[3] + '">' : I(up ? 'gift' : 'arrow-right', 30)) + '</view><view class="mr-m"><view class="mr-n">' + r[0] + '</view><view class="mr-s">' + r[1] + '</view></view><text class="wr7-v' + (up ? ' up' : '') + '">' + r[2] + '</text></view>';
    }).join('');
  }
  function earnPage(has) {
    // 上半屏保持原页面不动(主卡 + 四个按钮);只重做下半屏,照 Revolut 账户首页:一张交易卡(3 笔 + 查看全部),下面两块小卡
    var tx = [['上海像一部旧电影 · 第 2 站', '今天 14:20 · 核销 3 份', '+¥21.00', 'old-movie.jpg', ''], ['提现到微信零钱', '10-25 · 已到账', '−¥500.00', '', 'out'], ['苏州河慢慢走 · 第 1 章', '10-21 · 十月结算', '+¥1,000.00', 'time-pollution.jpg', '']];
    return '<view class="sv"><view class="er-page theme-merchant pg-er" style="padding-top:87px"><view class="c-page-title"><view class="pt pt--left"><view class="pt__title">账户收益</view></view></view><view class="c-asset-earnings"><view class="ae"><view class="ae-hero"><view class="ae-card"><text class="ae-card__label">账户收益（元）</text><text class="ae-card__amount">' + (has ? '¥1,280.00' : '¥0.00') + '</text></view>' +
      '<view class="ae-actions">' + [['arrow-right', '提现', 1], ['list', '明细'], ['sort', '分析'], ['more', '更多']].map(function (a) { return '<view class="ae-action"' + (a[1] === '更多' ? ' data-act="earnMore"' : '') + '><view class="ae-action__ic' + (a[2] ? ' ae-action__ic--primary' : '') + '">' + I(a[0], 40) + '</view><text>' + a[1] + '</text></view>'; }).join('') + '</view></view></view></view>' +
      '<view class="rv7"' + mk(1) + '>' + (has ? tx.map(function (r) { return '<view class="rv7-r"><view class="rv7-ic' + (r[4] ? ' o' : '') + '">' + (r[3] ? '<img src="' + C + r[3] + '">' : I('arrow-right', 30)) + '</view><view class="mr-m"><view class="mr-n">' + r[0] + '</view><view class="mr-s">' + r[1] + '</view></view><text class="rv7-v' + (r[2][0] === '+' ? ' up' : '') + '">' + r[2] + '</text></view>'; }).join('') + '<view class="rv7-all">查看全部</view>'
        : '<view class="rv7-none"><view class="rv7-ic o">' + I('list', 30) + '</view><b>还没有交易</b><text>收益到账、提现都会列在这里</text></view>') + '</view>' +
      '<view class="rv7-tiles"' + mk(2) + '><view class="rv7-t"><text>本月入账</text><b>' + (has ? '+¥1,167' : '¥0') + '</b>' + (has ? '<svg viewBox="0 0 100 28" class="rv7-sp"><polyline points="0,24 12,22 24,23 36,16 48,18 60,10 72,13 84,6 100,4" /></svg>' : '<span>—</span>') + '</view>' +
      '<view class="rv7-t"><text>待结算</text><b>' + (has ? '¥1,080' : '¥0') + '</b><span>' + (has ? '11-05 到账' : '有核销后按月结') + '</span></view></view>' +
      '<view style="height:40px"></view></view></view>' + U.navBar({});
  }
  var EARN_NOTES = [[1, '上半屏(主卡和四个按钮)保持原样。下半屏照 Revolut 账户首页:一张白色交易卡,最近 3 笔(头像 / 标题 / 时间,金额右对齐,进账绿色),底部居中「查看全部」;没有交易时卡里只放一个小图标和一句话,不再是大插画。'], [2, '下面两块并排小卡:本月入账(带一条迷你走势线)、待结算(写下次到账日)。']];
  S.push(scr('earn-more', '账户收益 · 还没有收益', '账户收益', 'components/cy/scene-asset-earnings/index.wxml', earnPage(false), EARN_NOTES));
  S.push(scr('earn-has', '账户收益 · 有收益', '账户收益', 'components/cy/scene-asset-earnings/index.wxml', earnPage(true), EARN_NOTES));

  // ---------------- 店铺参谋:其他区块照原样,只改「经营解读」----------------
  // 经营解读的三段(总结 / 机会 / 问题)来自 ai.summary / ai.opportunity / ai.problem,做成一叠可翻的卡
  // 经营解读:顶部一段总结(ai.summary),下面机会 / 问题做成一排横滑卡,不再用标签切换
  var READS = [
    { k: '机会', t: '承接一条周末路线', d: '「上海像一部旧电影」第 2 站离你 300 米,周末每场 20 人,正好补上下午的空位。', bg: '#DDF3E6', fg: '#14532D', dot: '#22A55B', art: 'pin', go: '去看看' },
    { k: '机会', t: '工作日晚上还空着', d: '附近两条夜游路线在招商,晚上 7–9 点能带来客流。', bg: '#E4EEFF', fg: '#163B7A', dot: '#3B76E8', art: 'arrow', go: '去看看' },
    { k: '问题', t: '到店后没人复购', d: '41 位里只有 3 位回来过。到店权益只送一杯茶,没有下次来的理由。', bg: '#FFE9D6', fg: '#6B2F05', dot: '#F07A1A', art: 'ticket', go: '去设权益' },
    { k: '问题', t: '周日核销慢', d: '周日下午平均每位等 6 分钟才核销,有 4 位没核销就走了。', bg: '#FDE2E4', fg: '#7A1622', dot: '#E5484D', art: 'chat', go: '看客户' }];
  function readDeck() {
    return '<view class="rd8"' + mk(2) + '><view class="rd8-sum"><text class="rd8-k">这 30 天</text><view class="rd8-t">客人集中在周末下午:41 位到店,七成在周六、周日 14–17 点,工作日几乎没人。</view><view class="rd8-c"><text><i style="background:#22A55B"></i>2 个机会</text><text><i style="background:#F07A1A"></i>2 个问题</text></view></view>' +
      '<scroll-view scroll-x class="rd8-row">' + READS.map(function (r) {
        return '<view class="rd8-card" style="background:' + r.bg + ';color:' + r.fg + '"><canvas width="520" height="240" data-art="' + r.art + '" data-dot="' + r.dot + '"></canvas><view class="rd8-b"><text class="rd8-tag" style="background:' + r.fg + '">' + r.k + '</text><view class="rd8-h">' + r.t + '</view><view class="rd8-d">' + r.d + '</view><view class="rd8-go" style="color:' + r.fg + '" data-toast7="' + r.go + '">' + r.go + ' ›</view></view></view>';
      }).join('') + '</scroll-view><view class="ai-source7">解读依据今天 06:00 的经营数据</view></view>';
  }
  S.push(scr('ins', '店铺参谋 · 经营解读', '店铺参谋', 'pages/merchant/marketing/ai-insight/index.wxml',
    '<view class="sv"><view class="page theme-merchant pg-ins"><view style="height:87px"></view><view class="body">' +
    '<view class="greet"><view class="greet-b"><view class="greet-line" style="font-size:24px;font-weight:600"><text>下午好，</text><text>星巴克咖啡厅</text></view></view>' + U.avatar(IMG + 'mer1.jpg', 112, 'greet-avatar') + '</view>' +
    '<view class="hero value-card"><view class="hero-top"><view class="hero-main"><text class="hero-k">近30天，城瘾为你带来</text><view class="hero-big"><text class="hero-num">41</text><text class="hero-unit">位玩家到店</text></view></view></view>' +
    '<view class="seg"><view class="seg-btn">近7天</view><view class="seg-btn seg-btn--on">近30天</view><view class="seg-btn">近90天</view></view></view>' +
    '<view class="sec-head"' + mk(1) + '><text class="sec-title">为你推荐的主题</text></view><scroll-view class="tscroll" scroll-x><view class="trow">' + [['自由探索合作验收0929', 'heat-escape.jpg'], ['外滩夜行', 'time-pollution.jpg']].map(function (t) {
      return '<view class="tcard"><view class="tcard-cover"><img class="tcard-img" src="' + C + t[1] + '"><view class="tcard-shade"></view><view class="tcard-over"><view class="tcard-title"><text class="tcard-name">' + t[0] + '</text><view class="tcard-tag">招商中</view></view><text class="tcard-reason">平台招商中的自由探索主题</text><view class="tcard-foot"><view class="tcard-cta">看看</view></view></view></view></view>'; }).join('') + '</view></scroll-view>' +
    '<view class="sec-head"><text class="sec-title">经营解读</text></view>' + readDeck() +
    '<view style="height:40px"></view></view></view></view>' + U.navBar({ title: '店铺参谋' }),
    [[1, '问候、主卡、推荐主题等其他区块都照原页面,不改。'], [2, '只改「经营解读」:上面一段总结(这 30 天怎么样),标出几个机会、几个问题;下面机会和问题做成一排横滑卡,不再用标签切换。每张一个颜色 + 会流动的点阵图,左上角标「机会 / 问题」,底部一个去处理的入口。']]));

  // ---------------- AI 店铺角色 首次介绍 ----------------
  S.push(scr('npc', 'AI 店铺角色 · 首次介绍', 'AI 店铺角色', 'pages/merchant/decor/ai-npc/index.wxml',
    '<view class="sv"><view class="dc-page theme-merchant pg-npc"><view style="height:87px"></view><view class="dc-content"><text class="dc-brand-title">AI 店铺角色</text><view class="npc-hero"><view class="npc-preview" style="display:flex;align-items:center;justify-content:center;background:#4c6fd8"><img src="' + D3 + 'detective.png" style="width:70%;height:70%"></view><text class="dc-name">当兵的</text></view>' +
    '<view style="text-align:center;margin-top:16px;font-size:13px;color:var(--o-mute)" data-act="aiIntro">再看一遍介绍</view></view></view></view>' + U.navBar({}),
    [[1, '只在第一次进入时弹。四步图都是 AI 角色相关的 3D 物件(机器人分身 → 挥手打招呼 + 对话气泡 → 菜单 + 闹钟 → 名牌 + 性格面具),翻页时旧物件缩小转走、新物件弹出,底下光晕换色。不用 logo。']]));

  document.getElementById('screens').innerHTML = S.join('');

  // ================= 弹层引擎(10-23 规范)=================
  var CAP = 756, LV = { peek: 253, half: 506, full: 756 };
  var stacks = {};
  function cur() { return PROTO.cur(); }
  function layer() { return cur().querySelector('.os-layer'); }
  function st() { var id = cur().getAttribute('data-id'); return stacks[id] || (stacks[id] = []); }
  function toast(t) { var el = cur().querySelector('.toast7'); el.querySelector('text').textContent = t; el.classList.add('in'); clearTimeout(toast._t); toast._t = setTimeout(function () { el.classList.remove('in'); }, 1800); }
  function open(def) {
    var L = layer(), s = st();
    var mask = L.querySelector('.os-mask');
    if (!mask) { mask = document.createElement('view'); mask.className = 'os-mask'; mask.setAttribute('data-act', 'osTop'); L.appendChild(mask); }
    var el = document.createElement('view'); el.className = 'os' + (def.inset ? ' os--inset' : ''); el.setAttribute('data-sid', def.id || '');
    var hd = def.title != null ? '<view class="os-hd' + (def.center ? ' os-hd--center' : '') + '">' + (s.length && def.back ? '<view class="os-bk" data-act="osTop">' + I('back', 44) + '</view>' : '') + '<text class="os-t">' + def.title + '</text><view class="os-x" data-act="osAll">' + I('close-sm', 44) + '</view></view>' : '';
    el.innerHTML = '<view class="os-grip"><i></i></view>' + hd + '<view class="os-b">' + def.body + '</view>' + (def.foot ? '<view class="os-f">' + def.foot + '</view>' : '');
    var max = def.level ? LV[def.level] : CAP;
    el.style.maxHeight = Math.min(max, CAP) + 'px';
    if (def.fixed) el.style.height = Math.min(max, CAP) + 'px';
    L.appendChild(el);
    s.forEach(function (x) { x.classList.add('under'); });
    s.push(el);
    requestAnimationFrame(function () { requestAnimationFrame(function () { mask.classList.add('in'); el.classList.add('in'); }); });
    if (def.after) def.after(el);
    setTimeout(PROTO.placeMarks, 340);
    return el;
  }
  function closeTop() { var s = st(); var el = s.pop(); if (!el) return; el.classList.remove('in'); setTimeout(function () { el.remove(); }, 300); if (s.length) s[s.length - 1].classList.remove('under'); else layer().querySelector('.os-mask').classList.remove('in'); }
  function closeAll() { var s = st(); while (s.length) closeTop(); }
  function topSheet() { var s = st(); return s[s.length - 1]; }

  // ---------------- 弹层内容 ----------------
  function clubRows() {
    return clubs().map(function (c, i) {
      var free = mode() === 'free', taken = free && clubs().some(function (x) { return x.st === 'ok'; });
      var right = c.st === 'ok' ? '<text class="chip7 chip7--ok">' + (free ? '已承办' : '已接受') + '</text>' : (c.st === 'wait' ? '<text class="chip7 chip7--warn"><i class="spin7"></i>等回复</text>' : '<view class="pill7' + (taken ? ' pill7--g dis7' : '') + '" data-act="inviteClub" data-i="' + i + '">邀约</view>');
      return '<view class="mr"><img class="mr-av" src="' + C + c.img + '" data-go="club-home"><view class="mr-m" data-go="club-home"><view class="mr-n">' + c.n + '</view><view class="mr-s">' + c.m + '</view></view>' + right + '<view class="pill7 pill7--g" data-go="club-home">主页</view></view>';
    }).join('');
  }
  function clubBody() {
    var ok = clubs().filter(function (c) { return c.st === 'ok'; }).length, w = clubs().filter(function (c) { return c.st === 'wait'; }).length;
    if (mode() === 'free') return '<view class="sum7"><view><b>' + ok + '/1</b><span>承办俱乐部</span></view><view><b>' + w + '</b><span>等回复</span></view><view><b>' + (ok ? '可开售' : '未开售') + '</b><span>售票</span></view></view><view class="lbl7"><text>附近的俱乐部</text></view><view id="clubRows">' + clubRows() + '</view>';
    return '<view class="sum7"><view><b>' + ok + '</b><span>已接受</span></view><view><b>' + w + '</b><span>等回复</span></view><view><b>15%</b><span>带团分成</span></view></view><view class="lbl7"><text>附近的俱乐部</text></view><view id="clubRows">' + clubRows() + '</view>';
  }
  function inviteSheet(kind, name, sub, img, go) {
    var free = kind === 'club' && mode() === 'free';
    var txt = free ? '你好,我们发布了自由探索「上海像一部旧电影」,想请你们来承办:带玩家开局、现场接待。你们接受后就能开始售票。' : kind === 'club' ? '你好,我们的主题「上海像一部旧电影」11 月 1 日开放,4 站都在苏州河沿岸。想邀请你们带团来玩,带团分成 15%,周末每场最多 20 人。' : '你好,我们在做主题「上海像一部旧电影」,第 2 站在上海邮政博物馆,想请你们承接这一站。按章节约定是引流档,玩家到店打卡、你提供一杯招牌饮品。';
    open({ id: 'invite', title: (free ? '邀请承办 · ' : '邀约') + name, back: true, level: 'full', body:
      '<view class="mr" style="border:0"><img class="mr-av' + (kind === 'club' ? '' : ' mr-av--sq') + '" src="' + C + img + '"><view class="mr-m"><view class="mr-n">' + name + '</view><view class="mr-s">' + sub + '</view></view><text class="link7" data-go="' + go + '">查看主页</text></view>' +
      '<view class="lbl7"><text>邀约语</text><text>' + txt.length + '/200</text></view><textarea class="field7" id="invTxt" maxlength="200">' + txt + '</textarea>' +
      '<view class="kv7"><text>' + (free ? '承办报酬' : kind === 'club' ? '带团分成' : '本站权益档') + '</text><span>' + (free ? '按平台统一条款 · 开售时锁定' : kind === 'club' ? '15% · 按核销结算' : '引流型 · 无分成(章节固定)') + '</span></view>',
      foot: '<view class="os-btn" data-act="sendInvite" data-kind="' + kind + '">发送邀约</view>' });
  }
  function stationRows() {
    return STATIONS.map(function (s, i) {
      var who = s.kind === 'self' ? '自己承接' : (s.kind === 'mer' ? '已招 · ' + s.who : (s.apply ? '1 个申请待处理' : '还没人接'));
      var right = s.kind ? (s.kind === 'mer' ? '<view class="pill7 pill7--g" data-go="mer-home">主页</view>' : '<text class="chip7 chip7--g">自己的店</text>')
        : '<view class="st7-btns"><view class="pill7 pill7--g" data-act="selfTake" data-i="' + i + '">自己承接</view><view class="pill7" data-act="station" data-i="' + i + '">招商</view></view>';
      return '<view class="st7"><img class="st7-img" src="' + C + s.img + '"><view class="mr-m"><view class="mr-n">第 ' + (i + 1) + ' 站 · ' + s.n + '</view><view class="mr-s">' + who + '</view></view>' + right + '</view>';
    }).join('');
  }
  function applyRows() {
    if (!APPLY.length) return '<view class="mr-s" style="padding:12px 0">没有待处理的申请</view>';
    return APPLY.map(function (a, i) { return '<view class="mr" style="align-items:flex-start"><img class="mr-av mr-av--sq" src="' + C + a.img + '" data-go="mer-home"><view class="mr-m"><view class="mr-n">' + a.n + ' <text class="mr-s">申请 ' + (mode() === 'free' ? '第 2 章 · 老城厢' : a.st) + '</text></view><view class="mr-s" style="white-space:normal;margin-top:4px">' + a.msg + '</view><view style="display:flex;gap:8px;margin-top:10px"><view class="pill7 pill7--g" data-act="applyNo" data-i="' + i + '">婉拒</view><view class="pill7" data-act="applyOk" data-i="' + i + '">通过</view><view class="pill7 pill7--g" data-go="mer-home">主页</view></view></view></view>'; }).join('');
  }
  function chapterRows() {
    return CHAPTERS.map(function (c, i) {
      var who = c.kind === 'self' ? '自己承接' : (c.kind === 'mer' ? '已招 · ' + c.who : (c.apply ? '1 个申请待处理' : '还没人接'));
      var right = c.kind ? (c.kind === 'mer' ? '<view class="pill7 pill7--g" data-go="mer-home">主页</view>' : '<text class="chip7 chip7--g">自己的店</text>')
        : '<view class="st7-btns"><view class="pill7 pill7--g" data-act="selfTakeCh" data-i="' + i + '">自己承接</view><view class="pill7" data-act="chapterRec" data-i="' + i + '">招商</view></view>';
      return '<view class="st7"><img class="st7-img" src="' + C + c.img + '"><view class="mr-m"><view class="mr-n">第 ' + (i + 1) + ' 章 · ' + c.n + '</view><view class="mr-s">' + c.nodes.length + ' 个节点 · ' + who + '</view></view>' + right + '</view>';
    }).join('');
  }
  function recruitBody() {
    if (mode() === 'free') {
      var gc = CHAPTERS.filter(function (c) { return c.kind; }).length;
      return '<view class="sum7"><view><b>' + gc + '/3</b><span>已招章节</span></view><view><b>' + APPLY.length + '</b><span>待处理申请</span></view><view><b>按章</b><span>权益档</span></view></view>' +
        '<view class="lbl7"><text>各章</text></view><view id="chRows">' + chapterRows() + '</view><view class="lbl7"><text>收到的申请</text></view><view id="apRows">' + applyRows() + '</view>';
    }
    var got = STATIONS.filter(function (s) { return s.kind; }).length;
    return '<view class="sum7"><view><b>' + got + '/4</b><span>已招站点</span></view><view><b>' + APPLY.length + '</b><span>待处理申请</span></view><view><b>引流</b><span>本章权益档</span></view></view>' +
      '<view class="lbl7"><text>各站</text></view><view id="stRows">' + stationRows() + '</view><view class="lbl7"><text>收到的申请</text></view><view id="apRows">' + applyRows() + '</view>';
  }
  function custBody(filter) {
    var rows = CUST.filter(function (c) { return !filter || c[2] === filter; });
    return '<view class="sum7"><view><b>36</b><span>已售</span></view><view><b>22</b><span>还没到店</span></view><view><b>14</b><span>已核销</span></view></view>' +
      '<view class="fchips">' + [['', '全部'], ['wait', '还没到店'], ['ok', '已核销']].map(function (f) { return '<view class="fchip' + (f[0] === (filter || '') ? ' on' : '') + '" data-act="custF" data-f="' + f[0] + '">' + f[1] + '</view>'; }).join('') + '</view>' +
      rows.map(function (c) { return '<view class="mr"><view class="mr-av" style="display:flex;align-items:center;justify-content:center;font-weight:600;color:var(--o-ink2)">' + c[0][0] + '</view><view class="mr-m"><view class="mr-n">' + c[0] + '</view><view class="mr-s">' + c[1] + '</view></view><text class="chip7 ' + (c[2] === 'ok' ? 'chip7--ok">已核销' : 'chip7--warn">未到店') + '</text></view>'; }).join('') +
      (/^j/.test(mode()) ? '' : '<view class="entry7" data-act="broadcast"' + mk(1) + '><b>定向广播</b><text class="mr-s">发给本主题的买票玩家</text><view class="tl-chev"></view></view>');
  }
  function bcastBody() {
    return '<view class="lbl7" style="margin-top:4px"><text>发给谁</text></view><view class="fchips" id="bcScope">' + [['全部买票玩家', 36], ['还没到店', 22], ['已核销', 14]].map(function (s, i) { return '<view class="fchip' + (i ? '' : ' on') + '" data-act="bcScope" data-n="' + s[1] + '">' + s[0] + '</view>'; }).join('') + '</view>' +
      '<view class="lbl7"><text>内容</text><text>0/200</text></view><textarea class="field7" placeholder="例:周六下雨,第三站改到室内,入口在西门"></textarea><view class="kv7"><text>发送前预览</text><span id="bcN">36 人</span></view>';
  }
  function moreBody(open) {
    return '<view class="tl">' + [['编辑主题', 'edit'], ['权益票夹', ''], ['票价与主题内容', '']].map(function (r) { return '<view class="tl-r" data-toast7="' + (r[1] ? '进入主题专业配置' : '打开' + r[0]) + '"><text class="l">' + r[0] + '</text><view class="tl-chev"></view></view>'; }).join('') +
      '<view class="tl-r"><text class="l">开放商家承接申请<small>打开后附近商家能在合作中心看到并申请</small></text><view class="sw7' + (open ? ' on' : '') + '" data-act="sw7"></view></view>' +
      '<view class="tl-r red" data-toast7="二次确认后结束本章"><text class="l">结束本章</text></view></view>';
  }
  var TIERS = { all: { sold: 36, price: '—', parts: [['主办', 40, '#A5A6E8', 168.0], ['承接商家', 35, '#F2C46B', 147.0], ['带团俱乐部', 15, '#7FD1A6', 63.0], ['平台', 10, '#5A5A60', 42.0]] },
    exp: { sold: 24, price: '¥68', parts: [['主办', 40, '#A5A6E8', 168.0], ['承接商家', 35, '#F2C46B', 147.0], ['带团俱乐部', 15, '#7FD1A6', 63.0], ['平台', 10, '#5A5A60', 42.0]] },
    lead: { sold: 12, price: '¥0', parts: [['承接商家', 100, '#F2C46B', 0]] } };
  // 档位不是商家能改的:按章节在开售时定死。这里只列这位商家在本主题真有的档,只有一档就不给切换。
  var MY_TIERS = [['exp', '第 1 章 · 体验档'], ['lead', '第 3 章 · 引流档']];
  function profitBody(k) {
    var t = TIERS[k], idx = MY_TIERS.map(function (x) { return x[0]; }).indexOf(k);
    return '<view class="pf-lock">' + I('lock', 24) + '<text>档位按章节在开售时定好,不能改</text></view>' + (MY_TIERS.length < 2 ? '' : '<view class="pf-seg"><i style="width:calc((100% - 6px)/' + MY_TIERS.length + ');transform:translateX(' + (idx * 100) + '%)"></i>' + MY_TIERS.map(function (x) { return '<div class="' + (x[0] === k ? 'on' : '') + '" data-act="pfTier" data-k="' + x[0] + '">' + x[1] + '</div>'; }).join('') + '</view>') +
      '<view class="pf-hero"><view class="pf-k">本主题已入账</view><view class="pf-big">¥420.00</view><view class="pf-sub"><text>待结算 <b>¥1,080.00</b></text><text>已售 <b>' + t.sold + ' 份</b></text></view></view>' +
      '<view class="pf-flow"' + mk(1) + '><view class="pf-flow-h"><text>每卖出 1 份' + (t.price !== '—' ? ' · <b>' + t.price + '</b>' : '') + '</text><text>怎么分</text></view><view class="pf-bar">' + t.parts.map(function (p, i) { return '<div style="flex:' + p[1] + ';background:' + p[2] + ';animation-delay:' + (i * 90) + 'ms"><span>' + p[1] + '%</span></div>'; }).join('') + '</view>' +
      '<view class="pf-legend">' + t.parts.map(function (p) { return '<div><i style="background:' + p[2] + '"></i>' + p[0] + '<b>' + p[1] + '%</b></div>'; }).join('') + '</view></view>' +
      '<view class="lbl7"><text>各方到手</text><text>截至 10-30</text></view>' + t.parts.map(function (p) { return '<view class="pf-party"><view class="mr-av" style="background:' + p[2] + ';width:36px;height:36px"></view><view class="mr-m"><view class="mr-n">' + p[0] + '</view><view class="mr-s">' + p[1] + '% · 按核销结算</view></view><view class="r"><b>¥' + p[3].toFixed(2) + '</b><span>已入账</span></view></view>'; }).join('') +
      '<view class="lbl7"><text>结算</text></view>' + steps([{ label: '10 月 · 已结算', time: '11-05 到账', st: 'done' }, { label: '11 月 · 结算中', st: 'run', tag: '12-05 到账', tagKind: 'g' }, { label: '12 月', st: 'todo' }]) +
      '<view class="mr-s" style="text-align:center;margin-top:8px">示例比例 · 真实比例读主题条款快照</view>';
  }

  // ---------------- 交互 ----------------
  window.ACT = {
    osTop: function () { closeTop(); }, osAll: function () { closeAll(); },
    'dw-club': function () { open({ id: 'club', title: mode() === 'free' ? '邀请俱乐部承办' : '合作俱乐部', level: 'full', fixed: true, body: clubBody() }); },
    'dw-recruit': function () { open({ id: 'recruit', title: '招商', level: 'full', fixed: true, body: recruitBody() }); },
    'dw-cust': function () { open({ id: 'cust', title: '客户', level: 'full', fixed: true, body: custBody('') }); },
    'dw-more': function () { open({ id: 'more', title: '更多', center: true, level: 'half', body: moreBody(false) }); },
    'dw-profit': function () { open({ id: 'profit', title: '分润明细', level: 'full', fixed: true, body: profitBody('exp') }); },
    scan: function () { toast('打开扫码核销'); }, publish: function () { toast('进入发布确认'); }, supply: function () { toast('进入补供给'); },
    inviteClub: function (el) { var c = clubs()[+el.getAttribute('data-i')]; inviteSheet('club', c.n, c.m, c.img, 'club-home'); },
    station: function (el) { var s = STATIONS[+el.getAttribute('data-i')];
      open({ id: 'near', title: '第 ' + (+el.getAttribute('data-i') + 1) + ' 站 · 招商', back: true, level: 'full', fixed: true, body: '<view class="st7" style="border:0"><img class="st7-img" src="' + C + s.img + '"><view class="mr-m"><view class="mr-n">' + s.n + '</view><view class="mr-s">权益档按章节固定:引流型 · 无分成</view></view></view><view class="lbl7"><text>附近 1 公里的商家</text></view>' +
        NEAR.map(function (m, i) { return '<view class="mr"><img class="mr-av mr-av--sq" src="' + C + m.img + '" data-go="mer-home"><view class="mr-m" data-go="mer-home"><view class="mr-n">' + m.n + '</view><view class="mr-s">' + m.m + '</view></view><view class="pill7" data-act="inviteMer" data-i="' + i + '">邀约</view></view>'; }).join('') }); },
    inviteMer: function (el) { var m = NEAR[+el.getAttribute('data-i')]; inviteSheet('mer', m.n, m.m, m.img, 'mer-home'); },
    sendInvite: function (el) { var kind = el.getAttribute('data-kind'); closeTop(); if (kind === 'club') { var c = clubs().filter(function (x) { return !x.st; })[0]; if (c) c.st = 'wait'; var r = cur().querySelector('#clubRows'); if (r) r.innerHTML = clubRows(); } toast('邀约已发出,对方会在消息里收到'); },
    selfTake: function (el) { var i = +el.getAttribute('data-i'); STATIONS[i].kind = 'self'; STATIONS[i].who = '星巴克咖啡厅'; var r = cur().querySelector('#stRows'); r.innerHTML = stationRows(); toast('这一站由你自己承接'); },
    applyOk: function (el) { var a = APPLY.splice(+el.getAttribute('data-i'), 1)[0]; STATIONS[1].kind = 'mer'; STATIONS[1].who = a.n; STATIONS[1].apply = 0; cur().querySelector('#stRows').innerHTML = stationRows(); cur().querySelector('#apRows').innerHTML = applyRows(); toast('已通过,可以在「主页」看对方微信、发消息'); },
    applyNo: function (el) { APPLY.splice(+el.getAttribute('data-i'), 1); STATIONS[1].apply = 0; cur().querySelector('#stRows').innerHTML = stationRows(); cur().querySelector('#apRows').innerHTML = applyRows(); toast('已婉拒'); },
    custF: function (el) { topSheet().querySelector('.os-b').innerHTML = custBody(el.getAttribute('data-f')); },
    broadcast: function () { open({ id: 'bc', title: '定向广播', back: true, level: 'half', body: bcastBody(), foot: '<view class="os-btn" data-act="bcSend">发送</view>' }); },
    bcScope: function (el) { [].forEach.call(el.parentNode.children, function (c) { c.classList.toggle('on', c === el); }); cur().querySelector('#bcN').textContent = el.getAttribute('data-n') + ' 人'; },
    bcSend: function () { var n = cur().querySelector('#bcN').textContent; closeTop(); toast('已发送给 ' + n); },
    sw7: function (el) { el.classList.toggle('on'); toast(el.classList.contains('on') ? '已开放商家承接申请' : '已关闭商家承接申请'); },
    pfTier: function (el) { topSheet().querySelector('.os-b').innerHTML = profitBody(el.getAttribute('data-k')); },
    earnMore: function () { open({ id: 'em', body: '<view class="tl">' + [['提现记录', 'clock'], ['邀请记录', 'gift']].map(function (r) { return '<view class="tl-r" data-toast7="打开' + r[0] + '"><text class="l">' + r[0] + '</text>' + I(r[1], 40) + '</view>'; }).join('') + '</view>' }); },
    aiIntro: function () { aiIntro(); },
    stepOpen: function (el, ev) { if (ev && ev.target && ev.target.closest('.go')) return; stepSheet(el.getAttribute('data-l'), el.getAttribute('data-s')); },
    askEdit: function () { askEditSheet(); },
    askPick: function (el) { if (el.classList.contains('dis')) return; el.classList.toggle('on'); var any = topSheet().querySelector('.pk7.on'); topSheet().querySelector('#askGo').classList.toggle('dis', !any); },
    askSend: function () { closeAll(); toast('修改申请已提交,审核通过后生效'); },
    invOpen: function (el) { var v = INV[+el.getAttribute('data-i')], t = THEMES[v.theme], k = INV.indexOf(v);
      open({ id: 'invd', title: v.box === 'out' ? '我发出的邀约' : '协作邀请', level: 'full', fixed: true, body:
        '<view class="th7-cv" style="height:150px"><img src="' + C + t.cover + '"><view class="th7-cvt"><span>' + (t.kind === 'free' ? '自由探索' : '城市定向') + ' · ' + t.date + '</span><b>' + t.name + '</b></view></view>' +
        '<view class="iv7-target" data-act="themeOpen" data-i="' + k + '">' + I('pin', 28) + '<view class="mr-m"><view class="mr-s">邀请你承接</view><view class="mr-n">' + targetText(v) + '</view></view><text class="link7">看详情</text></view>' +
        kvs([[v.box === 'out' ? '发给' : '邀请人', v.who], ['时间', v.time], ['分润', t.kind === 'free' ? '按章节档位(' + t.chapters[v.target ? v.target.ch : 0].tier + ')' : '引流档 · 无分成'], ['保证金', '无']]) +
        '<view class="lbl7"><text>邀请语</text></view><view class="tk7-msg" style="margin:0">' + v.msg + '</view>' +
        '<view class="tl-r" data-act="themeOpen" data-i="' + k + '" style="margin-top:8px"><text class="l">查看整个主题</text><view class="tl-chev"></view></view>',
        foot: v.box === 'in' && v.st === 'pending' ? '<view class="os-row2"><view class="os-btn os-btn--g" data-act="invNo" data-i="' + k + '">拒绝</view><view class="os-btn" data-act="invYes" data-i="' + k + '">同意</view></view>' : '' }); },
    themeOpen: function (el) { var v = INV[+el.getAttribute('data-i')]; themeSheet(v.theme, v.target); },
    chOpen: function (el) { chapterSheet(el.getAttribute('data-t'), +el.getAttribute('data-c')); },
    ndOpen: function (el) { nodeSheet(el.getAttribute('data-t'), +el.getAttribute('data-c'), +el.getAttribute('data-n')); },
    box: function (el) { var b = el.getAttribute('data-b'), root = el.parentNode; [].forEach.call(root.querySelectorAll('.bx7-t'), function (x) { x.classList.toggle('on', x === el); }); root.classList.toggle('r', b === 'out'); var L = cur().querySelector('#invList'), M = cur().querySelector('#msgList'); [L, M].forEach(function (x) { x.classList.add('sw'); }); setTimeout(function () { L.innerHTML = invList(b); L.scrollLeft = 0; M.innerHTML = msgList(b); cur().querySelector('#invN').textContent = b === 'in' ? '2 条待回复' : '1 条等对方回复'; [L, M].forEach(function (x) { x.classList.remove('sw'); }); }, 160); },
    dkOpen: function (el) { el.classList.toggle('open'); var l = el.querySelector('.link7'); if (l) l.textContent = el.classList.contains('open') ? '收起' : '展开'; },
    earnWin: function (el) { var root = el.parentNode, k = +el.getAttribute('data-k'); [].forEach.call(root.querySelectorAll('.sg7-t'), function (x) { x.classList.toggle('on', x === el); }); root.querySelector('.sg7-pill').style.transform = 'translateX(' + (k * 100) + '%)'; var box = root.nextElementSibling, inn = box.querySelector('.wn7-in'); box.style.height = inn.offsetHeight + 'px'; inn.innerHTML = earnRows(+el.getAttribute('data-w')); requestAnimationFrame(function () { box.style.height = inn.offsetHeight + 'px'; }); },
    startSale: function () { ACT.goTicket(); },
    goTicket: function () { toast('打开主题配置 · 票务(现有页面,定位到票务区)'); },
    themeQr: function () { var m = mode(), join = /^j/.test(m);
      open({ id: 'qr', title: '本主题二维码', center: true, level: 'full', body: '<view class="qr8"' + mk(9) + '>' + '<view class="qr8-card"><view class="qr8-h"><b>' + (m === 'jc' ? '苏州河慢慢走' : '上海像一部旧电影') + '</b><span>' + (join ? (m === 'jc' ? '第 1 章 · 苏河北岸 · 星巴克咖啡厅' : '第 2 站 · 上海邮政博物馆 · 星巴克咖啡厅') : '主办 · 星巴克咖啡厅') + '</span></view><canvas class="qr7 qr8-c" data-qr="1" width="220" height="220"></canvas><view class="qr8-f">玩家用微信扫一扫,直接进入' + (join ? '这一站' : '这个主题') + '</view></view>' +
        '<view class="mr-s" style="text-align:center;white-space:normal;margin-top:12px">玩法里要求「扫码参与」时,把它贴在店里或活动现场。每个主题一张码,' + (join ? '只对应你承接的这一站。' : '对应整个主题。') + '</view></view>',
        foot: '<view class="os-row2"><view class="os-btn os-btn--g" data-toast7="已保存到相册">保存到相册</view><view class="os-btn" data-toast7="已生成可打印的 A5 海报">打印海报</view></view>',
        after: function (el) { [].forEach.call(el.querySelectorAll('canvas[data-qr]'), drawQr); } }); },
    priceStep: function (el) { var n = cur().querySelector('#prN'), v = +n.getAttribute('data-v') + (+el.getAttribute('data-d')); if (v < 58) { toast('不能低于最低价 ¥58'); return; } n.setAttribute('data-v', v); n.textContent = '¥' + v; },
    saleSubmit: function () { closeAll(); var g = cur().querySelector('.gate7'); if (g) g.innerHTML = '<text>开售审核中 · 平台通过后自动开始售票</text><view class="pill7 pill7--g dis7">已提交</view>'; toast('已提交开售审核'); },
    invReply: function (el) { var v = INV[+el.getAttribute('data-i')];
      open({ id: 'invr', title: '回复 ' + v.who, back: true, level: 'half', body: '<textarea class="field7" placeholder="例:想先了解一下第 2 章的人数"></textarea>', foot: '<view class="os-btn" data-act="invSent">发送</view>' }); },
    invSent: function () { closeTop(); toast('已发送,对方在消息里能看到'); },
    invNo: function (el) { var v = INV[+el.getAttribute('data-i')];
      open({ id: 'invn', title: '拒绝这条邀约', back: true, level: 'half', body: '<view class="fchips">' + ['这段时间太忙', '离店太远', '档位不合适'].map(function (t) { return '<view class="fchip" data-act="pickChip">' + t + '</view>'; }).join('') + '</view><textarea class="field7" style="margin-top:12px" placeholder="写一句理由,对方能看到"></textarea>', foot: '<view class="os-btn" data-act="invNoDone">拒绝</view>' }); },
    pickChip: function (el) { [].forEach.call(el.parentNode.children, function (c) { c.classList.toggle('on', c === el); }); var ta = el.parentNode.nextElementSibling; if (ta) ta.value = el.textContent; },
    invNoDone: function () { closeAll(); toast('已拒绝'); },
    invYes: function (el) { var v = INV[+el.getAttribute('data-i')]; invKind = THEMES[v.theme].kind;
      if (invKind === 'free') open({ id: 'pickch', title: '选择承接的章节', back: true, level: 'full', fixed: true, body: '<view class="lbl7" style="margin-top:0"><text>一家只接一章 · 档位跟着章节走</text></view>' +
        [['苏河北岸', '四行仓库 · 上海邮政博物馆 · 河滨大楼', '体验档', 'time-pollution.jpg', ''], ['老城厢', '豫园老街 · 城隍庙小吃', '引流档', 'flavor-tour.jpg', ''], ['外滩源', '圆明园路 · 光陆大楼 · 亚洲文会', '体验档', 'old-movie.jpg', '已有商家承接']].map(function (c, k) {
          return '<view class="pk7' + (c[4] ? ' dis' : '') + '" data-act="pickCh"><img src="' + C + c[3] + '"><view class="mr-m"><view class="mr-n">第 ' + (k + 1) + ' 章 · ' + c[0] + '</view><view class="mr-s">' + (c[4] || c[1]) + '</view></view><text class="chip7 chip7--g">' + c[2] + '</text><i class="pk7-r"></i></view>'; }).join(''),
        foot: '<view class="os-btn dis" id="pkGo" data-act="supplySheet">下一步</view>' });
      else open({ id: 'cfnode', title: '确认承接这一站', back: true, level: 'half', body: '<view class="pk7 on" style="cursor:default"><img src="' + C + 'flavor-tour.jpg"><view class="mr-m"><view class="mr-n">第 2 站 · 上海邮政博物馆</view><view class="mr-s">北苏州路 250 号 · 引流档</view></view></view>', foot: '<view class="os-btn" data-act="supplySheet">下一步</view>' }); },
    pickCh: function (el) { if (el.classList.contains('dis')) return; [].forEach.call(el.parentNode.querySelectorAll('.pk7'), function (c) { c.classList.toggle('on', c === el); }); topSheet().querySelector('#pkGo').classList.remove('dis'); },
    supplySheet: function () { open({ id: 'sup', title: '填写供给', back: true, level: 'full', fixed: true, body:
      '<view class="lbl7" style="margin-top:0"><text>到店权益</text></view>' + [['招牌茉莉冷泡一杯', 'hot_beverage_3d.png'], ['第二杯半价券', 'admission_tickets_3d.png']].map(function (p, k) { return '<view class="pk7' + (k ? '' : ' on') + '" data-act="pickCh"><img src="' + D3 + p[1] + '" style="object-fit:contain;background:var(--o-sub)"><view class="mr-m"><view class="mr-n">' + p[0] + '</view><view class="mr-s">来自店铺装修 · 到店权益</view></view><i class="pk7-r"></i></view>'; }).join('') +
      '<view class="lbl7"><text>接待时段</text></view><view class="fchips">' + ['周六 14–17', '周日 14–17', '工作日晚上'].map(function (t, k) { return '<view class="fchip' + (k < 2 ? ' on' : '') + '">' + t + '</view>'; }).join('') + '</view>' +
      '<view class="lbl7"><text>每场最多接待</text></view><view class="kv7"><text>20 人</text><span class="link7">改</span></view>',
      foot: '<view class="os-btn" data-act="supplyDone">提交,进入工作台</view>' }); },
    supplyDone: function () { var free = invKind === 'free'; closeAll(); INV[0].st = 'accepted'; var r = cur().querySelector('#invList'); if (r) r.innerHTML = invList('in'); setTimeout(function () { PROTO.show(free ? 'join-chapter' : 'join-node', true); ON_ENTER(free ? 'join-chapter' : 'join-node'); }, 350); },
    rdGo: function (el) { rdShow(+el.getAttribute('data-i')); },
    rdNext: function () { rdShow((rdI + 1) % READS.length); },
    contactHost: function () { open({ id: 'ch', title: '联系主办', center: true, level: 'half', body:
      '<view class="mr" style="border:0"><img class="mr-av" src="' + C + 'treasure-hunter.jpg"><view class="mr-m"><view class="mr-n">副本人生</view><view class="mr-s">主办 · 俱乐部</view></view></view>' +
      '<view class="tl">' + [['打电话', 'phone', '拨打主办电话(号码不显示)'], ['发消息', 'comment', '打开和主办的对话']].map(function (r) { return '<view class="tl-r" data-toast7="' + r[2] + '"><text class="l">' + r[0] + '</text>' + I(r[1], 40) + '</view>'; }).join('') +
      '<view class="tl-r" data-go="club-home"><text class="l">查看主页</text><view class="tl-chev"></view></view></view>' }); },
    pubDone: function () { open({ id: 'pub', title: '自由探索已发布', center: true, level: 'peek', body: '<view class="pub7">邀请一个俱乐部承办,对方接受后就能开始售票。</view>', foot: '<view class="os-btn" data-act="pubGo">邀请俱乐部</view>' }); },
    pubGo: function () { closeTop(); setTimeout(function () { ACT['dw-club'](); }, 320); },
    chapterRec: function (el) { var i = +el.getAttribute('data-i'), c = CHAPTERS[i];
      open({ id: 'nearc', title: '第 ' + (i + 1) + ' 章 · 招商', back: true, level: 'full', fixed: true, body: '<view class="st7" style="border:0"><img class="st7-img" src="' + C + c.img + '"><view class="mr-m"><view class="mr-n">' + c.n + '</view><view class="mr-s">' + c.nodes.join(' · ') + '</view></view></view><view class="lbl7"><text>附近的商家</text></view>' +
        NEAR.map(function (m, k) { return '<view class="mr"><img class="mr-av mr-av--sq" src="' + C + m.img + '" data-go="mer-home"><view class="mr-m" data-go="mer-home"><view class="mr-n">' + m.n + '</view><view class="mr-s">' + m.m + '</view></view><view class="pill7" data-act="inviteMer" data-i="' + k + '">邀约</view><view class="pill7 pill7--g" data-go="mer-home">主页</view></view>'; }).join('') }); },
    selfTakeCh: function (el) { var i = +el.getAttribute('data-i'); CHAPTERS[i].kind = 'self'; cur().querySelector('#chRows').innerHTML = chapterRows(); toast('这一章由你自己承接'); },
    aiNext: function () { if (aiI < 3) { aiI++; paintAi(); } else { closeAll(); aiSeen = true; } }
  };
  // ---------------- 进度每一步可点开 ----------------
  function onSale() { var id = cur().getAttribute('data-id'); return id === 'ws-sale' || id === 'join-node'; }
  function kvs(rows) { return rows.map(function (r) { return '<view class="kv7"><text>' + r[0] + '</text><span>' + r[1] + '</span></view>'; }).join(''); }
  function editable() {
    var sold = onSale();
    return '<view class="lbl7"><text>能改什么</text></view>' +
      [['文案', '标题、介绍、剧情文字', '随时可改 · 审核后生效', ''], ['玩法', '每站的玩法与题目', '随时可改 · 审核后生效', ''], ['地点', '站点位置与地址', sold ? '已开售,不能再改' : '开售前可改', sold ? 'lock' : '']].map(function (r) {
        return '<view class="ed7' + (r[3] ? ' lock' : '') + '"><view class="mr-m"><view class="mr-n">' + r[0] + '</view><view class="mr-s">' + r[1] + '</view></view><text>' + (r[3] ? I('lock', 22) : '') + r[2] + '</text></view>'; }).join('');
  }
  function stepSheet(label, state) {
    var m = mode(), host = !/^j/.test(m), sold = onSale(), body = '', foot = '';
    var head = '<view class="sd7"><i class="pg7-dot ' + state + '"></i><text>' + (state === 'done' ? '已完成' : state === 'run' ? '进行中' : '还没开始') + '</text></view>';
    if (label === '配置主题' || label === '发布') {
      body = kvs([['主题', '上海像一部旧电影'], ['章节 / 站点', '1 章 · 4 站'], ['票价', '体验档 ¥68 · 引流档 ¥0'], ['开放', '11-01 至 11-30'], [label === '发布' ? '发布时间' : '最后修改', label === '发布' ? '10-27 20:14' : '10-26 18:02']]) + editable();
      foot = '<view class="os-btn" data-act="askEdit">申请修改</view>';
    } else if (label === '申请承接' || label === '报名已提交' || label === '主办确认' || label === '主办审核') {
      body = kvs([['主题', m === 'jc' ? '苏州河慢慢走' : '上海像一部旧电影'], ['承接', m === 'jc' ? '第 1 章 · 苏河北岸(3 个节点)' : '第 2 站 · 上海邮政博物馆'], ['档位', m === 'jc' ? '体验档' : '引流档'], ['时间', label.indexOf('主办') > -1 ? '10-28 09:12 通过' : '10-27 10:20 提交']]) + editable();
      foot = '<view class="os-btn" data-act="askEdit">申请修改</view>';
    } else if (label === '补供给' || label === '填写供给') {
      body = '<view class="lbl7" style="margin-top:0"><text>还差这些</text></view>' + [['接待时段', '周六、周日 14–17', 'ok'], [m === 'jc' ? '节点地址' : '门店照片', m === 'jc' ? '河滨大楼还没填' : '已上传 3 张', m === 'jc' ? 'now' : 'ok'], ['到店权益说明', '还没写', 'now']].map(function (r) { return '<view class="ed7"><view class="mr-m"><view class="mr-n">' + r[0] + '</view><view class="mr-s">' + r[1] + '</view></view>' + (r[2] === 'ok' ? '<text class="chip7 chip7--ok">已填</text>' : '<view class="pill7" data-act="supplySheet">去填</view>') + '</view>'; }).join('');
      foot = '<view class="os-btn" data-act="supplySheet">一次填完</view>';
    } else if (label === '招商') {
      body = kvs([['俱乐部', m === 'free' ? '1 家承办' : '1 家已接受 · 1 家等回复'], [m === 'free' ? '章节' : '站点', m === 'free' ? '1/3 章已招' : '2/4 站已招'], ['待处理申请', '1 个']]);
      foot = '<view class="os-row2"><view class="os-btn os-btn--g" data-act="dw-club">俱乐部</view><view class="os-btn" data-act="dw-recruit">去招商</view></view>';
    } else if (label === '邀请俱乐部承办' || label === '俱乐部承办') {
      body = kvs([['需要', '1 家俱乐部承办'], ['已邀请', label === '俱乐部承办' ? '城市定向免费测试俱乐部 · 已接受' : '还没有']]) + '<view class="mr-s" style="margin-top:10px;white-space:normal">俱乐部接受后才能开始售票。</view>';
      foot = '<view class="os-btn" data-act="dw-club">' + (label === '俱乐部承办' ? '看俱乐部' : '邀请俱乐部') + '</view>';
    } else if (label === '配置票务') {
      body = kvs([['合作方', '2/4 站商家 · 1 家俱乐部已就位'], ['票种 / 票价', '还没配'], ['在哪里配', '主题配置 · 票务']]) + '<view class="mr-s" style="margin-top:10px;white-space:normal">发布时可以先不配票务,先把商家和俱乐部邀齐;配好票务后提交开售。</view>';
      foot = '<view class="os-btn" data-act="goTicket">去主题配置 · 票务</view>';
    } else if (label === '售票') {
      body = kvs([['已售', sold ? '36 份' : '—'], ['还没到店', sold ? '22 位' : '—'], ['开放', '11-01 至 11-30'], ['地点', sold ? '已开售,锁定' : '开售前可改']]);
      foot = sold ? '<view class="os-btn" data-act="dw-cust">看客户</view>' : '';
    } else if (label === '核销接待' || label === '接待核销') {
      body = kvs([['已核销', sold ? '14 位' : '—'], ['待核销', sold ? '22 位' : '—'], ['核销方式', '扫玩家的入场码']]);
      foot = '<view class="os-row2"><view class="os-btn os-btn--g" data-act="dw-cust">客户</view><view class="os-btn" data-act="scan">去扫码</view></view>';
    } else if (label === '结算') {
      body = '<view class="sd7-lock">' + I('lock', 28) + '<text>每月 5 日结算上个月已核销的部分,到账账户收益。</text></view>' + kvs([['本主题已入账', '¥420.00'], ['待结算', '¥1,080.00'], ['下次结算', '11-05']]);
      foot = host ? '<view class="os-btn" data-act="dw-profit">分润明细</view>' : '';
    }
    open({ id: 'step', title: label, level: 'full', body: head + body, foot: foot });
  }
  function saleSheet() {
    open({ id: 'sale', title: '开始售卖', back: true, level: 'full', fixed: true, body:
      '<view class="pr7"><view class="pr7-k">票价</view><view class="pr7-row"><view class="pr7-b" data-act="priceStep" data-d="-1">−</view><view class="pr7-n" id="prN" data-v="68">¥68</view><view class="pr7-b" data-act="priceStep" data-d="1">+</view></view>' +
      '<view class="pr7-s"><text>最低 ¥58</text><text>同城同类均价 ¥72 · 18 个样本</text></view></view>' +
      kvs([['最低价怎么来', '站点供给 + 带团成本 + 平台服务费'], ['开放', '11-01 至 11-30'], ['各方分成', '已按条款锁定']]) +
      '<view class="sd7-lock" style="margin-top:12px">' + I('lock', 28) + '<text>提交后由平台审核开售。开售后票价和地点不能再改,文案和玩法仍可申请修改。</text></view>',
      foot: '<view class="os-btn" data-act="saleSubmit">提交开售审核</view>' });
  }
  function askEditSheet() {
    var sold = onSale();
    open({ id: 'ask', title: '申请修改', back: true, level: 'full', fixed: true, body:
      '<view class="lbl7" style="margin-top:0"><text>要改哪部分</text><text>可多选</text></view>' +
      [['文案', '标题、介绍、剧情文字', 'compose'], ['玩法', '每站的玩法与题目', 'game'], ['地点', sold ? '已开售,地点不能再改' : '站点位置与地址 · 开售前可改', 'loc']].map(function (r) {
        return '<view class="pk7' + (r[2] === 'loc' && sold ? ' dis' : '') + '" data-act="askPick"><view class="mr-m"><view class="mr-n">' + r[0] + '</view><view class="mr-s">' + r[1] + '</view></view>' + (r[2] === 'loc' && sold ? I('lock', 28) : '<i class="pk7-c"></i>') + '</view>'; }).join('') +
      '<view class="lbl7"><text>改成什么</text><text>0/300</text></view><textarea class="field7" placeholder="例:第 3 站的谜题太难,换成看图找细节"></textarea>' +
      '<view class="mr-s" style="margin-top:10px;white-space:normal">' + (sold ? '已开售:文案和玩法改动审核通过后对新玩家生效。' : '还没开售:三项都能改,审核通过后生效。') + '</view>',
      foot: '<view class="os-btn dis" id="askGo" data-act="askSend">提交申请</view>' });
  }
  var rdI = 0, invKind = '', dkTimer;
  function rollTo(el, to) {
    var t0 = performance.now(), dur = to ? 1100 : 300; el.classList.add('roll'); el.parentNode.classList.remove('settled');
    (function f(t) { var p = Math.min(1, (t - t0) / dur), e = 1 - Math.pow(1 - p, 3), v = to * e;
      el.textContent = '¥' + v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      el.style.filter = 'blur(' + ((1 - e) * 2.2).toFixed(2) + 'px)'; el.style.transform = 'translateY(' + ((1 - e) * 6).toFixed(1) + 'px)';
      if (p < 1) requestAnimationFrame(f); else { el.style.filter = ''; el.style.transform = ''; el.parentNode.classList.add('settled'); } })(t0);
  }
  function rdShow(n) {
    var root = cur().querySelector('.rd7'); if (!root) return; rdI = n;
    [].forEach.call(root.querySelectorAll('.rd7-card'), function (c) {
      var i = +c.getAttribute('data-i'), pos = (i - n + READS.length) % READS.length;
      c.className = 'rd7-card p' + pos;
    });
    [].forEach.call(root.querySelectorAll('.rd7-tab'), function (t, i) { t.classList.toggle('on', i === n); });
  }
  // data-toast7 小提示
  document.addEventListener('click', function (e) { var t = e.target.closest('[data-toast7]'); if (t && cur().contains(t)) toast(t.getAttribute('data-toast7')); }, true);

  // ---------------- AI 介绍 ----------------
  var AI = [{ a: 'convenience_store_3d.png', b: 'sparkles_3d.png', glow: 'rgba(165,166,232,.5)', t: '它是你的店在城瘾里的分身', s: '出现在漫游地图和店铺主页上,替你招呼路过的玩家' },
    { a: 'waving_hand_3d_default.png', b: 'speech_balloon_3d.png', glow: 'rgba(242,196,107,.5)', t: '玩家路过,它先打招呼', s: '按你写的招呼语和性格说话' },
    { a: 'fork_and_knife_with_plate_3d.png', b: 'alarm_clock_3d.png', glow: 'rgba(127,209,166,.5)', t: '菜单和营业时间它都答得上', s: '读的是你门店资料里的内容' },
    { a: 'name_badge_3d.png', b: 'performing_arts_3d.png', glow: 'rgba(240,138,160,.45)', t: '起个名字、选个性格就能用', s: '温柔 / 可爱 / 毒舌,随时可改' }];
  var aiI = 0, aiSeen = false;
  function aiIntro() {
    aiI = 0;
    open({ id: 'ai', inset: true, body: '<view class="ai-stage"' + mk(1) + '><view class="ai-glow"></view>' + AI.map(function (x, i) { return '<img class="ai-o out" data-a="' + i + '" src="' + D3 + x.a + '"><img class="ai-o sm out" data-b="' + i + '" src="' + D3 + x.b + '">'; }).join('') + '</view>' +
      '<view class="ai-t"></view><view class="ai-s"></view><view class="ai-dots"><i></i><i></i><i></i><i></i></view>', foot: '<view class="os-btn" data-act="aiNext">下一步</view>' });
    setTimeout(paintAi, 60);
  }
  function paintAi() {
    var el = topSheet(); if (!el) return; var x = AI[aiI];
    [].forEach.call(el.querySelectorAll('[data-a]'), function (o) { var on = +o.getAttribute('data-a') === aiI; o.classList.toggle('out', !on); o.style.transform = on ? 'translate(-18px,4px) rotate(-6deg)' : ''; });
    [].forEach.call(el.querySelectorAll('[data-b]'), function (o) { var on = +o.getAttribute('data-b') === aiI; o.classList.toggle('out', !on); o.style.transform = on ? 'translate(62px,-46px) rotate(10deg)' : ''; });
    el.querySelector('.ai-glow').style.background = 'radial-gradient(circle,' + x.glow + ',rgba(255,255,255,0) 65%)';
    el.querySelector('.ai-t').textContent = x.t; el.querySelector('.ai-s').textContent = x.s;
    [].forEach.call(el.querySelectorAll('.ai-dots i'), function (d, k) { d.classList.toggle('on', k === aiI); });
    el.querySelector('[data-act="aiNext"]').textContent = aiI === 3 ? '知道了' : '下一步';
  }

  // ---------------- 海报点阵图 / 二维码 ----------------
  function drawArt(cv) {
    var k = cv.getAttribute('data-art'), col = cv.getAttribute('data-dot'), W = cv.width, H = cv.height, g = cv.getContext('2d');
    var off = document.createElement('canvas'); off.width = W; off.height = H; var o = off.getContext('2d');
    o.fillStyle = '#fff'; o.fillRect(0, 0, W, H); o.fillStyle = '#000'; o.filter = 'blur(18px)';
    if (k === 'arrow') { o.beginPath(); o.moveTo(W * .15, H * .5); o.lineTo(W * .85, H * .1); o.lineTo(W * .62, H * .5); o.lineTo(W * .85, H * .9); o.closePath(); o.fill(); }
    if (k === 'pin') { o.beginPath(); o.arc(W * .5, H * .4, H * .26, 0, Math.PI * 2); o.fill(); o.beginPath(); o.moveTo(W * .32, H * .5); o.lineTo(W * .5, H * .95); o.lineTo(W * .68, H * .5); o.fill(); o.fillStyle = '#fff'; o.beginPath(); o.arc(W * .5, H * .4, H * .1, 0, Math.PI * 2); o.fill(); }
    if (k === 'ticket') { o.save(); o.translate(W / 2, H / 2); o.rotate(-.25); o.fillRect(-W * .36, -H * .22, W * .72, H * .44); o.fillStyle = '#fff'; o.beginPath(); o.arc(-W * .36, 0, H * .08, 0, 7); o.arc(W * .36, 0, H * .08, 0, 7); o.fill(); o.restore(); }
    if (k === 'chat') { o.beginPath(); o.ellipse(W * .46, H * .44, W * .34, H * .28, 0, 0, 7); o.fill(); o.beginPath(); o.moveTo(W * .3, H * .62); o.lineTo(W * .22, H * .86); o.lineTo(W * .46, H * .68); o.fill(); }
    var d = o.getImageData(0, 0, W, H).data; g.fillStyle = '#fff'; g.fillRect(0, 0, W, H); g.fillStyle = col;
    var step = 8, t = Date.now() / 900;
    for (var y = step / 2; y < H; y += step) for (var x = step / 2; x < W; x += step) {
      var v = 1 - d[(Math.floor(y) * W + Math.floor(x)) * 4] / 255;
      var r = v * step * .55 * (0.85 + .15 * Math.sin(x / 40 + y / 60 + t));
      if (r > .4) { g.globalAlpha = .35 + v * .65; g.beginPath(); g.arc(x, y, r, 0, 7); g.fill(); }
    }
    g.globalAlpha = 1;
  }
  function drawQr(cv) {
    var g = cv.getContext('2d'), n = 25, s = cv.width / n; g.fillStyle = '#fff'; g.fillRect(0, 0, cv.width, cv.height); g.fillStyle = '#14161A';
    var seed = 7; function rnd() { seed = (seed * 9301 + 49297) % 233280; return seed / 233280; }
    for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) { var f = (x < 7 && y < 7) || (x > n - 8 && y < 7) || (x < 7 && y > n - 8); if (!f && rnd() > .52) { g.beginPath(); g.arc(x * s + s / 2, y * s + s / 2, s * .42, 0, 7); g.fill(); } }
    [[0, 0], [n - 7, 0], [0, n - 7]].forEach(function (p) { g.lineWidth = s; g.strokeRect(p[0] * s + s / 2, p[1] * s + s / 2, s * 6, s * 6); g.fillRect(p[0] * s + s * 2, p[1] * s + s * 2, s * 3, s * 3); });
  }
  var artTimer;
  var lastId = null;
  window.ON_ENTER = function (id) {
    var keep = lastId === 'club-home' || lastId === 'mer-home';
    if (!keep && id !== 'club-home' && id !== 'mer-home') { Object.keys(stacks).forEach(function (k) { stacks[k] = []; }); [].forEach.call(document.querySelectorAll('.os-layer'), function (l) { l.innerHTML = ''; }); }
    lastId = id;
    [].forEach.call(cur().querySelectorAll('canvas[data-qr]'), drawQr);
    clearInterval(artTimer);
    var arts = cur().querySelectorAll('canvas[data-art]'); if (arts.length) { [].forEach.call(arts, drawArt); artTimer = setInterval(function () { [].forEach.call(arts, drawArt); }, 120); }
    var auto = /[?&]open=([\w-]+)/.exec(location.search);
    if (auto && !keep) setTimeout(function () { var f = ACT[auto[1]]; if (f) f(document.createElement('view')); var sub = /[?&]then=([\w-]+)(?:&i=(\d+))?/.exec(location.search); if (sub) setTimeout(function () { var el = document.createElement('view'); el.setAttribute('data-i', sub[2] || '0'); el.setAttribute('data-k', sub[2] || 'all'); ACT[sub[1]](el); }, 420); }, 120);
    clearInterval(dkTimer);
    var wl = cur().querySelector('.wl7-n'); if (wl) rollTo(wl, +wl.getAttribute('data-to'));
    var dk = cur().querySelector('.dk7-stack'); if (dk) dkTimer = setInterval(function () { if (dk.parentNode.classList.contains('open')) return; var cs = dk.querySelectorAll('.dk7-c'); [].forEach.call(cs, function (c) { var p = +(/p(\d)/.exec(c.className) || [0, 0])[1]; c.className = 'dk7-c p' + ((p + 2) % 3); }); }, 2200);
    var wn = cur().querySelector('.wn7-list'); if (wn) wn.style.height = '';
    if (id === 'npc' && !aiSeen) setTimeout(aiIntro, 300);
    var m = /[?&]scroll=(\d+)/.exec(location.search); if (m) setTimeout(function () { var sv = cur().querySelector('.sv,.ph7'); if (sv) sv.scrollTop = +m[1]; }, 80);
  };
})();
