const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { createRequire } = require('node:module');
const view = require('../../utils/orient-score-view.js');
const { createVerificationWorkflow } = require('../../utils/verification-readback.js');
const pagePath = path.resolve(__dirname, '../../subpackageMember/orient-score/index.js');
const requirePage = createRequire(pagePath);
const SID = '9223372036854775806', OWNER = '9007199254740993', ENTRY = '9223372036854775805';
const clone = data => JSON.parse(JSON.stringify(data));
function breakdown(extra = {}) {
  return Object.assign({ sessionId: SID, activityId: '11', topicId: '12', status: 1, totalCenti: '125',
    byDimension: { TASK: '100', PUZZLE: '50', BONUS: '0', PENALTY: '-25', ADJUSTMENT: '0' },
    timeline: [{ entryId: ENTRY, deltaCenti: '-25', runningCenti: '125', dimension: 'PENALTY', nodeId: '31', reversesEntryId: null, reasonCode: 'TEST', at: '2026-10-26' }],
    settlement: null, appeals: [], disputes: [], disputeWindowDeadline: '2026-10-30 12:00:00' }, extra);
}
function settlement(extra = {}) {
  return Object.assign({ state: 'SETTLED', sessionId: SID, settlementId: ENTRY, totalCenti: '125', pointsAwarded: 2,
    xpAwarded: 3, ledgerWatermarkId: ENTRY, idempotencyKey: 'system:original', settledAt: '2026-10-26' }, extra);
}
function session(sessionId = SID, extra = {}) {
  return Object.assign({ sessionId, topicId: '12', activityId: '11', phase: 'FINISHED', status: 1,
    configVersion: 1, startedAt: 1792972800000, elapsedMs: 123000, serverNow: 1792972923000 }, extra);
}
function harness(storage = new Map(), options = { sessionId: SID }) {
  let definition, owner = OWNER, auth = 'test-auth';
  const calls = [], modals = [], timers = new Map();
  let timerId = 0;
  const wx = { getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, clone(value)), removeStorageSync: key => storage.delete(key),
    stopPullDownRefresh() {}, navigateBack() {} };
  const app = { getUserID: () => owner, getAuthorization: () => auth,
    sendRequest(request) { calls.push(request); return { abort() { request.aborted = true; } }; } };
  vm.runInNewContext(fs.readFileSync(pagePath, 'utf8'), { getApp: () => app, wx,
    Page: value => { definition = value; }, require: name => {
      if (name.endsWith('/modal.js')) return { show: args => { modals.push(args); } };
      if (name.endsWith('/verification-readback.js')) return { createVerificationWorkflow: config => createVerificationWorkflow(Object.assign(config,
        { setTimer: callback => { const id = ++timerId; timers.set(id, callback); return id; }, clearTimer: id => timers.delete(id) })) };
      return requirePage(name);
    }
  });
  const page = Object.assign({}, definition, { data: clone(definition.data), setData(patch) { Object.assign(this.data, clone(patch)); } });
  page.onLoad(options);
  const respond = (request, data) => request.success({ code: 200, data });
  const read = (data = breakdown()) => { page.onShow(); respond(calls.at(-1), data); };
  const confirm = () => modals.at(-1).success({ confirm: true });
  const writes = () => calls.filter(call => call.method === 'POST');
  const receipt = () => calls.filter(call => call.url.endsWith('/receipt')).at(-1);
  const record = (request, extra = {}) => Object.assign({ id: '74', sessionId: SID, memberId: owner,
    status: 0, reason: request.data.reason, entryId: request.data.entryId || null, nodeId: request.data.nodeId || null,
    idempotencyKey: request.data.idempotencyKey }, extra);
  return { page, wx, calls, modals, storage, respond, read, confirm, writes, receipt, record,
    switchOwner(value, token = 'other-auth') { owner = value; auth = token; },
    timeout() { const callback = timers.values().next().value; assert.equal(typeof callback, 'function'); callback(); } };
}
test('initial read is GET only; dimensions and IDs preserve full Long precision', () => {
  const h = harness(); h.read(breakdown({ totalCenti: '9223372036854775807' }));
  assert.equal(h.page.data.state, 'ready');
  assert.equal(h.page.data.score.sessionId, SID);
  assert.equal(h.page.data.score.total, '92233720368547758.07 分');
  assert.equal(h.page.data.score.dimensions.find(row => row.key === 'PENALTY').value, '-0.25 分');
  assert.equal(h.page.data.score.timeline[0].entryId, ENTRY);
  assert.equal(h.writes().length, 0);
  assert.equal(h.calls[0].url, '/api/orient/scoring/session/' + SID);
  assert.equal(h.page.data.score.settlement, null);
});
test('invalid scope, logged out, feature off, missing session, and permission use unified states', () => {
  for (const options of [{}, { sessionId: 9007199254740992 }, { sessionId: '9223372036854775808' }, { sessionId: SID, topicId: '-1' }]) {
    const h = harness(new Map(), options); h.page.onShow();
    assert.equal(h.page.data.state, 'missing-param'); assert.equal(h.calls.length, 0);
  }
  const guest = harness(); guest.switchOwner('', ''); guest.page.onShow();
  assert.equal(guest.page.data.state, 'no-permission'); assert.equal(guest.calls.length, 0);
  for (const [body, expected] of [[{ errorCode: 'ORIENT_SCORING_DISABLED' }, 'not-started'], [{ errorCode: 'ORIENT_SESSION_NOT_FOUND' }, 'empty'], [{ code: 403 }, 'no-permission']]) {
    const h = harness(); h.page.onShow(); h.calls[0].success(Object.assign({ code: 500 }, body));
    assert.equal(h.page.data.state, expected); h.page.settle(); assert.equal(h.writes().length, 0);
  }
});
test('wrong session/scope and already imprecise JSON cannot become an actionable view', () => {
  for (const data of [breakdown({ sessionId: '5' }), breakdown({ totalCenti: 9007199254740992 }), breakdown({ timeline: [{ entryId: 9007199254740992, deltaCenti: 1, runningCenti: 1 }] })]) {
    const h = harness(); h.read(data); assert.notEqual(h.page.data.state, 'ready');
    h.page.sync(); assert.equal(h.writes().length, 0);
  }
  const h = harness(new Map(), { sessionId: SID, activityId: '999' }); h.read(); assert.notEqual(h.page.data.state, 'ready');
});
test('sync requires explicit confirmation and uses the real form endpoint; running sessions cannot settle', () => {
  const h = harness(); h.read(breakdown({ status: 0, disputeWindowDeadline: null }));
  h.page.settle(); h.page.openDispute(); assert.equal(h.modals.length, 0); assert.equal(h.page.data.form, '');
  h.page.sync(); h.modals[0].success({ confirm: false }); assert.equal(h.writes().length, 0);
  h.page.sync(); h.confirm(); const request = h.writes()[0];
  assert.equal(request.url, '/api/orient/scoring/session/' + SID + '/sync');
  assert.equal(request.header['content-type'], 'application/x-www-form-urlencoded');
  assert.equal(request.data.sessionId, SID); assert.ok(request.data.idempotencyKey);
  assert.equal(h.storage.size, 1);
  h.respond(request, { sessionId: SID, added: 1, totalCenti: '125' });
  assert.equal(h.storage.size, 0); assert.equal(h.page.data.busy, false); assert.equal(h.calls.at(-1).method, 'GET');
});
test('settle uses original durable identity and consumes only a real same-session settlement', () => {
  const h = harness(); h.read(); h.page.settle(); h.page.settle(); assert.equal(h.modals.length, 1);
  h.confirm(); const request = h.writes()[0];
  h.page.settle(); h.page.sync(); assert.equal(h.writes().length, 1);
  assert.equal(request.url, '/api/orient/scoring/session/' + SID + '/settle');
  assert.ok([...h.storage.values()].some(row => row.attemptId === request.data.idempotencyKey));
  h.respond(request, settlement());
  h.respond(h.calls.at(-1), breakdown({ settlement: settlement() }));
  assert.equal(h.page.data.score.settlement.points, '2'); assert.equal(h.page.data.score.settlement.xp, '3');
  assert.equal(h.page.data.score.canSettle, false); assert.equal(h.page.data.score.canSync, false);
  h.page.settle(); assert.equal(h.writes().length, 1);
});
test('appeal selects exact entry or valid missing-task node, validates reason, and stays pending', () => {
  const h = harness(); h.read(); h.page.openAppeal({ currentTarget: { dataset: { entryId: ENTRY } } });
  h.page.submitReview(); assert.equal(h.modals.length, 0);
  h.page.onReasonInput({ detail: { value: '请核对处罚' } }); h.page.submitReview(); h.confirm();
  const request = h.writes()[0]; assert.equal(request.url, '/api/orient/scoring/appeal');
  assert.equal(request.data.entryId, ENTRY); assert.equal(request.data.reason, '请核对处罚'); assert.equal(request.data.nodeId, undefined);
  const record = h.record(request); h.respond(request, record); h.respond(h.calls.at(-1), breakdown({ appeals: [record] }));
  assert.equal(h.page.data.score.appeals[0].statusText, '待处理'); assert.equal(h.page.data.score.timeline[0].appealPending, true);
  h.page.openAppeal({ currentTarget: { dataset: { entryId: ENTRY } } }); assert.equal(h.page.data.form, '');
  h.page.openAppeal(); h.page.onReasonInput({ detail: { value: '节点漏记' } });
  h.page.onNodeInput({ detail: { value: '9223372036854775808' } }); h.page.submitReview(); assert.equal(h.modals.length, 1);
  h.page.onNodeInput({ detail: { value: ENTRY } }); h.page.submitReview(); h.confirm();
  assert.equal(h.writes()[1].data.nodeId, ENTRY); assert.equal(h.writes()[1].data.entryId, undefined);
  h.respond(h.writes()[1], h.record(h.writes()[1]));
});
test('dispute uses controller fields and accepted/rejected status never claims rewards', () => {
  const h = harness(); h.read(); h.page.openDispute(); h.page.onReasonInput({ detail: { value: '成绩有误' } });
  h.page.submitReview(); h.confirm(); const request = h.writes()[0];
  assert.equal(request.url, '/api/orient/scoring/dispute');
  assert.deepEqual(Object.keys(request.data).sort(), ['idempotencyKey', 'reason', 'sessionId']);
  h.respond(request, h.record(request));
  h.respond(h.calls.at(-1), breakdown({ disputes: [h.record(request)] }));
  assert.equal(h.page.data.score.canDispute, false); assert.equal(h.page.data.score.disputes[0].statusText, '待处理');
  assert.equal(h.page.data.score.settlement, null);
});
test('timeout and NOT_FOUND keep the original request frozen across lifecycle reconstruction', () => {
  const h = harness(); h.read(); h.page.openDispute(); h.page.onReasonInput({ detail: { value: '复核成绩' } }); h.page.submitReview(); h.confirm();
  const request = h.writes()[0], original = request.data.idempotencyKey;
  h.timeout(); assert.equal(h.page.data.pending.requestId, original); assert.ok(request.aborted);
  assert.equal(h.receipt().data.idempotencyKey, original); assert.equal(h.receipt().data.kind, 'DISPUTE');
  h.respond(h.receipt(), { state: 'NOT_FOUND', idempotencyKey: original, kind: 'DISPUTE' });
  h.page.onReasonInput({ detail: { value: '改过理由' } }); assert.equal(h.page.data.reason, '复核成绩');
  h.page.submitReview(); h.page.settle(); assert.equal(h.writes().length, 1);
  h.page.onUnload();
  const rebuilt = harness(h.storage); rebuilt.read(); assert.equal(rebuilt.writes().length, 0);
  assert.equal(rebuilt.page.data.pending.requestId, original);
  const body = { state: 'PENDING', kind: 'DISPUTE', idempotencyKey: original, record: h.record(request) };
  rebuilt.respond(rebuilt.receipt(), Object.assign({}, body, { record: h.record(request, { memberId: '9' }) }));
  assert.equal(rebuilt.page.data.pending.requestId, original);
  rebuilt.page.recover(); rebuilt.respond(rebuilt.receipt(), body);
  assert.equal(rebuilt.storage.size, 0); assert.equal(rebuilt.page.data.pending, null); assert.equal(rebuilt.writes().length, 0);
});
test('unknown settlement accepts only positive same-session settlement; generic success is unknown', () => {
  const h = harness(); h.read(); h.page.settle(); h.confirm(); const request = h.writes()[0];
  h.respond(request, {}); assert.ok(h.page.data.pending);
  h.respond(h.receipt(), settlement({ sessionId: '8' })); assert.ok(h.page.data.pending);
  h.page.recover(); h.respond(h.receipt(), settlement()); assert.equal(h.page.data.pending, null); assert.equal(h.storage.size, 0);
});
test('sync unknown never retries or guesses success from a refreshed total', () => {
  const h = harness(); h.read(); h.page.sync(); h.confirm(); h.writes()[0].fail({ errMsg: 'network' });
  assert.equal(h.page.data.pending.kind, 'SYNC');
  assert.equal(h.receipt().data.idempotencyKey, h.writes()[0].data.idempotencyKey);
  h.respond(h.receipt(), { state: 'NOT_FOUND' });
  h.page.load(); h.respond(h.calls.at(-1), breakdown({ totalCenti: '9999' }));
  assert.equal(h.page.data.pending.kind, 'SYNC'); h.page.sync(); assert.equal(h.writes().length, 1);
});
test('explicit business rejection releases original record; opaque server failure does not', () => {
  for (const [response, frozen] of [[{ code: 500, errorCode: 'ORIENT_WINDOW_CLOSED' }, false], [{ code: 500, msg: '内部失败' }, true]]) {
    const h = harness(); h.read(); h.page.settle(); h.confirm(); h.writes()[0].success(response);
    assert.equal(!!h.page.data.pending, frozen); assert.equal(h.storage.size, frozen ? 1 : 0);
  }
});
test('read failures wipe sensitive snapshots and late reads cannot overwrite the newer session', () => {
  const h = harness(); h.page.onShow(); const old = h.calls.at(-1);
  h.page.retry(); const current = h.calls.at(-1); h.respond(current, breakdown({ totalCenti: '200' }));
  h.respond(old, breakdown({ totalCenti: '100' })); assert.equal(h.page.data.score.total, '2.00 分');
  h.page.retry(); h.calls.at(-1).successStatusAbnormal({ code: 403 }, 403);
  assert.equal(h.page.data.state, 'no-permission'); assert.equal(h.page.data.score, null);
});
test('account switch, token replacement, hide and delayed modal/write replies are isolated', () => {
  const h = harness(); h.read(); h.page.settle(); h.switchOwner('77'); h.confirm(); assert.equal(h.writes().length, 0);
  h.page.onShow(); h.respond(h.calls.at(-1), breakdown()); h.page.settle(); h.confirm(); const bWrite = h.writes()[0];
  h.page.onHide(); h.respond(bWrite, settlement()); assert.equal(h.page.data.score, null); assert.equal(h.storage.size, 1);
  h.switchOwner(OWNER); h.page.onShow(); h.respond(h.calls.at(-1), breakdown()); assert.equal(h.page.data.pending, null);
  assert.equal(h.writes().length, 1);
  h.page.settle(); h.confirm(); const aWrite = h.writes()[1];
  h.switchOwner(OWNER, 'rotated-token'); h.respond(aWrite, settlement()); assert.equal(h.page.data.busy, true);
  h.page.onHide(); h.page.onShow(); h.respond(h.calls.at(-1), breakdown());
  assert.equal(h.page.data.pending.requestId, aWrite.data.idempotencyKey);
});
test('unwritable or unreadable storage prevents POST; stale timeout success cannot unlock unknown', () => {
  const h = harness(); h.read(); h.wx.setStorageSync = () => { throw Error('full'); };
  h.page.settle(); h.confirm(); assert.equal(h.writes().length, 0);
  const unreadable = harness(); unreadable.read(); unreadable.wx.getStorageSync = () => { throw Error('unreadable'); };
  unreadable.page.settle(); unreadable.confirm(); assert.equal(unreadable.writes().length, 0);
  assert.equal(unreadable.page.data.pending.kind, 'STORAGE');
  const t = harness(); t.read(); t.page.settle(); t.confirm(); const request = t.writes()[0]; t.timeout();
  t.respond(request, settlement()); assert.ok(t.page.data.pending); assert.equal(t.storage.size, 1);
});
test('decimal formatter and receipt matching preserve negative centi, exact IDs and key ownership', () => {
  assert.equal(view.centi('-9223372036854775808', true), '-92233720368547758.08 分');
  assert.equal(view.centi('1', true), '+0.01 分'); assert.equal(view.id('9223372036854775808'), '');
  const pending = { kind: 'APPEAL', requestId: 'same-key' };
  const row = { id: '1', sessionId: SID, memberId: OWNER, idempotencyKey: 'same-key', status: 0, reason: '原理由', nodeId: '3', entryId: null };
  assert.equal(view.receiptMatches(row, pending, OWNER, SID, { reason: '另一个理由', nodeId: '3' }), false);
  assert.equal(view.receiptMatches(row, pending, OWNER, SID, { reason: '原理由', nodeId: '3' }), true);
  assert.equal(view.receiptMatches({ state: 'NOT_FOUND', kind: 'APPEAL', idempotencyKey: 'same-key' }, pending, OWNER, SID), false);
});
test('topic plus activity resolves only existing own sessions through the exact read contract', () => {
  const h = harness(new Map(), { topicId: '12', activityId: '11' }); h.page.onShow();
  assert.equal(h.calls[0].url, '/api/play/orient/session/current'); assert.equal(h.calls[0].method, 'GET');
  assert.deepEqual(clone(h.calls[0].data), { topicId: '12', activityId: '11' });
  h.respond(h.calls[0], { sessions: [session()] }); assert.equal(h.calls[1].url, '/api/orient/scoring/session/' + SID);
  h.respond(h.calls[1], breakdown()); assert.equal(h.page.data.state, 'ready'); assert.equal(h.writes().length, 0);
});
test('multiple sessions require a real user choice, including finished and disqualified sessions', () => {
  const h = harness(new Map(), { topicId: '12' }); h.page.onShow();
  assert.deepEqual(clone(h.calls[0].data), { topicId: '12' });
  h.respond(h.calls[0], { sessions: [session(), session('6', { phase: 'DISQUALIFIED', status: 3 })] });
  assert.equal(h.page.data.state, 'choosing'); assert.equal(h.calls.length, 1); assert.equal(h.page.data.sessions[1].phase, '已取消资格');
  h.page.chooseSession({ currentTarget: { dataset: { sessionId: '99' } } }); assert.equal(h.calls.length, 1);
  h.page.chooseSession({ currentTarget: { dataset: { sessionId: '6' } } });
  h.respond(h.calls.at(-1), breakdown({ sessionId: '6', status: 3 }));
  assert.equal(h.page.data.score.sessionId, '6'); assert.equal(h.page.data.score.canSettle, false);
  assert.equal(h.page.data.score.canSync, false); h.page.chooseAnother();
  assert.equal(h.calls.at(-1).url, '/api/play/orient/session/current'); assert.equal(h.writes().length, 0);
});
test('empty, mismatched, duplicate, unknown phase and feature-off session lists do not select or write', () => {
  for (const [data, expected] of [[{ sessions: [] }, 'empty'], [{ sessions: [session(SID, { topicId: '13' })] }, 'offline'],
    [{ sessions: [session(), session()] }, 'offline'], [{ sessions: [session(SID, { phase: 'MADE_UP' })] }, 'offline']]) {
    const h = harness(new Map(), { topicId: '12', activityId: '11' }); h.page.onShow(); h.respond(h.calls[0], data);
    assert.equal(h.page.data.state, expected); assert.equal(h.calls.length, 1); assert.equal(h.writes().length, 0);
  }
  const off = harness(new Map(), { topicId: '12' }); off.page.onShow();
  off.calls[0].success({ code: 404, err: 'ORIENT_SESSION_DISABLED' }); assert.equal(off.page.data.state, 'not-started');
  const missing = harness(new Map(), { activityId: '11' }); missing.page.onShow();
  assert.equal(missing.page.data.state, 'missing-param'); assert.equal(missing.calls.length, 0);
});
test('self-play scope preserves activityId zero and rejects an activity session masquerading as self-play', () => {
  const h = harness(new Map(), { topicId: '12', activityId: '0' }); h.page.onShow();
  assert.equal(h.calls[0].data.activityId, '0');
  h.respond(h.calls[0], { sessions: [session(SID, { activityId: '0' })] });
  h.respond(h.calls.at(-1), breakdown({ activityId: 0 })); assert.equal(h.page.data.state, 'ready');
  const wrong = harness(new Map(), { topicId: '12', activityId: '0' }); wrong.page.onShow();
  wrong.respond(wrong.calls[0], { sessions: [session()] }); assert.notEqual(wrong.page.data.state, 'ready');
});
test('switching chosen sessions keeps unknown request isolated and ignores late previous-session replies', () => {
  const h = harness(new Map(), { topicId: '12' });
  const list = () => h.respond(h.calls.at(-1), { sessions: [session(), session('6')] });
  const choose = value => h.page.chooseSession({ currentTarget: { dataset: { sessionId: value } } });
  h.page.onShow(); list(); choose(SID); h.respond(h.calls.at(-1), breakdown()); h.page.settle(); h.confirm();
  const write = h.writes()[0]; write.fail({ errMsg: 'timeout' }); const oldReceipt = h.receipt();
  h.page.chooseAnother(); list(); choose('6'); h.respond(h.calls.at(-1), breakdown({ sessionId: '6' }));
  assert.equal(h.page.data.pending, null); assert.equal(h.storage.size, 1);
  h.respond(oldReceipt, settlement()); assert.equal(h.page.data.score.sessionId, '6'); assert.equal(h.storage.size, 1);
  h.page.chooseAnother(); list(); choose(SID); h.respond(h.calls.at(-1), breakdown());
  assert.equal(h.page.data.pending.requestId, write.data.idempotencyKey); assert.equal(h.writes().length, 1);
});
test('account change during active write clears visible state and loads the new identity without waiting', () => {
  const h = harness(); h.read(); h.page.settle(); h.confirm(); const oldWrite = h.writes()[0];
  h.switchOwner('77'); h.page.onShow(); assert.equal(h.page.data.state, 'loading');
  assert.equal(h.page.data.score, null); assert.equal(h.page.data.busy, false);
  h.respond(h.calls.at(-1), breakdown()); assert.equal(h.page.data.pending, null);
  h.respond(oldWrite, settlement()); assert.equal(h.page.data.score.settlement, null); assert.equal(h.storage.size, 1);
});
test('appeal unknown recovery rejects a mismatched key and reads only the original own appeal receipt', () => {
  const h = harness(); h.read(); h.page.openAppeal({ currentTarget: { dataset: { entryId: ENTRY } } });
  h.page.onReasonInput({ detail: { value: '请复核' } }); h.page.submitReview(); h.confirm();
  const write = h.writes()[0]; write.successStatusAbnormal({}, 504);
  assert.equal(h.receipt().data.kind, 'APPEAL'); assert.equal(h.receipt().data.sessionId, SID);
  const receipt = { state: 'PENDING', kind: 'APPEAL', idempotencyKey: write.data.idempotencyKey, record: h.record(write) };
  h.respond(h.receipt(), Object.assign({}, receipt, { record: h.record(write, { idempotencyKey: 'foreign-key' }) }));
  assert.ok(h.page.data.pending); h.page.recover(); h.respond(h.receipt(), receipt);
  assert.equal(h.page.data.pending, null); assert.equal(h.storage.size, 0); assert.equal(h.writes().length, 1);
});
test('permission failures from writes and receipt readback clear the view without discarding unknown records', () => {
  for (const channel of ['fail', 'successStatusAbnormal']) {
    const h = harness(); h.read(); h.page.settle(); h.confirm(); h.writes()[0][channel]({}, 403);
    assert.equal(h.page.data.state, 'no-permission'); assert.equal(h.page.data.score, null); assert.equal(h.storage.size, 0);
    const r = harness(); r.read(); r.page.settle(); r.confirm(); r.writes()[0].fail({ errMsg: 'timeout' });
    r.receipt()[channel]({}, 403); assert.equal(r.page.data.state, 'no-permission'); assert.equal(r.page.data.score, null);
    assert.equal(r.storage.size, 1);
  }
});
test('page component declarations and WXML actions resolve to real files and executable Page handlers', () => {
  const h = harness(), xml = fs.readFileSync(pagePath.replace(/\.js$/, '.wxml'), 'utf8');
  const config = JSON.parse(fs.readFileSync(pagePath.replace(/\.js$/, '.json'), 'utf8'));
  for (const match of xml.matchAll(/<(cy-[a-z-]+)\b/g)) {
    assert.ok(config.usingComponents[match[1]], match[1]);
    assert.ok(fs.existsSync(path.resolve(__dirname, '../..', '.' + config.usingComponents[match[1]] + '.json')));
  }
  for (const match of xml.matchAll(/\b(?:bind|catch)(?::[\w]+|[\w]+)="(\w+)"/g)) assert.equal(typeof h.page[match[1]], 'function', match[1]);
});

test('score recovery uses player-facing copy while keeping uncertainty explicit', () => {
  const js = fs.readFileSync(pagePath, 'utf8');
  const xml = fs.readFileSync(pagePath.replace(/\.js$/, '.wxml'), 'utf8');
  assert.doesNotMatch(js + xml, /回执|服务端/);
  const h = harness(); h.read(); h.page.sync(); h.confirm(); h.writes()[0].fail({ errMsg: 'network' });
  assert.equal(h.page.data.pending.kind, 'SYNC');
  h.respond(h.receipt(), { state: 'NOT_FOUND' });
  assert.match(h.page.data.notice, /暂未查到记录不代表提交失败.*请勿重复操作/);
  h.page.sync(); assert.equal(h.writes().length, 1);
});

 test('SYNC receipt recovers the original request after reentry and rejects unrelated positive receipts', () => {
  const h = harness(); h.read(); h.page.sync(); h.confirm();
  const original = h.writes()[0].data.idempotencyKey; h.timeout(); h.page.onUnload();
  const next = harness(h.storage); next.read();
  assert.equal(next.receipt().data.idempotencyKey, original);
  const receipt = { state: 'SYNCED', kind: 'SYNC', sessionId: SID, memberId: OWNER,
    idempotencyKey: original, added: 0, totalCenti: '-25' };
  next.respond(next.receipt(), { ...receipt, idempotencyKey: 'other' });
  assert.ok(next.page.data.pending);
  next.page.recover(); next.respond(next.receipt(), receipt);
  assert.equal(next.page.data.pending, null); assert.equal(next.storage.size, 0);
  assert.equal(next.writes().length, 0); assert.equal(next.calls.at(-1).url, '/api/orient/scoring/session/' + SID);
 });
 test('pending photo review disables settlement but retains ledger and review access', () => {
  const h = harness(); h.read(breakdown({ pendingReviewCount: 1 }));
  assert.equal(h.page.data.score.canSettle, false); h.page.settle();
  assert.equal(h.modals.length, 0); assert.equal(h.page.data.score.canAppeal, true);
 });

test('explicit resume sends frozen original SYNC identity, never unlocks on rejection, and can recover legacy pending', () => {
  const h = harness(); h.read(); h.page.sync(); h.confirm(); const first = h.writes()[0]; h.timeout();
  h.respond(h.receipt(), { state: 'NOT_FOUND' });
  h.page.resumeSync(); h.confirm(); const resumed = h.writes()[1];
  assert.deepEqual(clone(resumed.data), clone(first.data));
  resumed.success({ code: 500, errorCode: 'ORIENT_INVALID_ARGUMENT' });
  assert.equal(h.page.data.pending.requestId, first.data.idempotencyKey); assert.equal(h.storage.size, 1);
  h.page.resumeSync(); h.confirm(); const successful = h.writes()[2];
  h.respond(successful, { state: 'SYNCED', kind: 'SYNC', sessionId: SID, memberId: OWNER,
    idempotencyKey: first.data.idempotencyKey, added: 0, totalCenti: '125' });
  assert.equal(h.storage.size, 0); assert.equal(h.page.data.pending, null);
});
test('account change during resume confirmation cannot send original owners sync', () => {
  const h = harness(); h.read(); h.page.sync(); h.confirm(); h.timeout();
  h.respond(h.receipt(), { state: 'NOT_FOUND' }); h.page.resumeSync();
  h.switchOwner('99'); h.confirm(); assert.equal(h.writes().length, 1); assert.equal(h.storage.size, 1);
});

test('a keyed sync response from another request stays unknown; duplicate resume confirmations send once', () => {
  const h = harness(); h.read(); h.page.sync(); h.confirm(); const original = h.writes()[0];
  h.respond(original, { state: 'SYNCED', kind: 'SYNC', sessionId: SID, memberId: OWNER,
    idempotencyKey: 'different', added: 1, totalCenti: '100' });
  assert.equal(h.page.data.pending.requestId, original.data.idempotencyKey);
  h.respond(h.receipt(), { state: 'NOT_FOUND' }); h.page.resumeSync(); h.confirm(); h.confirm();
  assert.equal(h.writes().length, 2);
});

const boardRow = (extra = {}) => ({ rowKey: '1', rank: '1', scoreCenti: '500', elapsedMs: '1000', reachedEnding: true, mine: false, ...extra });
test('full board consumer preserves server tie ranks, own rank outside top 50 and exact scores', () => {
  const h = harness(); h.read(); h.page.loadLeaderboard();
  const request = h.calls.at(-1); assert.equal(request.method, 'GET'); assert.equal(request.url, '/api/orient/scoring/session/' + SID + '/leaderboard');
  h.respond(request, { sessionId: SID, mode: 'SCORE', list: [boardRow(), boardRow({ rowKey: '2' }), boardRow({ rowKey: '3', rank: '3', scoreCenti: '-25' })], me: boardRow({ rowKey: '55', rank: '54', mine: true }) });
  assert.deepEqual(clone(h.page.data.board.list.map(row => row.rank)), ['1', '1', '3']);
  assert.equal(h.page.data.board.me.rank, '54'); assert.equal(h.page.data.board.list[2].score, '-0.25 分');
  assert.equal(h.writes().length, 0);
});
test('board rejects wrong session and malformed rows, and ignores duplicate/stale account results', () => {
  const h = harness(); h.read(); h.page.loadLeaderboard(); const first = h.calls.at(-1);
  h.respond(first, { sessionId: '99', mode: 'SCORE', list: [], me: null }); assert.equal(h.page.data.boardState, 'error');
  h.page.loadLeaderboard(); const second = h.calls.at(-1);
  h.respond(second, { sessionId: SID, mode: 'SCORE', list: [boardRow({ scoreCenti: 9007199254740992 })] }); assert.equal(h.page.data.boardState, 'error');
  h.page.loadLeaderboard(); const third = h.calls.at(-1); h.switchOwner('99'); h.page.load();
  h.respond(third, { sessionId: SID, mode: 'SCORE', list: [boardRow()] }); assert.equal(h.page.data.board, null);
});
function legacyPending(h, kind = 'SYNC') {
  h.read();
  if (kind === 'SYNC') h.page.sync();
  else { h.page.openDispute(); h.page.onReasonInput({ detail: { value: '历史意见' } }); h.page.submitReview(); }
  h.confirm(); h.timeout(); h.page.onUnload();
  for (const value of h.storage.values()) delete value.requestIdentityVersion;
  const reopened = harness(h.storage); reopened.read(); return reopened;
}
test('legacy missing-request case has a real manual intake and independently read review without releasing original', () => {
  const old = harness(), h = legacyPending(old, 'DISPUTE');
  assert.equal(h.page.data.pending.requestId, null);
  h.page.requestManualReview(); h.confirm(); const request = h.writes()[0];
  assert.equal(request.url, '/api/orient/scoring/session/' + SID + '/recovery'); assert.deepEqual(clone(request.data), { kind: 'DISPUTE' });
  const record = { id: '88', sessionId: SID, memberId: OWNER, kind: 'DISPUTE', status: 0, referenceId: null };
  h.respond(request, record); h.respond(h.calls.at(-1), breakdown({ recoveries: [record] }));
  assert.equal(h.page.data.score.recoveries[0].statusText, '人工核对中'); assert.equal(h.storage.size, 1);
  h.page.load(); h.respond(h.calls.at(-1), breakdown({ recoveries: [{ ...record, status: 1, resolutionNote: '已核对账本，仍不能认定原操作结果' }] }));
  assert.equal(h.page.data.score.recoveries[0].statusText, '已记录核对意见');
  assert.equal(h.page.data.pending.requestId, null); assert.equal(h.storage.size, 1);
  h.page.settle(); assert.equal(h.writes().length, 1);
});
test('manual intake timeout keeps legacy frozen and retry retains the same session/kind', () => {
  const h = legacyPending(harness()); h.page.requestManualReview(); h.confirm();
  const request = h.writes()[0]; request.fail({ errMsg: 'network' });
  assert.equal(h.storage.size, 1); assert.equal(h.page.data.pending.requestId, null);
  h.page.requestManualReview(); h.confirm(); assert.deepEqual(clone(h.writes()[1].data), clone(request.data));
  assert.equal(h.writes()[1].url, request.url);
});
test('manual intake confirmation crossing accounts never submits or clears old pending', () => {
  const h = legacyPending(harness()); h.page.requestManualReview(); h.switchOwner('99'); h.confirm();
  assert.equal(h.writes().length, 0); assert.equal(h.storage.size, 1);
});
