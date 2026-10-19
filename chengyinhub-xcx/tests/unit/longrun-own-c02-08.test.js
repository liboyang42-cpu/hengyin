// NOT_RUN: verification deferred by user.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const intents = require('../../utils/publish/publish-intent.js');
test('changed payload receives a new intent and an old success cannot settle the current one', () => {
  const values = {};
  const wx = { getStorageSync:k=>values[k], setStorageSync:(k,v)=>{values[k]=v;}, removeStorageSync:k=>{delete values[k];} };
  const options = { scope:'square:compose', memberId:42, payload:['A'] };
  const first = intents.begin(wx, options);
  intents.settle(wx, options.scope, 42, first.key, 'unknown');
  assert.equal(intents.begin(wx, options).key, first.key);
  const second = intents.begin(wx, { ...options, payload:['B'] });
  assert.notEqual(second.key, first.key);
  assert.equal(second.previousUnknown, true);
  intents.settle(wx, options.scope, 42, first.key, 'success');
  assert.equal(intents.pending(wx, options.scope, 42).key, second.key);
  assert.equal(intents.read(wx, options.scope + ':previous', 42).key, first.key);
});
