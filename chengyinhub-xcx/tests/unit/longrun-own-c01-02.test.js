'use strict';
// Written only; NOT_RUN under CY-LONGRUN-SOURCE-20261015-v1.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
test('queued pause save stays in its captured activity and never applies response state', async () => {
  let definition;
  const requests = [], writes = [];
  global.getApp = () => ({ getUserID: () => '11', sendRequest: r => requests.push(r) });
  global.Page = value => { definition = value; };
  global.wx = { setStorageSync: (key, value) => writes.push([key, value]) };
  const file = path.resolve(__dirname, '../../pages/play/index.js');
  delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, {
    data: { activityId: '9', topicId: '8' }, _runStarted: true,
    _sessionClock: { isPaused: () => true, elapsedSeconds: () => 90 },
  });
  page._persistRunSession();
  page.data.activityId = '19';
  await Promise.resolve();
  const request = requests[0];
  assert.equal(request.url, '/api/play/run-session/save');
  assert.equal(request.data.activityId, '9');
  assert.equal(writes[0][0], 'play_paused_run_v2:m11:a9');
  request.success({ code: 200 });
  await page._runSyncChain;
  assert.equal(page.data.activityId, '19');
  assert.equal(writes.length, 1);
});
