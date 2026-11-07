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
