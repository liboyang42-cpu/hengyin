'use strict';

// SOURCE ONLY: ROOT runs this with the consolidated R29 suite.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { normalizeRouteState, resolveNextRouteNode } = require('../../pages/play/utils/play-route-state.js');

function projection(currentNodeId, done = []) {
  return {
    routeMode: 'LINEAR', sessionId: '9007199254740993', version: 3,
    status: currentNodeId == null ? 'COMPLETED' : 'ACTIVE',
    currentNodeId, recommendedNodeId: currentNodeId,
    nodeStates: Object.fromEntries([11, 22].map(id => [id,
      done.includes(id) ? 'COMPLETED' : id === currentNodeId ? 'PLAYABLE' : 'DISCOVERED_LOCKED'])),
  };
}

async function enter(routeState, done = []) {
  const requests = [], writes = [], opened = [];
  // A bookmark in the appendix must not select the chapter of a fresh server run.
  const bookmark = { intro: true, pos: { chapterId: '202', i: 1 }, openNodeId: '22' };
  const app = { getUserID: () => '7', sendRequest: request => requests.push(request) };
  let definition;
  global.getApp = () => app;
  global.Page = value => { definition = value; };
  global.wx = {
    getStorageSync: () => bookmark,
    setStorageSync: (...args) => writes.push(args),
    removeStorageSync: (...args) => writes.push(args),
    nextTick: callback => callback(),
  };
  const file = require.resolve('../../pages/play/index.js');
  delete require.cache[file];
  require(file);
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
  Object.assign(page.data, { topicId: '8', regId: '9' });
  page._routeState = normalizeRouteState(null);
  page.setData = function (patch, callback) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.');
      let target = this.data;
      for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {});
      target[parts[parts.length - 1]] = value;
    }
    if (callback) callback();
  };
  // Keep the real loadData -> normNode -> computeNext -> rebuild -> chapter selection;
  // peripheral reads, timers and rendering are outside this cursor regression.
  for (const method of ['_applyStoryVars', '_applyLook', '_probeCasebook', '_ensureSessionClock',
    '_syncIdleStats', '_syncRunState', '_restoreRunSession', 'loadTeamProgress', '_loadPlayerGameModule',
    'refreshThemeBackpack', '_consumePendingStart', '_startThemeBgm', 'loadHudCharacter',
    'loadSessionStats', '_refreshNavRim', '_measureDeal', 'buildJournal']) page[method] = () => {};
  page._openStory = () => {
    opened.push(page.data.chapter.id);
    page.data.chapterParas = [{ kind: 'text', i: 0 }, { kind: 'text', i: 1 }];
    page._resumeStory();
  };
  page.openFinish = () => opened.push('finish');
  page.startStoryGame = () => assert.fail('Entry must not reopen a locked or completed saved task');
  const response = {
    code: 200, data: {
      registered: true, playable: true, selfPlay: true, topicId: '8', mode: 1, routeState,
      // The frozen route cursor can differ from presentation sortId after an author edit.
      nodes: [
        { nodeId: 22, chapterId: 202, sortId: 1, name: '附录任务', done: done.includes(22) },
        { nodeId: 11, chapterId: 101, sortId: 9, name: '主线任务', done: done.includes(11) },
      ],
      chapters: [{ chapterId: 101, name: '主线' }, { chapterId: 202, name: '附录' }],
    },
  };
  const before = JSON.stringify(response);
  page.loadData(true);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/play/nodes');
  requests[0].success(response);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page._loadCommitted, true);
  assert.equal(JSON.stringify(response), before, 'Returned progress is never rewritten');
  assert.deepEqual(writes, [], 'Selecting entry never clears or replaces stored progress');
  assert.deepEqual(requests.map(request => [request.url, request.method]),
    [['/api/play/nodes', 'GET'], ['/api/play/history', 'GET']], 'Entry reads never submit tasks, unlocks or rewards');
  assert.deepEqual(requests[1].data, { activityId: '0', topicId: '8', routeSessionId: routeState.sessionId },
    'History reads the exact original route session');
  requests[1].success({ code: 500, msg: '本局历史快照不可用' });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page._hasGenericProofFlow(), false, 'Cursor is correct before the backpack read');
  return { page, opened };
}

test('新购首进采用服务端当前主线，附录排序与旧书签均不能抢游标', async () => {
  const { page, opened } = await enter(projection(11));
  assert.equal(page.data.nextNode.nodeId, 11);
  assert.equal(page.data.chapter.id, 101);
  assert.equal(page.data.doneCount, 0);
  assert.equal(page.data.chapterGameAnchor, '');
  assert.deepEqual(opened, [101]);
});

test('主线已完成时合法续玩附录，并保留本章阅读位置', async () => {
  const { page, opened } = await enter(projection(22, [11]), [11]);
  assert.equal(page.data.nextNode.nodeId, 22);
  assert.equal(page.data.doneCount, 1);
  assert.equal(page.data.chapterGameAnchor, 'chapter-block-1');
  assert.deepEqual(opened, [202]);
});

test('已通关重进沿用完成入口，不将未选附录推荐为新任务', async () => {
  const { page, opened } = await enter(projection(null, [11]), [11]);
  assert.equal(page.data.allDone, true);
  assert.equal(page.data.nextNode, null);
  assert.equal(page.data.doneCount, 1, 'Unplayed optional content is not forged complete');
  assert.deepEqual(opened, ['finish']);
});

test('权威 LINEAR 目标缺失、锁定、已完成或状态失效时不回退附录排序', () => {
  const nodes = [{ nodeId: 22, sortId: 1 }, { nodeId: 11, sortId: 9 }];
  for (const changes of [
    { currentNodeId: 33, recommendedNodeId: 33 },
    { nodeStates: { 11: 'DISCOVERED_LOCKED', 22: 'PLAYABLE' } },
    { nodeStates: { 11: 'UNKNOWN' } },
    { nodeStates: {} },
    { status: 'EXPIRED' },
    { status: 'UNAVAILABLE' },
  ]) assert.equal(resolveNextRouteNode(nodes, { ...projection(11), ...changes }), null);
  assert.equal(resolveNextRouteNode([{ ...nodes[1], done: true }, nodes[0]], projection(11)), null);
  assert.equal(resolveNextRouteNode(nodes, { routeMode: 'LINEAR' }).nodeId, 22,
    'Legacy responses without any authoritative projection keep their existing sort order');
});
