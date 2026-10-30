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
      return '<view class="pg7-row ' + c + (s.lock ? ' lock' : '') + '"><view class="pg7-rail"><view class="pg7-dot ' + c + (s.lock ? ' lock' : '') + '">' + (s.lock ? I('lock', 20) : '') + '</view><view class="pg7-line ' + (c === 'done' ? 'done' : '') + '"></view></view>' +
        '<view class="pg7-m"><view class="pg7-t"><text class="lab">' + s.label + '</text>' + (s.lock ? '<text class="pg7-ask">' + s.lock + '</text>' : s.tag ? (s.st === 'run' && !s.tagKind ? '<text class="pg7-stat">' + s.tag + '</text>' : '<text class="chip7 chip7--' + (s.tagKind || 'warn') + '">' + s.tag + '</text>') : (s.time ? '<text class="pg7-time">' + s.time + '</text>' : '')) + '</view>' +
        (s.sub ? '<view class="pg7-sub">' + s.sub.map(function (x) { return '<view class="pg7-si ' + (x[1] || '') + '"><i></i><text>' + x[0] + '</text>' + (x[2] ? '<text class="go" data-act="' + x[2] + '">' + (x[3] || '去处理') + ' ›</text>' : '') + '</view>'; }).join('') + '</view>' : '') +
        '</view></view>';
    }).join('');
  }
  var HOST_STATE = {
    draft: { status: '草稿', cur: 1 }, recruit: { status: '招商中', cur: 2 }, sale: { status: '售票中', cur: 3 }, end: { status: '已结束', cur: 5 } };
  function hostSteps(k) {
    var cur = HOST_STATE[k].cur;
    var L = [
      { label: '配置主题', time: '10-26' },
      { label: '发布', sub: [['剧情、站点、票价已填好', 'ok'], ['发布后开始招商和售票', 'now', 'publish', '去发布']] },
      { label: '招商', sub: [['俱乐部 1 家已接受 · 1 家等回复', 'ok', 'dw-club', '看俱乐部'], ['商家 2/4 站 · 1 个申请待处理', 'now', 'dw-recruit', '去处理']] },
      { label: '售票', sub: [['已售 36 份 · 11-01 起开放', 'ok'], ['还没到店 22 位', 'now', 'dw-cust', '看客户']] },
      { label: '核销接待', sub: [['已核销 14 位', 'ok'], ['还剩 22 位待核销', 'now', 'scan', '去扫码']] },
      { label: '结算', sub: [['每月 5 日结上月', ''], ['本主题已入账 ¥420.00', 'ok', 'dw-profit', '分润明细']] }];
    return L.map(function (s, i) {
      var o = Object.assign({}, s);
      o.st = i < cur ? 'done' : (i === cur ? 'run' : 'todo');
      if (o.st === 'run') o.tag = ['', '待发布', '待邀约', '待核销', '待核销', '待结算'][i];
      if (o.st !== 'run') { o.sub = i < cur ? null : null; }
      if (i === 5 && o.st === 'todo') o.lock = '每月 5 日';
      if (i < cur && !o.time) o.time = ['10-26', '10-27', '10-29', '11-01', '11-30', ''][i];
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
      { label: '售票', st: 'todo', time: ok ? '' : '俱乐部接受后开放' }, { label: '核销接待', st: 'todo' }, { label: '结算', st: 'todo', lock: '每月 5 日' }];
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
    var qa = host ? [['dw-club', '俱乐部', 'icon_qa_club.svg'], ['dw-recruit', '招商', 'icon_qa_store.svg'], ['dw-cust', '客户', 'icon_qa_person.svg'], ['dw-more', '更多', 'icon_qa_edit.svg']]
      : [['scan', '扫码', 'icon_qa_scan.svg'], ['dw-club', '俱乐部', 'icon_qa_club.svg'], ['dw-cust', '客户', 'icon_qa_person.svg'], ['dw-more', '更多', 'icon_qa_edit.svg']];
    var stList = free ? freeSteps(o.ok) : hostSteps(o.state || 'recruit');
    return '<view class="sv"><view class="c-project-host"><view class="page theme-merchant">' + hero(free ? (o.ok ? '招商中' : '待俱乐部承办') : HOST_STATE[o.state || 'recruit'].status) +
      '<view class="head"><view class="head-title">上海像一部旧电影</view><view class="head-meta"><view class="meta-chip">' + I('calendar', 26) + '<text>11-01 至 11-30</text></view><view class="chip head-role">' + (free ? '主办 · 自由探索' : '主办 · 城市定向') + '</view></view></view>' +
      (free && !o.ok ? '<view class="gate7"' + mk(2) + '><text>邀请一个俱乐部承办后才能开始售票</text><view class="pill7" data-act="dw-club">去邀请</view></view>' : '') +
      qaRow(qa) +
      '<view class="sec"><view class="sec-head"><text class="sec-title">售卖</text><view class="sec-action" data-act="dw-profit"><text>结算</text>' + I('arrow-right', 24) + '</view></view>' +
      '<view class="metrics"><view class="metric"><text class="metric-n">' + (o.state === 'draft' || free ? 0 : 36) + '</text><text class="metric-l">已售份数</text></view><view class="metric"><text class="metric-n">' + (free ? (o.ok ? '1/3' : '0/3') : '2/4') + '</text><text class="metric-l">' + (free ? '已招章节' : '已招站点') + '</text></view><view class="metric"><text class="metric-n">' + (free && !o.ok ? 0 : 1) + '</text><text class="metric-l">' + (free ? '承办俱乐部' : '合作俱乐部') + '</text></view></view></view>' +
      '<view class="pg7"' + mk(1) + '><view class="pg7-h"><b>进度</b><span>' + (free ? '自由探索 · 主办' : '城市定向 · 主办') + '</span></view>' + steps(stList) + '</view>' +
      '<view style="height:40px"></view></view></view></view>' + U.navBar({ overlay: true, pill: true, tint: 'light' });
  }
  function qaRow(qa) { return '<view class="qa">' + qa.map(function (q) { return '<view class="qa-btn" data-act="' + q[0] + '"><view class="qa-ico"><img class="qa-ico-img" src="' + IMG + q[2] + '"></view><text class="qa-label">' + q[1] + '</text></view>'; }).join('') + '</view>'; }
  function joinBench(m) {
    var node = m === 'jn';
    return '<view class="sv"><view class="c-project-host"><view class="page theme-merchant">' + hero(node ? '承办 · 待补供给' : '承办 · 待补供给') +
      '<view class="head"><view class="head-title">' + (node ? '上海像一部旧电影' : '苏州河慢慢走') + '</view><view class="head-meta"><view class="meta-chip">' + I('calendar', 26) + '<text>11-01 至 11-30</text></view><view class="chip head-role">' + (node ? '承办 · 第 2 站' : '承办 · 第 1 章') + '</view></view></view>' +
      '<view' + mk(1) + '>' + qaRow([['scan', '扫码', 'icon_qa_scan.svg'], ['dw-cust', '客户', 'icon_qa_person.svg']]) + '</view>' +
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

  // ---------------- 消息 · 协作邀请卡(邀请人 / 主题 / 时间 / 邀请语 + 回复 · 拒绝 · 同意)----------------
  var INV = [
    { st: 'pending', who: '城瘾官方', av: 'old-movie.jpg', time: '09-29 14:20', topic: '【零元续测0929】自由探索供给', kind: 'free', cover: 'flavor-tour.jpg', msg: '想请你们承接其中一章,玩家到店打卡、你们提供一份到店权益,章节可以自己挑。' },
    { st: 'accepted', who: '副本人生', av: 'treasure-hunter.jpg', time: '10-12 09:05', topic: '上海像一部旧电影', kind: 'city', cover: 'old-movie.jpg', msg: '第 2 站在上海邮政博物馆旁边,想请你们承接这一站,引流档。' }];
  function invCard(v, i) {
    var acts = v.st === 'pending'
      ? '<view class="ic7-acts"><view class="ic7-b" data-act="invReply" data-i="' + i + '">回复</view><view class="ic7-b" data-act="invNo" data-i="' + i + '">拒绝</view><view class="ic7-b ic7-b--on" data-act="invYes" data-i="' + i + '">同意</view></view>'
      : '<view class="ic7-acts"><view class="ic7-b" data-act="invReply" data-i="' + i + '">回复</view><view class="ic7-b ic7-b--on ic7-b--wide" data-go="' + (v.kind === 'free' ? 'join-chapter' : 'join-node') + '">进入工作台</view></view>';
    return '<view class="ic7"' + (i ? '' : mk(1)) + '>' +
      '<view class="ic7-top"><img src="' + C + v.av + '"><view class="mr-m"><view class="ic7-who">' + v.who + ' <span>邀请你承接</span></view><view class="mr-s">' + v.time + '</view></view>' + (v.st === 'pending' ? '<i class="ic7-new"></i>' : '<text class="chip7 chip7--ok">已同意</text>') + '</view>' +
      '<view class="ic7-cv" data-act="invOpen" data-i="' + i + '"><img src="' + C + v.cover + '"><view class="ic7-cvs"></view><view class="ic7-cvt"><span>' + (v.kind === 'free' ? '自由探索 · 承接章节' : '城市定向 · 承接节点') + '</span><b>' + v.topic + '</b></view></view>' +
      '<view class="ic7-msg">' + v.msg + '</view>' + acts + '</view>';
  }
  S.push(scr('msg', '消息 · 协作邀请', '消息', 'subpackageB/pages/im/list/index.wxml + pages/coop/invite-detail',
    '<view class="sv"><view class="theme-merchant pg-im"><view class="im-list" style="padding-top:87px"><view class="message-tools">' + U.tabs({ variant: 'fill', wide: true, active: 'in', tabs: [{ key: 'in', label: '收件箱' }, { key: 'out', label: '发件箱' }] }) + '</view>' +
    '<view class="ic7-h">协作邀请<span>1 条待回复</span></view><scroll-view class="ic7-row" scroll-x id="invRow">' + INV.map(invCard).join('') + '</scroll-view></view></view></view>' + U.navBar({ title: '消息' }),
    [[1, '卡片上直接写清:谁邀请的、什么时候、哪个主题(自由探索承接章节 / 城市定向承接节点)、邀请语。待回复的卡底部三个按钮:回复 / 拒绝 / 同意;点主题那一行看完整邀约(条款)。同意自由探索 → 先选章节 → 填供给 → 进工作台;同意城市定向 → 确认这一站 → 填供给 → 进工作台。拒绝要写理由(后端要求)。']]));

  // ---------------- 账户收益 · 更多(Threads)----------------
  S.push(scr('earn-more', '账户收益 · 更多', '账户收益', 'components/cy/scene-asset-earnings/index.wxml',
    '<view class="sv"><view class="er-page theme-merchant pg-er" style="padding-top:87px"><view class="c-page-title"><view class="pt pt--left"><view class="pt__title">账户收益</view></view></view><view class="c-asset-earnings"><view class="ae"><view class="ae-hero"><view class="ae-card"><text class="ae-card__label">账户收益（元）</text><text class="ae-card__amount">¥0.00</text></view>' +
    '<view class="ae-actions">' + [['arrow-right', '提现', 1], ['list', '明细'], ['sort', '分析'], ['more', '更多']].map(function (a) { return '<view class="ae-action"' + (a[1] === '更多' ? ' data-act="earnMore"' : '') + '><view class="ae-action__ic' + (a[2] ? ' ae-action__ic--primary' : '') + '">' + I(a[0], 40) + '</view><text>' + a[1] + '</text></view>'; }).join('') + '</view></view>' +
    U.empty({ title: '还没有收益', cta: '去发布路线', art: true, fill: true, size: 'lg' }) + '</view></view></view></view>' + U.navBar({}),
    [[1, '「更多」按 Threads:白色底部弹窗、居中标题、行之间发丝线、右侧图标;只留提现记录、邀请记录。']]));

  // ---------------- 店铺参谋:其他区块照原样,只改「经营解读」----------------
  // 经营解读的三段(总结 / 机会 / 问题)来自 ai.summary / ai.opportunity / ai.problem,做成一叠可翻的卡
  var READS = [
    { k: 'sum', tab: '总结', label: '这 30 天', t: '客人集中在周末下午', d: '近 30 天 41 位玩家到店,七成在周六、周日 14–17 点,工作日几乎没人。', bg: '#E9E4FF', fg: '#2B2470', dot: '#6C5CE7', art: 'arrow' },
    { k: 'op', tab: '机会', label: '可以做', t: '承接一条周末路线', d: '「上海像一部旧电影」第 2 站离你 300 米,周末每场 20 人,正好补上下午的空位。', bg: '#DDF3E6', fg: '#14532D', dot: '#22A55B', art: 'pin', go: '去看看' },
    { k: 'pb', tab: '问题', label: '要留意', t: '到店后没人复购', d: '41 位里只有 3 位回来过。到店权益只送一杯茶,没有留下下次来的理由。', bg: '#FFE9D6', fg: '#6B2F05', dot: '#F07A1A', art: 'ticket', go: '去设权益' }];
  function readDeck() {
    return '<view class="rd7"' + mk(2) + '><view class="rd7-tabs">' + READS.map(function (r, i) { return '<view class="rd7-tab' + (i ? '' : ' on') + '" data-act="rdGo" data-i="' + i + '"><i style="background:' + r.dot + '"></i>' + r.tab + '</view>'; }).join('') + '</view>' +
      '<view class="rd7-stack">' + READS.map(function (r, i) {
        return '<view class="rd7-card" data-i="' + i + '" data-act="rdNext" style="background:' + r.bg + ';color:' + r.fg + '"><canvas width="560" height="300" data-art="' + r.art + '" data-dot="' + r.dot + '"></canvas>' +
          '<view class="rd7-b"><view class="rd7-l"><text>' + r.label + '</text><text>' + (i + 1) + ' / 3</text></view><view class="rd7-t">' + r.t + '</view><view class="rd7-d">' + r.d + '</view>' + (r.go ? '<view class="rd7-go" style="background:' + r.fg + '" data-toast7="' + r.go + '">' + r.go + '</view>' : '') + '</view></view>';
      }).join('') + '</view><view class="ai-source7">解读依据今天 06:00 的经营数据</view></view>';
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
    [[1, '问候、主卡、推荐主题等其他区块都照原页面,不改。'], [2, '只改「经营解读」:原来一张纯文字卡(总结 / 机会 / 问题),改成三张叠放的卡。点卡或点上面的小标签切换:当前这张往左滑出、落到最后面,下一张升上来;每张一个颜色和一幅会流动的点阵图。机会和问题各带一个去处理的按钮。']]));

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
    invOpen: function (el) { var v = INV[+el.getAttribute('data-i')];
      open({ id: 'invd', title: '协作邀请', level: 'full', fixed: true, body: '<view class="ic7-cv" style="margin:0 0 12px"><img src="' + C + v.cover + '"><view class="ic7-cvs"></view><view class="ic7-cvt"><span>' + (v.kind === 'free' ? '自由探索 · 承接章节' : '城市定向 · 承接节点') + '</span><b>' + v.topic + '</b></view></view>' +
        '<view class="kv7"><text>邀请人</text><span>' + v.who + '</span></view><view class="kv7"><text>邀请时间</text><span>' + v.time + '</span></view><view class="kv7"><text>承接</text><span>' + (v.kind === 'free' ? '任选一章(同意后选)' : '第 2 站 · 上海邮政博物馆') + '</span></view><view class="kv7"><text>分润</text><span>' + (v.kind === 'free' ? '按所选章节的档位' : '引流档 · 无分成') + '</span></view><view class="kv7"><text>保证金</text><span>无</span></view>' +
        '<view class="lbl7"><text>邀请语</text></view><view class="ic7-msg" style="margin:0">' + v.msg + '</view>',
        foot: v.st === 'pending' ? '<view class="os-row2"><view class="os-btn os-btn--g" data-act="invNo" data-i="' + INV.indexOf(v) + '">拒绝</view><view class="os-btn" data-act="invYes" data-i="' + INV.indexOf(v) + '">同意</view></view>' : '' }); },
    invReply: function (el) { var v = INV[+el.getAttribute('data-i')];
      open({ id: 'invr', title: '回复 ' + v.who, back: true, level: 'half', body: '<textarea class="field7" placeholder="例:想先了解一下第 2 章的人数"></textarea>', foot: '<view class="os-btn" data-act="invSent">发送</view>' }); },
    invSent: function () { closeTop(); toast('已发送,对方在消息里能看到'); },
    invNo: function (el) { var v = INV[+el.getAttribute('data-i')];
      open({ id: 'invn', title: '拒绝这条邀约', back: true, level: 'half', body: '<view class="fchips">' + ['这段时间太忙', '离店太远', '档位不合适'].map(function (t) { return '<view class="fchip" data-act="pickChip">' + t + '</view>'; }).join('') + '</view><textarea class="field7" style="margin-top:12px" placeholder="写一句理由,对方能看到"></textarea>', foot: '<view class="os-btn" data-act="invNoDone">拒绝</view>' }); },
    pickChip: function (el) { [].forEach.call(el.parentNode.children, function (c) { c.classList.toggle('on', c === el); }); var ta = el.parentNode.nextElementSibling; if (ta) ta.value = el.textContent; },
    invNoDone: function () { closeAll(); toast('已拒绝'); },
    invYes: function (el) { var v = INV[+el.getAttribute('data-i')]; invKind = v.kind;
      if (v.kind === 'free') open({ id: 'pickch', title: '选择承接的章节', back: true, level: 'full', fixed: true, body: '<view class="lbl7" style="margin-top:0"><text>一家只接一章 · 档位跟着章节走</text></view>' +
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
    supplyDone: function () { var free = invKind === 'free'; closeAll(); INV[0].st = 'accepted'; var r = cur().querySelector('#invRow'); if (r) r.innerHTML = INV.map(invCard).join(''); setTimeout(function () { PROTO.show(free ? 'join-chapter' : 'join-node', true); ON_ENTER(free ? 'join-chapter' : 'join-node'); }, 350); },
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
  var rdI = 0, invKind = '';
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
    if (id === 'ins') { rdI = 0; setTimeout(function () { rdShow(0); }, 30); }
    if (id === 'npc' && !aiSeen) setTimeout(aiIntro, 300);
    var m = /[?&]scroll=(\d+)/.exec(location.search); if (m) setTimeout(function () { var sv = cur().querySelector('.sv,.ph7'); if (sv) sv.scrollTop = +m[1]; }, 80);
  };
})();
