'use strict';
// U-142:用户往上翻看历史时,8 秒轮询到的新消息不能把视图强拉到底;停在底部附近时照常跟随。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../subpackageB/pages/im/chat/index.js');
function harness(position) {
  let page;
  const requests = [];
  const wx = { getStorageSync() {}, setStorageSync() {}, removeStorageSync() {}, navigateBack() {}, redirectTo() {},
    createSelectorQuery() {
      const fields = [];
      const query = {
        select(selector) {
          assert.equal(selector, '.msgs');
          return { boundingClientRect() { fields.push({ height: position.height }); return query; },
            scrollOffset() { fields.push({ scrollTop: position.scrollTop, scrollHeight: position.scrollHeight }); return query; } };
        },
        exec(callback) { callback(fields); },
      };
      return query;
    } };
  global.wx = wx;
  const app = { globalData: {}, getUserID: () => 1, sendRequest: request => requests.push(request), tips() {} };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { require: createRequire(file), getApp: () => app,
    Page: definition => { page = definition; }, wx, setTimeout: fn => fn(), clearTimeout() {}, setInterval() {}, clearInterval() {} });
  page.data = { ...page.data, myId: 1, conversationId: 26, loadState: 'ready', msgs: [], topicCache: {} };
  page.setData = patch => Object.entries(patch).forEach(([key, value]) => { page.data[key] = value; });
  page._pollWatermark = 10;
  return { page, requests };
}
function poll(h, id) {
  h.page.setData({ toView: '' });
  h.page.pollNew();
  const request = h.requests.filter(r => r.url === '/api/im/messages').pop();
  request.success({ code: 200, data: { list: [{ id, senderId: 2, msgType: 1, content: 'hi ' + id }], hasMore: false } });
}

test('poll follows to the newest message while the reader is at the bottom', () => {
  const h = harness({ height: 600, scrollTop: 1380, scrollHeight: 2000 });
  poll(h, 11);
  assert.equal(h.page.data.toView, 'bottom-anchor');
});

test('poll does not yank a reader who scrolled up into history', () => {
  const position = { height: 600, scrollTop: 300, scrollHeight: 2000 };
  const h = harness(position);
  poll(h, 11);
  assert.equal(h.page.data.toView, '', 'reading history must not jump to the newest message');
  assert.ok(h.page.data.msgs.some(m => m.id === 11), 'the new message is still merged');
  position.scrollTop = 1400;
  poll(h, 12);
  assert.equal(h.page.data.toView, 'bottom-anchor', 'back at the bottom, following resumes');
});
