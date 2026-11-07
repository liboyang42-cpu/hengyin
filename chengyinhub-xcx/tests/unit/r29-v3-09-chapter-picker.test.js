const { test } = require('node:test');
const assert = require('node:assert/strict');

test('V3-09: chapter selection reads server progress without gameplay or reward writes', async () => {
  const requests = [];
  const stored = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => 9,
    sendRequest: request => requests.push(request) });
  global.wx = { getStorageSync: () => '', setStorageSync: (...args) => stored.push(args), nextTick() {},
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }) };
  let config;
  global.Page = value => { config = value; };
  require('../../pages/play/index.js');
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  page.setData = (patch, callback) => {
    for (const [key, value] of Object.entries(patch)) {
      const path = key.replace(/\[(\d+)\]/g, '.$1').split('.');
      let target = page.data;
      while (path.length > 1) target = target[path.shift()];
      target[path[0]] = value;
    }
    if (callback) callback();
  };
  for (const method of ['showChapterGate', 'measureChapter', 'playChapterAudio', '_measureDeal',
    '_refreshNavRim', 'buildJournal', '_stopStoryDust', 'stopAudio']) page[method] = () => {};
  page.playChapterVideo = () => true;
  page._isPlayAccountCurrent = () => true;
  page._sessionKey = () => 'owner-9:topic-8';
  page._themePalette = value => value;
  page._chapterTime = () => '';
  page._routeState = { sessionId: 'run-1', routeMode: 'LINEAR', status: 'ACTIVE', thoughts: [] };
  page._hasGenericProofFlow = () => false;
  page._canContinueMerchantGames = () => false;
  page.computeNext = () => ({ nodeId: 20 });
  const cards = page.buildChapterCards({ chapters: [
    { chapterId: 1, name: '已完成章节', blocks: [
      { type: 'text', content: '第一章开场' }, { type: 'node', nodeId: 10 },
      { type: 'thought', thoughtKey: 'unclaimed' }, { type: 'text', content: '真实完成后文' },
    ] },
    { chapterId: 2, name: '当前章节', blocks: [
      { type: 'text', content: '第二章开场' }, { type: 'node', nodeId: 20, locationRequired: false },
      { type: 'text', content: '未解锁后文' },
    ] },
    { chapterId: 3, name: '未解锁章节', blocks: [{ type: 'node', nodeId: 30 }] },
  ] }, {});
  page.data.chapterCards = cards;
  page.data.chapter = page._chapterFromCard(cards[1]);
  page.data.nodes = [
    { nodeId: 20, chapterId: 2, done: false, locked: false, hasAdvanced: true },
    { nodeId: 30, chapterId: 3, done: false, locked: true, playable: false },
  ];
  page._historyNodes = [{ nodeId: 10, chapterId: 1, done: true, story: '已保存的手记' }];
  page.data.themeBag = { sessionId: 'theme-1', chapterIndexes: [0, 1] };
  page.data.playKit = { show: true, kit: { present: 'inline' } };
  page._inlineKit = () => true;
  page._playKitNodeId = 20;
  page._claimThoughts = () => assert.fail('browsing must not claim thoughts');
  page._themeChapterCommand = () => assert.fail('browsing must not send chapter commands');
  page._resumeStory = () => assert.fail('browsing must not reopen a saved game');
  page.startGame = () => assert.fail('browsing must not start a game');
  const before = JSON.stringify(page.data.nodes);
  const pick = idx => page.onPickChapter({ currentTarget: { dataset: { idx } } });
  const { chapterReadState } = require('../../pages/play/utils/chapter-trail.js');
  assert.equal(chapterReadState(cards[0], page.data.nodes, []), 'locked', 'missing history is not completion');
  assert.equal(chapterReadState(cards[0], [{ nodeId: 10, chapterId: 1, done: false, locked: true }], page._historyNodes),
    'locked', 'current incomplete progress wins over stale history');
  assert.equal(chapterReadState({ id: 99, blocks: [] }, [], []), 'locked', 'empty data does not prove access');
  assert.equal(chapterReadState({ id: 1, blocks: [{ type: 'node', nodeId: 99 }] }, [], page._historyNodes),
    'locked', 'missing authored node facts cannot turn a history-only chapter into a playable chapter');
  assert.equal(chapterReadState({ id: 0, opening: true, complete: true }, [], []), 'completed');
  page.openStackOrChapters();
  assert.equal(page.data.chapterPick.cur, 1, 'picker highlight follows the displayed chapter');

  pick(0);
  assert.equal(page.data.chapter.id, 1, 'select the chosen chapter, not just a picker highlight');
  assert.equal(page.data.chapter.complete, true, 'completion comes from the server-projected history');
  assert.deepEqual(page.data.chapterParas.map(p => p.text), ['第一章开场', '已保存的手记', '真实完成后文']);
  assert.equal(page.data.chapterParas.some(p => ['game', 'kit', 'journeyCheck'].includes(p.kind)), false);
  page.rebuild();
  assert.equal(page.data.chapter.id, 1, 'a live rebuild must preserve the selected chapter');
  page._enterThemeChapter();
  await page._endThemeChapter();
  page._saveReadingPos({ i: 1 });
  config._resumeStory.call(page);
  page.continueChapterGate();
  page._syncStoryScene = () => {};
  global.wx.createSelectorQuery = () => {
    const query = { in: () => query, select: () => query, selectAll: () => query,
      boundingClientRect: () => query,
      exec: callback => callback([{ top: 0, bottom: 600, height: 600 },
        page.data.chapterParas.map((_, i) => ({ top: 100 + i * 60, bottom: 140 + i * 60, height: 40 }))]) };
    return query;
  };
  page.data.reducedMotion = true;
  page._settleStory();
  assert.equal(page._resumeTried, undefined, 'history does not consume the live resume attempt');
  assert.deepEqual(stored, [], 'history does not replace the live reading bookmark or gates');
  page.data.chapterVideo = { show: true, token: 7 };
  page._chapterVideoToken = 7;
  page._chapterVideoScopeKey = page._chapterVideoScope();
  const audio = page.playChapterAudio;
  page.playChapterAudio = () => assert.fail('stale history video end must not resume current audio');
  page.startStoryGame({ currentTarget: { dataset: { nodeid: 10 } } });
  await page.continueChapterStory();
  page.finishChapterVideo({ currentTarget: { dataset: { token: 7 } } });
  page.playChapterAudio = audio;
  assert.equal(page.data.chapterFull, false);
  assert.equal(page.data.chapter.id, 2, 'leaving chapter reading restores the live chapter');

  let resumed = 0, entered = 0, started = 0;
  page._resumeStory = () => { resumed += 1; };
  page._enterThemeChapter = () => { entered += 1; };
  page._inlineKit = () => false;
  page.data.playKit = { show: false };
  page.startGame = node => { started = node.nodeId; };
  page._withLookTransition = action => action();
  pick(1);
  assert.equal(page.data.chapter.id, 2);
  assert.equal(page.data.chapter.complete, false);
  assert.equal(page._chapterReadCursor, null, 'the current playable chapter keeps its ordinary entry path');
  assert.equal(resumed, 1);
  assert.equal(entered, 1);
  assert.equal(page.data.chapterParas.some(p => p.kind === 'game' && p.nodeId === 20), true);
  assert.equal(page.data.chapterParas.some(p => p.text === '未解锁后文'), false);
  const selected = page.data.chapter;
  for (const invalid of [2, -1, 99, 'bad', '', null]) pick(invalid);
  assert.equal(page.data.chapter, selected, 'locked and invalid picks cannot change selection');
  page.startStoryGame({ currentTarget: { dataset: { nodeid: 20 } } });
  assert.equal(started, 20, 'the current chapter can still start its unfinished game');
  assert.equal(JSON.stringify(page.data.nodes), before);
  assert.deepEqual(requests, []);

  pick(0);
  page._routeState.sessionId = 'run-2';
  page.rebuild();
  assert.equal(page._chapterReadCursor, null, 'the cursor must not cross a route session');
  assert.equal(page.data.chapter.id, 2);
  page._isPlayAccountCurrent = () => false;
  pick(0);
  assert.equal(page.data.chapter.id, 2, 'an old account page cannot select a historical chapter');
});
