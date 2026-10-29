// P-4 主题从模板到发布(V-24 / V-25):模板预览 → 查看详情(还原的主题模板详情)→ 主题配置(内联必填)→ 票务 → 剧情预览。
(function () {
  var U = UI, I = U.icon, E = U.esc;
  var C = 'assets/covers/';
  var COVER = C + 'flavor-tour.jpg';
  var NODES = [
    ['春启新巷 · 老字号新春市集', '上海市黄浦区山西南路、福州路口', '拍照打卡', 'old-movie.jpg'],
    ['上海第一食品商店', '上海市黄浦区南京东路720号', '盲品三选一', 'treasure-hunter.jpg'],
    ['国际饭店帆声饼屋', '上海市黄浦区黄河路28号', '拍物成卡', 'time-pollution.jpg'],
    ['光明邨大酒家', '上海市黄浦区淮海中路588号', '猜数字', 'midlife-day.jpg']
  ];
  // 每站的玩法:能力 key 与作者入口名照《全游戏复用索引》,只换题面,调用原判定
  var GAMES = [
    ['qaShot', '拍照打卡', 'camera', '在市集里找到一块老字号招牌，拍下来交卷', '约 10 分钟'],
    ['qaPick + blindTaste', '选项问答 · 闭眼盲品', 'category-food', '闭眼尝三块糕点，选出哪一块是苔条饼', '约 15 分钟'],
    ['objectCard', '拍物成卡', 'image', '拍下刚出炉的蝴蝶酥，抠出来收进藏品册', '约 10 分钟'],
    ['estimate', '猜数字', 'sort', '猜光明邨一天卖出多少只鲜肉月饼，猜中就盖章', '约 15 分钟']
  ];
  var STORY = {
    open: '南京东路的午后，空气里一半是糖炒栗子，一半是葱油。你手里有一张空白的风味护照，四个印章位还空着 —— 老字号的掌柜们各藏着一枚。',
    ch: [['第 1 章', '上海自由探索', '从市集出发，沿南京东路一路吃到淮海中路。每到一家，完成掌柜出的小题，就能在护照上盖一枚风味印章。']],
    end: '四枚印章盖满，护照最后一页会自己合上，盖下「老饕」。'
  };
  function notes(list) { return '<ol class="notes">' + list.map(function (t) { return '<li data-n="' + t[0] + '">' + t[1] + '</li>'; }).join('') + '</ol>'; }

  // ===== ① 模板预览(pages/templatedetail/topic-preview)=====
  var STEPS = ['随时开始', '任选一个轻动作', '玩满 3 次通关'];
  var CROP = [[50, 50, 1], [18, 40, 1.7], [82, 35, 1.8], [30, 75, 1.9], [70, 70, 1.7]];
  var FAN_X = [0, 62, 108, 140], FAN_R = [0, 6, 11, 15];
  function deck(flip, fixed) {
    var cards = [{ cover: 1, title: '风味巡游', steps: NODES.map(function (n) { return n[0]; }) }, { title: '风味巡游', steps: STEPS }];
    var n = cards.length, sel = 0;
    return cards.map(function (c, i) {
      var o = i - sel; var a = Math.min(Math.abs(o), 3), s = Math.sign(o), crop = CROP[i % CROP.length], st;
      if (flip && !a) st = 'transform: scale(1.25); z-index: 40; opacity: 1;';
      else if (flip) st = 'transform: translateX(' + (s * 124) + 'px) translateY(8px) rotate(' + (s * 8) + 'deg) scale(.9); z-index: ' + (10 - a) + '; opacity: ' + (a === 1 ? 1 : 0) + '; filter: brightness(.7);';
      else st = 'transform: translateX(' + (s * FAN_X[a]) + 'px) translateY(' + (a * 6) + 'px) rotate(' + (s * FAN_R[a]) + 'deg) scale(' + (1 - a * 0.07) + '); z-index: ' + (10 - a) + '; opacity: ' + (a > 2 ? 0 : 1) + ';' + (a ? ' filter: brightness(' + (1 - a * 0.22) + ');' : '');
      return '<view class="kc' + (flip && !a ? ' kc--fl' : '') + '" style="' + st + '"' + (!a ? ' data-act="flip"' : '') + '><view class="kin"><view class="fc"><img class="fc__img" src="' + COVER + '" style="object-fit:cover;transform:scale(' + crop[2] + ');transform-origin:' + crop[0] + '% ' + crop[1] + '%"></view>' +
        '<view class="bk"' + (!a && fixed ? U.mk(3) : '') + '><text class="bk__t">' + c.title + '</text><view class="bk__ol">' + c.steps.map(function (t, k) { return '<view class="bk__li"><text class="bk__no">' + (k + 1) + '</text><text class="bk__tx">' + t + '</text></view>'; }).join('') + '</view>' +
        '<view class="bk__ft"><img class="bk__clock" src="assets/img/clock.svg">约 2 小时</view></view></view></view>';
    }).join('');
  }
  function preview(o) {
    o = o || {};
    return '<view class="' + (o.fixed ? 'p4-fix' : '') + '" style="height:100%"><view class="tp theme-dark" style="--glow: hsla(355,35%,34%,.85);height:100%">' +
      '<view class="tp-glow"></view>' +
      '<view class="stk" style="margin-top:87px;">' + deck(o.flip, o.fixed) + '</view>' +
      '<view class="tp-info' + (o.flip ? ' tp-info--dim' : '') + '"><text class="tp-title">风味巡游</text><view class="tp-ck">' +
      ['1 个玩法，一个人就能玩', '随时开始，可以跨天', '约 2 小时'].map(function (t) { return '<view class="tp-ck__li"><img class="tp-ck__ic" src="assets/img/check.svg"><text>' + t + '</text></view>'; }).join('') + '</view></view>' +
      '<view class="tp-cta"' + U.mk(o.fixed ? 1 : 0) + '><view class="tp-cta__glow"></view><view class="tp-cta__btn"' + (o.fixed ? ' data-go="p4-detail"' + U.mk(2) : ' data-toast="（改前）用这个模板 → 直接复制草稿进主题配置"') + '>' + (o.fixed ? '查看详情' : '用这个模板') + '</view></view>' +
      '</view></view>' + U.navBar({ overlay: true, tint: 'light' });
  }

  // ===== ② 主题模板详情(pages/templatedetail/templatedetail.wxml,isTopicTemplate 档)=====
  function detail(tab) {
    tab = tab || 'details';
    var gallery = NODES.map(function (n) { return C + n[3]; });
    var story = '<view class="xb-block xb-block--lead"' + U.mk(4) + '><view class="xb-section-title">' + I('info', 30) + '<text>剧情</text></view>' +
      '<view class="xb-body xb-body--soft">' + STORY.open + '</view>' +
      STORY.ch.map(function (c) {
        return '<view class="st-ch"><view class="st-ch__hd"><text class="st-ch__no">' + c[0] + '</text><text class="st-ch__t">' + c[1] + '</text></view><view class="st-ch__p">' + c[2] + '</view>' +
          '<view class="st-ch__path">' + NODES.map(function (n, i) { return '<view class="st-ch__stop"><text class="st-ch__dot">' + (i + 1) + '</text><text class="ep1">' + n[0] + '</text></view>'; }).join('') + '</view></view>';
      }).join('') +
      '<view class="st-end"><text class="st-end__k">结局</text><text>' + STORY.end + '</text></view></view>';
    var details = '<view class="xb-mosaic-wrap"><scroll-view class="xb-mosaic-scroll" scroll-x><view class="xb-mosaic-inner">' + gallery.map(function (g) { return '<view class="xb-mosaic-cell"><img src="' + g + '" style="width:100%;height:100%;object-fit:cover"></view>'; }).join('') + '</view></scroll-view></view>' +
      story +
      '<view class="xb-stat-grid">' + [['1', '章节'], ['4', '节点'], ['2 小时', '总时长']].map(function (s) { return '<view class="xb-stat-card"><view class="xb-stat-value">' + s[0] + '</view><view class="xb-stat-label">' + s[1] + '</view></view>'; }).join('') + '</view>' +
      '<view class="xb-block"><view class="xb-section-title">' + I('route', 30) + '<text>规则</text></view><view class="xb-rule-card"><view class="xb-rule-box"><view class="xb-rule-list">' +
      ['随时开始，可以跨天，进度自动保存。', '每到一站，完成这一站的玩法就盖章。', '四站都盖章即通关，领取主题勋章。'].map(function (t, i) { return '<view class="xb-rule-step"><view class="xb-rule-mark">' + (i + 1) + '</view><view class="xb-rule-copy"><text class="xb-body xb-body--soft">' + t + '</text></view></view>'; }).join('') + '</view></view></view></view>' +
      '<view class="xb-block"><view class="xb-section-title">' + I('image', 30) + '<text>节点</text></view><scroll-view class="xb-node-scroll" scroll-x><view class="xb-node-inner">' +
      NODES.map(function (n) { return '<view class="xb-node-card"><img class="xb-node-img" src="' + C + n[3] + '" style="object-fit:cover"><view class="xb-node-foot"><text class="xb-node-name">' + n[0] + '</text><text class="xb-node-desc ep1">' + n[2] + '</text></view></view>'; }).join('') + '</view></scroll-view></view>' +
      '<view class="xb-block"><view class="xb-section-title">' + I('star', 30) + '<text>通关勋章</text></view><view class="xb-quote-card">' + MEDALS2.svg('t-flavor', { cls: 'p4-medal' }) + '<view class="xb-quote-copy"><text class="xb-quote-author">老饕</text><text class="xb-quote-text">走完四站时点亮</text></view></view></view>';
    var caps = '<view class="cap-sum"' + U.mk(5) + '>' + ['自由探索', '单人可玩', '可跨天', '4 个玩法', '约 2 小时'].map(function (c) { return '<text class="xb-chip">' + c + '</text>'; }).join('') + '</view>' +
      '<view class="cap-list"' + U.mk(6) + '>' + NODES.map(function (n, i) {
        var g = GAMES[i];
        return '<view class="cap-stop"><view class="cap-stop__no">' + (i + 1) + '</view><view class="cap-stop__main"><view class="cap-stop__name ep1">' + n[0] + '</view>' +
          '<view class="cap-stop__game">' + I(g[2], 26) + '<text>' + g[1] + '</text></view><view class="cap-stop__do">' + g[3] + '</view><view class="cap-stop__dur">' + I('clock', 22) + '<text>' + g[4] + '</text></view></view></view>';
      }).join('') + '</view>' +
      '<view class="xb-caps" style="margin-top:8px"><view class="xb-caprow"><text class="xb-caprow-k">素材</text><view class="xb-caprow-v"><text class="xb-chip">模板封面可直接使用</text><text class="xb-chip">节点照片可直接使用</text></view></view></view>';
    return '<view class="theme-dark" style="height:100%"><view class="xb-page" style="height:100%">' +
      '<view class="xb-bg"><img class="xb-bg-img" src="' + COVER + '" style="object-fit:cover"><view class="xb-bg-mask"></view></view>' +
      '<scroll-view class="xb-scroll xb-scroll--bleed" scroll-y style="height:100%;position:relative"><view style="height:87px"></view>' +
      '<view class="xb-head"' + U.mk(1) + '><view class="xb-head-cover"><img src="' + COVER + '" style="width:100%;height:100%;object-fit:cover"></view><view class="xb-head-meta"><view class="xb-head-title ep2">风味巡游</view><view class="xb-head-sub ep1">城瘾官方</view><text class="xb-head-badge">官方</text></view></view>' +
      '<view class="xb-cta"' + U.mk(2) + '><view class="p4-apply" data-go="p4-config">应用</view><button class="xb-share" data-toast="分享给微信好友">' + I('share', 40) + '</button></view>' +
      '<view class="xb-tabs-wrap"' + U.mk(3) + '>' + U.tabs({ tabs: [{ key: 'details', label: '详情' }, { key: 'capabilities', label: '能力' }], active: tab, group: 'tdt' }) + '</view>' +
      '<view data-pane="tdt:details"' + (tab === 'details' ? '' : ' hidden') + '>' + details + '</view><view data-pane="tdt:capabilities"' + (tab === 'capabilities' ? '' : ' hidden') + '>' + caps + '</view>' +
      '<view style="height:60px"></view></scroll-view></view></view>' + U.navBar({ overlay: true, pill: true });
  }

  // ===== ③ 主题配置(pages/publish/fabu,第 1 页)=====
  function config(o) {
    o = o || {};
    var miss = o.ticketMissing === undefined ? 4 : o.ticketMissing;
    var nodes = NODES.map(function (n, i) {
      return '<view class="slopes-node-movable" style="height:70.72px"><view class="slopes-node-row"><view class="slopes-node-rail slopes-node-rail--handle"><text class="slopes-node-num">' + (i + 1) + '</text></view>' +
        '<view class="slopes-node-fields"><view class="slopes-node-photo"><img class="slopes-node-photo-img" src="' + C + n[3] + '" style="width:100%;height:100%;object-fit:cover"></view>' +
        '<view class="slopes-node-loc"><text class="slopes-node-loc-name">' + n[0] + '</text><text class="slopes-node-loc-addr">' + n[1] + '</text></view><view class="slopes-node-more">' + I('more', 32) + '</view></view></view></view>';
    }).join('');
    return '<view class="fabu slopes theme-topic-editor" style="height:100%;background:var(--cy-color-bg-page)">' +
      '<view class="slopes-back" style="top:51px" data-back><view class="slopes-back__ico"></view></view>' +
      '<scroll-view class="slopes-sheet slopes-sheet--full" scroll-y style="--cy-sheet-chrome-top:87px;--cy-sheet-chrome-action:51px;position:absolute;top:0;bottom:0;left:0;right:0">' +
      '<view class="pd-title-field" id="fieldName"><text class="g-req">*</text><input class="pd-title-input" value="风味巡游" maxlength="30"><text class="pd-save-state pd-save-state--saved">已存云端</text></view>' +
      '<view class="c-card pd-entry-card"><view class="cy-card" data-toast="打开主题详情"><view class="pd-entry-card__row"><view class="pd-entry-card__main"><text class="pd-entry-card__title">主题详情</text>' +
      '<text class="pd-entry-card__sub"' + U.mk(2) + '>已填完</text></view><view class="pd-row-chevron">' + I('chevron-right', 32) + '</view></view></view></view>' +
      '<view class="pd-modecards"><view class="pd-modecard"><view class="pd-modecard__ico">' + I('tab-explore', 36) + '</view><text class="pd-modecard__eyebrow">模式</text><text class="pd-modecard__name">自由探索</text><view class="pd-modecard__action"><text>切换</text>' + I('chevron-right', 24) + '</view></view>' +
      '<view class="pd-modecard" data-go="p4-ticket"' + U.mk(1) + '><view class="pd-modecard__ico">' + I('coupon', 36) + '</view><text class="pd-modecard__eyebrow">票务</text><text class="pd-modecard__name">票务设置</text>' +
      (miss ? '<view class="pd-modecard__action is-missing"><text><b>*</b> 还有 ' + miss + ' 项未填</text>' + I('chevron-right', 24) + '</view>' : '<view class="pd-modecard__action"><text>编辑</text>' + I('chevron-right', 24) + '</view>') + '</view></view>' +
      '<view class="slopes-section" id="routeSection"><view class="slopes-section-hd"><text class="slopes-section-tit">章节</text></view>' +
      '<view class="c-card slopes-chapter-block"><view class="cy-card"><view class="slopes-chapter-hd"><view class="slopes-chapter-copy"><text class="slopes-chapter-name">第1章 上海自由探索</text><view class="slopes-chapter-offer"><text>未开放商家承接</text></view></view><view class="slopes-chevron slopes-chevron--sm">' + I('chevron-right', 28) + '</view></view>' +
      '<view class="slopes-chapter-tags"><view class="slopes-tag is-ok">' + I('check', 22) + '<text>剧情已写</text></view><text class="slopes-tag">4 节点</text></view>' +
      '<view class="slopes-nodes"><view class="slopes-nodes-area">' + nodes + '</view></view>' +
      '<view class="slopes-chapter-actions"><view class="c-btn" style="flex:1"><view class="btn btn--secondary btn--sm">编辑章节</view></view><view class="c-btn" style="flex:1"><view class="btn btn--primary btn--sm">' + I('plus', 24) + '<text>创建节点</text></view></view></view></view></view>' +
      '<view class="slopes-add-chapter"><view class="slopes-add-chapter__ico">' + I('plus', 28) + '</view><text class="slopes-add-chapter__txt">添加章节</text></view></view>' +
      '<view style="height:140px"></view></scroll-view>' +
      '<view class="slopes-tabbar"><view class="slopes-tabbar__tabs"><view class="slopes-tab is-on">' + I('list', 46) + '<view class="slopes-tab__dot"></view></view><view class="slopes-tab">' + I('map', 46) + '<view class="slopes-tab__dot"></view></view></view>' +
      '<view class="c-btn slopes-tabbar__draftCta"><view class="btn btn--secondary" data-toast="草稿已保存">保存草稿</view></view>' +
      '<view class="c-btn slopes-tabbar__cta"' + U.mk(3) + '><view class="btn btn--primary" data-go="p4-ticket"><text>下一步</text>' + I('arrow-right', 28) + '</view></view></view>' +
      '</view>';
  }

  // ===== ④ 票务(fabu 第 2 页 step3.wxml)+ 发布者实名(从发布确认搬来)=====
  function ticket(o) {
    o = o || {};
    var f = !!o.filled;
    function row(icon, title, val, ph, id) {
      return '<view class="node-card__row"' + (id ? ' id="' + id + '"' : '') + ' data-act="fill"><view class="node-card__ico">' + I(icon, 32) + '</view><view class="node-card__main"><text class="node-card__title">' + title + '<text class="g-req">*</text></text>' +
        '<text class="node-card__value' + (f ? '' : ' is-missing') + '">' + (f ? val : ph) + '</text></view><view class="node-pill"><text>选择</text>' + I('chevron-right', 24) + '</view></view>';
    }
    function hd(t, n, target, mk) {
      return '<view class="sec-hd"><view class="pd-section-title">' + t + '</view>' + (!f && n ? '<view class="req-left" data-act="jump" data-target="' + target + '"' + U.mk(mk) + '><b>*</b> 还有 ' + n + ' 项未填</view>' : '') + '</view>';
    }
    return '<view class="fabu slopes theme-topic-editor" style="height:100%;background:var(--cy-color-bg-page)">' +
      '<view class="slopes-back" style="top:51px" data-back><view class="slopes-back__ico"></view></view>' +
      '<scroll-view class="slopes-sheet slopes-sheet--full" scroll-y style="--cy-sheet-chrome-top:87px;--cy-sheet-chrome-action:51px;position:absolute;top:0;bottom:0;left:0;right:0">' +
      '<view class="slopes-info-page gen-page"><view class="c-page-title"><view class="pt pt--left pt--flush"><view class="pt__title">票务设置</view></view></view>' +
      hd('档期', 3, 'tkDates', 1) +
      '<view class="node-card">' + row('calendar', '开始日期', '11月1日', '选择开始日期', 'tkDates') + '<view class="node-card__hr"></view>' + row('calendar', '结束日期', '12月31日', '选择结束日期') + '<view class="node-card__hr"></view>' + row('clock', '招商截止日期', '10月31日', '选择日期') + '</view>' +
      '<view class="pd-section-title">票务</view><view id="ticketSection"><view class="node-card"><view class="node-card__row"><view class="node-card__ico">' + I('coupon', 32) + '</view><view class="node-card__main"><text class="node-card__title">标准票</text><text class="node-card__value">免费 · 自由探索 · 100 张</text></view><view class="node-pill"><text>编辑</text>' + I('chevron-right', 24) + '</view></view></view>' +
      '<view class="slopes-add-chapter"><view class="slopes-add-chapter__ico">' + I('plus', 28) + '</view><text class="slopes-add-chapter__txt">添加新票种</text></view></view>' +
      hd('发布者实名', 1, 'tkId', 2) +
      '<view class="pc-identity" id="tkId"' + U.mk(f ? 0 : 3) + '>' +
      '<view class="c-field"><view class="fd fd--text' + (f ? ' is-filled' : '') + '"><view class="fd__label"><text class="fd__label-t">真实姓名</text><text class="fd__req">*</text></view><view class="fd__control"><input class="fd__input" placeholder="与身份证一致" value="' + (f ? '李明' : '') + '"></view></view></view>' +
      '<view class="c-field"><view class="fd fd--text' + (f ? ' is-filled' : '') + '"><view class="fd__label"><text class="fd__label-t">身份证号</text><text class="fd__req">*</text></view><view class="fd__control"><input class="fd__input" placeholder="18 位" value="' + (f ? '310***********1234' : '') + '"></view></view></view>' +
      '<view class="c-consent-check pc-identity__consent"><view class="cck"><view class="cck__box" style="width:20px;height:20px;border-radius:50%;' + (f ? 'background:#07C160' : 'border:1.5px solid var(--cy-color-text-tertiary)') + ';flex:none"></view><view class="cck__txt">同意向城瘾提供真实姓名与身份证号，仅用于发布人身份核验，不对外展示</view></view></view>' +
      '<view class="pc-identity__foot">修改实名请联系客服</view></view>' +
      '</view><view style="height:140px"></view></scroll-view>' +
      '<view class="slopes-tabbar"><view class="slopes-tabbar__tabs"><view class="slopes-tab" data-back>' + I('back', 46) + '</view></view>' +
      '<view class="c-btn slopes-tabbar__cta"' + U.mk(4) + '><view class="btn btn--primary" data-act="' + (f ? 'goStory' : 'firstMissing') + '"><text>预览</text>' + I('arrow-right', 28) + '</view></view></view></view>';
  }

  // ===== ⑤ 剧情预览(替代文字版发布确认)=====
  function story(o) {
    o = o || {};
    var blocks = [
      '<view class="sp-blk" data-go="p4-config"><view class="sp-say"><text class="who">旁白</text>南京东路的午后，空气里一半是糖炒栗子，一半是葱油。你手里有一张空白的风味护照，四个印章位还空着。</view><view class="sp-edit">' + I('edit', 24) + '</view></view>'
    ];
    NODES.forEach(function (n, i) {
      blocks.push('<view class="sp-blk sp-node" data-go="p4-config"><view class="sp-node__no">' + (i + 1) + '</view><view class="sp-node__card"><img class="sp-node__img" src="' + C + n[3] + '"><view class="sp-node__body"><view class="sp-node__name">' + n[0] + '</view><view class="sp-node__addr">' + n[1] + '</view><view class="sp-node__play">' + I('flag', 22) + n[2] + '</view></view></view><view class="sp-edit">' + I('edit', 24) + '</view></view>');
      if (i === 1) blocks.push('<view class="sp-blk" data-go="p4-config"><view class="sp-say"><text class="who">店员</text>「蝴蝶酥要趁热，凉了就是另一种甜。」</view><view class="sp-edit">' + I('edit', 24) + '</view></view>');
    });
    return '<view class="sv"><view class="sp">' +
      '<view class="sp-hero"' + U.mk(3) + '><img src="' + COVER + '"><view class="sp-hero__copy"><view class="sp-title">风味巡游</view><view class="sp-meta"><text class="sp-chip">自由探索</text><text class="sp-chip">1 章 · 4 个节点</text><text class="sp-chip">约 2 小时</text><text class="sp-chip">长期开放</text></view></view></view>' +
      '<view class="sp-sec"><view class="sp-lede">沿着南京东路一路吃过去：一碗老字号的汤、一块刚出炉的蝴蝶酥、一家只做熟客的本帮菜。</view></view>' +
      '<view class="sp-ticket" data-go="p4-ticket"' + U.mk(4) + '><text>标准票</text><b>¥0</b></view>' +
      '<view class="sp-ch"><b>第 1 章</b><text>上海自由探索</text></view>' +
      '<view' + U.mk(5) + '>' + blocks.join('') + '</view>' +
      '<view class="sp-end"' + U.mk(6) + '>' + MEDALS2.svg('t-flavor', { cls: 'p4-medal' }) + '<view><view class="t">通关勋章</view><view class="n">老饕</view></view></view>' +
      '</view></view>' +
      '<view class="sp-top"><view class="sp-x" data-go="p4-config"' + U.mk(1) + '>' + I('close', 32) + '</view><view class="sp-done" data-act="publish"' + U.mk(2) + '>完成</view></view>';
  }

  var N_DETAIL = [
    [1, '主题模板详情页 = templatedetail 的主题档(Figma 4312:13285):封面 + 标题 + 发布方。'],
    [2, '第二轮(业主「应用和旁边分享一样的样式」):「应用」做成和分享按钮同一套 —— 同样的半透明圆角底、同样高、白字,不描边;点了直接复制草稿进主题配置,不再二次确认。'],
    [3, '详情 / 能力两个 tab。'],
    [4, '新增「剧情」:开场白、每章一段 + 这一章的节点顺序、结局(见「剧情」屏)。']
  ];
  var SCREENS = [
    ['p4-preview-before', '改前 · 模板预览(按钮出屏)', '① 模板预览', 'cyp theme-dark pg-tp', preview(), 'pages/templatedetail/topic-preview/index.wxml', ''],
    ['p4-preview', '模板预览 · 查看详情', '① 模板预览', 'cyp theme-dark pg-tp', preview({ fixed: true }), 'pages/templatedetail/topic-preview/index.wxml', notes([[1, '按钮回到屏内:master 里 <code>.tp &gt; view:not(.tp-glow){position:relative}</code> 把 fixed 的按钮条改成了 relative,left/right 32rpx 变成整宽再右移 → 溢出。改为 <code>.tp &gt; .tp-cta{position:fixed}</code>。'], [2, '「用这个模板」改成「查看详情」:这一页只是预览,点了进主题模板详情页,不再直接复制草稿。']])],
    ['p4-preview-flip', '模板预览 · 翻面', '① 模板预览', 'cyp theme-dark pg-tp', preview({ fixed: true, flip: true }), 'pages/templatedetail/topic-preview/index.wxss .bk', notes([[3, '翻面后的卡背去掉 1rpx 白描边,只留深色卡面和阴影。'], [2, '同上。']])],
    ['p4-detail', '主题模板详情 · 详情', '② 主题模板详情', 'cyp theme-dark pg-td', detail('details'), 'pages/templatedetail/templatedetail.wxml(isTopicTemplate 档)', notes(N_DETAIL)],
    ['p4-detail-story', '主题模板详情 · 剧情', '② 主题模板详情', 'cyp theme-dark pg-td', detail('details'), 'pages/templatedetail/templatedetail.wxml(isTopicTemplate 档)', notes([[4, '「剧情」一节:开场白全文 → 每一章(章名 + 一段 + 这一章依次经过的节点)→ 结局。玩家看到的就是这个故事,不用点开配置。']])],
    ['p4-detail-cap', '主题模板详情 · 能力', '② 主题模板详情', 'cyp theme-dark pg-td', detail('capabilities'), 'pages/templatedetail/templatedetail.wxml「能力」', notes([[5, '能力顶上一行:方式 / 人数 / 跨天 / 玩法数 / 总时长。'], [6, '按站列出这个主题实际有什么游戏:第几站、站名、玩法(拍照打卡 / 选项问答 · 闭眼盲品 / 拍物成卡 / 猜数字,名字与能力 key 照《全游戏复用索引》的作者入口)、玩家具体做什么、大概多久。'], [2, '「应用」与右边分享同一个样式。']])],
    ['p4-config', '主题配置 · 还有未填项', '③ 主题配置', 'cyp theme-topic-editor pg-fabu', config(), 'pages/publish/fabu/index.wxml(第 1 页)', notes([[1, 'V-25:票务卡的「编辑」在有必填项没填时换成红色「* 还有 4 项未填」;点它直接进票务页并定位到第一项。'], [2, '主题详情卡的摘要同一套说法:没填完显示红色「* 还有 N 项未填」,填完是「已填完」。模板自带封面算已填(V-13)。'], [3, '右下「发布」→「下一步」,永远可点,去票务页;原来必填没齐就是灰的、点不了。']])],
    ['p4-config-ok', '主题配置 · 票务已填完', '③ 主题配置', 'cyp theme-topic-editor pg-fabu', config({ ticketMissing: 0 }), 'pages/publish/fabu/index.wxml(第 1 页)', notes([[1, '填完后票务卡回到「编辑 ›」。']])],
    ['p4-ticket', '票务 · 内联必填', '④ 票务', 'cyp theme-topic-editor pg-fabu', ticket(), 'pages/publish/fabu/step3.wxml', notes([[1, '每个分区标题右边红色「* 还有 N 项未填」,点它滚到并闪一下第一项;没填的行值显示成红色占位。'], [2, '「发布者实名」从发布确认搬到票务页最后一个分区,字段与同意文案照 master 原样。'], [3, '实名字段与同意勾选照 master cy-field / cy-consent-check。'], [4, '底部「预览」:还有未填项时点它跳到第一项(不再弹文字版发布确认);都填完进剧情预览。']])],
    ['p4-ticket-ok', '票务 · 已填完', '④ 票务', 'cyp theme-topic-editor pg-fabu', ticket({ filled: true }), 'pages/publish/fabu/step3.wxml', notes([[4, '全部填完,「预览」进剧情预览页。']])],
    ['p4-story', '剧情预览(直接发布)', '⑤ 剧情预览', 'cyp theme-dark', story(), '新页面(取代 pages/publish/fabu 的 publishCheck 弹层)', notes([[1, '左上 ✕ 退出预览,回到配置。'], [2, '右上「完成」= 直接发布,没有任何发布确认弹层;master 的 publishCheck 文字版发布确认整块删除(上一轮的「改前」对照屏也撤掉)。发布结果的提示照后端回执原文。'], [3, '玩家看到的样子:封面、名字、模式 / 节点数 / 时长 / 档期。'], [4, '票价一行,点它回票务页。'], [5, '按章节把剧情和节点串起来,像玩的时候一样往下读;每一块右上角有笔,点哪块就跳回配置里对应的位置。'], [6, '通关勋章(P-3 第二版插画勋章)。']])],
  ];
  var host = document.getElementById('screens');
  host.innerHTML = SCREENS.map(function (s) {
    return '<section class="scr ' + s[3] + '" data-id="' + s[0] + '" data-title="' + s[1] + '" data-group="' + s[2] + '" data-src="' + s[5] + '">' + s[4] + s[6] + '</section>';
  }).join('');
  THEME_MEDALS.paintAll(host);
  window.ON_ENTER = function (id) {
    if (id !== 'p4-detail-story') return;
    var s = PROTO.cur(), sc = s.querySelector('.xb-scroll'), t = s.querySelector('.st-ch');
    setTimeout(function () { sc.scrollTop = t.offsetTop - 180; }, 0);
  };

  window.ACT = {
    flip: function () {
      var s = PROTO.cur(); var id = s.getAttribute('data-id');
      PROTO.show(id === 'p4-preview-flip' ? 'p4-preview' : (id === 'p4-preview' ? 'p4-preview-flip' : id));
    },
    jump: function (el) {
      var s = PROTO.cur(), t = s.querySelector('#' + el.getAttribute('data-target'));
      if (!t) return; t.scrollIntoView({ behavior: 'smooth', block: 'center' }); t.classList.remove('is-flash'); void t.offsetWidth; t.classList.add('is-flash');
    },
    firstMissing: function () { ACT.jump({ getAttribute: function () { return 'tkDates'; } }); PROTO.toast('还有 4 项未填'); },
    fill: function () { PROTO.show('p4-ticket-ok'); },
    goStory: function () { PROTO.show('p4-story', true); },
    publish: function () { PROTO.toast('已提交（提示照后端回执原文）'); }
  };
})();
