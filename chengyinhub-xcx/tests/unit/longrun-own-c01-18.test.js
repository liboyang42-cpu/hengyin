'use strict';
// Written only; NOT_RUN.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition, member = '11';
  const requests = [], uploads = [], replies = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => member, sendRequest: r => requests.push(r),
    getUploadClient: () => ({ uploadOne: (_file, r) => uploads.push(r) }) });
  global.Page = v => { definition = v; }; global.wx = { showToast() {} };
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8,
    shopNpc: { show: true, open: true, nodeId: 31, msgs: [] } }, _startThinkBeat() {},
    _pushShopNpcReply: (...args) => replies.push(args) });
  page.setData = patch => { for (const [key, value] of Object.entries(patch)) {
    if (key.startsWith('shopNpc.')) page.data.shopNpc[key.slice(8)] = value;
  } };
  return { page, requests, uploads, replies, account: value => { member = value; } };
}
for (const kind of ['text', 'voice']) {
  for (const change of ['account', 'node', 'conversation', 'closed', 'newrequest']) test(kind + ' reply ignores ' + change, async () => {
    const h = load();
    if (kind === 'text') h.page._askShopNpc('fixture ask'); else h.page._sendVoice('fixture.mp3');
    if (change === 'account') h.account('22');
    if (change === 'node') h.page.data.shopNpc.nodeId = 32;
    if (change === 'conversation') h.page.data.shopNpc = { show: true, open: true, nodeId: 31, msgs: [] };
    if (change === 'closed') h.page.data.shopNpc.open = false;
    if (change === 'newrequest') h.page._askShopNpc('new fixture ask');
    if (kind === 'text') h.requests[0].success({ code: 200, data: { text: 'old reply' } });
    else h.uploads[0].onDone({ ok: true, data: { asr: 'old asr', data: { text: 'old reply' } } });
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(h.replies, []); assert.deepEqual(h.page.data.shopNpc.msgs, []);
  });
}
test('current voice callback appends ASR and applies its reply once', () => {
  const h = load(); h.page._sendVoice('fixture.mp3');
  const result = { ok: true, data: { asr: 'fixture asr', data: { text: 'fixture reply' } } };
  h.uploads[0].onDone(result); h.uploads[0].onDone(result);
  assert.equal(h.page.data.shopNpc.msgs.length, 1); assert.equal(h.replies.length, 1);
});
