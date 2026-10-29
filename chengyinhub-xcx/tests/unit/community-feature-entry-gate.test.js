'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../..');

function method(file, name, context) {
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  const start = source.indexOf('  ' + name + (file === 'app.js' ? ': function (' : '('));
  const end = source.indexOf('\n  },', start);
  assert.ok(start >= 0 && end > start, name);
  return vm.runInNewContext('({' + source.slice(start, end + 4).trim() + '})', context)[name];
}

function featureApp() {
  let owner = '7'; let token = 'A';
  const requests = [];
  const app = { globalData: { features: {} }, getUserID: () => owner,
    getAuthorization: () => token, sendRequest: request => requests.push(request) };
  for (const name of ['loadFeatureFlags', 'canUseCommunity']) app[name] = method('app.js', name, {});
  return { app, requests, switchOwner(id, auth) { owner = id; token = auth; } };
}

test('community server read and write gates are independent and default closed', async () => {
  const h = featureApp();
  assert.equal(h.app.canUseCommunity(false), false);
  const pending = h.app.loadFeatureFlags();
  h.requests[0].success({ code: 200, data: { communityPostRead: true, communityPostWrite: false } });
  await pending;
  assert.equal(h.app.canUseCommunity(false), true);
  assert.equal(h.app.canUseCommunity(true), false);
  const malformed = h.app.loadFeatureFlags();
  h.requests[1].success({ code: 200, data: { communityPostRead: 'true', communityPostWrite: 1 } });
  await malformed;
  assert.equal(h.app.canUseCommunity(false), false);
  assert.equal(h.app.canUseCommunity(true), false);
});

test('account switch and token renewal reject delayed feature responses from the prior identity', async () => {
  const h = featureApp();
  const old = h.app.loadFeatureFlags();
  h.switchOwner('9', 'B');
  const next = h.app.loadFeatureFlags();
  h.requests[1].success({ code: 200, data: { communityPostRead: false, communityPostWrite: false } });
  await next;
  h.requests[0].success({ code: 200, data: { communityPostRead: true, communityPostWrite: true } });
  await old;
  assert.equal(h.app.canUseCommunity(false), false);
  const renewing = h.app.loadFeatureFlags();
  h.switchOwner('9', 'C');
  h.requests[2].success({ code: 200, data: { communityPostRead: true, communityPostWrite: true } });
  await renewing;
  assert.equal(h.app.canUseCommunity(true), false);
});

test('same-account older response cannot reopen a gate after the latest read fails', async () => {
  const h = featureApp();
  const old = h.app.loadFeatureFlags();
  const latest = h.app.loadFeatureFlags();
  h.requests[1].fail({ errMsg: 'network' }); await latest;
  h.requests[0].success({ code: 200, data: { communityPostRead: true, communityPostWrite: true } }); await old;
  assert.equal(h.app.canUseCommunity(true), false);
});

test('play publish and IM read entries enforce distinct server gates before navigation', () => {
  let read = true; let write = false;
  const navigation = [];
  const app = { canUseCommunity: writing => writing ? write : read };
  // 2026-10-29:两个入口都改去发现页的全站帖文(utils/discover-nav.js)
  const discoverNav = { markDiscoverFeed: a => { a.globalData = { discoverFeedTab: true }; } };
  const context = { app, discoverNav, cyToast() {}, setTimeout: fn => fn(), wx: { switchTab: target => navigation.push(target.url) } };
  const play = method('pages/play/index.js', 'shareFinish', context);
  const im = method('subpackageB/pages/im/list/index.js', 'onEmptyCta', context);
  play.call({}); assert.equal(navigation.length, 0);
  im.call({ data: { activeTab: 'in' } }); assert.equal(navigation.length, 1);
  read = false; im.call({ data: { activeTab: 'in' } }); assert.equal(navigation.length, 1);
  write = true; play.call({}); assert.equal(navigation.length, 2);
  assert.equal(app.globalData.discoverFeedTab, true);
  assert.deepEqual(navigation, ['/pages/talent/list/index', '/pages/talent/list/index']);
});

test('roam cannot begin an upload or publish flow while server write is closed', async () => {
  const app = { canUseCommunity: () => false };
  const publish = method('pages/roam/index.js', '_shareToSquare', { getApp: () => app, cyToast() {} });
  const result = await publish.call({ _shareScope() { throw new Error('must not start export or upload'); } });
  assert.equal(result.blocked, true);
  assert.equal(result.posted, false);
});
