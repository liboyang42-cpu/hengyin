const templateIdOf = require('./template-adoption.js').id;
'use strict';

const HASH = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
const K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];
const rotate = (x, n) => (x >>> n) | (x << (32 - n));

function sha256(value) {
  const bytes = [];
  for (let i = 0; i < value.length; i++) {
    let cp = value.charCodeAt(i);
    if (cp >= 0xd800 && cp <= 0xdbff) cp = 0x10000 + ((cp - 0xd800) << 10) + (value.charCodeAt(++i) - 0xdc00);
    if (cp < 0x80) bytes.push(cp);
    else if (cp < 0x800) bytes.push(0xc0 | (cp >> 6), 0x80 | (cp & 63));
    else if (cp < 0x10000) bytes.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
    else bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
  }
  const bitLength = bytes.length * 8;
  bytes.push(0x80);
  while (bytes.length % 64 !== 56) bytes.push(0);
  for (let i = 7; i >= 0; i--) bytes.push(Math.floor(bitLength / 2 ** (i * 8)) & 255);
  const h = HASH.slice();
  for (let offset = 0; offset < bytes.length; offset += 64) {
    const w = new Array(64);
    for (let i = 0; i < 16; i++) w[i] = (bytes[offset + i * 4] << 24) | (bytes[offset + i * 4 + 1] << 16) | (bytes[offset + i * 4 + 2] << 8) | bytes[offset + i * 4 + 3];
    for (let i = 16; i < 64; i++) {
      const x = w[i - 15], y = w[i - 2];
      w[i] = (w[i - 16] + (rotate(x, 7) ^ rotate(x, 18) ^ (x >>> 3)) + w[i - 7] + (rotate(y, 17) ^ rotate(y, 19) ^ (y >>> 10))) | 0;
    }
    let [a, b, c, d, e, f, g, z] = h;
    for (let i = 0; i < 64; i++) {
      const s1 = rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25);
      const t1 = (z + s1 + ((e & f) ^ (~e & g)) + K[i] + w[i]) | 0;
      const s0 = rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22);
      const t2 = (s0 + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      z = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    [a, b, c, d, e, f, g, z].forEach((v, i) => { h[i] = (h[i] + v) | 0; });
  }
  return h.map(v => (v >>> 0).toString(16).padStart(8, '0')).join('');
}

function parse(raw) {
  if (!raw) return {};
  return typeof raw === 'string' ? JSON.parse(raw) : raw;
}

function slotsOf(template) {
  const config = parse(template && template.advancedConfigJson);
  const slots = config && config.interactionRules && config.interactionRules.resourceSlots;
  return Array.isArray(slots) ? slots : [];
}

function slotDigest(slots) {
  const canonical = slots.slice().sort((a, b) => a.key < b.key ? -1 : a.key > b.key ? 1 : 0)
    .map(s => ({ key: s.key, kind: s.kind, label: s.label, required: true }));
  return sha256(JSON.stringify(canonical));
}

function nodeKey(node) {
  const id = Number(node && node.id);
  if (Number.isSafeInteger(id) && id > 0) return String(id);
  const local = String((node && (node.clientNodeKey || node._localId)) || '').trim();
  return local && local.length <= 64 ? 'client:' + local : '';
}

function sharedSites(chapters, templateId, current) {
  const sites = [];
  const currentKey = nodeKey(current);
  (chapters || []).forEach(chapter => (chapter.nodes || []).forEach(node => {
    if (templateIdOf(node.templateId) !== templateIdOf(templateId)) return;
    sites.push({ key: nodeKey(node), label: (chapter.name ? chapter.name + ' · ' : '') + (node.name || '未命名节点') });
  }));
  if (current && templateIdOf(current.templateId) === templateIdOf(templateId)
    && !sites.some(site => currentKey && site.key === currentKey))
    sites.push({ key: currentKey, label: current.name || '当前节点' });
  return sites;
}

function itemUsage(template, slotKey) {
  const usage = { cost: false, material: false };
  const walk = (node) => {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) { node.forEach(walk); return; }
    ['resourceCosts', 'materialCost'].forEach(field => {
      if (Array.isArray(node[field]) && node[field].some(row => row && row.itemId === slotKey)) {
        usage.cost = true;
        if (field === 'materialCost') usage.material = true;
      }
    });
    Object.keys(node).forEach(key => walk(node[key]));
  };
  walk(parse(template && template.advancedConfigJson));
  return usage;
}

function optionsFor(kind, journeyRaw, runtimeRaw, template, slotKey) {
  const rules = parse(journeyRaw), runtime = parse(runtimeRaw);
  const usage = kind === 'ITEM' && slotKey ? itemUsage(template, slotKey) : { cost: true, material: true };
  if (kind === 'ITEM') return (Array.isArray(runtime.items) ? runtime.items : [])
    .filter(item => item && item.id && (!usage.cost || item.kind === 'ITEM') && (!usage.material || !item.wear))
    .map(item => ({ value: String(item.id), label: item.name || String(item.id) }));
  const options = [];
  if (rules.stateEnabled === true) {
    if (rules.hp && rules.hp.enabled === true) options.push({ value: 'sys.hp', label: runtime.hpName || 'HP' });
    if (rules.luck && rules.luck.enabled === true) options.push({ value: 'sys.luck', label: runtime.luckName || 'LUCK' });
    (Array.isArray(rules.attributes) ? rules.attributes : []).forEach(attribute => {
      if (attribute && /^counter\.[a-z][a-z0-9_]{0,47}$/.test(attribute.key))
        options.push({ value: attribute.key, label: attribute.name || attribute.label || attribute.key });
    });
  }
  if (runtime.actionLimit != null) options.push({ value: 'sys.actions', label: runtime.actionName || '行动次数' });
  return options;
}

function reconcile(slots, templateId, old, oldSlots, journeyRaw, runtimeRaw, template) {
  const next = { templateId: typeof templateId === 'number' && templateIdOf(templateId) ? templateId : templateIdOf(templateId), values: {}, items: {}, slotDigest: slotDigest(slots) };
  const previous = Object.fromEntries((oldSlots || []).map(slot => [slot.key, slot]));
  const used = { VALUE: new Set(), ITEM: new Set() };
  slots.forEach(slot => {
    // 槽标识只在同一模板内有意义：只沿用同标识、同类且同名的旧对应，换了模板的同号槽（如 item_2 电源≠维修件）不继承
    const before = previous[slot.key];
    if (!before || before.kind !== slot.kind || (before.label != null && before.label !== slot.label)) return;
    const field = slot.kind === 'VALUE' ? 'values' : 'items';
    const target = old && old[field] && old[field][slot.key];
    if (target && optionsFor(slot.kind, journeyRaw, runtimeRaw, template, slot.key).some(option => option.value === target) && !used[slot.kind].has(target)) {
      next[field][slot.key] = target;
      used[slot.kind].add(target);
    }
  });
  return next;
}

function bind(binding, slots, slotKey, target, journeyRaw, runtimeRaw, template) {
  const slot = slots.find(item => item.key === slotKey);
  if (!slot) throw new Error('资源槽不存在');
  const field = slot.kind === 'VALUE' ? 'values' : 'items';
  if (!optionsFor(slot.kind, journeyRaw, runtimeRaw, template, slot.key).some(option => option.value === target)) throw new Error('请选择已启用的主题资源');
  if (Object.keys(binding[field]).some(key => key !== slotKey && binding[field][key] === target)) throw new Error('两个资源槽不能对应同一主题资源');
  return Object.assign({}, binding, { [field]: Object.assign({}, binding[field], { [slotKey]: target }) });
}

function rows(slots, binding, journeyRaw, runtimeRaw, template) {
  return slots.map(slot => {
    const field = slot.kind === 'VALUE' ? 'values' : 'items';
    const target = binding && binding[field] && binding[field][slot.key];
    const options = optionsFor(slot.kind, journeyRaw, runtimeRaw, template, slot.key);
    return { key: slot.key, kind: slot.kind, kindLabel: slot.kind === 'VALUE' ? '数值' : '物资', label: slot.label,
      target: target || '', targetLabel: (options.find(option => option.value === target) || {}).label || '请选择', options };
  });
}

function issues(chapters, runtimeRaw, details, journeyRaw, requested) {
  const runtime = parse(runtimeRaw), bindings = runtime.templateBindings || {}, result = [];
  (chapters || []).forEach(chapter => (chapter.nodes || []).forEach(node => {
    if (!templateIdOf(node.templateId)) return;
    const template = (details || {})[node.templateId] || node.templateInfo;
    const key = nodeKey(node);
    if (!template || !Object.prototype.hasOwnProperty.call(template, 'advancedConfigJson')) {
      if (requested && requested[node.templateId])
        result.push({ key, label: (node.name || '未命名节点') + '：资源未绑定（玩法详情待确认）' });
      return;
    }
    let slots;
    try { slots = slotsOf(template); }
    catch (_) { result.push({ key, label: (node.name || '未命名节点') + '：资源未绑定（玩法详情无法读取）' }); return; }
    if (!slots.length) return;
    const binding = bindings[key];
    const used = { VALUE: new Set(), ITEM: new Set() };
    const missing = slots.some(slot => {
      const field = slot.kind === 'VALUE' ? 'values' : 'items';
      const target = binding && binding[field] && binding[field][slot.key];
      if (!target || !optionsFor(slot.kind, journeyRaw, runtime, template, slot.key)
        .some(option => option.value === target) || used[slot.kind].has(target)) return true;
      used[slot.kind].add(target);
      return false;
    });
    if (missing || templateIdOf(binding.templateId) !== templateIdOf(node.templateId) || binding.slotDigest !== slotDigest(slots))
      result.push({ key, label: (node.name || '未命名节点') + '：资源未绑定' });
  }));
  return result;
}

module.exports = { sha256, slotsOf, slotDigest, nodeKey, sharedSites, optionsFor, reconcile, bind, rows, issues, parse };
