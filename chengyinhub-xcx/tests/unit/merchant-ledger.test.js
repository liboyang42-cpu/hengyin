const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '../..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');

test('商家台账只读资金域接口，详情用 recordType 和 recordId 路由', () => {
  const js = read('pages/merchant/ledger/index.js');
  assert.match(js, /\/api\/merchant\/finance\/redemptions/);
  assert.match(js, /\/api\/merchant\/finance\/overview/);
  assert.match(js, /\/api\/merchant\/finance\/settlement-entries/);
  assert.match(js, /\/api\/merchant\/finance\/public-transfer-batches/);
  assert.match(js, /recordType=\$\{item\.recordType\}&recordId=\$\{item\.recordId\}/);
  assert.doesNotMatch(js, /loadLegacyOrders|\/api\/merchant\/orders/);
});

test('旧 tab 只在路由 shim 出现，未识别值不能默认进入资金页', () => {
  const route = read('pages/merchant/utils/merchant-finance.js');
  assert.match(route, /if \(view === 'redemptions' \|\| view === 'settlement' \|\| view === 'messages'\)/);
  assert.match(route, /return null;/);
  assert.match(route, /legacy === '0'.*redemptions/s);
  assert.match(route, /legacy === '2'.*settlement/s);
});


test('项目铃铛按真实主题/活动归属打开近期收入与核销，失败与非法链接不退回全店', () => {
  const vm = require('node:vm');
  const { normalizeMerchantFinanceRoute } = require('../../pages/merchant/utils/merchant-finance.js');
  const finance = require('../../pages/merchant/utils/merchant-finance.js');
  const access = require('../../utils/merchant-access-policy.js');
  const shape = require('../../utils/response-shape.js');
  const { buildProjectCards } = require('../../utils/merchant-workbench.js');
  const events = [
    { ownerType: 1, ownerId: 7, type: 'income', label: '收入到账', content: '主题分润 +¥12.00', time: '09-30 10:00' },
    { ownerType: 1, ownerId: 7, type: 'verify', label: '核销', content: '完成一笔到店核销', time: '09-30 09:00' },
    { ownerType: 2, ownerId: 7, type: 'verify', label: '核销', content: '活动到店核销', time: '09-30 08:00' },
    { ownerType: 1, ownerId: 8, type: 'income', label: '收入到账', content: '其他项目分润' },
    { type: 'income', label: '收入到账', content: '无项目归属' },
  ];
  assert.deepEqual(normalizeMerchantFinanceRoute({ view: 'messages', ownerType: '1', ownerId: '7' }),
    { view: 'messages', source: '', ownerType: 1, ownerId: 7 });
  const invalidRoutes = [
    { view: 'messages', ownerType: 1 }, { view: 'messages', ownerId: 7 },
    { view: 'messages', ownerType: 3, ownerId: 7 }, { view: 'messages', ownerType: 1, ownerId: 0 },
    { view: 'messages', ownerType: 1, ownerId: -1 }, { view: 'messages', ownerType: 1, ownerId: '7.5' },
    { view: 'messages', ownerType: 1, ownerId: Number.MAX_SAFE_INTEGER + 1 },
    { view: 'messages', ownerType: 1, ownerId: [7] },
    { view: 'redemptions', ownerType: 1, ownerId: 7 }, { ownerType: 1, ownerId: 7 },
  ];
  for (const options of invalidRoutes) assert.equal(normalizeMerchantFinanceRoute(options), null);
  function mount(relative) {
    let definition;
    let memberId = 'merchant-a';
    const requests = [];
    const urls = [];
    vm.runInNewContext(read(relative), {
      Page(value) { definition = value; },
      getApp: () => ({ globalData: { navBarHeight: 44 }, getUserID: () => memberId,
        sendRequest(options) { requests.push(options); } }),
      require: request => request.includes('merchant-finance') ? finance
        : (request.includes('merchant-access-policy') ? access
          : (request.includes('response-shape') ? shape : {})),
      wx: { getWindowInfo: () => ({ statusBarHeight: 20 }), navigateTo(options) { urls.push(options.url); } },
    }, { filename: path.join(root, relative) });
    const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
    page.setData = patch => Object.assign(page.data, patch);
    return { page, requests, urls, switchMember(value) { memberId = value; } };
  }
  function allow(request) {
    assert.equal(request.url, '/api/merchant/access/me');
    request.success({ code: 200, data: { active: true, merchant: { id: 4, name: '本店' },
      roleCode: 'MERCHANT_OWNER', permissions: ['merchant:basic:read', 'merchant:finance:read', 'merchant:verify:record:read'] } });
  }
  const cards = buildProjectCards({
    joinList: [{ id: 999, topicId: 7, status: 1 }],
    hostList: [{ id: 7, bizType: 'activity', titleText: '活动' }], todoByProject: [], events,
  });
  for (const card of cards) {
    const home = mount('pages/merchant/index/index.js');
    home.page.goProjectMessages({ currentTarget: { dataset: { ownertype: card.ownerType, ownerid: card.ownerId,
      id: 999, role: card.role } } });
    assert.deepEqual(home.urls, ['/pages/merchant/ledger/index?view=messages&ownerType=' + card.ownerType + '&ownerId=7']);
    const current = mount('pages/merchant/ledger/index.js');
    current.page.onLoad({ view: 'messages', ownerType: String(card.ownerType), ownerId: '7' });
    allow(current.requests[0]);
    assert.equal(current.requests[1].url, '/api/merchant/events');
    current.requests[1].success({ code: 200, data: events });
    assert.equal(current.page.data.pageTitle, '本项目近期动态');
    assert.equal(current.page.data.projectEvents.length, card.msgCount, '列表与铃铛使用相同事件归属');
    assert.deepEqual(Array.from(current.page.data.projectEvents, row => row.type), card.ownerType === 1 ? ['income', 'verify'] : ['verify']);
    assert.equal(current.page.data.hasLoaded, true);
    current.page.retry(); allow(current.requests[2]); current.requests[3].fail({ errMsg: 'network' });
    assert.equal(current.page.data.error, true);
    assert.equal(current.page.data.projectEvents.length, card.msgCount, '失败保留已确认动态，不能伪装空态');
    current.page.retry(); allow(current.requests[4]); current.requests[5].success({ code: 200, data: [] });
    assert.equal(current.page.data.error, false); assert.equal(current.page.data.loading, false);
    assert.equal(current.page.data.projectEvents.length, 0); assert.equal(current.page.data.hasLoaded, true);
    current.page.retry(); allow(current.requests[6]); current.requests[7].success({ code: 200, data: {} });
    assert.equal(current.page.data.error, true, '200+错误形状不冒充成功空态');
    current.page.retry(); allow(current.requests[8]); const stale = current.requests[9];
    current.switchMember('merchant-b'); current.page._resetMemberScope('merchant-b');
    stale.success({ code: 200, data: events });
    assert.equal(current.page.data.projectEvents.length, 0, '旧身份回包不能复活别店动态');
  }
  for (const lostPermission of ['merchant:finance:read', 'merchant:verify:record:read']) {
    const downgraded = mount('pages/merchant/ledger/index.js');
    downgraded.page.onLoad({ view: 'messages', ownerType: '1', ownerId: '7' });
    allow(downgraded.requests[0]); downgraded.requests[1].success({ code: 200, data: events });
    assert.equal(downgraded.page.data.projectEvents.length, 2);
    downgraded.page.retry();
    downgraded.requests[2].success({ code: 200, data: { active: true, merchant: { id: 4, name: '本店' },
      roleCode: 'MERCHANT_MANAGER', permissions: ['merchant:basic:read', 'merchant:finance:read',
        'merchant:verify:record:read'].filter(permission => permission !== lostPermission) } });
    assert.equal(downgraded.page.data.projectEvents.length, 0, '读取能力变化后不能保留旧权限动态');
    assert.equal(downgraded.page.data.hasLoaded, false);
    downgraded.requests[3].fail({ errMsg: 'network' });
    assert.equal(downgraded.page.data.projectEvents.length, 0, '降权后刷新失败也不能复活旧收入或核销');
    assert.equal(downgraded.page.data.error, true);
  }
  for (const options of invalidRoutes) {
    const invalid = mount('pages/merchant/ledger/index.js'); invalid.page.onLoad(options);
    assert.equal(invalid.page.data.routeError, true); assert.equal(invalid.requests.length, 0);
  }
  const invalidHome = mount('pages/merchant/index/index.js');
  invalidHome.page.goProjectMessages({ currentTarget: { dataset: { id: 7, ownertype: 1 } } });
  assert.equal(invalidHome.urls.length, 0, '不能用卡片报名id猜项目id');
  const legacy = mount('pages/merchant/ledger/index.js'); legacy.page.onLoad({ view: 'messages' });
  allow(legacy.requests[0]); assert.equal(legacy.requests.length, 1, '原全局消息只保留IM入口');
  legacy.page.goInbox(); assert.deepEqual(legacy.urls, ['/subpackageB/pages/im/list/index']);
});


test('actual merchant denial shows permission state while failed permission reads stay blank and issue no finance request', () => {
  const nodeVm = require('node:vm');
  const { createRequire } = require('node:module');
  const file = path.join(root, 'pages/merchant/ledger/index.js');
  const requests = []; let config;
  nodeVm.runInNewContext(read('pages/merchant/ledger/index.js'), {
    Page: value => { config = value; }, require: createRequire(file),
    getApp: () => ({ globalData: {}, getUserID: () => 23, sendRequest: req => requests.push(req) }),
    wx: { getStorageSync: () => '', getWindowInfo: () => ({ statusBarHeight: 20 }) },
  });
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)),
    setData: patch => Object.assign(page.data, patch) });
  page.onLoad({ view: 'redemptions' });
  requests[0].success({ code: 200, data: { active: false, permissions: [] } });
  assert.equal(page.data.accessDenied, true);
  assert.equal(page.data.error, true);
  assert.equal(requests.length, 1);
  page.retry(); requests[1].success({ code: 500 });
  assert.equal(page.data.accessDenied, false, 'failed read cannot claim absence of permission');
  assert.equal(page.data.error, true);
  assert.equal(requests.length, 2);
  const wxml = read('pages/merchant/ledger/index.wxml');
  assert.match(wxml, /cy-empty wx:if="\{\{accessDenied\}\}" kind="no-permission"/);
  assert.match(wxml, /cy-error load-failure wx:elif="\{\{error && !hasLoaded\}\}"/);
});
