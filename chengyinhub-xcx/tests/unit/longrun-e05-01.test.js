'use strict';
// NOT_RUN: authored only; user deferred execution.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
function load() {
  const calls = [], module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../utils/subscribe.js'), 'utf8'), {
    module, wx: { requestSubscribeMessage: options => calls.push(options) }, console: { warn() {} },
  });
  return { ...module.exports, calls };
}
const plain = value => JSON.parse(JSON.stringify(value));
test('E05-01 duplicate keys do not consume the three distinct template slots', () => {
  const h = load(), t = h.TEMPLATES;
  assert.deepEqual(plain(h.resolveTemplateIds(['refund', 'refund', 'signupSuccess', 'activityStart', 'refundRejected'])),
    [t.refund, t.signupSuccess, t.activityStart]);
});
test('E05-01 alias IDs are sent once while each business key receives the same result', async () => {
  const h = load(); h.TEMPLATES.fixtureAlias = h.TEMPLATES.refund;
  const pending = h.request(['refund', 'fixtureAlias', 'refundRejected']);
  assert.deepEqual(plain(h.calls[0].tmplIds), [h.TEMPLATES.refund, h.TEMPLATES.refundRejected]);
  h.calls[0].success({ [h.TEMPLATES.refund]: 'accept', [h.TEMPLATES.refundRejected]: 'reject' });
  const result = await pending;
  assert.equal(result.status, h.STATUS.PARTIAL);
  assert.deepEqual(plain(result.requestedKeys), ['refund', 'fixtureAlias', 'refundRejected']);
  assert.deepEqual(plain(result.acceptedKeys), ['refund', 'fixtureAlias']);
  assert.deepEqual(plain(result.rejectedKeys), ['refundRejected']);
});
test('E05-01 missing keys do not take slots and excluded fourth template is not reported rejected', async () => {
  const h = load(), t = h.TEMPLATES;
  const pending = h.request(['clubInterest', 'refund', 'refund', 'unknownFixture', 'signupSuccess', 'activityStart', 'refundRejected']);
  assert.deepEqual(plain(h.calls[0].tmplIds), [t.refund, t.signupSuccess, t.activityStart]);
  h.calls[0].success({ [t.refund]: 'accept', [t.signupSuccess]: 'accept', [t.activityStart]: 'accept' });
  const result = await pending;
  assert.equal(result.status, h.STATUS.ACCEPTED);
  assert.deepEqual(plain(result.requestedKeys), ['refund', 'signupSuccess', 'activityStart']);
  assert.deepEqual(plain(result.rejectedKeys), []);
  assert.deepEqual(plain(result.unconfiguredKeys), ['clubInterest', 'unknownFixture']);
});
test('E05-01 repeated rejected key appears once and SDK failure preserves unique selected keys', async () => {
  const h = load();
  const pending = h.request(['refund', 'refund']);
  h.calls[0].success({ [h.TEMPLATES.refund]: 'reject' });
  assert.deepEqual(plain((await pending).rejectedKeys), ['refund']);
  const failed = h.request(['refund', 'refund', 'refundRejected']); h.calls[1].fail({});
  const result = await failed;
  assert.equal(result.status, h.STATUS.FAILED);
  assert.deepEqual(plain(result.requestedKeys), ['refund', 'refundRejected']);
});
