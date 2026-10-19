const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequestClient } = require('../../utils/transport/request-client.js');

const UPDATE_PATH = require.resolve('../../utils/app-update.js');

// 每个用例拿一份干净的模块状态(promptUpdateIfAvailable 绑定本次启动安装的管理器)。
function freshUpdate() {
  delete require.cache[UPDATE_PATH];
  return require(UPDATE_PATH);
}

function harness(overrides) {
  let ready, failed, applies = 0;
  const dialogs = [], toasts = [], canIUseCalls = [];
  const manager = { onUpdateReady(fn) { ready = fn; }, onUpdateFailed(fn) { failed = fn; }, applyUpdate() { applies++; } };
  const wx = Object.assign({
    canIUse: api => { canIUseCalls.push(api); return true; },
    getUpdateManager: () => manager,
    showModal: opts => dialogs.push(opts),
    showToast: opts => toasts.push(opts),
  }, overrides);
  return { wx, dialogs, toasts, canIUseCalls, ready: () => ready(), failed: () => failed(), applies: () => applies };
}

function loadApp() {
  let app;
  const previousApp = global.App;
  const appPath = require.resolve('../../app.js');
  try {
    global.App = value => { app = value; };
    delete require.cache[appPath];
    require(appPath);
  } finally { global.App = previousApp; }
  return app;
}

test('update ready shows the restart modal and confirm applies the update', () => {
  const { installAppUpdate } = freshUpdate();
  const h = harness();
  assert.equal(installAppUpdate(h.wx), true);
  assert.deepEqual(h.canIUseCalls, ['getUpdateManager']);
  h.ready();
  assert.equal(h.dialogs.length, 1);
  assert.equal(h.dialogs[0].title, '新版本已就绪');
  h.dialogs[0].success({ confirm: true });
  assert.equal(h.applies(), 1);
});

test('download failure stays non-blocking: no modal and no restart', () => {
  const { installAppUpdate, promptUpdateIfAvailable } = freshUpdate();
  const h = harness();
  installAppUpdate(h.wx);
  h.failed();
  assert.equal(h.dialogs.length, 0);
  assert.equal(h.applies(), 0);
  assert.equal(promptUpdateIfAvailable(), false);
});

test('restart prompt shows at most once per launch, including helper re-prompts', () => {
  const { installAppUpdate, promptUpdateIfAvailable } = freshUpdate();
  const h = harness();
  installAppUpdate(h.wx);
  assert.equal(promptUpdateIfAvailable(), false, 'nothing downloaded yet');
  h.ready(); h.ready();
  h.dialogs[0].success({ confirm: false, cancel: true });
  assert.equal(promptUpdateIfAvailable(), false);
  assert.equal(h.dialogs.length, 1);
  assert.equal(h.applies(), 0);
});

test('helper shows the prompt when the ready-time modal could not be shown', () => {
  const { installAppUpdate, promptUpdateIfAvailable } = freshUpdate();
  let broken = true;
  const h = harness();
  const showModal = h.wx.showModal;
  h.wx.showModal = opts => { if (broken) throw Error('UI busy'); showModal(opts); };
  installAppUpdate(h.wx);
  h.ready();
  assert.equal(h.dialogs.length, 0);
  broken = false;
  assert.equal(promptUpdateIfAvailable(), true);
  assert.equal(promptUpdateIfAvailable(), false);
  assert.equal(h.dialogs.length, 1);
});

test('canIUse false leaves startup untouched: no manager, no prompt', () => {
  const { installAppUpdate, promptUpdateIfAvailable } = freshUpdate();
  let managerCalls = 0;
  const h = harness({ canIUse: () => false, getUpdateManager: () => { managerCalls++; return {}; } });
  assert.equal(installAppUpdate(h.wx), false);
  assert.equal(managerCalls, 0);
  assert.equal(promptUpdateIfAvailable(), false);
  assert.equal(h.dialogs.length, 0);
});

test('426 uses the restart prompt when available, otherwise keeps the once-only toast', () => {
  for (const prompted of [true, false]) {
    let options;
    const toasts = [];
    const client = createRequestClient({ wxRequest: o => { options = o; }, getBaseUrl: () => 'https://base', showToast: s => toasts.push(s), promptUpdate: () => prompted });
    const res = { statusCode: 426, data: { code: 426 } };
    client.send({ url: '/write', fail: () => {} }); options.success(res); options.complete(res);
    client.send({ url: '/write', fail: () => {} }); options.success(res); options.complete(res);
    assert.equal(toasts.length, prompted ? 0 : 1);
  }
});

test('onPageNotFound reLaunches the app.json home page once and never loops on home itself', t => {
  const appJson = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../app.json'), 'utf8'));
  const launches = [];
  const previousWx = global.wx;
  global.wx = { reLaunch: opts => launches.push(opts) };
  t.after(() => { global.wx = previousWx; });
  const app = loadApp();
  assert.equal(app.HOME_PAGE, appJson.pages[0]);
  assert.equal(app.HOME_PAGE, appJson.tabBar.list[0].pagePath);
  app.onPageNotFound({ path: 'pages/removed/old', query: {}, isEntryPage: true });
  assert.deepEqual(launches, [{ url: '/' + appJson.pages[0] }]);
  app.onPageNotFound({ path: 'pages/removed/other' });
  assert.equal(launches.length, 1);

  const fresh = loadApp();
  fresh.onPageNotFound({ path: '/' + appJson.pages[0] });
  fresh.onPageNotFound(undefined);
  assert.equal(launches.length, 2, 'home missing → no self-redirect; later miss still handled once');
});

test('request client defaults its 426 hook to the shared restart prompt', () => {
  const clientPath = require.resolve('../../utils/transport/request-client.js');
  const { installAppUpdate } = freshUpdate();
  delete require.cache[clientPath];
  const freshClient = require(clientPath).createRequestClient;
  let broken = true, options;
  const h = harness(), toasts = [];
  const showModal = h.wx.showModal;
  h.wx.showModal = opts => { if (broken) throw Error('UI busy'); showModal(opts); };
  installAppUpdate(h.wx); h.ready(); broken = false;
  const client = freshClient({ wxRequest: o => { options = o; }, getBaseUrl: () => 'https://base', showToast: s => toasts.push(s) });
  const res = { statusCode: 200, data: { code: 426 } };
  client.send({ url: '/write', fail: () => {} }); options.success(res); options.complete(res);
  assert.equal(h.dialogs.length, 1);
  assert.equal(toasts.length, 0);
  delete require.cache[clientPath];
});
