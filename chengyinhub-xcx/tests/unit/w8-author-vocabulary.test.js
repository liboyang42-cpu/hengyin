const test = require('node:test');
const assert = require('node:assert/strict');
const route = require('../../pages/publish/utils/publish/topic-route-graph');
const nodes = [{ id: '11' }, { id: '12' }];
const condition = { op: 'AND', conditions: [
  { op: 'OR', conditions: [{ op: 'ROLE_IN', values: ['NAVIGATOR'] }, { op: 'HAS_CONDITION', value: 'inspired' }] },
  { op: 'NOT', condition: { op: 'CHECK_RESULT', nodeId: 11, result: 'FAIL' } },
  { op: 'VISIT_COUNT', nodeId: 11, value: 1 }, { op: 'ITEM_EQUIPPED', itemId: 'lamp' }
] };
const effects = [{ op: 'ADD_ITEM', itemId: 'lamp', quantity: 1 }, { op: 'GRANT_BADGE', value: 'explorer' },
  { op: 'GRANT_XP', value: 5, id: 'read' }, { op: 'ADD_CONDITION', value: 'inspired' },
  { op: 'REMOVE_CONDITION', value: 'tired' }, { op: 'EQUIP', itemId: 'lamp', slot: 'hand' },
  { op: 'UNEQUIP', itemId: 'hat' }, { op: 'TICK_CLOCK', var: 'counter.time', value: 1, max: 4 }];
function graph(c = [condition], e = effects) {
  return { schemaVersion: 1, startNodeId: '11', terminalNodeIds: ['12'], variables: { 'counter.time': 0 },
    edges: [{ id: 'go', fromNodeId: '11', toNodeId: '12', trigger: { type: 'CHOICE', outcomeCode: 'GO' }, conditions: structuredClone(c), effects: structuredClone(e) }], fallbacks: [] };
}
test('N5条件/效果经作者读取、序列化不丢字段，合法配置通过校验', () => {
  const g = graph(); const out = JSON.parse(route.serialize(g));
  assert.deepEqual(out.edges[0].conditions, [condition]); assert.deepEqual(out.edges[0].effects, effects);
  assert.equal(route.validate(g, nodes).valid, true);
});
test('嵌套节点引用转clientNodeKey和复制均保留精确定位', () => {
  const g = graph(); const keyed = JSON.parse(route.serializeWithNodeKeys(g));
  assert.equal(keyed.edges[0].conditions[0].conditions[1].condition.nodeKey, '11');
  assert.equal(keyed.edges[0].conditions[0].conditions[2].nodeKey, '11');
  assert.equal(keyed.edges[0].conditions[0].conditions[2].nodeId, undefined);
  const copied = route.cloneSubgraph(g, ['11', '12'], (kind, id) => kind + '_' + id);
  assert.equal(String(copied.edges[0].conditions[0].conditions[2].nodeId), 'node_11');
  g.edges[0].conditions[0].conditions[2].nodeId = 99;
  assert.ok(route.validate(g, nodes).errors.some(e => e.edgeId === 'go' && e.nodeId === '99'
    && e.field === 'conditions[0].conditions[2].nodeId'));
});
for (const bad of [
  { op: 'AND', conditions: [] }, { op: 'NOT' }, { op: 'VISIT_COUNT', nodeId: 11, value: 100 },
  { op: 'ROLE_IN', values: ['NAVIGATOR', 'NAVIGATOR'] }, { op: 'HAS_CONDITION', value: '' },
  { op: 'ITEM_EQUIPPED', itemId: true }, { op: 'CHECK_RESULT', nodeId: 11, result: 'UNKNOWN' },
  { op: 'AND', conditions: [{ op: 'FUTURE_OP' }] }, { op: 'ROLE_IN', values: ['NAVIGATOR'], money: 5 }
]) test('非法条件明确拒绝并定位：' + JSON.stringify(bad), () => {
  const errors = route.validate(graph([bad], []), nodes).errors;
  assert.ok(errors.some(e => e.edgeId === 'go' && e.field.startsWith('conditions[0]')));
});
for (const bad of [
  { op: 'ADD_ITEM', itemId: 'lamp', quantity: 0 }, { op: 'GRANT_XP', value: 101 },
  { op: 'GRANT_BADGE', value: '?' }, { op: 'ADD_CONDITION', value: '' }, { op: 'REMOVE_CONDITION', value: '' },
  { op: 'EQUIP', itemId: 'lamp', slot: '' }, { op: 'UNEQUIP', itemId: '' },
  { op: 'TICK_CLOCK', var: 'counter.time', value: 5, max: 4 }, { op: 'FUTURE_EFFECT' },
  { op: 'GRANT_XP', value: 1, money: 3 }
]) test('非法效果明确拒绝并定位：' + JSON.stringify(bad), () => {
  assert.ok(route.validate(graph([], [bad]), nodes).errors.some(e => e.edgeId === 'go' && e.field.startsWith('effects[0]')));
});
test('作者预演组合条件实际读取角色、状态、装备持有、访问和检定事实', () => {
  const state = { currentNodeId: '11', actorRoleCode: 'NAVIGATOR', statusConditions: [],
    equipped: { hand: 'lamp' }, themeRuntime: { inventory: { lamp: 1 } }, visitedNodeIds: ['11'], variables: {}, visits: {} };
  assert.equal(route.previewNext(graph(), state, { type: 'CHOICE', outcomeCode: 'GO' }, 's').toNodeId, '12');
  state.variables['sys.passed.11'] = 0;
  assert.equal(route.previewNext(graph(), state, { type: 'CHOICE', outcomeCode: 'GO' }, 's').toNodeId, '');
  state.variables['sys.passed.11'] = 1; state.themeRuntime.inventory.lamp = 0;
  assert.equal(route.previewNext(graph(), state, { type: 'CHOICE', outcomeCode: 'GO' }, 's').toNodeId, '');
});
test('作者预演未知op不静默当false', () => {
  assert.throws(() => route.previewNext(graph([{ op: 'FUTURE_OP' }]), { currentNodeId: '11' },
    { type: 'CHOICE', outcomeCode: 'GO' }, 's'), /不支持/);
});

test('作者效果预演：物品待领取不等于持有、同节点奖励去重、装备只认库存、时钟不倒退', () => {
  const vocabulary = require('../../pages/publish/utils/publish/story-vocabulary');
  const s = { currentNodeId: '11', variables: { 'counter.time': 8 }, themeRuntime: { pending: {}, inventory: {} } };
  for (let i = 0; i < 2; i++) effects.forEach(e => vocabulary.applyEffect(e, s));
  assert.equal(Object.keys(s.themeRuntime.pending).length, 1);
  assert.deepEqual(s.themeRuntime.inventory, {});
  assert.equal(Object.keys(s.storyGrants).length, 2);
  assert.equal(s.storyGrants['XP:11:read'].amount, 5);
  assert.deepEqual(s.equipped, {}); assert.deepEqual(s.statusConditions, ['inspired']);
  assert.equal(s.variables['counter.time'], 8);
  s.themeRuntime.inventory.lamp = 1;
  vocabulary.applyEffect({ op: 'EQUIP', itemId: 'lamp', slot: 'hand' }, s);
  assert.equal(s.equipped.hand, 'lamp');
  vocabulary.applyEffect({ op: 'UNEQUIP', itemId: 'lamp' }, s); assert.deepEqual(s.equipped, {});
  vocabulary.applyEffect({ op: 'REMOVE_CONDITION', value: 'inspired' }, s); assert.deepEqual(s.statusConditions, []);
  s.currentNodeId = '12'; vocabulary.applyEffect({ op: 'GRANT_XP', value: 3, id: 'read' }, s);
  assert.equal(s.storyGrants['XP:12:read'].amount, 3);
  s.rewardCeilingXpBadgeOnly = true;
  vocabulary.applyEffect({ op: 'ADD_ITEM', itemId: 'hat', quantity: 1 }, s);
  assert.equal(Object.keys(s.themeRuntime.pending).length, 1);
  assert.throws(() => vocabulary.applyEffect({ op: 'UNKNOWN' }, s), /不支持/);
});
test('真实作者模拟执行状态效果与后继条件，不静默跳过', () => {
  const g = graph([], [{ op: 'ADD_CONDITION', value: 'inspired' }, { op: 'TICK_CLOCK', var: 'counter.time', value: 2, max: 4 }]);
  g.terminalNodeIds = ['13'];
  g.edges.push({ id: 'finish', fromNodeId: '12', toNodeId: '13', trigger: { type: 'CHOICE', outcomeCode: 'GO' },
    conditions: [{ op: 'HAS_CONDITION', value: 'inspired' }, { op: 'EQ', var: 'counter.time', value: 2 }], effects: [] });
  const result = route.simulate(g, [...nodes, { id: '13' }], { runs: 1000 });
  assert.equal(result.terminalCounts['13'], 1000); assert.equal(result.releaseReady, true);
});
