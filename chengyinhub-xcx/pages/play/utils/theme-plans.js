const sessionClient = require('../../../utils/game-session-client.js');
const trade = require('./theme-trade.js');
const modal = require('../../../utils/modal.js');

const reasonText = reason => ({ NOT_CAPTAIN: '仅当前队长可操作', RULE_BLOCKED: '方案前置条件尚未满足', NOT_ARRIVED: '尚未到达操作站点', PROOF_PENDING: '操作任务尚未通过', WORLD_LOCKED: '当前票轮已锁定，请先改案', SESSION_ENDED: '本局已结束', SESSION_PAUSED: '本局已暂停', INSUFFICIENT_RESOURCES: '可用物资不足', LIMIT_REACHED: '该操作已完成', CHALLENGE_LIMIT: '挑战次数已达上限', TEAM_COMPLETED: '本队已完成', COUNTERPART_COMPLETED: '对方已完成', TASK_ACTIVE: '请先完成或取消当前任务' }[reason] || reason || '信息尚未更新');
const phaseText = phase => ({ FUNDING: '讨论与投入', VOTING: '首选表决', ACCEPTANCE: '接受方案轮', SELECTED: '方案已选定', EXECUTED: '操作已执行', FALLBACK: '兜底收尾', TIED: '等待重投', CONSENSUS: '共识表决' }[phase] || phase || '信息尚未更新');
const policyText = policy => ({ MAJORITY: '多数通过', UNANIMOUS: '全体同意', MAJORITY_ACCEPTANCE: '多数通过 · 接受轮恢复' }[policy] || '信息尚未更新');
function present(view, nodes) {
  const world = view.world || {}, tools = (view.interactionTools || []).filter(row => row.kind === 'PLAN');
  const readback = Array.isArray(world.plans) && typeof world.votePolicy === 'string' && typeof world.votePhase === 'string';
  const allowed = action => readback && (view.availableActions || []).includes(action);
  const rows = [];
  for (const plan of readback ? world.plans : tools) {
    const tool = tools.find(row => row.endingKey === plan.endingKey) || {};
    const proposals = readback ? [(world.proposals || []).find(row => row.proposalKey === plan.proposalKey) || {}] : (world.proposals || []).filter(row => row.endingKey === plan.endingKey);
    for (const proposal of proposals.length ? proposals : [{}]) {
      const row = { ...tool, ...proposal, ...plan, actions: plan.actions || {} };
      row.uiKey = row.proposalKey || row.endingKey;
      row.title = row.title || '共同方案'; row.summary = row.summary || '';
      row.requirementText = trade.itemText(row.materialCost, view);
      row.confirmedText = Object.keys(proposal.confirmed || {}).map(teamId => '队伍 ' + teamId + '：' + trade.itemText(proposal.confirmed[teamId], view)).join('；') || '尚无确认';
      row.intentText = proposal.ownIntent ? trade.itemText(proposal.ownIntent.amounts, view) : '未填写';
      row.signatureText = proposal.ownIntent && proposal.ownIntent.signatureCurrent === false ? '队长已变更，旧意向签名失效，请由当前队长重新签署' : proposal.ownConfirmed === true ? '本队当前队长已确认托管' : proposal.ownIntent ? '本队当前队长已签署意向，尚未托管' : '';
      row.selected = row.proposalKey && row.proposalKey === world.selected;
      // 服务端只回读缺少的公开线索名称（主题登记名），不含内容或他队私有状态
      row.conditionText = Array.isArray(row.missingClues) && row.missingClues.length ? '还缺线索：' + row.missingClues.join('、')
        : row.conditionsMet === true ? '前置条件已满足' : row.conditionsMet === false ? '前置条件未满足' : '前置条件状态尚未更新';
      row.reason = row.actions.disabledReason ? reasonText(row.actions.disabledReason) : readback ? '' : '信息尚未更新';
      row.operationNodeName = ((nodes || []).find(node => String(node.nodeId) === String(row.operationNodeId)) || {}).name || '站点资料尚未加载';
      row.operationStarted = view.activeOperation && view.activeOperation.operationKind === 'PLAN' && view.activeOperation.operationKey === row.endingKey;
      row.canEditFunding = !!row.proposalKey && allowed('PLAN_INTENT') && row.actions.canFund === true;
      row.commandButtons = [];
      const add = (action, label, eligible) => { if (allowed(action) && eligible) row.commandButtons.push({ action, label }); };
      add('PLAN_PROPOSE', '提出此方案', !row.proposalKey && row.actions.canFund === true);
      add('PLAN_WITHDRAW', '撤回本队提案', !!row.proposalKey && String(row.teamId) === String(view.teamId));
      add('VOTE_CAST', '签署本队首选票', !!row.proposalKey && row.actions.canVote === true);
      add('CONSENSUS_CAST', '签署共识票', !!row.proposalKey && row.actions.canVote === true);
      add('PLAN_CONFIRM', '确认并锁定本队物资', row.selected && proposal.ownIntent && proposal.ownIntent.signatureCurrent === true && proposal.ownConfirmed !== true);
      add('OPERATION_START', '开始操作任务', row.selected && row.enabled === true && row.operation === 'OPERATION_START');
      add('OPERATION_CANCEL', '取消本队操作', row.selected && row.operationStarted);
      add('PLAN_EXECUTE', '执行已选方案', row.selected && row.actions.canExecute === true);
      rows.push(row);
    }
  }
  const planName = key => key === 'REJECT' ? '本轮不选任何方案' : (rows.find(row => row.proposalKey === key) || {}).title || '方案 ' + key;
  const ballot = [];
  if (world.ownVote) ballot.push('本队首选：' + planName(world.ownVote.planKey) + (world.ownVote.ownSignatureCurrent ? '（当前队长已签署）' : '（队长已变更，须重新签署）'));
  if (world.ownAcceptance) ballot.push('本队接受：' + (world.ownAcceptance.planKeys.map(planName).join('、') || '不接受任何方案') + (world.ownAcceptance.ownSignatureCurrent ? '（当前队长已签署）' : '（队长已变更，须重新签署）'));
  const lockedRows = (world.ownMaterials || []).filter(row => Number(row.locked) > 0);
  // 锁定物资仍在本队背包里占负重；表决与托管期间不能交易、使用或丢弃，改案即全部释放
  const planLockedText = lockedRows.length ? '本队已锁定：' + lockedRows.map(row => ((trade.catalogue(view).find(item => item.id === row.itemId) || {}).name || '物资 ' + row.itemId) + ' ×' + row.locked).join('、') + '（仍占背包负重，表决或托管期间不能交易、使用或丢弃）' : '';
  const planChangeText = allowed('PLAN_CHANGE') ? '改案会释放各队已锁定物资，旧票与旧确认作废，须重新表决并重新确认出资' : '';
  return { planBallotText: ballot.join('；'), planReadback: readback, planRows: rows, planLockedText, planChangeText, planPolicyText: policyText(world.votePolicy), planPhaseText: phaseText(world.votePhase),
    planMaterials: (world.ownMaterials || []).map(row => ({ ...row, name: (trade.catalogue(view).find(item => item.id === row.itemId) || {}).name || '物资 ' + row.itemId })),
    planGlobalActions: [['VOTE_OPEN', '开启首选表决'], ['VOTE_NEXT', '开启下一轮'], ['PLAN_CHANGE', '改案并重新签署'], ['PLAN_REJECT_ALL', world.ownRejectedAll ? '重新签署拒绝全部' : '拒绝全部剩余方案']].filter(([action]) => allowed(action)).map(([action, label]) => ({ action, label })),
    planCanAccept: allowed('ACCEPT_SET'), planCanRejectVote: allowed('VOTE_CAST'), planResult: world.result || world.lastOperationResult || null };
}
function fundingPayload(form) {
  const amounts = [];
  for (const row of form.items) {
    const raw = String(row.quantity).trim();
    if (!/^\d+$/.test(raw) || Number(raw) > 999) throw new Error(row.name + '的投入须为 0–999 的整数');
    if (Number(raw)) amounts.push({ itemId: row.itemId, quantity: Number(raw) });
  }
  return { proposalKey: form.proposalKey, version: form.version, amounts };
}
function endingResult(receipt) {
  const result = receipt && receipt.result || {};
  const value = result.result || result;
  return value.endingKey ? { endingKey: value.endingKey, title: value.title || '操作结果', summary: value.summary || '', terminal: value.terminal === true, fallback: value.fallback === true } : null;
}
function methods(req) {
  const client = () => sessionClient.createGameSessionClient({ sendRequest(options) {
    req(options.url, options.method, options.data, options.header, true).then(res => res.netFail ? options.fail() : options.success(res), options.fail);
  } });
  const handlers = {
    openThemePlan(e) {
      const row = this.data.themeBag.planRows.find(row => row.uiKey === e.currentTarget.dataset.key);
      if (!row) return;
      this.setData({ themePlanKey: row.uiKey, themePlanForm: null, themePlanError: '' });
    },
    closeThemePlan() { if (!this._themeSending) this.setData({ themePlanKey: '', themePlanForm: null }); },
    editThemePlanFunding() {
      const row = this._currentThemePlan();
      if (!row || !this._canThemePlan('PLAN_INTENT', row)) return;
      this.setData({ themePlanError: '', themePlanForm: { proposalKey: row.proposalKey, version: row.version,
        items: (row.materialCost || []).map(cost => ({ itemId: cost.itemId, name: trade.itemText([{ itemId: cost.itemId, quantity: cost.quantity }], this.data.themeBag),
          quantity: String(((row.ownIntent || {}).amounts || []).find(amount => amount.itemId === cost.itemId)?.quantity || 0) })) } });
    },
    inputThemePlanFunding(e) {
      const index = Number(e.currentTarget.dataset.index);
      if (this._themeSending || this._planPending || !this.data.themePlanForm || !this.data.themePlanForm.items[index]) return;
      this.setData({ ['themePlanForm.items[' + index + '].quantity']: e.detail.value, themePlanError: '' });
    },
    cancelThemePlanFunding() { if (!this._themeSending && !this._planPending) this.setData({ themePlanForm: null, themePlanError: '' }); },
    saveThemePlanFunding() {
      const row = this._currentThemePlan(), form = this.data.themePlanForm;
      if (!form || !row || !this._canThemePlan('PLAN_INTENT', row)) return Promise.resolve(false);
      if (form.proposalKey !== row.proposalKey || form.version !== row.version) { this.setData({ themePlanError: '方案版本已变化，请重新打开投入清单' }); return Promise.resolve(false); }
      let payload;
      try { payload = fundingPayload(form); } catch (error) { this.setData({ themePlanError: error.message }); return Promise.resolve(false); }
      return this._sendThemePlan('PLAN_INTENT', payload);
    },
    _currentThemePlan() { return this.data.themeBag.planRows.find(row => row.uiKey === this.data.themePlanKey); },
    _canThemePlan(action, row) {
      const view = this.data.themeBag;
      if (view.scope !== 'TEAM' || !view.sessionId || this.data.isPreview || this.data.isMock || this._themeSending || this._planPending || this._tradePending || this._themePending || this._gameplayPending) return false;
      if (!view.planReadback || !(view.availableActions || []).includes(action)) { this.setData({ themePlanError: view.planReadback ? '当前操作不可用，请核对队长身份与票轮' : '信息尚未更新' }); return false; }
      const gate = { PLAN_PROPOSE: 'canFund', PLAN_INTENT: 'canFund', VOTE_CAST: 'canVote', CONSENSUS_CAST: 'canVote', ACCEPT_SET: 'canAccept', PLAN_EXECUTE: 'canExecute' }[action];
      if (gate && row && row.actions[gate] !== true) { this.setData({ themePlanError: row.reason }); return false; }
      return true;
    },
    onThemePlanCommand(e) {
      const action = e.currentTarget.dataset.action;
      const row = this._currentThemePlan(), world = this.data.themeBag.world || {};
      if (!['PLAN_PROPOSE', 'PLAN_WITHDRAW', 'PLAN_CHANGE', 'PLAN_CONFIRM', 'PLAN_EXECUTE', 'VOTE_OPEN', 'VOTE_NEXT', 'VOTE_CAST', 'CONSENSUS_CAST', 'PLAN_REJECT_ALL', 'OPERATION_START', 'OPERATION_CANCEL'].includes(action) || !this._canThemePlan(action, row)) return Promise.resolve(false);
      const round = world.round, revision = this.data.themeBag.revision, epoch = this.data.themeBag.authorityEpoch;
      let payload = {};
      if (action === 'PLAN_PROPOSE') {
        if (!row || row.proposalKey) return Promise.resolve(false);
        payload = { proposalKey: 'plan_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 8), endingKey: row.endingKey };
      } else if (action === 'PLAN_WITHDRAW') {
        if (!row || !row.proposalKey) return Promise.resolve(false);
        payload = { proposalKey: row.proposalKey, version: row.version };
      } else if (['PLAN_CONFIRM', 'PLAN_EXECUTE', 'VOTE_CAST', 'CONSENSUS_CAST'].includes(action)) {
        if (!row || !row.proposalKey) return Promise.resolve(false);
        payload = { round, proposalKey: row.proposalKey };
      } else if (action === 'OPERATION_START') {
        if (!row || row.operation !== 'OPERATION_START' || row.enabled !== true) return Promise.resolve(false);
        payload = { operationKind: 'PLAN', operationKey: row.endingKey };
      }
      const send = () => this._sendThemePlan(action, payload);
      if (['PLAN_CHANGE', 'PLAN_WITHDRAW', 'PLAN_EXECUTE', 'PLAN_REJECT_ALL'].includes(action)) {
        modal.show({ title: { PLAN_CHANGE: '改案并重新签署', PLAN_WITHDRAW: '撤回本队提案', PLAN_EXECUTE: '执行已选方案', PLAN_REJECT_ALL: '拒绝全部剩余方案' }[action],
          content: action === 'PLAN_EXECUTE' ? '操作通过后才会消耗已确认物资，结果以确认的操作记录为准。' : action === 'PLAN_CHANGE' ? '改案会释放各队已锁定物资，当前票与出资确认全部作废，之后须重新表决并重新确认。' : '这次操作会影响当前票轮与签名，请核对后确认。', confirmText: '确认', success: result => {
            if (!result.confirm) return;
            if (revision !== this.data.themeBag.revision || epoch !== this.data.themeBag.authorityEpoch) { this.setData({ themePlanError: '状态已变化，请重新核对后确认' }); return; }
            if (this._canThemePlan(action, this._currentThemePlan())) send();
          } });
        return Promise.resolve(false);
      }
      return send();
    },
    rejectThemePlanVote() {
      if (!this._canThemePlan('VOTE_CAST')) return Promise.resolve(false);
      return this._sendThemePlan('VOTE_CAST', { round: this.data.themeBag.world.round, proposalKey: 'REJECT' });
    },
    toggleThemePlanAcceptance(e) {
      const row = this.data.themeBag.planRows.find(row => row.proposalKey === e.currentTarget.dataset.key);
      if (!row || !this._canThemePlan('ACCEPT_SET', row)) return;
      this._themePlanAcceptanceDirty = true;
      const selected = this.data.themePlanAcceptKeys;
      const next = selected.includes(row.proposalKey) ? selected.filter(key => key !== row.proposalKey) : selected.concat(row.proposalKey);
      this.setData({ themePlanAcceptKeys: next, themeBag: { ...this.data.themeBag, planRows: this.data.themeBag.planRows.map(plan => ({ ...plan, acceptSelected: next.includes(plan.proposalKey) })) } });
    },
    submitThemePlanAcceptance() {
      if (!this._canThemePlan('ACCEPT_SET')) return Promise.resolve(false);
      const rows = this.data.themeBag.planRows;
      if (this.data.themePlanAcceptKeys.some(key => !rows.some(row => row.proposalKey === key && row.actions.canAccept === true))) { this.setData({ themePlanError: '接受轮已变化，请重新选择' }); return Promise.resolve(false); }
      return this._sendThemePlan('ACCEPT_SET', { round: this.data.themeBag.world.round, proposalKeys: this.data.themePlanAcceptKeys.slice() });
    },
    openThemePlanOperation() {
      const row = this._currentThemePlan();
      if (!row || !row.operationNodeId || !row.operationStarted) return false;
      const node = this._nodeById(Number(row.operationNodeId));
      if (!node) { this.setData({ themePlanError: '操作站点资料尚未加载' }); return false; }
      this.setData({ themeBackpackOpen: false, themePlanKey: '', advancedPlay: { show: true, node }, playKit: { show: false, kit: null } }); return true;
    },
    async _sendThemePlan(action, payload) {
      const view = this.data.themeBag;
      if (this._themeSending || this._planPending) return false;
      const command = { activityId: view.activityId, requestId: 'plan_' + Date.now() + '_' + Math.random().toString(36).slice(2, 10),
        expectedRevision: view.revision, authorityEpoch: view.authorityEpoch, action, payload };
      this._planPending = command; this._themeSending = true;
      this.setData({ themeBagBusy: true, themePlanError: '' });
      const epoch = this._themeEpoch || 0;
      try {
        let result = await client().submitAction('player', command);
        if (result.status === 'unknown' || result.receipt && result.receipt.replayed) {
          this.setData({ themePlanUnknown: true });
          result = await client().readReceipt(command.activityId, command.requestId);
        }
        return epoch === (this._themeEpoch || 0) && await this._finishThemePlan(result, command);
      } finally { this._themeSending = false; if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagBusy: false }); }
    },
    async checkThemePlanReceipt() {
      const command = this._planPending;
      if (!command || this._themeSending) return false;
      this._themeSending = true; this.setData({ themeBagBusy: true });
      const epoch = this._themeEpoch || 0;
      try { const result = await client().readReceipt(command.activityId, command.requestId); return epoch === (this._themeEpoch || 0) && await this._finishThemePlan(result, command); }
      finally { this._themeSending = false; if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagBusy: false }); }
    },
    async _finishThemePlan(result, command) {
      if (result.receipt && (result.receipt.action !== command.action || result.receipt.requestId !== command.requestId || String(result.receipt.activityId) !== String(command.activityId))) result = { status: 'unknown', message: '操作记录不匹配，请核对原请求' };
      const applied = result.status === 'success' || result.status === 'matched';
      if (applied || result.status === 'business-error' && (!this.data.themePlanUnknown || result.receipt)) {
        this._planPending = null;
        const patch = { themePlanUnknown: false, themePlanError: applied ? '' : result.message };
        if (applied && command.action === 'PLAN_INTENT') patch.themePlanForm = null;
        if (applied && command.action === 'ACCEPT_SET') this._themePlanAcceptanceDirty = false;
        const ending = applied && endingResult(result.receipt);
        if (ending) patch.themePlanResult = ending;
        this.setData(patch); await this.refreshThemeBackpack(); return applied;
      }
      this.setData({ themePlanUnknown: true, themePlanError: result.message || '结果待确认，请核对原请求' });
      await this.refreshThemeBackpack(); return false;
    },
  };
  return handlers;
}
module.exports = { present, fundingPayload, endingResult, reasonText, phaseText, policyText, methods,
  data: { themePlanKey: '', themePlanForm: null, themePlanAcceptKeys: [], themePlanError: '', themePlanUnknown: false, themePlanResult: null } };
