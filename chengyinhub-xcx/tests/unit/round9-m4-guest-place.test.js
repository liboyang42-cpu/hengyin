'use strict';
// 第九轮 m4:分享来的据点游客可看;打卡、收藏是写,先弹同一个登录窗,登录后按新身份重读,不带游客状态直接写。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

const root = path.resolve(__dirname, '../..');
const FILE = 'subpackageMember/components/cy/scene-roam-poi-detail/index.js';

function loadComponent(app) {
  let definition;
  const localRequire = createRequire(path.join(root, FILE));
  const stub = new Proxy(function () {}, { get: (t, k) => (k === Symbol.toPrimitive ? () => '' : stub), apply: () => stub });
  vm.runInNewContext(fs.readFileSync(path.join(root, FILE), 'utf8'), {
    getApp: () => app, wx: { getStorageSync: () => '', removeStorageSync() {} }, console, setTimeout, clearTimeout,
    getCurrentPages: () => [], Component: (def) => { definition = def; }, Behavior: (b) => b,
    require(name) { try { return localRequire(name); } catch (e) { return stub; } },
    module: {}, exports: {},
  }, { filename: FILE });
  return definition;
}

function harness(userId) {
  const calls = { login: [], requests: [], loads: 0 };
  const app = {
    globalData: {}, getImgUrl: (v) => v, getUserID: () => userId,
    sendRequest: (o) => calls.requests.push(o.url),
    ensureLoginFor: (reason, resume) => { calls.login.push(reason); calls.resume = resume; return false; },
  };
  const def = loadComponent(app);
  const ctx = Object.assign({}, def.methods, {
    data: { poiId: '21', node: { poiId: 21, status: 1, validationMethod: 3, canInteract: true }, checkinPending: false,
      favoriteBusy: false, completing: false },
    setData(patch) { Object.assign(this.data, patch); },
    triggerEvent() {},
  });
  ctx._load = () => { calls.loads += 1; };
  return { ctx, calls };
}

test('游客点打卡:弹「漫游」登录窗,不发写请求;登录后重读据点', () => {
  const { ctx, calls } = harness('');
  ctx.startInteract();
  assert.deepEqual(calls.login, ['roam']);
  assert.deepEqual(calls.requests, []);
  assert.equal(ctx.data.choiceSheetShow, undefined, '游客没有题面,不能弹空选项');
  calls.resume();
  assert.equal(calls.loads, 1);
});

test('游客点收藏:弹「收藏」登录窗,不发写请求;登录后重读据点', () => {
  const { ctx, calls } = harness('');
  ctx.toggleFavorite();
  assert.deepEqual(calls.login, ['like']);
  assert.deepEqual(calls.requests, []);
  calls.resume();
  assert.equal(calls.loads, 1);
});

test('已登录会员不经过登录窗,收藏照常发请求', () => {
  const { ctx, calls } = harness('7001');
  ctx.toggleFavorite();
  assert.deepEqual(calls.login, []);
  assert.equal(calls.requests.length, 1);
  assert.match(calls.requests[0], /\/api\/city\/nodes\/21\/favorite$/);
});
