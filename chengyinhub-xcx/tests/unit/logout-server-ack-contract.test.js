'use strict';

// 2026-09-17 第二轮拍板 #16:弱网点「退出登录」,本机退了但服务端 token 没撤销。
// 拍板口径:先调服务端注销,失败提示「退出没有完成,请重试」并可重试,不清本地登录态;
// 成功才清。本文件钉住「本地登录态的清与不清跟着服务端回执走」这条唯一判据。
//
// 负控:把「只有服务端确认成功才清本地」的守卫摘回旧行为(success 一律清),用例必须真红。

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const PAGE_PATH = path.resolve(__dirname, '../../pages/shezhi/shezhi.js');
const read = () => fs.readFileSync(PAGE_PATH, 'utf8');

function loadPage(source, initialStorage, storageBehavior) {
  let definition = null;
  const requests = [];
  const cleared = { storage: 0, removed: [], relaunched: [] };
  const storage = { ...(initialStorage || {}) };
  const behavior = storageBehavior || {};
  const globalData = {
    statusBarHeight: 20, navBarHeight: 44,
    authorization: 'token-live', open_id: 'open', user_id: 9, user_type: 1,
    avatar: 'a.png', nickname: '旅人', role: 'player',
  };
  global.getApp = () => ({
    globalData,
    getUserID: () => 9,
    setUserRole() {},
    sendRequest(options) { requests.push(options); return { abort() {} }; },
  });
  global.Page = (config) => { definition = config; };
  global.wx = {
    getStorageSync(key) { return storage[key] == null ? '' : storage[key]; },
    setStorageSync(key, value) { storage[key] = value; },
    getStorageInfoSync() {
      if (behavior.listFailure) throw new Error('storage list failed');
      return { keys: Object.keys(storage) };
    },
    removeStorageSync(key) {
      if (behavior.removeFailure === key) throw new Error('storage remove failed');
      cleared.removed.push(key);
      delete storage[key];
    },
    clearStorageSync() {
      cleared.storage += 1;
      Object.keys(storage).forEach(key => { delete storage[key]; });
    },
    reLaunch(options) { cleared.relaunched.push(options && options.url); },
    showToast() {},
    hideLoading() {},
  };
  delete require.cache[require.resolve(PAGE_PATH)];
  if (source) {
    const Module = require('node:module');
    const m = new Module(PAGE_PATH, null);
    m.filename = PAGE_PATH;
    m.paths = Module._nodeModulePaths(path.dirname(PAGE_PATH));
    m._compile(source, PAGE_PATH);
  } else {
    require(PAGE_PATH);
  }
  assert.ok(definition, 'shezhi 必须注册 Page');
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
  });
  page.setData = function setData(patch, cb) {
    Object.assign(this.data, patch);
    if (cb) cb();
  };
  return { page, requests, cleared, globalData, storage };
}

function dangerDialogStub() {
  const calls = { busyOn: 0, failed: [], done: 0 };
  return {
    calls,
    dlg: {
      busyOn() { calls.busyOn += 1; },
      failed(text) { calls.failed.push(text); },
      done() { calls.done += 1; },
    },
  };
}

function confirmLogout(page, dlg) {
  page.selectComponent = (id) => (id === '#dc' ? dlg : null);
  page.onDangerConfirm({ detail: { key: 'account.logout' } });
}

test('#16 服务端注销成功才清本地登录态', () => {
  const { page, requests, cleared, globalData, storage } =
    loadPage(undefined, { authorization: 'stored-auth', role: 'player' });
  const { dlg, calls } = dangerDialogStub();

  confirmLogout(page, dlg);
  assert.equal(requests.length, 1, '必须先发服务端注销请求');
  assert.equal(requests[0].url, '/api/logout');
  assert.equal(requests[0].method, 'POST');
  assert.equal(calls.busyOn, 1, '请求在途要锁死弹窗');
  assert.equal(cleared.storage, 0, '服务端还没回执，本地一律不能清');

  requests[0].success({ code: 200 });

  assert.equal(cleared.storage, 0, '正常退出不得整库 clear 后搬运业务数据');
  assert.deepEqual(cleared.removed.sort(),
    ['authorization', 'role', 'role_permission_cache_v1']);
  assert.deepEqual(storage, {});
  assert.deepEqual(cleared.relaunched, ['/pages/index/index']);
  assert.equal(globalData.authorization, '', '内存里的 token 也要清');
  assert.equal(globalData.role, '');
});

test('正常退出后同账号可恢复漫游，不保留凭据且切号不串数据', () => {
  const initialStorage = {
    authorization: 'stored-auth',
    role: 'player',
    user_id: 9,
    'roam_memory_v1:9:sessions': [{ sessionId: '34', distance: 1.2 }],
    'roam_memory_v1:9:recovery': { sessionId: '34', elapsedSeconds: 25 },
    'roam_memory_v1:10:sessions': [{ sessionId: '52', distance: 0.8 }],
  };
  const { page, requests, storage } = loadPage(undefined, initialStorage);
  const { dlg } = dangerDialogStub();

  confirmLogout(page, dlg);
  requests[0].success({ code: 200 });

  assert.equal(storage.authorization, undefined, '本地凭据必须随正常退出清除');
  assert.equal(storage.role, undefined);
  assert.equal(storage.user_id, undefined);
  assert.deepEqual(storage['roam_memory_v1:9:sessions'], initialStorage['roam_memory_v1:9:sessions']);
  assert.deepEqual(storage['roam_memory_v1:9:recovery'], initialStorage['roam_memory_v1:9:recovery']);
  assert.deepEqual(storage['roam_memory_v1:10:sessions'], initialStorage['roam_memory_v1:10:sessions'],
    '正常退出不能连带删除设备上另一个玩家的独立漫游历史');
  assert.equal(storage['roam_memory_v1:10:recovery'], undefined,
    '不同账号不能读到当前账号恢复记录');
});

test('#16 弱网/业务失败:不清本地,弹窗给「退出没有完成,请重试」', () => {
  const { page, requests, cleared, globalData } = loadPage();
  const { dlg, calls } = dangerDialogStub();

  confirmLogout(page, dlg);
  requests[0].fail({ errMsg: 'request:fail timeout' });

  assert.equal(cleared.storage, 0, '请求失败时清本地 = 假退出，服务端 token 还活着');
  assert.deepEqual(cleared.relaunched, []);
  assert.equal(globalData.authorization, 'token-live', '重试必须复用同一个仍有效的 token');
  assert.deepEqual(calls.failed, ['退出没有完成，请重试']);
});

test('退出失败同时保留登录态和漫游业务存档', () => {
  const initialStorage = {
    authorization: 'stored-auth',
    'roam_memory_v1:9:sessions': [{ sessionId: '34' }],
    'roam_memory_v1:9:recovery': { sessionId: '34', elapsedSeconds: 25 },
  };
  const { page, requests, storage } = loadPage(undefined, initialStorage);
  const { dlg } = dangerDialogStub();

  confirmLogout(page, dlg);
  requests[0].fail({ errMsg: 'request:fail timeout' });

  assert.deepEqual(storage, initialStorage);
});

test('本地键列表读取失败时不清内存账号态、不跳首页并允许重试', () => {
  const initialStorage = {
    authorization: 'stored-auth',
    'roam_memory_v1:9:recovery': { sessionId: '34', elapsedSeconds: 25 },
  };
  const behavior = { listFailure: true };
  const { page, requests, storage, globalData, cleared } =
    loadPage(undefined, initialStorage, behavior);
  const { dlg, calls } = dangerDialogStub();

  confirmLogout(page, dlg);
  requests[0].success({ code: 200 });

  assert.deepEqual(storage, initialStorage);
  assert.equal(globalData.authorization, 'token-live');
  assert.deepEqual(cleared.relaunched, []);
  assert.deepEqual(calls.failed, ['本机登录信息清理失败，请重试']);
});

test('删除本地账号键失败时保留业务原值，重试成功后才清内存并跳首页', () => {
  const recovery = { sessionId: '34', elapsedSeconds: 25 };
  const initialStorage = {
    authorization: 'stored-auth',
    role: 'player',
    'roam_memory_v1:9:recovery': recovery,
  };
  const behavior = { removeFailure: 'authorization' };
  const { page, requests, storage, globalData, cleared } =
    loadPage(undefined, initialStorage, behavior);
  const { dlg, calls } = dangerDialogStub();

  confirmLogout(page, dlg);
  requests[0].success({ code: 200 });

  assert.equal(storage['roam_memory_v1:9:recovery'], recovery);
  assert.equal(storage.authorization, 'stored-auth');
  assert.equal(globalData.authorization, 'token-live');
  assert.deepEqual(cleared.relaunched, []);
  assert.deepEqual(calls.failed, ['本机登录信息清理失败，请重试']);

  behavior.removeFailure = '';
  confirmLogout(page, dlg);
  requests[1].fail({ code: 401 }, 401);

  assert.equal(storage.authorization, undefined);
  assert.equal(storage.role, undefined);
  assert.equal(storage['roam_memory_v1:9:recovery'], recovery);
  assert.equal(globalData.authorization, '');
  assert.deepEqual(cleared.relaunched, ['/pages/index/index']);
});

test('#16 业务码非 200 同样按失败处理,并透传后端文案', () => {
  const { page, requests, cleared } = loadPage();
  const { dlg, calls } = dangerDialogStub();

  confirmLogout(page, dlg);
  requests[0].success({ code: 500, msg: '系统繁忙，请稍后再试' });

  assert.equal(cleared.storage, 0, 'HTTP 200 但业务失败不算退出成功');
  assert.deepEqual(calls.failed, ['系统繁忙，请稍后再试']);
});

test('#16 失败后可重试:第二次成功才清本地', () => {
  const { page, requests, cleared } =
    loadPage(undefined, { authorization: 'stored-auth', role: 'player' });
  const { dlg } = dangerDialogStub();

  confirmLogout(page, dlg);
  requests[0].fail({});
  assert.equal(cleared.storage, 0);

  confirmLogout(page, dlg);
  assert.equal(requests.length, 2, '重试必须重新发一次服务端注销');
  requests[1].success({ code: 200 });
  assert.equal(cleared.storage, 0);
  assert.deepEqual(cleared.removed.sort(),
    ['authorization', 'role', 'role_permission_cache_v1']);
});

test('#16 注销账号动作不走正常退出保留器', () => {
  const initialStorage = {
    authorization: 'stored-auth',
    'roam_memory_v1:9:recovery': { sessionId: '34', elapsedSeconds: 25 },
  };
  const { page, requests, cleared, storage } = loadPage(undefined, initialStorage);
  const { dlg } = dangerDialogStub();

  page.selectComponent = (id) => (id === '#dc' ? dlg : null);
  page.onDangerConfirm({ detail: { key: 'account.deregister.apply' } });
  page.onDangerConfirm({ detail: { key: 'account.deregister.cancel' } });

  assert.equal(requests.length, 0, '非退出键不得触发 /api/logout');
  assert.equal(cleared.storage, 0);
  assert.deepEqual(storage, initialStorage,
    '注销账号必须由独立流程处置，不得被正常退出补丁擅自保存或改写数据');
});

test('#16 负控:撤掉「先服务端确认」守卫(旧行为:success 一律清)必须真红', () => {
  const source = read();
  const broken = source.replace(
    `      success(res) {
        if (res && (res.code == 200 || loginExpired(res))) {
          if (!clearLocalLogin() && dc) dc.failed('本机登录信息清理失败，请重试');
          return;
        }
        const message = (res && res.msg) || '退出没有完成，请重试';
        if (dc) dc.failed(message);
      },`,
    '      success() { clearLocalLogin(); },');
  assert.notEqual(broken, source, '负控锚点失效:成功分支守卫未命中');

  const { page, requests, cleared, globalData } =
    loadPage(broken, { authorization: 'stored-auth' });
  const { dlg } = dangerDialogStub();
  confirmLogout(page, dlg);
  requests[0].success({ code: 500, msg: '系统繁忙' });

  assert.deepEqual(cleared.removed, ['authorization', 'role_permission_cache_v1'],
    '旧行为在业务失败时也清本地 —— 这正是拍板要修掉的假退出');
  assert.equal(globalData.authorization, '');
});


test('明确登录失效可退出重登，403与网络失败仍保留登录', () => {
  for (const [callback, response, status, shouldClear] of [
    ['fail', {}, 401, true],
    ['fail', { code: '401' }, 401, true],
    ['fail', { code: 2 }, 2, true],
    ['success', { code: '401' }, undefined, true],
    ['success', { code: 2 }, undefined, true],
    ['fail', { code: 403 }, 403, false],
    ['fail', { errMsg: 'request:fail timeout' }, undefined, false],
  ]) {
    const { page, requests, cleared, globalData } =
      loadPage(undefined, { authorization: 'stored-auth', role: 'player' });
    const { dlg, calls } = dangerDialogStub();
    confirmLogout(page, dlg);
    requests[0][callback](response, status);
    assert.equal(cleared.storage, 0, '退出清理不得整库 clear');
    assert.equal(cleared.removed.length, shouldClear ? 3 : 0);
    assert.equal(globalData.authorization, shouldClear ? '' : 'token-live');
    assert.deepEqual(cleared.relaunched, shouldClear ? ['/pages/index/index'] : []);
    assert.equal(calls.failed.length, shouldClear ? 0 : 1);
  }
});
