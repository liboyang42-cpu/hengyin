'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const test = require('node:test');
const { createRequestClient } = require('../../utils/transport/request-client.js');
const { createRequireLogin, hasSession } = require('../../utils/auth/require-login.js');

const file = path.resolve(__dirname, '../../pages/talent/list/index.js');
const source = fs.readFileSync(file, 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));
const empty = { code: 200, data: { rows: [], hasMore: false } };
const post = { code: 200, data: { rows: Array.from({ length: 20 }, (_, i) => ({ id: 81 - i, clubId: 9, createTime: '2026-11-15 12:00:00' })), hasMore: true } };

// Production Page + merger + request-client + login continuation; only the platform/network boundary is synthetic.
function mount(code = source, community = false) {
  let definition, owner = '', token = '', loginCalls = 0, refreshCalls = 0;
  let login = () => Promise.resolve({ ok: false });
  const requests = [], prompts = [];
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => owner, getAuthorization: () => token,
    getUserRole: () => 'player', getUserType: () => 1,
    getRequestErrorMessage: (res, fallback) => res && res.msg || fallback,
    getImgUrl: value => value || '', canUseCommunity: () => community,
    firstLogin: () => { loginCalls++; return login(); },
  };
  const wx = { getStorageSync: () => '', stopPullDownRefresh() {}, showToast() {}, showModal() {}, setNavigationBarColor() {} };
  global.getApp = () => app;
  global.wx = wx;
  const client = createRequestClient({
    wxRequest: options => { requests.push(options); return { abort() {} }; },
    getBaseUrl: () => 'https://fixture.invalid', getUserID: app.getUserID, getAuthorization: app.getAuthorization,
    reLogin: done => { refreshCalls++; done(false); },
  });
  app.sendRequest = options => client.send(options);
  vm.runInNewContext(code, { require: createRequire(file), getApp: () => app, wx, Page: value => { definition = value; } }, { filename: file });
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch); },
  });
  const guard = createRequireLogin({ getApp: () => app, getPages: () => [page], isSinglePage: () => false,
    show: options => prompts.push(options) });
  app.ensureLoginFor = (reason, resume, context) => {
    if (hasSession(app)) return true;
    guard.withLogin(Object.assign({}, context, { reason }), resume);
    return false;
  };
  return { page, app, requests, prompts,
    session(id, credential) { owner = id; token = credential; },
    setLogin(value) { login = value; },
    get loginCalls() { return loginCalls; }, get refreshCalls() { return refreshCalls; },
    async open() { page.onTopTabChange({ detail: { key: 'feed' } }); await tick(); },
  };
}

async function requireGuestRecovery(h, response = { statusCode: 200, data: { code: 401, msg: '登录状态已失效，请重新登录' } }) {
  await h.open();
  assert.equal(h.requests.length, 1);
  assert.match(h.requests[0].url, /\/api\/club\/post\/feed$/);
  h.requests[0].success(response);
  await tick();
  assert.equal(h.page.data.feedState, 'error');
  assert.equal(h.page.data.feedLoginRequired, true);
  assert.equal(h.prompts.length, 0, 'browsing must not prompt or create an account');
  assert.equal(h.loginCalls, 0);
  assert.equal(h.refreshCalls, 0);
}

for (const response of [
  { statusCode: 200, data: { code: 401, msg: '登录状态已失效，请重新登录' } },
  { statusCode: 200, data: { code: '2' } },
  { statusCode: 401, data: { msg: '需要登录' } },
  { statusCode: 401, data: null },
]) test('production read preserves auth classification ' + JSON.stringify(response), async () => {
  await requireGuestRecovery(mount(), response);
});

test('existing error component exposes the login action rather than its loading-failure veil', async () => {
  const h = mount(); await requireGuestRecovery(h);
  const wxml = fs.readFileSync(file.replace(/\.js$/, '.wxml'), 'utf8');
  assert.match(wxml, /<cy-error load-failure="\{\{!feedLoginRequired\}\}"[^>]*wx:elif="\{\{feedState === 'error'\}\}"[^>]*retry="\{\{feedLoginRequired \? '登录' : '重试'\}\}"[^>]*bind:retry="retryFeed"/);
  const component = fs.readFileSync(path.resolve(__dirname, '../../components/cy/error/index.wxml'), 'utf8');
  assert.match(component, /wx:if="\{\{loadFailure\}\}"/);
  assert.match(component, /aria-role="button"[^>]*bindtap="onRetry"/);
});

test('cancel and failed login remain retryable guests; one successful explicit login rereads production feed once', async () => {
  const h = mount(); await requireGuestRecovery(h);
  h.page.retryFeed();
  assert.equal(h.prompts.length, 1); assert.equal(h.requests.length, 1);
  assert.equal(h.prompts[0].content, '登录后查看帖文');
  h.prompts[0].success({ confirm: false }); await tick();
  assert.equal(h.loginCalls, 0); assert.equal(h.app.getUserID(), '');
  assert.equal(h.page.data.feedLoginRequired, true);

  h.page.retryFeed(); h.prompts[1].success({ confirm: true }); await tick();
  assert.equal(h.loginCalls, 1); assert.equal(h.requests.length, 1);
  assert.equal(h.page.data.feedLoginRequired, true);

  h.setLogin(() => { h.session('9', 'synthetic-session'); return Promise.resolve({ ok: true }); });
  h.page.retryFeed(); h.page.retryFeed();
  assert.equal(h.prompts.length, 3, 'login guard shares the prompt');
  h.prompts[2].success({ confirm: true }); await tick();
  assert.equal(h.requests.length, 2);
  assert.equal(h.requests[1].header.Authorization, 'synthetic-session');
  h.requests[1].success({ statusCode: 200, data: empty }); await tick();
  assert.equal(h.page.data.feedState, 'empty'); assert.equal(h.page.data.feedLoginRequired, false);
});

for (const leave of ['switch tab', 'hide', 'unload', 'new request']) test('login continuation cannot reload an abandoned target: ' + leave, async () => {
  const h = mount(); await requireGuestRecovery(h);
  let finish;
  h.setLogin(() => new Promise(resolve => { finish = () => { h.session('9', 'synthetic-session'); resolve({ ok: true }); }; }));
  h.page.retryFeed(); h.prompts[0].success({ confirm: true }); await tick();
  if (leave === 'switch tab') h.page.onTopTabChange({ detail: { key: 'club' } });
  if (leave === 'hide') h.page.onHide();
  if (leave === 'unload') h.page.onUnload();
  if (leave === 'new request') { h.page.loadFeed(); await tick(); }
  const count = h.requests.length;
  finish(); await tick(); assert.equal(h.requests.length, count);
});

test('rejected login keeps the same visible recovery action', async () => {
  const h = mount(); await requireGuestRecovery(h);
  h.setLogin(() => Promise.reject(Error('synthetic login failure')));
  h.page.retryFeed(); h.prompts[0].success({ confirm: true }); await tick();
  assert.equal(h.requests.length, 1); assert.equal(h.page.data.feedLoginRequired, true);
  h.page.retryFeed(); assert.equal(h.prompts.length, 2);
});

for (const kind of ['network', '403', '500', 'malformed']) test('ordinary failure does not become login or anonymous success: ' + kind, async () => {
  const h = mount(); await h.open();
  if (kind === 'network') h.requests[0].fail({ errMsg: 'request:fail timeout' });
  else h.requests[0].success({ statusCode: kind === '403' ? 403 : 200,
    data: kind === 'malformed' ? { code: 200, data: {} } : { code: Number(kind) } });
  await tick();
  assert.equal(h.page.data.feedState, 'error'); assert.equal(h.page.data.feedLoginRequired, false);
  h.page.retryFeed(); await tick();
  assert.equal(h.requests.length, 2); assert.equal(h.prompts.length, 0); assert.equal(h.loginCalls, 0);
});

for (const more of [false, true]) test('auth failure after a snapshot still exposes login, including pagination: ' + more, async () => {
  const h = mount(); await h.open(); h.requests[0].success({ statusCode: 200, data: post }); await tick();
  assert.equal(h.page.data.feedState, 'ready');
  if (more) h.page.loadMoreFeed(); else h.page.loadFeed();
  await tick(); h.requests[1].success({ statusCode: 401, data: { msg: '需要登录' } }); await tick();
  assert.equal(h.page.data.feedState, 'error'); assert.equal(h.page.data.feedLoginRequired, true);
});

test('community feed transport also preserves a bare HTTP401; late merged replies cannot overwrite recovery', async () => {
  const h = mount(source, true); await h.open(); assert.equal(h.requests.length, 2);
  h.requests[1].success({ statusCode: 401, data: { msg: '需要登录' } }); await tick();
  h.requests[0].success({ statusCode: 200, data: empty }); await tick();
  assert.equal(h.page.data.feedLoginRequired, true); assert.equal(h.page.data.feedState, 'error');
});

test('cached session renewal failure exposes recovery without changing session or bypassing transport', async () => {
  const h = mount(); h.session('9', 'synthetic-expired'); await h.open();
  h.requests[0].success({ statusCode: 401, data: { code: 401 } }); await tick();
  assert.equal(h.refreshCalls, 1); assert.equal(h.page.data.feedLoginRequired, true);
  h.page.retryFeed(); await tick();
  assert.equal(h.requests.length, 2); assert.equal(h.prompts.length, 0);
  assert.equal(h.app.getAuthorization(), 'synthetic-expired');
});

test('negative controls: original forced-false flag and dropped HTTP status both fail real-load recovery assertions', async () => {
  const originalFlag = source.replace('feedLoginRequired: loginRequired,', 'feedLoginRequired: false,');
  assert.notEqual(originalFlag, source);
  await assert.rejects(requireGuestRecovery(mount(originalFlag)), assert.AssertionError);
  const droppedStatus = source.replaceAll('that._onFeedFail(that._feedCtx, res, statusCode)', 'that._onFeedFail(that._feedCtx, res)');
  assert.notEqual(droppedStatus, source);
  await assert.rejects(requireGuestRecovery(mount(droppedStatus), { statusCode: 401, data: { msg: '需要登录' } }), assert.AssertionError);
});
