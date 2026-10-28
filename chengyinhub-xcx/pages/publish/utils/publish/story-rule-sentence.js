'use strict';
// 下一站「条件 / 效果」的句子视图（10-28 原型 A e-route）。
// 底下仍是 story-rule-form 的 conditions[] / effects[]（键名照存），这里只负责：
//   名字 <-> 键 的对照、把一条规则读成一句中文、以及按句子做的增删改。
// 作者看不到也打不出任何键名：名字只从下面 names 里挑。
const clone = value => JSON.parse(JSON.stringify(value));
const COMPARE = ['GTE', 'LTE', 'EQ'];
const COMPARE_WORD = { GTE: '至少', LTE: '至多', EQ: '正好', GT: '多于', LT: '少于', NE: '不是' };
const NEGATE = {
  clue: ['已获得', '未获得'], item: ['带着', '没带'], equip: ['装备着', '没装备'], tag: ['有', '没有'],
  trait: ['有', '没有'], done: ['完成过', '没完成过'],
};
// 添加条件 / 效果那一层的种类，顺序照原型。name = 选名字用哪张名单。
const CONDITION_KINDS = [
  { kind: 'clue', label: '已获得线索', names: 'clue' },
  { kind: 'equip', label: '装备着物品', names: 'item' },
  { kind: 'tag', label: '有标签', names: 'tag' },
  { kind: 'trait', label: '有特质', names: 'trait' },
  { kind: 'attr', label: '属性', names: 'attr' },
  { kind: 'clock', label: '进度钟', names: 'clock' },
  { kind: 'value', label: '数值', names: 'value' },
  { kind: 'visit', label: '到过节点', names: 'node' },
  { kind: 'done', label: '完成过节点', names: 'node' },
  { kind: 'check', label: '检定结果', names: 'node' },
  { kind: 'group', label: '一组条件' },
];
const EFFECT_KINDS = [
  { kind: 'clue', label: '获得线索', names: 'clue' },
  { kind: 'item', label: '给物品', names: 'item' },
  { kind: 'equip', label: '装备物品', names: 'item' },
  { kind: 'unequip', label: '卸下物品', names: 'item' },
  { kind: 'tag', label: '加上标签', names: 'tag' },
  { kind: 'trait', label: '加上特质', names: 'trait' },
  { kind: 'untrait', label: '去掉特质', names: 'trait' },
  { kind: 'clock', label: '进度钟', names: 'clock' },
  { kind: 'attr', label: '属性', names: 'attr' },
  { kind: 'value', label: '数值', names: 'value' },
  { kind: 'xp', label: '给探索值' },
];

function record(value) { return !!value && typeof value === 'object' && !Array.isArray(value); }
function parse(raw) {
  if (record(raw)) return raw;
  try { const value = JSON.parse(raw || '{}'); return record(value) ? value : {}; } catch (_) { return {}; }
}
function text(value) { return typeof value === 'string' ? value.trim() : ''; }

// 名单只收作者能认出的名字；没有名字的键不进名单（不拿键名凑数）。
function buildNames(journeyRaw, runtimeRaw, nodes, variables) {
  const rules = parse(journeyRaw), runtime = parse(runtimeRaw), rpg = record(rules.rpg) ? rules.rpg : {};
  const out = { clue: [], tag: [], item: [], trait: [], attr: [], clock: [], value: [], node: [] };
  const add = (list, key, label, extra) => {
    if (key && label && !list.some(row => row.key === key)) list.push(Object.assign({ key, label }, extra));
  };
  const clocks = Array.isArray(rpg.clocks) ? rpg.clocks : [];
  clocks.forEach(row => record(row) && add(out.clock, text(row.var), text(row.label), { max: Number(row.segments) || 0 }));
  (Array.isArray(rules.attributes) ? rules.attributes : []).forEach(row => {
    if (!record(row)) return;
    const key = text(row.key), label = text(row.name) || text(row.label);
    if (/^clue\./.test(key)) add(out.clue, key, label);
    else if (/^tag\./.test(key)) add(out.tag, key, label);
    else if (/^counter\./.test(key) && !out.clock.some(c => c.key === key)) add(out.attr, key, label, { initial: Number(row.initial) || 0 });
  });
  (Array.isArray(rpg.aspects) ? rpg.aspects : []).forEach(row => record(row) && add(out.trait, text(row.key), text(row.label)));
  (Array.isArray(runtime.items) ? runtime.items : []).forEach(row => record(row) && add(out.item, text(String(row.id || '')), text(row.name)));
  const vars = variables || {};
  if (Object.prototype.hasOwnProperty.call(vars, 'sys.hp')) add(out.value, 'sys.hp', text(runtime.hpName) || '生命值');
  if (Object.prototype.hasOwnProperty.call(vars, 'sys.luck')) add(out.value, 'sys.luck', text(runtime.luckName) || '幸运值');
  if (Object.prototype.hasOwnProperty.call(vars, 'sys.actions')) add(out.value, 'sys.actions', text(runtime.actionName) || '行动次数');
  (nodes || []).forEach(node => add(out.node, String(node.id), text(node.label || node.name)));
  return out;
}
// 状态键注册表（GET /api/topic/state-keys）是线索、标签、数值名字的真源：同键以注册表的名字为准；
// counter 里不是属性、也不是进度钟的，算「数值」。
function mergeRegistry(names, rows) {
  const out = JSON.parse(JSON.stringify(names));
  (Array.isArray(rows) ? rows : []).forEach(row => {
    if (!record(row)) return;
    const key = text(row.stateKey), label = text(row.label);
    const list = /^clue\./.test(key) ? 'clue' : /^tag\./.test(key) ? 'tag'
      : /^counter\./.test(key) && !out.attr.some(r => r.key === key) && !out.clock.some(r => r.key === key) ? 'value' : '';
    if (!list || !label) return;
    const hit = out[list].find(r => r.key === key);
    if (hit) hit.label = label;
    else out[list].push(list === 'value' ? { key, label, initial: Number(row.initialValue) || 0 } : { key, label });
  });
  return out;
}
// 编辑时可用的变量 = 路线图已声明的 + 名单里的属性 / 进度钟 / 注册表数值（初值同设定，进度钟从 0）。
function editorVariables(variables, names) {
  const out = Object.assign({}, variables || {});
  (names.attr || []).forEach(row => { if (!(row.key in out)) out[row.key] = row.initial || 0; });
  (names.clock || []).forEach(row => { if (!(row.key in out)) out[row.key] = 0; });
  (names.value || []).forEach(row => { if (/^counter\./.test(row.key) && !(row.key in out)) out[row.key] = row.initial || 0; });
  return out;
}

function nameOf(names, list, key) {
  const row = (names[list] || []).find(item => item.key === String(key == null ? '' : key));
  return row ? row.label : '未命名';
}
function varList(names, key) {
  if ((names.clock || []).some(row => row.key === key)) return 'clock';
  if ((names.value || []).some(row => row.key === key) || /^sys\./.test(key || '')) return 'value';
  return 'attr';
}
const VAR_WORD = { attr: '属性', clock: '进度钟', value: '数值' };

// 叶子条件（已拆掉 NOT）→ 种类
function conditionKind(row) {
  switch (row && row.op) {
    case 'HAS_TAG': return /^tag\./.test(row.value || '') ? 'tag' : 'clue';
    case 'ITEM_EQUIPPED': return 'equip';
    case 'HAS_CONDITION': return 'trait';
    case 'VISIT_COUNT': return 'visit';
    case 'NODE_COMPLETED': return 'done';
    case 'CHECK_RESULT': return 'check';
    case 'AND': case 'OR': return 'group';
    case 'EQ': case 'NE': case 'GT': case 'GTE': case 'LT': case 'LTE': return 'var';
    default: return '';
  }
}
function effectKind(row) {
  switch (row && row.op) {
    case 'ADD_TAG': return /^tag\./.test(row.value || '') ? 'tag' : 'clue';
    case 'ADD_ITEM': return 'item';
    case 'EQUIP': return 'equip';
    case 'UNEQUIP': return 'unequip';
    case 'ADD_CONDITION': return 'trait';
    case 'REMOVE_CONDITION': return 'untrait';
    case 'TICK_CLOCK': return 'clock';
    case 'INC': case 'SET': return 'var';
    case 'GRANT_XP': return 'xp';
    case 'GRANT_BADGE': return 'badge';
    default: return '';
  }
}

function conditionSentence(row, negated, names) {
  const kind = conditionKind(row), neg = negated ? 1 : 0;
  switch (kind) {
    case 'clue': return NEGATE.clue[neg] + '线索 · ' + nameOf(names, 'clue', row.value);
    case 'tag': return NEGATE.tag[neg] + '标签 · ' + nameOf(names, 'tag', row.value);
    case 'equip': return NEGATE.equip[neg] + '物品 · ' + nameOf(names, 'item', row.itemId);
    case 'trait': return NEGATE.trait[neg] + '特质 · ' + nameOf(names, 'trait', row.value);
    case 'done': return NEGATE.done[neg] + '节点 · ' + nameOf(names, 'node', row.nodeId || row.nodeKey);
    case 'visit': return '到过节点 · ' + nameOf(names, 'node', row.nodeId || row.nodeKey) + ' ' + COMPARE_WORD[row.cmp || 'GTE'] + ' ' + row.value + ' 次';
    case 'check': return '检定结果 · ' + nameOf(names, 'node', row.nodeId || row.nodeKey) + ' ' + (row.result === 'FAIL' ? '未通过' : '通过');
    case 'var': { const list = varList(names, row.var);
      return VAR_WORD[list] + ' · ' + nameOf(names, list, row.var) + ' ' + COMPARE_WORD[row.op] + ' ' + row.value; }
    case 'group': return '一组条件 · ' + (row.op === 'OR' ? '任一满足 ' : '全部满足 ') + (row.conditions || []).length + ' 条';
    default: return '不再支持的条件';
  }
}
function signed(n) { n = Number(n) || 0; return n < 0 ? '−' + Math.abs(n) : '+' + n; }
function effectSentence(row, names) {
  switch (effectKind(row)) {
    case 'clue': return '获得线索 · ' + nameOf(names, 'clue', row.value);
    case 'tag': return '加上标签 · ' + nameOf(names, 'tag', row.value);
    case 'item': return '给物品 · ' + nameOf(names, 'item', row.itemId) + (Number(row.quantity) > 1 ? ' ×' + row.quantity : '');
    case 'equip': return '装备物品 · ' + nameOf(names, 'item', row.itemId);
    case 'unequip': return '卸下物品 · ' + nameOf(names, 'item', row.itemId);
    case 'trait': return '加上特质 · ' + nameOf(names, 'trait', row.value);
    case 'untrait': return '去掉特质 · ' + nameOf(names, 'trait', row.value);
    case 'clock': return '进度钟 · ' + nameOf(names, 'clock', row.var) + ' ' + signed(row.value);
    case 'var': { const list = varList(names, row.var);
      return VAR_WORD[list] + ' · ' + nameOf(names, list, row.var) + (row.op === 'SET' ? ' 设为 ' + row.value : ' ' + signed(row.value)); }
    case 'xp': return '给探索值 ' + signed(row.value);
    case 'badge': return '给徽章';
    default: return '不再支持的效果';
  }
}

// ---- 路径 ----
function at(root, path) {
  return path.split('.').reduce((node, key) => (node == null ? undefined : node[key]), root);
}
function parentOf(root, path) {
  const keys = path.split('.'), key = keys.pop();
  return { parent: keys.length ? at(root, keys.join('.')) : root, key };
}
// 顶层「全部满足 / 任一满足」：任一满足 = conditions 只有一条 OR。
function topMode(value) {
  const list = value.conditions || [];
  return list.length === 1 && list[0] && list[0].op === 'OR' ? 'OR' : 'AND';
}
function topListPath(value) { return topMode(value) === 'OR' ? 'conditions.0.conditions' : 'conditions'; }

// ---- 视图行（平铺；一组条件展开后把子句排在它下面，depth 只决定缩进） ----
// cy-segmented 的入参：options [{ label, value }] + 当前 value。
const SEG = (labels, selected) => ({ options: labels.map((label, index) => ({ label, value: String(index) })), value: String(selected) });
const NAME_SEG = (pool, key) => ({ options: pool.map(row => ({ label: row.label, value: row.key })), value: String(key) });
function leafEditor(leaf, negated, names, kind) {
  const ed = { kind };
  if (NEGATE[kind]) ed.neg = SEG(NEGATE[kind], negated ? 1 : 0);
  if (kind === 'var') {
    const list = varList(names, leaf.var), pool = names[list] || [];
    if (pool.length > 1 && pool.length <= 4) ed.names = NAME_SEG(pool, leaf.var);
    else if (pool.length > 4) ed.rename = true;
    ed.cmp = SEG(['至少', '至多', '正好'], Math.max(0, COMPARE.indexOf(leaf.op)));
    ed.amount = Number(leaf.value) || 0; ed.min = 0; ed.max = list === 'clock' ? Math.max(1, ((pool.find(row => row.key === leaf.var) || {}).max) || 12) : 999;
  } else if (kind === 'visit') {
    ed.rename = true; ed.cmp = SEG(['至少', '至多', '正好'], Math.max(0, COMPARE.indexOf(leaf.cmp || 'GTE'))); ed.amount = Number(leaf.value) || 0; ed.min = 0; ed.max = 99;
  } else if (kind === 'check') {
    ed.rename = true; ed.result = SEG(['通过', '未通过'], leaf.result === 'FAIL' ? 1 : 0);
  } else if (kind === 'trait') {
    const pool = names.trait || [];
    if (pool.length > 1 && pool.length <= 4) ed.names = NAME_SEG(pool, leaf.value);
    else ed.rename = pool.length > 1;
  } else if (kind) ed.rename = true;
  return ed;
}
function conditionRows(value, names, open, groups) {
  const openGroups = groups || [];
  const out = [];
  function walk(listPath, list, depth) {
    (list || []).forEach((item, index) => {
      const path = listPath + '.' + index;
      const negated = record(item) && item.op === 'NOT' && record(item.condition);
      const leaf = negated ? item.condition : item, leafPath = negated ? path + '.condition' : path;
      const kind = conditionKind(leaf), isOpen = kind === 'group' ? openGroups.includes(path) : open === path;
      if (kind === 'group') {
        out.push({ type: 'group', path, leafPath, depth, text: (negated ? '不满足 · ' : '') + conditionSentence(leaf, false, names), open: isOpen });
        if (isOpen) {
          out.push({ type: 'groupSeg', path: leafPath, depth: depth + 1, seg: { options: [{ label: '全部满足', value: 'AND' }, { label: '任一满足', value: 'OR' }], value: leaf.op === 'OR' ? 'OR' : 'AND' } });
          walk(leafPath + '.conditions', leaf.conditions, depth + 1);
          if (depth < 2) out.push({ type: 'groupAdd', path: leafPath, depth: depth + 1 });
          out.push({ type: 'groupDel', path, depth: depth + 1 });
        }
        return;
      }
      out.push({ type: 'leaf', path, leafPath, depth, negated, text: conditionSentence(leaf, negated, names), open: isOpen,
        editor: isOpen ? leafEditor(leaf, negated, names, kind === 'var' ? 'var' : kind) : null, unsupported: !kind });
    });
  }
  walk(topListPath(value), at(value, topListPath(value)), 0);
  return out;
}
function effectRows(value, names, open) {
  return (value.effects || []).map((row, index) => {
    const path = 'effects.' + index, kind = effectKind(row), isOpen = open === path;
    let editor = null;
    if (isOpen) {
      editor = { kind };
      if (['clue', 'tag', 'item', 'equip', 'unequip', 'clock'].includes(kind)) editor.rename = true;
      if (kind === 'trait' || kind === 'untrait') editor.rename = (names.trait || []).length > 1;
      if (kind === 'var') {
        const list = varList(names, row.var), pool = names[list] || [];
        editor.rename = pool.length > 1;
        editor.op = SEG(['增加', '减少', '设为'], row.op === 'SET' ? 2 : Number(row.value) < 0 ? 1 : 0);
        editor.amount = Math.abs(Number(row.value) || 0);
      }
      if (kind === 'var') { editor.min = 0; editor.max = 999; }
      if (kind === 'clock') { editor.amount = Number(row.value) || 0; editor.min = 1; editor.max = Number(row.max) || 12; }
      if (kind === 'xp') { editor.amount = Number(row.value) || 0; editor.min = 1; editor.max = 100; }
      if (kind === 'item') { editor.amount = Number(row.quantity) || 1; editor.min = 1; editor.max = 99; }
      editor.hasAmount = editor.amount !== undefined;
    }
    return { type: 'leaf', path, depth: 0, text: effectSentence(row, names), open: isOpen, editor, unsupported: !kind };
  });
}

// ---- 编辑 ----
function defaults(kind, key, names, isEffect) {
  if (isEffect) {
    switch (kind) {
      case 'clue': case 'tag': return { op: 'ADD_TAG', value: key };
      case 'item': return { op: 'ADD_ITEM', itemId: key, quantity: 1 };
      case 'equip': return { op: 'EQUIP', itemId: key };
      case 'unequip': return { op: 'UNEQUIP', itemId: key };
      case 'trait': return { op: 'ADD_CONDITION', value: key };
      case 'untrait': return { op: 'REMOVE_CONDITION', value: key };
      case 'clock': { const clock = (names.clock || []).find(row => row.key === key) || {};
        const max = Math.min(12, Math.max(2, clock.max || 4)); return { op: 'TICK_CLOCK', var: key, value: 1, max }; }
      case 'attr': case 'value': return { op: 'INC', var: key, value: 1 };
      case 'xp': return { op: 'GRANT_XP', value: 1 };
      default: throw Error('不支持的效果');
    }
  }
  switch (kind) {
    case 'clue': case 'tag': return { op: 'HAS_TAG', value: key };
    case 'equip': return { op: 'ITEM_EQUIPPED', itemId: key };
    case 'trait': return { op: 'HAS_CONDITION', value: key };
    case 'attr': case 'clock': case 'value': return { op: 'GTE', var: key, value: 1 };
    case 'visit': return { op: 'VISIT_COUNT', nodeId: key, cmp: 'GTE', value: 1 };
    case 'done': return { op: 'NODE_COMPLETED', nodeId: key };
    case 'check': return { op: 'CHECK_RESULT', nodeId: key, result: 'PASS' };
    case 'group': return { op: 'AND', conditions: [] };
    default: throw Error('不支持的条件');
  }
}
// 返回 { value, path }：path 是新加那一句的位置，便于就地展开。
function add(value, target, kind, key, names) {
  const out = clone(value || { conditions: [], effects: [] });
  out.conditions = out.conditions || []; out.effects = out.effects || [];
  if (target === 'effects') { out.effects.push(defaults(kind, key, names, true)); return { value: out, path: 'effects.' + (out.effects.length - 1) }; }
  const listPath = target === 'conditions' ? topListPath(out) : target + '.conditions';
  const list = at(out, listPath);
  if (!Array.isArray(list)) throw Error('这一组条件已不存在');
  if (list.length >= 10) throw Error('一组最多 10 条条件');
  list.push(defaults(kind, key, names, false));
  return { value: out, path: listPath + '.' + (list.length - 1) };
}
function remove(value, path) {
  const out = clone(value), loc = parentOf(out, path);
  if (!Array.isArray(loc.parent)) throw Error('这一句已不存在');
  loc.parent.splice(Number(loc.key), 1);
  // 任一满足里删到空：回到普通列表，免得留一个空的 OR。
  if (topMode(out) === 'OR' && !out.conditions[0].conditions.length) out.conditions = [];
  return out;
}
function setTopMode(value, mode) {
  const out = clone(value), list = at(out, topListPath(out)) || [];
  if (mode === topMode(out)) return out;
  out.conditions = mode === 'OR' ? [{ op: 'OR', conditions: list }] : list;
  return out;
}
function setGroupMode(value, path, mode) {
  const out = clone(value), group = at(out, path);
  if (!group || !['AND', 'OR'].includes(group.op)) throw Error('这一组条件已不存在');
  group.op = mode === 'OR' ? 'OR' : 'AND';
  return out;
}
function setNegated(value, path, negated) {
  const out = clone(value), loc = parentOf(out, path), row = loc.parent[loc.key];
  const isNot = row && row.op === 'NOT';
  if (negated && !isNot) loc.parent[loc.key] = { op: 'NOT', condition: row };
  if (!negated && isNot) loc.parent[loc.key] = row.condition;
  return out;
}
function patch(value, path, change) {
  const out = clone(value), row = at(out, path);
  if (!record(row)) throw Error('这一句已不存在');
  change(row);
  return out;
}
function setKey(value, path, key) {
  return patch(value, path, row => {
    if ('itemId' in row) row.itemId = key;
    else if ('nodeId' in row || 'nodeKey' in row) { row.nodeId = key; delete row.nodeKey; }
    else if ('var' in row) row.var = key;
    else row.value = key;
  });
}
function setCompare(value, path, index) {
  return patch(value, path, row => {
    const op = COMPARE[Number(index)] || 'GTE';
    if (row.op === 'VISIT_COUNT') row.cmp = op; else row.op = op;
  });
}
function setResult(value, path, index) { return patch(value, path, row => { row.result = Number(index) === 1 ? 'FAIL' : 'PASS'; }); }
function setAmount(value, path, amount) {
  return patch(value, path, row => {
    const n = Math.trunc(Number(amount)) || 0;
    if (row.op === 'ADD_ITEM') row.quantity = Math.min(99, Math.max(1, n));
    else if (row.op === 'TICK_CLOCK') row.value = Math.min(row.max || 12, Math.max(1, n));
    else if (row.op === 'GRANT_XP') row.value = Math.min(100, Math.max(1, n));
    else if (row.op === 'VISIT_COUNT') row.value = Math.min(99, Math.max(0, n));
    else if (row.op === 'INC') row.value = (Number(row.value) < 0 ? -1 : 1) * Math.max(0, n);
    else row.value = n;
  });
}
function setVarOp(value, path, index) {
  return patch(value, path, row => {
    const size = Math.abs(Number(row.value) || 0);
    if (Number(index) === 2) { row.op = 'SET'; row.value = size; } else { row.op = 'INC'; row.value = Number(index) === 1 ? -size : size; }
  });
}
function move(value, path, step) {
  const out = clone(value), loc = parentOf(out, path), from = Number(loc.key), to = from + step;
  if (!Array.isArray(loc.parent) || to < 0 || to >= loc.parent.length) return out;
  const [row] = loc.parent.splice(from, 1); loc.parent.splice(to, 0, row);
  return out;
}
// 改名字那一层要知道：这一句的名字来自哪张名单。
function namesListFor(value, path, names) {
  const row = at(value, path); if (!record(row)) return '';
  if (/^effects\./.test(path)) {
    const kind = effectKind(row);
    return { clue: 'clue', tag: 'tag', item: 'item', equip: 'item', unequip: 'item', trait: 'trait', untrait: 'trait', clock: 'clock' }[kind]
      || (kind === 'var' ? varList(names || {}, row.var) : '');
  }
  const kind = conditionKind(row);
  if (kind === 'var') return varList(names || {}, row.var);
  return { clue: 'clue', tag: 'tag', equip: 'item', trait: 'trait', visit: 'node', done: 'node', check: 'node' }[kind] || '';
}
module.exports = {
  CONDITION_KINDS, EFFECT_KINDS, buildNames, mergeRegistry, editorVariables, conditionSentence, effectSentence,
  conditionRows, effectRows, topMode, add, remove, setTopMode, setGroupMode, setNegated, setKey,
  setCompare, setResult, setAmount, setVarOp, move, namesListFor, varList,
};
