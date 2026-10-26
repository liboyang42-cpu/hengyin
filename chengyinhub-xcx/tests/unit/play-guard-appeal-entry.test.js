'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
function load() {
  let definition;
  global.getApp = () => ({});
  global.Page = value => { definition = value; };
  global.wx = { showToast() {} };
  const path = require.resolve('../../pages/play/index.js');
  delete require.cache[path]; require(path);
  const modal = require('../../utils/modal.js');
  const prompts = [], locations = [], arrivals = [];
  modal.show = options => prompts.push(options);
  wx.getLocation = options => locations.push(options);
  const page = { ...definition, data: {}, _themeHidden: false, _routeState: { sessionId: '9007199254740993' } };
  let live = true;
  page._isPlayAccountCurrent = () => live;
  page._sessionKey = () => 'original';
  page.diegetic = () => {};
  page.arrive = (...args) => arrivals.push(args);
  const view = { reference: { routeSessionId: '9007199254740993', nodeId: '9007199254740995' }, result: { canReverify: true } };
  page._ensureGuardAppeal = () => ({ read: async () => {}, view: () => view });
  return { page, view, prompts, locations, arrivals, switchAccount() { live = false; } };
}
test('Page reverify obtains fresh location then calls ordinary arrive with exact node ID', async () => {
  const e = load(); await e.page.reverifyGuardArrival();
  assert.equal(e.locations.length, 0);
  e.prompts[0].success({ confirm: true });
  assert.equal(e.locations[0].type, 'gcj02');
  e.locations[0].success({ latitude: 31, longitude: 121, accuracy: 12 });
  assert.deepEqual(e.arrivals, [['9007199254740995', 'guard-reverify']]);
  assert.equal(e.page._loc.accuracy, 12);
});
test('Page rejects ended/cross-run and ignores account-switched location callback', async () => {
  const e = load(); e.view.result.canReverify = false; await e.page.reverifyGuardArrival();
  assert.equal(e.prompts.length, 0);
  e.view.result.canReverify = true; e.page._routeState.sessionId = 'other'; await e.page.reverifyGuardArrival();
  assert.equal(e.prompts.length, 0);
  e.page._routeState.sessionId = e.view.reference.routeSessionId; await e.page.reverifyGuardArrival();
  e.prompts[0].success({ confirm: true }); e.switchAccount();
  e.locations[0].success({ latitude: 31, longitude: 121, accuracy: 12 });
  assert.equal(e.arrivals.length, 0);
});
test('verified template exposes query, qualified submit and accepted original-run reverify handlers', () => {
  const e = load();
  const xml = fs.readFileSync(require.resolve('../../pages/play/index.wxml'), 'utf8');
  for (const method of ['readGuardAppeal', 'submitGuardAppeal', 'reverifyGuardArrival']) {
    assert.equal(typeof e.page[method], 'function'); assert.ok(xml.includes('bindtap="' + method + '"'));
  }
  assert.ok(xml.includes("guardAppealView.state === 'FOUND' && guardAppealView.result.canReverify"));
});
