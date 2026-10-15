'use strict';
// Written only; NOT_RUN. Authorization material is fixture-only and never persisted.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition, member = '11';
  const logins = [], runs = [], actions = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => member }); global.Page = v => { definition = v; };
  global.wx = { login: r => logins.push(r), getWeRunData: r => runs.push(r), showToast() {} };
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const game = { action: (...args) => actions.push(args) };
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, playKit: { kit: { type: 'steps' } } },
    _playKitNodeId: 31, selectComponent: () => game });
  return { page, logins, runs, actions, account: value => { member = value; } };
}
test('steps reads remain login then getWeRunData then original action', () => {
  const h = load(); h.page._submitSteps(); assert.equal(h.runs.length, 0);
  h.logins[0].success({ code: 'fixture-login-code' }); assert.equal(h.runs.length, 1);
  h.runs[0].success({ encryptedData: 'fixture-encrypted', iv: 'fixture-iv' });
  assert.deepEqual(h.actions, [['SUBMIT_STEPS', { code: 'fixture-login-code', encryptedData: 'fixture-encrypted', iv: 'fixture-iv' }]]);
});
for (const change of ['node', 'kit', 'account', 'unload', 'component']) test('steps payload ignores changed ' + change, () => {
  const h = load(); h.page._submitSteps(); h.logins[0].success({ code: 'fixture-login-code' });
  if (change === 'node') h.page._playKitNodeId = 32;
  if (change === 'kit') h.page.data.playKit.kit = { type: 'steps' };
  if (change === 'account') h.account('22');
  if (change === 'unload') h.page._playUnloaded = true;
  if (change === 'component') h.page.selectComponent = () => ({});
  h.runs[0].success({ encryptedData: 'fixture-encrypted', iv: 'fixture-iv' });
  assert.deepEqual(h.actions, []);
});
