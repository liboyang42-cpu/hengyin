'use strict';

// Synthetic transport/SDK fixtures only: no native, phone, order or reward acceptance.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const memoryStorage = require('../helpers/memory-storage.js');
const storyResume = require('../../pages/play/utils/story-resume.js');
const { normalizeRouteState } = require('../../pages/play/utils/play-route-state.js');
const SESSION = '9007199254740993';
const PLAY = '../../pages/play/index.js';
const tick = async () => { for (let i = 0; i < 8; i++) await new Promise(setImmediate); };
const event = (key, value) => ({ currentTarget: { dataset: { [key]: value } } });

function fixture(image = 'https://assets.example.test/original-question.jpg') {
  const storage = memoryStorage(), requests = [], pending = [], previews = [], snapshots = [];
  const scope = { memberId: '9', activityId: '', topicId: '8' };
  let phase = 0, definition, revised = false;
  const chapters = [101, 202].map((chapterId, i) => ({ chapterId, name: '合成章' + (i + 1), blocks: [
    { type: 'text', content: '原开场' + chapterId },
    { type: 'node', nodeId: i ? 22 : 11, locationRequired: false },
    { type: 'text', content: '原收尾' + chapterId },
  ] }));
  const route = () => ({ routeMode: 'LINEAR', sessionId: SESSION, configVersion: '12', version: phase,
    status: phase === 2 ? 'COMPLETED' : 'ACTIVE', currentNodeId: [11, 22, null][phase],
    recommendedNodeId: [11, 22, null][phase],
    nodeStates: { 11: phase ? 'COMPLETED' : 'PLAYABLE', 22: phase === 2 ? 'COMPLETED' : phase ? 'PLAYABLE' : 'DISCOVERED_LOCKED' },
  });
  const nodes = () => [
    { nodeId: 11, chapterId: 101, sortId: 9, name: '合成选项题', validationMethod: 3,
      question: '选择图中的线索', questionImg: image, options: { A: '甲', B: '乙' },
      feedbackText: '第一题回执', storyText: '原手记11', done: phase > 0 },
    { nodeId: 22, chapterId: 202, sortId: 1, name: '合成输入题', validationMethod: 1,
      question: '输入线索', feedbackText: '第二题回执', storyText: '原手记22', done: phase === 2 },
  ];
  const history = () => ({ routeSessionId: SESSION, activityId: '0', topicId: '8', configVersion: '12',
    readOnly: true, narrativeAvailable: true, chapters: chapters.slice(0, phase).map(c => ({ ...c, complete: true })),
    nodes: nodes().filter(n => n.done).map(n => ({ ...n, history: true,
      results: [{ kind: 'routeReceipt', receiptId: 'receipt-' + n.nodeId, passed: true, outcomeCode: 'PASSED' }],
    })),
  });
  const respond = (request, response) => {
    const before = JSON.stringify(response);
    snapshots.push({ response, before });
    request.success(response);
  };
  const app = { globalData: {}, getUserID: () => '9', sendRequest(request) {
    requests.push(request);
    if (request.url === '/api/play/nodes') return respond(request, { code: 200, data: {
      registered: true, playable: true, selfPlay: true, topicId: '8', topicName: '合成双章旅程', mode: 1,
      routeState: route(), nodes: nodes().map(n => revised ? { ...n, storyText: '当前作者改写' } : n),
      chapters: structuredClone(chapters).map(c => revised ? { ...c,
        blocks: c.blocks.map(b => b.type === 'text' ? { ...b, content: '当前作者改写' } : b) } : c),
    } });
    if (request.url === '/api/play/history') return respond(request, { code: 200, data: history() });
    if (request.url === '/api/play/action-context') return respond(request, { code: 200, data: {
      routeSessionId: SESSION, expectedRouteVersion: phase, runtimeInstance: 'synthetic-r30', authorityEpoch: 1,
    } });
    if (request.url === '/api/play/answer' && request.method === 'POST') { pending.push(request); return; }
    throw new Error('Unexpected fixture transport: ' + request.method + ' ' + request.url);
  } };
  global.getApp = () => app;
  global.wx = { ...storage, nextTick: callback => callback(), vibrateShort() {}, setKeepScreenOn() {},
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    previewImage(options) { previews.push(options); if (image.includes('missing')) options.fail?.({ errMsg: 'synthetic missing image' }); },
  };
  global.Page = value => { definition = value; };
  delete require.cache[require.resolve('../../pages/play/utils/play-route-state.js')];
  delete require.cache[require.resolve(PLAY)];
  require(PLAY);
  function page() {
    // Same Page capture + dotted setData harness as r29-f05/r29c; real business methods stay bound.
    const p = Object.assign({}, definition, { data: structuredClone(definition.data) });
    Object.assign(p.data, { topicId: '8', reducedMotion: true });
    p._routeState = normalizeRouteState(null);
    p.setData = function (patch, callback) {
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.');
        let target = this.data;
        for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {});
        target[parts.at(-1)] = value;
      }
      callback?.();
    };
    // Exclude unrelated modules and visual effects, as in the existing cursor harness.
    for (const name of ['_probeCasebook', '_ensureSessionClock', '_syncIdleStats', '_syncRunState',
      '_restoreRunSession', 'loadTeamProgress', '_loadPlayerGameModule', 'refreshThemeBackpack',
      '_startThemeBgm', 'loadHudCharacter', 'loadSessionStats', '_refreshNavRim', '_measureDeal',
      'measureChapter', '_stopStoryDust', '_triggerCelebration', '_animateCompletedSegment']) p[name] = () => {};
    p.openFinish = () => { p.finishEntries = (p.finishEntries || 0) + 1; };
    p._withLookTransition = action => action();
    return p;
  }
  return { page, requests, pending, previews, storage, scope, route, snapshots,
    reviseLive() { revised = true; },
    async enter() { const p = page(); p.loadData(true); await tick(); assert.equal(p._loadCommitted, true); return p; },
    async accept() {
      assert.equal(pending.length, 1, 'only one transport write while the answer is in flight');
      const request = pending.shift(); phase++;
      respond(request, { code: 200, intentTerminal: true, data: { nodeId: request.data.nodeId,
        firstTime: true, done: phase, completed: phase === 2, routeState: route(), xp: 0 } });
      await tick();
    },
    assertSourcesUnchanged() { for (const { response, before } of snapshots) assert.equal(JSON.stringify(response), before); },
  };
}

async function openFirst(f) {
  const p = await f.enter();
  assert.equal(p.data.nextNode.nodeId, 11, 'server cursor beats the appendix presentation order');
  assert.equal(p.data.chapter.id, 101);
  assert.equal(p.data.storyIntro.show, true);
  p.startStoryIntro();
  p.continueChapterGate();
  assert.equal(p.data.chapterParas.some(row => row.text === '原收尾101'), false, 'unearned prose remains hidden');
  p.startStoryGame(event('nodeid', 11));
  assert.equal(p.data.game.vm, 3);
  assert.equal(p.data.game.nodeId, 11);
  return p;
}

test('synthetic two-chapter journey: fresh choice, duplicate guard, saved resume, input, completion and frozen replay', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const f = fixture();
  let p = await openFirst(f);
  p.previewQuestionImg();
  assert.deepEqual(f.previews[0], { urls: [p.data.game.qImg], current: p.data.game.qImg });
  assert.equal(p.data.game.qImg, 'https://assets.example.test/original-question.jpg');
  assert.equal(storyResume.read(f.scope).openNodeId, '11');
  const before = storyResume.read(f.scope);
  p = await f.enter();
  p.continueChapterGate();
  assert.equal(p.data.storyIntro.show, false, 'seen introduction is not replayed');
  assert.equal(p.data.game.nodeId, 11, 'unfinished saved game reopens through _resumeStory -> startStoryGame');
  assert.deepEqual(storyResume.read(f.scope), before);
  p.selOpt(event('k', 'B'));
  p.submitGame(); p.submitGame();
  await tick();
  assert.equal(f.pending.length, 1, 'duplicate answer must not issue another POST: ' + p.data.gNetErr);
  const first = f.pending[0].data;
  assert.equal(first.answer, 'B');
  assert.equal(first.routeSessionId, SESSION);
  assert.equal(first.expectedRouteVersion, 0);
  assert.ok(first.routeActionId);
  assert.equal(p.data.doneCount, 0, 'client cannot complete before the terminal server receipt');
  await f.accept();
  p.continueAfterRight();
  await tick();
  assert.equal(p.data.doneCount, 1);
  assert.equal(p.data.nextNode.nodeId, 22);
  assert.equal(p.data.chapter.id, 101, 'read first chapter tail before switching');
  assert.equal(p.data.chapterParas.some(row => row.text === '原收尾101'), true);
  await p.continueChapterStory();
  p.continueChapterGate();
  assert.equal(p.data.chapter.id, 202);
  p._saveReadingPos(p.data.chapterParas.find(row => row.kind === 'game'));
  assert.equal(storyResume.read(f.scope).trails['101'], true);
  const saved = storyResume.read(f.scope);
  f.reviseLive();
  p = await f.enter();
  p.continueChapterGate();
  assert.equal(p.data.chapter.id, 202);
  assert.equal(p.data.chapterGameAnchor, 'chapter-game-22');
  assert.deepEqual(storyResume.read(f.scope), saved, 'partial resume keeps original reading/gate/trail state');
  assert.equal(p._currentCompletedHistory().routeSessionId, SESSION);
  assert.deepEqual(p._precedingHistoryParas.map(row => row.text), ['原开场101', '原手记11', '原收尾101']);
  p.startStoryGame(event('nodeid', 22));
  assert.equal(p.data.game.vm, 1);
  assert.equal(p.data.game.qImg, '', 'second game cannot inherit the first question image');
  assert.equal(p.data.answerSel, '', 'choice state is cleared when entering input game');
  p.onInput({ detail: { value: '合成输入' } });
  p.submitGame(); p.submitGame();
  await tick();
  assert.equal(f.pending.length, 1, 'input duplicate must not issue another POST');
  assert.equal(f.pending[0].data.answer, '合成输入');
  assert.equal(f.pending[0].data.expectedRouteVersion, 1);
  assert.equal(f.pending[0].data.routeSessionId, SESSION);
  assert.notEqual(f.pending[0].data.routeActionId, first.routeActionId);
  await f.accept();
  p.continueAfterRight();
  await tick();
  assert.equal(p.data.allDone, true);
  assert.equal(p.data.doneCount, 2);
  const writes = () => f.requests.filter(r => r.method === 'POST');
  assert.equal(writes().length, 2);
  p = await f.enter();
  assert.equal(p.data.allDone, true);
  assert.equal(p.finishEntries, 1, 'completion reentry goes to the existing finish entry');
  assert.equal(p.data.nextNode, null);
  assert.equal(p.data.storyIntro.show, false);
  const state = JSON.stringify({ nodes: p.data.nodes, route: p._routeState });
  const bookmark = storyResume.read(f.scope), reads = f.requests.length;
  p.onPickChapter(event('idx', 0));
  assert.equal(p.data.chapter.complete, true);
  assert.deepEqual(p.data.chapterParas.map(row => row.text), ['原开场101', '原手记11', '原收尾101']);
  assert.equal(p.data.chapterParas.some(row => ['game', 'kit', 'journeyCheck'].includes(row.kind)), false);
  assert.equal(p._historyNodeById(11).results[0].receiptId, 'receipt-11');
  p.startStoryGame(event('nodeid', 11));
  p._saveReadingPos({ i: 1 });
  await p.continueChapterStory();
  assert.equal(f.requests.length, reads, 'history replay sends no writes or additional game transport');
  assert.equal(JSON.stringify({ nodes: p.data.nodes, route: p._routeState }), state);
  assert.deepEqual(storyResume.read(f.scope), bookmark);
  for (const r of f.requests.filter(r => r.url === '/api/play/history')) {
    assert.equal(r.method, 'GET');
    assert.deepEqual(r.data, { activityId: '0', topicId: '8', routeSessionId: SESSION });
  }
  f.assertSourcesUnchanged();
});

for (const image of ['', 'https://assets.example.test/missing-original.jpg', '/images/merchant-games/unknown-original.jpg']) {
  test('synthetic journey preserves absent/bad original question source without replacement: ' + (image || '(absent)'), async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const f = fixture(image), p = await openFirst(f);
    p.previewQuestionImg();
    assert.equal(p.data.game.qImg, image);
    assert.equal(f.previews.length, image ? 1 : 0);
    if (image) assert.deepEqual(f.previews[0].urls, [image], 'SDK failure cannot invent a replacement original');
    p.selOpt(event('k', 'A')); p.submitGame();
    await tick();
    assert.equal(f.pending.length, 1, 'missing image does not forge completion or block an explicit answer: ' + p.data.gNetErr);
    assert.equal(p.data.doneCount, 0);
    assert.equal(f.pending[0].data.answer, 'A');
    assert.equal(f.pending[0].data.routeSessionId, SESSION);
    f.assertSourcesUnchanged();
  });
}
