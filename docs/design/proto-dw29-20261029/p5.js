// P-5 商家版两页(10-27 文档 M-03 筛选客户、M-09 裁剪页),业主 10-29:「要」「还是没改」。商家视角浅色。
(function () {
  var U = UI, I = U.icon, E = U.esc;
  var C = 'assets/covers/';
  function notes(list) { return '<ol class="notes">' + list.map(function (t) { return '<li data-n="' + t[0] + '">' + t[1] + '</li>'; }).join('') + '</ol>'; }

  // ===== M-03 客户名单(pages/merchant/customer/index.wxml)=====
  var ROWS = [['周小满', 'repeat', '回头客', '10月26日 核销「风味巡游」', '138****2210'], ['陈一鸣', 'pending', '待核销', '10月25日 报名「异常事务局」', '139****8812'], ['林夕', 'repeat', '回头客', '10月21日 核销「风味巡游」', ''],
    ['王子豪', 'new', '新客', '10月20日 报名「海上方舟」', '186****0405'], ['赵清', 'repeat', '回头客', '10月18日 核销「风味巡游」', '135****7731'], ['孙蔓', 'new', '新客', '10月12日 报名「风味巡游」', '']];
  function list(o) {
    o = o || {};
    var rows = (o.filtered ? ROWS.filter(function (r) { return r[1] === 'repeat'; }) : ROWS).map(function (r, i) {
      return '<view class="cu-row"><view class="cu-avatar">' + U.avatar(C + ['old-movie', 'flavor-tour', 'treasure-hunter', 'midlife-day', 'heat-escape', 'time-pollution'][i % 6] + '.jpg', 88) + '</view><view class="cu-body"><view class="cu-line1"><text class="cu-name">' + r[0] + '</text>' +
        '<text class="p5-badge' + (r[1] === 'pending' ? ' is-warn' : '') + '">' + r[2] + '</text></view><text class="cu-action">' + r[3] + '</text>' +
        '<view class="cu-meta">' + (r[4] ? '<view class="cu-phone-row"><text class="cu-phone">' + r[4] + '</text></view>' : '<text class="cu-nophone">客户未授权电话</text>') + '</view></view>' + I('arrow-right', 24, 'cu-arrow') + '</view>';
    }).join('');
    return '<view class="sv"><view class="cu-page theme-merchant" style="padding-top:87px">' +
      '<view class="cu-hero"><view class="cu-hero-title">客户</view><text class="cu-hero-sub">' + (o.filtered ? '3 位 · 回头客 · 主题 · 咖啡常客' : '共 128 位客户') + '</text></view>' +
      '<view class="cu-segs"><view class="cu-seg' + (o.filtered ? ' p5-seg-on' : '') + '" data-sheet="' + (o.sheet || 'cu-filter') + '" data-detent="full"' + (o.filtered ? U.mk(1) : '') + '><text class="cu-seg-lbl">' + (o.filtered ? '筛选 · 3' : '筛选') + '</text></view><view class="cu-seg" data-toast="客户管理"><text class="cu-seg-lbl">管理</text></view></view>' +
      '<view class="cu-search"><view class="p5-search">' + I('search', 32) + '<text>搜索</text></view></view>' +
      '<view class="cu-list" style="margin-top:16px">' + rows + '</view>' +
      '<view class="cu-foot"><text class="cu-foot-txt">共 ' + (o.filtered ? 3 : 128) + ' 位客户' + (o.filtered ? ' · 已全部显示' : '') + '</text></view><view style="height:60px"></view></view></view>' + U.navBar({});
  }

  // 改前:master 的筛选半屏(cy-sheet fixed=full)原样
  function before() {
    function chip(t, on) { return '<view class="cu-filter-chip"><view class="cu-option-check">' + (on ? I('check', 28) : '') + '</view><text>' + t + '</text></view>'; }
    return list({ sheet: 'cu-old' }) +
      '<view class="ios-sheet theme-merchant pg-cu" id="cu-old" data-detent="full"><view class="mask"></view><view class="panel" style="height:86%"><view class="grab"></view><view class="body" style="padding:0 20px">' +
      '<view class="p5-old-ttl">筛选客户</view><view class="cu-filter">' +
      '<text class="cu-filter-title">客户类型</text><view class="cu-filter-options">' + chip('全部', 1) + chip('回头客') + chip('新客') + chip('有备注') + '</view>' +
      '<text class="cu-filter-title">客户来源</text><view class="cu-filter-options">' + chip('全部', 1) + chip('主题') + chip('活动') + '</view>' +
      '<text class="cu-filter-title">来源时间</text><view class="cu-date-row"><view class="cu-date-field"><view class="cu-date">开始日期</view></view><text class="cu-date-sep">至</text><view class="cu-date-field"><view class="cu-date">结束日期</view></view></view>' +
      '<text class="cu-filter-title">客户标签</text><view class="cu-tag-filter">' + chip('全部标签', 1) + chip('咖啡常客') + chip('test') + '</view>' +
      '<view class="cu-save-segment"><input class="cu-segment-input" placeholder="命名当前筛选"><view class="cu-save-button"><view class="c-btn"><view class="btn btn--secondary">保存分群</view></view></view></view>' +
      '<view class="cu-saved"><text class="cu-saved-item">测试分群1</text><text class="cu-saved-item">zz测试</text><text class="cu-saved-item">周末回头客</text></view></view></view>' +
      '<view class="cu-filter-footer p5-old-foot"><view class="c-btn cu-filter-reset"><view class="btn btn--secondary">清空筛选</view></view><view class="c-btn"><view class="btn btn--primary">查看结果</view></view></view></view></view>';
  }

  // 新版筛选半屏
  var GROUPS = [
    ['type', '客户类型', [['all', '全部'], ['repeat', '回头客'], ['new', '新客'], ['noted', '有备注']]],
    ['src', '客户来源', [['all', '全部'], ['topic', '主题'], ['event', '活动']]],
    ['tag', '客户标签', [['all', '全部'], ['coffee', '咖啡常客'], ['weekend', '周末来'], ['family', '亲子'], ['vip', '老会员']]]
  ];
  function filterSheet(id, sel, o) {
    o = o || {};
    sel = sel || {};
    var n = Object.keys(sel).filter(function (k) { return sel[k] && sel[k] !== 'all'; }).length;
    function chips(g) {
      return '<view class="fx-chips">' + g[2].map(function (c) {
        var on = (sel[g[0]] || 'all') === c[0];
        return '<view class="fx-chip' + (on ? ' is-on' : '') + '" data-act="pick" data-g="' + g[0] + '" data-k="' + c[0] + '"><view class="fx-ck">' + I('check', 26) + '</view><text>' + c[1] + '</text></view>';
      }).join('') + '</view>';
    }
    return '<view class="ios-sheet theme-merchant" id="' + id + '" data-detent="full"><view class="mask"></view><view class="panel"><view class="grab"></view>' +
      '<view class="fx-hd"><text class="fx-hd__l" data-close>取消</text><b>筛选客户</b><text class="fx-hd__r' + (n ? '' : ' is-off') + '" data-act="reset"' + U.mk(5) + '>重置</text></view><view class="body">' +
      '<view class="fx-sec"' + U.mk(1) + '><text class="fx-t">我的分群</text><view class="fx-chips fx-saved">' + ['周末回头客', '十月新客', '咖啡常客 · 回头客'].map(function (t, i) { return '<view class="fx-seg' + (o.applied === i ? ' is-on' : '') + '" data-toast="已套用「' + t + '」">' + t + '</view>'; }).join('') + '</view></view>' +
      GROUPS.slice(0, 2).map(function (g, i) { return '<view class="fx-sec"' + (i ? '' : U.mk(2)) + '><text class="fx-t">' + g[1] + '</text>' + chips(g) + '</view>'; }).join('') +
      '<view class="fx-sec"><text class="fx-t">来源时间</text><view class="fx-dates"><view class="fx-date' + (sel.from ? ' is-set' : '') + '">' + I('calendar', 28) + '<text>' + (sel.from || '开始日期') + '</text></view><text class="fx-to">至</text><view class="fx-date' + (sel.to ? ' is-set' : '') + '">' + I('calendar', 28) + '<text>' + (sel.to || '结束日期') + '</text></view></view></view>' +
      '<view class="fx-sec"' + U.mk(3) + '><text class="fx-t">' + GROUPS[2][1] + '</text>' + chips(GROUPS[2]) + '</view><view style="height:24px"></view></view>' +
      '<view class="fx-bar"' + U.mk(4) + '><view class="fx-save' + (n ? '' : ' is-off') + '" data-act="saveSeg">' + I('plus', 28) + '<text>存为分群</text></view><view class="fx-go" data-act="apply"><text>查看 ' + (n ? 3 : 128) + ' 位客户</text></view></view>' +
      '</view></view>';
  }
  function saveAlert() {
    return '<view class="p5-alert-mask"><view class="p5-alert"' + U.mk(6) + '><view class="p5-alert__t">存为分群</view><view class="p5-alert__in"><input value="回头客 · 主题 · 咖啡常客" maxlength="30"></view>' +
      '<view class="p5-alert__btns"><text data-toast="已取消">取消</text><text class="b" data-go="p5-cu-applied">保存</text></view></view></view>';
  }
  var SEL = { type: 'repeat', src: 'topic', tag: 'coffee' };

  // ===== M-09 裁剪页(pages/crop)=====
  function cropBefore() {
    return '<view class="crop theme-dark" style="height:100%;position:relative">' +
      '<view class="crop__nav" style="padding-top:47px"><view class="crop__nav-inner" style="height:44px"><text class="crop__title">裁剪</text></view></view>' +
      '<view class="crop__stage"><view class="crop__frame crop__frame--round" style="width:390px;height:390px"><img class="crop__img" src="' + C + 'flavor-tour.jpg" style="width:390px;height:520px;transform:translate3d(-50%,-50%,0)"><view class="crop__round-mask"></view></view></view>' +
      '<view class="crop__bar" style="height:56px;padding-bottom:34px"><view class="crop__action"><text>取消</text></view><view class="crop__action crop__action--confirm"><text>选取</text></view></view></view>';
  }
  function crop(o) {
    o = o || {};
    var round = o.kind === 'logo';
    var src = round ? C + 'treasure-hunter.jpg' : C + 'flavor-tour.jpg';
    return '<view class="cx' + (o.light ? ' cx--light theme-merchant' : ' theme-dark') + '" data-ratio="' + (round ? 1 : 5 / 3) + '">' +
      '<view class="cx-top"><text class="cx-title">' + (round ? '店铺 Logo' : '店铺封面') + '</text></view>' +
      '<view class="cx-stage"' + U.mk(1) + '><img class="cx-img" src="' + src + '">' +
      '<view class="cx-frame' + (round ? ' is-round' : '') + '"' + U.mk(2) + '>' + (round ? '' : '<i class="g1"></i><i class="g2"></i><i class="g3"></i><i class="g4"></i>') + '</view></view>' +
      '<view class="cx-bar"' + U.mk(3) + '><view class="cx-btn" data-toast="取消">取消</view><view class="cx-btn cx-btn--reset" data-toast="已还原">还原</view><view class="cx-btn cx-btn--ok" data-toast="已选取">选取</view></view>' +
      '</view>';
  }
  // 布局:整张图按「放得下」缩进舞台(左右各 16pt),取景框取图里能放下的最大那一块(按比例、居中、最宽 342pt)
  function layoutCrop(scr) {
    var cx = scr.querySelector('.cx'); if (!cx) return;
    var st = cx.querySelector('.cx-stage'), img = cx.querySelector('.cx-img'), fr = cx.querySelector('.cx-frame'), r = +cx.getAttribute('data-ratio');
    function go() {
      var bw = st.clientWidth - 32, bh = st.clientHeight - 48, k = Math.min(bw / img.naturalWidth, bh / img.naturalHeight);
      var iw = img.naturalWidth * k, ih = img.naturalHeight * k;
      img.style.width = iw + 'px'; img.style.height = ih + 'px';
      var fw = Math.min(342, iw, ih * r), fh = fw / r;
      fr.style.width = fw + 'px'; fr.style.height = fh + 'px';
      PROTO.placeMarks();
    }
    if (img.complete && img.naturalWidth) go(); else img.onload = go;
  }
  var N_CROP = [[1, '整张图都在屏幕里(按放得下缩进舞台):取景框外的部分压暗(浅色版提亮),不再被裁掉,看得到自己在裁哪一块。手势照旧:单指拖、双指缩放,不放提示文字。'],
    [2, '取景框按用途定比例、在舞台正中,取图里能放下的最大一块(最宽 342pt,左右至少留 24pt),不再贴边:Logo = 圆形 1:1,封面 = 5:3(UploadImagePolicy 补 5:3,见 M-10);封面框里有三分线。'],
    [3, '底栏按钮变小:左「取消」、中间「还原」、右「选取」32pt 高的小胶囊;不再是两块大按钮。标题放在胶囊同一行,不会被裁。']];

  var SCREENS = [
    ['p5-cu-before', '改前 · 筛选客户(master)', 'M-03 筛选客户', 'cyp theme-merchant pg-cu', before(), 'pages/merchant/customer/index.wxml(cu-filter-sheet)', ''],
    ['p5-cu-filter', '筛选客户 · 新版', 'M-03 筛选客户', 'cyp theme-merchant pg-cu', list() + filterSheet('cu-filter'), 'pages/merchant/customer/index.wxml(cu-filter-sheet)', notes([
      [1, '「我的分群」放最上面,一排可点的分群(点了直接套用);只列商家自己存的,测试分群按 E-3 清掉,不在界面上出现。'],
      [2, '每组单选 chip,每个 chip 左边固定留一个勾的位置:选中时是实心底 + 勾,没选是空着的圆圈 —— 原来没选时那块是白的,看着像多了空白。'],
      [3, '标签也是换行排的单选 chip,不再横滑。'],
      [4, '底栏两件事:左「存为分群」(有筛选条件才可点)、右「查看 N 位客户」(N 跟着条件实时变);原来夹在中间、会折行的「保存分群」输入框去掉。'],
      [5, 'iOS 半屏(80%):左「取消」、中间标题、右「重置」(有条件时才亮)。']])],
    ['p5-cu-picked', '筛选客户 · 选了条件', 'M-03 筛选客户', 'cyp theme-merchant pg-cu', list() + filterSheet('cu-filter', SEL), 'pages/merchant/customer/index.js onSegmentTap / onSourceTap / onTagFilterTap', notes([[2, '选了「回头客」「主题」「咖啡常客」:三个 chip 变实心带勾。'], [4, '底栏「查看 3 位客户」,「存为分群」可点。'], [5, '「重置」亮起。']])],
    ['p5-cu-save', '存为分群 · 命名', 'M-03 筛选客户', 'cyp theme-merchant pg-cu', list() + filterSheet('cu-filter', SEL) + saveAlert(), 'pages/merchant/customer/index.js saveCurrentSegment', notes([[6, '点「存为分群」弹一个 iOS 输入框:名字默认用当前条件拼好,改了就存;存完这个分群出现在「我的分群」最前面。']])],
    ['p5-cu-applied', '名单 · 已筛选', 'M-03 筛选客户', 'cyp theme-merchant pg-cu', list({ filtered: 1, sheet: 'cu-filter2' }) + filterSheet('cu-filter2', SEL, { applied: 2 }), 'pages/merchant/customer/index.wxml', notes([[1, '回到名单:「筛选」按钮显示条件数并变实心,标题下一行写清当前条件和人数。']])],
    ['p5-crop-before', '改前 · 裁剪(master)', 'M-09 裁剪', 'cyp theme-dark pg-crop', cropBefore(), 'pages/crop/index.wxml', ''],
    ['p5-crop-logo', '裁剪 · Logo(沉浸式)', 'M-09 裁剪', 'cyp theme-dark pg-crop', crop({ kind: 'logo' }), 'pages/crop/index.wxml(cropShape=circle,1:1)', notes(N_CROP)],
    ['p5-crop-cover', '裁剪 · 封面 5:3(沉浸式)', 'M-09 裁剪', 'cyp theme-dark pg-crop', crop({ kind: 'cover' }), 'pages/crop/index.wxml(cropScale=5:3)', notes(N_CROP)],
    ['p5-crop-logo-lt', '裁剪 · Logo(浅色)', 'M-09 裁剪', 'cyp theme-merchant pg-crop', crop({ kind: 'logo', light: 1 }), 'pages/crop/index.wxml + theme-merchant', notes([[1, '浅色版:框外提亮成半透明白,其余同沉浸式。业主二选一。'], [2, '同沉浸式。'], [3, '同沉浸式,按钮换深色。']])],
    ['p5-crop-cover-lt', '裁剪 · 封面 5:3(浅色)', 'M-09 裁剪', 'cyp theme-merchant pg-crop', crop({ kind: 'cover', light: 1 }), 'pages/crop/index.wxml + theme-merchant', notes([[1, '同上。'], [2, '同上。'], [3, '同上。']])]
  ];
  var host = document.getElementById('screens');
  host.innerHTML = SCREENS.map(function (s) {
    var open = { 'p5-cu-before': 'cu-old', 'p5-cu-filter': 'cu-filter', 'p5-cu-picked': 'cu-filter', 'p5-cu-save': 'cu-filter' }[s[0]];
    return '<section class="scr ' + s[3] + '" data-id="' + s[0] + '" data-title="' + s[1] + '" data-group="' + s[2] + '" data-src="' + s[5] + '"' + (open ? ' data-open="' + open + '"' : '') + '>' + s[4] + s[6] + '</section>';
  }).join('');

  window.ON_ENTER = function (id) { if (/^p5-crop-(logo|cover)/.test(id)) layoutCrop(PROTO.cur()); };
  window.ACT = {
    pick: function (el) {
      var sh = el.closest('.ios-sheet'), g = el.getAttribute('data-g');
      [].forEach.call(sh.querySelectorAll('.fx-chip[data-g="' + g + '"]'), function (c) { c.classList.toggle('is-on', c === el); });
      var n = [].filter.call(sh.querySelectorAll('.fx-chip.is-on'), function (c) { return c.getAttribute('data-k') !== 'all'; }).length;
      sh.querySelector('.fx-go text').textContent = '查看 ' + (n ? [128, 41, 12, 3][n] : 128) + ' 位客户';
      sh.querySelector('.fx-save').classList.toggle('is-off', !n); sh.querySelector('.fx-hd__r').classList.toggle('is-off', !n);
    },
    reset: function (el) {
      var sh = el.closest('.ios-sheet');
      [].forEach.call(sh.querySelectorAll('.fx-chip'), function (c) { c.classList.toggle('is-on', c.getAttribute('data-k') === 'all'); });
      sh.querySelector('.fx-go text').textContent = '查看 128 位客户'; sh.querySelector('.fx-save').classList.add('is-off'); el.classList.add('is-off');
    },
    saveSeg: function (el) { if (!el.classList.contains('is-off')) PROTO.show('p5-cu-save', true); },
    apply: function () { PROTO.show('p5-cu-applied', true); }
  };
})();
