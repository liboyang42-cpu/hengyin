// 第十轮 f3 第 1 项(j6 F3「约 30 个游客空白页」):游客打开只能登录后看的页,落「登录后查看 · 登录」,不留空白。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

const root = path.join(__dirname, '..', '..');
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');

function mountGate(kind, userId, page) {
  let definition;
  const requests = [];
  const redirects = [];
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => userId,
    sendRequest: (o) => requests.push(o),
    firstLogin: () => { userId = '9009'; return Promise.resolve({ ok: true }); },
  };
  const file = path.join(root, 'components/cy/access-gate/index.js');
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Component: (d) => { definition = d; }, getApp: () => app, getCurrentPages: () => [page || {}],
    wx: { redirectTo: (o) => redirects.push(o), navigateBack() {}, reLaunch() {} }, require: createRequire(file),
  });
  const gate = Object.assign({}, definition, definition.methods, {
    data: Object.assign(JSON.parse(JSON.stringify(definition.data)), { kind, need: '', clubId: '', activityId: '' }),
    setData(p, cb) { Object.assign(this.data, p); if (cb) cb(); },
  });
  return { gate, requests, redirects, attach: () => definition.lifetimes.attached.call(gate) };
}

test('俱乐部守卫:游客没带 clubId 也给登录,不再提前 return 留空白(旧代码停在 checking)', () => {
  const h = mountGate('club', '');
  h.gate.check();
  assert.equal(h.gate.data.state, 'login');
  assert.equal(h.requests.length, 0);
});

test('login 守卫:游客 → 登录态;已登录 → 放行,不读 access/me', () => {
  const guest = mountGate('login', '');
  guest.attach();
  assert.equal(guest.gate.data.state, 'login');
  const member = mountGate('login', '9009');
  member.attach();
  assert.equal(member.gate.data.state, 'allow');
  assert.equal(member.requests.length, 0);
});

test('守卫上登录成功后带原参数重开本页,让页面自己的读请求重来', async () => {
  const h = mountGate('login', '', { route: 'pages/club/roles/index', options: { clubId: '990004' } });
  h.attach();
  h.gate.onLogin();
  await new Promise((r) => setImmediate(r));
  assert.deepEqual(h.redirects.map((r) => r.url), ['/pages/club/roles/index?clubId=990004']);
});

const LOGIN_ONLY_PAGES = [
  'pages/activity/official-inbox/index', 'pages/activity/official-mine/index',
  'pages/club/enroll/index', 'pages/club/checkin-detail/index', 'pages/club/event-ops/index', 'pages/club/governance/index',
  'pages/club/notify/index', 'pages/club/roles/index', 'pages/club/topic-detail/index',
  'pages/merchant/aftercare/index', 'pages/merchant/citynode/index', 'pages/merchant/customer/detail/index',
  'pages/play/merchant/index', 'pages/address/address', 'pages/shezhi/marketing/index', 'pages/gerenziliao/gerenziliao',
  'subpackageMember/coupon/coupon', 'subpackageMember/report-mine/index', 'subpackageRoam/history/index',
  'pages/coop/invite-detail/index',
];

test('游客空白页逐页挂 login 守卫(或已挂 club/merchant 守卫)并注册组件', () => {
  for (const p of LOGIN_ONLY_PAGES) {
    const wxml = read(p + '.wxml').replace(/<!--[\s\S]*?-->/g, '');
    assert.match(wxml, /<cy-access-gate kind="login" \/>/, p);
    assert.equal(JSON.parse(read(p + '.json')).usingComponents['cy-access-gate'], '/components/cy/access-gate/index', p);
  }
  for (const p of ['pages/club/customers/index', 'pages/club/customer-detail/index', 'pages/club/settlement/index']) {
    assert.match(read(p + '.wxml'), /<cy-access-gate[^>]*kind="club"/, p);
  }
});

test('申诉页的游客态不能用加载失败类 kind(会被渲染成空白)', () => {
  assert.match(read('subpackageMember/appeal-mine/index.wxml'), /state === 'login'\}\}" kind="no-permission" title="登录后查看申诉"/);
});
