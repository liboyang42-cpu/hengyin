// 2026-10-29 走查 lane l6 第二轮:业主真机复查判为未修的 M-08 / M-11 / M-21 / M-09,与业主裁决 M-32 / M-26。
// 真因:后端出参把 logo / coverImage 按媒体登记重签,OSS 客户端默认 http ⇒ 商家 Logo 一律是 http 签名链接;
// 商家各页直接把它塞进 <image>,真机不加载 http 图片 ⇒ 头像空白、换完 Logo 别的页也看不到新图。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const noComments = (s) => s.replace(/<!--[\s\S]*?-->/g, '');

function loadImgWxs() {
  const sandbox = { module: { exports: {} }, getRegExp: (p, f) => new RegExp(p, f) };
  vm.createContext(sandbox);
  vm.runInContext(read('utils/wxs/img.wxs'), sandbox);
  return sandbox.module.exports;
}

const SIGNED = 'chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/a.jpg?Expires=1&OSSAccessKeyId=k&Signature=s%3D';

test('img.one:自有 OSS / 站点 http 换 https 且签名原样;本地路径与空值不动', () => {
  const img = loadImgWxs();
  assert.equal(img.one('http://' + SIGNED), 'https://' + SIGNED);
  assert.equal(img.one('https://' + SIGNED), 'https://' + SIGNED);
  assert.equal(img.one('http://www.chengyinhub.com/prod-api/profile/a.png'), 'https://www.chengyinhub.com/prod-api/profile/a.png');
  assert.equal(img.one('/images/mer1.jpg'), '/images/mer1.jpg');
  assert.equal(img.one('http://other.example.com/a.png'), 'http://other.example.com/a.png', '非自有域名不改写');
  assert.equal(img.one(''), '');
  assert.equal(img.one(null), '');
});

test('M-11 / M-08:商家 Logo 与封面的每个渲染点都经 img.one(首页、我的/主页、品牌中心、店铺参谋)', () => {
  const home = noComments(read('pages/merchant/index/index.wxml'));
  assert.equal((home.match(/src="\{\{ img\.one\(merchantInfo\.logo\) \}\}"/g) || []).length, 2);
  assert.doesNotMatch(home, /src="\{\{\s*merchantInfo\.logo/);

  const profile = noComments(read('subpackageMember/components/cy/profile/index.wxml'));
  assert.match(profile, /<wxs src="\.\.\/\.\.\/\.\.\/\.\.\/utils\/wxs\/img\.wxs" module="img" \/>/);
  assert.match(profile, /class="pc-avatar" src="\{\{img\.one\(_avatarSrc\)\}\}"/);
  assert.match(profile, /class="pc-hero-bg-img" src="\{\{img\.one\(heroImg\)\}\}"/);

  const decor = noComments(read('pages/merchant/decor/index.wxml'));
  assert.match(decor, /<wxs src="\.\.\/\.\.\/\.\.\/utils\/wxs\/img\.wxs" module="img" \/>/);
  assert.match(decor, /src="\{\{img\.one\(m\.logo\)\}\}"/);
  assert.match(decor, /src="\{\{img\.one\(m\.coverImage\)\}\}"/);
  assert.match(decor, /src="\{\{img\.one\(imageEditor\.value\)\}\}"/);
  assert.doesNotMatch(decor, /src="\{\{(m\.logo|m\.coverImage|imageEditor\.value)\}\}"/);

  const insight = noComments(read('pages/merchant/marketing/ai-insight/index.wxml'));
  assert.match(insight, /<wxs src="\.\.\/\.\.\/\.\.\/\.\.\/utils\/wxs\/img\.wxs" module="img" \/>/);
  assert.match(insight, /class="greet-avatar"[^>]*src="\{\{img\.one\(storeLogo\)\}\}"/);
});

function loadInsight(requests) {
  let def = null;
  const file = path.join(ROOT, 'pages/merchant/marketing/ai-insight/index.js');
  vm.runInNewContext(read('pages/merchant/marketing/ai-insight/index.js'), {
    Page: (d) => { def = d; },
    getApp: () => ({ globalData: {}, getUserID: () => '7', sendRequest: (o) => requests.push(o), getRequestErrorMessage: () => '' }),
    wx: { getSystemInfoSync: () => ({ statusBarHeight: 44 }), setNavigationBarColor() {} },
    console,
    require: (id) => require(path.resolve(path.dirname(file), id)),
  });
  const page = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data)) });
  page.setData = function (patch) { Object.assign(this.data, patch); };
  return page;
}

test('M-21:参谋包缺店名/Logo 时补读 /api/merchant/info(与首页、我的同一来源),只补空的那项', () => {
  const requests = [];
  const page = loadInsight(requests);
  page.setData({ storeName: '星巴克咖啡厅', storeLogo: '' });
  page._loadStoreIdentity(() => true);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/merchant/info');
  assert.equal(requests[0].method, 'POST');
  requests[0].success({ code: 200, data: { name: '别的名字', logo: 'https://' + SIGNED } });
  assert.equal(page.data.storeName, '星巴克咖啡厅', '已有店名不被覆盖');
  assert.equal(page.data.storeLogo, 'https://' + SIGNED);

  const stale = loadInsight(requests);
  stale._loadStoreIdentity(() => false);
  requests[1].success({ code: 200, data: { name: '旧号的店', logo: 'x' } });
  assert.equal(stale.data.storeName, '', '换号/离页后的回包不落地');
});

test('M-21:成功回包没有店名或 Logo 才补读;两者齐全不多发请求', () => {
  const js = read('pages/merchant/marketing/ai-insight/index.js');
  assert.match(js, /storeLogo: String\(profile\.logo \|\| ''\)\.trim\(\)/);
  assert.match(js, /if \(!that\.data\.storeName \|\| !that\.data\.storeLogo\) that\._loadStoreIdentity\(isCurrent\);/);
  assert.match(js, /_clearSensitiveState[\s\S]*?storeLogo: '',/);
});

test('M-21:逐日方块空格在白卡上看得见(全零店铺整片都是空格),1 档随之加深', () => {
  const wxss = read('pages/merchant/marketing/ai-insight/index.wxss');
  const cell = wxss.match(/\.heat-cell \{[^}]*\}/)[0];
  assert.match(cell, /background: var\(--mi-heat-off\)/);
  assert.match(wxss, /--mi-heat-off:\s*#DCDDE2;/);
  assert.match(wxss, /\.heat-cell--l1 \{ background: var\(--mi-heat-1\); \}/);
  // 没有逐日数据时仍铺满 30 格(上一轮已修,这里锁住)
  const requests = [];
  const page = loadInsight(requests);
  const hero = page._buildHero({ window: '近30天', attribution: { visitors: 0, checkins: 0, redeems: 0 } }, 30, 'visitors', false);
  assert.equal(hero.cells.length, 30);
  assert.ok(hero.cells.every((c) => c.lv === 0));
  assert.equal(hero.trio.map((t) => t.num).join(','), '0,0,0');
  assert.equal(page._pct(null), '0%', '比率缺失写 0%,不写 —');
});

// 2026-10-31 dw29 发布集成:裁剪页以 P-5(M-09)定稿为准(整图缩进舞台、框外压暗、底栏小胶囊),
// l6 第二轮的 fitFrame 缩框与「选取」实心 64rpx 方案被它取代;这里只保留两版共同的要求。
test('M-08 / M-09:整张图在舞台内、框外压暗不裁黑;Logo 为圆;遮罩在图之后;底栏命中区 88rpx', () => {
  const wxml = read('pages/crop/index.wxml');
  assert.match(wxml, /<view class="crop__mask \{\{round \? 'crop__mask--round' : ''\}\}"><\/view>/);
  assert.ok(wxml.indexOf('class="crop__img"') < wxml.indexOf('class="crop__mask'), '遮罩在图之后');
  const wxss = read('pages/crop/index.wxss');
  const frame = wxss.match(/\.crop__frame \{[^}]*\}/)[0];
  assert.doesNotMatch(frame, /overflow:\s*hidden/);
  assert.match(wxss, /\.crop__mask--round \{ border-radius: 50%; \}/);
  assert.match(wxss.match(/\.crop__action \{[^}]*\}/)[0], /min-height: 88rpx/);
  const js = read('pages/crop/index.js');
  // 整图按舞台减去四边留白等比缩进;取景框不超过整图显示宽
  assert.match(js, /const fit = Math\.min\(\(this\.avail\.w - FRAME_GAP \* 2\) \/ natW, \(this\.avail\.h - FRAME_GAP \* 2\) \/ natH\)/);
  assert.match(js, /let frameW = Math\.min\(FRAME_MAX_W, shownW\)/);
});

test('M-32:俱乐部计酬本月待结清/已结清行并进本月收入明细,按来源标注,时间倒序;不计入余额合计', () => {
  const ev = require(path.join(ROOT, 'utils/earnings-view.js'));
  const { ledgerView } = require(path.join(ROOT, 'components/cy/club-direct-ledger/view-model.js'));
  const now = new Date(2026, 9, 29, 12);
  const row = (id, status, time, fee) => ({ id, redemptionId: 100 + id, topicId: 9, payerClubId: 3, merchantMemberId: 7,
    status, perHeadFeeSnapshot: fee, createTime: time, claimId: String(id) });
  const ledger = ledgerView({ code: 200, data: [
    row(1, 'OPEN', '2026-10-20T10:00:00', '30.00'),
    row(2, 'SETTLED', '2026-10-05T09:00:00', '12.50'),
    row(3, 'VOID', '2026-10-21T09:00:00', '8.00'),
    row(4, 'REFUNDED', '2026-10-22T09:00:00', '8.00'),
    row(5, 'SETTLED', '2026-09-30T09:00:00', '5.00'),
  ] }, { kind: 'merchant' });
  const club = ev.clubPayMonthItems(ledger.rows, now);
  assert.deepEqual(club.map((c) => c.key), ['club-1', 'club-2'], '只取本月、只取待结清/已结清');
  assert.equal(club[0].tag, '俱乐部计酬 · 待结清');
  assert.equal(club[1].tag, '俱乐部计酬 · 已结清');
  assert.equal(club[0].title, '核销 #101');
  assert.equal(club[0].amountText, '+30.00');
  assert.equal(club[0].date, '10月20日');

  const month = ev.monthIncome([
    { id: 'b1', changeType: 1, eventType: 1, changeBalance: 10, changeReason: '主题分润', createTime: '2026-10-25 08:00:00' },
    { id: 'b2', changeType: 1, eventType: 2, changeBalance: 6, changeReason: '活动分润', createTime: '2026-10-10 08:00:00' },
  ], now, 3);
  const merged = ev.withClubPay(month, club);
  assert.deepEqual(merged.items.map((i) => i.key), ['b1', 'club-1', 'b2', 'club-2']);
  assert.equal(merged.total, '16.00', '俱乐部直接付款不在余额里,不加进余额口径合计');
  assert.equal(ev.withClubPay(month, []).items.length, 2);
});

test('M-32:商家账户收益读俱乐部计酬并进明细;结算明细页不再单列「俱乐部计酬」卡', () => {
  const scene = read('components/cy/scene-asset-earnings/index.wxml');
  assert.match(scene, /<cy-earnings-overview merchant="\{\{theme === 'merchant'\}\}" bind:detail="openIncomeDetail" \/>/);
  assert.match(scene, /wx:if="\{\{balance === '0\.00' && theme !== 'merchant'\}\}"/, '商家余额为 0 也要看得到本月俱乐部计酬');
  const js = read('components/cy/earnings-overview/index.js');
  assert.match(js, /if \(this\.properties\.merchant\) this\.loadClubPay\(epoch\);/);
  assert.match(js, /url: '\/api\/club-direct-merchant\/merchant', method: 'GET'/);
  assert.match(js, /ledgerView\(res, \{ kind: 'merchant' \}\)/);
  const ledgerWxml = read('pages/merchant/ledger/index.wxml');
  assert.doesNotMatch(ledgerWxml, /cy-club-direct-ledger/);
  assert.doesNotMatch(read('pages/merchant/ledger/index.json'), /cy-club-direct-ledger/);
  // 俱乐部侧的「商家计酬」不动
  assert.match(read('pages/club/settlement/index.wxml'), /<cy-club-direct-ledger class="section" wx:if="\{\{clubId\}\}" kind="club"/);
});

function loadOverview(requests, merchant) {
  let def = null;
  const file = path.join(ROOT, 'components/cy/earnings-overview/index.js');
  vm.runInNewContext(read('components/cy/earnings-overview/index.js'), {
    Component: (d) => { def = d; },
    getApp: () => ({ globalData: {}, getUserID: () => 7, getUserRole: () => 'merchant', getUserType: () => 2,
      sendRequest: (o) => requests.push(o) }),
    console, Date,
    require: (id) => require(path.resolve(path.dirname(file), id)),
  });
  const comp = Object.assign({}, def.methods, { data: JSON.parse(JSON.stringify(def.data)), properties: { merchant } });
  comp.setData = function (patch) { Object.assign(this.data, patch); };
  def.lifetimes.attached.call(comp);
  return comp;
}

test('M-32:组件按到达顺序合并;玩家视角不读俱乐部计酬;403 不出重试行', () => {
  const requests = [];
  const comp = loadOverview(requests, true);
  const urls = requests.map((r) => r.url);
  assert.deepEqual(urls, ['/api/user/balance/list', '/api/club-direct-merchant/merchant', '/api/wallet/stages']);
  const today = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const stamp = today.getFullYear() + '-' + pad(today.getMonth() + 1) + '-' + pad(Math.max(1, today.getDate() - 1)) + 'T10:00:00';
  // 计酬先到:余额没回来前不提前画本月列表
  requests[1].success({ code: 200, data: [{ id: 1, redemptionId: 5, topicId: 9, payerClubId: 3, merchantMemberId: 7,
    status: 'OPEN', perHeadFeeSnapshot: '20.00', createTime: stamp }] });
  assert.equal(comp.data.month.ready, false);
  requests[0].success({ code: '200', data: { rows: [] } });
  assert.equal(comp.data.month.ready, true);
  assert.deepEqual(comp.data.month.items.map((i) => i.tag), ['俱乐部计酬 · 待结清']);

  const denied = [];
  const c2 = loadOverview(denied, true);
  denied[1].successStatusAbnormal({ code: 403 });
  assert.equal(c2.data.clubError, false);
  denied[1].fail({}, 500);
  assert.equal(c2.data.clubError, true);

  const player = [];
  loadOverview(player, false);
  assert.ok(!player.some((r) => r.url === '/api/club-direct-merchant/merchant'));
});

test('M-26:我的项目长按菜单没有「复制」入口', () => {
  const wxml = read('subpackageA/pages/myproject/index.wxml');
  const keys = (wxml.match(/data-k="([a-zA-Z]+)"/g) || []).map((s) => s.slice(8, -1));
  assert.deepEqual(keys.sort(), ['audit', 'cancel', 'delete', 'edit', 'received', 'saveAsTemplate', 'toggle']);
  assert.doesNotMatch(noComments(wxml), /<text class="tm-label">复制/);
  assert.doesNotMatch(read('subpackageA/pages/myproject/index.js'), /k === 'copy'/);
});
