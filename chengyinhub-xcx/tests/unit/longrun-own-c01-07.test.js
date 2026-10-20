'use strict';
// Deferred execution only: NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createGameSessionClient } = require('../../utils/game-session-client.js');
for (const replyActivity of [9, 19]) test('receipt uses sent activity, not mutated caller input: ' + replyActivity, async () => {
  let request;
  const client = createGameSessionClient({ sendRequest: r => { request = r; } });
  const input = { activityId: 9, nodeId: 31, requestId: 'fixture-request-901', expectedRevision: 4,
    action: 'PLAYER_CHOICE', payload: { choiceId: 'A' } };
  const pending = client.submitAction('player', input);
  input.activityId = 19;
  request.success({ code: 200, data: { activityId: replyActivity, requestId: 'fixture-request-901',
    action: 'PLAYER_CHOICE', outcome: 'APPLIED', receiptId: 801, revision: 5 } });
  const result = await pending;
  assert.equal(request.data.activityId, 9);
  assert.equal(result.status, replyActivity === 9 ? 'success' : 'unknown');
  assert.equal(result.requestId, 'fixture-request-901');
});
for (const overrides of [{ action: 'PLAYER_HINT' }, { requestId: 'different-request-901' }, { revision: null }]) {
  test('mismatched terminal receipt remains unknown ' + JSON.stringify(overrides), async () => {
    let request;
    const client = createGameSessionClient({ sendRequest: r => { request = r; } });
    const input = { activityId: 9, nodeId: 31, requestId: 'fixture-request-901', expectedRevision: 4,
      action: 'PLAYER_CHOICE', payload: {} };
    const pending = client.submitAction('player', input);
    request.success({ code: 200, data: Object.assign({ activityId: 9, requestId: input.requestId,
      action: input.action, outcome: 'APPLIED', receiptId: 801, revision: 5 }, overrides) });
    assert.equal((await pending).status, 'unknown');
  });
}
