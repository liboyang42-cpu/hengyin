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
    images: settled && Array.isArray(receipt.images) ? receipt.images.filter(url => typeof url === 'string' && url).slice(0, 3) : [],
    statusText: settled ? receipt.outcome === 'PASS' ? '通过' : '未通过'
      : receipt.status === 'BLOCKED_COST' ? '费用不足，待结算' : '待结算' };
}

function checkView(view, theme) {
  const check = view.check || {};
  const receipt = (Array.isArray(check.dice) && check.dice.length || check.attemptId && (check.detailsHidden === true || check.hidden === true || check.redacted === true)) ? checkReceiptView(check) : null;
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
    mods: receipt && receipt.detailsHidden ? [] : check.mods || [], advantage: check.advantage === true, disadvantage: check.disadvantage === true,
    receipt };
}

function methods() {
  return {
    _applyR5OutcomeView(view) {
      const reply = outcomeReply(view), prev = this.data.r5OutcomeReply;
      // 单一结果位按回执 ID 替换，轮询与重进不追加消息或重播弹窗。
      if (JSON.stringify(reply) !== JSON.stringify(prev)) this.setData({ r5OutcomeReply: reply });
      // 成功 / 失败反馈不进背包:本页亲眼等到结算的那一次,整屏判定(照试玩台)把反馈文字和配图摆出来。
      // 进页时已经结过的不补弹 —— 第一份视图只记下来;同一张回执也只弹一次。
      const fresh = this._r5OutcomeSeeded && reply && reply.settled && (reply.reply || reply.images.length)
        && !(prev && prev.id === reply.id && prev.settled) && this._r5VerdictShownId !== reply.id;
      this._r5OutcomeSeeded = true;
      if (fresh) {
        this._r5VerdictShownId = reply.id;
        this.setData({ r5Verdict: { show: true, kind: reply.outcome === 'PASS' ? 'win' : 'fail', text: reply.reply, images: reply.images } });
      }
      if (this._r5CheckView && this.data.journeyCheck.r5) {
        const receipt = checkView(this._r5CheckView, view).receipt;
        if (JSON.stringify(receipt) !== JSON.stringify(this.data.journeyCheck.receipt)) this.setData({ 'journeyCheck.receipt': receipt });
      }
    },
    _applyR5AdvancedView(view) {
      this._applyR5PlainOutcome(view);
      if (view.challenge && (['FAILED', 'EXPIRED'].includes(view.status) || view.status === 'COMPLETED' && view.canRetry === true)) {
        this._r5CheckView = null;
        this.setData({ 'journeyCheck.show': false });
        return false;
      }
      if (!isR5(view)) return false;
      this._restartFailedQuest(view);
      if (view.config.check && view.config.check.enabled === true) {
        this._r5CheckView = view;
        const check = checkView(view, this.data.themeBag);
        // 第十二轮 k7 N4:内嵌检定只铺在故事流里;故事流没开(从地图/背包直接进)就改用整屏检定卡,不让这一站什么都不画
        if (check.present === 'inline' && !this.data.chapterFull) check.present = 'fullscreen';
        this.setData({ journeyCheck: check, playKit: { show: false, kit: null } });
        this.refreshThemeBackpack();
        if (check.present === 'inline') { this.openChapterFull(); this._guardInlineCheck(); }
        return true;
      }
      if (this.data.journeyCheck.r5 && this.data.journeyCheck.show) {
        this._r5CheckView = null; this.setData({ 'journeyCheck.show': false });
      }
      this.refreshThemeBackpack();
      return false;
    },
    /* 第十二轮 k7 N3:状态 / 检定主题里,这一站的任务进行中而玩法判了没过(FAILED,服务端不给原地重开 canRetry),
       屏上那道题还能点「提交」却什么都不发生 —— 以前唯一的路是 背包 → 任务 → 取消 → 开始任务 → 打开原任务。
       这里替玩家走同一条路:取消本站任务(进行中不扣费用)→ 重新开始 → 重开本站玩法,屏上那道题就是新的一局。
       要选费用组的任务不替玩家选,改为打开背包的任务页。每个玩法局只做一次。 */
    _restartFailedQuest(view) {
      if (!view || view.status !== 'FAILED' || view.canRetry === true || view.challenge || view.sessionId == null) return false;
      const bag = this.data.themeBag || {};
      const row = (bag.actionRows || []).find(item => item.kind === 'QUEST' && String(item.nodeId) === String(view.nodeId) && item.cancelable);
      if (!row) return false;
      this._questRestarted = this._questRestarted || {};
      const key = String(view.sessionId);
      if (this._questRestarted[key]) return false;
      this._questRestarted[key] = true;
      if ((row.costOptions || []).length) { this.setData({ themeBackpackOpen: true, themeBackpackTab: 'game' }); return true; }
      const node = this._nodeById ? this._nodeById(Number(row.nodeId)) : null;
      // 判没过之后背包的版本号刚往前走:每一步都等在途回读落地再读一次新的,带最新版本号发
      const fresh = () => Promise.resolve(this._themeLoading).then(() => this.refreshThemeBackpack());
      return fresh()
        .then(() => this._themeGameplayCommand({ operation: 'QUEST_CANCEL', contentId: row.key }))
        .then(ok => ok && fresh().then(() => this._themeGameplayCommand({ operation: 'QUEST_START', contentId: row.key })))
        .then(ok => {
          // 刚在这一站判没过:重开本站玩法,屏上那道题换成新的一局(旧局的提交服务端已不收)
          // 同一站的玩法面板已开着时 advancedPlay 不变就不会重读:先收起再开,新局才会去服务端开
          if (ok && node) this.setData({ advancedPlay: { show: false, node: null }, playKit: { show: false, kit: null } }, () => this.startGame(node));
          return !!ok;
        });
    },
    /* 第十二轮 k7 N4:内嵌检定的那一段要等故事流量到它在屏内才显形(_settleStory)。量不到(层被藏、几何为 0、
       页面换了)时段落永远 in:false,检定卡就一直是透明的 —— 玩家看见整屏黑。等一会儿还没显形就换成整屏检定卡。 */
    _guardInlineCheck() {
      clearTimeout(this._inlineCheckTimer);
      this._inlineCheckTimer = setTimeout(() => {
        const kit = this.data.journeyCheck || {};
        if (!kit.show || kit.present !== 'inline') return;
        const para = (this.data.chapterParas || []).find(p => p.kind === 'journeyCheck');
        if (para && para.in && this.data.chapterFull) return;
        const chapterParas = (this.data.chapterParas || []).filter(p => p.kind !== 'journeyCheck').map((p, i) => Object.assign({}, p, { i }));
        this.setData({ 'journeyCheck.present': 'fullscreen', chapterParas });
      }, 2500);
    },
    // 没有通用局回执的主题(themeBag 无 quests):判完那一次直接摆作者写的结果反馈;进页第一份视图只记下,不补弹。
    _applyR5PlainOutcome(view) {
      if (!isR5(view) || view.sessionId == null || ((this.data.themeBag || {}).quests || []).length) return;
      const fb = view.outcomeFeedback, seen = this._r5PlainSeen || (this._r5PlainSeen = {}), key = String(view.sessionId);
      const id = fb ? key + ':' + view.version : '';
      if (!(key in seen) || !fb || seen[key] === id) { seen[key] = id; return; }
      seen[key] = id;
      this.setData({ r5Verdict: { show: true, kind: fb.outcome === 'PASS' ? 'win' : 'fail', text: fb.reply || '', images: (fb.images || []).slice(0, 3) } });
    },
    _r5CheckAction(action) {
      const kit = this.data.journeyCheck;
      if (action === 'recover') {
        const game = this.selectComponent('#advancedGame');
        if (game && game.data.unknown && game.data.state && String(game.data.state.sessionId) === String(kit.sessionId)) {
          const owner = this._boundPlayOwner(), sessionKey = this._sessionKey();
          return Promise.resolve(game.resolveUnknown()).then(() => {
            if (this._isPlayAccountCurrent() && owner === this._boundPlayOwner() && sessionKey === this._sessionKey()
                && this.data.journeyCheck === kit && this.selectComponent('#advancedGame') === game) {
              this.setData({ 'journeyCheck.unknown': !!game.data.unknown,
                'journeyCheck.disabledReason': game.data.unknown ? '原检定结果仍待确认，请再次核对' : '' });
            }
          });
        }
        return Promise.resolve(false);
      }
      const name = CHECK_ACTIONS[action];
      if (!name || !kit.show || this._checkActing || !(kit.allowedActions || []).includes(name)) return Promise.resolve(false);
      const receipt = kit.receipt;
      if (action === 'roll' && receipt || action === 'reroll' && (!receipt || !receipt.canReroll)
        || action === 'settle' && (!receipt || receipt.pending || receipt.settled)) return Promise.resolve(false);
      const game = this.selectComponent('#advancedGame');
      if (!game || !game.data.state || String(game.data.state.sessionId) !== String(kit.sessionId)) return Promise.resolve(false);
      if (game.data.unknown) {
        this.setData({ 'journeyCheck.unknown': true, 'journeyCheck.disabledReason': '原检定结果待确认，请使用原请求核对入口' });
        return Promise.resolve(false);
      }
      const intent = this._r5CheckIntent = { owner: this._boundPlayOwner(), sessionKey: this._sessionKey(), sessionId: kit.sessionId };
      this._checkActing = true; this.setData({ 'journeyCheck.loading': true });
      return Promise.resolve(game.action(name, {})).finally(() => {
        if (this._r5CheckIntent !== intent) return;
        this._r5CheckIntent = null;
        this._checkActing = false;
        if (!this._isPlayAccountCurrent() || intent.owner !== this._boundPlayOwner() || intent.sessionKey !== this._sessionKey()
            || !this.data.journeyCheck.show || String(this.data.journeyCheck.sessionId) !== String(intent.sessionId)) return;
        this.setData({ 'journeyCheck.loading': false, 'journeyCheck.unknown': !!game.data.unknown,
          'journeyCheck.disabledReason': game.data.unknown ? '原检定结果待确认，请使用原请求核对入口' : this.data.journeyCheck.disabledReason });
      });
    },
  };
}
module.exports = { isR5, outcomeReply, checkView, methods, data: { r5OutcomeReply: null, r5Verdict: { show: false, kind: 'win', text: '', images: [] } } };
