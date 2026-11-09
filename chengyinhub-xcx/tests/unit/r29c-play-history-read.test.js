'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const historyView = require('../../pages/play/utils/play-history.js');
const SESSION = '9007199254740993', NODE = '9007199254740995', CHAPTER = '9007199254740997';
const TARGET = historyView.scope('9', '0', '8', SESSION);
function response(overrides = {}) {
  return { routeSessionId: SESSION, activityId: '0', topicId: '8', configVersion: '12', readOnly: true,
    narrativeAvailable: true, chapters: [{ chapterId: CHAPTER, complete: true, blocks: [
      { type: 'text', content: '原开场{name}' }, { type: 'node', nodeId: NODE },
      { type: 'divider', key: 'line-1', content: 'discard' }, { type: 'text', content: '原收尾' },
      { type: 'text', beat: 'secret', content: '未获结果' }, { type: 'text', when: {}, content: '未知条件' },
      { type: 'thought', thoughtKey: 'old' },
    ] }], nodes: [{ nodeId: NODE, chapterId: CHAPTER, done: true, history: true,
      description: '原节点正文{name}', storyText: '原手记{name}', advancedConfig: { answer: 'secret' },
      results: [{ kind: 'completedGame', gameSessionId: '9007199254740999', score: 0, completedUnits: 1,
        outcomes: { coinSide: 'TAILS', diceRoll: { pips: [1, 6], total: 7 }, draws: [{ id: 'd', content: '原抽签' }], answer: 'secret' },
        state: { answer: 'secret' } }, { kind: 'routeReceipt', receiptId: 'r1', outcomeCode: 'PASSED', passed: true }] }], ...overrides };
}
function pageFixture() {
  const requests = [], stored = [], actor = { id: 9, epoch: 0 };
  const app = { globalData: {}, getUserID: () => actor.id, sendRequest: request => requests.push(request) };
  Object.defineProperty(app, '_loginEpoch', { get: () => actor.epoch });
  global.getApp = () => app;
  global.wx = { getStorageSync: () => '', setStorageSync: (...args) => stored.push(args),
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }) };
  let config;
  global.Page = value => { config = value; };
  delete require.cache[require.resolve('../../pages/play/index.js')];
  require('../../pages/play/index.js');
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  page.setData = (patch, callback) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.'); let target = page.data;
      while (parts.length > 1) target = target[parts.shift()];
      target[parts[0]] = value;
    }
    if (callback) callback();
  };
  page.data.activityId = '0'; page.data.topicId = '8';
  page._routeState = { sessionId: SESSION, routeMode: 'LINEAR', thoughts: [] };
  page._playLoadEpoch = 1;
  page.data.nodes = [{ nodeId: '20', chapterId: '2', description: 'live', done: false }];
  page.data.total = 1; page.data.doneCount = 0;
  page.data.chapter = { id: '2' };
  page.data.chapterCards = [{ id: CHAPTER, title: '当前作者新标题', cover: 'new-cover', blocks: [{ type: 'text', content: '当前作者修改' }] },
    { id: '2', blocks: [{ type: 'node', nodeId: '20' }] }];
  page._storyVars = { name: '当前变量' };
  page._storyVoices = { [NODE]: [{ who: 'me', text: '当前局声音' }] };
  for (const name of ['_claimThoughts', '_enterThemeChapter', '_resumeStory', 'startGame', '_themeChapterCommand']) page[name] = () => assert.fail(name + ' must not run when reading');
  for (const name of ['measureChapter', 'showChapterGate', 'playChapterAudio', 'finishChapterVideo']) page[name] = () => {};
  page.playChapterVideo = () => true;
  page._leadHolds = () => false;
  page._themePalette = value => value;
  page._chapterTime = () => '';
  page.closeChapterFull = () => { page._chapterReadCursor = null; page.data.chapterFull = false; };
  return { page, requests, stored, actor, closeChapterFull: config.closeChapterFull };
}

for (const liveId of ['2', 'missing', null]) test('closing history preserves the live chapter boundary: ' + liveId, () => {
  const { page, requests, stored, closeChapterFull } = pageFixture();
  const cursor = liveId && { liveId }, chapter = page.data.chapter, patches = [];
  page._chapterReadCursor = cursor;
  page.data.chapterFull = true;
  page.data.chapterGate.show = true;
  page.closeAlbum = page._stopStoryDust = () => {};
  const setData = page.setData;
  page.setData = patch => { patches.push(patch); setData(patch); };
  closeChapterFull.call(page);
  assert.equal(patches.length, 1, 'closing sends exactly one synchronous patch');
  assert.deepEqual(Object.keys(patches[0]).sort(), liveId === '2'
    ? ['chapter', 'chapterFull', 'chapterGate.show'] : ['chapterFull', 'chapterGate.show']);
  assert.equal(page.data.chapterFull, false);
  assert.equal(page.data.chapterGate.show, false);
  assert.equal(page._chapterReadCursor, liveId === 'missing' ? cursor : null);
  if (liveId === '2') {
    assert.equal(page.data.chapter.id, '2');
    assert.equal(page.data.chapter.blocks, page.data.chapterCards[1].blocks);
  } else assert.equal(page.data.chapter, chapter, 'missing live card must not overwrite the chapter');
  assert.deepEqual(requests, []);
  assert.deepEqual(stored, []);
});

test('snapshot whitelist keeps exact IDs, original results and deeply immutable copies', () => {
  const source = response(), saved = historyView.project(source, TARGET, '12');
  assert.equal(saved.nodes[0].nodeId, NODE);
  assert.equal(saved.nodes[0].results[0].gameSessionId, '9007199254740999');
  assert.equal(saved.nodes[0].results[0].score, 0);
  assert.equal(saved.nodes[0].results[0].outcomes.coinSide, 'TAILS');
  assert.equal(saved.nodes[0].results[0].outcomes.draws[0].content, '原抽签');
  assert.deepEqual(saved.chapters[0].blocks.map(block => block.type), ['text', 'node', 'divider', 'text']);
  assert.deepEqual(saved.chapters[0].blocks[2], { type: 'divider', key: 'line-1' });
  assert.equal(JSON.stringify(saved).includes('secret'), false);
  source.nodes[0].results[0].outcomes.diceRoll.pips[0] = 20;
  source.chapters[0].blocks[0].content = '作者后来改动';
  assert.deepEqual(saved.nodes[0].results[0].outcomes.diceRoll.pips, [1, 6]);
  assert.equal(saved.chapters[0].blocks[0].content, '原开场{name}');
  assert.throws(() => { saved.nodes[0].results.push({}); }, TypeError);
});

test('missing/invalid snapshot, unearned blocks and rounded IDs fail closed without current prose', () => {
  for (const overrides of [ { readOnly: false }, { routeSessionId: '2' }, { activityId: '1' }, { topicId: '7' },
    { configVersion: '13' }, { configVersion: null }, { routeSessionId: Number(SESSION) },
    { nodes: [{ ...response().nodes[0], nodeId: Number(NODE) }] },
    { chapters: [{ chapterId: CHAPTER, complete: true, blocks: [{ type: 'node', nodeId: '404' }] }] } ]) {
    assert.equal(historyView.project(response(overrides), TARGET, '12'), null);
  }
  const unavailable = historyView.project(response({ narrativeAvailable: false }), TARGET, '12');
  assert.deepEqual(unavailable.chapters, []);
  assert.equal(unavailable.nodes.length, 1, 'node receipts do not establish original chapter prose');
  assert.equal(historyView.scope('9', '0', '8', Number(SESSION)), null);
});

test('exact authenticated GET wires immutable history into existing chapter reading without live commands/bookmark writes', async () => {
  const { page, requests, stored } = pageFixture();
  const before = JSON.stringify({ nodes: page.data.nodes, total: page.data.total, done: page.data.doneCount });
  page.data.chapterFull = true;
  const pending = page._loadCompletedHistory();
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/play/history');
  assert.equal(requests[0].method, 'GET');
  assert.deepEqual(requests[0].data, { activityId: '0', topicId: '8', routeSessionId: SESSION });
  assert.notEqual(requests[0].auth, false, 'existing transport supplies Authorization');
  requests[0].success({ code: 200, data: response() });
  assert.equal(await pending, true);
  assert.deepEqual(page._precedingHistoryParas.map(item => item.text), ['原开场{name}', '原手记{name}', '原收尾']);
  assert.equal(page._nodeById(NODE), undefined);
  assert.equal(page.data.journalCards.find(card => card.nodeId === NODE).kvK, '已完成', 'history alone does not prove delisting');
  page.onPickChapter({ currentTarget: { dataset: { idx: 0 } } });
  assert.equal(page.data.chapter.complete, true);
  assert.equal(page.data.chapter.cover, '', 'current media is not original snapshot media');
  assert.deepEqual(page.data.chapterParas.map(item => item.text), ['原开场{name}', '原手记{name}', '原收尾']);
  assert.equal(JSON.stringify(page.data.chapterParas).includes('当前'), false);
  assert.equal(page.data.chapterParas.some(item => ['game', 'kit', 'journeyCheck'].includes(item.kind)), false);
  page.openStory({ currentTarget: { dataset: { id: NODE } } });
  assert.equal(page.data.story.text, '原节点正文{name}');
  assert.equal(JSON.stringify({ nodes: page.data.nodes, total: page.data.total, done: page.data.doneCount }), before);
  assert.deepEqual(stored, []);
  assert.equal(requests.length, 1, 'reading sends only the history GET');
});

for (const [name, change] of [
  ['actor', fixture => { fixture.actor.id = 10; }], ['login generation', fixture => { fixture.actor.epoch++; }],
  ['route session', fixture => { fixture.page._routeState.sessionId = '999'; }],
  ['topic', fixture => { fixture.page.data.topicId = '7'; }], ['activity', fixture => { fixture.page.data.activityId = '4'; }],
  ['load generation', fixture => { fixture.page._playLoadEpoch++; }],
  ['configVersion', fixture => { fixture.page._routeState.configVersion = '99'; }],
  ['disposed page', fixture => { fixture.page._playDisposed = true; }],
]) test('late history response rejected after changed ' + name, async () => {
  const fixture = pageFixture(), pending = fixture.page._loadCompletedHistory();
  change(fixture);
  fixture.requests[0].success({ code: 200, data: response() });
  assert.equal(await pending, false);
  assert.equal(fixture.page._completedHistory, undefined);
  assert.equal(fixture.page._historyNodes, undefined);
});

test('new request generation wins and original configVersion pin rejects a different snapshot', async () => {
  const { page, requests } = pageFixture();
  const old = page._loadCompletedHistory(), latest = page._loadCompletedHistory();
  requests[1].success({ code: 200, data: response() }); assert.equal(await latest, true);
  requests[0].success({ code: 200, data: response({ configVersion: '11' }) }); assert.equal(await old, false);
  const mismatch = page._loadCompletedHistory();
  requests[2].success({ code: 200, data: response({ configVersion: '13' }) }); assert.equal(await mismatch, false);
  assert.equal(page._completedHistory, null, 'a current invalid response makes history unavailable');
  const expected = page._loadCompletedHistory('14');
  assert.equal(await expected, false);
  assert.equal(requests.length, 3, 'same original session cannot be repinned to another configVersion');
});

test('missing snapshot remains unavailable to chapter picker and no preview/mock/unknown session query is guessed', async () => {
  const { page, requests } = pageFixture();
  page.data.nodes = [{ nodeId: NODE, chapterId: CHAPTER, done: true }];
  const current = page.data.chapter;
  const pending = page._loadCompletedHistory();
  requests[0].success({ code: 500, msg: '本局历史快照不可用' }); assert.equal(await pending, false);
  page.onPickChapter({ currentTarget: { dataset: { idx: 0 } } });
  assert.equal(page.data.chapter, current);
  for (const flag of ['isPreview', 'isMock']) {
    page.data[flag] = true; assert.equal(await page._loadCompletedHistory(), false); page.data[flag] = false;
  }
  page._routeState.sessionId = ''; assert.equal(await page._loadCompletedHistory(), false);
  assert.equal(requests.length, 1);
});

test('same-scope reload retains read cursor until verified, then revoked history closes without writing', async () => {
  const { page, requests, stored } = pageFixture();
  const first = page._loadCompletedHistory(); requests[0].success({ code: 200, data: response() }); await first;
  page.onPickChapter({ currentTarget: { dataset: { idx: 0 } } });
  const cursor = page._chapterReadCursor;
  const refresh = page._loadCompletedHistory();
  assert.equal(page._chapterReadCursor, cursor, 'requesting the same original session preserves the reading cursor');
  requests[1].success({ code: 200, data: response() }); assert.equal(await refresh, true);
  assert.equal(page._chapterReadCursor, cursor);
  const revoked = page._loadCompletedHistory(); requests[2].success({ code: 403, msg: '历史不可用' });
  assert.equal(await revoked, false);
  assert.equal(page._chapterReadCursor, null);
  assert.equal(page.data.chapterFull, false);
  assert.equal(page.data.chapter.id, '2');
  assert.deepEqual(page._historyNodes, []);
  assert.deepEqual(stored, []);
  assert.equal(requests.every(request => request.method === 'GET'), true);
});

test('history lookup rejects already-loaded facts after session/account change and journal never duplicates live nodes', async () => {
  const { page, requests, actor } = pageFixture(), pending = page._loadCompletedHistory();
  requests[0].success({ code: 200, data: response() }); await pending;
  assert.ok(page._historyNodeById(NODE));
  page.data.nodes.push({ nodeId: NODE, chapterId: CHAPTER, description: 'new', done: true });
  page.buildJournal();
  assert.equal(page.data.journalCards.filter(card => String(card.nodeId) === NODE).length, 1);
  assert.equal(page.data.journalCards.find(card => String(card.nodeId) === NODE).kvS, '原节点正文{name}');
  page._routeState.sessionId = '999'; assert.equal(page._historyNodeById(NODE), undefined);
  const displayed = page.data.chapter;
  page.onPickChapter({ currentTarget: { dataset: { idx: 0 } } });
  assert.equal(page.data.chapter, displayed, 'stale loaded snapshot cannot be selected in another session');
  page._routeState.sessionId = SESSION; actor.id = 10; assert.equal(page._historyNodeById(NODE), undefined);
});

test('an incomplete live node still wins over an older completed-history projection', async () => {
  const { page, requests } = pageFixture(), pending = page._loadCompletedHistory();
  requests[0].success({ code: 200, data: response() }); await pending;
  page.data.nodes = [{ nodeId: NODE, chapterId: CHAPTER, done: false, locked: true, playable: false }];
  const current = page.data.chapter;
  page.onPickChapter({ currentTarget: { dataset: { idx: 0 } } });
  assert.equal(page.data.chapter, current);
  assert.equal(page._chapterReadCursor, undefined);
});

test('missing original narrative never falls back to edited author chapter or media', async () => {
  const { page, requests } = pageFixture(), pending = page._loadCompletedHistory();
  requests[0].success({ code: 200, data: response({ narrativeAvailable: false }) }); await pending;
  const current = page.data.chapter;
  page.onPickChapter({ currentTarget: { dataset: { idx: 0 } } });
  assert.equal(page.data.chapter, current);
  assert.deepEqual(page._precedingHistoryParas, []);
  assert.equal(page._completedHistory.nodes[0].results[0].outcomes.coinSide, 'TAILS', 'available receipts remain independent of absent narrative');
});

test('preceding cards use frozen order without inventing order for missing chapters', () => {
  const saved = historyView.project(response(), TARGET, '12');
  assert.deepEqual(historyView.preceding(saved, '2', [{ id: '2' }]), [], 'an absent live chapter position does not establish preceding order');
  assert.deepEqual(historyView.preceding(saved, CHAPTER, [{ id: CHAPTER }]), [], 'selected historical chapter is not duplicated in its preceding list');
});
