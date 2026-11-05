// 第十三轮 m1 P1:互斥组(线性路线里两站只能完成一站)的第二站玩家到不了、打不开、走完后屏幕空白。
// 行为测试直接跑 pages/play/index.js 的真实方法(与 k10-immersive-audio 同一套最小宿主)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')

function loadPlay() {
  const requests = []
  global.getApp = () => ({
    globalData: { user_id: 9, statusBarHeight: 20, navBarHeight: 44, features: {} },
    isDevEnv: () => false, getUserID: () => 9, getAvatar: () => '',
    sendRequest: (options) => { requests.push(options) },
  })
  const storage = new Map()
  global.wx = {
    getStorageSync: key => storage.get(key) || '', setStorageSync(key, value) { storage.set(key, value) },
    getWindowInfo: () => ({ statusBarHeight: 20, windowWidth: 375, screenHeight: 812, safeArea: { bottom: 800 } }),
    getSystemInfoSync: () => ({ statusBarHeight: 20, windowWidth: 375, screenHeight: 812, safeArea: { bottom: 800 } }),
    getMenuButtonBoundingClientRect: () => ({ left: 300 }),
    showLoading() {}, hideLoading() {}, showToast() {}, setKeepScreenOn() {},
    createSelectorQuery: () => ({ select: () => ({ boundingClientRect: () => ({ exec() {} }) }) }),
  }
  let config = null
  global.Page = (cfg) => { config = cfg }
  const abs = path.join(ROOT, 'pages/play/index.js')
  const src = fs.readFileSync(abs, 'utf8')
  const dir = path.dirname(abs)
  const localRequire = (id) => require(id.startsWith('.') ? path.resolve(dir, id) : id)
  new Function('Page', 'getApp', 'wx', 'require', 'module', 'exports', src)(global.Page, global.getApp, global.wx, localRequire, { exports: {} }, {})
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) })
  page.setData = (patch, cb) => {
    Object.keys(patch || {}).forEach((key) => {
      const parts = key.split('.'); let o = page.data
      for (let i = 0; i < parts.length - 1; i++) { o[parts[i]] = o[parts[i]] || {}; o = o[parts[i]] }
      o[parts[parts.length - 1]] = patch[key]
    })
    if (cb) cb()
  }
  return { page, requests }
}


function setup() {
  const host = loadPlay(); const p = host.page;
  p._playOwner = '9'; p._isPlayAccountCurrent = () => true; p._boundPlayOwner = () => '9';
  p._sessionKey = () => 'topic:7'; p._routeState = { routeMode: 'LINEAR', sessionId: '7', version: 1, status: 'ACTIVE', nodeStates: {} };
  p.data.topicId = 7; p.data.mode = 1; p.data.chapterFull = true;
  p.data.chapter = { id: 50, blocks: [{ type: 'node', nodeId: 85 }, { type: 'node', nodeId: 86 }] };
  p.data.nodes = [85, 86].map(nodeId => ({ nodeId, chapterId: 50, vm: 5, hasAdvanced: true, done: false }));
  p._routeNodeCatalog = p.data.nodes;
  p.data.themeBag = { sessionId: '7', runtimeScope: 'PLAYER', quests: [{ nodeId: 85, questKey: 'a' }], actionRows: [] };
  p.refreshThemeBackpack = () => Promise.resolve(true); p._productionRebuild = p.rebuild; p.rebuild = () => {};
  p._autoStationVoice = () => {}; p._syncStationBgm = () => {}; p._stopStationBgm = () => {};
  p._onKaccha = () => {}; p._restartFailedQuest = () => {}; p._applyR5PlainOutcome = () => {};
  p._productionChapterAudio = p.playChapterAudio;
  p.showChapterGate = () => {}; p.playChapterVideo = () => false; p.playChapterAudio = () => {}; p._enterThemeChapter = () => {};
  p.measureChapter = () => {}; p._resumeStory = () => {}; p._syncStoryScene = () => {};
  p.stopAudio = () => {}; p.loadData = () => { p.nodeReloads = (p.nodeReloads || 0) + 1; };
  return host;
}

test('同页结算站点清理已结算玩法并重读节点正文,后续轮询不重复触发', () => {
  const { page: p } = setup();
  p._playKitNodeId = 85;
  p.data.advancedPlay = { show: true, node: p.data.nodes[0] };
  p.data.playKit = { show: false, kit: { type: 'qa', present: 'inline', sessionId: 1 } };
  p._applyRouteState({ routeMode: 'LINEAR', sessionId: '7', version: 2, status: 'ACTIVE', nodeStates: { 85: 'COMPLETED', 86: 'PLAYABLE' } });
  assert.equal(p.data.advancedPlay.show, false, '已结算局不得继续隐藏故事流');
  assert.equal(p.data.playKit.kit, null);
  assert.equal(p.nodeReloads, 1, '结算后读取服务端新解锁的正文');
  p._applyRouteState({ routeMode: 'LINEAR', sessionId: '7', version: 2, status: 'ACTIVE', nodeStates: { 85: 'COMPLETED', 86: 'PLAYABLE' } });
  assert.equal(p.nodeReloads, 1);
});

test('末站结算后任务列表可为空,仍按真实任务局同步完成事实', () => {
  const { page: p } = setup();
  p.data.themeBag = { sessionId: '7', runtimeScope: 'PLAYER', status: 'COMPLETED', quests: [], actionRows: [] };
  p._applyRouteState({ routeMode: 'LINEAR', sessionId: '7', version: 3, status: 'COMPLETED', nodeStates: { 85: 'COMPLETED', 86: 'COMPLETED' } });
  assert.ok(p.data.nodes.every(n => n.done));
  assert.equal(p.nodeReloads, 1, '末站也必须进入 loadData 的真实结局链');
});

test('上一站仍有 kit 段时打开下一站,新题接在本站而非已完成站的空壳', () => {
  const { page: p } = setup();
  p.data.nodes[0].done = true;
  p._playKitNodeId = 85; p.data.chapterParas = [{ kind: 'kit', nodeId: 85 }];
  p.startGame(p.data.nodes[1]);
  const view = { nodeId: 86, sessionId: 2, version: 0, present: 'inline', config: {}, readyForBase: false,
    playKit: { qa: { mode: 'TYPE', title: '下一站题目', passed: false, finished: false } } };
  p.selectComponent = () => ({ data: { state: view } });
  p.onAdvancedSession({ detail: view });
  assert.equal(p.data.playKit.kit.title, '下一站题目');
  assert.deepEqual(p.data.chapterParas.filter(x => x.kind === 'kit').map(x => x.nodeId), [86]);
});

test('开始任务的迟到成功不能在账号或原局变化后再打开站点', async () => {
  for (const change of [p => { p._playOwner = '10'; }, p => { p._sessionKey = () => 'topic:8'; }]) {
    const { page: p } = setup();
    p.data.themeBag.actionRows = [{ kind: 'QUEST', nodeId: 86, key: 'b', operation: 'QUEST_START', enabled: true }];
    let resolve; p._themeGameplayCommand = () => new Promise(done => { resolve = done; });
    const pending = p.startGame(p.data.nodes[1]);
    change(p); resolve(true); await pending;
    assert.equal(p.data.advancedPlay.show, false);
  }
});

test('重复完成入口在结局读取和结局章期间不抢先打开通关卡', () => {
  const { page: p } = setup();
  p._endTeamTrail = () => {}; p._endRunSession = () => {};
  let endingReads = 0, finishCards = 0;
  p._loadLifeEnding = () => { endingReads++; };
  p.loadEnding = () => { finishCards++; throw new Error('finish overtook ending'); };
  p.openFinish();
  assert.doesNotThrow(() => p.openFinish());
  assert.equal(endingReads, 1); assert.equal(finishCards, 0);
});

test('结局投影晚到其它局时不能打开结局章', async () => {
  const { page: p, requests } = setup();
  let ending = 0; p._playEndingChapter = () => { ending++; };
  p._loadLifeEnding(() => {});
  p._routeState = { ...p._routeState, sessionId: '8' };
  requests.at(-1).success({ code: 200, data: { title: '尾声', chapter: { id: 51, blocks: [{ type: 'text', content: '终' }] } } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(ending, 0);
});

test('任务线性路线下一站只认服务端推荐,未选的互斥站不能继续当目标', () => {
  const { page: p } = setup();
  p.data.nodes = [
    { nodeId: 85, done: true, sortId: 1 },
    { nodeId: 86, done: false, sortId: 2, locked: true },
    { nodeId: 87, done: false, sortId: 3 },
  ];
  p._routeState = { ...p._routeState, currentNodeId: 87, recommendedNodeId: 87,
    nodeStates: { 85: 'COMPLETED', 86: 'DISCOVERED_LOCKED', 87: 'PLAYABLE' } };
  assert.equal(p.computeNext(p.data.nodes).nodeId, 87);
  p._routeState = { ...p._routeState, status: 'COMPLETED', currentNodeId: null, recommendedNodeId: null };
  assert.equal(p.computeNext(p.data.nodes), null);
});

test('互斥局完成横幅只数实际完成站,不把未选锁定站说成已点亮', () => {
  const { page: p } = setup();
  p._applyRouteState({ routeMode: 'LINEAR', sessionId: '7', version: 2, status: 'COMPLETED',
    nodeStates: { 85: 'COMPLETED', 86: 'DISCOVERED_LOCKED' } });
  p._refreshNavRim = () => {}; p._measureDeal = () => {};
  global.wx.nextTick = cb => cb(); p._productionRebuild();
  assert.equal(p.data.allDone, true, '局完成仍只认服务端完成事实');
  assert.equal(p.data.doneCount, 1); assert.equal(p.data.total, 2);
  assert.equal(p.data.nodes[1].done, false, '未选站保持未完成');
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxml'), 'utf8');
  const expression = wxml.match(/<view class="b1">\{\{(.*?)\}\}/)[1];
  const text = new Function('mode', 'total', 'doneCount', 'return (' + expression + ')');
  assert.equal(text(p.data.mode, p.data.total, p.data.doneCount), '1处旧址已点亮');
  assert.equal(text(2, p.data.total, p.data.doneCount), '本轮探索已完成');
});

function finishHost(p) {
  p.data.reducedMotion = true; p.data.allDone = true;
  p.data.nodes.forEach(n => { n.done = true; });
  for (const key of ['_endTeamTrail', '_endRunSession', 'loadEnding', 'loadNewLifeOS', '_triggerCelebration',
    'paintRouteThumb', 'buildReview', '_startRouteCard', '_openMedalScreen', 'loadMilestone', 'loadStageEnd',
    'loadFinishRouteRecommendation']) p[key] = () => {};
  p._openFinale = () => true;
  p.closeChapterFull = () => p.setData({ chapterFull: false });
  p._showEnding = value => p.setData({ previewEnding: value });
}

test('真实同页结局链:服务端结局章含视频→读章→结局摘要→通关卡,无重载', async () => {
  const { page: p, requests } = setup(); finishHost(p);
  const videoChapters = []; p.playChapterVideo = () => { videoChapters.push(p.data.chapter.id); return false; };
  p.openFinish();
  requests.at(-1).success({ code: 200, data: { title: '雨停了', archive: {}, chapter: {
    id: 51, name: '尾声', blocks: [{ type: 'text', content: '天亮之前' }], videoUrl: '/profile/ending.mp4' } } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(p.data.chapter.id, 51); assert.equal(p.data.chapter.ending, true);
  assert.ok(videoChapters.includes(51)); assert.equal(p.data.showFinish, false);
  p.openFinish(); assert.equal(p.data.showFinish, false, '重复回读不能越过结局章');
  await p.continueChapterStory();
  assert.equal(p.data.previewEnding.show, true); assert.equal(p.data.showFinish, false);
  p.continueLifeEnding();
  assert.equal(p.data.showFinish, true); assert.equal(p.data.previewEnding.show, false);
});

test('末站背包结算先读作者反馈,继续后收起任务层再播放结局章', async () => {
  const { page: p, requests } = setup(); finishHost(p);
  p.data.themeBackpackOpen = true; p.data.sessionToolsOpen = true;
  p.data.r5Verdict = { show: true, text: '年份正确，两站任务完成。' };
  p.data.sheet = { show: true, node: p.data.nodes[1] };
  p.data.advancedPlay = { show: true, node: p.data.nodes[1] };
  p.data.playKit = { show: true, kit: { type: 'qa', present: 'inline', nodeId: 86 } };
  p._playKitNodeId = 86;
  const starts = [];
  p.playChapterVideo = () => {
    starts.push({ chapter: p.data.chapter.id, bag: p.data.themeBackpackOpen,
      feedback: p.data.r5Verdict.show, game: p.data.advancedPlay.show,
      kit: p.data.playKit.show, sheet: p.data.sheet.show, tools: p.data.sessionToolsOpen });
    return true;
  };
  p.openFinish();
  requests.at(-1).success({ code: 200, data: { title: '尾声', chapter: {
    id: 51, name: '尾声', blocks: [{ type: 'text', content: '两站之后' }], videoUrl: '/profile/ending.mp4' } } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(p.data.chapter.id, 50, '成功反馈未读完时不先在背包下面播放结局');
  assert.equal(p.data.r5Verdict.show, true); assert.equal(starts.length, 0);
  p.openFinish(); assert.equal(p.data.showFinish, false, '等待反馈仍受同局结局围栏保护');
  p.closeR5Verdict();
  assert.deepEqual(starts, [{ chapter: 51, bag: false, feedback: false,
    game: false, kit: false, sheet: false, tools: false }]);
  assert.equal(p._playKitNodeId, null);
  p.closeR5Verdict(); assert.equal(starts.length, 1, '重复继续不重播结局');
});

for (const change of ['owner', 'session']) {
  test('等待末站反馈时' + change + '变化不能播放旧结局', () => {
    const { page: p } = setup();
    p.data.r5Verdict = { show: true }; p.data.themeBackpackOpen = true;
    let opened = 0; p.openChapterFull = () => { opened++; };
    p._playEndingChapter({ id: 51, blocks: [] }, () => {});
    if (change === 'owner') p._playOwner = '10';
    else p._routeState.sessionId = '8';
    p.closeR5Verdict();
    assert.equal(opened, 0); assert.equal(p.data.chapter.id, 50);
  });
}

for (const reply of [{ code: 200, data: {} }, { code: 500, msg: '读取失败' }]) {
  test('无结局或读取失败时仍进入通关卡:' + reply.code, async () => {
    const { page: p, requests } = setup(); finishHost(p);
    p.openFinish(); requests.at(-1).success(reply);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(p.data.showFinish, true);
    assert.equal(p._lifeEndingPendingScope, '');
  });
}


test('结束章已在读时后台路线刷新不能退回上一章未读收尾', () => {
  const { page: p } = setup();
  p._afterEndingChapter = () => {};
  p._lastActiveChapterId = 50;
  p.data.chapter = { id: 51, ending: true, blocks: [{ type: 'text', content: '尾声' }] };
  p.data.chapterCards = [{ id: 50, blocks: [{ type: 'node', nodeId: 85 }, { type: 'text', content: '上章收尾' }] }];
  p.data.nodes = [{ nodeId: 85, chapterId: 50, done: true }];
  p._routeState = { ...p._routeState, status: 'COMPLETED', currentNodeId: null, recommendedNodeId: null };
  p._refreshNavRim = () => {}; p._measureDeal = () => {};
  global.wx.nextTick = cb => cb();
  p._productionRebuild();
  assert.equal(p.data.chapter.id, 51); assert.equal(p._chapterTrailNext, null);
});


test('纯音频尾声空blocks无视频也进入结局章,重铺只自动播放一次', async () => {
  const { page: p, requests } = setup(); finishHost(p);
  let plays = 0;
  p._audio = { stop() {}, play() { plays++; } }; p.initAudio = () => {};
  p.playChapterAudio = p._productionChapterAudio;
  p.openFinish();
  requests.at(-1).success({ code: 200, data: { title: '雨停了', chapter: {
    id: 52, name: '纯音频尾声', blocks: [], audioUrl: '/profile/ending-audio.mp3' } } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(p.data.chapter.id, 52);
  assert.equal(plays, 1);
  p.openChapterFull(); assert.equal(plays, 1);
});


test('当前局开始任务成功后自动打开本站,业务拒绝不打开', async () => {
  for (const accepted of [true, false]) {
    const { page: p } = setup();
    p.data.themeBag.actionRows = [{ kind: 'QUEST', nodeId: 86, key: 'b', operation: 'QUEST_START', enabled: true }];
    p._themeGameplayCommand = () => Promise.resolve(accepted);
    await p.startGame(p.data.nodes[1]);
    assert.equal(p.data.advancedPlay.show, accepted);
    if (accepted) assert.equal(p.data.advancedPlay.node.nodeId, 86);
  }
});
