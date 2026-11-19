// 1119 复核修复(mini-a 道):PG-* 逐项的源码合同。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');
const exists = (rel) => fs.existsSync(path.join(root, rel));
const noComments = (s) => s.replace(/<!--[\s\S]*?-->/g, '');

test('PG-001:游客读俱乐部帖文被拒时只显示社区帖,两路都没有时落登录入口', () => {
  const js = read('pages/talent/list/index.js');
  assert.match(js, /if \(communityOn\) resolve\(\{ rows: \[\], hasMore: false, cursor: beforeId \}\);\n\s*else reject\(\{ loginRequired: true \}\);/);
  assert.match(js, /feedLoginRequired: !!\(res && res\.loginRequired\)/);
  assert.match(js, /retryFeed\(\) \{\n\s*if \(!this\.data\.feedLoginRequired\) return this\.loadFeed\(\);/);
});

test('PG-002/PG-004:我的队伍重试不把点击事件当回调;登录门只包「我的」页签', () => {
  const wxml = noComments(read('subpackageRoam/my-teams/index.wxml'));
  const js = read('subpackageRoam/my-teams/index.js');
  assert.doesNotMatch(wxml, /bindtap="reload"/);
  assert.match(wxml, /bindtap="retryReload"/);
  assert.match(js, /retryReload\(\) \{ this\.reload\(\); \}/);
  assert.doesNotMatch(js, /if \(done\) done\(\)/);
  assert.doesNotMatch(wxml, /<cy-access-gate/);
  assert.match(wxml, /wx:if="\{\{tab === 'mine' && guest\}\}"/);
  assert.match(js, /if \(!app\.ensureLoginFor\('team'/);
});

test('PG-003:URL 参数解码走 utils/safe-decode,坏 % 序列回退原值', () => {
  const { safeDecode } = require('../../utils/safe-decode.js');
  assert.equal(safeDecode('%E5%9F%8E'), '城');
  assert.equal(safeDecode('%E5%9F'), '%E5%9F');
  assert.equal(safeDecode(undefined), '');
  for (const f of ['subpackageRoam/citystamp/index.js', 'subpackageB/pages/im/chat/index.js', 'pages/play/poi-challenge/index.js',
    'subpackageMember/utils/public-catalog.js', 'subpackageMember/coupon-qr/index.js', 'subpackageMember/market/gift/index.js']) {
    assert.doesNotMatch(read(f), /decodeURIComponent\(/, f);
    assert.match(read(f), /require\('[./]+utils\/safe-decode\.js'\)/, f);
  }
  assert.doesNotMatch(read('pages/publish/fabu/index.js'), /decodeURIComponent\(options\.templateName\)/);
});

test('PG-005/PG-091:据点核销码页先判登录,并改为 cy-scene-qr-citynode 薄壳', () => {
  const wxml = noComments(read('subpackageRoam/citynode-code/index.wxml'));
  const js = read('subpackageRoam/citynode-code/index.js');
  const json = JSON.parse(read('subpackageRoam/citynode-code/index.json'));
  assert.match(wxml, /<cy-access-gate kind="login" \/>/);
  assert.match(wxml, /<cy-scene-qr-citynode poi-id="\{\{poiId\}\}" name="\{\{name\}\}" bind:close="onClose" \/>/);
  assert.doesNotMatch(wxml, /<cy-qr-voucher/);
  assert.doesNotMatch(js, /sendRequest|setInterval/);
  assert.equal(json.usingComponents['cy-scene-qr-citynode'], '/components/cy/scene-qr-citynode/index');
  assert.equal(json.usingComponents['cy-access-gate'], '/components/cy/access-gate/index');
});

test('PG-006:漫游地图据点评价带并复用 request_id,成功后清掉', () => {
  const js = read('subpackageRoam/nearby/index.js');
  assert.match(js, /reply_id: 0, request_id: this\._reviewReq\.id \}/);
  assert.match(js, /if \(!this\._reviewReq \|\| this\._reviewReq\.poiId !== String\(c\.id\)\)/);
  assert.match(js, /this\._reviewReq = null;\n\s*this\.setData\(\{ 'poiCard\.myText': ''/);
});

test('PG-007/PG-008/PG-044:地图搜索只留「最近」;筛选无关键词给提示;日期面板按身份切深浅', () => {
  const map = noComments(read('pages/searchmap/index.wxml'));
  assert.doesNotMatch(map, /最热/);
  assert.match(read('pages/searchmap/index.js'), /sortType: '1' \/\/ PG-007/);
  const js = read('pages/search2/index.js');
  const filter = js.slice(js.indexOf('  searchFilter() {'), js.indexOf('  // 选择日期'));
  assert.match(filter, /\n\s*this\.navigateToSearchList\(\);\n\s*\},/);
  assert.match(js, /toast\('请输入关键词或选择类别'\)/);
  for (const p of ['pages/search2/index', 'pages/searchmap/index']) {
    const wxml = read(p + '.wxml');
    assert.match(wxml, /<cy-date-sheet [^>]*dark="\{\{!merchantView\}\}"/, p);
    assert.doesNotMatch(wxml, /indicator-style="[^"]*rgba/, p);
    assert.match(read(p + '.wxss'), /\.date-picker-ind \{[^}]*var\(--cy-color-border-strong\)/, p);
  }
});

test('PG-009/PG-010/PG-012/PG-014:组队加入与主题详情返回、邀请人、作者兜底、加入码失败回输入框', () => {
  const joinWxml = read('pages/team/join/index.wxml');
  const joinJs = read('pages/team/join/index.js');
  assert.match(joinWxml, /<cy-nav-bar custom-back bind:back="goBack" \/>/);
  assert.match(joinJs, /goBack\(\) \{\n\s*wx\.navigateBack\(\{ delta: 1, fail\(\) \{ wx\.switchTab\(\{ url: '\/pages\/index\/index' \}\); \} \}\);/);
  assert.match(read('pages/templatedetail/topic-detail/index.wxml'), /<cy-nav-bar overlay pill="\{\{!!cover\}\}" custom-back bind:back="goBack" \/>/);
  assert.match(read('pages/team/detail/index.js'), /const inviter = lead \? String\(lead\.memberName \|\| ''\)\.trim\(\) : '';/);
  const topic = read('pages/templatedetail/topic-detail/index.js');
  assert.doesNotMatch(topic, /info\.author \|\| '城瘾'/);
  assert.match(topic, /author: info\.author \|\| '创作者'/);
  assert.match(joinJs, /this\._byCode = true;/);
  assert.match(joinJs, /if \(that\._byCode && !hasTeam\) \{ that\._backToCodeEntry\(/);
  assert.match(joinJs, /_backToCodeEntry\(message\) \{\n\s*this\.inviteCode = '';\n\s*this\.codeEntry = true;/);
});

test('PG-011:关于页删除「我的二维码」块与 explainQrPending', () => {
  assert.doesNotMatch(read('pages/shezhi/about/index.wxml'), /我的二维码|cy-qr-voucher/);
  assert.doesNotMatch(read('pages/shezhi/about/index.js'), /explainQrPending/);
});

test('PG-013/PG-030:参与人编辑与四个商家页挂登录守卫', () => {
  for (const p of ['pages/addressinfo/addressinfo', 'pages/merchant/verify-records/index', 'pages/merchant/bundle-reservations/index',
    'pages/merchant/crm-campaigns/index', 'pages/merchant/payout-account/index']) {
    assert.match(read(p + '.wxml'), /<cy-access-gate kind="login" \/>/, p);
    assert.equal(JSON.parse(read(p + '.json')).usingComponents['cy-access-gate'], '/components/cy/access-gate/index', p);
  }
});

test('PG-015/PG-045:工具页游客文案「请先登录」且不给重试;探索目标只挂一个 modal-host', () => {
  const rt = read('subpackageTools/lib/runtime.js');
  assert.doesNotMatch(rt, /请通过现有登录流程/);
  assert.match(rt, /this\.setData\(\{ error: '请先登录', guest: true \}\)/);
  assert.match(read('subpackageTools/exploration-goals/index.wxml'), /class="tu-retry" wx:if="\{\{!guest\}\}" bindtap="loadList"/);
  assert.match(read('subpackageTools/anniversary/index.wxml'), /class="tu-retry" wx:if="\{\{!guest\}\}" bindtap="loadMemories"/);
  const exp = read('subpackageTools/exploration-export/index.wxml');
  assert.match(exp, /wx:if="\{\{!guest && prepared\}\}" bindtap="readNext"/);
  assert.match(exp, /wx:elif="\{\{!guest\}\}" bindtap="prepare"/);
  assert.equal((read('subpackageTools/exploration-goals/index.wxml').match(/<cy-modal-host /g) || []).length, 1);
});

test('PG-016:主办控制台签到出勤移入主题根节点与滚动区,只在 ready 渲染', () => {
  const wxml = read('pages/activity/official-organizer/index.wxml');
  const scroll = wxml.slice(wxml.indexOf('<scroll-view wx:else'), wxml.indexOf('</scroll-view>'));
  assert.match(scroll, /<cy-event-attendance wx:if="\{\{phase === 'ready'\}\}"/);
  assert.equal((wxml.match(/<cy-event-attendance/g) || []).length, 1);
});

test('PG-017:站点商家页缺节点时从游玩页重读后再找;链接直达只给返回', () => {
  const vm = require('node:vm');
  const file = path.join(root, 'pages/play/merchant/index.js');
  let def; const timers = [];
  const prev = { data: { nodes: [], loading: false }, reloadPlay() { this.data.loading = true; this.data.nodes = [{ nodeId: 5, name: 'A' }]; } };
  const wx = { getWindowInfo: () => ({ statusBarHeight: 20 }), getMenuButtonBoundingClientRect: () => ({}), navigateBack() {}, switchTab() {} };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Page: (d) => { def = d; }, getApp: () => ({ globalData: {}, getUserID: () => '1' }), wx, console,
    getCurrentPages: () => [prev, {}], setTimeout: (fn) => { timers.push(fn); return timers.length; }, clearTimeout() {},
    require: require('node:module').createRequire(file),
  });
  const page = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data)), setData(p, cb) { Object.assign(this.data, p); if (cb) cb(); } });
  page.onLoad({ nodeId: '5' });
  assert.equal(page.data.notFound, true);
  assert.equal(page.data.canReloadNode, true);
  page.onReloadNode();
  prev.data.loading = false;
  timers.shift()();
  assert.equal(page.data.notFound, false);
  assert.equal(page.data.node.name, 'A');
});

test('PG-018/PG-019:合作邀请卡只留「去处理」;fabu 只定义一份 toggleCategorySelect', () => {
  const wxml = noComments(read('subpackageB/pages/im/list/index.wxml'));
  assert.doesNotMatch(wxml, />回复<|>拒绝<|>接受合作</);
  assert.match(wxml, /catchtap="openInvite"[^>]*>去处理</);
  assert.equal((read('pages/publish/fabu/index.js').match(/^\s+toggleCategorySelect[:(]/gm) || []).length, 1);
});

test('PG-020/PG-021:topicadd 与 simple 页删除并摘出 app.json 与主题 lint 白名单', () => {
  assert.equal(exists('pages/publish/topicadd'), false);
  assert.equal(exists('pages/publish/simple'), false);
  const app = read('app.json');
  assert.doesNotMatch(app, /topicadd|"simple\/index"/);
  assert.doesNotMatch(read('scripts/ui-theme-unit-lint.js'), /'pages\/publish\/topicadd/);
});

test('PG-022/PG-023:俱乐部主题详情缺 clubId 不跳结算;俱乐部详情不再引 mockData 演示分支', () => {
  assert.match(read('pages/club/topic-detail/index.js'), /goSettlement\(\) \{\n\s*if \(!this\._clubId\) return;/);
  const detail = read('pages/club/detail/index.js');
  assert.doesNotMatch(detail, /mockData|isDemoClubId/);
});

test('PG-024/PG-059/PG-081:商家报名缺参落 missing、门店标题随门店卡显隐、校验文案与字段名一致', () => {
  const js = read('pages/topic/merchantapply/index.js');
  assert.match(js, /if \(!registrationId && !\(topicId > 0 && nodeId > 0\)\) \{\n\s*this\.setData\(\{ loadState: 'missing'/);
  assert.match(js, /'请输入承接说明'/);
  assert.match(js, /'请上传承接图片'/);
  const wxml = read('pages/topic/merchantapply/index.wxml');
  assert.match(wxml, /wx:elif="\{\{loadState === 'missing'\}\}">\s*<cy-empty kind="missing-param"/);
  assert.match(wxml, /<block wx:if="\{\{shop\.name \|\| shop\.address \|\| shop\.businessTime\}\}">\s*<view class="ma-shop-note">/);
});

test('PG-026/PG-027:商家主题页参数先判 >0;招募广场浏览分支游客直接读公开详情', () => {
  const js = read('pages/topic/merchantinfo/merchantinfo.js');
  assert.match(js, /const optTopicId = parseInt\(options\.topicId\) \|\| 0;/);
  assert.match(js, /else if \(options\.id && !options\.topicId && optId > 0\) \{/);
  assert.match(js, /if \(!projectMemberId\(\) && this\.data\.fromMerchantJoin\) \{[\s\S]{0,200}this\._guestBrowse = true;[\s\S]{0,80}this\.loadBrowseData\(false\);/);
  assert.match(js, /!!page\._guestBrowse && key === 'loadBrowseData'/);
  assert.match(js, /Number\(d\.productType\) === 2 && !that\._guestBrowse/);
});

test('PG-028/PG-055:定价页深色同步状态栏前景;定价页与玩法编辑页去 vh 常量', () => {
  assert.match(read('pages/topic/pricing/index.js'), /if \(options\.club === '1' && wx\.setNavigationBarColor\) wx\.setNavigationBarColor\(\{ frontColor: '#ffffff'/);
  const pricing = read('pages/topic/pricing/index.wxss');
  assert.doesNotMatch(pricing, /calc\(100vh|height: 100vh/);
  assert.match(pricing, /\.pg-scroll \{ flex: 1; min-height: 0;/);
  const temp = read('pages/publish/temp/index.wxss');
  assert.doesNotMatch(temp, /calc\(100vh/);
  assert.doesNotMatch(temp, /\.cg \{\n\s*position: relative;\n\s*height: 100vh/);
  assert.match(read('pages/publish/temp/index.wxml'), /class="cg-pv-inline-scroll" enable-flex/);
});

test('PG-029/PG-036:商家口碑公开态深色、资料详情按身份切主题,两页归共享域', () => {
  const lint = require('../../scripts/ui-theme-unit-lint.js');
  assert.equal(lint.classifyPageDomain('pages/merchant/reviews/index'), 'shared');
  assert.equal(lint.classifyPageDomain('subpackageA/pages/infomationdetail/infomationdetail'), 'shared');
  assert.match(read('pages/merchant/reviews/index.wxml'), /class="review-page \{\{mode === 'public' \? 'theme-dark' : 'theme-merchant'\}\}"/);
  assert.match(read('pages/merchant/reviews/index.js'), /if \(this\.data\.mode === 'public'\) merchantTheme\.merchantPageRestore\(\);/);
  assert.match(read('subpackageA/pages/infomationdetail/infomationdetail.wxml'), /^<view class="\{\{isMerchantView \? 'theme-merchant' : 'theme-dark'\}\}">/);
  assert.match(read('subpackageA/pages/infomationdetail/infomationdetail.js'), /policy\.isMerchantView\(/);
});

test('PG-032/PG-033:批量标签不写死颜色;账户收益页删除 tx* 提现表单死代码', () => {
  assert.doesNotMatch(read('pages/merchant/customer/index.js'), /tagColor: '#2E6D5A', requestId/);
  const js = read('subpackageA/pages/assetcenter/earnings/index.js');
  const wxml = read('subpackageA/pages/assetcenter/earnings/index.wxml');
  assert.doesNotMatch(js, /\btx[A-Z]\w*|submitBankWithdrawal|withdrawalPreflight/);
  assert.doesNotMatch(wxml, /\btx[A-Z]\w*|<cy-sheet/);
  assert.match(js, /withdrawCs\.showWithdrawCsPopup\(\)/);
});

test('PG-034/PG-040/PG-042:商家提现两页、建券页与独立提现页下线,入口与注册表同步收口', () => {
  for (const dir of ['pages/coop/withdraw', 'subpackageMember/couponInfo', 'subpackageMember/tixian']) assert.equal(exists(dir), false, dir);
  const app = read('app.json');
  assert.doesNotMatch(app, /"withdraw\/index"|"withdraw\/records\/index"|couponInfo|"tixian\/tixian"/);
  assert.doesNotMatch(read('subpackageMember/coupon/coupon.js'), /GoAdd|couponInfo/);
  const { getScene } = require('../../utils/scene-registry.js');
  assert.equal(getScene('member-withdraw').route, '/subpackageA/pages/assetcenter/earnings/index');
});

test('PG-035/PG-037/PG-038/PG-043/PG-056:徽章缺图落缺参、收益子页身份兜底、分析与申诉识别登录态、顶距兜底', () => {
  assert.doesNotMatch(read('subpackageP3/pages/badge-3d/index.js'), /return DEFAULT_BADGE_ASSET;\n\}/);
  assert.match(read('subpackageP3/pages/badge-3d/index.wxml'), /wx:if="\{\{missing\}\}"><cy-empty kind="missing-param"/);
  for (const f of ['subpackageA/pages/assetcenter/analysis/index.js', 'subpackageA/pages/assetcenter/income-detail/income-detail.js']) {
    assert.match(read(f), /options\.theme === 'merchant'\) \|\| roleGuard\.isMerchantView\(\)/, f);
  }
  assert.match(read('subpackageA/pages/assetcenter/analysis/index.wxml'), /wx:elif="\{\{state === 'login'\}\}"[^>]*cta="登录" bind:cta="onLogin"/);
  assert.match(read('subpackageMember/appeal-mine/index.js'), /isLoginRequired\(app, res, statusCode\)\) \{ that\.setData\(\{ state: 'login'/);
  for (const f of ['points-history', 'report-mine', 'report-detail', 'appeal-mine']) {
    assert.match(read(`subpackageMember/${f}/index.js`), /statusBarHeight: app\.globalData\.statusBarHeight \|\| 20/, f);
  }
});

test('PG-046/047/050/051/052/053/054/057/058:样式与主题收口', () => {
  const club = read('pages/club/detail/index.wxss');
  assert.doesNotMatch(club, /border[^;\n]*rgba\(255, ?255, ?255, ?0\.(06|12)\)/);
  assert.doesNotMatch(club, /#8a8a8e|#0a0a0a;/);
  assert.match(read('pages/merchant/citynode/create/index.wxml'), /^<view class="cnc-page theme-merchant">/);
  assert.match(read('subpackageP3/pages/stamp-album/index/index.wxss'), /\.al-fab \{[^}]*bottom: calc\(var\(--cy-space-7\) \+ env\(safe-area-inset-bottom\)\)/);
  const sq = read('pages/square/detail/index.wxss');
  assert.doesNotMatch(sq, /font-weight: 700rpx/);
  assert.match(sq, /\.david_npl\{[^}]*bottom: 0;/);
  assert.doesNotMatch(read('pages/deregister/components/scene-settings-deregister/index.wxml'), /color="#1a1a1a"/);
  assert.equal(JSON.parse(read('pages/activity/official-detail/index.json')).backgroundColor, '#000000');
  assert.match(read('pages/coop/invite-detail/index.wxss'), /page \{ background: var\(--cy-bg-page\); \}/);
  assert.match(read('pages/club/customers/index.wxss'), /var\(--cy-safe-bottom\)/);
  assert.match(read('pages/club/notify/index.wxss'), /var\(--cy-safe-bottom\)/);
  const gc = read('subpackageP3/pages/growthcenter/index/index.wxss');
  assert.doesNotMatch(gc.slice(gc.indexOf('.gc-page {'), gc.indexOf('}', gc.indexOf('.gc-page {'))), /--cy-(space|font|type|radius|page)-[\w-]+:/);
  assert.match(read('subpackageP3/pages/growthcenter/index/index.wxml'), /<cy-page-title class="gc-title" title="成长中心" \/>/);
  assert.doesNotMatch(read('pages/topic/merchantinfo/merchantinfo.wxss'), /#D4CFC4/);
});

test('PG-048/PG-090:本局计分页改 custom 导航 + cy-nav-bar/cy-page-title/cy-toast,屏上不露编号与类型码', () => {
  const json = JSON.parse(read('subpackageMember/orient-score/index.json'));
  assert.equal(json.navigationStyle, 'custom');
  for (const c of ['cy-nav-bar', 'cy-page-title', 'cy-toast']) assert.ok(json.usingComponents[c], c);
  const wxml = noComments(read('subpackageMember/orient-score/index.wxml'));
  assert.match(wxml, /<cy-page-title title="本局计分"/);
  assert.doesNotMatch(wxml.replace(/data-[a-z-]+="[^"]*"/g, ""), /\{\{item\.sessionId\}\}|\{\{score\.sessionId\}\}|流水 \{\{|\{\{item\.id\}\}|\{\{item\.nodeId|\{\{item\.referenceId\}\}|\{\{score\.settlement\.id\}\}|\{\{pending\.requestId/);
  assert.doesNotMatch(wxml, /\{\{item\.kind\}\}|\{\{pending\.kind\}\}/);
});

test('PG-063/064/065/066/068/070/071/090:辅助说明与编号按清单撤除', () => {
  const gone = [
    ['pages/index/index.wxml', /v3-hero-action-subtitle/],
    ['pages/roam/index.wxml', /用于到点打卡与轨迹记录|边走边揭开地图迷雾/],
    ['subpackageRoam/nearby/index.wxml', /class="psub"/],
    ['subpackageJourney/pages/settle/index.wxml', /评价会公开，只显示昵称/],
    ['pages/shezhi/shezhi.wxml', /class="reg-opt-s"/],
    ['pages/activity/components/event-attendance/index.wxml', /WGS84/],
    ['pages/publish/fabu/index.wxml', /mode-opt__desc|切换后另存为新草稿/],
    ['pages/topic/index/index.wxml', /Beta 主题，欢迎评论反馈/],
    ['subpackageA/pages/infomationdetail/infomationdetail.wxml', /适用玩法版本暂未标注/],
    ['pages/coop/invite/index.wxml', /先发布主题后再发起协作邀请/],
    ['subpackageP3/pages/stamp-camera/index/index.wxml', /双指缩放裁切/],
    ['subpackageMember/complaint/index.wxml', /仅限已报名并支付的活动/],
    ['subpackageMember/complaint/index.js', /'活动#'/],
    ['subpackageMember/report-mine/index.wxml', /编号 \{\{item\.id\}\}/],
    ['subpackageMember/report-detail/index.wxml', /编号 \{\{report\.id\}\}/],
  ];
  for (const [f, re] of gone) assert.doesNotMatch(read(f), re, f);
});
