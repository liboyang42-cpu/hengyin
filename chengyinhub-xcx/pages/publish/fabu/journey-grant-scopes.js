'use strict';
const choice = require('./journey-choice-grants');
const own = (value, key) => !!value && Object.prototype.hasOwnProperty.call(value, key);
const copy = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
const validId = value => typeof value === 'string' && /^[a-z][a-z0-9_]{0,31}(?![\s\S])/.test(value);
function scenes(config) {
  if (config.scenes != null && (!Array.isArray(config.scenes) || config.scenes.length > 50)) throw new Error('场景目录无法确认');
  const seen = new Set();
  return (config.scenes || []).map(scene => {
    if (!scene || typeof scene !== 'object' || Array.isArray(scene) || !validId(scene.id) || seen.has(scene.id) || typeof scene.name !== 'string' || scene.name.length > 40) throw new Error('场景编号或名称无法确认');
    seen.add(scene.id);
    if (scene.grants != null && (!Array.isArray(scene.grants) || scene.grants.length > 300)) throw new Error('场景发放列表无法确认');
    return scene;
  });
}
function target(config, id) {
  const list = scenes(config);
  if (id === '') return config;
  const scene = list.find(row => row.id === id);
  if (!scene) throw new Error('所选场景已变化，请重新打开');
  return scene;
}
function inherited(config, id) { return id !== '' && !own(target(config, id), 'grants'); }
function view(config, id) {
  const row = target(config, id);
  return Object.assign(copy(config), { grants: copy(inherited(config, id) ? config.grants : row.grants) });
}
function project(config, id) {
  const list = scenes(config), row = target(config, id), follows = inherited(config, id);
  const options = [{ id: '', label: '主配置' }].concat(list.map(scene => ({ id: scene.id, label: scene.name })));
  return { options, index: options.findIndex(option => option.id === id), id, label: id === '' ? '主配置' : row.name,
    inherited: follows, editable: !follows, canRestore: id !== '' && !follows && (row.grants == null || row.grants.length === 0),
    mode: id === '' ? '主配置' : follows ? '沿用主配置（只读）' : row.grants === null ? '独立 null 列表（不发放）' : row.grants.length === 0 ? '独立空列表（不发放）' : '独立物资关联' };
}
function replace(config, id, grants) {
  if (inherited(config, id)) throw new Error('请先明确创建独立配置');
  const result = copy(config); target(result, id).grants = copy(grants); return result;
}
function copyRoot(config, id) {
  if (id === '' || !inherited(config, id)) throw new Error('当前场景不是沿用主配置');
  const result = copy(config); target(result, id).grants = own(config, 'grants') ? copy(config.grants) : []; return result;
}
function restore(config, id) {
  if (!project(config, id).canRestore) throw new Error('只允许空的独立列表恢复沿用；已有条目须先按原规则处理');
  const result = copy(config); delete target(result, id).grants; return result;
}
function ids(config) {
  const result = new Set();
  [config].concat(scenes(config)).forEach(row => (row.grants || []).forEach(grant => { if (grant && typeof grant.id === 'string') result.add(grant.id); }));
  return result;
}
function same(a, b) {
  return own(a, 'grants') === own(b, 'grants') && JSON.stringify(a.grants || []) === JSON.stringify(b.grants || []);
}
function serialize(raw, initial, current) {
  const output = choice.root(raw); let changed = false;
  const apply = (before, after, destination) => {
    if (same(before, after)) return;
    changed = true;
    if (own(after, 'grants')) destination.grants = copy(after.grants); else delete destination.grants;
  };
  apply(initial, current, output);
  const beforeScenes = scenes(initial), afterScenes = scenes(current);
  if (beforeScenes.length !== afterScenes.length || beforeScenes.some((row, index) => row.id !== afterScenes[index].id)) throw new Error('场景目录已变化，请重新打开');
  beforeScenes.forEach((before, index) => apply(before, afterScenes[index], target(output, before.id)));
  if (!changed) return raw;
  const value = JSON.stringify(output);
  if (value.length > 200000) throw new Error('主题配置长度超过现有上限');
  return value;
}
module.exports = { project, view, inherited, replace, copyRoot, restore, ids, serialize };
