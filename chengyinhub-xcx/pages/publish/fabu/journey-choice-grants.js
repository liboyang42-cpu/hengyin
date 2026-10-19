'use strict';
const conditions = require('./journey-grant-conditions');

const own = (value, key) => !!value && Object.prototype.hasOwnProperty.call(value, key);
const object = value => !!value && typeof value === 'object' && !Array.isArray(value);
const id = value => typeof value === 'string' && /^[a-z][a-z0-9_]{0,31}(?![\s\S])/.test(value);
// Use only canonical IDs: the service trims submitted IDs but compares frozen IDs exactly.
const choiceId = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}(?![\s\S])/.test(value);
const nodeId = value => ['string','number'].includes(typeof value) && /^[1-9][0-9]*$/.test(String(value)) && Number.isSafeInteger(Number(value));
const label = (value, fallback) => typeof value === 'string' && value ? value.slice(0, 100) : fallback;
const javaTrim = value => value.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '');
const emptyDraft = () => ({ editingId: '', nodeIndex: -1, stepIndex: -1, optionIndex: -1, itemIndex: -1, quantity: '' });

function assertSafeRaw(raw) {
  // Same conservative numeric-retention contract as journey-check-settings: do not
  // serialize imports whose numeric tokens JSON.stringify cannot preserve exactly.
  if (typeof raw === 'string') raw.replace(/"(?:\\.|[^"\\])*"|(-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/g, (token, number) => {
    if (number !== undefined) {
      const value = Number(number);
      if (!Number.isFinite(value) || (Number.isInteger(value) && !Number.isSafeInteger(value)) || JSON.stringify(value) !== number)
        throw new Error('原主题配置含不能无损保留的数字，请保留原文并先在原来源修正');
    }
    return token;
  });
  else {
    const visit = value => {
      if (typeof value === 'number' && (!Number.isFinite(value) || Object.is(value, -0) || (Number.isInteger(value) && !Number.isSafeInteger(value)))) throw new Error('原主题配置含不能无损保留的数字');
      if (value && typeof value === 'object') Object.keys(value).forEach(key => visit(value[key]));
    };
    visit(raw);
  }
}
function root(raw) {
  if (typeof raw === 'string' && raw.length > 200000) throw new Error('主题配置长度超过现有上限');
  assertSafeRaw(raw);
  const value = typeof raw === 'string' ? JSON.parse(raw) : raw;
  if (!object(value)) throw new Error('请先配置主题物资目录');
  return JSON.parse(JSON.stringify(value));
}
function branchChoices(raw) {
  const encoded = typeof raw === 'string' ? raw : JSON.stringify(raw);
  if (unescape(encodeURIComponent(encoded)).length > 65536) throw new Error('节点玩法配置超过 64 KiB');
  const config = typeof raw === 'string' ? JSON.parse(raw) : raw;
  if (!object(config) || !object(config.branch) || config.branch.enabled !== true) return null;
  if (config.schemaVersion !== 1) throw new Error('节点玩法版本无法确认');
  const branch = config.branch, steps = branch.steps;
  if (!Array.isArray(steps) || !steps.length || steps.length > 50 || !choiceId(branch.startStepId)) throw new Error('分支步骤格式无法确认');
  const ids = new Set();
  for (const step of steps) {
    if (!object(step) || !choiceId(step.id) || ids.has(step.id)) throw new Error('分支步骤编号无效或重复');
    ids.add(step.id);
  }
  if (!ids.has(branch.startStepId)) throw new Error('分支起点不存在');
  const projected = [], edges = new Map(), terminals = new Set();
  for (const step of steps) {
    if (own(step, 'terminal') && typeof step.terminal !== 'boolean') throw new Error('分支终点标记无法确认');
    if (!Array.isArray(step.options) || step.options.length > 12 || (step.terminal ? step.options.length > 0 : !step.options.length)) throw new Error('分支选项数量无法确认');
    if (step.terminal && !choiceId(step.outcomeCode)) throw new Error('分支终点编号无法确认');
    const seen = new Set(), options = [], next = [];
    for (const option of step.options) {
      if (!object(option) || !choiceId(option.id) || seen.has(option.id) || !choiceId(option.nextStepId) || !ids.has(option.nextStepId)
          || typeof option.label !== 'string' || !javaTrim(option.label) || javaTrim(option.label).length > 80) throw new Error('分支选项编号、名称或目标无法确认');
      seen.add(option.id); options.push({ id: option.id, label: option.label }); next.push(option.nextStepId);
    }
    edges.set(step.id, next); if (step.terminal) terminals.add(step.id);
    if (options.length) projected.push({ id: step.id, label: label(step.title, '步骤 ' + (projected.length + 1)), options });
  }
  const pending = [branch.startStepId], visited = new Set(); let terminal = false;
  while (pending.length) { const next = pending.shift(); if (visited.has(next)) continue; visited.add(next); if (terminals.has(next)) terminal = true; pending.push(...edges.get(next)); }
  if (!terminal) throw new Error('分支没有可达终点');
  // Unreachable steps cannot produce choices through the existing branch flow.
  return projected.filter(step => visited.has(step.id));
}
function choices(data, config, sceneId = '') {
  const nodes = [], blocked = []; let unsaved = false;
  if (!data.editingTopicId || data.editLoaded !== true) return { nodes, blocked, unsaved: true };
  const seen = new Set(); let count = 0, optionCount = 0;
  const chapters = data.formData && data.formData.chapters;
  if (!Array.isArray(chapters)) throw new Error('章节来源无法确认');
  if (config.scenes != null && (!Array.isArray(config.scenes) || config.scenes.length > 50)) throw new Error('场景来源无法确认');
  for (const scene of config.scenes || []) {
    if (!object(scene) || (own(scene, 'nodeConfigs') && !object(scene.nodeConfigs)) || (scene.grants != null && !Array.isArray(scene.grants))) throw new Error('场景覆盖来源无法确认');
  }
  const selectedScene = sceneId === '' ? null : (config.scenes || []).find(scene => scene.id === sceneId);
  if (sceneId !== '' && !selectedScene) throw new Error('所选场景已变化');
  for (const chapter of chapters) {
    if (!object(chapter) || (chapter.nodes != null && !Array.isArray(chapter.nodes))) throw new Error('站点来源无法确认');
    for (const node of chapter.nodes || []) {
      if (!object(node) || !nodeId(node.id)) { unsaved = true; continue; }
      const number = Number(node.id);
      if (seen.has(number) || ++count > 500) throw new Error('已保存站点编号重复或超过 500 项');
      seen.add(number);
      const name = label(chapter.name, '章节') + ' · ' + label(node.name, '站点 ' + number);
      try {
        // Runtime freezes configs by node.templateId; a direct node field is not persisted.
        const overridden = selectedScene && own(selectedScene.nodeConfigs, String(number));
        if (overridden && !object(selectedScene.nodeConfigs[String(number)])) {
          blocked.push({ label: name, reason: '场景节点覆盖必须为 JSON 对象；原值保留并只读' }); continue;
        }
        const source = overridden ? { advancedConfigJson: selectedScene.nodeConfigs[String(number)] } : object(node.templateInfo) ? node.templateInfo : null;
        if (!overridden && (!nodeId(node.templateId) || !source || !nodeId(source.id) || Number(source.id) !== Number(node.templateId))) {
          blocked.push({ label: name, reason: '已保存玩法绑定与加载的模板编号无法对应，请重新加载原草稿' }); continue;
        }
        if (source.advancedConfigJson == null || source.advancedConfigJson === '') continue;
        const steps = branchChoices(source.advancedConfigJson);
        if (!steps || !steps.length) continue;
        if (!selectedScene && (config.scenes || []).some(scene => !object(scene) || (!own(scene, 'grants') && own(scene.nodeConfigs, String(number))))) {
          blocked.push({ label: name, reason: '继承主发放的场景含独立节点玩法，当前入口保留原配置' }); continue;
        }
        optionCount += steps.reduce((sum, step) => sum + step.options.length, 0);
        if (optionCount > 2000) throw new Error('当前入口最多读取 2000 个分支选项');
        nodes.push({ id: number, label: name, steps });
      } catch (error) { blocked.push({ label: name, reason: error.message || '分支配置无法确认' }); }
    }
  }
  return { nodes, blocked, unsaved };
}
function reference(row, nodes) {
  const node = nodes.find(value => value.id === row.nodeId), step = node && node.steps.find(value => value.id === row.step), option = step && step.options.find(value => value.id === row.option);
  return option ? { node, step, option } : null;
}
function simple(row, config, nodes, context) {
  return object(row) && (Object.keys(row).length === 7 || (Object.keys(row).length === 8 && own(row, 'when'))) && Object.keys(row).every(key => ['id','source','nodeId','step','option','itemId','quantity','when'].includes(key))
    && conditions.supported(row.when, config, context || { nodes })
    && id(row.id) && row.source === 'CHOICE' && typeof row.nodeId === 'number' && nodeId(row.nodeId) && choiceId(row.step) && choiceId(row.option)
    && !!reference(row, nodes) && config.items.some(item => item.id === row.itemId) && Number.isInteger(row.quantity) && row.quantity >= 1 && row.quantity <= 99;
}
function settings(config, nodes, context) {
  if (!Array.isArray(config.items) || config.items.length > 200) throw new Error('物资目录格式无法确认');
  const itemIds = new Set();
  for (const item of config.items) {
    if (!object(item) || !id(item.id) || itemIds.has(item.id) || !['ITEM','MEDAL'].includes(item.kind)) throw new Error('物资目录编号无效或重复');
    itemIds.add(item.id);
  }
  if (config.grants != null && (!Array.isArray(config.grants) || config.grants.length > 300)) throw new Error('主题发放配置最多 300 项');
  const seen = new Set(), rows = []; let protectedCount = 0;
  for (const row of config.grants || []) {
    if (!object(row) || !id(row.id) || seen.has(row.id)) throw new Error('发放编号格式不正确或重复');
    seen.add(row.id);
    if (row.source !== 'CHOICE') { protectedCount++; continue; }
    const ref = reference(row, nodes), item = config.items.find(value => value.id === row.itemId);
    rows.push({ id: row.id, choice: ref ? ref.node.label + ' · ' + ref.step.label + ' · ' + ref.option.label : '无法对应当前可编辑的分支选项',
      item: item ? label(item.name, item.id) : '无法对应当前物资', quantity: Number.isInteger(row.quantity) ? row.quantity : '未识别', whenCount: Array.isArray(row.when) ? row.when.length : 0, editable: simple(row, config, nodes, context) });
  }
  return { rows, total: (config.grants || []).length, protectedCount, scopedOverrides: (config.scenes || []).filter(scene => own(scene, 'grants')).length };
}
function serialize(raw, grants) {
  const original = root(raw);
  if (JSON.stringify(original.grants || []) === JSON.stringify(grants || [])) return raw;
  original.grants = grants;
  const output = JSON.stringify(original);
  if (output.length > 200000) throw new Error('主题配置长度超过现有上限');
  return output;
}
module.exports = { emptyDraft, assertSafeRaw, root, choices, simple, settings, reference, serialize };
