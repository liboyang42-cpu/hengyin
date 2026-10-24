const sessionClient = require('../../../utils/game-session-client.js');
const gameplay = require('./theme-gameplay.js');
const definitions = [
  { action: 'ARRIVAL_ISSUE', title: '到场核验', icon: 'pin', summary: '领取到场凭证，交给本站工作人员核验' },
  { action: 'MANUAL_SIGN', title: '签署现场记录', icon: 'edit', summary: '确认实际发生的操作，等待工作人员逐条核验' },
  { action: 'TEAM_EXIT', title: '本队退出本局', icon: 'logout', summary: '退出不可撤销，已持有物品保留' },
];
const actionNames = { QUEST_START: '开始任务', QUEST_RESOLVE: '结算任务', QUEST_CANCEL: '取消任务', OPERATION_START: '开始操作任务', OPERATION_CANCEL: '取消操作任务', RECOVER: '使用恢复方式', VOTE_CAST: '签署首选票', ACCEPT_SET: '签署接受清单', CONSENSUS_CAST: '签署共识票' };
function rows(view) { return view.scope === 'TEAM' ? definitions.filter(row => (view.availableActions || []).includes(row.action)) : []; }
function exitText(view) { return view.run && view.run.exitedTeams && Object.prototype.hasOwnProperty.call(view.run.exitedTeams, String(view.teamId)) ? '本队已退出本局' : ''; }
// 主持人运营状态：只说明服务端实际执行的后果（暂停期间只读；中断/取消后只读、进行中的任务结束），不承诺冻结、顺延或费用处理。
function runText(view) {
  const run = view.scope === 'TEAM' && view.run || {};
  if (run.operationStatus === 'PAUSED' && run.authorityMode === 'ONLINE') return '主持人已暂停本局：暂停期间只能查看，不能提交操作';
  if (run.operationStatus === 'INTERRUPTED') return '主持人已中断本局：只能查看，进行中的任务已结束';
  if (run.operationStatus === 'CANCELLED') return '主持人已取消本局：只能查看，进行中的任务已结束';
  return '';
}
function manualRows(manual) { return (manual && manual.signatures || []).map(row => ({ ...row, title: actionNames[row.action] || '现场操作', statusText: { PENDING: '待核验', VERIFIED: '已核验', REJECTED: '未通过' }[row.status] || '状态尚未更新' })); }
function arrivalTargets(view, nodes) {
  const label = id => ((nodes || []).find(row => String(row.nodeId) === String(id)) || {}).name || '节点资料尚未加载';
  const targets = (view.quests || []).map(row => ({ label: label(row.nodeId), payload: { questKey: row.questKey }, nodeId: row.nodeId }));
  const operation = view.activeOperation;
  if (operation && operation.operationKind && operation.operationKey) targets.push({ label: label(operation.nodeId) + ' · 操作任务', payload: { operationKind: operation.operationKind, operationKey: operation.operationKey }, nodeId: operation.nodeId });
  return targets;
}
function manualCommands(view, nodes) {
  const commands = [];
  for (const target of arrivalTargets(view, nodes).filter(row => row.payload.questKey)) {
    const quest = view.quests.find(row => row.questKey === target.payload.questKey);
    const costs = gameplay.options(quest.costOptions, view, quest.costs);
    const starts = costs.length ? costs.map(row => ({ suffix: ' · ' + row.label + ' · ' + row.amountText, fields: { costOption: row.index } })) : [{ suffix: '', fields: {} }];
    for (const option of starts) commands.push({ label: target.label + ' · 开始任务' + option.suffix, command: { action: 'QUEST_START', payload: { ...target.payload, ...option.fields } } });
    const choices = (quest.choices || []).map((row, i) => ({ suffix: ' · ' + (row.label || row.title || '方案 ' + (i + 1)), fields: { choiceKey: row.choiceKey } }));
    for (const choice of choices.length ? choices : [{ suffix: '', fields: {} }]) commands.push({ label: target.label + ' · 结算任务' + choice.suffix, command: { action: 'QUEST_RESOLVE', payload: { ...target.payload, ...choice.fields } } });
    commands.push({ label: target.label + ' · 取消任务', command: { action: 'QUEST_CANCEL', payload: target.payload } });
  }
  for (const row of view.recoveries || []) commands.push({ label: row.title || '恢复方式', command: { action: 'RECOVER', payload: { recoveryKey: row.recoveryKey } } });
  if (view.activeOperation && view.activeOperation.operationKey) commands.push({ label: '取消当前操作任务', command: { action: 'OPERATION_CANCEL', payload: {} } });
  return commands;
}
function occurredAt(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/.exec(String(value || ''));
  if (!match) throw new Error('请填写实际发生时间，格式为 年-月-日 时:分:秒');
  const parts = match.slice(1).map(Number), date = new Date(parts[0], parts[1] - 1, ...parts.slice(2));
  if ([date.getFullYear(), date.getMonth() + 1, date.getDate(), date.getHours(), date.getMinutes(), date.getSeconds()].some((part, i) => part !== parts[i])) throw new Error('实际发生时间无效');
  return date.getTime();
}
function methods(req) {
  const client = () => sessionClient.createGameSessionClient({ sendRequest(options) { req(options.url, options.method, options.data, options.header, true).then(res => res.netFail ? options.fail() : options.success(res), options.fail); } });
  const handlers = {
    _applyPlayerOpsView(view) {
      this.setData({ playerOperationRows: rows(view), playerManualRows: manualRows(view.run && view.run.manual), playerExitText: exitText(view), playerRunText: runText(view), playerOperationAvailable: !!this.data.playerOperationForm && rows(view).some(row => row.action === this.data.playerOperationForm.action) });
      if (this.data.playerArrivalProof && this.data.playerArrivalProof.sessionId !== view.sessionId) this.setData({ playerArrivalProof: null });
      if (this._playerOpsPending && this._playerOpsPendingSessionId !== view.sessionId) this.setData({ playerOperationForm: null, playerOperationUnknown: true, playerOperationError: '上一局操作尚未确认，请先确认原操作结果' });
    },
    openPlayerOperations() { this.setData({ sessionToolsOpen: false, playerOperationsOpen: true }); return this.refreshThemeBackpack(); },
    closePlayerOperations() { if (!this._playerOpsReading) this.setData({ playerOperationsOpen: false }); },
    openPlayerOperation(e) {
      const view = this.data.themeBag, action = e.currentTarget.dataset.action;
      if (!rows(view).some(row => row.action === action) || this._playerOpsPending || this._themeSending || this.data.isPreview || this.data.isMock) return;
      const definition = definitions.find(row => row.action === action);
      this.setData({ playerOperationsOpen: true, playerOperationAvailable: true, playerOperationError: '', playerExitConfirm: false, playerArrivalProof: null,
        playerOperationForm: { ...definition, index: 0, targets: action === 'MANUAL_SIGN' ? manualCommands(view, this.data.nodes) : arrivalTargets(view, this.data.nodes),
          reason: '', occurredAt: '', sessionId: view.sessionId, authorityEpoch: view.authorityEpoch, sequence: view.run && view.run.manual && view.run.manual.nextSequence } });
    },
    closePlayerOperation() { if (!this._themeSending && !this._playerOpsPending) this.setData({ playerOperationForm: null, playerExitConfirm: false, playerOperationError: '' }); },
    choosePlayerOperationTarget(e) {
      const form = this.data.playerOperationForm, index = Number(e.detail.value);
      if (!form || this._themeSending || this._playerOpsPending || !form.targets[index]) return;
      this.setData({ playerOperationForm: { ...form, index }, playerOperationError: '' });
    },
    inputPlayerOperationTime(e) { if (!this._themeSending && !this._playerOpsPending && this.data.playerOperationForm) this.setData({ playerOperationForm: { ...this.data.playerOperationForm, occurredAt: e.detail.value }, playerOperationError: '' }); },
    inputPlayerOperationReason(e) { if (!this._themeSending && !this._playerOpsPending && this.data.playerOperationForm) this.setData({ playerOperationForm: { ...this.data.playerOperationForm, reason: e.detail.value }, playerOperationError: '' }); },
    _canPlayerOperation(action) {
      const view = this.data.themeBag;
      if (!view.sessionId || !rows(view).some(row => row.action === action) || this.data.isMock || this.data.isPreview || this._themeSending || this._playerOpsPending || this._planPending || this._tradePending || this._themePending || this._gameplayPending) { this.setData({ playerOperationError: '当前操作不可用，请刷新并确认上一条操作' }); return false; }
      return true;
    },
    submitPlayerOperation() {
      const form = this.data.playerOperationForm, view = this.data.themeBag;
      if (!form || !this._canPlayerOperation(form.action)) return Promise.resolve(false);
      let payload;
      try {
        if (form.sessionId !== view.sessionId || form.authorityEpoch !== view.authorityEpoch) throw new Error('状态已变化，请返回并重新打开');
        if (form.action === 'TEAM_EXIT') {
          if (!form.reason.trim() || form.reason.trim().length > 200) throw new Error('请填写退出原因（不超过 200 字）');
          this._playerExitSnapshot = { revision: view.revision, authorityEpoch: view.authorityEpoch, sessionId: view.sessionId, payload: { teamId: view.teamId, reason: form.reason.trim() } };
          this.setData({ playerExitConfirm: true }); return Promise.resolve(false);
        }
        const target = form.targets[form.index];
        if (!target) throw new Error('本站操作资料尚未加载，请刷新');
        if (form.action === 'MANUAL_SIGN') {
          if (!Number.isSafeInteger(form.sequence) || form.sequence < 1 || form.sequence !== (view.run && view.run.manual && view.run.manual.nextSequence)) throw new Error('现场记录已变化，请刷新后重新打开');
          payload = { sequence: form.sequence, occurredAt: occurredAt(form.occurredAt), command: target.command };
        } else {
          if (!arrivalTargets(view, this.data.nodes).some(row => JSON.stringify(row.payload) === JSON.stringify(target.payload) && row.nodeId === target.nodeId)) throw new Error('节点操作已变化，请重新打开');
          payload = target.payload; this._playerOpsArrivalLabel = target.label;
        }
      } catch (error) { this.setData({ playerOperationError: error.message }); return Promise.resolve(false); }
      return this._sendPlayerOperation(form.action, payload);
    },
    cancelPlayerExit() { if (!this._themeSending) { this._playerExitSnapshot = null; this.setData({ playerExitConfirm: false }); } },
    confirmPlayerExit() {
      const snapshot = this._playerExitSnapshot, view = this.data.themeBag;
      if (!this.data.playerExitConfirm || !snapshot || !this._canPlayerOperation('TEAM_EXIT')) return Promise.resolve(false);
      if (snapshot.sessionId !== view.sessionId || snapshot.revision !== view.revision || snapshot.authorityEpoch !== view.authorityEpoch) { this.cancelPlayerExit(); this.setData({ playerOperationError: '状态已变化，请重新确认后确认' }); return Promise.resolve(false); }
      this.setData({ playerExitConfirm: false }); this._playerExitSnapshot = null;
      return this._sendPlayerOperation('TEAM_EXIT', snapshot.payload);
    },
    async _sendPlayerOperation(action, payload) {
      const view = this.data.themeBag, epoch = this._themeEpoch || 0;
      const command = { activityId: view.activityId, expectedRevision: view.revision, authorityEpoch: view.authorityEpoch, action, payload,
        requestId: 'playerop_' + Date.now() + '_' + Math.random().toString(36).slice(2, 10) };
      this._playerOpsPending = command; this._playerOpsPendingSessionId = view.sessionId; this._playerOpsReading = true; this._themeSending = true; this.setData({ themeBagBusy: true, playerOperationError: '' });
      try {
        let result = await client().submitAction('player', command);
        if (result.status === 'unknown' || result.receipt && result.receipt.replayed) { this.setData({ playerOperationUnknown: true }); result = await client().readReceipt(command.activityId, command.requestId); }
        if (epoch !== (this._themeEpoch || 0) || view.sessionId !== this.data.themeBag.sessionId) return false;
        return await this._finishPlayerOperation(result, command, view.sessionId);
      } finally { this._playerOpsReading = false; this._themeSending = !!this._playerOpsPending; if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagBusy: false }); }
    },
    async checkPlayerOperationReceipt() {
      const command = this._playerOpsPending, epoch = this._themeEpoch || 0, sessionId = this._playerOpsPendingSessionId;
      if (!command || this._playerOpsReading) return false;
      this._playerOpsReading = true; this._themeSending = true; this.setData({ themeBagBusy: true });
      try { const result = await client().readReceipt(command.activityId, command.requestId); return epoch === (this._themeEpoch || 0) && await this._finishPlayerOperation(result, command, sessionId); }
      finally { this._playerOpsReading = false; this._themeSending = !!this._playerOpsPending; if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagBusy: false }); }
    },
    async _finishPlayerOperation(result, command, sessionId) {
      if (result.receipt && (result.receipt.action !== command.action || result.receipt.requestId !== command.requestId || String(result.receipt.activityId) !== String(command.activityId))) result = { status: 'unknown', message: '操作记录异常，请刷新后再试' };
      const applied = result.status === 'success' || result.status === 'matched';
      if (applied || result.status === 'business-error' && (!this.data.playerOperationUnknown || result.receipt)) {
        this._playerOpsPending = null;
        const patch = { playerOperationUnknown: false, playerOperationError: applied ? '' : result.message };
        if (sessionId !== this.data.themeBag.sessionId) { this.setData(patch); await this.refreshThemeBackpack(); return applied; }
        if (applied) {
          patch.playerOperationForm = null;
          const proof = result.receipt && result.receipt.result;
          if (command.action === 'ARRIVAL_ISSUE' && proof && proof.nonce) patch.playerArrivalProof = { ...proof, sessionId, stationName: this._playerOpsArrivalLabel, expiresText: new Date(proof.expiresAt).toLocaleTimeString() };
        }
        this.setData(patch); await this.refreshThemeBackpack(); return applied;
      }
      this.setData({ playerOperationUnknown: true, playerOperationError: result.message || '结果尚未确认，请刷新后再试' }); await this.refreshThemeBackpack(); return false;
    },
    copyPlayerArrivalProof() { const proof = this.data.playerArrivalProof; if (proof && proof.sessionId === this.data.themeBag.sessionId) wx.setClipboardData({ data: proof.nonce }); },
  };
  return handlers;
}
module.exports = { rows, exitText, runText, manualRows, arrivalTargets, manualCommands, occurredAt, methods,
  data: { playerOperationsOpen: false, playerOperationAvailable: false, playerOperationRows: [], playerManualRows: [], playerExitText: '', playerRunText: '', playerOperationForm: null, playerExitConfirm: false, playerOperationError: '', playerOperationUnknown: false, playerArrivalProof: null } };
