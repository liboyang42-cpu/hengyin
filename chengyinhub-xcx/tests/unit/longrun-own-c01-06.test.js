'use strict';
// Authored only, NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
for (const change of ['same', 'taskCode', 'inputType', 'prompt']) test('projection evidence merge checks ' + change, async () => {
  const client = require('../../utils/game-session-client.js');
  const original = client.loadProjection;
  let definition;
  global.getApp = () => ({ globalData: {}, getUserID: () => '11' });
  global.Page = v => { definition = v; };
  global.wx = { getStorageSync: () => '', removeStorageSync() {} };
  const task = { taskCode: 'SCAN_A', inputType: 'SCAN', prompt: 'Scan fixture', verificationRequired: false };
  const nextTask = { ...task };
  if (change === 'taskCode') nextTask.taskCode = 'SCAN_B';
  if (change === 'inputType') nextTask.inputType = 'PHOTO';
  if (change === 'prompt') nextTask.prompt = 'Scan a different fixture';
  client.loadProjection = () => Promise.resolve({ status: 'ready', data: {
    sessionId: 91, activityId: 9, perspective: 'PLAYER', status: 'RUNNING', revision: 9,
    availableActions: ['PLAYER_SUBMIT'], player: { role: {}, nodes: [
      { nodeId: 31, personalState: 'AVAILABLE', playerTask: nextTask },
    ] },
  } });
  try {
    const file = path.resolve(__dirname, '../../pages/play/index.js');
    delete require.cache[file]; require(file);
    const evidence = { type: 'SCAN', ready: true, evidenceUrls: ['scan:fixture'] };
    const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, nodes: [],
      gameModule: { enabled: true, activityId: 9, sessionId: 91, revision: 9, sheetShow: false,
        nodes: [{ nodeId: 31, status: 'AVAILABLE', playerTask: task, taskEvidence: evidence }] } }, rebuild() {} });
    page.setData = (patch, callback) => {
      for (const [key, value] of Object.entries(patch)) {
        if (key.startsWith('gameModule.')) page.data.gameModule[key.slice(11)] = value;
        else page.data[key] = value;
      }
      if (callback) callback();
    };
    page._loadPlayerGameModule();
    await new Promise(resolve => setImmediate(resolve));
    const result = page.data.gameModule.nodes[0].taskEvidence;
    if (change === 'same') assert.equal(result, evidence);
    else { assert.equal(result.ready, false); assert.deepEqual(result.evidenceUrls, []); }
  } finally { client.loadProjection = original; }
});
