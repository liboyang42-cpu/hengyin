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
  let definition; const requests = []; let member = '11', stops = 0;
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Page: value => { definition = value; }, require(name) {
      if (name.endsWith('/merchant-theme.js')) return { merchantPageShow() {}, merchantPageRestore() {} };
      return createRequire(file)(name);
    }, console,
    getApp: () => ({ globalData: {}, getUserID: () => member, sendRequest: r => requests.push(r),
      getRequestErrorMessage: (_r, fallback) => fallback }), wx: { getStorageSync: () => '', stopPullDownRefresh() { stops++; } },
  });
  const page = Object.assign({}, definition); page.data = JSON.parse(JSON.stringify(definition.data));
  page.data.activeFavoriteTab = tab; page.setData = patch => Object.assign(page.data, patch);
  return { page, requests, stops: () => stops, account(value) { member = value; }, list: () => tab === 'posts' ? page.data.postList : page.data.ztList1 };
}
function reply(request, ids = []) {
  request.success({ code: 200, data: { rows: ids.map(id => ({ id })) } }); request.complete();
}
for (const tab of ['posts', 'topics']) {
  test('E05-12 ' + tab + ' returning during old read queues exactly one fresh read', () => {
    const h = mount(tab); h.page.loadLikeData(); h.page.onShow(); // first display
    h.page.onHide(); h.page.onShow(); h.page.onShow(); // return(s) while old read is pending
    assert.equal(h.requests.length, 1);
    const old = h.requests[0]; reply(old, [7]);
    assert.equal(h.requests.length, 2); assert.equal(h.requests[1].data.pageNum, 1);
    assert.equal(h.page.data.loading, true);
    old.complete(); old.fail({});
    assert.equal(h.requests.length, 2); assert.equal(h.page.data.loading, true);
    reply(h.requests[1], []); assert.equal(h.list().length, 0);
    assert.equal(h.page.data.loading, false);
  });
}
test('E05-12 first display does not queue duplicate onLoad read', () => {
  const h = mount('posts'); h.page.onLoad({}); h.page.onShow(); reply(h.requests[0], [7]);
  assert.equal(h.requests.length, 1);
});
test('E05-12 account switch discards pending A return refresh and old completion', () => {
  const h = mount('posts'); h.page.loadLikeData(); h.page.onShow(); h.page.onShow();
  h.account('22'); h.page.onShow(); assert.equal(h.requests.length, 2);
  h.requests[0].complete(); assert.equal(h.requests.length, 2); assert.equal(h.page.data.loading, true);
  reply(h.requests[1], [8]); assert.equal(h.requests.length, 2);
});
test('E05-12 unload discards queued return read without dispatch on late completion', () => {
  const h = mount('topics'); h.page.loadLikeData(); h.page.onShow(); h.page.onShow();
  h.page.onUnload(); const state = JSON.stringify(h.page.data);
  reply(h.requests[0], [7]);
  assert.equal(h.requests.length, 1); assert.equal(JSON.stringify(h.page.data), state);
});
test('E05-12 failed old read still consumes return queue once and stops only its pull animation', () => {
  const h = mount('posts'); h.page.loadLikeData(true); h.page.onShow(); h.page.onShow();
  h.requests[0].fail({}); h.requests[0].complete();
  assert.equal(h.requests.length, 2); assert.equal(h.stops(), 1);
  reply(h.requests[1], []); assert.equal(h.stops(), 1); assert.equal(h.requests.length, 2);
});
test('E05-12 later return after queued read starts belongs to that new request', () => {
  const h = mount('posts'); h.page.loadLikeData(); h.page.onShow(); h.page.onShow();
  h.requests[0].complete(); assert.equal(h.requests.length, 2);
  h.page.onHide(); h.page.onShow(); h.requests[0].complete();
  assert.equal(h.requests.length, 2);
  h.requests[1].complete(); assert.equal(h.requests.length, 3);
  reply(h.requests[2], []); assert.equal(h.requests.length, 3);
});
