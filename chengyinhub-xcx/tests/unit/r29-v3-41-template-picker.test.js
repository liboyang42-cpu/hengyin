const { test } = require('node:test');
const assert = require('node:assert/strict');

function harness(storage = {}) {
  let config;
  const sent = [], notices = [];
  global.wx = {
    getStorageSync: key => storage[key], setStorageSync: (key, value) => { storage[key] = value; },
    removeStorageSync: key => { delete storage[key]; }, getSystemInfoSync: () => ({}),
    showToast: value => notices.push(value.title),
  };
  global.getApp = () => ({ globalData: {}, getUserID: () => '7', getAuthorization: () => 'actor-7',
    sendRequest: value => sent.push(value), getRequestErrorMessage: (value, fallback) => value.msg || fallback });
  global.Page = value => { config = value; };
  delete require.cache[require.resolve('../../pages/publish/fabu/index')];
  require('../../pages/publish/fabu/index');
  const page = { ...config, data: structuredClone(config.data) };
  page.setData = (patch, callback) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.');
      let target = page.data;
      for (const part of parts.slice(0, -1)) target = target[part];
      target[parts.at(-1)] = value;
    }
    if (callback) callback();
  };
  page._refreshSupportMatrix = () => {};
  page._openNodePlanEndings = () => {};
  Object.assign(page.data, { operationScope: '', draftMemberId: '7', draftUuid: 'topic-draft',
    editingTopicId: '501', editTargetChapterLid: 'chapter-a', editTargetNodeLid: 'node-a',
    nodeSheetView: 'games', popChapterNodes: true,
    nodesForm: { _localId: 'node-a', name: '作者的节点', templateId: 5 } });
  return { page, sent, storage, notices };
}
const own = { id: 31, title: '本人私有已通过模板', draftStatus: 1, status: 1, publishStatus: 0, advancedConfigJson: '{}' };
const publicRow = { id: 11, title: '公共模板', status: 1, advancedConfigJson: '{}' };
const tap = (page, item, source) => page.selectTemp({ currentTarget: { dataset: { item, source } } });
const version = 'a'.repeat(64), draftId = '9007199254740995';
function startAdoption(h) {
  tap(h.page, publicRow, 'public');
  h.page.confirmTemp();
  const source = h.sent.at(-1);
  assert.equal(source.url, '/api/template/adoption-source');
  source.success({ code: 200, data: { originalTemplateId: '11', sourceVersion: version } });
  assert.equal(h.sent.at(-1).url, '/api/template/adopt-draft');
  return h.sent.at(-1);
}
function receipt(h) {
  const record = Object.values(h.storage)[0];
  return { code: 200, data: { requestId: record.requestId, originalTemplateId: '11',
    sourceVersion: version, status: 'COMPLETED', draftId } };
}
function finishAdoption(h) {
  h.sent.at(-1).success(receipt(h));
  assert.equal(h.sent.at(-1).url, '/api/template/adoption-detail');
  h.sent.at(-1).success({ code: 200, data: { ...own, id: draftId, draftStatus: 0, status: 0 } });
}

test('V3-41 picker does not request or display draft rail; opening preserves adoption journal', () => {
  const h = harness({ retained: { requestId: 'existing' } });
  h.page.data.storyDraftList = [{ ...own, draftStatus: 0 }];
  h.page.showTemp();
  assert.deepEqual(h.page.data.storyDraftList, []);
  assert.equal(h.sent.length, 0);
  assert.deepEqual(h.storage.retained, { requestId: 'existing' });
});

test('V3-41 own list excludes draft/rejected/unknown states but allows approved private templates', () => {
  const h = harness();
  h.page.data.operationScope = 'MERCHANT';
  h.page.getTempList();
  const request = h.sent.at(-1);
  assert.equal(request.url, '/api/template/my-list');
  assert.equal(request.data.is_quote, 1);
  assert.equal(request.data.draft_status, 1);
  assert.equal(request.data.scope, 'MERCHANT');
  request.success({ code: 200, data: { rows: [own, { ...own, id: 32, draftStatus: 0 },
    { ...own, id: 33, status: 2 }, { ...own, id: 34, status: 0 }, { id: 35, title: '状态缺失' }] } });
  assert.deepEqual(h.page.data.tempList, [own]);
});

test('V3-41 public picker keeps public endpoint separate from owner/private listing', () => {
  const h = harness();
  h.page.getPublicTempList();
  const request = h.sent.at(-1);
  assert.equal(request.url, '/api/template/list');
  assert.equal(request.data.is_quote, undefined);
  request.success({ code: 200, data: { rows: [publicRow], total: 1 } });
  assert.deepEqual(h.page.data.publicTempList, [publicRow]);
  assert.deepEqual(h.page.data.tempList, []);
});

test('V3-41 confirmed own template binds original node and returns to its detail without saving chapters', () => {
  const h = harness(), node = h.page.data.nodesForm;
  const before = structuredClone(h.page.data.formData.chapters);
  tap(h.page, own, 'mine');
  assert.equal(node.templateId, 5);
  h.page.confirmTemp();
  assert.equal(h.page.data.nodesForm, node);
  assert.equal(node.templateId, own.id);
  assert.equal(node.name, '作者的节点');
  assert.equal(h.page.data.editTargetChapterLid, 'chapter-a');
  assert.equal(h.page.data.nodeSheetView, 'detail');
  assert.deepEqual(h.page.data.formData.chapters, before);
  assert.equal(h.sent.length, 0);
});

test('V3-41 cancelled selection and late own-detail callback cannot change original node', () => {
  const h = harness();
  tap(h.page, { id: 31, title: own.title, draftStatus: 1, status: 1 }, 'mine');
  h.page.confirmTemp();
  const request = h.sent.at(-1);
  assert.equal(request.url, '/api/template/author-myinfo');
  h.page.backToNodeDetail();
  request.success({ code: 200, data: own });
  assert.equal(h.page.data.nodesForm.templateId, 5);
  assert.equal(h.page.data.selectedTempInfo, null);
  assert.equal(h.sent.length, 1);
});

test('V3-41 malformed own detail does not recurse into unbounded detail reads', () => {
  const h = harness();
  tap(h.page, { id: 31, title: own.title }, 'mine');
  h.page.confirmTemp();
  h.sent.at(-1).success({ code: 200, data: { id: 31, title: own.title } });
  assert.equal(h.sent.length, 1);
  assert.equal(h.page.data.nodesForm.templateId, 5);
  assert.ok(h.notices.some(message => /详情未加载/.test(message)));
});

test('V3-41 draft or rejected stale own selection cannot bind through either existing handler', () => {
  for (const bad of [{ ...own, draftStatus: 0 }, { ...own, status: 2 }]) {
    const h = harness();
    tap(h.page, bad, 'mine');
    assert.equal(h.page.data.selectedTempInfo, null);
    Object.assign(h.page.data, { selectedTempInfo: bad, selectedTempSource: 'mine', selectedTempId: bad.id });
    h.page.confirmTemp();
    assert.equal(h.page.data.nodesForm.templateId, 5);
    assert.equal(h.sent.length, 0);
  }
});

test('V3-41 public confirmation adopts and reads exact draft receipt before binding the original node', () => {
  const h = harness(), node = h.page.data.nodesForm;
  startAdoption(h);
  assert.equal(node.templateId, 5);
  finishAdoption(h);
  assert.equal(h.page.data.nodesForm, node);
  assert.equal(node.templateId, draftId);
  assert.equal(h.page.data.editTargetChapterLid, 'chapter-a');
  assert.equal(h.page.data.nodeSheetView, 'detail');
  assert.equal(Object.keys(h.storage).length, 0);
});

test('V3-41 equal-valued replacement node cannot receive late public adoption at either callback stage', () => {
  for (const stage of ['source', 'adoption', 'detail']) {
    const h = harness();
    if (stage === 'source') { tap(h.page, publicRow, 'public'); h.page.confirmTemp(); }
    else startAdoption(h);
    if (stage === 'detail') h.sent.at(-1).success(receipt(h));
    const callback = h.sent.at(-1), count = h.sent.length;
    h.page.data.nodesForm = structuredClone(h.page.data.nodesForm);
    callback.success(stage === 'source' ? { code: 200, data: { originalTemplateId: '11', sourceVersion: version } }
      : stage === 'adoption' ? receipt(h) : { code: 200, data: { ...own, id: draftId } });
    assert.equal(h.page.data.nodesForm.templateId, 5);
    assert.equal(h.sent.length, count);
    if (stage !== 'source') assert.equal(Object.keys(h.storage).length, 1);
  }
});

test('V3-41 lost adoption ACK resumes original request; no draft-list dependency or duplicate command', () => {
  const first = harness();
  startAdoption(first).fail();
  const record = Object.values(first.storage)[0];
  const next = harness(first.storage);
  tap(next.page, publicRow, 'public');
  next.page.confirmTemp();
  assert.equal(next.sent.at(-1).url, '/api/template/adoption-receipt');
  assert.equal(next.sent.at(-1).data.requestId, record.requestId);
  finishAdoption(next);
  assert.equal(next.page.data.nodesForm.templateId, draftId);
  assert.equal(next.sent.some(request => request.url === '/api/template/adopt-draft'), false);
});

test('V3-41 support reason still blocks selection and confirmation', () => {
  const h = harness();
  h.page._templateSupportReason = () => '玩法暂不支持本站结算';
  tap(h.page, own, 'mine');
  assert.equal(h.page.data.selectedTempInfo, null);
  Object.assign(h.page.data, { selectedTempInfo: own, selectedTempSource: 'mine' });
  h.page.confirmTemp();
  assert.equal(h.page.data.nodesForm.templateId, 5);
  assert.equal(h.sent.length, 0);
});
