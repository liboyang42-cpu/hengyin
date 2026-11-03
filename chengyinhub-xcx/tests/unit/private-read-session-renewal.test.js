'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { createSessionStore } = require('../../utils/session/session-store.js');
const { createSessionManager } = require('../../utils/session/session-manager.js');
const root = path.resolve(__dirname, '../..');
const pages = {
  stamp: 'subpackageP3/pages/stamp-album/index/index.js', object: 'subpackageP3/pages/object-cards/index/index.js',
  settings: 'pages/shezhi/shezhi.js', cancelled: 'subpackageMember/components/cy/scene-play-activity-detail/index.js',
  nearbyMy: 'subpackageRoam/nearby/index.js', roam: 'pages/roam/index.js', deregister: 'utils/deregister-flow.js',
};
function mount(kind, loginOK = true) {
  let app, def; const requests = [], storage = new Map();
  const wx = { request: r => { requests.push(r); return { abort() {} }; },
    getWindowInfo: () => ({ windowWidth: 390 }), getStorageSync: k => storage.get(k),
    setStorageSync: (k, v) => storage.set(k, v), removeStorageSync: k => storage.delete(k),
    showToast() {}, hideToast() {}, showLoading() {}, hideLoading() {}, navigateTo() {}, nextTick: f => f(),
  };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), {
    App: v => { app = v; }, wx, console,
    require: file => file === './utils/toast.js' ? Object.assign(() => {}, { hide() {} }) : require(path.join(root, file)),
  });
  const session = createSessionStore({ target: app.globalData, wx });
  app.getSession = () => session; app.isDevEnv = () => false; app.loadFeatureFlags = () => {};
  app.globalData.siteBaseUrl = 'http://127.0.0.1:8129'; session.setUserID('7'); session.setAuthorization('fixture-initial');
  app.getSessionManager = () => createSessionManager({ session, isDevEnv: () => false,
    wxLogin: async () => ({ code: 'fixture-code' }), exchangeCode: async () => loginOK
      ? { token: 'fixture-renewed', data: { id: '7', userType: 1, role: 'player' } } : { code: 401 },
  });
  global.getApp = () => app; global.wx = wx;
  const file = path.join(root, pages[kind]);
  if (kind === 'deregister') {
    delete require.cache[file]; const flow = require(file); def = { data: flow.initialData(), methods: flow.methods };
  } else vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Page: v => { def = v; }, Component: v => { def = v; },
    getApp: () => app, getCurrentPages: () => [], wx, require: createRequire(file), console, setTimeout, clearTimeout });
  const page = Object.assign({}, def, def.methods, { data: JSON.parse(JSON.stringify(def.data)),
    setData(v, cb) { Object.assign(this.data, v); if (cb) cb(); }, triggerEvent() {}, syncViewStateAndTheme() {} });
  page.data.activityId = 42;
  const load = () => kind === 'settings' ? page.getUserData() : kind === 'deregister' ? page.loadStatus()
    : kind === 'cancelled' ? page.load(42) : kind === 'nearbyMy' ? page.loadMyTeams(false)
      : kind === 'roam' ? page._loadEntryMap(page.data.center) : page.onLoad();
  const access = () => kind === 'settings' ? page.data.settingsAuthRequired : kind === 'deregister' ? page.data.status === 'LOGIN_REQUIRED'
    : kind === 'cancelled' ? page.data.errorMsg.includes('登录') : kind === 'nearbyMy' ? page.data.myReadAccess === 'login'
      : kind === 'roam' ? page.data.entryReadAccess === 'login' : page.data.readAccess === 'login';
  const body = () => kind === 'settings' ? { code: 200, data: { role: 'player', name: 'current' } }
    : kind === 'deregister' ? { code: 200, data: { status: 'PENDING', executeAfter: '' } }
      : kind === 'cancelled' ? { code: 500, errorCode: 'ACTIVITY_CANCELLED', canViewOwnOrders: true }
        : kind === 'nearbyMy' ? { code: 200, data: [] } : kind === 'roam' ? { code: 200, data: [] }
          : { code: 200, data: { total: 0, list: [] } };
  return { page, app, requests, load, access, body, session };
}
function reply(req, statusCode, data) { req.success({ statusCode, data }); req.complete({ data }); }
async function settled() { for (let i = 0; i < 40; i++) await Promise.resolve(); }
for (const kind of Object.keys(pages)) {
  test(kind + ': actual App/request-client successful 401 renewal reaches a current-session read', async () => {
    const h = mount(kind); h.load(); const originalCount = h.requests.length;
    reply(h.requests[0], 401, { code: 401 }); await settled();
    assert.equal(h.app.getAuthorization(), 'fixture-renewed');
    reply(h.requests[originalCount], 200, h.body()); await settled();
    // Fingerprinted pages deliberately discard the retry payload and issue a fresh read.
    for (const req of h.requests.slice(originalCount + 1)) reply(req, 200,
      kind === 'roam' && req.url.includes('/entry/nearby') ? { code: 200, data: { items: [] } } : h.body());
    if (kind === 'nearbyMy' || kind === 'roam') reply(h.requests[1], 200, h.body());
    await settled();
    assert.equal(h.access(), false);
    if (kind === 'settings') assert.equal(h.page.data.userInfo.name, 'current');
    else if (kind === 'deregister') assert.equal(h.page.data.status, 'PENDING');
    else if (kind === 'cancelled') assert.equal(h.page.data.state, 'cancelled');
    else if (kind === 'nearbyMy') assert.equal(h.page.data.myLoading, false);
    else if (kind === 'roam') assert.equal(h.page.data.entryLoadError, false);
    else { assert.equal(h.page.data.loading, false); assert.equal(h.page.data.loaded, true); }
  });
  test(kind + ': actual failed automatic login clears session and leaves an actionable read state', async () => {
    const h = mount(kind, false); h.load(); reply(h.requests[0], 401, { code: 401 }); await settled();
    assert.equal(String(h.app.getUserID() || ''), '');
    if (kind === 'cancelled') {
      // Public activity detail has its own anonymous read; an auth failure is explicit.
      reply(h.requests.at(-1), 401, { code: 401 }); await settled();
    }
    assert.equal(h.access(), true);
    if (kind === 'nearbyMy') assert.equal(h.page.data.myLoading, false);
    if (kind === 'stamp' || kind === 'object') assert.equal(h.page.data.loading, false);
  });
}

for (const ownOrder of [true, false, 'true', undefined]) {
  test('cancelled activity uses a typed private-safe outcome; own order action requires literal true: ' + ownOrder, () => {
    const h = mount('cancelled'); h.load();
    reply(h.requests[0], 200, { code: 500, errorCode: 'ACTIVITY_CANCELLED', canViewOwnOrders: ownOrder });
    assert.equal(h.page.data.state, 'cancelled'); assert.equal(h.page.data.cancelledHasOwnOrder, ownOrder === true);
    assert.equal(Object.keys(h.page.data.info).length, 0); assert.equal(h.page.data.tickets.length, 0);
    assert.equal(h.page.data.canConfirmSignup, false);
  });
}
test('ordinary activity failure mentioning cancellation cannot impersonate the typed cancelled state', () => {
  const h = mount('cancelled'); h.load(); reply(h.requests[0], 500, { code: 500, msg: '取消状态暂不可用' });
  assert.equal(h.page.data.state, 'error'); assert.equal(h.page.data.cancelledHasOwnOrder, false);
});
for (const kind of Object.keys(pages)) {
  test(kind + ': external same-user session replacement cannot restore the old response', async () => {
    const h = mount(kind); h.load(); const count = h.requests.length;
    h.session.setAuthorization('fixture-external-session'); reply(h.requests[0], 200, h.body()); await settled();
    assert.ok(h.requests.length > count, 'active page must read the current session rather than retain a stale loading state');
    if (kind === 'settings') assert.equal(Object.keys(h.page.data.userInfo).length, 0);
    else if (kind === 'deregister') assert.equal(h.page.data.status, 'LOADING');
    else if (kind === 'cancelled') assert.equal(h.page.data.cancelledHasOwnOrder, false);
    else if (kind === 'nearbyMy') assert.equal(h.page.data.myRows.length, 0);
    else if (kind === 'roam') assert.equal(h.page.data.entryCards.length, 0);
    else assert.equal(h.page.data.loaded, false);
  });
  test(kind + ': external session replacement during hide clears owned read state without issuing a new read', async () => {
    const h = mount(kind); h.load(); const count = h.requests.length;
    h.page._hidden = true; h.session.setAuthorization('fixture-external-session');
    reply(h.requests[0], 200, h.body()); await settled(); assert.equal(h.requests.length, count);
    if (kind === 'settings') assert.equal(Object.keys(h.page.data.userInfo).length, 0);
    else if (kind === 'cancelled') assert.equal(h.page.data.cancelledHasOwnOrder, false);
    else if (kind === 'nearbyMy') assert.equal(h.page.data.myLoading, false);
    else if (kind === 'stamp' || kind === 'object') assert.equal(h.page.data.loading, false);
  });
}
