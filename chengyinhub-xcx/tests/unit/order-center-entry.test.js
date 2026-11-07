'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { createRequireLogin } = require('../../utils/auth/require-login.js');
const { createSessionStore } = require('../../utils/session/session-store.js');
const root = path.resolve(__dirname, '../..');
const file = path.join(root, 'subpackageMember/orderinfo/orderinfo.js');
const entryFile = path.join(root, 'subpackageMember/components/cy/order-center-entry/index.js');
const number = 'WXP' + 'a'.repeat(29);
const longId = '9223372036854775807';
const tick = async () => { for (let n = 0; n < 15; n++) await Promise.resolve(); };
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
function mount({ guest = false, cold = false } = {}) {
  const requests = [], routes = [], modals = [], storage = new Map(), changes = [];
  const ready = deferred(), login = deferred();
  let page, entry, definition, entryDefinition, firstLogins = 0, reLogins = 0, readyCalls = 0;
  const wx = { getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key), navigateTo: options => routes.push(options),
    navigateBack: options => routes.push(options), redirectTo: options => routes.push(options) };
  const session = createSessionStore({ target: {}, wx });
  const signIn = (id = '7', token = 'fixture-session') => session.applySnapshot({ user_id: id, authorization: token });
  if (!guest) signIn();
  const app = { globalData: {}, isAppReady: !cold, getUserID: session.getUserID, getAuthorization: session.getAuthorization,
    getSession: () => session, sendRequest: request => requests.push(request),
    waitForAppReady() { readyCalls++; return cold ? ready.promise : Promise.resolve(); },
    firstLogin() { firstLogins++; return login.promise; }, reLogin() { reLogins++; return login.promise; } };
  const auth = createRequireLogin({ getApp: () => app, getPages: () => [page], show: options => modals.push(options), isSinglePage: () => false });
  const shellMarkup = fs.readFileSync(file.replace('.js', '.wxml'), 'utf8');
  const shellConfig = JSON.parse(fs.readFileSync(file.replace('.js', '.json'), 'utf8'));
  const selections = [], events = [];
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Page: value => { definition = value; },
    wx, getCurrentPages: () => [page], require: createRequire(file) });
  function mountEntry() {
    assert.match(shellMarkup, /<cy-order-center-entry wx:else id="orderCenterEntry" bind:back="backToOrderList"/);
    assert.equal(path.join(root, shellConfig.usingComponents['cy-order-center-entry']) + '.js', entryFile);
    vm.runInNewContext(fs.readFileSync(entryFile, 'utf8'), { Component: value => { entryDefinition = value; }, getApp: () => app,
      require: name => name.endsWith('/auth/require-login.js')
        ? { ...auth, hasSession: require('../../utils/auth/require-login.js').hasSession } : createRequire(entryFile)(name) });
    entry = { ...entryDefinition.methods, data: structuredClone(entryDefinition.data), setData(patch, callback) {
      changes.push(structuredClone(patch)); Object.assign(this.data, patch); if (callback) callback();
    }, triggerEvent(name, detail) {
      events.push({ name, detail });
      if (name === 'back') page.backToOrderList({ detail });
    } };
    entryDefinition.lifetimes.attached.call(entry);
  }
  page = { ...definition, data: structuredClone(definition.data), setData(patch, callback) {
    changes.push(structuredClone(patch)); Object.assign(this.data, patch);
    if (this.data.orderCenter && !entry) mountEntry();
    if (callback) callback();
  }, selectComponent(selector) { selections.push(selector); return selector === '#orderCenterEntry' ? entry : null; } };
  const show = () => { if (entry) entryDefinition.pageLifetimes.show.call(entry); };
  const hide = () => { if (entry) entryDefinition.pageLifetimes.hide.call(entry); };
  const unload = () => { page.onUnload(); if (entry) entryDefinition.lifetimes.detached.call(entry); };
  const load = async (options = { outTradeNo: number }) => { page.onLoad(options); show(); page.onReady(); await tick(); };
  const respond = (request = requests.at(-1), kind = 'CLM', extra = {}) => request.success({ code: 200,
    data: { kind, id: longId, orderNo: 'fixture-order', title: '订单商品', statusText: '待确认', amountText: '3.40', ...extra } });
  return { page, get entry() { return entry; }, show, hide, unload, selections, events, app, requests, routes, modals, storage, session, changes, signIn, ready, login, load, respond,
    counts: () => ({ firstLogins, reLogins, readyCalls }) };
}

for (const kind of ['REGISTRATION', 'CLM', 'COMMERCE', 'DEP']) test(kind + ' uses exact v1 request and only authorized returned fields', async () => {
  const h = mount(); await h.load(); assert.equal(h.requests.length, 1);
  const req = h.requests[0];
  assert.equal(req.url, '/api/order-center/detail'); assert.equal(req.method, 'POST'); assert.equal(req.write, false);
  assert.equal(req.header, undefined, 'existing transport provides form encoding');
  assert.deepEqual(JSON.parse(JSON.stringify(req.data)), { outTradeNo: number });
  h.respond(req, kind, { createdAtText: '2026-11-06 08:00', openid: 'discarded-fixture', url: 'https://invalid.example' });
  assert.equal(h.entry.data.entryState, 'ready');
  if (kind === 'REGISTRATION') { assert.equal(h.entry.data.id, longId); assert.equal(h.entry.data.summary, null); }
  else { assert.equal(h.entry.data.id, ''); assert.equal(h.entry.data.summary.kind, kind); assert.equal(h.entry.data.summary.amountText, '3.40');
    assert.equal(h.entry.data.summary.url, undefined); assert.equal(h.entry.data.summary.openid, undefined); }
  assert.equal(h.routes.length, 0); assert.equal(h.requests.length, 1, 'no status/read/provider mutation requests');
  assert.equal(JSON.stringify(h.entry.data).includes(number), false);
  assert.equal(JSON.stringify([...h.storage.values()]).includes(number), false);
});

test('legacy id and existing navigation preserve exact value without readiness/resolution', async () => {
  for (const options of [{ id: longId }, { id: 'legacy-unvalidated' }, {}]) {
    const h = mount({ cold: true }); await h.load(options);
    assert.equal(h.page.data.id, options.id || ''); assert.equal(h.page.data.orderCenter, false);
    h.hide(); h.show(); assert.equal(h.page.data.id, options.id || '');
    assert.equal(h.requests.length, 0); assert.equal(h.counts().readyCalls, 0);
    h.page.backToOrderList({ detail: { navigating: true } }); assert.equal(h.routes.length, 0);
    h.page.backToOrderList(); assert.equal(h.routes[0].url, '/subpackageMember/order/order');
  }
});
test('cold readiness prevents authenticated API reads and resolves once', async () => {
  const h = mount({ cold: true }); await h.load(); assert.equal(h.requests.length, 0);
  assert.equal(h.counts().readyCalls, 1); h.show(); assert.equal(h.requests.length, 0);
  h.app.isAppReady = true; h.ready.resolve(); await tick(); assert.equal(h.requests.length, 1);
});
test('hidden initialization through the page bridge waits for show and reads exactly once', async () => {
  const h = mount(); h.page.onLoad({ outTradeNo: number });
  assert.equal(h.entry._attached, true); assert.equal(h.entry._initialized, undefined);
  h.hide(); h.page.onReady(); await tick();
  assert.deepEqual(h.selections, ['#orderCenterEntry']); assert.equal(h.entry._initialized, true);
  assert.equal(h.requests.length, 0); assert.equal(h.counts().readyCalls, 0);
  h.show(); await tick();
  assert.equal(h.requests.length, 1); assert.equal(h.counts().readyCalls, 1);
  assert.equal(h.requests[0].url, '/api/order-center/detail');
  assert.equal(h.requests[0].data.outTradeNo, number);
  h.show(); await tick(); assert.equal(h.requests.length, 1);
});
test('readiness failure is retryable and unloaded readiness cannot request', async () => {
  const h = mount({ cold: true }); await h.load(); h.ready.reject(Error('fixture')); await tick();
  assert.equal(h.entry.data.entryAction, '重试'); assert.equal(h.requests.length, 0);
  h.app.waitForAppReady = () => { h.app.isAppReady = true; return Promise.resolve(); };
  h.entry.onEntryAction(); await tick(); assert.equal(h.requests.length, 1);
  const gone = mount({ cold: true }); await gone.load(); gone.unload(); gone.app.isAppReady = true;
  gone.ready.resolve(); await tick(); assert.equal(gone.requests.length, 0);
});
test('guest waits for readiness, then explicit login cancellation keeps entry without requests', async () => {
  const h = mount({ guest: true, cold: true }); await h.load(); assert.equal(h.modals.length, 0); assert.equal(h.requests.length, 0);
  h.app.isAppReady = true; h.ready.resolve(); await tick(); assert.equal(h.entry.data.entryAction, '登录');
  const pending = h.entry.onEntryAction(); assert.equal(h.modals.length, 1);
  h.modals[0].success({ confirm: false }); await pending;
  assert.equal(h.counts().firstLogins, 0); assert.equal(h.requests.length, 0);
  assert.equal(h.entry.data.entryAction, '登录');
});
for (const result of ['success', 'failed', 'rejected', 'unload']) test('explicit login continuation ' + result, async () => {
  const h = mount({ guest: true }); await h.load(); const pending = h.entry.onEntryAction();
  h.modals[0].success({ confirm: true }); await tick(); assert.equal(h.counts().firstLogins, 1);
  if (result === 'unload') h.unload();
  if (result === 'success' || result === 'unload') { h.signIn(); h.login.resolve({ ok: true }); }
  else if (result === 'failed') h.login.resolve({ ok: false }); else h.login.reject(Error('fixture'));
  await pending; await tick(); assert.equal(h.requests.length, result === 'success' ? 1 : 0);
  if (result === 'success') assert.equal(h.requests[0].data.outTradeNo, number);
});
test('account switch during login prompt cannot continue old entry', async () => {
  const h = mount({ guest: true }); await h.load(); const pending = h.entry.onEntryAction();
  h.signIn('8'); h.modals[0].success({ confirm: true }); await pending;
  assert.equal(h.requests.length, 0); assert.equal(h.counts().firstLogins, 0);
});
test('authenticated 401 uses existing explicit re-login helper and reloads exact entry', async () => {
  const h = mount(); await h.load(); h.requests[0].fail({ code: 401 });
  assert.equal(h.entry.data.entryAction, '登录'); const pending = h.entry.onEntryAction();
  assert.equal(h.counts().reLogins, 1); h.signIn('7', 'fixture-renewed'); h.login.resolve({ ok: true }); await pending;
  assert.equal(h.requests.length, 2); assert.equal(h.requests[1].data.outTradeNo, number);
});

for (const switchType of ['account', 'same-account-revision', 'logout']) test('private context clears before fetch after ' + switchType, async () => {
  const h = mount(); await h.load(); h.respond(); assert.ok(h.entry.data.summary);
  h.hide(); assert.equal(h.entry.data.summary, null); assert.equal(h.entry.data.id, '');
  if (switchType === 'account') h.signIn('8');
  else if (switchType === 'logout') h.session.clearSession(); else h.signIn();
  h.show(); await tick();
  assert.equal(h.entry.data.summary, null); assert.equal(h.entry.data.id, '');
  assert.equal(h.requests.length, switchType === 'logout' ? 1 : 2);
  if (switchType === 'logout') assert.equal(h.entry.data.entryAction, '登录');
});
test('long registration id is removed on hide and re-resolved rather than reusing stale component', async () => {
  const h = mount(); await h.load(); h.respond(h.requests[0], 'REGISTRATION'); assert.equal(h.entry.data.id, longId);
  h.hide(); assert.equal(h.entry.data.id, ''); h.signIn('8'); h.show(); await tick();
  h.requests[1].fail({ code: 403 }); assert.equal(h.entry.data.id, ''); assert.equal(h.entry.data.summary, null);
});
test('foreground lifecycle detects same-token session revision before reading again', async () => {
  const h = mount(); await h.load(); h.respond(); h.signIn(); h.show();
  assert.equal(h.entry.data.summary, null); assert.equal(h.entry.data.id, ''); assert.equal(h.requests.length, 2);
});
test('actual returned registration string binds existing detail component and its real read request', async () => {
  const h = mount(); await h.load(); h.respond(h.requests[0], 'REGISTRATION');
  const config = JSON.parse(fs.readFileSync(entryFile.replace('.js', '.json'), 'utf8'));
  const componentFile = path.join(root, config.usingComponents['cy-scene-member-order-detail']) + '.js';
  assert.equal(componentFile, path.join(root, 'subpackageMember/components/cy/scene-member-order-detail/index.js'));
  let definition;
  vm.runInNewContext(fs.readFileSync(componentFile, 'utf8'), { Component: value => { definition = value; },
    getApp: () => h.app, wx: {}, require: createRequire(componentFile), setTimeout, clearTimeout });
  const markup = fs.readFileSync(entryFile.replace('.js', '.wxml'), 'utf8');
  const binding = markup.match(/id="orderDetail" order-id="\{\{(\w+)\}\}"/)[1];
  const detail = { ...definition, ...definition.methods, data: { ...structuredClone(definition.data), orderId: h.entry.data[binding] },
    setData(patch) { Object.assign(this.data, patch); } };
  detail.lifetimes.attached.call(detail);
  assert.equal(h.requests.length, 2); assert.equal(h.requests[1].url, '/api/registration/info');
  assert.equal(h.requests[1].data.id, longId); assert.equal(typeof h.requests[1].data.id, 'string');
  detail.lifetimes.detached.call(detail);
});
for (const type of ['account', 'revision']) test('late successful reply from old ' + type + ' is ignored and current session is re-read', async () => {
  const h = mount(); await h.load();
  h.signIn(type === 'account' ? '8' : '7'); h.respond(h.requests[0]);
  assert.equal(h.entry.data.summary, null); assert.equal(h.requests.length, 2);
  h.respond(h.requests[1], 'COMMERCE'); assert.equal(h.entry.data.summary.kind, 'COMMERCE');
  h.respond(h.requests[0], 'CLM'); assert.equal(h.entry.data.summary.kind, 'COMMERCE');
});
test('retry generation rejects out-of-order success/failure and unload invalidates callbacks', async () => {
  const h = mount(); await h.load(); h.requests[0].fail({}); assert.equal(h.entry.data.entryAction, '重试');
  h.entry.onEntryAction(); assert.equal(h.requests.length, 2); h.respond(h.requests[1], 'DEP');
  h.respond(h.requests[0], 'CLM'); h.requests[0].fail({ code: 403 }); assert.equal(h.entry.data.summary.kind, 'DEP');
  h.hide(); h.show(); const pending = h.requests.at(-1); const before = h.changes.length;
  h.unload(); h.respond(pending); pending.fail({}); assert.equal(h.changes.length, before);
});
test('hidden replies do not request or restore data; returning reads once', async () => {
  const h = mount(); await h.load(); h.hide(); h.signIn('8'); h.respond(h.requests[0]);
  assert.equal(h.requests.length, 1); assert.equal(h.entry.data.summary, null);
  h.show(); assert.equal(h.requests.length, 2);
});
for (const [code, state, action] of [[400, 'missing-param', '返回'], [401, 'no-permission', '登录'],
  [403, 'forbidden', '返回'], [404, 'not-found', '重试'], [502, 'network', '重试']]) {
  for (const callback of ['success', 'fail', 'successStatusAbnormal']) test(callback + ' ' + code + ' yields distinct actionable state', async () => {
    const h = mount(); await h.load(); h.requests[0][callback]({ code }, callback === 'success' ? undefined : code);
    assert.equal(h.entry.data.entryState, state); assert.equal(h.entry.data.entryAction, action);
    assert.equal(h.entry.data.id, ''); assert.equal(h.entry.data.summary, null);
  });
}
for (const invalid of ['', null, 123, ['WXP1'], 'x', 'abcde', 'x'.repeat(33), ' bad', 'a/b', 'a?b', 'a%b', 'a&b', 'a\nb', '订单']) {
  test('invalid provider entry shape fails closed ' + JSON.stringify(invalid), async () => {
    const h = mount(); await h.load({ outTradeNo: invalid, id: '7' });
    assert.equal(h.requests.length, 0); assert.equal(h.entry.data.id, ''); assert.equal(h.entry.data.entryState, 'missing-param');
    h.entry.onEntryAction(); assert.equal(h.routes[0].url, '/subpackageMember/order/order');
  });
}
test('valid provider characters and boundary length are transmitted without normalization', async () => {
  const h = mount(); const providerNumber = 'A_|*-0'; await h.load({ outTradeNo: providerNumber });
  assert.equal(h.requests.length, 1); assert.equal(h.requests[0].data.outTradeNo, providerNumber);
});
for (const invalid of [{ id: 9007199254740992 }, { id: '01' }, { id: '0' }, { id: '9223372036854775808' },
  { kind: 'UNKNOWN' }, { amountText: 3.4 }, { amountText: '-3' }, { amountText: '3.400' }, { title: null }, { statusText: '' }]) {
  test('invalid response fields cannot mount detail ' + JSON.stringify(invalid), async () => {
    const h = mount(); await h.load(); h.respond(h.requests[0], 'REGISTRATION', invalid);
    assert.equal(h.entry.data.id, ''); assert.equal(h.entry.data.summary, null); assert.equal(h.entry.data.entryAction, '重试');
  });
}
for (const fields of [{ inviteId: longId, inviteBox: 'sent' }, { inviteId: longId, inviteBox: 'received' },
  { inviteId: '1/../../', inviteBox: 'sent' }, { inviteId: longId, inviteBox: 'sent&x=1' },
  { inviteId: 9007199254740992, inviteBox: 'sent' }, { inviteId: longId }, { url: 'https://invalid.example' }]) {
  test('unused invitation and arbitrary URL fields are discarded without navigation ' + JSON.stringify(fields), async () => {
    const h = mount(); await h.load(); h.respond(h.requests[0], 'DEP', fields);
    assert.equal(h.entry.data.summary.inviteId, undefined); assert.equal(h.entry.data.summary.inviteBox, undefined);
    assert.equal(h.entry.data.summary.url, undefined); assert.equal(h.entry.openInvitation, undefined);
    assert.equal(h.routes.length, 0); assert.equal(h.requests.length, 1);
  });
}
test('template binds real detail id and existing contact/empty/card without invitation or URL controls', () => {
  const markup = fs.readFileSync(entryFile.replace('.js', '.wxml'), 'utf8');
  const config = JSON.parse(fs.readFileSync(entryFile.replace('.js', '.json'), 'utf8'));
  assert.match(markup, /wx:if="\{\{id\}\}" id="orderDetail" order-id="\{\{id\}\}"/);
  assert.match(markup, /bind:cta="onEntryAction"/); assert.doesNotMatch(markup, /openInvitation|inviteId|inviteBox/);
  assert.match(markup, /open-type="contact"/); assert.doesNotMatch(markup, /send-message-path=.*outTradeNo|web-view|requestPayment/);
  for (const tag of ['cy-empty', 'cy-card', 'cy-scene-member-order-detail']) assert.ok(config.usingComponents[tag]);
});

test('shell mounts and initializes the registered entry only onReady without rendering the raw attempt', async () => {
  const h = mount({ cold: true });
  h.page.onLoad({ outTradeNo: number, id: 'legacy-must-not-mount' });
  assert.ok(h.entry); assert.equal(h.entry._attached, true);
  assert.equal(h.entry._initialized, undefined); assert.equal(h.requests.length, 0);
  assert.equal(h.counts().readyCalls, 0); assert.deepEqual(h.selections, []);
  assert.equal(h.page._outTradeNo, number);
  assert.equal(JSON.stringify(h.page.data).includes(number), false);
  assert.equal(JSON.stringify(h.entry.data).includes(number), false);
  h.page.onReady(); await tick();
  assert.deepEqual(h.selections, ['#orderCenterEntry']); assert.equal(h.entry._outTradeNo, number);
  assert.equal(h.counts().readyCalls, 1);
  h.unload(); assert.equal(h.page._outTradeNo, null); assert.equal(h.entry._outTradeNo, null);
  assert.equal(h.entry._attached, false);
});
test('legacy shell loads without getApp and never mounts or initializes the order-center component', async () => {
  const h = mount({ cold: true }); await h.load({ id: longId });
  assert.equal(h.entry, undefined); assert.deepEqual(h.selections, []);
  assert.equal(h.page.data.id, longId); assert.equal(h.counts().readyCalls, 0);
  const markup = fs.readFileSync(file.replace('.js', '.wxml'), 'utf8');
  assert.match(markup, /<cy-scene-member-order-detail wx:if="\{\{!orderCenter\}\}" id="orderDetail" order-id="\{\{id\}\}" bind:back="backToOrderList" bind:close="backToOrderList"/);
  assert.doesNotMatch(fs.readFileSync(file, 'utf8'), /getApp\(|sendRequest|\/api\/|waitForAppReady|require-login/);
});
test('component forwards detail back and close payload without losing navigating or changing page navigation', async () => {
  const h = mount(); await h.load();
  const markup = fs.readFileSync(entryFile.replace('.js', '.wxml'), 'utf8');
  assert.match(markup, /bind:back="backToOrderList" bind:close="backToOrderList"/);
  const detail = { navigating: true, fixture: 'unchanged' };
  h.entry.backToOrderList({ detail });
  assert.equal(h.events[0].name, 'back'); assert.equal(h.events[0].detail, detail);
  assert.equal(h.routes.length, 0);
  h.entry.backToOrderList(); assert.equal(h.routes[0].url, '/subpackageMember/order/order');
});
test('hide invalidates pending guest login continuation even after the component is shown again', async () => {
  const h = mount({ guest: true }); await h.load(); const pending = h.entry.onEntryAction();
  h.modals[0].success({ confirm: true }); await tick();
  h.hide(); h.show(); h.signIn(); h.login.resolve({ ok: true }); await pending;
  assert.equal(h.requests.length, 0);
});
test('summary amount uses the existing resolvable money WXS and keeps exact decimal text', async () => {
  const h = mount(); await h.load(); h.respond();
  const markup = fs.readFileSync(entryFile.replace('.js', '.wxml'), 'utf8');
  assert.match(markup, /\{\{money\.amount\(summary\.amountText\)\}\}/);
  const source = markup.match(/<wxs src="([^"]+)" module="money"/)[1];
  const moneyFile = path.resolve(path.dirname(entryFile), source);
  const context = { module: { exports: {} } };
  vm.runInNewContext(fs.readFileSync(moneyFile, 'utf8'), context);
  assert.equal(context.module.exports.amount(h.entry.data.summary.amountText), '¥3.40');
  assert.equal(context.module.exports.amount('0.00'), '¥0.00');
  assert.equal(context.module.exports.amount(null), '—');
});

test('detach clears rendered order and private session context without setData or late restoration', async () => {
  for (const kind of ['REGISTRATION', 'CLM']) {
    const h = mount(); await h.load(); const request = h.requests[0]; h.respond(request, kind);
    assert.ok(h.entry._entryReader); assert.notEqual(h.entry._entryRevision, null);
    assert.ok(h.entry.data.id || h.entry.data.summary);
    const before = h.changes.length;
    h.unload();
    assert.equal(h.entry._entryReader, null); assert.equal(h.entry._entryRevision, null);
    assert.equal(h.entry.data.id, ''); assert.equal(h.entry.data.summary, null);
    assert.equal(h.changes.length, before);
    h.respond(request, kind); request.fail({ code: 403 }); request.successStatusAbnormal({ code: 401 });
    assert.equal(h.entry.data.id, ''); assert.equal(h.entry.data.summary, null);
    assert.equal(h.changes.length, before); assert.equal(h.requests.length, 1);
  }
});
