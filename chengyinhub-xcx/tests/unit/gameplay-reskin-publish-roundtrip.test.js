'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fixture = require('./fixtures/gameplay-reskin-roundtrip.json');
const supportMatrix = require('./fixtures/gameplay-support-matrix.json').data;

const backendCopies = [];
let pageConfig;
let requests = [];
let toasts = [];
let modals = [];
global.getApp = () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: request => requests.push(request), getAuthorization: () => 'test', getUserID: () => 7,
  getUserInfo: () => null, getNickname: () => '配置作者', getAvatar: () => '', getToken: () => '',
  getRequestErrorMessage: (error, fallback) => error && error.message || fallback, tips: () => {} });
global.wx = { getStorageSync: () => undefined, setStorageSync: () => {}, removeStorageSync: () => {},
  getSystemInfoSync: () => ({ windowWidth: 375 }), showToast: value => toasts.push(value), showLoading: () => {}, hideLoading: () => {},
  showModal: value => modals.push(value), navigateTo: () => {}, redirectTo: () => {}, pageScrollTo: () => {}, nextTick: fn => fn(),
  getBackgroundAudioManager: () => ({ stop: () => {} }) };
global.Page = config => { pageConfig = config; };

function makePage(path) {
  requests = [];
  toasts = [];
  modals = [];
  delete require.cache[require.resolve(path)];
  require(path);
  const page = Object.assign({}, pageConfig);
  page.data = JSON.parse(JSON.stringify(pageConfig.data));
  if (Object.hasOwn(page.data, 'supportMatrix')) page.data.supportMatrix = supportMatrix;
  page.setData = (patch, done) => {
    Object.entries(patch).forEach(([path, value]) => {
      const parts = path.replace(/\[(\d+)\]/g, '.$1').split('.'); let at = page.data;
      for (const part of parts.slice(0, -1)) at = at[part];
      at[parts.at(-1)] = value;
    });
    if (done) done();
  };
  return page;
}

function openTheme(skin) {
  const page = makePage('../../pages/publish/fabu/index.js');
  page.data.editingTopicId = skin.detail.topic.id;
  assert.equal(page.applyEditingTopic(skin.detail), true);
  requests.filter(request => request.url === '/api/template/author-myinfo').forEach(request => {
    const template = skin.templates.find(row => String(row.id) === String(request.data.id));
    assert.ok(template, request.data.id);
    request.success({ code: '200', data: template });
  });
  return page;
}

function themePayload(page) {
  let payload;
  page.sendData = data => { payload = data; };
  page._doSubmit();
  assert.ok(payload, '主题保存请求必须发出：' + JSON.stringify({ editLoaded: page.data.editLoaded,
    editingTopicId: page.data.editingTopicId, formData: { name: page.data.formData.name,
      chapters: page.data.formData.chapters.length, productType: page.data.formData.productType },
    errors: page.data.publishCheckIssues, toasts }));
  return payload;
}

function openTemplate(template) {
  const page = makePage('../../pages/publish/temp/index.js');
  page._templateId = template.id;
  page.data.isMyScope = true;
  page.fillFormWithTemplateData(template);
  page._refreshOutcomeContract = () => ({ items: [], errors: [] });
  page.refreshPreviewState = () => {};
  return page;
}

function changedPaths(before, after, prefix = '') {
  if (JSON.stringify(before) === JSON.stringify(after)) return [];
  if (before && after && typeof before === 'object' && typeof after === 'object'
      && !Array.isArray(before) && !Array.isArray(after)) {
    return [...new Set(Object.keys(before).concat(Object.keys(after)))].flatMap(key =>
      changedPaths(before[key], after[key], prefix ? prefix + '.' + key : key));
  }
  if (Array.isArray(before) && Array.isArray(after) && before.length === after.length) {
    return before.flatMap((value, index) => changedPaths(value, after[index], prefix + '[' + index + ']'));
  }
  return [prefix];
}

for (const skin of fixture.themes) {
  test(skin.name + '：主题与所有模板由服务端详情回填后无改动保存不丢通用配置', () => {
    const page = openTheme(skin);
    const saved = themePayload(page);
    assert.deepEqual(JSON.parse(saved.journeyRules), JSON.parse(skin.detail.journeyRules));
    assert.deepEqual(JSON.parse(saved.themeRuntimeConfig), JSON.parse(skin.detail.themeRuntimeConfig));
    for (const template of skin.templates) {
      const editor = openTemplate(template);
      const config = editor.prepareFormData().advancedConfigJson;
      assert.ok(config, template.title + '的高级玩法不能为空');
      assert.deepEqual(JSON.parse(config), JSON.parse(template.advancedConfigJson),
        template.title);
    }
  });

  test(skin.name + '：页面 handler 的主题换名和数值编辑只改变目标字段', () => {
    const page = openTheme(skin);
    const before = themePayload(page);
    page.onRuntimeName({ currentTarget: { dataset: { field: 'hpName' } }, detail: { value: skin.name + '新名称' } });
    page.onRuntimeActionLimit({ detail: { value: 9 } });
    page.onRuntimeItem({ currentTarget: { dataset: { index: 1, field: 'name' } }, detail: { value: '改名物资' } });
    page.openValueEditor({ currentTarget: { dataset: { index: 0 } } });
    page.onValueField({ currentTarget: { dataset: { field: 'label' } }, detail: { value: '新协作值' } });
    page.saveValueEditor();
    page.openInitialState();
    page.onInitialStateField({ currentTarget: { dataset: { field: 'hp.init' } }, detail: { value: '9' } });
    page.saveInitialState();
    const after = themePayload(page);
    assert.deepEqual(changedPaths(JSON.parse(before.themeRuntimeConfig), JSON.parse(after.themeRuntimeConfig)).sort(),
      ['hpName', 'actionLimit', 'items[1].name'].sort());
    assert.deepEqual(changedPaths(JSON.parse(before.journeyRules), JSON.parse(after.journeyRules)),
      ['hp.init', 'attributes[0].label']);
  });

  test(skin.name + '：模式切换复制重映射资源绑定并提示重选节点规则，可完整提交', () => {
    const page = openTheme(skin);
    const before = themePayload(page);
    const copied = page._buildCopiedDraft(2, false);
    const next = makePage('../../pages/publish/fabu/index.js');
    next.applyAiDraft(Object.assign({ from: 'ai' }, copied));
    assert.deepEqual(JSON.parse(next.data.formData.journeyRules), JSON.parse(before.journeyRules));
    const original = JSON.parse(before.themeRuntimeConfig);
    const runtime = JSON.parse(themePayload(next).themeRuntimeConfig);
    assert.deepEqual(runtime, JSON.parse(copied.formData.themeRuntimeConfig));
    const keyMap = new Map(next.data.formData.chapters.flatMap(ch => ch.nodes).map((node, index) => ['client:' + node.clientNodeKey, 'client:copy_' + index]));
    const serverRuntime = structuredClone(runtime);
    serverRuntime.templateBindings = Object.fromEntries(Object.entries(serverRuntime.templateBindings).map(([key, value]) => [keyMap.get(key), value]));
    for (const scene of serverRuntime.scenes || []) if (scene.nodeConfigs) scene.nodeConfigs = Object.fromEntries(Object.entries(scene.nodeConfigs).map(([key, value]) => [keyMap.get(key), value]));
    for (const ch of serverRuntime.chapters || []) {
      if (ch.exclusiveGroups) ch.exclusiveGroups = ch.exclusiveGroups.map(group => group.map(ref => keyMap.get(ref)));
      if (ch.extraNodes) ch.extraNodes = ch.extraNodes.map(item => Object.assign({}, item, { nodeId: keyMap.get(item.nodeId) }));
    }
    backendCopies.push({ name: skin.name, runtime: serverRuntime, journeyRules: JSON.parse(before.journeyRules),
      chapters: next.data.formData.chapters.map(ch => ({ name: ch.name, nodes: ch.nodes.map(node => ({ name: node.name, templateId: node.templateId, clientNodeKey: keyMap.get('client:' + node.clientNodeKey).slice(7) })) })), templates: skin.templates });
    assert.deepEqual(runtime.gameplayRuntime.acts, original.gameplayRuntime.acts.map(act => ({ ...act, questKeys: [] })));
    assert.deepEqual(runtime.gameplayRuntime.quests, []);
    assert.deepEqual(runtime.chapters.map(ch => ch.completion), original.chapters.map(ch => ch.completion));
    // 2026-10-12:互斥组与额外节点(共用额度关系)按位置换成副本节点的 client 引用保留,不再整段丢弃
    const idToClient = new Map(page.data.formData.chapters.flatMap(ch => ch.nodes).map((node, index) =>
      [String(node.id), 'client:' + next.data.formData.chapters.flatMap(ch => ch.nodes)[index].clientNodeKey]));
    assert.deepEqual(runtime.chapters.map(ch => [ch.exclusiveGroups || null, ch.extraNodes || null]),
      original.chapters.map(ch => [ch.exclusiveGroups ? ch.exclusiveGroups.map(group => group.map(id => idToClient.get(String(id)))) : null,
        ch.extraNodes ? ch.extraNodes.map(item => Object.assign({}, item, { nodeId: idToClient.get(String(item.nodeId)) })) : null]));
    const survival = Object.assign({}, original.gameplayRuntime.survival);
    delete survival.zeroHpPolicy;
    delete survival.zeroChallenge;
    assert.deepEqual(runtime.gameplayRuntime.survival, survival);
    const sourceNodes = page.data.formData.chapters.flatMap(ch => ch.nodes);
    const copiedNodes = next.data.formData.chapters.flatMap(ch => ch.nodes);
    assert.ok(copiedNodes.every(node => !node.id && node.clientNodeKey));
    for (const [id, binding] of Object.entries(original.templateBindings)) {
      const sourceNode = sourceNodes.find(node => String(node.id) === id);
      const target = copiedNodes.find(node => node._localId === sourceNode._localId);
      assert.deepEqual(runtime.templateBindings['client:' + target.clientNodeKey], binding);
    }
    assert.ok(Object.keys(runtime.templateBindings).every(key => key.startsWith('client:')));
    assert.equal(modals.length, 1);
    assert.equal(modals[0].content, '复制后需要重新选择：通用玩法结构需在副本中重新配置（到达地点、工作人员引导和备用节点）、归零挑战需重新选择挑战节点');
    assert.deepEqual(JSON.parse(page._runtimePayload()), original, '源主题编辑配置保持原值');
    assert.equal(next.data.formData.journeyStory, copied.formData.journeyStory);
    assert.equal(next.data.formData.completeRuleJson, copied.formData.completeRuleJson);
  });

  test(skin.name + '：多节点玩法另存副本带完整高级配置并只重绑本站', () => {
    const page = openTheme(skin);
    const first = page.data.formData.chapters.flatMap(chapter => chapter.nodes).find(node => node.templateId === 501);
    const original = skin.templates.find(template => template.id === 501);
    page.data.nodesForm = Object.assign({}, first, { templateInfo: original });
    page.getStoryDraftList = () => {};
    page._openNodeBinding(page.data.nodesForm);
    assert.ok(page.data.nodeSharedSites.length > 1);
    page.onCopyBoundTemplate();
    const detail = requests.at(-1);
    assert.equal(detail.url, '/api/template/author-myinfo');
    detail.success({ code: '200', data: original });
    const save = requests.find(request => request.url === '/api/template/draft');
    assert.ok(save, JSON.stringify({ error: page.data.nodeCopyError, busy: page.data.nodeCopyBusy,
      nodeId: page.data.nodesForm.templateId, requests: requests.map(request => request.url) }));
    const payload = JSON.parse(save.data);
    assert.equal(Object.hasOwn(payload, 'id'), false);
    for (const field of ['advancedConfigJson', 'rewardRulesJson', 'merchantGuide', 'sensorType', 'sensorConfig', 'storyImg'])
      if (original[field] != null) assert.equal(payload[field], original[field]);
    save.success({ code: '200', data: 801 });
    assert.equal(page.data.nodesForm.templateId, 801);
    assert.deepEqual(JSON.parse(page.data.nodesForm.templateInfo.advancedConfigJson), JSON.parse(original.advancedConfigJson));
  });

  test(skin.name + '：旧扫码投票未选主玩法前保留原配置', () => {
    const template = skin.templates.find(row => JSON.parse(row.advancedConfigJson).interactionTools?.plans?.length);
    const editor = openTemplate(template);
    const before = JSON.parse(editor.prepareFormData().advancedConfigJson);
    editor._supportContext = { runtime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED' } };
    editor.openToolSheet({ currentTarget: { dataset: { key: 'vote' } } });
    assert.equal(editor.data.toolSheetOpen, false);
    assert.equal(editor.data.gameKey, '');
    const after = JSON.parse(editor.prepareFormData().advancedConfigJson);
    assert.deepEqual(after, before);
  });

  test(skin.name + '：旧费用只读且打开保存不丢失', () => {
    const template = skin.templates.find(row => JSON.parse(row.advancedConfigJson).interactionRules?.costOptions);
    const editor = openTemplate(template);
    const before = JSON.parse(editor.prepareFormData().advancedConfigJson);
    editor.openToolSheet({ currentTarget: { dataset: { key: 'cost' } } });
    assert.equal(editor.data.toolSheetOpen, false);
    const after = JSON.parse(editor.prepareFormData().advancedConfigJson);
    assert.deepEqual(after, before);
  });
}

test('三主题后端首次保存夹具由实际页面复制 handlers 生成', () => {
  const path = require('node:path').join(__dirname, 'fixtures/gameplay-theme-copy.json');
  if (process.env.GG_COPY_FIXTURE === '1') require('node:fs').writeFileSync(path, JSON.stringify(backendCopies, null, 2) + '\n');
  assert.deepEqual(backendCopies, JSON.parse(require('node:fs').readFileSync(path, 'utf8')));
});
