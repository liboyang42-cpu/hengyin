const { test } = require('node:test');
const assert = require('node:assert/strict');
const fixture = require('./fixtures/gameplay-structure-form.js');
const ownership = require('../../pages/publish/fabu/theme-ownership.js');
const binding = require('../../pages/publish/utils/publish/template-binding.js');
const copy = value => structuredClone(value);
const vote = (decidesEnding = true) => ({ id: 55, advancedConfigJson: JSON.stringify({ interactionTools: { decidesEnding,
  plans: [{ planKey: 'stay', title: '继续守候' }, { planKey: 'leave', title: '离开现场' }] } }) });
function page(config = {}) {
  const p = Object.assign(fixture.page(), ownership.methods);
  p.data.formData.themeRuntimeConfig = JSON.stringify(config);
  p.data.formData.chapters.push({ id: 901, name: '继续守候', ending: { fallback: false, kind: 'tag', tag: 'tag.stay' }, nodes: [] },
    { id: 902, name: '离开现场', ending: { fallback: true }, nodes: [] });
  p.data.formData.journeyStory = JSON.stringify({ ending: { endings: [{ chapterId: 901, code: 'stay', title: '继续守候' },
    { chapterId: 902, code: 'default', title: '离开现场', fallback: true }] } });
  p.data.nodesForm = { _localId: 'node_1', templateId: 55, templateInfo: vote() };
  p._nodeBindingDraft = null;
  return p;
}
test('旧配置只读摘要完整，不把站点工具生成的设施算作独立旧设施', () => {
  const c = require('./fixtures/author-survival-config.json');
  const legacy = copy(c); legacy.endings = [{ id: 'old', title: '旧结局', fallback: true }];
  legacy.gameplayRuntime.facilities.push({ facilityKey: 'tpl_node_101' });
  const p = page(legacy); p._refreshThemeOwnership();
  assert.deepEqual(p.data.legacyRuntimeSummary, { hasLegacy: true, items: ['休整 1', '救助 1', '配给 1', '设施 1', '运行结局 1'], text: '休整 1 · 救助 1 · 配给 1 · 设施 1 · 运行结局 1' });
  p.onRuntimeName(fixture.event('hpName', '耐力'));
  const saved = JSON.parse(p._runtimePayload());
  assert.deepEqual(saved.gameplayRuntime, legacy.gameplayRuntime); assert.deepEqual(saved.endings, legacy.endings); assert.deepEqual(saved.card, legacy.card);
  assert.deepEqual(ownership.legacySummary({ gameplayRuntime: { facilities: [{ facilityKey: 'tpl_node_101' }] } }), { hasLegacy: false, items: [], text: '' });
});
test('R3 新主题不展示旧配置摘要；仅独立旧配置或旧结局出现时才有摘要', () => {
  for (const config of [{}, { gameplayRuntime: { runtimeScope: 'PLAYER', survival: { consumeOnNoEffect: false }, facilities: [{ facilityKey: 'tpl_node_101' }] } }]) {
    const p = page(config); p._refreshThemeOwnership();
    assert.deepEqual(p.data.legacyRuntimeSummary, { hasLegacy: false, items: [], text: '' });
  }
  assert.equal(ownership.legacySummary({ scenes: [{ endings: [{ id: 'old' }] }] }).hasLegacy, true);
});
test('R3 普通表决不显示方案结局；终局选项只取故事中真实结局章，排除普通章/悬空引用/旧运行目录', () => {
  const p = page({ endings: [{ id: 'runtime_only', title: '旧运行结局' }] });
  p._openNodePlanEndings(vote(false));
  assert.deepEqual(p.data.nodePlanEndingRows, []);
  const story = JSON.parse(p.data.formData.journeyStory);
  story.ending.endings.push({ chapterId: 101, title: '普通章不可选' }, { chapterId: 999, title: '已删除章不可选' });
  p.data.formData.journeyStory = JSON.stringify(story); p._openNodePlanEndings(vote());
  assert.equal(p.data.nodePlanEndingRows.length, 2);
  for (const row of p.data.nodePlanEndingRows) assert.deepEqual(row.options, [
    { value: '901', label: '继续守候' }, { value: '902', label: '离开现场' }
  ]);
  p.data.formData.journeyStory = ''; p._openNodePlanEndings(vote());
  assert.equal(p.data.nodePlanEndingRows.length, 2, '保留方案行与缺结局提示入口');
  assert.ok(p.data.nodePlanEndingRows.every(row => row.options.length === 0));
});
test('无效果默认不消耗；无进行方式不创建运行段；开关只改指定布尔字段', () => {
  const p = page({ schemaVersion: 1 }); p._refreshThemeOwnership();
  const before = p.data.formData.themeRuntimeConfig;
  assert.equal(p.data.runtimeConsumeOnNoEffect, false); assert.equal(p.data.runtimeConsumeOnNoEffectDisabledReason, '请先选择进行方式');
  assert.equal(p.onThemeConsumeOnNoEffect({ detail: { value: true } }), false);
  assert.equal(p.data.formData.themeRuntimeConfig, before);
  const legacy = copy(require('./fixtures/author-survival-config.json')); p.data.formData.themeRuntimeConfig = JSON.stringify(legacy);
  assert.equal(p.onThemeConsumeOnNoEffect({ detail: { value: true } }), true);
  const expected = copy(legacy); expected.gameplayRuntime.survival.consumeOnNoEffect = true;
  assert.deepEqual(JSON.parse(p._runtimePayload()), expected);
  p.onThemeConsumeOnNoEffect({ detail: { value: false } }); assert.deepEqual(JSON.parse(p._runtimePayload()), legacy);
  const invalid = copy(legacy); invalid.gameplayRuntime.survival.consumeOnNoEffect = 'true';
  assert.equal(require('../../pages/publish/fabu/theme-runtime-survival.js').validate(invalid), '无效果消耗策略请选择开启或关闭');
});
test('新主题配给只走章节发放与站点费用选项，编辑策略不生成历史配给数组', () => {
  const p = page({ schemaVersion: 1, gameplayRuntime: { runtimeScope: 'PLAYER' }, chapters: [{ index: 0, grants: [{ itemId: 'supply', quantity: 2, timing: 'START' }] }] });
  const c = p._runtimeConfig(); c.gameplayRuntime.quests = [{ questKey: 'task', costOptions: [{ resourceCosts: [{ itemId: 'supply', quantity: 1 }] }, { hpCost: 2 }] }]; p._saveRuntime(c);
  p.onThemeConsumeOnNoEffect({ detail: { value: true } }); p.onThemeConsumeOnNoEffect({ detail: { value: false } });
  assert.equal(Object.hasOwn(JSON.parse(p._runtimePayload()).gameplayRuntime.survival, 'rations'), false);
  assert.deepEqual(JSON.parse(p._runtimePayload()).chapters, c.chapters); assert.deepEqual(JSON.parse(p._runtimePayload()).gameplayRuntime.quests, c.gameplayRuntime.quests);
});
test('终局采用只列已有结局章；无资源槽仍输出完整绑定，回读选项名字', () => {
  const p = page(); p._openNodePlanEndings(vote());
  assert.deepEqual(p.data.nodePlanEndingRows.map(r => r.options), [ownership.endingOptions(p.data.formData), ownership.endingOptions(p.data.formData)]);
  assert.equal(p.onNodePlanEnding({ currentTarget: { dataset: { index: 0 } }, detail: { value: '901' } }), true);
  p.onNodePlanEnding({ currentTarget: { dataset: { index: 1 } }, detail: { value: '902' } });
  assert.equal(p.data.nodePlanEndingRows[0].targetLabel, '继续守候');
  const selected = p._nodeBindingDraft;
  assert.equal(selected.templateId, 55); assert.deepEqual(selected.values, {}); assert.deepEqual(selected.items, {});
  assert.equal(selected.slotDigest, binding.slotDigest([])); assert.deepEqual(selected.planEndings, { stay: '901', leave: '902' });
  assert.equal(ownership.planIssue(vote(), selected, p.data.formData, {}), null);
  p._openNodePlanEndings(vote(), selected); assert.deepEqual(p._nodeBindingDraft, selected);
});
test('普通表决无结局要求；旧表决不改兼容目录；无效/已删除结局精确报错', () => {
  const p = page(); assert.equal(ownership.planIssue(vote(false), null, p.data.formData, {}), null);
  const old = { id: 55, advancedConfigJson: JSON.stringify({ interactionTools: { decidesEnding: true, plans: [{ title: '旧结局', endingKey: 'old' }] } }) };
  assert.equal(ownership.planIssue(old, null, p.data.formData, { endings: [{ id: 'old', fallback: true }] }), null);
  assert.match(ownership.planIssue(vote(), { planDigest: ownership.planDigest(vote()), planEndings: { stay: '999' } }, p.data.formData, {}).error, /继续守候.*已保存的结局章/);
  p._templateDetailsById[55] = vote(); p.data.formData.chapters[0].nodes = [p.data.nodesForm];
  // 2026-10-11 终局表决默认在最终章收场：未单独链接结局章不再报错；放在非最终章时精确报错
  assert.deepEqual(p._nodePlanEndingIssues(), []);
  p.data.formData.chapters.push({ id: 903, name: '后续章', nodes: [] });
  assert.deepEqual(p._nodePlanEndingIssues()[0].field, 'templateTool:client:node_1:vote:stay');
  assert.match(p._nodePlanEndingIssues()[0].label, /须放在最终章/);
  p.data.formData.chapters.pop();
  p.data.formData.journeyStory = ''; p._openNodePlanEndings(vote());
  assert.equal(p.onNodePlanEnding({ currentTarget: { dataset: { index: 0 } }, detail: { value: 0 } }), false);
  assert.match(p.data.nodePlanEndingError, /先保存结局章/);
});
test('复制清除章节结局引用并单次告知，保留原效果资源绑定与故事', () => {
  const p = page(), c = { templateBindings: { 11: { templateId: 55, values: { value_1: 'sys.hp' }, items: {}, planEndings: { stay: '901' } },
    12: { templateId: 55, planEndings: { leave: '902' } } } }, notices = [];
  const story = p.data.formData.journeyStory; ownership.copyReferences(c, notices);
  assert.deepEqual(notices, ['终局表决的结局章']); assert.deepEqual(c.templateBindings[11].values, { value_1: 'sys.hp' });
  assert.equal(Object.hasOwn(c.templateBindings[11], 'planEndings'), false); assert.equal(p.data.formData.journeyStory, story);
  const legacy = { id: 55, advancedConfigJson: JSON.stringify({ interactionTools: { decidesEnding: true, plans: [{ title: '旧 A' }, { title: '旧 B' }] } }) };
  assert.deepEqual(ownership.terminalPlans(legacy).map(row => row.planKey), ['1', '2']);
});
test('方案摘要与后端 count/planKey/title 顺序一致，标题或旧方案排序变化须逐项重新链接', () => {
  const crypto = require('node:crypto');
  const canonical = '{"count":2,"plans":[{"planKey":"stay","title":"继续守候"},{"planKey":"leave","title":"离开现场"}]}';
  assert.equal(ownership.planDigest(vote()), crypto.createHash('sha256').update(canonical).digest('hex'));
  // W1 的 GameplayTemplateRules.planDigest 同构向量（含旧模板的空键）。
  for (const [plans, expected] of [
    [[{ planKey: 'leave', title: '离开' }, { planKey: 'stay', title: '留守' }], '80d492cc83d2133da813e9464b185828805137a67ae6b3efb1f39c8ec953ab29'],
    [[{ title: '甲' }, { title: '乙' }], 'b719abed77dd4b6ddc7a8a14b99a6f1905e46a2152a6652e5d6fef206ef744f7'],
  ]) assert.equal(ownership.planDigest({ advancedConfigJson: { interactionTools: { plans } } }), expected);
  const p = page(); p._openNodePlanEndings(vote());
  for (const [index, value] of [[0, '901'], [1, '902']]) p.onNodePlanEnding({ currentTarget: { dataset: { index } }, detail: { value } });
  const previous = copy(p._nodeBindingDraft), changed = vote();
  const config = JSON.parse(changed.advancedConfigJson); config.interactionTools.plans[0].title = '改名后的方案'; changed.advancedConfigJson = JSON.stringify(config);
  p.data.nodesForm.templateInfo = changed; p._openNodePlanEndings(changed, previous);
  assert.match(p.data.nodePlanEndingError, /重新选择各方案/);
  assert.match(ownership.planIssue(changed, previous, p.data.formData, {}).error, /重新选择/);
  p.onNodePlanEnding({ currentTarget: { dataset: { index: 0 } }, detail: { value: '901' } });
  assert.deepEqual(p._nodeBindingDraft.planEndings, { stay: '901' });
  // 只链接一个方案时，未链接的方案在最终章收场，不再要求逐项选择结局章
  assert.equal(ownership.planIssue(changed, p._nodeBindingDraft, p.data.formData, {}), null);
  assert.deepEqual(previous.planEndings, { stay: '901', leave: '902' });
});
test('已编译章节终局回读不依赖旧运行目录；删除结局章后仍拦截', () => {
  const p = page({ gameplayRuntime: { runtimeScope: 'PLAYER', plans: [{ terminal: true, storyEndingId: 'stay', endingChapterId: 901 }] } });
  const structure = require('../../pages/publish/fabu/theme-runtime-structure.js');
  assert.equal(structure.validate(p._runtimeConfig(), p.data.formData.chapters), null);
  assert.match(structure.validate(p._runtimeConfig(), p.data.formData.chapters.filter(ch => ch.id !== 901)).error, /已保存的结局章/);
});
test('当前场景覆盖模板方案时，采用回显、摘要和发布检查使用同一真实配置', () => {
  const sceneVote = vote(); const tools = JSON.parse(sceneVote.advancedConfigJson); tools.interactionTools.plans = [tools.interactionTools.plans[0]];
  const p = page({ currentScene: 'today', scenes: [{ id: 'today', nodeConfigs: { 'client:node_1': tools } }] });
  p._openNodePlanEndings(vote()); assert.equal(p.data.nodePlanEndingRows.length, 1);
  p.onNodePlanEnding({ currentTarget: { dataset: { index: 0 } }, detail: { value: '901' } });
  assert.equal(p._nodeBindingDraft.planDigest, ownership.planDigest({ advancedConfigJson: tools }));
  const c = p._runtimeConfig(); c.templateBindings = { 'client:node_1': copy(p._nodeBindingDraft) }; p._saveRuntime(c);
  p.data.formData.chapters[0].nodes = [p.data.nodesForm];
  assert.deepEqual(p._nodePlanEndingIssues(), []);
});
