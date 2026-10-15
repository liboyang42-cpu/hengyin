'use strict';
// Written, NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition;
  const scans = [], toasts = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => '11' });
  global.Page = v => { definition = v; };
  global.wx = { scanCode: r => scans.push(r), showToast: r => toasts.push(r) };
  const file = path.resolve(__dirname, '../../pages/play/index.js');
  delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8,
    gameModule: { enabled: true, activityId: 9, sessionId: 91, nodes: [
      { nodeId: 31, status: 'AVAILABLE', playerTask: { taskCode: 'SCAN_A', inputType: 'SCAN' } },
    ] } } });
  page.setData = patch => { if (patch['gameModule.nodes']) page.data.gameModule.nodes = patch['gameModule.nodes']; };
  return { page, scans, toasts };
}
for (const change of ['activity', 'session', 'task', 'status', 'unload']) test('scan ignores changed ' + change, () => {
  const h = load();
  h.page.scanPlayerTaskEvidence({ currentTarget: { dataset: { node: 31 } } });
  if (change === 'activity') h.page.data.activityId = 19;
  if (change === 'session') h.page.data.gameModule.sessionId = 92;
  if (change === 'task') h.page.data.gameModule.nodes[0].playerTask.taskCode = 'SCAN_B';
  if (change === 'status') h.page.data.gameModule.nodes[0].status = 'COMPLETED';
  if (change === 'unload') h.page._playUnloaded = true;
  h.scans[0].success({ result: 'fixture-code' });
  h.scans[0].fail({ errMsg: 'camera failed' });
  assert.equal(h.page.data.gameModule.nodes[0].taskEvidence, undefined);
  assert.deepEqual(h.toasts, []);
});
test('newest scan for the same task owns the callback', () => {
  const h = load();
  const e = { currentTarget: { dataset: { node: 31 } } };
  h.page.scanPlayerTaskEvidence(e); h.page.scanPlayerTaskEvidence(e);
  h.scans[1].success({ result: 'new' }); h.scans[0].success({ result: 'old' });
  assert.deepEqual(h.page.data.gameModule.nodes[0].taskEvidence.evidenceUrls, ['scan:new']);
});
