'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { platform, receipt } = require('./helpers/team-event-platform');
const PAGE = path.resolve(__dirname, '../../pages/activity/official-detail/index.js');
function boot() {
  let config, uid = '1', token = 'a';
  const requests = [], navs = [], toasts = [];
  const app = { globalData: {}, getUserID: () => uid, getAuthorization: () => token,
    sendRequest: r => requests.push(r) };
  global.getApp = () => app;
  global.wx = { ...platform(), setNavigationBarColor() {}, showToast: o => toasts.push(o.title),
    navigateTo: o => navs.push(o), getSystemInfoSync: () => ({}), vibrateShort() {}, requestSubscribeMessage() {} };
  global.Page = c => { config = c; };
  delete require.cache[PAGE]; require(PAGE);
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  page.setData = patch => { for (const [key, value] of Object.entries(patch)) {
    if (key === 'e.missions') page.data.e.missions = value; else page.data[key] = value;
  } };
  return { page, requests, navs, toasts, account: (id, auth) => { uid = id; token = auth; } };
}
const event = id => ({ id, contractVersion: 2, status: 2, signed: false, missions: [] });
test('W8-07 event Long remains exact through read, signup and share', async () => {
  const h = boot(); h.page.onLoad({ id: '9007199254740993' });
  assert.equal(h.requests[0].url, '/api/official/events/9007199254740993');
  await h.page.doSignup();
  assert.equal(h.requests[1].url, '/api/official/signup-command');
  assert.equal(JSON.parse(h.requests[1].data).targetId, '9007199254740993');
  assert.ok(h.page.onShareAppMessage().path.endsWith('9007199254740993'));
});
test('W8-07 unsafe numeric and out-of-range ids never request another event', async () => {
  for (const id of [9007199254740993, '9223372036854775808', '0', 'bad']) {
    const h = boot(); h.page.onLoad({ id }); await h.page.doSignup();
    assert.equal(h.requests.length, 0); assert.equal(h.page.data.loading, false);
  }
});
test('W8-07 theme Long reaches detail and name lookup without rounding', () => {
  const h = boot(); h.page.onLoad({ id: '9' });
  const mission = { missionType: 'THEME_VERIFIED_FINISH', binding: { bindingType: 'THEME', sourceRefId: '9007199254740993' } };
  h.requests[0].success({ code: 200, data: { ...event('9'), missions: [mission] } });
  assert.equal(h.requests.find(r => r.url === '/api/topic/info-to-user').data.id, '9007199254740993');
  assert.equal(h.page.goBoundTopic(mission), true);
  assert.ok(h.navs[0].url.endsWith('9007199254740993'));
});
test('W8-07 account switch discards old detail and clears old organizer state', () => {
  const h = boot(); h.page.onLoad({ id: '9' });
  h.page.data.organizerEntry = true; h.account('2', 'b');
  h.requests[0].success({ code: 200, data: event('9') });
  assert.equal(h.page.data.e, null); assert.equal(h.page.data.organizerEntry, false);
  h.page.onShow(); assert.equal(h.requests.length, 2);
});
test('W8-07 old organizer and bound-title responses cannot overwrite a refreshed event', () => {
  const h = boot(); h.page.onLoad({ id: '9' });
  h.requests[0].success({ code: 200, data: { ...event('9'), missions: [{ binding: { bindingType: 'THEME', sourceRefId: 12 } }] } });
  const topic = h.requests.find(r => r.url === '/api/topic/info-to-user');
  const probe = h.requests.find(r => r.url.endsWith('/publish-check'));
  h.page.fetch(); const current = h.requests.at(-1);
  current.success({ code: 200, data: event('9') });
  topic.success({ code: 200, data: { name: 'old' } }); probe.success({ code: 200 });
  assert.deepEqual(h.page.data.e.missions, []); assert.equal(h.page.data.organizerEntry, false);
});
test('W8-07 hide invalidates reads; returning refreshes server participation', () => {
  const h = boot(); h.page.onLoad({ id: '9' }); h.page.onHide();
  h.requests[0].success({ code: 200, data: event('9') }); assert.equal(h.page.data.e, null);
  h.page.onShow(); assert.equal(h.requests.length, 2);
});
test('W8-07 late signup after account change cannot toast success or reload for new user', async () => {
  const h = boot(); h.page.onLoad({ id: '9' }); await h.page.doSignup();
  const write = h.requests.at(-1); h.account('2', 'b');
  write.success(receipt(write, 'APPLIED'));
  assert.deepEqual(h.toasts, []); assert.equal(h.requests.length, 2);
});
test('W8-07 unload prevents late detail and signup effects', async () => {
  const h = boot(); h.page.onLoad({ id: '9' }); await h.page.doSignup();
  const read = h.requests[0], write = h.requests[1]; h.page.onUnload();
  read.success({ code: 200, data: event('9') }); write.success(receipt(write, 'APPLIED'));
  assert.equal(h.page.data.e, null); assert.deepEqual(h.toasts, []); assert.equal(h.requests.length, 2);
});

test('W8-07 invalidated participation cannot promise eligibility or resubmit signup', () => {
  for (const state of ['LEFT', 'REFUNDED', 'BANNED']) {
    const h = boot(); h.page.onLoad({ id: '9' });
    h.requests[0].success({ code: 200, data: { ...event('9'), participation: { state },
      missions: [{ missionCode: 'one', missionType: 'THEME_VERIFIED_FINISH' }] } });
    assert.equal(h.page.data.cta.type, 'wait');
    assert.equal(h.page.data.e._eligibleText, '参与资格已失效');
    const count = h.requests.length;
    h.page.doSignup(); h.page.onMissionTap({ currentTarget: { dataset: { code: 'one' } } });
    assert.equal(h.requests.length, count);
  }
});
test('W8-07 settlement and offline are distinct readback states', () => {
  const h = boot();
  assert.equal(h.page._cta({ ...event('9'), status: 4 }).text, '活动待结算');
  assert.equal(h.page._cta({ ...event('9'), status: 9 }).text, '活动已下线');
});
