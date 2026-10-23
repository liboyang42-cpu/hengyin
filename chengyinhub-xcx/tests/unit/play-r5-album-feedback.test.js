const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const { createUploadClient } = require('../../utils/transport/upload-client.js');
const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js');
const { canContinue } = require('../../pages/play/utils/playkit-r5-segments.js');

// DTO 真源：AdvancedGameRuntimeServiceImpl.toView/publicBranch/playKitRuntime；
// SUBMIT_ALBUM 与反馈状态来自 AdvancedGameRuntimeServiceImplTest、ThemeRuntimeMySqlIT 的 ALBUM/qaFeedback 用例。
const PLAYER_PHOTO = 'http://127.0.0.1/profile/player-evidence.jpg';
const AUTHOR_PHOTO = 'http://127.0.0.1/profile/author-illustration.jpg';
function albumView(done = false) {
  return { sessionId: 51, nodeId: 29, version: done ? 1 : 0, ownerType: 1,
    status: done ? 'COMPLETED' : 'RUNNING', present: 'fullscreen', readyForBase: done,
    config: { schemaVersion: 1, outcomeRulesVersion: 1 },
    playKit: { album: { images: done ? [PLAYER_PHOTO] : [{ url: AUTHOR_PHOTO, line: '作者示意' }], done } } };
}
function feedbackView({ writable = true, ended = false, terminal = false, step = true, legacy = false, present = 'fullscreen' } = {}) {
  const currentStep = { id: terminal ? 'done' : 'feedback', title: '反馈', body: '选择接下来的行动',
    terminal, options: terminal ? [] : [{ id: 'continue', label: '继续' }] };
  return { sessionId: 51, nodeId: 29, version: 1, ownerType: 1, status: 'COMPLETED', present,
    readyForBase: true,
    config: { schemaVersion: 1, ...(legacy ? {} : { outcomeRulesVersion: 1 }),
      branch: { enabled: true, steps: step ? [currentStep] : [] } },
    branch: { currentStepId: step ? currentStep.id : '', ...(step ? { currentStep } : {}),
      ...(legacy ? {} : { writable, ended }) },
    playKit: { qa: { mode: 'TYPE', title: '原题', finished: true, passed: true },
      branch: step ? { currentStep } : {} } };
}

let pageDef, albumDef, branchDef, wrapperDef, advancedDef;
let uploads, requests, toasts, media, lastOperation;
const app = { getUserID: () => 99, globalData: { features: {} }, isDevEnv: () => false };
global.getApp = () => app;
global.Behavior = value => value;
global.wx = { getStorageSync: () => '', getWindowInfo: () => ({ statusBarHeight: 20 }),
  getSystemInfoSync: () => ({ statusBarHeight: 20 }), showLoading() {}, hideLoading() {},
  showToast: options => toasts.push(options.title), hideToast() {}, vibrateShort() {},
  chooseMedia: options => { media = options; } };
global.Page = value => { pageDef = value; };
require('../../pages/play/index.js');
global.Component = value => { albumDef = value; };
require('../../pages/play/components/playkit-album/index.js');
global.Component = value => { branchDef = value; };
require('../../pages/play/components/playkit-branch/index.js');
global.Component = value => { wrapperDef = value; };
require('../../pages/play/components/playkit/index.js');
global.Component = value => { advancedDef = value; };
require('../../pages/play/components/advanced-game/index.js');

function setData(patch, callback) {
  for (const [key, value] of Object.entries(patch)) {
    const parts = key.split('.');
    let target = this.data;
    for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {});
    target[parts[parts.length - 1]] = value;
  }
  if (callback) callback();
}
function component(def, props) {
  return Object.assign({}, def.methods, { data: Object.assign({}, structuredClone(def.data || {}), props),
    setData, selectComponent: () => null });
}
beforeEach(() => {
  uploads = []; requests = []; toasts = []; media = null;
  app.sendRequest = options => { requests.push(options); };
  app.getUploadClient = () => createUploadClient({
    wxUploadFile(options) { uploads.push(options); return { abort() {} }; },
    getBaseUrl: () => 'http://127.0.0.1', getAuthorization: () => 'unit-test',
  });
  app.createPageBoundOperation = () => {
    let aborted = false;
    const listeners = [];
    lastOperation = { attach() {}, finish() {}, isAborted: () => aborted,
      onAbort(fn) { listeners.push(fn); }, abort() { aborted = true; listeners.forEach(fn => fn()); } };
    return lastOperation;
  };
});
function setup(view) {
  const page = Object.assign({}, pageDef, { data: structuredClone(pageDef.data), setData,
    _mergeStoryVars() {}, _onKaccha() {}, _stopPlayKitAudio() {}, openChapterFull() {} });
  page.data.advancedPlay = { show: true, node: { nodeId: 29, imgUrl: AUTHOR_PHOTO } };
  page.data.playKit = { show: true, kit: pickPlayKit(view), readyForBase: canContinue(view) };
  const game = component(advancedDef, { state: structuredClone(view), node: { nodeId: 29 },
    currentStep: view.branch && view.branch.currentStep,
    topicId: 19, activityId: 9, acting: false, unknown: false, readingBack: false });
  game.syncTimer = () => {};
  game.triggerEvent = (name, detail) => {
    if (name === 'session') page.onAdvancedSession({ detail });
    if (name === 'actionfail') page.onAdvancedActionFail({ detail });
  };
  page.selectComponent = selector => selector === '#advancedGame' ? game : null;
  const wrapper = component(wrapperDef, { kit: page._kit() });
  wrapper.triggerEvent = (name, detail) => page.onPlayKitAction({ detail });
  const album = component(albumDef, { completionEnabled: true, done: false, uploaded: false });
  album.triggerEvent = (name, detail) => {
    wrapper.data.kit = page._kit();
    if (name === 'shoot') wrapper.onShoot({ detail });
    if (name === 'submit') wrapper.onSubmit({ detail });
  };
  return { page, game, album, wrapper };
}
function choose(album) {
  album.onCta();
  assert.equal(media.count, 1);
  media.success({ tempFiles: [{ tempFilePath: '/tmp/player-evidence.jpg', size: 1024 }] });
}
function uploadSuccess() { uploads[0].success({ data: JSON.stringify({ code: 200, url: PLAYER_PHOTO }) }); }
const tick = () => new Promise(resolve => setImmediate(resolve));

test('相册：真实组件→分发器→认证上传→SUBMIT_ALBUM，只提交玩家证据', async () => {
  const { page, album } = setup(albumView());
  choose(album);
  assert.equal(uploads.length, 1);
  assert.equal(uploads[0].header.Authorization, 'Bearer unit-test');
  assert.equal(requests.length, 0);
  uploadSuccess();
  const command = JSON.parse(requests[0].data);
  assert.equal(command.action, 'SUBMIT_ALBUM');
  assert.deepEqual(command.payload, { imageUrls: [PLAYER_PHOTO] });
  assert.deepEqual(page._kit().images, [], '上传成功不冒充服务端完成回执');
  requests[0].success({ code: 200, data: albumView(true) });
  await tick();
  assert.equal(page._kit().done, true);
  assert.deepEqual(page._kit().images, [{ url: PLAYER_PHOTO }]);
  assert.deepEqual(page._kit().authorImages, []);
  assert.equal(page.data.advancedPlay.node.imgUrl, AUTHOR_PHOTO);
});

test('相册：缺证据或直接给作者/任意URL都拒绝，旧相册不进入R5提交', () => {
  const { page } = setup(albumView());
  for (const detail of [{}, { imageUrls: [AUTHOR_PHOTO] }, { imageUrls: [PLAYER_PHOTO] }]) {
    page.onPlayKitAction({ detail: { type: 'album', action: 'submit', detail } });
  }
  page.onPlayKitAction({ detail: { type: 'album', action: 'shoot', detail: {} } });
  assert.equal(requests.length, 0);
  assert.equal(uploads.length, 0);
  assert.equal(toasts.length, 4);
  const legacy = albumView(); delete legacy.config.outcomeRulesVersion;
  assert.equal(pickPlayKit(legacy), null);
  assert.deepEqual(page._kit().authorImages, [{ url: AUTHOR_PHOTO, line: '作者示意' }]);
});

test('相册：服务端拒绝不完成，原上传证据原幂等键重发且不重新上传', async () => {
  const { page, album } = setup(albumView());
  choose(album); uploadSuccess();
  requests[0].success({ code: 400, msg: '照片不属于当前会员' }); await tick();
  assert.equal(page._kit().done, false);
  assert.equal(page._kit().uploaded, true);
  album.data.uploaded = page._kit().uploaded;
  album.onCta();
  assert.equal(uploads.length, 1);
  assert.equal(requests.length, 2);
  assert.deepEqual(JSON.parse(requests[1].data), JSON.parse(requests[0].data));
  requests[1].success({ code: 200, data: albumView(true) }); await tick();
  assert.equal(page._kit().done, true);
});

test('相册：原证据被拒后可重新选择照片，不被重试按钮锁住', async () => {
  const { page, album } = setup(albumView());
  choose(album); uploadSuccess();
  requests[0].success({ code: 400, msg: '照片不属于当前会员' }); await tick();
  album.data.uploaded = true; album.choosePhoto();
  media.success({ tempFiles: [{ tempFilePath: '/tmp/reselected.jpg', size: 1024 }] });
  assert.equal(uploads.length, 2); assert.equal(requests.length, 1);
  assert.equal(page._kit().done, false);
});

test('相册：共享会话未知结果沿既有通道重放本人原命令，禁止再传照片', async () => {
  const view = albumView(); view.ownerType = 2;
  const { page, game, album } = setup(view);
  choose(album); uploadSuccess(); requests[0].fail({}); await tick();
  assert.equal(game.data.unknown, true);
  assert.equal(requests.length, 2);
  assert.deepEqual(JSON.parse(requests[1].data), JSON.parse(requests[0].data));
  page.onPlayKitAction({ detail: { type: 'album', action: 'shoot', detail: { tempFilePath: '/tmp/again.jpg' } } });
  assert.equal(uploads.length, 1);
  const done = albumView(true); done.ownerType = 2;
  requests[1].success({ code: 200, data: done }); await tick();
  assert.equal(game.data.unknown, false);
  assert.equal(page._kit().done, true);
});

test('相册：上传拒绝、离页及异步切换版本都不得提交', () => {
  for (const mode of ['failure', 'abort', 'version']) {
    uploads = []; requests = [];
    const { game, album } = setup(albumView()); choose(album);
    if (mode === 'abort') lastOperation.abort();
    if (mode === 'version') game.data.state.version++;
    if (mode === 'failure') uploads[0].success({ data: JSON.stringify({ code: 403, msg: '上传被拒绝' }) });
    else uploadSuccess();
    assert.equal(requests.length, 0, mode);
  }
});

test('反馈：空分支/未结算不得遮住主玩法，只有服务器当前步才进入反馈', () => {
  const unsettled = feedbackView({ step: false });
  unsettled.readyForBase = false; unsettled.status = 'RUNNING'; unsettled.playKit.qa.finished = false;
  assert.equal(pickPlayKit(unsettled).type, 'qa');
  const fail = feedbackView(); fail.status = 'FAILED'; fail.readyForBase = false;
  fail.playKit.qa.passed = false;
  assert.equal(pickPlayKit(fail).type, 'branch', 'FAIL也必须看已结算的反馈');
  const kit = pickPlayKit(feedbackView());
  assert.equal(kit.stepId, 'feedback'); assert.equal(kit.writable, true);
});

for (const present of ['fullscreen', 'inline']) {
  for (const mode of ['pending', 'closed', 'ended', 'terminal']) {
    test(`反馈：${present}/${mode} 的真实Page写入与继续门控`, () => {
      const options = { present, writable: mode !== 'closed', ended: mode === 'ended' || mode === 'terminal', terminal: mode === 'terminal' };
      const view = feedbackView(options);
      const { page, game } = setup(view);
      page.data.chapterFull = present === 'inline';
      page.onAdvancedSession({ detail: view });
      assert.equal(page.data.playKit.show, present !== 'inline');
      assert.equal(page.data.playKit.readyForBase, mode === 'terminal' || mode === 'ended');
      page.onPlayKitAction({ detail: { type: 'branch', action: 'choose', detail: { optionId: 'continue' } } });
      assert.equal(requests.length, mode === 'pending' ? 1 : 0);
      if (requests[0]) assert.equal(JSON.parse(requests[0].data).action, 'CHOOSE');
      let continued = 0; game.triggerEvent = name => { if (name === 'ready') continued++; };
      page.continueStoryGame();
      assert.equal(continued, mode === 'terminal' || mode === 'ended' ? 1 : 0);
    });
  }
}

test('反馈：重入/章内自动继续均不能跳过待选反馈，终点才继续', () => {
  const saved = global.setTimeout;
  const timers = [];
  global.setTimeout = callback => { timers.push(callback); return 1; };
  try {
    const { page, game } = setup(feedbackView());
    page.data.chapterFull = true; page._storyOpenNode = true; page._freshStoryGame = '29';
    page._playKitNodeId = 29; page.data.chapterParas = [{ kind: 'kit', nodeId: 29 }];
    let continued = 0; game.continueBase = () => { continued++; };
    page.onAdvancedSession({ detail: feedbackView({ present: 'inline' }) });
    assert.equal(timers.length, 0); page.continueStoryGame(); assert.equal(continued, 0);
    page._freshStoryGame = '29';
    page.onAdvancedSession({ detail: feedbackView({ terminal: true, ended: true, present: 'inline' }) });
    timers.forEach(callback => callback()); assert.ok(continued > 0);
  } finally { global.setTimeout = saved; }
});

test('反馈：组件只读、终点、同正文不同步、明确拒绝后的重试', () => {
  for (const [writable, ended, expected] of [[true, false, 1], [false, false, 0], [true, true, 0]]) {
    const comp = component(branchDef, { show: true, writable, ended, options: [{ id: 'continue' }], body: '同一正文' });
    let picks = 0; comp.triggerEvent = () => { picks++; };
    const observe = branchDef.observers['show, body, ended, writable, stepId, failureSeq'];
    observe.call(comp, true); comp.onPick({ currentTarget: { dataset: { i: 0 } } });
    assert.equal(picks, expected);
    if (expected) {
      observe.call(comp, true); comp.onPick({ currentTarget: { dataset: { i: 0 } } }); assert.equal(picks, 2);
    }
  }
});

test('反馈：主会话COMPLETED仍可选；服务端只读/终点及关闭后拒绝旧事件', () => {
  const { page, game } = setup(feedbackView());
  game.data.state.branch.writable = false;
  page.onPlayKitAction({ detail: { type: 'branch', action: 'choose', detail: { optionId: 'continue' } } });
  game.choose({ currentTarget: { dataset: { id: 'continue' } } });
  assert.equal(requests.length, 0);
  game.data.state.branch.writable = true;
  page.onAdvancedClose();
  page.onPlayKitAction({ detail: { type: 'branch', action: 'choose', detail: { optionId: 'continue' } } });
  assert.equal(requests.length, 0);
});

test('反馈：关闭全屏后即使原服务端状态可写也不再接旧选择事件', () => {
  const { page } = setup(feedbackView());
  page.closePlayKit();
  page.onPlayKitAction({ detail: { type: 'branch', action: 'choose', detail: { optionId: 'continue' } } });
  assert.equal(requests.length, 0);
});

test('反馈：CHOOSE明确拒绝释放组件选择锁，未知结果仍锁定', () => {
  const { page, game } = setup(feedbackView());
  page.onAdvancedActionFail({ detail: { action: 'CHOOSE', sessionId: 51 } });
  assert.equal(page._kit().failureSeq, 1);
  game.data.unknown = true;
  page.onAdvancedActionFail({ detail: { action: 'CHOOSE', sessionId: 51 } });
  assert.equal(page._kit().failureSeq, 1);
});

test('旧分支：原优先级、选项、限时与ready行为不受R5规则影响', () => {
  const view = feedbackView({ legacy: true }); view.deadlineAt = Date.now() + 60000;
  view.playKit.qa.finished = false;
  assert.equal(pickPlayKit(view).type, 'qa');
  view.playKit.qa.finished = true;
  const { page } = setup(view);
  assert.equal(page._kit().type, 'branch'); assert.equal(page._kit().feedback, false);
  assert.ok(page._kit().limitSeconds > 0); assert.equal(canContinue(view), true);
  page.onPlayKitAction({ detail: { type: 'branch', action: 'choose', detail: { optionId: 'continue' } } });
  assert.equal(JSON.parse(requests[0].data).action, 'CHOOSE');
});

test('advanced emitSession透传R5标记和权威反馈；无反馈时不额外阻塞继续', () => {
  const view = feedbackView(); const { game } = setup(view);
  let emitted; game.triggerEvent = (name, detail) => { emitted = detail; };
  game.emitSession(view);
  assert.deepEqual(emitted.branch, view.branch); assert.deepEqual(emitted.config, view.config);
  assert.equal(canContinue(albumView(true)), true);
});

for (const outcome of ['PASS', 'FAIL', 'CLOSED']) {
  test(`反馈：真实组件打开入口重入 ${outcome} 保留反馈与权威写权限`, async () => {
    const view = feedbackView({ writable: outcome !== 'CLOSED' });
    if (outcome === 'FAIL') { view.status = 'FAILED'; view.playKit.qa.passed = false; }
    const { page, game } = setup(view);
    advancedDef.observers['show,node.nodeId'].call(game, true, 29);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, '/api/play/advanced/start');
    assert.equal(requests[0].method, 'POST');
    requests[0].success({ code: 200, data: structuredClone(view) });
    await tick();
    assert.equal(game.data.state.sessionId, view.sessionId);
    assert.equal(game.data.state.version, view.version);
    assert.equal(game.data.state.status, view.status);
    assert.equal(game.data.currentStep.id, 'feedback');
    assert.equal(page._kit().type, 'branch');
    assert.equal(page._kit().writable, outcome !== 'CLOSED');
    assert.equal(requests.length, 1, '重入只刷新，不提交动作或重试');
    page.onPlayKitAction({ detail: { type: 'branch', action: 'choose', detail: { optionId: 'continue' } } });
    assert.equal(requests.length, outcome === 'CLOSED' ? 1 : 2);
    if (outcome !== 'CLOSED') assert.equal(JSON.parse(requests[1].data).action, 'CHOOSE');
  });
}

test('相册 X019：网络失败保留同一张图重传，上传成功后只提交一次', async () => {
  const { page, album } = setup(albumView());
  choose(album);
  uploads[0].fail({ errMsg: 'uploadFile:fail timeout' });
  assert.equal(requests.length, 0);
  assert.equal(page._kit().uploadFailure, 'retry');
  album.data.uploadFailure = page._kit().uploadFailure;
  album.onCta();
  assert.equal(media.count, 1, '重试不重新打开选图');
  assert.equal(uploads.length, 2);
  assert.equal(uploads[1].filePath, '/tmp/player-evidence.jpg');
  uploads[1].success({ data: JSON.stringify({ code: 200, url: PLAYER_PHOTO }) });
  assert.equal(requests.length, 1);
  assert.equal(JSON.parse(requests[0].data).action, 'SUBMIT_ALBUM');
  assert.equal(page._kit().uploadFailure, '');
  assert.equal(page._albumRetry, null);
});

test('相册 X019：HTTP 5xx 可重传同一张，业务拒绝请玩家重新选图且不能重传', () => {
  let ctx = setup(albumView()); choose(ctx.album);
  uploads[0].success({ statusCode: 502, data: '<html>bad gateway</html>' });
  assert.equal(ctx.page._kit().uploadFailure, 'retry');
  assert.equal(requests.length, 0);
  uploads = []; requests = [];
  ctx = setup(albumView()); choose(ctx.album);
  uploads[0].success({ statusCode: 200, data: JSON.stringify({ code: 500, msg: '图片格式不支持' }) });
  assert.equal(ctx.page._kit().uploadFailure, 'reselect');
  ctx.page.onPlayKitAction({ detail: { type: 'album', action: 'submit', detail: {} } });
  assert.equal(uploads.length, 1, '非网络失败不得自动重传旧图');
  assert.equal(requests.length, 0);
});

test('相册 X019：已提交后组件显示只读说明，不再提供拍照入口', () => {
  const fs = require('node:fs'); const path = require('node:path');
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/play/components/playkit-album/index.wxml'), 'utf8');
  assert.match(wxml, /wx:if="\{\{completionEnabled && done\}\}"[^>]*>照片已提交，相册仅供查看，不能再更换</);
  assert.match(fs.readFileSync(path.join(__dirname, '../../pages/play/components/playkit/index.wxml'), 'utf8'), /upload-failure="\{\{kit\.uploadFailure\}\}"/);
  const done = component(albumDef, { completionEnabled: true, done: true, uploaded: true, uploadFailure: 'retry' });
  let closed = false; done.triggerEvent = name => { if (name === 'close') closed = true; };
  done.onCta(); done.choosePhoto();
  assert.equal(closed, true); assert.equal(media, null);
});
