const test = require('node:test');
const assert = require('node:assert/strict');
const { createVerificationWorkflow } = require('../../utils/verification-readback.js');

function fixture(values = new Map(), requestIdentity = true) {
  let owner = '7';
  let deadline;
  const storage = {
    getStorageSync: key => values.get(key),
    setStorageSync: (key, value) => values.set(key, JSON.parse(JSON.stringify(value))),
    removeStorageSync: key => values.delete(key),
  };
  const options = { storage, requestIdentity, getOwner: () => owner,
    setTimer: callback => { deadline = callback; return 1; }, clearTimer() {} };
  return { values, storage, workflow: createVerificationWorkflow(options), options,
    setOwner: value => { owner = value; }, deadline: () => deadline() };
}
const key = '7:coop-invite:[1,"",31,42]';

test('request identity is durable before POST and restored without another POST', () => {
  const h = fixture();
  let requestId;
  h.workflow.run(key, (done, identity) => {
    requestId = identity.requestId;
    assert.equal([...h.values.values()][0].attemptId, requestId);
    done({ status: 'unknown' });
  }, result => assert.equal(result.requestId, requestId));
  assert.deepEqual(h.workflow.pendingRequest(key), { requestId, legacy: false });
  assert.deepEqual(Object.keys([...h.values.values()][0]).sort(),
    ['version', 'state', 'ownerDigest', 'targetDigest', 'attemptId', 'createdAt', 'requestIdentityVersion'].sort());
  const rebuilt = createVerificationWorkflow(h.options);
  rebuilt.run(key, () => assert.fail('recovered request cannot POST'), result => {
    assert.equal(result.requestId, requestId);
    assert.equal(result.recovered, true);
  });
  assert.equal(rebuilt.confirmRequest(key, requestId), true);
  assert.equal(rebuilt.isBusy(key), false);
  assert.equal(h.values.size, 0);
});

test('wrong request, account and destroyed instance cannot confirm another record', () => {
  const h = fixture();
  h.workflow.run(key, done => done({ status: 'unknown' }), () => {});
  const { requestId } = h.workflow.pendingRequest(key);
  assert.equal(h.workflow.confirmRequest(key, requestId + '_other'), false);
  h.setOwner('8');
  assert.equal(h.workflow.confirmRequest(key, requestId), false);
  h.setOwner('7'); h.workflow.destroy();
  assert.equal(h.workflow.confirmRequest(key, requestId), false);
  assert.equal(h.values.size, 1);
});

test('late old callback cannot remove a newer attempt after exact confirmation', () => {
  const h = fixture();
  let oldDone;
  h.workflow.run(key, done => { oldDone = done; }, () => assert.fail('stale callback'));
  const old = h.workflow.pendingRequest(key).requestId;
  assert.equal(h.workflow.confirmRequest(key, old), true);
  let replacement;
  assert.equal(h.workflow.run(key, (done, identity) => { replacement = identity.requestId; done({ status: 'unknown' }); }, () => {}), true);
  const current = h.workflow.pendingRequest(key).requestId;
  assert.equal(replacement, current);
  assert.notEqual(current, old);
  oldDone({ status: 'success' });
  oldDone({ status: 'failed' });
  assert.equal(h.workflow.pendingRequest(key).requestId, current);
  assert.equal(h.workflow.isBusy(key), true);
});

test('exact confirmation retires callback before synchronous abort can settle a replacement', () => {
  const h = fixture(); let oldDone, aborted = 0;
  h.workflow.run(key, done => { oldDone = done; return { abort() { aborted++; oldDone({ status: 'success' }); } }; },
    () => assert.fail('confirmed original callback must be retired'));
  const old = h.workflow.pendingRequest(key).requestId;
  assert.equal(h.workflow.confirmRequest(key, old), true); assert.equal(aborted, 1);
  assert.equal(h.workflow.run(key, done => done({ status: 'unknown' }), () => {}), true);
  const current = h.workflow.pendingRequest(key).requestId;
  assert.notEqual(current, old); oldDone({ status: 'success' });
  assert.equal(h.workflow.pendingRequest(key).requestId, current); assert.equal(h.workflow.isBusy(key), true);
});

test('storage failure keeps the exact pending record and lock', () => {
  const h = fixture();
  h.workflow.run(key, done => done({ status: 'unknown' }), () => {});
  const { requestId } = h.workflow.pendingRequest(key);
  h.storage.removeStorageSync = () => { throw Error('unavailable'); };
  assert.equal(h.workflow.confirmRequest(key, requestId), false);
  assert.equal(h.workflow.pendingRequest(key).requestId, requestId);
  assert.equal(h.workflow.isBusy(key), true);
});

test('legacy unknown stays locked without inventing a server request identity', () => {
  const h = fixture(new Map(), false);
  h.workflow.run(key, (done, identity) => {
    assert.equal(identity, undefined);
    done({ status: 'unknown' });
  }, result => assert.equal(result.requestId, undefined));
  const oldRecord = JSON.stringify([...h.values.values()][0]);
  const upgraded = createVerificationWorkflow({ ...h.options, requestIdentity: true });
  assert.deepEqual(upgraded.pendingRequest(key), { requestId: null, legacy: true });
  upgraded.run(key, () => assert.fail('legacy unknown cannot POST'), result => {
    assert.equal(result.requestId, null);
    assert.equal(result.legacyRequest, true);
  });
  assert.equal(upgraded.confirmRequest(key, [...h.values.values()][0].attemptId), false);
  assert.equal(JSON.stringify([...h.values.values()][0]), oldRecord);
});

test('failed persistence never enters POST even with request identity enabled', () => {
  for (const failure of ['read', 'write']) {
    const h = fixture();
    h.storage[failure === 'read' ? 'getStorageSync' : 'setStorageSync'] = () => { throw Error(failure); };
    h.workflow.run(key, () => assert.fail('POST entered'), result => assert.equal(result.status, 'blocked'));
  }
});

test('deadline returns the durable request identity and late POST success cannot settle it', () => {
  const h = fixture();
  let lateDone, result;
  h.workflow.run(key, done => { lateDone = done; }, value => { result = value; });
  const { requestId } = h.workflow.pendingRequest(key);
  h.deadline();
  assert.equal(result.status, 'unknown');
  assert.equal(result.requestId, requestId);
  lateDone({ status: 'success' });
  assert.equal(h.workflow.pendingRequest(key).requestId, requestId);
  assert.equal(h.workflow.isBusy(key), true);
});
