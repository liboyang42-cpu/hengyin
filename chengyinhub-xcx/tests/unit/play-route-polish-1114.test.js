const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
let pageDef, store, modals, generation;
global.Behavior = b => b;
global.Page = value => { pageDef = value; };
function patch(data, values) {
  for (const [key, value] of Object.entries(values)) {
    const parts = key.split('.'); let target = data;
    for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {});
    target[parts.at(-1)] = value;
  }
}
beforeEach(() => {
  store = new Map(); modals = []; generation = 1;
  global.wx = { getStorageSync: k => store.get(k), setStorageSync: (k,v) => store.set(k,v), removeStorageSync: k => store.delete(k),
    getSystemInfoSync: () => ({}), getWindowInfo: () => ({}), showModal: opts => modals.push(opts), showToast() {}, nextTick: fn => fn() };
  global.getApp = () => ({ getUserID: () => 7, globalData: {} });
  const file = require.resolve('../../pages/play/index.js'); delete require.cache[file]; require(file);
});
function page(run = '101') {
  const p = Object.assign({}, pageDef, {
    data: JSON.parse(JSON.stringify(pageDef.data)), _playOwner: 7, _routeState: { sessionId: run },
    _isPlayAccountCurrent: () => true, _boundPlayOwner: () => 7, _capturePlayLoginContext: () => ({ generation }),
    _runSessionScope: () => ({ memberId: 7, topicId: 19, activityId: 0, routeSessionId: run }), _sessionKey: () => 't:19',
    setData(values, cb) { patch(this.data, values); if (cb) cb(); },
    _autoStationVoice() {}, _mergeStoryVars() {}, _applyR5AdvancedView: () => false, _onKaccha() {},
    _stopTracking() {}, stopAudio() {}, rebuild() {}, watchLocation() {}, _hasGenericProofFlow: () => false,
  });
  p.data.advancedPlay = { show: true, node: { nodeId: 11 } };
  p.data.playKit = { show: true, kit: { type: 'qa', mode: 'type', sessionId: 42, version: 0 }, readyForBase: false };
  p.data.helpPanel.nodeId = 11;
  const game = { data: { state: { sessionId: 42, version: 0 } }, action() { return Promise.resolve(true); } };
  p.selectComponent = () => game; p._advancedGameComponent = () => game;
  return { p, game };
}
function result(p, game, version, passed, photoPassed) {
  const view = { sessionId: 42, nodeId: 11, version, config: {}, readyForBase: !!photoPassed,
    playKit: { qa: { mode: 'TYPE', finished: passed, passed }, photoCheck: { passed: !!photoPassed } } };
  game.data.state = view; p.onAdvancedSession({ detail: view });
}
test('B1 page retains only server-accepted own input across photo and QA component remount', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { p, game } = page();
  p._submitPlayKitAction('submit', { input: '041' });
  result(p, game, 1, true, false); await Promise.resolve(); assert.equal(p._kit().type, 'photocheck');
  result(p, game, 2, true, true); assert.equal(p._kit().acceptedInput, '041');
  let qa; global.Component = def => { qa = def; };
  const file = require.resolve('../../pages/playkit/components/playkit-qa/index.js'); delete require.cache[file]; require(file);
  const kit = p._kit(), c = Object.assign({}, qa.methods, { data: Object.assign({}, qa.data, kit, { show: true }),
    setData(v) { Object.assign(this.data,v); }, selectComponent: () => null });
  const observer = Object.keys(qa.observers).find(k => k.startsWith('show, options'));
  qa.observers[observer].apply(c, observer.split(',').map(k => c.data[k.trim()]));
  assert.equal(c.data.typed, '041'); assert.equal(c.data.passed, true);
  c.onInput({ detail: { value: 'changed' } }); assert.equal(c.data.typed, '041');
});
test('B1 rejected input, different run, node or login never becomes accepted display text', async () => {
  const { p, game } = page();
  p._submitPlayKitAction('submit', { input: 'wrong' }); result(p, game, 1, false, false); await Promise.resolve();
  result(p, game, 2, true, true); assert.equal(p._kit().acceptedInput, '');
  const key = p._qaInputKey(42, 11);
  assert.notEqual(key, page('102').p._qaInputKey(42, 11));
  assert.notEqual(key, p._qaInputKey(42, 12)); generation++;
  assert.notEqual(key, p._qaInputKey(42, 11));
});
test('B1 refused/unknown original command cannot borrow a later accepted view', async () => {
  for (const outcome of [false, undefined]) {
    const { p, game } = page();
    game.action = () => Promise.resolve(outcome);
    p._submitPlayKitAction('submit', { input: 'unconfirmed' });
    result(p, game, 1, true, false); await Promise.resolve();
    result(p, game, 2, true, true);
    assert.equal(p._kit().acceptedInput, '');
  }
});
test('B3 solved station start skips kit-start and goes through the existing arrival path', () => {
  const { p } = page(); const node = { nodeId: 11, hasAdvanced: true, vm: 0, lat: 31, lng: 121 };
  p.data.sheet = { show: true, node }; p._advancedReadyNodeId = 11;
  p.data.gpsOk = true; p._loc = { latitude: 31, longitude: 121, capturedAt: Date.now() };
  p._isStoryGame = () => false; p._startTooFar = () => ''; let arrivals = 0;
  p.arrive = () => { arrivals++; };
  p.openKitStart({ currentTarget: { dataset: { origin: 'sheet' } } });
  assert.equal(p.data.kitStart.show, false); assert.equal(arrivals, 1);
  p._advancedReadyNodeId = null;
  p.openKitStart({ currentTarget: { dataset: { origin: 'sheet' } } });
  assert.equal(p.data.kitStart.show, true); assert.equal(arrivals, 1);
});
test('B4 decline persists for this run, reopening does not prompt/read GPS, new run asks', () => {
  const { p } = page(); p.requestPlayLocation(); assert.equal(modals.length, 1);
  modals[0].success({ confirm: false });
  const reopened = page().p; reopened.requestPlayLocation();
  assert.equal(modals.length, 1); assert.equal(reopened._playLocationDeclined, true); assert.equal(reopened.data.locAsked, true);
  page('102').p.requestPlayLocation(); assert.equal(modals.length, 2);
  let setting = 0; wx.getSetting = () => { setting++; };
  reopened.onEnableLocation(); assert.equal(setting, 1);
  assert.equal(require('../../pages/play/utils/story-resume').read(reopened._runSessionScope()).locationDeclined, false);
});
test('u1 actual footer height includes safe area; stale and hidden measurements cannot replace it', () => {
  let def; global.Component = d => { def = d; };
  const file = require.resolve('../../pages/topic/components/cy/self-play-checkout/index.js'); delete require.cache[file]; require(file);
  const callbacks = [], c = Object.assign({}, def.methods, { data: { show: true, footerHeight: 0 },
    setData(v) { Object.assign(this.data,v); }, createSelectorQuery: () => ({ select: () => ({ boundingClientRect(fn) { callbacks.push(fn); return { exec() {} }; } }) }) });
  c.measureFooter(); c.measureFooter(); callbacks[1]({ height: 240 }); callbacks[0]({ height: 190 });
  assert.equal(c.data.footerHeight, 240); c.data.show = false; callbacks[1]({ height: 300 }); assert.equal(c.data.footerHeight, 240);
  const source = fs.readFileSync(require.resolve('../../pages/topic/components/cy/self-play-checkout/index.wxml'), 'utf8');
  assert.match(source, /ck__end-space[^>]*footerHeight/);
});
