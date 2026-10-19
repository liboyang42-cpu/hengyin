'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const theme = require('../../pages/play/utils/theme-backpack.js');
const run = require('../../pages/play/utils/play-run-session.js');
const story = require('../../pages/play/utils/story-resume.js');
const clone = value => JSON.parse(JSON.stringify(value));
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
const FIRST = 'theme-first:10:9';
const LONG_MAX = '9223372036854775807';
const baseScope = { memberId: 7, topicId: 10, activityId: 9 };
const view = (extra = {}) => Object.assign({ sessionId: 40, topicId: 10, activityId: 9,
  playInstanceId: FIRST, status: 'ENDED', version: 3, contents: [], pending: {}, events: [] }, extra);

function fixture(options = {}) {
  let owner = 7;
  const calls = [], modals = [], handlers = options.handlers || {};
  const current = { view: view(options.view) };
  const req = (url, method, data) => {
    calls.push({ url, method, data: clone(data) });
    if (handlers[url]) return Promise.resolve().then(() => handlers[url](data));
    if (url.endsWith('/capabilities')) return Promise.resolve({ code: 200, data: { enabled: true } });
    if (url.endsWith('/state')) return Promise.resolve({ code: 200, data: clone(current.view) });
    throw new Error('Unexpected request ' + url);
  };
  const p = Object.assign({ data: Object.assign(clone(theme.data), { topicId: current.view.topicId,
    activityId: current.view.activityId, nodes: [], chapter: {} }),
  setData(update) { Object.assign(this.data, clone(update)); },
  _sessionParams() { return { activityId: this.data.activityId }; },
  loadData() { this.nodesRefreshes = (this.nodesRefreshes || 0) + 1; },
  refreshRouteState() { return Promise.resolve(true); },
  }, theme.methods(req, { getUserID: () => owner }, { show(modal) {
    modals.push(modal);
    if (options.confirm !== 'manual') modal.success({ confirm: options.confirm !== false });
  } }));
  p._startThemePoll = () => {}; p._stopThemePoll = () => {};
  p._themeOnShow();
  const ready = () => p._acceptThemeNodes({ themeRuntimeAvailable: true,
    routeState: { sessionId: current.view.sessionId } }, p._themeNodesRequest());
  return { p, current, handlers, calls, modals, ready, owner: value => { owner = value; },
    scope: () => p._themeStoryScope({ memberId: owner, topicId: p.data.topicId, activityId: p.data.activityId }),
    posts: () => calls.filter(call => call.method === 'POST') };
}

function storage() {
  const map = new Map(), calls = [];
  return { map, calls, wx: {
    getStorageSync(key) { calls.push(['read', key]); return map.get(key); },
    setStorageSync(key, value) { calls.push(['write', key]); map.set(key, clone(value)); },
    removeStorageSync(key) { calls.push(['remove', key]); map.delete(key); },
  } };
}

test('FIRST gets an exact optional local namespace without changing the legacy key', () => {
  assert.equal(run.pausedRunStorageKey(baseScope), 'play_paused_run_v2:m7:a9');
  assert.equal(run.pausedRunStorageKey({ ...baseScope, instanceResolved: true, playInstanceId: FIRST }),
    'play_paused_run_v2:m7:a9:ptheme-first%3A10%3A9');
});

test('shared format guard accepts canonical FIRST signed-long bounds as strings', () => {
  for (const token of ['theme-first:1:0', FIRST, 'theme-first:' + LONG_MAX + ':0',
    'theme-first:9223372036854775806:9223372036854775806', 'theme-first:' + LONG_MAX + ':' + LONG_MAX]) {
    assert.equal(run.isThemePlayInstanceId(token), true, token);
    assert.ok(run.pausedRunStorageKey({ ...baseScope, playInstanceId: token }).endsWith(':p' + encodeURIComponent(token)), token);
  }
  assert.equal(('theme-first:' + LONG_MAX + ':' + LONG_MAX).length, 51);
});

test('FIRST rejects overflow, noncanonical decimal, extra segments and injection without Number rounding', () => {
  assert.equal(Number(LONG_MAX), Number('9223372036854775808'), 'test reaches the unsafe Number alias');
  const invalid = [null, undefined, 1, {}, ['theme-first:10:9'], '', 'theme-first', 'theme-first:1',
    'theme-first:0:0', 'theme-first:01:0', 'theme-first:1:00', 'theme-first:1:09', 'theme-first:-1:0',
    'theme-first:1:-1', 'theme-first:+1:0', 'theme-first:1:+1', 'theme-first:1:1.0', 'theme-first:1:1e1',
    'theme-first:9223372036854775808:0', 'theme-first:1:9223372036854775808',
    'theme-first:9999999999999999999:0', 'theme-first:10000000000000000000:0',
    'theme-first:1:0:1', 'theme-first::0', 'theme-first:1:', 'theme-first:１:0',
    ' theme-first:1:0', 'theme-first:1:0 ', 'theme-first:1:0\n', 'theme-first:1:0\r',
    'theme-first:1:0\u0000', 'theme-first:1:0/../../default', 'theme-first%3A1%3A0', 'THEME-FIRST:1:0'];
  for (const token of invalid) {
    assert.equal(run.isThemePlayInstanceId(token), false, String(token));
    assert.equal(run.pausedRunStorageKey({ ...baseScope, playInstanceId: token }), '', String(token));
  }
});

test('default, personal replay and team replay retain canonical long-format and key contracts', () => {
  for (const token of ['default', 'replay:1', 'replay:' + LONG_MAX, 'team-replay:1', 'team-replay:' + LONG_MAX]) {
    assert.equal(run.isThemePlayInstanceId(token), true, token);
    assert.equal(run.pausedRunStorageKey({ ...baseScope, playInstanceId: token }), 'play_paused_run_v2:m7:a9:p' + encodeURIComponent(token));
  }
  for (const token of ['default:1', 'replay:0', 'replay:01', 'replay:-1', 'replay:9223372036854775808',
    'team-replay:0', 'team-replay:9223372036854775808', 'team-replay:1:2', 'replay:1\n']) {
    assert.equal(run.isThemePlayInstanceId(token), false, token);
    assert.equal(run.pausedRunStorageKey({ ...baseScope, playInstanceId: token }), '', token);
  }
});

test('FIRST authoritative view reaches ready for self-play, activity and maximum long strings', async () => {
  for (const scope of [{ topicId: 10, activityId: 0 }, { topicId: 10, activityId: 9 },
    { topicId: LONG_MAX, activityId: LONG_MAX }]) {
    const token = 'theme-first:' + scope.topicId + ':' + scope.activityId;
    const f = fixture({ view: { ...scope, playInstanceId: token } });
    assert.equal(run.pausedRunStorageKey(f.scope()), '');
    assert.equal(await f.ready(), true);
    assert.equal(f.p.data.themeRuntimeReady, true);
    assert.equal(f.p.data.themeBag.playInstanceId, token);
    assert.equal(f.p._themeCacheInstance, token);
    assert.ok(run.pausedRunStorageKey(f.scope()).endsWith(':p' + encodeURIComponent(token)));
  }
});

test('FIRST format alone cannot bypass theme, preview, account or capability admission', async () => {
  for (const mode of ['legacy', 'preview', 'mock', 'anonymous', 'disabled', 'truthy']) {
    const f = fixture();
    if (mode === 'preview') f.p.data.isPreview = true;
    if (mode === 'mock') f.p.data.isMock = true;
    if (mode === 'anonymous') f.owner(0);
    if (mode === 'disabled' || mode === 'truthy') f.handlers['/api/play/theme/capabilities'] = () =>
      ({ code: 200, data: { enabled: mode === 'truthy' ? 'true' : false } });
    assert.equal(await f.p._acceptThemeNodes({ themeRuntimeAvailable: mode !== 'legacy' }, f.p._themeNodesRequest()), false, mode);
    assert.equal(f.p.data.themeRuntimeReady, false, mode);
    assert.equal(f.calls.filter(call => call.url.endsWith('/state')).length, 0, mode);
    assert.equal(await f.p._themeCommand('USE', 'a'), false, mode);
    assert.equal(f.posts().length, 0, mode);
  }
});

test('FIRST malformed view cannot mark ready or establish a cache reference', async () => {
  for (const token of ['theme-first:0:9', 'theme-first:10:09', 'theme-first:10:9223372036854775808',
    'theme-first:10:9:extra', 'theme-first:10:9\n']) {
    const f = fixture({ view: { playInstanceId: token } });
    assert.equal(await f.ready(), false, token);
    assert.equal(f.p.data.themeRuntimeReady, false, token);
    assert.equal(run.pausedRunStorageKey(f.scope()), '', token);
  }
});

test('FIRST view retains exact topic/activity, session and monotonic version guards', async () => {
  const f = fixture(); assert.equal(await f.ready(), true);
  for (const extra of [{ topicId: 11 }, { activityId: 8 }, { sessionId: 0 }, { version: 2 }])
    assert.equal(f.p._applyThemeView(view(extra)), false);
  assert.equal(f.p.data.themeBag.sessionId, 40); assert.equal(f.p.data.themeBag.version, 3);
  f.current.view = view({ sessionId: 41 });
  assert.equal(await f.p.refreshThemeBackpack(), false);
  assert.equal(f.p.nodesRefreshes, 1); assert.equal(f.p.data.themeRuntimeReady, false);
  assert.equal(run.pausedRunStorageKey(f.scope()), '');
});

test('pending FIRST cannot read, write or retire paused/story/draft records', () => {
  const store = storage(), previous = global.wx; global.wx = store.wx;
  try {
    const scope = { ...baseScope, playInstanceId: FIRST, instanceResolved: false };
    assert.equal(run.readPausedRun(store.wx, scope), null);
    assert.equal(run.writePausedRun(store.wx, scope, 1, 100), false);
    assert.equal(run.clearRunSession(store.wx, scope), false);
    assert.deepEqual(story.read(scope), {}); story.patch(scope, { intro: true });
    assert.equal(story.draftKey(scope, 11), ''); assert.deepEqual(store.calls, []);
  } finally { global.wx = previous; }
});

test('FIRST story/draft/timer re-entry preserves every old cache and isolates owner and scope', async () => {
  const store = storage(), previous = global.wx; global.wx = store.wx;
  try {
    const f = fixture(); assert.equal(await f.ready(), true);
    const firstScope = f.scope(), firstKey = run.pausedRunStorageKey(firstScope);
    const alternatives = [baseScope, { ...baseScope, playInstanceId: 'default' },
      { ...baseScope, playInstanceId: 'replay:40' }, { ...baseScope, playInstanceId: 'team-replay:80' },
      { ...firstScope, memberId: 8 }, { ...firstScope, activityId: 11, playInstanceId: 'theme-first:10:11' },
      { memberId: 7, topicId: 11, activityId: 0, playInstanceId: 'theme-first:11:0' }];
    for (const scope of alternatives) { assert.notEqual(run.pausedRunStorageKey(scope), firstKey); story.patch(scope, { marker: run.pausedRunStorageKey(scope) }); }
    store.map.set('play_paused_run_v1:a9', { old: true });
    assert.equal(run.writePausedRun(store.wx, firstScope, 25, 100), true);
    story.patch(firstScope, { intro: true, gates: [5], openNodeId: '11' });
    store.wx.setStorageSync(story.draftKey(firstScope, 11), { answer: 'FIRST draft' });
    const snapshot = clone(Array.from(store.map));
    f.p._themeOnHide(false); assert.equal(run.pausedRunStorageKey(f.scope()), '');
    f.p._themeOnShow(); assert.equal(run.pausedRunStorageKey(f.scope()), '');
    assert.equal(await f.ready(), true); assert.equal(run.pausedRunStorageKey(f.scope()), firstKey);
    assert.equal(run.readPausedRun(store.wx, f.scope()).elapsedSeconds, 25);
    assert.equal(story.read(f.scope()).openNodeId, '11');
    assert.equal(store.wx.getStorageSync(story.draftKey(f.scope(), 11)).answer, 'FIRST draft');
    assert.deepEqual(Array.from(store.map), snapshot);
    assert.equal(store.calls.filter(call => call[0] === 'remove').length, 0);
  } finally { global.wx = previous; }
});

test('clearing FIRST timer never deletes unscoped, default, replay or legacy records', () => {
  const store = storage(), firstScope = { ...baseScope, playInstanceId: FIRST };
  const others = [baseScope, { ...baseScope, playInstanceId: 'default' }, { ...baseScope, playInstanceId: 'replay:40' }];
  store.map.set('play_paused_run_v1:a9', { old: true });
  for (const scope of others) assert.equal(run.writePausedRun(store.wx, scope, 12, 100), true);
  assert.equal(run.writePausedRun(store.wx, firstScope, 25, 101), true);
  assert.equal(run.clearRunSession(store.wx, firstScope), true);
  assert.equal(run.readPausedRun(store.wx, firstScope), null);
  for (const scope of others) assert.equal(store.map.has(run.pausedRunStorageKey(scope)), true);
  assert.equal(store.map.has('play_paused_run_v1:a9'), true);
  assert.deepEqual(store.calls.filter(call => call[0] === 'remove'), [['remove', run.pausedRunStorageKey(firstScope)]]);
});

test('FIRST remains a local namespace and does not enter legacy server timer request bodies', async () => {
  const requests = [], request = (url, method, payload) => { requests.push({ url, method, payload }); return Promise.resolve({ code: 200 }); };
  const scope = { ...baseScope, instanceResolved: true, playInstanceId: FIRST };
  await run.saveServerPausedRun(request, scope, 15, 123);
  assert.deepEqual(requests[0].payload, { activityId: '9', elapsedSeconds: 15, savedAt: 123 });
  await run.clearServerRunSession(request, scope, 124);
  assert.deepEqual(requests[1].payload, { activityId: '9', savedAt: 124 });
});

test('hidden or unloaded delayed FIRST state cannot establish ready or restore its cache', async () => {
  for (const unload of [false, true]) {
    const wait = deferred(), f = fixture({ handlers: { '/api/play/theme/state': () => wait.promise } });
    const pending = f.ready(); await tick(); f.p._themeOnHide(unload);
    wait.resolve({ code: 200, data: view() }); assert.equal(await pending, false);
    assert.equal(f.p.data.themeRuntimeReady, false); assert.equal(run.pausedRunStorageKey(f.scope()), '');
  }
});

test('an old FIRST state cannot win after hide/show creates a fresh epoch for the same owner and token', async () => {
  const wait = deferred(), f = fixture({ handlers: { '/api/play/theme/state': () => wait.promise } });
  const old = f.ready(); await tick(); f.p._themeOnHide(false); f.p._themeOnShow();
  f.handlers['/api/play/theme/state'] = () => ({ code: 200, data: view({ version: 4 }) });
  assert.equal(await f.ready(), true);
  wait.resolve({ code: 200, data: view({ version: 99 }) }); assert.equal(await old, false);
  assert.equal(f.p.data.themeBag.version, 4); assert.equal(f.p._themeCacheInstance, FIRST);
});

test('same FIRST token for another account does not accept the prior owner response or cache', async () => {
  const f = fixture(); assert.equal(await f.ready(), true);
  const oldKey = run.pausedRunStorageKey(f.scope()), wait = deferred();
  f.handlers['/api/play/theme/state'] = () => wait.promise;
  const old = f.p.refreshThemeBackpack(); await tick(); f.owner(8);
  assert.equal(run.pausedRunStorageKey(f.scope()), ''); f.p._themeOnShow();
  f.handlers['/api/play/theme/state'] = () => ({ code: 200, data: view({ sessionId: 80, version: 1 }) });
  f.current.view = view({ sessionId: 80, version: 1 }); assert.equal(await f.ready(), true);
  const newKey = run.pausedRunStorageKey(f.scope()); assert.notEqual(newKey, oldKey);
  wait.resolve({ code: 200, data: view({ version: 99 }) }); assert.equal(await old, false);
  assert.equal(f.p.data.themeBag.sessionId, 80); assert.equal(run.pausedRunStorageKey(f.scope()), newKey);
});

test('FIRST replay cancel, hide, account change and unload never submit a delayed confirmation', async () => {
  for (const change of ['cancel', 'hide', 'owner', 'unload']) {
    const f = fixture({ confirm: 'manual' }); assert.equal(await f.ready(), true);
    const pending = f.p.requestThemeReplay(); await tick(); assert.equal(f.modals.length, 1);
    if (change === 'hide') f.p._themeOnHide(false);
    if (change === 'unload') f.p._themeOnHide(true);
    if (change === 'owner') f.owner(8);
    f.modals[0].success({ confirm: change !== 'cancel' }); assert.equal(await pending, false, change);
    assert.equal(f.posts().length, 0, change); assert.equal(f.p._themeReplayPending, null, change);
  }
});

test('a pre-replay FIRST read cannot resurrect FIRST after personal replay becomes current', async () => {
  const replay = view({ sessionId: 41, playInstanceId: 'replay:40', version: 0, status: 'ACTIVE' });
  const f = fixture({ handlers: { '/api/play/theme/replay': () => ({ code: 200, data: replay }) } });
  assert.equal(await f.ready(), true); const wait = deferred();
  f.handlers['/api/play/theme/state'] = () => wait.promise;
  const old = f.p.refreshThemeBackpack(); await tick(); assert.equal(await f.p.requestThemeReplay(), true);
  assert.deepEqual(f.posts()[0].data, { sessionId: 40 });
  assert.equal(f.p.data.themeBag.playInstanceId, 'replay:40'); assert.equal(f.p.data.themeRuntimeReady, false);
  wait.resolve({ code: 200, data: view({ version: 999 }) }); assert.equal(await old, false);
  f.current.view = replay; delete f.handlers['/api/play/theme/state']; assert.equal(await f.ready(), true);
  assert.equal(f.p.data.themeBag.sessionId, 41); assert.equal(f.p._themeCacheInstance, 'replay:40');
  assert.ok(run.pausedRunStorageKey(f.scope()).endsWith(':preplay%3A40'));
});

test('a post-replay FIRST response fails the new nodes session binding and retires its cache', async () => {
  const f = fixture({ view: { sessionId: 41, playInstanceId: 'replay:40', version: 0 } });
  assert.equal(await f.ready(), true);
  f.handlers['/api/play/theme/state'] = () => ({ code: 200, data: view({ version: 999 }) });
  assert.equal(await f.p.refreshThemeBackpack(), false);
  assert.equal(f.p.data.themeBag.sessionId, 41); assert.equal(f.p.data.themeRuntimeReady, false);
  assert.equal(f.p._themeAllowed(), false); assert.equal(run.pausedRunStorageKey(f.scope()), '');
  assert.equal(f.p.nodesRefreshes, 1);
});

test('hidden FIRST action response stays unknown and cannot rebind cache', async () => {
  const wait = deferred(), f = fixture({ handlers: { '/api/play/theme/action': () => wait.promise } });
  assert.equal(await f.ready(), true); const pending = f.p._themeCommand('USE', 'a'); await tick();
  const payload = clone(f.posts()[0].data); f.p._themeOnHide(false);
  wait.resolve({ code: 200, data: view({ version: 4 }) }); assert.equal(await pending, false);
  assert.equal(f.p.data.themeBag.version, 3); assert.equal(f.p.data.themeBagUnknown, true);
  assert.equal(run.pausedRunStorageKey(f.scope()), ''); assert.deepEqual(f.p._themePending, payload);
});

test('FIRST formatting does not satisfy the team-replay endpoint receipt contract', async () => {
  const f = fixture({ handlers: { '/api/play/theme/team/replay': () => ({ code: 200,
    data: view({ sessionId: 41, teamId: 50, teamRunId: 80 }) }) } });
  assert.equal(await f.ready(), true);
  f.p._themeReplayPending = { context: f.p._themeContext(), team: true,
    payload: Object.freeze({ teamId: 50, sourceSessionId: 40, previousTeamRunId: null }) };
  assert.equal(await f.p._sendThemeReplay(), false);
  assert.equal(f.p.data.themeReplayUnknown, true); assert.equal(f.p.data.themeBag.sessionId, 40);
  assert.equal(f.p.nodesRefreshes, undefined);
});
