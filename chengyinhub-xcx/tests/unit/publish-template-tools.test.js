const { test } = require('node:test');
const assert = require('node:assert/strict');
const tools = require('../../pages/publish/utils/publish/template-tools.js');
const advancedConfig = require('../../pages/publish/utils/publish/advanced-game-config.js');
const authorFields = require('../../pages/publish/utils/publish/template-author-fields.js');

let pageConfig;
const requests = [];
global.getApp = () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 },
  getAuthorization: () => 'test', sendRequest: request => requests.push(request), tips: () => {} });
global.wx = { getStorageSync: () => undefined, setStorageSync: () => {}, removeStorageSync: () => {},
  getBackgroundAudioManager: () => ({ stop: () => {} }), showToast: () => {}, showModal: () => {} };
global.Page = config => { pageConfig = config; };

function page() {
  delete require.cache[require.resolve('../../pages/publish/temp/index.js')];
  require('../../pages/publish/temp/index.js');
  const result = Object.assign({}, pageConfig);
  result.data = JSON.parse(JSON.stringify(pageConfig.data));
  result.setData = function (patch, callback) {
    for (const [path, value] of Object.entries(patch)) {
      const parts = path.split('.'); let at = this.data;
      for (const part of parts.slice(0, -1)) at = at[part];
      at[parts[parts.length - 1]] = value;
    }
    if (callback) callback();
  };
  result._refreshOutcomeContract = () => ({ items: [], errors: [] });
  result._syncCheckDerived = () => {};
  result.refreshPreviewState = () => {};
  result.hasModule = () => false;
  return result;
}

test('A10 详情回填后保存新草稿透传五个原字段', () => {
  requests.length = 0;
  const editor = page();
  const fields = { rewardRulesJson: '{"tier":2}', merchantGuide: '请到柜台', sensorType: 'steps',
    sensorConfig: '{"goal":30}', storyImg: 'https://example.com/story.jpg' };
  editor.fillFormWithTemplateData(Object.assign({ title: '测试模板', advancedConfigJson: '' }, fields));
  assert.deepEqual(Object.fromEntries(Object.keys(fields).map(key => [key, editor.data.formData[key]])), fields);
  editor.saveDraft();
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/template/draft');
  const payload = JSON.parse(requests[0].data);
  assert.deepEqual(Object.fromEntries(Object.keys(fields).map(key => [key, payload[key]])), fields);
  assert.equal(Object.hasOwn(payload, 'id'), false, '另存新副本不带原模板 id');
});

test('后端模板参数错误打开对应工具并保留草稿输入', () => {
  requests.length = 0;
  const editor = page();
  editor.data.gameKey = 'answer';
  editor._supportContext = { runtime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED' } };
  editor.data.formData.title = '未保存的玩法';
  editor.data.advanced = { interactionTools: { decidesEnding: true, votePolicy: 'MAJORITY',
    plans: [{ title: '继续', endingKey: 'quiet' }] } };
  editor._showBackendFieldError('方案 quiet 须有对应结局', 'vote', 'quiet');
  assert.equal(editor.data.toolSheetOpen, true);
  assert.equal(editor.data.toolKey, 'vote');
  assert.equal(editor.data.toolError.plan, 0);
  editor.closeToolSheet();
  editor.sendData({ title: editor.data.formData.title, status: 0 }, '/api/template/draft', 'draft');
  requests.at(-1).success({ code: 500, msg: 'costOptions 须为 2 至 4 组' });
  assert.equal(editor.data.formData.title, '未保存的玩法');
  assert.equal(editor.data.submitError, 'costOptions 须为 2 至 4 组');
  assert.equal(editor.data.toolSheetOpen, true, '费用参数错误打开费用工具（2026-10-11 费用回到工具目录）');
  assert.equal(editor.data.toolKey, 'cost');
  assert.equal(editor.data.toolError.error, 'costOptions 须为 2 至 4 组');
});

test('移除奖励和剧情只清空对应旧字段，未移除字段不列入 clearFields', () => {
  requests.length = 0;
  const editor = page();
  editor._templateId = 21;
  editor.data.isMyScope = true;
  editor.fillFormWithTemplateData({ id: 21, title: '旧玩法', advancedConfigJson: '',
    rewardRulesJson: '{"reward":1}', merchantGuide: '到柜台', sensorType: 'steps',
    sensorConfig: '{"goal":10}', storyImg: 'story.png' });
  editor.data.moduleList = [{ key: 'reward' }, { key: 'story' }];
  editor.removeModule({ currentTarget: { dataset: { key: 'reward' } } });
  editor.removeModule({ currentTarget: { dataset: { key: 'story' } } });
  editor.saveDraft();
  const body = JSON.parse(requests.at(-1).data);
  assert.equal(body.id, 21);
  assert.deepEqual(body.clearFields, ['rewardRulesJson', 'storyImg']);
  assert.equal(body.merchantGuide, '到柜台');
  assert.equal(body.sensorType, 'steps');
  assert.deepEqual(authorFields.clearFields({ rewardRulesJson: 'x', merchantGuide: 'y', unknown: 'z' },
    { rewardRulesJson: '', merchantGuide: 'y', unknown: '' }), ['rewardRulesJson']);
});

test('切换非传感器方式清空旧设置，传感器仍必需时在玩法字段报错', () => {
  requests.length = 0;
  const editor = page();
  editor.fillFormWithTemplateData({ title: '旧玩法', validationMethod: 7,
    advancedConfigJson: '', sensorType: 'steps', sensorConfig: '{"goal":10}' });
  editor.selectValidationMethod({ currentTarget: { dataset: { value: 1 } } });
  editor.saveDraft();
  assert.deepEqual(JSON.parse(requests.at(-1).data).clearFields, ['sensorType', 'sensorConfig']);

  const blocked = page();
  blocked.fillFormWithTemplateData({ title: '滤镜', validationMethod: 2,
    sensorType: 'filter_shot', sensorConfig: '{"filterStyle":"pet_pov"}', advancedConfigJson: '' });
  blocked.hasModule = key => key === 'finish';
  blocked._setFormState({ 'formData.sensorConfig': '' });
  const blockedPayload = blocked.prepareFormData();
  assert.deepEqual(blockedPayload.clearFields, ['sensorConfig']);
  assert.equal(blocked.validateForm(blockedPayload, true), false);
  assert.match(blocked.data.errors.finish, /请先切换为非传感器验证方式/);
  assert.equal(requests.length, 1, '字段错误不发请求');
});

test('主题内修改原模板沿用本人草稿 id，清空字段走同一 draft 请求', () => {
  requests.length = 0;
  const editor = page();
  editor.data.from = 'fabu';
  editor.data.isMyScope = true;
  editor._templateId = 31;
  editor._boundDraftId = 31;
  editor.fillFormWithTemplateData({ id: 31, title: '原模板', advancedConfigJson: '',
    rewardRulesJson: '{"reward":1}' });
  editor.data.moduleList = [{ key: 'finish' }, { key: 'reward' }];
  editor.hasModule = key => editor.data.moduleList.some(item => item.key === key);
  editor.removeModule({ currentTarget: { dataset: { key: 'reward' } } });
  editor.publishGame();
  const payload = JSON.parse(requests.at(-1).data);
  assert.equal(requests.at(-1).url, '/api/template/draft');
  assert.equal(payload.id, 31);
  assert.deepEqual(payload.clearFields, ['rewardRulesJson']);
});

test('固定费用按后端同名字段写入，其他玩法与奖励不变', () => {
  const original = advancedConfig.defaultConfig();
  original.qa.enabled = true; original.qa.title = '旧题'; original.qa.answerText = '答案';
  original.storyMarker = { answer: 'B' };
  const draft = { mode: 'FIXED', groups: [{ rows: [
    { kind: 'HP', amount: '2' }, { kind: 'LUCK', amount: 1 }, { kind: 'ACTION', amount: 3 },
    { kind: 'COUNTER', key: 'counter.focus', amount: 4 }, { kind: 'ITEM', key: 'supply', amount: 5 }
  ] }] };
  const result = tools.apply('cost', draft, original);
  assert.equal(result.error, undefined);
  assert.deepEqual(result.value.interactionRules, { hpCost: 2, luckCost: 1, actionCost: 3,
    statCosts: [{ statKey: 'counter.focus', amount: 4 }], resourceCosts: [{ itemId: 'supply', quantity: 5 }] });
  assert.equal(result.value.qa.title, '旧题');
  assert.deepEqual(result.value.storyMarker, original.storyMarker);
  assert.equal(original.interactionRules, undefined);
  assert.deepEqual(JSON.parse(advancedConfig.serialize(result.value)).interactionRules, result.value.interactionRules);
});

test('任选一组费用有两到四组，顶层固定费用互斥', () => {
  const first = { mode: 'FIXED', groups: [{ rows: [{ kind: 'HP', amount: 3 }] }] };
  const fixed = tools.apply('cost', first, advancedConfig.defaultConfig()).value;
  const options = { mode: 'OPTIONS', groups: [
    { label: '第一组', rows: [{ kind: 'ITEM', key: 'kit', amount: 1 }] },
    { label: '', rows: [{ kind: 'COUNTER', key: 'counter.focus', amount: 2 }] }
  ] };
  const result = tools.apply('cost', options, fixed);
  assert.deepEqual(result.value.interactionRules.costOptions, [
    { label: '第一组', resourceCosts: [{ itemId: 'kit', quantity: 1 }] },
    { statCosts: [{ statKey: 'counter.focus', amount: 2 }] }
  ]);
  for (const field of ['hpCost', 'luckCost', 'actionCost', 'statCosts', 'resourceCosts']) {
    assert.equal(Object.hasOwn(result.value.interactionRules, field), false);
  }
  assert.match(tools.apply('cost', { mode: 'OPTIONS', groups: options.groups.slice(0, 1) }, fixed).error, /2 至 4/);
  assert.match(tools.apply('cost', { mode: 'OPTIONS', groups: options.groups.concat(options.groups, options.groups.slice(0, 1)) }, fixed).error, /2 至 4/);
  assert.match(tools.apply('cost', { mode: 'FIXED', groups: [{ rows: [{ kind: 'ITEM', key: 'kit', amount: 0 }] }] }, fixed).error, /1 至 999/);
  assert.deepEqual(tools.apply('cost', { mode: 'FIXED', groups: [{ rows: [
    { kind: 'ITEM', key: 'kit', amount: 2 }, { kind: 'ITEM', key: 'kit', amount: 3 }
  ] }] }, fixed).value.interactionRules.resourceCosts, [{ itemId: 'kit', quantity: 5 }]);
});

test('成功次数、范围和交换写入服务端接受的字段', () => {
  const base = advancedConfig.defaultConfig();
  const uses = tools.apply('uses', { limit: '9', scope: 'WORLD' }, base).value;
  assert.deepEqual(uses.interactionRules, { limit: 9, scope: 'WORLD' });
  assert.match(tools.apply('uses', { limit: '100', scope: 'OWNER' }, base).error, /1 至 99/);
  uses.interactionRules.targetScope = 'PARTICIPANTS';
  assert.deepEqual(JSON.parse(advancedConfig.serialize(uses)).interactionRules,
    { limit: 9, scope: 'WORLD', targetScope: 'PARTICIPANTS' });
  const trade = tools.apply('trade', { allowTrade: true }, uses).value;
  assert.deepEqual(trade.interactionTools, { allowTrade: true });
  assert.equal(tools.remove('trade', trade).interactionTools, undefined);
});

test('投票方案、投入与决定结局按既有 plans 结构写入', () => {
  const draft = { votePolicy: 'MAJORITY_ACCEPTANCE', decidesEnding: true, plans: [
    { title: '继续', summary: '说明', materialCost: [{ itemId: 'ticket', quantity: '2' }], endingKey: 'end_a' },
    { title: '退出', summary: '', materialCost: [], endingKey: 'end_b' }
  ] };
  const result = tools.apply('vote', draft, advancedConfig.defaultConfig());
  assert.deepEqual(result.value.interactionTools, { votePolicy: 'MAJORITY_ACCEPTANCE', decidesEnding: true,
    plans: [{ title: '继续', summary: '说明', materialCost: [{ itemId: 'ticket', quantity: 2 }], endingKey: 'end_a' },
      { title: '退出', endingKey: 'end_b' }] });
  assert.deepEqual(JSON.parse(advancedConfig.serialize(result.value)).interactionTools, result.value.interactionTools);
  const independent = tools.apply('vote', { ...draft, plans: [{ ...draft.plans[0], endingKey: '' }] }, advancedConfig.defaultConfig());
  assert.equal(independent.error, undefined);
  assert.ok(independent.value.interactionTools.plans[0].planKey);
  assert.equal(tools.apply('vote', { ...draft, plans: [{ ...draft.plans[0], endingKey: 'END-A' }] }, advancedConfig.defaultConfig()).value.interactionTools.plans[0].endingKey, 'END-A');
  assert.match(tools.apply('vote', draft, { ...result.value, interactionRules: { limit: 2 } }).error, /不能与/);
  const continuing = tools.apply('vote', { ...draft, decidesEnding: false }, result.value).value.interactionTools;
  assert.equal(continuing.decidesEnding, undefined);
  assert.equal(continuing.plans[0].endingKey, 'end_a', '编辑投票策略不删除旧结局引用');
});

test('工具浮层取消不保存，确认只修改目标工具，失败保留输入', () => {
  const editor = page();
  editor.data.gameKey = 'qaPick';
  editor.data.advanced.qa.enabled = true;
  editor.data.advanced.qa.title = '原题';
  editor.openToolSheet({ currentTarget: { dataset: { key: 'uses' } } });
  editor.updateToolField({ currentTarget: { dataset: { field: 'limit' } }, detail: { value: '101' } });
  editor.saveTool();
  assert.equal(editor.data.toolSheetOpen, true);
  assert.equal(editor.data.toolDraft.limit, '101');
  assert.equal(editor.data.advanced.interactionRules, undefined);
  editor.closeToolSheet();
  assert.equal(editor.data.advanced.qa.title, '原题');
  editor.openToolSheet({ currentTarget: { dataset: { key: 'uses' } } });
  editor.updateToolField({ currentTarget: { dataset: { field: 'limit' } }, detail: { value: '2' } });
  editor.saveTool();
  assert.equal(editor.data.advanced.interactionRules.limit, 2);
  assert.equal(editor.data.advanced.qa.title, '原题');
  editor.changeTargetScope({ detail: { value: 1 } });
  assert.equal(editor.data.advanced.interactionRules.targetScope, 'PARTICIPANTS');
  editor.removeTargetScope();
  assert.equal(editor.data.advanced.interactionRules.targetScope, undefined);
});

test('模板费用声明数值和物资槽，复用已有槽且任选组不写主题字面量', () => {
  const base = advancedConfig.defaultConfig();
  const draft = tools.read('cost', base);
  draft.groups[0].rows = [{ kind: 'VALUE', key: '', label: '恢复的数值', amount: '2' },
    { kind: 'ITEM', key: '', label: '支付的物资', amount: '1' }];
  const first = tools.apply('cost', draft, base);
  assert.equal(first.error, undefined);
  assert.deepEqual(first.value.interactionRules, {
    statCosts: [{ statKey: 'value_1', amount: 2 }], resourceCosts: [{ itemId: 'item_1', quantity: 1 }],
    resourceSlots: [{ key: 'value_1', kind: 'VALUE', label: '恢复的数值', required: true },
      { key: 'item_1', kind: 'ITEM', label: '支付的物资', required: true }]
  });
  const reuse = tools.read('cost', first.value);
  assert.equal(reuse.valueSlots[1].key, 'value_1');
  assert.equal(reuse.itemSlots[1].key, 'item_1');
  reuse.mode = 'OPTIONS';
  reuse.groups = [{ label: 'A', rows: [{ kind: 'VALUE', key: 'value_1', amount: 3 }] },
    { label: 'B', rows: [{ kind: 'ITEM', key: 'item_1', amount: 4 }] }];
  const second = tools.apply('cost', reuse, first.value).value;
  assert.deepEqual(second.interactionRules.costOptions, [
    { label: 'A', statCosts: [{ statKey: 'value_1', amount: 3 }] },
    { label: 'B', resourceCosts: [{ itemId: 'item_1', quantity: 4 }] }
  ]);
  assert.equal(second.interactionRules.resourceSlots.length, 2);
  assert.equal(Object.hasOwn(second.interactionRules, 'statCosts'), false);
  assert.deepEqual(JSON.parse(advancedConfig.serialize(second)).interactionRules, second.interactionRules);
});

test('资源槽最后一个工具引用移除即清理，其他效果引用保留', () => {
  const draft = tools.read('cost', advancedConfig.defaultConfig());
  draft.groups[0].rows = [{ kind: 'VALUE', key: '', label: '消耗的数值', amount: 1 }];
  const configured = tools.apply('cost', draft, advancedConfig.defaultConfig()).value;
  assert.equal(tools.remove('cost', configured).interactionRules, undefined);
  configured.interactionRules.statChanges = [{ statKey: 'value_1', delta: 1 }];
  const remaining = tools.remove('cost', configured);
  assert.deepEqual(remaining.interactionRules, { statChanges: [{ statKey: 'value_1', delta: 1 }],
    resourceSlots: [{ key: 'value_1', kind: 'VALUE', label: '消耗的数值', required: true }] });
});

test('投票物资使用 ITEM 槽；空名称阻止保存并定位输入', () => {
  const draft = tools.read('vote', advancedConfig.defaultConfig());
  draft.plans = [{ title: '继续', materialCost: [{ itemId: '', label: '需要的物资', quantity: 2 }] }];
  const result = tools.apply('vote', draft, advancedConfig.defaultConfig());
  assert.deepEqual(result.value.interactionRules, { resourceSlots: [
    { key: 'item_1', kind: 'ITEM', label: '需要的物资', required: true }] });
  assert.deepEqual(result.value.interactionTools.plans, [
    { title: '继续', materialCost: [{ itemId: 'item_1', quantity: 2 }], planKey: result.value.interactionTools.plans[0].planKey }]);
  assert.match(result.value.interactionTools.plans[0].planKey, /^[a-z][a-z0-9_]{0,31}$/);
  const reused = tools.read('vote', result.value);
  reused.plans.push({ title: '退出', materialCost: [{ itemId: reused.itemSlots[1].key, quantity: 1 }] });
  const withReuse = tools.apply('vote', reused, result.value).value;
  assert.equal(withReuse.interactionRules.resourceSlots.length, 1);
  assert.equal(withReuse.interactionTools.plans[1].materialCost[0].itemId, 'item_1');
  const invalid = tools.read('vote', result.value);
  invalid.slots[0].label = '';
  assert.deepEqual(tools.apply('vote', invalid, result.value), { error: '资源名称须为 1 至 20 字', slot: 0, field: 'slotLabel' });
  const cleaned = tools.remove('vote', result.value);
  assert.equal(cleaned.interactionRules, undefined);
  assert.equal(cleaned.interactionTools, undefined);
});

test('旧模板无槽位时读取与保存仍保留原字面量，编辑工具才转为局部槽', () => {
  const legacy = { ...advancedConfig.defaultConfig(), interactionRules: {
    costOptions: [{ hpCost: 2, resourceCosts: [{ itemId: 'legacy_supply', quantity: 1 }] },
      { hpCost: 3, resourceCosts: [{ itemId: 'legacy_supply', quantity: 2 }] }]
  } };
  const before = JSON.stringify(legacy);
  const draft = tools.read('cost', legacy);
  assert.equal(JSON.stringify(legacy), before);
  assert.deepEqual(JSON.parse(advancedConfig.serialize(legacy)).interactionRules, legacy.interactionRules);
  assert.equal(draft.slots.length, 2, '同一旧资源在组选项中只声明一次');
  const edited = tools.apply('cost', draft, legacy).value;
  assert.equal(edited.interactionRules.resourceSlots.length, 2);
  assert.deepEqual(edited.interactionRules.costOptions.map(group => group.statCosts[0].statKey), ['value_1', 'value_1']);
  assert.deepEqual(edited.interactionRules.costOptions.map(group => group.resourceCosts[0].itemId), ['item_1', 'item_1']);
});

test('R5 页面提供费用工具（含物资）；与「结果之后」消耗只能二选一；存量费用完整保留', () => {
  const editor = page(); editor.data.gameKey = 'qaPick';
  editor.data.advanced.interactionRules = { hpCost: 3, resourceCosts: [{ itemId: 'legacy', quantity: 2 }], opaque: [3] };
  const before = JSON.parse(JSON.stringify(editor.data.advanced)); editor._refreshR5Editor();
  assert.deepEqual(editor.data.advanced, before);
  assert.equal(editor.data.r5Editor.toolOptions.some(row => row.key === 'cost'), true);
  const after = { ...advancedConfig.defaultConfig(), outcomeRules: { version: 1, pass: { costs: [{ statKey: 'value_1', amount: 1 }] }, fail: {} },
    interactionRules: { resourceSlots: [{ key: 'value_1', kind: 'VALUE', label: '体温', required: true }] } };
  assert.match(tools.apply('cost', tools.read('cost', after), after).error, /费用只能在一处设置/);
});

test('编辑旧工具时同步将已有数值效果和物资发放改为槽引用', () => {
  const legacy = { ...advancedConfig.defaultConfig(), interactionRules: {
    hpCost: 2, statCosts: [{ statKey: 'counter.focus', amount: 1 }],
    statChanges: [{ statKey: 'counter.focus', delta: 1 }],
    grants: [{ itemId: 'legacy_reward', quantity: 1 }],
    effects: [{ op: 'SET', var: 'clue.found', value: true }]
  } };
  const edited = tools.apply('cost', tools.read('cost', legacy), legacy).value.interactionRules;
  assert.deepEqual(edited.statCosts, [{ statKey: 'value_1', amount: 2 }, { statKey: 'value_2', amount: 1 }]);
  assert.deepEqual(edited.statChanges, [{ statKey: 'value_2', delta: 1 }]);
  assert.deepEqual(edited.grants, [{ itemId: 'item_1', quantity: 1 }]);
  assert.deepEqual(edited.effects, [{ op: 'SET', var: 'clue.found', value: true }]);
  assert.deepEqual(edited.resourceSlots.map(slot => slot.kind), ['VALUE', 'VALUE', 'ITEM']);
});

test('新槽标识避开旧模板已有的字面资源键', () => {
  const legacy = { ...advancedConfig.defaultConfig(), interactionRules: {
    grants: [{ itemId: 'item_1', quantity: 1 }]
  } };
  const draft = tools.read('cost', legacy);
  draft.groups[0].rows = [{ kind: 'ITEM', key: '', label: '支付的物资', amount: 1 }];
  const rules = tools.apply('cost', draft, legacy).value.interactionRules;
  assert.equal(rules.resourceCosts[0].itemId, 'item_2');
  assert.equal(rules.grants[0].itemId, 'item_3');
  assert.deepEqual(rules.resourceSlots.map(slot => slot.key), ['item_2', 'item_3']);
});

test('共享组队终局表决不依赖主题结局；取消和字段校验失败保留原模板', () => {
  const editor = page(); editor.data.gameKey = 'qaText';
  editor._supportContext = { runtime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED' } };
  editor.data.advanced = advancedConfig.defaultConfig();
  editor.openToolSheet({ currentTarget: { dataset: { key: 'vote' } } });
  editor.data.toolDraft.decidesEnding = true;
  editor.data.toolDraft.plans = [{ title: '共同前进', materialCost: [{ itemId: '', label: '支付的物资', quantity: 0 }] }];
  const before = JSON.parse(JSON.stringify(editor.data.advanced)); editor.saveTool();
  assert.equal(editor.data.toolError.field, 'quantity');
  assert.deepEqual(editor.data.advanced, before); assert.equal(editor.data.toolSheetOpen, true);
  editor.data.toolDraft.plans[0].materialCost[0].quantity = 1; editor.saveTool();
  const plans = editor.data.advanced.interactionTools.plans;
  assert.ok(plans[0].planKey); assert.equal(plans[0].endingKey, undefined);
  assert.equal(editor.data.advanced.interactionTools.decidesEnding, true);
  editor.openToolSheet({ currentTarget: { dataset: { key: 'vote' } } }); editor.data.toolDraft.plans[0].title = '未保存'; editor.closeToolSheet();
  assert.equal(editor.data.advanced.interactionTools.plans[0].title, '共同前进');
});

test('决定结局的表决可写「没有方案执行时」收场；关闭决定结局或移除投票时一并清除', () => {
  const base = { votePolicy: 'MAJORITY', decidesEnding: true, plans: [{ title: '撤离', summary: '启动撤离', materialCost: [] }] };
  const withFallback = tools.apply('vote', { ...base, fallbackTitle: ' 沉默的城市 ', fallbackSummary: '维护窗口结束。' }, advancedConfig.defaultConfig()).value;
  assert.deepEqual(withFallback.interactionTools.fallback, { title: '沉默的城市', summary: '维护窗口结束。' });
  assert.equal(withFallback.interactionTools.plans[0].endingKey, undefined, '新方案不要求结局标识或结局章');
  const read = tools.read('vote', withFallback);
  assert.equal(read.fallbackTitle, '沉默的城市'); assert.equal(read.fallbackSummary, '维护窗口结束。');
  assert.match(tools.apply('vote', { ...base, fallbackSummary: '只有内容' }, advancedConfig.defaultConfig()).error, /标题须为 1 至 80 字/);
  // 2026-10-11：是否收场由采用章节决定，「没有方案执行时」只要有投票选项就保留
  assert.deepEqual(tools.apply('vote', { ...base, decidesEnding: false, fallbackTitle: '沉默的城市' }, withFallback).value.interactionTools.fallback, { title: '沉默的城市' });
  const plain = tools.apply('vote', { plans: base.plans, slotMode: true, slots: [], legacyRefs: {} }, advancedConfig.defaultConfig()).value.interactionTools;
  assert.equal(plain.votePolicy, undefined, '表决方式归主题，模板不再写'); assert.equal(plain.decidesEnding, undefined);
  assert.equal(tools.remove('vote', withFallback).interactionTools, undefined);
  assert.deepEqual(JSON.parse(advancedConfig.serialize(withFallback)).interactionTools.fallback, withFallback.interactionTools.fallback);
});

test('奖励工具：固定或任选一组（物资槽），写入 interactionRules.grants / grantOptions，二者互斥', () => {
  const base = advancedConfig.defaultConfig();
  const fixed = tools.apply('reward', { mode: 'FIXED', groups: [{ label: '', rows: [{ kind: 'ITEM', key: '', label: '燃料', amount: '1' }, { kind: 'ITEM', key: '', label: '维修件', amount: 1 }] }], slotMode: true, slots: [], legacyRefs: {} }, base).value;
  assert.deepEqual(fixed.interactionRules.grants, [{ itemId: 'item_1', quantity: 1 }, { itemId: 'item_2', quantity: 1 }]);
  assert.equal(tools.selected(fixed).reward, true);
  const draft = tools.read('reward', fixed); assert.equal(draft.mode, 'FIXED'); assert.equal(draft.groups[0].rows.length, 2);
  const options = tools.apply('reward', { ...draft, mode: 'OPTIONS', groups: [{ label: '口粮', rows: [{ kind: 'ITEM', key: '', label: '口粮', amount: 3 }] }, { label: '口粮+医疗', rows: [{ kind: 'ITEM', key: '', label: '口粮', amount: 1 }, { kind: 'ITEM', key: '', label: '医疗包', amount: 1 }] }] }, fixed).value;
  assert.equal(options.interactionRules.grants, undefined, '任选与固定互斥');
  assert.equal(options.interactionRules.grantOptions.length, 2);
  assert.equal(options.interactionRules.grantOptions[0].label, '口粮');
  assert.match(tools.summaries(options).reward, /口粮：口粮 ×3/);
  assert.match(tools.apply('reward', { ...draft, mode: 'OPTIONS', groups: [draft.groups[0]] }, fixed).error, /2 至 4 组/);
  assert.match(tools.apply('reward', { ...draft, groups: [{ label: '', rows: [{ kind: 'ITEM', key: '', label: '燃料', amount: 0 }] }] }, base).error, /1 至 999/);
  const removed = tools.remove('reward', options);
  assert.equal(removed.interactionRules, undefined, '移除后槽位一并清理');
});

test('线索工具：本队或全场公开，同名同键；投票方案「需要的线索」按名称对应；名称表只保留在用的线索', () => {
  const base = advancedConfig.defaultConfig();
  const clue = tools.apply('clue', { rows: [{ name: 'EV01 原始采样', public: false }, { name: 'EV04 维护规程', public: true }] }, base).value;
  const ev01 = tools.clueKey('EV01 原始采样'), ev04 = tools.clueKey('EV04 维护规程');
  assert.match(ev04, /^clue\.h_[0-9a-f]{12}$/);
  assert.deepEqual(clue.interactionRules.effects, [{ var: ev01, op: 'SET', value: 1 }]);
  assert.deepEqual(clue.interactionRules.worldEffects, [{ var: ev04, op: 'SET', value: 1 }]);
  assert.deepEqual(clue.clues, { [ev01]: 'EV01 原始采样', [ev04]: 'EV04 维护规程' });
  assert.deepEqual(tools.read('clue', clue).rows, [{ name: 'EV01 原始采样', public: false }, { name: 'EV04 维护规程', public: true }]);
  assert.match(tools.apply('clue', { rows: [{ name: 'A', public: false }, { name: 'A', public: true }] }, base).error, /不能重复/);
  const vote = tools.apply('vote', { votePolicy: 'MAJORITY', decidesEnding: true, plans: [{ title: '关闭太阳', summary: '', materialCost: [], clueNames: ['EV04 维护规程'] }], slotMode: true, slots: [], legacyRefs: {} }, base).value;
  assert.deepEqual(vote.interactionTools.plans[0].when, [{ var: ev04, op: 'EQ', value: 1 }], '与公开线索同名即同一标识');
  assert.deepEqual(vote.clues, { [ev04]: 'EV04 维护规程' });
  assert.deepEqual(tools.read('vote', vote).plans[0].clueNames, ['EV04 维护规程']);
  assert.match(tools.apply('clue', { rows: [{ name: 'x', public: true }] }, vote).error, /投票不能与/);
  const cleared = tools.remove('clue', clue);
  assert.equal(cleared.clues, undefined); assert.equal(cleared.interactionRules, undefined);
  assert.equal(JSON.parse(advancedConfig.serialize(clue)).clues[ev04], 'EV04 维护规程');
});

test('同名同类的新资源沿用同一槽：两组奖励都写「口粮」时只声明一项，采用时可以对应', () => {
  const base = advancedConfig.defaultConfig();
  base.classify.enabled = true;
  base.interactionRules = { resourceSlots: [{ key: 'value_1', kind: 'VALUE', label: '保温储备', required: true }] };
  const reward = Object.assign(tools.read('reward', base), { mode: 'OPTIONS', groups: [
    { label: '三份口粮', rows: [{ kind: 'ITEM', key: '', label: '口粮', amount: 3 }] },
    { label: '一份口粮一份医疗包', rows: [{ kind: 'ITEM', key: '', label: '口粮', amount: 1 }, { kind: 'ITEM', key: '', label: '医疗包', amount: 1 }] },
  ] });
  const result = tools.apply('reward', reward, base);
  assert.equal(result.error, undefined, JSON.stringify(result));
  const slots = result.value.interactionRules.resourceSlots;
  const rations = slots.filter(s => s.kind === 'ITEM' && s.label === '口粮');
  assert.equal(rations.length, 1, JSON.stringify(slots));
  const keys = result.value.interactionRules.grantOptions.map(g => g.grants[0].itemId);
  assert.equal(keys[0], keys[1]);
  // 已有同名同类槽时新输入同名也沿用；同名不同类（数值/物资）仍各自声明
  const cost = Object.assign(tools.read('cost', result.value), { mode: 'FIXED', groups: [{ rows: [{ kind: 'VALUE', key: '', label: '保温储备', amount: 2 }, { kind: 'ITEM', key: '', label: '保温储备', amount: 1 }] }] });
  const next = tools.apply('cost', cost, result.value).value.interactionRules;
  assert.equal(next.resourceSlots.filter(s => s.label === '保温储备' && s.kind === 'VALUE').length, 1);
  assert.equal(next.resourceSlots.filter(s => s.label === '保温储备' && s.kind === 'ITEM').length, 1);
  assert.equal(next.statCosts[0].statKey, 'value_1');
});
