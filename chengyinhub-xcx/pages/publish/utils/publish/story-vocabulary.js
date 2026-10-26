'use strict';
// Author-side shape checks and preview only. Server StoryVocabulary/TopicRouteEngine remain authoritative.
const conditions = ['AND', 'OR', 'NOT', 'VISIT_COUNT', 'ROLE_IN', 'HAS_CONDITION', 'ITEM_EQUIPPED', 'CHECK_RESULT'];
const effects = ['ADD_ITEM', 'GRANT_BADGE', 'GRANT_XP', 'ADD_CONDITION', 'REMOVE_CONDITION', 'EQUIP', 'UNEQUIP', 'TICK_CLOCK'];
const nodeOps = ['NODE_COMPLETED', 'VISIT_COUNT', 'CHECK_RESULT'];
const compareOps = ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE'];
const code = /^[a-z][a-z0-9_]{0,31}$/;
const record = v => !!v && typeof v === 'object' && !Array.isArray(v);
const integer = (v, min, max) => Number.isInteger(v) && v >= min && v <= max;
const text = (v, pattern) => typeof v === 'string' && pattern.test(v);
function fields(row, allowed, path, error) {
  Object.keys(row).forEach(key => { if (!allowed.includes(key)) error(path + '.' + key, '不支持的字段：' + key); });
}
function validateCondition(row, path, depth, nodeIds, error, child) {
  const op = row.op;
  const allowed = { AND: ['conditions'], OR: ['conditions'], NOT: ['condition'], VISIT_COUNT: ['nodeId', 'nodeKey', 'cmp', 'value'],
    ROLE_IN: ['values'], HAS_CONDITION: ['value'], ITEM_EQUIPPED: ['itemId'], CHECK_RESULT: ['nodeId', 'nodeKey', 'result'] };
  fields(row, ['op', ...allowed[op]], path, error);
  if (['AND', 'OR', 'NOT'].includes(op)) {
    if (depth >= 3) { error(path, '条件组合最多嵌套 3 层'); return; }
    if (op === 'NOT') {
      if (!record(row.condition)) error(path + '.condition', '必须是条件对象');
      else child(row.condition, path + '.condition', depth + 1);
    } else if (!Array.isArray(row.conditions) || row.conditions.length < 1 || row.conditions.length > 10) {
      error(path + '.conditions', 'conditions须为1至10条的数组');
    } else row.conditions.forEach((value, i) => {
      const at = path + '.conditions[' + i + ']';
      if (!record(value)) error(at, '必须是条件对象'); else child(value, at, depth + 1);
    });
  } else if (op === 'VISIT_COUNT' || op === 'CHECK_RESULT') {
    const id = String(row.nodeId || row.nodeKey || '');
    if (!nodeIds.has(id) || typeof row.nodeId === 'number' && !Number.isSafeInteger(row.nodeId)) {
      error(path + '.nodeId', '条件引用的节点不属于当前主题', 'DANGLING_CONDITION_NODE', id);
    }
    if (op === 'VISIT_COUNT') {
      if (!integer(row.value, 0, 99)) error(path + '.value', '访问次数须为0至99的整数');
      if (row.cmp !== undefined && !compareOps.includes(row.cmp)) error(path + '.cmp', '不支持的比较运算符');
    } else if (!['PASS', 'FAIL'].includes(row.result)) error(path + '.result', '检定结果须为PASS或FAIL');
  } else if (op === 'ROLE_IN') {
    if (!Array.isArray(row.values) || row.values.length < 1 || row.values.length > 10
        || new Set(row.values).size !== row.values.length || row.values.some(v => !text(v, /^[A-Z][A-Z0-9_]{0,31}$/))) {
      error(path + '.values', '角色代码须为1至10个不重复的大写代码');
    }
  } else if (op === 'HAS_CONDITION' && !text(row.value, code)) error(path + '.value', '状态代码格式不正确');
  else if (op === 'ITEM_EQUIPPED' && !text(row.itemId, code)) error(path + '.itemId', '物品代码格式不正确');
}
function validateEffect(row, path, variables, error) {
  const allowed = { ADD_ITEM: ['itemId', 'quantity'], GRANT_BADGE: ['value'], GRANT_XP: ['value', 'id'],
    ADD_CONDITION: ['value'], REMOVE_CONDITION: ['value'], EQUIP: ['itemId', 'slot'], UNEQUIP: ['itemId'], TICK_CLOCK: ['var', 'value', 'max'] };
  fields(row, ['op', ...allowed[row.op]], path, error);
  if (['ADD_ITEM', 'EQUIP', 'UNEQUIP'].includes(row.op) && !text(row.itemId, code)) error(path + '.itemId', '物品代码格式不正确');
  if (row.op === 'ADD_ITEM' && !integer(row.quantity, 1, 99)) error(path + '.quantity', '数量须为1至99的整数');
  if (row.op === 'GRANT_BADGE' && !text(row.value, /^[a-z][a-z0-9_]{0,47}$/)) error(path + '.value', '徽章代码格式不正确');
  if (row.op === 'GRANT_XP') {
    if (!integer(row.value, 1, 100)) error(path + '.value', 'XP须为1至100的整数');
    if (row.id !== undefined && !text(row.id, /^[A-Za-z0-9_-]{1,32}$/)) error(path + '.id', '发放标识格式不正确');
  }
  if (['ADD_CONDITION', 'REMOVE_CONDITION'].includes(row.op) && !text(row.value, code)) error(path + '.value', '状态代码格式不正确');
  if (row.op === 'EQUIP' && row.slot !== undefined && !text(row.slot, code)) error(path + '.slot', '装备位置格式不正确');
  if (row.op === 'TICK_CLOCK') {
    if (!text(row.var, /^counter\.[a-z][a-z0-9_]{0,47}$/) || !Object.prototype.hasOwnProperty.call(variables, row.var)) error(path + '.var', '进度钟必须引用已声明counter变量');
    if (!integer(row.max, 2, 12)) error(path + '.max', '进度钟须为2至12格');
    if (!integer(row.value, 1, row.max)) error(path + '.value', '推进量须为1至总格数的整数');
  }
}
function mapNodes(row, mapper, depth = 0) {
  const out = JSON.parse(JSON.stringify(row));
  if (!record(out) || depth > 3) return out;
  if (nodeOps.includes(out.op)) return mapper(out);
  if (out.op === 'NOT' && record(out.condition)) out.condition = mapNodes(out.condition, mapper, depth + 1);
  if (['AND', 'OR'].includes(out.op) && Array.isArray(out.conditions)) out.conditions = out.conditions.map(c => mapNodes(c, mapper, depth + 1));
  return out;
}
function compare(a, b, op) {
  switch (op) { case 'EQ': return a === b; case 'NE': return a !== b; case 'GT': return a > b;
    case 'GTE': return a >= b; case 'LT': return a < b; case 'LTE': return a <= b;
    default: throw Error('不支持的比较运算符：' + op); }
}
function conditionValue(row, state, child, depth) {
  if (['AND', 'OR', 'NOT'].includes(row.op)) {
    if (depth >= 3) throw Error('条件组合最多嵌套3层');
    if (row.op === 'NOT') return !child(row.condition, state, depth + 1);
    return row.op === 'AND' ? row.conditions.every(c => child(c, state, depth + 1)) : row.conditions.some(c => child(c, state, depth + 1));
  }
  if (row.op === 'ROLE_IN') return !!state.actorRoleCode && row.values.includes(state.actorRoleCode);
  if (row.op === 'HAS_CONDITION') return (state.statusConditions || []).includes(row.value);
  if (row.op === 'ITEM_EQUIPPED') return Object.values(state.equipped || {}).includes(row.itemId)
    && Number(((state.themeRuntime || {}).inventory || {})[row.itemId] || 0) > 0;
  if (row.op === 'CHECK_RESULT') {
    const value = (state.variables || {})['sys.passed.' + row.nodeId];
    return row.result === 'PASS' ? value === 1 : row.result === 'FAIL' ? value === 0 : false;
  }
  const log = state.decisionLog || [], id = String(row.nodeId);
  const first = log.find(r => r.fromNodeId != null);
  const count = first ? log.filter(r => String(r.toNodeId) === id).length + (String(first.fromNodeId) === id ? 1 : 0)
    : (state.visitedNodeIds || []).map(String).includes(id) ? 1 : 0;
  return compare(count, row.value, row.cmp || 'GTE');
}
// Isolated author simulation state; grants stay pending and never call a reward service.
function applyEffect(effect, state) {
  const node = String(state.currentNodeId == null ? 'run' : state.currentNodeId);
  state.statusConditions = state.statusConditions || [];
  state.equipped = state.equipped || {};
  state.storyGrants = state.storyGrants || {};
  const runtime = state.themeRuntime || {};
  switch (effect.op) {
    case 'ADD_ITEM': {
      if (state.rewardCeilingXpBadgeOnly) return;
      if (!record(runtime.pending)) throw Error('本主题未启用物资背包，不能ADD_ITEM');
      const key = 'story:' + node + ':' + effect.itemId;
      runtime.sources = runtime.sources || {};
      if (!runtime.sources[key]) {
        runtime.sources[key] = true;
        runtime.pending[key] = { itemId: effect.itemId, quantity: effect.quantity };
      }
      return;
    }
    case 'GRANT_BADGE': {
      const key = 'BADGE:' + node + ':' + effect.value;
      if (!state.storyGrants[key]) state.storyGrants[key] = { kind: 'BADGE', code: effect.value, node };
      return;
    }
    case 'GRANT_XP': {
      const key = 'XP:' + node + ':' + (effect.id || 'xp');
      if (!state.storyGrants[key]) state.storyGrants[key] = { kind: 'XP', amount: effect.value, node };
      return;
    }
    case 'ADD_CONDITION':
      if (!state.statusConditions.includes(effect.value)) state.statusConditions.push(effect.value);
      return;
    case 'REMOVE_CONDITION':
      state.statusConditions = state.statusConditions.filter(v => v !== effect.value); return;
    case 'EQUIP':
      if (Number((runtime.inventory || {})[effect.itemId] || 0) > 0) state.equipped[effect.slot || effect.itemId] = effect.itemId;
      return;
    case 'UNEQUIP':
      Object.keys(state.equipped).forEach(slot => { if (state.equipped[slot] === effect.itemId) delete state.equipped[slot]; }); return;
    case 'TICK_CLOCK': {
      const base = Math.max(0, Number(state.variables[effect.var] || 0));
      state.variables[effect.var] = Math.max(base, Math.min(effect.max, base + effect.value)); return;
    }
    default: throw Error('不支持的路线效果：' + effect.op);
  }
}
module.exports = { conditions, effects, nodeOps, validateCondition, validateEffect, mapNodes, conditionValue, applyEffect };
