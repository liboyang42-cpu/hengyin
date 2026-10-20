'use strict';
// Written only; NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition, member = '11';
  const requests = [], bubbles = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => member, sendRequest: r => requests.push(r) });
  global.Page = v => { definition = v; }; global.wx = {};
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, nav: { active: true, targetNodeId: 31 } },
    showNavBubble: line => bubbles.push(line) });
  return { page, requests, bubbles, account: value => { member = value; } };
}
for (const change of ['target', 'restarted', 'stopped', 'account', 'activity', 'unload']) test('companion reply ignores ' + change, async () => {
  const h = load(); h.page._fetchCompanionLine();
  if (change === 'target') h.page.data.nav.targetNodeId = 32;
  if (change === 'restarted') h.page.data.nav = { active: true, targetNodeId: 31 };
  if (change === 'stopped') h.page.data.nav.active = false;
  if (change === 'account') h.account('22');
  if (change === 'activity') h.page.data.activityId = 19;
  if (change === 'unload') h.page._playDisposed = true;
  h.requests[0].success({ code: 200, data: { line: 'old fixture line' } });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.bubbles, []);
});
test('newest companion reply owns the current bubble', async () => {
  const h = load(); h.page._fetchCompanionLine(); h.page._fetchCompanionLine();
  h.requests[1].success({ code: 200, data: { line: 'new fixture line' } });
  h.requests[0].success({ code: 200, data: { line: 'old fixture line' } });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.bubbles, ['new fixture line']);
});
