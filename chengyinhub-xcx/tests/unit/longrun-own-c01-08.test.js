'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createGameSessionClient } = require('../../utils/game-session-client.js');
function load() {
  let request;
  const client = createGameSessionClient({ sendRequest: r => { request = r;
    return { abort() { r.fail({ errMsg: 'request:fail abort' }); } }; } });
  return { client, request: () => request };
}
for (const outcome of ['PENDING', 'FAILED', 'APPLIED']) test('receipt query separates ' + outcome, async () => {
  const h = load();
  const pending = h.client.readReceipt(9, 'fixture-request-901');
  h.request().success({ code: 200, data: { activityId: 9, requestId: 'fixture-request-901',
    receiptId: 801, revision: 5, action: 'PLAYER_CHOICE', outcome } });
  const result = await pending;
  assert.equal(result.status, { PENDING: 'pending', FAILED: 'business-error', APPLIED: 'matched' }[outcome]);
  assert.equal(result.requestId, 'fixture-request-901');
  assert.equal(result.receipt.outcome, outcome);
});
for (const mode of ['abort', 'network', 'http', 'missing']) test('query ' + mode + ' is never a terminal failure receipt', async () => {
  const h = load();
  const pending = h.client.readReceipt(9, 'fixture-request-901');
  if (mode === 'abort') pending.abort();
  if (mode === 'network') h.request().fail({});
  if (mode === 'http') h.request().successStatusAbnormal({ code: 503 });
  if (mode === 'missing') h.request().success({ code: 404, msg: 'not found' });
  const result = await pending;
  assert.equal(result.requestId, 'fixture-request-901');
  assert.equal(result.receipt, undefined);
  assert.notEqual(result.status, 'matched');
  assert.notEqual(result.status, 'success');
  if (mode !== 'missing') assert.equal(result.status, 'network-error');
});
