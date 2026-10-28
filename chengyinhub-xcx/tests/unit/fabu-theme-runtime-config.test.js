const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const toasts = [];
global.getApp = () => ({ globalData: {}, tips: message => toasts.push(message) });
global.wx = { showToast: option => toasts.push(option.title) };
const runtime = require('../../pages/publish/fabu/theme-runtime.js');
const initial = require('../../pages/publish/utils/publish/story-initial-state.js');
const values = require('../../pages/publish/fabu/theme-values.js');

function page() {
  const data = JSON.parse(JSON.stringify(runtime.data));
  data.supportMatrix = require('./fixtures/gameplay-support-matrix.json').data;
  data.formData = { chapters: [{ nodes: [] }], journeyRules: '', themeRuntimeConfig: '' };
  data.editingTopicId = '77';
  const instance = { data, setData(patch) {
    Object.entries(patch).forEach(([key, value]) => {
      const parts = key.split('.');
      let target = this.data;
      parts.slice(0, -1).forEach(part => { target = target[part]; });
      target[parts.at(-1)] = value;
    });
  } };
  return Object.assign(instance, runtime.methods);
}

const event = (field, value) => ({ currentTarget: { dataset: { field } }, detail: { value } });
const hpEvent = (dataset = {}, value) => ({ currentTarget: { dataset }, detail: { value } });
const pickHp = (p, key) => p.pickZeroHpPolicy(hpEvent({}, p.data.runtimeZeroHpOptions.findIndex(row => row.key === key)));
function roleEvent(p, nodeId, value) {
  return { currentTarget: { dataset: { nodeid: nodeId } }, detail: { value: p.data.storyRoleOptions.findIndex(o => o.value === value) } };
}

test('归零策略使用服务器矩阵，通用局回填 BLOCK_PAID，旧局保留 LEGACY_END', () => {
  const p = page();
  p.data.formData.themeRuntimeConfig = JSON.stringify({ gameplayRuntime: { runtimeScope: 'PLAYER', survival: { zeroHpPolicy: 'BLOCK_PAID' } } });
  p._syncRuntimeStats();
  assert.deepEqual(p.data.runtimeZeroHpPolicyKeys, p.data.supportMatrix.zeroHpPolicies.generic);
  assert.equal(p.data.runtimeZeroHpPolicyKeys[p.data.runtimeZeroHpPolicyIndex], 'BLOCK_PAID');
  assert.equal(p._validateRuntimeConfig(), '');
  p.data.formData.themeRuntimeConfig = JSON.stringify({ gameplayRuntime: { runtimeScope: 'PLAYER', survival: { zeroHpPolicy: 'LEGACY_END' } } });
  assert.match(p._validateRuntimeConfig(), /不支持此归零策略/);
  p.data.formData.themeRuntimeConfig = JSON.stringify({ gameplayRuntime: { survival: { zeroHpPolicy: 'LEGACY_END' } } });
  p._syncRuntimeStats();
  assert.equal(p.data.runtimeZeroHpPolicyKeys[p.data.runtimeZeroHpPolicyIndex], 'LEGACY_END');
  assert.equal(p._validateRuntimeConfig(), '');
  p.data.formData.chapters[0].nodes = [{ _localId: 'new', templateInfo: { advancedConfigJson: JSON.stringify({ interactionRules: { hpCost: 1 } }) } }];
  p._syncRuntimeStats();
  assert.equal(p.data.runtimeZeroHpPolicyKeys.includes('LEGACY_END'), false);
  assert.match(p._validateRuntimeConfig(), /不支持此归零策略/);
});
test('矩阵缺失时不猜归零枚举，不改已保存策略', () => {
  const p = page(); p.data.supportMatrix = null;
  p.data.formData.themeRuntimeConfig = JSON.stringify({ gameplayRuntime: { survival: { zeroHpPolicy: 'BLOCK_PAID' } } });
  const before = p.data.formData.themeRuntimeConfig;
  p._syncRuntimeStats(); assert.deepEqual(p.data.runtimeZeroHpPolicyKeys, []);
  p.openZeroHpEditor(); pickHp(p, 'BLOCK_HP_COST'); p.saveZeroHpEditor();
  assert.equal(p.data.formData.themeRuntimeConfig, before);
  assert.match(p._validateRuntimeConfig(), /支持矩阵未加载/);
});

test('内置数值改名写入主题 JSON，回读及作者文案有默认值', () => {
  const p = page();
  p.onRuntimeName(event('hpName', '体力'));
  p.onRuntimeName(event('luckName', '运气'));
  p.onRuntimeName(event('actionName', '行动点'));
  const saved = JSON.parse(p._runtimePayload());
  assert.deepEqual([saved.hpName, saved.luckName, saved.actionName], ['体力', '运气', '行动点']);
  assert.equal(Object.hasOwn(saved, 'gameplayRuntime'), false);
  const loaded = page(); loaded.data.formData.themeRuntimeConfig = JSON.stringify(saved);
  loaded.data.formData.journeyRules = initial.serialize({ hp: { enabled: true, init: 10, max: 10 }, luck: { enabled: true, init: 3, max: 3 } });
  loaded._syncRuntimeStats();
  assert.deepEqual([loaded.data.runtimeHpLabel, loaded.data.runtimeLuckLabel, loaded.data.runtimeActionLabel], ['体力', '运气', '行动点']);
  assert.deepEqual(loaded.data.runtimePurposes, ['展示', '体力', '运气']);
  assert.deepEqual(loaded.data.initialStateOptions.map(row => row.label), ['体力', '运气']);
  loaded.onRuntimeName(event('hpName', ''));
  assert.equal(loaded.data.runtimeHpLabel, '生命值');
  assert.equal(loaded.data.runtimeHpInitialLabel, '精力');
  loaded.onRuntimeName(event('hpName', '甲'.repeat(25)));
  assert.equal(loaded._runtimeConfig().hpName.length, 20);
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/index.wxml'), 'utf8');
  // 2026-10-12 用户定:数值消耗是通用的,章节弹窗不再单设「{{runtimeHpLabel}}消耗」;改名后的标签仍用于章末配给的扣减项
  assert.doesNotMatch(wxml, /\{\{runtimeHpLabel\}\}消耗/);
  assert.match(wxml, /\|\| runtimeHpLabel\}\}<\/text>/);
  assert.match(wxml, /开局\{\{runtimeHpInitialLabel\}\}/);
  assert.match(fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/runtime-editor.wxml'), 'utf8'), /item.statLabel \+ '变化'/);
  const detail = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/topic-detail-sheet.wxml'), 'utf8');
  assert.match(detail, /data-stat="hp" bindtap="editRuntimeStat"/);
  assert.match(detail, /data-stat="luck" bindtap="editRuntimeStat"/);
  assert.match(detail, /data-stat="action" bindtap="editRuntimeStat"/);
  loaded.onRuntimeActionToggle({ detail: { value: true } });
  assert.equal(loaded._runtimeConfig().actionLimit, 20);
  assert.equal(Object.hasOwn(loaded._runtimeConfig(), 'gameplayRuntime'), false);
  loaded.onRuntimeActionToggle({ detail: { value: false } });
  assert.equal(Object.hasOwn(loaded._runtimeConfig(), 'actionLimit'), false);
});

test('旧主题保存和校验不创建玩法配置，挑战退回默认也清理新增层级', () => {
  const p = page();
  p.data.formData.themeRuntimeConfig = JSON.stringify({ hpName: '体力', capacity: 8 });
  assert.equal(p._validateRuntimeConfig(), '');
  assert.deepEqual(JSON.parse(p._runtimePayload()), { hpName: '体力', capacity: 8 });
  p.openZeroHpEditor(); pickHp(p, 'BLOCK_HP_COST'); assert.equal(p.saveZeroHpEditor(), true);
  assert.equal(Object.hasOwn(p._runtimeConfig(), 'gameplayRuntime'), false);
  p.data.formData.journeyRules = initial.serialize({ hp: { enabled: true, init: 5, max: 5 } });
  p.data.formData.chapters[0].nodes = [{ id: 101, name: '挑战', templateInfo: { advancedConfigJson: JSON.stringify({ qa: { enabled: true, mode: 'TYPE' } }) } }];
  p.openZeroHpEditor(); pickHp(p, 'CHALLENGE'); p.pickZeroHpChallengeNode(hpEvent({}, 0));
  assert.equal(p.saveZeroHpEditor(), true);
  assert.equal(p._runtimeConfig().gameplayRuntime.survival.zeroHpPolicy, 'CHALLENGE');
  p.openZeroHpEditor(); pickHp(p, 'BLOCK_HP_COST'); assert.equal(p.saveZeroHpEditor(), true);
  assert.equal(Object.hasOwn(JSON.parse(p._runtimePayload()), 'gameplayRuntime'), false);
  assert.equal(p._validateRuntimeConfig(), '');
});

test('已有玩法配置的其余字段按原值回传，只更改作者所选策略', () => {
  const p = page();
  p.data.formData.journeyRules = initial.serialize({ hp: { enabled: true, init: 5, max: 5 } });
  const existing = { acts: [{ key: 'a' }], quests: [{ key: 'q' }], survival: { other: 7 } };
  p.data.formData.themeRuntimeConfig = JSON.stringify({ gameplayRuntime: existing });
  assert.deepEqual(JSON.parse(p._runtimePayload()).gameplayRuntime, existing);
  p.openZeroHpEditor(); pickHp(p, 'WAIT'); assert.equal(p.saveZeroHpEditor(), true);
  assert.deepEqual(JSON.parse(p._runtimePayload()).gameplayRuntime, { ...existing, survival: { other: 7, zeroHpPolicy: 'WAIT', zeroWait: { version: 1, minutes: 30, restoreTo: 1, limit: 1, thenPolicy: 'THEME_FAIL' } } });
  p.openZeroHpEditor(); pickHp(p, 'BLOCK_HP_COST'); assert.equal(p.saveZeroHpEditor(), true);
  assert.deepEqual(JSON.parse(p._runtimePayload()).gameplayRuntime, { ...existing, survival: { other: 7, zeroHpPolicy: 'BLOCK_HP_COST' } });
});

test('归零挑战只引用本主题已保存的数字节点，并保留原玩法与生存字段', () => {
  const p = page();
  p.data.formData.journeyRules = initial.serialize({ hp: { enabled: true, init: 5, max: 5 }, luck: { enabled: false, init: 0, max: 0 } });
  p.data.formData.themeRuntimeConfig = JSON.stringify({ gameplayRuntime: { quests: [{ key: 'keep' }], survival: { other: 7 } } });
  const before = p.data.formData.themeRuntimeConfig;
  p.openZeroHpEditor(); pickHp(p, 'CHALLENGE'); assert.equal(p.saveZeroHpEditor(), false);
  assert.match(p.data.runtimeZeroEditor.error, /先保存主题和挑战节点/);
  assert.equal(p.data.formData.themeRuntimeConfig, before);
  p.closeZeroHpEditor();
  p.data.formData.chapters[0].nodes = [
    { id: 101, name: '主挑战', templateInfo: { advancedConfigJson: JSON.stringify({ ballShake: { enabled: true } }) } },
    { id: 102, name: '备用题目', templateInfo: { advancedConfigJson: JSON.stringify({ qa: { enabled: true, mode: 'TYPE', title: '备用题', answerText: '口令', maxTries: 0 } }) } },
    { _localId: 'local', name: '未保存节点' },
  ];
  p.openZeroHpEditor(); pickHp(p, 'CHALLENGE'); p.pickZeroHpChallengeNode(hpEvent({}, 0));
  assert.deepEqual(p.data.runtimeChallengeNodes.map(row => row.id), [101, 102]);
  assert.equal(p.saveZeroHpEditor(), false); assert.match(p.data.runtimeZeroEditor.error, /节点 101.*客观题备选/);
  p.toggleZeroHpAlternative(hpEvent({ id: 102 }));
  p.inputZeroHpField(hpEvent({ path: 'challenge.restoreTo' }, '4'));
  p.inputZeroHpField(hpEvent({ path: 'challenge.limit' }, '2'));
  assert.equal(p.saveZeroHpEditor(), true); assert.equal(p._validateRuntimeConfig(), '');
  const saved = JSON.parse(p._runtimePayload());
  assert.deepEqual(saved.gameplayRuntime.quests, [{ key: 'keep' }]);
  assert.equal(saved.gameplayRuntime.survival.other, 7);
  assert.deepEqual(saved.gameplayRuntime.survival.zeroChallenge, { version: 2, gameplayKey: 'ballShake', challengeNodeId: 101, alternativeNodeIds: [102], requiredCompletions: 1, withinSeconds: 60, restoreTo: 4, limit: 2, thenPolicy: 'THEME_FAIL' });
  p.openZeroHpEditor(); p.toggleZeroHpAlternative(hpEvent({ id: 'local' }));
  assert.deepEqual(p.data.runtimeZeroEditor.draft.challenge.alternativeNodeIds, [102]);
  pickHp(p, 'BLOCK_HP_COST'); assert.equal(p.saveZeroHpEditor(), true);
  assert.equal(p._runtimeConfig().gameplayRuntime.survival.zeroChallenge, undefined);
  assert.equal(p._runtimeConfig().gameplayRuntime.survival.other, 7);
});

test('旧CHALLENGE无v2在新面板只读，所有编辑入口与完成均保留原JSON', () => {
  const p = page();
  const original = JSON.stringify({ gameplayRuntime: { survival: { other: 7, zeroHpPolicy: 'CHALLENGE', zeroChallenge: { challengeNodeId: 999, alternativeNodeIds: [], restoreTo: 4, limit: 2, opaque: [1] } } } });
  p.data.formData.themeRuntimeConfig = original; p.openZeroHpEditor();
  assert.equal(p.data.runtimeZeroEditor.readOnly, true); assert.match(p.data.runtimeZeroEditor.error, /节点 999/);
  pickHp(p, 'BLOCK_HP_COST'); p.pickZeroHpChallengeNode(hpEvent({}, 0)); p.toggleZeroHpAlternative(hpEvent({ id: 1 })); p.inputZeroHpField(hpEvent({ path: 'challenge.limit' }, '3'));
  assert.equal(p.saveZeroHpEditor(), true); assert.equal(p.data.formData.themeRuntimeConfig, original);
});

test('第一章设置有入口，初始状态完成保存、关闭丢弃', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/index.wxml'), 'utf8');
  assert.match(wxml, /popChapterAction == 1 && chapterNumbers\[popChapterIndex\] == 1[^>]+bindtap="openInitialState"/);
  global.Page = config => { global.pageConfig = config; };
  require('../../pages/publish/fabu/index.js');
  const config = global.pageConfig;
  const instance = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  instance.setData = function (patch) {
    Object.entries(patch).forEach(([key, value]) => {
      const parts = key.split('.'); let target = this.data;
      parts.slice(0, -1).forEach(part => { target = target[part]; });
      target[parts.at(-1)] = value;
    });
  };
  instance.openInitialState();
  instance.onInitialStateField(event('hp.init', '8'));
  instance.closeInitialState();
  assert.equal(instance.data.formData.journeyRules, '');
  instance.openInitialState();
  assert.equal(instance.data.initialStateEditor.rules.hp.init, 10);
  instance.addInitialState({ currentTarget: { dataset: { type: 'hp' } } });
  instance.onInitialStateField(event('hp.init', '8'));
  instance.saveInitialState();
  assert.equal(initial.parse(instance.data.formData.journeyRules).hp.init, 8);
  assert.equal(instance.data.initialStateEditor.show, false);
});

test('自定义标识与显示名无关，改名及范围回写保持稳定', () => {
  const p = page();
  p.openValueEditor();
  const key = p.data.valueEditor.row.key;
  assert.match(key, /^counter\.value_[a-z0-9]+_[a-z0-9]+$/);
  p.onValueField(event('label', '探索点'));
  p.saveValueEditor();
  let rules = initial.parse(p.data.formData.journeyRules);
  assert.equal(rules.attributes[0].key, key);
  p.openValueEditor({ currentTarget: { dataset: { index: 0 } } });
  p.onValueField(event('label', '发现数'));
  p.onValueField(event('min', '-10'));
  p.onValueField(event('max', '10'));
  p.saveValueEditor();
  rules = initial.parse(p.data.formData.journeyRules);
  assert.deepEqual([rules.attributes[0].key, rules.attributes[0].label, rules.attributes[0].min, rules.attributes[0].max], [key, '发现数', -10, 10]);
  assert.notEqual(values.nextKey(rules.attributes), key);
  assert.throws(() => values.attribute({ key, label: '越界', min: -10001, max: 1, initial: 0 }), /下限/);
  assert.throws(() => values.attribute({ key, label: '越界', min: 0, max: 10001, initial: 0 }), /上限/);
});

test('范围修改标出开局越界，第一章开局编辑可修复且关闭丢弃', () => {
  const p = page();
  p.data.formData.journeyRules = initial.serialize({ stateEnabled: true, hp: { enabled: false, init: 10, max: 10 }, luck: { enabled: false, init: 3, max: 3 }, attributes: [{ key: 'counter.value_1', label: '探索点', min: 0, max: 10, initial: 5, visible: true }] });
  p.openValueEditor({ currentTarget: { dataset: { index: 0 } } });
  p.onValueField(event('min', '6'));
  p.saveValueEditor();
  assert.equal(p.data.runtimeValues[0].invalidInitial, true);
  assert.match(p._validateRuntimeConfig(), /开局值/);
  global.Page = config => { global.pageConfig = config; };
  require('../../pages/publish/fabu/index.js');
  const config = global.pageConfig, instance = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  instance.setData = p.setData;
  instance.data.formData.journeyRules = p.data.formData.journeyRules;
  instance.openInitialState();
  assert.equal(instance.data.initialStateEditor.values[0].invalidInitial, true);
  instance.onInitialStateField({ currentTarget: { dataset: { field: 'attribute.initial', index: 0 } }, detail: { value: '7' } });
  assert.equal(instance.data.initialStateEditor.values[0].invalidInitial, false);
  instance.closeInitialState();
  assert.equal(initial.parse(instance.data.formData.journeyRules).attributes[0].initial, 5);
  instance.openInitialState();
  instance.onInitialStateField({ currentTarget: { dataset: { field: 'attribute.initial', index: 0 } }, detail: { value: '7' } });
  instance.saveInitialState();
  assert.equal(initial.parse(instance.data.formData.journeyRules).attributes[0].initial, 7);
});

test('数值关联按后端字段写入且只允许自定义值到已开启 HP', () => {
  const p = page();
  p.data.formData.journeyRules = initial.serialize({ stateEnabled: true, hp: { enabled: true, init: 10, max: 10 }, luck: { enabled: false, init: 3, max: 3 }, attributes: [{ key: 'counter.value_1', label: '探索点', min: 0, max: 10, initial: 5, visible: true }] });
  p.openValueEditor({ currentTarget: { dataset: { index: 0 } } });
  p.addValueLink();
  p.onValueLink({ currentTarget: { dataset: { index: 0, field: 'trigger' } }, detail: { value: 2 } });
  p.onValueLink({ currentTarget: { dataset: { index: 0, field: 'threshold' } }, detail: { value: '3' } });
  p.saveValueEditor();
  const link = initial.parse(p.data.formData.journeyRules).statLinks[0];
  assert.deepEqual(link, { id: 'link_1', sourceKey: 'counter.value_1', targetKey: 'sys.hp', trigger: 'FIRST_THRESHOLD', allowedReasons: ['TASK_COST'], frequency: 'RUN', amount: 1, threshold: 3 });
  assert.throws(() => values.links([{ ...link, targetKey: 'sys.luck' }], initial.parse(p.data.formData.journeyRules).attributes, true), /目标/);
  assert.throws(() => values.links([{ ...link, targetKey: 'sys.luck' }], initial.parse(p.data.formData.journeyRules).attributes, true, '体力'), /已启用的体力/);
  assert.throws(() => values.links([{ ...link, frequency: 'EVENT' }], initial.parse(p.data.formData.journeyRules).attributes, true), /本局一次/);
});

test('物品效果可选择已开启自定义值并以 statKey 保存', () => {
  const p = page();
  p.data.formData.journeyRules = initial.serialize({ stateEnabled: true, attributes: [{ key: 'counter.value_1', label: '探索点', min: 0, max: 10, initial: 5, visible: true }] });
  p.data.formData.themeRuntimeConfig = JSON.stringify({ items: [{ id: 'i1', kind: 'ITEM', name: '道具', image: 'https://example.com/item.png' }] });
  p.data.runtimeEditor.index = 0;
  p._refreshRuntimeEditor(p._runtimeConfig());
  p.onRuntimeItem({ currentTarget: { dataset: { index: 0, field: 'purpose' } }, detail: { value: 3 } });
  assert.deepEqual(JSON.parse(p._runtimePayload()).items[0], { id: 'i1', kind: 'ITEM', name: '道具', image: 'https://example.com/item.png', statKey: 'counter.value_1', delta: 1, wear: false });
  assert.equal(p.data.runtimeBag.items[0].statLabel, '探索点');
  assert.equal(p.data.runtimeBag.items[0].effectOptions.length, 3);
  assert.deepEqual(runtime.purposeOptions(p._runtimeConfig(), { hp: { enabled: false }, luck: { enabled: false }, stateEnabled: true, attributes: [{ key: 'counter.value_1', label: '探索点' }] }), [{ key: '', label: '展示' }, { key: 'counter.value_1', label: '探索点' }]);
});

test('删除或停用时列明章节、物品、关联、玩法绑定的引用', () => {
  const p = page(), key = 'counter.value_1';
  p.data.formData.journeyRules = initial.serialize({ stateEnabled: true, hp: { enabled: true, init: 10, max: 10 }, attributes: [{ key, label: '探索点', min: 0, max: 10, initial: 5, visible: true }], statLinks: [{ id: 'link_1', sourceKey: key, targetKey: 'sys.hp', trigger: 'PER_POINT', allowedReasons: ['TASK_COST'], frequency: 'EVENT', multiplier: 1 }] });
  p.data.formData.themeRuntimeConfig = JSON.stringify({ chapters: [{ index: 0, hpCost: 0, luckCost: 0, grants: [], eggs: [], statCosts: [{ statKey: key, amount: 1 }] }], items: [{ id: 'i1', kind: 'ITEM', name: '道具', image: 'https://example.com/item.png', statKey: key, delta: 1, wear: false }] });
  p.data.formData.chapters[0].nodes = [{ name: '节点甲', templateInfo: { advancedConfigJson: JSON.stringify({ qa: { changes: [{ statKey: key, delta: -1 }] } }) } }];
  p.onValueEnabled({ currentTarget: { dataset: { key } }, detail: { value: false } });
  assert.match(p.data.runtimeValueError, /第1章费用/);
  assert.match(p.data.runtimeValueError, /物品「道具」效果/);
  assert.match(p.data.runtimeValueError, /关联「link_1」/);
  assert.match(p.data.runtimeValueError, /第1章节点「节点甲」玩法绑定/);
  assert.equal(initial.parse(p.data.formData.journeyRules).attributes.length, 1);
});

test('章节默认完成规则不写入主题配置，值域页面不显示内部标识', () => {
  const p = page();
  p._openChapterRules(0);
  p.onChapterCompleteAll({ detail: { value: false } });
  p.onChapterQuantity({ detail: { value: '1' } });
  p._openChapterRules(0);
  assert.equal(p.data.runtimeChapterRules.completionType, 'ALL');
  assert.equal(p.data.formData.themeRuntimeConfig, '');
  assert.equal(p._saveChapterRules(), true);
  assert.equal(p.data.formData.themeRuntimeConfig, '');
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/index.wxml'), 'utf8');
  assert.match(wxml, /完成要求/);
  assert.match(wxml, /bindchange="onChapterCompleteAll"/);
  assert.match(wxml, /互斥选项/);
  // 2026-10-12 用户定:额外节点在故事流节点块上标,章节弹窗不再列「额外节点」,也不再单设数值消耗
  assert.match(wxml, /bind:change="onStoryNodeRole"/);
  assert.doesNotMatch(wxml, /addChapterExtraNode|onRuntimeChapterCost/);
  assert.doesNotMatch(wxml, /标识 \{\{valueEditor\.row\.key\}\}/);
  // 2026-10-28 原型 e-value:关联只剩一个开关,不再写说明
  assert.match(wxml, /下降时一起扣\{\{runtimeHpLabel\}\}<\/text><\/view><cy-switch checked="\{\{valueEditor\.links\.length > 0\}\}" bindchange="onValueLinkSwitch"/);
  assert.doesNotMatch(wxml, /仅支持此值下降时|定义在主题配置的状态键中管理/);
});

test('任选数量计入未保存节点，已有规则的章节恢复全部完成写显式 ALL', () => {
  const p = page();
  p.data.formData.chapters[0].nodes = [{ id: 101, name: '甲' }, { id: 102, name: '乙' }, { _localId: 'draft', name: '未保存' }];
  p._openChapterRules(0);
  p.onChapterCompleteAll({ detail: { value: false } });
  p.onChapterQuantity({ detail: { value: '4' } });
  assert.equal(p._saveChapterRules(), false);
  p.onChapterQuantity({ detail: { value: '3' } });
  assert.equal(p._saveChapterRules(), true);
  assert.deepEqual(JSON.parse(p._runtimePayload()).chapters[0].completion, { type: 'ANY_N', quantity: 3 });
  assert.equal(Object.hasOwn(JSON.parse(p._runtimePayload()), 'gameplayRuntime'), false);
  // 任选 3 时把一站标成额外节点:计入完成只剩 2 站(第 3 站未保存),标记被拒,原规则不变
  p.setData(p._storyRolePatch());
  p.onStoryNodeRole(roleEvent(p, 101, '__own'));
  assert.equal(p._runtimeConfig().chapters[0].extraNodes, undefined);
  p._openChapterRules(0);
  p.onChapterQuantity({ detail: { value: '1' } });
  assert.equal(p._saveChapterRules(), true);
  p.onStoryNodeRole(roleEvent(p, 101, '__own'));
  assert.deepEqual(p._runtimeConfig().chapters[0].extraNodes, [{ nodeId: 101, slot: 'extra_101' }]);
  p._openChapterRules(0);
  p.onChapterCompleteAll({ detail: { value: true } });
  assert.equal(p._saveChapterRules(), true);
  assert.deepEqual(p._runtimeConfig().chapters[0].completion, { type: 'ALL' });
});

test('新主题可以按草稿节点设置任选数量，但节点引用仍须保存', () => {
  const p = page();
  p.data.editingTopicId = '';
  p.data.formData.chapters[0].nodes = [{ _localId: 'a' }, { _localId: 'b' }];
  assert.equal(p._chapterRuleIssue(0, { completion: { type: 'ANY_N', quantity: 2 } }), '');
  assert.match(p._chapterRuleIssue(0, { completion: { type: 'ANY_N', quantity: 3 } }), /任选数量/);
  assert.match(p._chapterRuleIssue(0, { exclusiveGroups: [[101, 102]] }), /须先保存/);
  assert.match(p._chapterRuleIssue(0, { extraNodes: [{ nodeId: 101, slot: 'shared' }] }), /须先保存/);
});

test('超出后补做一站:可选不允许/单独额度/与既有额度共用，只随至少完成 N 站写入，开回全部完成即清除', () => {
  const p = page();
  p.data.formData.chapters = [
    { nodes: [{ id: 101, name: '甲' }, { id: 102, name: '乙' }] },
    { nodes: [{ id: 201, name: '丙' }, { id: 202, name: '丁' }] },
  ];
  const pick = value => p.onChapterOverflowPick({ detail: { value: p.data.runtimeOverflowOptions.findIndex(o => o.value === value) } });
  p._openChapterRules(0);
  p.onChapterCompleteAll({ detail: { value: false } });
  p.onChapterQuantity({ detail: { value: '1' } });
  assert.deepEqual(p.data.runtimeOverflowOptions.map(o => o.value), ['', '__own']);
  pick('__own');
  assert.equal(p.data.runtimeChapterRules.overflowSlot, 'overflow_1');
  assert.equal(p._saveChapterRules(), true);
  assert.deepEqual(p._runtimeConfig().chapters[0].completion, { type: 'ANY_N', quantity: 1, overflowSlot: 'overflow_1' });
  // 第二章选择与第一章共用同一份额度
  p._openChapterRules(1);
  p.onChapterCompleteAll({ detail: { value: false } });
  p.onChapterQuantity({ detail: { value: '1' } });
  assert.match(p.data.runtimeOverflowOptions[2].label, /第1章补做/);
  pick('overflow_1');
  assert.equal(p._saveChapterRules(), true);
  assert.deepEqual(p._runtimeConfig().chapters.map(ch => ch.completion.overflowSlot), ['overflow_1', 'overflow_1']);
  // 全部完成开回去:补做随之清除
  p._openChapterRules(0);
  p.onChapterCompleteAll({ detail: { value: true } });
  assert.equal(p.data.runtimeChapterRules.overflowSlot, '');
  assert.equal(p._saveChapterRules(), true);
  assert.deepEqual(p._runtimeConfig().chapters[0].completion, { type: 'ALL' });
  assert.equal(Object.hasOwn(JSON.parse(p._runtimePayload()), 'gameplayRuntime'), false);
});

test('已有规则的章节整体恢复默认时保留显式 ALL，清空互斥与额外节点', () => {
  // 已发布编译过的 ANY_N/互斥/额外标记要靠显式 ALL 清除；直接删字段服务端分不清“作者删除”与历史手写配置
  const p = page();
  p.data.formData.chapters[0].nodes = [{ id: 101, name: '甲' }, { id: 102, name: '乙' }, { id: 103, name: '丙' }];
  p._openChapterRules(0);
  p.onChapterCompleteAll({ detail: { value: false } });
  p.onChapterQuantity({ detail: { value: '1' } });
  p.addChapterExclusiveGroup();
  p.toggleChapterExclusiveNode({ currentTarget: { dataset: { index: 0, id: 101 } } });
  p.toggleChapterExclusiveNode({ currentTarget: { dataset: { index: 0, id: 102 } } });
  assert.equal(p._saveChapterRules(), true);
  p._openChapterRules(0);
  p.onChapterCompleteAll({ detail: { value: true } });
  p.removeChapterExclusiveGroup({ currentTarget: { dataset: { index: 0 } } });
  assert.equal(p._saveChapterRules(), true);
  const row = p._runtimeConfig().chapters[0];
  assert.deepEqual(row.completion, { type: 'ALL' });
  assert.equal(Object.hasOwn(row, 'exclusiveGroups'), false);
  assert.equal(Object.hasOwn(row, 'extraNodes'), false);
  assert.equal(Object.hasOwn(JSON.parse(p._runtimePayload()), 'gameplayRuntime'), false);
});

test('互斥组须两站且不可跨组、跨章或与额外节点重叠', () => {
  const p = page();
  p.data.formData.chapters = [
    { nodes: [{ id: 101, name: '甲' }, { id: 102, name: '乙' }, { id: 103, name: '丙' }] },
    { nodes: [{ id: 201, name: '别章' }] },
  ];
  p._openChapterRules(0);
  p.addChapterExclusiveGroup();
  p.toggleChapterExclusiveNode({ currentTarget: { dataset: { index: 0, id: 101 } } });
  assert.equal(p._saveChapterRules(), false);
  p.toggleChapterExclusiveNode({ currentTarget: { dataset: { index: 0, id: 102 } } });
  assert.equal(p._saveChapterRules(), true);
  assert.deepEqual(p._runtimeConfig().chapters[0].exclusiveGroups, [[101, 102]]);
  p.onChapterCompleteAll({ detail: { value: false } });
  p.onChapterQuantity({ detail: { value: '3' } });
  assert.equal(p._saveChapterRules(), false);
  p.onChapterCompleteAll({ detail: { value: true } });
  p.addChapterExclusiveGroup();
  p.toggleChapterExclusiveNode({ currentTarget: { dataset: { index: 1, id: 101 } } });
  assert.deepEqual(p.data.runtimeChapterRules.groups[1].ids, []);
  p.data.runtimeChapterRules.groups[1].ids = [102, 103];
  assert.equal(p._saveChapterRules(), false);
  p.data.runtimeChapterRules.groups[1].ids = [201, 103];
  assert.equal(p._saveChapterRules(), false);
  p.removeChapterExclusiveGroup({ currentTarget: { dataset: { index: 1 } } });
  // 互斥组里的节点不能再在故事流里标成额外节点
  p.setData(p._storyRolePatch());
  p.onStoryNodeRole(roleEvent(p, 101, '__own'));
  assert.equal(p._runtimeConfig().chapters[0].extraNodes, undefined);
});

test('故事流额度:两站共用一份、另一站单独一份，不写参数即可配置；跨章共用，插入开篇与删章后跟随原章', () => {
  const p = page();
  p.data.formData.chapters = [
    // 每章另有计入完成的节点：额外节点不能占满本章（A08 计数规则）
    { nodes: [{ id: 101, name: '甲' }, { id: 102, name: '甲二' }, { id: 103, name: '甲三' }] },
    { nodes: [{ id: 201, name: '乙' }, { id: 202, name: '乙二' }] },
  ];
  p.data.formData.themeRuntimeConfig = JSON.stringify({ chapters: [], gameplayRuntime: { runtimeScope: 'TEAM', worldMode: 'TEAM_INDEPENDENT' } });
  p.setData(p._storyRolePatch());
  p.onStoryNodeRole(roleEvent(p, 101, '__own'));
  p.onStoryNodeRole(roleEvent(p, 102, '__own'));
  const slots = () => p._runtimeConfig().chapters.flatMap(ch => (ch.extraNodes || []).map(x => [x.nodeId, x.slot]));
  assert.deepEqual(slots(), [[101, 'extra_101'], [102, 'extra_102']], '两站各自单独一份');
  // 乙 选择与「甲」共用
  p.onStoryNodeRole(roleEvent(p, 201, 'extra_101'));
  assert.deepEqual(slots(), [[101, 'extra_101'], [102, 'extra_102'], [201, 'extra_101']]);
  assert.match(p.data.storyNodeRoles[101], /与1处共用（每队共一次）/);
  assert.match(p.data.storyNodeRoles[102], /额外 · 每队一次/);
  // 共用中的节点改选「单独额度」:新开一份,不拖走另一站
  p.onStoryNodeRole(roleEvent(p, 201, '__own'));
  assert.deepEqual(slots(), [[101, 'extra_101'], [102, 'extra_102'], [201, 'extra_201']]);
  p.onStoryNodeRole(roleEvent(p, 201, 'extra_101'));
  p._prependRuntimeChapter();
  assert.deepEqual(p._runtimeConfig().chapters.filter(r => r.extraNodes).map(row => [row.index, row.extraNodes[0].slot]), [[1, 'extra_101'], [2, 'extra_101']]);
});

test('存量共用额度(既有槽名)原样保留：重选同一份不改名，回到计入完成写显式 ALL；未保存节点不能标', () => {
  const p = page();
  p.data.formData.chapters[0].nodes = [{ id: 101, name: '甲' }, { id: 102, name: '乙' }, { id: 103, name: '丙' }, { _localId: 'draft', name: '未保存' }];
  p.data.formData.themeRuntimeConfig = JSON.stringify({ chapters: [{ index: 0, hpCost: 0, luckCost: 0, grants: [], eggs: [], extraNodes: [{ nodeId: 101, slot: 'mobile' }, { nodeId: 102, slot: 'mobile' }] }] });
  p.setData(p._storyRolePatch());
  assert.equal(p.data.storyRoleOptions.find(o => o.value === 'mobile').label.includes('甲、乙'), true);
  // 单独额度对共用中的节点:新开,不把共用槽改名
  p.onStoryNodeRole(roleEvent(p, 102, 'mobile'));
  assert.deepEqual(p._runtimeConfig().chapters[0].extraNodes, [{ nodeId: 101, slot: 'mobile' }, { nodeId: 102, slot: 'mobile' }]);
  p.onStoryNodeRole(roleEvent(p, 0, ''));
  p.onStoryNodeRole(roleEvent(p, 101, ''));
  p.onStoryNodeRole(roleEvent(p, 102, ''));
  const row = p._runtimeConfig().chapters[0];
  assert.equal(Object.hasOwn(row, 'extraNodes'), false);
  assert.deepEqual(row.completion, { type: 'ALL' });
  assert.deepEqual(p.data.storyNodeRoles, {});
});

test('章末消耗：存量生命/自定义数值消耗在章节弹窗可见可改可删，停用数值标出；保存写回 hpCost/statCosts', () => {
  const p = page(), key = 'counter.value_1';
  p.data.formData.journeyRules = initial.serialize({ stateEnabled: true, hp: { enabled: true, init: 10, max: 10 }, luck: { enabled: false, init: 3, max: 3 }, attributes: [{ key, label: '温度', min: 0, max: 10, initial: 5, visible: true }] });
  p.data.formData.themeRuntimeConfig = JSON.stringify({ chapters: [{ index: 0, hpCost: 2, luckCost: 1, grants: [], eggs: [], statCosts: [{ statKey: key, amount: 3 }] }] });
  p._openChapterRules(0);
  assert.deepEqual(p.data.runtimeChapterCostRows.map(r => [r.key, r.amount, r.inactive]), [['HP', '2', false], ['LUCK', '1', true], [key, '3', false]]);
  p.onChapterCostAmount({ currentTarget: { dataset: { index: 0 } }, detail: { value: '4' } });
  p.removeChapterCost({ currentTarget: { dataset: { index: 1 } } });
  assert.equal(p._saveChapterRules(), true);
  let row = p._runtimeConfig().chapters[0];
  assert.deepEqual([row.hpCost, row.luckCost, row.statCosts], [4, 0, [{ statKey: key, amount: 3 }]]);
  p._openChapterRules(0);
  p.removeChapterCost({ currentTarget: { dataset: { index: 1 } } });
  p.onChapterCostAmount({ currentTarget: { dataset: { index: 0 } }, detail: { value: '1000' } });
  assert.equal(p._saveChapterRules(), false, '超出 0-999 不保存');
  p.onChapterCostAmount({ currentTarget: { dataset: { index: 0 } }, detail: { value: '0' } });
  assert.equal(p._saveChapterRules(), true);
  row = p._runtimeConfig().chapters[0];
  assert.deepEqual([row.hpCost, row.luckCost, row.statCosts], [0, 0, undefined]);
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/index.wxml'), 'utf8');
  assert.match(wxml, /章末消耗/);
  assert.match(wxml, /bind:change="addChapterCost"/);
});

test('额外节点不能占满本章全部节点（与后端“本章没有计数任务”一致）', () => {
  const p = page();
  p.data.formData.chapters[0].nodes = [{ id: 101, name: '甲' }];
  // 唯一的节点不能标成额外节点
  p.setData(p._storyRolePatch());
  p.onStoryNodeRole(roleEvent(p, 101, '__own'));
  assert.equal(p.data.formData.themeRuntimeConfig, '');
  const issue = p._chapterRuleIssue(0, { completion: null, exclusiveGroups: [], extraNodes: [{ nodeId: 101, slot: 'mobile' }] });
  assert.match(issue, /额外节点不能占满本章全部节点/);
  p.data.formData.chapters[0].nodes.push({ id: 102, name: '乙' });
  assert.equal(p._chapterRuleIssue(0, { completion: null, exclusiveGroups: [], extraNodes: [{ nodeId: 101, slot: 'mobile' }] }), '');
});

test('章末配给：写入既有 survival.rations（本章 actKey），每章一行，可关闭；无节点/无物品时给出原因', () => {
  const p = page();
  p._openChapterRules(0);
  assert.equal(p.data.runtimeChapterRation.reason, '请先选择进行方式');
  p.onChapterRationToggle({ detail: { value: true } });
  assert.equal(p.data.runtimeChapterRation.enabled, false);
  const config = { schemaVersion: 1, capacity: 14, hpName: '保温储备',
    items: [{ id: 'ration', kind: 'ITEM', name: '口粮', image: 'https://img.example/r.png', slotCost: 1 },
      { id: 'fuel', kind: 'ITEM', name: '燃料', image: 'https://img.example/f.png', slotCost: 2 }],
    chapters: [],
    gameplayRuntime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED', survival: { zeroHpPolicy: 'BLOCK_HP_COST', actionLimitPolicy: 'BLOCK_OVERFLOW' },
      acts: [{ actKey: 'chapter_11', questKeys: ['node_101'], completionRule: { type: 'ALL' } }, { actKey: 'chapter_12', questKeys: ['node_201'], completionRule: { type: 'ALL' } }],
      quests: [{ questKey: 'node_101', actKey: 'chapter_11', nodeId: 101, locationKey: 'node_101', settlementSlot: 'node_101' },
        { questKey: 'node_201', actKey: 'chapter_12', nodeId: 201, locationKey: 'node_201', settlementSlot: 'node_201' }] } };
  p.data.formData.themeRuntimeConfig = JSON.stringify(config);
  p.data.formData.chapters = [{ id: 11, nodes: [{ id: 101, name: '档案' }] }, { id: 12, nodes: [{ id: 201, name: '证据板' }] }];
  p._openChapterRules(0);
  assert.equal(p.data.runtimeChapterRation.reason, '');
  assert.equal(p.data.runtimeChapterRation.actKey, 'chapter_11');
  assert.deepEqual(p.data.runtimeChapterRation.items.map(i => i.name), ['口粮', '燃料']);
  p.onChapterRationToggle({ detail: { value: true } });
  assert.equal(p._saveChapterRules(), false, '未选物品不能保存');
  p.onChapterRationItem({ detail: { value: 0 } });
  p.onChapterRationQuantity({ detail: { value: '1' } });
  p.onChapterRationFallback({ detail: { value: '2' } });
  assert.equal(p._saveChapterRules(), true);
  let saved = JSON.parse(p.data.formData.themeRuntimeConfig);
  assert.deepEqual(saved.gameplayRuntime.survival.rations, [{ rationKey: 'ration_1', actKey: 'chapter_11', itemId: 'ration', quantity: 1, fallbackHpCost: 2 }]);
  p._openChapterRules(1);
  assert.equal(p.data.runtimeChapterRation.enabled, false, '其他章节不继承');
  p.onChapterRationToggle({ detail: { value: true } }); p.onChapterRationItem({ detail: { value: 0 } }); p.onChapterRationFallback({ detail: { value: '2' } });
  assert.equal(p._saveChapterRules(), true);
  saved = JSON.parse(p.data.formData.themeRuntimeConfig);
  assert.deepEqual(saved.gameplayRuntime.survival.rations.map(r => [r.rationKey, r.actKey]), [['ration_1', 'chapter_11'], ['ration_2', 'chapter_12']]);
  p._openChapterRules(0);
  assert.equal(p.data.runtimeChapterRation.enabled, true); assert.equal(p.data.runtimeChapterRation.itemLabel, '口粮');
  assert.equal(p.data.runtimeChapterRation.fallback, '2');
  p.onChapterRationQuantity({ detail: { value: '0' } });
  assert.equal(p._saveChapterRules(), false);
  p.onChapterRationQuantity({ detail: { value: '1' } });
  p.onChapterRationToggle({ detail: { value: false } });
  assert.equal(p._saveChapterRules(), true);
  saved = JSON.parse(p.data.formData.themeRuntimeConfig);
  assert.deepEqual(saved.gameplayRuntime.survival.rations.map(r => r.actKey), ['chapter_12'], '关闭只移除本章');
  assert.equal(runtime.validate(saved, initial.parse('{"schemaVersion":1,"stateEnabled":true,"hp":{"enabled":true,"init":8,"max":12}}'), ['BLOCK_HP_COST']), '');
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/index.wxml'), 'utf8');
  const settings = wxml.slice(wxml.indexOf('pop-chapter-settings'));
  assert.match(settings, /章末配给<\/text>[\s\S]*?bindchange="onChapterRationToggle"/);
  assert.match(settings, /bind:change="onChapterRationItem"/);
  assert.match(settings, /bind:change="onChapterRationValue"[^>]*>\s*<view class="ch-pill-row"><text>物品不够时减少<\/text>/);
});

test('章末配给不足时可扣自定义数值（温度），扣 0 不要求生命；旧 fallbackHpCost 按生命回显', () => {
  const p = page();
  p.data.formData.journeyRules = JSON.stringify({ schemaVersion: 1, stateEnabled: true, hp: { enabled: false, init: 10, max: 10 }, luck: { enabled: false, init: 3, max: 3 },
    attributes: [{ key: 'counter.temp', label: '体温', initial: 8, min: 0, max: 12, visible: true }] });
  p.data.formData.themeRuntimeConfig = JSON.stringify({ schemaVersion: 1, capacity: 14, items: [{ id: 'ration', kind: 'ITEM', name: '口粮', image: 'https://img.example/r.png' }], chapters: [],
    gameplayRuntime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED', survival: { zeroHpPolicy: 'BLOCK_HP_COST', actionLimitPolicy: 'BLOCK_OVERFLOW' },
      acts: [{ actKey: 'chapter_11', questKeys: ['node_101'], completionRule: { type: 'ALL' } }], quests: [{ questKey: 'node_101', actKey: 'chapter_11', nodeId: 101, locationKey: 'node_101', settlementSlot: 'node_101' }] } });
  p.data.formData.chapters = [{ id: 11, nodes: [{ id: 101, name: '档案' }] }];
  p._openChapterRules(0);
  assert.deepEqual(p.data.runtimeChapterRation.values.map(v => v.label), ['体温']);
  p.onChapterRationToggle({ detail: { value: true } }); p.onChapterRationItem({ detail: { value: 0 } });
  p.onChapterRationValue({ detail: { value: 0 } }); p.onChapterRationFallback({ detail: { value: '2' } });
  assert.equal(p._saveChapterRules(), true);
  const saved = JSON.parse(p.data.formData.themeRuntimeConfig).gameplayRuntime.survival.rations[0];
  assert.deepEqual(saved.fallback, { statKey: 'counter.temp', amount: 2 }); assert.equal(saved.fallbackHpCost, undefined);
  assert.equal(runtime.validate(JSON.parse(p.data.formData.themeRuntimeConfig), initial.parse(p.data.formData.journeyRules), ['BLOCK_HP_COST']), '', '生命未开启也能扣自定义数值');
  p._openChapterRules(0);
  assert.equal(p.data.runtimeChapterRation.fallback, '2'); assert.equal(p.data.runtimeChapterRation.values[p.data.runtimeChapterRation.valueIndex].key, 'counter.temp');
});
