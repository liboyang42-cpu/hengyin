'use strict';

const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const binding = require('../../pages/publish/utils/publish/template-binding.js');

const slots = [
  { key: 'energy', kind: 'VALUE', label: '消耗值域', required: true },
  { key: 'supply', kind: 'ITEM', label: '所需物品', required: true },
];
const template = (id, declared = slots) => ({ id, title: '模板', advancedConfigJson: JSON.stringify({
  interactionRules: { resourceSlots: declared, statCosts: [{ statKey: 'energy', amount: 2 }],
    resourceCosts: [{ itemId: 'supply', amount: 1 }] },
}) });
const rules = JSON.stringify({ stateEnabled: true, hp: { enabled: true }, luck: { enabled: true },
  attributes: [{ key: 'counter.focus', label: '专注' }] });
const runtime = JSON.stringify({ hpName: '体力', luckName: '运势', actionName: '步数', actionLimit: 5,
  items: [{ id: 'tool_a', kind: 'ITEM', name: '工具甲' }, { id: 'tool_b', kind: 'ITEM', name: '工具乙' }] });

test('Java TemplateResourceSlots 资源槽 fixture 的摘要一致且顺序无关', () => {
  // Fixture 来自 GameplayTemplateResourceSlotsTest.rule；期望值由 Java MessageDigest 算得。
  assert.equal(binding.slotDigest(slots), 'c908321600affc62d1b2003d6a660034cf771f761238ce65cbf3e1a7ff460c93');
  assert.equal(binding.slotDigest(slots.slice().reverse()), binding.slotDigest(slots));
});

test('资源行仅列启用的主题值与符合费用用途的物资，使用作者命名', () => {
  const initial = binding.reconcile(slots, 8, null, [], rules, runtime, template(8));
  const rows = binding.rows(slots, initial, rules, runtime, template(8));
  assert.deepEqual(rows[0].options, [
    { value: 'sys.hp', label: '体力' }, { value: 'sys.luck', label: '运势' },
    { value: 'counter.focus', label: '专注' }, { value: 'sys.actions', label: '步数' },
  ]);
  assert.deepEqual(rows[1].options.map(row => row.value), ['tool_a', 'tool_b']);
  assert.deepEqual(binding.optionsFor('VALUE', { stateEnabled: false, hp: { enabled: true } }, {}), []);
});

test('物资候选遵守后端费用用途：投票材料排除穿戴，纯发放可选勋章', () => {
  const items = { items: [{ id: 'wear', kind: 'ITEM', name: '可穿戴', wear: true },
    { id: 'plain', kind: 'ITEM', name: '普通物资' }, { id: 'medal', kind: 'MEDAL', name: '勋章' }] };
  const vote = { advancedConfigJson: JSON.stringify({ interactionTools: { plans: [{ materialCost: [{ itemId: 'supply', amount: 1 }] }] } }) };
  const grant = { advancedConfigJson: JSON.stringify({ interactionTools: { grants: [{ itemId: 'award', quantity: 1 }] } }) };
  assert.deepEqual(binding.optionsFor('ITEM', rules, items, vote, 'supply').map(row => row.value), ['plain']);
  assert.deepEqual(binding.optionsFor('ITEM', rules, items, grant, 'award').map(row => row.value), ['wear', 'plain', 'medal']);
});

test('绑定使用真实节点 ID 或稳定 clientNodeKey；两个槽不能指向同一资源', () => {
  assert.equal(binding.nodeKey({ id: 77, clientNodeKey: 'old' }), '77');
  assert.equal(binding.nodeKey({ clientNodeKey: 'draft_node', _localId: 'other' }), 'client:draft_node');
  assert.equal(binding.nodeKey({ _localId: 'local_node' }), 'client:local_node');
  const two = [{ key: 'first', kind: 'VALUE', label: '甲', required: true }, { key: 'second', kind: 'VALUE', label: '乙', required: true }];
  let draft = binding.reconcile(two, 9, null, [], rules, runtime);
  draft = binding.bind(draft, two, 'first', 'sys.hp', rules, runtime);
  assert.throws(() => binding.bind(draft, two, 'second', 'sys.hp', rules, runtime), /不能对应同一/);
});

test('换模板只保留同 key 同 kind 的有效目标，旧槽与已禁用资源清掉', () => {
  const oldSlots = [slots[0], slots[1], { key: 'old', kind: 'VALUE', label: '旧', required: true }];
  const old = { templateId: 8, values: { energy: 'sys.hp', old: 'sys.luck' }, items: { supply: 'tool_a' }, slotDigest: 'old' };
  const next = binding.reconcile(slots, 9, old, oldSlots, rules, runtime, template(9));
  assert.deepEqual(next.values, { energy: 'sys.hp' });
  assert.deepEqual(next.items, { supply: 'tool_a' });
  assert.equal(next.templateId, 9);
  const changedKind = binding.reconcile([{ key: 'energy', kind: 'ITEM', label: '另一个', required: true }], 10,
    old, oldSlots, rules, runtime);
  assert.deepEqual(changedKind.items, {});
  assert.deepEqual(binding.reconcile(slots, 9, old, oldSlots,
    JSON.stringify({ stateEnabled: false }), runtime, template(9)).values, {});
});

test('发布检查能定位缺槽的节点，旧模板没有资源槽则不改写', () => {
  const node = { _localId: 'n1', name: '第一站', templateId: 8, templateInfo: template(8) };
  const chapters = [{ nodes: [node] }];
  assert.deepEqual(binding.issues(chapters, runtime, null, rules),
    [{ key: 'client:n1', label: '第一站：资源未绑定' }]);
  const complete = { templateId: 8, values: { energy: 'sys.hp' }, items: { supply: 'tool_a' }, slotDigest: binding.slotDigest(slots) };
  const withBinding = JSON.stringify(Object.assign(JSON.parse(runtime), { templateBindings: { 'client:n1': complete } }));
  assert.deepEqual(binding.issues(chapters, withBinding, null, rules), []);
  assert.equal(binding.issues(chapters, withBinding, null, JSON.stringify({ stateEnabled: false })).length, 1);
  assert.deepEqual(binding.issues([{ nodes: [{ ...node, templateInfo: { advancedConfigJson: '{}' } }] }], runtime, null, rules), []);
});

let config;
global.getApp = () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 }, getUserID: () => 42,
  getRequestErrorMessage: (_, fallback) => fallback, sendRequest: () => {} });
global.wx = { getStorageSync: () => null, removeStorageSync: () => {}, setStorageSync: () => {},
  showToast: () => {}, showLoading: () => {}, hideLoading: () => {}, pageScrollTo: () => {}, nextTick: f => f() };
global.Page = value => { config = value; };

beforeEach(() => { delete require.cache[require.resolve('../../pages/publish/fabu/index.js')]; require('../../pages/publish/fabu/index.js'); });
function page() {
  const p = Object.assign({}, config);
  p.data = JSON.parse(JSON.stringify(config.data));
  p.setData = (patch, done) => {
    Object.keys(patch).forEach(key => {
      const parts = key.split('.'); let target = p.data;
      for (let i = 0; i < parts.length - 1; i++) target = target[parts[i]];
      target[parts[parts.length - 1]] = patch[key];
    });
    if (done) done();
  };
  p.data.formData.journeyRules = rules;
  p.data.formData.themeRuntimeConfig = runtime;
  p.data.nodesForm = { name: '', templateId: 0, templateInfo: {} };
  return p;
}

test('节点抽屉确认后写入精确 JSON，移除模板会清理绑定，取消未提交', () => {
  const p = page(); const node = { _localId: 'n1', templateId: 8, name: '第一站' };
  p._bindNodeTemplate(template(8));
  p.onNodeBindingPick({ currentTarget: { dataset: { index: 0 } }, detail: { value: 0 } });
  p.onNodeBindingPick({ currentTarget: { dataset: { index: 1 } }, detail: { value: 0 } });
  assert.equal(JSON.parse(p.data.formData.themeRuntimeConfig).templateBindings, undefined, '抽屉未确认不得写入');
  p._commitNodeTemplateBinding(node);
  assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).templateBindings, {
    'client:n1': { templateId: 8, values: { energy: 'sys.hp' }, items: { supply: 'tool_a' },
      slotDigest: binding.slotDigest(slots) },
  });
  assert.deepEqual(JSON.parse(p._runtimePayload()).templateBindings,
    JSON.parse(p.data.formData.themeRuntimeConfig).templateBindings);
  p.onToggleNodeGame({ detail: { value: false } });
  p._commitNodeTemplateBinding({ ...node, templateId: 0 });
  assert.equal(JSON.parse(p.data.formData.themeRuntimeConfig).templateBindings, undefined);
});

test('节点卡直接移除玩法时同步清理资源绑定', () => {
  const p = page();
  const node = { id: 77, _localId: 'n1', templateId: 8, templateInfo: template(8) };
  p.data.formData.chapters = [{ nodes: [node] }];
  p.data.formData.themeRuntimeConfig = JSON.stringify(Object.assign(JSON.parse(runtime), {
    templateBindings: { '77': { templateId: 8, values: {}, items: {}, slotDigest: binding.slotDigest(slots) } },
  }));
  p._pushUndo = () => {};
  p.updateAllStatistics = () => {};
  p.buildRouteMap = () => {};
  p.removeNodeGame({ currentTarget: { dataset: { chapterindex: 0, nodeindex: 0 } } });
  assert.equal(p.data.formData.chapters[0].nodes[0].templateId, 0);
  assert.equal(JSON.parse(p.data.formData.themeRuntimeConfig).templateBindings, undefined);
});

test('没有资源槽的旧模板确认时不改写原主题配置文本', () => {
  const p = page();
  const raw = '{ "schemaVersion": 1, "capacity": 8 }';
  p.data.formData.themeRuntimeConfig = raw;
  p._bindNodeTemplate({ id: 11, advancedConfigJson: '{}' });
  p._commitNodeTemplateBinding({ id: 42, templateId: 11 });
  assert.equal(p.data.formData.themeRuntimeConfig, raw);
});

test('已保存节点用真实 ID，草稿允许不完整绑定，发布检查定位缺槽节点', () => {
  const p = page();
  const node = { id: 77, _localId: 'local', name: '第七站', templateId: 8, templateInfo: template(8) };
  p.data.formData.chapters = [{ _localId: 'chapter', nodes: [node] }];
  p._bindNodeTemplate(node.templateInfo);
  p.onNodeBindingPick({ currentTarget: { dataset: { index: 0 } }, detail: { value: 0 } });
  p._commitNodeTemplateBinding(node);
  const saved = JSON.parse(p.data.formData.themeRuntimeConfig).templateBindings;
  assert.deepEqual(Object.keys(saved), ['77']);
  assert.deepEqual(saved['77'].values, { energy: 'sys.hp' });
  assert.deepEqual(saved['77'].items, {}, '缺槽在草稿中保留为空对象');
  p._buildValidationBag = () => ({ errors: {} });
  p.computeCompleteness = () => ({ missing: [] });
  const issue = p.buildPublishCheck().blocking.find(row => row.field === 'templateBinding:77');
  assert.match(issue.label, /第七站：资源未绑定/);
  let opened;
  p.setEditorState = () => {};
  p.showEditNodes = e => { opened = e.currentTarget.dataset; };
  p.locatePublishIssue({ currentTarget: { dataset: { field: issue.field } } });
  assert.deepEqual(opened, { chapterindex: 0, nodeindex: 0 });
  assert.equal(p.data.editorPage, 1);
  p._removeNodeTemplateBinding(node);
  assert.equal(JSON.parse(p.data.formData.themeRuntimeConfig).templateBindings, undefined);
});

test('换模板时同号槽不继承旧对应：上一站 item_2=维修件 不会预填到新模板的 item_2=电源', () => {
  const binding = require('../../pages/publish/utils/publish/template-binding.js');
  const journey = JSON.stringify({ schemaVersion: 1, stateEnabled: true, hp: { enabled: true, init: 8, max: 12 } });
  const runtime = JSON.stringify({ schemaVersion: 1, capacity: 14, actionLimit: 8, items: [
    { id: 'ration', kind: 'ITEM', name: '口粮', image: 'x' }, { id: 'power', kind: 'ITEM', name: '电源', image: 'x' }, { id: 'parts', kind: 'ITEM', name: '维修件', image: 'x' }] });
  const slotsA = [{ key: 'item_1', kind: 'ITEM', label: '口粮' }, { key: 'item_2', kind: 'ITEM', label: '维修件' }];
  const slotsB = [{ key: 'item_1', kind: 'ITEM', label: '口粮' }, { key: 'item_2', kind: 'ITEM', label: '电源' }, { key: 'item_3', kind: 'ITEM', label: '维修件' }];
  const tpl = slots => ({ id: 9, advancedConfigJson: JSON.stringify({ schemaVersion: 1, interactionRules: { resourceSlots: slots.map(s => Object.assign({ required: true }, s)) } }) });
  const old = { templateId: 8, values: {}, items: { item_1: 'ration', item_2: 'parts' } };
  const next = binding.reconcile(slotsB, 9, old, slotsA, journey, runtime, tpl(slotsB));
  assert.equal(next.items.item_1, 'ration', '同号同类同名的对应保留');
  assert.equal(next.items.item_2, undefined, '同号不同名不继承');
  // 同一模板（或副本）重新选择时全部保留
  const same = binding.reconcile(slotsA, 10, old, slotsA, journey, runtime, tpl(slotsA));
  assert.deepEqual(same.items, { item_1: 'ration', item_2: 'parts' });
});
