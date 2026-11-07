const test = require('node:test');
const assert = require('node:assert/strict');

test('站点选玩法：不请求或展示草稿，只保留最新返回的已发布且审核通过模板', () => {
  const sent = [];
  global.wx = { getStorageSync() { return ''; }, setStorageSync() {}, showToast() {}, getSystemInfoSync() { return {}; }, getWindowInfo() { return {}; } };
  global.getApp = () => ({ globalData: {}, getUserID: () => 42, sendRequest: opts => sent.push(opts) });
  let page; global.Page = value => { page = value; };
  delete require.cache[require.resolve('../../pages/publish/fabu/index')];
  require('../../pages/publish/fabu/index');
  page.data = { operationScope: '', storyDraftList: [{ id: 1, draftStatus: 0 }] }; page.setData = patch => Object.assign(page.data, patch); page._refreshSupportMatrix = () => {};
  page.getStoryDraftList();
  assert.equal(sent.length, 0, '不再请求草稿列表或其后续分页');
  assert.deepEqual(page.data.storyDraftList, [], '旧缓存草稿也不得显示');
  const draftToken = page._storyDraftListToken;
  page.getStoryDraftList();
  assert.equal(sent.length, 0);
  assert.deepEqual(page.data.storyDraftList, []);
  assert.ok(page._storyDraftListToken > draftToken, '再次打开仍失效旧草稿读取');

  page.getTempList();
  assert.equal(sent.length, 1);
  assert.equal(sent[0].data.is_quote, 1); assert.equal(sent[0].data.draft_status, 1);
  const published = { id: 141, title: '已发布玩法', draftStatus: 1, status: 1, publishStatus: 0 };
  sent[0].success({ code: 200, data: { rows: [published,
    { ...published, id: 142, draftStatus: 0 }, { ...published, id: 143, status: 0 },
    { id: 144, title: '缺少状态' }], total: 4 } });
  assert.deepEqual(page.data.tempList, [published], '本人私有的已发布且通过模板可用，草稿、未审核和状态缺失均不可用');
  assert.deepEqual(page.data.storyDraftList, []);
  page.getTempList(); const stale = sent.at(-1);
  page.getTempList(); const fresh = sent.at(-1);
  fresh.success({ code: 200, data: { rows: [{ ...published, id: 2, title: 'new' }], total: 1 } });
  stale.success({ code: 200, data: { rows: [{ ...published, id: 1, title: 'old' }], total: 1 } });
  assert.deepEqual(page.data.tempList.map(r => r.id), [2], '旧请求回包不覆盖最新模板');
});
