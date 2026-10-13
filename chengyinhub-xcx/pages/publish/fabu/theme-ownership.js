const binding = require('../utils/publish/template-binding.js');
const copy = value => JSON.parse(JSON.stringify(value));

function legacySummary(config) {
  const game = config.gameplayRuntime || {}, survival = game.survival || {}, items = [];
  for (const [kind, label] of [['REST', '休整'], ['RESCUE', '救助']]) {
    const count = (survival.recoveries || []).filter(row => row.kind === kind).length;
    if (count) items.push(label + ' ' + count);
  }
  if (survival.rations?.length) items.push('配给 ' + survival.rations.length);
  const facilities = (game.facilities || []).filter(row => !/^tpl_node_[1-9][0-9]*$/.test(row.facilityKey));
  if (facilities.length) items.push('设施 ' + facilities.length);
  const endings = (config.endings || []).length + (config.scenes || []).reduce((n, scene) => n + (scene.endings || []).length, 0);
  if (endings) items.push('运行结局 ' + endings);
  return { hasLegacy: items.length > 0, items, text: items.join(' · ') };
}
function terminalPlans(template) {
  const tools = binding.parse(template?.advancedConfigJson).interactionTools || {};
  return tools.decidesEnding === true ? (tools.plans || []).map((plan, i) => Object.assign({}, plan, { planKey: plan.planKey || String(i + 1) })) : [];
}
function planDigest(template) {
  const plans = binding.parse(template?.advancedConfigJson).interactionTools?.plans || [];
  return binding.sha256(JSON.stringify({ count: plans.length,
    plans: plans.map(plan => ({ planKey: plan.planKey || '', title: plan.title || '' })) }));
}
function effectiveTemplate(config, node, source) {
  const scene = (config.scenes || []).find(row => row.id === config.currentScene);
  const override = scene?.nodeConfigs?.[binding.nodeKey(node)];
  return override ? { ...source, advancedConfigJson: override } : source;
}
function endingOptions(form) {
  const story = binding.parse(form.journeyStory), chapters = form.chapters || [];
  return (story.ending?.endings || []).filter(row => row.chapterId != null
    && chapters.some(ch => String(ch.id) === String(row.chapterId) && ch.ending))
    .map(row => ({ value: String(row.chapterId), label: row.title || chapters.find(ch => String(ch.id) === String(row.chapterId)).name || '结局' }));
}
function withPlanEndings(template, current, previous) {
  if (!previous?.planEndings) return current;
  const next = current || binding.reconcile([], template.id, null, [], '', '', template);
  return Object.assign({}, next, { planEndings: copy(previous.planEndings), ...(previous.planDigest ? { planDigest: previous.planDigest } : {}) });
}
/** 最终章：章节顺序中最后一个非结局章。决定结局的表决默认在这里收场，结果内容取各方案本身。 */
function finalChapterIndex(form) {
  const chapters = form.chapters || [];
  for (let i = chapters.length - 1; i >= 0; i -= 1) if (!chapters[i].ending) return i;
  return -1;
}
function planIssue(template, selected, form, config, chapterIndex) {
  const options = endingOptions(form);
  if (terminalPlans(template).length && selected?.planEndings && selected.planDigest !== planDigest(template))
    return { planKey: terminalPlans(template)[0].planKey, error: '表决方案已修改，请重新选择各方案的结局章' };
  for (const plan of terminalPlans(template)) {
    const chosen = selected?.planEndings?.[plan.planKey];
    if (chosen && options.some(row => row.value === String(chosen))) continue;
    // 已保存的旧终局表决保持原合同。
    if (!chosen && plan.endingKey && (config.endings || []).some(row => row.id === plan.endingKey)
      && (config.endings || []).filter(row => row.fallback === true).length === 1) continue;
    // 未单独链接结局章：在最终章收场，不要求每个方案各建一章。
    if (!chosen && !plan.endingKey && (chapterIndex == null || chapterIndex === finalChapterIndex(form))) continue;
    if (!chosen && !plan.endingKey) return { planKey: plan.planKey, error: '决定结局的表决须放在最终章' };
    return { planKey: plan.endingKey || plan.planKey, error: '请为「' + (plan.title || '表决方案') + '」选择已保存的结局章' };
  }
  return null;
}
function copyReferences(config, notices) {
  for (const row of Object.values(config.templateBindings || {})) {
    if (!Object.keys(row.planEndings || {}).length) continue;
    delete row.planEndings;
    delete row.planDigest;
    if (!notices.includes('终局表决的结局章')) notices.push('终局表决的结局章');
  }
}
const VOTE_POLICIES = [{ key: 'MAJORITY', label: '过半数' }, { key: 'UNANIMOUS', label: '全体同意' }, { key: 'MAJORITY_ACCEPTANCE', label: '过半接受（平票可复议）' }];
const data = { themeWorldShared: false, themeAllowTrade: false, themeVotePolicies: VOTE_POLICIES, themeVotePolicyIndex: 0,
  legacyRuntimeSummary: { hasLegacy: false, items: [], text: '' }, runtimeConsumeOnNoEffect: false,
  runtimeConsumeOnNoEffectDisabledReason: '', nodePlanEndingRows: [], nodePlanEndingError: '' };
const methods = {
  _refreshThemeOwnership(config = this._runtimeConfig()) {
    const game = config.gameplayRuntime || {};
    this.setData({ themeWorldShared: game.runtimeScope === 'TEAM' && game.worldMode === 'WORLD_SHARED', themeAllowTrade: game.allowTrade === true,
      themeVotePolicyIndex: Math.max(0, VOTE_POLICIES.findIndex(row => row.key === game.votePolicy)),
      legacyRuntimeSummary: legacySummary(config),
      runtimeConsumeOnNoEffect: config.gameplayRuntime?.survival?.consumeOnNoEffect === true,
      runtimeConsumeOnNoEffectDisabledReason: config.gameplayRuntime?.runtimeScope ? '' : '请先选择进行方式' });
  },
  onThemeConsumeOnNoEffect(e) {
    const config = this._runtimeConfig(), game = config.gameplayRuntime, value = e.detail.value === true;
    if (!game?.runtimeScope) { this._refreshThemeOwnership(config); return false; }
    if (value) { game.survival = game.survival || {}; game.survival.consumeOnNoEffect = true; }
    else if (game.survival && Object.prototype.hasOwnProperty.call(game.survival, 'consumeOnNoEffect')) game.survival.consumeOnNoEffect = false;
    this._saveRuntime(config); this._refreshThemeOwnership(config); return true;
  },
  /** 队伍之间交换物资（主题级，只在队伍共用现场时可开）；写既有 gameplayRuntime.allowTrade。 */
  onThemeAllowTrade(e) {
    const config = this._runtimeConfig(), game = config.gameplayRuntime;
    if (!game || game.runtimeScope !== 'TEAM' || game.worldMode !== 'WORLD_SHARED') { this._refreshThemeOwnership(config); return false; }
    if (e.detail.value === true) game.allowTrade = true; else if (Object.prototype.hasOwnProperty.call(game, 'allowTrade')) game.allowTrade = false;
    this._saveRuntime(config); this._refreshThemeOwnership(config); return true;
  },
  /** 表决方式是整场共享规则；写既有 gameplayRuntime.votePolicy。 */
  onThemeVotePolicy(e) {
    const config = this._runtimeConfig(), game = config.gameplayRuntime, row = VOTE_POLICIES[Number(e.detail.value)];
    if (!row || !game || game.worldMode !== 'WORLD_SHARED') { this._refreshThemeOwnership(config); return false; }
    game.votePolicy = row.key; this._saveRuntime(config); this._refreshThemeOwnership(config); return true;
  },
  _openNodePlanEndings(template, previous) {
    template = effectiveTemplate(this._runtimeConfig(), this.data.nodesForm, template);
    const selected = withPlanEndings(template, this._nodeBindingDraft, previous), options = endingOptions(this.data.formData);
    const stale = selected?.planEndings && selected.planDigest !== planDigest(template);
    this._nodeBindingDraft = selected;
    this.setData({ nodePlanEndingError: stale ? '表决方案已修改，请重新选择各方案的结局章' : '', nodePlanEndingRows: terminalPlans(template).map(plan => {
      const value = selected?.planEndings?.[plan.planKey] || '';
      return { planKey: plan.planKey, label: plan.title || '表决方案', value, options,
        targetLabel: options.find(row => row.value === String(value))?.label || '最终章' };
    }) });
  },
  onNodePlanEnding(e) {
    const row = this.data.nodePlanEndingRows[Number(e.currentTarget.dataset.index)];
    if (!row) return;
    const option = row.options.find(o => o.value === String(e.detail.value)) || row.options[Number(e.detail.value)];
    if (!option) { this.setData({ nodePlanEndingError: '请先保存结局章，再选择表决结果' }); return false; }
    const template = effectiveTemplate(this._runtimeConfig(), this.data.nodesForm, this.data.nodesForm.templateInfo);
    const selected = this._nodeBindingDraft || binding.reconcile([], template.id, null, [], '', '', template);
    const digest = planDigest(template), previous = selected.planDigest === digest ? selected.planEndings : {};
    const next = Object.assign({}, selected, { planDigest: digest, planEndings: Object.assign({}, previous, { [row.planKey]: option.value }) });
    this._nodeBindingDraft = next; this._openNodePlanEndings(template, next); return true;
  },
  _nodePlanEndingIssues() {
    const form = this.data.formData, config = binding.parse(form.themeRuntimeConfig), issues = [];
    (form.chapters || []).forEach((ch, chapterIndex) => (ch.nodes || []).forEach(node => {
      const source = (this._templateDetailsById || {})[node.templateId] || node.templateInfo;
      const key = binding.nodeKey(node), template = effectiveTemplate(config, node, source);
      const issue = planIssue(template, config.templateBindings?.[key], form, config, chapterIndex);
      if (issue) issues.push({ label: (node.name || '站点') + '：' + issue.error,
        field: 'templateTool:' + key + ':vote:' + issue.planKey, tab: '0' });
    }));
    return issues;
  },
};
module.exports = { VOTE_POLICIES, data, methods, legacySummary, terminalPlans, planDigest, endingOptions, withPlanEndings, planIssue, copyReferences, finalChapterIndex };
