const test = require('node:test');
const assert = require('node:assert/strict');
const sheet = require('../../pages/square/utils/community-report-sheet');
const { createClient } = require('../../utils/community-post-api');
const items = Array.from({ length: 13 }, (_, i) => '原因' + (i + 1));
function page() { return { data: {}, setData(patch) { Object.assign(this.data, patch); } }; }
test('abort only closes its own pending selection, never a later one', async () => {
  const p = page(), first = {}, second = {};
  const a = sheet.open(p, items, first); sheet.select(p, { detail: { index: 0 } }); assert.equal(await a, 0);
  const b = sheet.open(p, items, second); first.cancel(); assert.equal(p.data.reportReasonShow, true);
  second.cancel(); await assert.rejects(b, error => error.code === 499); assert.equal(p.data.reportReasonShow, false);
});
test('invalid selection is a failure, not cancellation', async () => {
  const p = page(); const selection = sheet.open(p, items, {}); sheet.select(p, { detail: { index: 13 } });
  await assert.rejects(selection, error => error.code === 500 && error.msg === '举报原因选择无效，请重试');
});
test('unloaded or aborted page cannot open a selection', async () => {
  const p = page(); p._unloaded = true;
  await assert.rejects(sheet.open(p, items, {}), error => error.code === 499); assert.equal(p._reportReasonSelection, undefined);
  await assert.rejects(sheet.open(page(), items, { aborted: true }), error => error.code === 499);
});
test('render failure returns an accurate error and never POSTs a report', async () => {
  const p = page(); p.setData = () => { throw new Error('render failed'); };
  const calls = [], tips = [];
  const app = { getUserID: () => 990005, tips: message => tips.push(message), sendRequest(o) {
    calls.push(o); queueMicrotask(() => o.success({ code: 200, data: { reporting: { policyVersion: 'server-v1', reasons: items.map((label, i) => ({ label, code: 'R' + i })) } } }));
  } };
  const client = createClient(app, { getStorageSync() {}, showActionSheet() { throw new Error('native sheet must never be used'); } });
  const result = await new Promise(resolve => client.request({ url: '/api/comment/report', data: { id: 3 },
    selectReportReason: (labels, task) => sheet.open(p, labels, task), success: resolve }));
  assert.equal(result.code, 500); assert.equal(result.msg, '举报原因选择失败，请重试');
  assert.deepEqual(tips, ['举报原因选择失败，请重试']); assert.equal(calls.length, 1); assert.equal(calls[0].method, 'GET');
  assert.equal(p._reportReasonSelection, null);
});
test('missing selector refuses with accurate failure instead of native fallback', async () => {
  const app = { getUserID: () => 990005, sendRequest(o) { queueMicrotask(() => o.success({ code: 200, data: { reporting: { policyVersion: 'server-v1', reasons: [{ label: '原因', code: 'R1' }] } } })); } };
  const result = await new Promise(resolve => createClient(app, { getStorageSync() {} }).request({ url: '/api/creativesquare/report', data: { id: 59 }, success: resolve }));
  assert.equal(result.code, 503); assert.equal(result.msg, '举报原因选择暂不可用，请重试');
});
