const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const UPDATE_PATH = require.resolve('../../utils/app-update.js');
const MANAGER_PATH = require.resolve('../../utils/update-manager.js');

// 每个用例拿一份干净的模块状态(更新提示与失效页兜底都是「每次启动一次」)。
function fresh() {
  delete require.cache[UPDATE_PATH];
  delete require.cache[MANAGER_PATH];
  return require(MANAGER_PATH);
}

function harness(overrides) {
  let ready, failed, applies = 0, managers = 0;
  const dialogs = [], toasts = [], launches = [], canIUseCalls = [];
  const manager = { onUpdateReady(fn) { ready = fn; }, onUpdateFailed(fn) { failed = fn; }, applyUpdate() { applies++; } };
  const wx = Object.assign({
    canIUse: api => { canIUseCalls.push(api); return true; },
    getUpdateManager: () => { managers++; return manager; },
    showModal: opts => dialogs.push(opts),
    showToast: opts => toasts.push(opts),
    reLaunch: opts => launches.push(opts),
  }, overrides);
  return { wx, dialogs, toasts, launches, canIUseCalls, ready: () => ready(), failed: () => failed(), applies: () => applies, managers: () => managers };
}

test('update ready shows the restart modal and confirm applies the update', () => {
  const m = fresh();
  const h = harness(), app = {};
  assert.equal(m.installUpdateManager(app, h.wx), true);
  assert.deepEqual(h.canIUseCalls, ['getUpdateManager']);
  assert.equal(app.promptUpdateIfAvailable, m.promptUpdateIfAvailable);
  h.ready();
  assert.equal(h.dialogs.length, 1);
  assert.equal(h.dialogs[0].title, '新版本已就绪');
  h.dialogs[0].success({ confirm: true });
  assert.equal(h.applies(), 1);
});

test('download failure stays non-blocking: no modal and no restart', () => {
  const m = fresh();
  const h = harness();
  m.installUpdateManager({}, h.wx);
  h.failed();
  assert.equal(h.dialogs.length, 0);
  assert.equal(h.applies(), 0);
  assert.equal(m.promptUpdateIfAvailable(), false);
});

test('restart prompt shows at most once per launch, including helper re-prompts and double install', () => {
  const m = fresh();
  const { installAppUpdate } = require(UPDATE_PATH);
  const h = harness();
  installAppUpdate(h.wx); // app.js onLaunch 里既有的安装
  m.installUpdateManager({}, h.wx); // C00 接线后的第二次安装
  assert.equal(m.promptUpdateIfAvailable(), false, 'nothing downloaded yet');
  h.ready(); h.ready();
  h.dialogs[0].success({ confirm: false, cancel: true });
  assert.equal(m.promptUpdateIfAvailable(), false);
  assert.equal(h.dialogs.length, 1);
  assert.equal(h.applies(), 0);
});

test('helper shows the prompt when the ready-time modal could not be shown', () => {
  const m = fresh();
  let broken = true;
  const h = harness();
  const showModal = h.wx.showModal;
  h.wx.showModal = opts => { if (broken) throw Error('UI busy'); showModal(opts); };
  m.installUpdateManager({}, h.wx);
  h.ready();
  assert.equal(h.dialogs.length, 0);
  broken = false;
  assert.equal(m.promptUpdateIfAvailable(), true);
  assert.equal(m.promptUpdateIfAvailable(), false);
  assert.equal(h.dialogs.length, 1);
});

test('canIUse false leaves startup untouched: no manager, no prompt', () => {
  const m = fresh();
  const h = harness({ canIUse: () => false });
  assert.equal(m.installUpdateManager({}, h.wx), false);
  assert.equal(h.managers(), 0);
  assert.equal(m.promptUpdateIfAvailable(), false);
  assert.equal(h.dialogs.length, 0);
});

test('handlePageNotFound reLaunches the app.json home page once and never loops on home itself', () => {
  const appJson = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../app.json'), 'utf8'));
  let m = fresh();
  const h = harness();
  assert.equal(m.HOME_PAGE, appJson.pages[0]);
  assert.equal(m.HOME_PAGE, appJson.tabBar.list[0].pagePath);
  assert.equal(m.handlePageNotFound({ path: 'pages/removed/old', query: {}, isEntryPage: true }, h.wx), true);
  assert.deepEqual(h.launches, [{ url: '/' + appJson.pages[0] }]);
  assert.equal(m.handlePageNotFound({ path: 'pages/removed/other' }, h.wx), false);
  assert.equal(h.launches.length, 1);

  m = fresh();
  assert.equal(m.handlePageNotFound({ path: '/' + appJson.pages[0] }, h.wx), false);
  assert.equal(m.handlePageNotFound(undefined, h.wx), true);
  assert.equal(h.launches.length, 2, 'home missing → no self-redirect; later miss still handled once');
  assert.equal(fresh().handlePageNotFound({ path: 'pages/x' }, {}), false, 'no reLaunch API → no throw');
});

// app.js 归 C00(LONGRUN_SOURCE_OWNERS.json reserved_not_transferred),本 PR 不改它;
// 接线以 PR #328 的 CY-HANDOFF 为准。C00 落地后此用例会变红 —— 届时删掉本用例并改为断言已接线。
test('app.js does not wire utils/update-manager.js yet', { todo: 'app.js 接线待 C00 按 PR #328 CY-HANDOFF 落地' }, () => {
  const source = fs.readFileSync(path.resolve(__dirname, '../../app.js'), 'utf8');
  assert.doesNotMatch(source, /update-manager\.js/);
  assert.doesNotMatch(source, /onPageNotFound/);
});
