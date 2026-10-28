'use strict';
// 文档二 L1(2026-10-28):「d20 与角色设定」半屏的读写模型。只读写 journeyRules 里已有的字段:
//   checkEnabled · natAuto · tiers(规则难度三档展开) · rpg.bargain / rpg.momentum / rpg.clocks / rpg.aspects
//   · attributes(counter.* 行)。
// 规则难度不另存档名:按 tiers 的 dc 认出是哪一档(E1:轻松 8/11/14、标准 10/13/16、硬核 12/15/18)。
// 可玩时段 playWindow、开局前准备 startPrep 现在后端没有字段(需后端,见 PR 的 CY-HANDOFF),
// 先放在 journeyRules 根上,字段名以 L8 契约为准。
const values = require('./theme-values.js');

const PRESETS = [
  { key: 'easy', label: '轻松', dots: [1, 0, 0], dc: [8, 11, 14], segments: 8 },
  { key: 'std', label: '标准', dots: [1, 1, 0], dc: [10, 13, 16], segments: 6 },
  { key: 'hard', label: '硬核', dots: [1, 1, 1], dc: [12, 15, 18], segments: 4 },
];
const DEFAULT_PRESET = 'std';
const MAX_ATTRS = 6;
const MAX_CLOCKS = 6;
const MAX_ASPECTS = 12;
const MAX_PREP = 6;
const ATTR_PRESETS = [
  { label: '观察', min: 0, max: 5, initial: 2, dot: 'rare' },
  { label: '胆量', min: 0, max: 10, initial: 6, dot: 'status-warning' },
  { label: '体魄', min: 0, max: 8, initial: 4, dot: 'status-danger' },
  { label: '灵感', min: 0, max: 5, initial: 1, dot: 'status-info' },
  { label: '敏捷', min: 0, max: 5, initial: 2, dot: 'status-success' },
  { label: '魅力', min: 0, max: 5, initial: 2, dot: 'rare' },
  { label: '运气', min: 0, max: 3, initial: 1, dot: 'status-success' },
];
const ATTR_CEIL = 20;
const CLOCK_KINDS = [{ label: '坏事逼近', value: 'danger' }, { label: '事情进展', value: 'progress' }];
const CLOCK_SEGMENTS = [{ label: '4 格', value: 4 }, { label: '6 格', value: 6 }, { label: '8 格', value: 8 }];
const ASPECT_PRESETS = [
  { label: '夜行者', desc: '夜里行动更顺手' },
  { label: '受了伤', desc: '行动吃力，直到治好' },
  { label: '眼尖', desc: '更容易发现线索' },
  { label: '胆大', desc: '面对危险更镇定' },
  { label: '手巧', desc: '开锁和修理更顺手' },
  { label: '记性好', desc: '记得住看过的线索' },
  { label: '被盯上了', desc: '巡夜人更容易注意到你' },
];
const WINDOWS = [
  { value: 'any', label: '不限', glyph: 'any' },
  { value: 'day', label: '只在白天', sub: '06:00–20:00', glyph: 'day' },
  { value: 'night', label: '只在夜间', sub: '20:00–06:00', glyph: 'night' },
  { value: 'custom', label: '自定义', glyph: 'clock', stay: true },
];
const WINDOW_STARTS = ['06:00', '08:00', '10:00', '14:00', '18:00', '19:00'];
const WINDOW_ENDS = ['20:00', '21:00', '22:00', '23:00', '次日 02:00'];
const PREP_SUGGESTIONS = ['手机电量充足', '带手电筒', '戴帽子', '穿运动鞋', '带水', '和同伴约好集合地点'];

let nonce = 0;
function uid(prefix, used) {
  let key;
  do { key = prefix + Date.now().toString(36) + (++nonce).toString(36); } while (used.has(key));
  return key;
}

function parseRules(raw) {
  if (raw && typeof raw === 'object') return JSON.parse(JSON.stringify(raw));
  if (typeof raw !== 'string' || !raw.trim()) return {};
  try {
    const rules = JSON.parse(raw);
    return rules && typeof rules === 'object' && !Array.isArray(rules) ? rules : {};
  } catch (_) { return {}; }
}

function presetOf(rules) {
  const t = rules.tiers || {};
  const dc = [t.easy, t.medium, t.hard].map(x => x && Number(x.dc));
  const hit = PRESETS.find(p => p.dc.every((v, i) => v === dc[i]));
  // 没配过 tiers = 后端默认三档(10/13/16)= 标准;自己改过数字的老主题认不出时也落「标准」,保存时不动 tiers。
  return hit ? hit.key : (rules.tiers ? '' : DEFAULT_PRESET);
}

function dotOf(label) {
  const p = ATTR_PRESETS.find(x => x.label === label);
  return p ? p.dot : 'text-tertiary';
}

/** journeyRules → 半屏用的模型(纯数据,可直接 setData)。 */
function read(raw) {
  const rules = parseRules(raw);
  const rpg = rules.rpg || {};
  const clocks = Array.isArray(rpg.clocks) ? rpg.clocks : [];
  const clockVars = new Set(clocks.map(c => c.var));
  const attrs = (Array.isArray(rules.attributes) ? rules.attributes : [])
    .filter(row => values.COUNTER.test(row.key || '') && !clockVars.has(row.key))
    .map(row => ({ key: row.key, label: row.label, min: Number(row.min) || 0, max: Number(row.max) || 0,
      initial: Number(row.initial) || 0, dot: dotOf(row.label) }));
  return {
    checkEnabled: rules.checkEnabled === true,
    preset: presetOf(rules),
    natAuto: rules.natAuto !== false,
    bargain: !!(rpg.bargain && rpg.bargain.enabled),
    momentum: !!(rpg.momentum && rpg.momentum.enabled),
    attrs,
    bound: !!rules.attributeBinding,
    clocks: clocks.map(c => ({ var: c.var, label: c.label, kind: c.kind === 'danger' ? 'danger' : 'progress',
      segments: Number(c.segments) || 6, visible: c.visible !== false })),
    aspects: (Array.isArray(rpg.aspects) ? rpg.aspects : []).map(a => ({ key: a.key, label: a.label,
      kind: a.kind || 'aspect', desc: a.desc || '' })),
  };
}

/** 模型 → 写回 journeyRules 对象(在 rules 副本上改,只碰本半屏管的字段)。 */
function apply(rawRules, model) {
  const rules = parseRules(rawRules);
  const preset = PRESETS.find(p => p.key === model.preset);
  if (preset) {
    const tiers = Object.assign({}, rules.tiers);
    ['easy', 'medium', 'hard'].forEach((name, i) => { tiers[name] = Object.assign({}, tiers[name], { dc: preset.dc[i] }); });
    rules.tiers = tiers;
  }
  rules.natAuto = !!model.natAuto;
  const rpg = Object.assign({}, rules.rpg);
  rpg.bargain = Object.assign({}, rpg.bargain, { enabled: !!model.bargain });
  rpg.momentum = Object.assign({}, rpg.momentum, { enabled: !!model.momentum });
  if (model.clocks.length) rpg.clocks = model.clocks.map(c => ({ var: c.var, label: c.label, segments: c.segments, kind: c.kind, visible: c.visible !== false }));
  else delete rpg.clocks;
  if (model.aspects.length) rpg.aspects = model.aspects.map(a => Object.assign({ key: a.key, label: a.label, kind: a.kind || 'aspect' }, a.desc ? { desc: a.desc } : {}));
  else delete rpg.aspects;
  rules.rpg = rpg;
  if (!model.bound) {
    // 只替换 d20 管的属性行;别的 counter(自定义整数值)和非 counter 行原样留在原位置之后。
    const mine = new Set(read(rawRules).attrs.map(a => a.key));
    const kept = (Array.isArray(rules.attributes) ? rules.attributes : []).filter(row => !mine.has(row.key));
    const old = new Map((Array.isArray(rules.attributes) ? rules.attributes : []).map(row => [row.key, row]));
    const attrs = model.attrs.map(a => Object.assign({ visible: true }, old.get(a.key), {
      key: a.key, label: a.label, min: a.min, max: a.max, initial: a.initial }));
    rules.attributes = attrs.concat(kept);
    if (attrs.length) rules.stateEnabled = true;
  }
  return rules;
}

/** 关掉 d20:魔鬼交易与动量只挂在检定上(后端不许 checkEnabled=false 时开着),其余配置原样留着。 */
function setCheckEnabled(rawRules, on) {
  const rules = parseRules(rawRules);
  rules.checkEnabled = !!on;
  if (!on && rules.rpg) {
    const rpg = Object.assign({}, rules.rpg);
    if (rpg.bargain) rpg.bargain = Object.assign({}, rpg.bargain, { enabled: false });
    if (rpg.momentum) rpg.momentum = Object.assign({}, rpg.momentum, { enabled: false });
    rules.rpg = rpg;
    // rpg 需要 checkEnabled 或 stateEnabled 之一;进度钟 / 特质本来就记在局状态里。
    if ((rpg.clocks && rpg.clocks.length) || (rpg.aspects && rpg.aspects.length)) rules.stateEnabled = true;
  }
  return rules;
}

function presetLabel(key) {
  const p = PRESETS.find(x => x.key === key);
  return p ? p.label : '';
}

function summary(raw) {
  const m = read(raw);
  const n = m.attrs.length;
  const label = presetLabel(m.preset);
  return (n ? n + ' 项属性' : '没有属性') + (label ? ' · ' + label : '');
}

function newAttr(label, model) {
  const p = ATTR_PRESETS.find(x => x.label === label) || { min: 0, max: 5, initial: 0, dot: 'text-tertiary' };
  const used = new Set(model.attrs.map(a => a.key).concat(model.clocks.map(c => c.var)));
  return { key: values.nextKey(Array.from(used).map(key => ({ key }))), label, min: p.min, max: p.max, initial: p.initial, dot: p.dot || 'text-tertiary' };
}

/** 属性的三个步进器互相挡:最小值 < 最大值,开局值收进范围。 */
function clampAttr(attr, field, value) {
  const a = Object.assign({}, attr);
  if (field === 'min') { a.min = Math.max(0, Math.min(a.max - 1, value)); a.initial = Math.max(a.initial, a.min); }
  else if (field === 'max') { a.max = Math.max(a.min + 1, Math.min(ATTR_CEIL, value)); a.initial = Math.min(a.initial, a.max); }
  else a.initial = Math.max(a.min, Math.min(a.max, value));
  return a;
}

function newClock(label, model) {
  const used = new Set(model.attrs.map(a => a.key).concat(model.clocks.map(c => c.var)));
  const preset = PRESETS.find(p => p.key === model.preset) || PRESETS[1];
  return { var: uid('counter.clock_', used), label, kind: 'danger', segments: preset.segments, visible: true };
}

function newAspect(label, model) {
  const used = new Set(model.aspects.map(a => a.key));
  const p = ASPECT_PRESETS.find(x => x.label === label);
  return { key: uid('trait_', used), label, kind: 'aspect', desc: p ? p.desc : '' };
}

function move(list, from, to) {
  const out = list.slice();
  const [row] = out.splice(from, 1);
  out.splice(to, 0, row);
  return out;
}

// ---- 可玩时段 / 开局前准备(需后端) ----
function playWindowOf(raw) {
  const w = parseRules(raw).playWindow;
  const mode = w && WINDOWS.some(x => x.value === w.mode) ? w.mode : 'any';
  return { mode, start: (w && w.start) || '18:00', end: (w && w.end) || '22:00' };
}

function playWindowLabel(w) {
  if (w.mode === 'custom') return w.start + '–' + w.end;
  return (WINDOWS.find(x => x.value === w.mode) || WINDOWS[0]).label;
}

function setPlayWindow(rawRules, w) {
  const rules = parseRules(rawRules);
  if (!w || w.mode === 'any') delete rules.playWindow;
  else rules.playWindow = w.mode === 'custom' ? { mode: 'custom', start: w.start, end: w.end } : { mode: w.mode };
  return rules;
}

function prepOf(raw) {
  const list = parseRules(raw).startPrep;
  return Array.isArray(list) ? list.filter(x => typeof x === 'string' && x.trim()).slice(0, MAX_PREP) : [];
}

function setPrep(rawRules, list) {
  const rules = parseRules(rawRules);
  if (list && list.length) rules.startPrep = list.slice(0, MAX_PREP); else delete rules.startPrep;
  return rules;
}

module.exports = {
  PRESETS, ATTR_PRESETS, ASPECT_PRESETS, CLOCK_KINDS, CLOCK_SEGMENTS, WINDOWS, WINDOW_STARTS, WINDOW_ENDS,
  PREP_SUGGESTIONS, MAX_ATTRS, MAX_CLOCKS, MAX_ASPECTS, MAX_PREP, ATTR_CEIL,
  parseRules, read, apply, setCheckEnabled, summary, presetLabel, newAttr, clampAttr, newClock, newAspect, move,
  playWindowOf, playWindowLabel, setPlayWindow, prepOf, setPrep,
};
