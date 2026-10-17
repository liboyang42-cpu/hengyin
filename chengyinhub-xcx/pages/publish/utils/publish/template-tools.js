const LEGACY_TOOLS = [
  { key: 'cost', label: '费用', icon: 'finance', sub: '成功时支付数值或物资，可设任选一组' },
  { key: 'uses', label: '成功使用次数', icon: 'check', sub: '限制成功次数及归属' },
  { key: 'trade', label: '物资交换', icon: 'switch-chapter', sub: '允许参与者交换物资' },
  { key: 'vote', label: '投票与投入', icon: 'filter-lines', sub: '配置表决方式、方案和投入' }
];
// 2026-10-11：费用（含物资）回到工具目录；新增「奖励」「线索」两个工具，均写入既有 interactionRules 字段。
const NEW_TOOLS = [
  { key: 'reward', label: '奖励', icon: 'gift', sub: '成功时获得物资，可设任选一组' },
  { key: 'clue', label: '线索', icon: 'flag', sub: '成功时获得线索，可设为全场公开' }
];
const TOOLS = LEGACY_TOOLS.filter(item => item.key === 'cost').concat(NEW_TOOLS, LEGACY_TOOLS.filter(item => ['uses', 'vote'].includes(item.key)));
const CLUE_PREFIX = 'clue.h_';
/** 线索按名称得到稳定标识：同一名称在不同模板里是同一条线索（公开与条件可互相引用）。 */
function clueKey(name) { return CLUE_PREFIX + require('./template-binding.js').sha256(String(name || '').trim()).slice(0, 12); }
function isClueKey(key) { return /^clue\.h_[0-9a-f]{12}$/.test(String(key || '')); }
const COST_FIELDS = ['hpCost', 'luckCost', 'actionCost', 'statCosts', 'resourceCosts', 'costOptions'];
const SCOPES = [
  { key: 'OWNER', label: '本人或本队' }, { key: 'WORLD', label: '同场共用' }
];
const TARGETS = [
  { key: 'OWNER', label: '本人或本队' },
  { key: 'PARTICIPANTS', label: '已参与的队伍' },
  { key: 'WORLD', label: '全场有效队伍' }
];
const POLICIES = [
  { key: 'MAJORITY', label: '过半数' },
  { key: 'UNANIMOUS', label: '全体同意' },
  { key: 'MAJORITY_ACCEPTANCE', label: '过半接受' }
];
const clone = value => JSON.parse(JSON.stringify(value));
const SLOT_KINDS = [{ key: 'VALUE', label: '数值' }, { key: 'ITEM', label: '物资' }];
const slotRow = () => ({ kind: 'VALUE', key: '', label: '', amount: '' });

function slotsOf(advanced) { return clone(((advanced.interactionRules || {}).resourceSlots) || []); }
function slotChoices(slots, kind) {
  return [{ key: '', label: '新建需要的资源' }].concat(slots.filter(slot => slot.kind === kind).map(slot => ({ key: slot.key, label: slot.label })));
}
function legacySlot(slots, legacy, kind, key, reserved) {
  const existing = slots.find(item => item.key === key && item.kind === kind);
  if (existing) return existing.key;
  const identity = kind + ':' + key;
  if (!legacy[identity]) legacy[identity] = allocateSlot(slots, kind, kind === 'VALUE' ? '需要的数值' : '需要的物资', reserved).key;
  return legacy[identity];
}
function slotRowsOf(cost, slots, legacy, reserved) {
  const rows = [];
  const add = (kind, key, amount) => {
    rows.push({ kind, key: legacySlot(slots, legacy, kind, key, reserved), label: '', amount });
  };
  [['hpCost', 'sys.hp'], ['luckCost', 'sys.luck'], ['actionCost', 'sys.actions']].forEach(([field, key]) => {
    if (cost[field] != null) add('VALUE', key, cost[field]);
  });
  (cost.statCosts || []).forEach(value => add('VALUE', value.statKey, value.amount));
  (cost.resourceCosts || []).forEach(value => add('ITEM', value.itemId, value.quantity));
  return rows.length ? rows : [slotRow()];
}

function referencedSlots(owner, used) {
  for (const [field, ref] of [['statCosts', 'statKey'], ['statChanges', 'statKey'], ['resourceCosts', 'itemId'],
    ['grants', 'itemId'], ['materialCost', 'itemId'], ['when', 'itemId'], ['when', 'var'], ['effects', 'var']]) {
    for (const item of owner[field] || []) if (item && item[ref]) used.add(item[ref]);
  }
  for (const field of ['costOptions', 'grantOptions', 'plans']) for (const child of owner[field] || []) referencedSlots(child, used);
}
// C0/C1 注册的原有效果路径；不递归替换未知配置。
function effectPaths(advanced) {
  const paths = require('./outcome-after.js').paths(advanced).map(path => path + '.effects').concat(['effects', 'check.successEffects', 'check.failEffects', 'check.critEffects', 'check.fumbleEffects', 'interactionRules.effects',
    'pricePair.effects', 'hiddenObject.effects', 'sort.effects', 'match.effects', 'classify.effects']);
  (advanced.qa?.options || []).forEach((row, i) => paths.push('qa.options.' + i + '.effects'));
  (advanced.profile?.questions || []).forEach((question, q) => (question.options || []).forEach((row, i) =>
    paths.push('profile.questions.' + q + '.options.' + i + '.effects')));
  (advanced.branch?.steps || []).forEach((step, n) => (step.options || []).forEach((row, i) =>
    paths.push('branch.steps.' + n + '.options.' + i + '.effects')));
  return paths;
}
function effectsAt(advanced, path) {
  const value = path.split('.').reduce((owner, key) => owner == null ? undefined : owner[key], advanced);
  return Array.isArray(value) ? value : [];
}
function effectSlotErrors(advanced) {
  const slots = slotsOf(advanced);
  const errors = [];
  for (const path of effectPaths(advanced)) effectsAt(advanced, path).forEach((row, index) => {
    if (!row || typeof row !== 'object') { errors.push({ path, index, field: 'var', error: '效果格式不正确，请重新配置' }); return; }
    const declared = slots.find(slot => slot.key === row.var);
    const bound = declared || row.op === 'FLOOR' || row.var && !/^(counter\.|clue\.|relation\.|sys\.)/.test(row.var) && row.op === 'INC';
    if (!bound) return;
    let error = '', field = 'var';
    if (!declared || declared.kind !== 'VALUE') error = '所需数值资源已变更，请重新选择';
    else if (!['INC', 'FLOOR'].includes(row.op)) { error = '所需数值资源只能增加或恢复到至少'; field = 'op'; }
    else if (!Number.isInteger(row.value) || row.value < 1 || row.value > 999) { error = '效果数值须为 1 至 999 的整数'; field = 'value'; }
    if (error) errors.push({ path, index, field, error });
  });
  return errors;
}
function bindEffect(advanced, path, index, key, label) {
  if (!effectPaths(advanced).includes(path) || !effectsAt(advanced, path)[index]) return { error: '请选择现有的效果行', path, index, field: 'var' };
  const next = clone(advanced), slots = slotsOf(next);
  const resolved = resolveSlot(slots, 'VALUE', key, label, reservedRefs(next));
  if (resolved.error) return { ...resolved, path, index };
  const row = effectsAt(next, path)[index];
  row.var = resolved.key;
  if (!['INC', 'FLOOR'].includes(row.op)) row.op = 'INC';
  next.interactionRules = Object.assign({}, next.interactionRules, { resourceSlots: slots });
  const error = effectSlotErrors(next).find(error => error.path === path && error.index === index);
  if (error) return error;
  return { value: cleanSlots(next) };
}
function reservedRefs(advanced) {
  const refs = new Set();
  require('./outcome-after.js').paths(advanced).forEach(path => (require('./outcome-after.js').get(advanced, path)?.costs || []).forEach(row => refs.add(row.statKey)));
  referencedSlots(advanced.interactionRules || {}, refs);
  referencedSlots(advanced.interactionTools || {}, refs);
  effectPaths(advanced).forEach(path => effectsAt(advanced, path).forEach(row => { if (row && row.var) refs.add(row.var); }));
  return refs;
}

function cleanSlots(next, previouslyUsed) {
  const rules = next.interactionRules;
  if (!rules || !Array.isArray(rules.resourceSlots)) return next;
  const used = reservedRefs(next);
  rules.resourceSlots = rules.resourceSlots.filter(slot => used.has(slot.key) || previouslyUsed && !previouslyUsed.has(slot.key));
  if (!rules.resourceSlots.length) delete rules.resourceSlots;
  if (!Object.keys(rules).length) delete next.interactionRules;
  return next;
}

function allocateSlot(slots, kind, label, reserved) {
  if (!label || label.length > 20) return { error: '资源名称须为 1 至 20 字', field: 'label' };
  if (slots.length >= 20) return { error: '最多声明 20 项需要的资源', field: 'label' };
  const prefix = kind === 'VALUE' ? 'value_' : 'item_';
  let index = 1;
  while (slots.some(slot => slot.key === prefix + index) || (reserved && reserved.has(prefix + index))) index += 1;
  const key = prefix + index;
  slots.push({ key, kind, label, required: true });
  return { key };
}

function resolveSlot(slots, kind, key, label, reserved) {
  if (key) return slots.some(slot => slot.key === key && slot.kind === kind)
    ? { key } : { error: '请选择已声明的资源', field: 'key' };
  const name = String(label || '').trim();
  // 作者输入的同名同类资源就是同一项需要（例如两组奖励都写「口粮」），沿用已声明的槽，避免采用时无法分别对应
  const same = name && (slots || []).find(slot => slot.kind === kind && String(slot.label || '').trim() === name);
  if (same) return { key: same.key };
  return allocateSlot(slots, kind, name, reserved);
}

function migrateLegacyReferences(owner, slots, legacy, reserved) {
  for (const [field, ref, kind] of [['statCosts', 'statKey', 'VALUE'], ['statChanges', 'statKey', 'VALUE'],
    ['resourceCosts', 'itemId', 'ITEM'], ['grants', 'itemId', 'ITEM'], ['materialCost', 'itemId', 'ITEM'],
    ['when', 'itemId', 'ITEM'], ['when', 'var', 'VALUE']]) {
    for (const item of owner[field] || []) {
      if (!item || !item[ref] || slots.some(slot => slot.key === item[ref])) continue;
      if (kind === 'VALUE' && !/^(counter\.|sys\.(hp|luck|actions)$)/.test(item[ref])) continue;
      const identity = kind + ':' + item[ref];
      if (!legacy[identity]) {
        const added = allocateSlot(slots, kind, kind === 'VALUE' ? '需要的数值' : '需要的物资', reserved);
        if (added.error) return added;
        legacy[identity] = added.key;
      }
      item[ref] = legacy[identity];
    }
  }
  for (const field of ['costOptions', 'grantOptions', 'plans']) for (const child of owner[field] || []) {
    const error = migrateLegacyReferences(child, slots, legacy, reserved);
    if (error) return error;
  }
  return null;
}

function newPlanKey(plans, now = Date.now(), random = Math.random()) {
  const base = 'plan_' + now.toString(36) + '_' + random.toString(36).slice(2, 8);
  let key = base, ordinal = 1;
  while (plans.some(row => row.planKey === key)) key = base + '_' + ordinal++;
  return key;
}

function read(key, advanced) {
  const rules = advanced.interactionRules || {};
  const tools = advanced.interactionTools || {};
  const slots = slotsOf(advanced);
  const legacy = {};
  const reserved = reservedRefs(advanced);
  if (key === 'cost') return Object.assign(Array.isArray(rules.costOptions)
    ? { mode: 'OPTIONS', groups: rules.costOptions.map(g => ({ label: g.label || '', rows: slotRowsOf(g, slots, legacy, reserved) })) }
    : { mode: 'FIXED', groups: [{ label: '', rows: slotRowsOf(rules, slots, legacy, reserved) }] },
  { slotMode: true, slots, legacyRefs: legacy, valueSlots: slotChoices(slots, 'VALUE'), itemSlots: slotChoices(slots, 'ITEM') });
  if (key === 'reward') {
    const rows = grants => (grants || []).map(g => ({ kind: 'ITEM', key: legacySlot(slots, legacy, 'ITEM', g.itemId, reserved), label: '', amount: g.quantity }));
    const groups = Array.isArray(rules.grantOptions)
      ? rules.grantOptions.map(g => ({ label: g.label || '', rows: rows(g.grants) }))
      : [{ label: '', rows: rows(rules.grants) }];
    groups.forEach(group => { if (!group.rows.length) group.rows.push({ kind: 'ITEM', key: '', label: '', amount: '' }); });
    return { mode: Array.isArray(rules.grantOptions) ? 'OPTIONS' : 'FIXED', groups, slotMode: true, slots, legacyRefs: legacy, itemSlots: slotChoices(slots, 'ITEM') };
  }
  if (key === 'clue') {
    const names = advanced.clues || {};
    const rows = [];
    (rules.effects || []).forEach(row => { if (row && isClueKey(row.var)) rows.push({ name: names[row.var] || '', public: false }); });
    (rules.worldEffects || []).forEach(row => { if (row && isClueKey(row.var)) rows.push({ name: names[row.var] || '', public: true }); });
    return { rows: rows.length ? rows : [{ name: '', public: false }], publicOnce: rules.publicOnce === true };
  }
  if (key === 'uses') return { limit: rules.limit == null ? 1 : rules.limit, scope: rules.scope || 'OWNER', requireArrival: rules.requireArrival === true };
  if (key === 'trade') return { allowTrade: tools.allowTrade !== false };
  const vote = { slotMode: true, slots, legacyRefs: legacy,
    votePolicy: tools.votePolicy || '', decidesEnding: tools.decidesEnding === true,
    plans: (Array.isArray(tools.plans) ? tools.plans : []).map(p => ({ title: p.title || '', summary: p.summary || '', when: p.when,
      clueNames: (p.when || []).filter(row => row && isClueKey(row.var)).map(row => (advanced.clues || {})[row.var] || ''),
      materialCost: (p.materialCost || []).map(c => ({ itemId: legacySlot(slots, legacy, 'ITEM', c.itemId, reserved), label: '', quantity: c.quantity })),
      endingKey: p.endingKey || '', planKey: p.planKey || '' })),
    fallbackTitle: (tools.fallback && tools.fallback.title) || '', fallbackSummary: (tools.fallback && tools.fallback.summary) || '' };
  vote.valueSlots = slotChoices(slots, 'VALUE'); vote.itemSlots = slotChoices(slots, 'ITEM');
  return vote;
}

function costOf(rows, group, slots, reserved) {
  if (!rows.length) return { error: '请添加至少一项费用', group, field: 'rows' };
  const cost = {};
  for (let i = 0; i < rows.length; i += 1) {
    const r = rows[i];
    const key = String(r.key || '').trim();
    const amount = Number(r.amount);
    if (!Number.isInteger(amount) || amount < 1 || amount > 999) return { error: '数量须为 1 至 999', group, row: i, field: 'amount' };
    if (slots && SLOT_KINDS.some(item => item.key === r.kind)) {
      const resolved = resolveSlot(slots, r.kind, key, r.label, reserved);
      if (resolved.error) return { ...resolved, group, row: i };
      const field = r.kind === 'VALUE' ? 'statCosts' : 'resourceCosts';
      const keyField = r.kind === 'VALUE' ? 'statKey' : 'itemId';
      const amountField = r.kind === 'VALUE' ? 'amount' : 'quantity';
      const list = cost[field] || (cost[field] = []);
      const existing = list.find(item => item[keyField] === resolved.key);
      if (existing) {
        existing[amountField] += amount;
        if (existing[amountField] > 999) return { error: '同一资源合计不超过 999', group, row: i, field: 'amount' };
      } else list.push({ [keyField]: resolved.key, [amountField]: amount });
      continue;
    }
    if ((r.kind === 'COUNTER' && !/^counter\.[a-z][a-z0-9_]{0,47}$/.test(key)) ||
        (r.kind === 'ITEM' && !/^[a-z][a-z0-9_]{0,31}$/.test(key))) {
      return { error: r.kind === 'COUNTER' ? '数值引用须为 counter. 开头的键' : '请填写物资引用键', group, row: i, field: 'key' };
    }
    if (r.kind === 'HP' || r.kind === 'LUCK' || r.kind === 'ACTION') {
      const field = r.kind === 'HP' ? 'hpCost' : r.kind === 'LUCK' ? 'luckCost' : 'actionCost';
      cost[field] = (cost[field] || 0) + amount;
      if (cost[field] > 999) return { error: '同一资源合计不超过 999', group, row: i, field: 'amount' };
    } else if (r.kind === 'COUNTER' || r.kind === 'ITEM') {
      const field = r.kind === 'COUNTER' ? 'statCosts' : 'resourceCosts';
      const keyField = r.kind === 'COUNTER' ? 'statKey' : 'itemId';
      const amountField = r.kind === 'COUNTER' ? 'amount' : 'quantity';
      const list = cost[field] || (cost[field] = []);
      const existing = list.find(item => item[keyField] === key);
      if (existing) {
        existing[amountField] += amount;
        if (existing[amountField] > 999) return { error: '同一资源合计不超过 999', group, row: i, field: 'amount' };
      } else list.push({ [keyField]: key, [amountField]: amount });
    }
    else return { error: '请选择资源类型', group, row: i, field: 'kind' };
  }
  return { cost };
}

function apply(key, draft, advanced) {
  const next = clone(advanced);
  const rules = clone(next.interactionRules || {});
  const tools = clone(next.interactionTools || {});
  const slots = draft.slotMode ? clone(draft.slots || []) : null;
  const reserved = reservedRefs(advanced);
  if (['cost', 'uses', 'reward', 'clue'].includes(key) && Array.isArray(tools.plans) && tools.plans.length) {
    return { error: '投票不能与费用、奖励、线索或次数叠加，请先移除投票', field: key };
  }
  const names = Object.assign({}, next.clues || {});
  if (key === 'cost') {
    const after = require('./outcome-after.js');
    if (after.paths(advanced).some(path => ((after.get(advanced, path) || {}).costs || []).length)) return { error: '「结果之后」已设置消耗，费用只能在一处设置', field: 'cost' };
    COST_FIELDS.forEach(field => delete rules[field]);
    const groups = draft.mode === 'OPTIONS' ? draft.groups : draft.groups.slice(0, 1);
    if (draft.mode === 'OPTIONS' && (groups.length < 2 || groups.length > 4)) return { error: '任选费用须有 2 至 4 组', field: 'groups' };
    const costs = [];
    for (let i = 0; i < groups.length; i += 1) {
      if (String(groups[i].label || '').length > 20) return { error: '组名不超过 20 字', group: i, field: 'label' };
      const result = costOf(groups[i].rows || [], i, slots, reserved);
      if (result.error) return result;
      costs.push(Object.assign(draft.mode === 'OPTIONS' && groups[i].label ? { label: groups[i].label.trim() } : {}, result.cost));
    }
    if (draft.mode === 'OPTIONS') rules.costOptions = costs;
    else Object.assign(rules, costs[0]);
  } else if (key === 'reward') {
    delete rules.grants; delete rules.grantOptions;
    const groups = draft.mode === 'OPTIONS' ? draft.groups : draft.groups.slice(0, 1);
    if (draft.mode === 'OPTIONS' && (groups.length < 2 || groups.length > 4)) return { error: '任选奖励须有 2 至 4 组', field: 'groups' };
    const built = [];
    for (let i = 0; i < groups.length; i += 1) {
      if (String(groups[i].label || '').length > 20) return { error: '组名不超过 20 字', group: i, field: 'label' };
      const rows = groups[i].rows || [];
      if (!rows.length) return { error: '请添加至少一项奖励', group: i, field: 'rows' };
      const grants = [];
      for (let j = 0; j < rows.length; j += 1) {
        const amount = Number(rows[j].amount);
        if (!Number.isInteger(amount) || amount < 1 || amount > 999) return { error: '数量须为 1 至 999', group: i, row: j, field: 'amount' };
        const resolved = resolveSlot(slots, 'ITEM', String(rows[j].key || '').trim(), rows[j].label, reserved);
        if (resolved.error) return { ...resolved, group: i, row: j };
        const existing = grants.find(row => row.itemId === resolved.key);
        if (existing) existing.quantity += amount; else grants.push({ itemId: resolved.key, quantity: amount });
      }
      built.push(Object.assign(draft.mode === 'OPTIONS' && String(groups[i].label || '').trim() ? { label: groups[i].label.trim() } : {}, { grants }));
    }
    if (draft.mode === 'OPTIONS') rules.grantOptions = built; else rules.grants = built[0].grants;
  } else if (key === 'clue') {
    const rows = (draft.rows || []).filter(row => String(row.name || '').trim());
    if (!rows.length) return { error: '请填写线索名称', row: 0, field: 'name' };
    const seen = new Set();
    rules.effects = (rules.effects || []).filter(row => !(row && isClueKey(row.var)));
    rules.worldEffects = (rules.worldEffects || []).filter(row => !(row && isClueKey(row.var)));
    for (let i = 0; i < rows.length; i += 1) {
      const name = String(rows[i].name).trim();
      if (name.length > 20) return { error: '线索名称不超过 20 字', row: i, field: 'name' };
      if (seen.has(name)) return { error: '线索名称不能重复', row: i, field: 'name' };
      seen.add(name);
      const effect = { var: clueKey(name), op: 'SET', value: 1 };
      (rows[i].public ? rules.worldEffects : rules.effects).push(effect);
      names[effect.var] = name;
    }
    if (!rules.effects.length) delete rules.effects;
    if (!rules.worldEffects.length) delete rules.worldEffects;
    // 只首次生效：同一共享操作的多种途径，公开线索已公开后后来者不再结算（须同场共用次数）
    if (draft.publicOnce) {
      if (!rules.worldEffects) return { error: '只首次生效需要至少一条全场公开线索', field: 'publicOnce' };
      if (rules.scope !== 'WORLD') return { error: '只首次生效需要把成功次数设为同场共用', field: 'publicOnce' };
      rules.publicOnce = true;
    } else delete rules.publicOnce;
  } else if (key === 'uses') {
    const limit = Number(draft.limit);
    if (!Number.isInteger(limit) || limit < 1 || limit > 99) return { error: '成功次数须为 1 至 99', field: 'limit' };
    if (!SCOPES.some(s => s.key === draft.scope)) return { error: '请选择次数归属', field: 'scope' };
    rules.limit = limit; rules.scope = draft.scope;
    if (draft.requireArrival) rules.requireArrival = true; else delete rules.requireArrival;
    if (rules.publicOnce && rules.scope !== 'WORLD') return { error: '线索设为只首次生效时，次数归属须为同场共用', field: 'scope' };
  } else if (key === 'trade') {
    tools.allowTrade = !!draft.allowTrade;
  } else if (key === 'vote') {
    if (Object.keys(rules).some(field => field !== 'resourceSlots')) return { error: '投票不能与费用、次数或效果规则叠加，请先移除这些规则', field: 'vote' };
    // 表决方式归主题（整场共享规则）；旧模板里存的值原样保留
    if (draft.votePolicy && !POLICIES.some(p => p.key === draft.votePolicy)) return { error: '表决方式无效', field: 'votePolicy' };
    if (!draft.plans || !draft.plans.length || draft.plans.length > 30) return { error: '请添加 1 至 30 个选项', field: 'plans' };
    const plans = [];
    for (let i = 0; i < draft.plans.length; i += 1) {
      const p = draft.plans[i];
      const title = String(p.title || '').trim();
      if (!title || title.length > 80) return { error: '选项名称须为 1 至 80 字', plan: i, field: 'title' };
      if (String(p.summary || '').length > 2000) return { error: '选项说明不超过 2000 字', plan: i, field: 'summary' };
      const plan = { title };
      if (p.summary) plan.summary = p.summary.trim();
      // 需要的线索：按名称对应全场公开线索（与「线索」工具同名同键），其余既有条件原样保留
      const kept = (p.when || []).filter(row => !(row && isClueKey(row.var)));
      const clueNames = (p.clueNames || []).map(name => String(name || '').trim()).filter(Boolean);
      for (const name of clueNames) if (name.length > 20) return { error: '线索名称不超过 20 字', plan: i, field: 'clues' };
      const when = kept.concat([...new Set(clueNames)].map(name => { names[clueKey(name)] = name; return { var: clueKey(name), op: 'EQ', value: 1 }; }));
      if (when.length) plan.when = when;
      const seen = new Set();
      if (p.materialCost && p.materialCost.length) {
        plan.materialCost = [];
        for (let j = 0; j < p.materialCost.length; j += 1) {
          const c = p.materialCost[j];
          let itemId = String(c.itemId || '').trim();
          const quantity = Number(c.quantity);
          if (slots) {
            const resolved = resolveSlot(slots, 'ITEM', itemId, c.label, reserved);
            if (resolved.error) return { ...resolved, plan: i, material: j };
            itemId = resolved.key;
          } else if (!/^[a-z][a-z0-9_]{0,31}$/.test(itemId)) return { error: '物资引用键须有效', plan: i, material: j, field: 'itemId' };
          if (seen.has(itemId)) return { error: '同一选项不能重复投入同一物资', plan: i, material: j, field: 'itemId' };
          if (!Number.isInteger(quantity) || quantity < 1 || quantity > 999) return { error: '投入数量须为 1 至 999', plan: i, material: j, field: 'quantity' };
          seen.add(itemId); plan.materialCost.push({ itemId, quantity });
        }
      }
      if (p.endingKey) plan.endingKey = p.endingKey;
      if (p.planKey) plan.planKey = p.planKey;
      else if (!p.endingKey) {
        plan.planKey = newPlanKey(draft.plans.concat(plans));
      }
      if (plan.planKey && (!/^[a-z][a-z0-9_]{0,31}$/.test(plan.planKey)
        || plans.some(row => row.planKey === plan.planKey))) return { error: '方案标识无效或重复，请重新添加该方案', plan: i, field: 'planKey' };
      plans.push(plan);
    }
    if (draft.votePolicy) tools.votePolicy = draft.votePolicy; else delete tools.votePolicy;
    tools.plans = plans;
    // 是否收场由采用章节决定；旧模板显式的 decidesEnding 原样保留，新模板不再写
    if (draft.decidesEnding) tools.decidesEnding = true; else delete tools.decidesEnding;
    // 决定结局时「没有方案执行」的收场内容（选填）；写了说明就必须有标题
    const fallbackTitle = String(draft.fallbackTitle || '').trim(), fallbackSummary = String(draft.fallbackSummary || '').trim();
    if (fallbackTitle || fallbackSummary) {
      if (!fallbackTitle || fallbackTitle.length > 80) return { error: '没有方案执行时的标题须为 1 至 80 字', field: 'fallbackTitle' };
      if (fallbackSummary.length > 2000) return { error: '没有方案执行时的内容不超过 2000 字', field: 'fallbackSummary' };
      tools.fallback = fallbackSummary ? { title: fallbackTitle, summary: fallbackSummary } : { title: fallbackTitle };
    } else delete tools.fallback;
  }
  if (slots && !(advanced.interactionRules || {}).resourceSlots) {
    const legacy = Object.assign({}, draft.legacyRefs);
    const error = migrateLegacyReferences(rules, slots, legacy, reserved) || migrateLegacyReferences(tools, slots, legacy, reserved);
    if (error) return error;
  }
  if (slots) rules.resourceSlots = slots;
  if (Object.keys(rules).length) next.interactionRules = rules; else delete next.interactionRules;
  if (Object.keys(tools).length) next.interactionTools = tools; else delete next.interactionTools;
  syncClueNames(next, names);
  cleanSlots(next);
  const activeSlots = ((next.interactionRules || {}).resourceSlots) || [];
  for (let i = 0; i < activeSlots.length; i += 1) {
    const label = activeSlots[i].label;
    if (!label || !label.trim() || label.length > 20) return { error: '资源名称须为 1 至 20 字', slot: i, field: 'slotLabel' };
  }
  return { value: next };
}

/** 线索名称表只保留仍被引用的线索（效果、全场公开、方案条件）。 */
function syncClueNames(next, names) {
  const used = new Set();
  const rules = next.interactionRules || {};
  for (const row of (rules.effects || []).concat(rules.worldEffects || [])) if (row && isClueKey(row.var)) used.add(row.var);
  for (const plan of ((next.interactionTools || {}).plans || [])) for (const row of plan.when || []) if (row && isClueKey(row.var)) used.add(row.var);
  const clues = {};
  for (const key of used) if (names[key]) clues[key] = names[key];
  if (Object.keys(clues).length) next.clues = clues; else delete next.clues;
}

function remove(key, advanced) {
  const next = clone(advanced);
  const rules = next.interactionRules || {};
  const tools = next.interactionTools || {};
  if (key === 'cost') COST_FIELDS.forEach(field => delete rules[field]);
  if (key === 'reward') { delete rules.grants; delete rules.grantOptions; }
  if (key === 'clue') {
    for (const field of ['effects', 'worldEffects']) {
      if (!Array.isArray(rules[field])) continue;
      rules[field] = rules[field].filter(row => !(row && isClueKey(row.var)));
      if (!rules[field].length) delete rules[field];
    }
    delete rules.publicOnce;
  }
  if (key === 'uses') { delete rules.limit; delete rules.scope; delete rules.requireArrival; }
  if (key === 'trade') delete tools.allowTrade;
  if (key === 'vote') { delete tools.votePolicy; delete tools.plans; delete tools.decidesEnding; delete tools.fallback; }
  if (!Object.keys(rules).length) delete next.interactionRules;
  if (!Object.keys(tools).length) delete next.interactionTools;
  syncClueNames(next, next.clues || {});
  return cleanSlots(next);
}

function selected(advanced) {
  const rules = advanced.interactionRules || {};
  const tools = advanced.interactionTools || {};
  return {
    cost: COST_FIELDS.some(field => Object.prototype.hasOwnProperty.call(rules, field)),
    reward: Object.prototype.hasOwnProperty.call(rules, 'grants') || Object.prototype.hasOwnProperty.call(rules, 'grantOptions'),
    clue: (rules.effects || []).concat(rules.worldEffects || []).some(row => row && isClueKey(row.var)),
    uses: rules.limit != null || rules.scope != null,
    trade: Object.prototype.hasOwnProperty.call(tools, 'allowTrade'),
    vote: Array.isArray(tools.plans) && tools.plans.length > 0
  };
}

function summaries(advanced) {
  const rules = advanced.interactionRules || {};
  const tools = advanced.interactionTools || {};
  const slots = slotsOf(advanced);
  const label = key => (slots.find(slot => slot.key === key) || {}).label || key;
  const cost = value => [
    value.hpCost ? 'HP ×' + value.hpCost : '', value.luckCost ? 'LUCK ×' + value.luckCost : '',
    value.actionCost ? '行动 ×' + value.actionCost : '',
    ...(value.statCosts || []).map(r => label(r.statKey) + ' ×' + r.amount),
    ...(value.resourceCosts || []).map(r => label(r.itemId) + ' ×' + r.quantity)
  ].filter(Boolean).join('、');
  return {
    cost: Array.isArray(rules.costOptions) ? rules.costOptions.map((g, i) => (g.label || '第' + (i + 1) + '组') + '：' + cost(g)).join(' / ') : cost(rules),
    reward: Array.isArray(rules.grantOptions)
      ? rules.grantOptions.map((g, i) => (g.label || '第' + (i + 1) + '组') + '：' + (g.grants || []).map(r => label(r.itemId) + ' ×' + r.quantity).join('、')).join(' / ')
      : (rules.grants || []).map(r => label(r.itemId) + ' ×' + r.quantity).join('、'),
    clue: (rules.effects || []).filter(row => row && isClueKey(row.var)).map(row => (advanced.clues || {})[row.var] || '线索')
      .concat((rules.worldEffects || []).filter(row => row && isClueKey(row.var)).map(row => ((advanced.clues || {})[row.var] || '线索') + '（公开）')).join('、'),
    uses: '成功上限 ' + (rules.limit || 1) + ' 次 · ' + (rules.scope === 'WORLD' ? '同场共用' : '本人或本队') + (rules.requireArrival ? ' · 须已到达' : ''),
    trade: tools.allowTrade ? '允许物资交换' : '已关闭物资交换',
    vote: (Array.isArray(tools.plans) ? tools.plans.length : 0) + ' 个选项'
  };
}

module.exports = { TOOLS, LEGACY_TOOLS, NEW_TOOLS, clueKey, isClueKey, SLOT_KINDS, SCOPES, TARGETS, POLICIES, read, apply, remove, selected, summaries, reservedRefs, newPlanKey, slotsOf, slotChoices, cleanSlots, effectPaths, effectsAt, effectSlotErrors, bindEffect };
