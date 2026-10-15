'use strict';
// NOT_RUN: user deferred validation.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../pages/mylike/mylike.js');
function mount(tab) {
  let definition; const requests = [];
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Page: value => { definition = value; }, require: createRequire(file), console,
    getApp: () => ({ globalData: {}, getUserID: () => '11', sendRequest: r => requests.push(r),
      getRequestErrorMessage: (_r, fallback) => fallback }), wx: {},
  });
  const page = Object.assign({}, definition); page.data = JSON.parse(JSON.stringify(definition.data));
  page.data.activeFavoriteTab = tab; page.setData = patch => Object.assign(page.data, patch);
  return { page, requests, list: () => tab === 'posts' ? page.data.postList : page.data.ztList1 };
}
const rows = start => Array.from({ length: 10 }, (_v, i) => ({ id: start + i }));
function succeed(request, start) { request.success({ code: 200, data: { rows: rows(start) } }); request.complete(); }
for (const tab of ['posts', 'topics']) {
  for (const failure of ['fail', 'business', 'abnormal']) test('E05-06 ' + tab + ' retries failed next page after ' + failure, () => {
    const h = mount(tab); h.page.loadLikeData(); succeed(h.requests[0], 0);
    h.page.onReachBottom(); const request = h.requests[1]; assert.equal(request.data.pageNum, 2);
    if (failure === 'fail') request.fail({});
    else if (failure === 'business') request.success({ code: 500 });
    else request.successStatusAbnormal({});
    request.complete(); assert.equal(h.list().length, 10);
    h.page.onReachBottom(); assert.equal(h.requests[2].data.pageNum, 2);
    succeed(h.requests[2], 10); assert.equal(h.list().length, 20);
  });
  test('E05-06 ' + tab + ' failed refresh retains committed cursor and success resets it', () => {
    const h = mount(tab); h.page.loadLikeData(); succeed(h.requests[0], 0);
    h.page.onReachBottom(); succeed(h.requests[1], 10);
    h.page.refreshData(); assert.equal(h.requests[2].data.pageNum, 1);
    h.requests[2].fail({}); h.requests[2].complete();
    assert.equal(h.list().length, 20);
    h.page.onReachBottom(); assert.equal(h.requests[3].data.pageNum, 3); succeed(h.requests[3], 20);
    h.page.refreshData(); succeed(h.requests[4], 100);
    assert.equal(h.list().length, 10); h.page.onReachBottom(); assert.equal(h.requests[5].data.pageNum, 2);
  });
}
