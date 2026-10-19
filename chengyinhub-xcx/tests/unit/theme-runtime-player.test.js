'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const theme = require('../../pages/play/utils/theme-backpack.js');
const fs = require('node:fs');
const path = require('node:path');
const clone = v => JSON.parse(JSON.stringify(v));
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const view = (extra = {}) => Object.assign({ sessionId: 40, topicId: 10, activityId: 9, playInstanceId: 'default', status: 'ENDED', version: 3, contents: [], pending: {}, events: [] }, extra);
const teamRow = (extra = {}) => Object.assign({ id: 50, ownerType: 2, ownerId: 9, status: 1, leaderMemberId: 7 }, extra);
function fixture(options = {}) {
  let owner = 7; const calls = [], modals = [], timers = [];
  const handlers = options.handlers || {};
  const current = { view: view(options.view), teams: [teamRow()], info: { team: teamRow(), joined: true, leader: true } };
  const req = (url, method, data, header, silent) => {
    calls.push({ url, method, data: clone(data), header, silent });
    if (handlers[url]) return Promise.resolve().then(() => handlers[url](data));
    if (url.endsWith('/capabilities')) return Promise.resolve({ code: 200, data: { enabled: true } });
    if (url.endsWith('/state')) return Promise.resolve({ code: 200, data: clone(current.view) });
    if (url === '/api/team/my') return Promise.resolve({ code: 200, data: clone(current.teams) });
    if (url === '/api/team/info') return Promise.resolve({ code: 200, data: clone(current.info) });
    throw new Error('Unexpected request ' + url);
  };
  const p = Object.assign({ data: Object.assign(clone(theme.data), { topicId: 10, activityId: 9, nodes: [], chapter: {} }),
    setData(update) { Object.assign(this.data, clone(update)); }, _sessionParams() { return { activityId: this.data.activityId }; },
    refreshRouteState() { this.routeRefreshes = (this.routeRefreshes || 0) + 1; return Promise.resolve(true); },
    loadData() { this.nodesRefreshes = (this.nodesRefreshes || 0) + 1; },
    // Capture poll intent only. These tests execute the real helper's handlers, not a WeChat renderer.
  }, theme.methods(req, { getUserID: () => owner }, { show(o) { modals.push(o); if (options.confirm !== 'manual') o.success({ confirm: options.confirm !== false }); } }, options.nativeApi));
  p._startThemePoll = () => { timers.push('start'); }; p._stopThemePoll = () => { timers.push('stop'); };
  p._themeOnShow();
  async function ready() { await p._acceptThemeNodes({ themeRuntimeAvailable: true }, p._themeNodesRequest()); await new Promise(r => setImmediate(r)); }
  return { p, calls, current, handlers, modals, timers, ready, owner: n => { owner = n; }, posts: () => calls.filter(c => /replay$/.test(c.url)) };
}
test('non-theme nodes, mock and preview never probe capabilities/state/action/replay', async () => {
  for (const mode of ['legacy', 'isMock', 'isPreview']) { const f = fixture(); if (mode !== 'legacy') f.p.data[mode] = true;
    await f.p._acceptThemeNodes({ themeRuntimeAvailable: mode !== 'legacy' }, f.p._themeNodesRequest());
    await f.p.refreshThemeBackpack(); await f.p._themeCommand('USE', 'a'); await f.p.requestThemeReplay(); assert.equal(f.calls.length, 0); assert.equal(f.p.data.themeRuntimeReady, false); }
});
test('capability requires literal true and never probes state when disabled', async () => {
  for (const enabled of [false, 'true', 1, null]) { const f = fixture({ handlers: { '/api/play/theme/capabilities': () => ({ code: 200, data: { enabled } }) } }); await f.ready(); assert.equal(f.calls.length, 1); assert.equal(f.p._themeAllowed(), false); }
});
test('capability pending cannot enable a different account or hidden page', async () => {
  for (const change of ['owner', 'hide']) { const wait = deferred(); const f = fixture({ handlers: { '/api/play/theme/capabilities': () => wait.promise } });
    const pending = f.p._acceptThemeNodes({ themeRuntimeAvailable: true }, f.p._themeNodesRequest()); await Promise.resolve();
    if (change === 'owner') f.owner(8); else f.p._themeOnHide(false);
    wait.resolve({ code: 200, data: { enabled: true } }); await pending; assert.equal(f.calls.length, 1); assert.equal(f.p.data.themeRuntimeReady, false); }
});
test('state must belong to exact topic and activity, and old versions cannot regress', async () => {
  const f = fixture(); await f.ready(); assert.equal(f.p.data.themeBag.sessionId, 40);
  assert.equal(f.p._applyThemeView(view({ topicId: 11 })), false); assert.equal(f.p._applyThemeView(view({ activityId: 8 })), false);
  assert.equal(f.p._applyThemeView(view({ version: 2 })), false); assert.equal(f.p.data.themeBag.version, 3);
});
test('leader replay uses actual team endpoints and complete shared intent only', async () => {
  const f = fixture({ view: { teamReplayRequired: true }, handlers: { '/api/play/theme/team/replay': () => ({ code: 200, data: view({ sessionId: 41, teamReplayRequired: true, teamId: 50, teamRunId: 80, teamGeneration: 0, playInstanceId: 'team-replay:80', status: 'ACTIVE', version: 0 }) }) } });
  await f.ready(); assert.equal(await f.p.requestThemeReplay(), true);
  assert.equal(f.posts().length, 1); const request = f.posts()[0]; assert.equal(request.url, '/api/play/theme/team/replay');
  assert.deepEqual(Object.keys(request.data).sort(), ['actionId', 'activityId', 'previousTeamRunId', 'sourceSessionId', 'teamId', 'topicId'].sort());
  assert.equal(request.data.sourceSessionId, 40); assert.equal(request.data.previousTeamRunId, null); assert.equal(request.data.teamId, 50); assert.match(request.data.actionId, /^[A-Za-z0-9:_-]{1,80}$/);
  assert.equal(f.p.data.themeBag.playInstanceId, 'team-replay:80'); assert.equal(f.p.nodesRefreshes, 1); assert.equal(f.p._themeReplayPending, null);
  assert.ok(f.calls.some(c => c.url === '/api/team/my' && c.method === 'POST')); assert.ok(f.calls.some(c => c.url === '/api/team/info' && c.method === 'POST'));
});
test('solo replay retains old endpoint and session-only body', async () => {
  const f = fixture({ handlers: { '/api/play/theme/replay': () => ({ code: 200, data: view({ sessionId: 41, status: 'ACTIVE', version: 0 }) }) } });
  await f.ready(); assert.equal(await f.p.requestThemeReplay(), true); assert.deepEqual(f.posts()[0].data, { sessionId: 40 }); assert.equal(f.calls.some(c => c.url.startsWith('/api/team/')), false);
});
test('cancel and repeated taps do not create intents', async () => {
  const f = fixture({ confirm: 'manual' }); await f.ready(); const first = f.p.requestThemeReplay(); await Promise.resolve(); await Promise.resolve();
  assert.equal(await f.p.requestThemeReplay(), false); assert.equal(f.modals.length, 1); f.modals[0].success({ confirm: false });
  assert.equal(await first, false); assert.equal(f.posts().length, 0); assert.equal(f.p._themeReplayPending, null);
});
test('nonleader and ambiguous current teams cannot send either replay endpoint', async () => {
  for (const kind of ['nonleader', 'ambiguous', 'wrongActivity', 'disbanded', 'left']) {
    const f = fixture({ view: { teamReplayRequired: true } });
    if (kind === 'nonleader') { f.current.info.leader = false; f.current.info.team.leaderMemberId = 8; }
    if (kind === 'ambiguous') f.current.teams.push(teamRow({ id: 51 }));
    if (kind === 'wrongActivity') f.current.info.team.ownerId = 8;
    if (kind === 'disbanded') f.current.info.team.status = 2;
    if (kind === 'left') f.current.info.joined = false;
    await f.ready(); assert.equal(await f.p.requestThemeReplay(), false, kind); assert.equal(f.posts().length, 0, kind); assert.equal(f.modals.length, 0, kind);
  }
});
test('a state-bound team never silently falls back to another activity team', async () => {
  const f = fixture({ view: { teamReplayRequired: true, teamId: 99 } }); await f.ready(); await f.p.requestThemeReplay(); assert.equal(f.posts().length, 0); assert.equal(f.p.data.themeTeamLeader, false);
});
test('unknown HTTP/network replay retains same actionId and full payload even after state advances', async () => {
  for (const failure of [{ netFail: true }, { httpFail: true, upstreamCode: 200 }]) {
    let count = 0; const f = fixture({ view: { teamReplayRequired: true, teamId: 50, teamRunId: 79 }, handlers: { '/api/play/theme/team/replay': () => ++count === 1 ? failure : { code: 200, data: view({ sessionId: 41, teamId: 50, teamRunId: 80, previousTeamRunId: 79, playInstanceId: 'team-replay:80', status: 'ACTIVE' }) } } });
    await f.ready(); await f.p.requestThemeReplay(); const original = clone(f.posts()[0].data); assert.equal(f.p.data.themeReplayUnknown, true);
    f.current.view = view({ sessionId: 41, teamReplayRequired: true, teamId: 50, teamRunId: 80, status: 'ACTIVE' }); await f.p.refreshThemeBackpack();
    assert.equal(await f.p.retryThemeReplay(), true); assert.deepEqual(f.posts()[1].data, original); assert.equal(f.posts().length, 2);
  }
});
test('409 refreshes without automatic resubmission, new explicit confirmation creates new actionId', async () => {
  const f = fixture({ view: { teamReplayRequired: true }, handlers: { '/api/play/theme/team/replay': () => ({ code: 409, msg: 'changed' }) } });
  await f.ready(); await f.p.requestThemeReplay(); assert.equal(f.posts().length, 1); assert.equal(f.p._themeReplayPending, null); assert.equal(f.p.data.themeReplayUnknown, false);
  await f.p.retryThemeReplay(); assert.equal(f.posts().length, 1); await f.p.requestThemeReplay(); assert.equal(f.posts().length, 2); assert.equal(f.modals.length, 2); assert.notEqual(f.posts()[0].data.actionId, f.posts()[1].data.actionId);
});
test('unknown group replay blocks item commands and drops intent when membership ends', async () => {
  const f = fixture({ view: { teamReplayRequired: true }, handlers: { '/api/play/theme/team/replay': () => ({ netFail: true }) } });
  await f.ready(); await f.p.requestThemeReplay(); await f.p._themeCommand('USE', 'a'); assert.equal(f.calls.some(c => c.url.endsWith('/action')), false);
  f.current.teams = []; await f.p.retryThemeReplay(); assert.equal(f.posts().length, 1); assert.equal(f.p._themeReplayPending, null); assert.equal(f.p.data.themeReplayUnknown, false);
});
test('account change and unload clear intents; delayed confirmations never submit', async () => {
  for (const kind of ['owner', 'unload']) {
    const f = fixture({ confirm: 'manual' }); await f.ready(); const pending = f.p.requestThemeReplay(); await Promise.resolve(); await Promise.resolve();
    if (kind === 'owner') f.owner(8); else f.p._themeOnHide(true);
    f.modals[0].success({ confirm: true }); assert.equal(await pending, false); assert.equal(f.posts().length, 0);
    f.p._themeAllowed(); assert.equal(f.p._themeReplayPending, null);
  }
});
test('hidden replay response is ignored; visible retry uses the original intent', async () => {
  const wait = deferred(); let count = 0; const f = fixture({ view: { teamReplayRequired: true }, handlers: { '/api/play/theme/team/replay': () => ++count === 1 ? wait.promise : { code: 200, data: view({ sessionId: 41, teamId: 50, teamRunId: 80, previousTeamRunId: 79, playInstanceId: 'team-replay:80', status: 'ACTIVE' }) } } });
  await f.ready(); const pending = f.p.requestThemeReplay(); while (f.posts().length === 0) await new Promise(r => setImmediate(r));
  const original = clone(f.posts()[0].data); f.p._themeOnHide(false); wait.resolve({ code: 200, data: view({ sessionId: 41, teamId: 50, teamRunId: 80 }) }); await pending;
  assert.equal(f.p.data.themeBag.sessionId, 40); assert.equal(f.p.data.themeReplayUnknown, true);
  f.p._themeOnShow(); await f.ready(); assert.equal(await f.p.retryThemeReplay(), true); assert.deepEqual(f.posts()[1].data, original);
});
test('old finally cannot release a newer item request busy lock', async () => {
  const old = deferred(), fresh = deferred(); let n = 0;
  const f = fixture({ handlers: { '/api/play/theme/action': () => ++n === 1 ? old.promise : fresh.promise } }); await f.ready();
  const a = f.p._themeCommand('USE', 'a'); await Promise.resolve(); f.p._themeOnHide(false); f.p._themeOnShow(); await f.ready();
  const b = f.p.retryThemeBagAction(); await Promise.resolve(); const lock = f.p._themeSending;
  old.resolve({ code: 200, data: view({ version: 4 }) }); await a; assert.equal(f.p._themeSending, lock); assert.equal(f.p.data.themeBagBusy, true);
  fresh.resolve({ code: 200, data: view({ version: 4 }) }); assert.equal(await b, true); assert.equal(f.p.data.themeBagBusy, false);
});
test('item unknown retry keeps payload and rejects a different operation', async () => {
  const f = fixture({ handlers: { '/api/play/theme/action': () => ({ httpFail: true }) } }); await f.ready(); await f.p._themeCommand('USE', 'a');
  const old = clone(f.calls.find(c => c.url.endsWith('/action')).data); await f.p._themeCommand('DISCARD', 'a'); await f.p.retryThemeBagAction();
  const actions = f.calls.filter(c => c.url.endsWith('/action')); assert.equal(actions.length, 2); assert.deepEqual(actions[1].data, old); assert.equal(f.p.data.themeBagUnknown, true);
});
test('stale pre-write state response cannot overwrite command result', async () => {
  const wait = deferred(); const f = fixture({ handlers: { '/api/play/theme/action': () => ({ code: 200, data: view({ version: 4 }) }) } }); await f.ready();
  f.handlers['/api/play/theme/state'] = () => wait.promise; const read = f.p.refreshThemeBackpack(); await f.p._themeCommand('USE', 'a');
  wait.resolve({ code: 200, data: view({ version: 3 }) }); assert.equal(await read, false); assert.equal(f.p.data.themeBag.version, 4);
});
test('chapter end never bypasses unfinished nodes; chapter start dedupe is per session', async () => {
  const f = fixture({ view: { chapterIndexes: [0], chapterBindings: [{ chapterId: 5, index: 0 }] }, handlers: { '/api/play/theme/action': () => ({ code: 200, data: clone(f.current.view) }) } }); await f.ready();
  f.p.data.chapter = { id: 5 }; f.p.data.nodes = [{ chapterId: 5, done: false }]; assert.equal(await f.p._endThemeChapter(), true); assert.equal(f.calls.some(c => c.url.endsWith('/action')), false);
  f.p._enterThemeChapter(); await new Promise(r => setImmediate(r)); f.p._enterThemeChapter(); await new Promise(r => setImmediate(r)); assert.equal(f.calls.filter(c => c.url.endsWith('/action')).length, 1);
  f.current.view.sessionId = 41; f.p._applyThemeView(f.current.view); f.p._enterThemeChapter(); await new Promise(r => setImmediate(r)); assert.equal(f.calls.filter(c => c.url.endsWith('/action')).length, 2);
});
test('presentation keeps item/clue/pending data while bounding malformed slot count', () => {
  const result = theme.present(view({ capacity: 6, contents: [{ id: 'a', kind: 'ITEM', stat: 'HP', quantity: 2 }, { id: 'c', kind: 'CLUE', quantity: 1 }], events: [{ id: 'e', content: 'text' }], pending: { gift: { name: 'Gift', quantity: 1 } } }));
  assert.equal(result.slots.length, 6); assert.equal(result.slots.filter(x => x.filled).length, 2); assert.equal(result.items.length, 1); assert.equal(result.clues.length, 2); assert.equal(result.pendingRows[0].name, 'Gift');
  assert.equal(theme.present(view({ capacity: 1e10 })).slots.length, 100);
});
test('actual page wires lifecycle, eligible nodes, modal host and sheet events', () => {
  const base = path.resolve(__dirname, '../../pages/play'); const js = fs.readFileSync(path.join(base, 'index.js'), 'utf8');
  assert.match(js, /themeBackpack\.methods\(req, app, cyModal\)/); assert.match(js, /_acceptThemeNodes\(d, themeRequest\)/); assert.match(js, /_themeOnHide\(true\)/); assert.match(js, /_themeOnHide\(false\)/); assert.match(js, /_themeOnShow\(\)/);
  const sheet = fs.readFileSync(path.join(base, 'theme-backpack.wxml'), 'utf8'); assert.match(sheet, /bindtap="requestThemeReplay"/); assert.match(sheet, /bind:action="onThemeReplayError"/); assert.match(sheet, /wx:if="\{\{themeRuntimeReady\}\}"/);
  assert.equal(JSON.parse(fs.readFileSync(path.join(base, 'index.json'))).usingComponents['cy-sheet'], '/components/cy/sheet/index');
});
test('poll starts once, is paused on hide, and never runs with disabled capability', async () => {
  const vm = require('node:vm'); const source = fs.readFileSync(path.resolve(__dirname, '../../pages/play/utils/theme-backpack.js'), 'utf8');
  let interval, starts = 0, stops = 0, requests = 0;
  const box = { require: require('node:module').createRequire(path.resolve(__dirname, '../../pages/play/utils/theme-backpack.js')), module: { exports: {} }, setInterval(fn, delay) { assert.equal(delay, 30000); starts++; interval = fn; return 123; }, clearInterval(id) { assert.equal(id, 123); stops++; } };
  vm.runInNewContext(source, box);
  const runtime = box.module.exports; const p = Object.assign({ data: Object.assign(clone(runtime.data), { topicId: 10, activityId: 9, themeBag: view({ status: 'ACTIVE' }) }), setData(d) { Object.assign(this.data, d); }, _sessionParams() { return { activityId: 9 }; } }, runtime.methods(() => { requests++; return Promise.resolve({ code: 200, data: view({ status: 'ACTIVE' }) }); }, { getUserID: () => 7 }, {}));
  p._themeIdentity = p._themeContext(); p._themeHidden = false; p._themeEligible = true; p._themeEnabled = false;
  p._startThemePoll(); assert.equal(starts, 0); p._themeEnabled = true; p._startThemePoll(); p._startThemePoll(); assert.equal(starts, 1);
  interval(); await new Promise(r => setImmediate(r)); assert.equal(requests, 1); p._themeOnHide(false); assert.equal(stops, 1);
  interval(); await new Promise(r => setImmediate(r)); assert.equal(requests, 1);
});
test('replay between nodes and state requests a whole-screen reload without mixing inventory', async () => {
  const f = fixture({ view: { sessionId: 41 } });
  const ok = await f.p._acceptThemeNodes({ themeRuntimeAvailable: true, routeState: { sessionId: 40 } }, f.p._themeNodesRequest());
  assert.equal(ok, false); assert.equal(f.p.nodesRefreshes, 1); assert.equal(f.p.data.themeBag.sessionId, undefined); assert.equal(f.p._themeAllowed(), false);
});
test('malformed success cannot claim a shared replay succeeded', async () => {
  const f = fixture({ view: { teamReplayRequired: true }, handlers: { '/api/play/theme/team/replay': () => ({ code: 200, data: view({ sessionId: 41, teamId: 50, teamRunId: 80, playInstanceId: 'default' }) }) } });
  await f.ready(); assert.equal(await f.p.requestThemeReplay(), false); assert.equal(f.p.data.themeReplayUnknown, true); assert.ok(f.p._themeReplayPending); assert.equal(f.p.nodesRefreshes, undefined);
});
test('old team authorization cannot survive a confirmed logout failure', async () => {
  const f = fixture({ view: { teamReplayRequired: true }, handlers: { '/api/play/theme/team/replay': () => ({ netFail: true }) } });
  await f.ready(); await f.p.requestThemeReplay(); f.handlers['/api/team/my'] = () => ({ code: 403, msg: '请先登录' });
  await f.p.retryThemeReplay(); assert.equal(f.p._themeReplayPending, null); assert.equal(f.posts().length, 1); assert.equal(f.p.data.themeTeamLeader, false);
});
test('actual WeRun sync checks the theme owner epoch before posting and applying its response', async () => {
  const js = fs.readFileSync(path.resolve(__dirname, '../../pages/play/index.js'), 'utf8');
  const start = js.indexOf('  _syncThoughts(claim, themeGuard) {'), end = js.indexOf('  /* 故事流声音', start);
  assert.ok(start > 0 && end > start); const raw = js.slice(start, end); const sent = [];
  const method = Function('req', 'return ({' + raw + '});')((...args) => { sent.push(args); return Promise.resolve({ code: 200, data: {} }); })._syncThoughts;
  const f = fixture(); await f.ready(); const cipher = deferred(); f.p._weRunCipher = () => cipher.promise;
  const pending = method.call(f.p, [], f.p._themeSyncGuard()); f.p._themeOnHide(false); cipher.resolve({ encryptedData: 'test-fixture' }); await pending; assert.equal(sent.length, 0);
});
const runCache = require('../../pages/play/utils/play-run-session.js');
const storyCache = require('../../pages/play/utils/story-resume.js');
function storageFixture() {
  const map = new Map(), calls = [];
  return { map, calls, wx: { getStorageSync(k) { calls.push(['read', k]); return map.get(k); }, setStorageSync(k, v) { calls.push(['write', k]); map.set(k, clone(v)); }, removeStorageSync(k) { calls.push(['remove', k]); map.delete(k); } } };
}
test('legacy local keys are byte-identical; authoritative theme instances add distinct optional namespaces', () => {
  const scope = { memberId: 7, topicId: 10, activityId: 9 };
  assert.equal(runCache.pausedRunStorageKey(scope), 'play_paused_run_v2:m7:a9');
  assert.equal(runCache.pausedRunStorageKey({ memberId: 7, topicId: 10 }), 'play_paused_run_v2:m7:t10');
  const keys = ['default', 'replay:40', 'team-replay:80', 'team-replay:81'].map(playInstanceId => runCache.pausedRunStorageKey({ ...scope, instanceResolved: true, playInstanceId }));
  assert.equal(new Set(keys).size, 4); assert.ok(keys.every(k => k !== runCache.pausedRunStorageKey(scope)));
  assert.notEqual(keys[2], runCache.pausedRunStorageKey({ ...scope, memberId: 8, playInstanceId: 'team-replay:80' }));
  assert.notEqual(keys[2], runCache.pausedRunStorageKey({ ...scope, activityId: 11, playInstanceId: 'team-replay:80' }));
});
test('unknown or malformed instance yields no local key, never a legacy fallback', () => {
  const scope = { memberId: 7, activityId: 9 };
  for (const value of [null, '', 'replay:0', 'team-replay:-1', 'anything', '../default']) assert.equal(runCache.pausedRunStorageKey({ ...scope, playInstanceId: value }), '');
  assert.equal(runCache.pausedRunStorageKey({ ...scope, instanceResolved: false }), '');
  assert.equal(runCache.pausedRunStorageKey({ ...scope, playInstanceId: 'default', instanceResolved: false }), '');
});
test('theme local namespace never leaks into existing server timer request bodies', async () => {
  const requests = [], request = (url, method, payload) => { requests.push({ url, method, payload }); return Promise.resolve({ code: 200 }); };
  const old = { memberId: 7, activityId: 9, topicId: 10 }, scoped = { ...old, instanceResolved: false, playInstanceId: 'team-replay:80' };
  assert.deepEqual(runCache.runSessionRequestScope(scoped), { activityId: '9' });
  await runCache.saveServerPausedRun(request, old, 15, 123); await runCache.saveServerPausedRun(request, scoped, 15, 123); assert.deepEqual(requests[0], requests[1]);
  await runCache.clearServerRunSession(request, scoped, 123); assert.deepEqual(requests[2].payload, { savedAt: 123, activityId: '9' });
});
test('story and draft caches isolate generations while preserving every old record', () => {
  const store = storageFixture(), oldWx = global.wx; global.wx = store.wx;
  try {
    const base = { memberId: 7, activityId: 9 }, one = { ...base, instanceResolved: true, playInstanceId: 'team-replay:80' }, two = { ...base, instanceResolved: true, playInstanceId: 'team-replay:81' };
    storyCache.patch(base, { intro: true, pos: { i: 9 } }); storyCache.patch(one, { intro: true, gates: [5], openNodeId: '11' });
    const draftOne = storyCache.draftKey(one, 11), draftTwo = storyCache.draftKey(two, 11);
    store.wx.setStorageSync(draftOne, { answer: 'first run draft' }); assert.notEqual(draftOne, draftTwo);
    assert.deepEqual(storyCache.read(two), {}); assert.equal(store.wx.getStorageSync(draftTwo), undefined);
    storyCache.patch(two, { intro: false }); store.wx.setStorageSync(draftTwo, { answer: 'second run draft' });
    assert.equal(storyCache.read(one).openNodeId, '11'); assert.equal(store.wx.getStorageSync(draftOne).answer, 'first run draft'); assert.equal(storyCache.read(base).pos.i, 9);
    assert.equal(store.calls.filter(c => c[0] === 'remove').length, 0);
  } finally { global.wx = oldWx; }
});
test('pending instance neither reads nor writes nor deletes any persisted story or paused record', () => {
  const store = storageFixture(), oldWx = global.wx; global.wx = store.wx;
  try {
    const unresolved = { memberId: 7, activityId: 9, instanceResolved: false };
    assert.deepEqual(storyCache.read(unresolved), {}); storyCache.patch(unresolved, { intro: true }); assert.equal(storyCache.draftKey(unresolved, 11), '');
    assert.equal(runCache.readPausedRun(store.wx, unresolved), null); assert.equal(runCache.writePausedRun(store.wx, unresolved, 10, 123), false); assert.equal(runCache.clearRunSession(store.wx, unresolved), false); assert.deepEqual(store.calls, []);
  } finally { global.wx = oldWx; }
});
test('scoped paused read/clear does not retire legacy or another theme run cache', () => {
  const store = storageFixture(); const one = { memberId: 7, activityId: 9, playInstanceId: 'team-replay:80' }, two = { ...one, playInstanceId: 'team-replay:81' };
  store.map.set('play_paused_run_v1:a9', { legacy: true }); runCache.writePausedRun(store.wx, one, 1, 100); runCache.writePausedRun(store.wx, two, 2, 101);
  assert.equal(runCache.readPausedRun(store.wx, two).elapsedSeconds, 2); runCache.clearRunSession(store.wx, two);
  assert.equal(runCache.readPausedRun(store.wx, one).elapsedSeconds, 1); assert.equal(store.map.has('play_paused_run_v1:a9'), true);
});
test('real player binds cache only after authoritative state and retires references on hide/account switch', async () => {
  const f = fixture({ view: { playInstanceId: 'team-replay:80', teamId: 50, teamRunId: 80 } }); const scope = { memberId: 7, activityId: 9 };
  let retired = 0; f.p._retireThemeDraftReference = () => { retired++; };
  assert.equal(runCache.pausedRunStorageKey(f.p._themeStoryScope(scope)), ''); await f.ready();
  const oldKey = runCache.pausedRunStorageKey(f.p._themeStoryScope(scope)); assert.match(oldKey, /team-replay%3A80$/);
  f.p._themeOnHide(false); assert.equal(retired, 1); assert.equal(runCache.pausedRunStorageKey(f.p._themeStoryScope(scope)), '');
  f.p._themeOnShow(); f.current.view = view({ sessionId: 41, playInstanceId: 'team-replay:81', teamId: 50, teamRunId: 81 }); await f.ready();
  const newKey = runCache.pausedRunStorageKey(f.p._themeStoryScope(scope)); assert.match(newKey, /team-replay%3A81$/); assert.notEqual(oldKey, newKey);
  f.owner(8); assert.equal(runCache.pausedRunStorageKey(f.p._themeStoryScope(scope)), '');
});
test('legacy nodes restore only the existing namespace and never retire their draft reference', async () => {
  const f = fixture(); let retired = 0; f.p._retireThemeDraftReference = () => { retired++; };
  const scope = { memberId: 7, activityId: 9 }; await f.p._acceptThemeNodes({}, f.p._themeNodesRequest());
  assert.equal(f.p._themeStoryScope(scope), scope); assert.equal(runCache.pausedRunStorageKey(f.p._themeStoryScope(scope)), 'play_paused_run_v2:m7:a9'); assert.equal(retired, 0);
  f.p._themeOnHide(false); f.p._themeOnShow(); await f.p._acceptThemeNodes({}, f.p._themeNodesRequest()); assert.equal(retired, 0);
});
test('actual page uses separate story-only scope and waits for theme resolution before story restoration', () => {
  const js = fs.readFileSync(path.resolve(__dirname, '../../pages/play/index.js'), 'utf8');
  assert.match(js, /_storyResumeScope\(\) \{ return this\._themeStoryScope\(this\._runSessionScope\(\)\); \}/);
  assert.doesNotMatch(js, /storyResume\.(read|patch|draftKey)\(this\._runSessionScope\(/);
  assert.match(js, /themeLoad\.then\(ok => \{ if \(ok && that\._themeAllowed\(\)\) openCurrentStory\(\); \}\)/);
  assert.match(js, /_runSessionScope\(\) \{\s*return \{ memberId: app\.getUserID\(\), activityId: this\.data\.activityId, topicId: this\.data\.topicId \};/);
});
test('actual page retires prior run transient references once without erasing newly read current variables', () => {
  const js = fs.readFileSync(path.resolve(__dirname, '../../pages/play/index.js'), 'utf8');
  const start = js.indexOf('  _resetThemeRunUi(sessionId) {'), end = js.indexOf('  _storyResumeScope()', start);
  const reset = Function('return ({' + js.slice(start, end) + '});')()._resetThemeRunUi;
  const p = { data: {}, _seenChapterGates: new Set(['10:5']), _storyVars: { name: 'old' }, _storyVoices: { 11: ['old'] },
    _profileStoryVarsRefreshed: true, _routeActions: { clear() {} }, _stopPlayKitAudio() {}, _hideFinish() {}, setData(d) { Object.assign(this.data, d); } };
  reset.call(p, 41); assert.equal(p._seenChapterGates.size, 0); assert.deepEqual(p._storyVars, {}); assert.deepEqual(p._storyVoices, {}); assert.equal(p._profileStoryVarsRefreshed, false); assert.equal(p.data.playKit.kit, null);
  p._storyVars = { name: 'new-current-response' }; reset.call(p, 41); assert.equal(p._storyVars.name, 'new-current-response');
  assert.ok(js.indexOf('that._resetThemeRunUi(d.routeState.sessionId)') < js.indexOf('that._applyStoryVars(d)'));
});
const endedAt = Date.UTC(2026, 9, 8, 18, 30);
function historyRow(id, extra = {}) { return Object.assign(view({ sessionId: String(id), sceneName: '场景', status: 'ENDED', result: { endedAt } }), extra); }
function publicCard(extra = {}) { return Object.assign({ success: false, endedAt, title: '公开结果', summary: '结束说明' }, extra); }
async function readyRead(f) { await f.ready(); f.p.data.themeBackpackOpen = true; f.calls.length = 0; }
test('history/card are gated reads: hidden/disabled/mock/preview/closed sheet sends nothing', async () => {
  for (const mode of ['disabled', 'mock', 'preview', 'hidden', 'closed']) {
    const f = fixture({ view: { result: { endedAt } } }); await readyRead(f);
    if (mode === 'disabled') f.p._themeEnabled = false; if (mode === 'mock') f.p.data.isMock = true; if (mode === 'preview') f.p.data.isPreview = true;
    if (mode === 'hidden') f.p._themeOnHide(false); if (mode === 'closed') f.p.data.themeBackpackOpen = false;
    f.p.data.themeBackpackTab = 'history'; await f.p.loadThemeHistory(); await f.p.openCurrentThemeCard(); assert.equal(f.calls.length, 0, mode);
  }
});
test('history uses real descending cursor pagination, never treats twenty as all records', async () => {
  const pages = [Array.from({ length: 20 }, (_, i) => historyRow(1000 - i)), Array.from({ length: 20 }, (_, i) => historyRow(980 - i)), [historyRow(960)]];
  const f = fixture({ handlers: { '/api/play/theme/history': () => ({ code: 200, data: pages.shift() }) } }); await readyRead(f);
  assert.equal(await f.p.changeThemeBackpackTab({ detail: { key: 'history' } }), true); assert.equal(f.p.data.themeHistoryRows.length, 20); assert.equal(f.p.data.themeHistoryHasMore, true);
  await f.p.loadThemeHistory(); assert.equal(f.p.data.themeHistoryRows.length, 40); assert.equal(f.p.data.themeHistoryHasMore, true);
  await f.p.loadThemeHistory(); assert.equal(f.p.data.themeHistoryRows.length, 41); assert.equal(f.p.data.themeHistoryHasMore, false);
  await f.p.loadThemeHistory(); assert.deepEqual(f.calls.map(c => c.data), [{ topicId: 10, limit: 20 }, { topicId: 10, limit: 20, beforeSessionId: '981' }, { topicId: 10, limit: 20, beforeSessionId: '961' }]);
  assert.ok(f.calls.every(c => c.url === '/api/play/theme/history' && c.method === 'GET'));
});
test('history pagination failure retains the visible page and retries identical cursor', async () => {
  let n = 0; const f = fixture({ handlers: { '/api/play/theme/history': () => ++n === 1 ? { code: 200, data: Array.from({ length: 20 }, (_, i) => historyRow(100 - i)) } : n === 2 ? { netFail: true } : { code: 200, data: [] } } });
  await readyRead(f); await f.p.changeThemeBackpackTab({ detail: { key: 'history' } }); await f.p.loadThemeHistory();
  assert.equal(f.p.data.themeHistoryRows.length, 20); assert.equal(f.p.data.themeHistoryCursor, '81'); assert.ok(f.p.data.themeHistoryError); await f.p.loadThemeHistory();
  assert.deepEqual(f.calls[1].data, f.calls[2].data); assert.equal(f.p.data.themeHistoryRows.length, 20); assert.equal(f.p.data.themeHistoryHasMore, false);
});
test('history labels self-play and different activities and safely preserves long string ids', () => {
  const rows = theme.presentHistoryPage([historyRow('9007199254740995', { activityId: 9 }), historyRow('9007199254740994', { activityId: 12 }), historyRow('9007199254740993', { activityId: 0 })], 10, '');
  assert.deepEqual(rows.map(x => x.activityLabel), ['场次 9', '场次 12', '自玩']); assert.equal(rows[2].sessionId, '9007199254740993'); assert.equal(rows[0].endedAtText, '2026-10-09 02:30');
  assert.throws(() => theme.presentHistoryPage([historyRow(40, { sessionId: 9007199254740992 })], 10, ''));
});
test('bad history topic, cursor direction, duplicates and oversized pages fail before display', () => {
  for (const raw of [[historyRow(100, { topicId: 11 })], [historyRow(100), historyRow(100)], [historyRow(100), historyRow(101)], Array.from({ length: 21 }, (_, i) => historyRow(100 - i))]) assert.throws(() => theme.presentHistoryPage(raw, 10, ''));
  assert.throws(() => theme.presentHistoryPage([historyRow(101)], 10, '100'));
  const rows = theme.presentHistoryPage([historyRow(99, { status: 'ACTIVE' }), historyRow(98, { status: 'COMPLETED', result: null })], 10, '100');
  assert.equal(rows[0].hasCard, false); assert.equal(rows[1].hasCard, false); assert.equal(rows[1].statusLabel, '结果尚未结算');
});
test('current result renders only public card response, never substitutes raw result or hidden stats', async () => {
  const f = fixture({ view: { result: { endedAt, title: 'PRIVATE RAW TITLE', hp: 999, catalog: ['private'] } }, handlers: { '/api/play/theme/card': () => ({ code: 200, data: publicCard({ secret: 'do-not-render', inventory: ['private'] }) }) } });
  await readyRead(f); const before = clone(f.p.data.themeBag); assert.equal(await f.p.openCurrentThemeCard(), true);
  assert.equal(f.p.data.themeCard.title, '公开结果'); assert.deepEqual(f.p.data.themeCard.stats, []); assert.equal(JSON.stringify(f.p.data.themeCard).includes('PRIVATE'), false); assert.equal(JSON.stringify(f.p.data.themeCard).includes('do-not-render'), false);
  assert.deepEqual(f.p.data.themeBag, before); assert.deepEqual(f.calls.map(c => ({ url: c.url, method: c.method, data: c.data })), [{ url: '/api/play/theme/card', method: 'GET', data: { sessionId: '40' } }]);
});
test('history selection never changes current bag or pending replay intent', async () => {
  const f = fixture({ handlers: { '/api/play/theme/history': () => ({ code: 200, data: [historyRow(30, { activityId: 12 })] }), '/api/play/theme/card': () => ({ code: 200, data: publicCard() }) } });
  await readyRead(f); await f.p.changeThemeBackpackTab({ detail: { key: 'history' } });
  const bag = clone(f.p.data.themeBag), pending = { payload: Object.freeze({ sessionId: 40 }), context: f.p._themeContext(), team: false }; f.p._themeReplayPending = pending;
  assert.equal(await f.p.openHistoryThemeCard({ currentTarget: { dataset: { sessionid: '30' } } }), true);
  assert.equal(f.p.data.themeCardTarget.activityLabel, '场次 12'); assert.deepEqual(f.p.data.themeBag, bag); assert.equal(f.p._themeReplayPending, pending); assert.equal(f.calls.some(c => c.method !== 'GET'), false);
  f.p.closeThemeCard(); assert.equal(f.p.data.themeBackpackTab, 'history'); assert.equal(f.p.data.themeHistoryRows.length, 1); assert.equal(f.p.data.themeCardShow, false);
});
test('nonselected id and unfinished history cannot be used to fetch a card', async () => {
  const f = fixture(); await readyRead(f); f.p.data.themeHistoryRows = theme.presentHistoryPage([historyRow(30, { status: 'ACTIVE' })], 10, '');
  assert.equal(await f.p.openHistoryThemeCard({ currentTarget: { dataset: { sessionid: '30' } } }), false); assert.equal(await f.p.openHistoryThemeCard({ currentTarget: { dataset: { sessionid: '999' } } }), false); assert.equal(f.calls.length, 0);
});
test('latest card selection wins and old finally cannot release newer loading flag', async () => {
  const a = deferred(), b = deferred(); const f = fixture({ handlers: { '/api/play/theme/card': p => p.sessionId === '30' ? a.promise : b.promise } }); await readyRead(f);
  f.p.data.themeHistoryRows = theme.presentHistoryPage([historyRow(30), historyRow(29)], 10, '');
  const first = f.p.openHistoryThemeCard({ currentTarget: { dataset: { sessionid: '30' } } }); const second = f.p.openHistoryThemeCard({ currentTarget: { dataset: { sessionid: '29' } } });
  a.resolve({ code: 200, data: publicCard({ title: 'old-response' }) }); await first; assert.equal(f.p.data.themeCardBusy, true); assert.equal(f.p.data.themeCard, null);
  b.resolve({ code: 200, data: publicCard({ title: 'selected' }) }); await second; assert.equal(f.p.data.themeCard.title, 'selected'); assert.equal(f.p.data.themeCardTarget.sessionId, '29'); assert.equal(f.p.data.themeCardBusy, false);
});
test('back and sheet close cancel card responses without reopening any panel', async () => {
  for (const close of ['closeThemeCard', 'closeThemeBackpack']) {
    const wait = deferred(), f = fixture({ view: { result: { endedAt } }, handlers: { '/api/play/theme/card': () => wait.promise } }); await readyRead(f);
    const pending = f.p.openCurrentThemeCard(); f.p[close](); wait.resolve({ code: 200, data: publicCard() }); await pending; assert.equal(f.p.data.themeCardShow, false); assert.equal(f.p.data.themeCard, null); assert.equal(f.p.data.themeCardBusy, false);
  }
});
test('hide, account switch and topic switch reject late history responses', async () => {
  for (const change of ['hide', 'owner', 'topic']) {
    const wait = deferred(), f = fixture({ handlers: { '/api/play/theme/history': () => wait.promise } }); await readyRead(f);
    const pending = f.p.changeThemeBackpackTab({ detail: { key: 'history' } });
    if (change === 'hide') f.p._themeOnHide(false); if (change === 'owner') { f.owner(8); f.p._themeOnShow(); } if (change === 'topic') { f.p.data.topicId = 11; f.p._themeOnShow(); }
    wait.resolve({ code: 200, data: [historyRow(30)] }); assert.equal(await pending, false); assert.deepEqual(f.p.data.themeHistoryRows, []);
  }
});
test('leaving history tab cancels in-flight page; returning performs a fresh read', async () => {
  const wait = deferred(); let n = 0; const f = fixture({ handlers: { '/api/play/theme/history': () => ++n === 1 ? wait.promise : { code: 200, data: [] } } }); await readyRead(f);
  const pending = f.p.changeThemeBackpackTab({ detail: { key: 'history' } }); await f.p.changeThemeBackpackTab({ detail: { key: 'items' } }); wait.resolve({ code: 200, data: [historyRow(30)] }); await pending;
  assert.deepEqual(f.p.data.themeHistoryRows, []); await f.p.changeThemeBackpackTab({ detail: { key: 'history' } }); assert.equal(n, 2); assert.equal(f.p.data.themeHistoryLoaded, true);
});
test('card error has an actual fixed-target retry and never falls back to private state', async () => {
  let count = 0; const f = fixture({ view: { result: { endedAt, title: 'private' } }, handlers: { '/api/play/theme/card': () => ++count === 1 ? { code: 200, data: { title: 'bad-shape' } } : { code: 200, data: publicCard() } } }); await readyRead(f);
  assert.equal(await f.p.openCurrentThemeCard(), false); assert.equal(f.p.data.themeCard, null); assert.ok(f.p.data.themeCardError); assert.equal(await f.p.retryThemeCard(), true); assert.deepEqual(f.calls[0].data, f.calls[1].data);
});
test('card preserves zero-valued enabled fields and labels nextScene as current published arrangement', () => {
  const c = theme.presentCard(publicCard({ hp: 0, hpName: '能量', actions: 0, nextScene: { name: '当前新幕', asOf: endedAt }, epilogues: [{ name: '角色', line: '尾声', npcId: 77 }], exhibits: ['一段展签'], unlocks: { total: 3, known: [{ id: 'hidden-id', title: '已知结局' }] } }));
  assert.deepEqual(c.stats, [{ key: 'hp', label: '能量', value: 0 }, { key: 'actions', label: '行动', value: 0 }]); assert.equal(c.nextScene.name, '当前新幕'); assert.equal(c.nextScene.asOfText, '2026-10-09 02:30'); assert.equal(c.epilogues[0].npcId, undefined); assert.equal(c.unlocks.known[0].id, undefined);
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/theme-backpack.wxml'), 'utf8'); assert.match(wxml, /当前发布安排/); assert.match(wxml, /不代表这条历史记录中已经发生/); assert.match(wxml, /仅自己的本主题记录，包含不同场次；历史只读/);
});
test('actual history template wires paging, read-only result, retry and back without historical writes', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/theme-backpack.wxml'), 'utf8');
  for (const method of ['openCurrentThemeCard', 'openHistoryThemeCard', 'loadThemeHistory', 'reloadThemeHistory', 'retryThemeCard', 'closeThemeCard']) assert.ok(wxml.includes('="' + method + '"'), method);
  const historyBlock = wxml.slice(wxml.indexOf('<block wx:elif="{{themeBackpackTab === \'history\'}}">')); assert.doesNotMatch(historyBlock, /onThemeBagAction|requestThemeReplay/);
});
test('rescue requires literal current awaitingRescue and no historical/closed controls', async () => {
  for (const kind of ['notWaiting', 'historicalCard', 'historyTab', 'closed', 'ended', 'off']) {
    const f = fixture({ view: { status: 'ACTIVE', awaitingRescue: true } }); await readyRead(f);
    if (kind === 'notWaiting') f.p.data.themeBag.awaitingRescue = false;
    if (kind === 'historicalCard') f.p.data.themeCardShow = true; if (kind === 'historyTab') f.p.data.themeBackpackTab = 'history';
    if (kind === 'closed') f.p.data.themeBackpackOpen = false; if (kind === 'ended') f.p.data.themeBag.status = 'ENDED'; if (kind === 'off') f.p._themeEnabled = false;
    assert.equal(await f.p.requestThemeRescue(), false); assert.equal(f.calls.length, 0, kind);
    if (kind !== 'notWaiting') { assert.equal(await f.p.requestThemeEnd(), false); assert.equal(f.calls.length, 0, kind); }
  }
});
test('rescue submits only current session/version/action tuple and displays server recovery', async () => {
  const f = fixture({ view: { status: 'ACTIVE', awaitingRescue: true, hp: 0, rescues: 0 }, handlers: { '/api/play/theme/action': () => ({ code: 200, data: view({ status: 'ACTIVE', awaitingRescue: false, hp: 5, rescues: 1, version: 4 }) }) } });
  await readyRead(f); assert.equal(await f.p.requestThemeRescue(), true); const sent = f.calls.find(x => x.url.endsWith('/action')).data;
  assert.deepEqual(Object.keys(sent).sort(), ['sessionId', 'expectedVersion', 'actionId', 'operation', 'contentId'].sort()); assert.equal(sent.operation, 'RESCUE'); assert.equal(sent.sessionId, 40); assert.equal(sent.expectedVersion, 3); assert.equal(sent.contentId, null);
  assert.equal(f.p.data.themeBag.hp, 5); assert.equal(f.p.data.themeBag.rescues, 1); assert.equal(f.p.data.themeBag.awaitingRescue, false); assert.equal(f.modals.length, 0);
});
test('END refreshes current state, then requires explicit consequence confirmation; cancel sends no action', async () => {
  const f = fixture({ confirm: false, view: { status: 'ACTIVE' } }); await readyRead(f);
  assert.equal(await f.p.requestThemeEnd(), false); assert.equal(f.modals.length, 1); assert.match(f.modals[0].content, /不能继续在这一局操作/); assert.match(f.modals[0].content, /不会因此视为成功通关/);
  assert.deepEqual(f.calls.map(c => c.url), ['/api/play/theme/state']); assert.equal(f.p.data.themeControlBusy, false); assert.equal(f.p._themePending, null);
});
test('confirmed END uses newly refreshed version and group wording ends only this personal run', async () => {
  const f = fixture({ view: { status: 'ACTIVE', teamReplayRequired: true }, handlers: { '/api/play/theme/action': () => ({ code: 200, data: view({ status: 'ENDED', teamReplayRequired: true, version: 5, result: { endedAt } }) }) } });
  await readyRead(f); f.current.view.version = 4; assert.equal(await f.p.requestThemeEnd(), true);
  const action = f.calls.find(c => c.url.endsWith('/action')); assert.equal(action.data.expectedVersion, 4); assert.equal(action.data.operation, 'END'); assert.equal(action.data.contentId, null);
  assert.match(f.modals[0].content, /不会替其他队员结束/); assert.match(f.modals[0].content, /不会自动整队重开/); assert.equal(f.posts().length, 0); assert.equal(f.p.data.themeBag.status, 'ENDED');
});
test('unknown rescue preserves the full original payload and blocks conflicting END', async () => {
  let n = 0; const f = fixture({ view: { status: 'ACTIVE', awaitingRescue: true }, handlers: { '/api/play/theme/action': () => ++n === 1 ? { netFail: true } : { code: 200, data: view({ status: 'ACTIVE', version: 4, awaitingRescue: false }) } } });
  await readyRead(f); await f.p.requestThemeRescue(); const original = clone(f.calls.find(c => c.url.endsWith('/action')).data);
  assert.equal(await f.p.requestThemeEnd(), false); assert.equal(f.modals.length, 0); assert.equal(await f.p.retryThemeBagAction(), true);
  const actions = f.calls.filter(c => c.url.endsWith('/action')); assert.equal(actions.length, 2); assert.deepEqual(actions[1].data, original);
});
test('unknown END retry reuses prior explicit intent without a new confirmation/key', async () => {
  let n = 0; const f = fixture({ view: { status: 'ACTIVE' }, handlers: { '/api/play/theme/action': () => ++n === 1 ? { httpFail: true } : { code: 200, data: view({ status: 'ENDED', version: 4, result: { endedAt } }) } } });
  await readyRead(f); await f.p.requestThemeEnd(); const original = clone(f.calls.find(c => c.url.endsWith('/action')).data);
  assert.equal(f.p.data.themeBagUnknown, true); assert.equal(await f.p.requestThemeEnd(), true); assert.equal(f.modals.length, 1);
  assert.deepEqual(f.calls.filter(c => c.url.endsWith('/action'))[1].data, original);
});
test('END confirmation becomes invalid on version/run/account/observed permission changes', async () => {
  for (const change of ['version', 'run', 'owner', 'permission']) {
    const f = fixture({ confirm: 'manual', view: { status: 'ACTIVE' } }); await readyRead(f); const pending = f.p.requestThemeEnd();
    while (!f.modals.length) await new Promise(r => setImmediate(r));
    if (change === 'version') f.p._applyThemeView(view({ status: 'ACTIVE', version: 4 }));
    if (change === 'run') f.p._applyThemeView(view({ status: 'ACTIVE', sessionId: 41, playInstanceId: 'replay:40', version: 0 }));
    if (change === 'owner') { f.owner(8); f.p._themeOnShow(); }
    if (change === 'permission') f.p._themeNodesFailed({ code: 403, msg: '资格已撤销' }, f.p._themeNodesRequest());
    f.modals[0].success({ confirm: true }); assert.equal(await pending, false, change); assert.equal(f.calls.some(c => c.url.endsWith('/action')), false, change);
  }
});
test('END preparation rejects double taps and a delayed hidden confirmation', async () => {
  const f = fixture({ confirm: 'manual', view: { status: 'ACTIVE' } }); await readyRead(f); const first = f.p.requestThemeEnd();
  while (!f.modals.length) await new Promise(r => setImmediate(r)); assert.equal(await f.p.requestThemeEnd(), false); assert.equal(f.modals.length, 1);
  f.p._themeOnHide(false); f.modals[0].success({ confirm: true }); assert.equal(await first, false); assert.equal(f.p.data.themeControlBusy, false); assert.equal(f.calls.some(c => c.url.endsWith('/action')), false);
});
test('END preflight denied scope never shows confirmation or sends mutation', async () => {
  for (const msg of ['活动已取消', '请先报名并完成支付', '主题运行时暂未开放', '自玩通行证已过期']) {
    const f = fixture({ view: { status: 'ACTIVE' } }); await readyRead(f); f.handlers['/api/play/theme/state'] = () => ({ code: 500, msg });
    assert.equal(await f.p.requestThemeEnd(), false, msg); assert.equal(f.modals.length, 0); assert.equal(f.calls.some(c => c.url.endsWith('/action')), false); assert.equal(f.p._themeAllowed(), false);
  }
});
test('server rescue refusal cannot fabricate recovery values or refill anything', async () => {
  const f = fixture({ view: { status: 'ACTIVE', awaitingRescue: true, hp: 0, rescues: 2, used: 4 }, handlers: { '/api/play/theme/action': () => ({ code: 500, msg: '本局没有可用救援' }) } });
  await readyRead(f); assert.equal(await f.p.requestThemeRescue(), false); assert.equal(f.p.data.themeBag.hp, 0); assert.equal(f.p.data.themeBag.rescues, 2); assert.equal(f.p.data.themeBag.used, 4); assert.equal(f.p._themePending, null);
});
test('observed action permission revocation retires current capability and pending request', async () => {
  const f = fixture({ view: { status: 'ACTIVE', awaitingRescue: true }, handlers: { '/api/play/theme/action': () => ({ code: 500, msg: '请先报名并完成支付' }) } });
  await readyRead(f); await f.p.requestThemeRescue(); assert.equal(f.p._themeAllowed(), false); assert.equal(f.p._themePending, null); assert.equal(f.p.data.themeRuntimeReady, false);
  await f.p.retryThemeBagAction(); assert.equal(f.calls.filter(c => c.url.endsWith('/action')).length, 1);
});
test('actual current controls are absent from history/card and disclose existing semantics', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/theme-backpack.wxml'), 'utf8');
  assert.match(wxml, /themeBag\.status === 'ACTIVE' && themeBackpackTab !== 'history'/); assert.match(wxml, /wx:if="\{\{themeBag.awaitingRescue\}\}" bindtap="requestThemeRescue"/);
  assert.match(wxml, /bindtap="requestThemeEnd"/); assert.match(wxml, /不会重置背包或进度/);
  const card = wxml.slice(wxml.indexOf('<block wx:if="{{themeCardShow}}">'), wxml.indexOf('  <block wx:else>')); assert.doesNotMatch(card, /requestThemeEnd|requestThemeRescue/);
});
function recorded(id, extra = {}) { return Object.assign({ id, name: '已触发线索', content: '自己的记录', status: 'RECORDED' }, extra); }
test('passive current receipts distinguish PENDING FAILED and REACHED without claiming read/device delivery', () => {
  const rows = theme.presentRecordedEvents([recorded('event:p'), recorded('event:f'), recorded('event:r')], [
    { eventId: 'event:p', status: 'PENDING', attempts: 0 }, { eventId: 'event:f', status: 'FAILED', attempts: 5 }, { eventId: 'event:r', status: 'REACHED', attempts: 1, messageId: 99 }
  ], false);
  assert.deepEqual(rows.map(r => r.deliveryStatus), ['PENDING', 'FAILED', 'REACHED']);
  assert.deepEqual(rows.map(r => r.deliveryLabel), ['站内消息待生成', '站内消息暂未生成', '站内消息已生成']); assert.equal(rows[1].deliveryAttempts, 5); assert.equal(rows[2].messageId, undefined);
});
test('missing unknown and duplicate receipt status stays unconfirmed rather than inventing success', () => {
  for (const receipts of [undefined, [], [{ eventId: 'event:a', status: 'READ' }], [{ eventId: 'event:a', status: 'FAILED' }, { eventId: 'event:a', status: 'REACHED' }]]) {
    const rows = theme.presentRecordedEvents([recorded('event:a')], receipts, false); assert.equal(rows[0].deliveryStatus, 'UNCONFIRMED'); assert.equal(rows[0].deliveryLabel, '已记录，消息状态未同步');
  }
});
test('receipt matches exact recorded event id, never invents future events or leaks internal fields', () => {
  const rows = theme.presentRecordedEvents([recorded('event:a', { grants: ['private-rule'], when: ['condition'], effects: ['mutation'] })], [
    { eventId: 'a', status: 'REACHED' }, { eventId: 'event:future', status: 'REACHED' }
  ], false);
  assert.equal(rows.length, 1); assert.equal(rows[0].deliveryStatus, 'UNCONFIRMED'); assert.equal(rows[0].grants, undefined); assert.equal(rows[0].when, undefined); assert.equal(rows[0].effects, undefined);
  assert.equal(theme.presentRecordedEvents([], [{ eventId: 'event:a', status: 'REACHED' }], false).length, 0);
});
test('broadcast presentation is pure, validates attempts and handles hostile map keys safely', () => {
  const events = [recorded('__proto__')], receipts = [{ eventId: '__proto__', status: 'FAILED', attempts: -1 }];
  const original = JSON.stringify({ events, receipts }); const rows = theme.presentRecordedEvents(events, receipts, false);
  assert.equal(rows[0].deliveryStatus, 'FAILED'); assert.equal(rows[0].deliveryAttempts, null); assert.equal(JSON.stringify({ events, receipts }), original); assert.equal({}.deliveryStatus, undefined);
});
test('history recorded events are archived facts even if an unrelated delivery status is included', () => {
  const raw = historyRow(30, { activityId: 12, events: [recorded('event:a')], broadcasts: [{ eventId: 'event:a', status: 'REACHED', attempts: 1 }] });
  const rows = theme.presentHistoryPage([raw], 10, ''); assert.equal(rows[0].activityLabel, '场次 12'); assert.equal(rows[0].recordedEvents[0].deliveryStatus, 'ARCHIVED'); assert.equal(rows[0].recordedEvents[0].deliveryLabel, '历史已记录，不重新发送'); assert.equal(rows[0].recordedEvents[0].deliveryAttempts, null);
});
test('expanding archived clues makes zero requests and preserves current bag/replay intent', async () => {
  const f = fixture({ handlers: { '/api/play/theme/history': () => ({ code: 200, data: [historyRow(30, { events: [recorded('event:a')], status: 'ACTIVE', result: null })] }) } });
  await readyRead(f); await f.p.changeThemeBackpackTab({ detail: { key: 'history' } }); const bag = clone(f.p.data.themeBag), pending = { payload: Object.freeze({ sessionId: 40 }), context: f.p._themeContext() }; f.p._themeReplayPending = pending; f.calls.length = 0;
  const event = { currentTarget: { dataset: { sessionid: '30' } } }; assert.equal(f.p.toggleHistoryThemeRecords(event), true); assert.equal(f.p.data.themeHistoryExpandedId, '30'); assert.equal(f.p.data.themeHistoryRows[0].hasCard, false);
  assert.equal(f.p.toggleHistoryThemeRecords(event), true); assert.equal(f.p.data.themeHistoryExpandedId, ''); assert.equal(f.calls.length, 0); assert.deepEqual(f.p.data.themeBag, bag); assert.equal(f.p._themeReplayPending, pending);
});
test('history clue expansion rejects foreign ids, disabled capability, result-card context and closes on hide', async () => {
  const f = fixture(); await readyRead(f); f.p.data.themeBackpackTab = 'history'; f.p.data.themeHistoryLoaded = true; f.p.data.themeHistoryRows = theme.presentHistoryPage([historyRow(30, { events: [recorded('event:a')] })], 10, '');
  const event = id => ({ currentTarget: { dataset: { sessionid: id } } }); assert.equal(f.p.toggleHistoryThemeRecords(event('99')), false);
  f.p.data.themeCardShow = true; assert.equal(f.p.toggleHistoryThemeRecords(event('30')), false); f.p.data.themeCardShow = false;
  f.p._themeEnabled = false; assert.equal(f.p.toggleHistoryThemeRecords(event('30')), false); f.p._themeEnabled = true;
  assert.equal(f.p.toggleHistoryThemeRecords(event('30')), true); f.p._themeOnHide(false); assert.equal(f.p.data.themeHistoryExpandedId, ''); assert.deepEqual(f.p.data.themeHistoryRows, []); assert.equal(f.calls.length, 0);
});
test('a receipt-free action response and a new instance never reuse a prior REACHED badge', async () => {
  const f = fixture({ view: { events: [recorded('event:a')], broadcasts: [{ eventId: 'event:a', status: 'REACHED', attempts: 1 }] } }); await readyRead(f);
  assert.equal(f.p.data.themeBag.clues[0].deliveryStatus, 'REACHED');
  f.p._applyThemeView(view({ version: 4, events: [recorded('event:a')] })); assert.equal(f.p.data.themeBag.clues[0].deliveryStatus, 'UNCONFIRMED');
  f.p._applyThemeView(view({ sessionId: 41, playInstanceId: 'replay:40', version: 0, events: [recorded('event:a')], broadcasts: [{ eventId: 'event:a', status: 'FAILED', attempts: 2 }] })); assert.equal(f.p.data.themeBag.clues[0].deliveryStatus, 'FAILED'); assert.equal(f.p.data.themeBag.clues[0].deliveryAttempts, 2);
});
test('existing clue items retain their kind and actions while recorded events stay immutable', () => {
  const projected = theme.present(view({ contents: [{ id: 'clue-item', kind: 'ITEM', content: 'existing item', quantity: 1 }], events: [recorded('event:a')] }));
  assert.equal(projected.clues[0].kind, 'ITEM'); assert.equal(projected.clues[1].kind, 'EVENT'); assert.equal(projected.recordedEventCount, 1);
});
test('actual receipt widgets clarify generation-only semantics and historical expansion has no delivery trigger', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/theme-backpack.wxml'), 'utf8'); assert.match(wxml, /不代表设备通知已送达或你已读/); assert.match(wxml, /发送失败不会抹去已记录线索/); assert.match(wxml, /bindtap="toggleHistoryThemeRecords"/); assert.match(wxml, /仅展开历史快照，不触发重新发送或发放/);
  const src = fs.readFileSync(path.resolve(__dirname, '../../pages/play/utils/theme-backpack.js'), 'utf8'); const start = src.indexOf('    toggleHistoryThemeRecords(e) {'), end = src.indexOf('    openCurrentThemeCard()', start); assert.doesNotMatch(src.slice(start, end), /req\(|refreshThemeBackpack|_themeCommand/);
});
test('current HP/luck names and action budget show server values including zero', () => {
  const rows = theme.presentCurrentStats(view({ hpEnabled: true, hpName: '体温', hp: 0, hpMax: 12, luckEnabled: true, luckName: '希望', luck: 0, luckMax: 8, actions: 0, actionLimit: 10 }));
  assert.deepEqual(rows, [
    { key: 'hp', label: '体温', value: 0, hasMax: true, max: 12 },
    { key: 'luck', label: '希望', value: 0, hasMax: true, max: 8 },
    { key: 'actions', label: '行动用量', value: 0, hasMax: true, max: 10 }
  ]);
});
test('disabled or absent dimensions do not become visible from numbers alone', () => {
  for (const enabled of [false, undefined, null, 'true', 1]) assert.deepEqual(theme.presentCurrentStats(view({ hpEnabled: enabled, hp: 7, hpMax: 12, luckEnabled: enabled, luck: 3, luckMax: 8 })), []);
  assert.deepEqual(theme.presentCurrentStats(view({ hpEnabled: true, hp: '7', hpMax: 12, luckEnabled: true, luck: Infinity })), []);
});
test('missing names stay neutral HP/LUCK and missing max is not guessed or labelled unlimited', () => {
  const rows = theme.presentCurrentStats(view({ hpEnabled: true, hp: 0, hpName: '', luckEnabled: true, luck: 4 }));
  assert.equal(rows[0].label, 'HP'); assert.equal(rows[1].label, 'LUCK'); assert.equal(rows[0].hasMax, false); assert.equal(rows[0].max, null); assert.equal(rows[1].hasMax, false);
  assert.equal(JSON.stringify(rows).includes('无限'), false); assert.equal(JSON.stringify(rows).includes('生命值'), false);
});
test('unconfigured action limit is hidden and no remaining budget or clamping is fabricated', () => {
  for (const limit of [undefined, null, 0, '10', NaN]) assert.deepEqual(theme.presentCurrentStats(view({ actions: 0, actionLimit: limit })), []);
  const raw = view({ hpEnabled: true, hp: 12, hpMax: 10, luckEnabled: true, luck: -2, luckMax: 5, actions: 15, actionLimit: 10 });
  const before = JSON.stringify(raw), rows = theme.presentCurrentStats(raw); assert.deepEqual(rows.map(x => x.value), [12, -2, 15]); assert.equal(rows.some(x => Object.hasOwn(x, 'remaining')), false); assert.equal(JSON.stringify(raw), before);
});
test('item effect labels share frozen current names without changing effects or original catalog', () => {
  const input = view({ hpName: '体温', luckName: '希望', contents: [{ id: 'heat', kind: 'ITEM', stat: 'HP', delta: 3, mode: 'ONCE', quantity: 1 }, { id: 'hope', kind: 'ITEM', stat: 'LUCK', delta: -2, mode: 'HELD', wear: true, quantity: 2 }] });
  const before = JSON.stringify(input), bag = theme.present(input); assert.deepEqual(bag.items.map(x => x.statLabel), ['体温', '希望']); assert.deepEqual(bag.items.map(x => x.delta), [3, -2]); assert.equal(bag.items[1].wear, true); assert.equal(JSON.stringify(input), before);
});
test('new authoritative instance carries its own names and does not inherit old current stat labels', async () => {
  const f = fixture({ view: { hpEnabled: true, hpName: '旧局名称', hp: 5, hpMax: 10 } }); await readyRead(f); assert.equal(f.p.data.themeBag.currentStats[0].label, '旧局名称');
  f.p._applyThemeView(view({ sessionId: 41, playInstanceId: 'replay:40', version: 0, hpEnabled: true, hpName: '新局名称', hp: 8, hpMax: 12 }));
  assert.equal(f.p.data.themeBag.currentStats[0].label, '新局名称'); assert.equal(f.p.data.themeBag.currentStats[0].value, 8);
});
test('actual current stats stay outside history/card and item text no longer hardcodes theme meanings', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/theme-backpack.wxml'), 'utf8');
  assert.match(wxml, /themeBackpackTab !== 'history' && themeBag.currentStats.length/); assert.match(wxml, /\{\{item.statLabel\}\}/); assert.doesNotMatch(wxml, /item.stat === 'HP' \? '生命值' : '幸运值'/);
  const card = wxml.slice(wxml.indexOf('<block wx:if="{{themeCardShow}}">'), wxml.indexOf('  <block wx:else>')); assert.doesNotMatch(card, /themeBag.currentStats/);
});
function posterNative(options = {}) {
  const exports = [], saved = [], shared = [], removed = [], drawn = [], covers = [];
  const context = { measureText: text => ({ width: Array.from(text).length * 18 }), scale() {}, fillRect() {}, drawImage() {}, fillText: text => drawn.push(text) };
  const api = {
    createOffscreenCanvas: () => ({ width: 1, height: 1, getContext: () => context, createImage: () => ({ set src(value) { this.onload(); } }) }),
    canvasToTempFilePath(args) { exports.push(args); if (!options.manualExport) args.success({ tempFilePath: 'wxfile://tmp_result_' + exports.length + '.png' }); },
    getImageInfo(args) { covers.push(args); args.fail({ errMsg: 'download failed' }); },
    getFileSystemManager: () => ({ unlink: args => removed.push(args.filePath) }),
    canIUse: name => name === 'showShareImageMenu.object.needShowEntrance' && options.shareSupport !== false,
    saveImageToPhotosAlbum(args) { saved.push(args); if (!options.manualNative) args.success({}); },
    showShareImageMenu(args) { shared.push(args); if (!options.manualNative) args.success({}); },
    authorize() { throw new Error('must not auto-authorize'); }, openSetting() { throw new Error('must not auto-open settings'); },
    previewImage() { throw new Error('inline preview only'); }, getCurrentPages() { throw new Error('must not read route/query'); },
  };
  return { api, exports, saved, shared, removed, drawn, covers };
}
async function posterFixture(options = {}) {
  const native = posterNative(options);
  const f = fixture({ nativeApi: native.api, view: { result: { endedAt, title: 'PRIVATE_RAW_TITLE', archive: ['PRIVATE_ARCHIVE'], track: ['PRIVATE_TRACK'] } },
    handlers: { '/api/play/theme/card': data => ({ code: 200, data: publicCard(Object.assign({ title: '公开结果 ' + data.sessionId }, options.card)) }),
      '/api/play/theme/history': () => ({ code: 200, data: [historyRow(30, { activityId: 12 })] }) } });
  await readyRead(f); await f.p.openCurrentThemeCard(); f.calls.length = 0;
  return Object.assign(f, { native });
}
test('poster reads no private result and generation only starts on explicit tap after selected public card', async () => {
  const f = await posterFixture(); assert.equal(f.native.exports.length, 0); assert.equal(f.native.saved.length, 0); assert.equal(f.native.shared.length, 0);
  assert.equal(await f.p.generateThemePoster(), true); assert.match(f.native.drawn.join(' '), /公开结果 40/); assert.doesNotMatch(f.native.drawn.join(' '), /PRIVATE/);
  assert.equal(f.calls.length, 0); assert.equal(f.native.saved.length, 0); assert.equal(f.native.shared.length, 0);
  assert.equal(f.p.data.themePosterPath, 'wxfile://tmp_result_1.png'); f.p.closeThemeCard();
});
test('raw state or manually displayed card cannot substitute for a successful selected public response', async () => {
  const n = posterNative(), f = fixture({ nativeApi: n.api, view: { result: publicCard() } }); await readyRead(f);
  f.p.data.themeCardShow = true; f.p.data.themeCard = theme.presentCard(publicCard());
  assert.equal(await f.p.generateThemePoster(), false); assert.equal(n.exports.length, 0); assert.equal(f.calls.length, 0);
});
test('poster is the selected historical public card and leaves current run untouched', async () => {
  const f = await posterFixture(); await f.p.changeThemeBackpackTab({ detail: { key: 'history' } });
  await f.p.openHistoryThemeCard({ currentTarget: { dataset: { sessionid: '30' } } }); const bag = clone(f.p.data.themeBag); f.calls.length = 0;
  assert.equal(await f.p.generateThemePoster(), true); assert.match(f.native.drawn.join(' '), /公开结果 30/); assert.doesNotMatch(f.native.drawn.join(' '), /PRIVATE/);
  assert.deepEqual(f.p.data.themeBag, bag); assert.equal(f.calls.length, 0); f.p.closeThemeCard();
});
test('poster double taps have one in-flight canvas/export and no automatic native action', async () => {
  const f = await posterFixture({ manualExport: true }); const first = f.p.generateThemePoster();
  assert.equal(await f.p.generateThemePoster(), false); await new Promise(r => setImmediate(r)); assert.equal(f.native.exports.length, 1);
  f.native.exports[0].success({ tempFilePath: 'wxfile://tmp_single.png' }); assert.equal(await first, true);
  assert.equal(f.native.saved.length + f.native.shared.length, 0); f.p.closeThemeCard();
});
test('stale poster generation is rejected on owner topic page card tab and run changes', async () => {
  for (const mode of ['owner', 'topic', 'hide', 'unload', 'close', 'sheet', 'card', 'tab', 'run']) {
    const f = await posterFixture({ manualExport: true }), pending = f.p.generateThemePoster(); await new Promise(r => setImmediate(r));
    if (mode === 'owner') f.owner(8); if (mode === 'topic') f.p.data.topicId = 11;
    if (mode === 'hide') f.p._themeOnHide(false); if (mode === 'unload') f.p._themeOnHide(true);
    if (mode === 'close') f.p.closeThemeCard(); if (mode === 'sheet') f.p.closeThemeBackpack();
    if (mode === 'card') await f.p.retryThemeCard(); if (mode === 'tab') await f.p.changeThemeBackpackTab({ detail: { key: 'clues' } });
    if (mode === 'run') f.p._applyThemeView(view({ sessionId: 41, playInstanceId: 'replay:40', version: 0 }));
    f.native.exports[0].success({ tempFilePath: 'wxfile://tmp_stale_' + mode + '.png' }); assert.equal(await pending, false, mode);
    assert.equal(f.p.data.themePosterPath, '', mode); assert.deepEqual(f.native.removed, ['wxfile://tmp_stale_' + mode + '.png'], mode);
    assert.equal(f.p.data.themePosterBusy, false); assert.equal(f.native.saved.length + f.native.shared.length, 0);
  }
});
test('existing preview file retires on card/backpack/hide/identity/run change and cannot be saved', async () => {
  for (const mode of ['card', 'sheet', 'hide', 'owner', 'run']) {
    const f = await posterFixture(); await f.p.generateThemePoster();
    if (mode === 'card') await f.p.retryThemeCard(); if (mode === 'sheet') f.p.closeThemeBackpack(); if (mode === 'hide') f.p._themeOnHide(false);
    if (mode === 'owner') f.owner(8); if (mode === 'run') f.p._applyThemeView(view({ sessionId: 41, playInstanceId: 'replay:40', version: 0 }));
    assert.equal(await f.p.saveThemePoster(), false, mode); assert.equal(f.p.data.themePosterPath, '', mode);
    assert.deepEqual(f.native.removed, ['wxfile://tmp_result_1.png'], mode); assert.equal(f.native.saved.length, 0);
  }
});
test('failed cover keeps an actionable error and text-only output requires its own explicit tap', async () => {
  const f = await posterFixture({ card: { cover: 'https://images.example/card.png' } });
  assert.equal(await f.p.generateThemePoster(), false); assert.match(f.p.data.themePosterError, /文字版/); assert.equal(f.native.exports.length, 0);
  assert.equal(await f.p.generateThemeTextPoster(), true); assert.equal(f.native.covers.length, 1); assert.equal(f.p.data.themePosterTextOnly, true);
  assert.equal(f.p.data.themePosterError, ''); assert.equal(f.native.saved.length + f.native.shared.length, 0); f.p.closeThemeCard();
});
test('unsupported canvas failure is visible and retries never invoke permissions or page sharing', async () => {
  const f = await posterFixture(); delete f.native.api.createOffscreenCanvas;
  assert.equal(await f.p.generateThemePoster(), false); assert.match(f.p.data.themePosterError, /暂不支持/); assert.equal(f.p.data.themePosterBusy, false);
  assert.equal(f.native.exports.length + f.native.saved.length + f.native.shared.length, 0);
});
test('explicit save and share only pass local output and share disables the app entrance', async () => {
  const f = await posterFixture(); await f.p.generateThemePoster(); assert.equal(await f.p.saveThemePoster(), true);
  assert.deepEqual(Object.keys(f.native.saved[0]).sort(), ['fail', 'filePath', 'success']); assert.equal(f.native.saved[0].filePath, f.p.data.themePosterPath);
  assert.equal(await f.p.shareThemePoster(), true); assert.equal(f.native.shared[0].needShowEntrance, false);
  assert.deepEqual(Object.keys(f.native.shared[0]).sort(), ['fail', 'needShowEntrance', 'path', 'success']); assert.equal(f.native.shared[0].path, f.p.data.themePosterPath);
  assert.equal(f.calls.length, 0); assert.match(f.p.data.themePosterNotice, /菜单/); f.p.closeThemeCard();
});
test('image share fails closed when needShowEntrance support is absent or changes', async () => {
  for (const support of [false, 'changes']) {
    const f = await posterFixture({ shareSupport: support !== false }); await f.p.generateThemePoster();
    if (support === 'changes') f.native.api.canIUse = () => false;
    assert.equal(await f.p.shareThemePoster(), false); assert.equal(f.native.shared.length, 0); assert.match(f.p.data.themePosterError, /安全分享/);
    assert.equal(await f.p.saveThemePoster(), true); f.p.closeThemeCard();
  }
});
test('native double taps are blocked and retired image stays pinned until native callback', async () => {
  const f = await posterFixture({ manualNative: true }); await f.p.generateThemePoster();
  const pending = f.p.shareThemePoster(); assert.equal(await f.p.shareThemePoster(), false); assert.equal(await f.p.saveThemePoster(), false); assert.equal(await f.p.generateThemePoster(), false);
  assert.equal(f.native.shared.length, 1); f.p._themeOnHide(false); assert.equal(f.p.data.themePosterPath, ''); assert.deepEqual(f.native.removed, []);
  f.native.shared[0].success({}); await pending; assert.deepEqual(f.native.removed, ['wxfile://tmp_result_1.png']);
  assert.equal(f.p.data.themePosterNotice, ''); assert.equal(f.p.data.themePosterNativeBusy, false);
});
test('save denial or cancellation preserves preview for manual retry without any settings escalation', async () => {
  for (const error of ['saveImageToPhotosAlbum:fail auth deny', 'saveImageToPhotosAlbum:fail cancel']) {
    const f = await posterFixture({ manualNative: true }); await f.p.generateThemePoster(); const pending = f.p.saveThemePoster();
    f.native.saved[0].fail({ errMsg: error }); assert.equal(await pending, false); assert.equal(f.p.data.themePosterPath, 'wxfile://tmp_result_1.png');
    assert.equal(f.p.data.themePosterNativeBusy, false); assert.notEqual(f.p.data.themePosterError, ''); assert.deepEqual(f.native.removed, []);
    const retry = f.p.saveThemePoster(); f.native.saved[1].success({}); assert.equal(await retry, true); f.p.closeThemeCard();
  }
});
test('abandoned native operation times out, releases retired image and cannot report late success', async t => {
  const f = await posterFixture({ manualNative: true }); await f.p.generateThemePoster();
  t.mock.timers.enable({ apis: ['setTimeout'] }); const pending = f.p.saveThemePoster(); f.p.closeThemeCard();
  t.mock.timers.tick(30001); assert.equal(await pending, false); assert.deepEqual(f.native.removed, ['wxfile://tmp_result_1.png']);
  f.native.saved[0].success({}); assert.equal(f.p.data.themePosterNotice, ''); assert.equal(f.p.data.themePosterNativeBusy, false);
});
test('repeated generation deletes prior preview and scopes clipped notice to the new image', async () => {
  const f = await posterFixture({ card: { summary: '很长的内容。'.repeat(500) } });
  assert.equal(await f.p.generateThemePoster(), true); assert.equal(f.p.data.themePosterClipped, true);
  assert.equal(await f.p.generateThemeTextPoster(), true); assert.deepEqual(f.native.removed, ['wxfile://tmp_result_1.png']);
  assert.equal(f.p.data.themePosterPath, 'wxfile://tmp_result_2.png'); f.p.closeThemeCard(); assert.equal(f.native.removed.length, 2);
});
test('real template has explicit inline preview/actions and never adds canvas nodes or page share bindings', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/theme-backpack.wxml'), 'utf8');
  for (const action of ['generateThemePoster', 'generateThemeTextPoster', 'saveThemePoster', 'shareThemePoster']) assert.match(wxml, new RegExp('bindtap="' + action + '"'));
  assert.match(wxml, /show-menu-by-longpress="\{\{false\}\}"/); assert.match(wxml, /themePosterClipped/); assert.doesNotMatch(wxml, /<canvas|open-type="share"/);
  const src = fs.readFileSync(path.resolve(__dirname, '../../pages/play/utils/theme-backpack.js'), 'utf8');
  const posterHandlers = src.slice(src.indexOf('    _retireThemePoster()'), src.indexOf('    _themeCommand(operation'));
  assert.doesNotMatch(posterHandlers, /getCurrentPages|currentpagepath|onShareAppMessage|openSetting\(|authorize\(|previewImage\(/);
  assert.match(posterHandlers, /needShowEntrance: false/);
});


function overviewEnvelope(extra = {}) {
  return Object.assign({ owner: '7', isCurrent: () => true, value: { kind: 'CONFIG', version: 1, clipped: false,
    rows: Array.from({ length: 45 }, (_, index) => ({ section: '目录', label: '配置 ' + (index + 1), detail: '目录声明' })) } }, extra);
}
function acceptOverview(f, envelope = overviewEnvelope()) {
  f.p.data.isPreview = true; const context = f.p._newThemeOverviewContext();
  return { context, accepted: f.p._acceptThemeOverview(envelope, context) };
}
test('local CONFIG acceptance, pagination and close/reopen never request live runtime or retain source payload', () => {
  const f = fixture(), envelope = overviewEnvelope(); envelope.value.secret = { answer: 'never_render' };
  envelope.value.rows[0].nodeId = 'never_render';
  assert.equal(acceptOverview(f, envelope).accepted, true); assert.equal(f.p.data.themeOverviewAvailable, true);
  assert.equal(f.p.data.themeOverviewRows.length, 0); assert.equal(f.p.openThemeOverview(), true);
  assert.equal(f.p.data.themeOverviewRows.length, 20); assert.equal(f.p.data.themeOverviewPage, 1); assert.equal(f.p.data.themeOverviewPages, 3);
  f.p.nextThemeOverviewPage(); assert.equal(f.p.data.themeOverviewRows[0].label, '配置 21');
  f.p.nextThemeOverviewPage(); assert.equal(f.p.data.themeOverviewRows.length, 5); assert.equal(f.p.nextThemeOverviewPage(), false);
  f.p.previousThemeOverviewPage(); assert.equal(f.p.data.themeOverviewPage, 2);
  f.p.closeThemeOverview(); assert.equal(f.p.data.themeOverviewRows.length, 0); assert.equal(f.p.data.themeOverviewShow, false);
  assert.equal(f.p.openThemeOverview(), true); assert.equal(f.p.data.themeOverviewPage, 1);
  assert.equal(JSON.stringify(f.p.data).includes('never_render'), false); assert.equal(f.p.data.themeRuntimeReady, false); assert.equal(f.calls.length, 0);
});
test('local CONFIG guard requires exact preview owner/context, real preview mode and explicit live source guard', () => {
  for (const change of [f => f.owner(8), f => { f.p.data.isMock = true; }, f => { f.p.data.isPreview = false; }, f => { f.p._themeHidden = true; }]) {
    const f = fixture(); f.p.data.isPreview = true; const context = f.p._newThemeOverviewContext(); change(f);
    assert.equal(f.p._acceptThemeOverview(overviewEnvelope(), context), false); assert.equal(f.p.data.themeOverviewAvailable, false);
  }
  for (const extra of [{ owner: '8' }, { isCurrent: null }, { isCurrent: () => false }, { isCurrent: () => { throw Error(); } }]) {
    const f = fixture(); assert.equal(acceptOverview(f, overviewEnvelope(extra)).accepted, false); assert.equal(f.calls.length, 0);
  }
});
test('local CONFIG retires on hidden page/unload and a late callback cannot revive it after showing again', () => {
  for (const unload of [false, true]) {
    const f = fixture(); const { context } = acceptOverview(f); f.p.openThemeOverview(); f.p._themeOnHide(unload);
    assert.equal(f.p.data.themeOverviewAvailable, false); assert.equal(f.p._themeOverviewEnvelope, null); assert.deepEqual(f.p.data.themeOverviewRows, []);
    f.p._themeOnShow(); assert.equal(f.p._acceptThemeOverview(overviewEnvelope(), context), false); assert.equal(f.p.openThemeOverview(), false); assert.equal(f.calls.length, 0);
  }
});
test('local CONFIG retires on owner or author source/revision/permission changes during later interactions', () => {
  for (const action of ['openThemeOverview', 'nextThemeOverviewPage', '_themeOnShow']) {
    for (const change of ['owner', 'source']) {
      let current = true; const f = fixture(); acceptOverview(f, overviewEnvelope({ isCurrent: () => current })); f.p.openThemeOverview();
      if (change === 'owner') f.owner(8); else current = false;
      f.p[action](); assert.equal(f.p.data.themeOverviewAvailable, false); assert.equal(f.p.data.themeOverviewShow, false); assert.deepEqual(f.p.data.themeOverviewRows, []);
    }
  }
});
test('local CONFIG replacement consumes one EventChannel context and rejects older callbacks without replacing fresh data', () => {
  const f = fixture(); const old = acceptOverview(f).context;
  const next = f.p._newThemeOverviewContext(); assert.equal(f.p.data.themeOverviewAvailable, false);
  const fresh = overviewEnvelope(); fresh.value.rows[0].label = '新的配置';
  assert.equal(f.p._acceptThemeOverview(fresh, next), true);
  assert.equal(f.p._acceptThemeOverview(overviewEnvelope(), old), false); assert.equal(f.p._acceptThemeOverview(overviewEnvelope(), next), false);
  f.p.openThemeOverview(); assert.equal(f.p.data.themeOverviewRows[0].label, '新的配置');
});
test('local CONFIG copies both source DTO and rendered rows defensively', () => {
  const f = fixture(), envelope = overviewEnvelope(); acceptOverview(f, envelope);
  envelope.value.rows[0].label = 'caller mutation'; envelope.owner = '8'; envelope.isCurrent = () => false;
  f.p.openThemeOverview(); assert.equal(f.p.data.themeOverviewRows[0].label, '配置 1');
  f.p.data.themeOverviewRows[0].label = 'render mutation'; f.p.closeThemeOverview(); f.p.openThemeOverview(); assert.equal(f.p.data.themeOverviewRows[0].label, '配置 1');
});
test('local CONFIG reprojector enforces maximum rows and exact string bounds and strips unknown keys', () => {
  const good = overviewEnvelope().value;
  for (const change of [v => { v.kind = 'ENTRY'; }, v => { v.version = 2; }, v => { v.clipped = 'false'; },
    v => { v.rows = []; }, v => { v.rows = Array(601).fill(v.rows[0]); }, v => { v.rows[0].label = 'x'.repeat(81); },
    v => { v.rows[0].detail = 'x'.repeat(241); }, v => { v.rows[0].section = 'raw'; }, v => { v.rows[0].detail = { raw: 1 }; }]) {
    const value = clone(good); change(value); assert.equal(theme.presentThemeOverview(value), null);
  }
  const value = clone(good); value.rows = Array(600).fill({ section: '规则', label: 'x'.repeat(80), detail: 'y'.repeat(240), secret: 'hidden' });
  const result = theme.presentThemeOverview(value); assert.equal(result.rows.length, 600); assert.equal(JSON.stringify(result).includes('hidden'), false);
});
test('invalid or absent local CONFIG replaces prior overview without affecting preview availability', () => {
  const f = fixture(); acceptOverview(f); const context = f.p._newThemeOverviewContext();
  assert.equal(f.p._acceptThemeOverview(undefined, context), false); assert.equal(context.used, true); assert.equal(f.p.data.isPreview, true); assert.equal(f.p.data.themeOverviewAvailable, false);
});
test('actual preview page fences late callbacks before any story replacement and wires read-only tool/sheet', () => {
  const base = path.resolve(__dirname, '../../pages/play'); const js = fs.readFileSync(path.join(base, 'index.js'), 'utf8');
  assert.match(js, /const overviewContext = this\._newThemeOverviewContext\(\);/);
  assert.match(js, /applyPreviewData\(data \|\| \{\}, overviewContext\)/);
  const start = js.indexOf('  applyPreviewData(data, overviewContext) {');
  const guard = js.slice(start + '  applyPreviewData(data, overviewContext) {'.length, js.indexOf('    const that = this;', start));
  const applyGuard = new Function('data', 'overviewContext', guard + 'return true;');
  const f = fixture(); f.p.data.isPreview = true; const context = f.p._newThemeOverviewContext();
  assert.equal(applyGuard.call(f.p, { themeOverview: overviewEnvelope() }, context), true);
  assert.equal(applyGuard.call(f.p, { themeOverview: overviewEnvelope() }, context), undefined);
  f.p._themeOnHide(false); assert.equal(applyGuard.call(f.p, {}, context), undefined);
  const wxml = fs.readFileSync(path.join(base, 'index.wxml'), 'utf8');
  assert.match(wxml, /isPreview && themeOverviewAvailable.*bindtap="openThemeOverview"/);
  assert.match(wxml, /!themeBackpackOpen && !themeOverviewShow && !previewEnding.show/);
  const sheet = fs.readFileSync(path.join(base, 'theme-backpack.wxml'), 'utf8').split('<!-- Author local CONFIG')[1];
  assert.match(sheet, /未经系统规则校验/); assert.match(sheet, /不是玩家背包/); assert.match(sheet, /bindtap="closeThemeOverview"/);
  assert.equal(/<image|share|ThemeReplay|ThemeCard|ThemeBag/.test(sheet), false);
});

test('first local preview delivery survives normal initial onShow/reset in either event order', () => {
  for (const deliveryBeforeShow of [false, true]) {
    const f = fixture(); f.p.data.isPreview = true;
    // New player onLoad registers its EventChannel before the first native onShow.
    f.p._themeIdentity = undefined;
    const context = f.p._newThemeOverviewContext(), envelope = overviewEnvelope();
    if (deliveryBeforeShow) assert.equal(f.p._acceptThemeOverview(envelope, context), true);
    f.p._themeOnShow();
    if (!deliveryBeforeShow) assert.equal(f.p._acceptThemeOverview(envelope, context), true);
    assert.equal(f.p.openThemeOverview(), true); assert.equal(f.p.data.themeOverviewRows.length, 20); assert.equal(f.calls.length, 0);
    f.p._themeOnHide(false); f.p._themeOnShow();
    assert.equal(f.p._acceptThemeOverview(envelope, context), false); assert.equal(f.p.data.themeOverviewAvailable, false);
  }
});
