const initial = require('../utils/publish/story-initial-state.js');
const matrix = require('../utils/publish/gameplay-support-matrix.js');
const clone = value => JSON.parse(JSON.stringify(value));
const OPTIONS = [{ key: 'BLOCK_HP_COST', label: '仅禁止扣该值的任务', sub: '其他任务仍可参与' }, { key: 'THEME_FAIL', label: '主题失败', sub: '本次主题结束' }, { key: 'CHALLENGE', label: '完成恢复挑战', sub: '完成指定站点后恢复' }, { key: 'STEPS', label: '走够步数', sub: '达到恢复步数后恢复' }, { key: 'WAIT', label: '等待一段时间', sub: '等待指定分钟后恢复' }];
const THEN = [{ key: 'THEME_FAIL', label: '主题失败' }, { key: 'BLOCK_HP_COST', label: '仅禁止扣该值的任务' }];
const BLOCK = { CHALLENGE: 'zeroChallenge', STEPS: 'zeroSteps', WAIT: 'zeroWait' };
const FIELD = { CHALLENGE: 'challenge', STEPS: 'steps', WAIT: 'wait' };
const integer = (value, min, max) => Number.isInteger(value) && value >= min && value <= max;
function read(config) {
  const survival = config.gameplayRuntime?.survival || {}, policy = survival.zeroHpPolicy || 'BLOCK_HP_COST';
  return { policy, zeroMessage: survival.zeroMessage || '', challenge: clone(survival.zeroChallenge || { version: 2, gameplayKey: '', challengeNodeId: null, alternativeNodeIds: [], requiredCompletions: 1, withinSeconds: 60, restoreTo: 1, limit: 1, thenPolicy: 'THEME_FAIL' }), steps: clone(survival.zeroSteps || { version: 1, steps: 2000, restoreTo: 1, limit: 1, thenPolicy: 'THEME_FAIL' }), wait: clone(survival.zeroWait || { version: 1, minutes: 30, restoreTo: 1, limit: 1, thenPolicy: 'THEME_FAIL' }) };
}
function readOnly(config) {
  const survival = config.gameplayRuntime?.survival || {};
  return ['BLOCK_PAID', 'LEGACY_END'].includes(survival.zeroHpPolicy) || survival.zeroHpPolicy === 'CHALLENGE' && survival.zeroChallenge?.version !== 2;
}
function apply(config, draft) {
  if (readOnly(config) || JSON.stringify(read(config)) === JSON.stringify(draft)) return clone(config);
  const out = clone(config); out.gameplayRuntime = out.gameplayRuntime || {}; out.gameplayRuntime.survival = out.gameplayRuntime.survival || {};
  const survival = out.gameplayRuntime.survival;
  survival.zeroHpPolicy = draft.policy; survival.zeroMessage = draft.zeroMessage;
  for (const [policy, field] of Object.entries(BLOCK)) {
    if (policy === draft.policy) survival[field] = clone(draft[FIELD[policy]]);
    else delete survival[field];
  }
  if (!draft.zeroMessage) delete survival.zeroMessage;
  if (draft.policy === 'BLOCK_HP_COST' && Object.keys(survival).length === 1) delete out.gameplayRuntime.survival;
  if (!Object.keys(out.gameplayRuntime).length) delete out.gameplayRuntime;
  return out;
}
function validate(config, rules, policies, nodes, support) {
  const survival = config.gameplayRuntime?.survival; if (!survival) return '';
  const policy = survival.zeroHpPolicy || 'BLOCK_HP_COST';
  if (survival.zeroMessage != null && (typeof survival.zeroMessage !== 'string' || survival.zeroMessage.length > 2000)) return '归零时回复不能超过 2000 字';
  if (Array.isArray(policies) && !policies.length) return '玩法支持矩阵未加载，请稍后重试';
  if (policies && !policies.includes(policy)) return '当前主题不支持此归零策略，请重新选择';
  if (['BLOCK_PAID', 'LEGACY_END'].includes(policy)) return '';
  if (policy === 'CHALLENGE' && survival.zeroChallenge?.version == null) {
    const row = survival.zeroChallenge;
    if (!integer(row.challengeNodeId, 1, Number.MAX_SAFE_INTEGER)) return '请先保存主题和挑战站点，再选择主挑战';
    if (!Array.isArray(row.alternativeNodeIds) || row.alternativeNodeIds.length > 3 || new Set(row.alternativeNodeIds).size !== row.alternativeNodeIds.length || row.alternativeNodeIds.some(id => !integer(id, 1, Number.MAX_SAFE_INTEGER) || id === row.challengeNodeId)) return '备选挑战最多 3 个，且不能与主挑战重复';
    if (!rules?.hp?.enabled) return '请先开启' + (config.hpName || '生命值');
    if (!integer(row.restoreTo, 1, rules.hp.max)) return '恢复值须在 1 到' + (config.hpName || '生命值') + '上限之间';
    if (!integer(row.limit, 1, 99)) return '挑战次数须在 1 到 99 之间';
    return '';
  }
  if (!OPTIONS.some(row => row.key === policy)) return '请选择有效的 HP 归零策略';
  // 未启用生命时，缺省的「仅禁止扣该值的任务」不起作用，也不该要求先开启生命（例如只用自定义数值的主题）
  if (!rules?.hp?.enabled) return policy === 'BLOCK_HP_COST' ? '' : '请先开启' + (config.hpName || '生命值');
  for (const [key, block] of Object.entries(BLOCK)) if (key !== policy && survival[block] != null) return '归零策略只能配置所选恢复参数';
  if (!BLOCK[policy]) return '';
  const row = survival[BLOCK[policy]];
  if (!row || typeof row !== 'object' || Array.isArray(row)) return '请配置恢复条件';
  if (row.version !== (policy === 'CHALLENGE' ? 2 : 1)) return '恢复配置版本不受支持';
  if (!integer(row.restoreTo, 1, rules.hp.max)) return '恢复值须在 1 到' + (config.hpName || '生命值') + '上限之间';
  if (!integer(row.limit, 1, 99)) return '最多恢复次数须为 1 至 99 的整数';
  if (!THEN.some(option => option.key === row.thenPolicy)) return '请选择次数用完后的策略';
  if (policy === 'WAIT' && !integer(row.minutes, 1, 10080)) return '等待分钟须为 1 至 10080 的整数';
  if (policy === 'STEPS' && !integer(row.steps, 1, 100000)) return '恢复步数须为 1 至 100000 的整数';
  if (policy === 'CHALLENGE') {
    if (!integer(row.requiredCompletions, 1, 99)) return '需完成次数须为 1 至 99 的整数';
    if (!integer(row.withinSeconds, 1, 86400)) return '恢复挑战限时须为 1 至 86400 秒';
    if (!integer(row.challengeNodeId, 1, Number.MAX_SAFE_INTEGER)) return '请先保存主题和挑战站点，再选择主挑战';
    if (!Array.isArray(row.alternativeNodeIds) || row.alternativeNodeIds.length > 3 || new Set(row.alternativeNodeIds).size !== row.alternativeNodeIds.length || row.alternativeNodeIds.some(id => !integer(id, 1, Number.MAX_SAFE_INTEGER) || id === row.challengeNodeId)) return '备选挑战最多 3 个，且不能与主挑战重复';
    if (!matrix.ZERO_CHALLENGE_GAMEPLAYS.includes(row.gameplayKey)) return '请选择恢复玩法';
    if (nodes) {
      const main = nodes.find(node => node.id === row.challengeNodeId);
      if (!main || row.alternativeNodeIds.some(id => !nodes.some(node => node.id === id))) return '请选择主题已有真实挑战站点';
      const advanced = matrix.config(main.template);
      if (!advanced[row.gameplayKey]?.enabled) return '恢复玩法须匹配主挑战站点';
      if (row.gameplayKey === 'check' && advanced.outcomeRules?.version !== 1) return '检定恢复须先配置通过/没通过之后';
      const issue = matrix.challengeError(support, nodes, row); if (issue) return issue;
    }
  }
  return '';
}
function options(policies) { return OPTIONS.map(row => Object.assign({}, row, { disabled: !policies.includes(row.key), reason: policies.includes(row.key) ? '' : row.key === 'STEPS' ? '微信运动恢复暂未开放' : '当前主题暂不支持，请加载玩法支持信息' })); }
function summary(config) {
  const draft = read(config), label = OPTIONS.find(row => row.key === draft.policy)?.label || ({ BLOCK_PAID: '禁止所有收费动作', LEGACY_END: '旧版结束' })[draft.policy] || draft.policy;
  const condition = draft.policy === 'WAIT' ? draft.wait.minutes + ' 分钟' : draft.policy === 'STEPS' ? draft.steps.steps + ' 步' : draft.policy === 'CHALLENGE' ? '站点 ' + (draft.challenge.challengeNodeId || '未选择') : '';
  return [label, condition, draft.zeroMessage].filter(Boolean).join(' · ');
}
const data = { runtimeZeroHpSummary: '', runtimeZeroHpOptions: [], runtimeZeroThenOptions: THEN, runtimeZeroEditor: { show: false, draft: null, error: '', readOnly: false } };
const methods = {
  _refreshZeroHpEditor() {
    const nodes = this._runtimeSavedNodes(), draft = this.data.runtimeZeroEditor.draft;
    const choices = nodes.map(node => {
      const reason = this.data.supportMatrix ? matrix.validate(this.data.supportMatrix, node.template, Object.assign({}, node.context, { challenge: true, challengeVersion: 2 })) : '请先加载玩法支持信息';
      return Object.assign({}, node, { reason, disabled: !!reason });
    });
    const selected = draft.challenge.alternativeNodeIds || [];
    this.setData({ runtimeChallengeNodes: choices,
      runtimeAlternativeNodes: choices.filter(node => node.id !== draft.challenge.challengeNodeId).map(node => Object.assign({}, node, { selected: selected.includes(node.id) })),
      'runtimeZeroEditor.error': draft.policy === 'CHALLENGE' ? matrix.challengeError(this.data.supportMatrix, nodes, draft.challenge) : '' });
  },
  openZeroHpEditor() { const config = this._runtimeConfig(); this.setData({ runtimeZeroHpOptions: options(this._zeroHpPolicyKeys(config)), runtimeZeroEditor: { show: true, draft: read(config), error: '', readOnly: readOnly(config) } }); this._refreshZeroHpEditor(); },
  closeZeroHpEditor() { this.setData({ runtimeZeroEditor: { show: false, draft: null, error: '', readOnly: false } }); },
  pickZeroHpPolicy(e) {
    if (this.data.runtimeZeroEditor.readOnly) return;
    const option = e.currentTarget.dataset.key ? this.data.runtimeZeroHpOptions.find(row => row.key === e.currentTarget.dataset.key) : this.data.runtimeZeroHpOptions[Number(e.detail.value)];
    if (!option || option.disabled) return;
    this.setData({ 'runtimeZeroEditor.draft.policy': option.key, 'runtimeZeroEditor.error': '' });
  },
  inputZeroHpField(e) {
    if (this.data.runtimeZeroEditor.readOnly) return;
    const path = e.currentTarget.dataset.path;
    if (!/^(zeroMessage|(challenge|steps|wait)\.(requiredCompletions|withinSeconds|restoreTo|limit|steps|minutes))$/.test(path)) return;
    this.setData({ ['runtimeZeroEditor.draft.' + path]: path === 'zeroMessage' ? e.detail.value : e.detail.value === '' ? '' : Number(e.detail.value) });
  },
  pickZeroHpThenPolicy(e) { const block = e.currentTarget.dataset.block, option = THEN[Number(e.detail.value)]; if (!this.data.runtimeZeroEditor.readOnly && ['challenge', 'steps', 'wait'].includes(block) && option) this.setData({ ['runtimeZeroEditor.draft.' + block + '.thenPolicy']: option.key }); },
  pickZeroHpChallengeNode(e) {
    if (this.data.runtimeZeroEditor.readOnly) return;
    const node = this.data.runtimeChallengeNodes[Number(e.detail.value)]; if (!node || node.reason) return;
    const row = clone(this.data.runtimeZeroEditor.draft.challenge), config = matrix.config(node.template);
    const main = matrix.mains(this.data.supportMatrix || { types: [], addons: [] }, config, true)[0]; if (!main?.section) return;
    row.challengeNodeId = node.id; row.gameplayKey = main.section; row.alternativeNodeIds = (row.alternativeNodeIds || []).filter(id => id !== node.id);
    this.setData({ 'runtimeZeroEditor.draft.challenge': row });
    this._refreshZeroHpEditor();
  },
  toggleZeroHpAlternative(e) {
    if (this.data.runtimeZeroEditor.readOnly) return;
    const id = Number(e.currentTarget.dataset.id), row = clone(this.data.runtimeZeroEditor.draft.challenge);
    const choices = matrix.challengeChoices(this.data.supportMatrix, this._runtimeSavedNodes(), 2).primary;
    if (!(row.alternativeNodeIds || []).includes(id) && (!choices.some(node => node.id === id) || id === row.challengeNodeId)) return;
    const ids = row.alternativeNodeIds || []; if (ids.includes(id)) row.alternativeNodeIds = ids.filter(value => value !== id); else if (ids.length < 3) row.alternativeNodeIds = ids.concat(id); else { this.setData({ 'runtimeZeroEditor.error': '最多选择 3 个备选挑战' }); return; }
    this.setData({ 'runtimeZeroEditor.draft.challenge': row });
    this._refreshZeroHpEditor();
  },
  saveZeroHpEditor() {
    if (this.data.runtimeZeroEditor.readOnly) { this.closeZeroHpEditor(); return true; }
    const config = apply(this._runtimeConfig(), this.data.runtimeZeroEditor.draft);
    const issue = validate(config, initial.parse(this.data.formData.journeyRules), this._zeroHpPolicyKeys(config), this._runtimeSavedNodes(), this.data.supportMatrix);
    if (issue) { this.setData({ 'runtimeZeroEditor.error': issue }); return false; }
    this._saveRuntime(config);
    // 归零挑战主/备节点不能同时是普通任务：保存策略后按现有章节同步把它们移出普通 quests（与新增/删除章节同一入口）
    if (this._syncStructureChapters) this._syncStructureChapters();
    this._syncRuntimeStats(); this.closeZeroHpEditor(); return true;
  },
};
module.exports = { OPTIONS, THEN, read, readOnly, apply, validate, options, summary, data, methods };
