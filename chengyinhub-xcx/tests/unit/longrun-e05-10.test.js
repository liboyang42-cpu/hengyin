'use strict';
// NOT_RUN: source-only delivery, centralized execution deferred by user.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../subpackageMember/components/scene-member-participation-detail/index.js');
function mount() {
  let definition, member = '11';
  const requests = [], patches = [];
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Component: config => { definition = config; }, require: createRequire(file),
    getApp: () => ({ getUserID: () => member, sendRequest: request => requests.push(request),
      getRequestErrorMessage: (_r, fallback) => fallback }), console,
  }, { filename: file });
  const page = Object.assign({}, definition.methods);
  page.data = JSON.parse(JSON.stringify(definition.data)); page.data.id = '7';
  page.setData = patch => { patches.push(patch); Object.assign(page.data, patch); };
  return { page, requests, patches, account(value) { member = value; },
    detach() { definition.lifetimes.detached.call(page); },
    attach() { definition.lifetimes.attached.call(page); } };
}
function reply(request, outcome) {
  if (outcome === 'success') request.success({ code: 200, data: { id: 7, paymentStatus: 2 } });
  else if (outcome === 'business') request.success({ code: 500 });
  else request.fail({});
}
for (const invalidate of ['account', 'detach', 'record']) {
  for (const outcome of ['success', 'business', 'fail']) {
    test('E05-10 late detail ' + outcome + ' after ' + invalidate, () => {
      const h = mount(); h.page._cancelRecordPending = true; h.page.getData();
      if (invalidate === 'account') h.account('22');
      else if (invalidate === 'detach') h.detach();
      else h.page._applyRecordId('8');
      const state = JSON.stringify(h.page.data), writes = h.patches.length;
      reply(h.requests[0], outcome);
      assert.equal(JSON.stringify(h.page.data), state); assert.equal(h.patches.length, writes);
      assert.equal(h.page._cancelRecordPending, true, 'stale success cannot enable new-context cancellation');
    });
  }
}
test('E05-10 latest live request retains original epoch ordering', () => {
  const h = mount(); h.page.getData(); h.page.getData();
  reply(h.requests[1], 'success'); const state = JSON.stringify(h.page.data), writes = h.patches.length;
  for (const outcome of ['success', 'business', 'fail']) reply(h.requests[0], outcome);
  assert.equal(JSON.stringify(h.page.data), state); assert.equal(h.patches.length, writes);
  assert.equal(h.page.data.loadState, 'ready');
});
test('E05-10 current B detail works while A response cannot overwrite it', () => {
  const h = mount(); h.page.getData(); h.account('22'); h.page.getData();
  reply(h.requests[1], 'success'); const writes = h.patches.length;
  reply(h.requests[0], 'success'); assert.equal(h.patches.length, writes);
  assert.equal(h.page.data.loadState, 'ready');
});
test('E05-10 detached instance cannot dispatch detail read and retired generation stays invalid', () => {
  const h = mount(); h.page.getData(); h.detach(); h.page.getData();
  assert.equal(h.requests.length, 1);
  h.attach(); h.page.getData(); const writes = h.patches.length;
  reply(h.requests[0], 'success'); assert.equal(h.patches.length, writes);
  reply(h.requests.at(-1), 'fail'); assert.equal(h.page.data.loadState, 'error');
});
