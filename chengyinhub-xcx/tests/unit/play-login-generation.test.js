// Public PR671 / 634151: QP008 guard regressions only; independent OSS cases intentionally excluded.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function playHost(actualApp) {
  let definition;
  const app = actualApp || {
    globalData: {}, _loginEpoch: 1, _featureFlagsEpoch: 1, owner: '7', auth: 'token-a', respond: null,
    getUserID: () => app.owner,
    getAuthorization: () => app.auth,
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    sendRequest: (options) => app.respond(options),
  };
  global.getApp = () => app;
  global.Page = (value) => { definition = value; };
  global.wx = { getStorageSync: () => '', showToast() {}, nextTick(fn) { fn(); } };
  const file = require.resolve('../../pages/play/index');
  delete require.cache[file];
  require(file);
  const page = Object.assign({}, definition, {
    data: { ...definition.data, topicId: '990069', loading: true },
    setData(patch) { Object.assign(this.data, patch); },
  });
  page._playOwner = page._playMember();
  page._playLoginContext = page._capturePlayLoginContext();
  return { app, page };
}

test('灰度开关重拉、401 静默续期换 token 仍是同一账号：play 首屏不被丢弃、不停在黑屏 loading', async () => {
  const { app, page } = playHost();
  app.respond = (options) => {
    // 回包前：开关重拉(_featureFlagsEpoch++) + 续期换 token，账号与登录代次不变
    app._featureFlagsEpoch++;
    app.auth = 'token-b';
    options.success({ code: 402, msg: '请先购买自玩通行证' });
  };
  page.loadData(true);
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(page._isPlayAccountCurrent(), true);
  assert.equal(page.data.loading, false);
  assert.equal(page.data.emptyKind, 'needPass');
});

test('真换号（会话清除推进登录代次 / 换成员）仍丢弃在途回包', async () => {
  for (const change of [(app) => { app._loginEpoch++; }, (app) => { app.owner = '8'; }]) {
    const { app, page } = playHost();
    app.respond = (options) => { change(app); options.success({ code: 402, msg: '请先购买自玩通行证' }); };
    page.loadData(true);
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(page._isPlayAccountCurrent(), false);
    assert.equal(page.data.emptyKind, '');
  }
});

test('真实 App 的开关刷新和同账号续期不推进登录代次，真实 session clear 后同账号旧页仍失效', async () => {
  const disk = new Map();
  const storage = {
    getStorageSync: key => disk.get(key) || '',
    setStorageSync: (key, value) => disk.set(key, value),
    removeStorageSync: key => disk.delete(key),
  };
  global.wx = storage;
  const root = path.join(__dirname, '../..');
  let app;
  vm.runInNewContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), {
    App: value => { app = value; }, wx: storage, console, setTimeout, clearTimeout,
    require: request => request.startsWith('.') ? require(path.join(root, request)) : require(request),
  });
  const session = app.getSession();
  session.applySnapshot({ user_id: '7', user_type: 1, role: 'player', authorization: 'fixture-original' });
  const { page } = playHost(app);
  const originalGeneration = page._capturePlayLoginContext().generation;
  app.sendRequest = options => options.success({ code: 200, data: { communityPostRead: true } });
  await app.loadFeatureFlags();
  session.setAuthorization('fixture-renewed');
  await app.loadFeatureFlags();
  assert.equal(page._capturePlayLoginContext().generation, originalGeneration);
  assert.equal(page._isPlayAccountCurrent(), true);
  session.clearSession();
  session.applySnapshot({ user_id: '7', user_type: 1, role: 'player', authorization: 'fixture-renewed' });
  assert.equal(app._loginEpoch, 1);
  assert.notEqual(page._capturePlayLoginContext().generation, originalGeneration);
  assert.equal(page._isPlayAccountCurrent(), false);
});
