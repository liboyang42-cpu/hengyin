const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../subpackageA/pages/myproject/index.js');
const realRequire = createRequire(file);

function mount(owner = '', token = '') {
  let definition;
  const requests = [], roleReads = [], logins = [];
  const app = {
    globalData: {}, getUserID: () => owner, getAuthorization: () => token,
    getRequestErrorMessage: (res, fallback) => res && res.msg || fallback,
    sendRequest: req => requests.push(req),
    firstLogin: () => { logins.push('first'); return new Promise(resolve => { app.finishLogin = resolve; }); },
    reLogin: () => { logins.push('renew'); return new Promise(resolve => { app.finishLogin = resolve; }); }
  };
  const roles = { role: () => 'club', load: callback => roleReads.push(callback) };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Page: value => { definition = value; }, getApp: () => app,
    wx: { navigateTo() {} },
    require: name => name.endsWith('/roleGuard.js') ? roles
      : name.endsWith('/merchant-theme.js') ? { merchantPageShow() {}, merchantPageRestore() {} }
        : realRequire(name)
  }, { filename: file });
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
  page.setData = patch => Object.entries(patch).forEach(([key, value]) => {
    const keys = key.split('.');
    if (keys.length === 2) page.data[keys[0]][keys[1]] = value;
    else page.data[key] = value;
  });
  return { page, app, requests, roleReads, logins,
    session(id, auth) { owner = id; token = auth; },
    last(url) { return requests.filter(req => req.url === url).at(-1); } };
}
const tick = () => new Promise(resolve => setImmediate(resolve));
const switchTab = (page, key) => page.switchTypeTab({ detail: { key } });
function projects(req, id) { req.success({ code: 200, data: { rows: [{ id, bizType: 'topic', state: 'draft' }], total: 1 } }); req.complete(); }
function templates(req, id) { req.success({ code: 200, data: { rows: [{ id, draftStatus: 1, status: 1, checkStatus: 1 }] } }); req.complete(); }

for (const [owner, token] of [['', ''], ['7', ''], ['', 'token']]) {
  test('all three guest tabs avoid private reads with session ' + JSON.stringify([owner, token]), () => {
    const h = mount(owner, token); h.page.onLoad({ scope: 'MERCHANT' }); h.page.onShow();
    for (const tab of ['activity', 'template', 'topic']) switchTab(h.page, tab);
    h.page.loadProjects(); h.page.loadTemplates();
    assert.equal(h.requests.length, 0); assert.equal(h.roleReads.length, 0);
    assert.equal(h.page.data.authRequired, true);
    assert.equal(h.page.data.loading, false); assert.equal(h.page.data.tplLoading, false);
    assert.equal(h.page.data.canPublishActivity, false);
  });
}

test('explicit login is single flight and reloads the selected template tab with the real session', async () => {
  const h = mount(); h.page.onLoad({ type: 'template' }); h.page.onShow();
  h.page.onLogin(); h.page.onLogin(); assert.deepEqual(h.logins, ['first']);
  h.session('7', 'current'); h.app.finishLogin(); await tick();
  assert.equal(h.page.data.authRequired, false);
  assert.equal(h.requests.filter(req => req.url === '/api/project/my').length, 1);
  assert.equal(h.requests.filter(req => req.url === '/api/template/my-list').length, 1);
  projects(h.last('/api/project/my'), 1); templates(h.last('/api/template/my-list'), 2);
  assert.equal(h.page.data.tplList[0].id, 2);
});

test('declining login or completing login while hidden does not start private reads', async () => {
  for (const hidden of [false, true]) {
    const h = mount(); h.page.onLoad({}); h.page.onShow(); h.page.onLogin();
    if (hidden) { h.page.onHide(); h.session('7', 'token'); }
    h.app.finishLogin(hidden ? undefined : { ok: false }); await tick();
    assert.equal(h.requests.length, 0);
  }
});

for (const kind of ['project', 'template']) {
  for (const transport of ['business', 'http', 'network']) {
    test(kind + ' distinguishes login, permission and load failures through ' + transport, () => {
      for (const code of transport === 'network' ? [500] : [401, 403, 500]) {
        const h = mount('7', 'token'); h.page.onLoad({}); h.page.onShow();
        switchTab(h.page, 'template');
        const req = h.last(kind === 'project' ? '/api/project/my' : '/api/template/my-list');
        if (transport === 'business') req.success({ code, msg: 'actual rejection' });
        else if (transport === 'http') req.successStatusAbnormal({ statusCode: code, msg: 'actual HTTP rejection' }, code);
        else req.fail({ errMsg: 'request:fail' });
        assert.equal(h.page.data.authRequired, code === 401);
        assert.equal(h.page.data[kind === 'project' ? 'projectAccessDenied' : 'tplAccessDenied'], code === 403);
        if (code === 500) assert.ok(h.page.data[kind === 'project' ? 'projectErrorMsg' : 'tplErrorMsg']);
      }
    });
  }
}

test('logout clears private cards and open operations; returning old callbacks cannot restore them', () => {
  const h = mount('7', 'token'); h.page.onLoad({}); h.page.onShow();
  projects(h.last('/api/project/my'), 10); switchTab(h.page, 'template'); templates(h.last('/api/template/my-list'), 11);
  h.page.setData({ 'opsSheet.show': true, canPublishActivity: true });
  h.page.reload(); const oldProject = h.last('/api/project/my'), oldTemplate = h.last('/api/template/my-list');
  h.session('', ''); h.page.onShow();
  projects(oldProject, 20); templates(oldTemplate, 21);
  assert.equal(h.page.data.list.length, 0); assert.equal(h.page.data.tplList.length, 0);
  assert.equal(h.page._all.length, 0); assert.equal(h.page.data.opsSheet.show, false);
  assert.equal(h.page.data.canPublishActivity, false); assert.equal(h.page.data.authRequired, true);
});

test('successful current-owner cards survive an ordinary refresh error but not a current403', () => {
  const h = mount('7', 'token'); h.page.onLoad({}); h.page.onShow();
  projects(h.last('/api/project/my'), 30);
  h.page.loadProjects(); h.last('/api/project/my').fail({ errMsg: 'offline' });
  assert.equal(h.page.data.list[0].id, 30); assert.equal(h.page.data.authRequired, false);
  h.page.loadProjects(); h.last('/api/project/my').success({ code: 403 });
  assert.equal(h.page.data.list.length, 0); assert.equal(h.page.data.projectAccessDenied, true);
});

test('guest prompt sits before both type branches and has an explicit real login handler', () => {
  const wxml = fs.readFileSync(file.replace(/\.js$/, '.wxml'), 'utf8');
  assert.match(wxml, /wx:if="{{authRequired}}"[\s\S]*?title="登录后查看我的项目"[\s\S]*?bind:cta="onLogin"/);
  assert.match(wxml, /<block wx:elif="{{typeTab !== 'template'}}">/);
});


test('401 login prompt persists across all tabs until explicit successful renewal', async () => {
  const h = mount('7', 'expired'); h.page.onLoad({}); h.page.onShow();
  h.last('/api/project/my').success({ code: 401 });
  const before = h.requests.length;
  for (const tab of ['activity', 'template', 'topic']) switchTab(h.page, tab);
  assert.equal(h.page.data.authRequired, true); assert.equal(h.requests.length, before);
  h.page.onLogin(); h.page.onLogin(); assert.deepEqual(h.logins, ['renew']);
  h.session('7', 'renewed'); h.app.finishLogin(); await tick();
  assert.equal(h.page.data.authRequired, false); assert.equal(h.requests.length, before + 1);
});

test('legacy businesscode2 is a login prompt rather than a project loading error', () => {
  const h = mount('7', 'expired'); h.page.onLoad({}); h.page.onShow();
  h.last('/api/project/my').success({ code: 2 });
  assert.equal(h.page.data.authRequired, true); assert.equal(h.page.data.projectAccessDenied, false);
});

test('initial bootstrap waits once and reloads current owner without starting a competing login', async () => {
  const h = mount(); let ready; let waits = 0;
  h.app.isAppReady = false;
  h.app.waitForAppReady = () => { waits++; return new Promise(resolve => { ready = resolve; }); };
  h.page.onLoad({}); h.page.onShow(); h.page.reload();
  assert.equal(waits, 1); assert.equal(h.requests.length, 0); assert.equal(h.logins.length, 0);
  h.session('7', 'ready'); ready(); await tick();
  assert.equal(h.requests.filter(req => req.url === '/api/project/my').length, 1);
});

test('request-client sessionChanged reloads the new session once without retaining old cards', () => {
  const h = mount('7', 'old'); h.page.onLoad({}); h.page.onShow();
  const old = h.last('/api/project/my'); h.session('8', 'new');
  old.fail({ reason: 'sessionChanged' });
  assert.equal(h.page.data.list.length, 0); assert.equal(h.page.data.authRequired, false);
  assert.equal(h.requests.filter(req => req.url === '/api/project/my').length, 2);
  projects(h.last('/api/project/my'), 42); projects(old, 41);
  assert.equal(h.page.data.list[0].id, 42);
});

test('request-client renewal failure clears the session while foreground and shows login immediately', () => {
  const h = mount('7', 'old'); h.page.onLoad({}); h.page.onShow();
  const req = h.last('/api/project/my'); h.session('', ''); req.fail({ code: 401 });
  assert.equal(h.page.data.authRequired, true); assert.equal(h.page.data.loading, false);
  assert.equal(h.page.data.list.length, 0);
});

test('a response for an old token cannot restore private cards before the next onShow', () => {
  const h = mount('7', 'old'); h.page.onLoad({}); h.page.onShow();
  const req = h.last('/api/project/my'); h.session('7', 'new'); projects(req, 52);
  assert.equal(h.page.data.list.length, 0);
});

for (const kind of ['project', 'template']) {
  test(kind + ' request-client fail(body500, HTTP401) keeps authoritative transport status', () => {
    const h = mount('7', 'expired'); h.page.onLoad({}); h.page.onShow();
    switchTab(h.page, 'template');
    const req = h.last(kind === 'project' ? '/api/project/my' : '/api/template/my-list');
    req.fail({ code: 500, msg: 'silent renewal failed' }, 401);
    assert.equal(h.page.data.authRequired, true);
    assert.equal(h.page.data.projectAccessDenied, false);
    assert.equal(h.page.data.tplAccessDenied, false);
    assert.equal(h.page.data.loading, false); assert.equal(h.page.data.tplLoading, false);
  });
}

test('a login started before hide and return cannot reload the returning page', async () => {
  const h = mount(); h.page.onLoad({ type: 'template' }); h.page.onShow();
  h.page.onLogin(); h.page.onHide(); h.page.onShow();
  const before = h.requests.length;
  h.session('7', 'late'); h.app.finishLogin(); await tick();
  assert.equal(h.requests.length, before);
  assert.equal(h.page.data.authRequired, true);
  h.page.onShow();
  assert.equal(h.requests.filter(req => req.url === '/api/project/my').length, 1);
  assert.equal(h.requests.filter(req => req.url === '/api/template/my-list').length, 1);
});
