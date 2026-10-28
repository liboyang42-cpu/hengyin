'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createRecovery } = require('../../utils/team-event-command.js');
function harness(store = new Map()) {
  let uid = '71', token = 'a', serial = 0;
  const requests = [], views = [], receipts = [];
  const app = { getUserID: () => uid, getAuthorization: () => token, sendRequest: r => requests.push(r) };
  const platform = {
    getStorageSync: k => store.has(k) ? JSON.parse(store.get(k)) : '',
    setStorageSync: (k, v) => store.set(k, JSON.stringify(v)), removeStorageSync: k => store.delete(k),
    getRandomValues: o => o.success({ randomValues: new Uint8Array(16).fill(++serial).buffer })
  };
  const open = () => createRecovery({ app, platform, domain: 'EVENT', targetId: '9007199254740993',
    changed: v => views.push(v), terminal: (r,c) => receipts.push({ r,c }) });
  return { store, requests, views, receipts, platform, open, switchAccount() { uid = '72'; token = 'b'; } };
}
test('persist before dispatch; retry keeps exact key and frozen inputs despite caller edits', async () => {
  const h = harness(), recovery = h.open(), input = { reason: 'original', impactScope: 'all' };
  await recovery.start('pause', input);
  assert.equal(h.store.size, 1); assert.equal(h.requests.length, 1);
  const first = h.requests[0].data; input.reason = 'changed';
  h.requests[0].fail({}); recovery.retry();
  assert.equal(h.requests[1].data, first);
  assert.equal(JSON.parse(first).targetId, '9007199254740993');
});
test('UNKNOWN negative query never releases original request or allows new intention', async () => {
  const h = harness(), r = h.open(); await r.start('pause', { reason: 'first' });
  const key = JSON.parse(h.requests[0].data).requestId;
  h.requests[0].fail({}); r.query();
  h.requests[1].success({ code: 200, data: { requestId: key, state: 'UNKNOWN' } });
  await r.start('resume', { reason: 'second' }); r.acknowledge();
  assert.equal(h.requests.length, 2); assert.equal(h.store.size, 1); assert.ok(r.pending());
});
test('re-entry restores original input, and cancellation sends the same frozen command', async () => {
  const h = harness(), r = h.open(); await r.start('pause', { reason: 'original' });
  const original = h.requests[0].data; r.dispose();
  const next = h.open(); next.cancel();
  assert.ok(h.requests[1].url.endsWith('/cancel')); assert.equal(h.requests[1].data, original);
});
test('only matching terminal receipt permits acknowledgment; latest business state is irrelevant', async () => {
  const h = harness(), r = h.open(); await r.start('pause', {});
  const key = JSON.parse(h.requests[0].data).requestId;
  h.requests[0].success({ code: 200, data: { controlState: 'PAUSED' } });
  assert.equal(h.receipts.length, 0); r.query();
  h.requests[1].success({ code: 200, data: { requestId: 'bad', state: 'APPLIED' } });
  assert.equal(h.receipts.length, 0); r.query();
  h.requests[2].success({ code: 200, data: { requestId: key, state: 'APPLIED' } });
  assert.equal(h.receipts.length, 1); assert.equal(h.store.size, 1);
  r.acknowledge(); assert.equal(h.store.size, 0); assert.equal(r.pending(), false);
});
test('storage failure blocks all network writes', async () => {
  const h = harness(); h.platform.setStorageSync = () => { throw new Error('full'); };
  const r = h.open(); await r.start('pause', {});
  assert.equal(h.requests.length, 0); assert.equal(h.views.at(-1).state, 'STORAGE_ERROR');
});
test('old actor receipt never renders after switch; other actor has independent storage', async () => {
  const h = harness(), r = h.open(); await r.start('pause', {});
  const key = JSON.parse(h.requests[0].data).requestId; h.switchAccount();
  h.requests[0].success({ code: 200, data: { requestId: key, state: 'APPLIED' } });
  assert.equal(h.receipts.length, 0); const other = h.open();
  assert.equal(other.pending(), false); assert.equal(h.store.size, 1);
});
test('single flight includes cryptographic key allocation and network callbacks', async () => {
  const h = harness(), r = h.open(); await Promise.all([r.start('pause', {}), r.start('pause', {})]);
  assert.equal(h.requests.length, 1); r.retry(); r.cancel(); r.query(); assert.equal(h.requests.length, 1);
});
test('terminal result is retained across reentry until explicitly acknowledged', async () => {
  const h = harness(), r = h.open(); await r.start('pause', {});
  const key = JSON.parse(h.requests[0].data).requestId;
  h.requests[0].success({ code: 200, data: { requestId: key, state: 'REJECTED' } });
  r.dispose(); const next = h.open(); assert.equal(h.views.at(-1).state, 'REJECTED');
  next.retry(); assert.equal(h.requests.length, 1); next.acknowledge(); assert.equal(h.store.size, 0);
});
test('cross-page empty snapshot cannot overwrite another pending intention', async () => {
  const h = harness(), a = h.open(), b = h.open();
  await a.start('pause', { reason: 'first' }); h.requests[0].fail();
  const saved = [...h.store.values()];
  await b.start('resume', { reason: 'second' });
  assert.equal(h.requests.length, 1); assert.deepEqual([...h.store.values()], saved);
});
test('cross-page late receipt cannot resurrect an acknowledged command', async () => {
  const h = harness(), a = h.open(); await a.start('pause', {});
  const b = h.open(); b.query();
  const result = { code: 200, data: { state: 'APPLIED', requestId: JSON.parse(h.requests[0].data).requestId } };
  h.requests[1].success(result); b.acknowledge(); assert.equal(h.store.size, 0);
  h.requests[0].success(result); assert.equal(h.store.size, 0); assert.equal(h.receipts.length, 1);
});
test('cross-page stale terminal cannot remove a newer pending command', async () => {
  const h = harness(), a = h.open(); await a.start('pause', {});
  h.requests[0].success({ code: 200, data: { state: 'APPLIED', requestId: JSON.parse(h.requests[0].data).requestId } });
  const b = h.open(); b.acknowledge(); await b.start('resume', {});
  const saved = [...h.store.values()]; a.acknowledge();
  assert.deepEqual([...h.store.values()], saved);
});
