'use strict';

// Existing JourneyRulesValidator fields only. Null integer/bool/object fields use defaults.
const FIELDS = [
  { path: 'tiers.easy.dc', label: '简单 · 目标数 DC', fallback: 10, min: 5, max: 25 },
  { path: 'tiers.medium.dc', label: '普通 · 目标数 DC', fallback: 13, min: 5, max: 25 },
  { path: 'tiers.hard.dc', label: '困难 · 目标数 DC', fallback: 16, min: 5, max: 25 },
  { path: 'tiers.easy.damage', label: '简单 · 失败基础扣血', fallback: 1, min: 0, max: 5 },
  { path: 'tiers.medium.damage', label: '普通 · 失败基础扣血', fallback: 2, min: 0, max: 5 },
  { path: 'tiers.hard.damage', label: '困难 · 失败基础扣血', fallback: 3, min: 0, max: 5 },
  { path: 'modCap', label: '加值上限', fallback: 6, min: 0, max: 10 },
  { path: 'fumbleExtraDamage', label: '大失败额外扣血', fallback: 1, min: 0, max: 3 },
  { path: 'recovery.stationEndIfHurt', label: '本站受伤后的站末恢复量', fallback: 2, min: 0, max: 'hp' },
  { path: 'recovery.stageRestHpFloor', label: '阶段休整后的精力下限', fallback: 6, min: 0, max: 'hp' },
  { path: 'recovery.stageRestLuckFloor', label: '阶段休整后的幸运下限', fallback: 1, min: 0, max: 'luck' },
  { path: 'recovery.exhaustRestHp', label: '力竭休整恢复的精力值', fallback: 5, min: 0, max: 'hp' },
];
const own = (object, key) => !!object && Object.prototype.hasOwnProperty.call(object, key);
const copy = value => JSON.parse(JSON.stringify(value));
const blank = value => /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/.test(value);
// No lossless JSON parser is present in the pinned initial-state helper. Before any
// edited serialization, refuse numeric tokens that JSON.stringify cannot retain exactly.
// Quoted strings (including escaped quotes/backslashes) are skipped, never interpreted.
function numericReadOnly(raw) {
  if (typeof raw === 'string') {
    let unsafe = false;
    raw.replace(/"(?:\\.|[^"\\])*"|(-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/g, (token, number) => {
      if (number !== undefined) {
        const value = Number(number);
        if (!Number.isFinite(value) || (Number.isInteger(value) && !Number.isSafeInteger(value)) || JSON.stringify(value) !== number) unsafe = true;
      }
      return token;
    });
    return unsafe;
  }
  function unsafeValue(value) {
    if (typeof value === 'number') return !Number.isFinite(value) || Object.is(value, -0) || (Number.isInteger(value) && !Number.isSafeInteger(value));
    return !!value && typeof value === 'object' && Object.keys(value).some(key => unsafeValue(value[key]));
  }
  return unsafeValue(raw);
}
const READ_ONLY_MESSAGE = '原配置含不能无损保留的数字，当前只读；完成或关闭均保留原文，请先在原来源修正后再编辑';
function rawObject(raw) {
  if (raw == null || (typeof raw === 'string' && blank(raw))) return {};
  if (typeof raw === 'string') {
    if (raw.length > 16384) throw new Error('旅程规则超过 16 KiB，请保留原配置');
    try { raw = JSON.parse(raw); } catch (_) { throw new Error('旅程规则无法读取'); }
  }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('旅程规则须为对象');
  return copy(raw);
}
function objectAt(parent, key) {
  const value = parent && parent[key];
  if (value == null) return null;
  if (typeof value !== 'object' || Array.isArray(value)) throw new Error(key + ' 须为对象');
  return value;
}
function at(root, path) {
  const parts = path.split('.'); let parent = root;
  for (const part of parts.slice(0, -1)) parent = objectAt(parent, part);
  const key = parts[parts.length - 1];
  return !own(parent, key) ? { mode: 'missing' } : parent[key] === null ? { mode: 'null' } : { mode: 'value', value: parent[key] };
}
function integer(root, path, fallback, min, max) {
  const row = at(root, path), value = row.mode === 'value' ? row.value : fallback;
  if (typeof value !== 'number' || !Number.isInteger(value) || value < -2147483648 || value > 2147483647 || value < min || value > max) throw new Error(path + ' 须为 ' + min + ' 到 ' + max + ' 的整数');
  return value;
}
function enabledMode(root) {
  const state = at(root, 'checkEnabled');
  if (state.mode !== 'value') return state.mode;
  if (typeof state.value !== 'boolean') throw new Error('checkEnabled 须为布尔值');
  return state.value ? 'true' : 'false';
}
function limits(root) {
  return { hp: integer(root, 'hp.max', 10, 1, 20), luck: integer(root, 'luck.max', 3, 0, 5) };
}
function normalize(raw) {
  const root = rawObject(raw), bound = limits(root), mode = enabledMode(root), values = {};
  FIELDS.forEach(field => { values[field.path] = integer(root, field.path, field.fallback, field.min, typeof field.max === 'string' ? bound[field.max] : field.max); });
  if (!(values['tiers.easy.dc'] < values['tiers.medium.dc'] && values['tiers.medium.dc'] < values['tiers.hard.dc'])) throw new Error('三档目标数须为简单 < 普通 < 困难');
  return { checkEnabled: mode === 'true', hpMax: bound.hp, luckMax: bound.luck, values };
}
function project(raw) {
  const root = rawObject(raw), bound = limits(root), mode = enabledMode(root);
  const options = [{ mode: 'missing', label: '未配置（系统默认关闭）' }, { mode: 'false', label: '明确关闭检定' }, { mode: 'true', label: '明确开启检定' }];
  if (mode === 'null') options.push({ mode: 'null', label: '保留已有 null（系统默认关闭）' });
  const fields = FIELDS.map(field => {
    const row = at(root, field.path);
    if (row.mode === 'value' && !['number', 'string', 'boolean'].includes(typeof row.value)) throw new Error(field.path + ' 内容无法编辑，请保留原配置');
    return { path: field.path, label: field.label, mode: row.mode, value: row.mode === 'value' ? String(row.value) : '',
      min: field.min, max: typeof field.max === 'string' ? bound[field.max] : field.max, fallback: field.fallback,
      presence: row.mode === 'missing' ? '未配置' : row.mode === 'null' ? '已有 null，使用系统缺省' : '已明确配置' };
  });
  const readOnly = numericReadOnly(raw);
  let error = ''; try { normalize(root); } catch (failure) { error = failure.message; }
  if (readOnly) error = READ_ONLY_MESSAGE;
  return { readOnly, enabledMode: mode, enabledOptions: options, enabledIndex: options.findIndex(option => option.mode === mode), fields, hpMax: bound.hp, luckMax: bound.luck, error };
}
function set(root, path, mode, value) {
  const parts = path.split('.'); let parent = root;
  for (const key of parts.slice(0, -1)) {
    if (mode === 'missing' && parent[key] == null) return;
    if (parent[key] == null) parent[key] = {};
    parent = parent[key];
  }
  const key = parts[parts.length - 1];
  if (mode === 'missing') delete parent[key]; else parent[key] = value;
}
function apply(raw, draft) {
  const root = rawObject(raw), before = project(raw);
  if (!draft || !Array.isArray(draft.fields) || draft.fields.length !== FIELDS.length) throw new Error('规则编辑状态已变化，请重新打开');
  let changed = false;
  if (draft.enabledMode !== before.enabledMode) {
    if (!before.enabledOptions.some(option => option.mode === draft.enabledMode)) throw new Error('检定开关选项不支持');
    set(root, 'checkEnabled', draft.enabledMode, draft.enabledMode === 'null' ? null : draft.enabledMode === 'true'); changed = true;
  }
  FIELDS.forEach((field, index) => {
    const row = draft.fields[index], old = before.fields[index];
    if (!row || row.path !== field.path || typeof row.value !== 'string' || !['missing','null','value'].includes(row.mode)) throw new Error('规则字段不支持');
    if (row.mode !== 'value' && row.value !== '') throw new Error('缺省规则值须留空');
    if (row.mode === old.mode && row.value === old.value) return;
    if (row.mode === 'null' && old.mode !== 'null') throw new Error('不能新建 null 规则值');
    if (row.mode === 'value' && (row.value.length > 16 || !/^-?[0-9]+$/.test(row.value))) throw new Error(field.label + ' 请填写完整整数');
    set(root, field.path, row.mode, row.mode === 'null' ? null : Number(row.value)); changed = true;
  });
  if (!changed) return raw;
  if (before.readOnly) throw new Error(READ_ONLY_MESSAGE);
  if (!Object.keys(rawObject(raw)).length) root.schemaVersion = 1;
  normalize(root);
  const output = JSON.stringify(root);
  if (unescape(encodeURIComponent(output)).length > 16384) throw new Error('旅程规则超过 16 KiB');
  return output;
}
module.exports = { project, apply, normalize };
