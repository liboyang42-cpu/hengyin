// P-6 走查 version2(商家)。每屏照 master WXML 的结构和类名拼,样式来自 cy.css;本次改动只在 p6.css,并用 data-mk 编号。
(function () {
  var U = UI, I = U.icon, E = U.esc, mk = U.mk;
  var C = 'assets/covers/', IMG = 'assets/img/', D3 = 'assets/3d/';
  function notes(list) { return '<ol class="notes">' + list.map(function (t) { return '<li data-n="' + t[0] + '">' + t[1] + '</li>'; }).join('') + '</ol>'; }
  function scr(id, title, group, src, cls, body, nt) {
    return '<section class="scr ' + (cls || '') + '" data-id="' + id + '" data-title="' + title + '" data-group="' + group + '" data-src="' + src + '">' + body + (nt ? notes(nt) : '') + '</section>';
  }
  var S = [];
  // 商家五 tab(components/tabBar mode=merchant,黑白)
  function mtab(active) {
    return '<view class="c-tabbar" style="position:absolute;left:0;right:0;bottom:0;z-index:50"><view class="tabbar merchant monochrome"><view class="kg"></view><view class="list flex-cb"><view class="ul flex-cb">' +
      ['workbench', 'marketing', 'template', 'relation', 'mine'].map(function (k, i) { return '<view class="li' + (i === active ? ' active' : '') + '"><view class="icon flex-cc"><img class="tabbar__icon" src="' + IMG + 'icon_tab_' + k + '.svg"></view></view>'; }).join('') +
      '</view></view></view></view>';
  }

  // ================= 商家首页(pages/merchant/index)=================
  function heroAndActions() {
    return '<view class="rv-hero"><view class="rv-hero-top"><view class="rv-hero-greet"><view class="rv-hero-line ep1"><text class="rv-hero-hi">Hi，</text><text class="rv-hero-name">星巴克咖啡厅</text></view>' +
      '<view class="rv-hero-meta"><view class="rv-biz"><text class="rv-biz-dot"></text>营业中</view></view></view>' +
      '<view class="rv-hero-avatar-hit">' + U.avatar(IMG + 'mer1.jpg', 112, 'rv-hero-avatar') + '</view></view>' +
      '<view class="rv-revenue-zone"><view class="rv-revenue-label">累计收入</view><view class="rv-revenue-value"><view class="rv-revenue-amount">¥0.00</view></view></view></view>' +
      '<view class="rv-actions">' + [['scan', '扫码'], ['mtab-customers', '客户'], ['finance', '财务'], ['more', '更多']].map(function (a) {
        return '<view class="rv-action"><view class="rv-action-circle">' + I(a[0], 44) + '</view><text class="rv-action-label">' + a[1] + '</text></view>'; }).join('') + '</view>';
  }
  function projOld() {
    return '<view class="rv-section"><view class="rv-section-head"><text class="rv-section-title">项目</text><view class="rv-section-more"><text>全部 3</text>' + I('arrow-right', 24) + '</view></view>' +
      '<view class="rv-project-cards"><view class="rv-project-item"><view class="rv-project-card"><view class="rv-project-thumb"></view><view class="rv-project-body">' +
      '<view class="rv-project-line1"><view class="rv-project-line1-fill"></view><view class="rv-project-bell rv-project-bell--muted"><view class="rv-project-bell-ico"><img class="rv-project-bell-img" src="' + IMG + 'icon_bell_muted.svg"></view></view></view>' +
      '<text class="rv-project-title">上海像一部旧电影</text><text class="rv-project-sub">查看商家、进度与分润</text><text class="rv-project-card-note">今日无待办</text></view></view></view></view></view>';
  }
  function projNew(cover) {
    return '<view class="rv-section"><view class="rv-section-head"><text class="rv-section-title">项目</text><view class="rv-section-more"><text>全部 3</text>' + I('arrow-right', 24) + '</view></view>' +
      '<view class="p6-pcard" data-go="ws"' + mk(1) + '>' + (cover ? '<img src="' + C + 'old-movie.jpg">' : '<view class="p6-pcard__none"><img src="' + IMG + 'cy_logo_mark.png"></view>') +
      '<view class="p6-pcard__shade"></view><view class="p6-pcard__top"><text class="p6-pcard__tag">草稿 · 主办</text><view class="p6-pcard__bell"><img src="' + IMG + 'icon_bell.svg"><i></i></view></view>' +
      '<view class="p6-pcard__txt"><view class="p6-pcard__t">上海像一部旧电影</view><view class="p6-pcard__s">自由探索 · 4 站 · 已招 2 站</view><view class="p6-pcard__visit">今日无待办</view></view></view></view>';
  }
  var MSGS = [['bell', '城瘾小助手', '你的门店资料已通过审核', '2分钟前'], ['connect', '协作邀请', '夜跑上海邀请你承接第 2 站', '12分钟前'], ['finance', '核销到账', '阿乔 到店核销 · +¥68.00', '1小时前']];
  function msgStack(open) {
    if (!MSGS.length) return '<view class="p6-done"><view><i>✓</i>都看完了</view></view>';
    return '<view class="rv-msg-card p6-ns rv-msg-stack ' + (open ? 'rv-msg-stack--open' : 'rv-msg-stack--n' + Math.min(3, MSGS.length)) + '" data-act="nsOpen"' + mk(2) + '>' +
      MSGS.map(function (m, i) {
        return '<view class="rv-msg-item"><view class="rv-msg-ico">' + I(m[0], 34) + '</view><view class="rv-msg-main"><text class="rv-msg-title ep1">' + m[1] + '</text><text class="rv-msg-sub ep1">' + m[2] + '</text></view>' +
          '<text class="rv-msg-time">' + m[3] + '</text><i class="p6-dot"></i><view class="p6-x" data-act="nsX" data-i="' + i + '" aria-label="标为已读">' + I('close-sm', 24) + '</view></view>';
      }).join('') + '</view>' + (open ? '<view class="p6-clear" data-act="nsClear">' + I('trash', 26) + '<text>清空(全部标为已读)</text></view>' : '');
  }
  function msgSection(open) {
    return '<view class="rv-section"><view class="rv-section-head"><text class="rv-section-title">我的消息</text><view class="rv-section-more"><text>全部</text>' + I('arrow-right', 24) + '</view></view><view id="nsHost">' + msgStack(open) + '</view></view>';
  }
  function home(o) {
    return '<view class="sv"><view class="rv-merchant theme-merchant pg-mi"><view class="rv-scroll" style="padding-top:87px">' + heroAndActions() +
      (o.before ? projOld() : projNew(o.cover !== false)) + msgSection(false) +
      (o.before ? '<view style="padding:20px 16px 0;font-size:15px;color:#111"><view style="font-weight:600;margin:12px 0 8px">商品与履约</view><view style="font-weight:600;margin:12px 0 8px">门店与核销范围</view><view style="text-align:center;color:#888">读取门店与营业时段</view><view style="text-align:center;color:#888;margin-top:8px">门店位置与营业设置</view><view style="text-align:center;color:#888;margin-top:8px">查看供货与合作</view></view>'
        : '<view class="p6-gone"' + mk(3) + '>首页底部的工程面板(「读取门店与营业时段」等)已撤下 · PR #684</view>') +
      '<view style="height:200px"></view></view></view></view>' + U.navBar({ back: false }) + mtab(0);
  }
  S.push(scr('home-before', '商家首页 · 改前', '商家首页', 'pages/merchant/index/index.wxml(真机 IMG_0736)', 'cyp theme-merchant', home({ before: true })));
  S.push(scr('home', '商家首页 · 改后', '商家首页', 'pages/merchant/index/index.wxml', 'cyp theme-merchant', home({}), [
    [1, '项目卡改大卡:整张封面 + 底部渐暗,标题压在图上(和「为你推荐的主题」同一套卡);左上「草稿 · 主办」,右上铃铛保留红点;没有封面时用城瘾 logo 灰色占位。点卡进主题工作台。'],
    [2, '我的消息照 ramandesigns9「通知叠层」:沿用 10-23 已定的三张叠放,只改四处 —— 图标换成黑色方块、右下黑色未读点、展开后每条右侧 × = 标为已读、底下「清空」= 全部标为已读,清完收成「都看完了」小胶囊(10-30 你定)。点叠层展开试试。'],
    [3, '底部那串白字(W8 工程面板)已在代码删除,见 PR #684。']]));
  S.push(scr('home-nocover', '商家首页 · 项目无封面', '商家首页', 'pages/merchant/index/index.wxml', 'cyp theme-merchant', home({ cover: false }), [[1, '没有封面时用城瘾 logo 灰色占位,不留灰方块。']]));

  // ================= 消息(subpackageB/pages/im/list,商家视角)=================
  var INV = [
    { s: 1, tone: 'pending', st: '待补供给', from: '来自 城瘾官方', name: '【零元续测0929】自由探索供给', date: '09-29', sub: '到店探索', img: 'flavor-tour.jpg', step: 3 },
    { s: 1, tone: 'pending', st: '待补供给', from: '来自 城瘾官方', name: '自由探索合作验收0929', date: '09-29', sub: '到店探索', img: 'heat-escape.jpg', step: 3 },
    { s: 0, tone: 'info', st: '待你回复', from: '来自 副本人生', name: '智能科技体验馆 · 莘庄公园', date: '10-12', sub: '上海市浦东新区张江科学城', img: 'anomaly-coordinate.jpg', step: 1 },
    { s: 2, tone: 'accepted', st: '已上线', from: '发给 城市定向免费测试俱乐部', name: '预制人生 · 第一站', date: '09-27', sub: '引流型 · 无分成', img: 'prebuilt-life.jpg', step: 5 }];
  var LAB = ['发出', '已看', '接受', '补供给', '上线'];
  function steps(k) {
    var h = '<view class="p6-steps">';
    for (var i = 0; i < 5; i++) { h += '<view class="n ' + (i < k ? 'done' : (i === k ? 'run' : '')) + '"></view>'; if (i < 4) h += '<view class="l ' + (i < k ? 'done' : '') + '"></view>'; }
    return h + '</view><view class="p6-steplab">' + LAB.map(function (l, i) { return i === k ? '<b>' + l + '</b>' : '<text>' + l + '</text>'; }).join('') + '</view>';
  }
  function inviteCard(v, i) {
    return '<view class="invite-card" data-act="openInv" data-i="' + i + '"' + (i ? '' : mk(1)) + '><view class="invite-card__top"><text class="invite-status is-' + v.tone + '">' + v.st + '</text><text class="invite-card__from">' + v.from + '</text></view>' +
      '<view class="invite-card__main"><img class="invite-card__cover" src="' + C + v.img + '"><view class="invite-card__info"><text class="invite-card__date">' + v.date + ' 起</text><text class="invite-card__name">' + v.name + '</text><text class="invite-card__sub">' + v.sub + '</text></view></view>' +
      steps(v.step) + '</view>';
  }
  function convs() {
    return [['城瘾小助手', '你的门店资料已通过审核', '10:21', 1, 'sys'], ['城市定向免费测试俱乐部', '我们周六 14:00 带 12 人到店', '昨天', 2], ['官方客服', '提现问题可以直接在这里留言', '10-27', 0]].map(function (c) {
      return '<view class="conv-wrap"><view class="conv ' + (c[4] ? 'is-sys' : '') + '"><view class="avatar">' + (c[4] ? '<view class="sys-avatar">' + I('bell', 48) + '</view>' : '<img class="avatar-img" src="' + C + 'treasure-hunter.jpg">') + '</view>' +
        '<view class="conv-body"><view class="conv-top"><text class="conv-name ' + (c[4] ? 'sys-name' : '') + '">' + c[0] + '</text><text class="conv-time">' + c[2] + '</text></view><view class="conv-bottom"><text class="conv-preview">' + c[1] + '</text>' + (c[3] ? '<view class="badge">' + c[3] + '</view>' : '') + '</view></view></view></view>';
    }).join('');
  }
  function messages(empty) {
    return '<view class="sv"><view class="theme-merchant pg-im"><view class="im-list" style="padding-top:87px">' +
      '<view class="message-tools">' + U.tabs({ variant: 'fill', wide: true, active: 'in', tabs: [{ key: 'in', label: '收件箱' }, { key: 'out', label: '发件箱' }] }) + '</view>' +
      '<view class="invite-strip"><view class="p6-strip-hd"><text class="invite-strip__title">协作邀请</text><view class="p6-remind"' + mk(2) + '><text>新邀约提醒</text>' + sw(true, 'remind') + '</view></view>' +
      (empty ? '<view style="padding:0 16px">' + U.empty({ title: '还没有协作邀请', sub: '' }) + '</view>' :
        '<scroll-view class="invite-strip__scroll" scroll-x><view class="invite-strip__track">' + INV.map(inviteCard).join('') + '</view></scroll-view>') + '</view>' +
      '<view class="body"><text class="invite-strip__title">消息</text>' + convs() + '</view></view></view></view>' + U.navBar({ title: '消息' }) + invSheet();
  }
  // cy-switch = 原生 <switch color="#0E9E73">,浏览器里按微信原生开关画
  function sw(on, act) { return '<view class="p6-switch' + (on ? ' is-on' : '') + '"' + (act ? ' data-act="' + act + '"' : '') + '></view>'; }
  function invSheet() {
    return '<view class="ios-sheet theme-merchant pg-mif" id="inv-sheet" data-detent="full"><view class="mask"></view><view class="panel" style="background:var(--cy-bg-page)"><view class="grab"></view>' +
      '<view class="c-project-drawer"><view class="dw-head" style="padding:0 20px"><view class="dw-head-text"><text class="dw-title" id="invT">协作邀请</text><text class="dw-sub" id="invS"></text></view><view class="dw-close" data-close>' + I('close-sm', 44) + '</view></view></view>' +
      '<view class="body c-project-host" style="padding:8px 16px 24px"><view class="dcard" style="padding:16px 18px"' + mk(3) + '><view class="p6-vt" id="invVt"></view></view>' +
      '<view class="dg-label" style="margin-top:16px">合作内容</view><view class="dcard">' +
      [['主题', '【零元续测0929】'], ['你的身份', '承接商家 · 第 2 站'], ['权益档', '引流型 · 无分成'], ['开放时间', '09-29 起']].map(function (r) { return '<view class="dpick"><view class="dpick-text"><text class="dpick-name">' + r[0] + '</text></view><text class="dpick-tag">' + r[1] + '</text></view>'; }).join('') +
      '</view><view class="c-btn" style="margin-top:20px"><view class="btn btn--primary" data-toast="进入补供给">补到店权益说明</view></view></view></view></view>';
  }
  S.push(scr('msg', '消息 · 协作邀请', '消息', 'subpackageB/pages/im/list/index.wxml', 'cyp theme-merchant', messages(false), [
    [1, '协作邀请卡在原卡下面加一条步骤条:发出 → 已看 → 接受 → 补供给 → 上线(shedsgns 的形:做完是实心圆 + 勾、连线逐段填满,当前这步是带呼吸点的空心圆)。商家这边用近黑,不用参考里的粉色。原来卡上的「拒绝 / 接受合作」保留在详情里。'],
    [2, '「新邀约到达时提醒我」改成标题右边的开关。原来那条「只看「#99071」的协作 / 看全部」是从主题点进来时的主题筛选条,删掉 —— 主题里的申请改在主题工作台的弹窗里看(见「主题工作台」)。'],
    [3, '点卡打开全屏详情:同一套圆点竖着排,当前这步下面列出还差什么(✓ 已完成 / 转圈 = 还差),底部是这一步要做的事。']]));
  S.push(scr('msg-empty', '消息 · 没有邀请', '消息', 'subpackageB/pages/im/list/index.wxml', 'cyp theme-merchant', messages(true), [[2, '没有邀请时标题和开关照常在,下面用 master 空态。']]));

  // ================= 主题工作台(pages/topic/merchantinfo + project-host)=================
  var CLUBS = [['城市定向免费测试俱乐部', '兴趣社群 · 86 人', 'accepted', 'old-movie.jpg'], ['夜跑上海', '运动 · 214 人', 'invited', 'heat-escape.jpg'], ['旧书与胶片', '文化 · 40 人', '', 'midlife-day.jpg'], ['周末城市漫步团', '城市探索 · 132 人', '', 'treasure-hunter.jpg']];
  function drawer(id, title, body, o) {
    o = o || {};
    return '<view class="c-project-drawer p6-stack" id="' + id + '" hidden><view class="dw-mask" data-act="dwClose" data-id="' + id + '"></view><view class="dw" style="--cy-drawer-top:88px">' +
      '<view class="dw-head"><view class="p6-dw-hd">' + (o.back ? '<view class="p6-back" data-act="dwClose" data-id="' + id + '">' + I('back', 44) + '</view>' : '') +
      '<view class="dw-head-text"><text class="dw-title">' + title + '</text>' + (o.sub ? '<text class="dw-sub">' + o.sub + '</text>' : '') + '</view></view>' +
      '<view class="dw-close" data-act="dwClose" data-id="' + id + '">' + I('close-sm', 44) + '</view></view>' +
      '<view class="dw-scroll" style="overflow-y:auto"><view class="dw-scroll-content">' + body + '<view class="dw-scroll-pad"></view></view></view>' +
      (o.cta ? '<view class="dw-cta">' + o.cta + '</view>' : '') + '</view></view>';
  }
  function clubRows() {
    return '<view class="dcard" id="clubRows">' + CLUBS.map(function (c, i) {
      var st = c[2] === 'accepted' ? '<text class="dr-tag dr-tag--ok">已接受</text>' : (c[2] === 'invited' ? '<text class="dr-tag dr-tag--wait p6-pending">已邀约</text>' : '');
      return '<view class="dr"><img class="dr-avatar dr-avatar--round" src="' + C + c[3] + '"><view class="dr-text"><view class="dr-line1"><text class="dr-name">' + c[0] + '</text>' + st + '</view><text class="dr-sub">' + c[1] + '</text></view>' +
        '<view class="p6-btns">' + (c[2] ? '' : '<view class="dn-btn" data-act="inviteClub" data-i="' + i + '">邀约</view>') + '<view class="dn-btn p6-btn-g" data-act="clubHome" data-i="' + i + '">主页</view></view></view>';
    }).join('') + '</view>';
  }
  function clubBody() {
    var acc = CLUBS.filter(function (c) { return c[2] === 'accepted'; }).length, inv = CLUBS.filter(function (c) { return c[2] === 'invited'; }).length;
    return '<view class="dsum"' + mk(1) + '><text>已接受 <text class="dsum-n">' + acc + '</text></text><text class="dsum-pending">等回复 <text class="dsum-n">' + inv + '</text></text></view>' +
      '<view class="dg-label">可以邀约的俱乐部</view>' + clubRows();
  }
  function clubHome(i) {
    var c = CLUBS[i];
    return '<view style="height:140px;border-radius:16px;overflow:hidden;margin-bottom:12px"><img src="' + C + c[3] + '" style="width:100%;height:100%;object-fit:cover"></view>' +
      '<view class="dcard"><view class="dr"><img class="dr-avatar dr-avatar--round" src="' + C + c[3] + '"><view class="dr-text"><view class="dr-line1"><text class="dr-name">' + c[0] + '</text></view><text class="dr-sub">' + c[1] + ' · 公开加入</text></view></view></view>' +
      '<view class="dg-label">最近活动</view><view class="dcard"><view class="dpick"><view class="dpick-text"><text class="dpick-name">周六城市定向 · 莘庄</text><text class="dpick-sub">11-02 14:00 · 12 人</text></view></view><view class="dpick"><view class="dpick-text"><text class="dpick-name">外滩夜行</text><text class="dpick-sub">10-26 · 已结束</text></view></view></view>';
  }
  var STATIONS = [['1933老场坊', '星巴克咖啡厅', 'old-movie.jpg'], ['上海邮政博物馆', '', ''], ['上海四行仓库抗战纪念馆及外墙', '', ''], ['M50创意园', '城市运动装备馆', 'flavor-tour.jpg']];
  function recruitBody() {
    return '<view class="dp">' + STATIONS.map(function (s) { return '<view class="dp-seg ' + (s[1] ? 'dp-seg--on' : '') + '"></view>'; }).join('') + '</view><view class="dg-sub">已招 2/4 站 · 权益档按章节固定</view>' +
      '<view class="dg-label">各站</view><view class="dcard"' + mk(1) + '>' + STATIONS.map(function (s, i) {
        return s[1] ? '<view class="dr"><img class="dr-avatar" src="' + C + s[2] + '"><view class="dr-text"><view class="dr-line1"><text class="dr-name ep1">' + s[0] + '</text><text class="dr-tag dr-tag--ok">已招</text></view><text class="dr-sub">' + s[1] + '</text></view></view>'
          : '<view class="dn"><view class="dn-plus">' + I('plus', 28) + '</view><text class="dn-name">' + s[0] + '</text><view class="dn-btn" data-act="station" data-i="' + i + '">招商</view></view>';
      }).join('') + '</view>' +
      '<view class="dg-label"' + mk(2) + '>收到的申请 · 1</view><view class="dcard"><view class="review-row"><view class="review-main"><view class="dr-line1"><text class="dr-name ep1">苏河茶室</text><text class="dr-tag dr-tag--wait">待处理</text></view><text class="dr-sub">上海邮政博物馆 · 主动申请</text></view>' +
      '<view class="review-actions" id="applyAct"><view class="review-btn review-btn--ghost" data-act="apply" data-v="0">婉拒</view><view class="review-btn" data-act="apply" data-v="1">通过</view></view></view></view>';
  }
  function stationBody(i) {
    return '<view class="dcard"><view class="dpick"><view class="dpick-text"><text class="dpick-name">本站权益档</text><text class="dpick-sub">档位由章节决定,被邀商家只能按这一档接受</text></view><text class="dpick-tag">引流型 · 无分成</text></view></view>' +
      '<view class="dg-label">附近 1 公里的商家</view><view class="dcard">' + [['苏河茶室', '茶饮 · 320m', 'midlife-day.jpg'], ['一尺花园', '咖啡 · 480m', 'flavor-tour.jpg'], ['老码头书店', '书店 · 760m', 'time-pollution.jpg']].map(function (m, k) {
        return '<view class="dr"><img class="dr-avatar" src="' + C + m[2] + '"><view class="dr-text"><view class="dr-line1"><text class="dr-name">' + m[0] + '</text></view><text class="dr-sub">' + m[1] + '</text></view><view class="dn-btn" data-act="inviteMer">邀约</view></view>';
      }).join('') + '</view>';
  }
  function custBody(sold) {
    if (!sold) return U.empty({ title: '未开售票', sub: '开售后,买票的玩家会出现在这里', cta: '去设置票价', art: true, mk: 1 });
    function rows(list, state) { return list.map(function (p) { return '<view class="dr player-row"><view class="dr-avatar dr-avatar--round dr-avatar--fallback">' + p[0][0] + '</view><view class="dr-text"><view class="dr-line1"><text class="dr-name">' + p[0] + '</text></view><text class="dr-sub">' + p[1] + '</text></view><view class="dr-right"><text class="dr-state dr-state--' + state + '">' + (state === 'arrived' ? '已核销' : '未到店') + '</text>' + (p[2] ? '<text class="dr-time">' + p[2] + '</text>' : '') + '</view></view>'; }).join(''); }
    return '<view class="dsum"><text>已售 <text class="dsum-n">36</text></text><text class="dsum-pending">还没到店 <text class="dsum-n">22</text></text><text class="dsum-redeemed">已核销 <text class="dsum-n">14</text></text></view>' +
      '<view class="dg-label"' + mk(1) + '>还没到店 · 22</view><view class="dcard player-list">' + rows([['阿乔', '11-02 14:00 场'], ['小林', '11-02 15:30 场'], ['Mia', '11-03 10:00 场']], 'pending') + '</view>' +
      '<view class="dg-label">已核销 · 14</view><view class="dcard player-list">' + rows([['周周', '10-30 场', '16:12'], ['Kai', '10-30 场', '11:40']], 'arrived') + '</view>';
  }
  function bcastBody() {
    return '<view class="c-broadcast-sheet"><view class="cdbs__body"' + mk(1) + '><text class="cdbs__section-title">范围</text>' +
      '<view class="c-chip-group"><view class="cdcg" style="display:flex;gap:8px;flex-wrap:wrap" id="bcScope">' + ['全部买票玩家', '还没到店', '已核销'].map(function (t, i) { return '<view class="fx-chip' + (i ? '' : ' is-on') + '" data-act="bcScope" data-i="' + i + '" style="height:34px;padding:0 14px;border-radius:999px;display:inline-flex;align-items:center;font-size:14px;background:' + (i ? 'var(--cy-color-bg-surface-subtle)' : 'var(--cy-color-action-primary-bg)') + ';color:' + (i ? 'var(--cy-color-text-primary)' : 'var(--cy-color-action-primary-fg)') + '">' + t + '</view>'; }).join('') + '</view></view>' +
      '<text class="cdbs__section-title">内容<text class="form-required">*</text></text><textarea class="cdbs__textarea" maxlength="200" placeholder="输入广播内容"></textarea>' +
      '<view class="dcard" style="margin-top:12px"><view class="dpick"><view class="dpick-text"><text class="dpick-name">发送前预览</text></view><text class="dpick-tag" id="bcN">36 人</text></view></view>' +
      '<view class="cdbs__primary" data-act="bcSend">确认发送</view><text class="cdbs__caption">玩家在「城瘾小助手」里收到;已送达以站内发送记录为准。</text></view></view>';
  }
  function moreBody(open) {
    function row(t, o) { o = o || {}; return '<view class="dpick"' + (o.mk ? mk(o.mk) : '') + (o.toast ? ' data-toast="' + o.toast + '"' : '') + '><view class="dpick-text"><text class="dpick-name' + (o.danger ? ' dpick-name--danger' : '') + '">' + t + '</text></view>' + (o.sw != null ? '<view data-act="moreSw">' + sw(o.sw) + '</view>' : (o.danger ? '' : I('arrow-right', 24))) + '</view>'; }
    return '<view class="dcard more-card">' + row('编辑主题', { mk: 1, toast: '进入主题专业配置' }) + row('权益票夹') + row('票价与主题内容') + '</view>' +
      '<view class="dcard more-card">' + row('开放商家承接申请', { sw: open }) + (open ? row('邀请其他商家', { mk: 2, toast: '叠层打开附近商家' }) : '') + '</view>' +
      '<view class="dcard more-card">' + row('结束本章', { danger: true }) + '</view>';
  }
  function profitBody() {
    var T = [['体验档', '¥68 / 份 · 已售 24', [['主办', 40, '#14161A'], ['承接商家', 35, '#6B6B6B'], ['带团俱乐部', 15, '#A5A6E8'], ['平台', 10, '#DADADF']]], ['引流档', '¥0 / 份 · 已售 12', [['承接商家', 100, '#6B6B6B']]]];
    return '<view class="dcard" style="padding:16px 18px"><text class="dg-sub" style="margin:0">本主题已入账</text><view style="font-size:32px;font-weight:700;margin:2px 0;font-variant-numeric:tabular-nums">¥420.00</view><text class="dg-sub" style="margin:0">待结算 ¥1,080.00 · 每月 5 日结上月</text></view>' +
      '<view class="dg-label"' + mk(1) + '>每一档怎么分</view>' + T.map(function (t) {
        return '<view class="dcard" style="padding:14px 18px;margin-bottom:10px"><view class="dr-line1" style="justify-content:space-between"><text class="dr-name">' + t[0] + '</text><text class="dr-sub" style="margin:0">' + t[1] + '</text></view>' +
          '<view class="p6-tierbar">' + t[2].map(function (p) { return '<i style="width:' + p[1] + '%;background:' + p[2] + '"></i>'; }).join('') + '</view>' +
          t[2].map(function (p) { return '<view class="p6-tierrow"><text><i class="p6-sw" style="background:' + p[2] + '"></i>' + p[0] + '</text><b>' + p[1] + '%</b></view>'; }).join('') + '</view>';
      }).join('') + '<view class="dg-sub" style="text-align:center">示意比例 · 真实比例读主题条款快照</view>';
  }
  function workbench(o) {
    o = o || {};
    var qa = [['club', '俱乐部', 'icon_qa_club.svg'], ['recruit', o.before ? '商家' : '招商', 'icon_qa_store.svg'], ['cust', '客户', 'icon_qa_person.svg'], ['more', '更多', 'icon_qa_edit.svg']];
    return '<view class="sv"><view class="c-project-host"><view class="page theme-merchant">' +
      '<view class="hero"><img class="hero-img" src="' + C + 'old-movie.jpg"><view class="hero-scrim"></view><view class="hero-status"><view class="hero-status-dot state-ok"></view><text>' + (o.sold ? '售卖中' : '草稿') + '</text></view></view>' +
      '<view class="head"><view class="head-title">上海像一部旧电影</view><view class="head-meta"><view class="meta-chip">' + I('calendar', 26) + '<text>' + (o.sold ? '11-01 至 11-30' : '开放时间待定') + '</text></view><view class="chip head-role">主办</view></view></view>' +
      '<view class="qa">' + qa.map(function (q, i) { return '<view class="qa-btn" data-act="dw" data-id="dw-' + q[0] + '"' + (i === 1 && !o.before ? mk(1) : '') + '><view class="qa-ico"><img class="qa-ico-img" src="' + IMG + q[2] + '"></view><text class="qa-label">' + q[1] + '</text></view>'; }).join('') + '</view>' +
      '<view class="sec"><view class="sec-head"><text class="sec-title">售卖</text><view class="sec-action" data-act="dw" data-id="dw-profit"' + (o.before ? '' : mk(3)) + '><text>结算</text>' + I('arrow-right', 24) + '</view></view>' +
      '<view class="metrics"><view class="metric"><text class="metric-n">' + (o.sold ? 36 : 0) + '</text><text class="metric-l">已售份数</text></view><view class="metric"><text class="metric-n">2</text><text class="metric-l">已招节点</text></view><view class="metric"><text class="metric-n">1</text><text class="metric-l">合作俱乐部</text></view></view></view>' +
      '<view class="sec rules">' + I('info', 26) + '<text>哪些内容还能改</text>' + I('arrow-right', 24) + '</view>' +
      (o.before ? '<view class="page-pad"></view><view class="bottom-bar"><view class="c-btn" style="width:100%"><view class="btn btn--primary">查看收到的申请</view></view></view>' : '<view class="p6-gone"' + mk(2) + '>底部「查看收到的申请」已删除:申请放进「俱乐部」「招商」弹窗里</view><view class="page-pad"></view>') +
      drawer('dw-club', '合作俱乐部', clubBody()) +
      '<view id="dw-clubhome-wrap"></view>' +
      drawer('dw-recruit', '招商', recruitBody()) +
      drawer('dw-station', '邀请商家 · 第 2 站', stationBody(1), { back: true }) +
      drawer('dw-cust', '客户', custBody(o.sold), o.sold ? { sub: '本主题的买票玩家' } : {}) +
      '<view id="dw-cust-cta"></view>' +
      drawer('dw-bcast', '定向广播', bcastBody(), { back: true }) +
      drawer('dw-more', '更多', moreBody(!!o.open)) +
      drawer('dw-profit', '分润明细', profitBody()) +
      '</view></view></view>' + U.navBar({ overlay: true, pill: true, tint: 'light' });
  }
  S.push(scr('ws-before', '主题工作台 · 改前', '主题工作台', 'pages/topic/components/project-host/index.wxml(真机 IMG_0737)', 'cyp theme-merchant', workbench({ before: true })));
  S.push(scr('ws', '主题工作台 · 改后', '主题工作台', 'pages/topic/components/project-host/index.wxml', 'cyp theme-merchant', workbench({}), [
    [1, '「商家」改名「招商」。四个入口打开的弹窗都改成和「承接商家」一样的全屏弹窗(顶到胶囊下沿),都在主题里叠,不跳出主题。'],
    [2, '删掉底部「查看收到的申请」:俱乐部的申请在「俱乐部」里,商家的申请在「招商」里。'],
    [3, '「结算 →」打开本主题的「分润明细」弹窗(叠在工作台上),不再跳到账户收益页。']]));
  S.push(scr('ws-sold', '主题工作台 · 已开售', '主题工作台', 'pages/topic/components/project-host/index.wxml', 'cyp theme-merchant', workbench({ sold: true }), [[1, '已开售时再点「客户」「结算」看有数据的样子。']]));
  S.push(scr('ws-club', '合作俱乐部', '主题工作台', 'project-host · project-drawer', 'cyp theme-merchant', workbench({}), [
    [1, '弹窗里直接是俱乐部表单:上面已接受 / 等回复两个数,下面每行「邀约」「主页」。邀约后这一行变「已邀约」转圈,接受后变「已接受」。底部不放按钮。'],
    [2, '「主页」再叠一层弹窗看俱乐部(封面、简介、最近活动),左上返回回到表单;弹窗里也能直接邀约。']]));
  S.push(scr('ws-recruit', '招商', '主题工作台', 'project-host · project-drawer', 'cyp theme-merchant', workbench({}), [
    [1, '「去招商」改「招商」。点了在主题里叠一层,看附近商家并邀约;权益档按章节固定,被邀商家只能按这一档接受(CONTEXT.md 不变量 3),主办商家可以邀约商家。'],
    [2, '收到的申请放在下面一段,就地婉拒 / 通过。底部「去找商家 / 查看收到的申请」两颗按钮删掉。']]));
  S.push(scr('ws-cust', '客户', '主题工作台', 'project-host · project-drawer', 'cyp theme-merchant', workbench({ sold: true }), [
    [1, '没开售票:直接写「未开售票」+ 去设置票价。开售后:上面「还没到店」、下面「已核销」,沿用原来的客户行。右上「广播」复用俱乐部「定向广播」弹层(范围 / 内容 / 发送前预览 / 确认发送),后端用已有的商家客户广播接口。']]));
  S.push(scr('ws-more', '更多', '主题工作台', 'project-host · project-drawer', 'cyp theme-merchant', workbench({ open: true }), [
    [1, '加「编辑主题」第一行,进主题专业配置。'],
    [2, '「开放商家承接申请」打开后才出现「邀请其他商家」。拨开关试试。']]));
  S.push(scr('ws-profit', '分润明细', '主题工作台', 'scene-merchant-profit → 主题内弹窗', 'cyp theme-merchant', workbench({ sold: true }), [
    [1, '只看这个主题:本主题已入账 / 待结算,下面每一档(票价、已售)怎么分 —— 主办 / 承接商家 / 带团俱乐部 / 平台各占多少。浅色、整页可滑。比例是示意,真实值读条款快照。']]));

  // ================= 账户收益 · 更多(components/cy/scene-asset-earnings)=================
  function earnings(menu) {
    return '<view class="sv"><view class="er-page theme-merchant pg-er" style="padding-top:87px"><view class="c-page-title"><view class="pt pt--left"><view class="pt__title">账户收益</view></view></view>' +
      '<view class="c-asset-earnings"><view class="ae"><view class="ae-hero"><view class="ae-card"><text class="ae-card__label">账户收益（元）</text><text class="ae-card__amount">¥0.00</text></view>' +
      '<view class="ae-actions">' + [['arrow-right', '提现', 1], ['list', '明细'], ['sort', '分析'], ['more', '更多']].map(function (a) { return '<view class="ae-action"><view class="ae-action__ic' + (a[2] ? ' ae-action__ic--primary' : '') + '">' + I(a[0], 40) + '</view><text>' + a[1] + '</text></view>'; }).join('') + '</view></view>' +
      U.empty({ title: '还没有收益', cta: '去发布路线', art: true, fill: true, size: 'lg' }) + '</view></view></view></view>' + U.navBar({}) +
      (menu ? '<view class="ios-sheet open theme-merchant" style="display:block"><view class="mask"></view><view style="position:absolute;left:8px;right:8px;bottom:8px;border-radius:24px;overflow:hidden;background:var(--cy-color-bg-elevated)"><view class="tm" style="padding:8px"><view class="tm-group"' + mk(1) + '>' +
        [['提现记录', 'clock'], ['邀请记录', 'gift']].map(function (r) { return '<view class="tm-row"><text class="tm-label">' + r[0] + '</text>' + I(r[1], 40, 'tm-ico') + '</view>'; }).join('') + '</view></view></view></view>' : '');
  }
  S.push(scr('earn', '账户收益(商家浅色)', '账户收益', 'subpackageA/pages/assetcenter/earnings + scene-asset-earnings', 'cyp theme-merchant', earnings(false), [[1, '这一页本来就有浅色版(G-9)。改的只是两个入口(主页 → 我的资产、项目 → 结算)没带浅色参数,已在 PR #684 修。页面本身不动。']]));
  S.push(scr('earn-more', '账户收益 · 更多', '账户收益', 'components/cy/scene-asset-earnings/index.wxml', 'cyp theme-merchant', earnings(true), [[1, '删掉「结算明细」(和「明细」按钮是同一份),剩提现记录、邀请记录。其余照 master 的 Threads 菜单。']]));

  // ================= 店铺参谋(pages/merchant/marketing/ai-insight)=================
  function insight(loading) {
    var greet = '<view class="greet"><view class="greet-b"><view class="greet-line"><text>Hi，</text><text>星巴克咖啡厅</text></view></view>' + U.avatar(IMG + 'mer1.jpg', 112, 'greet-avatar') + '</view>';
    if (loading) return '<view class="sv"><view class="page theme-merchant pg-ins"><view style="height:87px"></view><view class="c-skeleton"' + mk(1) + '><view class="sk sk--light" style="padding:12px 16px">' +
      '<view style="display:flex;justify-content:space-between;align-items:center;padding:4px 0 16px"><view class="sk-bar" style="width:52%;height:26px"></view><view class="sk-bar" style="width:58px;height:58px;border-radius:50%"></view></view>' +
      '<view style="padding:16px;border-radius:16px;background:#fff;margin-bottom:12px"><view class="sk-bar" style="width:40%;height:14px"></view><view class="sk-bar" style="width:46%;height:40px;margin-top:10px"></view><view class="sk-bar" style="height:30px;margin-top:16px;border-radius:999px"></view><view class="sk-bar" style="height:40px;margin-top:12px"></view><view style="display:flex;gap:8px;margin-top:12px"><view class="sk-bar" style="flex:1;height:62px"></view><view class="sk-bar" style="flex:1;height:62px"></view><view class="sk-bar" style="flex:1;height:62px"></view></view></view>' +
      '<view style="display:grid;grid-template-columns:1fr 1fr;gap:12px"><view style="padding:16px;border-radius:16px;background:#fff"><view class="sk-bar" style="width:54%;height:12px"></view><view class="sk-bar" style="width:76%;height:30px;margin-top:12px"></view></view><view style="padding:16px;border-radius:16px;background:#fff"><view class="sk-bar" style="width:54%;height:12px"></view><view class="sk-bar" style="width:76%;height:30px;margin-top:12px"></view></view></view></view></view></view></view>' + U.navBar({ title: '店铺参谋' });
    var tips = [['承接一条主题路线', '玩家按路线来店打卡,0 成本带来第一批客流', 'old-movie.jpg', 'round_pushpin_3d.png', '去看看'], ['把门店设成打卡点', '漫游地图上出现你的店,路过的玩家能看到', 'flavor-tour.jpg', 'convenience_store_3d.png', '去设置'], ['发一张到店券', '新客到店核销,复购率会算进这里', 'midlife-day.jpg', 'admission_tickets_3d.png', '去发券'], ['让 AI 店铺角色招呼客人', '玩家路过时它会打招呼、介绍菜单', 'treasure-hunter.jpg', 'busts_in_silhouette_3d.png', '去配置']];
    return '<view class="sv"><view class="page theme-merchant pg-ins"><view style="height:87px"></view><view class="body">' + greet +
      '<view class="hero value-card"><view class="hero-top"><view class="hero-main"><text class="hero-k">近30天，城瘾为你带来</text><view class="hero-big"><text class="hero-num">0</text><text class="hero-unit">位玩家到店</text></view></view></view></view>' +
      '<view class="sec-head"' + mk(2) + '><text class="sec-title">为你推荐的主题</text></view><scroll-view class="tscroll" scroll-x><view class="trow">' + [['自由探索合作验收0929', 'heat-escape.jpg'], ['外滩夜行', 'time-pollution.jpg']].map(function (t) {
        return '<view class="tcard"><view class="tcard-cover"><img class="tcard-img" src="' + C + t[1] + '"><view class="tcard-shade"></view><view class="tcard-over"><view class="tcard-title"><text class="tcard-name">' + t[0] + '</text><view class="tcard-tag">招商中</view></view><text class="tcard-reason">平台招商中的自由探索主题</text><view class="tcard-foot"><view class="tcard-cta">看看</view></view></view></view></view>'; }).join('') + '</view></scroll-view>' +
      '<view class="sec-head"' + mk(3) + '><text class="sec-title">经营优化</text></view><view class="p6-tips">' + tips.map(function (t) {
        return '<view class="p6-tip"><view class="p6-tip__cv"><img src="' + C + t[2] + '"><img class="ic" src="' + D3 + t[3] + '"></view><view class="p6-tip__b"><view style="flex:1;min-width:0"><view class="p6-tip__t">' + t[0] + '</view><view class="p6-tip__s">' + t[1] + '</view></view><view class="p6-tip__go" data-toast="' + t[4] + '">' + t[4] + '</view></view></view>'; }).join('') + '</view>' +
      '<view style="height:40px"></view></view></view></view>' + U.navBar({ title: '店铺参谋' });
  }
  S.push(scr('ins-loading', '店铺参谋 · 加载中', '店铺参谋', 'pages/merchant/marketing/ai-insight/index.wxml', 'cyp theme-merchant', insight(true), [[1, '加载骨架和页面同构(问候 + 头像、主卡、两列小卡),已在 PR #684 落代码(cy-skeleton 新档 merchant-insight)。']]));
  S.push(scr('ins', '店铺参谋 · 经营优化', '店铺参谋', 'pages/merchant/marketing/ai-insight/index.wxml', 'cyp theme-merchant', insight(false), [
    [2, '「为你推荐的主题」删掉「查看全部」,只横滑(PR #684)。'],
    [3, '「经营解读」纯文字换成「经营优化」:四张带图卡(封面 + 3D 图标),讲怎么用城瘾带客流,每张一个动作。卡片沿用推荐主题卡的圆角和投影。']]));

  // ================= AI 店铺角色(pages/merchant/decor/ai-npc)=================
  var LOGO_O = 'M37 12H91L76 40H64V66H122L99 113Q97 116 93 116H37L6 64Z';
  var PIECES = [['L', 'o', '0,0 22.6,0 50.6,40 50.6,88 22.6,128 0,128'], ['T', 'o', '26.6,0 128,0 128,50 54.6,50 54.6,40'], ['B', 'o', '54.6,52 128,52 128,128 26.6,128 54.6,88'], ['I1', 'i', '50,30 80,30 64,57'], ['I2', 'i', '30,30 47,30 61.7,58.5 61.7,66.5 49,92 30,92'], ['I3', 'i', '65.5,67 100,67 100,100 52,100']];
  function logo() {
    return '<svg class="p6-logo" viewBox="-10 -14 152 152" width="176" height="176" aria-hidden="true"><defs><mask id="lgm"><rect x="-20" y="-20" width="180" height="180" fill="#fff"/><circle cx="64" cy="64" r="36" fill="#000"/></mask>' +
      '<linearGradient id="lgg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#9A82FF"/><stop offset="1" stop-color="#6A4CF5"/></linearGradient>' + PIECES.map(function (p) { return '<clipPath id="lgc' + p[0] + '"><polygon points="' + p[2] + '"/></clipPath>'; }).join('') + '</defs>' +
      PIECES.map(function (p) { return '<g class="lp" data-p="' + p[0] + '" style="filter:drop-shadow(0 4px 0 #4630C4) drop-shadow(0 12px 10px rgba(40,20,120,.25))"><g clip-path="url(#lgc' + p[0] + ')">' + (p[1] === 'o' ? '<path d="' + LOGO_O + '" fill="url(#lgg)" mask="url(#lgm)"/>' : '<circle cx="63.5" cy="63" r="25" fill="url(#lgg)"/>') + '</g></g>'; }).join('') +
      '<g class="lbub" style="opacity:0;transform:scale(.2)"><rect x="98" y="-12" width="40" height="26" rx="13" fill="#14161A"/><text x="118" y="6" font-size="13" font-weight="700" fill="#fff" text-anchor="middle">嗨</text></g></svg>';
  }
  function npc(first) {
    return '<view class="sv"><view class="dc-page theme-merchant pg-npc"><view style="height:87px"></view><view class="dc-content"><text class="dc-brand-title">AI 店铺角色</text>' +
      '<view class="npc-hero"><view class="npc-preview" style="display:flex;align-items:center;justify-content:center;background:#4c6fd8"><img src="' + D3 + 'detective.png" style="width:70%;height:70%"></view><text class="dc-name">当兵的</text></view>' +
      '<view class="dc-card" style="margin-top:16px">' + [['角色形象', '未设置'], ['角色声音', '未录制'], ['名字', '当兵的'], ['招呼语', '未填写'], ['性格设定', '选择性格']].map(function (r) { return '<view class="dpick" style="display:flex;justify-content:space-between;padding:14px 0;border-bottom:.5px solid var(--cy-color-border-subtle)"><text>' + r[0] + '</text><text style="color:var(--cy-color-text-tertiary)">' + r[1] + '</text></view>'; }).join('') + '</view>' +
      '<view style="text-align:center;margin-top:16px;font-size:13px;color:var(--cy-color-text-tertiary)" data-act="intro">' + I('info', 26) + ' 再看一遍介绍</view></view></view></view>' + U.navBar({}) +
      '<view class="ios-sheet theme-merchant" id="npc-intro" data-detent="peek"><view class="mask" data-act="introClose"></view><view class="p6-intro"' + mk(1) + '><view class="p6-intro__x" data-act="introClose">' + I('close-sm', 32) + '</view>' + logo() +
      '<view class="p6-intro__t" id="inT"></view><view class="p6-intro__s" id="inS"></view><view class="p6-intro__dots"><i></i><i></i><i></i><i></i></view><view class="p6-intro__go" data-act="introNext" id="inGo">下一步</view></view></view>';
  }
  S.push(scr('npc', 'AI 店铺角色 · 首次介绍', 'AI 店铺角色', 'pages/merchant/decor/ai-npc/index.wxml', 'cyp theme-merchant', npc(true), [
    [1, '页顶那张常驻说明卡删掉,改成只在第一次进入弹出的四步介绍(参考 TrninhHuy1 的 noku 四步弹窗)。图用城瘾 logo —— 积木:六块积木散开 → 靠拢 → 拼成 logo → logo 说「嗨」。点「下一步」看过程;× 或「知道了」都算看过,之后不再弹。']]));

  // ================= 发现(原「合作」tab,pages/merchant/relation)=================
  function post(p) {
    return '<view class="c-post-card"><view class="post-card post-card--light"><view class="post-card__avatar"><img class="post-card__avatar-img" src="' + C + p[3] + '"></view><view class="post-card__main"><view class="post-card__head"><view class="post-card__identity"><view class="post-card__name"><text class="post-card__nick">' + p[0] + '</text></view>' +
      '<view class="post-card__meta"><text class="post-card__club">' + p[1] + '</text><text> · </text><text>' + p[4] + '</text></view></view></view>' +
      '<view class="post-card__body"><view class="post-card__text">' + p[2] + '</view></view><view class="post-card__gallery"><view class="post-card__media"><view class="post-card__media-row"><img class="post-card__media-pic" src="' + C + p[5] + '"></view></view></view>' +
      '<view class="post-card__actions" style="display:flex;gap:22px;margin-top:10px;color:var(--cy-color-text-tertiary);font-size:13px"><text data-act="like">♡ 12</text><text data-toast="打开评论">评论 3</text><text>转发</text><text>分享</text></view></view></view></view>';
  }
  function discover() {
    return '<view class="sv"><view class="page theme-merchant m-flush-nav pg-rel" style="padding-top:87px">' + U.tabs({ variant: 'fill', wide: true, active: 'feed', tabs: [{ key: 'feed', label: '帖子', mk: 2 }, { key: 'club', label: '俱乐部' }], cls: 'rel-tabs' }) +
      '<view class="body">' + [['阿乔', '夜跑上海', '刚走完「上海像一部旧电影」第三站,四行仓库的光太好了', 'treasure-hunter.jpg', '2小时', 'old-movie.jpg'], ['城市定向免费测试俱乐部', '城市定向免费测试俱乐部', '周六 14:00 莘庄集合,带 12 人跑「副本人生」', 'heat-escape.jpg', '5小时', 'anomaly-coordinate.jpg']].map(post).join('') +
      '</view><view style="height:110px"></view></view></view>' + U.navBar({ title: '发现', back: false, mk: 1 }) + mtab(3);
  }
  S.push(scr('disc', '发现(原「合作」tab)', '发现', 'pages/merchant/relation/index.wxml', 'cyp theme-merchant', discover(), [
    [1, '底部第 4 个 tab「合作」改名「发现」。'],
    [2, '「帖子」是全站帖文(和玩家版发现同一条全站流,#677),不再只看合作过的俱乐部;帖文卡用 master cy-post-card 浅色。商家可以点赞、评论(10-30 你定)。']]));

  document.getElementById('screens').innerHTML = S.join('');

  // ================= 交互 =================
  var opened = [];
  function cur() { return PROTO.cur(); }
  function q(sel) { return cur().querySelector(sel); }
  function openDw(id) {
    var el = q('#' + id); if (!el) return;
    opened.forEach(function (o) { o.classList.add('is-under'); });
    el.hidden = false; el.classList.remove('is-under'); opened.push(el); PROTO.placeMarks();
  }
  function closeDw(id) {
    var el = q('#' + id); if (!el) return; el.hidden = true;
    opened = opened.filter(function (o) { return o !== el; });
    if (opened.length) opened[opened.length - 1].classList.remove('is-under');
    PROTO.placeMarks();
  }
  var INVSTEP = [['对方发出邀请', '10-27'], ['你已查看', ''], ['你已接受', '10-28'], ['补供给', ''], ['上线', '']];
  window.ACT = {
    nsOpen: function (el, ev) { if (ev.target.closest('[data-act="nsX"]')) return; if (!el.classList.contains('rv-msg-stack--open') && MSGS.length > 1) { q('#nsHost').innerHTML = msgStack(true); } else PROTO.toast('打开这条消息'); },
    nsX: function (el, ev) { ev.stopPropagation(); MSGS.splice(+el.getAttribute('data-i'), 1); q('#nsHost').innerHTML = msgStack(MSGS.length > 1); PROTO.toast('已读'); },
    nsClear: function () { var s = q('.p6-ns'); s.classList.add('is-leave'); setTimeout(function () { MSGS = []; q('#nsHost').innerHTML = msgStack(false); PROTO.toast('全部已读'); }, 380); },
    remind: function (el) { el.classList.toggle('is-on'); PROTO.toast(el.classList.contains('is-on') ? '新邀约会提醒你' : '已关闭新邀约提醒'); },
    openInv: function (el) {
      var v = INV[+el.getAttribute('data-i')], k = v.step;
      q('#invT').textContent = v.name; q('#invS').textContent = v.from + ' · ' + v.st;
      q('#invVt').innerHTML = INVSTEP.map(function (s, i) {
        var c = i < k ? 'done' : (i === k ? 'run' : 'todo');
        return '<view class="it ' + c + '"><view class="t"><text>' + s[0] + '</text><span>' + (i === k ? '待你处理' : s[1]) + '</span></view>' +
          (i === 3 && i === k ? '<view class="p6-sub"><text class="ok">门店照片</text><text class="ok">接待时段</text><text class="wait">到店权益说明</text></view>' : '') + '</view>';
      }).join('');
      PROTO.openSheet('inv-sheet', 'full');
    },
    dw: function (el) { openDw(el.getAttribute('data-id')); },
    dwClose: function (el) { closeDw(el.getAttribute('data-id')); },
    inviteClub: function (el) { var i = +el.getAttribute('data-i'); CLUBS[i][2] = 'invited'; q('#dw-club .dw-scroll-content').innerHTML = clubBody() + '<view class="dw-scroll-pad"></view>'; var h = q('#dw-clubhome'); if (h) closeDw('dw-clubhome'); PROTO.toast('已邀约,等对方回复'); },
    clubHome: function (el) {
      var i = +el.getAttribute('data-i'), c = CLUBS[i];
      q('#dw-clubhome-wrap').innerHTML = drawer('dw-clubhome', c[0], clubHome(i), { back: true, cta: c[2] ? '' : '<view class="dw-btn" data-act="inviteClub" data-i="' + i + '">邀约这个俱乐部</view>' });
      openDw('dw-clubhome');
    },
    station: function () { openDw('dw-station'); },
    inviteMer: function (el) { el.outerHTML = '<text class="dr-tag dr-tag--wait p6-pending">已邀约</text>'; PROTO.toast('已邀约 · 引流型 · 无分成'); },
    apply: function (el) { q('#applyAct').innerHTML = '<text class="dr-tag ' + (el.getAttribute('data-v') === '1' ? 'dr-tag--ok">已通过' : '">已婉拒') + '</text>'; },
    bcScope: function (el) { var i = +el.getAttribute('data-i'); [].forEach.call(q('#bcScope').children, function (c, k) { c.style.background = k === i ? 'var(--cy-color-action-primary-bg)' : 'var(--cy-color-bg-surface-subtle)'; c.style.color = k === i ? 'var(--cy-color-action-primary-fg)' : 'var(--cy-color-text-primary)'; }); q('#bcN').textContent = ['36 人', '22 人', '14 人'][i]; },
    bcSend: function () { var n = q('#bcN').textContent; closeDw('dw-bcast'); PROTO.toast('已发送给 ' + n); },
    moreSw: function () { var open = !q('#dw-more [data-mk="2"]'); q('#dw-more .dw-scroll-content').innerHTML = moreBody(open) + '<view class="dw-scroll-pad"></view>'; },
    like: function (el) { var on = el.getAttribute('data-on') !== '1'; el.setAttribute('data-on', on ? '1' : '0'); el.textContent = (on ? '♥ 13' : '♡ 12'); el.style.color = on ? 'var(--cy-color-status-danger,#E5484D)' : ''; },
    intro: function () { showIntro(); },
    introNext: function () { if (introI < 3) { introI++; paintIntro(); } else closeIntro(); },
    introClose: function () { closeIntro(); }
  };
  // 客户弹窗右上「广播」
  var STEPS = [{ L: 'translate(-26px,14px) rotate(-24deg)', T: 'translate(22px,-30px) rotate(16deg)', B: 'translate(28px,30px) rotate(-14deg)', I1: 'translate(18px,-46px) rotate(38deg)', I2: 'translate(-38px,-30px) rotate(-30deg)', I3: 'translate(40px,-6px) rotate(26deg)' },
    { L: 'translate(-10px,4px) rotate(-8deg)', T: 'translate(8px,-12px) rotate(6deg)', B: 'translate(10px,12px) rotate(-5deg)', I1: 'translate(6px,-16px) rotate(12deg)', I2: 'translate(-14px,-8px) rotate(-10deg)', I3: 'translate(14px,2px) rotate(8deg)' }, {}, {}];
  var TXT = [['它是你的店在城瘾里的分身', '出现在漫游地图和店铺主页上'], ['路过的玩家会和它打招呼', '按你写的招呼语和性格说话'], ['它能回答菜单和营业时间', '读你门店资料里的内容'], ['起个名字、选个性格就能用', '温柔 / 可爱 / 毒舌,随时可改']];
  var introI = 0, seen = false;
  function paintIntro() {
    var sh = q('#npc-intro'); if (!sh) return;
    [].forEach.call(sh.querySelectorAll('.lp'), function (g) { g.style.transform = STEPS[introI][g.getAttribute('data-p')] || 'none'; });
    var b = sh.querySelector('.lbub'); b.style.opacity = introI === 3 ? 1 : 0; b.style.transform = introI === 3 ? 'scale(1)' : 'scale(.2)';
    sh.querySelector('#inT').textContent = TXT[introI][0]; sh.querySelector('#inS').textContent = TXT[introI][1];
    [].forEach.call(sh.querySelectorAll('.p6-intro__dots i'), function (d, k) { d.classList.toggle('on', k === introI); });
    sh.querySelector('#inGo').textContent = introI === 3 ? '知道了' : '下一步';
  }
  function showIntro() { introI = 0; PROTO.openSheet('npc-intro'); paintIntro(); }
  function closeIntro() { seen = true; PROTO.closeSheets(); }
  window.ON_ENTER = function (id) {
    opened = [];
    [].forEach.call(document.querySelectorAll('.c-project-drawer.p6-stack'), function (d) { d.hidden = true; d.classList.remove('is-under'); });
    var auto = { 'ws-club': ['dw-club'], 'ws-recruit': ['dw-recruit'], 'ws-cust': ['dw-cust'], 'ws-more': ['dw-more'], 'ws-profit': ['dw-profit'] }[id];
    if (auto) auto.forEach(openDw);
    if (id === 'ws-cust') addBroadcast();
    if (id === 'npc' && !seen) setTimeout(showIntro, 300);
    var m = /[?&]scroll=(\d+)/.exec(location.search); if (m) setTimeout(function () { var sv = cur().querySelector('.sv'); if (sv) sv.scrollTop = +m[1]; PROTO.placeMarks(); }, 60);
  };
  function addBroadcast() {
    var head = q('#dw-cust .dw-head'); if (!head || head.querySelector('.p6-bc')) return;
    var b = document.createElement('view'); b.className = 'dn-btn p6-btn-g p6-bc'; b.setAttribute('data-act', 'dw'); b.setAttribute('data-id', 'dw-bcast'); b.setAttribute('data-mk', '1'); b.style.marginRight = '8px'; b.textContent = '广播';
    head.insertBefore(b, head.querySelector('.dw-close'));
  }
  var _dw = window.ACT.dw; window.ACT.dw = function (el) { _dw(el); if (el.getAttribute('data-id') === 'dw-cust') addBroadcast(); };
})();
