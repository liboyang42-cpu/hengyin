const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '../..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');

test('商家记录只读资金域接口，详情用 recordType 和 recordId 路由', () => {
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

function loadLedgerSubscription(options = {}) {
  const vm = require('node:vm');
  const { createRequire } = require('node:module');
  const requirePage = createRequire(path.join(root, 'pages/merchant/ledger/index.js'));
  let uid = 'merchant-a', definition;
  const settings = [], subscriptions = [], openings = [], messages = [], writes = [];
  const wx = {
    getWindowInfo: () => ({ statusBarHeight: 20 }),
    getSetting(request) { settings.push(request); if (options.settingThrows) throw new Error('setting failed'); },
    requestSubscribeMessage(request) {
      subscriptions.push(request);
      if (options.requestThrows) throw new Error('request failed');
    },
    openSetting(request) { openings.push(request); if (options.openThrows) throw new Error('open failed'); },
  };
  if (options.noSetting) delete wx.getSetting;
  if (options.noRequest) delete wx.requestSubscribeMessage;
  if (options.noOpen) delete wx.openSetting;
  const module = { exports: {} };
  vm.runInNewContext(read('utils/subscribe.js'), { module, wx, console: { warn() {} } });
  const subscribe = module.exports;
  if (options.noTemplate) subscribe.TEMPLATES.coopSettle = '';
  const app = { globalData: { navBarHeight: 44 }, getUserID: () => uid, sendRequest() {} };
  vm.runInNewContext(read('pages/merchant/ledger/index.js'), {
    Page(value) { definition = value; }, getApp: () => app, wx, console,
    require(id) {
      if (id.endsWith('/subscribe.js')) return subscribe;
      if (id.endsWith('/toast.js')) return text => messages.push(text);
      if (id.endsWith('/merchant-theme.js')) return { merchantPageShow() {}, merchantPageRestore() {} };
      return requirePage(id);
    },
  }, { filename: 'pages/merchant/ledger/index.js' });
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
  page.setData = patch => { writes.push(patch); Object.assign(page.data, patch); };
  page.onLoad({ view: 'settlement' });
  return {
    page, wx, subscribe, settings, subscriptions, openings, messages, writes,
    switchOwner(value, show = true) { uid = value; if (show) page.onShow(); },
    setting(allowed = 'accept', mainSwitch = true) {
      return { subscriptionsSetting: { mainSwitch, itemSettings: { [subscribe.TEMPLATES.coopSettle]: allowed } } };
    },
    tap(value = true) { return page.enableCoopSettleAlerts({ detail: { value } }); },
  };
}

test('settlement notice: actual helper stays in tap stack and accepted means notification permission only', async () => {
  const h = loadLedgerSubscription();
  const pending = h.tap();
  assert.equal(h.subscriptions.length, 1, 'native subscription must start before tap returns');
  h.subscriptions[0].success({ [h.subscribe.TEMPLATES.coopSettle]: 'accept' });
  await pending;
  assert.equal(h.page.data.settleAlertsState, 'allowed');
  assert.equal(h.page.data.settleAlertsEnabled, true);
  assert.equal(h.page.data.settleAlertsLoading, false);
  assert.deepEqual(h.messages, ['已允许微信通知']);
  const wxml = read('pages/merchant/ledger/index.wxml');

  assert.match(wxml, /settleAlertsHint/);
  assert.doesNotMatch(wxml, /入账后通过服务通知告诉你/);
});

for (const scenario of ['reject', 'failed', 'unsupported', 'not-configured', 'throws']) {
  test('settlement notice: actual helper distinguishes ' + scenario, async () => {
    const h = loadLedgerSubscription({ noRequest: scenario === 'unsupported', noTemplate: scenario === 'not-configured', requestThrows: scenario === 'throws' });
    const pending = h.tap();
    if (scenario === 'reject') h.subscriptions[0].success({ [h.subscribe.TEMPLATES.coopSettle]: 'reject' });
    if (scenario === 'failed') h.subscriptions[0].fail({ errMsg: 'offline' });
    await pending;
    assert.equal(h.page.data.settleAlertsState, scenario === 'reject' ? 'not_allowed' : 'unknown');
    assert.equal(h.page.data.settleAlertsEnabled, false);
    assert.equal(h.page.data.settleAlertsLoading, false);
    assert.ok(!h.messages.some(text => /已开启|已允许|到账成功/.test(text)));
  });
}

for (const scenario of ['missing-api', 'failure', 'throws', 'missing-settings', 'missing-main', 'missing-item']) {
  test('settlement notice: getSetting ' + scenario + ' remains unknown, never refusal', () => {
    const h = loadLedgerSubscription({ noSetting: scenario === 'missing-api', settingThrows: scenario === 'throws' });
    Object.assign(h.page.data, { settleAlertsState: 'allowed', settleAlertsEnabled: true });
    h.page.syncCoopSettleAlertSetting();
    if (scenario === 'failure') h.settings[0].fail({ errMsg: 'offline' });
    if (scenario === 'missing-settings') h.settings[0].success({});
    if (scenario === 'missing-main') h.settings[0].success({ subscriptionsSetting: { itemSettings: { [h.subscribe.TEMPLATES.coopSettle]: 'accept' } } });
    if (scenario === 'missing-item') h.settings[0].success({ subscriptionsSetting: { mainSwitch: true } });
    assert.equal(h.page.data.settleAlertsState, 'unknown');
    assert.equal(h.page.data.settleAlertsEnabled, false);
    assert.equal(h.page.data.settleAlertsLoading, false);
  });
}

for (const [value, main, expected] of [['accept', true, 'allowed'], ['reject', true, 'not_allowed'], ['ban', true, 'not_allowed'], ['accept', false, 'not_allowed']]) {
  test('settlement notice: current settings ' + value + '/' + main + ' report only permission', () => {
    const h = loadLedgerSubscription(); h.page.syncCoopSettleAlertSetting();
    h.settings[0].success(h.setting(value, main));
    assert.equal(h.page.data.settleAlertsState, expected);
    assert.equal(h.page.data.settleAlertsEnabled, expected === 'allowed');
    assert.equal(h.messages.length, 0);
  });
}

test('settlement notice: duplicate tap and stale setting cannot replace active subscription', async () => {
  const h = loadLedgerSubscription(); h.page.syncCoopSettleAlertSetting();
  const pending = h.tap(); h.tap(); h.page.onShow();
  assert.equal(h.subscriptions.length, 1);
  assert.equal(h.settings.length, 1);
  h.settings[0].success(h.setting('reject'));
  assert.equal(h.page.data.settleAlertsLoading, true);
  h.subscriptions[0].success({ [h.subscribe.TEMPLATES.coopSettle]: 'accept' });
  await pending;
  h.settings[0].fail({});
  assert.equal(h.page.data.settleAlertsState, 'allowed');
});

test('settlement notice: only newest setting read may update, duplicate terminal callbacks are inert', () => {
  const h = loadLedgerSubscription(); h.page.syncCoopSettleAlertSetting(); h.page.syncCoopSettleAlertSetting();
  h.settings[1].success(h.setting()); h.settings[0].success(h.setting('reject')); h.settings[1].fail({});
  assert.equal(h.page.data.settleAlertsState, 'allowed');
});

for (const operation of ['subscription', 'setting', 'opening']) {
  test('settlement notice: old ' + operation + ' owner callback cannot change new owner or release its lock', async () => {
    const h = loadLedgerSubscription();
    let old;
    if (operation === 'subscription') old = h.tap();
    if (operation === 'setting') h.page.syncCoopSettleAlertSetting();
    if (operation === 'opening') h.tap(false);
    h.switchOwner('merchant-b');
    const fresh = h.tap();
    const before = h.writes.length;
    if (operation === 'subscription') { h.subscriptions[0].success({ [h.subscribe.TEMPLATES.coopSettle]: 'accept' }); await old; }
    if (operation === 'setting') h.settings[0].success(h.setting());
    if (operation === 'opening') { h.openings[0].success({}); h.openings[0].complete(); }
    assert.equal(h.writes.length, before);
    assert.equal(h.messages.length, 0);
    assert.equal(h.page.data.settleAlertsLoading, true);
    h.subscriptions[h.subscriptions.length - 1].success({ [h.subscribe.TEMPLATES.coopSettle]: 'reject' });
    await fresh;
    assert.equal(h.page.data.settleAlertsState, 'not_allowed');
  });
}

for (const operation of ['subscription', 'setting', 'opening']) {
  test('settlement notice: unloaded ' + operation + ' callback cannot write or toast', async () => {
    const h = loadLedgerSubscription(); let pending;
    if (operation === 'subscription') pending = h.tap();
    if (operation === 'setting') h.page.syncCoopSettleAlertSetting();
    if (operation === 'opening') h.tap(false);
    h.page.onUnload(); const before = h.writes.length;
    if (operation === 'subscription') h.subscriptions[0].success({ [h.subscribe.TEMPLATES.coopSettle]: 'accept' });
    if (operation === 'setting') h.settings[0].success(h.setting());
    if (operation === 'opening') { h.openings[0].success({}); h.openings[0].complete(); }
    await pending;
    assert.equal(h.writes.length, before);
    assert.equal(h.messages.length, 0);
  });
}

test('settlement notice: toggle off opens real settings and only readback may decide state', async () => {
  const h = loadLedgerSubscription(); const pending = h.tap();
  h.subscriptions[0].success({ [h.subscribe.TEMPLATES.coopSettle]: 'accept' }); await pending;
  h.tap(false); h.tap(false);
  assert.equal(h.openings.length, 1);
  assert.equal(h.page.data.settleAlertsState, 'unknown');
  h.openings[0].success({}); h.openings[0].complete();
  assert.equal(h.page.data.settleAlertsState, 'unknown');
  h.settings[0].success(h.setting());
  assert.equal(h.page.data.settleAlertsState, 'allowed', 'opening settings did not revoke permission');
  assert.ok(!h.messages.some(text => /关闭/.test(text)));
});

for (const scenario of ['missing', 'fail', 'throws', 'complete-only']) {
  test('settlement notice: openSetting ' + scenario + ' is unknown and allows retry', () => {
    const h = loadLedgerSubscription({ noOpen: scenario === 'missing', openThrows: scenario === 'throws' });
    h.tap(false);
    if (scenario === 'fail') h.openings[0].fail({});
    if (scenario === 'complete-only') h.openings[0].complete();
    assert.equal(h.page.data.settleAlertsState, 'unknown');
    assert.equal(h.page.data.settleAlertsLoading, false);
    assert.ok(!h.messages.some(text => /已关闭/.test(text)));
  });
}

test('settlement notice: unknown rejected promise releases lock and a later real helper attempt can succeed', async () => {
  const h = loadLedgerSubscription();
  const original = h.subscribe.request;
  h.subscribe.request = () => Promise.reject(new Error('unexpected failure'));
  await h.tap();
  assert.equal(h.page.data.settleAlertsState, 'unknown');
  assert.equal(h.page.data.settleAlertsLoading, false);
  h.subscribe.request = original;
  const pending = h.tap(); h.subscriptions[0].success({ [h.subscribe.TEMPLATES.coopSettle]: 'accept' }); await pending;
  assert.equal(h.page.data.settleAlertsState, 'allowed');
});

test('settlement notice: A-B-A navigation cannot revive the first A request or clear latest A lock', async () => {
  const h = loadLedgerSubscription(); const old = h.tap();
  h.switchOwner('merchant-b'); h.switchOwner('merchant-a');
  const fresh = h.tap(); const before = h.writes.length;
  h.subscriptions[0].success({ [h.subscribe.TEMPLATES.coopSettle]: 'accept' }); await old;
  assert.equal(h.writes.length, before);
  assert.equal(h.page.data.settleAlertsLoading, true);
  h.subscriptions[1].fail({}); await fresh;
  assert.equal(h.page.data.settleAlertsState, 'unknown');
});

test('settlement notice: immediate owner change without onShow and missing identity cannot apply or request', async () => {
  const h = loadLedgerSubscription(); const pending = h.tap(); h.switchOwner('merchant-b', false);
  const before = h.writes.length;
  h.subscriptions[0].success({ [h.subscribe.TEMPLATES.coopSettle]: 'accept' }); await pending;
  assert.equal(h.writes.length, before); assert.equal(h.messages.length, 0);
  h.switchOwner(''); const requests = h.subscriptions.length; h.tap();
  assert.equal(h.subscriptions.length, requests);
  assert.equal(h.page.data.settleAlertsState, 'unknown');
});
