'use strict';

// BL-199:官方活动详情底部 CTA(报名 / 完成任务)请求在途时连点只发一次,回包后可再次触发。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { platform, receipt } = require('./helpers/team-event-platform');

const PAGE = path.resolve(__dirname, '../../pages/activity/official-detail/index.js');

function harness() {
  const requests = [];
  let config;
  global.getApp = () => ({ sendRequest: (o) => requests.push(o), globalData: {}, getUserID: () => 1, getAuthorization: () => 'test-token' });
  global.wx = { ...platform(),
    showToast() {}, hideToast() {}, vibrateShort() {},
    requestSubscribeMessage() {}, getSystemInfoSync() { return {}; },
  };
  global.Page = (c) => { config = c; };
  delete require.cache[PAGE];
  require(PAGE);
  const page = Object.assign({}, config);
  page.data = { id: 9, e: { contractVersion: 1 }, cta: { type: 'explore' } };
  page.setData = function (patch) { Object.assign(this.data, patch); };
  page.fetch = () => {};
  return { page, requests };
}

const hits = (requests, suffix) => requests.filter((r) => String(r.url).endsWith(suffix));

test('BL-199 连调两次「完成任务」只发 1 个请求,回包后可再次触发', () => {
  const { page, requests } = harness();
  page.onCta();
  page.onCta();
  assert.equal(hits(requests, '/complete').length, 1);
  const first = hits(requests, '/complete')[0];
  first.success({ code: 200 });
  first.complete();
  page.onCta();
  assert.equal(hits(requests, '/complete').length, 2);
});

test('BL-199 报名单飞，UNKNOWN保留原请求，终态确认后才允许新意图', async () => {
  const { page, requests } = harness();
  page.data.cta = { type: 'signup' };
  page.onCta();
  page.onCta();
  await Promise.resolve(); await Promise.resolve();
  assert.equal(hits(requests, '/signup-command').length, 1);
  const first = hits(requests, '/signup-command')[0]; first.fail({});
  await page.onCta(); assert.equal(hits(requests, '/signup-command').length, 1);
  page.queryCommand(); const query = requests.at(-1); query.success(receipt(first, 'APPLIED'));
  await page.onCta(); assert.equal(hits(requests, '/signup-command').length, 1);
  page.acknowledgeCommand(); await page.onCta();
  assert.equal(hits(requests, '/signup-command').length, 2);
});

test('BL-199 V2 活动「完成任务」仍提示现场验证,不发请求也不占用在途标记', async () => {
  const { page, requests } = harness();
  page.data.e = { contractVersion: 2 };
  page.onCta();
  assert.equal(hits(requests, '/complete').length, 0);
  page.data.cta = { type: 'signup' };
  page.onCta();
  await Promise.resolve(); await Promise.resolve();
  assert.equal(hits(requests, '/signup-command').length, 1);
});
