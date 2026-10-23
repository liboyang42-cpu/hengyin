'use strict';
// PRIVATE-GROUP-route-card-unavailable:路线卡拉不到(null)时可能是下架、私密,也可能只是网络失败。
// 卡片与 toast 一律用泛称「暂不可用」,不得声称「已下架」或「不公开」。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const CHAT = require.resolve('../../subpackageB/pages/im/chat/index.js');

function harness(t) {
  const h = { requests: [], nav: [], tips: [] };
  h.app = { globalData: { authorization: 'synthetic-session' }, getUserID: () => 1,
    getAvatar: () => '', getPageSize: () => 20, getTotalPage: (n, size) => Math.ceil(n / size), canUseCommunity: () => true,
    getRequestErrorMessage: (r, f) => r && r.msg || f,
    sendRequest(row) { h.requests.push(row); return { abort() {} }; }, tips: text => h.tips.push(text) };
  global.getApp = () => h.app;
  global.wx = { getStorageSync: () => '', navigateTo: row => h.nav.push(row) };
  global.Page = row => { h.definition = row; };
  delete require.cache[CHAT]; require(CHAT);
  h.page = { ...h.definition, data: { ...JSON.parse(JSON.stringify(h.definition.data)), myId: 1, type: 2, conversationId: 10, loadState: 'ready' },
    setData(patch) {
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.');
        let target = this.data;
        while (parts.length > 1) { const part = parts.shift(); target = target[part] = target[part] || {}; }
        target[parts[0]] = value;
      }
    } };
  t.after(() => h.page.onUnload());
  return h;
}
const tap = id => ({ currentTarget: { dataset: { topicid: id } } });

test('null-state route/signup card copy is generic and never claims delisted or private', () => {
  const wxml = fs.readFileSync(CHAT.replace(/\.js$/, '.wxml'), 'utf8');
  const nullBlock = wxml.slice(wxml.indexOf("topicCache[item.card.topicId] === null"));
  const sub = nullBlock.slice(0, nullBlock.indexOf('</block>'));
  assert.match(sub, /路线暂不可用/);
  assert.match(sub, /活动暂不可用/);
  assert.doesNotMatch(sub, /下架|不公开|私密/);
});

test('tapping an unavailable route card toasts a generic message and does not navigate', t => {
  for (const cached of [null, { name: '路线已不可用' }]) {
    const h = harness(t);
    h.page.data.topicCache = { 8: cached };
    h.page.onRouteTap(tap(8));
    assert.deepEqual(h.tips, ['该路线暂不可用']);
    assert.equal(h.nav.length, 0);
  }
});

test('network failure while fetching a signup topic lands in the same generic null state', t => {
  const h = harness(t);
  h.page.fetchTopics([{ content: '', card: { cardType: 'signup', topicId: 9 } }]);
  assert.equal(h.requests.length, 1);
  h.requests[0].fail();
  assert.equal(h.page.data.topicCache[9], null);
  h.page.onRouteTap(tap(9));
  assert.deepEqual(h.tips, ['该路线暂不可用']);
});
