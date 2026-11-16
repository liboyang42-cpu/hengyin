const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { normalizeMerchantAccess } = require('../../utils/merchant-access-policy.js');
const pagePath = path.resolve(__dirname, '../../pages/merchant/index/index.js');
const markup = () => fs.readFileSync(pagePath.replace('.js', '.wxml'), 'utf8');
const permissions = {
  MERCHANT_OWNER: ['merchant:basic:read', 'merchant:verify', 'merchant:verify:record:read', 'merchant:project:manage', 'merchant:coop:manage', 'merchant:finance:read', 'merchant:order:read', 'merchant:crm:read', 'merchant:profile:write', 'merchant:coupon:manage', 'merchant:aftercare:read'],
  MERCHANT_MANAGER: ['merchant:basic:read', 'merchant:verify', 'merchant:verify:record:read', 'merchant:project:manage', 'merchant:coop:manage', 'merchant:order:read', 'merchant:crm:read'],
  MERCHANT_CHECKIN: ['merchant:basic:read', 'merchant:verify', 'merchant:verify:record:read'],
};
function harness(role = 'MERCHANT_CHECKIN') {
  const requests = [], navigation = [], toasts = [], scans = [];
  const app = { globalData: {}, getUserID: () => 49, getRequestErrorMessage: (r, fallback) => r?.msg || fallback,
    sendRequest: r => { requests.push(r); return { abort() {} }; } };
  global.getApp = () => app;
  global.getCurrentPages = () => [];
  global.wx = { showToast: x => toasts.push(x.title), getStorageSync: () => '',
    navigateTo: x => navigation.push(x.url), scanCode: x => scans.push(x), getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }) };
  let definition;
  global.Page = p => { definition = p; };
  delete require.cache[require.resolve(pagePath)]; require(pagePath);
  const page = Object.assign({}, definition, { data: structuredClone(definition.data) });
  page.setData = (patch, done) => { Object.assign(page.data, patch); done?.(); };
  page.data.merchantAccess = normalizeMerchantAccess({ active: true, merchant: { id: 31 }, roleCode: role, permissions: permissions[role] });
  page._dataEpoch = 1;
  return { page, requests, navigation, toasts, scans };
}
function visibleTag(tag, data) {
  const expression = tag.match(/wx:if="\{\{(.*?)\}\}"/)?.[1];
  return !expression || Function('data', 'with(data) { return !!(' + expression + '); }')(data);
}
for (const role of Object.keys(permissions)) test(`${role}: actual Page keeps revenue, four actions and project structure`, () => {
  const { page } = harness(role), wxml = markup();
  const revenue = wxml.match(/<view[^>]*class="rv-revenue-zone"[^>]*>/)[0];
  assert.equal(visibleTag(revenue, page.data), true, 'Revenue slot must remain even without figures');
  for (const action of ['goScanQR', 'goCustomers', 'goFinance', 'openMoreMenu']) {
    const tag = wxml.match(new RegExp('<view[^>]*class="rv-action[^"\\n]*"[^>]*bindtap="' + action + '"[^>]*>'))[0];
    assert.equal(visibleTag(tag, page.data), true, action);
  }
  const project = wxml.match(/<view[^>]*class="rv-section"[^>]*>[\s\S]*?<text class="rv-section-title">项目/)[0].split('>')[0] + '>';
  assert.equal(visibleTag(project, page.data), true, 'Project section must remain when locked or empty');
  assert.match(wxml, /rv-section-title">待办/);
});
test('CHECKIN locked finance/customer/project/menu clicks give feedback without navigation, scan or reads', () => {
  const { page, requests, navigation, toasts, scans } = harness();
  for (const method of ['goCustomers', 'goFinance', 'goPublishedProjects', 'goManageCoupon', 'goAftercare', 'goPayoutAccount', 'goBrandManage', 'goMessages']) page[method]();
  assert.deepEqual(requests, []); assert.deepEqual(navigation, []); assert.deepEqual(scans, []);
  assert.equal(toasts.length, 8); assert.ok(toasts.every(x => /店主/.test(x)));
});
test('CHECKIN allowed scan, verification records and bundle entry still work', () => {
  const { page, navigation, scans } = harness();
  page.goScanQR(); page.goVerifyRecords(); page._navigating = false; page.goBundleReservations();
  assert.equal(scans.length, 1);
  assert.ok(navigation.some(x => x.includes('/verify-records/')));
  assert.ok(navigation.some(x => x.includes('/bundle-reservations/')));
});
test('unresolved access is not a grant or a resolved permission lock', () => {
  const { page, requests, navigation, toasts } = harness();
  page.data.merchantAccess = normalizeMerchantAccess(null);
  page.goCustomers(); page.goFinance();
  assert.equal(requests.length, 0); assert.equal(navigation.length, 0);
  assert.ok(toasts.every(x => /确认|加载/.test(x) && !/联系店主/.test(x)));
});
test('revocation clears old revenue and stops its delayed animation; late old response cannot paint it', () => {
  const { page, requests } = harness('MERCHANT_OWNER');
  page.data.revenueShown = '999.00'; page.data.dashboardData = { revenue: '999.00', pendingOrders: 7 };
  page.loadDashboard(); const old = requests.at(-1);
  let stopped = 0; page._revenueCountStop = () => { stopped++; };
  page.data.merchantAccess = normalizeMerchantAccess({ active: true, merchant: { id: 31 }, roleCode: 'MERCHANT_CHECKIN', permissions: permissions.MERCHANT_CHECKIN });
  for (const key of ['loadMerchantInfo', 'loadUserInfo', 'loadMerchantGameEntries', 'loadUpcomingRuns', 'loadTodo', 'startInbox', 'loadEvents']) page[key] = () => {};
  page.loadMerchantConsole(page.data.merchantAccess);
  old.success({ code: 200, data: { revenue: 999, revenue7d: [], pendingOrders: 7 } });
  assert.equal(stopped, 1); assert.equal(page.data.revenueShown, '—');
  assert.equal(page.data.dashboardData.revenue, null); assert.deepEqual(page.data.projectCards, []);
});
for (const response of [{ code: 200, data: null }, { code: 500, data: null }]) test(`owner actual dashboard failure remains error: ${response.code}`, () => {
  const { page, requests } = harness('MERCHANT_OWNER'); page.loadDashboard(); requests.at(-1).success(response);
  assert.ok(page.data.dashboardError); assert.equal(page.data.dashboardData.revenue, null);
});
test('owner real zero remains zero only after a valid dashboard read', () => {
  const { page, requests } = harness('MERCHANT_OWNER'); page.countRevenueTo = value => page.setData({ revenueShown: Number(value).toFixed(2) });
  assert.equal(page.data.revenueShown, '—'); page.loadDashboard(); requests.at(-1).success({ code: 200, data: { revenue: 0, pendingOrders: 0, revenue7d: [] } });
  assert.equal(page.data.dashboardData.revenue, '0.00'); assert.equal(page.data.revenueShown, '0.00');
});
for (const role of Object.keys(permissions)) test(`${role}: every More menu slot remains, with resolved permission state`, () => {
  const { page } = harness(role), wxml = markup();
  for (const [action, flag] of Object.entries({ messages: 'canManageCoop', aftercare: 'canReadAftercare', verifyRecords: 'canReadVerifyRecords', bundleReservations: 'canVerify', payoutAccount: 'canReadFinance', coupon: 'canManageCoupons', brand: 'canWriteProfile' })) {
    const tag = wxml.match(new RegExp('<view[^>]*data-action="' + action + '"[^>]*>'))[0];
    assert.equal(visibleTag(tag, page.data), true, action);
    const expression = tag.match(/aria-disabled="\{\{(.*?)\}\}"/)[1];
    assert.equal(Function('data', 'with(data) { return !!(' + expression + '); }')(page.data), !page.data.merchantAccess[flag], action);
  }
});
test('CHECKIN normal console reads do not request restricted dashboard, profile, project or cooperation data', () => {
  const { page, requests } = harness();
  for (const key of ['loadUserInfo', 'loadMerchantGameEntries', 'loadUpcomingRuns']) page[key] = () => {};
  page.loadMerchantConsole(page.data.merchantAccess);
  const urls = requests.map(x => x.url);
  assert.ok(urls.includes('/api/merchant/todo-summary'));
  assert.ok(urls.includes('/api/im/conversations'));
  assert.ok(!urls.some(x => /dashboard|merchant\/info|registration\/merchant\/list|project\/my|chapter-application|\/coop\/list/.test(x)));
  assert.equal(page.data.dashboardData.revenue, null);
});
test('revoked inbox scopes clear cached owner events, and an old merge cannot restore them', () => {
  const { page } = harness('MERCHANT_OWNER');
  page.loadInboxConversations = page.loadInboxInvites = () => {};
  page.startInbox(1, page.data.merchantAccess);
  page.data.inboxItems = [{ key: 'event:income', content: 'private owner event' }];
  page._inboxUnread = page.data.inboxItems.slice();
  const next = normalizeMerchantAccess({ active: true, merchant: { id: 31 }, roleCode: 'MERCHANT_CHECKIN', permissions: permissions.MERCHANT_CHECKIN });
  page.startInbox(2, next);
  assert.deepEqual(page.data.inboxItems, []); assert.deepEqual(page._inboxUnread, []);
  page.settleInboxSource(1, 'events', [{ content: 'late owner event' }]);
  assert.deepEqual(page.data.inboxItems, []);
});
test('owner permitted finance, customer and project clicks preserve existing destinations', () => {
  const { page, navigation, requests } = harness('MERCHANT_OWNER');
  for (const method of ['goFinance', 'goCustomers', 'goPublishedProjects']) { page._navigating = false; page[method](); }
  assert.deepEqual(navigation, ['/subpackageA/pages/assetcenter/earnings/index?theme=merchant', '/pages/merchant/customer/index', '/subpackageA/pages/myproject/index?scope=MERCHANT']);
  assert.deepEqual(requests, []);
});
test('task rows use the actually registered house cell and its custom tap contract', () => {
  const config = JSON.parse(fs.readFileSync(pagePath.replace('.js', '.json'), 'utf8'));
  assert.equal(config.usingComponents['cy-cell'], '/components/cy/cell/index');
  for (const handler of ['goVerifyRecords', 'goBundleReservations', 'goAftercare']) assert.match(markup(), new RegExp('<cy-cell[^>]*bind:tap="' + handler + '"'));
});
for (const [method, flag] of Object.entries({ toggleBusiness: 'canWriteProfile', goAftercare: 'canReadAftercare', goBundleReservations: 'canVerify', goVerifyRecords: 'canReadVerifyRecords', goPayoutAccount: 'canReadFinance', goPredictInbox: 'canManageProjects' })) test(`${method}: resolved permission is checked once at entry; denial and navigation lock remain`, () => {
  const allowed = harness('MERCHANT_OWNER');
  let reads = 0, changes = 0;
  allowed.page.data.merchantAccess = new Proxy(allowed.page.data.merchantAccess, { get(target, key) { if (key === flag) reads++; return target[key]; } });
  allowed.page.data.businessStatus = 0;
  allowed.page._applyBusinessStatus = () => { changes++; };
  allowed.page[method]();
  assert.equal(reads, 1, 'The central entry helper is the only entry permission read');
  assert.equal(method === 'toggleBusiness' ? changes : allowed.navigation.length, 1);
  if (method !== 'toggleBusiness') {
    reads = 0; allowed.page[method]();
    assert.equal(reads, 1); assert.equal(allowed.navigation.length, 1, 'Navigation lock still blocks a second entry');
  }
  const denied = harness('MERCHANT_OWNER');
  denied.page.data.merchantAccess = { active: true, [flag]: false };
  denied.page._applyBusinessStatus = () => { throw Error('Denied entry must not write'); };
  denied.page[method]();
  assert.deepEqual(denied.navigation, []); assert.deepEqual(denied.requests, []);
  assert.ok(denied.toasts.some(x => /店主/.test(x)));
});
