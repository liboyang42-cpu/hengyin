const { test } = require('node:test');
const assert = require('node:assert/strict');
const fixture = require('./fixtures/gameplay-structure-form.js');
const structure = require('../../pages/publish/fabu/theme-runtime-structure.js');

const choose = (page, key) => page.chooseStructureMode({ currentTarget: { dataset: { key } } });
const arrival = page => fixture.open(page, 'quests');

test('到达配置缺少进行方式时复用现有选择层，取消不写入或自动指定模式', () => {
  const page = fixture.page();
  page.data.nodesForm = page.data.formData.chapters[0].nodes[1];
  arrival(page);
  assert.equal(page.data.structureEditor.kind, 'mode');
  assert.equal(page.data.structureModeOptions.filter(row => row.selected).length, 0);
  assert.equal(page.data.formData.themeRuntimeConfig, '');
  page.saveStructure();
  assert.match(page.data.structureError, /请选择/);
  assert.equal(page.data.structureEditor.kind, 'mode');
  choose(page, 'TEAM_INDEPENDENT');
  page.closeStructure();
  assert.equal(page.data.formData.themeRuntimeConfig, '');
  fixture.open(page, 'mode');
  choose(page, 'PLAYER');
  page.saveStructure();
  assert.equal(page.data.structureEditor.show, false);
});

for (const mode of structure.MODES) {
  test(`明确选择 ${mode.key} 后返回原节点，保存并序列化回填到达配置`, () => {
    const page = fixture.page();
    page.data.nodesForm = page.data.formData.chapters[0].nodes[1];
    arrival(page);
    assert.equal(page.data.structureEditor.kind, 'mode');
    choose(page, mode.key);
    page.saveStructure();
    assert.equal(page.data.structureEditor.kind, 'quests');
    assert.equal(page.data.structureEditor.show, true);
    assert.equal(page.data.structureEditor.quest.nodeId, 202);
    page.onStructureLocation({ detail: { value: 0 } });
    if (mode.runtimeScope === 'TEAM') page.onStructureGuide({ detail: { value: true } });
    page.toggleStructureFallback({ currentTarget: { dataset: { id: 201 } } });
    page.saveStructure();
    assert.equal(page.data.structureError, '');
    const payload = page._runtimePayload();
    const game = JSON.parse(payload).gameplayRuntime;
    assert.equal(game.runtimeScope, mode.runtimeScope);
    assert.equal(game.worldMode, mode.worldMode);
    assert.equal(structure.validate(JSON.parse(payload), page.data.formData.chapters), null);
    const saved = game.quests.find(row => row.nodeId === 202);
    assert.equal(saved.locationKey, 'node_201');
    assert.deepEqual(saved.approvedFallbackNodeIds, [201]);
    assert.equal(saved.guideKey, mode.runtimeScope === 'TEAM' ? 'guide_202' : undefined);
    const reopened = fixture.page();
    reopened.data.formData.themeRuntimeConfig = payload;
    reopened.data.nodesForm = reopened.data.formData.chapters[0].nodes[1];
    arrival(reopened);
    assert.equal(reopened.data.structureEditor.kind, 'quests');
    assert.deepEqual(reopened.data.structureEditor.quest, saved);
    reopened.saveStructure();
    assert.deepEqual(JSON.parse(reopened._runtimePayload()), JSON.parse(payload));
  });
}

test('不完整或未知的模式必须重选，已有共享策略不可强行切换个人模式', () => {
  for (const game of [null, { runtimeScope: 'TEAM' }, { runtimeScope: 'TEAM', worldMode: 'UNKNOWN' }, { runtimeScope: 'UNKNOWN' }]) {
    const page = fixture.page();
    page.data.nodesForm = page.data.formData.chapters[0].nodes[1];
    const raw = JSON.stringify({ schemaVersion: 1, gameplayRuntime: game });
    page.data.formData.themeRuntimeConfig = raw;
    assert.equal(structure.syncChapters(raw, page.data.formData.chapters).field, 'mode');
    arrival(page);
    assert.equal(page.data.structureEditor.kind, 'mode');
    page.closeStructure();
    assert.equal(page.data.formData.themeRuntimeConfig, raw);
  }
  const page = fixture.page();
  page.data.nodesForm = page.data.formData.chapters[0].nodes[1];
  const raw = JSON.stringify({ schemaVersion: 1, gameplayRuntime: { allowTrade: true } });
  page.data.formData.themeRuntimeConfig = raw;
  arrival(page);
  choose(page, 'PLAYER');
  assert.match(page.data.structureError, /共用现场/);
  assert.equal(page.data.formData.themeRuntimeConfig, raw);
  assert.equal(page.data.structureEditor.kind, 'mode');
  choose(page, 'WORLD_SHARED');
  page.saveStructure();
  assert.equal(page.data.structureEditor.kind, 'quests');
  assert.equal(JSON.parse(page.data.formData.themeRuntimeConfig).gameplayRuntime.allowTrade, true);
});

test('玩法支持矩阵与保存节点前置条件继续生效，不生成未保存节点引用', () => {
  const page = fixture.page();
  page.data.nodesForm = page.data.formData.chapters[0].nodes[1];
  page.data.formData.chapters[0].nodes[0].templateInfo.advancedConfigJson = JSON.stringify({ estimate: { enabled: true } });
  arrival(page);
  choose(page, 'PLAYER');
  assert.match(page.data.structureError, /无法确认是否完成/);
  assert.equal(page.data.formData.themeRuntimeConfig, '');
  const unsaved = fixture.page();
  unsaved.data.nodesForm = unsaved.data.formData.chapters[0].nodes[1];
  delete unsaved.data.formData.chapters[0].nodes[1].id;
  arrival(unsaved);
  choose(unsaved, 'PLAYER');
  unsaved.saveStructure();
  assert.equal(unsaved.data.structureEditor.kind, 'quests');
  assert.match(unsaved.data.structureError, /先保存/);
  assert.equal(unsaved.data.structureEditor.quest, null);
  assert.equal(JSON.parse(unsaved._runtimePayload()).gameplayRuntime.quests, undefined);
});

function alternativePage(t, configs = []) {
  const previous = global.getApp, requests = [], actor = { id: 7, auth: 'session-a' };
  global.getApp = () => ({ getUserID: () => actor.id, getAuthorization: () => actor.auth,
    sendRequest: request => requests.push(request) });
  t.after(() => { global.getApp = previous; });
  const page = fixture.page();
  page.data.formData.themeRuntimeConfig = JSON.stringify(fixture.build().runtime);
  page.data.nodesForm = page.data.formData.chapters[0].nodes[1];
  page._hydrateStructureAlts(alternativeDetail(configs), page.data.formData.chapters);
  arrival(page);
  return { page, requests, actor };
}
function alternativeDetail(configs, nodeId = 202) {
  return { topic: { id: 77 }, chapters: [{ cmsTopicNodeList: [{ id: nodeId }] }],
    nodeAlternativeConfigsLoaded: true, nodeAlternativeConfigs: { [nodeId]: configs } };
}
const configured = (type = 'QUIZ', enabled = true) => ({ type, enabled, promptText: type === 'QUIZ' ? '现场题目' : '现场内容', answerConfigured: type !== 'PHOTO' });
const altInput = (page, type, field, value) => page.onStructureAltInput({ currentTarget: { dataset: { type, field } }, detail: { value } });
const altToggle = (page, type, value) => page.onStructureAltToggle({ currentTarget: { dataset: { type } }, detail: { value } });

test('冷作者回读启用及停用配置，忽略任何答案原文或摘要字段；取消不发请求', t => {
  const { page, requests } = alternativePage(t, [{ ...configured(), answer: 'never-fill', answerHash: 'never-fill' }, configured('SCAN', false), configured('PHOTO', false)]);
  assert.deepEqual(page.data.structureAlts.map(row => [row.type, row.on, row.answerConfigured, row.answer]), [
    ['SCAN', false, true, ''], ['QUIZ', true, true, ''], ['PHOTO', false, false, '']]);
  assert.ok(!JSON.stringify(page._altDrafts).includes('never-fill'));
  altToggle(page, 'QUIZ', false); altInput(page, 'PHOTO', 'prompt', '未提交');
  page.closeStructure(); arrival(page);
  assert.equal(requests.length, 0);
  assert.equal(page.data.structureAlts.find(row => row.type === 'QUIZ').on, true);
  assert.equal(page.data.structureAlts.find(row => row.type === 'PHOTO').prompt, '现场内容');
});

test('未知、缺失、失败与畸形读取不会当成空保存配置，缓存不替代读取', t => {
  const { page, requests } = alternativePage(t, [configured()]);
  for (const detail of [{}, { nodeAlternativeConfigsLoaded: false, nodeAlternativeConfigs: { 202: [] } },
    { nodeAlternativeConfigsLoaded: true, nodeAlternativeConfigs: {} },
    alternativeDetail([{ ...configured(), answerConfigured: 'true' }]),
    alternativeDetail([{ ...configured(), type: 'constructor' }]),
    alternativeDetail([configured(), configured()])]) {
    page._hydrateStructureAlts(detail, page.data.formData.chapters); arrival(page);
    assert.match(page.data.structureError, /未读取/);
    altToggle(page, 'QUIZ', true); altInput(page, 'QUIZ', 'answer', '不能写');
    assert.deepEqual(page._altDirty, {}); assert.equal(page._structureAltsReady(202), false);
  }
  assert.equal(requests.length, 0);
  page._hydrateStructureAlts(alternativeDetail([]), page.data.formData.chapters); arrival(page);
  assert.equal(page._structureAltsReady(202), true); assert.equal(page.data.structureError, '');
});

test('已配置答案留空只改题目，POST 后必须实际回读确认才完成；首次启用仍需真实答案', t => {
  const { page, requests } = alternativePage(t, [configured()]);
  altInput(page, 'QUIZ', 'prompt', '修改后的题目'); page.saveStructure();
  assert.equal(requests.length, 1);
  assert.deepEqual(JSON.parse(requests[0].data), { topicId: 77, taskId: 202, alternativeTaskType: 'QUIZ', promptText: '修改后的题目', enabled: true });
  requests[0].success({ code: 200 });
  assert.equal(requests[1].url, '/api/topic/author-saved-detail');
  assert.equal(page.data.structureEditor.show, true); assert.equal(page._altDirty.QUIZ, true);
  requests[1].success({ code: 200, data: alternativeDetail([{ ...configured(), promptText: '修改后的题目' }]) });
  assert.equal(page.data.structureEditor.show, false); assert.deepEqual(page._altDirty, {});
  arrival(page); assert.equal(page.data.structureAlts.find(row => row.type === 'QUIZ').answer, '');
  page._hydrateStructureAlts(alternativeDetail([]), page.data.formData.chapters); arrival(page);
  altToggle(page, 'QUIZ', true); altInput(page, 'QUIZ', 'prompt', '初次题目'); page.saveStructure();
  assert.match(page.data.structureError, /答案/); assert.equal(requests.length, 2);
  altInput(page, 'QUIZ', 'answer', '原作真实答案'); page.saveStructure();
  assert.equal(JSON.parse(requests[2].data).answer, '原作真实答案');
});

test('既有 QUIZ/SCAN 空答案可关闭和重新启用；没有合法码的首次 SCAN 不伪造输入', t => {
  const { page, requests } = alternativePage(t, [configured(), configured('SCAN')]);
  altToggle(page, 'SCAN', false); altToggle(page, 'QUIZ', false); page.saveStructure();
  requests[0].success({ code: 200 }); requests[1].success({ code: 200 });
  requests[2].success({ code: 200, data: alternativeDetail([configured('SCAN', false), configured('QUIZ', false)]) });
  assert.ok(requests.slice(0, 2).every(request => !Object.hasOwn(JSON.parse(request.data), 'answer')));
  arrival(page); altToggle(page, 'SCAN', true); altToggle(page, 'QUIZ', true); page.saveStructure();
  assert.equal(JSON.parse(requests[3].data).alternativeTaskType, 'SCAN');
  requests[3].success({ code: 200 }); requests[4].success({ code: 200 });
  requests[5].success({ code: 200, data: alternativeDetail([configured('SCAN'), configured()]) });
  page._hydrateStructureAlts(alternativeDetail([]), page.data.formData.chapters); arrival(page);
  altToggle(page, 'SCAN', true); page.saveStructure();
  assert.match(page.data.structureError, /有效码/); assert.equal(requests.length, 6);
});

test('部分写入与回读失败保留待恢复输入，重开编辑器仍可重试，后端错误不改写', t => {
  const { page, requests } = alternativePage(t, []);
  altToggle(page, 'QUIZ', true); altInput(page, 'QUIZ', 'prompt', '新题'); altInput(page, 'QUIZ', 'answer', '真实答案');
  altToggle(page, 'PHOTO', true); altInput(page, 'PHOTO', 'prompt', '拍门牌'); page.saveStructure();
  requests[0].success({ code: 200 }); requests[1].success({ code: 403, msg: '原有本人作者限制' });
  assert.equal(page.data.structureError, '原有本人作者限制'); assert.equal(page.data.structureEditor.show, true);
  assert.deepEqual(page._altDirty, { QUIZ: true, PHOTO: true }); assert.deepEqual(page._altDrafts[202], {});
  page.closeStructure(); arrival(page);
  assert.equal(page.data.structureAlts.find(row => row.type === 'QUIZ').answer, '真实答案');
  page.saveStructure(); requests[2].success({ code: 200 }); requests[3].success({ code: 200 });
  requests[4].fail();
  assert.equal(page.data.structureSaving, false); assert.equal(page._altDirty.PHOTO, true);
  assert.equal(page.data.structureAlts.find(row => row.type === 'QUIZ').answer, '真实答案');
  page.saveStructure(); requests[5].success({ code: 200 }); requests[6].success({ code: 200 });
  requests[7].success({ code: 200, data: alternativeDetail([]) });
  assert.match(page.data.structureError, /不一致/); assert.equal(page.data.structureEditor.show, true);
  assert.equal(page._altDirty.QUIZ, true);
});

test('账号、会话、读取代次、节点和编辑层切换后丢弃迟到写响应，不继续 POST 或清除新状态', t => {
  const { page, requests, actor } = alternativePage(t, [configured()]);
  const mutations = [() => { actor.id++; }, () => { actor.auth += 'new'; }, () => { page._editLoadToken = 2; },
    () => { page.data.nodesForm = page.data.formData.chapters[0].nodes[0]; },
    () => page.closeStructure(), () => { page._unloaded = true; }, () => { page._hidden = true; },
    () => { page._loginContinuationEpoch = 1; }];
  for (const mutate of mutations) {
    page._unloaded = false; page._hidden = false;
    page.data.nodesForm = page.data.formData.chapters[0].nodes[1];
    page._hydrateStructureAlts(alternativeDetail([configured()]), page.data.formData.chapters); arrival(page);
    altInput(page, 'QUIZ', 'prompt', '在途修改'); page.saveStructure();
    const request = requests.at(-1), count = requests.length;
    mutate(); request.success({ code: 200 });
    assert.equal(requests.length, count, '过时写响应不能继续请求');
  }
  assert.equal(page._structureAltsReady(202), true);
  actor.id++; assert.equal(page._structureAltsReady(202), false); assert.deepEqual(page._altDrafts, {});
});

test('空/畸形回包、未读取标志与关闭时丢失答案摘要均保留修改，迟到回读不能清掉待恢复输入', t => {
  const { page, requests, actor } = alternativePage(t, [configured()]);
  altToggle(page, 'QUIZ', false);
  const invalid = [null, { code: 200, data: { ...alternativeDetail([]), chapters: null } },
    { code: 200, data: alternativeDetail([null]) },
    { code: 200, data: { ...alternativeDetail([configured('QUIZ', false)]), nodeAlternativeConfigsLoaded: false } },
    { code: 200, data: alternativeDetail([{ ...configured('QUIZ', false), answerConfigured: false }]) }];
  for (const response of invalid) {
    page.saveStructure(); requests.at(-1).success({ code: 200 });
    assert.doesNotThrow(() => requests.at(-1).success(response));
    assert.equal(page.data.structureEditor.show, true); assert.equal(page.data.structureSaving, false);
    assert.equal(page._altDirty.QUIZ, true); assert.ok(page._altWritePending[202]);
  }
  page.saveStructure(); requests.at(-1).success({ code: 200 });
  actor.auth = 'another-session'; requests.at(-1).success({ code: 200, data: alternativeDetail([configured('QUIZ', false)]) });
  assert.equal(page._altDirty.QUIZ, true); assert.equal(page.data.structureEditor.show, true);
});

test('SCAN 已存空提示按空输入回填，题目首尾空白按后端规范化核对，不要求重输答案', t => {
  const { page, requests } = alternativePage(t, [{ ...configured('SCAN'), promptText: null }, configured()]);
  assert.equal(page.data.structureAlts.find(row => row.type === 'SCAN').prompt, '');
  altInput(page, 'QUIZ', 'prompt', '  新题目  '); page.saveStructure();
  requests[0].success({ code: 200 });
  requests[1].success({ code: 200, data: alternativeDetail([{ ...configured('SCAN'), promptText: null }, { ...configured(), promptText: '新题目' }]) });
  assert.equal(page.data.structureEditor.show, false); assert.deepEqual(page._altDirty, {});
});
