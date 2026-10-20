'use strict';
// Written only; NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition, member = '11';
  const requests = [], applied = [], opened = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => member, sendRequest: r => requests.push(r) });
  global.Page = v => { definition = v; }; global.wx = {};
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, chapter: { id: 3 }, chapterFull: true },
    _applyStoryVars: value => { applied.push(value); return true; }, openChapterFull: () => opened.push(true) });
  return { page, requests, applied, opened, account: value => { member = value; } };
}
for (const change of ['activity', 'account', 'chapter', 'load']) test('vars read ignores changed ' + change, async () => {
  const h = load(); h.page._refreshStoryVarsFromNodes();
  if (change === 'activity') h.page.data.activityId = 19;
  if (change === 'account') h.account('22');
  if (change === 'chapter') h.page.data.chapter.id = 4;
  if (change === 'load') h.page._playLoadEpoch = 1;
  h.requests[0].success({ code: 200, data: { fixture: 'old-vars' } });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.applied, []); assert.deepEqual(h.opened, []);
});
test('newest vars read alone updates the original chapter', async () => {
  const h = load(); h.page._refreshStoryVarsFromNodes(); h.page._refreshStoryVarsFromNodes();
  h.requests[1].success({ code: 200, data: { fixture: 'new-vars' } });
  h.requests[0].success({ code: 200, data: { fixture: 'old-vars' } });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.applied, [{ fixture: 'new-vars' }]); assert.equal(h.opened.length, 1);
});
