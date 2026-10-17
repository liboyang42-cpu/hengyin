const test = require('node:test');
const assert = require('node:assert/strict');
const tools = require('../../pages/publish/utils/publish/template-tools.js');
const config = require('../../pages/publish/utils/publish/advanced-game-config.js');

const slot = { key: 'value_1', kind: 'VALUE', label: '恢复的数值', required: true };

test('C2 原有效果引用的槽在移除费用后仍保留，最后一个引用移除才清理', () => {
  const advanced = { check: { successEffects: [{ var: slot.key, op: 'INC', value: 2 }] },
    interactionRules: { resourceSlots: [slot], statCosts: [{ statKey: slot.key, amount: 1 }] } };
  const next = tools.remove('cost', advanced);
  assert.deepEqual(next.interactionRules.resourceSlots, [slot]);
  assert.deepEqual(next.check, advanced.check);
  assert.deepEqual(advanced.interactionRules.statCosts, [{ statKey: slot.key, amount: 1 }]);
});

test('C2 终局新方案不要求主题结局，稳定方案标识编辑后不变，旧 endingKey 不丢失', () => {
  const original = { interactionTools: { votePolicy: 'MAJORITY', decidesEnding: true,
    plans: [{ title: '旧方案', endingKey: 'legacy_end', materialCost: [] }] } };
  const draft = tools.read('vote', original);
  draft.plans.push({ title: '新方案', materialCost: [] });
  const saved = tools.apply('vote', draft, original);
  assert.equal(saved.error, undefined);
  assert.equal(saved.value.interactionTools.plans[0].endingKey, 'legacy_end');
  const key = saved.value.interactionTools.plans[1].planKey;
  assert.match(key, /^[a-z][a-z0-9_]{0,31}$/);
  const edited = tools.read('vote', saved.value);
  edited.plans[1].title = '改名'; edited.plans.reverse();
  const again = tools.apply('vote', edited, saved.value).value;
  assert.equal(again.interactionTools.plans[0].planKey, key);
  assert.equal(again.interactionTools.plans[1].endingKey, 'legacy_end');
  assert.equal(original.interactionTools.plans.length, 1);
});

test('C2 未改动原有模板效果与旧结局标识完整往返', () => {
  const raw = JSON.stringify({ schemaVersion: 1, check: { enabled: true, skill: 'notice', tier: 'medium',
    successEffects: [{ var: 'counter.old', op: 'INC', value: -2 }] },
    interactionTools: { votePolicy: 'MAJORITY', decidesEnding: true, plans: [{ title: '旧选项', endingKey: 'old' }] } });
  assert.equal(config.serializeChanges(config.parse(raw).value, raw), raw);
});

for (const path of ['effects', 'check.successEffects', 'check.failEffects', 'check.critEffects', 'check.fumbleEffects', 'qa.options.0.effects',
  'profile.questions.0.options.0.effects', 'branch.steps.0.options.0.effects',
  'pricePair.effects', 'hiddenObject.effects', 'sort.effects', 'match.effects', 'classify.effects', 'interactionRules.effects']) {
  test('C2 注册路径声明、复用、顺序与槽清理：' + path, () => {
    const advanced = config.defaultConfig();
    advanced.qa.options = [{ id: 'a', effects: [] }];
    advanced.profile.questions = [{ options: [{ effects: [] }] }];
    advanced.branch.steps = [{ options: [{ effects: [] }] }];
    const parts = path.split('.');
    const owner = parts.slice(0, -1).reduce((at, key) => at[key] || (at[key] = {}), advanced);
    owner[parts.at(-1)] = [{ var: 'counter.legacy', op: 'INC', value: -1 }, { var: '', op: 'INC', value: 2 }];
    const declared = tools.bindEffect(advanced, path, 1, '', '恢复的数值');
    assert.equal(declared.error, undefined);
    assert.deepEqual(tools.effectsAt(declared.value, path), [{ var: 'counter.legacy', op: 'INC', value: -1 },
      { var: 'value_1', op: 'INC', value: 2 }]);
    assert.deepEqual(declared.value.interactionRules.resourceSlots, [slot]);
    const reuse = tools.bindEffect(declared.value, path, 0, slot.key);
    assert.deepEqual(reuse.value, undefined, '负数不能成为资源恢复效果，失败不得改草稿');
    assert.equal(reuse.field, 'value');
    tools.effectsAt(declared.value, path)[0].value = 1;
    const shared = tools.bindEffect(declared.value, path, 0, slot.key).value;
    assert.equal(shared.interactionRules.resourceSlots.length, 1);
    tools.effectsAt(shared, path).splice(1, 1); tools.cleanSlots(shared);
    assert.equal(shared.interactionRules.resourceSlots.length, 1);
    tools.effectsAt(shared, path).splice(0, 1); tools.cleanSlots(shared);
    assert.equal(shared.interactionRules?.resourceSlots, undefined);
    assert.equal(tools.effectsAt(advanced, path)[0].value, -1, '原对象不变');
  });
}

test('C2 资源效果的操作、数量、声明类型与失效槽定位', () => {
  const advanced = { effects: [{ var: slot.key, op: 'FLOOR', value: 999 }], interactionRules: { resourceSlots: [slot] } };
  assert.deepEqual(tools.effectSlotErrors(advanced), []);
  for (const [field, value] of [['op', 'SET'], ['value', 0], ['value', 1000], ['value', 1.5], ['var', 'deleted_value']]) {
    const bad = JSON.parse(JSON.stringify(advanced)); bad.effects[0][field] = value;
    const error = tools.effectSlotErrors(bad)[0];
    assert.equal(error.path, 'effects'); assert.equal(error.index, 0); assert.equal(error.field, field);
  }
  const wrong = JSON.parse(JSON.stringify(advanced)); wrong.interactionRules.resourceSlots[0].kind = 'ITEM';
  assert.match(tools.effectSlotErrors(wrong)[0].error, /重新选择/);
  assert.match(tools.bindEffect(advanced, 'future.effects', 0, slot.key).error, /现有/);
  assert.match(tools.bindEffect(advanced, 'effects', 0, '', '').error, /资源名称/);
  assert.match(tools.bindEffect(advanced, 'effects', 0, 'deleted').error, /已声明/);
});

test('C2 资源效果保存再进入、采用、复制保持原效果和其他数据，旧 var 不改写', () => {
  const advanced = config.defaultConfig();
  advanced.check.enabled = true; advanced.check.skill = 'notice'; advanced.check.tier = 'medium';
  advanced.check.successEffects = [{ var: 'counter.old', op: 'INC', value: -2 }, { var: 'value_1', op: 'FLOOR', value: 7 }];
  advanced.check.failEffects = [{ var: 'clue.fail', op: 'SET', value: 1 }];
  advanced.interactionRules = { resourceSlots: [slot], targetScope: 'WORLD', limit: 2, scope: 'OWNER' };
  advanced.present = 'fullscreen';
  const raw = config.serialize(advanced);
  const entered = config.parse(raw).value;
  assert.equal(config.validate(entered), '');
  assert.deepEqual(entered.check.successEffects, advanced.check.successEffects);
  const original = JSON.parse(raw);
  entered.check.successText = '恢复完成';
  const edited = JSON.parse(config.serializeChanges(entered, raw));
  assert.deepEqual(edited.interactionRules, original.interactionRules);
  assert.deepEqual(edited.check.successEffects, original.check.successEffects);
  assert.deepEqual(edited.check.failEffects, original.check.failEffects);
  assert.deepEqual(edited.present, original.present);
  for (const adoptedFromLibrary of [false, true]) {
    assert.deepEqual(JSON.parse(config.serializeChanges(config.parse(JSON.stringify(edited)).value,
      JSON.stringify(edited), { adoptedFromLibrary })), edited);
  }
});

test('C2 方案键在固定时钟/随机源下生成，重名有保护，排序或删除不会重写旧键', () => {
  const first = tools.newPlanKey([], 100, 0.5);
  assert.equal(first, 'plan_2s_i');
  assert.equal(tools.newPlanKey([{ planKey: first }], 100, 0.5), 'plan_2s_i_1');
  const original = { interactionTools: { votePolicy: 'UNANIMOUS', plans: [
    { planKey: 'plan_a', title: 'A' }, { planKey: 'plan_b', title: 'B' }] } };
  const draft = tools.read('vote', original); draft.plans.splice(0, 1);
  const saved = tools.apply('vote', draft, original).value;
  assert.equal(saved.interactionTools.plans[0].planKey, 'plan_b');
  const duplicate = tools.read('vote', original); duplicate.plans[1].planKey = 'plan_a';
  assert.equal(tools.apply('vote', duplicate, original).field, 'planKey');
});

let pageConfig;
const requests = [];
global.getApp = () => ({ globalData: {}, getAuthorization: () => 'test', sendRequest: request => requests.push(request), tips: () => {} });
global.wx = { getStorageSync: () => undefined, setStorageSync: () => {}, removeStorageSync: () => {}, showToast: () => {}, showModal: () => {} };
global.Page = value => { pageConfig = value; };
require('../../pages/publish/temp/index.js');
function page(advanced) {
  const self = Object.assign({}, pageConfig, { data: JSON.parse(JSON.stringify(pageConfig.data)) });
  self.setData = function (patch, done) {
    for (const [path, value] of Object.entries(patch)) {
      const keys = path.replace(/\[(\d+)\]/g, '.$1').split('.');
      let owner = this.data;
      for (const key of keys.slice(0, -1)) owner = owner[key] || (owner[key] = {});
      owner[keys.at(-1)] = value;
    }
    if (done) done();
  };
  self._refreshSupportMatrix = () => {};
  self._refreshOutcomeContract = () => ({ items: [], errors: [] });
  self._syncCheckDerived = () => {};
  self.refreshPreviewState = () => {};
  self.hasModule = () => false;
  if (advanced) self.data.advanced = advanced;
  self._refreshEffectResources();
  return self;
}
const event = (path, index, value, field) => ({ currentTarget: { dataset: { path, index, field } }, detail: { value } });

test('C2 页面资源草稿取消不保存，失败保留输入，确认声明再复用；范围仍写原字段', () => {
  const advanced = config.defaultConfig();
  advanced.check.successEffects = [{ var: '', op: 'INC', value: 2 }, { var: '', op: 'INC', value: 3 }];
  const self = page(advanced);
  const before = JSON.parse(JSON.stringify(advanced));
  self.openEffectResource(event('check.successEffects', 0));
  self.updateEffectResourceField(event('', 0, '恢复的数值', 'label')); self.closeEffectResource();
  assert.deepEqual(self.data.advanced, before);
  self.openEffectResource(event('check.successEffects', 0)); self.saveEffectResource();
  assert.equal(self.data.effectResourceError.field, 'label'); assert.ok(self.data.effectResourceDraft);
  self.updateEffectResourceField(event('', 0, '恢复的数值', 'label')); self.saveEffectResource();
  assert.deepEqual(self.data.advanced.interactionRules.resourceSlots, [slot]);
  assert.equal(self.data.effectResourceDraft, null);
  self.pickEffectResource(event('check.successEffects', 1, 1));
  assert.equal(self.data.advanced.check.successEffects[1].var, 'value_1');
  self.pickEffectOp(event('check.successEffects', 1, 1));
  assert.equal(self.data.advanced.check.successEffects[1].op, 'FLOOR');
  self.updateEffectField(event('check.successEffects', 1, 'counter.wrong', 'var'));
  assert.equal(self.data.advanced.check.successEffects[1].var, 'value_1', '资源行不能手输变量替换绑定');
  self.changeTargetScope({ detail: { value: 2 } });
  assert.equal(self.data.advanced.interactionRules.targetScope, 'WORLD');
  assert.equal(self.data.advanced.interactionRules.effects, undefined);
  self.removeEffectRow(event('check.successEffects', 1)); assert.equal(self.data.advanced.interactionRules.resourceSlots.length, 1);
  self.removeEffectRow(event('check.successEffects', 0)); assert.equal(self.data.advanced.interactionRules.resourceSlots, undefined);
  assert.equal(self.data.advanced.interactionRules.targetScope, 'WORLD');
});

test('C2 从服务端加载失效的效果声明会定位到原行，不重写存量模板', () => {
  const raw = JSON.stringify({ schemaVersion: 1, effects: [{ var: 'value_deleted', op: 'INC', value: 2 }] });
  const self = page(); self.fillFormWithTemplateData({ title: '失效的模板', advancedConfigJson: raw });
  assert.deepEqual(self.data.effectErrors[0], { path: 'effects', index: 0, field: 'var', error: '所需数值资源已变更，请重新选择' });
  assert.equal(self.data.effectRows.effects[0].missing, true);
  assert.equal(self.data.formData.advancedConfigJson, raw);
  const before = JSON.parse(JSON.stringify(self.data.advanced));
  assert.equal(self.validateForm(self.data.formData, true), false);
  assert.match(self.data.errors.advanced, /重新选择/);
  assert.deepEqual(self.data.advanced, before);
});

test('C2 页面原有效果编辑后草稿/复制请求只改目标，A10 与其他内容完整保留；失败不丢输入', () => {
  requests.length = 0;
  const original = { schemaVersion: 1, check: { enabled: true, skill: 'notice', tier: 'medium',
    successEffects: [{ var: 'value_1', op: 'FLOOR', value: 7 }], failEffects: [{ var: 'counter.legacy', op: 'INC', value: -2 }] },
    interactionRules: { resourceSlots: [slot], targetScope: 'WORLD' },
    interactionTools: { allowTrade: true }, future: { untouched: true } };
  const self = page();
  const author = { rewardRulesJson: '{"reward":1}', merchantGuide: '说明', sensorType: '', sensorConfig: '', storyImg: 'story.png' };
  self.fillFormWithTemplateData({ ...author, title: '完整的模板', advancedConfigJson: JSON.stringify(original) });
  self.updateEffectField(event('check.successEffects', 0, '8', 'value'));
  self.saveDraft();
  assert.equal(requests.length, 1);
  const payload = JSON.parse(requests[0].data), edited = JSON.parse(payload.advancedConfigJson);
  assert.equal(payload.id, undefined);
  const expected = JSON.parse(JSON.stringify(original)); expected.check.successEffects[0].value = 8;
  assert.deepEqual(edited, expected);
  for (const [key, value] of Object.entries(author)) assert.equal(payload[key], value);
  requests[0].success({ code: 500, msg: '服务暂不可用' });
  assert.equal(self.data.advanced.check.successEffects[0].value, 8);
  assert.equal(self.data.submitError, '服务暂不可用');
  const reentered = page(); reentered.fillFormWithTemplateData(payload);
  assert.deepEqual(reentered.data.advanced.check.successEffects, edited.check.successEffects);
  assert.deepEqual(reentered.data.effectValueSlots[1], { key: slot.key, label: slot.label });
});

test('C2 删除整题/选项会清理最后引用，其他来源及无关旧声明仍保留', () => {
  const advanced = config.defaultConfig();
  advanced.profile.questions = [{ key: 'q1', options: [{ effects: [{ var: slot.key, op: 'INC', value: 2 }] }] },
    { key: 'q2', options: [{ effects: [] }] }];
  advanced.interactionRules = { resourceSlots: [slot, { ...slot, key: 'unused_old' }] };
  const self = page(advanced);
  self.removeProfileQuestion({ currentTarget: { dataset: { index: 0 } } });
  assert.deepEqual(self.data.advanced.interactionRules.resourceSlots.map(row => row.key), ['unused_old']);
  const qa = config.defaultConfig();
  qa.qa.options = [{ id: 'a', effects: [{ var: slot.key, op: 'INC', value: 2 }] }, { id: 'b', effects: [] }];
  qa.interactionRules = { resourceSlots: [slot] };
  const other = page(qa); other.removeAdvancedRow(event('qa.options', 0));
  assert.equal(other.data.advanced.interactionRules?.resourceSlots, undefined);
});

test('C2 编辑没有槽的旧效果不引入空 interactionRules 或自动资源声明', () => {
  const advanced = config.defaultConfig(); advanced.check.successEffects = [{ var: 'counter.legacy', op: 'INC', value: -2 }];
  const self = page(advanced); self.updateEffectField(event('check.successEffects', 0, '-3', 'value'));
  assert.equal(self.data.advanced.interactionRules, undefined);
  assert.deepEqual(self.data.advanced.check.successEffects, [{ var: 'counter.legacy', op: 'INC', value: -3 }]);
});

test('C2 效果声明沿用 Java 同构 slotDigest，采用后的旧摘要精确提示该站点重新绑定', () => {
  const binding = require('../../pages/publish/utils/publish/template-binding.js');
  const crypto = require('node:crypto');
  const digest = binding.slotDigest([slot]);
  const canonical = JSON.stringify([{ key: slot.key, kind: slot.kind, label: slot.label, required: true }]);
  assert.equal(digest, crypto.createHash('sha256').update(canonical, 'utf8').digest('hex'));
  const config = { interactionRules: { resourceSlots: [slot] }, effects: [{ var: slot.key, op: 'INC', value: 2 }] };
  const template = { advancedConfigJson: JSON.stringify(config) };
  const chapters = [{ nodes: [{ id: 101, templateId: 31, name: '恢复站' }] }];
  const journey = { stateEnabled: true, hp: { enabled: true } };
  const runtime = { templateBindings: { '101': { templateId: 31, values: { value_1: 'sys.hp' }, items: {}, slotDigest: digest } } };
  assert.deepEqual(binding.issues(chapters, runtime, { 31: template }, journey), []);
  config.interactionRules.resourceSlots[0] = { ...slot, label: '新的数值要求' };
  template.advancedConfigJson = JSON.stringify(config);
  assert.deepEqual(binding.issues(chapters, runtime, { 31: template }, journey), [{ key: '101', label: '恢复站：资源未绑定' }]);
  assert.equal(runtime.templateBindings['101'].slotDigest, digest, '不得静默替作者更新摘要或猜绑定');
});

test('C2 编辑旧费用不自动把原有 SET/负 INC 效果改成资源行', () => {
  const legacy = { interactionRules: { hpCost: 1, effects: [
    { var: 'counter.old', op: 'SET', value: 2 }, { var: 'counter.old', op: 'INC', value: -1 }] } };
  const saved = tools.apply('cost', tools.read('cost', legacy), legacy).value;
  assert.deepEqual(saved.interactionRules.effects, legacy.interactionRules.effects);
  assert.deepEqual(saved.interactionRules.resourceSlots, [{ key: 'value_1', kind: 'VALUE', label: '需要的数值', required: true }]);
  assert.deepEqual(tools.effectSlotErrors(saved), []);
});

test('C2 新资源行未选槽时也按资源操作列表读下标；失效 FLOOR 不能退回旧变量行', () => {
  const advanced = config.defaultConfig(); advanced.check.successEffects = [{ var: '', op: 'INC', value: 1 }];
  const self = page(advanced);
  assert.equal(self.data.effectRows['check.successEffects'][0].legacy, false);
  self.pickEffectOp(event('check.successEffects', 0, 1));
  assert.equal(self.data.advanced.check.successEffects[0].op, 'FLOOR');
  self.data.advanced.check.successEffects[0].var = 'counter.deleted_slot'; self._refreshEffectResources();
  assert.equal(self.data.effectRows['check.successEffects'][0].legacy, false);
  assert.equal(self.data.effectRows['check.successEffects'][0].missing, true);
  assert.match(self.data.effectErrors[0].error, /重新选择/);
});

test('C2 已绑定效果选择新建时清除预选，确认新名称创建另一槽，取消仍保留原资源', () => {
  const advanced = config.defaultConfig();
  advanced.check.successEffects = [{ var: slot.key, op: 'INC', value: 2 }];
  advanced.interactionRules = { resourceSlots: [slot] };
  const self = page(advanced);
  self.pickEffectResource(event('check.successEffects', 0, 0));
  assert.equal(self.data.effectResourceDraft.key, '');
  self.updateEffectResourceField(event('', 0, '另一个数值', 'label')); self.closeEffectResource();
  assert.equal(self.data.advanced.check.successEffects[0].var, slot.key);
  self.pickEffectResource(event('check.successEffects', 0, 0));
  self.updateEffectResourceField(event('', 0, '另一个数值', 'label')); self.saveEffectResource();
  assert.equal(self.data.advanced.check.successEffects[0].var, 'value_2');
  assert.deepEqual(self.data.advanced.interactionRules.resourceSlots,
    [{ key: 'value_2', kind: 'VALUE', label: '另一个数值', required: true }]);
});

test('C2 原个人效果按矩阵 OWNER 路径保存，默认范围不生成费用/次数规则；跨队不可用时不改输入', () => {
  const advanced = config.defaultConfig(); advanced.check.enabled = true;
  advanced.check.successEffects = [{ var: slot.key, op: 'INC', value: 2 }];
  advanced.interactionRules = { resourceSlots: [slot] };
  const self = page(advanced);
  self._supportMatrix = require('./fixtures/gameplay-support-matrix.json').data;
  self._supportContext = { runtime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED' } };
  self._refreshSupportMatrix = pageConfig._refreshSupportMatrix;
  self._refreshSupportMatrix();
  assert.equal(self.data.supportError, '');
  // R5：范围选项改由 effectScopeOptions 提供（不可选项带 reason），旧 effectTargets 已撤
  assert.equal(self.data.effectTargets, undefined);
  self.changeTargetScope({ detail: { value: 2 } });
  assert.deepEqual(self.data.advanced.interactionRules, { resourceSlots: [slot] });
  assert.equal(self.data.effectResourceError.field, 'targetScope');
  self.changeTargetScope({ detail: { value: 0 } });
  assert.equal(self.data.effectResourceError, null);
  assert.deepEqual(self.data.advanced.interactionRules, { resourceSlots: [slot] });
  const denied = JSON.parse(JSON.stringify(self._supportMatrix));
  denied.types.find(row => row.section === 'check').effectSlotSupport = false;
  self._supportMatrix = denied; self._refreshSupportMatrix();
  assert.match(self.data.effectResourceDisabledReason, /数值资源/);
  self.openEffectResource(event('check.successEffects', 0));
  assert.equal(self.data.effectResourceDraft, null);
  assert.match(self.data.effectResourceError.error, /数值资源/);
});
