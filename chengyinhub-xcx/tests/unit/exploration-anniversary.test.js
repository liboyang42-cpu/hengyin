const test = require('node:test');
const assert = require('node:assert/strict');
const { createExplorationAnniversaryClient } = require('../../subpackageTools/utils/features/exploration-anniversary');
const row = { id: '9007199254740999', topicId: '9007199254740998', title: '当前可见名称', completedAt: '2025-10-12 12:00:00.123', year: 2025, yearsAgo: 1 };
const page = { month: 10, day: 12, referenceYear: 2026, timeZone: 'Asia/Shanghai', asOfMs: Date.parse('2026-10-12T12:00:00Z'),
  coverage: 'RECORDED_OWN_COMPLETED_SESSIONS_WITH_CURRENT_VISIBLE_ROUTE', metadataMode: 'CURRENT_ROUTE_METADATA', leapDayRule: 'EXACT_MONTH_DAY_ONLY', items: [row], hasMore: false, nextCursor: null };
function setup(extra = {}) {
  let owner = '7:login-1'; const sent = [];
  const client = createExplorationAnniversaryClient({ getIdentity: () => owner, sendRequest: request => sent.push(request), timeoutMs: 30, ...extra });
  return { client, sent, owner(value) { owner = value; } };
}
function ok(request, data = page) { request.success({ code: 200, data }); }
test('default load uses server Shanghai day and exact string IDs', async () => {
  const s = setup(), p = s.client.load(); assert.equal(s.sent[0].url, '/api/exploration-anniversary'); assert.equal(s.sent[0].method, 'GET'); assert.deepEqual(s.sent[0].data, { limit: 20 });
  ok(s.sent[0]); assert.deepEqual(await p, page);
});
test('explicit preview allows leap day in non-leap current year without moving it', async () => {
  const s = setup(), p = s.client.load(2, 29); const value = { ...page, month: 2, day: 29, items: [{ ...row, completedAt: '2024-02-29 00:00:00.000', year: 2024, yearsAgo: 2 }] };
  assert.deepEqual(s.sent[0].data, { month: 2, day: 29, limit: 20 }); ok(s.sent[0], value); assert.deepEqual(await p, value);
});
test('calendar fractional string incomplete and out-of-range inputs never send', async () => {
  const s = setup();
  for (const args of [[4, 31], [2, 30], [13, 1], [1, 0], [1.2, 1], ['10', 12], [10], [null, 12], [10, 12, 51], [10, 12, 1.2]]) await assert.rejects(s.client.load(...args));
  assert.equal(s.sent.length, 0);
});
test('strict code and complete schema reject malformed successful responses', async () => {
  for (const response of [{ code: '200', data: page }, { code: 200, data: { ...page, items: {} } }, { code: 200, data: { ...page, timeZone: 'UTC' } }, { code: 200, data: { ...page, metadataMode: 'HISTORICAL_SNAPSHOT' } }, { code: 200, data: { ...page, coverage: 'ALL_HISTORY' } }]) {
    const s = setup(), p = s.client.load(); s.sent[0].success(response); await assert.rejects(p); assert.equal(s.client.snapshot(), null);
  }
});
test('default selector follows Shanghai year boundary rather than UTC day', async () => {
  const s = setup(), p = s.client.load(); const value = { ...page, month: 1, day: 1, referenceYear: 2027, asOfMs: Date.parse('2026-12-31T16:00:00Z'), items: [] };
  ok(s.sent[0], value); assert.equal((await p).referenceYear, 2027);
});
test('wrong preview date or year not matching server time rejects', async () => {
  for (const value of [{ ...page, day: 13 }, { ...page, referenceYear: 2025 }]) {
    const s = setup(), p = s.client.load(10, 12); ok(s.sent[0], value); await assert.rejects(p);
  }
});
test('future same-year invalid or other-month records cannot become memories', async () => {
  for (const item of [{ ...row, year: 2026, completedAt: '2026-10-12 12:00:00.123', yearsAgo: 0 }, { ...row, completedAt: '2025-10-13 12:00:00.123' }, { ...row, completedAt: '2025-02-30 12:00:00.123' }, { ...row, yearsAgo: 2 }]) {
    const s = setup(), p = s.client.load(); ok(s.sent[0], { ...page, items: [item] }); await assert.rejects(p);
  }
});
test('response projection drops legacy image snapshot body and owner metadata', async () => {
  const s = setup(), p = s.client.load(); ok(s.sent[0], { ...page, memberId: '7', items: [{ ...row, imageUrl: 'private', body: 'old', configSnapshot: { secret: true } }] }); assert.deepEqual(await p, page);
});
test('null current title remains unknown instead of invented fallback', async () => {
  const s = setup(), p = s.client.load(); ok(s.sent[0], { ...page, items: [{ ...row, title: null }] }); assert.equal((await p).items[0].title, null);
});
test('pagination pins original day and replaces previous page rather than retaining old authorized titles', async () => {
  const s = setup(), first = s.client.load(10, 12, 1); ok(s.sent[0], { ...page, hasMore: true, nextCursor: 'cursor_1' }); await first;
  const second = s.client.next(); assert.equal(s.client.snapshot(), null); assert.deepEqual(s.sent[1].data, { month: 10, day: 12, limit: 1, cursor: 'cursor_1' });
  const older = { ...row, id: '8', year: 2024, yearsAgo: 2, completedAt: '2024-10-12 01:00:00.000' }; ok(s.sent[1], { ...page, items: [older] }); assert.deepEqual((await second).items, [older]);
});
test('cross-page duplicate and reference-year changes reject', async () => {
  for (const changed of [{ ...page }, { ...page, referenceYear: 2027, asOfMs: Date.parse('2027-10-12T00:00:00Z'), items: [] }]) {
    const s = setup(), first = s.client.load(10, 12, 1); ok(s.sent[0], { ...page, hasMore: true, nextCursor: 'cursor_1' }); await first;
    const second = s.client.next(); ok(s.sent[1], changed); await assert.rejects(second); assert.equal(s.client.snapshot(), null);
  }
});
test('duplicate IDs and incorrect timestamp or exact large-ID ordering reject', async () => {
  for (const items of [[row, row], [{ ...row, id: '9007199254740998' }, row], [{ ...row, completedAt: '2024-10-12 00:00:00.000', year: 2024, yearsAgo: 2 }, { ...row, id: '8' }]]) {
    const s = setup(), p = s.client.load(); ok(s.sent[0], { ...page, items }); await assert.rejects(p);
  }
});
test('numeric overflow IDs and missing current title fail strict projection', async () => {
  for (const item of [{ ...row, id: 9007199254740999 }, { ...row, id: '9223372036854775808' }, { ...row, topicId: '0' }, { ...row, title: undefined }]) {
    const s = setup(), p = s.client.load(); ok(s.sent[0], { ...page, items: [item] }); await assert.rejects(p);
  }
});
test('cursor must be bounded and hasMore must include a full page', async () => {
  for (const value of [{ ...page, hasMore: true, nextCursor: 'x'.repeat(257) }, { ...page, hasMore: true, nextCursor: 'valid_cursor', items: [] }, { ...page, hasMore: false, nextCursor: 'cursor' }]) {
    const s = setup(), p = s.client.load(10, 12, 1); ok(s.sent[0], value); await assert.rejects(p);
  }
});
test('empty authoritative page remains empty without synthetic anniversaries', async () => {
  const s = setup(), p = s.client.load(); ok(s.sent[0], { ...page, items: [] }); assert.deepEqual((await p).items, []); await assert.rejects(s.client.next(), /没有/);
});
test('latest selector wins and stale callback cannot clear newer result', async () => {
  const s = setup(), a = s.client.load(10, 12), b = s.client.load(2, 29);
  ok(s.sent[1], { ...page, month: 2, day: 29, items: [] }); await b; ok(s.sent[0]); await assert.rejects(a); assert.equal(s.client.snapshot().day, 29);
});
test('account change and same-member new login epoch fence old responses', async () => {
  for (const owner of ['8:login-1', '7:login-2', '']) {
    const s = setup(), p = s.client.load(); s.owner(owner); ok(s.sent[0]); await assert.rejects(p, /上下文/);
  }
});
test('identity change between success callback and promise projection cannot disclose old title', async () => {
  const s = setup(), p = s.client.load(); ok(s.sent[0]); s.owner('8:login-1'); await assert.rejects(p, /上下文/); assert.equal(s.client.snapshot(), null);
});
test('timeout late success and synchronous transport failure never restore old page', async () => {
  const s = setup(), p = s.client.load(); await assert.rejects(p, /超时/); ok(s.sent[0]); assert.equal(s.client.snapshot(), null);
  const broken = setup({ sendRequest() { throw new Error('transport'); } }); await assert.rejects(broken.client.load(), /transport/); assert.equal(broken.client.snapshot(), null);
});
test('failed reload clears previous private content and snapshots are detached', async () => {
  const s = setup(), p = s.client.load(); ok(s.sent[0]); const value = await p; value.items[0].title = 'mutated'; assert.equal(s.client.snapshot().items[0].title, row.title);
  const q = s.client.load(); assert.equal(s.client.snapshot(), null); s.sent[1].fail(); await assert.rejects(q); assert.equal(s.client.snapshot(), null);
});
test('dispose permanently prevents callbacks and new requests', async () => {
  const s = setup(), p = s.client.load(); s.client.dispose(); ok(s.sent[0]); await assert.rejects(p); await assert.rejects(s.client.load(), /销毁/); await assert.rejects(s.client.next(), /销毁/); assert.throws(() => s.client.snapshot(), /销毁/); assert.equal(s.sent.length, 1);
});
test('invalid new selector or limit invalidates pending old date and clears visible old page', async () => {
  for (const args of [[2, 30], [10, 12, 51]]) {
    const s = setup(), old = s.client.load(10, 12); await assert.rejects(s.client.load(...args));
    ok(s.sent[0]); await assert.rejects(old, /上下文/); assert.equal(s.client.snapshot(), null);
    const valid = s.client.load(); ok(s.sent[1]); await valid; await assert.rejects(s.client.load(...args)); assert.equal(s.client.snapshot(), null);
  }
});
test('identity getter exceptions at transport completion reject rather than leaving promise pending', async () => {
  let broken = false;
  const s = setup({ getIdentity() { if (broken) throw new Error('identity unavailable'); return '7:login-1'; } });
  const p = s.client.load(); broken = true; ok(s.sent[0]); await assert.rejects(p, /identity/);
});
test('identity getter exception in final projection rejects private response', async () => {
  let broken = false;
  const s = setup({ getIdentity() { if (broken) throw new Error('identity unavailable'); return '7:login-1'; } });
  const p = s.client.load(); ok(s.sent[0]); broken = true; await assert.rejects(p, /identity/);
});
