'use strict';
// UI-sweep 1119 U3/U4:商家页被无权限的人打开时原地讲清(锁 + 一行「当前身份不能…」+ 一个动作),
// 不再 2s 半屏后自动退回;无权限文案统一成「当前身份不能…」。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');
const { decideMerchantGate } = require('../../utils/access-gate.js');
const { PERMISSIONS } = require('../../utils/merchant-access-policy.js');

const merchantOk = (perms) => ({ code: 200, data: { active: true, merchant: { id: 1, name: '河畔咖啡' }, roleCode: 'MERCHANT_OWNER', permissions: perms } });

function mountGate(kind, pages) {
  let def;
  const requests = [];
  const navs = [];
  const app = { globalData: {}, getUserID: () => '7', sendRequest: (o) => requests.push(o) };
  global.getApp = () => app;
  global.Component = (d) => { def = d; };
  global.getCurrentPages = () => pages;
  global.wx = {
    navigateTo: (o) => { navs.push(['navigateTo', o.url]); },   // complete 不回:连点必须被锁住
    navigateBack: (o) => { navs.push(['navigateBack']); if (o && o.complete) o.complete(); },
    reLaunch: (o) => { navs.push(['reLaunch', o.url]); if (o.complete) o.complete(); },
    redirectTo: (o) => { navs.push(['redirectTo', o.url]); },
  };
  for (const rel of ['components/cy/access-gate/index.js', 'utils/merchant-no-permission.js']) delete require.cache[path.join(root, rel)];
  require(path.join(root, 'components/cy/access-gate/index.js'));
  const gate = Object.assign({}, def.methods, {
    data: Object.assign({}, def.data, { kind, need: 'canReadFinance', backUrl: '' }),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done(); },
    triggerEvent() {},
  });
  gate._page = pages[pages.length - 1];
  return { gate, requests, navs };
}

test('决策带出 member:非成员 false,在营成员缺权限 true;state 判定不变', () => {
  const none = decideMerchantGate({ code: 200, data: { active: false } }, 'canReadFinance');
  assert.equal(none.state, 'deny');
  assert.equal(none.member, false);
  const lacking = decideMerchantGate(merchantOk([PERMISSIONS.BASIC_READ]), 'canReadFinance');
  assert.equal(lacking.state, 'deny');
  assert.equal(lacking.member, true);
  assert.equal(decideMerchantGate({ code: 403 }, '').member, false);
  assert.equal(decideMerchantGate(merchantOk([PERMISSIONS.FINANCE_READ]), 'canReadFinance').state, 'allow');
});

test('access-gate merchant deny:原地 cy-empty no-permission 盖住内容,不弹自动退出半屏', () => {
  const wxml = read('components/cy/access-gate/index.wxml');
  const block = wxml.match(/<view wx:if="\{\{state === 'deny' && kind === 'merchant'\}\}"[\s\S]*?<\/view>/);
  assert.ok(block, '商家 deny 必须有自己的原地分支');
  assert.match(block[0], /class="gate"/, 'deny 仍须用 gate 盖层盖住页面内容');
  assert.match(block[0], /<cy-empty kind="no-permission" fill size="lg" title="[^"]+" cta="\{\{denyCta\}\}" bind:cta="onDenyCta"/);
  assert.doesNotMatch(block[0], /cy-result-sheet|onExit|sub=/, '商家 deny 不再自动退出,也不加副文案');
  // 俱乐部行为不变:fail 半屏 + onExit
  assert.match(wxml, /<view wx:elif="\{\{state === 'deny'\}\}"[\s\S]*?<cy-result-sheet [^>]*kind="fail"[^>]*bind:close="onExit"/);
});

test('access-gate merchant deny 动作:非成员「申请入驻」,在营成员「返回」;都不自动退出', () => {
  const pages = [{ route: 'pages/merchant/payout-account/index' }];
  const outsider = mountGate('merchant', pages);
  outsider.gate.check();
  outsider.requests[0].success({ code: 200, data: { active: false, applicationState: 'NONE' } });
  assert.equal(outsider.gate.data.state, 'deny');
  assert.equal(outsider.gate.data._sheet, false, '商家 deny 不弹 2s 自动退出的半屏');
  assert.equal(outsider.gate.data.denyCta, '申请入驻');
  assert.match(outsider.gate.data.reason, /^当前身份不能/);
  assert.deepEqual(outsider.navs, [], '不许自动离开');
  assert.equal(pages[0].__cyDenyInPlace, true, '同页 cy-error auto-back 必须被压住');
  outsider.gate.onDenyCta();
  outsider.gate.onDenyCta();
  assert.deepEqual(outsider.navs, [['navigateTo', '/pages/merchant/apply/index']], '连点只走一次');

  const member = mountGate('merchant', [{ route: 'a' }, { route: 'pages/merchant/payout-account/index' }]);
  member.gate.check();
  member.requests[0].success(merchantOk([PERMISSIONS.BASIC_READ]));
  assert.equal(member.gate.data.denyCta, '返回');
  member.gate.onDenyCta();
  assert.deepEqual(member.navs, [['navigateBack']]);

  const root1 = mountGate('merchant', [{ route: 'pages/merchant/payout-account/index' }]);
  root1.gate.data.backUrl = '/pages/merchant/marketing/index';
  root1.gate.check();
  root1.requests[0].success(merchantOk([PERMISSIONS.BASIC_READ]));
  root1.gate.onDenyCta();
  assert.deepEqual(root1.navs, [['reLaunch', '/pages/merchant/marketing/index']], '栈底回页面自己的去处');
});

test('access-gate 商家会话失效走登录门(仍盖住),俱乐部 deny 仍是半屏', () => {
  const expired = mountGate('merchant', [{ route: 'x' }]);
  expired.gate.check();
  expired.requests[0].success({ code: 500, msg: '请先登录' });
  assert.equal(expired.gate.data.state, 'login');
  const club = mountGate('club', [{ route: 'x' }]);
  club.gate.data.clubId = 7;
  club.gate.check();
  club.requests[0].success({ code: 200, data: { active: false } });
  assert.equal(club.gate.data.state, 'deny');
  assert.equal(club.gate.data._sheet, true, '俱乐部 deny 行为不变');
});

const MERCHANT_PAGES = [
  'pages/merchant/citynode/index', 'pages/merchant/citynode/create/index', 'pages/merchant/coop-center/index',
  'pages/merchant/crm-campaigns/index', 'pages/merchant/decor/index', 'pages/merchant/decor/coop-setting/index',
  'pages/merchant/decor/gallery/index', 'pages/merchant/decor/perks/index', 'pages/merchant/decor/ai-npc/index',
  'pages/merchant/game-node/index', 'pages/merchant/index/index', 'pages/merchant/ledger/order-detail/index',
  'pages/merchant/ledger/batch-detail/index', 'pages/merchant/marketing/ai-insight/index', 'pages/merchant/payout-account/index',
  'pages/merchant/team/index', 'pages/merchant/verify-records/index', 'pages/merchant/aftercare/index',
  'pages/merchant/aftercare/detail/index', 'pages/merchant/bundle-reservations/index', 'pages/merchant/relation/index',
];

test('列出的商家页:无权限态不再 auto-back,也不在 JS 里静默退回', () => {
  const bad = [];
  for (const rel of MERCHANT_PAGES) {
    const wxml = read(rel + '.wxml');
    const js = read(rel + '.js');
    for (const tag of wxml.match(/<cy-error[\s\S]*?\/>/g) || []) {
      if (/no-permission|当前身份不能|permission'/.test(tag) && /auto-back(?!="\{\{!errorIsDenied\}\}")/.test(tag)) bad.push(rel + ' 无权限态仍 auto-back');
    }
    if (/switchTab\(\{ url: '\/pages\/member\/index\/index' \}\)/.test(js)) bad.push(rel + ' 仍静默回我的');
    if (/暂无提现权限|_permissionBackTimer/.test(js)) bad.push(rel + ' 仍 toast 后自动退出');
    const deny = wxml.match(/<cy-empty[^>]*kind="no-permission"[^>]*title="当前身份不能[^"]*"[^>]*>/g) || [];
    const gated = /<cy-access-gate[^>]*kind="merchant"/.test(wxml);
    if (!deny.length && !gated) bad.push(rel + ' 没有原地无权限态');
    for (const tag of deny) {
      if (/ sub="/.test(tag)) bad.push(rel + ' 无权限态不许加副文案');
      if (!/cta="[^"]+"[^>]*bind:cta="\w+"/.test(tag)) bad.push(rel + ' 无权限态必须有且只有一个动作');
    }
  }
  assert.deepEqual(bad, []);
});

test('商家首页 / 经营团队:非成员落原地无权限态 + 申请入驻,不 switchTab', () => {
  assert.match(read('pages/merchant/index/index.wxml'), /<view wx:elif="\{\{consoleDenied\}\}" class="rv-denied" style="padding-top: \{\{statusBarHeight \+ navBarHeight\}\}px;">\s*<cy-empty kind="no-permission"[^>]*cta="申请入驻" bind:cta="goApplicationProgress"/);
  assert.match(read('pages/merchant/team/index.wxml'), /<cy-empty wx:elif="\{\{accessState === 'no-permission'\}\}" kind="no-permission"[^>]*cta="申请入驻"/);
});

test('前端无权限文案统一为「当前身份不能…」,不再出现 当前岗位/当前账号 的拒绝句式', () => {
  const hits = [];
  const walk = (dir) => {
    for (const name of fs.readdirSync(path.join(root, dir))) {
      const rel = path.join(dir, name);
      if (fs.statSync(path.join(root, rel)).isDirectory()) {
        if (!['node_modules', 'tests', 'miniprogram_npm', 'docs', 'artifacts', '.git'].includes(name)) walk(rel);
      } else if (/\.(js|wxml|wxs)$/.test(name)) {
        fs.readFileSync(path.join(root, rel), 'utf8').split('\n').forEach((line, i) => {
          const code = line.trim();
          if (code.startsWith('//') || code.startsWith('<!--') || code.startsWith('*')) return;
          if (/当前(岗位|账号)(不能|没有\S*权限|无法查看|无权|仅可|只有|暂时不能|未开放)/.test(line)) hits.push(rel + ':' + (i + 1));
        });
      }
    }
  };
  for (const dir of fs.readdirSync(root)) {
    if (fs.statSync(path.join(root, dir)).isDirectory() && !['node_modules', 'tests', 'miniprogram_npm', 'docs', 'artifacts', '.git'].includes(dir)) walk(dir);
  }
  assert.deepEqual(hits, []);
});
