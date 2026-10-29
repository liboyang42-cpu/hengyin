// P-2 个人资料与头像(V-15 / V-21)。改前照 pages/gerenziliao/gerenziliao.wxml;改后为 Revolut 式资料页。
(function () {
  var U = UI, I = U.icon, E = U.esc;
  var C = 'assets/covers/';
  var OLD = C + 'old-movie.jpg', NEW = C + 'flavor-tour.jpg', BG = 'assets/img/mer1.jpg', BG2 = C + 'time-pollution.jpg';
  var INTRO = '喜欢在老城区慢慢走，听店主讲旧事。周末常在虹口和黄浦，欢迎约我一起走「海上方舟」。';

  function notes(list) { return '<ol class="notes">' + list.map(function (t) { return '<li data-n="' + t[0] + '">' + t[1] + '</li>'; }).join('') + '</ol>'; }

  // 改前:master 资料编辑页原样
  function before() {
    function row(k, v, empty) {
      return '<view class="shzlbox_li"><view class="shzlbox_li_left"><view class="shzlbox_li_left_name">' + k + '</view><view class="shzlbox_li_left_txt' + (empty ? ' is-empty' : '') + '">' + v + '</view></view>' +
        '<view class="shzlbox_li_right"><view class="pen-badge">' + I('edit', 24) + '</view></view></view>';
    }
    return '<view class="sv"><view class="theme-dark"><view class="profile-nav-spacer" style="height:87px"></view>' +
      '<view class="shzltop"><view class="shzltop_tu">' + U.avatar('', 170) + '<view class="dbj pen-badge">' + I('edit', 24) + '</view></view>' +
      '<text class="profile-avatar-review">头像已更新，图片安全复核中；未通过会被撤下</text></view>' +
      '<view class="shzlbox">' + row('昵称', '智能科技体验馆') + row('手机号', '152****0419') + row('城市签名', '未填写', 1) + row('探索作品', '未填写', 1) + row('联系二维码', '未填写', 1) + row('路线偏好', '未填写', 1) + '</view>' +
      '<view style="height:120px"></view></view></view>' + U.navBar({}) +
      '<view class="c-footer-bar" style="position:absolute;left:0;right:0;bottom:0;padding:12px 16px 34px;background:var(--cy-color-bg-page)">' + U.btn('保存') + '</view>';
  }

  // 改后
  function edit(o) {
    o = o || {};
    var light = !!o.light;
    var ava = o.avatar || OLD;
    var ring = o.uploading ? '<svg class="rv-ring" viewBox="0 0 112 112"><circle class="tr" cx="56" cy="56" r="54"/><circle class="pg" cx="56" cy="56" r="54" stroke-dasharray="339.3" stroke-dashoffset="' + (339.3 * (1 - (o.progress || .35))) + '" transform="rotate(-90 56 56)"/></svg>' : '';
    var intro = o.intro === undefined ? INTRO : o.intro;
    return '<view class="sv"><view class="rv">' +
      '<view class="rv-bg"' + U.mk(1) + '><img src="' + (o.bg || BG) + '"></view>' +
      '<view class="rv-bg-btn" data-sheet="pick-bg" data-detent="peek"' + U.mk(2) + '>' + I('image', 30) + '<text>更换背景</text></view>' +
      '<view class="rv-head"><view class="rv-ava" data-sheet="pick-ava" data-detent="peek"' + U.mk(3) + '><img src="' + ava + '">' + ring + '<view class="rv-ava__edit"' + U.mk(4) + '>' + I('edit', 26) + '</view></view>' +
      '<view class="rv-name">智能科技体验馆</view>' +
      (o.rejected ? '<view class="rv-review"' + U.mk(5) + '>头像未通过图片安全复核，正在撤下，请换一张</view>' : '') + '</view>' +
      '<view class="rv-group"' + U.mk(6) + '>' +
      '<view class="rv-row" data-toast="编辑昵称"><text class="rv-row__k">昵称</text><text class="rv-row__v">智能科技体验馆</text>' + I('chevron-right', 32, 'cv') + '</view>' +
      '<view class="rv-row rv-row--stack" data-sheet="intro" data-detent="half"' + U.mk(7) + '><text class="rv-row__k">个人介绍</text><view class="rv-row__sp"></view>' + I('chevron-right', 32, 'cv') +
      '<text class="rv-row__v' + (intro ? '' : ' is-empty') + '">' + E(intro || '介绍一下你自己') + '</text></view></view>' +
      '<view class="rv-group"><view class="rv-row" data-toast="更换手机号"><text class="rv-row__k">手机号</text><text class="rv-row__v">152****0419</text>' + I('chevron-right', 32, 'cv') + '</view></view>' +
      '<view class="rv-group">' +
      '<view class="rv-row" data-toast="编辑探索作品"><text class="rv-row__k">探索作品</text><view class="rv-thumbs"><img src="' + C + 'treasure-hunter.jpg"><img src="' + C + 'old-movie.jpg"><img src="' + C + 'anomaly-coordinate.jpg"></view>' + I('chevron-right', 32, 'cv') + '</view>' +
      '<view class="rv-row" data-toast="编辑联系二维码"><text class="rv-row__k">联系二维码</text><text class="rv-row__v is-empty">未填写</text>' + I('chevron-right', 32, 'cv') + '</view>' +
      '<view class="rv-row" data-toast="编辑路线偏好"><text class="rv-row__k">路线偏好</text><view class="rv-chips"><text class="rv-chip">城市探索</text><text class="rv-chip">怀旧人文</text></view>' + I('chevron-right', 32, 'cv') + '</view></view>' +
      '</view></view>' + U.navBar({ overlay: true }) +
      '<view class="c-footer-bar" style="position:absolute;left:0;right:0;bottom:0;padding:12px 16px 34px;background:linear-gradient(180deg,transparent,var(--cy-color-bg-page) 30%)"' + U.mk(8) + '>' + U.btn('保存', { act: 'save' }) + '</view>' +
      sheets(light, intro);
  }

  function sheets(light, intro) {
    var lt = light ? ' theme-merchant' : '';
    function pick(id, act, title) {
      return '<view class="ios-sheet' + lt + '" id="' + id + '" data-detent="peek"><view class="mask"></view><view class="panel" style="background:transparent"><view class="body"><view class="ios-acts">' +
        '<view class="grp"><view class="it" data-act="' + act + '">拍照</view><view class="it" data-act="' + act + '">从相册选择</view>' + (title === 'ava' ? '<view class="it" data-act="' + act + '">使用微信头像</view>' : '') + '</view>' +
        '<view class="grp cancel"><view class="it" data-close>取消</view></view></view></view></view></view>';
    }
    return pick('pick-ava', 'pickAvatar', 'ava') + pick('pick-bg', 'pickBg', 'bg') +
      '<view class="ios-sheet' + lt + '" id="intro" data-detent="half"><view class="mask"></view><view class="panel"><view class="grab"></view><view class="body">' +
      '<view class="rv-sheet-hd"><text class="l" data-close>取消</text><b>个人介绍</b><text class="r" data-act="introDone">完成</text></view>' +
      '<view class="rv-ta"' + U.mk(9) + '><textarea id="introTa" maxlength="140" placeholder="介绍一下你自己">' + E(intro || '') + '</textarea></view><view class="rv-count">' + (intro || '').length + '/140</view>' +
      '</view></view></view>';
  }

  // 主页 · 关于
  function about(o) {
    o = o || {};
    return '<view class="sv"><view class="pc-page pc-player pc-self">' + PROFILE.head({ tab: 'about', avatar: o.avatar, hero: o.hero, editGo: 'p2-edit' }) +
      '<view class="pc-about-head"><view class="pc-sec-tit pc-sec-tit--flush">个人介绍</view><view class="pc-about-edit" data-go="' + (o.introGo || 'p2-intro') + '"' + U.mk(1) + '>' + I('edit', 28) + '<text>编辑</text></view></view>' +
      '<view class="pc-card pc-about"' + U.mk(2) + '>' + E(o.intro || INTRO) + '</view>' +
      '<view class="pc-sec-tit">兴趣标签</view><view class="pc-tags"><text class="pc-tag">城市探索</text><text class="pc-tag">怀旧人文</text></view>' +
      '<view class="c-cell pc-solo-cell"><view class="cell"><view class="cell__body"><view class="cell__title">加入时间</view></view><view class="cell__value">2025.11.11</view></view></view>' +
      '<view style="height:140px"></view></view></view></view></view>' + U.tabbar(4);
  }

  var N_EDIT = [
    [1, '页顶铺我的主页背景图(和「我的 · 主页」同一张),第二轮按业主批注套上模糊蒙版:整张高斯模糊 + 半透明压暗(浅色是提亮),往下渐隐到页面底色,像 Revolut;头像和名字压在上面更清楚。'],
    [2, '背景右下角「更换背景」,点开选拍照 / 相册,选完立刻换上(现在只存在本机,见 README 决定 4)。'],
    [3, '头像居中放大到 104pt;点头像或下面的笔都打开选图半屏(iOS 样式:拍照 / 从相册选择 / 使用微信头像)。'],
    [4, '编辑笔改成头像正下方居中的实心圆(原来在右下角、偏心)。'],
    [6, '字段改成 Revolut 式分组列表:左边字段名、右边当前值、末尾 ›;每行右侧的圆形笔按钮全部去掉。'],
    [7, '「城市签名」改名为「个人介绍」(同一个字段 introduction,「关于」里显示的就是它),值最多显示 3 行;点开半屏编辑。'],
    [8, '底部「保存」保留(master 行为不变:资料一起提交)。']
  ];

  var SCREENS = [
    ['p2-before', '改前 · 资料编辑(master)', '资料编辑', 'cyp theme-dark pg-edit', before(), 'pages/gerenziliao/gerenziliao.wxml', ''],
    ['p2-edit', '资料编辑 · 新版', '资料编辑', 'cyp theme-dark pg-edit', edit(), 'pages/gerenziliao/gerenziliao.wxml', notes(N_EDIT.concat([[5, '审核中不再显示「头像已更新，图片安全复核中；未通过会被撤下」:新头像直接显示,只有未通过时才出提示(见「未通过」屏)。']]))],
    ['p2-pick', '点头像 · 选图半屏', '头像', 'cyp theme-dark pg-edit', edit(), 'pages/gerenziliao/gerenziliao.wxml(app.chooseImage)', notes([[3, '30% 高的 iOS 动作面板,三项 + 取消。']])],
    ['p2-uploading', '上传中 · 立即预览', '头像', 'cyp theme-dark pg-edit', edit({ avatar: NEW, uploading: true, progress: .4 }), 'pages/gerenziliao/gerenziliao.js uploadPic', notes([[3, 'V-15:选完立刻用本地图显示新头像,外圈细环表示上传进度;上传完成环消失。审核中也不清空、不回退旧头像。'], [6, '同时把新头像写进全局用户信息,主页 / 帖文 / 俱乐部成员列表下一次渲染就用新图(G-5 全站同步)。']])],
    ['p2-done', '上传完成', '头像', 'cyp theme-dark pg-edit', edit({ avatar: NEW }), 'pages/gerenziliao/gerenziliao.wxml', notes([[3, '新头像。没有「审核中」小字。']])],
    ['p2-rejected', '头像未通过审核', '头像', 'cyp theme-dark pg-edit', edit({ avatar: NEW, rejected: true }), 'utils/profile-review.js memberAvatarText(REJECTED)', notes([[5, '只在未通过时出一行红色提示,文字照 utils/profile-review.js 原样。']])],
    ['p2-bg', '更换背景 · 立即生效', '背景', 'cyp theme-dark pg-edit', edit({ avatar: NEW, bg: BG2 }), 'subpackageMember/components/cy/profile/index.js onChangeHeroBg', notes([[1, '选完立刻换上新背景(编辑页里同样是模糊蒙版);回到主页是这张原图,不模糊。'], [2, '入口从主页「⋯」菜单挪到这里(主页那条保留)。']])],
    ['p2-intro', '个人介绍 · 编辑半屏', '个人介绍', 'cyp theme-dark pg-edit', edit({ avatar: NEW }), 'pages/gerenziliao/gerenziliao.wxml(原「城市签名」cy-sheet)', notes([[9, '60% 半屏:左「取消」、右「完成」,输入框 140 字,右下角计数。占位「介绍一下你自己」。']])],
    ['p2-intro-empty', '个人介绍 · 未填写', '个人介绍', 'cyp theme-dark pg-edit', edit({ avatar: NEW, intro: '' }), 'pages/gerenziliao/gerenziliao.wxml', notes([[7, '没填时行里显示灰色占位「介绍一下你自己」,不写「未填写」。']])],
    ['p2-about', '主页 · 关于(有介绍)', '个人介绍', 'cyp theme-dark pg-profile', about({ avatar: NEW, introGo: 'p2-intro' }), 'subpackageMember/components/cy/profile/index.wxml「关于」', notes([[1, 'V-21:「编辑」直接进资料编辑页并打开个人介绍半屏(goEditIntro 带 focus=intro)。'], [2, '个人介绍卡显示全文。']])],
    ['p2-home-sync', '主页 · 新头像与背景已同步', '背景', 'cyp theme-dark pg-profile', about({ avatar: NEW, hero: BG2 }), 'subpackageMember/components/cy/profile/index.wxml', notes([[1, '回到主页,头像和背景都是刚换的那张,不用重进小程序。']])],
    ['p2-edit-lt', '资料编辑 · 浅色(商家)', '资料编辑', 'cyp theme-merchant pg-edit', edit({ light: true }), 'pages/gerenziliao/gerenziliao.wxml + theme-merchant', notes(N_EDIT)]
  ];
  var host = document.getElementById('screens');
  host.innerHTML = SCREENS.map(function (s) {
    var open = { 'p2-pick': 'pick-ava', 'p2-intro': 'intro' }[s[0]];
    return '<section class="scr ' + s[3] + '" data-id="' + s[0] + '" data-title="' + s[1] + '" data-group="' + s[2] + '" data-src="' + s[5] + '"' + (open ? ' data-open="' + open + '"' : '') + '>' + s[4] + s[6] + '</section>';
  }).join('');

  window.ACT = {
    pickAvatar: function (el) {
      PROTO.closeSheets(el);
      var s = PROTO.cur(); var box = s.querySelector('.rv-ava');
      box.querySelector('img').src = NEW;
      var p = 0;
      box.insertAdjacentHTML('beforeend', '<svg class="rv-ring" viewBox="0 0 112 112"><circle class="tr" cx="56" cy="56" r="54"/><circle class="pg" cx="56" cy="56" r="54" stroke-dasharray="339.3" stroke-dashoffset="339.3" transform="rotate(-90 56 56)"/></svg>');
      var c = box.querySelector('.pg');
      var t = setInterval(function () { p += .25; c.setAttribute('stroke-dashoffset', 339.3 * (1 - Math.min(1, p))); if (p >= 1) { clearInterval(t); setTimeout(function () { var r = box.querySelector('.rv-ring'); if (r) r.remove(); }, 400); } }, 300);
    },
    pickBg: function (el) { PROTO.closeSheets(el); PROTO.cur().querySelector('.rv-bg img').src = BG2; PROTO.toast('背景已更新'); },
    introDone: function (el) {
      var s = PROTO.cur(); var v = s.querySelector('#introTa').value.trim();
      var t = s.querySelector('.rv-row--stack .rv-row__v'); t.textContent = v || '介绍一下你自己'; t.classList.toggle('is-empty', !v);
      PROTO.closeSheets(el);
    },
    save: function () { PROTO.toast('资料已保存'); }
  };
  document.addEventListener('input', function (e) { if (e.target.id === 'introTa') { var c = e.target.closest('.body').querySelector('.rv-count'); c.textContent = e.target.value.length + '/140'; } });
})();
