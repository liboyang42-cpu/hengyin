'use strict';
// Written only; NOT_RUN.
const { test } = require('node:test'); const assert = require('node:assert/strict'); const path = require('node:path');
function load() {
  let definition, member = '11'; const requests = [], cards = [], continuations = [], callbacks = [], opened = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => member, sendRequest: r => requests.push(r) }); global.Page = v => { definition = v; }; global.wx = {};
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, mode: 2, nodes: [] }, _routeState: { sessionId: 'route-9' },
    _showEnding: card => cards.push(card), stopAudio() {}, buildChapter: () => ({ name: 'fixture chapter' }),
    openChapterFull: () => opened.push(true), setData(p, cb) { Object.assign(this.data, p); if (cb) callbacks.push(cb); } });
  return { page, requests, cards, continuations, callbacks, opened, next: () => continuations.push(true), account: v => { member = v; } };
}
for (const change of ['topic', 'account', 'session', 'newread']) test('life ending refuses old ' + change + ' continuation', async () => {
  const h = load(); h.page._loadLifeEnding(h.next);
  if (change === 'topic') h.page.data.topicId = 18;
  if (change === 'account') h.account('22');
  if (change === 'session') h.page._routeState.sessionId = 'route-19';
  if (change === 'newread') h.page._loadLifeEnding(h.next);
  h.requests[0].fail({}); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(h.continuations, []); assert.deepEqual(h.cards, []);
});
test('ending chapter and its after callback cannot open in a new account', async () => {
  const h = load(); h.page._loadLifeEnding(h.next);
  h.requests[0].success({ code: 200, data: { title: 'fixture ending', archive: {}, chapter: { id: 3, name: 'fixture', blocks: [{}] } } });
  await new Promise(resolve => setImmediate(resolve));
  h.account('22'); h.callbacks[0](); h.page._afterEndingChapter();
  assert.deepEqual(h.opened, []); assert.deepEqual(h.cards, []);
});
test('mode2 old ending cannot write error into a changed topic', async () => {
  // 有 activityId 时 _sessionParams 只认 activityId;按主题游玩(无 activityId)时换主题才是换会话。
  const h = load(); h.page.data.activityId = 0; h.page.loadEnding(); h.page.data.topicId = 18;
  h.requests[0].fail({}); await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.page.data.endingError, false);
});
