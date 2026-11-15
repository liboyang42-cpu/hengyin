const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '../..');
function challenge(location) {
  let page, definition, misses = 0, wins = 0; const requests = [];
  const app = { globalData: {}, ensureLoginFor: () => true, sendRequest: r => requests.push(r) };
  const wx = { getLocation: r => location(r), vibrateShort() {}, vibrateLong() {}, getStorageSync() {} };
  const load = (rel, capture) => {
    const file = path.join(root, rel);
    vm.runInNewContext(fs.readFileSync(file, 'utf8'), { require: createRequire(file), getApp: () => app,
      wx, Page: capture, Component: capture, console, setTimeout: () => 1, clearTimeout() {} });
  };
  global.wx = wx; global.getApp = () => app; global.Behavior = d => d;
  load('pages/play/components/playkit-qa/index.js', d => { definition = d; });
  const kit = { ...definition.methods, data: { ...definition.data, mode: 'pick', multi: false,
    reducedMotion: true, judge: true, optionList: [{ id: 'A', state: 'is-on' }, { id: 'B', state: '' }] },
    setData(patch) { Object.assign(this.data, patch); },
    selectComponent: () => ({ miss() { misses++; return false; }, win() { wins++; }, nudge() {} }) };
  load('pages/play/poi-challenge/index.js', d => { page = d; });
  page.data = { ...page.data, node: { lat: 0, lng: 0, radiusM: 80 } }; page._poiId = 1;
  page.setData = patch => Object.assign(page.data, patch); page.selectComponent = () => kit;
  return { page, kit, requests, misses: () => misses, wins: () => wins };
}
for (const reason of ['far', 'denied', 'server-distance']) {
  test('UI-02 neutral arrival rejection: ' + reason, () => {
    const h = challenge(r => reason === 'denied' ? r.fail({}) : r.success({ latitude: reason === 'far' ? 1 : 0, longitude: 0 }));
    h.page.onPick({ detail: { optionId: 'A', index: 0 } });
    if (reason === 'server-distance') h.requests.at(-1).success({ code: 500, msg: '离据点太远，请到店后再完成' });
    assert.equal(h.misses(), 0, 'unjudged answer must not consume a miss');
    assert.equal(h.wins(), 0); assert.equal(h.kit.data.optionList[0].state, 'is-on');
    assert.equal(h.page._result, undefined);
  });
}
test('UI-02 real wrong answer still uses miss and wrong option state', () => {
  const h = challenge(r => r.success({ latitude: 0, longitude: 0 }));
  h.page.onPick({ detail: { optionId: 'A', index: 0 } });
  h.requests.at(-1).success({ code: 500, msg: '答案不正确' });
  assert.equal(h.misses(), 1); assert.equal(h.wins(), 0); assert.equal(h.kit.data.optionList[0].state, 'is-wrong');
});
function hangman() {
  let definition; const events = [];
  global.Behavior = d => d; global.wx = { getStorageSync() {} };
  const file = path.join(root, 'pages/play/components/playkit-hangman/index.js');
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { require: createRequire(file), Component: d => { definition = d; },
    wx: global.wx, setTimeout: () => 1, clearTimeout() {} });
  const slots = Array.from('STONE', (ch, i) => i === 1 ? { i, kind: 'PREFILLED', ch } : { i, kind: 'BLANK' });
  const c = { ...definition.methods, data: { ...definition.data, slots, reducedMotion: true, solved: false },
    setData(patch) { Object.assign(this.data, patch); }, triggerEvent(name, detail) { events.push({ name, detail }); } };
  definition.observers['show, slots, solved'].call(c, true, slots, false);
  return { c, events, definition, slots };
}
test('GTA-01 existing slot entry retains fixed letters and emits slots only', () => {
  const { c, events } = hangman(); c.onInput({ detail: { value: 'SONE' } }); c.onSubmit();
  assert.deepEqual(JSON.parse(JSON.stringify(events[0].detail)), { slots: { 0: 'S', 2: 'O', 3: 'N', 4: 'E' } });
});
test('GTA-01 normal whole-answer control emits the entire long alias and leaves fixed slots unchanged', () => {
  const { c, events, slots } = hangman(); c.onToggleWord(); c.onInput({ detail: { value: 'GRANITE' } }); c.onSubmit();
  assert.deepEqual(JSON.parse(JSON.stringify(events[0].detail)), { input: 'GRANITE' });
  assert.equal(slots[1].ch, 'T');
  const markup = fs.readFileSync(path.join(root, 'pages/play/components/playkit-hangman/index.wxml'), 'utf8');
  assert.match(markup, /bindtap="onToggleWord"/); assert.match(markup, /wx:if="\{\{wholeWord\}\}"/);
});
test('GTA-01 whole-answer empty input cannot submit and switching back retains slot work', () => {
  const { c, events } = hangman(); c.onInput({ detail: { value: 'SONE' } });
  c.onToggleWord(); c.onInput({ detail: { value: '  ' } }); c.onSubmit(); assert.equal(events.length, 0);
  c.onToggleWord(); c.onSubmit(); assert.equal(events[0].detail.slots[0], 'S');
});
test('GTA-01 canonical and incorrect candidates stay server-judged; failed word can retry without changing fixed letters', () => {
  const { c, events, definition, slots } = hangman(); c.onToggleWord();
  c.onInput({ detail: { value: 'STONE' } }); c.onSubmit(); assert.equal(events[0].detail.input, 'STONE');
  definition.observers['show, slots, solved'].call(c, true, slots, false);
  assert.equal(c.data.word, ''); assert.equal(c.data.canSubmit, false); assert.equal(slots[1].ch, 'T');
  c.onInput({ detail: { value: 'GRANIT' } }); c.onSubmit(); assert.equal(events[1].detail.input, 'GRANIT');
  assert.equal(c.data.solved, false, 'component must not decide a candidate is correct');
  definition.observers['show, slots, solved'].call(c, false, slots, false);
  assert.equal(c.data.word, ''); assert.equal(c.data.wholeWord, false);
});
test('GTA-02 completion boolean cannot classify pending, failed or unrecognised run report', () => {
  const { resolveFinishResult } = require('../../pages/play/utils/finish-result');
  for (const state of ['pending', 'failed', 'ok']) {
    const result = resolveFinishResult(null, { topicCompleted: true, allDone: true }, state);
    assert.equal(result.label, ''); assert.equal(result.win, false); assert.equal(result.source, 'unknown');
  }
  assert.equal(resolveFinishResult({ completionKind: 'ASSISTED_COMPLETION' }, { topicCompleted: true }, 'ok').label, '辅助完成');
  assert.equal(resolveFinishResult({ completionKind: 'LEGITIMATE_COMPLETION' }, {}, 'ok').label, '已通关');
});
function endingPage() {
  let page; const requests = [], shown = []; let current = true, finishes = 0;
  const app = { globalData: {}, getUserID: () => 1, sendRequest: r => requests.push(r) };
  const wx = { getSystemInfoSync: () => ({}), getMenuButtonBoundingClientRect: () => ({}), getStorageSync() {} };
  global.wx = wx; global.getApp = () => app;
  const file = path.join(root, 'pages/play/index.js');
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { require: createRequire(file), getApp: () => app, wx,
    Page: d => { page = d; }, console, setTimeout: () => 1, clearTimeout() {} });
  page.data = { ...page.data, topicId: 1, activityId: 0, topicName: 'TEST', mode: 1, nodes: [], isMock: false, isPreview: false };
  page._routeState = { sessionId: 22 };
  page.setData = patch => Object.assign(page.data, patch);
  page._isPlayAccountCurrent = () => current; page._boundPlayOwner = () => '1'; page._sessionKey = () => '1:0';
  page._endTeamTrail = () => {}; page._endRunSession = () => {};
  page._showEnding = card => shown.push(card);
  const open = page.openFinish;
  page.openFinish = function () { if (this._lifeEndingFailedScope) { finishes++; return; } return open.call(this); };
  return { page, requests, shown, retire() { current = false; }, finishes: () => finishes };
}
test('GTA-03 journey ending failed read remains unshown and normal retry uses same read scope', async () => {
  const h = endingPage(); h.page.openFinish();
  assert.notEqual(h.page._lifeEndingShown, true, 'in-flight read is not a shown ending');
  h.requests[0].success({ code: 500 }); await Promise.resolve(); await Promise.resolve();
  assert.equal(h.finishes(), 1); assert.equal(h.page.data.endingError, true); assert.notEqual(h.page._lifeEndingShown, true);
  h.page.retryEnding(); assert.equal(h.requests.length, 2);
  assert.equal(h.requests[1].url, '/api/play/journey/ending');
  assert.deepEqual(JSON.parse(JSON.stringify(h.requests[1].data)), { topicId: 1, activityId: 0 });
  h.requests[1].success({ code: 200, data: { code: 'end', title: 'frozen ending' } }); await Promise.resolve();
  assert.equal(h.shown.length, 1); assert.equal(h.page._lifeEndingShown, true); assert.equal(h.page.data.endingError, false);
});
test('GTA-03 retired journey ending response cannot mark a different account shown', async () => {
  const h = endingPage(); h.page.openFinish(); h.retire();
  h.requests[0].success({ code: 200, data: { title: 'old account' } }); await Promise.resolve();
  assert.equal(h.shown.length, 0); assert.notEqual(h.page._lifeEndingShown, true);
});

for (const retired of ['node', 'account']) {
  test('GTA-01 whole-answer late response cannot cross ' + retired + ' boundary', async () => {
    let definition; const requests = [], events = []; let account = 1;
    const app = { _loginEpoch: 1, getUserID: () => account, sendRequest: r => requests.push(r) };
    const store = new Map();
    const wx = { getStorageSync: key => store.get(key), setStorageSync: (key, value) => store.set(key, value),
      removeStorageSync: key => store.delete(key) };
    global.wx = wx; global.getApp = () => app;
    const file = path.join(root, 'pages/play/components/advanced-game/index.js');
    vm.runInNewContext(fs.readFileSync(file, 'utf8'), { require: createRequire(file), getApp: () => app, wx,
      Component: d => { definition = d; }, console, setTimeout: () => 1, clearTimeout() {} });
    const state = { sessionId: 501, version: 1, nodeId: 51, topicId: 1, activityId: 0, ownerId: 1, ownerType: 1 };
    const game = { ...definition.methods, data: { ...definition.data, state, node: { nodeId: 51 }, topicId: 1, activityId: 0 },
      setData(patch) { Object.assign(this.data, patch); }, triggerEvent: (name, detail) => events.push({ name, detail }) };
    const { c, events: submitted } = hangman(); c.onToggleWord(); c.onInput({ detail: { value: 'GRANITE' } }); c.onSubmit();
    const pending = game.action('SUBMIT_HANGMAN', submitted[0].detail);
    assert.equal(JSON.parse(requests[0].data).payload.input, 'GRANITE');
    if (retired === 'node') { game.data.node = { nodeId: 52 }; game.data.state = { ...state, sessionId: 502, nodeId: 52 }; }
    else { account = 2; app._loginEpoch++; }
    const current = game.data.state;
    requests[0].success({ code: 200, data: { ...state, version: 2, playKit: { hangman: { solved: true } } } });
    await pending;
    assert.equal(game.data.state, current); assert.equal(events.length, 0);
  });
}
