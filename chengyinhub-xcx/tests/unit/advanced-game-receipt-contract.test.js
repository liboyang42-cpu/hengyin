const assert = require('node:assert/strict');
const test = require('node:test');

const COMPONENT_PATH = require.resolve('../../pages/play/components/advanced-game/index.js');
let definition;

function loadDefinition() {
  const previousComponent = global.Component;
  const previousGetApp = global.getApp;
  global.Component = (options) => { definition = options; };
  global.getApp = () => ({ sendRequest() {} });
  delete require.cache[COMPONENT_PATH];
  require(COMPONENT_PATH);
  global.Component = previousComponent;
  global.getApp = previousGetApp;
}
loadDefinition();

/** queue: 每次 request 按顺序取一项；{ res } 走 resolve，{ err } 走 reject。 */
function createGame(queue, state) {
  const calls = [];
  const instance = {
    properties: {},
    data: Object.assign(JSON.parse(JSON.stringify(definition.data)), {
      state: Object.assign({
        sessionId: 42, version: 3, status: 'RUNNING', draws: [],
        config: { random: { enabled: true, drawCount: 3, items: [] }, branch: { enabled: false, steps: [] },
          multiplayer: { enabled: false, roles: [], requiredTurns: 1 }, leaderboard: { enabled: false },
          timer: { enabled: false } },
        multiplayer: { roles: {}, members: [], completedUnitIds: [], turnIndex: 0 },
      }, state || {}),
    }),
    setData(update, cb) { Object.assign(this.data, update); if (cb) cb(); },
    triggerEvent() {},
    syncTimer() {},
    stopTimer() {},
    loadLeaderboard() {},
  };
  Object.assign(instance, definition.methods);
  instance.request = function (url, method, data) {
    calls.push({ url, method, data });
    const next = queue.shift();
    if (!next) return Promise.reject(new Error('unexpected request: ' + url));
    return next.err ? Promise.reject(next.err) : Promise.resolve(next.res);
  };
  instance.calls = calls;
  return instance;
}

const okAction = (version) => ({ res: { code: 200, data: { sessionId: 42, version, status: 'RUNNING', draws: [], config: {} } } });

test('权威会话的呈现方式和可收尾状态完整递交页面，内嵌不会变成默认全屏', () => {
  const game = createGame([]);
  let received;
  game.triggerEvent = (name, detail) => { if (name === 'session') received = detail; };
  game.emitSession({ sessionId: 42, version: 4, present: 'inline', readyForBase: true,
    playKit: { profile: { done: true } } });
  assert.equal(received.present, 'inline');
  assert.equal(received.readyForBase, true);
  assert.equal(received.sessionId, 42);
});

test('提交成功：hydrate 权威状态，不进 unknown', async () => {
  const game = createGame([okAction(4)]);
  await game.action('DRAW', {});
  assert.equal(game.data.unknown, false);
  assert.equal(game.data.acting, false);
  assert.equal(game.data.state.version, 4);
});

test('★负控：响应丢失 ⇒ unknown，不得写成失败，也不得放行重复提交', async () => {
  const game = createGame([{ err: new Error('timeout') }, { err: new Error('timeout') }]);
  await game.action('DRAW', {});
  await new Promise(setImmediate);

  assert.equal(game.data.unknown, true, 'unknown 必须置位');
  assert.equal(game.data.error, '', 'unknown 不能被写成 error —— 那是在对用户说「失败了」');
  assert.match(game.data.unknownText, /勿重复提交|核对/);

  const before = game.calls.length;
  await game.action('DRAW', {});
  assert.equal(game.calls.length, before, 'unknown 未解除前不得再发写请求');
});

test('unknown 回读发现 version 已前进 ⇒ 判定已落地，清锁且不重发', async () => {
  const game = createGame([
    { err: new Error('timeout') },
    { res: { code: 200, data: { sessionId: 42, version: 4, status: 'RUNNING', draws: [{ id: 'x' }], config: {} } } },
  ]);
  await game.action('DRAW', {});
  await new Promise(setImmediate);
  await new Promise(setImmediate);

  assert.equal(game.data.unknown, false);
  assert.equal(game.data.state.version, 4, '必须采用服务端权威 version');
  assert.equal(game.data.error, '', '写入已落地，不该提示重试');
  assert.equal(game.calls[1].url, '/api/play/advanced/state', '回读必须问服务端，不是读本地缓存');
});

test('★多人局:version 前进也不算证据 —— 队友的写入同样推高它,不许判成我方已落地', async () => {
  const game = createGame([
    { err: new Error('timeout') },
    // 队友写了一笔:version 从 3 涨到 4,但我的 DRAW 一个 draw 都没留下。
    { res: { code: 200, data: { sessionId: 42, version: 4, status: 'RUNNING', draws: [],
        config: { multiplayer: { enabled: true } } } } },
  ]);
  await game.action('DRAW', {});
  await new Promise(setImmediate);
  await new Promise(setImmediate);

  assert.equal(game.data.unknown, false, '回读拿到结果就该解锁,不能永远转圈');
  assert.notEqual(game.data.error, '', '证不到是我落的,就不许把提示清空当成功');
  assert.match(game.data.error, /没能确认/, '要如实说「没能确认」,别谎报成功也别谎报失败');
  assert.match(game.data.error, /不会重复提交/, '得告诉用户重试是安全的(幂等键兜底)');
});

test('unknown 回读发现 version 未变 ⇒ 说清没提交成功，才放行重试', async () => {
  const game = createGame([
    { err: new Error('timeout') },
    { res: { code: 200, data: { sessionId: 42, version: 3, status: 'RUNNING', draws: [], config: {} } } },
  ]);
  await game.action('DRAW', {});
  await new Promise(setImmediate);
  await new Promise(setImmediate);

  assert.equal(game.data.unknown, false);
  assert.match(game.data.error, /没有提交成功/);
});

test('回读本身也失败 ⇒ 保持 unknown 锁定，不猜结论', async () => {
  const game = createGame([{ err: new Error('timeout') }, { err: new Error('still offline') }]);
  await game.action('DRAW', {});
  await new Promise(setImmediate);
  await new Promise(setImmediate);

  assert.equal(game.data.unknown, true, '核对不上就得一直锁着');
  assert.equal(game.data.readingBack, false, '要能再点一次核对');
  assert.match(game.data.unknownText, /勿重复提交/);
});

test('★同一 version 上重试复用同一幂等键 —— 服务端 replay 才能生效', async () => {
  const first = createGame([{ err: new Error('timeout') },
    { res: { code: 200, data: { sessionId: 42, version: 3, status: 'RUNNING', draws: [], config: {} } } }]);
  await first.action('CHOOSE', { optionId: 'a1' });
  await new Promise(setImmediate);
  await new Promise(setImmediate);

  const retryKeySource = createGame([okAction(4)], { version: 3 });
  await retryKeySource.action('CHOOSE', { optionId: 'a1' });

  assert.equal(first.calls[0].data.idempotencyKey, retryKeySource.calls[0].data.idempotencyKey,
    '同一 session+version+动作+入参必须出同一个键');
});

test('服务端说「状态已更新」⇒ 自动回读权威状态，不让用户拿旧 version 反复重发', async () => {
  const game = createGame([
    { res: { code: 500, msg: '状态已更新，请刷新后重试' } },
    { res: { code: 200, data: { sessionId: 42, version: 9, status: 'RUNNING', draws: [], config: {} } } },
  ]);
  await game.action('DRAW', {});
  await new Promise(setImmediate);
  await new Promise(setImmediate);

  assert.equal(game.calls[1].url, '/api/play/advanced/state');
  assert.equal(game.data.state.version, 9);
  assert.equal(game.data.acting, false);
});

test('state 缺 version ⇒ 拒发写请求，不拿空幂等键去撞服务端', async () => {
  const game = createGame([], { version: undefined });
  await game.action('DRAW', {});
  assert.equal(game.calls.length, 0);
  assert.match(game.data.error, /状态不完整/);
});

test('★C-25 榜单请求失败:落「榜单暂时读不到」行内态,不再 unhandled rejection', async () => {
  const game = createGame([{ err: new Error('network down') }]);
  await game.loadLeaderboard();
  await new Promise(setImmediate);
  assert.equal(game.data.leaderboardError, true);
  assert.equal(game.data.leaderboardLoading, false);
});

test('C-25 榜单成功清掉失败态;业务错也落失败态而不是静默', async () => {
  const ok = createGame([{ res: { code: 200, data: [{ rank: 1, displayName: '阿杰', score: 9 }] } }]);
  await ok.loadLeaderboard();
  await new Promise(setImmediate);
  assert.equal(ok.data.leaderboardError, false);
  assert.equal(ok.data.leaderboard.length, 1);

  const denied = createGame([{ res: { code: 500, msg: '该主题未开启排行榜' } }]);
  await denied.loadLeaderboard();
  await new Promise(setImmediate);
  assert.equal(denied.data.leaderboardError, true);
  assert.equal(denied.data.leaderboard.length, 0);
});


test('草稿建档走本地kit，提交和重新打开都不请求正式会话', () => {
  const game = createGame([]);
  game.data.preview = true;
  game.data.node = { nodeId: 7, advancedConfig: { profile: { enabled: true } },
    previewKit: { type: 'profile', present: 'inline', answers: {}, done: false } };
  game.syncTimer = () => {};
  let received;
  game.triggerEvent = (name, view) => { if (name === 'session') received = view; };
  game.start();
  assert.equal(received.previewKit.type, 'profile');
  assert.equal(received.readyForBase, false);
  game.previewKitAction({ action: 'submit', detail: { answers: { name: '小林' } } });
  assert.equal(received.previewKit.answers.name, '小林');
  assert.equal(received.vars.name, '小林');
  assert.equal(received.readyForBase, true);
  assert.equal(game.calls.length, 0);
  game.data.preview = false;
  assert.equal(game.previewKitAction({ action: 'submit', detail: {} }), false);
  assert.equal(game.calls.length, 0);
});


test('限时打字开始业务拒绝发送失败出口，并携带当次局标识', async () => {
  const game = createGame([{ res: { code: 500, msg: '不能开始这一局' } }]);
  const failed = [];
  game.triggerEvent = (name, detail) => { if (name === 'actionfail') failed.push(detail); };
  await game.action('START_CHALLENGE', { game: 'typeIn' });
  assert.equal(game.data.acting, false);
  assert.equal(game.data.unknown, false);
  assert.equal(failed.length, 1);
  assert.equal(failed[0].action, 'START_CHALLENGE');
  assert.equal(failed[0].sessionId, 42);
});

test('限时打字响应丢失按服务器started回读；版本增长不冒充开表事实', async () => {
  for (const started of [true, false]) {
    const game = createGame([{ err: new Error('timeout') },
      { res: { code: 200, data: { sessionId: 42, version: 4, config: {}, playKit: {
        typeIn: { enabled: true, target: 'hi', seconds: 10, started }
      } } } }]);
    const failed = [];
    game.triggerEvent = (name, detail) => { if (name === 'actionfail') failed.push({ detail, unknown: game.data.unknown }); };
    await game.action('START_CHALLENGE', { game: 'typeIn' });
    await new Promise(setImmediate);
    assert.equal(game.data.unknown, false);
    assert.equal(game.data.state.playKit.typeIn.started, started);
    assert.equal(failed.length, started ? 0 : 1);
    if (!started) {
      assert.equal(failed[0].unknown, false, '先解除unknown再通知可重试');
      assert.match(game.data.error, /没有提交成功/);
    }
  }
});

test('打字回读缺started事实保持unknown，禁止重复开表直到能核对', async () => {
  const game = createGame([{ err: new Error('timeout') },
    { res: { code: 200, data: { sessionId: 42, version: 4, config: {}, playKit: { typeIn: { target: 'hi' } } } } }]);
  await game.action('START_CHALLENGE', { game: 'typeIn' });
  await new Promise(setImmediate);
  assert.equal(game.data.unknown, true);
  assert.equal(game.data.readingBack, false);
  assert.match(game.data.unknownText, /开始计时|核对/);
  await game.action('START_CHALLENGE', { game: 'typeIn' });
  assert.equal(game.calls.length, 2, '没有第三个重复写入');
});

test('切换到另一局后旧开表响应不覆盖新局或弹旧失败出口', async () => {
  const game = createGame([]);
  let resolve;
  const failed = [];
  game.request = () => new Promise(done => { resolve = done; });
  game.triggerEvent = (name, detail) => { if (name === 'actionfail') failed.push(detail); };
  const result = game.action('START_CHALLENGE', { game: 'typeIn' });
  game.data.state = { sessionId: 99, version: 1, config: {} };
  resolve({ code: 200, data: { sessionId: 42, version: 4, playKit: { typeIn: { started: true } } } });
  await result;
  assert.equal(game.data.state.sessionId, 99);
  assert.equal(game.data.state.version, 1);
  assert.deepEqual(failed, []);
});

test('CAS二阶段权威回读迟到不能覆盖同节点新局或解除其写锁', async () => {
  for (const nextNode of [7, 9]) {
    const game = createGame([]);
    game.data.node = { nodeId: 7 };
    let finishRead;
    game.request = url => url.endsWith('/action')
      ? Promise.resolve({ code: 500, msg: '状态已更新，请刷新后重试' })
      : new Promise(resolve => { finishRead = resolve; });
    const events = [];
    game.triggerEvent = (name, detail) => events.push({ name, detail });
    const old = game.action('START_CHALLENGE', { game: 'typeIn' });
    await new Promise(setImmediate);
    game.data.node = { nodeId: nextNode };
    game.data.state = { sessionId: 99, nodeId: nextNode, version: 1, config: {} };
    game.data.acting = true;
    game.data.unknown = true;
    game.data.error = '新局写入待确认';
    finishRead({ code: 200, data: { sessionId: 42, nodeId: 7, version: 4, config: {} } });
    await old;
    assert.equal(game.data.state.sessionId, 99);
    assert.equal(game.data.state.version, 1);
    assert.equal(game.data.acting, true);
    assert.equal(game.data.unknown, true);
    assert.equal(game.data.error, '新局写入待确认');
    assert.deepEqual(events, []);
  }
});

test('独立refreshState的迟到响应、返回异局身份、节点上下文变化均不hydrate', async () => {
  for (const kind of ['new-session', 'alien-payload', 'new-node']) {
    const game = createGame([]);
    game.data.node = { nodeId: 7 };
    let finish;
    game.request = () => new Promise(resolve => { finish = resolve; });
    const old = game.refreshState();
    if (kind === 'new-session') game.data.state = { sessionId: 99, version: 1, config: {} };
    if (kind === 'new-node') game.data.node = { nodeId: 9 };
    const before = game.data.state;
    game.data.acting = true;
    game.data.unknown = true;
    finish({ code: 200, data: { sessionId: kind === 'alien-payload' ? 88 : 42, nodeId: 7, version: 9, config: {} } });
    assert.equal(await old, false);
    assert.equal(game.data.state, before);
    assert.equal(game.data.acting, true);
    assert.equal(game.data.unknown, true);
  }
});

test('unknown权威读返回异局事实时保留锁，不再次写入', async () => {
  const game = createGame([{ res: { code: 200, data: { sessionId: 88, version: 4, config: {}, playKit: { typeIn: { started: true } } } } }]);
  game.data.unknown = true;
  game._unknownAction = 'START_CHALLENGE'; game._unknownChallengeGame = 'typeIn';
  await game.resolveUnknown();
  assert.equal(game.data.state.sessionId, 42);
  assert.equal(game.data.unknown, true);
  assert.equal(game.data.readingBack, false);
  await game.action('START_CHALLENGE', { game: 'typeIn' });
  assert.equal(game.calls.length, 1);
});
