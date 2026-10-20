const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createRequire } = require('node:module');
const source = path.resolve(__dirname, '../../subpackageB/pages/im/list/index.js');
function fixture() {
  let page;
  const requests = [];
  const toast = Object.assign(() => {}, { success() {} });
  const context = {
    Page(value) { page = value; }, wx: {},
    getApp: () => ({ sendRequest(r) { requests.push(r); }, tips() {}, getUserID: () => 1 }),
    require(id) {
      if (id.includes('toast')) return toast;
      if (id.includes('response-shape')) return { isBizOk: r => r && r.code == 200, isRecordList: Array.isArray };
      if (id.includes('datetime')) return { toTimestamp: () => NaN };
      if (id.includes('merchant-access-policy')) return { normalizeMerchantAccess: () => ({}) };
      return createRequire(source)(id);
    },
  };
  vm.runInNewContext(fs.readFileSync(source, 'utf8'), context, { filename: source });
  page.data = JSON.parse(JSON.stringify(page.data));
  page.setData = function (patch) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.');
      let obj = this.data;
      for (let i = 0; i < parts.length - 1; i++) obj = obj[parts[i]];
      obj[parts.at(-1)] = value;
    }
  };
  page.selectComponent = () => ({ busyOn() {}, done() {}, failed() {} });
  page.data.list = [{ conversationId: 1, type: 1, muted: 0 }, { conversationId: 2, type: 1, muted: 1 }];
  page.data.loaded = true;
  page.refreshView();
  return { page, requests };
}

test('delete success invalidates an earlier list response and settles refresh state', () => {
  const { page, requests } = fixture();
  page.loadConversations(true);
  const oldList = requests.at(-1);
  page._pendingDelete = { id: 1, memberId: 1 };
  page.onDangerConfirm({ detail: { key: 'im.conversation.delete' } });
  requests.at(-1).success({ code: 200 });
  oldList.success({ code: 200, data: [{ conversationId: 1, type: 1, lastMsgType: 3, lastMsgText: 'old route title' }] });
  assert.equal(page.data.list.some(c => c.conversationId === 1), false);
  assert.equal(page.data.refreshing, false);
  page.loadConversations(true);
  requests.at(-1).success({ code: 200, data: [{ conversationId: 3, type: 1 }] });
  assert.equal(page.data.list[0].conversationId, 3, 'later fresh loads remain accepted');
});
// master 语义:mute 发出后才发出的新列表回读为准(im-uiux-recovery-contract「新列表已回读后忽略旧」);
// 只有 mute 之前就发出的旧列表重排时,失败才按会话 ID 回滚。
for (const response of ['network', 'business']) test('mute ' + response + ' failure after older-list reorder restores only original conversation', () => {
  const { page, requests } = fixture();
  page.loadConversations(true);
  const oldList = requests.at(-1);
  page.onRowMute({ currentTarget: { dataset: { id: 1, index: 0 } } });
  const mute = requests.at(-1);
  oldList.success({ code: 200, data: [{ conversationId: 2, type: 1, muted: 1 }, { conversationId: 1, type: 1, muted: 1 }] });
  if (response === 'network') mute.fail(); else mute.success({ code: 500 });
  assert.equal(page.data.list[0].conversationId, 2);
  assert.equal(page.data.list[0].muted, 1, 'unrelated conversation unchanged');
  assert.equal(page.data.list[1].muted, 0, 'original conversation rolled back');
});
for (const response of ['network', 'business']) test('mute ' + response + ' failure after a newer list read keeps the server read', () => {
  const { page, requests } = fixture();
  page.onRowMute({ currentTarget: { dataset: { id: 1, index: 0 } } });
  const mute = requests.at(-1);
  page.loadConversations(true);
  requests.at(-1).success({ code: 200, data: [{ conversationId: 2, type: 1, muted: 1 }, { conversationId: 1, type: 1, muted: 1 }] });
  if (response === 'network') mute.fail(); else mute.success({ code: 500 });
  assert.deepEqual(page.data.list.map(c => [c.conversationId, c.muted]), [[2, 1], [1, 1]]);
  page.onRowMute({ currentTarget: { dataset: { id: 1, index: 1 } } });
  assert.equal(requests.at(-1).url, '/api/im/mute', 'lock released after ignored result');
});
test('late mute failure after original conversation disappears neither recreates nor changes another row', () => {
  const { page, requests } = fixture();
  page.onRowMute({ currentTarget: { dataset: { id: 1, index: 0 } } });
  const mute = requests.at(-1);
  page.loadConversations(true);
  requests.at(-1).success({ code: 200, data: [{ conversationId: 2, type: 1, muted: 1 }] });
  mute.fail();
  assert.equal(page.data.list.length, 1);
  assert.equal(page.data.list[0].muted, 1);
});

for (const order of ['list-first', 'mute-first']) test('mute success preserves confirmed value with an older list: ' + order, () => {
  const { page, requests } = fixture();
  page.loadConversations(true);
  const oldList = requests.at(-1);
  page.onRowMute({ currentTarget: { dataset: { id: 1, index: 0 } } });
  const mute = requests.at(-1);
  const load = () => oldList.success({ code: 200, data: [{ conversationId: 2, type: 1, muted: 1 }, { conversationId: 1, type: 1, muted: 0 }] });
  if (order === 'list-first') { load(); mute.success({ code: 200 }); }
  else { mute.success({ code: 200 }); load(); }
  assert.equal(page.data.list.find(c => c.conversationId === 1).muted, 1);
  assert.equal(page.data.list.find(c => c.conversationId === 2).muted, 1);
  assert.equal(page.data.refreshing, false);
});

test('mute success after original row disappears does not recreate it', () => {
  const { page, requests } = fixture();
  page.onRowMute({ currentTarget: { dataset: { id: 1, index: 0 } } });
  const mute = requests.at(-1);
  page.loadConversations(true);
  requests.at(-1).success({ code: 200, data: [{ conversationId: 2, type: 1, muted: 1 }] });
  mute.success({ code: 200 });
  assert.equal(page.data.list.length, 1);
  assert.equal(page.data.list[0].conversationId, 2);
});

test('mute sends only one request per conversation while other conversations remain independent', () => {
  const { page, requests } = fixture();
  const event = id => ({ currentTarget: { dataset: { id, index: id - 1 } } });
  page.onRowMute(event(1));
  page.onRowMute(event(1));
  assert.equal(requests.length, 1);
  page.onRowMute(event(2));
  assert.equal(requests.length, 2);
  requests[0].success({ code: 200 });
  page.onRowMute(event(1));
  assert.equal(requests.length, 3);
  assert.equal(requests[2].data.muted, 0);
});

test('mute network failure unlocks retry and duplicate old callback cannot release new request', () => {
  const { page, requests } = fixture();
  const event = { currentTarget: { dataset: { id: 1, index: 0 } } };
  page.onRowMute(event);
  const old = requests[0];
  old.fail();
  page.onRowMute(event);
  assert.equal(requests.length, 2);
  assert.equal(requests[1].data.muted, 1);
  old.fail();
  page.onRowMute(event);
  assert.equal(requests.length, 2);
  assert.equal(page.data.list[0].muted, 1);
});
