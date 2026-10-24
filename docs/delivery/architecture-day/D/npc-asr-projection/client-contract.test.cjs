const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../../../..');
const file = process.env.NPC_ASR_CLIENT_SOURCE || path.join(root, 'chengyinhub-xcx/subpackageMember/components/cy/npc-chat/index.js');
const source = fs.readFileSync(file, 'utf8');
const cases = JSON.parse(fs.readFileSync(path.join(__dirname, 'contract-cases.json'), 'utf8'));
const rid = 'e2596106-82d9-4a4f-9ff2-058414599f90';
function fixture() {
  let definition;
  const calls = [], timers = [];
  vm.runInNewContext(source, {
    Component(value) { definition = value; },
    require(name) { return name.includes('analytics') ? { track() {} } : {}; },
    getApp() { return { sendRequest(options) { calls.push(options); return { abort() {} }; } }; },
    setTimeout(callback) { timers.push(callback); return timers.length; },
    clearTimeout() {},
  }, { filename: file });
  const pending = { requestId: rid, message: 'original message', userMessage: { id: 'u1' }, bizId: 21, scope: 'shop' };
  const component = Object.assign({}, definition.methods, {
    properties: { show: true, sessionId: 21, scope: 'shop' },
    data: Object.assign({}, definition.data, { messages: [] }),
    _generation: 1, _messageSeq: 0, _retryCount: 0,
    _pendingRecovery: pending, _retryMessage: pending, _retryMode: 'send',
    setData(update) { Object.assign(this.data, update); },
  });
  return { component, pending, calls, timers };
}
assert.equal(cases.length, 9, 'nonempty explicit contract shared with Java VO test');
for (const entry of cases) test(entry.name + ': real component preserves original recovery identity', () => {
  const { component, pending, calls, timers } = fixture();
  const response = { code: 200, data: {
    requestId: rid, outcomeStatus: entry.outcomeStatus, safetyDecision: entry.safetyDecision,
    errorCode: entry.errorCode, asrState: entry.state, asrReason: entry.asrReason,
    asrResumable: entry.asrResumable, retryable: false, safeText: 'safe phase description',
  } };
  component._onResponse(1, rid, response);
  if (entry.outcomeStatus === 'PROCESSING') {
    assert.equal(component._pendingRecovery, pending);
    assert.equal(component._retryMessage, pending);
    assert.equal(component.data.state, 'CHECKING');
    assert.equal(component._retryMode, 'readback', 'ASR waiting must never automatically POST again');
    assert.equal(timers.length, 1);
    timers.shift()();
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/api/ai/npc/chat/request');
    assert.equal(calls[0].method, 'GET');
    assert.equal(calls[0].data.requestId, rid);
    calls[0].success(response);
    assert.equal(component._pendingRecovery, pending);
    assert.equal(component._retryMessage, pending);
    assert.equal(component.data.state, 'CHECKING');
    assert.equal(calls.filter(call => call.method === 'POST').length, 0);
  } else {
    assert.equal(component._pendingRecovery, null);
    component._onReadback(1, pending, response);
    assert.equal(component._pendingRecovery, null);
    assert.equal(component._retryMessage, null);
    assert.equal(timers.length, 0);
    assert.equal(calls.length, 0);
  }
});
