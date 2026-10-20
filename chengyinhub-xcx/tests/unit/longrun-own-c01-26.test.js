'use strict';
// Authored regression; NOT_RUN by source-only delivery instruction.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition, scan, picked; const requests = [], completed = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => '11', sendRequest: r => requests.push(r), chooseImage: cb => { picked = cb; } });
  global.Page = value => { definition = value; };
  global.wx = { scanCode: options => { scan = options; }, showToast() {} };
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, mode: 1, game: { nodeId: 31 }, answerInput: 'fixture' },
    _routeState: { sessionId: 's9', version: 4 }, _routeActions: {}, setData(patch) { Object.assign(this.data, patch); },
    _routeWritePayload(kind, nodeId, extra) { return Object.assign({ activityId: this.data.activityId, routeActionId: 'original-fixture-key' }, extra); },
    clearGameNetworkError() {}, _resolveRouteAction() {}, onComplete: id => completed.push(id), _applyAiScore() {} });
  return { page, requests, completed, scan: () => scan, picked: () => picked };
}
test('old scanner and picker cannot submit for replacement game', () => {
  const h = load(); const calls = []; h.page.checkin = (...args) => calls.push(args); h.page.submitPhoto = (...args) => calls.push(args);
  h.page.scanGame(); h.page.photoGame(); h.page.data.game = { nodeId: 32 };
  h.scan().success({ result: 'fixture' }); h.picked()(['fixture-image']); assert.deepEqual(calls, []);
});
for (const method of ['submitPhoto', 'submitGame', 'revealAnswer']) test(method + ' cannot send after thought wait changes scope', async () => {
  const h = load(); let done; h.page.data.game.puzzleScoring = true;
  h.page._thoughtSyncPending = new Promise(resolve => { done = resolve; }); h.page._syncThoughts = () => Promise.resolve(true);
  h.page[method](31, 'fixture-image'); h.page.data.activityId = 10; done(true);
  await new Promise(resolve => setImmediate(resolve)); assert.deepEqual(h.requests, []); assert.deepEqual(h.completed, []);
});
test('old photo response cannot finish replacement game or release its write lock', async () => {
  const h = load(); h.page.submitPhoto(31, 'fixture-image'); await new Promise(resolve => setImmediate(resolve));
  h.page.data.game = { nodeId: 32 }; h.page._gameWriteInFlight = false; h.page.submitPhoto(32, 'next-image');
  const next = h.page._gameWriteIntent; h.requests[0].success({ code: 200, data: {} });
  await new Promise(resolve => setImmediate(resolve)); assert.equal(h.page._gameWriteIntent, next); assert.equal(h.page._gameWriteInFlight, true); assert.deepEqual(h.completed, []);
});
