'use strict';
// Source-only regression, NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition;
  const uploads = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => '11', chooseImage: cb => uploads.push(cb) });
  global.Page = v => { definition = v; };
  global.wx = {};
  const file = path.resolve(__dirname, '../../pages/play/index.js');
  delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8,
    gameModule: { enabled: true, activityId: 9, sessionId: 91, nodes: [
      { nodeId: 31, status: 'AVAILABLE', playerTask: { taskCode: 'PHOTO_A', inputType: 'PHOTO' } },
    ] } } });
  page.setData = patch => { if (patch['gameModule.nodes']) page.data.gameModule.nodes = patch['gameModule.nodes']; };
  return { page, uploads };
}
for (const change of ['activity', 'session', 'task', 'submission', 'unload']) test('upload ignores changed ' + change, () => {
  const h = load();
  h.page.photoPlayerTaskEvidence({ currentTarget: { dataset: { node: 31 } } });
  if (change === 'activity') h.page.data.activityId = 19;
  if (change === 'session') h.page.data.gameModule.sessionId = 92;
  if (change === 'task') h.page.data.gameModule.nodes[0].playerTask.taskCode = 'PHOTO_B';
  if (change === 'submission') h.page.data.gameModule.nodes[0].submission = { submissionId: 8, status: 'PENDING' };
  if (change === 'unload') h.page._playUnloaded = true;
  h.uploads[0](['https://cdn.example.com/fixture.jpg']);
  assert.equal(h.page.data.gameModule.nodes[0].taskEvidence, undefined);
});
test('newest photo upload wins and empty or failed upload never creates ready evidence', () => {
  const h = load();
  const e = { currentTarget: { dataset: { node: 31 } } };
  h.page.photoPlayerTaskEvidence(e); h.page.photoPlayerTaskEvidence(e);
  h.uploads[1](['https://cdn.example.com/new.jpg']);
  h.uploads[0](['https://cdn.example.com/old.jpg']);
  assert.deepEqual(h.page.data.gameModule.nodes[0].taskEvidence.evidenceUrls, ['https://cdn.example.com/new.jpg']);
  h.page.photoPlayerTaskEvidence(e); h.uploads[2]([]);
  assert.deepEqual(h.page.data.gameModule.nodes[0].taskEvidence.evidenceUrls, ['https://cdn.example.com/new.jpg']);
});
