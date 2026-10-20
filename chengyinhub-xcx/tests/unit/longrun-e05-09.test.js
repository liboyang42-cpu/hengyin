'use strict';
// NOT_RUN: regression authored for later centralized validation.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../subpackageMember/components/cy/scene-member-order-history/index.js');
function mount() {
  let definition, member = '11';
  const requests = [], patches = [];
  const app = { getUserID: () => member, getPageSize: () => 10,
    getRequestErrorMessage: (_r, fallback) => fallback,
    sendRequest: request => requests.push(request) };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    getApp: () => app, Component: config => { definition = config; },
    require: createRequire(file), wx: { getStorageSync: () => '' }, console,
  }, { filename: file });
  const page = Object.assign({}, definition.methods);
  page.data = JSON.parse(JSON.stringify(definition.data));
  page.setData = patch => { patches.push(patch); Object.assign(page.data, patch); };
  page._initWorkflow = () => {};
  return { page, requests, patches, account(value) { member = value; },
    attach() { definition.lifetimes.attached.call(page); },
    detach() { definition.lifetimes.detached.call(page); },
    show() { definition.pageLifetimes.show.call(page); } };
}
for (const outcome of ['success', 'business', 'fail', 'complete']) {
  test('E05-09 detached history ignores late list ' + outcome, () => {
    const h = mount(); h.attach(); const old = h.requests[0]; h.detach();
    const state = JSON.stringify(h.page.data), writes = h.patches.length;
    if (outcome === 'success') old.success({ code: 200, data: { rows: [{ id: 7 }] } });
    if (outcome === 'business') old.success({ code: 500 });
    if (outcome === 'fail') old.fail({});
    old.complete();
    assert.equal(JSON.stringify(h.page.data), state); assert.equal(h.patches.length, writes);
    assert.equal(h.page._listFetchInFlight, 0); assert.equal(h.page.data.loading, false);
  });
}
test('E05-09 retired generation cannot populate or end newly attached list request', () => {
  const h = mount(); h.attach(); const old = h.requests[0]; h.detach(); h.attach();
  const current = h.requests[1], currentId = h.page._listFetchInFlight;
  const writes = h.patches.length;
  old.success({ code: 200, data: { rows: [{ id: 7 }] } }); old.fail({}); old.complete();
  assert.equal(h.patches.length, writes); assert.equal(h.page._listFetchInFlight, currentId);
  assert.equal(h.page.data.loading, true);
  current.success({ code: 200, data: { rows: [] } }); current.complete();
  assert.equal(h.page.data.loading, false); assert.equal(h.page._listFetchInFlight, 0);
});
test('E05-09 disposed history cannot start list loads through show or getList', () => {
  const h = mount(); h.attach(); h.detach(); const writes = h.patches.length;
  h.show(); h.page.getList();
  assert.equal(h.requests.length, 1); assert.equal(h.patches.length, writes);
});
test('E05-09 live latest list retains account and requestId guards', () => {
  const h = mount(); h.attach(); const first = h.requests[0];
  h.account('22'); h.show(); const current = h.requests[1];
  const writes = h.patches.length;
  first.success({ code: 200, data: { rows: [{ id: 7 }] } }); first.complete();
  assert.equal(h.patches.length, writes); assert.equal(h.page.data.loading, true);
  current.success({ code: 200, data: { rows: [] } }); current.complete();
  assert.equal(h.page.data.loading, false);
});
