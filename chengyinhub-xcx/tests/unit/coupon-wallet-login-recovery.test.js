const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../subpackageMember/components/cy/scene-game-coupon-wallet/index.js');
const realRequire = createRequire(file);
function mount(owner = '', token = '') {
  let definition;
  const requests = [], logins = [], events = [];
  const app = { getUserID: () => owner, getAuthorization: () => token,
    sendRequest: req => requests.push(req),
    firstLogin: () => { logins.push('first'); return new Promise(resolve => { app.finishLogin = resolve; }); },
    reLogin: () => { logins.push('renew'); return new Promise(resolve => { app.finishLogin = resolve; }); }
  };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    getApp: () => app, Component: value => { definition = value; }, require: realRequire
  }, { filename: file });
  const component = Object.assign({}, definition.methods, { data: JSON.parse(JSON.stringify(definition.data)) });
  component.setData = (patch, done) => { Object.assign(component.data, patch); if (done) done(); };
  component.triggerEvent = (name, detail) => events.push({ name, detail });
  return { component, definition, requests, logins, events, app,
    session(id, auth) { owner = id; token = auth; },
    attach() { definition.lifetimes.attached.call(component); },
    show() { definition.pageLifetimes.show.call(component); },
    hide() { definition.pageLifetimes.hide.call(component); },
    detach() { definition.lifetimes.detached.call(component); }
  };
}
const tick = () => new Promise(resolve => setImmediate(resolve));
const succeed = req => req.success({ code: 200, data: [{ id: 9, couponName: '当前账号券', useStatus: 0 }] });
for (const [owner, token] of [['', ''], ['7', ''], ['', 'token']]) {
  test('wallet needs both owner and token before private reads: ' + JSON.stringify([owner, token]), () => {
    const h = mount(owner, token); h.attach(); h.show();
    for (let tab = 0; tab < 4; tab++) h.component.selectTab({ currentTarget: { dataset: { tab } } });
    assert.equal(h.requests.length, 0); assert.equal(h.component.data.state, 'login');
    assert.equal(h.component.data.all.length, 0); assert.equal(h.component.data.items.length, 0);
  });
}

test('explicit guest login is single flight and only then reads the current wallet', async () => {
  const h = mount(); h.attach(); h.component.retry(); h.component.retry();
  assert.deepEqual(h.logins, ['first']); assert.equal(h.requests.length, 0);
  h.session('7', 'token'); h.app.finishLogin(); await tick();
  assert.equal(h.requests.length, 1); assert.equal(h.requests[0].url, '/api/coupon/myrecvlist');
  succeed(h.requests[0]); assert.equal(h.component.data.all[0].id, 9);
});

test('declined or rejected login can be explicitly retried without a private read', async () => {
  const h = mount(); h.attach(); h.component.retry(); h.app.finishLogin({ ok: false }); await tick();
  assert.equal(h.component.data.state, 'login'); assert.equal(h.requests.length, 0);
  h.component.retry(); assert.deepEqual(h.logins, ['first', 'first']);
});

for (const transport of ['business', 'http', 'fail']) {
  for (const code of [401, 403, 500]) {
    test('wallet ' + transport + code + ' distinguishes auth, permission and ordinary failure', () => {
      const h = mount('7', 'token'); h.attach(); const req = h.requests[0];
      if (transport === 'business') req.success({ code });
      else if (transport === 'http') req.successStatusAbnormal({ code: 500 }, code);
      else req.fail({ code: 500 }, code);
      assert.equal(h.component.data.state, code === 401 ? 'login' : code === 403 ? 'permission' : 'error');
      assert.equal(h.component.data.items.length, 0);
    });
  }
}

test('legacy code2 stays login and explicitly renews once across all tabs', async () => {
  const h = mount('7', 'expired'); h.attach(); h.requests[0].success({ code: 2 });
  for (let tab = 0; tab < 4; tab++) h.component.selectTab({ currentTarget: { dataset: { tab } } });
  assert.equal(h.component.data.state, 'login'); h.component.retry(); h.component.retry();
  assert.deepEqual(h.logins, ['renew']); h.session('7', 'renewed'); h.app.finishLogin(); await tick();
  assert.equal(h.requests.length, 2); succeed(h.requests[1]); assert.equal(h.component.data.state, 'ready');
});

for (const transition of ['hide', 'hide-return', 'detach', 'owner', 'token']) {
  test('old wallet response is ignored after ' + transition, () => {
    const h = mount('7', 'token'); h.attach(); const old = h.requests[0];
    if (transition === 'hide' || transition === 'hide-return') { h.hide(); if (transition === 'hide-return') h.show(); }
    else if (transition === 'detach') h.detach();
    else h.session(transition === 'owner' ? '8' : '7', transition === 'token' ? 'new' : 'token');
    const before = JSON.stringify(h.component.data); succeed(old); old.fail({ code: 401 }, 401);
    assert.equal(JSON.stringify(h.component.data), before);
  });
}

for (const transition of ['hide-return', 'detach']) {
  test('late explicit login cannot reopen wallet after ' + transition, async () => {
    const h = mount(); h.attach(); h.component.retry();
    if (transition === 'detach') h.detach(); else { h.hide(); h.show(); }
    h.session('7', 'token'); h.app.finishLogin(); await tick();
    assert.equal(h.requests.length, 0);
  });
}

test('bootstrap waits once without competing login, then reads real current session', async () => {
  const h = mount(); let ready; let waits = 0; h.app.isAppReady = false;
  h.app.waitForAppReady = () => { waits++; return new Promise(resolve => { ready = resolve; }); };
  h.attach(); h.show(); assert.equal(waits, 1); assert.equal(h.logins.length, 0);
  h.session('7', 'token'); ready(); await tick(); assert.equal(h.requests.length, 1);
});

test('sessionChanged resets old wallet and reads new owner once; missing renewal shows login', () => {
  const h = mount('7', 'old'); h.attach(); h.session('8', 'new'); h.requests[0].fail({ reason: 'sessionChanged' });
  assert.equal(h.requests.length, 2); succeed(h.requests[1]); assert.equal(h.component.data.state, 'ready');
  h.component.load(); h.session('', ''); h.requests[2].fail({ code: 500 }, 401);
  assert.equal(h.component.data.state, 'login'); assert.equal(h.component.data.all.length, 0);
});

test('logout and show remove private coupons and preserve the owner gate before opening codes', () => {
  const h = mount('7', 'token'); h.attach(); succeed(h.requests[0]); const item = h.component.data.all[0];
  h.session('', ''); h.show(); h.component.openCode({ currentTarget: { dataset: { item } } });
  assert.equal(h.component.data.state, 'login'); assert.equal(h.component.data.items.length, 0); assert.equal(h.events.length, 0);
});

test('login and permission prompts render ahead of load-failure; ordinary service failure remains blank', () => {
  const wxml = fs.readFileSync(file.replace(/\.js$/, '.wxml'), 'utf8');
  assert.match(wxml, /state === 'login'[\s\S]*?title="登录后查看我的优惠券"[\s\S]*?bind:cta="retry"/);
  assert.match(wxml, /state === 'permission'[\s\S]*?kind="no-permission"/);
  assert.match(wxml, /<cy-error load-failure[^>]*state === 'error'/);
});

test('bootstrap completion from before hide and return cannot issue an unsolicited wallet read', async () => {
  const h = mount(); let ready; h.app.isAppReady = false;
  h.app.waitForAppReady = () => new Promise(resolve => { ready = resolve; });
  h.attach(); h.hide(); h.show(); h.session('7', 'late'); ready(); await tick();
  assert.equal(h.requests.length, 0); h.show(); assert.equal(h.requests.length, 1);
});

test('current401 after token disappears keeps login visible even if cached owner remains', () => {
  const h = mount('7', 'token'); h.attach(); h.session('7', '');
  h.requests[0].fail({ code: 500 }, 401);
  assert.equal(h.component.data.state, 'login'); assert.equal(h.component.data.items.length, 0);
});

test('rejected login releases the single-flight latch for an explicit retry', async () => {
  const h = mount(); h.attach(); let calls = 0;
  h.app.firstLogin = () => { calls++; return Promise.reject(new Error('login refused')); };
  h.component.retry(); await tick(); h.component.retry(); await tick();
  assert.equal(calls, 2); assert.equal(h.requests.length, 0); assert.equal(h.component.data.state, 'login');
});
