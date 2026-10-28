const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const fixture = require('./fixtures/gameplay-structure-form.js');
const structure = require('../../pages/publish/fabu/theme-runtime-structure.js');
const copy = value => JSON.parse(JSON.stringify(value));
test('表单生成 TEAM/WORLD_SHARED、真实章节幕、到达/引导/备用站和唯一运行默认结局', () => {
  const built = fixture.build();
  assert.deepEqual(built.runtime.gameplayRuntime, { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED', survival: { zeroHpPolicy: 'BLOCK_HP_COST', actionLimitPolicy: 'BLOCK_OVERFLOW' },
    acts: [{ actKey: 'chapter_101', completionRule: { type: 'ALL' }, questKeys: ['node_201', 'node_202', 'node_203'], hostUnlock: true }],
    quests: [{ questKey: 'node_201', nodeId: 201, locationKey: 'node_201', settlementSlot: 'node_201', actKey: 'chapter_101' },
      { questKey: 'node_202', nodeId: 202, locationKey: 'node_201', settlementSlot: 'node_202', actKey: 'chapter_101', guideKey: 'guide_202', approvedFallbackNodeIds: [201] }, { questKey: 'node_203', nodeId: 203, locationKey: 'node_203', settlementSlot: 'node_203', actKey: 'chapter_101' }] });
  assert.deepEqual(built.runtime.endings.map(row => row.fallback), [false, true]);
  assert.equal(structure.validate(built.runtime, built.chapters), null);
});
test('三个模式遵照后端组合与矩阵，世界工具不允许切换个人/独立队伍；旧配置原样保留', () => {
  const p = fixture.page(), raw = { schemaVersion: 1, capacity: 2, gameplayRuntime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED', survival: { zeroHpPolicy: 'BLOCK_PAID' } } };
  for (const mode of structure.MODES) {
    const next = structure.setMode(raw, mode.key, p.data.formData.chapters, p.data.supportMatrix, p._templateDetailsById).value;
    assert.equal(next.gameplayRuntime.runtimeScope, mode.runtimeScope);
    assert.equal(next.gameplayRuntime.worldMode, mode.worldMode);
    assert.equal(next.gameplayRuntime.survival.zeroHpPolicy, 'BLOCK_PAID');
  }
  const shared = copy(raw); shared.gameplayRuntime.allowTrade = true;
  assert.match(structure.modeOptions(shared, [], p.data.supportMatrix).find(row => row.key === 'PLAYER').reason, /共用现场/);
  const unsupported = copy(p.data.formData.chapters); unsupported[0].nodes[0].templateInfo.advancedConfigJson = JSON.stringify({ estimate: { enabled: true } });
  assert.match(structure.modeOptions(raw, unsupported, p.data.supportMatrix)[0].reason, /出发站.*无法确认是否完成/);
  assert.equal(structure.modeOptions(raw, unsupported, null)[0].reason, '');
  assert.deepEqual(raw, { schemaVersion: 1, capacity: 2, gameplayRuntime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED', survival: { zeroHpPolicy: 'BLOCK_PAID' } } });
});
test('各结构表单回填后不改动保存 / 复制不丢其他运行字段，取消不写草稿', () => {
  const built = fixture.build(), p = fixture.page(); p.data.formData.chapters = copy(built.chapters); p._templateDetailsById = Object.fromEntries(built.templates.map(row => [row.id, row])); p.data.formData.themeRuntimeConfig = JSON.stringify(built.runtime);
  for (const kind of ['mode', 'acts', 'quests', 'endings']) {
    p.data.nodesForm = p.data.formData.chapters[0].nodes[1]; fixture.open(p, kind); p.saveStructure();
    assert.equal(p.data.structureError, ''); assert.deepEqual(JSON.parse(p._runtimePayload()), built.runtime);
  }
  fixture.open(p, 'endings'); p.onStructureEnding(fixture.event('title', '不保存的修改')); p.closeStructure();
  assert.deepEqual(JSON.parse(p._runtimePayload()), built.runtime);
  const copied = copy(built.runtime); copied.gameplayRuntime.facilities = [{ facilityKey: 'preserved' }];
  const reselect = []; structure.copyReferences(copied, reselect);
  assert.deepEqual(copied.gameplayRuntime.quests, []);
  assert.deepEqual(copied.gameplayRuntime.acts, built.runtime.gameplayRuntime.acts.map(act => ({ ...act, questKeys: [] })));
  assert.deepEqual(copied.endings, built.runtime.endings); assert.deepEqual(copied.gameplayRuntime.facilities, [{ facilityKey: 'preserved' }]);
  assert.match(reselect.join(''), /到达.*引导.*备用/);
});
test('字段错误以中文显示，不清空草稿；未保存节点不可虚构引用，归零挑战不作为普通任务', () => {
  const p = fixture.page(); fixture.open(p, 'mode'); p.chooseStructureMode({ currentTarget: { dataset: { key: 'PLAYER' } } }); p.saveStructure();
  p.onChapterHostUnlock({ detail: { value: true } }); assert.match(p.data.structureError, /个人.*工作人员/); assert.match(p.data.chapterHostUnlockDisabledReason, /个人.*工作人员/);
  p.data.formData.chapters[0].nodes[0].id = undefined; fixture.open(p, 'acts'); assert.match(p.data.structureError, /先保存/);
  const built = fixture.build(), zero = copy(built.runtime); zero.gameplayRuntime.survival.zeroChallenge = { challengeNodeId: 202 };
  assert.deepEqual(structure.syncChapters(zero, built.chapters).value.gameplayRuntime.quests.map(row => row.nodeId), [201, 203]);
  const draft = copy(built.runtime); draft.endings[1].fallback = false;
  assert.match(structure.validate(draft, built.chapters).error, /默认收尾/);
  draft.endings[1].fallback = true; draft.gameplayRuntime.quests[0].approvedFallbackNodeIds = [999];
  assert.match(structure.validate(draft, built.chapters).error, /本主题/);
  p.data.formData.chapters = copy(built.chapters); p.data.formData.themeRuntimeConfig = JSON.stringify(built.runtime); fixture.open(p, 'endings'); p.onStructureEnding(fixture.event('title', ''));
  p.saveStructure(); assert.match(p.data.structureError, /名称/); assert.equal(p.data.structureEditor.show, true);
  assert.deepEqual(JSON.parse(p._runtimePayload()), built.runtime);
});
test('运行结局唯一默认 / 被方案引用不可删除，与剧情结局无关；指南复用原题不存新答案', () => {
  const p = fixture.page(), built = fixture.build(); p.data.formData.themeRuntimeConfig = JSON.stringify(built.runtime);
  p.data.formData.journeyStory = JSON.stringify({ ending: { endings: [{ id: 'story' }] } });
  const story = p.data.formData.journeyStory; fixture.open(p, 'endings'); p.chooseStructureFallbackEnding({ currentTarget: { dataset: { index: 0 } } }); p.saveStructure();
  assert.equal(p.data.formData.journeyStory, story);
  const config = JSON.parse(p.data.formData.themeRuntimeConfig); config.gameplayRuntime.plans = [{ endingKey: 'ending_1' }]; p.data.formData.themeRuntimeConfig = JSON.stringify(config);
  fixture.open(p, 'endings'); p.removeStructureEnding({ currentTarget: { dataset: { index: 0 } } }); assert.match(p.data.structureError, /正在.*使用/);
  assert.ok(!('answers' in built.runtime.gameplayRuntime.quests[1]));
  const source = fs.readFileSync(require('node:path').resolve(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8');
  // R3 归属纠正：模板不再选主题级运行结局，终局结果在站点采用时链接最终章结局；旧运行结局数据仍按上面兼容逻辑保留。
  assert.doesNotMatch(source, /chooseVoteEnding|runtimeEndingOptions/);
  // 2026-10-11 主人要求配置里不放说明文字：结局在站点采用时选，编辑页不再写这句提示。
  assert.doesNotMatch(source, /在把这个模板放到站点时，从最终章已写好的结局里选/);
  const detail = fs.readFileSync(require('node:path').resolve(__dirname, '../../pages/publish/fabu/topic-detail-sheet.wxml'), 'utf8');
  assert.doesNotMatch(detail, /data-kind="endings"|data-section="recovery"|data-section="facility"/);
});
test('生成的后端编译夹具始终与表单结果相同', () => {
  const generated = require('./fixtures/gameplay-structure-form.json');
  assert.deepEqual(fixture.build(), generated);
});

test('共用地点可改回自己的站点，历史自定义地点不会丢失；多幕配置不静默合并', () => {
  const built = fixture.build(), p = fixture.page();
  p.data.formData.themeRuntimeConfig = JSON.stringify(built.runtime); p.data.nodesForm = p.data.formData.chapters[0].nodes[1];
  fixture.open(p, 'quests'); const own = p.data.structureLocations.findIndex(row => row.value === 'node_202');
  assert.ok(own >= 0); p.onStructureLocation({ detail: { value: own } }); p.saveStructure();
  assert.equal(JSON.parse(p._runtimePayload()).gameplayRuntime.quests[1].locationKey, 'node_202');
  const config = copy(built.runtime); config.gameplayRuntime.quests[1].locationKey = 'legacy_place';
  assert.ok(structure.locations(config, built.chapters).some(row => row.value === 'legacy_place'));
  config.gameplayRuntime.quests[1].actKey = 'another_act';
  assert.match(structure.syncChapters(config, built.chapters).error, /多幕/);
});

test('模式复制清理模板生成设施 / 任务设施引用 / 来源记录，按新节点重新编译；自写设施保留', () => {
  const raw = fixture.build().runtime, game = raw.gameplayRuntime;
  game.quests[0].facilityKey = 'tpl_node_201';
  game.facilities = [{ facilityKey: 'tpl_node_201', operationNodeId: 201 }, { facilityKey: 'custom', operationNodeId: 202 }];
  game.templateTools = { baselineAllowTrade: false, nodes: [{ nodeId: 201, planKeys: ['ending_1'], decidesEnding: true }] };
  game.allowTrade = true; game.votePolicy = 'MAJORITY'; game.plans = [{ endingKey: 'ending_1', operationNodeId: 201 }];
  const messages = []; structure.copyReferences(raw, messages);
  assert.deepEqual(game.quests, []); assert.equal(game.acts[0].actKey, "chapter_101"); assert.ok(!game.templateTools);
  assert.deepEqual(game.facilities, [{ facilityKey: 'custom', operationNodeId: 202 }]);
  assert.deepEqual(game.plans, []); assert.equal(game.allowTrade, false); assert.ok(!game.votePolicy);
  const chapters = fixture.build().chapters.map(ch => ({ ...ch, id: 301, nodes: ch.nodes.map(n => ({ ...n, id: n.id + 100 })) }));
  const rebuilt = structure.syncChapters(raw, chapters).value.gameplayRuntime;
  assert.equal(rebuilt.acts[0].actKey, 'chapter_101');
  assert.deepEqual(rebuilt.quests.map(q => q.nodeId), [301, 302, 303]);
  assert.ok(rebuilt.quests.every(q => !q.facilityKey));
});

test('协调者界面合同：直接切换章节开幕、主题/节点摘要与仅展示的默认结局标志', () => {
  const built = fixture.build(), p = fixture.page(); p.data.formData.themeRuntimeConfig = JSON.stringify(built.runtime);
  p.data.nodesForm = p.data.formData.chapters[0].nodes[1]; p._refreshStructureSummary();
  assert.deepEqual(p.data.structureSummary, { modeLabel: '队伍共用现场', endingsText: '2 个 · 默认收尾：安全撤离', endingsError: '' });
  // 10-28 e-node：摘要只写到达地点；打开了换个方式时写「可扫码或答题」。
  assert.equal(p.data.nodeStructureSummary, '出发站');
  p._altDrafts = { [p.data.nodesForm.id]: { SCAN: { on: true }, QUIZ: { on: true } } }; p._refreshStructureSummary();
  assert.equal(p.data.nodeStructureSummary, '可扫码或答题');
  assert.equal(p.data.structureModeOptions.find(row => row.selected).key, 'WORLD_SHARED');
  assert.equal(p.data.chapterHostUnlock, true); assert.equal(p.data.chapterHostUnlockDisabledReason, '');
  assert.deepEqual(p.data.structureModeOptions.map(row => row.icon), ['tab-me', 'tab-club', 'tab-explore']);
  p.onChapterHostUnlock({ detail: { value: false } });
  assert.equal(p.data.chapterHostUnlock, false); assert.equal(JSON.parse(p._runtimePayload()).gameplayRuntime.acts[0].hostUnlock, false);
  fixture.open(p, 'quests'); assert.equal(p.data.structureFallbackCount, 1);
  fixture.open(p, 'endings'); assert.deepEqual(p.data.structureEndings.map(row => row.isFallback), [false, true]);
  p.chooseStructureFallbackEnding({ currentTarget: { dataset: { index: 0 } } });
  assert.deepEqual(p.data.structureEndings.map(row => row.isFallback), [true, false]); p.saveStructure();
  assert.ok(JSON.parse(p._runtimePayload()).endings.every(row => !('isFallback' in row)));
  const config = p._runtimeConfig(); config.gameplayRuntime.runtimeScope = 'PLAYER'; delete config.gameplayRuntime.worldMode; p._saveRuntime(config);
  p.onChapterHostUnlock({ detail: { value: true } }); assert.match(p.data.structureError, /个人.*工作人员/);
  assert.equal(p.data.chapterHostUnlock, false);
  p.data.popChapterAction = 0; p._refreshStructureSummary(); assert.match(p.data.chapterHostUnlockDisabledReason, /保存/);
  p.onChapterHostUnlock({ detail: { value: true } }); assert.match(p.data.structureError, /本章没有/);
});

test('删除章节幕可选调用生存引用清理接口，显示中文重选列表；未合入接口时仍可使用', () => {
  for (const available of [false, true]) {
    const p = fixture.page(), built = fixture.build(); p.data.formData.themeRuntimeConfig = JSON.stringify(built.runtime);
    let calls = 0, modal;
    const previousModal = wx.showModal; wx.showModal = options => { modal = options; };
    if (available) p._pruneSurvivalActRefs = (config, removed) => {
      calls++; assert.deepEqual(removed, ['chapter_101']); assert.deepEqual(config.gameplayRuntime.acts, []);
      config.gameplayRuntime.facilities = [{ facilityKey: 'rest', actKey: '' }]; return ['休整设施：请重新选择章节'];
    };
    try { p._syncStructureChapters([]); } finally { wx.showModal = previousModal; }
    assert.equal(calls, Number(available)); assert.deepEqual(JSON.parse(p._runtimePayload()).gameplayRuntime.acts, []);
    assert.equal(p.data.structureReferenceNotice, available ? '休整设施：请重新选择章节' : '');
    if (available) { assert.equal(modal.content, p.data.structureReferenceNotice); assert.equal(modal.showCancel, false); }
  }
});

test('章节完成要求更新对应幕，字段错误定位当前站点或章节而非错误的结局表单', () => {
  const built = fixture.build(), p = fixture.page(); p.data.formData.themeRuntimeConfig = JSON.stringify(built.runtime);
  p._openChapterRules(0); p.onChapterCompleteAll({ detail: { value: false } });
  p.onChapterQuantity({ detail: { value: '2' } }); assert.equal(p._saveChapterRules(), true);
  assert.deepEqual(p._runtimeConfig().gameplayRuntime.acts[0].completionRule, { type: 'ANY_N', quantity: 2 });
  p.onChapterCompleteAll({ detail: { value: true } }); p._saveChapterRules();
  assert.deepEqual(p._runtimeConfig().gameplayRuntime.acts[0].completionRule, { type: 'ALL' });
  let target; p.showEditChapter = e => { target = ['chapter', e.currentTarget.dataset.index]; };
  p._locateStructureIssue('acts'); assert.deepEqual(target, ['chapter', 0]);
  const config = p._runtimeConfig(); config.gameplayRuntime.quests[1].approvedFallbackNodeIds = [999]; p._saveRuntime(config);
  p.showEditNodes = e => { target = e.currentTarget.dataset; p.data.nodesForm = p.data.formData.chapters[target.chapterindex].nodes[target.nodeindex]; };
  p._locateStructureIssue('quests'); assert.deepEqual(target, { chapterindex: 0, nodeindex: 1 }); assert.equal(p.data.structureEditor.kind, 'quests');
});
