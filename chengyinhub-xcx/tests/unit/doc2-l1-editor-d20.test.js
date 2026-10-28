'use strict';
// 文档二 L1(2026-10-28):d20 与角色设定 / 可玩时段 / 开局前准备 / 半屏通用小件。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const d20 = require('../../pages/publish/fabu/theme-d20.js');
const storyInitialState = require('../../pages/publish/utils/publish/story-initial-state.js');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

function loadComponent(rel) {
  let cfg;
  global.Component = (c) => { cfg = c; };
  const file = require.resolve(path.join(ROOT, rel));
  delete require.cache[file];
  require(file);
  const events = [];
  const vm = {
    ...cfg.methods,
    data: structuredClone(Object.assign({}, cfg.data, Object.fromEntries(Object.entries(cfg.properties || {}).map(([k, v]) => [k, v.value])))),
    setData(patch) { Object.assign(this.data, patch); },
    triggerEvent(name, detail) { events.push({ name, detail }); },
  };
  return { cfg, vm, events };
}

test('规则难度按 tiers 的 dc 认档,写回只动 dc;没配过 tiers 落「标准」', () => {
  assert.equal(d20.read('').preset, 'std');
  const rules = { tiers: { easy: { dc: 8, damage: 1 }, medium: { dc: 11 }, hard: { dc: 14 } } };
  assert.equal(d20.read(JSON.stringify(rules)).preset, 'easy');
  assert.equal(d20.read(JSON.stringify({ tiers: { easy: { dc: 9 }, medium: { dc: 13 }, hard: { dc: 16 } } })).preset, '');
  const m = d20.read(JSON.stringify(rules));
  m.preset = 'hard';
  const out = d20.apply(JSON.stringify(rules), m);
  assert.deepEqual(out.tiers, { easy: { dc: 12, damage: 1 }, medium: { dc: 15 }, hard: { dc: 18 } });
});

test('属性:预设带范围,三个步进器互相挡;只替换 d20 管的行,别的 counter 留着', () => {
  const raw = JSON.stringify({ attributes: [{ key: 'counter.gold', label: '金币', min: 0, max: 99, initial: 5, visible: false }] });
  const m = d20.read(raw);
  assert.equal(m.attrs.length, 1);
  const obs = d20.newAttr('观察', m);
  assert.deepEqual([obs.min, obs.max, obs.initial], [0, 5, 2]);
  assert.match(obs.key, /^counter\.[a-z][a-z0-9_]*$/);
  let a = d20.clampAttr(obs, 'min', 9);
  assert.deepEqual([a.min, a.max, a.initial], [4, 5, 4]);
  a = d20.clampAttr(a, 'max', 99);
  assert.equal(a.max, d20.ATTR_CEIL);
  a = d20.clampAttr(a, 'initial', -3);
  assert.equal(a.initial, a.min);
  m.attrs.push(obs);
  m.attrs = d20.move(m.attrs, 1, 0);
  const out = d20.apply(raw, m);
  assert.deepEqual(out.attributes.map((r) => r.label), ['观察', '金币']);
  assert.equal(out.attributes[1].visible, false, '原有行的其余字段原样保留');
  assert.equal(out.stateEnabled, true);
});

test('进度钟格数跟着规则难度;rpg 只写后端认识的字段', () => {
  const m = d20.read('');
  m.preset = 'easy';
  const clock = d20.newClock('巡夜人警觉', m);
  assert.equal(clock.segments, 8);
  assert.match(clock.var, /^counter\.[a-z][a-z0-9_]{0,47}$/);
  m.clocks.push(clock);
  const aspect = d20.newAspect('夜行者', m);
  assert.match(aspect.key, /^[a-z][a-z0-9_]{0,31}$/);
  m.aspects.push(aspect);
  const rpg = d20.apply('', m).rpg;
  assert.deepEqual(Object.keys(rpg.clocks[0]).sort(), ['kind', 'label', 'segments', 'var', 'visible']);
  assert.deepEqual(Object.keys(rpg.aspects[0]).sort(), ['desc', 'key', 'kind', 'label']);
  assert.equal(d20.read(JSON.stringify(d20.apply('', m))).attrs.length, 0, '进度钟的 counter 不算属性');
});

test('关掉 d20:魔鬼交易与动量跟着关,进度钟 / 特质保留并保证 rpg 仍合法', () => {
  const on = { checkEnabled: true, rpg: { bargain: { enabled: true }, momentum: { enabled: true, max: 6 }, aspects: [{ key: 'a', label: '夜行者', kind: 'aspect' }] } };
  const off = d20.setCheckEnabled(JSON.stringify(on), false);
  assert.equal(off.checkEnabled, false);
  assert.equal(off.rpg.bargain.enabled, false);
  assert.deepEqual(off.rpg.momentum, { enabled: false, max: 6 });
  assert.equal(off.rpg.aspects.length, 1);
  assert.equal(off.stateEnabled, true);
});

test('摘要:「4 项属性 · 标准」/「没有属性 · 标准」;序列化走 story-initial-state', () => {
  assert.equal(d20.summary(''), '没有属性 · 标准');
  const m = d20.read('');
  ['观察', '胆量', '体魄', '灵感'].forEach((n) => m.attrs.push(d20.newAttr(n, m)));
  const raw = storyInitialState.serializeChanges('', Object.assign(d20.apply('', m), { checkEnabled: true }));
  assert.equal(d20.summary(raw), '4 项属性 · 标准');
  const page = require('../../pages/publish/fabu/theme-d20-page.js');
  assert.equal(page.publishSummaryLine(raw), 'd20 检定 · 4 项属性');
  assert.equal(page.publishSummaryLine(''), '');
});

test('可玩时段与开局前准备(需后端,先放 journeyRules 根上)', () => {
  assert.deepEqual(d20.playWindowOf(''), { mode: 'any', start: '18:00', end: '22:00' });
  const night = d20.setPlayWindow('', { mode: 'night' });
  assert.deepEqual(night.playWindow, { mode: 'night' });
  assert.equal(d20.playWindowLabel(d20.playWindowOf(night)), '只在夜间');
  assert.equal(d20.setPlayWindow(JSON.stringify(night), { mode: 'any' }).playWindow, undefined);
  const custom = d20.playWindowOf(d20.setPlayWindow('', { mode: 'custom', start: '19:00', end: '23:00' }));
  assert.equal(d20.playWindowLabel(custom), '19:00–23:00');
  const prep = d20.setPrep('', ['1', '2', '3', '4', '5', '6', '7']);
  assert.equal(prep.startPrep.length, d20.MAX_PREP);
  assert.equal(d20.setPrep(JSON.stringify(prep), []).startPrep, undefined);
});

test('d20 半屏组件:预设药丸只列没加的,满 6 项不出现;完成交回整份规则,取消不交', () => {
  const { cfg, vm, events } = loadComponent('pages/publish/components/d20-settings/index.js');
  vm.data.rules = JSON.stringify({ checkEnabled: true });
  cfg.observers.show.call(vm, true);
  assert.equal(vm.data.attrChips.length, d20.ATTR_PRESETS.length);
  vm.attrAdd({ detail: { label: '观察' } });
  assert.equal(vm.data.openAttr, vm.data.model.attrs[0].key, '加上后自动展开');
  assert.ok(!vm.data.attrChips.some((c) => c.label === '观察'));
  ['胆量', '体魄', '灵感', '敏捷', '魅力', '运气'].forEach((label) => vm.attrAdd({ detail: { label } }));
  assert.equal(vm.data.model.attrs.length, 6);
  assert.deepEqual(vm.data.attrChips, []);
  vm.attrStep({ currentTarget: { dataset: { key: vm.data.model.attrs[0].key, field: 'max' } }, detail: { value: 3 } });
  assert.equal(vm.data.model.attrs[0].max, 3);
  assert.equal(vm.data.model.attrs[0].initial, 2);
  vm.pickPreset({ currentTarget: { dataset: { key: 'hard' } } });
  assert.equal(vm.data.tapKey, 'hard');
  vm.onCancel();
  assert.equal(events.at(-1).name, 'close');
  vm.onDone();
  const saved = events.at(-1);
  assert.equal(saved.name, 'save');
  assert.equal(saved.detail.rules.attributes.length, 6);
  assert.equal(saved.detail.rules.tiers.hard.dc, 18);
  cfg.lifetimes.detached.call(vm);
});

test('开局前准备组件:最多 6 项、重复不加、建议点一下就加入', () => {
  const { cfg, vm, events } = loadComponent('pages/publish/components/start-prep/index.js');
  vm.data.items = ['带水'];
  cfg.observers.items.call(vm, vm.data.items);
  assert.ok(!vm.data.suggestions.includes('带水'));
  vm.onSuggest({ currentTarget: { dataset: { text: '带手电筒' } } });
  assert.deepEqual(events.at(-1).detail.items, ['带水', '带手电筒']);
  vm.data.text = ' 带水 ';
  vm.onAdd();
  assert.equal(events.length, 1, '重名不加');
  vm.data.items = ['1', '2', '3', '4', '5', '6'];
  vm.data.text = '7';
  vm.onAdd();
  assert.equal(events.length, 1, '满 6 项不加');
  vm.onMove({ detail: { from: 0, to: 2 } });
  assert.deepEqual(events.at(-1).detail.items, ['2', '3', '1', '4', '5', '6']);
});

test('通用小件:步进器挡上下限、底部选择卡点一项即收(stay 不收)、药丸重名', () => {
  const stp = loadComponent('components/cy/stepper/index.js');
  Object.assign(stp.vm.data, { value: 5, min: 0, max: 5, step: 1 });
  stp.vm.onStep({ currentTarget: { dataset: { d: '1' } } });
  assert.equal(stp.events.length, 0);
  stp.vm.onStep({ currentTarget: { dataset: { d: '-1' } } });
  assert.deepEqual(stp.events[0].detail, { value: 4 });

  const chips = loadComponent('components/cy/chips/index.js');
  chips.vm.data.taken = ['观察'];
  chips.vm.onAddTap();
  chips.vm.onInput({ detail: { value: '观察' } });
  assert.equal(chips.vm.data.dup, true);
  chips.vm.onConfirm();
  assert.equal(chips.events.length, 0);
  chips.vm.onInput({ detail: { value: '洞察' } });
  chips.vm.onConfirm();
  assert.deepEqual(chips.events[0], { name: 'add', detail: { value: '洞察' } });

  const cs = loadComponent('components/cy/choice-sheet/index.js');
  cs.vm.data.options = d20.WINDOWS;
  const timers = [];
  const realSet = global.setTimeout;
  global.setTimeout = (fn) => { timers.push(fn); return timers.length; };
  try {
    cs.vm.onPick({ currentTarget: { dataset: { index: 3 } } });
    assert.equal(timers.length, 0, '自定义不收');
    cs.vm.onPick({ currentTarget: { dataset: { index: 2 } } });
    timers.forEach((fn) => fn());
  } finally { global.setTimeout = realSet; }
  assert.deepEqual(cs.events.map((e) => e.name), ['change', 'change', 'close']);
});

test('主题详情:取消 / 完成、d20 开关在玩法规则卡里、可玩时段对俱乐部带队不出现、无英文副标签', () => {
  const wxml = read('pages/publish/fabu/topic-detail-sheet.wxml');
  assert.match(wxml, /node-sheet__close"[^>]*>取消<\/view>/);
  assert.match(wxml, /bindchange="onD20Toggle"/);
  assert.match(wxml, /wx:if="\{\{d20Enabled\}\}"/);
  assert.match(wxml, /wx:if="\{\{clubIndex < 0\}\}" bindtap="openPlayWindow"/);
  assert.match(wxml, /stacked="\{\{[^"]*d20Sheet\.show \|\| prepSheet\.show\}\}"/);
  const pc = read('pages/publish/fabu/index.wxml');
  assert.doesNotMatch(pc, /探索值\(XP\)/);
  for (const f of ['pages/publish/components/d20-settings/index.wxml', 'pages/publish/components/start-prep/index.wxml']) {
    const visible = read(f).replace(/<!--[\s\S]*?-->/g, '').replace(/\{\{[^}]*\}\}/g, ' ').replace(/<[^>]+>/g, ' ');
    assert.doesNotMatch(visible.replace(/d20/g, ''), /[A-Za-z]{2,}/, f + ' 里不能出现英文');
  }
});
