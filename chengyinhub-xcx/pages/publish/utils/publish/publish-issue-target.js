'use strict';

const binding = require('./template-binding.js');

function parse(raw) {
  try { return raw ? (typeof raw === 'string' ? JSON.parse(raw) : raw) : {}; }
  catch (_) { return {}; }
}

function nodes(chapters) {
  return (chapters || []).flatMap((chapter, chapterIndex) => ((chapter && chapter.nodes) || [])
    .map((node, nodeIndex) => ({ node, chapterIndex, nodeIndex, key: binding.nodeKey(node) })));
}

function configOf(row, details) {
  const template = (details || {})[row.node.templateId] || row.node.templateInfo;
  return parse(template && template.advancedConfigJson);
}

function templateField(message) {
  const msg = String(message || '');
  if (/costOptions|任选费用|固定费用|hpCost|luckCost|actionCost|statCosts|resourceCosts/.test(msg)) return { field: 'advanced', tool: 'cost' };
  if (/投票|提案|方案\s+[a-z][a-z0-9_]*|decidesEnding|endingKey|votePolicy|materialCost/.test(msg)) return { field: 'advanced', tool: 'vote' };
  if (/allowTrade|物资交换/.test(msg)) return { field: 'advanced', tool: 'trade' };
  if (/limitScope|成功使用次数/.test(msg)) return { field: 'advanced', tool: 'uses' };
  if (/targetScope|影响范围/.test(msg)) return { field: 'advanced' };
  if (/资源槽|resourceSlots|数值变化|效果规则|interactionRules|interactionTools|高级配置/.test(msg)) return { field: 'advanced' };
  if (/传感器|滤镜|验证方式|sensorType|sensorConfig|validationMethod/.test(msg)) return { field: 'finish' };
  if (/rewardRulesJson|奖励规则/.test(msg)) return { field: 'reward' };
  if (/storyImg|storyJson/.test(msg)) return { field: 'story' };
  return null;
}

function fromMessage(message, chapters, details) {
  const msg = String(message || '');
  const all = nodes(chapters);
  const slot = /节点\s+(client:[a-zA-Z0-9_-]{1,64}|[1-9]\d*)\s+资源槽\s+([a-z][a-z0-9_.]{0,47})/.exec(msg);
  if (slot && all.some(row => row.key === slot[1]))
    return { label: msg, field: 'templateBinding:' + slot[1] + ':' + slot[2], tab: '0' };
  const chapter = /章节\s+[^；]*?index=(\d+)/.exec(msg);
  if (chapter && Number(chapter[1]) < (chapters || []).length)
    return { label: msg, field: 'chapterRuntime:' + chapter[1], tab: '0' };
  if (/zeroChallenge|归零挑战|挑战站点|备选挑战/.test(msg))
    return { label: msg, field: 'themeState', tab: '2' };
  const nodeError = /节点\s+([1-9]\d*)[：:]\s*(.*)/.exec(msg);
  if (nodeError) {
    const row = all.find(item => item.key === nodeError[1]);
    const target = templateField(nodeError[2]);
    if (row && target) return { label: msg, field: 'templateTool:' + row.key + ':' + (target.tool || 'advanced'), tab: '0' };
  }
  const plan = /方案\s+([a-z][a-z0-9_]{0,47})\s+须有对应结局/.exec(msg);
  if (plan) {
    const generated = /^tpl_vote_([1-9]\d*)_([1-9]|[12]\d|30)$/.exec(plan[1]);
    const matches = all.filter(row => {
      const tools = configOf(row, details).interactionTools || {};
      return Array.isArray(tools.plans) && (generated
        ? row.key === generated[1]
        : tools.plans.some(item => item.endingKey === plan[1]));
    });
    if (matches.length === 1)
      return { label: msg, field: 'templateTool:' + matches[0].key + ':vote:' + plan[1], tab: '0' };
  }
  if (/costOptions|任选费用/.test(msg)) {
    const matches = all.filter(row => Array.isArray((configOf(row, details).interactionRules || {}).costOptions));
    if (matches.length === 1)
      return { label: msg, field: 'templateTool:' + matches[0].key + ':cost', tab: '0' };
  }
  return null;
}

function costIssue(config) {
  const rules = config && config.interactionRules;
  if (!rules || !Object.prototype.hasOwnProperty.call(rules, 'costOptions')) return '';
  const options = rules.costOptions;
  if (!Array.isArray(options) || options.length < 2 || options.length > 4) return 'costOptions 须为 2 至 4 组';
  if (['hpCost', 'luckCost', 'actionCost', 'statCosts', 'resourceCosts'].some(key => Object.prototype.hasOwnProperty.call(rules, key)))
    return '任选费用与固定费用不能同时配置';
  if (options.some(group => !group || Object.keys(group).some(key =>
    !['label', 'hpCost', 'luckCost', 'actionCost', 'statCosts', 'resourceCosts'].includes(key))
    || group.label != null && (typeof group.label !== 'string' || group.label.length > 20)
    || ['hpCost', 'luckCost', 'actionCost'].some(key => group[key] != null
      && (!Number.isInteger(group[key]) || group[key] < 0 || group[key] > 999))))
    return 'costOptions 费用字段不合法';
  if (options.some(group => ['statCosts', 'resourceCosts'].some(key => group[key] != null
    && (!Array.isArray(group[key]) || group[key].some(row => !row || !Number.isInteger(row[key === 'statCosts' ? 'amount' : 'quantity'])
      || row[key === 'statCosts' ? 'amount' : 'quantity'] < (key === 'statCosts' ? 0 : 1)
      || row[key === 'statCosts' ? 'amount' : 'quantity'] > 999)))))
    return 'costOptions 费用数量不合法';
  if (options.some(group => !group || !['hpCost', 'luckCost', 'actionCost'].some(key => Number(group[key]) > 0)
    && !(Array.isArray(group.statCosts) ? group.statCosts : []).some(row => row && Number(row.amount) > 0)
    && !(Array.isArray(group.resourceCosts) ? group.resourceCosts : []).some(row => row && Number(row.quantity) > 0)))
    return 'costOptions 每组至少一项正数费用';
  return '';
}

function planIssue(runtime) {
  const plans = (((runtime || {}).gameplayRuntime || {}).plans || []);
  const endings = (runtime || {}).endings || [];
  if (!Array.isArray(plans) || !Array.isArray(endings)) return '';
  const fallback = endings.filter(row => row && row.fallback === true).length;
  const plan = plans.find(row => row && row.terminal === true
    && (!endings.some(ending => ending.id === row.endingKey) || fallback !== 1));
  return plan ? '方案 ' + plan.endingKey + ' 须有对应结局和唯一默认收尾' : '';
}

// N011：作者运行检查报告合同。结构问题带可跳回的定位；规则样例按服务端同一公式（骰点+静态加值≥DC，无大成功/大失败）
// 枚举有限边界；未覆盖范围永远列出 —— 结构全过也不宣称全路径可玩。
const UNCOVERED = ['数值、消耗与互斥组合的全部路径未模拟', '任意复杂脚本与多人并发未穷举', '本地样例不代表正式运行时同一发布版本的结果'];
function d20Sample(row, dice) {
  const dc = Number(dice.dc) || 0, modifier = Number(dice.modifier) || 0, need = dc - modifier;
  const text = need > 20 ? '任何骰点都失败' : need <= 1 ? '任何骰点都成功' : '骰出 ' + need + ' 及以上成功';
  return { nodeKey: row.key, kind: 'D20', field: 'templateTool:' + row.key + ':advanced', tab: '0',
    text: 'DC ' + dc + '，静态加值 ' + modifier + '：' + text, warning: need > 20 || need <= 1 };
}
function checkReport(input) {
  const { messages, chapters, details, version } = input || {};
  const issues = (messages || []).filter(Boolean).map(message => {
    const target = fromMessage(message, chapters, details);
    return { message: String(message), target, located: !!target };
  });
  const samples = [];
  for (const row of nodes(chapters)) {
    const config = configOf(row, details);
    const cost = costIssue(config);
    if (cost) issues.push({ message: '节点 ' + row.key + '：' + cost, target: { label: cost, field: 'templateTool:' + row.key + ':cost', tab: '0' }, located: true });
    const dice = config.diceRoll;
    if (dice && dice.enabled === true && dice.mode === 'd20') samples.push(d20Sample(row, dice));
  }
  return { version: version == null ? null : version, structurePassed: issues.length === 0, issues, samples,
    uncovered: UNCOVERED.slice(), fullPathGuarantee: false,
    summary: issues.length ? issues.length + ' 处待修，点按可跳回对应位置' : '结构检查通过；下列未覆盖范围仍需实跑，不代表全路径可玩' };
}

module.exports = { fromMessage, templateField, costIssue, planIssue, parse, checkReport };
