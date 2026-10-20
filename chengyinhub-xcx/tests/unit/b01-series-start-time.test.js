'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

function load() {
  let definition;
  const requests = [], toasts = [];
  global.getApp = () => ({ sendRequest: options => requests.push(options) });
  global.Page = config => { definition = config; };
  global.wx = { showToast: options => toasts.push(options.title) };
  const filename = path.resolve(__dirname, '../../pages/club/event-ops/index.js');
  delete require.cache[filename];
  require(filename);
  const page = Object.assign({}, definition);
  page.data = JSON.parse(JSON.stringify(definition.data));
  page.setData = patch => Object.assign(page.data, patch);
  Object.assign(page.data, { canManage: true, clubId: 21, minDate: '2099-01-01' });
  return { page, requests, toasts };
}
function detail(h, overrides) {
  h.page.beginEditSeries({ currentTarget: { dataset: { seriesId: 61 } } });
  const request = h.requests.at(-1);
  assert.equal(request.url, '/api/club/event-ops/series/detail');
  assert.equal(request.method, 'POST');
  assert.deepEqual(JSON.parse(request.data), { clubId: 21, seriesId: 61 });
  request.success({ code: 200, data: Object.assign({ id: 61, clubId: 21, topicId: 31,
    timezone: 'Asia/Shanghai', startTime: '00:30', futureDates: ['2099-01-17'], version: 0,
    defaultLeadMemberId: 41, defaultCapacity: null, offerMinutes: 1440,
    recurrenceType: 'CUSTOM_DATES', waitlistEnabled: true }, overrides) });
  request.complete();
}
for (const startTime of ['00:30', '18:45']) test('detail and date reschedule preserve ' + startTime, () => {
  const h = load();
  detail(h, { startTime });
  assert.equal(h.page.data.startTime, startTime);
  h.page.onCustomDateChange({ currentTarget: { dataset: { index: 0 } }, detail: { value: '2099-01-18' } });
  h.page.submitSeries();
  const write = h.requests.at(-1);
  assert.equal(write.url, '/api/club/event-ops/series/update-future');
  assert.equal(write.method, 'POST');
  assert.deepEqual(Object.assign({}, JSON.parse(write.data), { startTime: null }), {
    clubId: 21, topicId: 31, recurrenceType: 'CUSTOM_DATES', startDate: '2099-01-17',
    startTime: null, occurrenceCount: 1, customDates: ['2099-01-18'], capacity: null,
    defaultLeadMemberId: 41, waitlistEnabled: true, offerMinutes: 1440, seriesId: 61, expectedVersion: 0,
  });
  assert.equal(JSON.parse(write.data).startTime, startTime);
});
for (const [reason, startTime] of [['mixed future times', null], ['no future occurrences', null],
  ['omitted field', undefined], ['invalid clock', '24:00'], ['un-padded clock', '9:00']]) {
  test(reason + ' clears previous edit time and refuses a silent write', () => {
    const h = load();
    detail(h, { startTime: '18:45' });
    detail(h, { startTime, futureDates: reason === 'no future occurrences' ? [] : ['2099-01-17'] });
    h.page.submitSeries();
    assert.equal(h.requests.filter(r => /\/(update-future|create)$/.test(r.url)).length, 0);
    assert.equal(h.page.data.startTime, '');
    assert.ok(h.toasts.includes('请选择集合时刻'));
  });
}

test('unknown time can be saved after an explicit time selection', () => {
  const h = load();
  detail(h, { startTime: null });
  h.page.onStartTimeChange({ detail: { value: '16:45' } });
  h.page.submitSeries();
  assert.equal(h.requests.at(-1).url, '/api/club/event-ops/series/update-future');
  assert.equal(JSON.parse(h.requests.at(-1).data).startTime, '16:45');
});
