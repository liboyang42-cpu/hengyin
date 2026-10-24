const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createSessionStore } = require('../../utils/session/session-store.js');
const { createSessionManager } = require('../../utils/session/session-manager.js');
const { retryLogin } = require('../../utils/session/login-reload.js');

function fixture(initial = true) {
  const root = path.resolve(__dirname, '../..');
  let app;
  vm.runInNewContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), {
    App: value => { app = value; }, wx: {}, console,
    require: file => file === './utils/toast.js' ? Object.assign(() => {}, { hide() {} }) : require(path.join(root, file)),
  }, { filename: 'app.js' });
  const storage = new Map();
  const session = createSessionStore({ target: app.globalData, wx: {
    setStorageSync: (key, value) => storage.set(key, value),
    getStorageSync: key => storage.get(key), removeStorageSync: key => storage.delete(key),
  } });
  app.getSession = () => session;
  app.isDevEnv = () => false;
  let features = 0;
  app.loadFeatureFlags = () => { features++; };
  if (initial) { session.setUserID('7'); session.setAuthorization('fixture-old'); }
  let finish;
  const manager = createSessionManager({ session, isDevEnv: () => false,
    wxLogin: async () => ({ code: 'fixture-code' }),
    exchangeCode: () => new Promise(resolve => { finish = resolve; }),
  });
  app.getSessionManager = () => manager;
  return { app, session, features: () => features,
    async ready() { for (let i = 0; i < 20 && !finish; i++) await Promise.resolve(); assert.equal(typeof finish, 'function'); },
    finish: body => finish(body),
  };
}
const accepted = { token: 'fixture-new', data: { id: '7', userType: 1, role: 'player', nickname: 'fixture' } };

test('real App reLogin waits for refresh and preserves callback plus the same final result', async () => {
  const f = fixture();
  let callback;
  const pending = f.app.reLogin((ok, result) => { callback = { ok, result }; });
  assert.equal(typeof pending.then, 'function');
  await f.ready(); assert.equal(callback, undefined); assert.equal(f.features(), 0);
  f.finish(accepted);
  const result = await pending;
  assert.equal(result.ok, true); assert.equal(callback.ok, true); assert.equal(callback.result, result);
  assert.equal(f.session.getAuthorization(), 'fixture-new'); assert.equal(f.features(), 1);
});

test('private read reload waits for the real App and actual session manager to replace the old token', async () => {
  const f = fixture(); const host = {}; let reloads = 0;
  const pending = retryLogin(host, f.app, () => { reloads++; assert.equal(f.session.getAuthorization(), 'fixture-new'); });
  await f.ready(); assert.equal(reloads, 0);
  f.finish(accepted); await pending;
  assert.equal(reloads, 1); assert.equal(host._loginPending, false);
});

test('real rejected refresh clears the old session and cannot reload a private read', async () => {
  const f = fixture(); const host = {}; let reloads = 0;
  const pending = retryLogin(host, f.app, () => { reloads++; });
  await f.ready(); f.finish({ code: 401, msg: 'fixture-denied' }); await pending;
  assert.equal(reloads, 0); assert.ok(!f.session.getUserID()); assert.equal(f.features(), 0);
});

test('real App refresh after account change returns failure without clearing the new account', async () => {
  const f = fixture(); let callback;
  const pending = f.app.reLogin((ok, result) => { callback = { ok, result }; });
  await f.ready(); f.session.setUserID('9'); f.session.setAuthorization('fixture-other');
  f.finish(accepted); const result = await pending;
  assert.equal(result.ok, false); assert.equal(result.reason, 'sessionChanged');
  assert.equal(callback.ok, false); assert.equal(callback.result, result);
  assert.equal(f.session.getUserID(), '9'); assert.equal(f.session.getAuthorization(), 'fixture-other');
  assert.equal(f.features(), 0);
});

for (const stopped of ['_unloaded', '_hidden', '_detached']) {
  test('real App refresh cannot reload a private caller after ' + stopped, async () => {
    const f = fixture(); const host = {}; let reloads = 0;
    const pending = retryLogin(host, f.app, () => { reloads++; });
    await f.ready(); host[stopped] = true; f.finish(accepted); await pending;
    assert.equal(reloads, 0); assert.equal(f.session.getAuthorization(), 'fixture-new');
  });
}

test('real firstLogin returns its explicit failure while initialization remains non-rejecting', async () => {
  const f = fixture(false); const pending = f.app.firstLogin();
  await f.ready(); f.finish({ msg: 'fixture-denied' });
  const result = await pending;
  assert.equal(result.ok, false); assert.equal(result.reason, 'noData');
  assert.ok(!f.session.getUserID()); assert.equal(f.features(), 0);
});

test('real guest firstLogin returns success only after the new account session is stored', async () => {
  const f = fixture(false); const host = {}; let reloads = 0;
  const pending = retryLogin(host, f.app, () => {
    reloads++; assert.equal(f.session.getUserID(), '7'); assert.equal(f.session.getAuthorization(), 'fixture-new');
  });
  await f.ready(); assert.equal(reloads, 0); assert.ok(!f.session.getUserID());
  f.finish(accepted); await pending;
  assert.equal(reloads, 1); assert.equal(f.features(), 1);
});
