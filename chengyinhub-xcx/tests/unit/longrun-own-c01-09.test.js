'use strict';
// Written, NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition;
  const modals = [], assists = [], requests = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => '11', sendRequest: r => requests.push(r) });
  global.getCurrentPages = () => [];
  global.Page = v => { definition = v; };
  global.wx = { showModal: r => modals.push(r), showToast() {}, getStorageSync: () => '', removeStorageSync() {} };
  const file = path.resolve(__dirname, '../../pages/play/index.js');
  delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, roleCard: {},
    gameModule: { enabled: true, activityId: 9, sessionId: 91, revision: 4,
      role: { code: 'SCOUT' }, availableActions: ['PLAYER_HINT', 'PLAYER_REVEAL'],
      write: { status: 'idle' }, nodes: [{ nodeId: 31, status: 'AVAILABLE', hint: {
        nextLevel: 1, nextImpactLabel: 'fixture hint impact', revealAvailable: true, revealImpactLabel: 'fixture reveal impact',
      } }] } }, _submitPlayerAssist: input => assists.push(input) });
  page.setData = patch => Object.assign(page.data, patch);
  return { page, modals, assists, requests };
}
for (const handler of ['requestPlayerHint', 'requestPlayerReveal']) {
  for (const change of ['revision', 'session', 'pending', 'hint']) test(handler + ' rejects stale ' + change + ' modal', () => {
    const h = load();
    h.page[handler]({ currentTarget: { dataset: { node: 31, level: 1 } } });
    if (change === 'revision') h.page.data.gameModule.revision = 5;
    if (change === 'session') h.page.data.gameModule.sessionId = 92;
    if (change === 'pending') h.page.data.gameModule.write.status = 'unknown';
    if (change === 'hint') h.page.data.gameModule.nodes[0].hint.nextImpactLabel = 'changed';
    h.modals[0].success({ confirm: true });
    assert.deepEqual(h.assists, []);
  });
  test(handler + ' still accepts current modal', () => {
    const h = load();
    h.page[handler]({ currentTarget: { dataset: { node: 31, level: 1 } } });
    h.modals[0].success({ confirm: true });
    assert.equal(h.assists.length, 1);
    assert.equal(h.assists[0].expectedRevision, 4);
  });
}
test('old role card cannot confirm a newer assignment projection', () => {
  const h = load();
  h.page._maybeShowRoleCard({ roleCode: 'SCOUT', roleName: 'fixture', confirmed: false });
  h.page.data.gameModule.revision = 5;
  h.page.confirmPlayerRole();
  assert.deepEqual(h.requests, []);
});
