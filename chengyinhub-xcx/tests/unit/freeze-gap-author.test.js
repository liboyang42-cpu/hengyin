const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const requests = [], toasts = [];
global.getApp = () => ({ globalData: {}, sendRequest: request => requests.push(request),
  getRequestErrorMessage: (error, fallback) => error.message || fallback });
global.wx = { getStorageSync() {}, showToast: options => toasts.push(options.title) };
let definition;
global.Page = value => { definition = value; };
require('../../pages/publish/fabu/index');
const fixture = require('./fixtures/gameplay-structure-form');
const initial = require('../../pages/publish/utils/publish/story-initial-state');
const runtime = require('../../pages/publish/fabu/theme-runtime');
const read = file => fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu', file), 'utf8');
function page() {
  const p = fixture.page();
  const data = p.data;
  Object.assign(p, definition);
  p.data = { ...JSON.parse(JSON.stringify(definition.data)), ...data };
  p.setData = fixture.page().setData;
  p.data.formData.tickets = [];
  p.data.formData.productType = 2;
  p.data.editLoaded = true;
  p.data.formData.journeyRules = initial.serialize({ stateEnabled: true,
    hp: { enabled: true, init: 8, max: 12 }, luck: { enabled: true, init: 2, max: 5 },
    attributes: [{ key: 'counter.temp', label: '温度', min: 0, max: 12, initial: 8, visible: true }] });
  return p;
}
const fuel = { id: 'fuel', kind: 'ITEM', name: '燃料', slotCost: 2, image: 'https://example.test/fuel.png' };

test('每件占格编辑、保存、回填与主题副本保留 slotCost，副本编辑不改原稿', () => {
  const p = page();
  p._saveRuntime({ schemaVersion: 1, capacity: 14, items: [{ ...fuel, slotCost: 1 }], chapters: [] });
  p.openChapterBackpack();
  p.onRuntimeItem(fixture.event('slotCost', '2'));
  const saved = p._runtimePayload();
  assert.equal(JSON.parse(saved).items[0].slotCost, 2);
  const reopened = page();
  reopened.data.formData.themeRuntimeConfig = saved;
  reopened.openChapterBackpack();
  assert.equal(reopened.data.runtimeBag.items[0].slotCost, 2);
  const draft = reopened._buildCopiedDraft(2, false);
  assert.equal(JSON.parse(draft.formData.themeRuntimeConfig).items[0].slotCost, 2);
  const copied = page(); copied.data.formData = draft.formData;
  copied.openChapterBackpack(); copied.onRuntimeItem(fixture.event('slotCost', '3'));
  assert.equal(copied._runtimeConfig().items[0].slotCost, 3);
  assert.equal(reopened._runtimeConfig().items[0].slotCost, 2);
});

test('每件占格只接受物品的 1 至 999 整数，兼容旧物品未填占格', () => {
  const rules = initial.parse(page().data.formData.journeyRules);
  for (const slotCost of [1, 2, 999]) assert.equal(runtime.validate({ chapters: [], capacity: 14, items: [{ ...fuel, slotCost }] }, rules), '');
  for (const slotCost of [0, -1, 1.5, 1000, '2']) assert.match(runtime.validate({ chapters: [], capacity: 14, items: [{ ...fuel, slotCost }] }, rules), /每件占格/);
  const legacy = { ...fuel }; delete legacy.slotCost;
  assert.equal(runtime.validate({ chapters: [], capacity: 14, items: [legacy] }, rules), '');
  assert.match(runtime.validate({ chapters: [], capacity: 14, items: [{ ...fuel, kind: 'CLUE' }] }, rules), /每件占格/);
});

test('上限在主题编辑，首章上限只读且字段事件不能改 HP、幸运和自定义范围', () => {
  const p = page();
  for (const [stat, max] of [['hp', 10], ['luck', 4]]) p.onRuntimeStatMax({ currentTarget: { dataset: { stat } }, detail: { value: String(max) } });
  p.openValueEditor({ currentTarget: { dataset: { index: 0 } } });
  p.onValueField(fixture.event('max', '11')); p.saveValueEditor();
  p.openInitialState();
  const before = JSON.parse(JSON.stringify(p.data.initialStateEditor.rules));
  for (const field of ['hp.max', 'luck.max', 'attribute.max', 'attribute.min']) p.onInitialStateField(fixture.event(field, '99'));
  assert.deepEqual(p.data.initialStateEditor.rules, before);
  p.onInitialStateField(fixture.event('hp.init', '9'));
  p.onInitialStateField(fixture.event('attribute.initial', '7'));
  p.saveInitialState();
  const rules = initial.parse(p.data.formData.journeyRules);
  assert.deepEqual([rules.hp.init, rules.hp.max, rules.luck.max, rules.attributes[0].initial, rules.attributes[0].max], [9, 10, 4, 7, 11]);
  const wxml = read('index.wxml');
  // 2026-10-28 原型 e-init:上限只在主题详情里改,初始状态不再重复显示
  assert.doesNotMatch(wxml, /initialStateEditor\.rules\.(?:hp|luck)\.max/);
  assert.doesNotMatch(wxml, /data-field="(?:hp\.max|luck\.max|attribute\.(?:min|max))"/);
  assert.match(read('topic-detail-sheet.wxml'), /bindinput="onRuntimeStatMax"/);
});

test('开局超主题上限通过真实发布入口阻断，保持原值且不发送请求', () => {
  for (const field of ['hp', 'luck', 'attribute', 'attributeLow']) {
    requests.length = 0; toasts.length = 0;
    const p = page();
    if (field.startsWith('attribute')) {
      p.openValueEditor({ currentTarget: { dataset: { index: 0 } } });
      p.onValueField(fixture.event(field === 'attribute' ? 'max' : 'min', field === 'attribute' ? '7' : '9')); p.saveValueEditor();
    } else p.onRuntimeStatMax({ currentTarget: { dataset: { stat: field } }, detail: { value: field === 'hp' ? '7' : '1' } });
    // 隔离无关表单/站点校验；submitForm → _doSubmit → 数值域校验保持真实。
    p.validateForm = () => true; p._structureIssue = () => null;
    p._precheckAcked = true;
    p.submitForm();
    assert.equal(requests.length, 0, field);
    assert.match(toasts.at(-1), /开局.*(?:上限|范围|整数)/, field);
    const rules = initial.parse(p.data.formData.journeyRules);
    assert.equal(field.startsWith('attribute') ? rules.attributes[0].initial : rules[field].init, field === 'luck' ? 2 : 8);
    assert.equal(p.data.submitting, false);
    assert.equal(p._precheckAcked, false);
  }
});

test('主题交换许可与表决方式只在队伍共用现场模式显示，存取保留且模式不兼容不能开启', () => {
  for (const game of [{}, { runtimeScope: 'PLAYER' }, { runtimeScope: 'TEAM', worldMode: 'TEAM_INDEPENDENT' }, { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED' }]) {
    const p = page();
    p._saveRuntime({ capacity: 14, items: [], chapters: [], gameplayRuntime: game });
    const shared = game.runtimeScope === 'TEAM' && game.worldMode === 'WORLD_SHARED';
    assert.equal(p.data.themeWorldShared, shared);
    assert.equal(p.onThemeAllowTrade({ detail: { value: true } }), shared);
    assert.equal(p.onThemeVotePolicy({ detail: { value: 2 } }), shared);
    const saved = JSON.parse(p._runtimePayload()).gameplayRuntime;
    assert.equal(saved.allowTrade, shared ? true : undefined);
    assert.equal(saved.votePolicy, shared ? 'MAJORITY_ACCEPTANCE' : undefined);
    const reopened = page(); reopened.data.formData.themeRuntimeConfig = p._runtimePayload(); reopened._refreshThemeOwnership();
    assert.equal(reopened.data.themeWorldShared, shared);
    if (shared) {
      assert.equal(reopened.data.themeAllowTrade, true); assert.equal(reopened.data.themeVotePolicyIndex, 2);
      reopened.onThemeAllowTrade({ detail: { value: false } });
      assert.equal(reopened._runtimeConfig().gameplayRuntime.allowTrade, false);
    }
  }
  const block = read('topic-detail-sheet.wxml').match(/<block wx:if="{{themeWorldShared}}">([\s\S]*?)<\/block>/)[1];
  for (const handler of ['onThemeAllowTrade', 'onThemeVotePolicy']) assert.match(block, new RegExp(handler));
});

test('章末配给扣零不要求 HP，旧 fallbackHpCost 按生命回显并原样再存', () => {
  for (const hpEnabled of [false, true]) {
    const p = page();
    p.data.formData.journeyRules = initial.serialize({ stateEnabled: hpEnabled,
      hp: { enabled: hpEnabled, init: 8, max: 12 }, luck: { enabled: false } });
    p._saveRuntime({ schemaVersion: 1, capacity: 14, items: [fuel], chapters: [],
      gameplayRuntime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED',
        acts: [{ actKey: 'chapter_101', questKeys: ['node_201'], completionRule: { type: 'ALL' } }],
        quests: [{ questKey: 'node_201', actKey: 'chapter_101', nodeId: 201, locationKey: 'node_201', settlementSlot: 'node_201' }],
        survival: { zeroHpPolicy: 'BLOCK_HP_COST', actionLimitPolicy: 'BLOCK_OVERFLOW',
          rations: hpEnabled ? [{ rationKey: 'ration_1', actKey: 'chapter_101', itemId: 'fuel', quantity: 1, fallbackHpCost: 2 }] : [] } } });
    p._openChapterRules(0);
    if (hpEnabled) {
      assert.equal(p.data.runtimeChapterRation.values[p.data.runtimeChapterRation.valueIndex].key, 'HP');
      assert.equal(p.data.runtimeChapterRation.fallback, '2');
    } else {
      p.onChapterRationToggle({ detail: { value: true } });
      p.onChapterRationItem({ detail: { value: 0 } });
      p.onChapterRationFallback({ detail: { value: '0' } });
    }
    assert.equal(p._saveChapterRules(), true);
    const saved = JSON.parse(p._runtimePayload());
    assert.equal(saved.gameplayRuntime.survival.rations[0].fallbackHpCost, hpEnabled ? 2 : 0);
    assert.equal(runtime.validate(saved, initial.parse(p.data.formData.journeyRules), ['BLOCK_HP_COST']), '');
    p._openChapterRules(0);
    assert.equal(p.data.runtimeChapterRation.fallback, hpEnabled ? '2' : '0');
  }
});
