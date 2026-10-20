'use strict';
// NOT_RUN: user deferred centralized execution.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
function load() {
  const calls = [], warnings = [], module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../utils/subscribe.js'), 'utf8'), {
    module, wx: { requestSubscribeMessage: options => calls.push(options) }, console: { warn: text => warnings.push(text) },
  });
  return { ...module.exports, calls, warnings };
}
const plain = value => JSON.parse(JSON.stringify(value));
const inherited = ['constructor', 'toString', '__proto__'];
test('E05-13 inherited property names are unknown template keys', () => {
  const h = load(); assert.equal(h.resolveTemplateIds(inherited).length, 0);
});
test('E05-13 inherited keys resolve not_configured without contacting SDK and warn once', async () => {
  const h = load(); const result = await h.request(inherited.concat('constructor'));
  assert.equal(result.status, h.STATUS.NOT_CONFIGURED);
  assert.deepEqual(plain(result.requestedKeys), []);
  assert.deepEqual(plain(result.unconfiguredKeys), inherited);
  await h.request(inherited);
  assert.equal(h.calls.length, 0); assert.equal(h.warnings.length, 1);
});
test('E05-13 inherited keys do not consume slots or corrupt result mapping', async () => {
  const h = load(), t = h.TEMPLATES;
  const pending = h.request(['constructor', 'refund', 'toString', 'signupSuccess', '__proto__', 'activityStart', 'refundRejected']);
  assert.deepEqual(plain(h.calls[0].tmplIds), [t.refund, t.signupSuccess, t.activityStart]);
  h.calls[0].success({ [t.refund]: 'accept', [t.signupSuccess]: 'reject', [t.activityStart]: 'accept' });
  const result = await pending;
  assert.equal(result.status, h.STATUS.PARTIAL);
  assert.deepEqual(plain(result.acceptedKeys), ['refund', 'activityStart']);
  assert.deepEqual(plain(result.rejectedKeys), ['signupSuccess']);
  assert.deepEqual(plain(result.unconfiguredKeys), inherited);
});
test('E05-13 explicitly configured own alias may shadow a prototype name safely', async () => {
  const h = load(); h.TEMPLATES.hasOwnProperty = h.TEMPLATES.refund;
  const pending = h.request(['hasOwnProperty', 'refund']);
  assert.deepEqual(plain(h.calls[0].tmplIds), [h.TEMPLATES.refund]);
  h.calls[0].success({ [h.TEMPLATES.refund]: 'accept' });
  const result = await pending;
  assert.equal(result.status, h.STATUS.ACCEPTED);
  assert.deepEqual(plain(result.acceptedKeys), ['hasOwnProperty', 'refund']);
});
