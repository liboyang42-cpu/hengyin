'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { createReplyRecovery } = require('../../pages/merchant/reviews/reply-recovery');
function harness(records = new Map(), reviewId = 71) {
  let definition, owner = '7', token = 'first';
  const requests = [], timers = [], copied = [];
  const app = { globalData: {}, getUserID: () => owner, getAuthorization: () => token,
    sendRequest: options => requests.push(options) };
  const storage = { setClipboardData: options => copied.push(options.data), getStorageSync: key => records.get(key), setStorageSync: (key, value) => records.set(key, JSON.parse(JSON.stringify(value))), removeStorageSync: key => records.delete(key) };
  const file = path.resolve(__dirname, '../../pages/merchant/reviews/index.js');
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Page: value => { definition = value; }, getApp: () => app, wx: storage,
    require(name) {
      if (name === './reply-recovery') return { createReplyRecovery: options => createReplyRecovery({ ...options, timer: fn => { timers.push(fn); return timers.length; }, cancel() {} }) };
      if (name.includes('merchant-theme')) return { merchantPageShow() {}, merchantPageRestore() {} };
      if (name.includes('toast')) return () => {};
      return require(path.resolve(path.dirname(file), name));
    } });
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)), setData(value) { Object.assign(this.data, value); } };
  page.data.mode = 'manage'; page.data.pageState = 'ready'; page._reviewOwner = owner;
  page.data.items = [{ id: reviewId, version: 2, canReply: true, canEditReply: true, merchantReply: 'old' }];
  page.syncReplyRecovery();
  return { page, requests, timers, records, storage, copied, owner: value => { owner = value; }, token: value => { token = value; },
    submit() { page.openReply({ currentTarget: { dataset: { id: reviewId } } }); page.inputReply({ detail: { value: 'private reply' } }); page.submitReply(); } };
}
test('timeout freezes editing and new commands; negative receipt retains original, positive receipt reloads', () => {
  const h = harness(); h.submit(); assert.equal(h.requests.length, 1);
  const command = JSON.parse(h.requests[0].data); h.timers[0]();
  h.page.inputReply({ detail: { value: 'changed' } }); h.page.submitReply(); h.page.cancelEditReply();
  assert.equal(h.page.data.replyContent, 'private reply'); assert.equal(h.requests.length, 1);
  assert.equal(JSON.stringify([...h.records.values()]).includes('private reply'), false);
  h.page.recoverReply(); const query = JSON.parse(h.requests[1].data);
  assert.equal(query.requestId, command.requestId); assert.equal(query.reviewId, '71');
  h.requests[1].success({ code: 200, data: { ...query, state: 'UNKNOWN' } });
  assert.equal(h.page.data.replyPending, true);
  h.page.recoverReply(); h.requests[2].success({ code: 200, data: { ...query, state: 'APPLIED' } });
  assert.equal(h.page.data.replyPending, false); assert.equal(h.records.size, 0);
  assert.match(h.requests[3].url, /reviews\/manage/);
});
test('reentry only queries persisted identity, never resubmits content', () => {
  const h = harness(); h.submit(); h.page.onUnload(); const next = harness(h.records);
  assert.equal(next.page.data.replyPending, true); next.page.submitReply(); assert.equal(next.requests.length, 0);
  next.page.recoverReply(); assert.equal(next.requests[0].url, '/api/merchant/reviews/reply/receipt');
  assert.equal(Object.hasOwn(JSON.parse(next.requests[0].data), 'content'), false);
});
for (const kind of ['owner', 'token']) test(`${kind} change rejects late reply and clears editor before new intent`, () => {
  const h = harness(); h.submit(); h[kind]('changed');
  h.requests[0].success({ code: 200, data: { reviewId: 71, status: 'VISIBLE', version: 3 } });
  assert.equal(h.records.size, 1); h.page.submitReply();
  assert.equal(h.page.data.replyContent, ''); assert.equal(h.requests.length, 1);
});
test('hide/show unblocks original receipt query and ignores hidden callback', () => {
  const h = harness(); h.submit(); h.page.onHide();
  h.requests[0].success({ code: 200, data: { reviewId: 71, status: 'VISIBLE', version: 3 } });
  h.page.onShow(); assert.equal(h.page.data.replySubmitting, false); assert.equal(h.page.data.replyPending, true);
  h.page.recoverReply(); assert.equal(h.requests[1].url, '/api/merchant/reviews/reply/receipt');
});
test('storage write failure sends no command', () => {
  const h = harness(); h.storage.setStorageSync = () => { throw Error('full'); }; h.submit();
  assert.equal(h.requests.length, 0); assert.equal(h.page.data.replyPending, true);
});
test('explicit command rejection unlocks draft, invalid receipt never unlocks', () => {
  const h = harness(); h.submit(); h.requests[0].success({ code: 409, msg: 'version conflict' });
  assert.equal(h.page.data.replyPending, false); assert.equal(h.page.data.replyContent, 'private reply');
  h.page.submitReply(); h.requests[1].fail(); h.page.recoverReply();
  h.requests[2].success({ code: 404 }); assert.equal(h.page.data.replyPending, true);
});

for (const id of ['9007199254740991', '9007199254740992', '9007199254740993', '9223372036854775807']) {
  test(`actual page preserves ${id} through list, reply and original receipt`, () => {
    const h = harness(new Map(), id);
    h.page.loadReviews(false);
    h.requests[0].success({ code: 200, data: { mode: 'manage', pageNum: 1, pageSize: 20, total: 1, hasMore: false,
      items: [{ id, version: 2, rating: 5, imageUrls: [], status: 'VISIBLE', verifiedRedemption: true, canReply: true, canReport: true }] } });
    assert.equal(h.page.data.items[0].id, id);
    h.submit(); const command = JSON.parse(h.requests[1].data);
    assert.equal(command.reviewId, id); h.requests[1].fail();
    h.page.copyReplyRequestId(); assert.deepEqual(h.copied, [command.requestId]);
    h.page.onUnload(); const next = harness(h.records, id); next.page.recoverReply();
    const query = JSON.parse(next.requests[0].data); assert.equal(query.reviewId, id); assert.equal(query.requestId, command.requestId);
    next.requests[0].success({ code: 200, data: { ...query, state: 'APPLIED' } });
    assert.equal(next.page.data.replyPending, false);
  });
}
test('copy after account change cannot disclose previous actor request', () => {
  const h = harness(); h.submit(); h.requests[0].fail(); h.owner('8'); h.page.copyReplyRequestId(); assert.deepEqual(h.copied, []);
});
test('unsafe numeric review ID cannot become a reply command', () => {
  const h = harness(new Map(), 9007199254740992); h.submit(); assert.equal(h.requests.length, 0);
});

for (const operation of ['edit', 'delete']) test(`Long max ${operation} finds original row and sends exact ID`, () => {
  const id = '9223372036854775807'; const h = harness(new Map(), id);
  const event = { currentTarget: { dataset: { id } } };
  h.page.selectComponent = () => ({ open() {}, busyOn() {}, close() {}, done() {} });
  if (operation === 'edit') { h.page.openEditReply(event); h.page.submitReply(); }
  else { h.page.askDeleteReply(event); assert.equal(h.requests.length, 0); h.page.onConfirmDeleteReply(); }
  assert.equal(JSON.parse(h.requests[0].data).reviewId, id);
  assert.equal(h.requests[0].url, operation === 'edit' ? '/api/merchant/reviews/reply/update' : '/api/merchant/reviews/reply/delete');
  h.requests[0].success({ code: 200, data: { reviewId: id, status: 'VISIBLE', version: 3 } });
  assert.equal(h.page.data.replyPending, false);
});
test('customer contact is a native contact control with no outcome promise', () => {
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/merchant/reviews/index.wxml'), 'utf8');
  assert.match(wxml, /<cy-cell[^>]*title="联系平台客服"[^>]*open-type="contact"/);
  assert.match(wxml, /当前结果仍待确认/);
  const config = require('../../pages/merchant/reviews/index.json');
  assert.equal(config.usingComponents['cy-cell'], '/components/cy/cell/index');
});
test('actual route carries merchant Long max to public list URL without coercion', () => {
  const h = harness(); h.storage.getWindowInfo = () => ({ statusBarHeight: 20 });
  h.page.onLoad({ merchantRowId: '9223372036854775807' });
  assert.equal(h.page._merchantRowId, '9223372036854775807');
  assert.match(h.requests[0].url, /merchantRowId=9223372036854775807&/);
});
