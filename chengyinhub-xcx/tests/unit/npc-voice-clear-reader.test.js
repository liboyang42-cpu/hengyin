'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const file = path.resolve(__dirname, '../../pages/merchant/decor/ai-npc/index.js');
function mount(legacy = false) {
  let page;
  const requests = [], dialogs = [], toasts = [], changes = [];
  const app = { sendRequest: options => requests.push(options) }, wx = {};
  const environment = require('../helpers/voice-enrollment-environment.cjs');
  const journal = environment.install(app, wx), session = app.getSession();
  session.applySnapshot({ user_id: '1', authorization: 'a', role: 'merchant' });
  app.getUserID = () => session.getUserID(); app.getAuthorization = () => session.getAuthorization();
  let original = null;
  if (!legacy) {
    original = environment.seedUnknown(app, wx, 'merchant', 'media-asset:73');
    journal.observe(wx, '1', 'merchant', original.requestId, { voiceRequestId: original.requestId,
      voiceIntentId: 17, voiceIntentState: 'READY', voiceStatus: 2, voiceSelected: true, voiceSample: 'media-asset:73' });
  }
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Page: d => { page = d; }, getApp: () => app,
    wx, clearTimeout, setTimeout,
    require: name => name.endsWith('/voice-enrollment-journal.js') ? journal
      : name.endsWith('/toast.js') ? x => toasts.push(x)
      : name.endsWith('/modal.js') ? { show: x => dialogs.push(x) }
      : name.endsWith('/merchant-theme.js') ? { merchantPageShow() {}, merchantPageRestore() {} }
      : name.endsWith('/pixel-avatar.js') ? { AVATAR_IDS: [] } : {} });
  page = { ...page, data: { ...page.data, voiceStatus: 2 }, setData(p) { changes.push(p); Object.assign(this.data, p); } };
  return { page, requests, dialogs, toasts, changes, original, journal, wx,
    snapshot() { return JSON.stringify(journal.load(wx, '1', 'merchant')); },
    response() { return { code: 200, data: original ? { voiceStatus: 0, voiceSample: null,
      voiceRequestId: original.requestId, voiceIntentId: 17, voiceIntentState: 'READY',
      voiceSelected: false, voiceReasonCode: 'VOICE_PROFILE_CHANGED' } : { voiceStatus: 0 } }; },
    switch(kind) { if (kind === 'owner') session.applySnapshot({ user_id: '2', authorization: 'b', role: 'merchant' }); else session.setAuthorization('b'); } };
}
const settle = () => new Promise(r => setImmediate(r));
for (const stage of ['confirm', 'reset', 'readback']) for (const transition of ['owner', 'token', 'hide', 'unload']) {
  test(stage + ' ignores late result after ' + transition, async () => {
    const h = mount(); h.page.clearVoice();
    if (stage !== 'confirm') h.dialogs[0].success({ confirm: true });
    if (stage === 'readback') { h.requests[0].success({ code: 200 }); await settle(); assert.equal(h.requests.length, 2); }
    if (transition === 'hide') h.page.onHide(); else if (transition === 'unload') h.page.onUnload(); else h.switch(transition);
    const before = h.changes.length, saved = h.snapshot();
    if (stage === 'confirm') h.dialogs[0].success({ confirm: true });
    else h.requests.at(-1).success(h.response());
    await settle();
    assert.equal(h.requests.length, stage === 'confirm' ? 0 : stage === 'reset' ? 1 : 2);
    assert.equal(h.toasts.length, 0); assert.equal(h.changes.length, before);
    assert.equal(h.snapshot(), saved, '迟到回调不能改原声音意图');
  });
}
test('same reader clears only after zero-status readback', async () => {
  for (const legacy of [false, true]) {
    const h = mount(legacy), saved = h.snapshot(); h.page.clearVoice(); h.dialogs[0].success({ confirm: true });
    h.requests[0].success({ code: 200 }); await settle(); assert.equal(h.toasts.length, 0);
    assert.equal(h.requests[1].url, '/api/merchant/npc/voice/status');
    assert.equal(typeof h.requests[1].data, 'string', '真实_request发送JSON文本，不是可变参数对象');
    const query = JSON.parse(h.requests[1].data);
    if (h.original) assert.equal(query.voiceRequestId, h.original.requestId);
    else assert.equal(query.voiceRequestId, null);
    assert.deepEqual(Object.keys(query), ['voiceRequestId']);
    h.requests[1].success(h.response()); await settle();
    assert.equal(h.toasts[0], '角色声音已清除'); assert.equal(h.page.data.voiceBusy, false);
    assert.equal(h.snapshot(), saved, '清除选中声音不删除原READY事实/身份');
    assert.equal(h.requests.filter(request => request.url.endsWith('/enroll')).length, 0);
  }
  for (const mismatch of ['request', 'profile']) {
    const h = mount(), saved = h.snapshot(); h.page.clearVoice(); h.dialogs[0].success({ confirm: true });
    h.requests[0].success({ code: 200 }); await settle();
    const response = h.response();
    if (mismatch === 'request') response.data.voiceRequestId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
    else response.data.voiceReasonCode = 'UNKNOWN';
    h.requests[1].success(response); await settle();
    assert.equal(h.toasts.length, 0); assert.equal(h.page.data.voiceStatus, 2);
    assert.equal(h.snapshot(), saved, '错原号或未证实profile变更不得确认清除');
    assert.equal(h.requests.filter(request => request.url.endsWith('/reset')).length, 1);
  }
});
test('same reader late failure after hide cannot update feedback; reentry unlocks readback', async () => {
  const h = mount(); h.page.clearVoice(); h.dialogs[0].success({ confirm: true }); h.page.onHide();
  const before = h.changes.length; h.requests[0].fail(); await settle(); assert.equal(h.changes.length, before);
  h.page.onShow(); assert.equal(h.page.data.voiceBusy, false); assert.equal(h.page._voiceClearing, false);
  assert.equal(h.requests.filter(x => x.url.endsWith('/reset')).length, 1);
});
