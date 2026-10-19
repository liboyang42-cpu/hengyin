'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const check = require('../../pages/publish/fabu/topic-saved-check.js');
const clone = value => JSON.parse(JSON.stringify(value));
const issue = (extra = {}) => Object.assign({ level: 'error', code: 'STATE_KEY_UNREGISTERED', message: '状态键未注册：clue.door', nodeId: 501 }, extra);
function fixture() {
  let owner = 7; const calls = [];
  const app = { getUserID: () => owner, sendRequest: options => calls.push(options) };
  const page = Object.assign({ data: Object.assign(clone(check.data), {
    editingTopicId: 31, editLoaded: true, editLoading: false, editLoadError: '', draftMemberId: 7,
    draftUuid: 'local_draft', baseRevision: 'revision_a', editScope: 'FULL', operationScope: '', topicDetailShow: true,
    formData: { name: 'Current local draft', chapters: [{ nodes: [{ answer: 'private_local_answer' }] }] },
  }), setData(update) { Object.assign(this.data, clone(update)); } }, check.methods(app));
  page._savedCheckOnShow();
  return { page, calls, owner: value => { owner = value; }, succeed: (index = 0, data = [issue()]) => calls[index].success({ code: 200, data }) };
}

test('explicit saved-topic check uses only existing GET and owner-selected topic/MERCHANT scope, never dirty form content', () => {
  for (const scope of ['', 'MERCHANT']) {
    const f = fixture(); f.page.data.operationScope = scope; const before = clone(f.page.data.formData);
    assert.equal(f.calls.length, 0); assert.equal(f.page.openSavedTopicCheck(), true);
    assert.equal(f.calls.length, 1); const request = f.calls[0];
    assert.equal(request.url, '/api/topic/publish-check'); assert.equal(request.method, 'GET'); assert.equal(request.silentError, true);
    assert.deepEqual(request.data, { topicId: '31', scope }); assert.equal(JSON.stringify(request.data).includes('private_local_answer'), false);
    f.succeed(); request.complete(); assert.equal(f.page.data.savedTopicCheck.loaded, true); assert.equal(f.page.data.savedTopicCheck.errors, 1);
    assert.equal(f.page.data.savedTopicCheck.busy, false); assert.deepEqual(f.page.data.formData, before);
  }
});
test('read-only saved-topic check remains available in the existing frozen edit scope', () => {
  const f = fixture(); f.page.data.editScope = 'WHITELIST'; assert.equal(f.page.openSavedTopicCheck(), true); f.succeed();
  assert.equal(f.page.data.savedTopicCheck.loaded, true); assert.equal(f.calls.length, 1);
});
test('new drafts, unknown identities, wrong authors and incomplete topic loads never request a saved check', () => {
  const changes = [f => { f.page.data.editingTopicId = ''; }, f => f.owner(0), f => { f.page.data.draftMemberId = 8; },
    f => { f.page.data.editLoaded = false; }, f => { f.page.data.editLoaded = 'true'; }, f => { f.page.data.editLoading = true; },
    f => { f.page.data.editLoadError = 'failed'; }, f => { f.page.data.operationScope = 'OTHER'; },
    f => { f.page.data.editingTopicId = Number.MAX_SAFE_INTEGER + 1; }, f => { f.page.data.editingTopicId = '9223372036854775808'; },
    f => { f.page.data.formData.text = 'x'.repeat(1000001); }, f => { f.page.data.formData.self = f.page.data.formData; }];
  for (const change of changes) { const f = fixture(); change(f); assert.equal(f.page.openSavedTopicCheck(), false); assert.equal(f.calls.length, 0); assert.ok(f.page.data.savedTopicCheck.error); assert.equal(f.page.data.savedTopicCheck.loaded, false); }
});
test('large valid Long topic IDs remain exact strings in the request', () => {
  const f = fixture(); f.page.data.editingTopicId = '9223372036854775807'; f.page.openSavedTopicCheck(); assert.equal(f.calls[0].data.topicId, '9223372036854775807');
});
test('only a complete successful empty array becomes an empty issue list', () => {
  const f = fixture(); f.page.openSavedTopicCheck(); f.succeed(0, []); f.calls[0].complete();
  assert.equal(f.page.data.savedTopicCheck.loaded, true); assert.equal(f.page.data.savedTopicCheck.total, 0); assert.equal(f.page.data.savedTopicCheck.page, 1); assert.equal(f.page.data.savedTopicCheck.pages, 1);
  for (const body of [undefined, null, {}, { issues: [] }, [null], [{}], [issue(), { level: 'warn' }], new Array(1)]) {
    const b = fixture(); b.page.openSavedTopicCheck(); b.calls[0].success({ code: 200, data: body }); b.calls[0].complete();
    assert.equal(b.page.data.savedTopicCheck.loaded, false); assert.ok(b.page.data.savedTopicCheck.error); assert.deepEqual(b.page.data.savedTopicCheck.rows, []);
  }
});
test('error, denial, network failure and complete-only HTTP failure never become all-clear', () => {
  for (const callback of [o => o.success({ code: 403, msg: '无权操作该主题' }), o => o.success({ code: 500 }), o => o.fail({ errMsg: 'network' }), o => o.complete({ statusCode: 502 })]) {
    const f = fixture(); f.page.openSavedTopicCheck(); callback(f.calls[0]); f.calls[0].complete();
    assert.equal(f.page.data.savedTopicCheck.loaded, false); assert.equal(f.page.data.savedTopicCheck.busy, false); assert.ok(f.page.data.savedTopicCheck.error);
  }
});
test('transport synchronous exceptions release the busy state with an explicit failure', () => {
  const f = fixture(); Object.assign(f.page, check.methods({ getUserID: () => 7, sendRequest() { throw Error('platform unavailable'); } }));
  f.page.openSavedTopicCheck(); assert.equal(f.page.data.savedTopicCheck.busy, false); assert.match(f.page.data.savedTopicCheck.error, /未打开/);
});
test('repeated taps are deduplicated while busy; retry is explicit and only reads', () => {
  const f = fixture(); f.page.openSavedTopicCheck(); assert.equal(f.page.openSavedTopicCheck(), false); assert.equal(f.page.retrySavedTopicCheck(), false); assert.equal(f.calls.length, 1);
  f.calls[0].complete(); assert.equal(f.calls.length, 1); assert.equal(f.page.retrySavedTopicCheck(), true); assert.equal(f.calls.length, 2);
  f.succeed(1); f.calls[1].complete(); assert.equal(f.page.data.savedTopicCheck.loaded, true); assert.equal(f.calls.every(c => c.method === 'GET'), true);
});
test('close then reopen invalidates all old success/failure/complete callbacks without unlocking the newer request', () => {
  const f = fixture(); f.page.openSavedTopicCheck(); const old = f.calls[0]; f.page.closeSavedTopicCheck(); assert.equal(f.page.data.savedTopicCheck.show, false);
  f.page.openSavedTopicCheck(); old.success({ code: 200, data: [] }); old.fail(); old.complete();
  assert.equal(f.page.data.savedTopicCheck.busy, true); assert.equal(f.page.data.savedTopicCheck.loaded, false);
  f.succeed(1, [issue({ level: 'warn', message: 'new warning' })]); f.calls[1].complete();
  assert.equal(f.page.data.savedTopicCheck.warnings, 1); assert.equal(f.page.data.savedTopicCheck.rows[0].message, 'new warning');
});
test('late responses are retired after owner/topic/scope/source/revision/load changes', () => {
  const changes = [f => f.owner(8), f => { f.page.data.editingTopicId = 32; }, f => { f.page.data.operationScope = 'MERCHANT'; },
    f => { f.page.data.formData.name = 'changed locally'; }, f => { f.page.data.baseRevision = 'revision_b'; },
    f => { f.page.data.draftUuid = 'other'; }, f => { f.page.data.draftMemberId = 8; },
    f => { f.page.data.editScope = 'WHITELIST'; }, f => { f.page._editLoadToken = 2; }, f => { f.page.data.editLoaded = false; }];
  for (const change of changes) {
    const f = fixture(); f.page.openSavedTopicCheck(); change(f); f.succeed(0, []); f.calls[0].complete();
    assert.equal(f.page.data.savedTopicCheck.show, false); assert.equal(f.page.data.savedTopicCheck.loaded, false); assert.deepEqual(f.page.data.savedTopicCheck.rows, []);
  }
});
test('owner or source changes between successful read and paging retire the stored result', () => {
  for (const change of [f => f.owner(8), f => { f.page.data.formData.name = 'next'; }]) {
    const f = fixture(); f.page.openSavedTopicCheck(); f.succeed(0, Array.from({ length: 21 }, () => issue())); change(f);
    assert.equal(f.page.nextSavedCheckPage(), false); assert.equal(f.page.data.savedTopicCheck.show, false); assert.equal(f.page._savedCheckValue, null);
  }
});
test('hide/show requires a new explicit request; unload never accepts another request', () => {
  for (const unload of [false, true]) {
    const f = fixture(); f.page.openSavedTopicCheck(); f.page._savedCheckOnHide(unload); f.page._savedCheckOnShow(); f.succeed(); f.calls[0].complete();
    assert.equal(f.page.data.savedTopicCheck.show, false); assert.equal(f.page.data.savedTopicCheck.loaded, false); assert.equal(f.calls.length, 1);
    assert.equal(f.page.openSavedTopicCheck(), !unload); assert.equal(f.calls.length, unload ? 1 : 2);
  }
});
test('projection rejects malformed/partial rows and bounds raw response volume', () => {
  for (const row of [issue({ level: 'info' }), issue({ code: '' }), issue({ code: 'raw.code' }), issue({ message: '' }), issue({ message: {} }), []]) assert.throws(() => check.presentSavedCheck([row]));
  assert.throws(() => check.presentSavedCheck(Array(501).fill(issue())));
  assert.throws(() => check.presentSavedCheck([issue({ message: 'x'.repeat(200001) })]));
});
test('projection accepts at most 500 bounded text rows and excludes node IDs/unknown payload', () => {
  const raw = Array.from({ length: 500 }, () => issue({ message: 'm', answer: { secret: 'never' }, nodeId: 'private_node' }));
  const value = check.presentSavedCheck(raw); assert.equal(value.total, 500); assert.equal(value.errors, 500);
  assert.equal(JSON.stringify(value).includes('private_node'), false); assert.equal(JSON.stringify(value).includes('never'), false);
  value.rows.forEach(row => assert.deepEqual(Object.keys(row).sort(), ['label', 'level', 'message']));
});
test('long messages and errors are visibly clipped without cutting emoji and controls are sanitized', () => {
  const value = check.presentSavedCheck([issue({ message: '😀'.repeat(500) + '\u0000tail' })]);
  assert.equal(value.clipped, true); assert.ok(value.rows[0].message.length <= 800); assert.equal(/[\uD800-\uDBFF]…/.test(value.rows[0].message), false);
  assert.equal(check.presentSavedCheck([issue({ message: 'a\u0000b\nc' })]).rows[0].message, 'a b c');
  const f = fixture(); f.page.openSavedTopicCheck(); f.calls[0].success({ code: 403, msg: '😀'.repeat(500) }); assert.ok(f.page.data.savedTopicCheck.error.length <= 500);
});
test('paging sends at most 20 rows to setData and isolates source and rendered mutations', () => {
  const f = fixture(), raw = Array.from({ length: 45 }, (_, i) => issue({ message: 'Issue ' + i, level: i % 2 ? 'warn' : 'error' }));
  f.page.openSavedTopicCheck(); f.succeed(0, raw); raw[0].message = 'server mutation';
  assert.equal(f.page.data.savedTopicCheck.rows.length, 20); assert.equal(f.page.data.savedTopicCheck.rows[0].message, 'Issue 0');
  f.page.data.savedTopicCheck.rows[0].message = 'renderer mutation'; f.page.nextSavedCheckPage(); assert.equal(f.page.data.savedTopicCheck.rows[0].message, 'Issue 20');
  f.page.nextSavedCheckPage(); assert.equal(f.page.data.savedTopicCheck.rows.length, 5); assert.equal(f.page.nextSavedCheckPage(), false);
  f.page.previousSavedCheckPage(); f.page.previousSavedCheckPage(); assert.equal(f.page.data.savedTopicCheck.rows[0].message, 'Issue 0'); assert.equal(f.page.previousSavedCheckPage(), false);
});
test('duplicate callbacks cannot overwrite a settled result or convert a failure to success', () => {
  const f = fixture(); f.page.openSavedTopicCheck(); f.succeed(); f.calls[0].success({ code: 200, data: [] }); f.calls[0].fail(); f.calls[0].complete();
  assert.equal(f.page.data.savedTopicCheck.errors, 1);
  const b = fixture(); b.page.openSavedTopicCheck(); b.calls[0].fail(); b.succeed(0, []); assert.equal(b.page.data.savedTopicCheck.loaded, false);
});
test('actual editor wiring includes owner lifecycle, nested native back and explicit saved-only wording', () => {
  const base = path.resolve(__dirname, '../../pages/publish/fabu'); const js = fs.readFileSync(path.join(base, 'index.js'), 'utf8');
  assert.match(js, /\.\.\.savedTopicCheck\.methods\(app\)/); assert.match(js, /\.\.\.savedTopicCheck\.data/);
  assert.match(js, /onHide\(\) \{\s*this\._savedCheckOnHide\(false\);[\s\S]*?\}/); assert.match(js, /onUnload\(\) \{\s*this\._savedCheckOnHide\(true\)/);
  assert.match(js, /onShow\(\) \{\s*this\._savedCheckOnShow\(\)/); assert.match(js, /closeTopicDetail\(\) \{ this\.closeSavedTopicCheck\(\)/);
  const runtime = fs.readFileSync(path.join(base, 'theme-runtime.js'), 'utf8');
  assert.equal(/\bonHide\s*[:(]/.test(runtime), false, 'The added host hook must not shadow a runtime hook');
  const host = fs.readFileSync(path.join(base, 'index.wxml'), 'utf8');
  assert.match(host, /<page-container[\s\S]*?show="[^"\n]*topicDetailShow/);
  const begin = js.indexOf('  onNativeBackGuard() {'), end = js.indexOf('\n  onStoryChapterNameInput', begin);
  const back = new Function('setTimeout', 'return ({' + js.slice(begin, end) + '});')(() => {}).onNativeBackGuard;
  const f = fixture(); f.page.openSavedTopicCheck(); back.call(f.page); assert.equal(f.page.data.savedTopicCheck.show, false); assert.equal(f.page.data.topicDetailShow, true);
  const wxml = fs.readFileSync(path.join(base, 'topic-detail-sheet.wxml'), 'utf8');
  assert.match(wxml, /stacked="\{\{runtimeEditor.show \|\| savedTopicCheck.show\}\}"/);
  const sheet = wxml.slice(wxml.indexOf('<cy-sheet show="{{savedTopicCheck.show}}"'));
  assert.match(sheet, /线上已保存内容检查/); assert.match(sheet, /不包含本地未保存修改/); assert.match(sheet, /未标明对应的保存版本/);
  assert.match(sheet, /本次试检查未报告问题/); assert.equal(/confirmPublish|submitForm|previewDraft|<image/.test(sheet), false);
});


test('unavailable identity lookup and nonprimitive IDs fail closed without a request', () => {
  const f = fixture(); Object.assign(f.page, check.methods({ getUserID() { throw Error('identity unavailable'); }, sendRequest() { throw Error('must not request'); } }));
  assert.equal(f.page.openSavedTopicCheck(), false); assert.equal(f.page.data.savedTopicCheck.loaded, false);
  const b = fixture(); b.page.data.editingTopicId = [31]; assert.equal(b.page.openSavedTopicCheck(), false); assert.equal(b.calls.length, 0);
});

// Compose the actual app reLogin body with real session storage, login manager and
// request transport. Only wx.login / code exchange / wx.request are in-memory I/O.
function realSessionFixture() {
  const { createSessionStore } = require('../../utils/session/session-store.js');
  const { createSessionManager } = require('../../utils/session/session-manager.js');
  const { createRequestClient } = require('../../utils/transport/request-client.js');
  const appSource = fs.readFileSync(path.resolve(__dirname, '../../app.js'), 'utf8');
  const start = appSource.indexOf('  reLogin: function (callback) {');
  const end = appSource.indexOf('  // 等待app初始化的方法', start);
  assert.ok(start >= 0 && end > start, 'fixture must execute the production reLogin body');
  const reLogin = new Function('return ({' + appSource.slice(start, end) + '}).reLogin;')();
  const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
  const login = deferred(), exchange = deferred(), memory = {}, target = {}, native = [], toasts = [], tips = [];
  const session = createSessionStore({ target, storage: {
    getStorageSync: key => memory[key], setStorageSync: (key, value) => { memory[key] = value; }, removeStorageSync: key => { delete memory[key]; },
  } });
  const setOwner = owner => session.applySnapshot({ user_id: owner, authorization: 'fixture-session-' + owner, user_type: 1, role: 'player' });
  setOwner(7);
  const manager = createSessionManager({ session, wxLogin: () => login.promise, exchangeCode: () => exchange.promise });
  const app = { getSession: () => session, getSessionManager: () => manager, reLogin,
    getUserID: () => session.getUserID(), tips: message => tips.push(message) };
  const client = createRequestClient({ wxRequest: options => { native.push(options); }, getBaseUrl: () => 'https://fixture.invalid',
    getAuthorization: () => session.getAuthorization(), reLogin: callback => app.reLogin(callback),
    showToast: message => toasts.push(message), shouldAutoToast: (param, body) => !param.silentError && Number(body.code) !== 200 });
  app.sendRequest = client.send;
  const f = fixture(); Object.assign(f.page, check.methods(app));
  const flush = () => new Promise(resolve => setImmediate(resolve));
  const startAuthFailure = (code = 401, statusCode = 401) => {
    assert.equal(f.page.openSavedTopicCheck(), true);
    const response = { statusCode, data: { code } }; native[0].success(response); native[0].complete(response);
    assert.equal(f.page.data.savedTopicCheck.busy, true, 'complete stays suppressed while real asynchronous reLogin is pending');
  };
  const failRefresh = async () => { login.resolve({ code: 'fixture-code' }); await flush(); exchange.reject(new Error('fixture exchange failure')); await flush(); };
  const succeedRefresh = async owner => { login.resolve({ code: 'fixture-code' }); await flush(); exchange.resolve({ token: 'fixture-new-session-' + owner, data: { id: owner, userType: 1, role: 'player' } }); await flush(); };
  return { page: f.page, session, native, tips, toasts, setOwner, startAuthFailure, failRefresh, succeedRefresh };
}

test('real asynchronous reLogin clearSession retires the old sheet and gives one visible sign-in failure', async () => {
  for (const [code, status] of [[401, 401], [undefined, 401], [401, 200], [2, 200]]) {
    const f = realSessionFixture(), before = clone(f.page.data.formData);
    f.startAuthFailure(code, status); await f.failRefresh();
    assert.equal(f.session.getUserID(), undefined, 'production reLogin must really clear the identity');
    assert.equal(f.page.data.savedTopicCheck.show, false, 'never reopen a sheet owned by the invalid identity');
    assert.equal(f.page.data.savedTopicCheck.busy, false); assert.equal(f.page.data.savedTopicCheck.loaded, false);
    assert.deepEqual(f.page.data.savedTopicCheck.rows, []); assert.equal(f.page._savedCheckContext, null);
    assert.deepEqual(f.tips, ['登录已失效，请重新进入编辑器']); assert.deepEqual(f.toasts, []);
    assert.equal(f.page.retrySavedTopicCheck(), false); assert.equal(f.native.length, 1);
    assert.deepEqual(f.page.data.formData, before);
    f.native[0].fail({ errMsg: 'late failure' }); f.native[0].complete({ statusCode: 401 });
    assert.equal(f.tips.length, 1, 'duplicate callbacks must not repeat the sign-in notice');
  }
});

test('late authentication failures after close, hide, unload or a different draft never raise a notice', async () => {
  for (const retire of [
    page => page.closeSavedTopicCheck(), page => page._savedCheckOnHide(false), page => page._savedCheckOnHide(true),
    page => { page.data.editingTopicId = 32; }, page => { page.data.formData.name = 'new local draft'; },
    page => { page.data.editLoading = true; }, page => { page.data.baseRevision = 'newer revision'; },
  ]) {
    const f = realSessionFixture(); f.startAuthFailure(); retire(f.page); await f.failRefresh();
    assert.equal(f.page.data.savedTopicCheck.show, false); assert.deepEqual(f.tips, []); assert.deepEqual(f.toasts, []);
  }
});

test('switching identity while refresh is pending retires the old request without its failure notice', async () => {
  const f = realSessionFixture(); f.startAuthFailure(); f.setOwner(8); f.page._savedCheckOnShow();
  assert.equal(f.page.data.savedTopicCheck.show, false); await f.failRefresh();
  assert.deepEqual(f.tips, []); assert.deepEqual(f.toasts, []); assert.equal(f.page._savedCheckContext, null);
});

test('successful refresh into another identity cannot publish old check errors into the new identity', async () => {
  const f = realSessionFixture(); f.startAuthFailure(); await f.succeedRefresh(8);
  assert.equal(f.session.getUserID(), 8); assert.equal(f.native.length, 2);
  const response = { statusCode: 403, data: { code: 403, msg: 'old topic denied' } };
  f.native[1].success(response); f.native[1].complete(response);
  assert.equal(f.session.getUserID(), 8); assert.equal(f.page.data.savedTopicCheck.show, false);
  assert.deepEqual(f.tips, []); assert.deepEqual(f.toasts, []);
});

test('same-owner successful refresh still completes the original read without an expiry notice', async () => {
  const f = realSessionFixture(); f.startAuthFailure(); await f.succeedRefresh(7);
  assert.equal(f.native.length, 2);
  const response = { statusCode: 200, data: { code: 200, data: [] } };
  f.native[1].success(response); f.native[1].complete(response);
  assert.equal(f.page.data.savedTopicCheck.show, true); assert.equal(f.page.data.savedTopicCheck.loaded, true);
  assert.deepEqual(f.tips, []); assert.deepEqual(f.toasts, []);
});

test('real transport 403, 502 and timeout keep their inline error and explicit retry with the current session', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/publish/fabu/topic-detail-sheet.wxml'), 'utf8');
  assert.match(wxml, /wx:if="\{\{savedTopicCheck\.error\}\}"[^>]*><text[^>]*>\{\{savedTopicCheck\.error\}\}<\/text><view[^>]*bindtap="retrySavedTopicCheck"/);
  for (const trigger of [
    options => { const res = { statusCode: 200, data: { code: 403, msg: '无权操作该主题' } }; options.success(res); options.complete(res); },
    options => { const res = { statusCode: 502, data: {} }; options.success(res); options.complete(res); },
    options => { const res = { errMsg: 'request:fail timeout' }; options.fail(res); options.complete(res); },
  ]) {
    const f = realSessionFixture(); f.page.openSavedTopicCheck(); trigger(f.native[0]);
    assert.equal(f.session.getUserID(), 7); assert.equal(f.page.data.savedTopicCheck.show, true);
    assert.equal(f.page.data.savedTopicCheck.busy, false); assert.equal(f.page.data.savedTopicCheck.loaded, false);
    assert.ok(f.page.data.savedTopicCheck.error); assert.deepEqual(f.tips, []); assert.deepEqual(f.toasts, []);
    assert.equal(f.page.retrySavedTopicCheck(), true); assert.equal(f.native.length, 2);
    const success = { statusCode: 200, data: { code: 200, data: [] } }; f.native[1].success(success); f.native[1].complete(success);
    assert.equal(f.page.data.savedTopicCheck.loaded, true); assert.equal(f.page.data.savedTopicCheck.error, '');
  }
});

test('old refresh failure cannot clear or notify for a newer saved-check request', async () => {
  const f = realSessionFixture(); f.startAuthFailure(); f.page.closeSavedTopicCheck();
  assert.equal(f.page.openSavedTopicCheck(), true); const newer = f.page._savedCheckContext;
  await f.failRefresh();
  assert.equal(f.page._savedCheckContext, newer); assert.equal(f.page.data.savedTopicCheck.busy, true);
  assert.equal(f.page.data.savedTopicCheck.show, true); assert.deepEqual(f.tips, []); assert.deepEqual(f.toasts, []);
});

test('a new identity installed during retirement suppresses the old sign-in notice', async () => {
  const f = realSessionFixture(); f.startAuthFailure();
  const setData = f.page.setData;
  f.page.setData = function (update) {
    setData.call(this, update);
    if (update.savedTopicCheck && !update.savedTopicCheck.show) f.setOwner(8);
  };
  await f.failRefresh();
  assert.equal(f.session.getUserID(), 8); assert.equal(f.page.data.savedTopicCheck.show, false);
  assert.deepEqual(f.tips, []); assert.deepEqual(f.toasts, []);
});
