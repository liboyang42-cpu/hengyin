'use strict';

// BL-217:主办控制台详情与「我发布的」两路都断网时,不能当成读到了状态。
// 有快照不冲掉、受理后回读落 unread 文案、首读落网络错误态。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const ROOT = path.resolve(__dirname, '../..');
const rules = require('../../pages/activity/official-organizer/organizer-rules.js');
const { attachDangerConfirm } = require('./helpers/danger-confirm-stub');

function read(rel) { return fs.readFileSync(path.join(ROOT, rel), 'utf8'); }

function installWx(extra) {
  const calls = { navigateTo: [], toasts: [] };
  const base = {
    getStorageSync: () => '',
    stopPullDownRefresh: () => {},
    navigateTo: (o) => calls.navigateTo.push(o.url),
    navigateBack: () => {},
    redirectTo: () => {},
    showToast: (o) => calls.toasts.push(o.title),
  };
  global.wx = new Proxy(Object.assign(base, extra || {}), {
    get(target, key) { return key in target ? target[key] : () => {}; },
  });
  global.getCurrentPages = () => [];
  return calls;
}

function loadPage(rel, app) {
  let definition;
  global.getApp = () => app;
  global.Page = (config) => { definition = config; };
  const abs = path.join(ROOT, rel);
  delete require.cache[require.resolve(abs)];
  require(abs);
  return definition;
}

function setPath(target, key, value) {
  const parts = key.split('.');
  let node = target;
  for (let i = 0; i < parts.length - 1; i++) {
    node[parts[i]] = Object.assign({}, node[parts[i]]);
    node = node[parts[i]];
  }
  node[parts[parts.length - 1]] = value;
}

function makePage(definition) {
  const page = Object.assign({}, definition);
  page.data = JSON.parse(JSON.stringify(definition.data));
  page.setData = (patch) => {
    // 先拷贝顶层对象,与真实 setData 一样不共享旧引用
    Object.keys(patch).forEach((key) => setPath(page.data, key, patch[key]));
  };
  return page;
}

function makeApp() {
  const requests = [];
  return {
    requests,
    sendRequest: (req) => { requests.push(req); return { abort() {} }; },
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  };
}

function ok(data) { return { code: 200, data }; }

// 把一次完整读取走完:publish-check → 详情(或回落到我发布的)
function finishLoad(app, { check, detail, mine }) {
  const take = (suffix) => {
    const idx = app.requests.findIndex((r) => !r._done && r.url.endsWith(suffix));
    assert.ok(idx >= 0, '应发出请求 ' + suffix);
    app.requests[idx]._done = true;
    return app.requests[idx];
  };
  take('/publish-check').success(check);
  if (check && check.code === 200) {
    const d = take('/api/official/events/7');
    if (detail === 'fail') d.fail({});
    else d.success(detail);
    if (mine !== undefined) take('/api/official/my-published').success(mine);
  }
}

function openPage(app, query) {
  const calls = installWx();
  const definition = loadPage('pages/activity/official-organizer/index.js', app);
  const page = makePage(definition);
  page.onLoad(query || { id: '7' });
  return { page, calls };
}

function wireConfirms(page) {
  const stubs = {};
  for (const action of ['schedule', 'pause', 'resume']) {
    const name = 'onConfirm' + action[0].toUpperCase() + action.slice(1);
    stubs[action] = attachDangerConfirm(page, { id: '#dc-' + action, confirmHandler: name });
  }
  page.selectComponent = (sel) => {
    const action = sel.replace('#dc-', '');
    return stubs[action] || null;
  };
  return stubs;
}

const LIVE = { id: 7, title: '外滩夜行', status: 3, contractVersion: 2, controlState: 'NORMAL' };
const PASS = ok({ blockers: [], risks: [] });

// 读取走到 my-published 后让它断网
function finishOffline(app) {
  const take = (suffix) => {
    const idx = app.requests.findIndex((r) => !r._done && r.url.endsWith(suffix));
    assert.ok(idx >= 0, '应发出请求 ' + suffix);
    app.requests[idx]._done = true;
    return app.requests[idx];
  };
  take('/publish-check').success(PASS);
  take('/api/official/events/7').fail({});
  take('/api/official/my-published').fail({});
}

test('有快照时下拉刷新两路断网:保留已显示的活动名和状态,只收刷新', () => {
  const app = makeApp();
  const { page, calls } = openPage(app);
  finishLoad(app, { check: PASS, detail: ok(LIVE) });
  assert.equal(page.data.view.title, '外滩夜行');
  const statusText = page.data.view.statusText;
  page.onPullDownRefresh();
  assert.equal(page.data.refreshing, true);
  finishOffline(app);
  assert.equal(page.data.phase, 'ready');
  assert.equal(page.data.refreshing, false);
  assert.equal(page.data.view.title, '外滩夜行');
  assert.equal(page.data.view.statusText, statusText);
  assert.ok(calls.toasts.includes('网络没连上，显示上次读到的状态'));
});

test('受理后回读两路断网:落「状态未刷新 / 回读最新状态失败」,不报不一致', () => {
  const app = makeApp();
  const { page } = openPage(app);
  const dc = wireConfirms(page);
  finishLoad(app, { check: PASS, detail: ok(Object.assign({}, LIVE, { controlState: 'PAUSED' })) });
  page.onFieldInput({ currentTarget: { dataset: { form: 'resume', key: 'reason' } }, detail: { value: '已处理' } });
  page.onAction({ currentTarget: { dataset: { action: 'resume' } } });
  dc.resume.confirm();
  app.requests[app.requests.length - 1].success(ok());
  finishOffline(app);
  assert.equal(dc.resume.doneCalls.length, 0);
  assert.equal(page.data.result.tone, 'unknown');
  assert.match(page.data.result.title, /已受理，但状态未刷新/);
  assert.match(page.data.result.sub, /回读最新状态失败/);
  assert.equal(page.data.view.title, '外滩夜行', '快照不被空状态冲掉');
});

test('首读两路断网:落网络错误态,不渲染空状态', () => {
  const app = makeApp();
  const { page } = openPage(app);
  finishOffline(app);
  assert.equal(page.data.phase, 'error');
  assert.equal(page.data.loadErrKind, 'network');
});
