const test = require('node:test');
const assert = require('node:assert/strict');
const { create } = require('../../pages/play/utils/guard-appeal.js');
const reference = { guardLogId: '9223372036854775807', nodeId: '99', routeSessionId: '101', arrivalRequestId: 'arrive-1' };
function setup(options = {}) {
  const saved = options.saved || new Map(), calls = []; let live = true;
  const storage = { getStorageSync: k => saved.get(k), setStorageSync: (k, v) => saved.set(k, structuredClone(v)) };
  const flow = create({ storage, owner: options.owner || '10', session: 't:77', current: () => live, changed: () => {}, keyFactory: () => 'appeal-1',
    send: async (url, method, body) => {
      calls.push({ url, method, body });
      if (options.send) return options.send(url, method, body);
      return { code: 200, data: { guardOrigin: reference, canSubmit: true } };
    } });
  return { flow, saved, calls, storage, leave: () => { live = false; } };
}
test('UNKNOWN persists exact IDs and original request; NOT_FOUND never resubmits', async () => {
  const h = setup({ send: (url, method) => {
    if (method === 'POST') throw Error('timeout');
    return { code: 200, data: url.endsWith('/reference') ? { guardOrigin: reference, canSubmit: true } : { state: 'NOT_FOUND' } };
  } });
  h.flow.reference(reference); await h.flow.read(); await h.flow.submit('现场情况');
  assert.equal(h.flow.view().state, 'UNKNOWN');
  const original = h.flow.view().command;
  const next = setup({ saved: h.saved, send: () => ({ code: 200, data: { state: 'NOT_FOUND' } }) });
  await next.flow.read(); await next.flow.submit('改理由');
  assert.deepEqual(next.flow.view().command, original);
  assert.equal(next.calls.filter(c => c.method === 'POST').length, 0);
  assert.equal(original.guardLogId, reference.guardLogId);
});
test('positive receipt must match original actor, target, reason and arrival identity', async () => {
  const h = setup(); h.flow.reference(reference); await h.flow.read(); await h.flow.submit('理由');
  const original = h.flow.view().command;
  for (const change of [{ memberId: '11' }, { bizId: '92' }, { reason: 'different' }, { guardReference: { arrivalRequestId: 'other-run' } }]) {
    const next = setup({ saved: h.saved, send: () => ({ code: 200, data: { state: 'FOUND', intent: {
      requestId: original.requestId, memberId: '10', bizId: reference.guardLogId, reason: '理由', evidence: null, attachments: [], guardReference: { guardLogId: reference.guardLogId, arrivalRequestId: 'arrive-1' }, ...change
    } } }) });
    await next.flow.read(); assert.equal(next.flow.view().state, 'UNKNOWN');
  }
});
test('storage failure blocks POST and old-account late read cannot change saved facts', async () => {
  const h = setup(); h.flow.reference(reference); await h.flow.read();
  h.storage.setStorageSync = () => { throw Error('full'); };
  await assert.rejects(h.flow.submit('理由'), /full/); assert.equal(h.calls.filter(c => c.method === 'POST').length, 0);
  let finish;
  const next = setup({ send: () => new Promise(r => { finish = r; }) }); next.flow.reference(reference);
  const reading = next.flow.read(); next.leave(); finish({ code: 200, data: { guardOrigin: reference, canSubmit: true } }); await reading;
  assert.equal(next.flow.view().eligibility, undefined);
  assert.equal(setup({ saved: next.saved, owner: '11' }).flow.view(), null);
});
test('ended original run is queryable but cannot submit', async () => {
  const h = setup({ send: () => ({ code: 200, data: { guardOrigin: reference, canSubmit: false, originalRunActive: false } }) });
  h.flow.reference(reference); await h.flow.read(); await assert.rejects(h.flow.submit('理由'), /资格/);
  assert.equal(h.calls.filter(c => c.method === 'POST').length, 0);
});

test('matched persisted intent exposes accepted result without inventing an arrival completion', async () => {
  let original;
  const h = setup({ send: (url, method, body) => {
    if (method === 'POST') { original = body; return { code: 200 }; }
    if (url.endsWith('/reference')) return { code: 200, data: { guardOrigin: reference, canSubmit: true } };
    return { code: 200, data: { state: 'FOUND', appealState: 'ACCEPTED', canReverify: false, originalRunActive: false,
      intent: { requestId: original.requestId, memberId: '10', bizId: reference.guardLogId, reason: original.reason,
        evidence: null, attachments: [], guardReference: { guardLogId: reference.guardLogId, arrivalRequestId: reference.arrivalRequestId } } } };
  } });
  h.flow.reference(reference); await h.flow.read(); await h.flow.submit('理由');
  assert.equal(h.flow.view().state, 'FOUND'); assert.equal(h.flow.view().result.canReverify, false);
  assert.equal(h.calls.filter(c => c.method === 'POST').length, 1);
});
