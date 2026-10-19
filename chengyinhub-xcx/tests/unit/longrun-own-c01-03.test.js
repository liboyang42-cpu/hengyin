'use strict';
// Authored only. All execution NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load(saved) {
  let definition;
  const requests = [];
  global.getApp = () => ({ getUserID: () => '11', sendRequest: r => requests.push(r) });
  global.Page = v => { definition = v; };
  global.wx = { getStorageSync: () => saved || '', removeStorageSync() {}, setStorageSync() {} };
  const file = path.resolve(__dirname, '../../pages/play/index.js');
  delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: '9', topicId: '8' },
    _applyRestoredRun() { this.restores = (this.restores || 0) + 1; } });
  return { page, requests };
}
for (const change of ['activity', 'epoch', 'unload']) test('restore read ignores obsolete ' + change, async () => {
  const h = load();
  h.page._restoreRunSession({}, {}, []);
  if (change === 'activity') h.page.data.activityId = '19';
  if (change === 'epoch') h.page._runRestoreEpoch += 1;
  if (change === 'unload') h.page._playUnloaded = true;
  h.requests[0].success({ code: 200, data: { runState: 'PAUSED', elapsedSeconds: 90, savedAt: 100 } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.page.restores || 0, 0);
});
test('story anchor callback cannot position a newer chapter', () => {
  const h = load({ pos: { chapterId: '3', i: 1 } });
  Object.assign(h.page.data, { chapter: { id: 3 }, chapterParas: [{ i: 1, kind: 'text' }], nodes: [] });
  let callback;
  const patches = [];
  h.page.setData = (patch, cb) => { patches.push(patch); callback = cb || callback; };
  h.page._resumeStory();
  h.page.data.chapter.id = 4;
  callback();
  assert.deepEqual(patches, [{ chapterGameAnchor: '' }]);
});
