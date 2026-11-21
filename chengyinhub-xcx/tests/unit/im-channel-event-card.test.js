'use strict';
// W7 SOC-7: a channel event card in the chat opens the existing activity page.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const CHAT = require.resolve('../../subpackageB/pages/im/chat/index.js');
const systemCard = require('../../subpackageB/pages/im/chat/system-card-target.js');

const card = extra => JSON.stringify({ cardType: 'channel_event', eventType: 'event', eventId: '7', activityId: 900,
  title: '周六夜跑', ...extra });

test('channelEventUrl reads only the activity id of a channel_event text message', () => {
  assert.equal(systemCard.channelEventUrl({ msgType: 1, senderId: 3, extraJson: card() }), '/pages/activity/detail/index?id=900');
  assert.equal(systemCard.channelEventUrl({ msgType: '1', extraJson: card({ activityId: '901' }) }), '/pages/activity/detail/index?id=901');
  assert.equal(systemCard.channelEventUrl({ msgType: 1, extraJson: card({ activityId: 0 }) }), '');
  assert.equal(systemCard.channelEventUrl({ msgType: 1, extraJson: card({ activityId: 'x' }) }), '');
  assert.equal(systemCard.channelEventUrl({ msgType: 1, extraJson: card({ url: '/pages/other/index' }) }), '');
  assert.equal(systemCard.channelEventUrl({ msgType: 1, extraJson: card({ cardType: 'game_event' }) }), '');
  assert.equal(systemCard.channelEventUrl({ msgType: 3, extraJson: card() }), '');
  assert.equal(systemCard.channelEventUrl({ msgType: 1, extraJson: 'not json' }), '');
  assert.equal(systemCard.channelEventUrl(null), '');
});

function harness(t, message) {
  const h = { nav: [] };
  h.app = { globalData: { authorization: 'synthetic-session' }, getUserID: () => 1,
    getAvatar: () => '', getPageSize: () => 20, getTotalPage: (n, size) => Math.ceil(n / size), canUseCommunity: () => true,
    getRequestErrorMessage: (r, f) => r && r.msg || f, sendRequest() { return { abort() {} }; }, tips() {} };
  global.getApp = () => h.app;
  global.wx = { getStorageSync: () => '', navigateTo: row => h.nav.push(row) };
  global.Page = row => { h.definition = row; };
  delete require.cache[CHAT]; require(CHAT);
  h.page = { ...h.definition, data: { ...JSON.parse(JSON.stringify(h.definition.data)), myId: 1, type: 5, conversationId: 10, loadState: 'ready' },
    setData(patch) { Object.assign(this.data, patch); } };
  h.page.data.msgs = h.page.decorate([message]);
  t.after(() => h.page.onUnload());
  return h;
}

test('tapping a member-posted event card opens the activity; other text does nothing', t => {
  const h = harness(t, { id: 5, senderId: 3, msgType: 1, conversationId: 10, content: '【活动】周六夜跑', extraJson: card() });
  assert.equal(h.page.data.msgs[0].channelEvent, true);
  h.page.onChannelEventTap({ currentTarget: { dataset: { msg: 5 } } });
  assert.deepEqual(h.nav.map(row => row.url), ['/pages/activity/detail/index?id=900']);
  h.page.onChannelEventTap({ currentTarget: { dataset: { msg: 6 } } });
  assert.equal(h.nav.length, 1);
});

test('a plain text message is not an event card', t => {
  const h = harness(t, { id: 5, senderId: 3, msgType: 1, conversationId: 10, content: 'hi', extraJson: null });
  assert.equal(h.page.data.msgs[0].channelEvent, false);
  h.page.onChannelEventTap({ currentTarget: { dataset: { msg: 5 } } });
  assert.equal(h.nav.length, 0);
});

test('both bubbles bind the tap only for event cards', () => {
  const wxml = fs.readFileSync(CHAT.replace(/\.js$/, '.wxml'), 'utf8');
  assert.equal((wxml.match(/bindtap="\{\{item\.channelEvent \? 'onChannelEventTap' : ''\}\}"/g) || []).length, 2);
});
