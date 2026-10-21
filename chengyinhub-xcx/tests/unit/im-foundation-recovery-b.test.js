'use strict';
// 路线卡只信消息投影；signup 详情继续验证账号隔离。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../subpackageB/pages/im/chat/index.js');
function harness() {
  let page, account = 1;
  const requests = [];
  const wx = { getStorageSync() {}, setStorageSync() {}, removeStorageSync() {}, navigateBack() {}, redirectTo() {} };
  global.wx = wx;
  const app = { globalData: {}, getUserID: () => account, sendRequest: request => requests.push(request), tips() {} };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { require: createRequire(file), getApp: () => app,
    Page: definition => { page = definition; }, wx, setTimeout() {}, clearTimeout() {}, setInterval() {}, clearInterval() {} });
  page.data = { ...page.data, myId: 1, conversationId: 26, loadState: 'ready', msgs: [], topicCache: {} };
  page.setData = patch => Object.entries(patch).forEach(([key, value]) => {
    if (key.startsWith('topicCache.')) page.data.topicCache[key.slice(11)] = value;
    else page.data[key] = value;
  });
  return { page, requests, account: value => { account = value; } };
}
function committed(id, key) { return { id, senderId: 1, msgType: 1, content: 'hello', clientMessageId: key }; }

test('switched account cannot apply old history, current load, polling or route results', () => {
  const h = harness();
  h.page.setData({ hasMore: true, cursor: 80 }); h.page.loadMore(); h.page.loadMessages(false); h.page.pollNew();
  h.page.fetchTopics([{ card: { cardType: 'signup', topicId: 8 } }]);
  assert.ok(h.requests.some(r => r.url === '/api/topic/info-to-user'));
  const before = JSON.stringify(h.page.data); h.account(2);
  h.requests.forEach(r => r.success({ code: 200, data: { list: [committed(99, 'other')], hasMore: false, name: 'other account route' } }));
  assert.equal(JSON.stringify(h.page.data), before);
});

test('switched account drops late route detail failure as well', () => {
  const h = harness();
  h.page.fetchTopics([{ card: { cardType: 'signup', topicId: 9 } }]);
  h.account(2);
  h.requests[0].fail({});
  assert.equal(h.page.data.topicCache[9], undefined);
});

test('same account signup detail still lands', () => {
  const h = harness();
  h.page.fetchTopics([{ card: { cardType: 'signup', topicId: 8 } }]);
  h.requests[0].success({ code: 200, data: { name: 'public route' } });
  assert.equal(h.page.data.topicCache[8].name, 'public route');
});
