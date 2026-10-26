'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const file = path.resolve(__dirname, '../../pages/merchant/decor/ai-npc/index.js');
function mount() {
  let page, owner = '1', token = 'a';
  const requests = [], dialogs = [], toasts = [], changes = [];
  const app = { getUserID: () => owner, getAuthorization: () => token, sendRequest: options => requests.push(options) };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Page: d => { page = d; }, getApp: () => app,
    wx: {}, clearTimeout, setTimeout,
    require: name => name.endsWith('/toast.js') ? x => toasts.push(x)
      : name.endsWith('/modal.js') ? { show: x => dialogs.push(x) }
      : name.endsWith('/merchant-theme.js') ? { merchantPageShow() {}, merchantPageRestore() {} }
      : name.endsWith('/pixel-avatar.js') ? { AVATAR_IDS: [] } : {} });
  page = { ...page, data: { ...page.data, voiceStatus: 2 }, setData(p) { changes.push(p); Object.assign(this.data, p); } };
  return { page, requests, dialogs, toasts, changes, switch(kind) { if (kind === 'owner') owner = '2'; else token = 'b'; } };
}
const settle = () => new Promise(r => setImmediate(r));
for (const stage of ['confirm', 'reset', 'readback']) for (const transition of ['owner', 'token', 'hide', 'unload']) {
  test(stage + ' ignores late result after ' + transition, async () => {
    const h = mount(); h.page.clearVoice();
    if (stage !== 'confirm') h.dialogs[0].success({ confirm: true });
    if (stage === 'readback') { h.requests[0].success({ code: 200 }); await settle(); assert.equal(h.requests.length, 2); }
    if (transition === 'hide') h.page.onHide(); else if (transition === 'unload') h.page.onUnload(); else h.switch(transition);
    const before = h.changes.length;
    if (stage === 'confirm') h.dialogs[0].success({ confirm: true });
    else h.requests.at(-1).success({ code: 200, data: { voiceStatus: 0 } });
    await settle();
    assert.equal(h.requests.length, stage === 'confirm' ? 0 : stage === 'reset' ? 1 : 2);
    assert.equal(h.toasts.length, 0); assert.equal(h.changes.length, before);
  });
}
test('same reader clears only after zero-status readback', async () => {
  const h = mount(); h.page.clearVoice(); h.dialogs[0].success({ confirm: true });
  h.requests[0].success({ code: 200 }); await settle(); assert.equal(h.toasts.length, 0);
  h.requests[1].success({ code: 200, data: { voiceStatus: 0 } }); await settle();
  assert.equal(h.toasts[0], '角色声音已清除'); assert.equal(h.page.data.voiceBusy, false);
});
test('same reader late failure after hide cannot update feedback; reentry unlocks readback', async () => {
  const h = mount(); h.page.clearVoice(); h.dialogs[0].success({ confirm: true }); h.page.onHide();
  const before = h.changes.length; h.requests[0].fail(); await settle(); assert.equal(h.changes.length, before);
  h.page.onShow(); assert.equal(h.page.data.voiceBusy, false); assert.equal(h.page._voiceClearing, false);
  assert.equal(h.requests.filter(x => x.url.endsWith('/reset')).length, 1);
});
