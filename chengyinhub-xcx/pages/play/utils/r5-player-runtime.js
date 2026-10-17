const { checkReceiptView } = require('./playkit-view.js');

const CHECK_ACTIONS = { roll: 'ROLL_CHECK', reroll: 'REROLL_CHECK', settle: 'SUBMIT_CHECK' };
function isR5(view) { return !!view && !!view.config && view.config.outcomeRulesVersion === 1; }

function outcomeReply(view) {
  const receipt = view && (view.lastOutcome || view.settlement);
  const active = view && (view.activeQuest || view.gameplay && view.gameplay.activeQuest);
  const quest = view && (view.quests || []).find(row => row.questKey === active);
  if (view && (quest && quest.trustedPass === true || view.activeOperation && view.activeOperation.verified === true)
    && (!receipt || receipt.settled === true)) {
    return { id: 'pending:' + view.sessionId + ':' + (active || view.activeOperation.operationKey),
      settled: false, outcome: '', reply: '', statusText: '待确认结算' };
  }
  if (!receipt || !(receipt.attemptId || receipt.receiptId)) return null;
  const settled = receipt.settled === true && receipt.status === 'SETTLED';
  return { id: String(receipt.attemptId || receipt.receiptId), settled,
    outcome: settled ? receipt.outcome : '', reply: settled ? receipt.reply || '' : '',
    statusText: settled ? receipt.outcome === 'PASS' ? '通过' : '未通过'
      : receipt.status === 'BLOCKED_COST' ? '费用不足，待结算' : '待结算' };
}

function checkView(view, theme) {
  const check = view.check || {};
  const receipt = Array.isArray(check.dice) && check.dice.length ? checkReceiptView(check) : null;
  if (receipt) {
    receipt.r5 = true;
    receipt.settled = false; receipt.text = ''; receipt.failCostLabel = '';
    receipt.pending = check.submitted === true;
    receipt.allowedActions = check.allowedActions || [];
    receipt.rerollRemaining = check.rerollRemaining || 0;
    receipt.canReroll = receipt.allowedActions.includes('REROLL_CHECK');
    const outcome = outcomeReply(theme);
    if (outcome && outcome.settled && outcome.id === check.attemptId) {
      receipt.settled = true; receipt.pending = false; receipt.success = outcome.outcome === 'PASS'; receipt.text = outcome.reply;
    }
  }
  return { show: true, loading: false, r5: true, sessionId: view.sessionId, nodeId: view.nodeId,
    present: view.present === 'inline' ? 'inline' : 'fullscreen', skill: '', tier: check.tier || view.config.check.tier || '',
    allowedActions: check.allowedActions || [], disabledReason: check.disabledReason || '',
    mods: check.mods || [], advantage: check.advantage === true, disadvantage: check.disadvantage === true,
    receipt };
}

function methods() {
  return {
    _applyR5OutcomeView(view) {
      const reply = outcomeReply(view);
      // 单一结果位按回执 ID 替换，轮询与重进不追加消息或重播弹窗。
      if (JSON.stringify(reply) !== JSON.stringify(this.data.r5OutcomeReply)) this.setData({ r5OutcomeReply: reply });
      if (this._r5CheckView && this.data.journeyCheck.r5) {
        const receipt = checkView(this._r5CheckView, view).receipt;
        if (JSON.stringify(receipt) !== JSON.stringify(this.data.journeyCheck.receipt)) this.setData({ 'journeyCheck.receipt': receipt });
      }
    },
    _applyR5AdvancedView(view) {
      if (view.challenge && (['FAILED', 'EXPIRED'].includes(view.status) || view.status === 'COMPLETED' && view.canRetry === true)) {
        this._r5CheckView = null;
        this.setData({ 'journeyCheck.show': false });
        return false;
      }
      if (!isR5(view)) return false;
      if (view.config.check && view.config.check.enabled === true) {
        this._r5CheckView = view;
        this.setData({ journeyCheck: checkView(view, this.data.themeBag), playKit: { show: false, kit: null } });
        this.refreshThemeBackpack();
        if (view.present === 'inline' && this.data.chapterFull) this.openChapterFull();
        return true;
      }
      if (this.data.journeyCheck.r5 && this.data.journeyCheck.show) {
        this._r5CheckView = null; this.setData({ 'journeyCheck.show': false });
      }
      this.refreshThemeBackpack();
      return false;
    },
    _r5CheckAction(action) {
      const kit = this.data.journeyCheck;
      const name = CHECK_ACTIONS[action];
      if (!name || !kit.show || this._checkActing || !(kit.allowedActions || []).includes(name)) return Promise.resolve(false);
      const receipt = kit.receipt;
      if (action === 'roll' && receipt || action === 'reroll' && (!receipt || !receipt.canReroll)
        || action === 'settle' && (!receipt || receipt.pending || receipt.settled)) return Promise.resolve(false);
      const game = this.selectComponent('#advancedGame');
      if (!game || !game.data.state || String(game.data.state.sessionId) !== String(kit.sessionId)) return Promise.resolve(false);
      this._checkActing = true; this.setData({ 'journeyCheck.loading': true });
      return Promise.resolve(game.action(name, {})).finally(() => {
        this._checkActing = false; this.setData({ 'journeyCheck.loading': false });
      });
    },
  };
}
module.exports = { isR5, outcomeReply, checkView, methods, data: { r5OutcomeReply: null } };
