const app = getApp();
const toast = require('../../../utils/toast.js');
const initial = require('../utils/publish/story-initial-state.js');
<<<<<<< public
const supportMatrix = require('../utils/publish/gameplay-support-matrix.js');
const values = require('./theme-values.js');
const zeroHp = require('./theme-runtime-zero-hp.js');
=======
const journeyChecks = require('./journey-check-settings.js');
const choiceGrants = require('./journey-choice-grants.js');
const grantScopes = require('./journey-grant-scopes.js');
const grantConditions = require('./journey-grant-conditions.js');
const modal = require('../../../utils/modal.js');
const { chinaParts } = require('../../../utils/datetime.js');
>>>>>>> private-github/featu
const PURPOSES = ['展示', '生命值', '幸运值'];
const EFFECTS = ['使用时', '获得时一次', '持有期间', '持有期间减耗'];
const TIMINGS = ['不自动发放', '章节开始', '章节结束'];
const CARD_FIELDS = [
  { key: 'scene', label: '本局场景' }, { key: 'ending', label: '结局标题与说明' },
  { key: 'hp', label: '本局生命值' }, { key: 'luck', label: '本局幸运值' },
  { key: 'actions', label: '本局行动用量' }, { key: 'medals', label: '本局获得的勋章' },
  { key: 'items', label: '本局剩余物品' }, { key: 'story', label: '故事尾声与展签' },
  { key: 'rescue', label: '本局救援次数' },
];
const copy = value => JSON.parse(JSON.stringify(value));
const ACTION_SOURCES = [{ key: 'NODE', label: '节点信号' }, { key: 'CHOICE', label: '选项信号' }, { key: 'DRAW', label: '抽取结果信号' }];
function runtimeRuleSettings(c) {
  const has = key => Object.prototype.hasOwnProperty.call(c, key);
  for (const key of ['hpName','luckName']) if (has(key) && (typeof c[key] !== 'string' || c[key].length > 20))
    throw new Error('数值名称须为不超过 20 个字符的文字，部分表情按 2 个计');
  if (has('actionLimit') && (!Number.isInteger(c.actionLimit) || c.actionLimit < 1 || c.actionLimit > 99999)) throw new Error('行动上限须为 1 到 99999 的整数');
  if (has('rescueLimit') && (!Number.isInteger(c.rescueLimit) || c.rescueLimit < 0 || c.rescueLimit > 10)) throw new Error('救援上限须为 0 到 10 的整数');
  // The validator accepts null as an empty list; the engine distinguishes it from an absent key.
  if (has('actionSources') && c.actionSources != null && (!Array.isArray(c.actionSources) || c.actionSources.length > 3
      || c.actionSources.some(key => !ACTION_SOURCES.some(row => row.key === key)))) throw new Error('行动计数来源不受支持，请保留原配置');
  const selected = has('actionSources') ? (c.actionSources || []) : ACTION_SOURCES.map(row => row.key);
  return { hpName: has('hpName') ? c.hpName : '', hpNameConfigured: has('hpName'),
    luckName: has('luckName') ? c.luckName : '', luckNameConfigured: has('luckName'),
    budgetEnabled: has('actionLimit'), budgetValue: has('actionLimit') ? String(c.actionLimit) : '',
    sources: ACTION_SOURCES.map(row => Object.assign({}, row, { enabled: selected.includes(row.key) })),
    rescueValue: has('rescueLimit') ? String(c.rescueLimit) : '' };
}
function ruleInteger(value, min, max, message) {
  if (typeof value !== 'string' || !/^[0-9]+$/.test(value) || !Number.isSafeInteger(Number(value)) || Number(value) < min || Number(value) > max) throw new Error(message);
  return Number(value);
}
function applyRuntimeRules(c, draft) {
  const before = runtimeRuleSettings(c);
  for (const key of ['hpName','luckName']) {
    if (draft[key + 'Configured']) {
      if (typeof draft[key] !== 'string' || draft[key].length > 20) throw new Error('数值名称最多 20 个字符，部分表情按 2 个计');
      // Optional names permit empty/control/Unicode whitespace exactly as Java text(..., false).
      if (!before[key + 'Configured'] || before[key] !== draft[key]) c[key] = draft[key];
    } else if (before[key + 'Configured']) delete c[key];
  }
  if (draft.budgetEnabled) c.actionLimit = ruleInteger(draft.budgetValue, 1, 99999, '开启行动上限后，请明确填写 1 到 99999 的整数');
  else delete c.actionLimit;
  if (JSON.stringify(draft.sources.map(row => row.enabled)) !== JSON.stringify(before.sources.map(row => row.enabled)))
    c.actionSources = draft.sources.filter(row => row.enabled).map(row => row.key);
  if (draft.rescueValue === '') delete c.rescueLimit;
  else c.rescueLimit = ruleInteger(draft.rescueValue, 0, 10, '救援上限须明确填写 0 到 10 的整数，或留空保持未配置');
  runtimeRuleSettings(c); return c;
}
const BROADCAST_TRIGGERS = [
  { key: 'ITEM_HELD', label: '当前持有物品或勋章', item: true },
  { key: 'ITEM_ACQUIRED', label: '本局累计获得物品或勋章', item: true },
  { key: 'sys.hp', label: '本局生命值' }, { key: 'sys.luck', label: '本局幸运值' },
  { key: 'sys.actions', label: '本局行动用量' },
];
const BROADCAST_COMPARISONS = [
  { op: 'EQ', label: '等于' }, { op: 'NE', label: '不等于' }, { op: 'GT', label: '大于' },
  { op: 'GTE', label: '大于或等于' }, { op: 'LT', label: '小于' }, { op: 'LTE', label: '小于或等于' },
];
const BROADCAST_EFFECT_OPS = [{ op: 'SET', label: '设为（SET）' }, { op: 'INC', label: '增减（INC）' }];
const emptyBroadcastDraft = () => ({ content: '', triggerIndex: -1, comparisonIndex: -1, itemIndex: -1, value: '',
  effects: [], effectStateIndex: -1, effectOpIndex: -1, effectValue: '' });
function broadcastAttributes(raw) {
  try {
    const rules = initial.parse(raw);
    const keys = initial.normalizeAttributes(rules.attributes).filter(row => /^(clue|relation|counter)\./.test(row.key))
      .map(row => ({ key: row.key, label: row.label + '（' + row.key + '）' }));
    return { keys, enabled: rules.stateEnabled, error: '' };
  } catch (_) { return { keys: [], enabled: false, error: '初始属性声明无法读取；已保留原配置，暂不能选择属性条件或后果' }; }
}
function broadcastTriggers(keys) { return BROADCAST_TRIGGERS.concat(keys || []).map(row => Object.assign({}, row)); }
function pendingBroadcastEffect(draft) { return draft.effectStateIndex !== -1 || draft.effectOpIndex !== -1 || draft.effectValue !== ''; }

function assertBroadcastSafeNumbers(value) {
  if (typeof value === 'number' && (!Number.isFinite(value) || (Number.isInteger(value) && !Number.isSafeInteger(value))))
    throw new Error('现有配置包含不能安全读取的数值，请保留原配置');
  if (value && typeof value === 'object') Object.values(value).forEach(assertBroadcastSafeNumbers);
}
function broadcastText(value, required) {
  // Match Java String.length and String.trim, without normalizing the stored content.
  if (typeof value !== 'string' || value.length > 500 || (required && /^[\u0000-\u0020]*$/.test(value)))
    throw new Error('请填写不超过 500 个字符的广播内容，部分表情按 2 个计');
}
function simpleBroadcastCondition(row, c, keys) {
  if (!Array.isArray(row.when) || row.when.length !== 1) return null;
  const condition = row.when[0];
  if (!condition || typeof condition !== 'object' || Array.isArray(condition)) return null;
  const trigger = broadcastTriggers(keys).find(t => t.item ? t.key === condition.op : t.key === condition.var);
  if (!trigger) return null;
  if (trigger.item) {
    const item = c.items.find(i => i.id === condition.itemId);
    if (Object.keys(condition).some(k => !['op','itemId','quantity'].includes(k)) || !item
        || !Number.isInteger(condition.quantity) || condition.quantity < 1 || condition.quantity > 999) return null;
    return trigger.label + '“' + (item.name || item.id) + '”至少 ' + condition.quantity + ' 个';
  }
  const comparison = BROADCAST_COMPARISONS.find(p => p.op === condition.op);
  if (Object.keys(condition).some(k => !['op','var','value'].includes(k)) || !comparison
      || !Number.isInteger(condition.value) || Math.abs(condition.value) > 999999) return null;
  return trigger.label + comparison.label + ' ' + condition.value;
}
function broadcastSettings(c, required = false, keys = [], created = new Set()) {
  if (c.events != null && (!Array.isArray(c.events) || c.events.length > 100)) throw new Error('主题广播事件最多 100 项');
  const seen = new Set();
  const events = (c.events || []).map(row => {
    if (!row || typeof row !== 'object' || Array.isArray(row) || typeof row.id !== 'string'
        || !/^[a-z][a-z0-9_]{0,31}$/.test(row.id) || seen.has(row.id)) throw new Error('广播事件编号格式不正确或重复');
    seen.add(row.id); broadcastText(row.content, required);
    if (row.when != null && (!Array.isArray(row.when) || row.when.length > 30)) throw new Error('广播条件最多 30 项');
    const condition = simpleBroadcastCondition(row, c, keys);
    const extra = Object.keys(row).some(k => !['id','content','when','grants','effects'].includes(k));
    const consequence = ['grants','effects'].some(k => row[k] != null && (!Array.isArray(row[k]) || row[k].length));
    return { id: row.id, content: row.content, conditionLabel: condition || '现有复合、剧情、节点或无条件配置（只读）',
      canRemove: !extra && (created.has(row.id) || (!!simpleBroadcastCondition(row, c) && !consequence)), retainedPayload: extra || consequence };
  });
  return { events, scopedOverrides: (c.scenes || []).filter(s => s && Object.prototype.hasOwnProperty.call(s, 'events')).length };
}
const emptyNodeGrantDraft = () => ({ editingId: '', nodeIndex: -1, itemIndex: -1, quantity: '' });
function savedNodeId(value) {
  if (!['number', 'string'].includes(typeof value) || !/^[1-9][0-9]*$/.test(String(value))) return null;
  const id = Number(value); return Number.isSafeInteger(id) ? id : null;
}
function grantLabel(value, fallback) { return typeof value === 'string' && value ? value.slice(0, 100) : fallback; }
function savedGrantNodes(data) {
  if (!data.editingTopicId || data.editLoaded !== true) return { nodes: [], unsaved: true };
  const nodes = [], seen = new Set(); let unsaved = false;
  for (const chapter of data.formData.chapters || []) for (const node of chapter.nodes || []) {
    const id = node && savedNodeId(node.id);
    if (!id) { unsaved = true; continue; }
    if (seen.has(id)) throw new Error('当前草稿的已保存站点编号重复，请先核对站点');
    seen.add(id); nodes.push({ id, label: grantLabel(chapter.name, '章节') + ' · ' + grantLabel(node.name, '站点 ' + id) });
    if (nodes.length > 500) throw new Error('此配置面板最多读取 500 个已保存站点，请保留原配置');
  }
  return { nodes, unsaved };
}
function simpleNodeGrant(row, c, nodes, context) {
  return (Object.keys(row).length === 5 || (Object.keys(row).length === 6 && Object.prototype.hasOwnProperty.call(row, 'when'))) && Object.keys(row).every(key => ['id','source','nodeId','itemId','quantity','when'].includes(key))
    && grantConditions.supported(row.when, c, context || { nodes })
    && row.source === 'NODE' && typeof row.nodeId === 'number' && savedNodeId(row.nodeId) !== null
    && nodes.some(node => node.id === row.nodeId) && c.items.some(item => item.id === row.itemId)
    && Number.isInteger(row.quantity) && row.quantity >= 1 && row.quantity <= 99;
}
function nodeGrantSettings(c, nodes, context) {
  if (c.grants != null && (!Array.isArray(c.grants) || c.grants.length > 300)) throw new Error('主题发放配置最多 300 项');
  const seen = new Set(); let protectedCount = 0;
  const rows = [];
  (c.grants || []).forEach(row => {
    if (!row || typeof row !== 'object' || Array.isArray(row) || typeof row.id !== 'string'
        || !/^[a-z][a-z0-9_]{0,31}(?![\s\S])/.test(row.id) || seen.has(row.id)) throw new Error('发放配置编号格式不正确或重复');
    seen.add(row.id);
    if (row.source !== 'NODE') { protectedCount++; return; }
    const node = nodes.find(n => n.id === row.nodeId), item = c.items.find(i => i.id === row.itemId);
    rows.push({ id: row.id, node: node ? node.label : '无法对应当前已保存站点', item: item ? grantLabel(item.name, item.id) : '无法对应当前物资',
      quantity: Number.isInteger(row.quantity) ? row.quantity : '未识别', whenCount: Array.isArray(row.when) ? row.when.length : 0, editable: simpleNodeGrant(row, c, nodes, context) });
  });
  return { rows, protectedCount, total: (c.grants || []).length,
    scopedOverrides: (c.scenes || []).filter(scene => scene && Object.prototype.hasOwnProperty.call(scene, 'grants')).length };
}
function sceneSettings(c, requireNames = false) {
  const has = key => Object.prototype.hasOwnProperty.call(c, key);
  if (has('replayEnabled') && typeof c.replayEnabled !== 'boolean') throw new Error('重玩开关格式不正确');
  if (has('scenes') && (!Array.isArray(c.scenes) || c.scenes.length > 50)) throw new Error('场景目录须为最多 50 项的列表');
  const list = c.scenes || [], seen = new Set();
  const labels = { grants: '发放', events: '事件', endings: '结局', journeyStory: '剧情', nodeConfigs: '节点玩法' };
  const scenes = list.map(row => {
    if (!row || typeof row !== 'object' || Array.isArray(row) || typeof row.id !== 'string'
        || !/^[a-z][a-z0-9_]{0,31}$/.test(row.id) || seen.has(row.id)) throw new Error('场景编号格式不正确或重复');
    seen.add(row.id);
    // Java String.length and JS .length both count UTF-16 code units, including surrogate pairs.
    // Java String.trim removes only U+0000..U+0020; NBSP and other Unicode spaces are retained.
    if (typeof row.name !== 'string' || row.name.length > 40 || (requireNames && /^[\u0000-\u0020]*$/.test(row.name))) throw new Error('请填写不超过 40 个字符的场景名称，部分表情按 2 个计');
    const overrides = Object.keys(labels).filter(key => Object.prototype.hasOwnProperty.call(row, key)).map(key => labels[key]);
    const other = Object.keys(row).some(key => !['id','name',...Object.keys(labels)].includes(key));
    return { id: row.id, name: row.name, current: row.id === c.currentScene,
      contentLabel: overrides.length ? '保留独立配置：' + overrides.join('、') : '沿用主题主配置', hasOther: other };
  });
  if (has('currentScene') && (typeof c.currentScene !== 'string' || !seen.has(c.currentScene))) throw new Error('所选场景不在当前目录中，请检查原配置');
  if (list.length && !has('currentScene')) throw new Error('现有场景缺少起始选择，请检查原配置');
  const mode = has('rotation') ? c.rotation : 'FIXED';
  if (!['FIXED','ROUND_ROBIN','WEEKLY'].includes(mode)) throw new Error('场景轮换方式不受支持，请保留原配置');
  if ((mode === 'WEEKLY' && (!Number.isSafeInteger(c.rotationAnchor) || c.rotationAnchor < 0))
      || (typeof c.rotationAnchor === 'number' && (!Number.isFinite(c.rotationAnchor)
        || (Number.isInteger(c.rotationAnchor) && !Number.isSafeInteger(c.rotationAnchor)))))
    throw new Error('现有轮换锚点不能安全读取，请保留原配置');
  let anchorText = '';
  if (has('rotationAnchor')) {
    if (Number.isSafeInteger(c.rotationAnchor) && c.rotationAnchor >= 0) {
      const parts = chinaParts(new Date(c.rotationAnchor)), pad = n => String(n).padStart(2,'0');
      anchorText = parts ? parts.year + '-' + pad(parts.month) + '-' + pad(parts.day) + ' ' + pad(parts.hours) + ':' + pad(parts.minutes) + '（北京时间）' : '时间超出可显示范围';
      anchorText += ' · 毫秒值 ' + String(c.rotationAnchor);
    } else anchorText = '现有未启用的锚点格式，原值保留'; // The server only validates this field for WEEKLY.
  }
  return { replayEnabled: c.replayEnabled === true, mode, rotationIndex: mode === 'FIXED' ? 0 : mode === 'ROUND_ROBIN' ? 1 : -1,
    rotationLabel: mode === 'FIXED' ? '固定场景' : mode === 'ROUND_ROBIN' ? '按新局轮换' : '现有按周轮换（只读）',
    anchorText, scenes };
}
function cardSettings(c) {
  const card = c.card == null ? {} : c.card;
  if (!card || typeof card !== 'object' || Array.isArray(card)) throw new Error('公开结果配置格式不正确');
  const has = key => Object.prototype.hasOwnProperty.call(card, key);
  if (has('cover') && (typeof card.cover !== 'string' || card.cover.length > 500 || !/^https:\/\/[^\s]+$/.test(card.cover)))
    throw new Error('结果封面须为不超过 500 字符的 HTTPS 图片地址');
  if (has('showUnlocks') && typeof card.showUnlocks !== 'boolean') throw new Error('已知结局开关格式不正确');
  if (has('fields') && (!Array.isArray(card.fields) || card.fields.length > 10
      || card.fields.some(key => !CARD_FIELDS.some(row => row.key === key)))) throw new Error('公开结果字段不受支持，请检查原配置');
  // Missing fields means ending-only on the server; an explicit [] means no optional fields.
  const selected = has('fields') ? card.fields : ['ending'];
  return { cover: card.cover || '', showUnlocks: card.showUnlocks === true,
    fields: CARD_FIELDS.map(row => Object.assign({}, row, { enabled: selected.includes(row.key) })) };
}
function config(raw) {
  const c = raw ? (typeof raw === 'string' ? JSON.parse(raw) : copy(raw)) : {};
  if (!c || typeof c !== 'object' || Array.isArray(c) || (c.schemaVersion != null && c.schemaVersion !== 1)) throw new Error('主题配置格式不正确');
  ['items', 'chapters', 'grants', 'events'].forEach(key => { if (c[key] != null && !Array.isArray(c[key])) throw new Error('主题配置格式不正确'); });
  const result = Object.assign({ schemaVersion: 1, capacity: 6, allowDiscard: true, items: [], chapters: [], grants: [], events: [] }, c);
  result.chapters.forEach(ch => { if (!ch || !Number.isInteger(ch.index) || ch.index < 0) throw new Error('章节配置格式不正确'); ch.grants = ch.grants || []; ch.eggs = ch.eggs || []; if (!Array.isArray(ch.grants) || !Array.isArray(ch.eggs)) throw new Error('章节配置格式不正确'); });
  return result;
}
function chapter(c, index) {
  if (!Number.isInteger(index) || index < 0 || index > 199) throw new Error('主题章节序号须在 0 到 199 之间');
  let row = c.chapters.find(v => v.index === index);
  if (!row) { if (c.chapters.length >= 200) throw new Error('主题章节配置最多 200 项'); row = { index, hpCost: 0, luckCost: 0, grants: [], eggs: [] }; c.chapters.push(row); }
  return row;
}
<<<<<<< public
function purposeOptions(c, rules) {
  const out = [{ key: '', label: '展示' }];
  if (rules.hp.enabled) out.push({ key: 'HP', label: c.hpName || '生命值' });
  if (rules.luck.enabled) out.push({ key: 'LUCK', label: c.luckName || '幸运值' });
  if (rules.stateEnabled) (rules.attributes || []).filter(row => values.COUNTER.test(row.key || '')).forEach(row => out.push({ key: row.key, label: row.label }));
  return out;
}
function rows(c, index, options) {
  const grants = chapter(c, index).grants || [];
  const keys = options.map(row => row.key);
=======
function referencesItem(value, id) {
  if (!value || typeof value !== 'object') return false;
  return Object.keys(value).some(key => (key === 'itemId' && value[key] === id) || referencesItem(value[key], id));
}
function nextId(prefix, values) {
  let sequence = 0, id; const existing = new Set(values.map(value => value.id));
  do { id = prefix + Date.now().toString(36) + '_' + (++sequence).toString(36); } while (existing.has(id));
  return id;
}
function rows(c, index) {
  const grants = chapter(c, index).grants;
>>>>>>> private-github/featu
  return c.items.map(item => {
    const g = grants.find(v => v.itemId === item.id) || {};
    const purpose = item.statKey || item.stat || '';
    return Object.assign({}, item, {
      purposeIndex: Math.max(0, keys.indexOf(purpose)),
      statLabel: (options.find(row => row.key === purpose) || {}).label || (purpose === 'HP' ? c.hpName || '生命值' : c.luckName || '幸运值'),
      effectIndex: !item.wear ? 0 : item.mode === 'ONCE' ? 1 : item.mode === 'HELD' ? 2 : 3,
      timingIndex: g.timing === 'START' ? 1 : g.timing === 'END' ? 2 : 0,
      quantity: g.quantity || 1, effectOptions: purpose && purpose !== 'HP' ? EFFECTS.slice(0, 3) : EFFECTS,
    });
  });
}
<<<<<<< public
function validate(c, rules, policies) {
  const survivalIssue = require('./theme-runtime-survival.js').validate(c, undefined, rules); if (survivalIssue) return survivalIssue; // survival
  for (const key of ['hpName', 'luckName', 'actionName']) if (String(c[key] || '').length > 20) return '数值名称不能超过 20 字';
  const zeroIssue = zeroHp.validate(c, rules, policies); if (zeroIssue) return zeroIssue;
  if (!Number.isInteger(c.capacity) || c.capacity < 1 || c.capacity > 999) return '物品容量请输入 1 到 999 的整数';
  for (const i of c.items) {
    if (!String(i.name || '').trim()) return '请输入物品或勋章名称';
    if (i.slotCost != null && (i.kind !== 'ITEM' || !Number.isInteger(i.slotCost) || i.slotCost < 1 || i.slotCost > 999)) return '每件占格请输入 1 到 999 的整数';
    if (!/^https:\/\/[^\s]+$/.test(String(i.image || ''))) return '请上传物品或勋章图片';
    if ((i.stat || i.statKey) && (!Number.isInteger(Number(i.delta)) || !Number(i.delta) || Math.abs(Number(i.delta)) > 999 || (i.mode === 'REDUCE' && (i.statKey || i.stat !== 'HP' || Number(i.delta) < 0)))) return '请输入非 0 的整数增减值';
    if (i.stat && rules && !rules[i.stat === 'HP' ? 'hp' : 'luck'].enabled) return '请先开启对应的' + (i.stat === 'HP' ? c.hpName || '生命值' : c.luckName || '幸运值');
    if (i.statKey && rules && (!rules.stateEnabled || !(rules.attributes || []).some(row => row.key === i.statKey))) return '请先开启物品对应的自定义值';
=======
function validate(c, rules, publishing = true) {
  try { cardSettings(c); } catch (error) { return error.message; }
  try { sceneSettings(c, true); } catch (error) { return error.message; }
  try { broadcastSettings(c, true); } catch (error) { return error.message; }
  try { runtimeRuleSettings(c); } catch (error) { return error.message; }
  if (c.items.length > 200) return '物品与勋章最多 200 项';
  const ids = new Set();
  for (const item of c.items) { if (!item || !item.id || ids.has(item.id) || !['ITEM', 'MEDAL'].includes(item.kind)) return '物品编号或类型不正确'; ids.add(item.id); }
  if (!Number.isInteger(c.capacity) || c.capacity < (c.items.length ? 1 : 0) || c.capacity > 999) return '物品容量请输入有效的 0 到 999 整数，有物品时至少为 1';
  for (const i of c.items) {
    if (publishing && !String(i.name || '').trim()) return '请输入物品或勋章名称';
    if (publishing && !/^https:\/\/[^\s]+$/.test(String(i.image || ''))) return '请上传物品或勋章图片';
    if (i.stat && (!Number.isInteger(Number(i.delta)) || !Number(i.delta) || Math.abs(Number(i.delta)) > 999 || (i.mode === 'REDUCE' && Number(i.delta) < 0))) return '请输入非 0 的整数增减值';
    if (i.stat && rules && !rules[i.stat === 'HP' ? 'hp' : 'luck'].enabled) return '请先开启对应的生命值或幸运值';
>>>>>>> private-github/featu
  }
  if (c.chapters.length > 200) return '主题章节配置最多 200 项';
  const indexes = new Set();
  for (const ch of c.chapters) {
    if (!Number.isInteger(ch.index) || ch.index < 0 || ch.index > 199 || indexes.has(ch.index)) return '主题章节序号不正确或重复';
    indexes.add(ch.index);
    if (ch.grants.length > 200 || new Set(ch.grants.map(g => g.itemId)).size !== ch.grants.length) return '章节发放最多 200 项且不能重复';
    if (ch.eggs.length > 100 || new Set(ch.eggs.map(e => e.id)).size !== ch.eggs.length) return '每章彩蛋最多 100 项且编号不能重复';
    for (const stat of ['hp', 'luck']) if (!Number.isInteger(ch[stat + 'Cost']) || ch[stat + 'Cost'] < 0 || ch[stat + 'Cost'] > 999 || (ch[stat + 'Cost'] > 0 && rules && !rules[stat].enabled)) return '请开启对应数值，并填写 0 到 999 的章节消耗';
<<<<<<< public
    for (const g of ch.grants || []) if (!Number.isInteger(g.quantity) || g.quantity < 1 || g.quantity > 99) return '发放数量请输入 1 到 99 的整数';
    for (const e of ch.eggs || []) {
      if (!e.name.trim() || !e.content.trim()) return '请填写彩蛋名称和内容';
=======
    for (const g of ch.grants) if (!ids.has(g.itemId) || !['START', 'END'].includes(g.timing) || !Number.isInteger(g.quantity) || g.quantity < 1 || g.quantity > 99) return '发放数量请输入 1 到 99 的整数';
    for (const e of ch.eggs) {
      if (!Array.isArray(e.rewards) || e.rewards.length > 20) return '每个彩蛋最多 20 项奖励';
      if (String(e.name || '').length > 40 || String(e.content || '').length > 500) return '彩蛋名称或内容过长';
      if (publishing && (!String(e.name || '').trim() || !String(e.content || '').trim())) return '请填写彩蛋名称和内容';
>>>>>>> private-github/featu
      if (!['TIME', 'STEPS'].includes(e.trigger) || !Number.isInteger(e.threshold) || e.threshold < 1 || e.threshold > 99999) return '请填写彩蛋触发方式与数值';
      for (const r of e.rewards) if (!c.items.some(i => i.id === r.itemId) || !Number.isInteger(r.quantity) || r.quantity < 1 || r.quantity > 99) return '请选择彩蛋奖励并填写数量';
    }
  }
  return '';
}
const data = {
<<<<<<< public
  ...zeroHp.data,
=======
  runtimeEnabled: false, runtimeCapabilityLoaded: false, runtimeRewards: {},
>>>>>>> private-github/featu
  runtimeEditor: { show: false, kind: '', title: '', index: 0 },
  runtimeBag: { capacity: 6, allowDiscard: true, items: [] }, runtimeEggs: [], runtimeRewardOptions: [],
  runtimePurposes: PURPOSES, runtimeEffects: EFFECTS, runtimeTimings: TIMINGS,
  runtimePurposeKeys: ['', 'HP', 'LUCK'], runtimeValues: [], runtimeValueError: '', valueEditor: { show: false, index: -1, row: null, links: [], error: '' },
  runtimeEditingStat: '',
  valueTriggers: ['每下降 1 点', '每次下降事件', '首次达到阈值'], valueFrequencies: ['每次事件', '本局一次'],
  valueReasonLabels: ['任务费用', '玩法效果', '章节费用', '使用物品', '设施效果'],
  runtimeTriggers: ['时间', '步数'], runtimeHpEnabled: false, runtimeLuckEnabled: false,
<<<<<<< public
  runtimeHpName: '', runtimeLuckName: '', runtimeActionName: '', runtimeHpLabel: '生命值', runtimeLuckLabel: '幸运值', runtimeActionLabel: '行动次数',
  runtimeHpInitialLabel: '精力', runtimeLuckInitialLabel: '幸运',
  runtimeZeroHpPolicies: [], runtimeZeroHpPolicyKeys: [], runtimeZeroHpPolicyIndex: -1,
  runtimeChallengeError: '', runtimeChallengeHint: '',
  runtimeChallengeNodes: [], runtimeChallengeNodeIndex: -1, runtimeAlternativeNodes: [], runtimeZeroChallenge: { restoreTo: 1, limit: 3 },
  runtimeRewardOpen: false, subtitleEditing: false, runtimeBingoIndex: 0, runtimeCompletionIndex: -1, runtimeChapterCosts: { hpCost: 0, luckCost: 0 },
  runtimeChapterRules: { index: 0, completionType: 'ALL', quantity: '', overflowSlot: '', groups: [], extraNodes: [], nodes: [], slots: [] },
  runtimeChapterRation: { enabled: false, actKey: '', rationKey: '', itemIndex: -1, itemLabel: '请选择', quantity: '1', fallback: '0', values: [], valueIndex: 0, items: [], reason: '' },
};
const methods = {
  ...zeroHp.methods,
  _runtimeConfig() { return config(this.data.formData.themeRuntimeConfig); },
  _saveRuntime(c) {
    const raw = this.data.formData.themeRuntimeConfig;
    if (raw) {
      const previous = typeof raw === 'string' ? JSON.parse(raw) : raw;
      for (const [key, fallback] of Object.entries({ allowDiscard: true, grants: [], events: [] })) {
        if (!(key in previous) && JSON.stringify(c[key]) === JSON.stringify(fallback)) delete c[key];
      }
    }
    this.setData({ 'formData.themeRuntimeConfig': JSON.stringify(c) });
    if (this._refreshStructureSummary) this._refreshStructureSummary(); // structure
    if (this._refreshThemeOwnership) this._refreshThemeOwnership();
  },
  _runtimePayload() {
    if (!this.data.formData.themeRuntimeConfig) return null;
    const raw = this.data.formData.themeRuntimeConfig;
    const c = typeof raw === 'string' ? JSON.parse(raw) : copy(raw);
    if (Array.isArray(c.chapters)) c.chapters = c.chapters.filter(ch => ch.index < this.data.formData.chapters.length);
    if (Array.isArray(c.items)) c.items.forEach(i => { if (i.stat || i.statKey) i.delta = Number(i.delta); });
    return JSON.stringify(c);
  },
  _runtimeCosts(index) { return chapter(this._runtimeConfig(), index); },
  _prependRuntimeChapter() { const c = this._runtimeConfig(); const added = c.chapters.find(ch => ch.index === this.data.formData.chapters.length); c.chapters.forEach(ch => { ch.index = ch === added ? 0 : ch.index + 1; }); this._saveRuntime(c); if (this._syncStructureChapters) this._syncStructureChapters(); /* structure */ },
  _removeRuntimeChapter(index) { const c = this._runtimeConfig(); c.chapters = c.chapters.filter(ch => ch.index !== index).map(ch => Object.assign(ch, { index: ch.index > index ? ch.index - 1 : ch.index })); this._saveRuntime(c); if (this._syncStructureChapters) this._syncStructureChapters(this.data.formData.chapters.filter((_, i) => i !== index)); /* structure */ },
=======
  subtitleEditing: false, runtimeBingoIndex: 0,
  runtimeRewardOpen: false, runtimeCompletionIndex: -1, runtimeChapterCosts: { hpCost: 0, luckCost: 0 },
  runtimeResultCard: cardSettings({}),
  runtimeScenes: sceneSettings({}), runtimeRotationOptions: ['固定场景', '按新局轮换'],
  runtimeBroadcasts: { events: [], scopedOverrides: 0 }, runtimeBroadcastDraft: emptyBroadcastDraft(),
  runtimeBroadcastTriggers: BROADCAST_TRIGGERS, runtimeBroadcastComparisons: BROADCAST_COMPARISONS,
  runtimeBroadcastAttributes: { keys: [], enabled: false, error: '' }, runtimeBroadcastEffectOps: BROADCAST_EFFECT_OPS,
  runtimeRules: runtimeRuleSettings({}),
  runtimeJourneyChecks: null,
  runtimeGrantWhenRows: [], runtimeGrantConditionDraft: grantConditions.emptyDraft(), runtimeGrantConditionSources: [], runtimeGrantConditionNodes: [], runtimeGrantConditionComparisons: grantConditions.comparisons.map(row => Object.assign({}, row)), runtimeGrantConditionKind: '', runtimeGrantConditionContext: { enabled: false, error: '' },
  runtimeGrantScope: { options: [], index: 0, id: '', label: '主配置', inherited: false, editable: true, canRestore: false, mode: '主配置' },
  runtimeChoiceGrants: { rows: [], total: 0, protectedCount: 0, scopedOverrides: 0 },
  runtimeChoiceNodes: [], runtimeChoiceSteps: [], runtimeChoiceOptions: [], runtimeChoiceBlocked: [], runtimeChoiceUnsaved: false, runtimeChoiceGrantDraft: choiceGrants.emptyDraft(),
  runtimeNodeGrants: { rows: [], protectedCount: 0, total: 0, scopedOverrides: 0 },
  runtimeGrantNodes: [], runtimeGrantUnsaved: false, runtimeNodeGrantDraft: emptyNodeGrantDraft(),
};
const methods = {
  _runtimeConfig() { return config(this._runtimeWorking || this._runtimeChapterWorking || this.data.formData.themeRuntimeConfig); },
  _saveRuntime(c) { if (this._runtimeWorking) this._runtimeWorking = copy(c); else if (this._runtimeChapterWorking) { this._runtimeChapterWorking = copy(c); this._runtimeChapterDirty = true; } else this.setData({ 'formData.themeRuntimeConfig': JSON.stringify(c) }); },
  _runtimePayload() {
    if (!this._runtimeMayEdit()) return undefined;
    // Recheck max-coupled recovery against the latest staged HP/LUCK values before original save.
    journeyChecks.normalize(this.data.formData.journeyRules);
    const raw = this.data.formData.themeRuntimeConfig;
    if (raw == null) return undefined;
    if (raw === '') return ''; // Explicit clear is distinct from omission.
    const c = config(raw); c.items.forEach(i => { if (i.stat) i.delta = Number(i.delta); });
    if (c.chapters.some(ch => ch.index >= this.data.formData.chapters.length)) throw new Error('请检查主题章节配置');
    return JSON.stringify(c);
  },
  _runtimeCosts(index) { return chapter(this._runtimeConfig(), index); },
  _prependRuntimeChapter() { if (!this.data.formData.themeRuntimeConfig && !this._runtimeChapterDirty) return; const c = this._runtimeConfig(); const added = c.chapters.find(ch => ch.index === this.data.formData.chapters.length); c.chapters.forEach(ch => { ch.index = ch === added ? 0 : ch.index + 1; }); this._saveRuntime(c); },
  _removeRuntimeChapter(index) { if (!this.data.formData.themeRuntimeConfig) return; this._runtimeChapterWorking = null; const c = this._runtimeConfig(); c.chapters = c.chapters.filter(ch => ch.index !== index).map(ch => Object.assign(ch, { index: ch.index > index ? ch.index - 1 : ch.index })); this._saveRuntime(c); },
>>>>>>> private-github/featu
  _runtimeChapterIndex() { return this.data.popChapterAction == 0 ? this.data.formData.chapters.length : this.data.popChapterIndex; },
  _chapterSavedNodes(index) {
    if (!this.data.editingTopicId) return [];
    const ch = (this.data.formData.chapters || [])[index];
    return ((ch && ch.nodes) || []).map(node => ({ id: Number(node.id), name: node.name || ('站点 ' + node.id) }))
      .filter(node => Number.isSafeInteger(node.id) && node.id > 0);
  },
  _chapterRuleIssue(index, row) {
    if (!row || (!row.completion && !row.exclusiveGroups && !row.extraNodes)) return '';
    const nodes = this._chapterSavedNodes(index), ids = new Set(nodes.map(node => node.id));
    const extra = row.extraNodes || [], groups = row.exclusiveGroups || [];
    const used = new Set();
    for (const item of extra) {
      if (!ids.has(item.nodeId)) return '额外节点须先保存，且必须属于本章';
      if (!/^[a-z][a-z0-9_]{0,31}$/.test(item.slot || '')) return '额外任务槽名称须以小写字母开头，只能使用小写字母、数字或下划线，最多 32 位';
      if (used.has(item.nodeId)) return '同一站点不能重复配置为额外节点';
      used.add(item.nodeId);
    }
    for (const group of groups) {
      if (!Array.isArray(group) || group.length < 2) return '互斥选项每组至少选择 2 个站点';
      for (const id of group) {
        if (!ids.has(id)) return '互斥选项须先保存，且必须属于本章';
        if (used.has(id)) return '站点不能同时属于多个互斥组或额外节点';
        used.add(id);
      }
    }
    // 计入完成的单位：未分组的普通站点各 1，每个互斥组合计 1，额外节点不计（与后端 A08 计数一致）
    const ch = (this.data.formData.chapters || [])[index];
    const count = ((ch && ch.nodes) || []).length - extra.length - groups.reduce((total, group) => total + group.length - 1, 0);
    if (extra.length && count < 1) return '额外节点不能占满本章全部站点，至少保留一个计入完成的站点';
    if (row.completion) {
      if (row.completion.type !== 'ANY_N') return row.completion.type === 'ALL' ? '' : '本章完成要求不支持该方式';
      if (!Number.isInteger(row.completion.quantity) || row.completion.quantity < 1 || row.completion.quantity > count) return '任选数量须在 1 到本章可完成的站点数之间';
      if (row.completion.overflowSlot && !/^[a-z][a-z0-9_]{0,31}$/.test(row.completion.overflowSlot)) return '超量任务槽名称须以小写字母开头，只能使用小写字母、数字或下划线，最多 32 位';
    }
    return '';
  },
  _openChapterRules(index) {
    const c = this._runtimeConfig(), row = c.chapters.find(ch => ch.index === index) || {};
    const nodes = this._chapterSavedNodes(index);
    const slots = [...new Set(c.chapters.flatMap(ch => (ch.extraNodes || []).map(item => item.slot).concat(ch.completion && ch.completion.overflowSlot || [])).filter(Boolean))];
    this.setData({ runtimeChapterRules: {
      index,
      completionType: row.completion && row.completion.type === 'ANY_N' ? 'ANY_N' : 'ALL',
      quantity: row.completion && row.completion.quantity != null ? String(row.completion.quantity) : '',
      overflowSlot: row.completion && row.completion.overflowSlot || '',
      groups: copy(row.exclusiveGroups || []).map(ids => ({ ids, options: nodes.map(node => ({ ...node, selected: ids.includes(node.id) })) })),
      extraNodes: copy(row.extraNodes || []).map(item => ({ ...item, nodeIndex: nodes.findIndex(node => node.id === item.nodeId), nodeName: (nodes.find(node => node.id === item.nodeId) || {}).name || '选择已保存站点' })),
      nodes, slots,
    } });
    this._openChapterRation(index);
  },
  /* 章末配给：本章（幕）结束时每队/每人按配置消耗一次物品，没有物品时扣对应数值。
     写入既有 gameplayRuntime.survival.rations（按本章任务的 actKey），运行端按幕结算一次。 */
  _chapterActKey(index) {
    const ids = new Set(this._chapterSavedNodes(index).map(node => node.id));
    const quest = ((this._runtimeConfig().gameplayRuntime || {}).quests || []).find(row => ids.has(Number(row.nodeId)));
    return quest ? quest.actKey : '';
  },
  _openChapterRation(index) {
    const c = this._runtimeConfig(), game = c.gameplayRuntime || {};
    const actKey = this._chapterActKey(index);
    const items = (c.items || []).filter(item => item.kind === 'ITEM' && !item.wear).map(item => ({ id: item.id, name: item.name || '未命名物品' }));
    const row = actKey ? ((game.survival || {}).rations || []).find(r => r.actKey === actKey) : null;
    const itemIndex = row ? items.findIndex(item => item.id === row.itemId) : -1;
    // 不足时扣哪个数值：复用数值域选项（生命 + 已定义的自定义数值），不含幸运与「展示」
    const valueRows = purposeOptions(c, initial.parse(this.data.formData.journeyRules)).filter(row => row.key === 'HP' || values.COUNTER.test(row.key || ''));
    const fallbackKey = row && row.fallback && row.fallback.statKey ? row.fallback.statKey : 'HP';
    const valueIndex = Math.max(0, valueRows.findIndex(v => v.key === fallbackKey));
    const reason = !game.runtimeScope ? '请先选择进行方式' : !actKey ? '请先保存本章站点' : !items.length ? '请先在主题里添加物品' : '';
    this.setData({ runtimeChapterRation: {
      enabled: !!row, actKey, rationKey: row ? row.rationKey : '', itemIndex, itemLabel: itemIndex >= 0 ? items[itemIndex].name : '请选择',
      quantity: String(row ? row.quantity : 1), fallback: String(row ? (row.fallback ? row.fallback.amount : row.fallbackHpCost || 0) : 0), values: valueRows, valueIndex, items, reason,
    } });
  },
  onChapterRationToggle(e) {
    const on = !!e.detail.value, draft = this.data.runtimeChapterRation;
    if (on && draft.reason) { toast(draft.reason); this.setData({ 'runtimeChapterRation.enabled': false }); return; }
    this.setData({ 'runtimeChapterRation.enabled': on });
  },
  onChapterRationItem(e) {
    const index = Number(e.detail.value), item = this.data.runtimeChapterRation.items[index];
    if (item) this.setData({ 'runtimeChapterRation.itemIndex': index, 'runtimeChapterRation.itemLabel': item.name });
  },
  onChapterRationQuantity(e) { this.setData({ 'runtimeChapterRation.quantity': e.detail.value }); },
  onChapterRationFallback(e) { this.setData({ 'runtimeChapterRation.fallback': e.detail.value }); },
  onChapterRationValue(e) { const index = Number(e.detail.value); if (this.data.runtimeChapterRation.values[index]) this.setData({ 'runtimeChapterRation.valueIndex': index }); },
  _chapterRationIssue(draft) {
    if (!draft.enabled) return '';
    if (draft.reason) return draft.reason;
    if (!draft.items[draft.itemIndex]) return '请选择章末配给的物品';
    const quantity = Number(draft.quantity), fallback = Number(draft.fallback);
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > 999) return '配给数量须为 1 到 999 的整数';
    if (!Number.isInteger(fallback) || fallback < 0 || fallback > 999) return '物品不足时减少的数值须为 0 到 999 的整数';
    return '';
  },
  _saveChapterRation() {
    const draft = this.data.runtimeChapterRation;
    if (!draft.actKey) return true;
    const issue = this._chapterRationIssue(draft);
    if (issue) { toast(issue); return false; }
    const c = this._runtimeConfig(), game = c.gameplayRuntime || (c.gameplayRuntime = {});
    const survival = game.survival || (game.survival = { zeroHpPolicy: 'BLOCK_HP_COST', actionLimitPolicy: 'BLOCK_OVERFLOW' });
    const rations = (survival.rations || []).filter(row => row.actKey !== draft.actKey);
    if (draft.enabled) {
      let rationKey = draft.rationKey;
      if (!rationKey) { let n = 1; while (rations.some(row => row.rationKey === 'ration_' + n)) n++; rationKey = 'ration_' + n; }
      const value = (draft.values || [])[draft.valueIndex] || { key: 'HP' };
      const ration = { rationKey, actKey: draft.actKey, itemId: draft.items[draft.itemIndex].id, quantity: Number(draft.quantity) };
      if (value.key === 'HP') ration.fallbackHpCost = Number(draft.fallback); else ration.fallback = { statKey: value.key, amount: Number(draft.fallback) };
      rations.push(ration);
    }
    const before = JSON.stringify(survival.rations || []);
    if (rations.length) survival.rations = rations; else delete survival.rations;
    if (JSON.stringify(survival.rations || []) !== before) this._saveRuntime(c);
    return true;
  },
  onChapterCompletion(e) { this.setData({ 'runtimeChapterRules.completionType': e.currentTarget.dataset.type }); },
  onChapterQuantity(e) { this.setData({ 'runtimeChapterRules.quantity': e.detail.value }); },
  onChapterOverflowSlot(e) { this.setData({ 'runtimeChapterRules.overflowSlot': e.detail.value }); },
  reuseChapterOverflowSlot(e) { this.setData({ 'runtimeChapterRules.overflowSlot': e.currentTarget.dataset.slot }); },
  addChapterExclusiveGroup() {
    if (this.data.runtimeChapterRules.nodes.length < 2) { toast('请先保存本章至少 2 个站点'); return; }
    const groups = this.data.runtimeChapterRules.groups.slice();
    groups.push({ ids: [], options: this.data.runtimeChapterRules.nodes.map(node => ({ ...node, selected: false })) });
    this.setData({ 'runtimeChapterRules.groups': groups });
  },
  toggleChapterExclusiveNode(e) {
    const index = Number(e.currentTarget.dataset.index), id = Number(e.currentTarget.dataset.id);
    const groups = copy(this.data.runtimeChapterRules.groups), group = groups[index];
    if (!group) return;
    if (!group.ids.includes(id) && groups.some((other, i) => i !== index && other.ids.includes(id))) { toast('该站点已在其他互斥组'); return; }
    group.ids = group.ids.includes(id) ? group.ids.filter(value => value !== id) : group.ids.concat(id);
    group.options.forEach(option => { option.selected = group.ids.includes(option.id); });
    this.setData({ 'runtimeChapterRules.groups': groups });
  },
  removeChapterExclusiveGroup(e) {
    const groups = this.data.runtimeChapterRules.groups.slice(); groups.splice(Number(e.currentTarget.dataset.index), 1);
    this.setData({ 'runtimeChapterRules.groups': groups });
  },
  addChapterExtraNode() {
    const nodes = this.data.runtimeChapterRules.nodes;
    if (!nodes.length) { toast('请先保存本章站点，再配置额外节点'); return; }
    const extraNodes = this.data.runtimeChapterRules.extraNodes.concat({ nodeId: nodes[0].id, nodeIndex: 0, nodeName: nodes[0].name, slot: '' });
    this.setData({ 'runtimeChapterRules.extraNodes': extraNodes });
  },
  onChapterExtraNode(e) {
    const extraNodes = copy(this.data.runtimeChapterRules.extraNodes), index = Number(e.currentTarget.dataset.index);
    const nodeIndex = Number(e.detail.value), node = this.data.runtimeChapterRules.nodes[nodeIndex];
    if (!extraNodes[index] || !node) return;
    extraNodes[index].nodeId = node.id; extraNodes[index].nodeIndex = nodeIndex; extraNodes[index].nodeName = node.name;
    this.setData({ 'runtimeChapterRules.extraNodes': extraNodes });
  },
  onChapterExtraSlot(e) {
    const extraNodes = copy(this.data.runtimeChapterRules.extraNodes), index = Number(e.currentTarget.dataset.index);
    if (!extraNodes[index]) return;
    extraNodes[index].slot = e.detail.value;
    this.setData({ 'runtimeChapterRules.extraNodes': extraNodes });
  },
  reuseChapterExtraSlot(e) {
    const extraNodes = copy(this.data.runtimeChapterRules.extraNodes), index = Number(e.currentTarget.dataset.index);
    if (!extraNodes[index]) return;
    extraNodes[index].slot = e.currentTarget.dataset.slot;
    this.setData({ 'runtimeChapterRules.extraNodes': extraNodes });
  },
  removeChapterExtraNode(e) {
    const extraNodes = this.data.runtimeChapterRules.extraNodes.slice(); extraNodes.splice(Number(e.currentTarget.dataset.index), 1);
    this.setData({ 'runtimeChapterRules.extraNodes': extraNodes });
  },
  _saveChapterRules() {
    if (!this._saveChapterRation()) return false;
    const draft = this.data.runtimeChapterRules, index = draft.index;
    const completion = draft.completionType === 'ANY_N' ? { type: 'ANY_N', quantity: Number(draft.quantity) } : null;
    if (completion && String(draft.overflowSlot || '').trim()) completion.overflowSlot = String(draft.overflowSlot).trim();
    const exclusiveGroups = draft.groups.map(group => group.ids);
    const extraNodes = draft.extraNodes.map(item => ({ nodeId: item.nodeId, slot: String(item.slot || '').trim() }));
    const issue = this._chapterRuleIssue(index, { completion, exclusiveGroups, extraNodes });
    if (issue) { toast(issue); return false; }
    const c = this._runtimeConfig();
    let row = c.chapters.find(ch => ch.index === index);
    if (!row && !completion && !exclusiveGroups.length && !extraNodes.length) return true;
    // 已有规则的章节恢复默认时显式写 ALL：已发布编译过的 ANY_N/互斥/额外标记需要靠它清除；从未设过规则的章节仍不写（存量不变）
    const hadRules = !!(row && (row.completion || row.exclusiveGroups || row.extraNodes));
    if (!row) row = chapter(c, index);
    if (completion) row.completion = completion; else if (hadRules) row.completion = { type: 'ALL' }; else delete row.completion;
    if (exclusiveGroups.length) row.exclusiveGroups = exclusiveGroups; else delete row.exclusiveGroups;
    if (extraNodes.length) row.extraNodes = extraNodes; else delete row.extraNodes;
    this._saveRuntime(c);
    if (this._applyStructureChapterCompletion) this._applyStructureChapterCompletion(index, completion); // structure
    return true;
  },
  _refreshRuntimeEditor(c) {
    const index = this.data.runtimeEditor.index;
<<<<<<< public
    const ch = chapter(c, index);
    const options = purposeOptions(c, initial.parse(this.data.formData.journeyRules));
    this.setData({ runtimePurposes: options.map(row => row.label), runtimePurposeKeys: options.map(row => row.key),
      runtimeBag: { capacity: c.capacity, allowDiscard: c.allowDiscard, items: rows(c, index, options) },
      runtimeEggs: (ch.eggs || []).map(e => Object.assign({}, e, { triggerIndex: e.trigger === 'STEPS' ? 1 : 0,
        rewards: e.rewards.map(r => Object.assign({}, r, { selectedIndex: c.items.findIndex(i => i.id === r.itemId) })) })),
      runtimeCompletionIndex: c.items.findIndex(i => (c.grants || []).some(g => g.id === 'completion_material' && g.itemId === i.id)),
      runtimeRewardOptions: c.items.map(i => ({ id: i.id, name: i.name || (i.kind === 'MEDAL' ? '未命名勋章' : '未命名物品') })) });
=======
    const grantView = ['nodeGrants','choiceGrants'].includes(this.data.runtimeEditor.kind) ? grantScopes.view(c, this._runtimeGrantScopeId) : c;
    if (['nodeGrants','choiceGrants'].includes(this.data.runtimeEditor.kind)) this.setData({ runtimeGrantScope: grantScopes.project(c, this._runtimeGrantScopeId) });
    if (this.data.runtimeEditor.kind === 'choiceGrants') {
      const draft = this.data.runtimeChoiceGrantDraft, node = this._runtimeChoiceNodes[draft.nodeIndex], step = node && node.steps[draft.stepIndex];
      this.setData({ runtimeChoiceGrants: choiceGrants.settings(grantView, this._runtimeChoiceNodes, this._runtimeGrantConditionContext), runtimeChoiceSteps: node ? copy(node.steps) : [], runtimeChoiceOptions: step ? copy(step.options) : [] });
    }
    const ch = ['bag', 'eggs'].includes(this.data.runtimeEditor.kind) ? chapter(c, index) : { grants: [], eggs: [] };
    if (this.data.runtimeEditor.kind === 'nodeGrants') this.setData({ runtimeNodeGrants: nodeGrantSettings(grantView, this._runtimeGrantNodes, this._runtimeGrantConditionContext) });
    this.setData({ runtimeBag: { capacity: c.capacity, allowDiscard: c.allowDiscard, items: ['bag', 'eggs'].includes(this.data.runtimeEditor.kind) ? rows(c, index) : [] },
      runtimeEggs: ch.eggs.map(e => Object.assign({}, e, { triggerIndex: e.trigger === 'STEPS' ? 1 : 0,
        rewards: e.rewards.map(r => Object.assign({}, r, { selectedIndex: c.items.findIndex(i => i.id === r.itemId) })) })),
      runtimeCompletionIndex: c.items.findIndex(i => (c.grants || []).some(g => g.id === 'completion_material' && g.itemId === i.id)),
      runtimeRewardOptions: c.items.map(i => ({ id: i.id, name: i.name || (i.kind === 'MEDAL' ? '未命名勋章' : '未命名物品') })),
      runtimeResultCard: this.data.runtimeEditor.kind === 'card' ? cardSettings(c) : cardSettings({}),
      runtimeScenes: this.data.runtimeEditor.kind === 'scenes' ? sceneSettings(c) : sceneSettings({}),
      runtimeBroadcasts: this.data.runtimeEditor.kind === 'broadcasts' ? broadcastSettings(c, false, this._runtimeBroadcastKeys, this._runtimeBroadcastCreated) : { events: [], scopedOverrides: 0 } });
>>>>>>> private-github/featu
  },
  _openRuntime(kind, title) {
    const c = this._runtimeConfig();
    if (['nodeGrants','choiceGrants'].includes(kind)) {
      choiceGrants.root(this.data.formData.themeRuntimeConfig);
      grantScopes.project(c, '');
      this._runtimeGrantScopeId = ''; this._runtimeGrantScopeIntent = null;
      this._runtimeGrantOriginal = copy(c); this._runtimeGrantRaw = this.data.formData.themeRuntimeConfig;
      this._runtimeGrantConditionContext = grantConditions.context(this.data.formData.journeyRules, savedGrantNodes(this.data).nodes);
      this._runtimeGrantWhenSequence = this._runtimeGrantWhenSequence || 0; this._runtimeGrantWhenRows = [];
      this.setData({ runtimeGrantWhenRows: [], runtimeGrantConditionDraft: grantConditions.emptyDraft(), runtimeGrantConditionSources: grantConditions.sources(this._runtimeGrantConditionContext), runtimeGrantConditionNodes: copy(this._runtimeGrantConditionContext.nodes), runtimeGrantConditionKind: '', runtimeGrantConditionContext: { enabled: this._runtimeGrantConditionContext.enabled, error: this._runtimeGrantConditionContext.error } });
    }
    if (kind === 'journeyChecks') {
      const scope = this._nodeGrantSnapshot();
      if (!scope) throw new Error('草稿来源无法确认，请重新打开');
      this._runtimeJourneyCheckScope = scope;
      this._runtimeJourneyCheckRaw = this.data.formData.journeyRules;
      this.setData({ runtimeJourneyChecks: journeyChecks.project(this._runtimeJourneyCheckRaw) });
    }
    if (kind === 'choiceGrants') {
      choiceGrants.root(this.data.formData.themeRuntimeConfig);
      const source = this._nodeGrantSnapshot();
      if (!source) throw new Error('站点或草稿配置无法确认，请重新进入编辑器');
      const selected = choiceGrants.choices(this.data, c);
      choiceGrants.settings(c, selected.nodes, this._runtimeGrantConditionContext);
      this._runtimeChoiceNodes = copy(selected.nodes); this._runtimeChoiceScope = source;
      this._runtimeChoiceRaw = this.data.formData.themeRuntimeConfig;
      this._runtimeChoiceIds = grantScopes.ids(c);
      this._runtimeChoiceDelete = null; this._runtimeChoiceEdit = null;
      this.setData({ runtimeChoiceNodes: copy(selected.nodes), runtimeChoiceBlocked: selected.blocked, runtimeChoiceUnsaved: selected.unsaved, runtimeChoiceGrantDraft: choiceGrants.emptyDraft() });
    }
    const initialCard = kind === 'card' ? JSON.stringify(cardSettings(c)) : null;
    const initialScenes = kind === 'scenes' ? JSON.stringify(sceneSettings(c)) : null;
    if (kind === 'broadcasts') { assertBroadcastSafeNumbers(c); broadcastSettings(c); }
    if (kind === 'rules') { assertBroadcastSafeNumbers(c); runtimeRuleSettings(c); }
    if (kind === 'nodeGrants') {
      assertBroadcastSafeNumbers(c);
      const source = this._nodeGrantSnapshot();
      if (!source) throw new Error('站点或草稿配置无法确认，请重新进入编辑器');
      const selected = savedGrantNodes(this.data);
      nodeGrantSettings(c, selected.nodes, this._runtimeGrantConditionContext);
      this._runtimeGrantNodes = copy(selected.nodes); this._runtimeNodeGrantScope = source;
      this._runtimeNodeGrantInitial = JSON.stringify(c.grants); this._runtimeNodeGrantIds = grantScopes.ids(c);
      this._runtimeNodeGrantDelete = null; this._runtimeNodeGrantEdit = null;
      this.setData({ runtimeGrantNodes: selected.nodes, runtimeGrantUnsaved: selected.unsaved, runtimeNodeGrantDraft: emptyNodeGrantDraft() });
    }
    if (kind === 'bag' || kind === 'eggs') chapter(c, this._runtimeChapterIndex());
    this._runtimeWorking = copy(c);
    this._runtimeEditorEpoch = (this._runtimeEditorEpoch || 0) + 1;
    this._runtimeCardScope = kind === 'card' ? this._resultCardScope() : null;
    this._runtimeCardBaseRaw = JSON.stringify(this.data.formData.themeRuntimeConfig);
    this._runtimeCardInitial = initialCard;
    this._runtimeCardCoverSeq = (this._runtimeCardCoverSeq || 0) + 1;
    this._runtimeSceneScope = kind === 'scenes' ? this._resultCardScope() : null;
    this._runtimeSceneBaseRaw = JSON.stringify(this.data.formData.themeRuntimeConfig);
    this._runtimeSceneInitial = initialScenes; this._runtimeSceneDelete = null;
    this._runtimeBroadcastScope = kind === 'broadcasts' ? this._resultCardScope() : null;
    this._runtimeBroadcastRulesRaw = JSON.stringify(this.data.formData.journeyRules);
    this._runtimeBroadcastCreated = new Set();
    const attributes = kind === 'broadcasts' ? broadcastAttributes(this.data.formData.journeyRules) : { keys: [], enabled: false, error: '' };
    this._runtimeBroadcastKeys = copy(attributes.keys);
    this.setData({ runtimeBroadcastAttributes: attributes, runtimeBroadcastTriggers: broadcastTriggers(attributes.keys) });
    this._runtimeBroadcastBaseRaw = JSON.stringify(this.data.formData.themeRuntimeConfig);
    this._runtimeBroadcastInitial = kind === 'broadcasts' ? JSON.stringify(c.events) : null;
    this._runtimeBroadcastIds = new Set(kind === 'broadcasts' ? (c.events || []).map(row => row.id) : []);
    this._runtimeBroadcastDelete = null;
    this._runtimeRulesScope = kind === 'rules' ? this._resultCardScope() : null;
    this._runtimeRulesBaseRaw = JSON.stringify(this.data.formData.themeRuntimeConfig);
    if (kind === 'rules') this.setData({ runtimeRules: runtimeRuleSettings(c) });
    this.setData({ runtimeBroadcastDraft: emptyBroadcastDraft() });
    this.setData({ runtimeRewards: { couponId: this.data.formData.completeRewardCouponId || 0, couponName: this.data.completionRewardCouponName || '', medalName: this.data.formData.finishMedalName || '', medalImage: this.data.formData.finishMedalImg || '' } });
    this.setData({ runtimeEditor: { show: true, kind, title, index: this._runtimeChapterIndex() } });
    this._refreshRuntimeEditor(c);
  },
  openChapterBackpack() { this._openRuntime('bag', '背包'); },
  openChapterEggs() { this._openRuntime('eggs', '途中彩蛋'); },
  toggleSubtitleEditing() { this.setData({ subtitleEditing: !this.data.subtitleEditing }); },
  openThemeBingo() {
    if (this.data.editScope === 'WHITELIST' || this.data.runtimeEditor.show || (this.data.editingTopicId && !this.data.editLoaded)) return false;
    this._runtimeBingoOriginal = copy(this.data.bingoRows || []);
    this.setData({ runtimeEditor: { show: true, kind: 'bingo', title: '宾果九宫格', index: 0 } });
    return true;
  },
  openRuntimeBingoCell(e) { const index = Number(e.currentTarget.dataset.index); if (!this.data.bingoRows || !this.data.bingoRows[index]) return false; this.setData({ runtimeBingoIndex: index, 'runtimeEditor.kind': 'bingo-cell', 'runtimeEditor.title': '编辑格子' }); },
  backRuntimeBingo() { this.setData({ 'runtimeEditor.kind': 'bingo', 'runtimeEditor.title': '宾果九宫格' }); },
  openThemeRewards() { this._openRuntime('rewards', '主题通关奖励'); },
  openThemeResultCard() {
    if (this.data.runtimeEditor.show || this.data.popChapter) return false;
    return this._openRuntime('card', '公开结果设置');
  },
  openThemeScenes() {
    if (this.data.runtimeEditor.show || this.data.popChapter || this._runtimeChapterWorking) return false;
    return this._openRuntime('scenes', '重玩与场景');
  },
  openThemeBroadcasts() {
    if (this.data.runtimeEditor.show || this.data.popChapter || this._runtimeChapterWorking) return false;
    return this._openRuntime('broadcasts', '主题事件广播');
  },
  openJourneyCheckSettings() {
    if (this.data.runtimeEditor.show || this.data.popChapter || this._runtimeChapterWorking) return false;
    return this._openRuntime('journeyChecks', '检定难度与恢复');
  },
  openThemeChoiceGrants() {
    if (this.data.runtimeEditor.show || this.data.popChapter || this._runtimeChapterWorking) return false;
    return this._openRuntime('choiceGrants', '分支选项物资');
  },
  openThemeNodeGrants() {
    if (this.data.runtimeEditor.show || this.data.popChapter || this._runtimeChapterWorking) return false;
    return this._openRuntime('nodeGrants', '站点完成物资');
  },
  openThemeRuntimeRules() {
    if (this.data.runtimeEditor.show || this.data.popChapter || this._runtimeChapterWorking) return false;
    return this._openRuntime('rules', '主题运行规则');
  },
  closeRuntimeEditor() {
    if (this.data.runtimeRewardOpen) return false;
    const kind = this.data.runtimeEditor.kind;
    if (kind === 'bingo' || kind === 'bingo-cell') { this._runtimeBingoOriginal = null; this._dismissRuntimeEditor(); return true; }
    if (kind === 'journeyChecks') {
      if (!this._journeyChecksLive(this._runtimeEditorEpoch)) { this._dismissRuntimeEditor(); toast('属性上限或草稿已变化，请重新打开'); return false; }
      try {
        const raw = journeyChecks.apply(this._runtimeJourneyCheckRaw, this.data.runtimeJourneyChecks);
        if (raw !== this.data.formData.journeyRules) this.setData({ 'formData.journeyRules': raw });
        this._dismissRuntimeEditor(); return true;
      } catch (error) { this.setData({ 'runtimeJourneyChecks.error': error.message }); return false; }
    }
    if (['choiceGrants','nodeGrants'].includes(kind)) {
      if (!this._grantEditorLive(this._runtimeEditorEpoch)) { this._dismissRuntimeEditor(); toast('场景、站点、物资或草稿状态已变化，请重新打开'); return false; }
      const draft = kind === 'choiceGrants' ? this.data.runtimeChoiceGrantDraft : this.data.runtimeNodeGrantDraft;
      const empty = kind === 'choiceGrants' ? choiceGrants.emptyDraft() : emptyNodeGrantDraft();
      if (JSON.stringify(draft) !== JSON.stringify(empty) || this._grantConditionPending() || this._runtimeGrantWhenRows.length) { toast('请先暂存这条关联，或取消正在编辑的关联'); return false; }
      const c = this._runtimeConfig(), output = grantScopes.serialize(this._runtimeGrantRaw, this._runtimeGrantOriginal, c);
      if (output !== this._runtimeGrantRaw) {
        const issue = validate(c, initial.parse(this.data.formData.journeyRules), false);
        if (issue) { toast(issue); return false; }
        this.setData({ 'formData.themeRuntimeConfig': output });
      }
      this._dismissRuntimeEditor(); return true;
    }
    if (kind === 'card' && !this._resultCardLive(this._runtimeEditorEpoch)) { this._dismissRuntimeEditor(); toast('草稿或编辑状态已变化，请重新打开'); return false; }
    if (kind === 'scenes' && !this._sceneEditorLive(this._runtimeEditorEpoch)) { this._dismissRuntimeEditor(); toast('草稿或编辑状态已变化，请重新打开'); return false; }
    if (kind === 'broadcasts' && !this._broadcastEditorLive(this._runtimeEditorEpoch)) { this._dismissRuntimeEditor(); toast('草稿或编辑状态已变化，请重新打开'); return false; }
    if (kind === 'rules' && !this._runtimeRulesLive(this._runtimeEditorEpoch)) { this._dismissRuntimeEditor(); toast('草稿或编辑状态已变化，请重新打开'); return false; }
    if (kind === 'broadcasts' && JSON.stringify(this.data.runtimeBroadcastDraft) !== JSON.stringify(emptyBroadcastDraft())) { toast('请先添加这条广播，或清空新增内容'); return false; }
    const c = this._runtimeConfig();
    if (kind === 'rules') {
      const before = JSON.stringify(c); applyRuntimeRules(c, this.data.runtimeRules);
      if (JSON.stringify(c) === before) { this._dismissRuntimeEditor(); return true; }
    }
    // A view-only visit, cancelled choice or change reverted to its original meaning preserves raw bytes.
    if (kind === 'card' && JSON.stringify(cardSettings(c)) === this._runtimeCardInitial) { this._dismissRuntimeEditor(); return true; }
    if (kind === 'scenes' && JSON.stringify(sceneSettings(c)) === this._runtimeSceneInitial) { this._dismissRuntimeEditor(); return true; }
    if (kind === 'broadcasts' && (JSON.stringify(c.events) === this._runtimeBroadcastInitial
        || (this._runtimeBroadcastInitial === 'null' && Array.isArray(c.events) && !c.events.length))) { this._dismissRuntimeEditor(); return true; }
    if (kind === 'nodeGrants' && (JSON.stringify(c.grants) === this._runtimeNodeGrantInitial
        || (this._runtimeNodeGrantInitial === 'null' && Array.isArray(c.grants) && !c.grants.length))) { this._dismissRuntimeEditor(); return true; }
    const issue = validate(c, initial.parse(this.data.formData.journeyRules), false);
    if (issue) { toast(issue); return false; }
    this._runtimeWorking = null; this._saveRuntime(c);
    if (kind === 'rewards') {
      const reward = this.data.runtimeRewards;
      const patch = { 'formData.completeRewardCouponId': reward.couponId, completionRewardCouponName: reward.couponName, completeRewardOn: !!reward.couponId };
      if (this.data.medalEditable) Object.assign(patch, { 'formData.finishMedalName': reward.medalName, 'formData.finishMedalImg': reward.medalImage });
      this.setData(patch);
    }
    this._dismissRuntimeEditor(); return true;
  },
  cancelRuntimeEditor() { if (this.data.runtimeRewardOpen) return false; if (this._runtimeBingoOriginal) { this.setData({ bingoRows: this._runtimeBingoOriginal }); this._runtimeBingoOriginal = null; } this._dismissRuntimeEditor(); return true; },
  _dismissRuntimeEditor() { this._runtimeGrantWhenRows = []; this._runtimeGrantConditionContext = null; this._runtimeGrantScopeIntent = null; this._runtimeGrantScopeId = ''; this._runtimeGrantOriginal = null; this._runtimeGrantRaw = undefined; this._runtimeChoiceScope = null; this._runtimeChoiceRaw = undefined; this._runtimeChoiceDelete = null; this._runtimeChoiceEdit = null; this._runtimeChoiceNodes = []; this._runtimeJourneyCheckScope = null; this._runtimeJourneyCheckRaw = undefined; this._runtimeNodeGrantScope = null; this._runtimeNodeGrantDelete = null; this._runtimeNodeGrantEdit = null; this._runtimeGrantNodes = []; this._runtimeWorking = null; this._runtimeCardScope = null; this._runtimeCardInitial = null; this._runtimeCardBaseRaw = undefined; this._runtimeSceneScope = null; this._runtimeSceneInitial = null; this._runtimeSceneBaseRaw = undefined; this._runtimeSceneDelete = null; this._runtimeBroadcastScope = null; this._runtimeBroadcastRulesRaw = undefined; this._runtimeBroadcastKeys = []; this._runtimeBroadcastCreated = new Set(); this._runtimeBroadcastInitial = null; this._runtimeBroadcastBaseRaw = undefined; this._runtimeBroadcastDelete = null; this._runtimeRulesScope = null; this._runtimeRulesBaseRaw = undefined; this._runtimeEditorEpoch = (this._runtimeEditorEpoch || 0) + 1; this.setData({ 'runtimeEditor.show': false, runtimeRewardOpen: false, runtimeBroadcastDraft: emptyBroadcastDraft() }); },
  _resultCardScope() {
    return JSON.stringify([app.getUserID && app.getUserID(), this.data.editingTopicId || '', this.data.draftUuid || '',
      this.data.draftMemberId || '', this.data.baseRevision || '', this.data.operationScope || '']);
  },
  _resultCardLive(epoch) {
    return this._runtimeMayEdit() && this.data.runtimeEditor.show && this.data.runtimeEditor.kind === 'card'
      && !!this._runtimeWorking && epoch === this._runtimeEditorEpoch && this._runtimeCardScope === this._resultCardScope()
      && this._runtimeCardBaseRaw === JSON.stringify(this.data.formData.themeRuntimeConfig);
  },
  onRuntimeCardField(e) {
    if (!this._resultCardLive(this._runtimeEditorEpoch)) return false;
    const key = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.field;
    const enabled = e && e.detail && e.detail.value;
    if (!CARD_FIELDS.some(row => row.key === key) || typeof enabled !== 'boolean') return false;
    const c = this._runtimeConfig(), settings = cardSettings(c);
    if (settings.fields.find(row => row.key === key).enabled === enabled) return false;
    c.card = Object.assign({}, c.card);
    c.card.fields = settings.fields.filter(row => row.key === key ? enabled : row.enabled).map(row => row.key);
    this._saveRuntime(c); this._refreshRuntimeEditor(c); return true;
  },
  onRuntimeCardUnlocks(e) {
    if (!this._resultCardLive(this._runtimeEditorEpoch) || !e || !e.detail || typeof e.detail.value !== 'boolean') return false;
    const c = this._runtimeConfig();
    if (cardSettings(c).showUnlocks === e.detail.value) return false;
    c.card = Object.assign({}, c.card, { showUnlocks: e.detail.value });
    this._saveRuntime(c); this._refreshRuntimeEditor(c); return true;
  },
  uploadRuntimeCardCover() {
    const epoch = this._runtimeEditorEpoch;
    if (!this._resultCardLive(epoch)) return false;
    const seq = this._runtimeCardCoverSeq = (this._runtimeCardCoverSeq || 0) + 1;
    app.chooseImage(res => {
      if (!this._resultCardLive(epoch) || seq !== this._runtimeCardCoverSeq || !res || !res[0]) return;
      const c = this._runtimeConfig(); c.card = Object.assign({}, c.card, { cover: res[0] });
      try { cardSettings(c); } catch (error) { toast(error.message); return; }
      this._saveRuntime(c); this._refreshRuntimeEditor(c);
    }, 1, { crop: true, cropScale: '16:9' });
    return true;
  },
  clearRuntimeCardCover() {
    if (!this._resultCardLive(this._runtimeEditorEpoch)) return false;
    this._runtimeCardCoverSeq = (this._runtimeCardCoverSeq || 0) + 1;
    const c = this._runtimeConfig();
<<<<<<< public
    if (this.data.runtimeEditor.kind === 'bag' || this.data.runtimeEditor.kind === 'eggs') {
      const issue = validate(c, initial.parse(this.data.formData.journeyRules), this._zeroHpPolicyKeys(c));
      if (issue) { toast(issue); return; }
=======
    if (!cardSettings(c).cover) return false;
    delete c.card.cover; // Clear this draft reference only; never delete the remote media asset.
    this._saveRuntime(c); this._refreshRuntimeEditor(c); return true;
  },
  _sceneEditorLive(epoch) {
    return this._runtimeMayEdit() && this.data.runtimeEditor.show && this.data.runtimeEditor.kind === 'scenes'
      && !!this._runtimeWorking && epoch === this._runtimeEditorEpoch && this._runtimeSceneScope === this._resultCardScope()
      && this._runtimeSceneBaseRaw === JSON.stringify(this.data.formData.themeRuntimeConfig);
  },
  onRuntimeReplayToggle(e) {
    if (!this._sceneEditorLive(this._runtimeEditorEpoch) || !e || !e.detail || typeof e.detail.value !== 'boolean') return false;
    const c = this._runtimeConfig(); if (sceneSettings(c).replayEnabled === e.detail.value) return false;
    c.replayEnabled = e.detail.value; this._saveRuntime(c); this._refreshRuntimeEditor(c); return true;
  },
  onRuntimeRotation(e) {
    if (!this._sceneEditorLive(this._runtimeEditorEpoch) || !e || !e.detail) return false;
    const value = e.detail.value;
    if (![0,1,'0','1'].includes(value)) return false;
    const mode = ['FIXED','ROUND_ROBIN'][Number(value)], c = this._runtimeConfig();
    if (sceneSettings(c).mode === mode) return false;
    c.rotation = mode; // Explicit selection only; keep any existing weekly anchor unchanged.
    this._saveRuntime(c); this._refreshRuntimeEditor(c); return true;
  },
  addRuntimeScene() {
    if (!this._sceneEditorLive(this._runtimeEditorEpoch)) return false;
    const c = this._runtimeConfig(); sceneSettings(c);
    const list = c.scenes || [];
    if (list.length >= 50) { toast('最多 50 个场景'); return false; }
    const id = nextId('s', list);
    list.push({ id, name: '' }); c.scenes = list;
    if (list.length === 1) c.currentScene = id; // No prior scene reference exists on first creation.
    this._saveRuntime(c); this._refreshRuntimeEditor(c); return true;
  },
  onRuntimeSceneName(e) {
    if (!this._sceneEditorLive(this._runtimeEditorEpoch)) return false;
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id, value = e && e.detail && e.detail.value;
    if (typeof value !== 'string') return false;
    if (value.length > 40) { toast('场景名称最多 40 个字符，部分表情按 2 个计'); return false; }
    const c = this._runtimeConfig(), row = (c.scenes || []).find(scene => scene.id === id);
    if (!row || row.name === value) return false;
    row.name = value; this._saveRuntime(c); this._refreshRuntimeEditor(c); return true;
  },
  chooseRuntimeScene(e) {
    if (!this._sceneEditorLive(this._runtimeEditorEpoch)) return false;
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id, c = this._runtimeConfig();
    if (!(c.scenes || []).some(row => row.id === id) || c.currentScene === id) return false;
    c.currentScene = id; this._saveRuntime(c); this._refreshRuntimeEditor(c); return true;
  },
  moveRuntimeScene(e) {
    if (!this._sceneEditorLive(this._runtimeEditorEpoch)) return false;
    const d = e && e.currentTarget && e.currentTarget.dataset;
    if (!d || !['up','down'].includes(d.direction)) return false;
    const c = this._runtimeConfig(), list = c.scenes || [], index = list.findIndex(row => row.id === d.id), target = index + (d.direction === 'up' ? -1 : 1);
    if (index < 0 || target < 0 || target >= list.length) return false;
    const row = list.splice(index,1)[0]; list.splice(target,0,row);
    // The chosen ID remains the same; only the explicit ordering is changed.
    this._saveRuntime(c); this._refreshRuntimeEditor(c); return true;
  },
  removeRuntimeScene(e) {
    const epoch = this._runtimeEditorEpoch;
    if (!this._sceneEditorLive(epoch) || this._runtimeSceneDelete) return false;
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id;
    const c = this._runtimeConfig(), row = (c.scenes || []).find(scene => scene.id === id);
    if (!row) return false;
    if (id === c.currentScene) { toast('请先明确选择另一个起始场景，再删除这个场景'); return false; }
    const intent = { id, snapshot: JSON.stringify(row) }; this._runtimeSceneDelete = intent;
    try {
      modal.show({ title: '删除这个场景？', content: '将从当前草稿移除“' + (row.name || '未命名场景') + '”及其全部已有配置（含发放、事件、结局、剧情和节点设置）。当前起始场景不变，已开局快照不变；仍需按原流程保存与发布。', confirmText: '删除场景',
        success: result => {
          if (this._runtimeSceneDelete !== intent) return;
          this._runtimeSceneDelete = null;
          if (!result.confirm || !this._sceneEditorLive(epoch)) return;
          const latest = this._runtimeConfig(), target = (latest.scenes || []).find(scene => scene.id === id);
          if (!target || latest.currentScene === id || JSON.stringify(target) !== intent.snapshot) { toast('场景或起始选择已变化，请重新确认'); return; }
          latest.scenes = latest.scenes.filter(scene => scene.id !== id);
          this._saveRuntime(latest); this._refreshRuntimeEditor(latest);
        }, fail: () => { if (this._runtimeSceneDelete === intent) this._runtimeSceneDelete = null; } });
    } catch (error) { this._runtimeSceneDelete = null; throw error; }
    return true;
  },
  _broadcastEditorLive(epoch) {
    return this._runtimeMayEdit() && this.data.runtimeEditor.show && this.data.runtimeEditor.kind === 'broadcasts'
      && !!this._runtimeWorking && epoch === this._runtimeEditorEpoch && this._runtimeBroadcastScope === this._resultCardScope()
      && this._runtimeBroadcastBaseRaw === JSON.stringify(this.data.formData.themeRuntimeConfig)
      && this._runtimeBroadcastRulesRaw === JSON.stringify(this.data.formData.journeyRules);
  },
  _runtimeRulesLive(epoch) {
    return this._runtimeMayEdit() && this.data.runtimeEditor.show && this.data.runtimeEditor.kind === 'rules'
      && !!this._runtimeWorking && epoch === this._runtimeEditorEpoch && this._runtimeRulesScope === this._resultCardScope()
      && this._runtimeRulesBaseRaw === JSON.stringify(this.data.formData.themeRuntimeConfig);
  },
  onRuntimeRuleName(e) {
    if (!this._runtimeRulesLive(this._runtimeEditorEpoch)) return false;
    const key = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.field, value = e && e.detail && e.detail.value;
    if (!['hpName','luckName'].includes(key) || typeof value !== 'string') return false;
    if (value.length > 20) { toast('数值名称最多 20 个字符，部分表情按 2 个计'); return false; }
    this.setData({ ['runtimeRules.' + key]: value, ['runtimeRules.' + key + 'Configured']: true }); return true;
  },
  clearRuntimeRuleName(e) {
    if (!this._runtimeRulesLive(this._runtimeEditorEpoch)) return false;
    const key = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.field;
    if (!['hpName','luckName'].includes(key)) return false;
    this.setData({ ['runtimeRules.' + key]: '', ['runtimeRules.' + key + 'Configured']: false }); return true;
  },
  onRuntimeBudgetToggle(e) {
    if (!this._runtimeRulesLive(this._runtimeEditorEpoch) || !e || !e.detail || typeof e.detail.value !== 'boolean') return false;
    // Keep the typed value while toggling, but never create a default or activate a budget here.
    this.setData({ 'runtimeRules.budgetEnabled': e.detail.value }); return true;
  },
  onRuntimeRuleInteger(e) {
    if (!this._runtimeRulesLive(this._runtimeEditorEpoch)) return false;
    const key = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.field, value = e && e.detail && e.detail.value;
    if (!['budgetValue','rescueValue'].includes(key) || typeof value !== 'string' || value.length > 16) return false;
    this.setData({ ['runtimeRules.' + key]: value }); return true;
  },
  onRuntimeActionSource(e) {
    if (!this._runtimeRulesLive(this._runtimeEditorEpoch)) return false;
    const key = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.field, value = e && e.detail && e.detail.value;
    if (!ACTION_SOURCES.some(row => row.key === key) || typeof value !== 'boolean') return false;
    this.setData({ 'runtimeRules.sources': this.data.runtimeRules.sources.map(row => row.key === key ? Object.assign({}, row, { enabled: value }) : row) }); return true;
  },
  _journeyChecksLive(epoch) {
    return this._runtimeMayEdit() && this.data.runtimeEditor.show && this.data.runtimeEditor.kind === 'journeyChecks'
      && !!this._runtimeWorking && epoch === this._runtimeEditorEpoch && this._runtimeJourneyCheckScope === this._nodeGrantSnapshot();
  },
  onJourneyCheckEnabled(e) {
    if (!this._journeyChecksLive(this._runtimeEditorEpoch)) return false;
    const value = e && e.detail && e.detail.value;
    if (!/^(0|[1-9][0-9]*)$/.test(String(value))) return false;
    const original = journeyChecks.project(this._runtimeJourneyCheckRaw), option = original.enabledOptions[Number(value)];
    if (original.readOnly) return false;
    if (!option) return false;
    this.setData({ 'runtimeJourneyChecks.enabledMode': option.mode, 'runtimeJourneyChecks.enabledIndex': Number(value), 'runtimeJourneyChecks.error': '' }); return true;
  },
  onJourneyCheckNumber(e) {
    if (!this._journeyChecksLive(this._runtimeEditorEpoch)) return false;
    const path = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.field, value = e && e.detail && e.detail.value;
    if (typeof value !== 'string' || value.length > 16) return false;
    const original = journeyChecks.project(this._runtimeJourneyCheckRaw), index = original.fields.findIndex(field => field.path === path);
    if (original.readOnly) return false;
    if (index < 0) return false;
    const fields = copy(this.data.runtimeJourneyChecks.fields), row = fields[index];
    if (row.value === value) return false;
    row.value = value; row.mode = value === '' ? 'missing' : 'value'; row.presence = value === '' ? '暂存为未配置' : '暂存明确值';
    this.setData({ 'runtimeJourneyChecks.fields': fields, 'runtimeJourneyChecks.error': '' }); return true;
  },
  clearJourneyCheckNumber(e) {
    if (!this._journeyChecksLive(this._runtimeEditorEpoch)) return false;
    const path = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.field;
    const original = journeyChecks.project(this._runtimeJourneyCheckRaw), index = original.fields.findIndex(field => field.path === path);
    if (original.readOnly) return false;
    if (index < 0) return false;
    const fields = copy(this.data.runtimeJourneyChecks.fields); Object.assign(fields[index], { value: '', mode: 'missing', presence: '暂存为未配置' });
    this.setData({ 'runtimeJourneyChecks.fields': fields, 'runtimeJourneyChecks.error': '' }); return true;
  },
  _nodeGrantSnapshot() {
    try {
      const source = JSON.stringify([this._resultCardScope(), this._editLoadToken, this.data.formData.themeRuntimeConfig,
        this.data.formData.journeyRules, this.data.formData.chapters]);
      return source.length <= 1000000 ? source : null;
    } catch (_) { return null; }
  },
  _grantEditorLive(epoch) { return this.data.runtimeEditor.kind === 'choiceGrants' ? this._choiceGrantsLive(epoch) : this.data.runtimeEditor.kind === 'nodeGrants' && this._nodeGrantsLive(epoch); },
  _grantScopeEditable() { return !grantScopes.inherited(this._runtimeConfig(), this._runtimeGrantScopeId); },
  _grantConfig() { return grantScopes.view(this._runtimeConfig(), this._runtimeGrantScopeId); },
  _saveGrantConfig(view) { this._saveRuntime(grantScopes.replace(this._runtimeConfig(), this._runtimeGrantScopeId, view.grants)); },
  _resetGrantScope(id) {
    const config = this._runtimeConfig(); grantScopes.project(config, id);
    const selected = this.data.runtimeEditor.kind === 'choiceGrants' ? choiceGrants.choices(this.data, config, id) : null;
    if (selected) choiceGrants.settings(grantScopes.view(config, id), selected.nodes, this._runtimeGrantConditionContext);
    else nodeGrantSettings(grantScopes.view(config, id), this._runtimeGrantNodes, this._runtimeGrantConditionContext);
    this._runtimeGrantScopeId = id; this._runtimeEditorEpoch = (this._runtimeEditorEpoch || 0) + 1;
    this._runtimeGrantScopeIntent = null; this._runtimeChoiceDelete = null; this._runtimeNodeGrantDelete = null;
    this._runtimeChoiceEdit = null; this._runtimeNodeGrantEdit = null;
    this.setData({ runtimeChoiceGrantDraft: choiceGrants.emptyDraft(), runtimeNodeGrantDraft: emptyNodeGrantDraft() });
    this._resetGrantWhen();
    if (selected) { this._runtimeChoiceNodes = copy(selected.nodes); this.setData({ runtimeChoiceNodes: copy(selected.nodes), runtimeChoiceBlocked: selected.blocked, runtimeChoiceUnsaved: selected.unsaved }); }
    this._refreshRuntimeEditor(config);
  },
  onRuntimeGrantScope(e) {
    if (!this._grantEditorLive(this._runtimeEditorEpoch)) return false;
    const value = e && e.detail && e.detail.value, options = grantScopes.project(this._runtimeConfig(), this._runtimeGrantScopeId).options;
    if (!/^(0|[1-9][0-9]*)$/.test(String(value)) || Number(value) >= options.length) return false;
    if (options[Number(value)].id === this._runtimeGrantScopeId) return true;
    this._resetGrantScope(options[Number(value)].id); return true;
  },
  _confirmGrantScope(action) {
    const epoch = this._runtimeEditorEpoch;
    if (!this._grantEditorLive(epoch) || this._runtimeGrantScopeIntent) return false;
    const config = this._runtimeConfig(), id = this._runtimeGrantScopeId, current = grantScopes.project(config, id);
    if ((action === 'copy' && !current.inherited) || (action === 'restore' && !current.canRestore)) return false;
    const intent = { id, source: JSON.stringify(config), action }; this._runtimeGrantScopeIntent = intent;
    try {
      modal.show({ title: action === 'copy' ? '为此场景创建独立物资配置？' : '恢复沿用主物资配置？',
        content: action === 'copy' ? '将当前主物资关联完整复制到所选场景；此后主关联的改动不会自动同步到此场景。高级条目及无法对应此场景玩法的引用均原样保留并只读，不会自动改写引用。仅暂存当前草稿，已有局与库存不变。'
          : '仅移除当前场景的空物资列表字段，之后沿用主配置。主配置及其他场景不变，仍需按原保存流程提交。',
        confirmText: action === 'copy' ? '确认创建' : '恢复沿用', success: result => {
          if (this._runtimeGrantScopeIntent !== intent) return;
          this._runtimeGrantScopeIntent = null;
          if (!result.confirm || !this._grantEditorLive(epoch) || this._runtimeGrantScopeId !== id || JSON.stringify(this._runtimeConfig()) !== intent.source) return;
          const next = action === 'copy' ? grantScopes.copyRoot(config, id) : grantScopes.restore(config, id);
          this._saveRuntime(next); this._resetGrantScope(id);
        }, fail: () => { if (this._runtimeGrantScopeIntent === intent) this._runtimeGrantScopeIntent = null; } });
    } catch (error) { this._runtimeGrantScopeIntent = null; throw error; }
    return true;
  },
  createIndependentGrantScope() { return this._confirmGrantScope('copy'); },
  restoreInheritedGrantScope() { return this._confirmGrantScope('restore'); },
  _grantConditionPending() { return JSON.stringify(this.data.runtimeGrantConditionDraft) !== JSON.stringify(grantConditions.emptyDraft()); },
  _syncGrantWhen() {
    const config = this._grantConfig(), context = this._runtimeGrantConditionContext;
    this.setData({ runtimeGrantWhenRows: this._runtimeGrantWhenRows.map(row => ({ id: row.id, label: grantConditions.label(row.condition, config, context) })) });
  },
  _resetGrantWhen(grant) {
    const values = grantConditions.read(grant && grant.when, this._grantConfig(), this._runtimeGrantConditionContext);
    this._runtimeGrantWhenRows = values.map(condition => ({ id: 'w' + (++this._runtimeGrantWhenSequence), condition }));
    this.setData({ runtimeGrantConditionDraft: grantConditions.emptyDraft(), runtimeGrantConditionKind: '' }); this._syncGrantWhen();
  },
  _applyGrantWhen(grant, config) { return grantConditions.apply(grant, this._runtimeGrantWhenRows.map(row => row.condition), config, this._runtimeGrantConditionContext); },
  onRuntimeGrantConditionDraft(e) {
    if (!this._grantEditorLive(this._runtimeEditorEpoch) || !this._grantScopeEditable()) return false;
    const field = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.field, value = e && e.detail && e.detail.value;
    const draft = Object.assign({}, this.data.runtimeGrantConditionDraft), context = this._runtimeGrantConditionContext;
    if (field === 'value') { if (typeof value !== 'string' || value.length > 16) return false; draft.value = value; }
    else {
      const count = field === 'sourceIndex' ? grantConditions.sources(context).length : field === 'comparisonIndex' ? grantConditions.comparisons.length
        : field === 'itemIndex' ? this._grantConfig().items.length : field === 'nodeIndex' ? context.nodes.length : 0;
      if (!/^(0|[1-9][0-9]*)$/.test(String(value)) || Number(value) >= count) return false;
      if (field === 'sourceIndex' && draft.sourceIndex !== Number(value)) Object.assign(draft, { comparisonIndex: -1, itemIndex: -1, nodeIndex: -1, value: '' });
      draft[field] = Number(value);
    }
    const source = grantConditions.sources(context)[draft.sourceIndex];
    this.setData({ runtimeGrantConditionDraft: draft, runtimeGrantConditionKind: source ? source.kind : '' }); return true;
  },
  clearRuntimeGrantConditionDraft() {
    if (!this._grantEditorLive(this._runtimeEditorEpoch) || !this._grantScopeEditable()) return false;
    this.setData({ runtimeGrantConditionDraft: grantConditions.emptyDraft(), runtimeGrantConditionKind: '' }); return true;
  },
  saveRuntimeGrantCondition() {
    if (!this._grantEditorLive(this._runtimeEditorEpoch) || !this._grantScopeEditable()) return false;
    const draft = this.data.runtimeGrantConditionDraft, value = grantConditions.compose(draft, this._grantConfig(), this._runtimeGrantConditionContext);
    if (draft.editingId) {
      const existing = this._runtimeGrantWhenRows.find(row => row.id === draft.editingId);
      if (!existing) return false;
      const oldFields = Object.keys(existing.condition), newFields = Object.keys(value);
      existing.condition = oldFields.length === newFields.length && oldFields.every(key => newFields.includes(key)) ? Object.assign({}, existing.condition, value) : value;
    } else {
      if (this._runtimeGrantWhenRows.length >= 30) { toast('每条关联最多 30 项 AND 前提'); return false; }
      this._runtimeGrantWhenRows.push({ id: 'w' + (++this._runtimeGrantWhenSequence), condition: value });
>>>>>>> private-github/featu
    }
    this.clearRuntimeGrantConditionDraft(); this._syncGrantWhen(); return true;
  },
  editRuntimeGrantCondition(e) {
    if (!this._grantEditorLive(this._runtimeEditorEpoch) || !this._grantScopeEditable()) return false;
    if (this._grantConditionPending()) { toast('请先添加或取消正在输入的前提'); return false; }
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id, row = this._runtimeGrantWhenRows.find(row => row.id === id);
    if (!row) return false;
    const draft = grantConditions.draft(row.condition, id, this._grantConfig(), this._runtimeGrantConditionContext);
    this.setData({ runtimeGrantConditionDraft: draft, runtimeGrantConditionKind: grantConditions.sources(this._runtimeGrantConditionContext)[draft.sourceIndex].kind }); return true;
  },
  removeRuntimeGrantCondition(e) {
    if (!this._grantEditorLive(this._runtimeEditorEpoch) || !this._grantScopeEditable()) return false;
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id;
    if (!this._runtimeGrantWhenRows.some(row => row.id === id)) return false;
    this._runtimeGrantWhenRows = this._runtimeGrantWhenRows.filter(row => row.id !== id);
    if (this.data.runtimeGrantConditionDraft.editingId === id) this.clearRuntimeGrantConditionDraft();
    this._syncGrantWhen(); return true;
  },
  _choiceGrantsLive(epoch) {
    return this._runtimeMayEdit() && this.data.runtimeEditor.show && this.data.runtimeEditor.kind === 'choiceGrants'
      && !!this._runtimeWorking && epoch === this._runtimeEditorEpoch && this._runtimeChoiceScope === this._nodeGrantSnapshot();
  },
  onRuntimeChoiceGrantDraft(e) {
    if (!this._grantScopeEditable() || !this._choiceGrantsLive(this._runtimeEditorEpoch)) return false;
    const field = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.field, value = e && e.detail && e.detail.value;
    const draft = Object.assign({}, this.data.runtimeChoiceGrantDraft), node = this._runtimeChoiceNodes[draft.nodeIndex], step = node && node.steps[draft.stepIndex];
    if (field === 'quantity') { if (typeof value !== 'string' || value.length > 8) return false; draft.quantity = value; }
    else {
      const count = field === 'nodeIndex' ? this._runtimeChoiceNodes.length : field === 'stepIndex' ? (node ? node.steps.length : 0)
        : field === 'optionIndex' ? (step ? step.options.length : 0) : field === 'itemIndex' ? this._grantConfig().items.length : 0;
      if (!/^(0|[1-9][0-9]*)$/.test(String(value)) || Number(value) >= count) return false;
      if (field === 'nodeIndex' && draft.nodeIndex !== Number(value)) { draft.stepIndex = -1; draft.optionIndex = -1; }
      if (field === 'stepIndex' && draft.stepIndex !== Number(value)) draft.optionIndex = -1;
      draft[field] = Number(value);
    }
    this.setData({ runtimeChoiceGrantDraft: draft }); this._refreshRuntimeEditor(this._runtimeConfig()); return true;
  },
  clearRuntimeChoiceGrantDraft() {
    if (!this._grantScopeEditable() || !this._choiceGrantsLive(this._runtimeEditorEpoch)) return false;
    this._runtimeChoiceEdit = null; this._resetGrantWhen(); this.setData({ runtimeChoiceGrantDraft: choiceGrants.emptyDraft() }); this._refreshRuntimeEditor(this._runtimeConfig()); return true;
  },
  editRuntimeChoiceGrant(e) {
    if (!this._grantScopeEditable() || !this._choiceGrantsLive(this._runtimeEditorEpoch)) return false;
    if (JSON.stringify(this.data.runtimeChoiceGrantDraft) !== JSON.stringify(choiceGrants.emptyDraft()) || this._grantConditionPending() || this._runtimeGrantWhenRows.length) { toast('请先暂存或取消正在编辑的关联'); return false; }
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id, c = this._grantConfig();
    const row = (c.grants || []).find(grant => grant.id === id);
    if (!row || !choiceGrants.simple(row, c, this._runtimeChoiceNodes, this._runtimeGrantConditionContext)) { toast('含不支持的条件、扩展或未知引用的关联保持只读'); return false; }
    this._runtimeChoiceEdit = { id, snapshot: JSON.stringify(row) }; this._resetGrantWhen(row);
    const ref = choiceGrants.reference(row, this._runtimeChoiceNodes);
    this.setData({ runtimeChoiceGrantDraft: { editingId: id, nodeIndex: this._runtimeChoiceNodes.indexOf(ref.node), stepIndex: ref.node.steps.indexOf(ref.step), optionIndex: ref.step.options.indexOf(ref.option),
      itemIndex: c.items.findIndex(item => item.id === row.itemId), quantity: String(row.quantity) } }); this._refreshRuntimeEditor(this._runtimeConfig()); return true;
  },
  saveRuntimeChoiceGrant() {
    if (!this._grantScopeEditable() || !this._choiceGrantsLive(this._runtimeEditorEpoch)) return false;
    if (this._grantConditionPending()) { toast('请先添加或取消正在输入的前提'); return false; }
    const c = this._grantConfig(), draft = this.data.runtimeChoiceGrantDraft;
    choiceGrants.settings(c, this._runtimeChoiceNodes, this._runtimeGrantConditionContext);
    const node = this._runtimeChoiceNodes[draft.nodeIndex], step = node && node.steps[draft.stepIndex], option = step && step.options[draft.optionIndex], item = c.items[draft.itemIndex], quantity = Number(draft.quantity);
    if (!node || !step || !option || !item || !/^[a-z][a-z0-9_]{0,31}(?![\s\S])/.test(item.id || '') || !['ITEM','MEDAL'].includes(item.kind)
        || !/^[0-9]+$/.test(draft.quantity) || !Number.isInteger(quantity) || quantity < 1 || quantity > 99) {
      toast('请明确选择已保存站点、分支步骤和选项、现有物资，并填写 1 到 99 的整数'); return false;
    }
    const rules = initial.parse(this.data.formData.journeyRules);
    if (item.kind === 'ITEM' && item.stat && (rules.checkEnabled !== true || !['HP','LUCK'].includes(item.stat)
        || rules[item.stat === 'HP' ? 'hp' : 'luck'].enabled !== true)) { toast('这种物品需要主题已开启对应检定属性，请先核对原数值配置'); return false; }
    const list = c.grants || []; let id = draft.editingId;
    if (id) {
      const row = list.find(grant => grant.id === id), edit = this._runtimeChoiceEdit;
      if (!edit || edit.id !== id || !row || JSON.stringify(row) !== edit.snapshot || !choiceGrants.simple(row, c, this._runtimeChoiceNodes, this._runtimeGrantConditionContext)) { toast('这条关联已变化，请取消后重新编辑'); return false; }
      list[list.indexOf(row)] = this._applyGrantWhen(Object.assign({}, row, { nodeId: node.id, step: step.id, option: option.id, itemId: item.id, quantity }), c);
    } else {
      if (list.length >= 300) { toast('主题发放配置最多 300 项'); return false; }
      id = nextId('q', [...this._runtimeChoiceIds].map(value => ({ id: value }))); this._runtimeChoiceIds.add(id);
      list.push(this._applyGrantWhen({ id, source: 'CHOICE', nodeId: node.id, step: step.id, option: option.id, itemId: item.id, quantity }, c));
    }
    c.grants = list; this._saveGrantConfig(c); this.clearRuntimeChoiceGrantDraft(); this._refreshRuntimeEditor(this._runtimeConfig()); return true;
  },
  removeRuntimeChoiceGrant(e) {
    const epoch = this._runtimeEditorEpoch;
    if (!this._grantScopeEditable() || !this._choiceGrantsLive(epoch) || this._runtimeChoiceDelete) return false;
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id, c = this._grantConfig(), row = (c.grants || []).find(grant => grant.id === id);
    if (!row || !choiceGrants.simple(row, c, this._runtimeChoiceNodes, this._runtimeGrantConditionContext)) { toast('含不支持的条件、扩展或未知引用的关联保持只读'); return false; }
    const intent = { id, snapshot: JSON.stringify(row) }; this._runtimeChoiceDelete = intent;
    try {
      modal.show({ title: '移除分支选项物资关联？', content: '只从当前草稿移除这条关联，仍须通过原保存流程提交。已有局快照与个人库存不变，不撤销已获得的物资。', confirmText: '移除关联', success: result => {
        if (this._runtimeChoiceDelete !== intent) return;
        this._runtimeChoiceDelete = null;
        if (!result.confirm || !this._choiceGrantsLive(epoch)) return;
        const latest = this._grantConfig(), target = (latest.grants || []).find(grant => grant.id === id);
        if (!target || JSON.stringify(target) !== intent.snapshot || !choiceGrants.simple(target, latest, this._runtimeChoiceNodes, this._runtimeGrantConditionContext)) { toast('关联内容已变化，请重新确认'); return; }
        if (this.data.runtimeChoiceGrantDraft.editingId === id) { toast('请先暂存或取消这条关联的编辑'); return; }
        latest.grants = latest.grants.filter(grant => grant.id !== id); this._saveGrantConfig(latest); this._refreshRuntimeEditor(this._runtimeConfig());
      }, fail: () => { if (this._runtimeChoiceDelete === intent) this._runtimeChoiceDelete = null; } });
    } catch (error) { this._runtimeChoiceDelete = null; throw error; }
    return true;
  },
  _nodeGrantsLive(epoch) {
    return this._runtimeMayEdit() && this.data.runtimeEditor.show && this.data.runtimeEditor.kind === 'nodeGrants'
      && !!this._runtimeWorking && epoch === this._runtimeEditorEpoch && this._runtimeNodeGrantScope === this._nodeGrantSnapshot();
  },
  onRuntimeNodeGrantDraft(e) {
    if (!this._grantScopeEditable() || !this._nodeGrantsLive(this._runtimeEditorEpoch)) return false;
    const field = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.field, value = e && e.detail && e.detail.value;
    if (field === 'quantity') { if (typeof value !== 'string' || value.length > 8) return false; }
    else if (['nodeIndex', 'itemIndex'].includes(field)) {
      const count = field === 'nodeIndex' ? this._runtimeGrantNodes.length : this._grantConfig().items.length;
      if (!/^(0|[1-9][0-9]*)$/.test(String(value)) || Number(value) >= count) return false;
    } else return false;
    this.setData({ ['runtimeNodeGrantDraft.' + field]: field === 'quantity' ? value : Number(value) }); return true;
  },
  clearRuntimeNodeGrantDraft() {
    if (!this._grantScopeEditable() || !this._nodeGrantsLive(this._runtimeEditorEpoch)) return false;
    this._runtimeNodeGrantEdit = null; this._resetGrantWhen(); this.setData({ runtimeNodeGrantDraft: emptyNodeGrantDraft() }); return true;
  },
  editRuntimeNodeGrant(e) {
    if (!this._grantScopeEditable() || !this._nodeGrantsLive(this._runtimeEditorEpoch)) return false;
    if (JSON.stringify(this.data.runtimeNodeGrantDraft) !== JSON.stringify(emptyNodeGrantDraft()) || this._grantConditionPending() || this._runtimeGrantWhenRows.length) { toast('请先暂存或取消正在编辑的关联'); return false; }
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id, c = this._grantConfig();
    const row = (c.grants || []).find(grant => grant.id === id);
    if (!row || !simpleNodeGrant(row, c, this._runtimeGrantNodes, this._runtimeGrantConditionContext)) { toast('含不支持的条件、扩展或未知引用的关联保持只读'); return false; }
    this._runtimeNodeGrantEdit = { id, snapshot: JSON.stringify(row) }; this._resetGrantWhen(row);
    this.setData({ runtimeNodeGrantDraft: { editingId: id, nodeIndex: this._runtimeGrantNodes.findIndex(node => node.id === row.nodeId),
      itemIndex: c.items.findIndex(item => item.id === row.itemId), quantity: String(row.quantity) } }); return true;
  },
  saveRuntimeNodeGrant() {
    if (!this._grantScopeEditable() || !this._nodeGrantsLive(this._runtimeEditorEpoch)) return false;
    if (this._grantConditionPending()) { toast('请先添加或取消正在输入的前提'); return false; }
    const c = this._grantConfig(), draft = this.data.runtimeNodeGrantDraft;
    nodeGrantSettings(c, this._runtimeGrantNodes, this._runtimeGrantConditionContext);
    const node = this._runtimeGrantNodes[draft.nodeIndex], item = c.items[draft.itemIndex], quantity = Number(draft.quantity);
    if (!node || !item || !/^[a-z][a-z0-9_]{0,31}(?![\s\S])/.test(item.id || '') || !['ITEM','MEDAL'].includes(item.kind)
        || !/^[0-9]+$/.test(draft.quantity) || !Number.isInteger(quantity) || quantity < 1 || quantity > 99) {
      toast('请明确选择已保存站点、现有物资，并填写 1 到 99 的整数'); return false;
    }
    const rules = initial.parse(this.data.formData.journeyRules);
    if (item.kind === 'ITEM' && item.stat && (rules.checkEnabled !== true || !['HP','LUCK'].includes(item.stat)
        || rules[item.stat === 'HP' ? 'hp' : 'luck'].enabled !== true)) { toast('这种物品需要主题已开启对应检定属性，请先核对原数值配置'); return false; }
    const list = c.grants || []; let id = draft.editingId;
    if (id) {
      const row = list.find(grant => grant.id === id), edit = this._runtimeNodeGrantEdit;
      if (!edit || edit.id !== id || !row || JSON.stringify(row) !== edit.snapshot || !simpleNodeGrant(row, c, this._runtimeGrantNodes, this._runtimeGrantConditionContext)) { toast('这条关联已变化，请取消后重新编辑'); return false; }
      list[list.indexOf(row)] = this._applyGrantWhen(Object.assign({}, row, { nodeId: node.id, itemId: item.id, quantity }), c);
    } else {
      if (list.length >= 300) { toast('主题发放配置最多 300 项'); return false; }
      id = nextId('n', [...this._runtimeNodeGrantIds].map(value => ({ id: value }))); this._runtimeNodeGrantIds.add(id);
      list.push(this._applyGrantWhen({ id, source: 'NODE', nodeId: node.id, itemId: item.id, quantity }, c));
    }
    c.grants = list; this._saveGrantConfig(c); this.clearRuntimeNodeGrantDraft(); this._refreshRuntimeEditor(this._runtimeConfig()); return true;
  },
  removeRuntimeNodeGrant(e) {
    const epoch = this._runtimeEditorEpoch;
    if (!this._grantScopeEditable() || !this._nodeGrantsLive(epoch) || this._runtimeNodeGrantDelete) return false;
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id, c = this._grantConfig(), row = (c.grants || []).find(grant => grant.id === id);
    if (!row || !simpleNodeGrant(row, c, this._runtimeGrantNodes, this._runtimeGrantConditionContext)) { toast('含不支持的条件、扩展或未知引用的关联保持只读'); return false; }
    const intent = { id, snapshot: JSON.stringify(row) }; this._runtimeNodeGrantDelete = intent;
    try {
      modal.show({ title: '移除站点物资关联？', content: '只从当前草稿移除这条关联，仍须通过原保存流程提交。已有局快照与个人库存不变，不撤销已获得的物资。', confirmText: '移除关联', success: result => {
        if (this._runtimeNodeGrantDelete !== intent) return;
        this._runtimeNodeGrantDelete = null;
        if (!result.confirm || !this._nodeGrantsLive(epoch)) return;
        const latest = this._grantConfig(), target = (latest.grants || []).find(grant => grant.id === id);
        if (!target || JSON.stringify(target) !== intent.snapshot || !simpleNodeGrant(target, latest, this._runtimeGrantNodes, this._runtimeGrantConditionContext)) { toast('关联内容已变化，请重新确认'); return; }
        if (this.data.runtimeNodeGrantDraft.editingId === id) { toast('请先暂存或取消这条关联的编辑'); return; }
        latest.grants = latest.grants.filter(grant => grant.id !== id); this._saveGrantConfig(latest); this._refreshRuntimeEditor(this._runtimeConfig());
      }, fail: () => { if (this._runtimeNodeGrantDelete === intent) this._runtimeNodeGrantDelete = null; } });
    } catch (error) { this._runtimeNodeGrantDelete = null; throw error; }
    return true;
  },
  onRuntimeBroadcastDraft(e) {
    if (!this._broadcastEditorLive(this._runtimeEditorEpoch)) return false;
    const field = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.field;
    const value = e && e.detail && e.detail.value, draft = Object.assign({}, this.data.runtimeBroadcastDraft);
    if (field === 'content') { if (typeof value !== 'string') return false; broadcastText(value, false); draft.content = value; }
    else if (field === 'value') { if (typeof value !== 'string' || value.length > 16) return false; draft.value = value; }
    else if (['triggerIndex','comparisonIndex','itemIndex'].includes(field)) {
      if (!/^(0|[1-9][0-9]*)$/.test(String(value))) return false;
      const index = Number(value), c = this._runtimeConfig();
      const count = field === 'triggerIndex' ? broadcastTriggers(this._runtimeBroadcastKeys).length : field === 'comparisonIndex' ? BROADCAST_COMPARISONS.length : c.items.length;
      if (!Number.isSafeInteger(index) || index >= count) return false;
      if (field === 'triggerIndex' && draft.triggerIndex !== index) Object.assign(draft, { comparisonIndex: -1, itemIndex: -1, value: '' });
      draft[field] = index;
    } else return false;
    this.setData({ runtimeBroadcastDraft: draft }); return true;
  },
  clearRuntimeBroadcastDraft() {
    if (!this._broadcastEditorLive(this._runtimeEditorEpoch)) return false;
    this.setData({ runtimeBroadcastDraft: emptyBroadcastDraft() }); return true;
  },
  addRuntimeBroadcast() {
    if (!this._broadcastEditorLive(this._runtimeEditorEpoch)) return false;
    const c = this._runtimeConfig(), draft = this.data.runtimeBroadcastDraft;
    broadcastSettings(c);
    if ((c.events || []).length >= 100) { toast('最多 100 条主题广播事件'); return false; }
    broadcastText(draft.content, true);
    const trigger = broadcastTriggers(this._runtimeBroadcastKeys)[draft.triggerIndex];
    if (!trigger) { toast('请明确选择广播触发条件'); return false; }
    if (!/^-?[0-9]+$/.test(draft.value)) { toast('请输入完整的整数条件值'); return false; }
    const value = Number(draft.value);
    if (!Number.isSafeInteger(value) || (trigger.item ? value < 1 || value > 999 : Math.abs(value) > 999999)) { toast(trigger.item ? '数量须为 1 到 999 的整数' : '条件值须为 -999999 到 999999 的整数'); return false; }
    let condition;
    if (trigger.item) {
      const item = c.items[draft.itemIndex];
      if (!item || typeof item.id !== 'string' || !/^[a-z][a-z0-9_]{0,31}$/.test(item.id)) { toast('请选择当前目录中的物品或勋章'); return false; }
      condition = { op: trigger.key, itemId: item.id, quantity: value };
    } else {
      const comparison = BROADCAST_COMPARISONS[draft.comparisonIndex];
      if (!comparison) { toast('请明确选择比较方式'); return false; }
      condition = { op: comparison.op, var: trigger.key, value };
    }
    if (pendingBroadcastEffect(draft)) { toast('请先添加这条属性后果，或清空后果输入'); return false; }
    if (draft.effects.length > 20 || draft.effects.some(effect => !this._runtimeBroadcastKeys.some(key => key.key === effect.var)
        || !BROADCAST_EFFECT_OPS.some(op => op.op === effect.op) || !Number.isInteger(effect.value) || Math.abs(effect.value) > 999)) {
      toast('属性后果须引用当前初始声明，并明确填写 SET/INC 和 -999 到 999 的整数'); return false;
    }
    const list = c.events || [];
    // Do not recycle an ID removed earlier in this editor, even if the clock has not advanced.
    const id = nextId('b', [...this._runtimeBroadcastIds].map(value => ({ id: value })));
    this._runtimeBroadcastIds.add(id);
    const row = { id, content: draft.content, when: [condition] };
    if (draft.effects.length) row.effects = draft.effects.map(effect => ({ var: effect.var, op: effect.op, value: effect.value }));
    list.push(row); c.events = list; this._runtimeBroadcastCreated.add(id);
    this._saveRuntime(c); this.setData({ runtimeBroadcastDraft: emptyBroadcastDraft() }); this._refreshRuntimeEditor(c); return true;
  },
  onRuntimeBroadcastEffectDraft(e) {
    if (!this._broadcastEditorLive(this._runtimeEditorEpoch)) return false;
    const field = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.field, value = e && e.detail && e.detail.value;
    if (field === 'effectValue') {
      if (typeof value !== 'string' || value.length > 16) return false;
    } else if (['effectStateIndex', 'effectOpIndex'].includes(field)) {
      const count = field === 'effectStateIndex' ? this._runtimeBroadcastKeys.length : BROADCAST_EFFECT_OPS.length;
      if (!/^(0|[1-9][0-9]*)$/.test(String(value)) || Number(value) >= count) return false;
    } else return false;
    this.setData({ ['runtimeBroadcastDraft.' + field]: field === 'effectValue' ? value : Number(value) }); return true;
  },
  clearRuntimeBroadcastEffectDraft() {
    if (!this._broadcastEditorLive(this._runtimeEditorEpoch)) return false;
    this.setData({ 'runtimeBroadcastDraft.effectStateIndex': -1, 'runtimeBroadcastDraft.effectOpIndex': -1, 'runtimeBroadcastDraft.effectValue': '' }); return true;
  },
  addRuntimeBroadcastEffect() {
    if (!this._broadcastEditorLive(this._runtimeEditorEpoch)) return false;
    const draft = this.data.runtimeBroadcastDraft, key = this._runtimeBroadcastKeys[draft.effectStateIndex], op = BROADCAST_EFFECT_OPS[draft.effectOpIndex];
    if (!key || !op || !/^-?[0-9]+$/.test(draft.effectValue) || !Number.isInteger(Number(draft.effectValue)) || Math.abs(Number(draft.effectValue)) > 999) {
      toast('请明确选择已声明属性、SET/INC，并填写 -999 到 999 的整数'); return false;
    }
    if (draft.effects.length >= 20) { toast('每条事件最多 20 条属性后果'); return false; }
    const id = 'effect_' + (this._runtimeBroadcastEffectSeq = (this._runtimeBroadcastEffectSeq || 0) + 1);
    this.setData({ 'runtimeBroadcastDraft.effects': draft.effects.concat({ id, var: key.key, label: key.label, op: op.op, value: Number(draft.effectValue) }) });
    this.clearRuntimeBroadcastEffectDraft(); return true;
  },
  removeRuntimeBroadcastEffect(e) {
    if (!this._broadcastEditorLive(this._runtimeEditorEpoch)) return false;
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id, effects = this.data.runtimeBroadcastDraft.effects;
    if (!effects.some(effect => effect.id === id)) return false;
    this.setData({ 'runtimeBroadcastDraft.effects': effects.filter(effect => effect.id !== id) }); return true;
  },
  onRuntimeBroadcastContent(e) {
    if (!this._broadcastEditorLive(this._runtimeEditorEpoch)) return false;
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id, value = e && e.detail && e.detail.value;
    if (typeof value !== 'string') return false;
    broadcastText(value, false);
    const c = this._runtimeConfig(), row = (c.events || []).find(r => r.id === id);
    if (!row || row.content === value) return false;
    row.content = value; this._saveRuntime(c); this._refreshRuntimeEditor(c); return true;
  },
  removeRuntimeBroadcast(e) {
    const epoch = this._runtimeEditorEpoch;
    if (!this._broadcastEditorLive(epoch) || this._runtimeBroadcastDelete) return false;
    const id = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id, c = this._runtimeConfig();
    const view = broadcastSettings(c, false, this._runtimeBroadcastKeys, this._runtimeBroadcastCreated).events.find(r => r.id === id), row = (c.events || []).find(r => r.id === id);
    if (!view || !view.canRemove) { toast('含现有复杂条件、后果或保留字段的事件只能编辑文字'); return false; }
    const intent = { id, snapshot: JSON.stringify(row) }; this._runtimeBroadcastDelete = intent;
    try {
      modal.show({ title: '删除这条广播？', content: '仅从当前草稿移除这条广播及其条件；若为本次新建事件，也移除为它明确添加的属性后果。场景独立事件和已开局快照不变，仍需按原流程保存与发布。', confirmText: '删除广播',
        success: result => {
          if (this._runtimeBroadcastDelete !== intent) return;
          this._runtimeBroadcastDelete = null;
          if (!result.confirm || !this._broadcastEditorLive(epoch)) return;
          const latest = this._runtimeConfig(), target = (latest.events || []).find(r => r.id === id);
          const current = broadcastSettings(latest, false, this._runtimeBroadcastKeys, this._runtimeBroadcastCreated).events.find(r => r.id === id);
          if (!target || !current.canRemove || JSON.stringify(target) !== intent.snapshot) { toast('广播内容已变化，请重新确认'); return; }
          latest.events = latest.events.filter(r => r.id !== id); this._saveRuntime(latest); this._refreshRuntimeEditor(latest);
        }, fail: () => { if (this._runtimeBroadcastDelete === intent) this._runtimeBroadcastDelete = null; } });
    } catch (error) { this._runtimeBroadcastDelete = null; throw error; }
    return true;
  },
  onRuntimeRewardOpen(e) { this.setData({ runtimeRewardOpen: !!e.detail.open }); },
  _syncRuntimeStats() {
    const r = initial.parse(this.data.formData.journeyRules);
    const c = this._runtimeConfig();
    const survival = c.gameplayRuntime && c.gameplayRuntime.survival || {};
    const challenge = survival.zeroChallenge || {};
    const alternatives = Array.isArray(challenge.alternativeNodeIds) ? challenge.alternativeNodeIds : [];
    const nodes = this._runtimeSavedNodes();
    const policies = this._zeroHpPolicyKeys(c);
    const policyLabels = { THEME_FAIL: '主题失败', WAIT: '等待一段时间', STEPS: '走够步数', BLOCK_HP_COST: '仅禁止扣该值的任务', BLOCK_PAID: '禁止所有收费动作', CHALLENGE: '归零挑战', LEGACY_END: '旧版结束' };
    const choices = supportMatrix.challengeChoices(this.data.supportMatrix, nodes, challenge.version);
    const primary = choices.primary;
    const primaryNode = nodes.find(node => node.id === challenge.challengeNodeId);
    const sensor = this.data.supportMatrix && primaryNode && supportMatrix.mains(this.data.supportMatrix, supportMatrix.config(primaryNode.template))
      .some(row => row.tools.zeroChallenge === 'CHALLENGE_ONLY' && row.key !== 'typeIn');
    const hpName = String(c.hpName || '').trim(), luckName = String(c.luckName || '').trim(), actionName = String(c.actionName || '').trim();
    const options = purposeOptions(c, r);
    this.setData({ runtimeHpEnabled: !!r.hp.enabled, runtimeLuckEnabled: !!r.luck.enabled,
      runtimeHpMax: r.hp.max, runtimeLuckMax: r.luck.max,
      runtimeHpName: hpName, runtimeLuckName: luckName, runtimeActionName: actionName,
      runtimeHpLabel: hpName || '生命值', runtimeLuckLabel: luckName || '幸运值', runtimeActionLabel: actionName || '行动次数',
      runtimeHpInitialLabel: hpName || '精力', runtimeLuckInitialLabel: luckName || '幸运',
      initialStateOptions: initial.OPTIONS.map(row => Object.assign({}, row, { label: row.type === 'hp' ? hpName || row.label : luckName || row.label })),
      runtimePurposes: options.map(row => row.label), runtimePurposeKeys: options.map(row => row.key),
      runtimeValues: (r.attributes || []).filter(row => values.COUNTER.test(row.key || '')).map(row => Object.assign({}, row, { invalidInitial: row.initial === '' || row.initial == null || !Number.isInteger(Number(row.initial)) || Number(row.initial) < Number(row.min) || Number(row.initial) > Number(row.max) })),
      runtimeActionEnabled: Number.isInteger(c.actionLimit) && c.actionLimit > 0, runtimeActionLimit: c.actionLimit || '',
      runtimeZeroHpOptions: zeroHp.options(policies),
      runtimeZeroHpSummary: zeroHp.summary(c),
      runtimeZeroHpPolicies: policies.map(policy => policyLabels[policy] || policy), runtimeZeroHpPolicyKeys: policies,
      runtimeZeroHpPolicyIndex: policies.indexOf(survival.zeroHpPolicy || 'BLOCK_HP_COST'),
      runtimeChallengeNodes: primary, runtimeChallengeNodeIndex: primary.findIndex(node => node.id === challenge.challengeNodeId),
      runtimeChallengeError: supportMatrix.challengeError(this.data.supportMatrix, nodes, survival.zeroHpPolicy === 'CHALLENGE' ? challenge : null),
      runtimeChallengeHint: sensor ? this.data.supportMatrix.constraints.CHALLENGE_ONLY : '',
      runtimeAlternativeNodes: primary.concat(nodes.filter(node => alternatives.includes(node.id) && !primary.some(row => row.id === node.id)))
        .filter(node => node.id !== challenge.challengeNodeId).map(node => Object.assign({}, node, { selected: alternatives.includes(node.id),
          reason: this.data.supportMatrix ? supportMatrix.validate(this.data.supportMatrix, node.template, Object.assign({}, node.context, { challenge: true })) : '' })),
      runtimeZeroChallenge: { restoreTo: challenge.restoreTo == null ? 1 : challenge.restoreTo, limit: challenge.limit == null ? 3 : challenge.limit },
    });
    if (this._refreshSurvivalSummary) this._refreshSurvivalSummary(c); // survival
    if (this._refreshSupportMatrix) this._refreshSupportMatrix();
    if (this._refreshStructureSummary) this._refreshStructureSummary(); // structure
    if (this._refreshThemeOwnership) this._refreshThemeOwnership();
  },
  _runtimeSavedNodes() {
    if (!this.data.editingTopicId) return [];
    const nodes = [];
    (this.data.formData.chapters || []).forEach(ch => (ch.nodes || []).forEach(node => {
      const id = Number(node.id);
      if (Number.isSafeInteger(id) && id > 0 && !nodes.some(row => row.id === id)) {
        const template = (this._templateDetailsById || {})[node.templateId] || node.templateInfo || node;
        let game = {};
        try { const raw = template.advancedConfigJson || node.advancedConfigJson || '{}'; game = (typeof raw === 'string' ? JSON.parse(raw) : raw) || {}; } catch (_) {}
        const enabled = key => game[key] && game[key].enabled === true;
        nodes.push({ id, name: node.name || ('站点 ' + id), template: Object.assign({}, template, { advancedConfigJson: game }), context: supportMatrix.context(this.data.formData.themeRuntimeConfig, node), physical: ['ballShake', 'shout', 'steps', 'compass', 'quietHold'].some(enabled), question: ['qa', 'sort', 'match', 'classify'].some(enabled) });
      }
    }));
    return nodes;
  },
  onRuntimeName(e) {
    const field = e.currentTarget.dataset.field;
    if (!['hpName', 'luckName', 'actionName'].includes(field)) return;
    const value = String(e.detail.value || '').slice(0, 20);
    const c = this._runtimeConfig(); c[field] = value;
    this._saveRuntime(c); this._syncRuntimeStats();
  },
  editRuntimeStat(e) {
    const stat = e.currentTarget.dataset.stat;
    if (!['hp', 'luck', 'action'].includes(stat)) return;
    this.setData({ runtimeEditingStat: this.data.runtimeEditingStat === stat ? '' : stat });
  },
  onRuntimeActionToggle(e) {
    const c = this._runtimeConfig();
    if (e.detail.value) c.actionLimit = 20; else delete c.actionLimit;
    this._saveRuntime(c); this._syncRuntimeStats();
  },
  onRuntimeActionLimit(e) {
    const c = this._runtimeConfig(); c.actionLimit = Number(e.detail.value);
    this._saveRuntime(c); this._syncRuntimeStats();
  },
  openValueEditor(e) {
    const r = initial.parse(this.data.formData.journeyRules);
    const attributes = (r.attributes || []).filter(row => values.COUNTER.test(row.key || ''));
    const index = e && e.currentTarget && e.currentTarget.dataset.index != null ? Number(e.currentTarget.dataset.index) : -1;
    const row = index < 0 ? { key: values.nextKey(r.attributes), label: '', min: 0, max: 100, initial: 0, visible: true }
      : copy(attributes[index]);
    if (!row) return;
    this.setData({ valueEditor: { show: true, index, row, links: copy((r.statLinks || []).filter(link => link.sourceKey === row.key)), error: '' } });
  },
  closeValueEditor() { this.setData({ 'valueEditor.show': false }); },
  onValueField(e) {
    const field = e.currentTarget.dataset.field;
    if (!['label', 'min', 'max', 'visible'].includes(field)) return;
    this.setData({ ['valueEditor.row.' + field]: field === 'visible' ? !!e.detail.value : e.detail.value, 'valueEditor.error': '' });
  },
  addValueLink() {
    if (!initial.parse(this.data.formData.journeyRules).hp.enabled) { this.setData({ 'valueEditor.error': '请先启用' + this.data.runtimeHpLabel }); return; }
    const editor = this.data.valueEditor;
    const existing = (initial.parse(this.data.formData.journeyRules).statLinks || []).concat(editor.links);
    let suffix = 1;
    while (existing.some(link => link.id === 'link_' + suffix)) suffix++;
    const links = editor.links.concat({ id: 'link_' + suffix, sourceKey: editor.row.key, targetKey: 'sys.hp', trigger: 'PER_POINT', allowedReasons: ['TASK_COST'], frequency: 'EVENT', multiplier: 1 });
    this.setData({ 'valueEditor.links': links });
  },
  removeValueLink(e) {
    const links = copy(this.data.valueEditor.links);
    links.splice(Number(e.currentTarget.dataset.index), 1);
    this.setData({ 'valueEditor.links': links });
  },
  onValueLink(e) {
    const index = Number(e.currentTarget.dataset.index), field = e.currentTarget.dataset.field;
    const links = copy(this.data.valueEditor.links), link = links[index];
    if (!link) return;
    const option = Number(e.detail.value);
    if (field === 'trigger') {
      link.trigger = values.TRIGGERS[option];
      delete link.amount; delete link.multiplier; delete link.threshold;
      if (link.trigger === 'PER_POINT') link.multiplier = 1; else link.amount = 1;
      if (link.trigger === 'FIRST_THRESHOLD') { link.frequency = 'RUN'; link.threshold = Number(this.data.valueEditor.row.min); }
    } else if (field === 'frequency') link.frequency = link.trigger === 'FIRST_THRESHOLD' || option ? 'RUN' : 'EVENT';
    else if (field === 'reason') link.allowedReasons = [values.REASONS[option]];
    else if (['amount', 'multiplier', 'threshold'].includes(field)) link[field] = e.detail.value;
    this.setData({ 'valueEditor.links': links, 'valueEditor.error': '' });
  },
  saveValueEditor() {
    const editor = this.data.valueEditor, r = initial.parse(this.data.formData.journeyRules);
    const attributes = (r.attributes || []).slice();
    const current = attributes.findIndex(row => row.key === editor.row.key);
    try {
      const min = Number(editor.row.min), row = values.attribute(Object.assign({}, editor.row, { initial: min }));
      row.initial = current < 0 ? editor.row.initial : attributes[current].initial;
      if (current < 0) attributes.push(row); else attributes[current] = row;
      if (attributes.length > 32) throw new Error('初始属性最多配置 32 项');
      const links = (r.statLinks || []).filter(link => link.sourceKey !== row.key).concat(editor.links);
      r.statLinks = values.links(links, attributes, r.hp.enabled, this.data.runtimeHpLabel);
      r.attributes = attributes; r.stateEnabled = true;
      this.setData({ 'formData.journeyRules': initial.preserveChanges(this.data.formData.journeyRules, r), 'valueEditor.show': false, runtimeValueError: '' });
      this._syncRuntimeStats();
    } catch (error) { this.setData({ 'valueEditor.error': error.message }); }
  },
  removeValue(e) {
    const r = initial.parse(this.data.formData.journeyRules), c = this._runtimeConfig();
    const key = e && e.currentTarget && e.currentTarget.dataset.key || this.data.valueEditor.row.key;
    const refs = values.references(key, this.data.formData, c, r);
    if (refs.length) {
      const error = '请先移除引用：' + refs.join('、');
      if (this.data.valueEditor.show) this.setData({ 'valueEditor.error': error });
      else { this.setData({ runtimeValueError: error }); toast('请先移除引用，位置已列在值域中'); }
      this._syncRuntimeStats();
      return;
    }
    r.attributes = (r.attributes || []).filter(row => row.key !== key);
    r.stateEnabled = r.hp.enabled || r.luck.enabled || r.attributes.length > 0;
    this.setData({ 'formData.journeyRules': initial.preserveChanges(this.data.formData.journeyRules, r), 'valueEditor.show': false, runtimeValueError: '' });
    this._syncRuntimeStats();
  },
  onValueEnabled(e) { if (!e.detail.value) this.removeValue(e); },
  /** 生命/幸运上限归主题数值域；开局值超出时在发布前检查里拦截，不在这里改开局值。 */
  onRuntimeStatMax(e) {
    const r = initial.parse(this.data.formData.journeyRules), stat = e.currentTarget.dataset.stat, max = Number(e.detail.value);
    if (!['hp', 'luck'].includes(stat) || !Number.isInteger(max)) return;
    r[stat].max = max;
    this.setData({ 'formData.journeyRules': initial.preserveChanges(this.data.formData.journeyRules, r),
      runtimeValueError: Number(r[stat].init) > max ? '第一章开局值超过上限，请调整初始状态' : '' });
    this._syncRuntimeStats();
  },
  onRuntimeStatToggle(e) {
    const r = initial.parse(this.data.formData.journeyRules);
    const stat = e.currentTarget.dataset.stat;
    if (!e.detail.value) {
      const refs = values.references(stat === 'hp' ? 'sys.hp' : 'sys.luck', this.data.formData, this._runtimeConfig(), r);
      if (refs.length) { this.setData({ runtimeValueError: '请先移除引用：' + refs.join('、') }); toast('请先移除引用，位置已列在值域中'); this._syncRuntimeStats(); return; }
    }
    r[stat].enabled = !!e.detail.value;
    r.stateEnabled = r.hp.enabled || r.luck.enabled || (r.attributes || []).length > 0;
    if (r.hp.enabled || r.luck.enabled) r.checkEnabled = true;
    try { this.setData({ 'formData.journeyRules': initial.serializeChanges(this.data.formData.journeyRules, r) }); this._saveRuntime(this._runtimeConfig()); this._syncRuntimeStats(); }
    catch (error) { toast(error.message); }
  },
  addRuntimeContent(e) {
    if (e.currentTarget.dataset.kind === 'MEDAL' && !this.data.medalEditable) { toast('当前账号无法添加勋章'); return; }
    const c = this._runtimeConfig();
    if (c.items.length >= 200) { toast('物品与勋章最多 200 项'); return; }
    c.items.push({ id: nextId('i', c.items), kind: e.currentTarget.dataset.kind, name: '', image: '' });
    this._saveRuntime(c); this._refreshRuntimeEditor(c);
  },
  onRuntimePolicy(e) {
    const c = this._runtimeConfig(), f = e.currentTarget.dataset.field;
    c[f] = f === 'capacity' ? Number(e.detail.value) : !!e.detail.value;
    this._saveRuntime(c); this._refreshRuntimeEditor(c);
  },
  onRuntimeItem(e) {
    const c = this._runtimeConfig(), d = e.currentTarget.dataset, item = c.items[Number(d.index)];
    if (!item) return;
    const v = e.detail.value;
    if (item.kind === 'MEDAL' && !this.data.medalEditable && !['timing', 'quantity'].includes(d.field)) return;
    if (d.field === 'purpose') {
      delete item.stat; delete item.statKey; delete item.delta; delete item.wear; delete item.mode;
      const key = this.data.runtimePurposeKeys[Number(v)];
      if (key) { if (key.startsWith('counter.')) item.statKey = key; else item.stat = key; item.delta = 1; item.wear = false; }
    } else if (d.field === 'effect') {
      if (item.statKey && Number(v) === 3) { toast('自定义值不能用于减耗'); return; }
      item.wear = Number(v) > 0; delete item.mode;
      if (item.wear) item.mode = ['', 'ONCE', 'HELD', 'REDUCE'][Number(v)];
    } else if (d.field === 'timing' || d.field === 'quantity') {
      const ch = chapter(c, this.data.runtimeEditor.index);
      if (!ch.grants) ch.grants = [];
      let g = ch.grants.find(x => x.itemId === item.id);
      if (!g) { g = { itemId: item.id, quantity: 1, timing: 'START' }; ch.grants.push(g); }
      if (d.field === 'quantity') g.quantity = Number(v);
      else if (Number(v)) g.timing = ['', 'START', 'END'][Number(v)];
      else ch.grants = ch.grants.filter(x => x !== g);
    } else if (d.field === 'slotCost') {
      if (item.kind !== 'ITEM') return;
      item.slotCost = Number(v);
    } else item[d.field] = v;
    this._saveRuntime(c); this._refreshRuntimeEditor(c);
  },
  uploadRuntimeImage(e) {
    const item = this._runtimeConfig().items[Number(e.currentTarget.dataset.index)];
    if (!item || (item.kind === 'MEDAL' && !this.data.medalEditable)) return;
    const id = item.id, epoch = this._runtimeEditorEpoch;
    app.chooseImage(res => {
      if (!this._runtimeMayEdit() || !this.data.runtimeEditor.show || epoch !== this._runtimeEditorEpoch) return;
      const c = this._runtimeConfig(), current = c.items.find(row => row.id === id);
      if (!current || (current.kind === 'MEDAL' && !this.data.medalEditable) || !res || !res[0]) return;
      current.image = res[0]; this._saveRuntime(c); this._refreshRuntimeEditor(c);
    }, 1, { crop: true, cropScale: '1:1' });
  },
  removeRuntimeItem(e) {
    const c = this._runtimeConfig(), index = Number(e.currentTarget.dataset.index), item = c.items[index];
    if (!item || (item.kind === 'MEDAL' && !this.data.medalEditable)) return;
    const referenced = referencesItem(c, item.id);
    if (referenced) { toast('请先移除这个物品的发放和奖励配置'); return; }
    c.items.splice(index, 1); this._saveRuntime(c); this._refreshRuntimeEditor(c);
  },
  addRuntimeEgg() {
    const c = this._runtimeConfig(), eggs = chapter(c, this.data.runtimeEditor.index).eggs;
    if (eggs.length >= 100) { toast('每章最多 100 个彩蛋'); return; }
    eggs.push({ id: nextId('e', eggs), name: '', trigger: 'TIME', threshold: 10, content: '', rewards: [] });
    this._saveRuntime(c); this._refreshRuntimeEditor(c);
  },
  onRuntimeEgg(e) {
    const c = this._runtimeConfig(), d = e.currentTarget.dataset, egg = chapter(c, this.data.runtimeEditor.index).eggs[Number(d.index)];
    egg[d.field] = d.field === 'trigger' ? (Number(e.detail.value) ? 'STEPS' : 'TIME') : d.field === 'threshold' ? Number(e.detail.value) : e.detail.value;
    this._saveRuntime(c); this._refreshRuntimeEditor(c);
  },
  removeRuntimeEgg(e) { const c = this._runtimeConfig(); chapter(c, this.data.runtimeEditor.index).eggs.splice(Number(e.currentTarget.dataset.index), 1); this._saveRuntime(c); this._refreshRuntimeEditor(c); },
  addRuntimeEggReward(e) { const c = this._runtimeConfig(), egg = chapter(c, this.data.runtimeEditor.index).eggs[Number(e.currentTarget.dataset.index)]; if (!egg) return; if (egg.rewards.length >= 20) { toast('每个彩蛋最多 20 项奖励'); return; } egg.rewards.push({ itemId: '', quantity: 1 }); this._saveRuntime(c); this._refreshRuntimeEditor(c); },
  onRuntimeEggReward(e) {
    const c = this._runtimeConfig(), d = e.currentTarget.dataset, egg = chapter(c, this.data.runtimeEditor.index).eggs[Number(d.index)], r = egg.rewards[Number(d.reward)];
    if (!egg || !r) return;
    if (d.field === 'itemId') { const item = c.items[Number(e.detail.value)]; if (!item) return; r.itemId = item.id; } else r.quantity = Number(e.detail.value);
    this._saveRuntime(c); this._refreshRuntimeEditor(c);
  },
  removeRuntimeEggReward(e) { const c = this._runtimeConfig(), d = e.currentTarget.dataset; chapter(c, this.data.runtimeEditor.index).eggs[Number(d.index)].rewards.splice(Number(d.reward), 1); this._saveRuntime(c); this._refreshRuntimeEditor(c); },
  onRuntimeChapterCost(e) { const c = this._runtimeConfig(); chapter(c, this._runtimeChapterIndex())[e.currentTarget.dataset.field] = Number(e.detail.value); this._saveRuntime(c); this.setData({ runtimeChapterCosts: chapter(c, this._runtimeChapterIndex()) }); },
  onRuntimeCompletionItem(e) {
    const c = this._runtimeConfig(), item = c.items[Number(e.detail.value)]; if (!item) return; const id = item.id;
    c.grants = c.grants.filter(g => g.id !== 'completion_material');
    c.grants.push({ id: 'completion_material', source: 'ENDING', itemId: id, quantity: 1 });
    this._saveRuntime(c); this.setData({ runtimeCompletionIndex: Number(e.detail.value) });
  },
  clearRuntimeCompletionItem() { const c = this._runtimeConfig(); c.grants = c.grants.filter(g => g.id !== 'completion_material'); this._saveRuntime(c); this.setData({ runtimeCompletionIndex: -1 }); },
<<<<<<< public
  _zeroHpPolicyKeys(c) {
    const nodes = (this.data.formData.chapters || []).flatMap(chapter => chapter.nodes || []);
    return supportMatrix.zeroHpPolicies(this.data.supportMatrix, c, nodes);
  },
  _validateRuntimeConfig() {
    const endingIssue = this._nodePlanEndingIssues && this._nodePlanEndingIssues()[0];
    if (endingIssue) return endingIssue.label;
    const c = this._runtimeConfig();
    if (c.actionLimit != null && (!Number.isInteger(c.actionLimit) || c.actionLimit < 1 || c.actionLimit > 99999)) return '行动次数上限须为 1 至 99999 的整数';
    const ranges = initial.parse(this.data.formData.journeyRules);
    for (const stat of ['hp', 'luck']) if (ranges[stat].enabled && Number(ranges[stat].init) > Number(ranges[stat].max))
      return '第一章开局' + (stat === 'hp' ? (c.hpName || '生命值') : (c.luckName || '幸运值')) + '超过主题上限，请调整初始状态';
    c.chapters = c.chapters.filter(ch => ch.index < this.data.formData.chapters.length);
    const issue = this.data.formData.themeRuntimeConfig ? validate(c, initial.parse(this.data.formData.journeyRules), this._zeroHpPolicyKeys(c)) : '';
    if (issue) return issue;
    for (const row of c.chapters) {
      const chapterIssue = this._chapterRuleIssue(row.index, row);
      if (chapterIssue) return chapterIssue;
    }
    try { initial.serialize(this.data.formData.journeyRules, c); } catch (error) { return error.message; }
    const survival = c.gameplayRuntime && c.gameplayRuntime.survival;
    if (survival && survival.zeroHpPolicy === 'CHALLENGE') {
      const nodes = this._runtimeSavedNodes(), challenge = survival.zeroChallenge;
      const ids = [challenge.challengeNodeId].concat(challenge.alternativeNodeIds);
      if (ids.some(id => !nodes.some(node => node.id === id))) return '请先保存主题和挑战站点，再选择真实站点';
      const matrixError = supportMatrix.challengeError(this.data.supportMatrix, nodes, challenge);
      if (matrixError) return matrixError;
      if (!this.data.supportMatrix && nodes.find(node => node.id === challenge.challengeNodeId).physical && !challenge.alternativeNodeIds.some(id => nodes.find(node => node.id === id).question)) return '体能或传感器主挑战须配题目类备选挑战';
    }
    return '';
  },
};
module.exports = { data, methods, config, validate, rows, purposeOptions };
=======
  _validateRuntimeConfig() {
    if (!this._runtimeMayEdit() || !this.data.formData.themeRuntimeConfig) return '';
    try { return validate(config(this._runtimePayload()), initial.parse(this.data.formData.journeyRules)); }
    catch (error) { return error.message; }
  },
  _runtimeMayEdit() { return this.data.runtimeEnabled === true && (this._runtimeCapabilityOwner == null || (app.getUserID && app.getUserID() === this._runtimeCapabilityOwner)) && this.data.editScope !== 'WHITELIST' && !(this.data.editingTopicId && (!this.data.editLoaded || this.data.editLoading || this.data.editLoadError)) && !this._runtimeDisposed; },
  _loadRuntimeCapability() {
    const member = app.getUserID && app.getUserID();
    const epoch = this._runtimeCapabilityEpoch = (this._runtimeCapabilityEpoch || 0) + 1;
    const finish = enabled => {
      if (this._runtimeDisposed || epoch !== this._runtimeCapabilityEpoch || !app.getUserID || app.getUserID() !== member) return;
      if (!enabled && this.data.runtimeEditor.show) this._dismissRuntimeEditor();
      if (!enabled) this._runtimeChapterWorking = null;
      this._runtimeCapabilityOwner = member;
      this.setData({ runtimeEnabled: enabled, runtimeCapabilityLoaded: true });
    };
    if (!member) { finish(false); return; }
    if (this._runtimeCapabilityOwner !== member) {
      if (this.data.runtimeEditor.show) this._dismissRuntimeEditor();
      this._runtimeChapterWorking = null; this.setData({ runtimeEnabled: false });
    }
    app.sendRequest({ url: '/api/play/theme/capabilities', method: 'GET', hideLoading: true,
      success: res => finish(!!(res && Number(res.code) === 200 && res.data && res.data.enabled === true)),
      fail: () => finish(false) });
  },
  _disposeRuntimeEditor() { this._runtimeDisposed = true; this._runtimeCapabilityEpoch = (this._runtimeCapabilityEpoch || 0) + 1; this._runtimeEditorEpoch = (this._runtimeEditorEpoch || 0) + 1; this._runtimeWorking = null; this._runtimeChapterWorking = null; },
  _beginRuntimeChapter(index) {
    this._runtimeChapterWorking = null; this._runtimeChapterDirty = false;
    if (!this._runtimeMayEdit()) return;
    try { this._runtimeChapterWorking = config(this.data.formData.themeRuntimeConfig); this._syncRuntimeStats(); this.setData({ runtimeChapterCosts: chapter(this._runtimeChapterWorking, index) }); }
    catch (error) { toast(error.message); }
  },
  _finishRuntimeChapter(save) {
    const working = this._runtimeChapterWorking; this._runtimeChapterWorking = null;
    if (save && working && this._runtimeChapterDirty && this._runtimeMayEdit()) this.setData({ 'formData.themeRuntimeConfig': JSON.stringify(working) });
    if (this.data.runtimeEditor.show) this._dismissRuntimeEditor();
  },
  clearThemeRuntime() {
    modal.show({ title: '清空主题运行时配置', content: '将清空当前草稿的物品、章节发放、彩蛋、场景、事件和公开结果等主题运行时配置。修改仍需按原流程保存与发布；已开始或已结束的局保留各自快照。', confirmText: '清空',
      success: result => { if (result.confirm && this._runtimeMayEdit()) this.setData({ 'formData.themeRuntimeConfig': '' }); } });
  },
  onRuntimeCouponChange(e) { this.setData({ 'runtimeRewards.couponId': Number(e.detail.couponId || 0), 'runtimeRewards.couponName': e.detail.couponName || '' }); },
  onRuntimeMedalName(e) { if (this.data.medalEditable) this.setData({ 'runtimeRewards.medalName': e.detail.value }); },
  uploadRuntimeMedal() {
    if (!this.data.medalEditable) return;
    const epoch = this._runtimeEditorEpoch;
    app.chooseImage(res => { if (this._runtimeMayEdit() && this.data.medalEditable && this.data.runtimeEditor.show && epoch === this._runtimeEditorEpoch && res && res[0]) this.setData({ 'runtimeRewards.medalImage': res[0] }); }, 1, { crop: true, cropScale: '1:1' });
  },
  backRuntimeLayer() {
    if (this.data.runtimeRewardOpen) { const selector = this.selectComponent && this.selectComponent(this.data.runtimeEditor.kind === 'bingo-cell' ? '#runtime-bingo-reward-selector' : '#runtime-reward-selector'); if (selector && selector.backSelector) selector.backSelector(); return; }
    if (this.data.runtimeEditor.kind === 'bingo-cell') { this.backRuntimeBingo(); return; }
    this.cancelRuntimeEditor();
  },
};
// Every UI mutation enforces capability and the existing author edit window again.
const guarded = ['_syncRuntimeStats','_openRuntime','_prependRuntimeChapter','_removeRuntimeChapter','closeRuntimeEditor','onRuntimeStatToggle','addRuntimeContent','onRuntimePolicy','onRuntimeItem','uploadRuntimeImage','removeRuntimeItem','addRuntimeEgg','onRuntimeEgg','removeRuntimeEgg','addRuntimeEggReward','onRuntimeEggReward','removeRuntimeEggReward','onRuntimeChapterCost','onRuntimeCompletionItem','clearRuntimeCompletionItem','clearThemeRuntime','onRuntimeCouponChange','onRuntimeMedalName','uploadRuntimeMedal','openThemeResultCard','onRuntimeCardField','onRuntimeCardUnlocks','uploadRuntimeCardCover','clearRuntimeCardCover','openThemeScenes','onRuntimeReplayToggle','onRuntimeRotation','addRuntimeScene','onRuntimeSceneName','chooseRuntimeScene','moveRuntimeScene','removeRuntimeScene','openThemeBroadcasts','onRuntimeBroadcastDraft','clearRuntimeBroadcastDraft','addRuntimeBroadcast','onRuntimeBroadcastContent','removeRuntimeBroadcast','onRuntimeBroadcastEffectDraft','clearRuntimeBroadcastEffectDraft','addRuntimeBroadcastEffect','removeRuntimeBroadcastEffect','openJourneyCheckSettings','onJourneyCheckEnabled','onJourneyCheckNumber','clearJourneyCheckNumber','onRuntimeGrantConditionDraft','clearRuntimeGrantConditionDraft','saveRuntimeGrantCondition','editRuntimeGrantCondition','removeRuntimeGrantCondition','onRuntimeGrantScope','createIndependentGrantScope','restoreInheritedGrantScope','openThemeChoiceGrants','onRuntimeChoiceGrantDraft','clearRuntimeChoiceGrantDraft','editRuntimeChoiceGrant','saveRuntimeChoiceGrant','removeRuntimeChoiceGrant','openThemeNodeGrants','onRuntimeNodeGrantDraft','clearRuntimeNodeGrantDraft','editRuntimeNodeGrant','saveRuntimeNodeGrant','removeRuntimeNodeGrant','openThemeRuntimeRules','onRuntimeRuleName','clearRuntimeRuleName','onRuntimeBudgetToggle','onRuntimeRuleInteger','onRuntimeActionSource'];
guarded.forEach(name => { const original = methods[name]; methods[name] = function(...args) { if (!(name === 'closeRuntimeEditor' && ['bingo','bingo-cell'].includes(this.data.runtimeEditor.kind)) && !this._runtimeMayEdit()) return false; try { return original.apply(this,args); } catch (error) { toast(error.message || '主题配置无法读取'); return false; } }; });
module.exports = { data, methods, config, validate, rows, cardSettings, sceneSettings, broadcastSettings, runtimeRuleSettings };
>>>>>>> private-github/featu
