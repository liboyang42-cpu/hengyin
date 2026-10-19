'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
function methods(req) {
  const sync = source.slice(source.indexOf('  _syncThoughts(claim, themeGuard) {'), source.indexOf('  /* 故事流声音'));
  const write = source.slice(source.indexOf('  _withThoughtSync(write) {'), source.indexOf('  _resolveRouteAction('));
  return Function('req', 'cyToast', 'return ({' + sync + write + '});')(req, () => {});
}
function page(req) {
  return Object.assign({ data: { topicId: 10, themeRuntimeReady: true, themeBag: { sessionId: 40, stepEggs: true }, chapStory: {} },
    _epoch: 1, _allowed: true, _thoughtClaimed: {}, _routeState: { thoughtCount: 1 },
    _themeAllowed() { return this._allowed; }, _themeSyncGuard() { return { epoch: this._epoch, context: '7:10:9', sessionId: this.data.themeBag.sessionId }; },
    _themeLive(epoch) { return epoch === this._epoch; }, _sessionParams() { return { activityId: 9 }; },
    _weRunCipher() { return Promise.resolve({ code: 'login', encryptedData: 'fixture', iv: 'fixture' }); },
    _applyRouteState(state, done) { this.applied = state; done(); },
  }, methods(req));
}
test('merged sync serializes claim, step sync, and completion in that order', async () => {
  const first = deferred(), calls = [];
  const p = page((url, method, body) => { calls.push(body.claim.length ? 'claim' : 'steps'); return calls.length === 1 ? first.promise : Promise.resolve({ code: 200, data: { version: 2 } }); });
  const claim = p._syncThoughts(['x']);
  const write = p._withThoughtSync(() => { calls.push('completion'); return { code: 200 }; });
  await new Promise(resolve => setImmediate(resolve)); assert.deepEqual(calls, ['claim']);
  first.resolve({ code: 200, data: { version: 1 } }); await claim; assert.equal((await write).code, 200);
  assert.deepEqual(calls, ['claim', 'steps', 'completion']);
});
test('cipher failures release claims and block completion; permission absence keeps normal play', async () => {
  let posts = 0, writes = 0; const p = page(() => { posts++; });
  p._thoughtClaimed.x = true; p._weRunCipher = () => Promise.resolve(false);
  assert.equal(await p._syncThoughts(['x']), false); assert.equal(p._thoughtClaimed.x, undefined);
  assert.equal((await p._withThoughtSync(() => { writes++; })).netFail, true); assert.equal(posts, 0); assert.equal(writes, 0);
  p._weRunCipher = () => Promise.resolve(null);
  await p._withThoughtSync(() => { writes++; }); assert.equal(writes, 1); assert.equal(posts, 0);
});
test('queued theme sync revalidates before accessing WeRun or posting', async () => {
  const pending = deferred(); let reads = 0, posts = 0;
  const p = page(() => { posts++; }); p._thoughtSyncPending = pending.promise;
  p._weRunCipher = () => { reads++; return Promise.resolve({}); };
  const result = p._syncThoughts([], p._themeSyncGuard()); p._epoch++; pending.resolve(true);
  assert.equal(await result, false); assert.equal(reads, 0); assert.equal(posts, 0);
});
test('accepted request response after owner epoch change cannot apply state or complete', async () => {
  const response = deferred(); let posts = 0, writes = 0;
  const p = page(() => { posts++; return response.promise; });
  const result = p._withThoughtSync(() => { writes++; return { code: 200 }; });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(posts, 1);
  p._epoch++; response.resolve({ code: 200, data: { version: 99 } });
  assert.equal((await result).netFail, true); assert.equal(p.applied, undefined); assert.equal(writes, 0);
});
test('session replacement after cipher request cannot send old session steps', async () => {
  const cipher = deferred(); let posts = 0;
  const p = page(() => { posts++; }); p._weRunCipher = () => cipher.promise;
  const result = p._syncThoughts([], p._themeSyncGuard()); await Promise.resolve();
  p.data.themeBag.sessionId = 41; cipher.resolve({ encryptedData: 'fixture' });
  assert.equal(await result, false); assert.equal(posts, 0);
});
test('same-run accepted new clue retains master toast while stale versions remain silent', () => {
  const theme = require('../../pages/play/utils/theme-backpack.js'); const previousWx = global.wx; const notices = [];
  global.wx = { showToast(o) { notices.push(o.title); } };
  try {
    const p = { data: { topicId: 10, activityId: 9, themeBag: { sessionId: 40, version: 1, events: [] } }, setData(patch) { Object.assign(this.data, patch); } };
    Object.assign(p, theme.methods(() => {}, { getUserID: () => 7 }));
    const view = { sessionId: 40, topicId: 10, activityId: 9, playInstanceId: 'default', version: 2, contents: [], events: [{ id: 'a', name: '新线索' }] };
    assert.equal(p._applyThemeView(view), true); assert.deepEqual(notices, ['新线索']);
    assert.equal(p._applyThemeView(view), true); assert.equal(notices.length, 1);
    assert.equal(p._applyThemeView({ ...view, version: 1, events: [{ id: 'b', name: '过期' }] }), false); assert.equal(notices.length, 1);
  } finally { global.wx = previousWx; }
});

test('retiring queued claims never deletes replacement account claim markers', async () => {
  const wait = deferred(); const p = page(() => { throw new Error('stale request'); });
  const oldClaims = { clue: true }; p._thoughtClaimed = oldClaims; p._thoughtSyncPending = wait.promise;
  const result = p._syncThoughts(['clue'], p._themeSyncGuard());
  p._thoughtClaimed = { clue: true }; p._epoch++; wait.resolve(true);
  assert.equal(await result, false); assert.equal(oldClaims.clue, undefined); assert.equal(p._thoughtClaimed.clue, true);
});
