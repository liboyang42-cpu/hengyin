'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { platform, receipt } = require('./helpers/team-event-platform');
const { createRequire } = require('node:module');
function boot(relative) {
  const file = path.resolve(__dirname, '../../pages', relative, 'index.js');
  let config, user = '1', token = 'a';
  const requests = [], timers = [], navs = [], effects = [];
  const app = { globalData: {}, getUserID: () => user, getAuthorization: () => token,
    sendRequest: r => requests.push(r), getRequestErrorMessage: (r, f) => r && r.msg || f };
  const dc = { busyOn() {}, close() {}, done: () => effects.push('done'), failed: () => effects.push('failed') };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Page: d => { config = d; }, getApp: () => app,
    getCurrentPages: () => [], setTimeout: fn => timers.push(fn),
    wx: { ...platform(), redirectTo: o => navs.push(o), stopPullDownRefresh() {} },
    require: id => id.includes('merchant-theme') ? { merchantPageShow() {}, merchantPageRestore() {} }
      : id.includes('toast') ? m => effects.push(m) : createRequire(file)(id) });
  const page = { ...config, data: JSON.parse(JSON.stringify(config.data)), selectComponent: () => dc,
    setData(patch) { Object.assign(this.data, patch); } };
  return { page, requests, timers, navs, effects, account() { user = '2'; token = 'b'; } };
}
test('invite read rejects rounded IDs but accepts precise Long string', () => {
  const h = boot('team/join'); h.page.onLoad({ code: 'invite' });
  h.requests[0].success({ code: 200, data: { team: { id: 9007199254740993 } } });
  h.page.goDetail(); assert.equal(h.navs.length, 0);
  h.page.load(); h.requests.at(-1).success({ code: 200, data: { team: { id: '9007199254740993' } } });
  h.page.goDetail(); assert.ok(h.navs[0].url.endsWith('9007199254740993'));
});
test('invite late read after account switch cannot expose prior membership', () => {
  const h = boot('team/join'); h.page.onLoad({ code: 'invite' }); h.account();
  h.requests[0].success({ code: 200, data: { team: { id: 9 }, joined: true } });
  assert.equal(h.page.data.team, null); assert.equal(h.page.data.joined, false);
  h.page.onShow(); assert.equal(h.requests.length, 2);
});
test('invite join delayed receipt cannot refresh after switch or unload', async () => {
  for (const change of ['account', 'unload']) {
    const h = boot('team/join'); h.page.onLoad({ code: 'invite' });
    h.requests[0].success({ code: 200, data: { team: { id: 9 } } });
    await h.page.join(); const command = h.requests.at(-1); const count = h.requests.length;
    if (change === 'account') h.account(); else h.page.onUnload();
    command.success(receipt(command, 'APPLIED'));
    assert.equal(h.requests.length, count); assert.equal(h.navs.length, 0);
    assert.equal(h.page.data.joined, false);
  }
});
test('organizer preserves Long in publish-check URL', () => {
  const h = boot('activity/official-organizer'); h.page.onLoad({ id: '9007199254740993' });
  assert.equal(h.requests[0].url, '/api/official/v2/events/9007199254740993/publish-check');
});
test('organizer old permission read cannot open controls after switch', () => {
  const h = boot('activity/official-organizer'); h.page.onLoad({ id: '9' }); h.account();
  h.requests[0].success({ code: 200, data: { pass: true } });
  assert.equal(h.requests.length, 1); assert.equal(h.page.data.view, null);
  assert.equal(h.page.data.check.known, false);
});
test('organizer confirmation after account change cannot submit prior form', () => {
  const h = boot('activity/official-organizer'); h.page.onLoad({ id: '9' }); h.account();
  h.page.onConfirmPause(); assert.equal(h.requests.length, 1);
  assert.equal(h.page.data.busyAction, '');
});
