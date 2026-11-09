'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');

function harness() {
  const requests = [], dialogs = [], locations = [];
  let definition;
  const app = { getUserID: () => 7, sendRequest: request => requests.push(request) };
  global.getApp = () => app;
  global.Page = page => { definition = page; };
  global.wx = { getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
    showModal: options => dialogs.push(options), getLocation: options => locations.push(options) };
  const file = require.resolve('../../pages/play/index.js');
  delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: structuredClone(definition.data) });
  Object.assign(page.data, { topicId: 31, loading: true });
  page.setData = function (patch, callback) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.'); let target = this.data;
      for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {});
      target[parts.at(-1)] = value;
    }
    if (callback) callback();
  };
  // Keep the real load/eligibility/normalization/consent chain; omit rendering, unrelated reads and clocks.
  for (const method of ['_applyStoryVars', '_applyLook', '_probeCasebook', '_ensureSessionClock',
    '_syncIdleStats', '_syncRunState', '_restoreRunSession', 'loadTeamProgress', '_loadPlayerGameModule',
    'refreshThemeBackpack', '_consumePendingStart', '_startThemeBgm', 'loadHudCharacter',
    'loadSessionStats', '_openStory', 'openFinish', 'openChapterFull']) page[method] = () => {};
  page.rebuild = function () { this.data.allDone = this.data.nodes.every(node => node.done); };
  const response = (extra = {}, nodes = [{ nodeId: 11, latitude: 31, longitude: 121 }]) => ({
    code: 200, data: { registered: true, playable: true, topicId: 31, mode: 1,
      chapters: [{ chapterId: 1, name: '第一章' }], nodes, ...extra }
  });
  async function load(result, first = true) {
    page.loadData(first);
    const pending = requests.splice(0);
    assert.equal(pending.length, 1); assert.equal(pending[0].url, '/api/play/nodes');
    pending[0].success(result);
    await new Promise(resolve => setImmediate(resolve));
  }
  return { page, dialogs, locations, requests, load, response };
}

for (const mode of [1, 2]) test('entering a playable located game offers consent before a task, mode ' + mode, async () => {
  const h = harness(); await h.load(h.response({ mode }));
  assert.equal(h.dialogs.length, 1); assert.equal(h.dialogs[0].title, '开启定位');
  assert.equal(h.locations.length, 0, 'no SDK location before explicit consent');
  assert.equal(h.page._runStarted, undefined, 'entry must not start the game or its clock');
  h.dialogs[0].success({ confirm: false });
  await h.load(h.response({ mode }), false);
  assert.equal(h.dialogs.length, 1, 'poll/reload must not repeat a declined offer');
  h.page.requestPlayLocation();
  assert.equal(h.dialogs.length, 2, 'existing task control can explicitly retry after refusal');
  h.dialogs[1].success({ confirm: true });
  assert.equal(h.locations.length, 1);
  h.page._stopTracking();
});

for (const state of ['unregistered', 'spectator', 'unplayable', 'completed', 'story-only', 'paused', 'need-pass', 'malformed']) {
  test('entry does not request location for ' + state, async () => {
    const h = harness();
    let result = h.response();
    if (state === 'unregistered') result.data.registered = false;
    if (state === 'spectator') { result.data.registered = false; result.data.leadSpectator = true; }
    if (state === 'unplayable') result.data.playable = false;
    if (state === 'completed') result.data.nodes[0].done = true;
    if (state === 'story-only') result.data.nodes = [{ nodeId: 11 }];
    if (state === 'paused') h.page.data.runState = 'paused';
    if (state === 'need-pass') result = { code: 402, msg: '请先获取通行证' };
    if (state === 'malformed') result.data.nodes = null;
    await h.load(result);
    assert.equal(h.dialogs.length, 0); assert.equal(h.locations.length, 0);
  });
}

for (const change of ['hide', 'account']) test('stale entry read cannot prompt after ' + change, async () => {
  const h = harness(); h.page.loadData(true);
  assert.equal(h.requests.length, 1);
  if (change === 'hide') h.page._themeHidden = true;
  else h.page._playOwner = 'another-member';
  h.requests[0].success(h.response());
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.dialogs.length, 0); assert.equal(h.locations.length, 0);
});
