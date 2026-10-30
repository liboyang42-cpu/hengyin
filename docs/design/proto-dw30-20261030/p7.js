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
      return '<view class="pg7-row ' + c + '"><view class="pg7-rail"><view class="pg7-dot ' + c + '"></view><view class="pg7-line ' + (c === 'done' ? 'done' : '') + '"></view></view>' +
        '<view class="pg7-m"><view class="pg7-t"><text class="lab">' + s.label + '</text>' + (s.tag ? '<text class="chip7 chip7--' + (s.tagKind || 'warn') + '">' + s.tag + '</text>' : (s.time ? '<text class="pg7-time">' + s.time + '</text>' : '')) + '</view>' +
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
      if (i < cur && !o.time) o.time = ['10-26', '10-27', '10-29', '11-01', '11-30', ''][i];
      return o;
    });
  }
  function joinSteps() {
    return [{ label: '报名已提交', time: '10-27 10:20', st: 'done' }, { label: '主办审核', time: '10-28 09:12', st: 'done' },
      { label: '补供给', st: 'run', tag: '待补齐', sub: [['门店照片', 'ok'], ['接待时段', 'ok'], ['到店权益说明', 'now', 'supply', '去补']] },
      { label: '接待核销', st: 'todo', time: '11-01 起' }, { label: '结算', st: 'todo' }];
  }
  function workbench(o) {
    var host = o.role !== 'join';
    var qa = host ? [['dw-club', '俱乐部', 'icon_qa_club.svg'], ['dw-recruit', '招商', 'icon_qa_store.svg'], ['dw-cust', '客户', 'icon_qa_person.svg'], ['dw-more', '更多', 'icon_qa_edit.svg']]
      : [['scan', '扫码', 'icon_qa_scan.svg'], ['dw-club', '俱乐部', 'icon_qa_club.svg'], ['dw-cust', '客户', 'icon_qa_person.svg'], ['dw-more', '更多', 'icon_qa_edit.svg']];
    var stList = host ? hostSteps(o.state || 'recruit') : joinSteps();
    return '<view class="sv"><view class="c-project-host"><view class="page theme-merchant">' + hero(host ? HOST_STATE[o.state || 'recruit'].status : '承接 · 待补供给') +
      '<view class="head"><view class="head-title">上海像一部旧电影</view><view class="head-meta"><view class="meta-chip">' + I('calendar', 26) + '<text>11-01 至 11-30</text></view><view class="chip head-role">' + (host ? '主办' : '承接 · 第 1 站') + '</view></view></view>' +
      '<view class="qa">' + qa.map(function (q) { return '<view class="qa-btn" data-act="' + q[0] + '"><view class="qa-ico"><img class="qa-ico-img" src="' + IMG + q[2] + '"></view><text class="qa-label">' + q[1] + '</text></view>'; }).join('') + '</view>' +
      '<view class="sec"><view class="sec-head"><text class="sec-title">售卖</text><view class="sec-action" data-act="dw-profit"><text>结算</text>' + I('arrow-right', 24) + '</view></view>' +
      '<view class="metrics"><view class="metric"><text class="metric-n">' + (o.state === 'draft' ? 0 : 36) + '</text><text class="metric-l">已售份数</text></view><view class="metric"><text class="metric-n">2</text><text class="metric-l">已招站点</text></view><view class="metric"><text class="metric-n">1</text><text class="metric-l">合作俱乐部</text></view></view></view>' +
      '<view class="pg7"' + mk(1) + '><view class="pg7-h"><b>进度</b><span>' + (host ? '主办视角' : '承接视角') + '</span></view>' + steps(stList) + '</view>' +
      '<view style="height:40px"></view></view></view></view>' + U.navBar({ overlay: true, pill: true, tint: 'light' });
  }
  var WS_NOTES = [[1, '页面最下面加「进度」(参考 shedsgns:做完是实心圆 + 勾、当前这步空心圆带呼吸点、连线逐段填满;子项列出这一步还差什么,右边直接点进去处理)。主办:配置 → 发布 → 招商 → 售票 → 核销接待 → 结算;承接:报名 → 审核 → 补供给 → 接待核销 → 结算。四个入口点开都是底部弹窗,不过微信导航(胶囊下沿 +5 封顶)。底部「查看收到的申请」删掉。']];
  S.push(scr('ws-recruit', '主办 · 招商中', '主题工作台', 'pages/topic/components/project-host/index.wxml', workbench({ state: 'recruit' }), WS_NOTES));
  S.push(scr('ws-draft', '主办 · 待发布', '主题工作台', 'project-host', workbench({ state: 'draft' }), WS_NOTES));
  S.push(scr('ws-sale', '主办 · 售票中', '主题工作台', 'project-host', workbench({ state: 'sale' }), WS_NOTES));
  S.push(scr('ws-join', '承接 · 待补供给', '主题工作台', 'pages/topic/components/project-join/index.wxml', workbench({ role: 'join' }), [[1, '承接方的进度沿用 master 已有那条线(报名 → 审核 → 开始接待 → 结束),换成同一套步骤样式,并加「补供给」一步、列出还差的项。']]));

  // ---------------- 推入页:正式俱乐部主页 / 商家主页 ----------------
  S.push(scr('club-home', '俱乐部主页(正式页)', '主题工作台', 'pages/club/detail(P-1 定稿)',
    '<view class="ph7"><img class="ph7-img" src="assets/pages/club-head.jpg"><view class="c-post-card" style="background:var(--o-page)">' + [['林小满', '2小时', '周六下午走完了「上海像一部旧电影」。第三站的老唱片店老板还记得九十年代的进货单,一张张翻给我们看。', 'treasure-hunter.jpg'], ['何以解忧', '昨天', '武康大楼下的黄昏。', 'old-movie.jpg']].map(function (p) {
      return '<view class="post-card post-card--light"><view class="post-card__avatar"><img class="post-card__avatar-img" src="' + C + p[3] + '"></view><view class="post-card__main"><view class="post-card__head"><view class="post-card__identity"><view class="post-card__name"><text class="post-card__nick">' + p[0] + '</text></view><view class="post-card__meta"><text>' + p[1] + '</text></view></view></view><view class="post-card__body"><view class="post-card__text">' + p[2] + '</view></view></view></view>'; }).join('') + '</view></view>' +
    '<view style="position:absolute;left:0;top:47px;width:60px;height:44px;z-index:70" data-back></view>',
    [[1, '「主页」打开的就是正式俱乐部主页(pages/club/detail),不是新做的一页;左上返回回到工作台,刚才的弹窗还在。']]));
  function qrCanvas() { return '<canvas class="qr7" data-qr="1" width="120" height="120"></canvas>'; }
  S.push(scr('mer-home', '商家主页', '主题工作台', 'pages/merchant/home(canonical 商家主页)',
    '<view class="ph7"><view class="mh-cover"><img src="' + C + 'midlife-day.jpg"></view><view class="mh-head"><img class="mh-logo" src="' + C + 'flavor-tour.jpg"><view class="mh-name">苏河茶室</view></view>' +
    '<view class="mh-tags"><view class="mh-tag"><img src="' + D3 + 'hot_beverage_3d.png">茶饮</view><view class="mh-tag"><img src="' + D3 + 'round_pushpin_3d.png">320m</view><view class="mh-tag">营业中 · 10:00–22:00</view></view>' +
    '<view class="mh-card"' + mk(1) + '><view class="kv7"><text>门店地址</text><span>天潼路 133 号</span></view><view class="kv7"><text>门店微信</text><span>suhe-tea</span></view><view style="display:flex;gap:14px;align-items:center;padding:12px 0">' + qrCanvas() + '<view class="mr-s" style="white-space:normal">扫码加门店微信,商量接待细节</view></view></view>' +
    '<view class="mh-btns"' + mk(2) + '><view class="os-btn os-btn--g" data-toast7="已复制微信号">复制微信号</view><view class="os-btn" data-toast7="打开和苏河茶室的会话">发消息</view></view></view>' + U.navBar({ overlay: true, pill: true, tint: 'light' }),
    [[1, '通过招商后能看到对方:门店地址、门店微信和二维码。'], [2, '底部直接「发消息」进站内会话,或复制微信号线下沟通。']]));

  // ---------------- 消息(不改,点卡进主题工作台)----------------
  S.push(scr('msg', '消息 · 协作邀请(不改)', '消息', 'subpackageB/pages/im/list/index.wxml',
    '<view class="sv"><view class="theme-merchant pg-im"><view class="im-list" style="padding-top:87px"><view class="message-tools">' + U.tabs({ variant: 'fill', wide: true, active: 'in', tabs: [{ key: 'in', label: '收件箱' }, { key: 'out', label: '发件箱' }] }) + '</view>' +
    '<view class="invite-strip"><text class="invite-strip__title">协作邀请</text><scroll-view class="invite-strip__scroll" scroll-x><view class="invite-strip__track">' +
    [['待接受', 'pending', '来自 城瘾官方', '09-29', '【零元续测0929】自由探索供给', '到店探索', 'flavor-tour.jpg'], ['已接受', 'accepted', '来自 副本人生', '10-12', '上海像一部旧电影', '上海市黄浦区', 'old-movie.jpg']].map(function (v, i) {
      return '<view class="invite-card" data-go="ws-join"' + (i ? '' : mk(1)) + '><view class="invite-card__top"><text class="invite-status is-' + v[1] + '">' + v[0] + '</text><text class="invite-card__from">' + v[2] + '</text></view><view class="invite-card__main"><img class="invite-card__cover" src="' + C + v[6] + '"><view class="invite-card__info"><text class="invite-card__date">' + v[3] + ' 起</text><text class="invite-card__name">' + v[4] + '</text><text class="invite-card__sub">' + v[5] + '</text></view></view><text class="invite-card__time">点开看主题工作台</text></view>';
    }).join('') + '</view></scroll-view></view></view></view></view>' + U.navBar({ title: '消息' }),
    [[1, '协作邀请卡用你已经定好的样子,不改;只是点卡直接进这个主题的工作台(进度在工作台最下面)。']]));

  // ---------------- 账户收益 · 更多(Threads)----------------
  S.push(scr('earn-more', '账户收益 · 更多', '账户收益', 'components/cy/scene-asset-earnings/index.wxml',
    '<view class="sv"><view class="er-page theme-merchant pg-er" style="padding-top:87px"><view class="c-page-title"><view class="pt pt--left"><view class="pt__title">账户收益</view></view></view><view class="c-asset-earnings"><view class="ae"><view class="ae-hero"><view class="ae-card"><text class="ae-card__label">账户收益（元）</text><text class="ae-card__amount">¥0.00</text></view>' +
    '<view class="ae-actions">' + [['arrow-right', '提现', 1], ['list', '明细'], ['sort', '分析'], ['more', '更多']].map(function (a) { return '<view class="ae-action"' + (a[1] === '更多' ? ' data-act="earnMore"' : '') + '><view class="ae-action__ic' + (a[2] ? ' ae-action__ic--primary' : '') + '">' + I(a[0], 40) + '</view><text>' + a[1] + '</text></view>'; }).join('') + '</view></view>' +
    U.empty({ title: '还没有收益', cta: '去发布路线', art: true, fill: true, size: 'lg' }) + '</view></view></view></view>' + U.navBar({}),
    [[1, '「更多」按 Threads:白色底部弹窗、居中标题、行之间发丝线、右侧图标;只留提现记录、邀请记录。']]));

  // ---------------- 店铺参谋 · 经营优化(inspora 海报卡)----------------
  var POSTERS = [
    { k: 'arrow', label: '带客 · 主题', t: '承接一条主题路线', d: '玩家按路线来店打卡,0 成本带来第一批客流。', meta: '附近 6 条路线招商中', bg: '#2F5D3A', fg: '#fff', dot: '#5FB36B', go: '去看看', gobg: '#3F7F4C' },
    { k: 'pin', label: '地图 · 曝光', t: '把门店设成打卡点', d: '漫游地图上出现你的店,路过的玩家一眼看到。', meta: '本周 128 人路过附近', bg: '#D7D8F3', fg: '#2B2C7A', dot: '#5A5DE0', go: '去设置', gobg: '#4B4ED6' },
    { k: 'ticket', label: '复购 · 券', t: '发一张到店券', d: '新客到店核销,复购率会算进店铺参谋。', meta: '同商圈平均核销率 41%', bg: '#F1E3C9', fg: '#5A3B12', dot: '#E08A2E', go: '去发券', gobg: '#C9741F' },
    { k: 'chat', label: 'AI · 招呼', t: '让店铺角色招呼客人', d: '玩家路过时它会打招呼、介绍菜单。', meta: '已有 312 家店启用', bg: '#1C1C1E', fg: '#fff', dot: '#A5A6E8', go: '去配置', gobg: '#3A3A3C' }];
  S.push(scr('ins', '店铺参谋 · 经营优化', '店铺参谋', 'pages/merchant/marketing/ai-insight/index.wxml',
    '<view class="sv"><view class="page theme-merchant pg-ins"><view style="height:87px"></view><view class="body"><view class="greet"><view class="greet-b"><view class="greet-line" style="font-size:24px;font-weight:600"><text>下午好，</text><text>星巴克咖啡厅</text></view></view>' + U.avatar(IMG + 'mer1.jpg', 112, 'greet-avatar') + '</view>' +
    '<view class="hero value-card"><view class="hero-top"><view class="hero-main"><text class="hero-k">近30天，城瘾为你带来</text><view class="hero-big"><text class="hero-num">0</text><text class="hero-unit">位玩家到店</text></view></view></view></view>' +
    '<view class="sec-head"' + mk(1) + '><text class="sec-title">经营优化</text></view><view class="pc-row">' + POSTERS.map(function (p, i) {
      return '<view class="pc"><canvas width="472" height="400" data-art="' + p.k + '" data-dot="' + p.dot + '"></canvas><view class="pc-b" style="background:' + p.bg + ';color:' + p.fg + '"><view class="pc-top"><text>' + p.label + '</text><view class="pc-add">＋ 收藏</view></view><view class="pc-t">' + p.t + '</view><view class="pc-d">' + p.d + '</view><view class="pc-f"><text class="pc-meta"><i></i>' + p.meta + '</text><view class="pc-go" style="background:' + p.gobg + ';color:#fff" data-toast7="' + p.go + '">' + p.go + '</view></view></view></view>';
    }).join('') + '</view><view style="height:40px"></view></view></view></view>' + U.navBar({ title: '店铺参谋' }),
    [[1, '「经营优化」改成一排横滑的海报卡(参考 inspora 两条):上半是点阵生成图(箭头 / 定位 / 票 / 对话,每张一个颜色),下半色块写标题、一句话、一个数和按钮。左右滑,不往下堆。问候语按时段、字号加大(D05,已在 PR #684)。']]));

  // ---------------- AI 店铺角色 首次介绍 ----------------
  S.push(scr('npc', 'AI 店铺角色 · 首次介绍', 'AI 店铺角色', 'pages/merchant/decor/ai-npc/index.wxml',
    '<view class="sv"><view class="dc-page theme-merchant pg-npc"><view style="height:87px"></view><view class="dc-content"><text class="dc-brand-title">AI 店铺角色</text><view class="npc-hero"><view class="npc-preview" style="display:flex;align-items:center;justify-content:center;background:#ECECF8"><img src="' + D3 + 'robot_3d.png" style="width:70%;height:70%"></view><text class="dc-name">当兵的</text></view>' +
    '<view style="text-align:center;margin-top:16px;font-size:13px;color:var(--o-mute)" data-act="aiIntro">再看一遍介绍</view></view></view></view>' + U.navBar({}),
    [[1, '只在第一次进入时弹。四步图都是 AI 角色相关的 3D 物件(机器人分身 → 挥手打招呼 + 对话气泡 → 菜单 + 闹钟 → 名牌 + 性格面具),翻页时旧物件缩小转走、新物件弹出,底下光晕换色。不用 logo。']]));

  document.getElementById('screens').innerHTML = S.join('');

  // ================= 弹层引擎(10-23 规范)=================
  var CAP = 756, LV = { peek: 253, half: 506, full: 675 };
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
    return CLUBS.map(function (c, i) {
      var right = c.st === 'ok' ? '<text class="chip7 chip7--ok">已接受</text>' : (c.st === 'wait' ? '<text class="chip7 chip7--warn"><i class="spin7"></i>等回复</text>' : '<view class="pill7" data-act="inviteClub" data-i="' + i + '">邀约</view>');
      return '<view class="mr"><img class="mr-av" src="' + C + c.img + '" data-go="club-home"><view class="mr-m" data-go="club-home"><view class="mr-n">' + c.n + '</view><view class="mr-s">' + c.m + '</view></view>' + right + '<view class="pill7 pill7--g" data-go="club-home">主页</view></view>';
    }).join('');
  }
  function clubBody() {
    var ok = CLUBS.filter(function (c) { return c.st === 'ok'; }).length, w = CLUBS.filter(function (c) { return c.st === 'wait'; }).length;
    return '<view class="sum7"><view><b>' + ok + '</b><span>已接受</span></view><view><b>' + w + '</b><span>等回复</span></view><view><b>15%</b><span>带团分成</span></view></view><view class="lbl7"><text>附近的俱乐部</text></view><view id="clubRows">' + clubRows() + '</view>';
  }
  function inviteSheet(kind, name, sub, img, go) {
    var txt = kind === 'club' ? '你好,我们的主题「上海像一部旧电影」11 月 1 日开放,4 站都在苏州河沿岸。想邀请你们带团来玩,带团分成 15%,周末每场最多 20 人。' : '你好,我们在做主题「上海像一部旧电影」,第 2 站在上海邮政博物馆,想请你们承接这一站。按章节约定是引流档,玩家到店打卡、你提供一杯招牌饮品。';
    open({ id: 'invite', title: '邀约' + name, back: true, level: 'full', body:
      '<view class="mr" style="border:0"><img class="mr-av' + (kind === 'club' ? '' : ' mr-av--sq') + '" src="' + C + img + '"><view class="mr-m"><view class="mr-n">' + name + '</view><view class="mr-s">' + sub + '</view></view><text class="link7" data-go="' + go + '">查看主页</text></view>' +
      '<view class="lbl7"><text>邀约语</text><text>' + txt.length + '/200</text></view><textarea class="field7" id="invTxt" maxlength="200">' + txt + '</textarea>' +
      '<view class="kv7"><text>' + (kind === 'club' ? '带团分成' : '本站权益档') + '</text><span>' + (kind === 'club' ? '15% · 按核销结算' : '引流型 · 无分成(章节固定)') + '</span></view>',
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
    return APPLY.map(function (a, i) { return '<view class="mr" style="align-items:flex-start"><img class="mr-av mr-av--sq" src="' + C + a.img + '" data-go="mer-home"><view class="mr-m"><view class="mr-n">' + a.n + ' <text class="mr-s">申请 ' + a.st + '</text></view><view class="mr-s" style="white-space:normal;margin-top:4px">' + a.msg + '</view><view style="display:flex;gap:8px;margin-top:10px"><view class="pill7 pill7--g" data-act="applyNo" data-i="' + i + '">婉拒</view><view class="pill7" data-act="applyOk" data-i="' + i + '">通过</view><view class="pill7 pill7--g" data-go="mer-home">主页</view></view></view></view>'; }).join('');
  }
  function recruitBody() {
    var got = STATIONS.filter(function (s) { return s.kind; }).length;
    return '<view class="sum7"><view><b>' + got + '/4</b><span>已招站点</span></view><view><b>' + APPLY.length + '</b><span>待处理申请</span></view><view><b>引流</b><span>本章权益档</span></view></view>' +
      '<view class="lbl7"><text>各站</text></view><view id="stRows">' + stationRows() + '</view><view class="lbl7"><text>收到的申请</text></view><view id="apRows">' + applyRows() + '</view>';
  }
  function custBody(filter) {
    var rows = CUST.filter(function (c) { return !filter || c[2] === filter; });
    return '<view class="sum7"><view><b>36</b><span>已售</span></view><view><b>22</b><span>还没到店</span></view><view><b>14</b><span>已核销</span></view></view>' +
      '<view class="fchips">' + [['', '全部'], ['wait', '还没到店'], ['ok', '已核销']].map(function (f) { return '<view class="fchip' + (f[0] === (filter || '') ? ' on' : '') + '" data-act="custF" data-f="' + f[0] + '">' + f[1] + '</view>'; }).join('') + '</view>' +
      rows.map(function (c) { return '<view class="mr"><view class="mr-av" style="display:flex;align-items:center;justify-content:center;font-weight:600;color:var(--o-ink2)">' + c[0][0] + '</view><view class="mr-m"><view class="mr-n">' + c[0] + '</view><view class="mr-s">' + c[1] + '</view></view><text class="chip7 ' + (c[2] === 'ok' ? 'chip7--ok">已核销' : 'chip7--warn">未到店') + '</text></view>'; }).join('') +
      '<view class="entry7" data-act="broadcast"' + mk(1) + '><b>定向广播</b><text class="mr-s">发给本主题的买票玩家</text><view class="tl-chev"></view></view>';
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
  function profitBody(k) {
    var t = TIERS[k], idx = ['all', 'exp', 'lead'].indexOf(k);
    return '<view class="pf-seg"><i style="width:calc((100% - 6px)/3);transform:translateX(' + (idx * 100) + '%)"></i>' + [['all', '全部'], ['exp', '体验档 ¥68'], ['lead', '引流档 ¥0']].map(function (x) { return '<div class="' + (x[0] === k ? 'on' : '') + '" data-act="pfTier" data-k="' + x[0] + '">' + x[1] + '</div>'; }).join('') + '</view>' +
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
    'dw-club': function () { open({ id: 'club', title: '合作俱乐部', level: 'full', fixed: true, body: clubBody() }); },
    'dw-recruit': function () { open({ id: 'recruit', title: '招商', level: 'full', fixed: true, body: recruitBody() }); },
    'dw-cust': function () { open({ id: 'cust', title: '客户', level: 'full', fixed: true, body: custBody('') }); },
    'dw-more': function () { open({ id: 'more', title: '更多', center: true, level: 'half', body: moreBody(false) }); },
    'dw-profit': function () { open({ id: 'profit', title: '分润明细', level: 'full', fixed: true, body: profitBody('all') }); },
    scan: function () { toast('打开扫码核销'); }, publish: function () { toast('进入发布确认'); }, supply: function () { toast('进入补供给'); },
    inviteClub: function (el) { var c = CLUBS[+el.getAttribute('data-i')]; inviteSheet('club', c.n, c.m, c.img, 'club-home'); },
    station: function (el) { var s = STATIONS[+el.getAttribute('data-i')];
      open({ id: 'near', title: '第 ' + (+el.getAttribute('data-i') + 1) + ' 站 · 招商', back: true, level: 'full', fixed: true, body: '<view class="st7" style="border:0"><img class="st7-img" src="' + C + s.img + '"><view class="mr-m"><view class="mr-n">' + s.n + '</view><view class="mr-s">权益档按章节固定:引流型 · 无分成</view></view></view><view class="lbl7"><text>附近 1 公里的商家</text></view>' +
        NEAR.map(function (m, i) { return '<view class="mr"><img class="mr-av mr-av--sq" src="' + C + m.img + '" data-go="mer-home"><view class="mr-m" data-go="mer-home"><view class="mr-n">' + m.n + '</view><view class="mr-s">' + m.m + '</view></view><view class="pill7" data-act="inviteMer" data-i="' + i + '">邀约</view></view>'; }).join('') }); },
    inviteMer: function (el) { var m = NEAR[+el.getAttribute('data-i')]; inviteSheet('mer', m.n, m.m, m.img, 'mer-home'); },
    sendInvite: function (el) { var kind = el.getAttribute('data-kind'); closeTop(); if (kind === 'club') { var c = CLUBS.filter(function (x) { return !x.st; })[0]; if (c) c.st = 'wait'; var r = cur().querySelector('#clubRows'); if (r) r.innerHTML = clubRows(); } toast('邀约已发出,对方会在消息里收到'); },
    selfTake: function (el) { var i = +el.getAttribute('data-i'); STATIONS[i].kind = 'self'; STATIONS[i].who = '星巴克咖啡厅'; var r = cur().querySelector('#stRows'); r.innerHTML = stationRows(); toast('这一站由你自己承接'); },
    applyOk: function (el) { var a = APPLY.splice(+el.getAttribute('data-i'), 1)[0]; STATIONS[1].kind = 'mer'; STATIONS[1].who = a.n; STATIONS[1].apply = 0; cur().querySelector('#stRows').innerHTML = stationRows(); cur().querySelector('#apRows').innerHTML = applyRows(); toast('已通过,可以在「主页」看对方微信、发消息'); },
    applyNo: function (el) { APPLY.splice(+el.getAttribute('data-i'), 1); STATIONS[1].apply = 0; cur().querySelector('#stRows').innerHTML = stationRows(); cur().querySelector('#apRows').innerHTML = applyRows(); toast('已婉拒'); },
    custF: function (el) { topSheet().querySelector('.os-b').innerHTML = custBody(el.getAttribute('data-f')); },
    broadcast: function () { open({ id: 'bc', title: '定向广播', back: true, level: 'half', body: bcastBody(), foot: '<view class="os-btn" data-act="bcSend">发送</view>' }); },
    bcScope: function (el) { [].forEach.call(el.parentNode.children, function (c) { c.classList.toggle('on', c === el); }); cur().querySelector('#bcN').textContent = el.getAttribute('data-n') + ' 人'; },
    bcSend: function () { var n = cur().querySelector('#bcN').textContent; closeTop(); toast('已发送给 ' + n); },
    sw7: function (el) { el.classList.toggle('on'); toast(el.classList.contains('on') ? '已开放商家承接申请' : '已关闭商家承接申请'); },
    pfTier: function (el) { topSheet().querySelector('.os-b').innerHTML = profitBody(el.getAttribute('data-k')); },
    earnMore: function () { open({ id: 'em', title: '更多', center: true, inset: true, body: '<view class="tl">' + [['提现记录', 'clock'], ['邀请记录', 'gift']].map(function (r) { return '<view class="tl-r" data-toast7="打开' + r[0] + '"><text class="l">' + r[0] + '</text>' + I(r[1], 40) + '</view>'; }).join('') + '</view>' }); },
    aiIntro: function () { aiIntro(); },
    aiNext: function () { if (aiI < 3) { aiI++; paintAi(); } else { closeAll(); aiSeen = true; } }
  };
  // data-toast7 小提示
  document.addEventListener('click', function (e) { var t = e.target.closest('[data-toast7]'); if (t && cur().contains(t)) toast(t.getAttribute('data-toast7')); }, true);

  // ---------------- AI 介绍 ----------------
  var AI = [{ a: 'robot_3d.png', b: 'sparkles_3d.png', glow: 'rgba(165,166,232,.5)', t: '它是你的店在城瘾里的分身', s: '出现在漫游地图和店铺主页上,替你招呼路过的玩家' },
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
    if (id === 'npc' && !aiSeen) setTimeout(aiIntro, 300);
    var m = /[?&]scroll=(\d+)/.exec(location.search); if (m) setTimeout(function () { var sv = cur().querySelector('.sv,.ph7'); if (sv) sv.scrollTop = +m[1]; }, 80);
  };
})();
