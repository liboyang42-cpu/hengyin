const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '../..');
let spec, store, asked, app;
global.Behavior = b => b;
global.Page = p => { spec = p; };
beforeEach(() => {
  store = new Map(); asked = 0;
  global.wx = { getStorageSync: k => store.get(k), setStorageSync: (k, v) => store.set(k, v),
    removeStorageSync: k => store.delete(k), getSystemInfoSync: () => ({}), getWindowInfo: () => ({}), showToast() {} };
  app = { getUserID: () => 7, globalData: {}, requestSeparateConsent: async (kind, current) => {
    asked++; assert.equal(kind, 'photo'); assert.equal(current(), true);
  } };
  global.getApp = () => app;
  const file = require.resolve('../../pages/play/index.js'); delete require.cache[file]; require(file);
});
function page() {
  return Object.assign({}, spec, { data: Object.assign(JSON.parse(JSON.stringify(spec.data)), {
    topicId: 9913301, activityId: 0, mode: 1, chapter: { id: 1, blocks: [] }, nodes: [],
  }), _playOwner: 7, _routeState: { sessionId: '' }, _serverRunKey: '901',
  _isPlayAccountCurrent: () => true, _playMember: () => 7, _sessionKey: () => 'topic:9913301',
  setData(patch) { Object.assign(this.data, patch); } });
}

test('A2/q2 server run key restores intro and chapter gates across page restarts, new run stays isolated', () => {
  const resume = require('../../pages/play/utils/story-resume.js');
  const first = page();
  assert.equal(first._runSessionScope().routeSessionId, '901');
  resume.patch(first._runSessionScope(), { intro: true, gates: ['1'] });
  const reopened = page(); let opened = 0;
  reopened.openChapterFull = () => { opened++; };
  reopened._openStory(); reopened.showChapterGate();
  assert.equal(opened, 1); assert.equal(reopened.data.storyIntro.show, false);
  assert.equal(reopened.data.chapterGate.show, false);
  reopened._routeState.sessionId = '902';
  assert.deepEqual(resume.read(reopened._runSessionScope()), {});
  reopened._openStory(); assert.equal(reopened.data.storyIntro.show, true);
  reopened._routeState.sessionId = ''; reopened._serverRunKey = '';
  resume.patch(reopened._runSessionScope(), { intro: true });
  assert.deepEqual(resume.read(reopened._runSessionScope()), {});
});

test('A1 consent uses nonempty server run, fresh run reasks, absent run refuses cache and upload', async () => {
  const p = page();
  await p._ensurePhotoConsent(() => true); assert.equal(asked, 1);
  await page()._ensurePhotoConsent(() => true); assert.equal(asked, 1);
  p._serverRunKey = '902'; await p._ensurePhotoConsent(() => true); assert.equal(asked, 2);
  p._serverRunKey = '';
  await assert.rejects(p._ensurePhotoConsent(() => true), /路线局尚未就绪/);
  assert.equal(asked, 2);
  assert.ok([...store.values()].every(v => !String(v).endsWith('|')));
});

test('A1 stale consent reason clears cache, asks and resubmits canonical uploaded reference once', async () => {
  const p = page(), kit = { type: 'photocheck', sessionId: 42, version: 3 };
  const commands = [], state = { sessionId: 42, version: 3 };
  const game = { data: { state }, action: (...args) => { commands.push(args); return Promise.resolve(true); } };
  p.data.advancedPlay = { show: true }; p._kit = () => kit;
  p._advancedGameComponent = () => game; p.selectComponent = () => game;
  p._notifyPhotoFail = () => {}; p._trackPhotoJudging = () => {};
  await p._ensurePhotoConsent(() => true); p._kitPhotoUrl = 'media-asset:701';
  p.onAdvancedActionFail({ detail: { action: 'SUBMIT_PHOTO_CHECK', sessionId: 42, reason: 'PHOTO_MODEL_CONSENT_REQUIRED' } });
  await new Promise(setImmediate);
  assert.equal(asked, 2);
  assert.deepEqual(commands, [['SUBMIT_PHOTO_CHECK', { imageUrl: 'media-asset:701' }]]);
  p.onAdvancedActionFail({ detail: { action: 'SUBMIT_PHOTO_CHECK', sessionId: 42, reason: 'PHOTO_MODEL_CONSENT_REQUIRED' } });
  await new Promise(setImmediate); assert.equal(commands.length, 1, 'no automatic consent loop');
});

test('A1 decline/late consent cannot cache or resubmit after leaving the question', async () => {
  const p = page(); let current = true, finish;
  app.requestSeparateConsent = () => new Promise(resolve => { finish = resolve; });
  const pending = p._ensurePhotoConsent(() => current); current = false; finish();
  await assert.rejects(pending, /玩法已变化/); assert.equal(store.size, 0);
});

test('q1 first arrival still requires start verification, subsequent stations do not request GPS', () => {
  const p = page(), node = { nodeId: 1, lat: 31, lng: 121 };
  p._arriveTarget = () => node; p._isStoryGame = () => false; p._startTooFar = () => '';
  let prompts = 0, arrivals = 0;
  p._locateThen = () => { prompts++; }; p.arrive = () => { arrivals++; };
  p.onArriveTap(); assert.equal(prompts, 1); assert.equal(arrivals, 0);
  p.data.nodes = [{ nodeId: 1, done: true }]; p.onArriveTap();
  assert.equal(prompts, 1); assert.equal(arrivals, 1);
  assert.match(fs.readFileSync(path.join(root, 'pages/play/index.wxml'), 'utf8'), /首站尚未到达：仅开局需在起点 300 米内核验定位，或扫码到达。/);
});

test('u1 fallback share/save returns actual thumbnail and reserves a matching landscape frame', () => {
  const p = page(); p.data.routeCard = { on: true, error: true, ready: false }; p.data.finishSnap = '/tmp/footprint.png';
  p._routeCardPath(result => assert.equal(result, '/tmp/footprint.png'));
  p.paintFinishCard(result => assert.equal(result, '/tmp/footprint.png'));
  assert.match(fs.readFileSync(path.join(root, 'pages/play/index.wxss'), 'utf8'), /is-fallback\{[^}]*aspect-ratio:690 \/ 320/);
});
