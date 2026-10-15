const { test } = require('node:test');
const assert = require('node:assert/strict');
global.getApp = () => ({ globalData: {} });
global.wx = { showToast() {} };
const structure = require('../../pages/publish/fabu/theme-runtime-structure.js');
const survival = require('../../pages/publish/fabu/theme-runtime-survival.js');
const source = require('./fixtures/author-survival-config.json');

test('复制结构与生存跨模块往返：幕身份/开幕/完成要求及配给恢复费用保留，节点引用清除', () => {
  const sourceSnapshot = structuredClone(source);
  const original = structuredClone(source);
  original.gameplayRuntime.acts[0].hostUnlock = true;
  original.gameplayRuntime.acts[0].completionRule = { type: 'ANY_N', quantity: 2 };
  original.gameplayRuntime.acts.push({ actKey: 'second', hostUnlock: false, completionRule: { type: 'ALL' }, questKeys: ['old_second'] });
  original.gameplayRuntime.quests.push({ questKey: 'old_second', actKey: 'second', nodeId: 103, locationKey: 'node_103', settlementSlot: 'old_second', approvedFallbackNodeIds: [101], facilityKey: 'tpl_node_103' });
  original.gameplayRuntime.facilities.push({ facilityKey: 'tpl_node_103', actKey: 'second', operationNodeId: 103 });
  const copied = structuredClone(original), notices = [];
  structure.copyReferences(copied, notices); survival.copyReferences(copied, notices);
  assert.deepEqual(copied.gameplayRuntime.acts, original.gameplayRuntime.acts.map(row => ({ ...row, questKeys: [] })));
  assert.deepEqual(copied.gameplayRuntime.quests, []);
  assert.deepEqual(copied.gameplayRuntime.survival, original.gameplayRuntime.survival);
  assert.equal(copied.gameplayRuntime.facilities.length, 1);
  assert.equal(copied.gameplayRuntime.facilities[0].operationNodeId, undefined);
  assert.deepEqual(copied.gameplayRuntime.facilities[0].when.map(row => row.op), ['ITEM_HELD', 'GTE']);
  assert.equal(survival.validate(copied, []), '');
  assert.deepEqual(JSON.parse(JSON.stringify(copied)), copied);
  const chapters = [{ id: 300, nodes: [] }, { id: 301, nodes: [{ id: 201 }, { id: 202 }] }, { id: 302, nodes: [{ id: 203 }] }];
  const rebound = structure.syncChapters(copied, chapters).value;
  assert.deepEqual(rebound.gameplayRuntime.acts.map(row => row.actKey), ['act_one', 'second']);
  assert.deepEqual(rebound.gameplayRuntime.acts.map(row => row.hostUnlock), [true, false]);
  assert.deepEqual(rebound.gameplayRuntime.acts.map(row => row.completionRule), [{ type: 'ANY_N', quantity: 2 }, { type: 'ALL' }]);
  assert.deepEqual(rebound.gameplayRuntime.quests.map(row => row.nodeId), [201, 202, 203]);
  assert.ok(rebound.gameplayRuntime.quests.every(row => !row.facilityKey && !row.approvedFallbackNodeIds));
  assert.equal(survival.validate(rebound, chapters.flatMap(ch => ch.nodes).map(row => ({ value: row.id }))), '');
  assert.deepEqual(source, sourceSnapshot, '原配置不变');
});

test('副本重新同步包含空章节或暂未匹配章节时，不静默删除幕与生存引用', () => {
  const copied = structuredClone(source); structure.copyReferences(copied, []); survival.copyReferences(copied, []);
  copied.gameplayRuntime.acts.push({ actKey: 'unmatched', hostUnlock: true, completionRule: { type: 'ALL' }, questKeys: [] });
  const rebound = structure.syncChapters(copied, [{ id: 301, nodes: [] }]).value;
  assert.deepEqual(rebound.gameplayRuntime.acts, copied.gameplayRuntime.acts);
  assert.deepEqual(rebound.gameplayRuntime.quests, []);
  assert.equal(survival.validate(rebound, []), '');
});
