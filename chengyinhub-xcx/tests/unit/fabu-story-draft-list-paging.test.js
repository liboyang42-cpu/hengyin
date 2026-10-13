const test = require('node:test');
const assert = require('node:assert/strict');

test('站点选玩法：草稿列表分页读取全部，第 11 个起的草稿也能选', () => {
  const sent = [];
  global.wx = { getStorageSync() { return ''; }, setStorageSync() {}, showToast() {}, getSystemInfoSync() { return {}; }, getWindowInfo() { return {}; } };
  global.getApp = () => ({ globalData: {}, sendRequest: opts => sent.push(opts) });
  let page; global.Page = value => { page = value; };
  delete require.cache[require.resolve('../../pages/publish/fabu/index')];
  require('../../pages/publish/fabu/index');
  page.data = { operationScope: '' }; page.setData = patch => Object.assign(page.data, patch); page._refreshSupportMatrix = () => {};
  page.getStoryDraftList();
  assert.equal(sent.length, 1);
  assert.equal(sent[0].data.pageNum, 1); assert.equal(sent[0].data.pageSize, 100); assert.equal(sent[0].data.draft_status, 0);
  const first = Array.from({ length: 100 }, (_, i) => ({ id: 1000 - i, title: 't' + i }));
  sent[0].success({ code: 200, data: { rows: first, total: 119 } });
  assert.equal(sent.length, 2, '还有剩余时继续读下一页'); assert.equal(sent[1].data.pageNum, 2);
  sent[1].success({ code: 200, data: { rows: Array.from({ length: 19 }, (_, i) => ({ id: 900 - i, title: 'u' + i })), total: 119 } });
  assert.equal(page.data.storyDraftList.length, 119);
  // 只有一页时直接结束；过期回包不覆盖
  page.getStoryDraftList(); const stale = sent.at(-1);
  page.getStoryDraftList(); const fresh = sent.at(-1);
  stale.success({ code: 200, data: { rows: [{ id: 1, title: 'old' }], total: 1 } });
  assert.equal(page.data.storyDraftList.length, 119, '旧请求回包不覆盖');
  fresh.success({ code: 200, data: { rows: [{ id: 2, title: 'new' }], total: 1 } });
  assert.deepEqual(page.data.storyDraftList.map(r => r.id), [2]);
});
