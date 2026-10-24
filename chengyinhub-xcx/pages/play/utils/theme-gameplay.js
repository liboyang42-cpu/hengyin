const { reasonText } = require('./theme-plans.js');
const name = (value, fallback) => String(value || '').trim() || fallback;

function labels(view) {
  return { hp: name(view.hpName, '生命值'), luck: name(view.luckName, '幸运值'), action: name(view.actionName, '行动次数') };
}

function valueName(key, view) {
  const words = labels(view);
  if (key === 'HP' || key === 'sys.hp') return words.hp;
  if (key === 'LUCK' || key === 'sys.luck') return words.luck;
  if (key === 'sys.actions') return words.action;
  const attribute = (view.attributes || []).find(row => row.key === key);
  const catalog = view.itemCatalog || view.world && view.world.itemCatalog || [];
  const item = catalog.find(row => row.id === key) || (view.contents || []).find(row => row.id === key);
  return attribute && attribute.label || item && item.name || (String(key || '').startsWith('counter.') ? '数值' : '物资');
}

function amounts(cost, view) {
  if (!cost) return [];
  const out = [];
  for (const [field, key] of [['hpCost', 'sys.hp'], ['luckCost', 'sys.luck'], ['actionCost', 'sys.actions']])
    if (Number(cost[field]) > 0) out.push(valueName(key, view) + ' ' + cost[field]);
  for (const row of cost.statCosts || []) if (Number(row.amount) > 0) out.push(valueName(row.statKey, view) + ' ' + row.amount);
  for (const row of cost.resourceCosts || []) if (Number(row.quantity) > 0) out.push(valueName(row.itemId, view) + ' ×' + row.quantity);
  return out;
}

function hasFees(cost) { return amounts(cost, {}).length > 0; }

function shortfallText(rows, view) {
  return (rows || []).map(part => '缺少' + valueName(part.key, view) + ' ' + part.missing).join(' · ');
}

function options(rows, view, baseCosts) {
  return (rows || []).map((row, index) => ({
    index, label: name(row.label, '方式 ' + (index + 1)), amountText: amounts(baseCosts, view).concat(amounts(row, view)).join(' · '),
    affordable: row.affordable === true,
    shortfallText: Array.isArray(row.shortfall) && row.shortfall.length
      ? shortfallText(row.shortfall, view) : '费用不足',
  }));
}

const reasons = {
  LIMIT_REACHED: '次数已用完', INSUFFICIENT_RESOURCES: '资源不足', NOT_ARRIVED: '尚未到达',
  PROOF_PENDING: '待核验', NOT_CAPTAIN: '仅队长可操作', SESSION_ENDED: '本局已结束',
  CHALLENGE_LIMIT: '挑战次数已用完', TEAM_COMPLETED: '本队已完成', COUNTERPART_COMPLETED: '对方已完成',
  RULE_BLOCKED: '前置条件未满足', TASK_ACTIVE: '请先完成或取消当前任务', SESSION_PAUSED: '本局已暂停',
  WORLD_LOCKED: '当前表决已锁定',
};

function toolStatus(source, view, selected) {
  let disabledText = reasons[source.disabledReason] || source.disabledMessage || source.disabledReason || '';
  if (source.disabledReason === 'INSUFFICIENT_RESOURCES') {
    const groups = Number.isInteger(selected) && selected >= 0 ? [source.costOptions && source.costOptions[selected]] : source.costOptions || [];
    const missing = shortfallText(source.shortfall, view) || groups.filter(row => row && row.affordable === false && row.shortfall && row.shortfall.length)
      .map(row => name(row.label, '费用') + '：' + shortfallText(row.shortfall, view)).join('；');
    if (missing) disabledText += ' · ' + missing;
  }
  const scope = source.countScope === 'WORLD' ? '全场' : source.countScope === 'OWNER' ? view.scope === 'TEAM' ? '本队' : '本人' : '';
  return { limit: source.limit, count: source.count, remainingCount: source.remainingCount, countScope: source.countScope,
    trustedPass: source.trustedPass === true, disabledReason: source.disabledReason, disabledText,
    remainingText: scope && Number.isSafeInteger(source.remainingCount) && source.remainingCount >= 0 ? '剩 ' + source.remainingCount + ' 次（' + scope + '）' : '' };
}

function actionRows(view, nodes) {
  const personal = view.scope !== 'TEAM';
  const available = personal ? view.allowedActions || [] : view.availableActions || [];
  const activeQuest = personal ? view.activeQuest : view.gameplay && view.gameplay.activeQuest;
  const frozenQuest = personal ? view.activeQuestCostOption : view.gameplay && view.gameplay.activeQuestCostOption;
  const activeOperation = view.activeOperation || {};
  const nodeName = id => ((nodes || []).find(node => Number(node.nodeId) === Number(id)) || {}).name || '本站任务';
  const allowed = (operation, key) => personal
    ? available.find(action => action.operation === operation && action.contentId === key)
    : available.includes(operation);
  const previewAllowed = (operation, key) => personal && available.some(action => action.operation === 'GAMEPLAY_PREVIEW' && action.resolvesOperation === operation && action.contentId === key);
  const ended = view.claimOnly === true || view.status === 'COMPLETED' || !!view.result || view.naturallyCompleted === true;
  const rows = [];
  for (const quest of view.quests || []) {
    const key = quest.questKey;
    const active = activeQuest === key;
    const summary = typeof quest.enabled === 'boolean' ? quest : quest.actionSummary || quest;
    const hasState = typeof summary.enabled === 'boolean';
    const operation = hasState ? summary.operation : active ? allowed('QUEST_RESOLVE', key) || previewAllowed('QUEST_RESOLVE', key) ? 'QUEST_RESOLVE' : 'OPEN_QUEST' : 'QUEST_START';
    const source = hasState ? summary : personal ? allowed(operation, key) || summary : summary;
    const status = toolStatus(source, view, active ? frozenQuest : undefined);
    const proofPending = source.disabledReason === 'PROOF_PENDING';
    const costOptions = options(source.costOptions || quest.costOptions, view, source.costs);
    const blocked = !hasState && !active && view.awaitingChallenge && (costOptions.length > 0 || hasFees(source.costs));
    rows.push({ ...status, kind: 'QUEST', key, nodeId: quest.nodeId, title: quest.title || nodeName(quest.nodeId), operation,
      actionText: proofPending ? '待审核' : operation === 'QUEST_START' ? '开始任务' : operation === 'QUEST_RESOLVE' ? source.trustedPass === true ? '结算任务' : '完成任务' : '打开任务',
      proofText: source.trustedPass === true ? source.enabled === true ? '凭证已通过，可结算' : '凭证已通过，当前不可结算' : '',
      openable: !ended && active && proofPending && source.trustedPass === false,
      enabled: !ended && (!hasState || source.enabled === true) && (operation === 'OPEN_QUEST' || !!allowed(operation, key) || previewAllowed(operation, key)) && !blocked, blocked, costOptions, frozenIndex: active && Number.isInteger(frozenQuest) ? frozenQuest : -1,
      choices: personal ? [...new Set(available.filter(action => action.contentId === key && ['QUEST_RESOLVE', 'GAMEPLAY_PREVIEW'].includes(action.operation) && action.choiceKey).map(action => action.choiceKey))].map((choiceKey, index) => ({ choiceKey, label: '方案 ' + (index + 1) })) : (quest.choices || []).map((choice, index) => ({ choiceKey: choice.choiceKey, label: choice.label || choice.title || '方案 ' + (index + 1) })),
      disabledText: proofPending ? '待审核 / 凭证未通过，请打开原任务确认' : status.disabledText || (ended ? '本局已结束，仅可领取待领奖励' : ''),
      frozenText: active && costOptions[frozenQuest] ? costOptions[frozenQuest].label + ' · ' + costOptions[frozenQuest].amountText : '',
      chargeNote: !ended && active ? '任务进行中不扣费用，结算时才按实际扣除' : '',
      cancelable: !ended && active && !!allowed('QUEST_CANCEL', key) });
  }
  const tools = personal ? view.tools || [] : (view.interactionTools || []).filter(tool => tool.kind === 'FACILITY');
  for (const tool of tools) {
    const key = tool.facilityKey || tool.operationKey;
    const active = activeOperation.operationKey === key;
    const verified = typeof tool.trustedPass === 'boolean' ? tool.trustedPass : activeOperation.verified;
    const operation = active ? verified ? 'FACILITY_USE' : 'OPEN_OPERATION'
      : tool.operationNodeId ? 'OPERATION_START' : 'FACILITY_USE';
    const source = typeof tool.enabled === 'boolean' ? tool : personal ? allowed(operation, key) || tool : tool;
    const serverState = typeof source.enabled === 'boolean';
    const status = toolStatus(source, view, active ? activeOperation.costOption : undefined);
    const costOptions = options(source.costOptions || tool.costOptions, view, source.costs);
    const blocked = !serverState && !active && view.awaitingChallenge && (costOptions.length > 0 || hasFees(source.costs));
    rows.push({ ...status, kind: 'FACILITY', key, nodeId: active && activeOperation.nodeId || tool.operationNodeId || tool.nodeId, title: tool.title || '本站工具', operation,
      targetScope: (tool.actionSummary || tool).targetScope, participantCatalog: (tool.actionSummary || tool).participantCatalog, payerRequired: (tool.actionSummary || tool).payerRequired,
      actionText: operation === 'OPERATION_START' ? '开始工具任务' : operation === 'OPEN_OPERATION' ? '打开工具任务' : '使用工具',
      enabled: serverState ? source.enabled : !ended && (operation === 'OPEN_OPERATION' || !!allowed(operation, key) || previewAllowed(operation, key)) && !blocked, openable: !ended && serverState && active && source.disabledReason === 'PROOF_PENDING' && source.trustedPass === false && operation === 'OPEN_OPERATION', blocked, costOptions,
      disabledText: status.disabledText || (ended ? '本局已结束，仅可领取待领奖励' : ''),
      frozenIndex: active && Number.isInteger(activeOperation.costOption) ? activeOperation.costOption : -1,
      frozenText: active && costOptions[activeOperation.costOption] ? costOptions[activeOperation.costOption].label + ' · ' + costOptions[activeOperation.costOption].amountText : '',
      cancelable: !ended && active && !!allowed('OPERATION_CANCEL', key) });
  }
  for (const recovery of view.recoveries || []) {
    const key = recovery.recoveryKey || recovery.operationKey;
    const source = recovery.actionSummary || recovery;
    const status = toolStatus(source, view);
    rows.push({ ...status, kind: 'RECOVERY', key, title: recovery.title || recovery.name || (recovery.kind === 'RESCUE' ? '救援' : '恢复'),
      operation: 'RECOVER', actionText: '恢复', enabled: !ended && source.enabled === true, costOptions: [], frozenIndex: -1,
      costText: amounts(source.costs, view).join(' · '), cancelable: false,
      disabledText: status.disabledText || (ended ? '本局已结束，仅可领取待领奖励' : '') });
  }
  for (const row of rows) row.rowKey = row.kind + ':' + row.key;
  return rows;
}

// 任务回执：开始任务不预留、不扣费；只有 QUEST_RESOLVE 真实结算（未被拦截）后，才按结算输出列出已扣数额。
function questReceiptText(operation, result, view) {
  result = result || {};
  if (operation === 'QUEST_CANCEL') return result.cancelledQuest ? '任务已取消，未扣除任何费用' : '';
  if (operation !== 'QUEST_RESOLVE' || result.settled === false || result.status === 'BLOCKED_COST' || result.competitionLost === true
    || typeof result.settlementKey !== 'string' || !result.settlementKey.startsWith('quest:')) return '';
  const parts = [];
  for (const [key, delta] of Object.entries(result.statDeltas || {})) if (Number.isSafeInteger(delta) && delta < 0) parts.push(valueName(key, view) + ' ' + -delta);
  for (const [key, quantity] of Object.entries(result.resourceCosts || {})) if (Number.isSafeInteger(quantity) && quantity > 0) parts.push(valueName(key, view) + ' ×' + quantity);
  return parts.length ? '任务已结算，本次实际扣除：' + parts.join(' · ') : '';
}

function participantChoice(view, input) {
  const row = (view.actionRows || []).find(row => row.kind === 'FACILITY' && row.key === input.contentId);
  if (view.scope !== 'TEAM' || input.operation !== 'FACILITY_USE' || !row || row.targetScope !== 'PARTICIPANTS') return {};
  if (!Array.isArray(row.participantCatalog) || row.payerRequired !== true) return { error: '参与队伍目录尚未加载，请刷新后重试' };
  const ids = input.participants || [];
  const rows = row.participantCatalog.map(team => ({ ...team, selected: Array.isArray(ids) && ids.includes(team.teamId),
    required: row.payerRequired && team.teamId === view.teamId, label: team.teamName || '队伍 ' + team.teamId, reason: team.disabledReason === 'TEAM_COMPLETED' ? '该队已完成' : reasons[team.disabledReason] || team.disabledReason || '' }));
  let error = '';
  if (!Array.isArray(ids) || !ids.length) error = '请选择参与队伍';
  else if (new Set(ids).size !== ids.length || ids.some(id => !Number.isSafeInteger(id) || id <= 0 || !rows.some(team => team.teamId === id && team.eligible === true))) error = '参与队伍不可用，请重新选择';
  else if (row.payerRequired && !ids.includes(view.teamId)) error = '付费的本队必须参与';
  return { rows, participants: ids, payerRequired: row.payerRequired, error };
}

module.exports = { labels, valueName, amounts, options, toolStatus, actionRows, participantChoice, questReceiptText };
