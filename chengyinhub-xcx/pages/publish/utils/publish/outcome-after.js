const clone = value => JSON.parse(JSON.stringify(value));
const get = (config, path) => path.split('.').reduce((value, key) => value == null ? undefined : value[key], config);
const ONCE = ['profile', 'album', 'note', 'dailySign'];
const MAINS = ['qa', 'pricePair', 'blindTaste', 'predict', 'photoCheck', 'coinFlip', 'diceRoll', 'random', 'sort', 'match', 'classify', 'estimate', 'hiddenObject', ...ONCE, 'steps', 'reaction', 'ballShake', 'quietHold', 'compass', 'shout', 'countdown', 'stopwatch', 'typeIn', 'check'];
const mains = config => MAINS.filter(key => config[key]?.enabled === true);
const once = config => mains(config).some(key => ONCE.includes(key));
const empty = () => ({ reply: '', costs: [], effects: [], next: '' });
function draft(row) { return Object.assign(empty(), clone(row || {}), { next: row?.nextStepId || '' }); }
function paths(config) {
  const result = ['outcomeRules.pass'];
  if (!once(config) || config.outcomeRules?.fail != null) result.push('outcomeRules.fail');
  for (const [section, field] of [['qa', 'options'], ['pricePair', 'items'], ['blindTaste', 'options'], ['predict', 'options'], ['random', 'items']]) {
    (config[section]?.[field] || []).forEach((row, i) => result.push(section + '.' + field + '.' + i + '.after'));
  }
  (config.branch?.steps || []).forEach((step, n) => (step.options || []).forEach((row, i) => result.push('branch.steps.' + n + '.options.' + i + '.after')));
  for (const side of ['heads', 'tails']) if (config.coinFlip?.[side]) result.push('coinFlip.' + side + '.after');
  (config.diceRoll?.faces || []).forEach((row, i) => result.push('diceRoll.faces.' + i + '.after'));
  return result;
}
function localReason(config, path) {
  if (path.startsWith('qa.options.') && config.qa?.multi === true) return '多选题请使用通过/没通过之后';
  if (path.startsWith('random.items.') && config.random?.drawCount > 1) return '多张抽卡请使用通过/没通过之后';
  if (path.startsWith('diceRoll.faces.') && (config.diceRoll?.mode === 'd20' || config.diceRoll?.diceCount === 2)) return '此形式请使用通过/没通过之后';
  if (path.startsWith('branch.')) return '暂未开放';
  return '';
}
function afterError(row, config, path) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) return path + '：结果格式不正确';
  if (Object.keys(row).some(key => !['reply', 'costs', 'effects', 'nextStepId'].includes(key))) return path + '：结果含不支持的字段';
  if (row.reply != null && (typeof row.reply !== 'string' || row.reply.length > 2000)) return path + '：回复不能超过 2000 字';
  if (row.nextStepId != null && (typeof row.nextStepId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(row.nextStepId) || !(config.branch?.steps || []).some(step => step.id === row.nextStepId))) return '下一段必须选择本节点 branch.steps 中的有效步骤';
  const slots = config.interactionRules?.resourceSlots || [];
  const seen = new Set();
  for (const kind of ['costs', 'effects']) {
    const rows = row[kind];
    if (rows == null) continue;
    if (!Array.isArray(rows) || rows.length > 20) return path + '：消耗/效果最多 20 项';
    for (const entry of rows) {
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return path + '：消耗/效果格式不正确';
      const allowed = kind === 'costs' ? ['statKey', 'amount'] : ['var', 'op', 'value'];
      if (Object.keys(entry).some(key => !allowed.includes(key))) return path + '：消耗/效果含不支持的字段';
      const key = kind === 'costs' ? entry.statKey : entry.var;
      if (!slots.some(slot => slot.key === key && slot.kind === 'VALUE')) return path + '：请选择已声明的 VALUE 数值';
      const amount = kind === 'costs' ? entry.amount : entry.value;
      if (!Number.isInteger(amount) || amount < 1 || amount > 999) return path + '：数量须为 1 至 999 的整数';
      if (kind === 'effects' && !['INC', 'FLOOR'].includes(entry.op)) return path + '：效果只能增加或恢复到至少';
      if (kind === 'costs' && seen.has(key)) return path + '：消耗目标不能重复';
      seen.add(key);
    }
  }
  return '';
}
function validate(config) {
  const rules = config.outcomeRules;
  if (rules == null) return '';
  if (!rules || typeof rules !== 'object' || Array.isArray(rules) || rules.version !== 1 || Object.keys(rules).some(key => !['version', 'pass', 'fail'].includes(key))) return '结果规则版本或字段不受支持';
  if (mains(config).length !== 1) return '结果规则须且仅须一个主玩法，扫码不是主玩法';
  if (!rules.pass || (!once(config) && !rules.fail)) return '请配置通过和没通过之后';
  if (once(config) && rules.fail != null) return '完成型玩法只配置完成之后';
  if (config.interactionTools?.plans?.length || config.interactionTools?.votePolicy || config.interactionTools?.decidesEnding) return '投票与结果规则不能同时使用';
  if (rules.fail != null && config.interactionRules?.targetScope === 'PARTICIPANTS') return '没通过之后不能作用于已参与的队伍';
  let hasCosts = false;
  for (const path of paths(config)) {
    const row = get(config, path);
    if (row == null) continue;
    const issue = localReason(config, path) || afterError(row, config, path);
    if (issue) return issue;
    hasCosts = hasCosts || !!row.costs?.length;
  }
  const legacy = config.interactionRules || {};
  if (hasCosts && (['hpCost', 'luckCost', 'actionCost'].some(field => legacy[field] > 0) || legacy.statCosts?.some(row => row.amount > 0) || legacy.resourceCosts?.length || legacy.costOptions?.length)) return '结果费用不能与旧费用叠加，请明确迁移';
  return '';
}
function apply(config, path, value) {
  if (!paths(config).includes(path) || (once(config) && path === 'outcomeRules.fail')) return { error: '请选择现有结果' };
  const reason = localReason(config, path); if (reason) return { error: reason };
  const next = clone(config);
  next.outcomeRules = next.outcomeRules || Object.assign({ version: 1, pass: {} }, once(next) ? {} : { fail: {} });
  const row = clone(value); delete row.next; delete row.nextStepId;
  if (value.next) row.nextStepId = value.next;
  const keys = path.split('.'); const last = keys.pop();
  let owner = keys.reduce((value, key) => value[key], next);
  // 六面旧值是字符串；只在作者编辑该面 after 时转换成服务端已有的 action 对象。
  if (typeof owner === 'string') {
    const faceIndex = keys.pop(); const faces = get(next, keys.join('.'));
    owner = faces[faceIndex] = { action: owner };
  }
  owner[last] = row;
  const error = validate(next);
  return error ? { error } : { value: next };
}
module.exports = { get, paths, empty, draft, once, mains, validate, afterError, localReason, apply };
