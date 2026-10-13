const { beforeEach, test } = require('node:test');
const assert = require('node:assert/strict');
const MODULE = require.resolve('../../utils/npc-pending-request.js');
const requestId = 'abcdef01-abcd-4abc-8abc-abcdefabcdef';
let storage;
let pending;
function reload() {
  delete require.cache[MODULE];
  return require(MODULE);
}
beforeEach(() => {
  storage = {};
  global.wx = {
    getStorageSync: key => storage[key],
    setStorageSync: (key, value) => { storage[key] = JSON.parse(JSON.stringify(value)); },
  };
  const api = reload();
  pending = Object.assign(api.context(9, 'shop', 11, 'activity:123'), { requestId });
});

test('process recreation reads only original request metadata, without text/audio/token', () => {
  assert.equal(reload().save(Object.assign({}, pending, { message: 'secret text', audioUrl: 'secret audio', token: 'secret token' })), true);
  const restored = reload().read(pending);
  assert.deepEqual(restored, { error: false, pending });
  assert.doesNotMatch(JSON.stringify(storage), /secret|message|audioUrl|token/);
});

test('object, play session and NPC scope are isolated without overwriting another original request', () => {
  const api = reload();
  api.save(pending);
  for (const target of [api.context(9, 'shop', 12, 'activity:123'), api.context(9, 'shop', 11, 'topic:123'), api.context(9, 'merchant', 11)]) {
    assert.equal(api.read(target).pending, null);
  }
  assert.equal(api.save(Object.assign({}, pending, { requestId: requestId.replace('abcdef01', 'abcdef02') })), false);
  assert.equal(api.read(pending).pending.requestId, requestId);
});

test('account change and logout isolate A metadata; returning to A still restores the original ID', () => {
  const api = reload();
  api.save(pending);
  assert.equal(api.read(api.context(10, 'shop', 11, 'activity:123')).pending, null);
  assert.equal(api.read(pending).pending.requestId, requestId);
  assert.equal(api.read(api.context('', 'shop', 11, 'activity:123')).error, true);
  assert.equal(reload().read(pending).pending.requestId, requestId);
});

test('corruption and throwing reads fail closed without destroying unknown pending state', () => {
  const api = reload();
  const key = 'npc_pending_requests_v1:9';
  for (const value of ['broken JSON', false, { owner: '9', requests: [{ ...pending, message: 'unexpected' }] },
    { owner: '9', requests: [null] }, { owner: '9', requests: [{ ...pending, session: ['activity:123'] }] },
    { owner: '9', requests: [{ ...pending, requestId: [requestId] }] },
    { owner: '9', requests: [pending, { ...pending, requestId: requestId.replace('abcdef01', 'abcdef02') }] }]) {
    storage[key] = value;
    assert.equal(api.read(pending).error, true);
    assert.equal(api.save(pending), false);
    assert.deepEqual(storage[key], value);
  }
  global.wx.getStorageSync = () => { throw new Error('unavailable'); };
  assert.equal(api.read(pending).error, true);
  assert.equal(api.save(pending), false);
});

test('only well formed original terminal responses permit cleanup; invalid/unavailable stays unknown', () => {
  const api = reload();
  for (const outcomeStatus of ['SUCCEEDED', 'FAILED', 'REJECTED']) {
    assert.equal(api.isTerminal({ outcomeStatus, safetyDecision: 'PASS', safeText: 'safe', errorCode: outcomeStatus === 'SUCCEEDED' ? '' : 'UPSTREAM_FAILED' }), true);
  }
  for (const result of [null, {}, { outcomeStatus: 'SUCCEEDED', safetyDecision: 'PASS' },
    { outcomeStatus: 'FAILED', safeText: 'unconfirmed' },
    ...['REQUEST_NOT_FOUND', 'REQUEST_STATE_UNAVAILABLE', 'REQUEST_CONFLICT', 'INVALID_REQUEST'].map(errorCode =>
      ({ outcomeStatus: 'REJECTED', safetyDecision: 'NOT_RUN', safeText: 'unknown', errorCode }))]) {
    assert.ok(!api.isTerminal(result));
  }
});

test('failed and silently dropped writes are rejected before a request can be sent', () => {
  const api = reload();
  global.wx.setStorageSync = () => { throw new Error('quota'); };
  assert.equal(api.save(pending), false);
  global.wx.setStorageSync = () => {};
  assert.equal(api.save(pending), false);
});

test('terminal cleanup only removes its original request and retains unknown on failed cleanup', () => {
  const api = reload();
  api.save(pending);
  const other = Object.assign(api.context(9, 'shop', 12, 'activity:123'), { requestId: requestId.replace('abcdef01', 'abcdef02') });
  api.save(other);
  api.clear(Object.assign({}, pending, { requestId: 'other' }));
  assert.equal(api.read(pending).pending.requestId, requestId);
  const write = global.wx.setStorageSync;
  global.wx.setStorageSync = () => { throw new Error('quota'); };
  assert.equal(api.clear(pending), false);
  global.wx.setStorageSync = write;
  assert.equal(api.clear(pending), true);
  assert.equal(api.read(pending).pending, null);
  assert.deepEqual(api.read(other).pending, other);
  assert.equal(api.clear(pending), true);
});
