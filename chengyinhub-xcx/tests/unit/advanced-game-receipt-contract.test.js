const assert = require('node:assert/strict');
const test = require('node:test');

const COMPONENT_PATH = require.resolve('../../pages/play/components/advanced-game/index.js');
let definition;
const appStub = { sendRequest() {}, getUserID: () => 7 };
let photoStorage;

function loadDefinition() {
  const previousComponent = global.Component;
  const previousGetApp = global.getApp;
  global.Component = (options) => { definition = options; };
  global.getApp = () => appStub;
  delete require.cache[COMPONENT_PATH];
  require(COMPONENT_PATH);
  global.Component = previousComponent;
  global.getApp = previousGetApp;
}
loadDefinition();

/** queue: 每次 request 按顺序取一项；{ res } 走 resolve，{ err } 走 reject。 */
function createGame(queue, state) {
  const storage = new Map();
  global.wx = photoStorage || { getStorageSync: k => storage.get(k), setStorageSync: (k,v) => storage.set(k,v), removeStorageSync: k => storage.delete(k) };
  const calls = [];
  global.wx = global.wx || {};
  if (!global.wx.getStorageSync) global.wx.getStorageSync = () => undefined;
  const instance = {
    properties: {},
    data: Object.assign(JSON.parse(JSON.stringify(definition.data)), {
      node: { nodeId: 7 },
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
    if (next.receipt) return Promise.resolve({ code: 200, data: { sessionId: String(data.sessionId), idempotencyKey: data.idempotencyKey, view: next.receipt } });
    return next.err ? Promise.reject(next.err) : Promise.resolve(next.res);
  };
  instance._unknownCommand = { sessionId: 42, version: 3, idempotencyKey: 'original', action: 'DRAW' };
  instance._unknownRequestContext = instance.sessionRequestContext();
  instance.calls = calls;
  return instance;
}

const okAction = (version) => ({ res: { code: 200, data: { sessionId: 42, version, status: 'RUNNING', draws: [], config: {} } } });

test('失败玩法重新进入只恢复结果；明确重试走现有带版本幂等动作', async () => {
  const failed = { sessionId: 42, version: 3, status: 'FAILED', canRetry: true, ownerType: 1, config: {}, draws: [] };
  const game = createGame([{ res: { code: 200, data: failed } }, okAction(4)], failed);
  game.start();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(game.data.state.status, 'FAILED');
  assert.equal(game.calls.length, 1);
  assert.equal(game.calls[0].url, '/api/play/advanced/start');
  await game.retry();
  assert.equal(game.calls.length, 2);
  assert.equal(game.calls[1].url, '/api/play/advanced/action');
  assert.equal(game.calls[1].data.action, 'RETRY');
  assert.equal(game.calls[1].data.version, 3);
  assert.ok(game.calls[1].data.idempotencyKey);
  assert.equal(game.data.state.status, 'RUNNING');
  assert.equal(game.data.state.version, 4);
});

test('非队长、缺服务端重试授权、运行中和未知结果均不能重试', () => {
  for (const patch of [
    { status: 'FAILED', ownerType: 2, canRetry: false },
    { status: 'FAILED', ownerType: 2 },
    { status: 'RUNNING', canRetry: true },
    { status: 'COMPLETED', canRetry: true },
  ]) {
    const game = createGame([], patch);
    assert.equal(game.retry(), false);
    assert.equal(game.calls.length, 0);
  }
  const game = createGame([], { status: 'EXPIRED', canRetry: true, ownerType: 2 });
  game.data.unknown = true;
  assert.equal(game.retry(), false);
  game.data.unknown = false;
  game.data.acting = true;
  assert.equal(game.retry(), false);
  assert.equal(game.calls.length, 0);
});

test('重试撞到队长或版本变化，只读权威状态，不自动换版本再次重开', async () => {
  const game = createGame([{ res: { code: 409, data: { view: {
    sessionId: 42, version: 4, status: 'FAILED', canRetry: false, ownerType: 2, config: {}, draws: []
  } } } }], { status: 'FAILED', canRetry: true, ownerType: 2 });
  await game.retry();
  assert.equal(game.calls.length, 1);
  assert.equal(game.data.state.version, 4);
  assert.equal(game.data.state.canRetry, false);
  assert.equal(game.retry(), false);
});

test('多人角色读取权威分配：队长显示标签，队员不可把隐藏角色误写成待分配', () => {
  const game = createGame([]);
  const members = [{ memberId: 7, name: '甲' }, { memberId: 8, name: '乙' }];
  game.hydrate({ sessionId: 42, config: { multiplayer: { enabled: true,
    roles: [{ id: 'starter', label: '风味描述者' }, { id: 'finisher', label: '路线选择者' }] } },
    multiplayer: { members, roleAssignments: { 7: 'starter', 8: 'finisher' } } });
  assert.deepEqual(game.data.members.map(member => member.roleText), ['风味描述者', '路线选择者']);
  game.hydrate({ sessionId: 42, config: { multiplayer: { enabled: true } },
    multiplayer: { members, myRole: 'starter', myRoleLabel: '风味描述者' } });
  assert.equal(game.data.state.multiplayer.myRoleLabel, '风味描述者');
  game.hydrate(game.data.state);
  assert.ok(game.data.members.every(member => member.roleText === '角色仅本人可见' && member.roleId === ''));
  const wxml = require('node:fs').readFileSync(require.resolve('../../pages/play/components/advanced-game/index.wxml'), 'utf8');
  assert.match(wxml, /我的角色.*state\.multiplayer\.myRoleLabel/);
  game.hydrate({ sessionId: 'preview', config: { multiplayer: { enabled: true } },
    multiplayer: { members, roles: { 7: 'starter' } } });
  assert.deepEqual(game.data.members.map(member => member.roleText), ['starter', '待分配'], '既有预览角色字段仍兼容');
});

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

test('原请求回执匹配才解锁；读取不会再次写入', async () => {
  const game = createGame([{ err: new Error('timeout') },
    { receipt: { sessionId: 42, version: 4, status: 'RUNNING', draws: [{ id: 'x' }], config: {} } }]);
  await game.action('DRAW', {}); await new Promise(setImmediate);
  assert.equal(game.data.unknown, false);
  assert.equal(game.data.state.version, 4);
  assert.equal(game.data.error, '');
  assert.equal(game.calls[1].url, '/api/play/advanced/receipt');
  assert.equal(game.calls[1].method, 'GET');
  assert.equal(game.calls[1].data.idempotencyKey, game.calls[0].data.idempotencyKey);
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

  assert.equal(game.data.unknown, true, '队友版本增长不能解除本人UNKNOWN');
  assert.equal(game.data.error, '');
  assert.match(game.data.unknownText, /未查到不代表未提交/);
  assert.equal(game.calls.filter(c => c.method === 'POST').length, 1);
});

test('unknown 回读版本未变仍不能证明未写入，保持冻结', async () => {
  const game = createGame([
    { err: new Error('timeout') },
    { res: { code: 200, data: { sessionId: 42, version: 3, status: 'RUNNING', draws: [], config: {} } } },
  ]);
  await game.action('DRAW', {});
  await new Promise(setImmediate);
  await new Promise(setImmediate);

  assert.equal(game.data.unknown, true);
  assert.match(game.data.unknownText, /未查到不代表未提交/);
});

test('回读本身也失败 ⇒ 保持 unknown 锁定，不猜结论', async () => {
  const game = createGame([{ err: new Error('timeout') }, { err: new Error('still offline') }]);
  await game.action('DRAW', {});
  await new Promise(setImmediate);
  await new Promise(setImmediate);

  assert.equal(game.data.unknown, true, '确认不上就得一直锁着');
  assert.equal(game.data.readingBack, false, '要能再点一次确认');
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

test('通用错误文案不能证明回滚，查询原回执且保持UNKNOWN', async () => {
  const game = createGame([
    { res: { code: 500, msg: '状态已更新，请刷新后重试' } },
    { res: { code: 200, data: { sessionId: 42, version: 9, status: 'RUNNING', draws: [], config: {} } } },
  ]);
  await game.action('DRAW', {});
  await new Promise(setImmediate);
  await new Promise(setImmediate);

  assert.equal(game.calls[1].url, '/api/play/advanced/receipt');
  assert.equal(game.calls[1].method, 'GET');
  assert.equal(game.data.unknown, true);
  assert.equal(game.data.state.version, 3);
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
  const game = createGame([{ res: { code: 409, msg: '不能开始这一局', data: { view: { sessionId: 42, version: 3, config: {} } } } }]);
  const failed = [];
  game.triggerEvent = (name, detail) => { if (name === 'actionfail') failed.push(detail); };
  await game.action('START_CHALLENGE', { game: 'typeIn' });
  assert.equal(game.data.acting, false);
  assert.equal(game.data.unknown, false);
  assert.equal(failed.length, 1);
  assert.equal(failed[0].action, 'START_CHALLENGE');
  assert.equal(failed[0].sessionId, 42);
});

test('限时打字原请求回执恢复started事实，不重新起表', async () => {
  const game = createGame([{ err: new Error('timeout') }, { receipt: { sessionId: 42, version: 4,
    config: {}, playKit: { typeIn: { enabled: true, target: 'hi', seconds: 10, started: true } } } }]);
  await game.action('START_CHALLENGE', { game: 'typeIn' }); await new Promise(setImmediate);
  assert.equal(game.data.unknown, false);
  assert.equal(game.data.state.playKit.typeIn.started, true);
  assert.equal(game.calls.filter(c => c.method === 'POST').length, 1);
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

function failedRealRefresh() {
  let app;
  const previousApp = global.App;
  const appPath = require.resolve('../../app.js');
  try {
    global.App = value => { app = value; };
    delete require.cache[appPath];
    require(appPath);
  } finally { global.App = previousApp; }
  const { createSessionStore } = require('../../utils/session/session-store.js');
  const { createSessionManager } = require('../../utils/session/session-manager.js');
  const session = createSessionStore({ target: {}, wx: {
    setStorageSync() {}, removeStorageSync() {}, getStorageSync() { return ''; }
  } });
  session.setUserID(7);
  const manager = createSessionManager({ session,
    wxLogin: () => Promise.reject(new Error('wx login failed')),
    exchangeCode: () => { throw new Error('failed login must not exchange'); }
  });
  app.getSession = () => session;
  app.getSessionManager = () => manager;
  appStub.getUserID = () => app.getUserID();
  return async () => {
    assert.equal(await new Promise(resolve => app.reLogin(resolve)), false);
    assert.ok(!app.getUserID(), 'real session clear removes member identity');
  };
}

for (const method of ['start', 'action', 'resolveUnknown', 'loadLeaderboard', 'refreshState']) {
  for (const completion of ['success', 'fail']) {
    test(`真实静默重登失败清身份后 ${method} 的旧${completion}回包不改变原组件`, async () => {
      const clearIdentity = failedRealRefresh();
      try {
        const game = createGame([]);
        game.data.node = { nodeId: 7 };
        game.data.topicId = 11;
        game.data.state.nodeId = 7;
        let resolve, reject;
        const requests = [];
        game.request = url => { requests.push(url); return new Promise((done, fail) => { resolve = done; reject = fail; }); };
        const events = [];
        game.triggerEvent = (name, detail) => events.push({ name, detail });
        game._unknownVersion = 3;
        if (method === 'resolveUnknown') { game.data.unknown = true; game._unknownRequestContext = game.sessionRequestContext(); }
        const old = method === 'action' ? game.action('DRAW', {}) : game[method]();
        await clearIdentity();
        assert.equal(game._requestEpoch || 0, method === 'start' ? 1 : 0,
          'actual relogin does not detach the original component');
        const before = JSON.parse(JSON.stringify(game.data));
        if (completion === 'fail') reject(new Error('late response failed'));
        else resolve({ code: 200, data: method === 'loadLeaderboard'
          ? [{ memberId: 7, score: 9 }]
          : { sessionId: 42, nodeId: 7, version: 9, config: {} } });
        await old;
        await new Promise(setImmediate);
        assert.deepEqual(game.data, before, 'old identity cannot hydrate, unlock or replace failure/leaderboard state');
        assert.deepEqual(events, [], 'old session facts must not propagate to the page');
        assert.equal(requests.length, 1, 'old callback must not start another read or write after identity clear');
      } finally { appStub.getUserID = () => 7; }
    });
  }
}

test('同一账号正常续期保留在途回读；身份清空后重新start不永久卡住', async () => {
  const game = createGame([]);
  let finish;
  game.request = () => new Promise(resolve => { finish = resolve; });
  const old = game.refreshState();
  appStub.getUserID = () => '7';
  finish({ code: 200, data: { sessionId: 42, version: 4, config: {} } });
  assert.equal(await old, true, 'same member numeric/string representation remains compatible');
  appStub.getUserID = () => 7;
  game.data.loading = true;
  game.data.node = { nodeId: 7 };
  game._startingContext = Object.assign(game.sessionRequestContext(), { memberId: '' });
  game.start();
  assert.ok(finish);
  finish({ code: 200, data: { sessionId: 43, nodeId: 7, version: 1, config: {} } });
  await new Promise(setImmediate);
  assert.equal(game.data.state.sessionId, 43);
  assert.equal(game.data.loading, false);
});

test('正常卸载仍按epoch拒绝旧回读，预览启动不新增网络或身份要求', async () => {
  const game = createGame([]);
  let finish;
  game.request = () => new Promise(resolve => { finish = resolve; });
  const before = game.data.state;
  const old = game.refreshState();
  definition.lifetimes.detached.call(game);
  finish({ code: 200, data: { sessionId: 42, version: 9, config: {} } });
  assert.equal(await old, false);
  assert.equal(game.data.state, before);
  try {
    appStub.getUserID = () => '';
    game.data.preview = true;
    game.data.node = { nodeId: 7, advancedConfig: {} };
    game.request = () => { assert.fail('preview must not use the network'); };
    game.start();
    assert.equal(game.data.state.sessionId, 'preview');
  } finally { appStub.getUserID = () => 7; }
});

for (const nextStatus of ['RUNNING', 'COMPLETED', 'FAILED', 'EXPIRED']) {
  test(`同局旧回读不能覆盖新动作或 ${nextStatus} 状态`, async () => {
    const game = createGame([]);
    game.data.node = { nodeId: 7 };
    let finishRead;
    let stateReads = 0;
    game.request = url => url.endsWith('/state') && stateReads++ === 0
      ? new Promise(resolve => { finishRead = resolve; })
      : Promise.resolve({ code: 200, data: { sessionId: 42, nodeId: 7, version: 4,
        status: nextStatus, config: {}, vars: { stage: 'after' }, readyForBase: true } });
    const events = [];
    game.triggerEvent = (name, detail) => events.push({ name, detail });
    const old = game.refreshState();
    assert.equal(await (nextStatus === 'RUNNING' ? game.action('DRAW', {}) : game.refreshState()), true);
    const current = game.data.state;
    finishRead({ code: 200, data: { sessionId: 42, nodeId: 7, version: 3,
      status: 'RUNNING', config: {}, vars: { stage: 'before' }, readyForBase: false } });
    assert.equal(await old, false);
    assert.equal(game.data.state, current);
    assert.equal(game.data.state.status, nextStatus);
    assert.equal(game.data.state.vars.stage, 'after');
    assert.equal(events.filter(e => e.name === 'session').length, 1);
  });
}

test('旧原请求回执可以确认动作但不得倒退已读的新版本', async () => {
  const game = createGame([]); game.data.unknown = true;
  let finish; game.request = () => new Promise(resolve => { finish = resolve; });
  const old = game.resolveUnknown();
  game.hydrate({ sessionId: 42, version: 5, config: {}, vars: { stage: 'latest' } });
  const current = game.data.state;
  finish({ code: 200, data: { sessionId: '42', idempotencyKey: 'original', view: { sessionId: 42, version: 4, config: {} } } });
  await old;
  assert.equal(game.data.state, current);
  assert.equal(game.data.unknown, false);
  assert.equal(game.data.readingBack, false);
});

test('通用错误后的原回执迟到不倒退视图，也不冒充确定拒绝', async () => {
  const game = createGame([]);
  let finish;
  game.request = url => url.endsWith('/action')
    ? Promise.resolve({ code: 500, msg: '状态已更新，请刷新后重试' })
    : new Promise(resolve => { finish = resolve; });
  const events = [];
  game.triggerEvent = (name, detail) => events.push({ name, detail });
  const old = game.action('DRAW', {});
  await new Promise(setImmediate);
  game.hydrate({ sessionId: 42, version: 5, config: {} });
  const current = game.data.state;
  finish({ code: 200, data: { sessionId: 42, version: 4, config: {} } });
  await old;
  assert.equal(game.data.state, current);
  assert.equal(game.data.acting, false);
  assert.equal(game.data.unknown, true);
  assert.equal(game.data.error, '');
  assert.equal(events.filter(e => e.name === 'actionfail').length, 0);
  assert.equal(events.filter(e => e.name === 'session').length, 1);
});

test('同局等版本仍可接受，新局低版本不能被旧局高版本挡住', async () => {
  const game = createGame([]);
  game.hydrate({ sessionId: 42, version: 9, config: {} });
  game.request = () => Promise.resolve({ code: 200, data: { sessionId: 42, version: 9, config: {}, vars: { equal: true } } });
  assert.equal(await game.refreshState(), true);
  assert.equal(game.data.state.vars.equal, true);
  game.data.node = { nodeId: 7 };
  game.request = () => Promise.resolve({ code: 200, data: { sessionId: 99, nodeId: 7, version: 1, config: {} } });
  game.start();
  await new Promise(setImmediate);
  assert.equal(game.data.state.sessionId, 99);
  assert.equal(game.data.state.version, 1);
});

test('团队挑战只读本人原请求，队友版本增长不改写历史进度', async () => {
  const state = { sessionId: 51, nodeId: 7, ownerType: 2, ownerId: 88, version: 0, config: {},
    challenge: { instanceId: 'one', progress: 0, goal: 10, success: false } };
  const game = createGame([{ err: new Error('timeout') }, { err: new Error('offline') },
    { receipt: { ...state, version: 1, challenge: { ...state.challenge, progress: 6 } } }], state);
  game.data.node = { nodeId: 7 };
  await game.action('SUBMIT_BALL_SHAKE', { hits: 6 }); await new Promise(setImmediate);
  assert.equal(game.data.unknown, true);
  const original = game.calls[0].data;
  game.data.state.version = 9;
  assert.equal(await game.resolveUnknown(), true);
  assert.equal(game.calls.filter(c => c.method === 'POST').length, 1);
  for (const call of game.calls.slice(1)) {
    assert.equal(call.method, 'GET'); assert.equal(call.url, '/api/play/advanced/receipt');
    assert.deepEqual(call.data, { sessionId: original.sessionId, idempotencyKey: original.idempotencyKey });
  }
  assert.equal(game.data.state.version, 9);
  assert.equal(game.data.state.challenge.progress, 0, '历史回执不覆盖当前视图');
  assert.equal(game.data.state.challenge.success, false);
  assert.equal(game.data.unknown, false);
});

test('团队挑战原回执查询冲突不能解除UNKNOWN或发起state副作用', async () => {
  const state = { ownerType: 2, nodeId: 7, challenge: { progress: 0, goal: 10, success: false } };
  const game = createGame([{ err: new Error('timeout') }, { res: { code: 409, msg: '原回执冲突' } }], state);
  game.data.node = { nodeId: 7 };
  await game.action('SUBMIT_BALL_SHAKE', { hits: 6 }); await new Promise(setImmediate);
  assert.equal(game.data.unknown, true); assert.equal(game.data.error, '');
  assert.equal(game.data.state.challenge.success, false); assert.equal(game.data.state.version, 3);
  assert.equal(game.calls.length, 2); assert.equal(game.calls[1].method, 'GET');
});

test('共享挑战旧原键回执迟到不能覆盖新局或解除新局写锁', async () => {
  const game=createGame([],{ownerType:2,nodeId:7});game.data.node={nodeId:7};
  game._unknownCommand={sessionId:42,version:3,idempotencyKey:'original',action:'SUBMIT_BALL_SHAKE',payload:{hits:6}};
  game._unknownRequestContext=game.sessionRequestContext();
  let finish;game.request=()=>new Promise(resolve=>{finish=resolve;});
  const waiting=game.resolveUnknown();
  game.data.state={sessionId:99,version:1,nodeId:7,ownerType:2,config:{}};game.data.unknown=true;game.data.acting=true;game.data.error='新局待确认';
  finish({code:200,data:{sessionId:42,nodeId:7,ownerType:2,version:4,challenge:{progress:6,goal:10,success:false}}});await waiting;
  assert.equal(game.data.state.sessionId,99);assert.equal(game.data.unknown,true);assert.equal(game.data.acting,true);assert.equal(game.data.error,'新局待确认');
});


test('零血恢复必须明确重试，接受同属新会话并解除提交锁', async () => {
  const next = { sessionId: 43, retrySourceSessionId: 42, nodeId: 9, version: 0, status: 'RUNNING',
    ownerType: 1, ownerId: 7, config: {}, draws: [], challenge: { instanceId: 'next' } };
  const game = createGame([{ res: { code: 200, data: next } }], { status: 'FAILED', canRetry: true,
    ownerType: 1, ownerId: 7, challenge: { instanceId: 'old' } });
  game.data.node = { nodeId: 9 };
  await game.retry();
  assert.equal(game.calls.length, 1);
  assert.equal(game.calls[0].data.sessionId, 42);
  assert.equal(game.calls[0].data.action, 'RETRY');
  assert.equal(game.data.state.sessionId, 43);
  assert.equal(game.data.acting, false);
  assert.equal(game.data.unknown, false);
});

test('零血重试丢失响应后个人局也按原键核对新会话，不能猜版本成功', async () => {
  const next = { sessionId: 43, retrySourceSessionId: 42, nodeId: 9, version: 0, status: 'RUNNING',
    ownerType: 1, ownerId: 7, config: {}, draws: [], challenge: { instanceId: 'next' } };
  const game = createGame([{ err: new Error('lost response') }, { receipt: next }],
    { status: 'FAILED', canRetry: true, ownerType: 1, ownerId: 7, challenge: { instanceId: 'old' } });
  game.data.node = { nodeId: 9 };
  await game.retry();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(game.calls.length, 2);
  assert.equal(game.calls[1].method, 'GET');
  assert.deepEqual(game.calls[1].data, { sessionId: game.calls[0].data.sessionId, idempotencyKey: game.calls[0].data.idempotencyKey });
  assert.equal(game.data.state.sessionId, 43);
  assert.equal(game.data.unknown, false);
  assert.equal(game.data.readingBack, false);
});

test('新挑战回执必须属于原会话、原成员和原节点，普通动作不能接受换局', () => {
  const game = createGame([], { ownerType: 1, ownerId: 7, challenge: { instanceId: 'old' } });
  game.data.node = { nodeId: 9 };
  const context = game.sessionRequestContext();
  const next = { sessionId: 43, retrySourceSessionId: 42, nodeId: 9, ownerType: 1, ownerId: 7 };
  assert.equal(game.responseMatchesSession(next, context, 'RETRY'), true);
  assert.equal(game.responseMatchesSession({ ...next, sessionId: '9007199254740993' }, context, 'RETRY'), true);
  for (const patch of [{ retrySourceSessionId: 99 }, { nodeId: 8 }, { ownerId: 8 }, { sessionId: 9007199254740992 }]) {
    assert.equal(game.responseMatchesSession({ ...next, ...patch }, context, 'RETRY'), false);
  }
  assert.equal(game.responseMatchesSession(next, context, 'SUBMIT_QA'), false);
});


test('已完成一次但恢复次数不足的零血挑战仍需明确继续，普通完成局不允许重试', async () => {
  const game = createGame([{ res: { code: 200, data: { sessionId: 43, retrySourceSessionId: 42, nodeId: 9, version: 0,
    status: 'RUNNING', ownerType: 1, ownerId: 7, config: {}, draws: [], challenge: { instanceId: 'next' } } } }],
    { status: 'COMPLETED', canRetry: true, ownerType: 1, ownerId: 7, challenge: { gameCompleted: true, success: false } });
  game.data.node = { nodeId: 9 };
  await game.retry();
  assert.equal(game.calls.length, 1);
  assert.equal(game.calls[0].data.action, 'RETRY');
  assert.equal(game.data.state.sessionId, 43);
});

test('组件重建后仅查询持久化原请求，不重新start或保存照片输入', async () => {
  const first = createGame([{ err: new Error('timeout') }, { err: new Error('offline') }]);
  await first.action('SUBMIT_QA', { imageUrl: 'private-photo', answer: 'private-answer' });
  await new Promise(setImmediate);
  const savedWx = global.wx;
  const saved = savedWx.getStorageSync(first.pendingKey());
  assert.equal(saved.command.action, 'SUBMIT_QA');
  assert.equal(saved.command.payload, undefined);
  assert.doesNotMatch(JSON.stringify(saved), /private-photo|private-answer/);
  const second = createGame([{ receipt: { sessionId: 42, version: 4, config: {} } }]);
  global.wx = savedWx;
  await second.start();
  assert.equal(second.calls.length, 1);
  assert.equal(second.calls[0].url, '/api/play/advanced/receipt');
  assert.equal(second.calls[0].method, 'GET');
  assert.equal(second.calls[0].data.idempotencyKey, saved.command.idempotencyKey);
  assert.equal(second.data.unknown, false);
  assert.equal(savedWx.getStorageSync(second.pendingKey()), undefined);
});

test('持久化失败先于网络写入，不能丢失原请求身份', async () => {
  const game = createGame([]);
  global.wx.setStorageSync = () => { throw new Error('full'); };
  await game.action('DRAW', {});
  assert.equal(game.calls.length, 0);
  assert.equal(game.data.acting, false);
  assert.match(game.data.error, /尚未提交/);
});

test('unsafe numeric node identity cannot be rounded into a start request', () => {
  const game = createGame([]); game.data.node = { nodeId: 9007199254740992 };
  game.start();
  assert.equal(game.calls.length, 0);
  assert.match(game.data.error, /标识不完整/);
});


function photoDisk() {
  const rows = new Map();
  photoStorage = global.wx = { getStorageSync: key => rows.get(key),
    setStorageSync: (key, value) => rows.set(key, JSON.parse(JSON.stringify(value))),
    removeStorageSync: key => rows.delete(key) };
  return rows;
}
const photoInput = () => ({ imageUrl: 'media-asset:701' });
const tick = () => new Promise(setImmediate);

test('Photo 520 保留原 key/输入/expected；个人局只读原回执，版本变化不推断成功', async () => {
  photoDisk();
  const game = createGame([{ res: { code: 520, intentUnknown: true } }, { res: { code: 520 } }], { ownerType: 1 });
  await game.action('SUBMIT_PHOTO_CHECK', photoInput()); await tick();
  assert.equal(game.data.unknown, true); assert.equal(game.calls.length, 2);
  assert.deepEqual(game.calls[1], { url: '/api/play/advanced/receipt', method: 'GET',
    data: { sessionId: 42, idempotencyKey: game.calls[0].data.idempotencyKey } });
  assert.equal(game.calls.filter(c => c.method === 'POST').length, 1);
  assert.deepEqual(game._unknownCommand, game.calls[0].data);
  game.data.state.version = 9;
  game.request = async (url, method, data) => { game.calls.push({ url, method, data }); return { code: 520 }; };
  await game.resolveUnknown();
  assert.deepEqual(game.calls[2], game.calls[1]); assert.equal(game._unknownCommand.version, 3); assert.equal(game.data.unknown, true);
});

test('Photo 已保存原结果但玩法冲突，不清 pending、不换版本重发', async () => {
  const disk = photoDisk();
  const game = createGame([{ res: { code: 409, data: { view: { sessionId: 42, version: 9 } } } }, { res: { code: 520 } }]);
  await game.action('SUBMIT_PHOTO_CHECK', photoInput()); await tick();
  assert.equal(game.data.unknown, true); assert.equal(game.data.state.version, 3); assert.ok(disk.size > 0);
  assert.equal(game._unknownCommand.version, 3);
  assert.equal(game.calls[1].url, '/api/play/advanced/receipt'); assert.equal(game.calls[1].method, 'GET');
});

test('Photo 重启先读取原 session，用户核对只读原回执，不自动启动另一局', async () => {
  photoDisk();
  const first = createGame([{ err: Error('response lost') }, { res: { code: 520 } }]);
  await first.action('SUBMIT_PHOTO_CHECK', photoInput()); await tick();
  const recovered = createGame([okAction(8)], { sessionId: 99 });
  await recovered.start();
  assert.equal(recovered.calls.length, 1); assert.equal(recovered.calls[0].method, 'GET');
  assert.deepEqual(recovered.calls[0].data, { sessionId: 42 }); assert.equal(recovered.data.state.sessionId, 42);
  assert.equal(recovered.data.unknown, true);
  recovered.request = async (url, method, data) => { recovered.calls.push({ url, method, data });
    return { code: 200, data: { sessionId: String(data.sessionId), idempotencyKey: data.idempotencyKey, view: okAction(4).res.data } }; };
  await recovered.resolveUnknown();
  assert.deepEqual(recovered.calls[1], { url: '/api/play/advanced/receipt', method: 'GET',
    data: { sessionId: 42, idempotencyKey: first.calls[0].data.idempotencyKey } });
  assert.equal(recovered.data.unknown, false);
  assert.equal(recovered.calls.some(c => c.method === 'POST'), false);
});

test('Photo A UNKNOWN 切 B retry 与 A→B→A 同页迟到均不再发 A 命令', async () => {
  photoDisk(); appStub._featureFlagsEpoch = 10;
  try {
    const game = createGame([{ res: { code: 520 } }, { res: { code: 520 } }]);
    await game.action('SUBMIT_PHOTO_CHECK', photoInput()); await tick();
    const before = game.calls.length;
    appStub.getUserID = () => 8; appStub._featureFlagsEpoch++;
    assert.equal(await game.resolveUnknown(), false); assert.equal(game.calls.length, before);
    appStub.getUserID = () => 7; appStub._featureFlagsEpoch++;
    assert.equal(await game.resolveUnknown(), false); assert.equal(game.calls.length, before);
  } finally { appStub.getUserID = () => 7; delete appStub._featureFlagsEpoch; }
});

test('Photo A 迟到成功仅清 A 原键，不清 B pending，不改变 B UI', async () => {
  const disk = photoDisk(); appStub._featureFlagsEpoch = 20;
  try {
    const a = createGame([]); let finish;
    a.request = () => new Promise(resolve => { finish = resolve; });
    const old = a.action('SUBMIT_PHOTO_CHECK', photoInput());
    appStub.getUserID = () => 8; appStub._featureFlagsEpoch++;
    const b = createGame([{ res: { code: 520 } }, { res: { code: 520 } }]);
    await b.action('SUBMIT_PHOTO_CHECK', photoInput()); await tick();
    const bKeys = [...disk.keys()].filter(k => k.startsWith('advanced_photo_pending:8:'));
    const before = JSON.parse(JSON.stringify(b.data));
    finish(okAction(4).res); await old;
    assert.deepEqual(b.data, before); assert.ok(bKeys.length > 0); assert.ok(bKeys.every(k => disk.has(k)));
    assert.ok(![...disk.keys()].some(k => k.startsWith('advanced_photo_pending:7:')));
  } finally { appStub.getUserID = () => 7; delete appStub._featureFlagsEpoch; }
});

test('Photo 仅显式 intentNotExecuted 释放原记录，缺媒体或存储失败零发送', async () => {
  const disk = photoDisk();
  const rejected = createGame([{ res: { code: 409, data: { intentNotExecuted: true,
    view: { sessionId: 42, version: 8 } } } }]);
  await rejected.action('SUBMIT_PHOTO_CHECK', photoInput()); assert.equal(rejected.data.unknown, false); assert.equal(disk.size, 0);
  const absent = createGame([]); await absent.action('SUBMIT_PHOTO_CHECK', { imageUrl: 'https://example.invalid/signed?token=private' });
  assert.equal(absent.calls.length, 0);
  global.wx.setStorageSync = () => { throw Error('storage full'); };
  const full = createGame([]); await full.action('SUBMIT_PHOTO_CHECK', photoInput()); assert.equal(full.calls.length, 0);
});

test('Photo 存储写入无异常但无法读回时明确拒绝，不发送原动作', async () => {
  photoDisk();
  global.wx.setStorageSync = () => {};
  const game = createGame([]);
  assert.equal(await game.action('SUBMIT_PHOTO_CHECK', photoInput()), false);
  assert.equal(game.calls.length, 0);
  assert.equal(game.data.acting, false);
  assert.equal(game.data.unknown, false);
  assert.match(game.data.error, /未保存/);
});

test('Photo A→B→A 的旧成功不改新登录代次 UI，原终态只清原存储键', async () => {
  const disk = photoDisk(); appStub._featureFlagsEpoch = 30;
  try {
    const game = createGame([]); let finish;
    game.request = () => new Promise(resolve => { finish = resolve; });
    const old = game.action('SUBMIT_PHOTO_CHECK', photoInput());
    appStub.getUserID = () => 8; appStub._featureFlagsEpoch++;
    appStub.getUserID = () => 7; appStub._featureFlagsEpoch++;
    const before = JSON.parse(JSON.stringify(game.data));
    finish(okAction(4).res); await old;
    assert.deepEqual(game.data, before); assert.equal(disk.size, 0);
  } finally { appStub.getUserID = () => 7; delete appStub._featureFlagsEpoch; }
});

test('Photo 在途时调用者改 payload，原发送、原存储和原重试仍冻结首次输入', async () => {
  const disk = photoDisk(); const game = createGame([]); let finish;
  game.request = (url, method, data) => {
    game.calls.push({ url, method, data });
    if (game.calls.length === 1) return new Promise(resolve => { finish = resolve; });
    return Promise.resolve({ code: 520 });
  };
  const payload = photoInput(); const waiting = game.action('SUBMIT_PHOTO_CHECK', payload);
  payload.imageUrl = 'media-asset:702'; finish({ code: 520 }); await waiting; await tick();
  assert.equal(game.calls[0].data.payload.imageUrl, 'media-asset:701');
  assert.deepEqual(game._unknownCommand, game.calls[0].data);
  assert.deepEqual(game.calls[1], { url: '/api/play/advanced/receipt', method: 'GET',
    data: { sessionId: 42, idempotencyKey: game.calls[0].data.idempotencyKey } });
  assert.ok([...disk.values()].some(value => value && value.command && value.command.payload.imageUrl === 'media-asset:701'));
});

test('共享高级动作 A UNKNOWN 切 B 及 A→B→A 不得用当前 token 重发旧命令', async () => {
  appStub._featureFlagsEpoch = 50;
  try {
    const game = createGame([{ err: Error('timeout') }, { err: Error('offline') }], { ownerType: 2 });
    await game.action('SUBMIT_BALL_SHAKE', { hits: 6 }); await tick();
    assert.equal(game.data.unknown, true); const count = game.calls.length;
    appStub.getUserID = () => 8; appStub._featureFlagsEpoch++;
    assert.equal(await game.resolveUnknown(), false); assert.equal(game.calls.length, count);
    appStub.getUserID = () => 7; appStub._featureFlagsEpoch++;
    assert.equal(await game.resolveUnknown(), false); assert.equal(game.calls.length, count);
  } finally { appStub.getUserID = () => 7; delete appStub._featureFlagsEpoch; }
});
