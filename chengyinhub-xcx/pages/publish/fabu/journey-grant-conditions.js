'use strict';
const initial = require('../utils/publish/story-initial-state');
const COMPARISONS = [{ op: 'EQ', label: '等于' }, { op: 'NE', label: '不等于' }, { op: 'GT', label: '大于' }, { op: 'GTE', label: '大于或等于' }, { op: 'LT', label: '小于' }, { op: 'LTE', label: '小于或等于' }];
const SYSTEM = [{ key: 'sys.hp', label: '本局生命值' }, { key: 'sys.luck', label: '本局幸运值' }, { key: 'sys.actions', label: '本局行动用量（含本次计数）' }];
const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
const object = value => !!value && typeof value === 'object' && !Array.isArray(value);
const clone = value => JSON.parse(JSON.stringify(value));
function context(raw, nodes) {
  let keys = [], enabled = false, error = '';
  try { const rules = initial.parse(raw); keys = initial.normalizeAttributes(rules.attributes).filter(row => /^(clue|relation|counter)\./.test(row.key)).map(row => ({ key: row.key, label: row.label + '（' + row.key + '）' })); enabled = rules.stateEnabled === true; }
  catch (_) { error = '初始属性声明无法读取，不能选择声明属性；原配置保持不变'; }
  return { nodes: clone(nodes || []), keys, enabled, error };
}
function sources(ctx) {
  return [{ key: 'ITEM_HELD', label: '当前持有物品或勋章', kind: 'item' }, { key: 'ITEM_ACQUIRED', label: '本局累计获得物品或勋章', kind: 'item' }, { key: 'NODE_COMPLETED', label: '已完成指定站点', kind: 'node' }]
    .concat(SYSTEM, ctx && ctx.keys || []).map(row => Object.assign({ kind: 'state' }, row));
}
function shape(row, config, ctx) {
  if (!object(row)) throw new Error('条件格式不支持');
  const fields = Object.keys(row), source = sources(ctx).find(value => value.key === row.op || value.key === row.var);
  if (!source) throw new Error('条件引用不在当前支持的目录或声明中');
  let expected, result;
  if (source.kind === 'item') {
    expected = ['op','itemId','quantity'];
    if (typeof row.itemId !== 'string' || !/^[a-z][a-z0-9_]{0,31}(?![\s\S])/.test(row.itemId) || !config.items.some(item => item.id === row.itemId) || !Number.isInteger(row.quantity) || row.quantity < 1 || row.quantity > 999) throw new Error('物资前提须引用当前目录且数量为 1 到 999');
    result = { op: source.key, itemId: row.itemId, quantity: row.quantity };
  } else if (source.kind === 'node') {
    expected = ['op','nodeId'];
    if (!Number.isSafeInteger(row.nodeId) || row.nodeId < 1 || !(ctx && ctx.nodes || []).some(node => node.id === row.nodeId)) throw new Error('完成前提须引用当前已保存站点');
    result = { op: 'NODE_COMPLETED', nodeId: row.nodeId };
  } else {
    expected = ['op','var','value'];
    if (!COMPARISONS.some(value => value.op === row.op) || row.var !== source.key || !Number.isInteger(row.value) || Math.abs(row.value) > 999999) throw new Error('属性前提须明确比较方式与 -999999 到 999999 的整数');
    result = { op: row.op, var: row.var, value: row.value };
  }
  if (fields.length !== expected.length || fields.some(key => !expected.includes(key))) throw new Error('扩展条件保持只读');
  return result;
}
function read(value, config, ctx) {
  if (value == null) return [];
  if (!Array.isArray(value) || value.length > 30) throw new Error('每条关联最多 30 项 AND 前提');
  return Array.from(value, row => { shape(row, config, ctx); return clone(row); });
}
function supported(value, config, ctx) { try { read(value, config, ctx); return true; } catch (_) { return false; } }
function snapshot(value, config, ctx) { return JSON.stringify(read(value, config, ctx).map(row => shape(row, config, ctx))); }
function apply(grant, conditions, config, ctx) {
  read(conditions, config, ctx);
  const result = Object.assign({}, grant);
  if (snapshot(grant.when, config, ctx) === snapshot(conditions, config, ctx)) return result;
  if (conditions.length) result.when = clone(conditions); else delete result.when;
  return result;
}
function label(row, config, ctx) {
  const value = shape(row, config, ctx), source = sources(ctx).find(item => item.key === value.op || item.key === value.var);
  if (source.kind === 'item') { const item = config.items.find(item => item.id === value.itemId); return source.label + '“' + String(item.name || item.id).slice(0, 100) + '”至少 ' + value.quantity + ' 个'; }
  if (source.kind === 'node') return '已完成 ' + ctx.nodes.find(node => node.id === value.nodeId).label;
  return source.label + COMPARISONS.find(item => item.op === value.op).label + ' ' + value.value;
}
const emptyDraft = () => ({ editingId: '', sourceIndex: -1, comparisonIndex: -1, itemIndex: -1, nodeIndex: -1, value: '' });
function compose(draft, config, ctx) {
  const source = sources(ctx)[draft.sourceIndex];
  if (!source) throw new Error('请明确选择前提类型');
  let row;
  if (source.kind === 'node') {
    const node = ctx.nodes[draft.nodeIndex]; if (!node) throw new Error('请选择已保存站点'); row = { op: source.key, nodeId: node.id };
  } else {
    if (typeof draft.value !== 'string' || draft.value.length > 16 || !/^-?[0-9]+$/.test(draft.value)) throw new Error('请明确填写完整整数');
    if (source.kind === 'item') { const item = config.items[draft.itemIndex]; if (!item) throw new Error('请选择当前目录物资'); row = { op: source.key, itemId: item.id, quantity: Number(draft.value) }; }
    else { const comparison = COMPARISONS[draft.comparisonIndex]; if (!comparison) throw new Error('请选择比较方式'); row = { op: comparison.op, var: source.key, value: Number(draft.value) }; }
  }
  return shape(row, config, ctx);
}
function draft(row, id, config, ctx) {
  const value = shape(row, config, ctx), result = emptyDraft(); result.editingId = id;
  result.sourceIndex = sources(ctx).findIndex(item => item.key === value.op || item.key === value.var);
  if (own(value, 'itemId')) { result.itemIndex = config.items.findIndex(item => item.id === value.itemId); result.value = String(value.quantity); }
  else if (own(value, 'nodeId')) result.nodeIndex = ctx.nodes.findIndex(node => node.id === value.nodeId);
  else { result.comparisonIndex = COMPARISONS.findIndex(item => item.op === value.op); result.value = String(value.value); }
  return result;
}
module.exports = { context, sources, comparisons: COMPARISONS, shape, read, supported, snapshot, apply, label, emptyDraft, compose, draft };
