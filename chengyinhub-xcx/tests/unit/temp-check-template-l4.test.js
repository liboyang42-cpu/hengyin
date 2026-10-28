// 玩法模板 · 检定 / 消耗与效果(原型 A e-temp / e-after,2026-10-28 晚)
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ctxUtil = require('../../pages/publish/utils/publish/check-context.js');
const cfg = require('../../pages/publish/utils/publish/advanced-game-config.js');
const matrix = require('./fixtures/gameplay-support-matrix.json').data;

const clone = v => JSON.parse(JSON.stringify(v));
const event = (dataset = {}, value) => ({ currentTarget: { dataset }, detail: { value } });
const RULES = {
  schemaVersion: 1, checkEnabled: true, modCap: 4,
  attributes: [{ key: 'counter.observe', label: '观察', min: 0, max: 5, initial: 2 }, { key: 'counter.nerve', label: '胆量', min: 0, max: 5, initial: 1 }],
  rpg: {
    bargain: { enabled: true },
    clocks: [{ var: 'counter.alarm', label: '巡夜人警觉', segments: 6, kind: 'danger', visible: true }],
    aspects: [{ key: 'night_owl', label: '夜行者', kind: 'aspect' }],
  },
};
const WXML = fs.readFileSync(path.join(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8');

let definition;
const app = { globalData: {}, tips() {}, getAuthorization: () => '', sendRequest() {} };
global.getApp = () => app; global.Page = value => { definition = value; };
global.wx = { getStorageSync() {}, setStorageSync() {}, getBackgroundAudioManager: () => ({ stop() {} }), showToast() {}, navigateBack() {} };

function page(rules) {
  delete require.cache[require.resolve('../../pages/publish/temp/index.js')]; require('../../pages/publish/temp/index.js');
  const p = Object.assign({}, definition, { data: clone(definition.data) });
  p.setData = function (patch, done) { for (const [key, value] of Object.entries(patch)) { const keys = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let at = this.data; for (const k of keys.slice(0, -1)) at = at[k]; at[keys.at(-1)] = value; } if (done) done(); };
  p._refreshOutcomeContract = () => ({ items: [], errors: [] }); p.refreshPreviewState = () => {}; p.hasModule = () => false;
  p._supportMatrix = matrix;
  p._initCheckCtx(rules === undefined ? {} : { checkCtx: ctxUtil.query(rules).slice('&checkCtx='.length) });
  p.data.advanced = cfg.parse({ schemaVersion: 1, check: { enabled: true, checkId: 'c1', tier: 'medium', skill: '', successText: '', failText: '', mods: [] } }).value;
  p.data.gameSection = 'check';
  p._syncCheckDerived(p.data.advanced);
  return p;
}

test('上下文:主题规则压成 checkCtx,读回来只剩名字和键;状态四种', () => {
  const ctx = ctxUtil.parse(ctxUtil.query(RULES).slice('&checkCtx='.length));
  assert.deepEqual(ctx.attrs, [{ key: 'counter.observe', label: '观察' }, { key: 'counter.nerve', label: '胆量' }]);
  assert.equal(ctx.cap, 4); assert.equal(ctx.bargain, true);
  assert.equal(ctxUtil.skillState(ctx), 'ok');
  assert.equal(ctxUtil.skillState(null), 'off', '不是从主题进来:技能只填名字');
  assert.equal(ctxUtil.skillState(Object.assign({}, ctx, { on: false })), 'off', 'd20 检定关');
  assert.equal(ctxUtil.skillState(Object.assign({}, ctx, { attrs: [] })), 'empty');
  assert.equal(ctxUtil.skillState(ctxUtil.parse('%E0%A4%A')), 'error');
  assert.equal(ctxUtil.skillValue(Object.assign({}, ctx, { attrs: [] }), ''), '无');
  assert.equal(ctxUtil.skillValue({ error: true }, ''), '—');
  assert.equal(ctxUtil.fromRules('{bad').error, true);
  assert.equal(ctxUtil.skillText(null, 'counter.observe'), '', 'd20 关时技能框不露键名');
  assert.equal(ctxUtil.skillText(ctx, 'counter.observe'), '观察');
  assert.equal(ctxUtil.skillText(null, '撬锁'), '撬锁');
  assert.equal(ctxUtil.query(''), '', '没有规则不带');
  assert.equal(ctxUtil.query(Object.assign({}, RULES, { checkEnabled: false })), '', 'd20 检定关不带');
  assert.match(ctxUtil.query('{bad'), /^&checkCtx=/, '读不出来要带上,编辑页显示出错');
});

test('加上属性:就地勾选,写 counter.* 键,「不加属性」写空;界面值只显示名字', () => {
  const p = page(RULES);
  assert.equal(p.data.checkView.skillValue, '不加属性');
  p.toggleCheckRow(event({ open: 'skill' })); assert.equal(p.data.checkOpen, 'skill');
  p.pickCheckSkill(event({ key: 'counter.observe' }));
  assert.equal(p.data.advanced.check.skill, 'counter.observe');
  assert.equal(p.data.checkView.skillValue, '观察'); assert.equal(p.data.checkOpen, '');
  assert.deepEqual(p.data.checkView.skillOptions.map(o => o.label), ['观察', '胆量', '不加属性']);
  p.pickCheckSkill(event({ key: '' })); assert.equal(p.data.advanced.check.skill, '');
});

test('难度分段:写 easy / medium / hard', () => {
  const p = page(RULES);
  p.pickCheckTierSeg(event({ index: 2 })); assert.equal(p.data.advanced.check.tier, 'hard');
  p.pickCheckTierSeg(event({ index: 0 })); assert.equal(p.data.advanced.check.tier, 'easy');
});

test('情境加值:种类 → 名字就加上并展开;句子不带键名;合计到上限「添加加值」变灰', () => {
  const p = page(RULES);
  p.openCheckSheet(event({ mode: 'mod' }));
  assert.deepEqual(p.data.checkSheet.kinds.map(k => k.label), ['有特质', '属性', '进度钟', '数值']);
  p.pickCheckName(event({ kind: 'aspect', key: 'night_owl' }));
  assert.equal(p.data.checkSheet.show, false);
  assert.deepEqual(p.data.advanced.check.mods[0], { when: { op: 'HAS_CONDITION', value: 'night_owl' }, value: 1, label: '有特质 · 夜行者' });
  assert.equal(p.data.checkOpen, 'mod-0');
  assert.equal(p.data.checkView.mods[0].title, '有特质 · 夜行者'); assert.equal(p.data.checkView.mods[0].value, '+1');
  p.stepModAmount(event({ index: 0, delta: 1, max: p.data.checkView.mods[0].max }));
  p.stepModAmount(event({ index: 0, delta: 1, max: p.data.checkView.mods[0].max }));
  assert.equal(p.data.advanced.check.mods[0].value, 3);
  p.openCheckSheet(event({ mode: 'mod' })); p.pickCheckName(event({ kind: 'attr', key: 'counter.nerve' }));
  assert.equal(p.data.checkView.mods[1].title, '属性 · 胆量 至少 1');
  assert.equal(p.data.checkView.modAddOff, true, '3 + 1 = 上限 4');
  p.data.checkSheet.show = false; p.openCheckSheet(event({ mode: 'mod' })); assert.equal(p.data.checkSheet.show, false, '变灰时点不开');
  // 第二条换成加上属性值,腾出上限
  p.pickModMode(event({ index: 1, mode: 'attr' }));
  assert.equal(p.data.advanced.check.mods[1].var, 'counter.observe'); assert.equal('value' in p.data.advanced.check.mods[1], false);
  assert.equal(p.data.checkView.mods[1].value, '+观察'); assert.equal(p.data.checkView.modAddOff, false);
  p.pickModAttr(event({ index: 1, key: 'counter.nerve' })); assert.equal(p.data.checkView.mods[1].value, '+胆量');
  p.stepModThreshold(event({ index: 1, delta: 2 })); assert.equal(p.data.advanced.check.mods[1].when.value, 3);
  p.removeMod(event({ index: 0 })); assert.equal(p.data.advanced.check.mods.length, 1);
  for (const row of p.data.checkView.mods) assert.doesNotMatch(row.title + row.value, /[A-Za-z_]{2,}/);
});

test('幸运加分与生命值阶梯不进情境加值列表;幸运三项写法统一', () => {
  const p = page(RULES);
  p.toggleLuck({ detail: { value: true } });
  p.toggleHpLadder({ detail: { value: true } });
  assert.equal(p.data.checkView.mods.length, 0);
  assert.equal(p.data.checkView.luckLabel, '幸运高多 1 分');
  p.onLuckPick(event({ index: 2 }));
  assert.equal(p.data.checkView.luckLabel, '幸运高多 1 分，幸运低少 1 分');
  assert.deepEqual(cfg.LUCK_OPTIONS.map(o => o.label), ['幸运高多 1 分', '幸运低少 1 分', '幸运高多 1 分，幸运低少 1 分']);
  assert.equal(p.data.advanced.check.mods.filter(m => m.label === '幸运高获得多1分').length, 1, '存盘的预设 label 不变');
});

test('魔鬼交易:主题关着整段不出现;开着写 bargain{label, bonus, effects},代价最多 4 条', () => {
  assert.equal(page(Object.assign({}, RULES, { rpg: Object.assign({}, RULES.rpg, { bargain: { enabled: false } }) })).data.checkView.bargainShow, false);
  assert.equal(page(undefined).data.checkView.bargainShow, false, '不是从主题进来、模板也没配');
  const p = page(RULES);
  assert.equal(p.data.checkView.bargainShow, true);
  p.toggleBargain({ detail: { value: true } });
  assert.deepEqual(p.data.advanced.check.bargain, { label: '', bonus: 2, effects: [] });
  assert.equal(cfg.validate(p.data.advanced), '请写魔鬼交易的说明。');
  p.inputBargainLabel(event({}, '让巡夜人听见你'));
  p.pickBargainBonus(event({ bonus: 3 }));
  assert.equal(cfg.validate(p.data.advanced), '请为魔鬼交易添加代价。');
  p.openCheckSheet(event({ mode: 'price' }));
  assert.deepEqual(p.data.checkSheet.kinds.map(k => k.label), ['进度钟', '属性', '加上特质']);
  p.pickCheckName(event({ kind: 'clock', key: 'counter.alarm' }));
  assert.deepEqual(p.data.advanced.check.bargain.effects[0], { op: 'TICK_CLOCK', var: 'counter.alarm', value: 1, max: 6 });
  p.stepPrice(event({ index: 0, delta: 1 }));
  assert.equal(p.data.checkView.prices[0].title, '进度钟 · 巡夜人警觉 +2');
  assert.equal(cfg.validate(p.data.advanced), '');
  for (const [kind, key] of [['attr', 'counter.observe'], ['aspect', 'night_owl'], ['clock', 'counter.alarm']]) {
    p.openCheckSheet(event({ mode: 'price' })); p.pickCheckName(event({ kind, key }));
  }
  assert.equal(p.data.advanced.check.bargain.effects.length, 4);
  assert.equal(p.data.checkView.priceAddShow, false, '满 4 条「添加代价」不出现');
  assert.equal(p.data.checkView.prices[1].title, '属性 · 观察 −1');
  p.stepPrice(event({ index: 1, delta: 1 })); assert.equal(p.data.advanced.check.bargain.effects[1].value, 1, '属性代价跳过 0');
  p.removePrice(event({ index: 3 })); assert.equal(p.data.checkView.priceAddShow, true);
  p.toggleBargain({ detail: { value: false } });
  assert.equal('bargain' in p.data.advanced.check, false);
});

test('消耗与效果:药丸加消耗、种类一层加效果、就地改数量与种类、之后勾选,保存走原来的 apply', () => {
  const p = page(RULES);
  p.data.advanced = cfg.parse({ schemaVersion: 1, check: { enabled: true, checkId: 'c1', tier: 'medium' }, interactionRules: { resourceSlots: [{ key: 'vital', kind: 'VALUE', label: '体温', required: true }] }, outcomeRules: { version: 1, pass: {}, fail: {} } }).value;
  p.data.gameKey = 'check'; p._refreshR5Editor();
  p.openAfterSheet(event({ path: 'outcomeRules.fail', inline: '1' }));
  p.toggleAfterRow(event({ open: 'costAdd' })); assert.equal(p.data.afterOpen, 'costAdd');
  p.addAfterCost(event({ key: 'vital' }));
  assert.deepEqual(p.data.afterEditor.draft.costs, [{ statKey: 'vital', amount: 1 }]); assert.equal(p.data.afterOpen, 'cost-0');
  p.addAfterCost(event({ key: 'vital' })); assert.equal(p.data.afterEditor.draft.costs.length, 1, '同一个数值不重复');
  p.stepAfterRow(event({ kind: 'costs', index: 0, delta: 1 })); assert.equal(p.data.afterEditor.draft.costs[0].amount, 2);
  p.openAfterAdd(); p.toggleAfterAddKind(event({ key: 'FLOOR' })); p.pickAfterAddName(event({ op: 'FLOOR', key: 'vital' }));
  assert.equal(p.data.afterAdd.show, false); assert.equal(p.data.afterOpen, 'eff-0');
  p.pickAfterEffectOp(event({ index: 0, op: 'INC' })); p.stepAfterRow(event({ kind: 'effects', index: 0, delta: 1 }));
  assert.deepEqual(p.data.afterEditor.draft.effects, [{ var: 'vital', op: 'INC', value: 2 }]);
  assert.equal(p.saveAfterSheet(), true);
  assert.deepEqual(p.data.advanced.outcomeRules.fail.costs, [{ statKey: 'vital', amount: 2 }]);
  assert.equal(p.data.afterOpen, ''); assert.equal(p.data.afterAdd.show, false);
  const fb = p.data.r5Editor.fbRows.find(r => r.key === 'fail'); assert.equal(fb.count, 2);
});

test('页面:检定段沿用 cg-gcfg > cg-adv-body 外壳;不再有下拉框和键名占位;消耗与效果左上是「取消」', () => {
  const start = WXML.indexOf("<view class=\"cg-gcfg\" wx:if=\"{{gameSection === 'check'}}\">");
  assert.ok(start > 0);
  const end = WXML.indexOf('判定通过后的效果', start);
  const block = WXML.slice(start, end);
  assert.match(block, /^<view class="cg-gcfg" wx:if="\{\{gameSection === 'check'\}\}">\s*<view class="cg-adv-body">/);
  assert.doesNotMatch(block, /cy-dropdown|cg-pick|判定什么技能/);
  for (const text of ['加上属性', '难度', '情境加值', '添加加值', '魔鬼交易', '代价', '添加代价', '过了说一句', '没过说一句']) assert.ok(block.includes(text), text);
  assert.match(WXML, /幸运加分/);
  assert.match(WXML, /data-close="closeAfterSheet" data-out="after" aria-role="button" aria-label="取消"[^>]*>取消<\/view>/);
  const sheet = WXML.slice(WXML.indexOf('R5 结果之后'), WXML.indexOf('工具子浮层'));
  assert.doesNotMatch(sheet.slice(sheet.indexOf('e-after:')), /cy-dropdown|新建需要的数值/);
  for (const text of ['消耗', '添加消耗', '新建数值', '效果', '添加效果', '之后']) assert.ok(sheet.includes(text), text);
});

test('保存:加上属性值的加值(var)与魔鬼交易随序列化原样带上,本地校验放行', () => {
  const p = page(RULES);
  p.openCheckSheet(event({ mode: 'mod' })); p.pickCheckName(event({ kind: 'aspect', key: 'night_owl' }));
  p.pickModMode(event({ index: 0, mode: 'attr' }));
  p.toggleBargain({ detail: { value: true } }); p.inputBargainLabel(event({}, '换一次更稳的手'));
  p.openCheckSheet(event({ mode: 'price' })); p.pickCheckName(event({ kind: 'aspect', key: 'night_owl' }));
  assert.equal(cfg.validate(p.data.advanced), '');
  const saved = JSON.parse(cfg.serialize(p.data.advanced)).check;
  assert.deepEqual(saved.mods, [{ when: { op: 'HAS_CONDITION', value: 'night_owl' }, var: 'counter.observe', label: '有特质 · 夜行者' }]);
  assert.deepEqual(saved.bargain, { label: '换一次更稳的手', bonus: 2, effects: [{ op: 'ADD_CONDITION', value: 'night_owl' }] });
});
