const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
global.getApp = () => ({ globalData: {}, chooseImage: callback => callback(['https://example.com/card.jpg']) });
global.wx = { showToast() {} };
const survival = require('../../pages/publish/fabu/theme-runtime-survival.js');
const runtime = require('../../pages/publish/fabu/theme-runtime.js');
const base = () => ({ schemaVersion: 1, capacity: 6, hpName: '体力', items: [{ id: 'supply', kind: 'ITEM', name: '补给包', image: 'https://example.com/item.jpg', slotCost: 1 }], gameplayRuntime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED', survival: { zeroHpPolicy: 'BLOCK_PAID', actionLimitPolicy: 'BLOCK_OVERFLOW', consumeOnNoEffect: false }, acts: [{ actKey: 'act_one', questKeys: ['quest_one'], completionRule: { type: 'ALL' } }], quests: [{ questKey: 'quest_one', actKey: 'act_one', nodeId: 101, locationKey: 'gate', settlementSlot: 'quest_one' }] } });
function page(config = base()) {
  const p = { data: { ...structuredClone(runtime.data), ...structuredClone(survival.data), editingTopicId: 77, formData: { themeRuntimeConfig: JSON.stringify(config), journeyRules: JSON.stringify({ stateEnabled: true, hp: { enabled: true, init: 8, max: 10 } }), chapters: [{ name: '启程', nodes: [{ id: 101, name: '广场' }, { id: 102, name: '街角' }] }] } }, setData(patch) { for (const [key, value] of Object.entries(patch)) { const parts = key.split('.'); let o = this.data; for (const part of parts.slice(0, -1)) o = o[part]; o[parts.at(-1)] = value; } } };
  return Object.assign(p, runtime.methods, survival.methods);
}
const e = (dataset = {}, value) => ({ currentTarget: { dataset }, detail: { value } });
function add(p, kind, fields) { p.editSurvivalRow(e({ kind })); p._setSurvivalRow({ ...p.data.survivalEditor.row, ...fields }); assert.equal(p.saveSurvivalEditor(), true, p.data.survivalEditor.error); }
function built() {
  const p = page(); p.openSurvivalEditor();
  add(p, 'recoveries', { kind: 'REST', hpDelta: 3, limit: 2, actKeys: ['act_one'] });
  p.editSurvivalRow(e({ kind: 'recoveries' })); p.pickRecoveryKind(e({}, 1)); p.inputSurvivalField(e({ field: 'hpFloor' }, '4')); assert.equal(p.saveSurvivalEditor(), true);
  add(p, 'rations', { actKey: 'act_one', itemId: 'supply', quantity: 2, fallbackHpCost: 1 });
  add(p, 'facilities', { targetScope: 'PARTICIPANTS', scope: 'WORLD', limit: 3, hpDelta: 2, arrivalLocationKey: 'gate', operationNodeId: 102, when: [{ op: 'ITEM_HELD', itemId: 'supply', quantity: 1 }, { op: 'GTE', var: 'sys.hp', value: 1 }, { op: 'NODE_COMPLETED', nodeId: 101 }] });
  add(p, 'card', { cover: 'https://example.com/card.jpg', fields: ['hp', 'items', 'ending'], showUnlocks: true });
  assert.equal(p.saveSurvivalEditor(), true); return JSON.parse(p._runtimePayload());
}
test('REST+RESCUE+配给+参与队伍设施：表单精确输出后端共享夹具', () => {
  assert.deepEqual(built(), JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures/author-survival-config.json'))));
});
test('关闭丢弃；空白与仅结果卡保存不创建运行段', () => {
  const p = page({ schemaVersion: 1, capacity: 0 }); const before = p.data.formData.themeRuntimeConfig;
  p.openSurvivalEditor(); assert.equal(p.saveSurvivalEditor(), true); assert.equal(p.data.formData.themeRuntimeConfig, before);
  p.openSurvivalEditor(); p.toggleSurvivalReplay(e({}, true)); p.closeSurvivalEditor(); assert.equal(p.data.formData.themeRuntimeConfig, before);
  p.openSurvivalEditor(); add(p, 'card', { fields: ['ending'] }); p.saveSurvivalEditor(); assert.equal(Object.hasOwn(JSON.parse(p._runtimePayload()), 'gameplayRuntime'), false);
});
test('新增记录标识稳定，回读可编辑并保留 W3 当前幕与其他字段', () => {
  const p = page(built()); p.openSurvivalEditor(); p.editSurvivalRow(e({ kind: 'recoveries', index: 0 }));
  p.inputSurvivalField(e({ field: 'hpDelta' }, '5')); p.saveSurvivalEditor();
  const live = JSON.parse(p.data.formData.themeRuntimeConfig); live.gameplayRuntime.acts[0].hostUnlock = true; live.gameplayRuntime.ruleVersion = 'v2'; p.data.formData.themeRuntimeConfig = JSON.stringify(live);
  p.saveSurvivalEditor(); const saved = JSON.parse(p._runtimePayload());
  assert.equal(saved.gameplayRuntime.survival.recoveries[0].recoveryKey, 'recovery_1'); assert.equal(saved.gameplayRuntime.survival.recoveries[0].hpDelta, 5);
  assert.equal(saved.gameplayRuntime.acts[0].hostUnlock, true); assert.equal(saved.gameplayRuntime.ruleVersion, 'v2'); assert.equal(saved.gameplayRuntime.survival.consumeOnNoEffect, false);
});
test('REST/RESCUE 切换只保存对应增量或下限，适用幕中文回显', () => {
  const p = page(); p.openSurvivalEditor(); p.editSurvivalRow(e({ kind: 'recoveries' })); p.toggleRecoveryAct(e({ value: 'act_one' }));
  assert.equal(p.data.survivalActChecks[0].checked, true); assert.equal(p.data.survivalActs[0].label, '启程');
  p.pickRecoveryKind(e({}, 1)); assert.deepEqual(p.data.survivalEditor.row, { recoveryKey: 'recovery_1', kind: 'RESCUE', limit: 1, actKeys: ['act_one'], hpFloor: 1 });
  p.toggleRecoveryAct(e({ value: 'act_one' })); assert.equal(Object.hasOwn(p.data.survivalEditor.row, 'actKeys'), false);
});
test('无幕禁止添加；新增结构幕即时读取；数量与引用中文校验', () => {
  const p = page({ schemaVersion: 1 }); p.openSurvivalEditor(); p.editSurvivalRow(e({ kind: 'recoveries' })); assert.match(p.data.survivalEditor.error, /先配置幕/);
  const c = built(); assert.match(survival.validate(c, undefined, { stateEnabled: false, hp: { enabled: false } }), /请先开启体力/); c.gameplayRuntime.survival.recoveries[0].limit = 100; assert.match(survival.validate(c), /次数/);
  c.gameplayRuntime.survival.recoveries[0].limit = 2; c.gameplayRuntime.survival.rations[0].quantity = 0; assert.match(survival.validate(c), /配给数量/);
  c.gameplayRuntime.survival.rations[0].quantity = 2; c.gameplayRuntime.survival.rations[0].actKey = 'removed'; assert.match(survival.validate(c), /幕已删除/);
  const newPage = page(); const live = JSON.parse(newPage.data.formData.themeRuntimeConfig); live.gameplayRuntime.acts.push({ actKey: 'new_act', questKeys: [] }); newPage.data.formData.themeRuntimeConfig = JSON.stringify(live); newPage.openSurvivalEditor(); assert.deepEqual(newPage.data.survivalActs.map(a => a.value), ['act_one', 'new_act']);
});
test('配给物品只列真实物品，不能用勋章；下拉正确提交稳定键', () => {
  const c = base(); c.items.push({ id: 'medal', kind: 'MEDAL', name: '纪念章' }); const p = page(c); p.openSurvivalEditor(); p.editSurvivalRow(e({ kind: 'rations' }));
  assert.deepEqual(p.data.survivalMaterialItems.map(i => i.value), ['supply']); p.pickSurvivalField(e({ field: 'itemId', options: 'survivalMaterialItems' }, 0)); assert.equal(p.data.survivalEditor.row.itemId, 'supply');
  p._setSurvivalRow({ ...p.data.survivalEditor.row, itemId: 'medal' }); assert.equal(p.saveSurvivalEditor(), false); assert.match(p.data.survivalEditor.error, /配给物品/);
});
test('独立设施稳定键/槽、所有前置类型、范围与节点边界', () => {
  const p = page(); p.openSurvivalEditor(); p.editSurvivalRow(e({ kind: 'facilities' })); assert.equal(p.data.survivalEditor.row.settlementSlot, 'facility_slot_1');
  p.addSurvivalCondition(); p.editSurvivalCondition(e({ index: 0, field: 'op', options: 'survivalOps' }, 6)); p.editSurvivalCondition(e({ index: 0, field: 'itemId', options: 'survivalItems' }, 0));
  assert.deepEqual(p.data.survivalEditor.row.when, [{ op: 'ITEM_HELD', itemId: 'supply', quantity: 1 }]);
  const c = built(); assert.equal(survival.validate(c, [{ value: 101 }, { value: 102 }]), ''); assert.match(survival.validate(c, []), /保存主题/);
  c.gameplayRuntime.worldMode = 'TEAM_INDEPENDENT'; assert.match(survival.validate(c), /公共世界/);
});
test('未保存节点不提供假标识，模板设施只读', () => {
  const p = page(built()); p.data.editingTopicId = ''; p.openSurvivalEditor(); assert.deepEqual(p.data.survivalNodes, [{ value: '', label: '不绑定操作节点' }]);
  const c = base(); c.gameplayRuntime.facilities = [{ facilityKey: 'tpl_node_101', actKey: 'act_one', settlementSlot: 'tpl_node_101', scope: 'OWNER', targetScope: 'OWNER', limit: 1, operationNodeId: 101 }];
  const generated = page(c); generated.openSurvivalEditor(); generated.editSurvivalRow(e({ kind: 'facilities', index: 0 })); assert.match(generated.data.survivalEditor.error, /节点玩法工具/); assert.equal(generated.data.survivalEditor.kind, '');
});
test('复制保留恢复/配给/费用/奖励/结果卡，移除真实节点引用且告知作者', () => {
  const c = built(), notices = []; const recovery = structuredClone(c.gameplayRuntime.survival), card = structuredClone(c.card);
  survival.copyReferences(c, notices); assert.deepEqual(c.gameplayRuntime.survival, recovery); assert.deepEqual(c.card, card); assert.deepEqual(notices, ['设施1操作与前置节点']);
  assert.equal(Object.hasOwn(c.gameplayRuntime.facilities[0], 'operationNodeId'), false); assert.deepEqual(c.gameplayRuntime.facilities[0].when.map(w => w.op), ['ITEM_HELD', 'GTE']);
  assert.equal(survival.validate(c, []), ''); const source = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/index.js'), 'utf8'); assert.match(source, /survivalEditor.copyReferences\(runtime, reselect\)/);
});
test('结果卡白名单使用主题名称、上传封面、重玩局限与默认恢复', () => {
  const p = page(); p.openSurvivalEditor(); p.editSurvivalRow(e({ kind: 'card' })); p.toggleSurvivalCardField(e({ value: 'hp' })); p.toggleSurvivalCardUnlocks(e({}, true)); p.uploadSurvivalCover();
  assert.equal(p.data.survivalCardFields.find(f => f.value === 'hp').label, '体力'); assert.equal(p.data.survivalEditor.row.cover, 'https://example.com/card.jpg'); p.saveSurvivalEditor(); p.toggleSurvivalReplay(e({}, true)); assert.equal(p.saveSurvivalEditor(), false); assert.match(p.data.survivalEditor.error, /队伍玩法/);
  p.toggleSurvivalReplay(e({}, false)); p.editSurvivalRow(e({ kind: 'card' })); p.removeSurvivalRow(); p.saveSurvivalEditor(); assert.equal(Object.hasOwn(JSON.parse(p._runtimePayload()), 'card'), false);
});
test('表单入口和原生接线、无开发标识或不支持场景开关', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/survival-editor.wxml'), 'utf8');
  assert.doesNotMatch(wxml, /recoveryKey|rationKey|facilityKey|settlementSlot|allowReplay|<picker/); assert.match(wxml, /runtimeHpLabel/);
  for (const control of wxml.matchAll(/<view\b[^>]*bindtap=[^>]*>/g)) { assert.match(control[0], /hover-class="cy-pressed"/); assert.match(control[0], /hover-stay-time="80"/); }
});

test('删幕保留费用与效果，恢复的未解决引用不能变成全幕，作者可明确重选', () => {
  const c = built(), recovery = structuredClone(c.gameplayRuntime.survival.recoveries[0]);
  const notices = survival.pruneActRefs(c, ['act_one']);
  assert.equal(notices.length, 3); assert.ok(notices.every(n => /引用的幕已删除，请重新选择/.test(n)));
  assert.equal(Object.hasOwn(c.gameplayRuntime.facilities[0], 'actKey'), false); assert.equal(c.gameplayRuntime.facilities[0].hpDelta, 2);
  assert.equal(Object.hasOwn(c.gameplayRuntime.survival.rations[0], 'actKey'), false); assert.equal(c.gameplayRuntime.survival.rations[0].quantity, 2);
  assert.deepEqual(c.gameplayRuntime.survival.recoveries[0], recovery); c.gameplayRuntime.acts = [{ actKey: 'new_act' }];
  assert.match(survival.validate(c), /引用的幕已删除/);
  const p = page(c); p.openSurvivalEditor(); p.editSurvivalRow(e({ kind: 'recoveries', index: 0 }));
  assert.match(p.data.survivalActChecks[1].label, /已删除的幕/); assert.equal(p.data.survivalActChecks[1].missing, true); assert.equal(p.data.survivalActCheckedCount, 1); p.toggleRecoveryAct(e({ value: 'act_one' })); p.toggleRecoveryAct(e({ value: 'new_act' }));
  assert.deepEqual(p.data.survivalEditor.row.actKeys, ['new_act']); p.editSurvivalRow(e({ kind: 'rations', index: 0 })); assert.match(p.data.survivalSelected.actKey.label, /幕已删除/);
});

 test('协调者界面合同：分区直达、返回分区、摘要、空下拉与生成来源', () => {
  const p = page(built()); p.openSurvivalEditor(e({ section: 'recovery' }));
  assert.equal(p.data.survivalEditor.section, 'recovery'); p.editSurvivalRow(e({ kind: 'rations', index: 0 })); p.backSurvivalList(); assert.equal(p.data.survivalEditor.section, 'recovery');
  p._refreshSurvivalSummary(); assert.deepEqual(p.data.survivalSummary, { recoveryText: '休整 1 · 救助 1 · 配给 1', facilityText: '独立 1 · 节点生成 0', cardText: '已自定义' });
  assert.deepEqual(p.data.survivalRecoveryKinds.map(r => r.value), ['REST', 'RESCUE']);
  p.openSurvivalEditor(e({ section: 'facility' })); p.editSurvivalRow(e({ kind: 'facilities', index: 0 }));
  p.pickSurvivalField(e({ field: 'arrivalLocationKey', options: 'survivalLocations' }, 0)); p.pickSurvivalField(e({ field: 'operationNodeId', options: 'survivalNodes' }, 0));
  assert.equal(Object.hasOwn(p.data.survivalEditor.row, 'operationNodeId'), false); assert.equal(Object.hasOwn(p.data.survivalEditor.row, 'arrivalLocationKey'), false);
  p.openSurvivalEditor(e({ section: 'card' })); assert.equal(p.data.survivalEditor.kind, 'card'); assert.equal(p.data.survivalEditor.section, 'card'); p.backSurvivalList(); assert.equal(p.data.survivalEditor.section, 'card');
  const c = base(); c.gameplayRuntime.facilities = [{ facilityKey: 'tpl_node_101', operationNodeId: 101 }]; const generated = page(c); generated.openSurvivalEditor(); assert.equal(generated.data.survivalFacilities[0].sourceLabel, '由节点「广场」的玩法工具生成');
  assert.equal(p._pruneSurvivalActRefs, survival.pruneActRefs);
 });

test('单项提交只验该项、返回丢弃，卡片分区直接完成，详情重玩立即保存', () => {
  const c = built(); c.gameplayRuntime.survival.rations[0].actKey = 'removed';
  const p = page(c); p.openSurvivalEditor(e({ section: 'recovery' })); p.editSurvivalRow(e({ kind: 'recoveries', index: 0 }));
  p.inputSurvivalField(e({ field: 'hpDelta' }, '0')); assert.equal(p.commitSurvivalRow(), false); assert.match(p.data.survivalEditor.error, /增加值/);
  p.inputSurvivalField(e({ field: 'hpDelta' }, '7')); assert.equal(p.commitSurvivalRow(), true); assert.equal(p.data.survivalEditor.kind, ''); assert.equal(p.saveSurvivalEditor(), false);
  p.editSurvivalRow(e({ kind: 'rations', index: 0 })); p.pickSurvivalField(e({ field: 'actKey', options: 'survivalActs' }, 0)); assert.equal(p.commitSurvivalRow(), true); assert.equal(p.saveSurvivalEditor(), true);
  p.openSurvivalEditor(e({ section: 'recovery' })); p.editSurvivalRow(e({ kind: 'recoveries', index: 0 })); p.inputSurvivalField(e({ field: 'hpDelta' }, '99')); p.backSurvivalList(); p.saveSurvivalEditor(); assert.equal(JSON.parse(p._runtimePayload()).gameplayRuntime.survival.recoveries[0].hpDelta, 7);
  p.openSurvivalEditor(e({ section: 'card' })); assert.equal(p.data.survivalEditor.customized, true); p.toggleSurvivalCardField(e({ value: 'story' })); assert.equal(p.saveSurvivalEditor(), true); assert.equal(p.data.survivalEditor.show, false); assert.ok(JSON.parse(p._runtimePayload()).card.fields.includes('story'));
  assert.equal(p.toggleThemeReplay(e({}, true)), false); assert.match(p.data.themeReplayError, /队伍玩法/);
  const personal = page({ schemaVersion: 1 }); personal.openSurvivalEditor(); assert.equal(personal.toggleThemeReplay(e({}, true)), true); personal.closeSurvivalEditor(); assert.equal(JSON.parse(personal._runtimePayload()).replayEnabled, true); assert.equal(Object.hasOwn(JSON.parse(personal._runtimePayload()), 'gameplayRuntime'), false);
});
