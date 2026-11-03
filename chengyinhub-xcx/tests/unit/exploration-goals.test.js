const test = require('node:test');
const assert = require('node:assert/strict');
const { createExplorationGoalsClient } = require('../../subpackageTools/utils/features/exploration-goals');
const draft = { requestId: 'personal-goal-0001', metric: 'DISTINCT_ROUTES', target: 2, startDate: '2026-10-12', endDateExclusive: '2026-11-01' };
const row = { ...draft, id: '9007199254740999', progress: 0, state: 'ACTIVE', privateCommemoration: false, timeZone: 'Asia/Shanghai', countingVersion: 'COMPLETED_SESSION_AGGREGATE_V1', asOfMs: 1791763200000, createdAtMs: 1791763200000 };
function setup(extra = {}) {
  let identity = '7:login-1'; const sent = [];
  const client = createExplorationGoalsClient({ getIdentity: () => identity, sendRequest: req => sent.push(req), timeoutMs: 30, ...extra });
  return { client, sent, identity(value) { identity = value; } };
}
function ok(request, value = row) { request.success({ code: 200, data: value }); }
test('creates with original request and exact fields; same payload double tap joins one request', async () => {
  const s = setup(); const a = s.client.create({ ...draft, memberId: '8', progress: 500 }), b = s.client.create(draft);
  assert.equal(a, b); assert.equal(s.sent.length, 1); assert.deepEqual(JSON.parse(s.sent[0].data), draft);
  assert.equal(s.sent[0].header['Content-Type'], 'application/json'); ok(s.sent[0]); assert.deepEqual(await a, row); assert.equal(s.client.pendingRequestId(), null);
});
test('network uncertainty preserves exact original payload and explicit retry', async () => {
  const s = setup(); const a = s.client.create(draft); s.sent[0].fail(); await assert.rejects(a);
  await assert.rejects(s.client.create({ ...draft, target: 3 }), /上一个目标/);
  const b = s.client.retry(); assert.equal(s.sent[1].data, s.sent[0].data); ok(s.sent[1]); await b;
});
test('first deterministic rejection releases pending request so correction is possible', async () => {
  for (const code of [400, 409]) {
    const s = setup(); const a = s.client.create(draft); s.sent[0].success({ code }); await assert.rejects(a);
    assert.equal(s.client.pendingRequestId(), null);
    const changed = { ...draft, requestId: 'corrected-goal-0001', target: 3 }; const b = s.client.create(changed); ok(s.sent[1], { ...row, ...changed }); await b;
  }
});
test('deterministic error after unknown cannot erase a potentially committed original', async () => {
  const s = setup(); const a = s.client.create(draft); s.sent[0].fail(); await assert.rejects(a);
  const b = s.client.retry(); s.sent[1].success({ code: 400 }); await assert.rejects(b); assert.equal(s.client.pendingRequestId(), draft.requestId);
});
test('malformed success and string code preserve pending original', async () => {
  for (const response of [{ code: '200', data: row }, { code: 200, data: { ...row, progress: '2' } }, { code: 200, data: { ...row, requestId: 'wrong-request-001' } }]) {
    const s = setup(); const p = s.client.create(draft); s.sent[0].success(response); await assert.rejects(p); assert.equal(s.client.pendingRequestId(), draft.requestId);
  }
});
test('timeout then late success retains pending until a matching receipt', async () => {
  const s = setup(); const a = s.client.create(draft); await assert.rejects(a, /超时/); ok(s.sent[0]);
  assert.equal(s.client.pendingRequestId(), draft.requestId); const b = s.client.receipt(draft.requestId); ok(s.sent[1]); await b; assert.equal(s.client.pendingRequestId(), null);
});
test('404 receipt is not proof of no commit; retain original', async () => {
  const s = setup(); const a = s.client.create(draft); s.sent[0].fail(); await assert.rejects(a);
  const b = s.client.receipt(draft.requestId); s.sent[1].success({ code: 404 }); await assert.rejects(b); assert.equal(s.client.pendingRequestId(), draft.requestId);
});
test('receipt with original key but changed body cannot resolve unknown', async () => {
  const s = setup(); const a = s.client.create(draft); s.sent[0].fail(); await assert.rejects(a);
  const b = s.client.receipt(draft.requestId); ok(s.sent[1], { ...row, target: 3 }); await assert.rejects(b, /异常/); assert.equal(s.client.pendingRequestId(), draft.requestId);
});
test('old account and same-member new login callbacks cannot expose goal data', async () => {
  for (const owner of ['8:login-1', '7:login-2', '']) {
    const s = setup(); const p = s.client.detail(row.id); s.identity(owner); ok(s.sent[0]); await assert.rejects(p, /上下文/);
  }
});
test('old create completion cannot clear new actor pending request', async () => {
  const s = setup(); const a = s.client.create(draft); s.identity('8:login-1'); const newer = { ...draft, requestId: 'new-owner-goal-001' };
  const b = s.client.create(newer); ok(s.sent[0]); await assert.rejects(a); assert.equal(s.client.pendingRequestId(), newer.requestId);
  ok(s.sent[1], { ...row, ...newer }); await b;
});
test('latest read wins and cancellation invalidates an in-flight old read', async () => {
  const s = setup(); const a = s.client.detail(row.id), b = s.client.detail(row.id); ok(s.sent[0]); await assert.rejects(a); ok(s.sent[1]); await b;
  const c = s.client.detail(row.id), d = s.client.cancel(row.id); ok(s.sent[2]); await assert.rejects(c); ok(s.sent[3], { ...row, state: 'CANCELLED' }); await d;
});
test('cancel singleflight captures exact original string ID and requires cancelled receipt', async () => {
  const s = setup(); const a = s.client.cancel(row.id), b = s.client.cancel(row.id); assert.equal(a, b); assert.deepEqual(s.sent[0].data, { id: row.id });
  ok(s.sent[0]); await assert.rejects(a, /取消/); const c = s.client.cancel(row.id); ok(s.sent[1], { ...row, state: 'CANCELLED' }); assert.equal((await c).state, 'CANCELLED');
});
test('cancel transport failure permits idempotent retry for the same goal', async () => {
  const s = setup(); const a = s.client.cancel(row.id); s.sent[0].fail(); await assert.rejects(a);
  const b = s.client.cancel(row.id); ok(s.sent[1], { ...row, state: 'CANCELLED' }); await b; assert.deepEqual(s.sent[1].data, s.sent[0].data);
});
test('strict response projection strips route titles, IDs, images and owner metadata', async () => {
  const s = setup(); const p = s.client.detail(row.id); ok(s.sent[0], { ...row, title: 'private', routeIds: [1], photoUrl: 'secret', memberId: 7 }); assert.deepEqual(await p, row);
});
test('unsafe numeric IDs and invalid local parameters never send', async () => {
  const s = setup(); await assert.rejects(s.client.detail(9007199254740999)); await assert.rejects(s.client.cancel('0'));
  await assert.rejects(s.client.create({ ...draft, target: 0 })); await assert.rejects(s.client.create({ ...draft, startDate: '2026-02-30' }));
  await assert.rejects(s.client.list(null, 51)); assert.equal(s.sent.length, 0);
});
test('list validates bounded count, duplicate IDs and exact string next cursor', async () => {
  const s = setup(); const p = s.client.list(null, 1); ok(s.sent[0], { items: [row], hasMore: true, nextBeforeId: row.id }); assert.equal((await p).nextBeforeId, row.id);
  for (const value of [{ items: [row, row], hasMore: false, nextBeforeId: null }, { items: [row], hasMore: true, nextBeforeId: '8' }, { items: [], hasMore: true, nextBeforeId: '8' }]) {
    const q = s.client.list(); ok(s.sent[s.sent.length - 1], value); await assert.rejects(q);
  }
});
test('empty authoritative list stays empty and does not fabricate progress', async () => {
  const s = setup(); const p = s.client.list(); ok(s.sent[0], { items: [], hasMore: false, nextBeforeId: null }); assert.deepEqual(await p, { items: [], hasMore: false, nextBeforeId: null });
});
test('achievement must agree with server metric count and private commemoration', async () => {
  for (const value of [{ ...row, state: 'ACHIEVED', privateCommemoration: true }, { ...row, state: 'CANCELLED', privateCommemoration: true }, { ...row, countingVersion: 'UNKNOWN' }]) {
    const s = setup(); const p = s.client.detail(row.id); ok(s.sent[0], value); await assert.rejects(p);
  }
});
test('dispose rejects late callbacks and clears private memory', async () => {
  const s = setup(); const p = s.client.create(draft); s.client.dispose(); ok(s.sent[0]); await assert.rejects(p); assert.throws(() => s.client.pendingRequestId(), /销毁/);
});
test('synchronous transport failure is recoverable and never silently creates another request', async () => {
  const s = setup({ sendRequest() { throw new Error('transport'); } }); await assert.rejects(s.client.create(draft), /transport/); assert.equal(s.client.pendingRequestId(), draft.requestId);
});

test('confirmed cancellation dominates a late create receipt while clearing committed original intent', async () => {
  const s = setup(); const created = s.client.create(draft), cancel = s.client.cancel(row.id);
  ok(s.sent[1], { ...row, state: 'CANCELLED' }); await cancel;
  ok(s.sent[0]); assert.equal((await created).state, 'CANCELLED'); assert.equal(s.client.pendingRequestId(), null);
});
test('cancel confirmation invalidates a read begun after cancel started and masks late original receipt', async () => {
  const s = setup(); const cancel = s.client.cancel(row.id), detail = s.client.detail(row.id), receipt = s.client.receipt(draft.requestId);
  ok(s.sent[0], { ...row, state: 'CANCELLED' }); await cancel;
  ok(s.sent[1]); await assert.rejects(detail, /上下文/);
  ok(s.sent[2]); assert.equal((await receipt).state, 'CANCELLED');
});
test('disposed clients cannot send fresh operations or revive private memory', async () => {
  const s = setup(); s.client.dispose();
  for (const action of [() => s.client.create(draft), () => s.client.detail(row.id), () => s.client.list(), () => s.client.cancel(row.id), () => s.client.receipt(draft.requestId), () => s.client.retry()]) await assert.rejects(action(), /销毁/);
  assert.equal(s.sent.length, 0);
});

test('identity change between callback and promise projection fences private goal', async () => {
  const s = setup(); const p = s.client.detail(row.id); ok(s.sent[0]); s.identity('8:login-2'); await assert.rejects(p, /上下文/);
});
