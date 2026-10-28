const test = require('node:test');
const assert = require('node:assert/strict');
let page;
const requests = [];
global.getApp = () => ({ globalData: {}, sendRequest: options => {
  requests.push(options);
  // 基础写入缺原局身份时先读 /api/play/action-context 绑定原局，再发真正的完成请求。
  options.success(options.url === '/api/play/action-context'
    ? { code: 200, data: { routeSessionId: '77', expectedRouteVersion: 3 } } : { code: 200, data: {} });
} });
global.wx = { getStorageSync: () => null,
  getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ top: 20, height: 32 }) };
global.Page = value => { page = value; };
require('../../pages/play/index.js');
test('剧情认领与开章完成使用 JSON，保留局信息及版本', async () => {
  const p = Object.assign({}, page, {
    data: { topicId: 12, activityId: 24, chapter: { opening: true } },
    _routeState: { version: 3, opening: { ready: true, chapterId: 8 } },
    _sessionParams: () => ({ activityId: 24 }), _weRunCipher: () => Promise.resolve(null),
    _applyRouteState() {}, closeChapterFull() {}, loadData() {},
  });
  await p._syncThoughts(['first']);
  await p.continueChapterStory();
  assert.equal(requests.length, 2);
  for (const request of requests) {
    assert.equal(request.header['content-type'], 'application/json');
    assert.equal(request.data.topicId, 12);
    assert.equal(request.data.activityId, 24);
  }
  assert.deepEqual(requests[0].data.claim, ['first']);
  assert.equal(requests[1].data.expectedVersion, 3);
  assert.equal(requests[1].data.chapterId, 8);
});
test('advanced completion refreshes route before the base write and stops on refresh failure', async () => {
  for (const ok of [true, false]) {
    const calls = [];
    const node = { nodeId: 229, vm: 0 };
    const p = Object.assign({}, page, {
      data: { advancedPlay: { show: true, node } },
      _routeState: { routeMode: 'BRANCH_GRAPH', version: 3 },
      async refreshRouteState() { calls.push('refresh'); if (ok) this._routeState.version = 4; return ok; },
      setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb(); },
      onSheetGoTap() { calls.push(this._routeState.version); },
    });
    await p.onAdvancedReady({ detail: { nodeId: 229 } });
    assert.deepEqual(calls, ok ? ['refresh', 4] : ['refresh']);
  }
});
test('completed run returns to its entry; unfinished chapter stays in play', () => {
  for (const allDone of [true, false]) {
    let returned = false;
    const p = Object.assign({}, page, { data: { allDone },
      _hideFinish(cb) { cb(); }, goBackDetail() { returned = true; } });
    p.closeFinish();
    assert.equal(returned, allDone);
  }
});

test('六种节点完成入口先同步伏笔，再用同步后的版本提交', async () => {
  const cases = [
    ['arrive', p => p.arrive(9, 'arrive')],
    ['checkin', p => p.checkin(9, 'scan-code', 'game')],
    ['preference', p => {
      // 偏好题已由 loadPreference 读出(真实页面提交前必有这份上下文)
      p._preferenceEpoch = 1;
      p._preferenceContext = { epoch: 1, nodeId: 9, viewContext: p._legacyGameContext() };
      p.submitPreference({ choices: { a: 'b' } });
    }],
    ['photo', p => p.submitPhoto(9, 'photo.jpg')],
    ['answer', p => p.submitGame()],
    ['puzzleReveal', p => p.revealAnswer()],
  ];
  for (const [kind, submit] of cases) {
    const p = Object.assign({}, page, {
      data: { topicId: 12, mode: 1, game: { nodeId: 9, vm: 1, puzzleScoring: true },
        answerInput: 'answer', preference: { submitting: false } },
      _routeState: { thoughtCount: 1, version: 3 },
      async _syncThoughts() { this._routeState.version = 4; return true; },
      _routeWritePayload(action, nodeId, payload) {
        assert.equal(action, kind);
        assert.equal(this._routeState.version, 4, '完成请求不能提前生成旧版本');
        const body = Object.assign({}, payload, { expectedRouteVersion: this._routeState.version, routeActionId: 'sync-' + kind });
        Object.defineProperty(body, '_bindRouteContext', { value: fields => Object.assign(body, fields) });
        return body;
      },
      setData(patch) { for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.'); let target = this.data;
        while (parts.length > 1) target = target[parts.shift()];
        target[parts[0]] = value;
      } },
      _resolveRouteAction() {}, clearGameNetworkError() {}, onComplete() {}, _applyAiScore() {},
    });
    const before = requests.length;
    submit(p);
    assert.equal(requests.length, before, '同步完成前不得发出节点完成请求');
    await new Promise(setImmediate);
    const writes = requests.slice(before).filter(r => r.url !== '/api/play/action-context');
    assert.equal(writes.length, 1, kind);
    assert.equal(writes[0].data.expectedRouteVersion, 4);
  }
});

test('同步失败不阻核心动作；等待认领成功后再同步，拒绝运动授权仍能继续', async () => {
  const originalToast = wx.showToast;
  wx.showToast = () => {};
  try {
    let writes = 0;
    const p = Object.assign({}, page, { data: {}, _routeState: { thoughtCount: 1 },
      _syncThoughts: async () => false });
    const failed = await p._withThoughtSync(() => { writes += 1; });
    assert.equal(failed, undefined);
    assert.equal(writes, 1);
    let release;
    p._thoughtSyncPending = new Promise(resolve => { release = resolve; });
    let synced = false;
    p._syncThoughts = async () => { synced = true; return true; };
    const waiting = p._withThoughtSync(() => { writes += 1; return 'done'; });
    await new Promise(setImmediate);
    assert.equal(synced, false);
    release(true);
    assert.equal(await waiting, 'done');
    assert.equal(writes, 2);
    p._thoughtSyncPending = null;
    p._syncThoughts = page._syncThoughts;
    p._weRunCipher = async () => null;
    const before = requests.length;
    assert.equal(await p._withThoughtSync(() => 'done'), 'done');
    assert.equal(requests.length, before, '拒绝授权时不伪造微信步数请求');
  } finally { wx.showToast = originalToast; }
});
test('note own entry uses server text, not object coercion', () => {
  const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js');
  assert.equal(pickPlayKit({playKit:{note:{enabled:true,mine:{text:'car traffic'},done:true}}}).mine, 'car traffic');
});


function typeInPage(game) {
  return Object.assign({}, page, { data: { playKit: { show: true, kit: {
    type: 'typein', sessionId: 42, target: 'hi', startConfirmed: false
  } }, advancedPlay: { show: true, node: { nodeId: 7 } } },
    setData(patch) { for (const [path, value] of Object.entries(patch)) {
      const parts = path.split('.'); let target = this.data;
      while (parts.length > 1) target = target[parts.shift()];
      target[parts[0]] = value;
    } },
    selectComponent: () => game,
  });
}

test('开始拒绝后向当前打字组件发恢复序号，并允许再次开始', async () => {
  let count = 0;
  const game = { data: { unknown: false, acting: false, state: { sessionId: 42 } }, action() { count += 1; return Promise.resolve(); } };
  const p = typeInPage(game);
  p._submitPlayKitAction('start', {});
  await new Promise(setImmediate);
  assert.equal(p.data.playKit.kit.startFailureSeq, 1);
  assert.equal(p._typeInStart, null);
  p._submitPlayKitAction('start', {});
  await new Promise(setImmediate);
  assert.equal(count, 2);
  assert.equal(p.data.playKit.kit.startFailureSeq, 2);
});

test('unknown保持等待，权威回读确认开始后允许提交，不依赖丢失的原回包', async () => {
  const calls = [];
  const game = { data: { unknown: true, acting: false }, action(name) { calls.push(name); return Promise.resolve(); } };
  const p = typeInPage(game);
  p._submitPlayKitAction('start', {});
  await new Promise(setImmediate);
  p.onAdvancedActionFail({ detail: { action: 'START_CHALLENGE', sessionId: 42 } });
  assert.equal(p.data.playKit.kit.startFailureSeq, undefined);
  p.data.playKit.kit.startConfirmed = true;
  game.data.unknown = false;
  p._submitPlayKitAction('submit', { text: 'hi' });
  assert.deepEqual(calls, ['START_CHALLENGE', 'SUBMIT_TYPE_IN']);
});

test('旧局迟到失败不解锁当前节点；pickPlayKit只认服务端started布尔', () => {
  const p = typeInPage({ data: { unknown: false } });
  p.data.playKit.kit.sessionId = 99;
  p.onAdvancedActionFail({ detail: { action: 'START_CHALLENGE', sessionId: 42 } });
  assert.equal(p.data.playKit.kit.startFailureSeq, undefined);
  const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js');
  for (const started of [true, false, undefined]) {
    assert.equal(pickPlayKit({ sessionId: 42, playKit: { typeIn: { started } } }).startConfirmed, started === true);
  }
});

test('草稿试玩开始也向打字组件确认，但不写正式请求', () => {
  const { applyPreviewAction } = require('../../utils/game-preview-action.js');
  const original = { type: 'typein', target: 'hi', seconds: 10, attempts: 0 };
  const result = applyPreviewAction(original, { action: 'start' }, 0, 1000);
  assert.equal(result.kit.startConfirmed, true);
  assert.equal(result.startedAt, 1000);
  assert.equal(original.startConfirmed, undefined);
});

test('F-R2S-01:微信运动回调迟迟不回时,步数同步最多等 8 秒,之后照常提交玩家动作', async (t) => {
  const originalToast = wx.showToast;
  wx.showToast = () => {};
  t.mock.timers.enable({ apis: ['setTimeout'] });
  try {
    let writes = 0;
    const p = Object.assign({}, page, { data: {}, _routeState: { thoughtCount: 1 },
      _syncThoughts: () => new Promise(() => {}) });
    const waiting = p._withThoughtSync(() => { writes += 1; return 'done'; });
    await new Promise(setImmediate);
    t.mock.timers.tick(7999);
    await new Promise(setImmediate);
    assert.equal(writes, 0, '等待窗口内仍按原顺序先同步');
    t.mock.timers.tick(1);
    assert.equal(await waiting, 'done');
    assert.equal(writes, 1);
  } finally { wx.showToast = originalToast; t.mock.timers.reset(); }
});
