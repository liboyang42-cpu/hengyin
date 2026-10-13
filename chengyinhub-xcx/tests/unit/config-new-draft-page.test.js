'use strict';
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const localDraft = require('../../utils/publish/pro-editor-draft.js');
let pageConfig, requests, storage, member, written, dialogs;
const app = { globalData: {}, getUserID: () => member, getRequestErrorMessage: (error, fallback) => error && error.message || fallback,
  getUserInfo: () => null, sendRequest: options => { requests.push(options); if (/\/(create|update)$/.test(options.url)) written = JSON.parse(options.data); }, tips: () => {} };
global.getApp = () => app;
global.wx = { getStorageSync: key => storage[key], setStorageSync: (key, value) => { storage[key] = JSON.parse(JSON.stringify(value)); },
  showModal: options => { dialogs.push(options); },
  removeStorageSync: key => { delete storage[key]; }, showToast: () => {}, getSystemInfoSync: () => ({ windowWidth: 375 }), nextTick: fn => fn(),
  createMapContext: () => ({}), pageScrollTo: () => {} };
global.Page = config => { pageConfig = config; };
beforeEach(() => {
  requests = []; storage = {}; member = 42; written = null; dialogs = [];
  for (const file of ['../../pages/publish/fabu/index.js', '../../pages/publish/fabu/config-selection.js']) delete require.cache[require.resolve(file)];
  require('../../pages/publish/fabu/index.js');
});
function page() {
  const instance = Object.assign({}, pageConfig); instance.data = JSON.parse(JSON.stringify(pageConfig.data));
  instance.setData = function (patch, callback) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let target = instance.data;
      for (const part of parts.slice(0, -1)) { if (!target[part]) target[part] = {}; target = target[part]; }
      target[parts[parts.length - 1]] = value;
    }
    if (callback) callback();
  };
  instance.data.configSelectionEligible = true; instance.data.draftUuid = 'new-fixture'; instance.data.draftMemberId = 42;
  instance.data.formData.productType = 2; instance.data.formData.chapters = []; instance.data.formData.tickets = [];
  instance.updateAllStatistics = () => {}; instance.refreshRouteGraph = () => {}; instance.destroyPreviewAudio = () => {};
  return instance;
}
function selectStyle(instance) {
  instance.openConfigPicker({ currentTarget: { dataset: { kind: 'style' } } });
  const request = requests.pop(); assert.equal(request.url, '/api/preset/style/list');
  request.success({ code: 200, data: [{ id: 2, name: '纸色', status: '1', defaultTitle: '新风格草稿', defaultDesc: '介绍', toneTokens: '{"--cy-color-text-primary":"#112233"}' }] });
  instance.selectDraftConfig({ currentTarget: { dataset: { id: 2 } } });
  assert.equal(instance.data.configSourceUsed, true);
}
function readback(instance, id = 900) {
  const runtime = written.themeRuntimeConfig ? JSON.parse(written.themeRuntimeConfig) : null;
  if (runtime && runtime.configSource && runtime.configSource.route) runtime.configSource.templateCopies = { 77: 500 };
  const chapters = (written.chapters || []).map((chapter, i) => ({ ...chapter, id: 1000 + i, cmsTopicNodeList: (chapter.nodes || []).map((node, j) => {
    const saved = { ...node, id: 2000 + i * 100 + j, chapterId: 1000 + i, templateId: node.sourceLibraryTemplateId ? 500 : node.templateId };
    delete saved.sourceLibraryTemplateId; delete saved.clientNodeKey; return saved;
  }) }));
  return { isDraft: true, editScope: 'FULL', topic: { id, memberId: 42, name: written.name, subtitle: written.subtitle, description: written.description, imgUrl: written.imgUrl, imgArr: written.imgArr, categoryIds: written.categoryIds,
    productType: written.productType, configVersion: 1, publishMode: 'pro', updateTime: '2026-10-13 12:00:00', themeRuntimeConfigJson: JSON.stringify(runtime) },
    themeRuntimeConfig: JSON.stringify(runtime), chapters, tickets: [], collaboratorIds: [] };
}
test('actual page saves pending marker before request, reads back, migrates local identity and reopens without fetching live config', () => {
  const instance = page(); selectStyle(instance);
  instance.saveConfigDraft();
  const request = requests.shift(); assert.equal(request.url, '/api/topic/create');
  const pending = localDraft.loadDraft(wx, { draftUuid: 'new-fixture' }, { memberId: 42 });
  assert.equal(pending.envelope.editorMeta.configDraftUnknown, true, 'must be durable before request/response');
  const restarted = page(); restarted._applyDraftEnvelope(pending.envelope); restarted.saveConfigDraft();
  assert.equal(requests.length, 0, 'process restart must not repeat unknown create');
  request.success({ code: 200, data: { topicId: 900, reviewState: 'DRAFT', published: false } });
  assert.equal(instance.data.editingTopicId, '900'); assert.equal(requests[0].url, '/api/topic/edit-detail');
  requests.shift().success({ code: 200, data: readback(instance) });
  assert.equal(instance.data.configDraftSaving, false); assert.equal(instance.data.isDraft, true);
  assert.equal(instance.data.configDraftError, ''); assert.equal(instance.data.formData.configVersion, '1');
  assert.equal(localDraft.loadDraft(wx, { draftUuid: 'new-fixture' }, { memberId: 42 }).status, 'missing');
  const known = localDraft.loadDraft(wx, { topicId: 900 }, { memberId: 42 }); assert.equal(known.status, 'ready');
  assert.equal(known.envelope.editorMeta.configDraftUnknown, false);
  const reopened = page(); reopened.data.editingTopicId = '900'; reopened.applyEditingTopic(readback(instance));
  assert.equal(reopened.data.formData.themeRuntimeConfig, instance.data.formData.themeRuntimeConfig);
  assert.equal(requests.filter(req => req.url.includes('/preset/') || req.url.includes('/world/')).length, 0);
});
test('actual page keeps edits made during save and failed readback never becomes success', () => {
  const instance = page(); selectStyle(instance); instance.saveConfigDraft();
  requests.shift().success({ code: 200, data: 900 });
  const detail = readback(instance); instance.data.formData.name = '保存中继续编辑';
  requests.shift().success({ code: 200, data: detail });
  assert.equal(instance.data.formData.name, '保存中继续编辑'); assert.equal(instance.data.configDraftUnknown, true);
  assert.match(instance.data.configDraftError, /本机内容已保留/);
  assert.equal(localDraft.loadDraft(wx, { topicId: 900 }, { memberId: 42 }).envelope.formData.name, '保存中继续编辑');
});
test('existing drafts and sold contents do not open selection; stale account and wrong detail ID do not apply', () => {
  const instance = page(); instance.data.editingTopicId = '900'; instance.openConfigPicker({ currentTarget: { dataset: { kind: 'style' } } });
  assert.equal(requests.length, 0); instance.data.editingTopicId = '';
  instance.openConfigPicker({ currentTarget: { dataset: { kind: 'style' } } });
  member = 99; requests.shift().success({ code: 200, data: [{ id: 2, status: '1' }] });
  assert.equal(instance.data.configPicker.rows.length, 0);
  member = 42; instance.openConfigPicker({ currentTarget: { dataset: { kind: 'route' } } });
  requests.shift().success({ code: 200, data: [{ id: 7, name: '路线' }] });
  instance.selectDraftConfig({ currentTarget: { dataset: { id: 7 } } });
  requests.shift().success({ code: 200, data: { id: 8, nodes: [] } });
  assert.equal(instance.data.configSourceUsed, false); assert.match(instance.data.configPicker.error, /编号/);
});
test('known ID lost response reconciles by original readback and reused workflow rejects switched account', () => {
  const instance = page(); selectStyle(instance); instance.saveConfigDraft();
  requests.shift().success({ code: 200, data: 900 }); requests.shift().success({ code: 200, data: readback(instance) });
  instance.saveConfigDraft(); const update = requests.shift(); assert.equal(update.url, '/api/topic/update');
  update.fail(); assert.equal(instance._configDraftWorkflow.getState(), 'readbackFailed');
  instance.saveConfigDraft(); assert.equal(requests[0].url, '/api/topic/edit-detail');
  const detail = readback(instance); detail.topic.configVersion = 2; requests.shift().success({ code: 200, data: detail });
  member = 99; instance.saveConfigDraft(); assert.equal(requests.length, 0); assert.match(instance.data.configDraftError, /账号已变动/);
});
test('configuration publication saves and verifies first, then uses existing same-ID publish-draft; preview image failure falls back', () => {
  const instance = page(); selectStyle(instance);
  instance._doSubmit(); assert.equal(requests[0].url, '/api/topic/create');
  requests.shift().success({ code: 200, data: 900 }); requests.shift().success({ code: 200, data: readback(instance) });
  const publish = requests.shift(); assert.equal(publish.url, '/api/topic/publish-draft'); assert.equal(publish.data.id, 900);
  assert.equal(requests.length, 0); assert.equal(instance._buildPublishPreview().style, '--cy-color-text-primary:#112233;--cy-text-title:#112233');
  instance.data.publishCheck.preview = { cover: 'https://example.test/missing.png' }; instance.onConfigPreviewCoverError();
  assert.equal(instance.data.publishCheck.preview.cover, '');
});
test('unload ignores outstanding source and save callbacks', () => {
  const instance = page(); selectStyle(instance); instance.saveConfigDraft(); const request = requests.shift();
  instance.onUnload(); request.success({ code: 200, data: 900 });
  assert.equal(instance.data.editingTopicId, ''); assert.equal(requests.length, 0);
});

test('actual route import keeps library/private IDs distinct, uses V2, and reopens independent frozen source', () => {
  const instance = page();
  instance.openConfigPicker({ currentTarget: { dataset: { kind: 'route' } } });
  requests.shift().success({ code: 200, data: [{ id: 7, name: '路线' }] });
  instance.selectDraftConfig({ currentTarget: { dataset: { id: 7 } } });
  const source = { id: 7, name: '路线', status: '1', nodes: [
    { id: 10, name: '甲', address: '甲入口', longitude: '121.1', latitude: '31.1', nodeTime: 5, playTemplateId: 77 },
    { id: 11, name: '乙', address: '乙入口', longitude: '121.2', latitude: '31.2', nodeTime: 6, playTemplateId: 77 }] };
  requests.shift().success({ code: 200, data: source });
  instance.saveConfigDraft(); const save = requests.shift();
  assert.equal(save.url, '/api/topic/v2/create');
  const payload = JSON.parse(save.data);
  assert.equal(payload.chapters[0].nodes[0].id, undefined);
  assert.equal(payload.chapters[0].nodes[0].sourceLibraryTemplateId, 77);
  assert.equal(payload.chapters[0].nodes[0].templateId, 0);
  save.success({ code: 200, data: { topicId: 900, reviewState: 'DRAFT' } });
  requests.shift().success({ code: 200, data: readback(instance) });
  assert.equal(instance.data.configDraftError, '');
  assert.equal(instance.data.formData.chapters[0].nodes[0].templateId, 500);
  assert.equal(instance.data.formData.chapters[0].nodes[0].sourceLibraryTemplateId, undefined);
  source.nodes[0].name = '后台新版本';
  assert.equal(JSON.parse(instance.data.formData.themeRuntimeConfig).configSource.route.nodes[0].name, '甲');
  instance.saveConfigDraft(); assert.equal(requests.shift().url, '/api/topic/v2/update');
});
test('local drafts from before this policy retain their version and cannot open source selection', () => {
  const instance = page(); instance._applyDraftEnvelope({ formData: instance.data.formData, pendingMaterials: [], editorMeta: {} });
  assert.equal(instance.data.configSelectionEligible, false);
  instance.openConfigPicker({ currentTarget: { dataset: { kind: 'style' } } });
  assert.equal(requests.length, 0);
});

test('physical back closes config picker and invalidates its request while preserving underlying editor sheet and draft', () => {
  const instance = page(); instance.data.modePickerShow = true;
  instance.openConfigPicker({ currentTarget: { dataset: { kind: 'style' } } });
  const request = requests.shift(), original = JSON.stringify(instance.data.formData);
  instance.onNativeBackGuard();
  assert.equal(instance.data.configPicker.show, false); assert.equal(instance.data.modePickerShow, true);
  assert.equal(instance.data.backGuardOff, true);
  request.success({ code: 200, data: [{ id: 2, status: '1' }] });
  assert.equal(instance.data.configPicker.rows.length, 0);
  assert.equal(JSON.stringify(instance.data.formData), original);
});

test('new known-ID readback failure exposes read-only retry and restart offers saved version without overwriting local edits', () => {
  const instance = page(); selectStyle(instance); instance.saveConfigDraft();
  requests.shift().success({ code: 200, data: 900 }); requests.shift().fail();
  assert.equal(instance.data.isDraft, false); assert.equal(instance.data.configDraftNeedsReadback, true);
  instance.saveConfigDraft(); assert.equal(requests[0].url, '/api/topic/edit-detail');
  const pending = localDraft.loadDraft(wx, { topicId: 900 }, { memberId: 42 }).envelope;
  const restarted = page(); restarted.data.editingTopicId = '900'; restarted.applyEditingTopic(readback(instance));
  assert.equal(dialogs.length, 1); assert.equal(dialogs[0].title, '原草稿需要核对');
  assert.deepEqual(localDraft.loadDraft(wx, { topicId: 900 }, { memberId: 42 }).envelope, pending);
  dialogs[0].success({ confirm: false });
  assert.equal(restarted.data.configDraftUnknown, false); assert.equal(restarted.data.isDraft, true);
  assert.equal(localDraft.loadDraft(wx, { topicId: 900 }, { memberId: 42 }).status, 'missing');
});

test('actual configuration publication refuses lost ticket or collaborator readback and preserves all local data', () => {
  for (const missing of ['ticket', 'collaborator']) {
    requests = [];
    const instance = page(); selectStyle(instance);
    instance.data.formData.tickets = [{ name: '票', price: 9, totalStock: 10, startTime: '2026-10-13', endTime: '2026-10-15', syncWithTheme: false }];
    instance.data.formData.collaboratorIds = [42, 43];
    const original = JSON.stringify(instance.data.formData);
    instance._doSubmit(); requests.shift().success({ code: 200, data: 900 });
    const detail = readback(instance);
    detail.tickets = [{ ...written.tickets[0], id: 700, ownerId: 900, ownerType: 1, totalInventory: 10 }];
    detail.collaboratorIds = written.collaboratorIds.slice();
    if (missing === 'ticket') detail.tickets = []; else detail.collaboratorIds = [42];
    requests.shift().success({ code: 200, data: detail });
    assert.equal(requests.length, 0, 'lost content must never trigger publish-draft');
    assert.equal(instance._configDraftWorkflow.getState(), 'readbackFailed');
    assert.equal(JSON.stringify(instance.data.formData), original);
    assert.match(instance.data.configDraftError, /票种|协作者/);
  }
});
