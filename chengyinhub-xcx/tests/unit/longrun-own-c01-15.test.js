'use strict';
// Written only; NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition, member = '11';
  const requests = [], toasts = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => member, sendRequest: r => requests.push(r) });
  global.Page = v => { definition = v; }; global.getCurrentPages = () => [];
  global.wx = { showToast: r => toasts.push(r) };
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, newLifeOS: { tags: [{ id: 7 }, { id: 8 }] } },
    setData(patch) { if (patch['newLifeOS.tags']) this.data.newLifeOS.tags = patch['newLifeOS.tags']; } });
  return { page, requests, toasts, account: value => { member = value; } };
}
const tap = { currentTarget: { dataset: { id: 7 } } };
test('old topic completion cannot remove current tag or release current lock', async () => {
  const h = load(); h.page.revokeOsTag(tap);
  h.page.data.topicId = 18; h.page.revokeOsTag(tap);
  assert.equal(h.requests.length, 2);
  h.requests[0].success({ code: 200 });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.page.data.newLifeOS.tags, [{ id: 7 }, { id: 8 }]);
  assert.equal(Object.keys(h.page._osTagWrites).length, 1);
  h.page.revokeOsTag(tap); assert.equal(h.requests.length, 2, 'current lock stays held');
  h.requests[1].success({ code: 200 });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.page.data.newLifeOS.tags, [{ id: 8 }]);
  assert.equal(Object.keys(h.page._osTagWrites).length, 0);
});
test('old account failure does not toast or modify the current account list', async () => {
  const h = load(); h.page.revokeOsTag(tap); h.account('22');
  h.requests[0].success({ code: 400, msg: 'fixture failure' });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.toasts, []);
  assert.deepEqual(h.page.data.newLifeOS.tags, [{ id: 7 }, { id: 8 }]);
});
