'use strict';
// Written only; NOT_RUN.
const { test } = require('node:test'); const assert = require('node:assert/strict'); const path = require('node:path');
function load() {
  let definition, member = '11'; const requests = [], feedback = [], reads = [];
  // master 的 leadArrive 先经用途说明弹窗、再取一次 GCJ02 定位才 POST:真实登录态 + 用户同意 + 定位成功。
  global.getApp = () => ({ globalData: {}, DEV_TOKEN: 'dev-token', getUserID: () => member, getAuthorization: () => 'fixture-session',
    sendRequest: r => requests.push(r) });
  global.Page = v => { definition = v; };
  global.wx = { showModal: o => o.success({ confirm: true }), getLocation: o => o.success({ latitude: 31.23, longitude: 121.47 }) };
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8 }, diegetic: v => feedback.push(v), loadTeamProgress: () => reads.push(true) });
  return { page, requests, feedback, reads, account: v => { member = v; } };
}
for (const action of ['leadArrive', 'leadStart']) {
  for (const change of ['account', 'activity', 'lifecycle']) test(action + ' ignores old ' + change, async () => {
    const h = load(); h.page[action]();
    if (change === 'account') h.account('22');
    if (change === 'activity') h.page.data.activityId = 19;
    if (change === 'lifecycle') h.page._leadLifecycleEpoch = 1;
    h.requests[0].success({ code: 200, msg: 'old fixture success' });
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(h.feedback, []); assert.deepEqual(h.reads, []);
  });
  // master 的 leadArrive 是单飞:请求在途时第二次点击直接忽略(_leadArriving),只验在途那一条。
  if (action === 'leadArrive') test(action + ' second press while in flight is ignored and the response reads once', async () => {
    const h = load(); h.page[action](); h.page[action]();
    assert.equal(h.requests.length, 1); assert.equal(h.requests[0].data.activityId, 9);
    h.requests[0].success({ code: 200, msg: 'fixture success' });
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(h.feedback, ['fixture success']); assert.equal(h.reads.length, 1);
  });
  else test(action + ' latest response keeps its own original activity and reads once', async () => {
    const h = load(); h.page[action](); h.page[action]();
    assert.equal(h.requests[0].data.activityId, 9);
    h.requests[1].success({ code: 200, msg: 'new fixture success' });
    h.requests[0].success({ code: 200, msg: 'old fixture success' });
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(h.feedback, ['new fixture success']); assert.equal(h.reads.length, 1);
  });
}
