// Phase 1.2 单航班登录与续期 —— 先写失败测试(TDD RED)。
//
// 契约(摘自 Phase 1 不可破坏契约 #6/#14/#17/#20 + 假ready 修复 #19/#20):
//  - 单航班:并发 login/refresh 只触发一次 wx.login(并发 401 共享一次换 token)
//  - 成功:token 取响应顶层 token、其余取安全 data.*(双层 data),不接收/持久化 OpenID
//  - 正式版失败:返回显式失败结果,绝不伪装成已认证、绝不写会话
//  - 开发版:与正式版同一失败路径,不再写入假用户(F-W1-01 删除 useDevUser 兜底)
//  - 缓存短路:已有 user_id+authorization 直接成功,不发网络登录
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createSessionManager } = require('../../utils/session/session-manager.js');

// 记录式会话桩
function makeSession(initial) {
  const snap = Object.assign({ open_id: '', authorization: '', role: '' }, initial);
  return {
    snap,
    setOpenID: (v) => { snap.open_id = v; },
    setAuthorization: (v) => { snap.authorization = v; },
    setUserID: (v) => { snap.user_id = v; },
    setUserType: (v) => { snap.user_type = v; },
    setAvatar: (v) => { snap.avatar = v; },
    setNickname: (v) => { snap.nickname = v; },
    setUserRole: (v) => { if (v) snap.role = v; },
    getOpenID: () => snap.open_id,
    getUserID: () => snap.user_id,
    getAuthorization: () => snap.authorization,
  };
}

const okBody = {
  token: 'TOKEN_TOP', // 注意:token 在顶层
  data: { id: '7', userType: 2, avatar: 'a.png', nickname: '阿岚', role: 'merchant' },
};

function makeDeps(over) {
  const calls = { wxLogin: 0, exchange: 0, dev: 0 };
  const deps = {
    session: makeSession(),
    wxLogin: async () => { calls.wxLogin++; return { code: 'CODE' }; },
    exchangeCode: async () => { calls.exchange++; return okBody; },
    isDevEnv: () => false,
    useDevUser: () => { calls.dev++; },
  };
  return { deps: Object.assign(deps, over), calls };
}

test('login 成功:只落地安全会话字段,不持久化 OpenID', async () => {
  const { deps } = makeDeps();
  const mgr = createSessionManager(deps);
  const r = await mgr.login();
  assert.equal(r.ok, true);
  assert.equal(r.authed, true);
  assert.equal(deps.session.snap.authorization, 'TOKEN_TOP'); // 顶层 token
  assert.equal(deps.session.snap.open_id, '');
  assert.equal(deps.session.snap.user_id, '7');
  assert.equal(deps.session.snap.role, 'merchant');
});

test('支持原子快照的会话存储必须一次落地完整身份', async () => {
  const session = makeSession();
  let snapshot;
  session.applySnapshot = (value) => { snapshot = value; Object.assign(session.snap, value); };
  const { deps } = makeDeps({ session });
  const mgr = createSessionManager(deps);
  const result = await mgr.login();

  assert.equal(result.ok, true);
  assert.deepEqual(snapshot, {
    authorization: 'TOKEN_TOP',
    user_id: '7',
    user_type: 2,
    open_id: '',
    avatar: 'a.png',
    nickname: '阿岚',
    role: 'merchant',
  });
})

test('升级旧会话:初始化即清除历史 OpenID,不等待重新登录', () => {
  const { deps } = makeDeps({ session: makeSession({ open_id: 'legacy-openid' }) });

  createSessionManager(deps);

  assert.equal(deps.session.snap.open_id, '');
});

test('ensureSession 缓存短路:已有 user_id+authorization 不发网络登录', async () => {
  const { deps, calls } = makeDeps({ session: makeSession({ user_id: '7', authorization: 'tok' }) });
  const mgr = createSessionManager(deps);
  const r = await mgr.ensureSession();
  assert.equal(r.ok, true);
  assert.equal(r.cached, true);
  assert.equal(calls.wxLogin, 0);
  assert.equal(calls.exchange, 0);
});

test('正式版失败(无 data):显式失败,不写会话,不伪装已认证', async () => {
  const { deps } = makeDeps({ exchangeCode: async () => ({ msg: '登录失败' }) });
  const mgr = createSessionManager(deps);
  const r = await mgr.login();
  assert.equal(r.ok, false);
  assert.equal(r.authed, false);
  assert.equal(deps.session.snap.authorization, ''); // 未写
});

test('畸形 200 缺 token/id/受控 role 时 fail closed，且不部分写入会话', async () => {
  const cases = [
    { data: { id: '7', userType: 2, role: 'merchant' } },
    { token: 'TOKEN_TOP', data: { userType: 2, role: 'merchant' } },
    { token: 'TOKEN_TOP', data: { id: '7', userType: 2, role: 'root' } },
    { token: 'TOKEN_TOP', data: { id: '7', userType: 99, role: 'player' } },
  ];
  for (const body of cases) {
    const { deps } = makeDeps({ exchangeCode: async () => body });
    const mgr = createSessionManager(deps);
    const r = await mgr.login();
    assert.equal(r.ok, false);
    assert.equal(r.reason, 'invalidPayload');
    assert.equal(deps.session.snap.authorization, '');
    assert.equal(deps.session.snap.user_id, undefined);
  }
});

test('正式版 wx.login 失败:显式失败', async () => {
  const { deps } = makeDeps({ wxLogin: async () => { throw new Error('login fail'); } });
  const mgr = createSessionManager(deps);
  const r = await mgr.login();
  assert.equal(r.ok, false);
  assert.equal(r.authed, false);
});

test('wx.login 永不回调时在 deadline 后失败并释放单航班锁', async () => {
  const { deps } = makeDeps({
    wxLogin: () => new Promise(() => {}),
    loginTimeoutMs: 10,
  });
  const mgr = createSessionManager(deps);
  const r = await mgr.login();
  assert.equal(r.ok, false);
  assert.equal(r.reason, 'wxLoginFail');
  assert.equal(mgr.isLoggingIn(), false);
});

test('换 token 请求永不回调时在 deadline 后失败并释放单航班锁', async () => {
  const { deps } = makeDeps({
    exchangeCode: () => new Promise(() => {}),
    loginTimeoutMs: 10,
  });
  const mgr = createSessionManager(deps);
  const r = await mgr.login();
  assert.equal(r.ok, false);
  assert.equal(r.reason, 'networkFail');
  assert.equal(mgr.isLoggingIn(), false);
});

// F-W1-01:开发版曾在登录失败时写入假用户 id=1 / dev-token,401 静默重登失败后整个会话被换成假身份。
// 项目规则禁止假用户降级:即便注入了旧的 isDevEnv/useDevUser 依赖,也必须如实失败、不写会话。
for (const failure of ['wx', 'network', 'empty']) {
  test('开发版登录失败不再写入假用户: ' + failure, async () => {
    const { deps, calls } = makeDeps({ isDevEnv: () => true,
      wxLogin: failure === 'wx' ? async () => { throw new Error('login failed'); } : async () => ({ code: 'CODE' }),
      exchangeCode: failure === 'network' ? async () => { throw new Error('network failed'); } : async () => ({ msg: 'x' }) });
    const mgr = createSessionManager(deps);
    const r = await mgr.login();
    assert.equal(r.ok, false);
    assert.equal(r.authed, false);
    assert.equal(r.dev, undefined);
    assert.equal(r.reason, { wx: 'wxLoginFail', network: 'networkFail', empty: 'noData' }[failure]);
    assert.equal(calls.dev, 0);
    assert.equal(deps.session.snap.authorization, '');
    assert.equal(deps.session.snap.user_id, undefined);
  });
}

test('会话管理器源码不再包含假用户兜底', () => {
  const src = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../utils/session/session-manager.js'), 'utf8');
  assert.doesNotMatch(src, /useDevUser\s*\(|devFallback/);
  const app = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../app.js'), 'utf8');
  assert.doesNotMatch(app, /useDevUser|getDefaultUser/);
});

test('单航班:两个并发 login 只触发一次 wx.login', async () => {
  let resolveExchange;
  const gate = new Promise((res) => { resolveExchange = res; });
  const { deps, calls } = makeDeps({
    exchangeCode: async () => { calls.exchange++; await gate; return okBody; },
  });
  const mgr = createSessionManager(deps);
  const p1 = mgr.login();
  const p2 = mgr.login(); // 并发,inFlight 仍在
  resolveExchange();
  const [r1, r2] = await Promise.all([p1, p2]);
  assert.equal(r1.ok, true);
  assert.equal(r2.ok, true);
  assert.equal(calls.wxLogin, 1, 'wx.login 应只调一次');
});

test('单航班释放:前一轮结束后可再次登录', async () => {
  const { deps, calls } = makeDeps();
  const mgr = createSessionManager(deps);
  await mgr.login();
  await mgr.login();
  assert.equal(calls.wxLogin, 2);
});

// —— 已有 dev-token 会话(升级前残留)被拒:如实失败,不再续命假会话 ——
test('残留 dev token 被拒时如实失败,不再写假会话', async () => {
  const { deps, calls } = makeDeps({
    isDevEnv: () => true,
    exchangeCode: async () => ({ msg: '微信服务暂不可用' }),
    session: makeSession({ authorization: 'dev-token', user_id: '7' }),
  });
  const mgr = createSessionManager(deps);
  const r = await mgr.refresh();
  assert.equal(r.ok, false);
  assert.equal(r.reason, 'noData');
  assert.equal(calls.dev, 0);
});

function deferredLoginFixture(stage, dev) {
  const session = makeSession({ user_id: '7', authorization: 'A' });
  let finish, exchanges = 0, devCalls = 0;
  const pending = new Promise((resolve, reject) => { finish = value => value instanceof Error ? reject(value) : resolve(value); });
  const manager = createSessionManager({
    session,
    wxLogin: () => stage === 'wx' ? pending : Promise.resolve({ code: 'CODE' }),
    exchangeCode: () => { exchanges++; return stage === 'exchange' ? pending : Promise.resolve(okBody); },
    isDevEnv: () => !!dev,
    useDevUser: () => { devCalls++; session.setUserID('1'); session.setAuthorization('DEV'); }
  });
  return { session, manager, finish, exchanges: () => exchanges, devCalls: () => devCalls,
    async ready() { for (let i = 0; i < 8; i++) await Promise.resolve(); },
    switchSession(id = '9', token = 'B') { session.setUserID(id); session.setAuthorization(token); } };
}
for (const stage of ['wx', 'exchange']) {
  for (const outcome of ['success', 'failure']) {
    test('旧登录在换号后停止 ' + stage + '/' + outcome + '，不覆盖真实或开发会话', async () => {
      const f = deferredLoginFixture(stage, true);
      const promise = f.manager.refresh(); await f.ready();
      f.switchSession();
      f.finish(outcome === 'failure' ? new Error('fixture failure') : stage === 'wx' ? { code: 'CODE' } : okBody);
      const result = await promise;
      assert.equal(result.ok, false); assert.equal(result.reason, 'sessionChanged');
      assert.equal(f.devCalls(), 0);
      if (stage === 'wx') assert.equal(f.exchanges(), 0);
      assert.equal(f.session.getUserID(), '9'); assert.equal(f.session.getAuthorization(), 'B');
      assert.equal(f.manager.isLoggingIn(), false);
    });
  }
}
for (const replacement of [{ id: '', token: '' }, { id: '7', token: 'A-new' }]) {
  test('旧登录不能恢复已退出会话或覆盖同账号更新的 token: ' + replacement.id, async () => {
    const f = deferredLoginFixture('exchange');
    const promise = f.manager.login(); await f.ready();
    f.switchSession(replacement.id, replacement.token); f.finish(okBody);
    assert.equal((await promise).reason, 'sessionChanged');
    assert.equal(f.session.getUserID(), replacement.id);
    assert.equal(f.session.getAuthorization(), replacement.token);
  });
}
test('登录微任务开始前换号，不再发起旧 wx.login', async () => {
  const { deps, calls } = makeDeps({ session: makeSession({ user_id: '7', authorization: 'A' }) });
  const manager = createSessionManager(deps), promise = manager.login();
  deps.session.setUserID('9'); deps.session.setAuthorization('B');
  assert.equal((await promise).reason, 'sessionChanged');
  assert.equal(calls.wxLogin, 0); assert.equal(calls.exchange, 0);
});
test('换号导致旧单航班结束后，新会话仍能再次登录', async () => {
  const f = deferredLoginFixture('exchange');
  const old = f.manager.login(); assert.equal(f.manager.refresh(), old); await f.ready();
  f.switchSession(); f.finish(okBody); assert.equal((await old).reason, 'sessionChanged');
  f.switchSession('7', 'A'); assert.equal((await f.manager.refresh()).ok, true);
});

function loadOwnerApp(now) {
  const path = require('node:path'), fs = require('node:fs'), vm = require('node:vm');
  const root = path.join(__dirname, '../..');
  let app;
  const toasts = [], requests = [];
  vm.runInNewContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), {
    App: value => { app = value; }, wx: { request: options => { requests.push(options); } },
    require: file => file === './utils/toast.js' ? Object.assign(msg => toasts.push(msg), { hide() {} }) : require(path.join(root, file)),
    console
  }, { filename: 'app.js' });
  const { createSessionStore } = require('../../utils/session/session-store.js');
  const storage = new Map();
  const session = createSessionStore({ target: app.globalData, now, wx: {
    setStorageSync: (key, value) => storage.set(key, value),
    getStorageSync: key => storage.get(key), removeStorageSync: key => storage.delete(key)
  } });
  app.getSession = () => session;
  app.isDevEnv = () => false;
  return { app, session, storage, toasts, requests };
}
for (const statusCode of [200, 401]) {
  test('真实App请求丢弃同毫秒A-B-A旧' + statusCode + '，不续期或清除新A', async () => {
    const f = loadOwnerApp(() => 1234567890000);
    const snapshot = id => ({ user_id: id, authorization: id + '-token', user_type: 1, role: 'player' });
    f.session.applySnapshot(snapshot('7'));
    let loginCalls = 0;
    f.app.reLogin = () => { loginCalls++; throw new Error('Old request must never renew current identity'); };
    const callbacks = [];
    f.app.sendRequest({ url: '/api/user/info', method: 'POST',
      success: () => callbacks.push('success'), fail: error => callbacks.push(error.reason), complete: () => callbacks.push('complete') });
    const originalIssuedAt = f.storage.get('authorization_issued_at');
    f.session.applySnapshot(snapshot('9')); f.session.applySnapshot(snapshot('7'));
    // Token/UID ABA is enough to reproduce; issued-at wall clock must not be the callback fence.
    f.requests[0].success({ statusCode, data: { code: statusCode, private: 'old' } });
    f.requests[0].complete({});
    await Promise.resolve();
    assert.equal(loginCalls, 0);
    assert.equal(f.session.getUserID(), '7'); assert.equal(f.session.getAuthorization(), '7-token');
    assert.deepEqual(callbacks, ['sessionChanged', 'complete']);
    assert.equal(f.storage.get('authorization_issued_at'), originalIssuedAt);
  });
}
for (const entry of ['reLogin', 'firstLogin']) {
  test('App ' + entry + ' 在旧失败后保留新账号内存与 Storage，并停止旧提示', async () => {
    const f = loadOwnerApp(), pending = deferredLoginFixture('exchange');
    f.session.setUserID('7'); f.session.setAuthorization('A');
    const manager = createSessionManager({ session: f.session, wxLogin: () => Promise.resolve({ code: 'CODE' }),
      exchangeCode: () => new Promise(resolve => { pending.resolve = resolve; }) });
    f.app.getSessionManager = () => manager;
    // firstLogin 使用登录中的同一单航班，而非命中缓存。
    const refresh = manager.refresh();
    if (entry === 'firstLogin') manager.ensureSession = () => refresh;
    const result = entry === 'reLogin' ? new Promise(resolve => f.app.reLogin((ok, r) => resolve({ ok, r }))) : f.app.firstLogin();
    await pending.ready();
    f.session.setUserID('9'); f.session.setAuthorization('B');
    pending.resolve({ msg: '登录失败' }); await result;
    assert.equal(f.session.getUserID(), '9'); assert.equal(f.session.getAuthorization(), 'B');
    assert.equal(f.session.restore(), undefined);
    assert.equal(f.session.getUserID(), '9'); assert.equal(f.session.getAuthorization(), 'B');
    assert.deepEqual(f.toasts, []);
  });
}
test('App 失败已返回但回调尚未执行时换号，也不能清除新账号', async () => {
  const f = loadOwnerApp(); f.session.setUserID('7'); f.session.setAuthorization('A');
  let finish;
  f.app.getSessionManager = () => ({ refresh: () => new Promise(resolve => { finish = resolve; }) });
  const result = new Promise(resolve => f.app.reLogin(resolve));
  finish({ ok: false, reason: 'networkFail' }); f.session.setUserID('9'); f.session.setAuthorization('B');
  assert.equal(await result, false);
  assert.equal(f.session.getUserID(), '9'); assert.equal(f.session.getAuthorization(), 'B');
});
test('App 同账号真实续期失败仍清理旧会话', async () => {
  const f = loadOwnerApp(); f.session.setUserID('7'); f.session.setAuthorization('A');
  f.app.getSessionManager = () => createSessionManager({ session: f.session,
    wxLogin: () => Promise.reject(new Error('fixture')), exchangeCode: async () => okBody });
  assert.equal(await new Promise(resolve => f.app.reLogin(resolve)), false);
  assert.ok(!f.session.getUserID()); assert.ok(!f.session.getAuthorization());
});
// 2026-10-24 owner 拍板「游客可浏览，写入时弹窗登录」:匿名读请求 401 不再静默 wx.login 建号;
// 只有写请求会弹「登录后继续」,用户点「微信登录」后首登落地,再用真实新会话重发原请求。
for (const method of ['GET', 'POST']) {
  test('App 实际装配:游客匿名读 ' + method + ' 401 不登录、不建号', async () => {
    const f = loadOwnerApp(); let logins = 0;
    const manager = createSessionManager({ session: f.session, wxLogin: async () => { logins++; return { code: 'CODE' }; }, exchangeCode: async () => okBody });
    f.app.getSessionManager = () => manager;
    f.app.globalData.siteBaseUrl = 'https://fixture';
    const events = [];
    f.app.getRequestClient().send({ url: '/api/fixture', method, fail: (body, status) => events.push('fail:' + status) });
    f.requests[0].success({ statusCode: 401, data: {} }); f.requests[0].complete({});
    for (let i = 0; i < 15; i++) await Promise.resolve();
    assert.equal(logins, 0); assert.equal(f.requests.filter(r => r.url.endsWith('/api/fixture')).length, 1);
    assert.deepEqual(events, ['fail:401']); assert.ok(!f.session.getUserID());
  });
}
test('App 实际装配让游客写请求在弹窗登录后使用真实新会话重发一次', async () => {
  const f = loadOwnerApp();
  const manager = createSessionManager({ session: f.session, wxLogin: async () => ({ code: 'CODE' }), exchangeCode: async () => okBody });
  f.app.getSessionManager = () => manager;
  f.app.globalData.siteBaseUrl = 'https://fixture';
  const prompts = [];
  // 用户在「登录后继续」里点了「微信登录」:与 utils/auth/require-login.js 一样走 app.firstLogin。
  f.app.requireLogin = options => { prompts.push(options); return f.app.firstLogin().then(r => !!r.ok); };
  f.app.getRequestClient().send({ url: '/api/fixture/create', method: 'POST', loginReason: 'post' });
  f.requests[0].success({ statusCode: 401, data: {} }); f.requests[0].complete({});
  for (let i = 0; i < 15; i++) await Promise.resolve();
  const retries = f.requests.filter(request => request.url.endsWith('/api/fixture/create'));
  const features = f.requests.filter(request => request.url.endsWith('/api/config/features'));
  assert.equal(prompts.length, 1); assert.equal(prompts[0].reason, 'post');
  assert.equal(retries.length, 2); assert.equal(retries[1].header.Authorization, okBody.token);
  assert.equal(features.length, 1); assert.equal(features[0].header.Authorization, okBody.token);
  features[0].success({ statusCode: 200, data: { code: 200, data: { communityPostRead: false, communityPostWrite: false } } });
  features[0].complete({});
  assert.equal(f.session.getUserID(), '7');
});
test('App 实际请求装配在换号后拒绝旧 POST 登录和重发', () => {
  const f = loadOwnerApp(); let loginCalls = 0;
  f.app.globalData.siteBaseUrl = 'https://fixture';
  f.app.reLogin = () => { loginCalls++; };
  f.session.setUserID('7'); f.session.setAuthorization('A');
  const events = [];
  f.app.getRequestClient().send({ url: '/api/fixture', method: 'POST', data: { draft: 'A-content' },
    fail: body => { events.push('fail'); assert.equal(body.reason, 'sessionChanged'); },
    complete: () => events.push('complete') });
  f.session.setUserID('9'); f.session.setAuthorization('B');
  f.requests[0].success({ statusCode: 401, data: {} }); f.requests[0].complete({});
  assert.equal(loginCalls, 0); assert.equal(f.requests.length, 1);
  assert.deepEqual(events, ['fail', 'complete']);
});
test('App 登录成功落地后、回调前换号，匿名旧请求也不能借新账号重发', async () => {
  const f = loadOwnerApp();
  f.app.globalData.siteBaseUrl = 'https://fixture';
  const manager = createSessionManager({ session: f.session, wxLogin: async () => ({ code: 'CODE' }), exchangeCode: async () => okBody });
  const refresh = manager.refresh();
  const completed = await refresh;
  // 将真实成功登录结果延迟交给真实 App 回调，再换成其他会话。
  let deliver;
  f.app.getSessionManager = () => ({ refresh: () => new Promise(resolve => { deliver = resolve; }) });
  f.session.clearSession();
  const events = [];
  // 2026-10-24:游客读请求 401 直接按 401 失败,压根不进静默续期(refresh 不会被调用)。
  f.app.getRequestClient().send({ url: '/api/fixture', method: 'GET',
    fail: (body, status) => { events.push('fail'); assert.equal(status, 401); },
    complete: () => events.push('complete') });
  f.requests[0].success({ statusCode: 401, data: {} }); f.requests[0].complete({});
  f.session.setUserID('9'); f.session.setAuthorization('B');
  assert.equal(deliver, undefined, '游客读请求不得触发静默续期'); deliver = () => {}; deliver(completed);
  for (let i = 0; i < 4; i++) await Promise.resolve();
  assert.equal(f.requests.length, 1); assert.deepEqual(events, ['fail', 'complete']);
  assert.equal(f.session.getUserID(), '9'); assert.equal(f.session.getAuthorization(), 'B');
});

test('启动前已失效的登录即使账号随后恢复，也不能调用交换或开发兜底', async () => {
  const { deps, calls } = makeDeps({ session: makeSession({ user_id: '7', authorization: 'A' }), isDevEnv: () => true });
  const manager = createSessionManager(deps), promise = manager.login();
  deps.session.setUserID('9'); deps.session.setAuthorization('B');
  await Promise.resolve();
  deps.session.setUserID('7'); deps.session.setAuthorization('A');
  assert.equal((await promise).reason, 'sessionChanged');
  assert.equal(calls.wxLogin, 0); assert.equal(calls.exchange, 0); assert.equal(calls.dev, 0);
});
for (const reason of ['noData', 'networkFail']) {
  test('App 当前首登失败保留既有清理/弱网规则: ' + reason, async () => {
    const f = loadOwnerApp(); f.session.setUserID('7'); f.session.setAuthorization('A');
    f.app.getSessionManager = () => ({ ensureSession: () => Promise.resolve({ ok: false, reason }) });
    await f.app.firstLogin();
    assert.equal(!!f.session.getUserID(), reason === 'networkFail');
    assert.equal(!!f.session.getAuthorization(), reason === 'networkFail');
    assert.equal(f.toasts.length, 1);
  });
}

for (const response of [
  { statusCode: 401, data: { msg: '登录失败' }, expected: 401 },
  { statusCode: 200, data: { code: 401, msg: '登录失败' }, expected: 401 },
  { statusCode: 200, data: { code: 2, msg: '登录失败' }, expected: 2 }
]) {
  test('真实App本次重登失败清会话仍保留鉴权失败码: ' + response.statusCode + '/' + response.expected, async () => {
    const f = loadOwnerApp(); f.app.globalData.siteBaseUrl = 'https://fixture';
    f.session.setUserID('7'); f.session.setAuthorization('A');
    const manager = createSessionManager({ session: f.session, wxLogin: async () => { throw new Error('fixture'); },
      exchangeCode: async () => okBody });
    f.app.getSessionManager = () => manager;
    const events = [];
    f.app.getRequestClient().send({ url: '/api/fixture', method: 'POST',
      fail: (body, status) => { events.push(['fail', body.reason, status]); },
      complete: () => events.push(['complete']) });
    f.requests[0].success(response); f.requests[0].complete(response);
    for (let i = 0; i < 15; i++) await Promise.resolve();
    assert.equal(f.requests.length, 1);
    assert.deepEqual(events, [['fail', undefined, response.expected], ['complete']]);
    assert.ok(!f.session.getUserID()); assert.ok(!f.session.getAuthorization());
  });
}
test('真实App本次清会话后若又换号，旧请求仍必须取消而非冒充正常鉴权失败', async () => {
  const f = loadOwnerApp(); f.app.globalData.siteBaseUrl = 'https://fixture';
  f.session.setUserID('7'); f.session.setAuthorization('A');
  f.app.getSessionManager = () => createSessionManager({ session: f.session,
    wxLogin: async () => { throw new Error('fixture'); }, exchangeCode: async () => okBody });
  const reLogin = f.app.reLogin;
  f.app.reLogin = cb => reLogin.call(f.app, (ok, result) => {
    f.session.setUserID('9'); f.session.setAuthorization('B'); cb(ok, result);
  });
  const events = [];
  f.app.getRequestClient().send({ url: '/api/fixture', method: 'POST',
    fail: body => events.push(body.reason), complete: () => events.push('complete') });
  f.requests[0].success({ statusCode: 401, data: {} }); f.requests[0].complete({});
  for (let i = 0; i < 15; i++) await Promise.resolve();
  assert.equal(f.requests.length, 1); assert.deepEqual(events, ['sessionChanged', 'complete']);
  assert.equal(f.session.getUserID(), '9'); assert.equal(f.session.getAuthorization(), 'B');
});


for (const failure of ['wx', 'network', 'empty']) {
  test('real session is preserved after development refresh failure: ' + failure, async () => {
    const session = makeSession({ authorization: 'real-session-token', user_id: '912' });
    const { deps, calls } = makeDeps({ session, isDevEnv: () => true, devToken: 'dev-token',
      wxLogin: failure === 'wx' ? async () => { throw new Error('login failed'); } : async () => ({ code: 'test-code' }),
      exchangeCode: failure === 'network' ? async () => { throw new Error('network failed'); } : async () => ({ msg: 'no data' }) });
    const mgr = createSessionManager(deps); const result = await mgr.refresh();
    assert.equal(result.ok, false);
    assert.equal(result.reason, { wx: 'wxLoginFail', network: 'networkFail', empty: 'noData' }[failure]);
    assert.equal(session.getUserID(), '912');
    assert.equal(session.getAuthorization(), 'real-session-token');
    assert.equal(calls.dev, 0);
  });
}
