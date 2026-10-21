const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../pages/activity/list/index.js');
const realRequire = createRequire(file);
function mount(owner = '', token = '') {
  let definition;
  const requests = [], logins = [];
  const app = { globalData: { statusBarHeight: 20, navBarHeight: 44 }, getUserID: () => owner,
    getAuthorization: () => token, sendRequest: req => requests.push(req),
    getRequestErrorMessage: (res, fallback) => res && res.msg || fallback,
    firstLogin: () => { logins.push('first'); return new Promise(resolve => { app.finishLogin = resolve; }); },
    reLogin: () => { logins.push('renew'); return new Promise(resolve => { app.finishLogin = resolve; }); }
  };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    getApp: () => app, Page: value => { definition = value; }, require: realRequire,
    wx: { pageScrollTo() {}, navigateTo() {} }
  }, { filename: file });
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
  page.setData = patch => Object.assign(page.data, patch);
  return { page, app, requests, logins, session(id, auth) { owner = id; token = auth; },
    last(url) { return requests.filter(req => req.url === url).at(-1); },
    start() { page.onLoad(); page.onShow(); },
    tab(index) { page.onTab({ detail: { key: String(index) } }); } };
}
const tick = () => new Promise(resolve => setImmediate(resolve));
const row = (id, status = 2) => ({ id, status, title: '活动' + id, city: '上海' });
const publicRows = h => h.last('/api/official/events').success({ code: 200, data: [row(1), row(2, 1), row(3, 5)] });
const myRows = (req, id = 11) => req.success({ code: 200, data: [row(id)] });

for (const [owner, token] of [['', ''], ['7', ''], ['', 'token']]) {
  test('public events stay anonymous while private sources require both identity parts ' + JSON.stringify([owner, token]), () => {
    const h = mount(owner, token); h.start();
    assert.equal(h.requests.length, 1); assert.equal(h.requests[0].url, '/api/official/events');
    publicRows(h);
    for (const [tab, id] of [[0, 1], [1, 2], [2, 3]]) { h.tab(tab); assert.equal(h.page.data.events[0].id, id); }
    h.tab(3); assert.equal(h.page.data.mineAuthRequired, true); assert.equal(h.page.data.events.length, 0);
    assert.equal(h.page.data.canPublish, false); assert.equal(h.requests.length, 1);
  });
}

test('my guest tab shows login even while public list is pending or failed', () => {
  const h = mount(); h.start(); h.tab(3);
  assert.equal(h.page.data.mineAuthRequired, true); assert.equal(h.page.data.loading, false);
  h.requests[0].fail({ msg: 'public offline' });
  assert.equal(h.page.data.mineAuthRequired, true); assert.equal(h.page.data.mineAccessDenied, false);
});

test('explicit login single flight recovers only private sources and preserves anonymous public rows', async () => {
  const h = mount(); h.start(); publicRows(h); h.tab(3); h.page.onRetry(); h.page.onRetry();
  assert.deepEqual(h.logins, ['first']); h.session('7', 'token'); h.app.finishLogin(); await tick();
  assert.equal(h.requests.filter(req => req.url === '/api/official/events').length, 1);
  assert.equal(h.requests.filter(req => req.url === '/api/official/my-events').length, 1);
  assert.equal(h.requests.filter(req => req.url === '/api/official/can-publish').length, 1);
  myRows(h.last('/api/official/my-events')); assert.equal(h.page.data.events[0].id, 11);
  h.tab(0); assert.equal(h.page.data.events[0].id, 1);
});

test('cancelled or rejected guest login stays retryable without private reads', async () => {
  for (const rejected of [false, true]) {
    const h = mount(); h.start(); h.tab(3);
    if (rejected) h.app.firstLogin = () => Promise.reject(new Error('declined'));
    h.page.onRetry(); if (!rejected) h.app.finishLogin({ ok: false }); await tick();
    assert.equal(h.page.data.mineAuthRequired, true); assert.equal(h.requests.length, 1);
    h.page.onRetry(); assert.equal(h.page._loginPending, true); await tick();
  }
});

for (const transport of ['business', 'http', 'fail']) {
  for (const code of [401, 403, 500]) {
    test('my-events ' + transport + code + ' has separate auth/permission/load states and leaves public usable', () => {
      const h = mount('7', 'token'); h.start(); publicRows(h); h.tab(3);
      const req = h.last('/api/official/my-events');
      if (transport === 'business') req.success({ code });
      else req[transport === 'http' ? 'successStatusAbnormal' : 'fail']({ code: 500 }, code);
      assert.equal(h.page.data.mineAuthRequired, code === 401);
      assert.equal(h.page.data.mineAccessDenied, code === 403);
      assert.equal(h.page.data.curError, true); assert.equal(h.page.data.loading, false);
      h.tab(0); assert.equal(h.page.data.events[0].id, 1); assert.equal(h.page.data.curError, false);
    });
  }
}

test('businesscode2 remains a login prompt until explicit same-owner renewal', async () => {
  const h = mount('7', 'expired'); h.start(); publicRows(h); h.tab(3);
  h.last('/api/official/my-events').success({ code: 2 });
  h.tab(0); h.tab(3); assert.equal(h.page.data.mineAuthRequired, true);
  assert.equal(h.requests.filter(req => req.url === '/api/official/my-events').length, 1);
  h.page.onRetry(); h.page.onRetry(); assert.deepEqual(h.logins, ['renew']);
  h.session('7', 'new'); h.app.finishLogin(); await tick();
  assert.equal(h.requests.filter(req => req.url === '/api/official/my-events').length, 2);
});

for (const transition of ['hide', 'hide-return', 'unload', 'owner', 'token']) {
  test('old private list and publish permission callbacks cannot refill after ' + transition, () => {
    const h = mount('7', 'token'); h.start();
    const mine = h.last('/api/official/my-events'), permission = h.last('/api/official/can-publish');
    if (transition === 'hide' || transition === 'hide-return') { h.page.onHide(); if (transition === 'hide-return') h.page.onShow(); }
    else if (transition === 'unload') h.page.onUnload();
    else h.session(transition === 'owner' ? '8' : '7', transition === 'token' ? 'new' : 'token');
    const before = JSON.stringify(h.page.data); myRows(mine, 99); permission.success({ code: 200, data: { canPublish: true } });
    mine.fail({ code: 401 }, 401); assert.equal(JSON.stringify(h.page.data), before);
  });
}

test('logout clears mine and publish permission while public rows remain available', () => {
  const h = mount('7', 'token'); h.start(); publicRows(h); myRows(h.last('/api/official/my-events'));
  h.last('/api/official/can-publish').success({ code: 200, data: { canPublish: true } }); h.tab(3);
  h.session('', ''); h.page.onShow();
  assert.equal(h.page.data.events.length, 0); assert.equal(h.page._mine.length, 0); assert.equal(h.page.data.canPublish, false);
  assert.equal(h.page.data.mineAuthRequired, true); h.tab(0); assert.equal(h.page.data.events[0].id, 1);
});

for (const transition of ['hide-return', 'unload']) {
  test('late login cannot reload after ' + transition, async () => {
    const h = mount(); h.start(); h.tab(3); h.page.onRetry();
    if (transition === 'unload') h.page.onUnload(); else { h.page.onHide(); h.page.onShow(); }
    const before = h.requests.length; h.session('7', 'late'); h.app.finishLogin(); await tick();
    assert.equal(h.requests.length, before);
  });
}

test('bootstrap waits once without forcing login or denying public browsing', async () => {
  const h = mount(); let ready; let waits = 0; h.app.isAppReady = false;
  h.app.waitForAppReady = () => { waits++; return new Promise(resolve => { ready = resolve; }); };
  h.start(); publicRows(h); h.tab(3); assert.equal(waits, 1); assert.equal(h.logins.length, 0);
  h.session('7', 'ready'); ready(); await tick(); assert.ok(h.last('/api/official/my-events'));
  assert.equal(h.requests.filter(req => req.url === '/api/official/events').length, 1);
});

test('old public response after hide cannot overwrite a newer visible public list', () => {
  const h = mount(); h.start(); const old = h.requests[0]; h.page.onHide(); h.page.onShow();
  publicRows(h); old.success({ code: 200, data: [row(99)] }); assert.equal(h.page.data.events[0].id, 1);
});

test('public and mine error reasons stay with their original tab and retry only that source', () => {
  const h = mount('7', 'token'); h.start();
  h.last('/api/official/events').fail({ msg: 'public failed' }); h.last('/api/official/my-events').fail({ msg: 'mine failed' });
  assert.equal(h.page.data.errorMsg, 'public failed'); h.tab(3);
  assert.equal(h.page.data.errorMsg, 'mine failed'); const before = h.requests.filter(req => req.url === '/api/official/events').length;
  h.page.onRetry(); assert.equal(h.requests.filter(req => req.url === '/api/official/events').length, before);
});

test('publisher403 does not deny a normal participant private list', () => {
  const h = mount('7', 'token'); h.start();
  h.last('/api/official/can-publish').successStatusAbnormal({ code: 500 }, 403);
  myRows(h.last('/api/official/my-events')); h.tab(3);
  assert.equal(h.page.data.mineAccessDenied, false); assert.equal(h.page.data.events[0].id, 11); assert.equal(h.page.data.canPublish, false);
});

test('guest/permission messages apply only to mine and precede load-failure blank branch', () => {
  const wxml = fs.readFileSync(file.replace(/\.js$/, '.wxml'), 'utf8');
  assert.match(wxml, /tab === 3 && mineAuthRequired[\s\S]*?title="登录后查看我的活动"[\s\S]*?bind:cta="onRetry"/);
  assert.match(wxml, /tab === 3 && mineAccessDenied[\s\S]*?kind="no-permission"/);
  assert.match(wxml, /<cy-error load-failure/);
});


test('same-owner private refresh failure preserves own last successful cards,403 clears them', () => {
  const h = mount('7', 'token'); h.start(); myRows(h.last('/api/official/my-events'), 71); h.tab(3);
  h.page.fetchMine(); h.last('/api/official/my-events').fail({ msg: 'offline' });
  assert.equal(h.page.data.events[0].id, 71); assert.equal(h.page.data.mineAuthRequired, false);
  h.page.fetchMine(); h.last('/api/official/my-events').fail({ code: 500 }, 403);
  assert.equal(h.page.data.events.length, 0); assert.equal(h.page.data.mineAccessDenied, true);
});

test('sessionChanged re-reads the new private owner but never refills old-owner cards', () => {
  const h = mount('7', 'old'); h.start(); publicRows(h); const old = h.last('/api/official/my-events');
  h.session('8', 'new'); old.fail({ reason: 'sessionChanged' });
  assert.equal(h.requests.filter(req => req.url === '/api/official/my-events').length, 2);
  myRows(h.last('/api/official/my-events'), 82); myRows(old, 81); h.tab(3);
  assert.equal(h.page.data.events[0].id, 82);
});

test('bootstrap completion after hide and return cannot start private reads', async () => {
  const h = mount(); let ready; h.app.isAppReady = false;
  h.app.waitForAppReady = () => new Promise(resolve => { ready = resolve; });
  h.start(); h.page.onHide(); h.page.onShow(); const before = h.requests.length;
  h.session('7', 'late'); ready(); await tick(); assert.equal(h.requests.length, before);
});

test('public retry replacement ignores the earlier public failure and success', () => {
  const h = mount(); h.start(); const old = h.last('/api/official/events');
  h.page.fetchAll(); publicRows(h); old.fail({ msg: 'late failure' }); old.success({ code: 200, data: [row(91)] });
  assert.equal(h.page.data.events[0].id, 1); assert.equal(h.page.data.curError, false);
});

test('public refresh interrupted after an earlier success restarts on show and settles only new request', () => {
  const h = mount(); h.start(); publicRows(h); h.page.fetchAll(); const old = h.last('/api/official/events');
  h.page.onHide(); h.page.onShow(); const current = h.last('/api/official/events');
  assert.notEqual(current, old); assert.equal(h.page._publicLoading, true);
  old.fail({ msg: 'stale refresh failure' }); assert.equal(h.page._publicLoading, true);
  current.success({ code: 200, data: [row(92)] });
  assert.equal(h.page._publicLoading, false); assert.equal(h.page.data.loading, false);
  assert.equal(h.page.data.events[0].id, 92); assert.equal(h.page.data.curError, false);
});


test('private read bookkeeping stays off the render bridge while current public and mine states remain visible', () => {
  const internal = ['_all', '_mine', 'mineLoading', 'mineError', 'mineErrorMsg', 'publicLoading', 'loadError', 'loadErrorMsg'];
  const h = mount('7', 'token'), writes = [];
  const render = h.page.setData;
  h.page.setData = patch => { writes.push(Object.keys(patch)); render(patch); };
  h.start();
  assert.equal(h.page.data.loading, true);
  h.last('/api/official/events').fail({ msg: 'public offline' });
  assert.equal(h.page.data.curError, true); assert.equal(h.page.data.errorMsg, 'public offline');
  assert.equal(h.page.data.loading, false);
  h.tab(3); assert.equal(h.page.data.loading, true);
  h.last('/api/official/my-events').fail({ code: 500, msg: 'mine offline' }, 500);
  assert.equal(h.page.data.curError, true); assert.equal(h.page.data.errorMsg, 'mine offline');
  assert.equal(h.page.data.loading, false);
  h.page.onRetry(); myRows(h.last('/api/official/my-events'));
  assert.equal(h.page.data.curError, false); assert.equal(h.page.data.events[0].id, 11);
  h.tab(0); assert.equal(h.page.data.errorMsg, 'public offline');
  assert.deepEqual(writes.flat().filter(key => internal.includes(key)), []);
  for (const key of internal) assert.equal(Object.hasOwn(h.page.data, key), false, key);
});


test('same-session public refresh 500 keeps lawful public rows and settles visible loading', () => {
  const h = mount('7', 'token'); h.start(); publicRows(h);
  assert.equal(h.page.data.events[0].id, 1);
  h.page.fetchAll(); assert.equal(h.page.data.loading, true);
  h.last('/api/official/events').fail({ code: 500, msg: 'refresh offline' }, 500);
  assert.equal(h.page.data.events[0].id, 1); assert.equal(h.page.data.loading, false);
  assert.equal(h.page.data.curError, true); assert.equal(h.page.data.errorMsg, 'refresh offline');
  h.tab(1); assert.equal(h.page.data.events[0].id, 2);
  h.page.fetchAll(); publicRows(h);
  assert.equal(h.page.data.events[0].id, 2); assert.equal(h.page.data.curError, false);
});
