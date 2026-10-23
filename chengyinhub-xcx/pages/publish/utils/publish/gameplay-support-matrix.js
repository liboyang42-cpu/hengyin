// 只读策略按 app 会话缓存；旧服务端不可用时继续交给发布服务校验。
const sessions = new WeakMap();
// 当前后端 GameplayRuntimeConfig 的 v2 恢复白名单；完成桥开放不等于恢复配置已接通。
const ZERO_CHALLENGE_GAMEPLAYS = ['pricePair', 'blindTaste', 'check', 'diceRoll', 'coinFlip', 'random', 'estimate', 'qa', 'sort', 'match', 'classify', 'typeIn', 'ballShake', 'reaction', 'quietHold', 'shout', 'compass', 'stopwatch', 'countdown'];
const ZERO_CHALLENGE_REASON = '此玩法暂不支持归零恢复，请选择其他挑战节点';
// 服务端约束说明是工程合同（OWNER/TEAM/maxTries…），作者页只显示下面的作者文案；未知代码保留原文。
const CONSTRAINT_COPY = {
  PROOF_REQUIRED: '这个附加玩法不能单独判定完成，需要配合主玩法使用',
  OWNER_OR_TEAM: '个人或独立队伍的主题里，效果只能作用于本人或本队',
  WORLD_SHARED_ONLY: '交易和投票只能用在全场共享的组队主题里；投票不能再设置扣费、次数或奖励',
  CHALLENGE_ONLY: '感应类玩法只能用作归零挑战，且要有可作答的备选玩法；挑战不扣费、不发奖励、不推进章节',
  FREE_RETRY: '这里的问答和排序不能限制尝试次数'
};
function authorCopy(m) {
  return m && Object.assign({}, m, { constraints: Object.assign({}, m.constraints, CONSTRAINT_COPY) });
}
function load(app, retry = false) {
  if (retry) sessions.delete(app);
  if (!sessions.has(app)) sessions.set(app, new Promise(resolve => {
    app.sendRequest({ url: '/api/play/theme/support-matrix', method: 'GET', auth: true,
      hideLoading: true, autoErrorToast: false,
      success(res) {
        const m = res && res.code == 200 && res.data;
        resolve(m && m.schemaVersion === 1 && Array.isArray(m.types) && Array.isArray(m.addons)
          && m.constraints && m.legacyProofs && m.types.concat(m.addons).every(row => row && row.key && row.tools) ? authorCopy(m) : null);
      }, fail() { resolve(null); }
    });
  }));
  return sessions.get(app);
}
function parse(raw) {
  try { return (typeof raw === 'string' ? JSON.parse(raw || '{}') : raw) || {}; } catch (_) { return {}; }
}
function config(template) { return parse(template && template.advancedConfigJson); }
function mains(matrix, advanced, recoveryV2 = false) {
  const r5 = advanced.outcomeRules?.version === 1 || recoveryV2;
  const secondary = section => section === 'scan' && Object.keys(advanced).some(key => key !== 'scan' && matrix.types.some(row => row.section === key) && advanced[key]?.enabled) || (r5 ? ['timer', 'timeWindow', 'leaderboard', 'multiplayer', 'branch'].includes(section) : matrix.addons.some(row => row.section === section) || ['branch', 'random'].includes(section));
  return Object.keys(advanced).filter(section => advanced[section] && advanced[section].enabled === true && !secondary(section))
    .map(section => (r5 ? matrix.types.concat(matrix.addons) : matrix.types).find(row => row.section === section && (!row.mode || row.mode === (advanced[section].mode || (section === 'diceRoll' ? 'd6' : ''))))
      || { key: section, tools: {}, completionProof: false });
}
function generic(advanced) {
  return advanced.outcomeRules != null || advanced.interactionTools != null || Object.keys(advanced.interactionRules || {}).some(key => key !== 'resourceSlots');
}
function effectOnly(advanced) { return advanced.interactionRules?.resourceSlots != null && !generic(advanced); }
function effectSlotPolicy(matrix, advanced) {
  if (!matrix) return '';
  const rows = matrix.types.concat(matrix.addons).filter(row => advanced[row.section]?.enabled === true
    && (!row.mode || row.mode === advanced[row.section].mode));
  return rows.some(row => row.effectSlotSupport === false || row.effectSlotScopes && !row.effectSlotScopes.includes('OWNER'))
    ? '这种玩法暂不支持所需数值资源' : '';
}
function zeroHpPolicies(matrix, theme, nodes = []) {
  const game = theme.gameplayRuntime || {};
  const settled = ['PLAYER', 'TEAM'].includes(game.runtimeScope) || (game.acts || []).length > 0 || (game.quests || []).length > 0
    || (theme.chapters || []).some(chapter => chapter.completion || chapter.exclusiveGroups || chapter.extraNodes)
    || nodes.some(node => generic(config(node.template || node.templateInfo || node)));
  const policies = matrix && matrix.zeroHpPolicies && matrix.zeroHpPolicies[settled ? 'generic' : 'legacy'];
  return Array.isArray(policies) ? policies : [];
}
function toolPolicy(matrix, mainKey, toolKey, advanced, ctx = {}) {
  if (toolKey === 'vote' && ctx.runtime && !(ctx.runtime?.runtimeScope === 'TEAM' && ctx.runtime?.worldMode === 'WORLD_SHARED')) return '需在主题里选全场共享的组队进行方式';
  if (toolKey === 'vote' && advanced.outcomeRules != null) return '投票与结果规则不能同时使用';
  if (!matrix) return '';
  const row = matrix.types.concat(matrix.addons).find(row => row.key === mainKey);
  const field = { cost: 'costs', reward: 'costs', clue: 'costs', uses: 'limitsScope', trade: 'trade', vote: 'vote' }[toolKey];
  if (!row || !field) return '';
  if (ctx.challenge) return '归零挑战不能再设置扣费、次数或效果';
  if (row.tools[field] === 'UNSUPPORTED' && !(toolKey === 'uses' && advanced.outcomeRules?.version === 1 && row.r5CompletionProof)) return '这种玩法暂时无法确认是否完成';
  if (row.tools[field] === 'WORLD_SHARED_ONLY' && ctx.runtime
    && !(ctx.runtime.runtimeScope === 'TEAM' && ctx.runtime.worldMode === 'WORLD_SHARED')) return matrix.constraints.WORLD_SHARED_ONLY;
  if (['cost', 'reward', 'clue', 'uses'].includes(toolKey) && (advanced.interactionTools || {}).plans?.length)
    return '投票不能再设置扣费、次数或效果';
  if (toolKey === 'vote' && Object.keys(advanced.interactionRules || {}).some(key => key !== 'resourceSlots'))
    return '投票不能再设置扣费、次数或效果';
  return '';
}
function condition(matrix, mainKey, toolKey) {
  if (!matrix) return '';
  const row = matrix.types.concat(matrix.addons).find(row => row.key === mainKey);
  const field = { cost: 'costs', reward: 'costs', clue: 'costs', uses: 'limitsScope', trade: 'trade', vote: 'vote' }[toolKey];
  return row && matrix.constraints[row.tools[field]] || '';
}
function validate(matrix, template, context = {}) {
  if (!matrix) return '';
  const advanced = config(template);
  const outcomeIssue = require('./outcome-after.js').validate(advanced); if (outcomeIssue) return outcomeIssue;
  if (advanced.outcomeRules && matrix.outcomeRules?.nextStepId !== 'SETTLED_FEEDBACK' && require('./outcome-after.js').paths(advanced).some(path => require('./outcome-after.js').get(advanced, path)?.nextStepId)) return '进入下一段暂未开放';
  const rules = advanced.interactionRules || {}, tools = advanced.interactionTools || {};
  const values = new Set((Array.isArray(rules.resourceSlots) ? rules.resourceSlots : []).filter(slot => slot.kind === 'VALUE').map(slot => slot.key));
  const effects = require('./template-tools.js');
  if (values.size && effects.effectPaths(advanced).some(path => effects.effectsAt(advanced, path).some(row => row && values.has(row.var)))) {
    const reason = effectSlotPolicy(matrix, advanced);
    if (reason) return reason;
  }
  if (!context.challenge && context.runtime && !(context.runtime.runtimeScope === 'TEAM' && context.runtime.worldMode === 'WORLD_SHARED')) {
    if (context.vote) return matrix.constraints.WORLD_SHARED_ONLY;
    if ((context.rules || []).some(rule => rule.scope && rule.scope !== 'OWNER' || rule.targetScope && rule.targetScope !== 'OWNER')) return matrix.constraints.OWNER_OR_TEAM;
  }
  if (!context.settled && !context.challenge && effectOnly(advanced)) return effectSlotPolicy(matrix, advanced);
  if (!context.settled && !context.challenge && !generic(advanced)) return '';
  let rows = mains(matrix, advanced, context.challengeVersion === 2);
  if (!rows.length && !context.challenge) {
    const key = Object.keys(matrix.legacyProofs).find(key => matrix.legacyProofs[key] === Number(template.validationMethod));
    rows = [key === 'GPS' ? { key, completionProof: true, tools: {} } : matrix.types.find(row => row.key === key) || { tools: {} }];
  }
  if (!rows.length) return '这种玩法不能用作归零挑战';
  for (const row of rows) {
    if (context.challenge) {
      if (context.challengeVersion === 2 && !ZERO_CHALLENGE_GAMEPLAYS.includes(row.section)) return ZERO_CHALLENGE_REASON;
      if (!['SUPPORTED', 'CHALLENGE_ONLY'].includes(row.tools.zeroChallenge) && !(context.challengeVersion === 2 && row.r5CompletionProof)) return '这种玩法不能用作归零挑战';
      if (advanced.interactionTools != null || Object.keys(advanced.interactionRules || {}).some(key => key !== 'resourceSlots') || (context.rules || []).length) return '归零挑战不能再设置扣费、次数或效果';
    } else {
      if (!(advanced.outcomeRules?.version === 1 ? row.r5CompletionProof : row.completionProof)) return '这种玩法暂时无法确认是否完成';
      if (advanced.outcomeRules?.version !== 1 && (['qaText', 'qaPick'].includes(row.key) && Number((advanced.qa || {}).maxTries) > 0
        || row.key === 'sort' && Number((advanced.sort || {}).maxAttempts) > 0)) return matrix.constraints.FREE_RETRY;
      if (row.key === 'scan' && Number(template.validationMethod) !== matrix.legacyProofs.scan) return '扫码玩法须选择扫码验证';
    }
  }
  if (tools.plans?.length && advanced.interactionRules != null
    && !(Object.keys(rules).length === 1 && Object.prototype.hasOwnProperty.call(rules, 'resourceSlots')))
    return '投票不能再设置扣费、次数或效果';
  if (context.runtime && !(context.runtime.runtimeScope === 'TEAM' && context.runtime.worldMode === 'WORLD_SHARED')) {
    if ([rules].concat(context.rules || []).some(rule => rule.scope && rule.scope !== 'OWNER' || rule.targetScope && rule.targetScope !== 'OWNER')) return matrix.constraints.OWNER_OR_TEAM;
    if (context.vote || tools.allowTrade === true || tools.plans?.length || tools.votePolicy || tools.decidesEnding === true) return matrix.constraints.WORLD_SHARED_ONLY;
  }
  return '';
}
function context(runtime, node) {
  runtime = parse(runtime).gameplayRuntime || {};
  const id = Number(node && node.id), zero = runtime.survival && runtime.survival.zeroChallenge || {};
  const challenge = id > 0 && (id === zero.challengeNodeId || (zero.alternativeNodeIds || []).includes(id));
  const rules = (runtime.quests || []).filter(row => id > 0 && Number(row.nodeId) === id)
    .concat((runtime.facilities || []).filter(row => id > 0 && Number(row.operationNodeId) === id));
  const vote = (runtime.plans || []).some(row => id > 0 && Number(row.operationNodeId) === id);
  return { challengeVersion: zero.version, runtime: { runtimeScope: runtime.runtimeScope, worldMode: runtime.worldMode },
    challenge, settled: ['PLAYER', 'TEAM'].includes(runtime.runtimeScope) && rules.length > 0,
    rules: rules.map(row => ({ scope: row.scope, targetScope: row.targetScope })), vote };
}
function choice(matrix, key, advanced, ctx = {}) {
  if (!matrix) return '';
  const row = matrix.types.concat(matrix.addons).find(row => row.key === key);
  if (!row) return '';
  if (ctx.challenge && ctx.challengeVersion === 2 && !ZERO_CHALLENGE_GAMEPLAYS.includes(row.section)) return ZERO_CHALLENGE_REASON;
  if (ctx.challenge) return !['SUPPORTED', 'CHALLENGE_ONLY'].includes(row.tools.zeroChallenge) && !(ctx.challengeVersion === 2 && row.r5CompletionProof)
    ? '这种玩法不能用作归零挑战' : generic(advanced) ? '归零挑战不能再设置扣费、次数或效果' : '';
  if (!ctx.settled && effectOnly(advanced)) return row.effectSlotSupport === false ? '这种玩法暂不支持所需数值资源' : '';
  return (ctx.settled || generic(advanced)) && !(advanced.outcomeRules?.version === 1 ? row.r5CompletionProof : row.completionProof) ? '这种玩法暂时无法确认是否完成' : '';
}
function challengeChoices(matrix, nodes, version) {
  if (!matrix) return { primary: nodes, alternatives: nodes };
  const primary = nodes.filter(node => !validate(matrix, node.template, Object.assign({}, node.context, { challenge: true, challengeVersion: version || node.context?.challengeVersion })));
  const alternatives = primary.filter(node => mains(matrix, config(node.template)).some(row => row.completionProof && row.key !== 'scan'));
  return { primary, alternatives };
}
function challengeError(matrix, nodes, zero) {
  if (!matrix || !zero) return '';
  const choices = challengeChoices(matrix, nodes, zero.version);
  const ids = [zero.challengeNodeId].concat(zero.alternativeNodeIds || []);
  const invalid = ids.find(id => !choices.primary.some(node => node.id === id));
  if (invalid != null) return '节点 ' + invalid + '：这种玩法不能用作归零挑战，或已设置扣费、次数或效果';
  const primary = nodes.find(node => node.id === zero.challengeNodeId);
  const sensor = primary && mains(matrix, config(primary.template)).some(row => row.tools.zeroChallenge === 'CHALLENGE_ONLY' && row.key !== 'typeIn');
  if (sensor && !(zero.alternativeNodeIds || []).some(id => choices.alternatives.some(node => node.id === id)))
    return '节点 ' + zero.challengeNodeId + '：' + matrix.constraints.CHALLENGE_ONLY;
  return '';
}
module.exports = { ZERO_CHALLENGE_GAMEPLAYS, load, parse, config, mains, generic, effectOnly, effectSlotPolicy, zeroHpPolicies, toolPolicy, condition, validate, context, choice, challengeChoices, challengeError };
