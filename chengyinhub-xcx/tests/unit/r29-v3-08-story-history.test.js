const { test } = require('node:test');
const assert = require('node:assert/strict');

test('V3-08: completed history nodes do not truncate the chapter or become playable', () => {
  const requests = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => 9,
    sendRequest: request => requests.push(request) });
  global.wx = { getStorageSync: () => '', setStorageSync() {},
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }) };
  let config;
  global.Page = value => { config = value; };
  require('../../pages/play/index.js');
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  page.setData = (patch, callback) => { Object.assign(page.data, patch); if (callback) callback(); };
  for (const method of ['showChapterGate', 'measureChapter', 'playChapterAudio']) {
    page[method] = () => {};
  }
  page.playChapterVideo = () => true;
  page.startGame = () => assert.fail('history must not start or resubmit a game');
  page.data.chapter = { id: 5, blocks: [
    { type: 'text', content: '已经读过的开场' },
    { type: 'node', nodeId: 10, locationRequired: false },
    { type: 'text', content: '完成后解锁的收尾' },
    { type: 'node', nodeId: 20, locationRequired: false },
    { type: 'text', content: '尚未解锁的后文' },
  ] };
  page.data.nodes = [{ nodeId: 20, chapterId: 5, done: false, name: '当前玩法', hasAdvanced: true }];
  page._historyNodes = [{ nodeId: 10, chapterId: 5, done: true, history: true }];
  const before = JSON.stringify(page.data.nodes);

  page.openChapterFull();
  assert.deepEqual(page.data.chapterParas.map(p => p.text).slice(0, 2),
    ['已经读过的开场', '完成后解锁的收尾']);
  assert.equal(page.data.chapterParas.some(p => p.text === '尚未解锁的后文'), false);
  assert.equal(page.data.chapterParas.some(p => p.nodeId === 10), false,
    'completed history must not acquire an active game/kit entry');
  page.startStoryGame({ currentTarget: { dataset: { nodeid: 10 } } });
  page.openChapterFull();
  assert.equal(JSON.stringify(page.data.nodes), before, 'reading must preserve authoritative progress');
  assert.equal(page._nodeById(10), undefined, 'history stays outside playable nodes');
  assert.deepEqual(requests, [], 're-reading must not send a command or reward request');

  page.data.nodes.unshift({ nodeId: 10, chapterId: 5, done: false });
  page.openChapterFull();
  assert.equal(page.data.chapterParas.some(p => p.text === '完成后解锁的收尾'), false,
    'current incomplete progress must take precedence over a stale history entry');
  page.data.nodes.shift();

  // A new response/page without this history must still stop at the unknown node.
  page._historyNodes = [];
  page.openChapterFull();
  assert.deepEqual(page.data.chapterParas.map(p => p.text), ['已经读过的开场']);
  page._historyNodes = [{ nodeId: 10, chapterId: 5, done: false }];
  page.openChapterFull();
  assert.deepEqual(page.data.chapterParas.map(p => p.text), ['已经读过的开场'],
    'a historical-looking ID is insufficient without completion');
});
