const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { platform } = require('./helpers/team-event-platform');
const file = path.resolve(__dirname, '../../subpackageB/pages/im/chat/index.js');
function mount() {
  let page, user = '71'; const requests = [];
  const app = { globalData: {}, getUserID: () => user, getAuthorization: () => 'session',
    getAvatar: () => '', getNickname: () => 'self', getUserRole: () => 'player', getUserType: () => 1,
    tips() {}, getRequestErrorMessage: (r, fallback) => r && r.msg || fallback,
    sendRequest(r) { requests.push(r); return { abort() {} }; } };
  const wx = { ...platform(), getStorageSync: () => true, setNavigationBarColor() {}, setBackgroundColor() {} };
  global.wx = wx; global.getApp = () => app;
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { require: createRequire(file), Page: d => { page = d; },
    getApp: () => app, wx, console, setTimeout: () => 1, clearTimeout() {}, setInterval: () => 1, clearInterval() {} });
  page.data = JSON.parse(JSON.stringify(page.data));
  page.setData = patch => Object.assign(page.data, patch);
  Object.assign(page.data, { myId: '71', conversationId: 26, type: 1, loadState: 'ready', msgs: [], topicCache: {} });
  page.loadMessages(false);
  const by = () => requests.filter(r => r.url === '/api/im/messages');
  return { page, by, account(id) { user = id; }, answer(list, extra = {}) { by().at(-1).success({ code: 200, data: { list, hasMore: false, ...extra } }); } };
}
const message = (id, avatar, name = 'peer') => ({ id, senderId: 72, senderAvatar: avatar, senderName: name, msgType: 1, content: 'original' });

test('poll refreshes current profile on existing message without changing body, cursor, watermark or scrolling', () => {
  const h = mount(); h.answer([message(10, 'OLD', 'Old name')]);
  const original = h.page.data.msgs[0], cursor = h.page.data.cursor;
  let scrolls = 0; h.page.followIfNearBottom = () => { scrolls++; };
  h.page.pollNew(); h.answer([{ ...message(10, 'NEW', 'New name'), content: 'must not replace body' }]);
  assert.equal(h.page.data.msgs[0].senderAvatar, 'NEW');
  assert.equal(h.page.data.msgs[0].senderName, 'New name');
  assert.equal(h.page.data.msgs[0].content, original.content);
  assert.equal(h.page.data.cursor, cursor); assert.equal(h.page._pollWatermark, 10); assert.equal(scrolls, 0);
});
test('poll clears withheld identity fields, retains rows outside response and merges new messages in order', () => {
  const h = mount(); h.answer([message(9, 'HISTORY'), message(10, 'OLD')]);
  h.page.pollNew(); const cleared = message(10, ''); delete cleared.senderAvatar; delete cleared.senderName;
  h.answer([message(11, 'NEW'), cleared]);
  assert.deepEqual(Array.from(h.page.data.msgs, m => m.id), [9, 10, 11]);
  assert.equal(h.page.data.msgs[0].senderAvatar, 'HISTORY');
  assert.equal(h.page.data.msgs[1].senderAvatar, ''); assert.equal(h.page.data.msgs[1].senderName, '');
  assert.equal(h.page._pollWatermark, 11);
});
test('hidden and changed-account poll responses cannot update cached profiles', () => {
  for (const retire of [h => h.page.onHide(), h => h.account('99')]) {
    const h = mount(); h.answer([message(10, 'OLD')]); h.page.pollNew(); retire(h);
    h.answer([message(10, 'NEW')]); assert.equal(h.page.data.msgs[0].senderAvatar, 'OLD');
  }
});
test('profile refresh waits for the complete validated polling bridge and preserves fresh-message order', () => {
  const h = mount(); h.answer([message(10, 'OLD')]); h.page.pollNew();
  h.answer([message(12, 'LATEST')], { hasMore: true, nextCursor: 12 });
  assert.equal(h.page._pollWatermark, 10); assert.equal(h.page.data.msgs.length, 1);
  h.answer([message(11, 'NEW'), message(10, 'CURRENT')]);
  assert.deepEqual(Array.from(h.page.data.msgs, m => m.id), [10, 11, 12]);
  assert.equal(h.page.data.msgs[0].senderAvatar, 'CURRENT'); assert.equal(h.page._pollWatermark, 12);
});
