'use strict';
// Written only; NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition, member = '11';
  const requests = [], patches = [], callbacks = [], events = [];
  const analytics = require('../../utils/analytics.js'), original = analytics.track;
  analytics.track = (...args) => events.push(args);
  global.getApp = () => ({ globalData: { features: { finishNearbyRoute: true } }, getUserID: () => member, sendRequest: r => requests.push(r) });
  global.Page = v => { definition = v; }; global.wx = {};
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, mode: 2, showFinish: true, nodes: [] },
    _loc: { latitude: 0, longitude: 0 }, setData(p, cb) { patches.push(p); Object.assign(this.data, p); if (cb) callbacks.push(cb); } });
  return { page, requests, patches, callbacks, events, account: value => { member = value; }, cleanup: () => { analytics.track = original; } };
}
const response = { code: 200, data: { rows: [{ id: 19, topicId: 18, name: 'fixture recommendation' }] } };
for (const change of ['account', 'activity', 'topic', 'load']) test('finish recommendation ignores old ' + change, async () => {
  const h = load();
  try {
    const pending = h.page.loadFinishRouteRecommendation();
    if (change === 'account') h.account('22');
    if (change === 'activity') h.page.data.activityId = 29;
    if (change === 'topic') h.page.data.topicId = 28;
    if (change === 'load') h.page._playLoadEpoch = 1;
    h.requests[0].success(response);
    assert.equal(await pending, null); assert.equal(h.patches.length, 1); assert.deepEqual(h.events, []);
  } finally { h.cleanup(); }
});
test('recommendation display callback cannot log for a later account', async () => {
  const h = load();
  try {
    const pending = h.page.loadFinishRouteRecommendation(); h.requests[0].success(response);
    assert.equal((await pending).id, 19);
    h.account('22'); h.callbacks[0]();
    assert.deepEqual(h.events, []);
  } finally { h.cleanup(); }
});
