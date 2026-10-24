'use strict';

const COUNTER = /^counter\.[a-z][a-z0-9_]{0,47}$/;
const REASONS = ['TASK_COST', 'TEMPLATE_EFFECT', 'CHAPTER_COST', 'ITEM_USE', 'FACILITY_EFFECT'];
const TRIGGERS = ['PER_POINT', 'PER_EVENT', 'FIRST_THRESHOLD'];
let nonce = 0;

function nextKey(attributes) {
  const used = new Set((attributes || []).map(row => row.key));
  let key;
  do { key = 'counter.value_' + Date.now().toString(36) + '_' + (++nonce).toString(36); } while (used.has(key));
  return key;
}

function integer(value, title, min, max) {
  if (value === '' || value == null || !Number.isInteger(Number(value)) || Number(value) < min || Number(value) > max) {
    throw new Error(title + '须为 ' + min + ' 至 ' + max + ' 的整数');
  }
  return Number(value);
}

function attribute(row) {
  if (!COUNTER.test(row.key || '')) throw new Error('数值编号无效');
  const label = String(row.label || '').trim();
  if (!label || label.length > 24) throw new Error('数值名称须为 1 至 24 字');
  const min = integer(row.min, label + '下限', -10000, 10000);
  const max = integer(row.max, label + '上限', min, 10000);
  const initial = integer(row.initial, label + '开局值', min, max);
  return Object.assign({}, row, { label, min, max, initial, visible: row.visible !== false });
}

function links(list, attributes, hpEnabled, hpLabel = '生命值') {
  if (!Array.isArray(list) || list.length > 32) throw new Error('数值关联最多 32 条');
  const ids = new Set();
  return list.map(row => {
    const source = (attributes || []).find(a => a.key === row.sourceKey && COUNTER.test(a.key));
    if (!source) throw new Error('关联来源须为已启用的自定义值');
    if (!hpEnabled || row.targetKey !== 'sys.hp') throw new Error('关联目标只支持已启用的' + hpLabel);
    if (!/^[a-z][a-z0-9_]{0,31}$/.test(row.id || '') || ids.has(row.id)) throw new Error('关联编号无效或重复');
    ids.add(row.id);
    if (!TRIGGERS.includes(row.trigger) || !['EVENT', 'RUN'].includes(row.frequency)) throw new Error('关联触发方式无效');
    if (!Array.isArray(row.allowedReasons) || !row.allowedReasons.length || new Set(row.allowedReasons).size !== row.allowedReasons.length || row.allowedReasons.some(reason => !REASONS.includes(reason))) throw new Error('请选择关联的触发来源');
    const result = { id: row.id, sourceKey: row.sourceKey, targetKey: 'sys.hp', trigger: row.trigger, allowedReasons: row.allowedReasons, frequency: row.frequency };
    if (row.trigger === 'PER_POINT') result.multiplier = integer(row.multiplier, '每点扣减', 1, 999);
    else result.amount = integer(row.amount, '固定扣减', 1, 999);
    if (row.trigger === 'FIRST_THRESHOLD') {
      if (row.frequency !== 'RUN') throw new Error('首次阈值须选择本局一次');
      result.threshold = integer(row.threshold, '触发阈值', Number(source.min), Number(source.max));
    }
    return result;
  });
}

function references(key, formData, runtime, rules) {
  const found = [];
  (runtime.chapters || []).forEach((chapter, index) => (chapter.statCosts || []).forEach(cost => {
    if (cost.statKey === key) found.push('第' + (Number.isInteger(chapter.index) ? chapter.index + 1 : index + 1) + '章费用');
  }));
  (runtime.chapters || []).forEach((chapter, index) => {
    if ((key === 'sys.hp' && chapter.hpCost > 0) || (key === 'sys.luck' && chapter.luckCost > 0)) found.push('第' + (Number.isInteger(chapter.index) ? chapter.index + 1 : index + 1) + '章费用');
  });
  (runtime.items || []).forEach(item => { if (item.statKey === key || (key === 'sys.hp' && item.stat === 'HP') || (key === 'sys.luck' && item.stat === 'LUCK')) found.push('物品「' + (item.name || item.id) + '」效果'); });
  (rules.statLinks || []).forEach(link => { if (link.sourceKey === key || link.targetKey === key) found.push('关联「' + link.id + '」'); });
  const walk = (node, location, path) => {
    if (typeof node === 'string' && /^[\s]*[\[{]/.test(node)) {
      try { walk(JSON.parse(node), location, path); } catch (_) {}
      return;
    }
    if (Array.isArray(node)) { node.forEach((row, i) => walk(row, location, path + '[' + (i + 1) + ']')); return; }
    if (!node || typeof node !== 'object') return;
    for (const field of ['statKey', 'sourceKey', 'targetKey']) if (node[field] === key) found.push(location + ' · ' + path + field);
    Object.keys(node).forEach(field => { if (!['statKey', 'sourceKey', 'targetKey'].includes(field)) walk(node[field], location, path + field + '.'); });
  };
  (formData.chapters || []).forEach((chapter, ci) => (chapter.nodes || []).forEach((node, ni) => {
    walk(node, '第' + (ci + 1) + '章节点「' + (node.name || ni + 1) + '」玩法绑定', '');
  }));
  return [...new Set(found)];
}

module.exports = { nextKey, attribute, links, references, COUNTER, REASONS, TRIGGERS };
