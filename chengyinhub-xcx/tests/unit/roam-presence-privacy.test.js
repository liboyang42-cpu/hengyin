const test = require('node:test');
const assert = require('node:assert/strict');
const P = require('../../utils/roam-presence-privacy.js');

test('sharing is opt-in, saved by the server and read back before enabled', async () => {
  const calls = [];
  const app = { sendRequest(o) { calls.push(o); o.success({ code: 200, data: true }); } };
  await assert.rejects(P.setSharing(app, '', true), /请先开始漫游/);
  assert.equal(calls.length, 0);
  assert.equal(await P.setSharing(app, '77', true), true);
  assert.equal(calls[0].method, 'POST');
  assert.equal(calls[0].data.consentVersion, P.CONSENT_VERSION);
  assert.equal(calls[1].method, 'GET');
  assert.equal(calls[1].data.sessionId, '77');
});

test('closing without a session disables all own sessions; errors never claim success', async () => {
  let sent;
  const app = { sendRequest(o) { sent = o; o.success({ code: 200, data: false }); } };
  assert.equal(await P.setSharing(app, null, false), false);
  assert.equal(sent.data.visible, false);
  assert.equal(sent.data.sessionId, '');
  await assert.rejects(P.setSharing({ sendRequest(o) { o.fail(); } }, null, false), /无法确认/);
  await assert.rejects(P.setSharing({ sendRequest(o) { o.success({ code: 200, data: false }); } }, '77', true), /尚未确认/);
});
