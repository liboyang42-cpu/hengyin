const sessionClient = require('../../../utils/game-session-client.js');
const toast = require('../../../utils/toast.js');
const gameplay = require('./theme-gameplay.js');
const rewards = require('./theme-rewards.js');
const trade = require('./theme-trade.js');
const plans = require('./theme-plans.js');
const titles = { WAIT: '休息恢复', CHALLENGE: '归零挑战', STEPS: '步行恢复', BLOCK_HP_COST: '暂停消耗任务', BLOCK_PAID: '暂停付费任务', THEME_FAIL: '本局结束' };

function presentZeroHp(view) {
  const zero = view.zeroHp;
  if (!zero || zero.phase === 'HEALTHY') return null;
  const actions = Array.isArray(zero.allowedActions) ? zero.allowedActions : [];
  const effective = zero.effectivePolicy || zero.policy;
  const usedText = Number.isInteger(zero.used) && Number.isInteger(zero.limit) && zero.limit > 0 ? '已恢复 ' + zero.used + ' / ' + zero.limit + ' 次' : '';
  const thenText = titles[zero.thenPolicy] || (zero.phase === 'BLOCKED' ? titles[effective] : '');
  return { ...zero, title: titles[effective] || '归零状态',
    canSteps: ['AWAITING_BASELINE', 'RECOVERING'].includes(zero.phase) && zero.policy === 'STEPS' && actions.includes('ZERO_HP_STEPS'),
    canSync: ['AWAITING_BASELINE', 'RECOVERING'].includes(zero.phase) && actions.includes('ZERO_HP_SYNC'),
    progressText: Number.isInteger(zero.progress) && Number.isInteger(zero.required) && zero.required > 0 ? zero.progress + ' / ' + zero.required : '',
    usedText, thenText,
    metaText: [zero.restoreTo > 0 && zero.phase !== 'BLOCKED' ? '恢复到 ' + zero.restoreTo : '', usedText, thenText ? (zero.phase === 'BLOCKED' ? '当前处理：' : '用尽后：') + thenText : ''].filter(Boolean).join(' · '),
    memberText: zero.recoveryMemberName || (zero.recoveryMemberId ? '队员 ' + zero.recoveryMemberId : ''),
    // 团队共享归零：恢复由服务端判定，任一队员完成后全队同时恢复，多人同时提交也只结算一次
    sharedText: view.scope === 'TEAM' ? '全队共用这次归零：任一队员完成恢复，全队同时恢复，只结算一次' : '',
  };
}
function present(view, nodes) {
  const names = gameplay.labels(view);
  const contents = Array.isArray(view.contents) ? view.contents : [];
  const claimOnly = rewards.ended(view);
  const items = contents.filter(i => i.kind === 'ITEM').map(i => Object.assign({}, i, {
    name: i.name || gameplay.valueName(i.id, view),
    statLabel: gameplay.valueName(i.statKey || i.stat, view), initial: String(i.name || gameplay.valueName(i.id, view)).slice(0, 1),
    effectText: i.stat || i.statKey ? gameplay.valueName(i.statKey || i.stat, view) + ' ' + (i.delta > 0 ? '+' : '') + i.delta : i.content || '随身物资',
    canUse: !claimOnly && !i.wear && !i.worn && !!(i.stat || i.statKey) && (view.scope !== 'TEAM' || (view.availableActions || []).includes('ITEM_USE')),
    canDiscard: !claimOnly && view.allowDiscard === true && (view.scope !== 'TEAM' || (view.availableActions || []).includes('ITEM_DISCARD')),
  }));
  const clues = contents.filter(i => i.kind !== 'ITEM' && !i.stat && !i.statKey).concat((view.events || []).map(e => ({ id: e.id, name: e.name || '线索', content: e.content })))
    // 服务端回读的已获得线索：范围单列，内容为来源站点的线索段落（正文与图片原样保留）
    .concat((view.clues || []).map(c => {
      const blocks = [];
      (Array.isArray(c.sources) ? c.sources : []).forEach(source => (Array.isArray(source.blocks) ? source.blocks : []).forEach(block => blocks.push({
        source: source.title || '', text: block.text || '', images: (Array.isArray(block.images) ? block.images : []).filter(Boolean),
      })));
      // 来源未能按本队/全场归属确认时只显示名称与范围，不显示正文和图片
      return { id: c.id + ':' + (c.scope || ''), name: c.name || '线索', scopeText: c.scope === 'PUBLIC' ? '全场公开' : '本队线索', blocks,
        lockedText: blocks.length ? '' : '已获得 · 来源未确认或未配正文，仅显示名称' };
    }));
  const valueRows = [];
  if (view.hpEnabled) valueRows.push({ key: 'hp', name: names.hp, value: view.hp, max: view.hpMax, zero: view.awaitingChallenge || view.zeroHp && view.zeroHp.phase !== 'HEALTHY' });
  if (view.luckEnabled) valueRows.push({ key: 'luck', name: names.luck, value: view.luck, max: view.luckMax });
  if (view.actionLimit > 0) valueRows.push({ key: 'actions', name: names.action, value: view.actions, max: view.actionLimit });
  (view.attributes || []).filter(row => row.visible !== false && !String(row.key).startsWith('sys.')).forEach(row => valueRows.push({ key: row.key, name: row.label, value: row.value, max: row.max }));
  const challenge = view.challenge || {};
  const challengeIds = [challenge.nodeId].concat(challenge.alternativeNodeIds || []).filter(id => Number.isSafeInteger(Number(id)) && Number(id) > 0);
  return Object.assign({}, view, trade.present(view, nodes), plans.present(view, nodes), { items, clues, valueRows, claimOnly, names, actionRows: gameplay.actionRows(view, nodes).filter(row => view.scope !== 'TEAM' || row.kind !== 'PLAN'),
    challengeNodes: challengeIds.map((id, index) => ({ id: Number(id), name: ((nodes || []).find(node => Number(node.nodeId) === Number(id)) || {}).name || (index ? '备选挑战' : '主挑战') })),
    challengeRestoreText: Number.isInteger(challenge.restoreTo) && challenge.restoreTo > 0 ? '恢复到 ' + challenge.restoreTo : '恢复',
    challengeProgressLabel: view.scope === 'TEAM' ? '全队进度' : '挑战进度',
    challengeProgressText: typeof challenge.progress === 'number' && typeof challenge.goal === 'number' ? challenge.progress + '/' + challenge.goal : '',
    challengeRetryText: challenge.disabledReason === 'CHALLENGE_LIMIT' ? '挑战次数已达上限（' + challenge.used + '/' + challenge.limit + '）' : challenge.disabledReason || '失败后可免费重试',
    pendingRows: Object.keys(view.pending || {}).map(id => ({ ...view.pending[id], name: view.pending[id].name || rewards.itemName(view.pending[id].itemId, view), id, canClaim: !claimOnly || rewards.canClaim(view, id) })) });
}
function methods(req) {
  const client = () => sessionClient.createGameSessionClient({ sendRequest(options) {
    req(options.url, options.method, options.data, options.header, true).then(res => res.netFail ? options.fail() : options.success(res), options.fail);
  } });
  const bagMethods = {
    _applyZeroHpView(view) {
      const zero = presentZeroHp(view), key = view.sessionId + ':' + (zero && zero.episodeId || '');
      if (key !== this._zeroHpKey) {
        this._zeroHpKey = key; this._zeroHpDueKey = null;
        this.setData({ zeroHpError: '', zeroHpPermissionDenied: false });
      }
      this._zeroHpClock = zero && Number.isFinite(zero.serverNow) && Number.isFinite(zero.deadlineAt)
        ? { receivedAt: Date.now(), serverNow: zero.serverNow, deadlineAt: zero.deadlineAt } : null;
      this.setData({ zeroHpView: zero, zeroHpCountdown: '' });
      this._stopZeroHpTimer(); this._startZeroHpTimer();
      if ((view.result || view.resultCard || {}).reason === 'HP_ZERO' && this._zeroHpResultSession !== view.sessionId && !this._themeHidden) {
        this._zeroHpResultSession = view.sessionId;
        this.setData({ themeBackpackOpen: false, advancedPlay: { show: false, node: null }, playKit: { show: false, kit: null } });
        this.openThemeResultCard();
      }
    },
    _startZeroHpTimer() {
      if (this._themeHidden || this._zeroHpTimer || !this._zeroHpClock || !this.data.zeroHpView || this.data.zeroHpView.phase !== 'RECOVERING' || this.data.isMock || this.data.isPreview) return;
      this._tickZeroHpClock();
      this._zeroHpTimer = setInterval(() => this._tickZeroHpClock(), 1000);
    },
    _stopZeroHpTimer() { if (this._zeroHpTimer) clearInterval(this._zeroHpTimer); this._zeroHpTimer = null; },
    _tickZeroHpClock() {
      const clock = this._zeroHpClock, zero = this.data.zeroHpView;
      if (!clock || !zero) return;
      const seconds = Math.max(0, Math.ceil((clock.deadlineAt - clock.serverNow - (Date.now() - clock.receivedAt)) / 1000));
      this.setData({ zeroHpCountdown: seconds > 0 ? Math.floor(seconds / 60) + '分' + seconds % 60 + '秒（预计）' : '时间已到，正在确认恢复状态' });
      const key = this._zeroHpKey + ':' + clock.deadlineAt;
      // 倒计时只显示估算，到点仍须服务器回读确认恢复或超时。
      if (!seconds && zero.canSync && this._zeroHpDueKey !== key && !this._themeSending && !this.data.zeroHpBusy && !this._gameplayPending && !this._themePending && !this._planPending && !this._tradePending) {
        this._zeroHpDueKey = key; this.syncZeroHpState();
      }
    },
    syncZeroHpState() {
      if (!this.data.zeroHpView || !this.data.zeroHpView.canSync || this.data.zeroHpBusy) return Promise.resolve(false);
      return this._themeGameplayCommand({ operation: 'ZERO_HP_SYNC' });
    },
    async syncZeroHpSteps() {
      const zero = this.data.zeroHpView, view = this.data.themeBag;
      if (!zero || !zero.canSteps || this.data.zeroHpBusy || this._themeSending || this.data.isMock || this.data.isPreview) return false;
      if (this._gameplayPending && this._gameplayPending.input.operation.startsWith('ZERO_HP_')) return this.retryThemeBagAction();
      if (this._gameplayPending) { this.setData({ zeroHpError: '请先核对上一条操作' }); return false; }
      if (this._themePending || this._planPending || this._tradePending) { this.setData({ zeroHpError: '请先核对上一条操作' }); return false; }
      const epoch = this._themeEpoch || 0, episodeId = zero.episodeId, sessionId = view.sessionId;
      this.setData({ zeroHpBusy: true, zeroHpError: '', zeroHpPermissionDenied: false });
      try {
        const weRun = await new Promise((resolve, reject) => wx.login({
          success: login => {
            if (epoch !== (this._themeEpoch || 0)) { resolve(null); return; }
            if (!login || !login.code) { reject({ message: '微信登录态获取失败，请重试' }); return; }
            wx.getWeRunData({
              success: run => run && run.encryptedData && run.iv ? resolve({ episodeId, code: login.code, encryptedData: run.encryptedData, iv: run.iv }) : reject({ message: '微信运动数据未获取，请重试' }),
              fail: error => {
                const denied = !error || !error.errMsg || /auth|deny|denied|scope/i.test(error.errMsg);
                reject({ message: denied ? '需要授权微信运动。请开启微信运动权限后重试同步' : '微信运动数据获取失败，请重试', permissionDenied: denied });
              },
            });
          },
          fail: () => reject({ message: '微信登录态获取失败，请重试' }),
        }));
        if (epoch !== (this._themeEpoch || 0)) return false;
        if (this.data.themeBag.sessionId !== sessionId || !this.data.zeroHpView || this.data.zeroHpView.episodeId !== episodeId || !this.data.zeroHpView.canSteps) {
          this.setData({ zeroHpError: '恢复状态已更新，请重新同步' }); return false;
        }
        return await this._themeGameplayCommand({ operation: 'ZERO_HP_STEPS', weRun });
      } catch (error) {
        if (epoch === (this._themeEpoch || 0)) this.setData({ zeroHpError: error.message || '同步失败，请重试', zeroHpPermissionDenied: error.permissionDenied === true });
        return false;
      } finally { if (epoch === (this._themeEpoch || 0)) this.setData({ zeroHpBusy: false }); }
    },
    openZeroHpWeRunSettings() {
      if (!this.data.zeroHpPermissionDenied || this.data.zeroHpBusy) return;
      const epoch = this._themeEpoch || 0;
      wx.openSetting({ success: result => {
        if (epoch !== (this._themeEpoch || 0)) return;
        const granted = result.authSetting && result.authSetting['scope.werun'] === true;
        this.setData({ zeroHpPermissionDenied: !granted, zeroHpError: granted ? '已开启微信运动，请重新同步' : '请开启微信运动权限后重试同步' });
      }, fail: () => { if (epoch === (this._themeEpoch || 0)) this.setData({ zeroHpError: '设置未打开，请重试' }); } });
    },
    _applyThemeView(view) {
      const previous = this.data.themeBag;
      if (previous.sessionId === view.sessionId) { const ids = new Set((previous.events || []).map(e => e.id)); const added = (view.events || []).find(e => !ids.has(e.id)); if (added) toast(added.name || '获得新线索'); }
      const next = present(view, this.data.nodes);
      const sameRound = previous.sessionId === view.sessionId && previous.world && view.world && previous.world.round === view.world.round;
      const acceptKeys = sameRound && this._themePlanAcceptanceDirty ? this.data.themePlanAcceptKeys || [] : view.world && view.world.ownAcceptance && view.world.ownAcceptance.planKeys || [];
      if (!sameRound) this._themePlanAcceptanceDirty = false;
      next.planRows = next.planRows.map(row => ({ ...row, acceptSelected: acceptKeys.includes(row.proposalKey) }));
      this.setData({ themeBag: next, themePlanAcceptKeys: acceptKeys, themeBagError: this._gameplayPending && this._gameplayPending.input.operation.startsWith('ZERO_HP_') && this.data.themeBagUnknown ? this.data.themeBagError : '' });
      this._applyZeroHpView(view);
      if (this._applyR5OutcomeView) this._applyR5OutcomeView(view);
      if (this._applyPlayerOpsView) this._applyPlayerOpsView(view);
      if (this._themeChallengeNodeId && this.data.playKit && this.data.playKit.kit) {
        if (view.awaitingChallenge === false) {
          this._themeChallengeNodeId = null;
          this.setData({ advancedPlay: { show: false, node: null }, playKit: { show: false, kit: null } });
        } else this.setData({ 'playKit.kit.challenge': view.challenge || null });
      }
      const preview = this.data.themeTradePreview;
      if (preview && (preview.revision !== view.revision || preview.authorityEpoch !== view.authorityEpoch
        || !this.data.themeBag.tradeTargets.some(row => String(row.teamId) === String(preview.payload.toTeamId) && row.enabled))) this.setData({ themeTradePreview: null });
      this._stopThemePoll(); this._startThemePoll();
    },
    _startThemePoll() {
      const bag = this.data.themeBag;
      if (this._themeHidden || this._themeTimer || !bag.sessionId || !(bag.timeEggs || bag.stepEggs || bag.awaitingChallenge || bag.zeroHp && ['RECOVERING', 'AWAITING_BASELINE'].includes(bag.zeroHp.phase) || this.data.themeBackpackOpen && (bag.planRows || []).length) || !['ACTIVE', 'RUNNING'].includes(bag.status)) return;
      this._themeTimer = setInterval(() => { if (!this._themeSending) (bag.stepEggs ? this._syncThoughts([]) : Promise.resolve()).then(() => this.refreshThemeBackpack()); }, bag.awaitingChallenge || bag.zeroHp && ['RECOVERING', 'AWAITING_BASELINE'].includes(bag.zeroHp.phase) || (bag.planRows || []).length ? 10000 : 30000);
    },
    _stopThemePoll() { if (this._themeTimer) clearInterval(this._themeTimer); this._themeTimer = null; },
    refreshThemeBackpack() {
      if (this.data.isMock || this.data.isPreview) return Promise.resolve(false);
      if (this.data.themeBag.scope === 'TEAM') return this._loadPlayerGameModule();
      if (!this.data.topicId) return Promise.resolve(false);
      if (this._themeLoading) return this._themeLoading;
      const epoch = this._themeEpoch || 0;
      const loading = req('/api/play/theme/state', 'GET', Object.assign({ topicId: this.data.topicId }, this._sessionParams()), null, true).then(res => {
        if (epoch !== (this._themeEpoch || 0)) return false;
        if (res.code == 200 && res.data) {
          if (this.data.themeBag.scope === 'TEAM') return false;
          this._applyThemeView(res.data);
          if (this._routeState && String(this._routeState.sessionId) === String(res.data.sessionId) && this._routeState.version < res.data.version && !this._themeSending) this.refreshRouteState();
          if (this.data.chapterFull) this._enterThemeChapter();
          if (res.data.status !== 'ACTIVE') this._stopThemePoll(); else this._startThemePoll();
          if (res.data.stepEggs) { this._askWeRunOnce(); if (!this._themeStepBaseline) { this._themeStepBaseline = true; this._syncThoughts([]); } }
          return true;
        }
        // Older themes have no inventory configuration. Retain life HUD from journey rules.
        if (this.data.themeBackpackOpen) this.setData({ themeBagError: res.msg || '背包暂未加载，点击重试' });
        return false;
      }).finally(() => { if (this._themeLoading === loading) this._themeLoading = null; });
      this._themeLoading = loading;
      return loading;
    },
    openThemeBackpack() { this.setData({ themeBackpackOpen: true, themeBackpackTab: this.data.themeBag.awaitingChallenge || this.data.zeroHpView ? 'game' : 'items', themeBagError: '' }); return this.refreshThemeBackpack(); },
    openThemeGameplay() { this.setData({ sessionToolsOpen: false, themeBackpackOpen: true, themeBackpackTab: (this.data.themeBag.planRows || []).length || this.data.themeBag.actionRows.length || this.data.themeBag.tradeTargets.length || this.data.themeBag.tradeRows.length || this.data.themeBag.awaitingChallenge ? 'game' : 'items', themeBagError: '' }); return this.refreshThemeBackpack(); },
    _applyThemeTeamView(player, projection) {
      this._applyThemeView(Object.assign({}, player, { scope: 'TEAM', sessionId: projection.sessionId,
        activityId: projection.activityId, revision: projection.revision, status: projection.status, claimOnly: rewards.ended(player),
        availableActions: projection.availableActions || [], inventoryEnabled: true,
        hpEnabled: typeof player.hp === 'number', authorityEpoch: player.authorityEpoch }));
    },
    closeThemeBackpack() { this.setData({ themeBackpackOpen: false }); },
    changeThemeBackpackTab(e) { this.setData({ themeBackpackTab: e.currentTarget && e.currentTarget.dataset.key || e.detail.key }); },
    // 背包物资动作：团队局走团队命令（只有 ITEM_USE/ITEM_DISCARD/ITEM_RECEIVE），个人局走主题动作
    _themeCommand(operation, contentId, discardOwned) {
      const view = this.data.themeBag;
      if (!this._themePending && !this._gameplayPending && rewards.ended(view) && (operation !== 'RECEIVE' || !rewards.canClaim(view, contentId))) return Promise.resolve(false);
      if (view.scope === 'TEAM') return this._themeGameplayCommand(Object.assign({ operation: 'ITEM_' + operation, contentId }, discardOwned === undefined ? {} : { discardOwned }));
      return this._themePersonalCommand(operation, contentId, discardOwned);
    },
    // 通用局在任务结算/路线推进时发章节奖励；只有旧版个人局发送章节动作。
    _themeChapterCommand(operation, contentId) {
      if (this.data.themeBag.scope === 'TEAM' || this.data.themeBag.runtimeScope === 'PLAYER' || rewards.ended(this.data.themeBag)) return Promise.resolve(true);
      return this._themePersonalCommand(operation, contentId);
    },
    _themePersonalCommand(operation, contentId, discardOwned) {
      if (this._themeSending) return Promise.resolve(false);
      if (this._gameplayPending || this._tradePending || this._planPending) { toast('请先核对上一条操作'); return Promise.resolve(false); }
      if (this._themePending && (this._themePending.operation !== operation || this._themePending.contentId !== contentId)) { toast('请先重试上一条操作'); return Promise.resolve(false); }
      const view = this.data.themeBag;
      if (!view.sessionId || this.data.isPreview || this.data.isMock) return Promise.resolve(false);
      if (!this._themePending && rewards.ended(view) && (operation !== 'RECEIVE' || !rewards.canClaim(view, contentId))) return Promise.resolve(false);
      const command = this._themePending || Object.assign({ sessionId: view.sessionId, expectedVersion: view.version, actionId: 'bag_' + Date.now().toString(36) + Math.random().toString(36).slice(2), operation, contentId }, discardOwned === undefined ? {} : { discardOwned });
      this._themePending = command; this._themeSending = true;
      this.setData({ themeBagBusy: true, themeBagError: '' });
      const epoch = this._themeEpoch || 0;
      return req('/api/play/theme/action', 'POST', command, { 'content-type': 'application/json' }, true).then(res => {
        if (epoch !== (this._themeEpoch || 0)) return false;
        if (res.code == 200 && res.data) {
          this._themePending = null; this.setData({ themeBagUnknown: false }); this._applyThemeView(res.data);
          if (operation === 'RECEIVE') { toast('待领奖励已领取'); this.setData({ themePendingClaim: null }); }
          return this.refreshThemeBackpack().then(() => this.refreshRouteState()).then(() => true);
        }
        if (res.netFail) this.setData({ themeBagUnknown: true, themeBagError: '操作结果未确认，点击重试' });
        else { this._themePending = null; const message = res.msg || '操作失败'; return this.refreshThemeBackpack().then(() => { this.setData({ themeBagUnknown: false, themeBagError: message }); return false; }); }
        return false;
      }).finally(() => { this._themeSending = false; if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagBusy: false }); });
    },
    onThemeBagAction(e) {
      const d = e.currentTarget.dataset;
      if (d.operation === 'RECEIVE' && rewards.ended(this.data.themeBag)) return this.openThemePendingClaim(d.id);
      const item = this.data.themeBag.items.find(row => row.id === d.id);
      if (item && (d.operation === 'USE' && !item.canUse || d.operation === 'DISCARD' && !item.canDiscard)) return Promise.resolve(false);
      this.setData({ themeItemDetail: null }); return this._themeCommand(d.operation, d.id);
    },
    retryThemeBagAction() { if (this._themeSending) return Promise.resolve(false); if (this._gameplayPending && this._gameplayPending.input.operation.startsWith('QUEST_')) return this._readThemeGameplayReceipt(); return this._gameplayPending ? this._themeGameplayCommand(this._gameplayPending.input) : this._themePending ? this._themeCommand(this._themePending.operation, this._themePending.contentId, this._themePending.discardOwned) : this.refreshThemeBackpack(); },
    onThemeGameplayAction(e) {
      const row = (this.data.themeBag.actionRows || [])[Number(e.currentTarget.dataset.index)];
      const opening = e.currentTarget.dataset.open === true || e.currentTarget.dataset.open === 'true';
      if (!row || (opening ? !row.openable : !row.enabled) || this._themeSending) return Promise.resolve(false);
      if (opening || row.operation === 'OPEN_QUEST' || row.operation === 'OPEN_OPERATION') {
        const node = this._nodeById(Number(row.nodeId));
        if (!node) { this.setData({ themeBagError: '任务节点尚未加载，请稍后重试' }); return Promise.resolve(false); }
        this.setData({ themeBackpackOpen: false });
        if (row.operation === 'OPEN_OPERATION') {
          this.setData({ advancedPlay: { show: true, node }, playKit: { show: false, kit: null } });
        } else if (!node.hasAdvanced && node.vm === 5) {
          this.stopNav(); this.setData({ 'sheet.node': node }); this.onArriveTap();
        } else this.startGame(node);
        return Promise.resolve(true);
      }
      if ((row.operation === 'QUEST_START' || row.operation === 'OPERATION_START' || row.operation === 'FACILITY_USE') && row.costOptions.length && row.frozenIndex < 0) {
        this.setData({ themeCostChoice: { row, selected: -1 } }); return Promise.resolve(true);
      }
      return this._prepareThemeGameplayAction({ operation: row.operation, contentId: row.key }, row);
    },
    onThemeCancelGameplayAction(e) {
      const row = (this.data.themeBag.actionRows || [])[Number(e.currentTarget.dataset.index)];
      if (!row || !row.cancelable) return Promise.resolve(false);
      return this._themeGameplayCommand({ operation: row.kind === 'QUEST' ? 'QUEST_CANCEL' : 'OPERATION_CANCEL', contentId: row.key });
    },
    selectThemeCostOption(e) {
      const choice = this.data.themeCostChoice;
      const index = Number(e.currentTarget.dataset.index);
      if (choice && choice.row.costOptions[index] && choice.row.costOptions[index].affordable) this.setData({ 'themeCostChoice.selected': index });
    },
    closeThemeCostChoice() { this.setData({ themeCostChoice: null }); },
    confirmThemeCostChoice() {
      const choice = this.data.themeCostChoice;
      if (!choice || choice.selected < 0 || !choice.row.costOptions[choice.selected].affordable) return Promise.resolve(false);
      this.setData({ themeCostChoice: null });
      return this._prepareThemeGameplayAction({ operation: choice.row.operation, contentId: choice.row.key, costOption: choice.selected }, choice.row);
    },
    openThemeItem(e) {
      const item = this.data.themeBag.items.find(row => row.id === e.currentTarget.dataset.id);
      if (item) this.setData({ themeItemDetail: item });
    },
    closeThemeItem() { this.setData({ themeItemDetail: null }); },
    showThemeItemInfo() { this.setData({ 'themeItemDetail.showInfo': true }); },
    _prepareThemeGameplayAction(input, row) {
      if (!['QUEST_RESOLVE', 'FACILITY_USE'].includes(input.operation)) return this._themeGameplayCommand(input);
      if (input.operation === 'FACILITY_USE' && row.targetScope === 'PARTICIPANTS') {
        const view = this.data.themeBag;
        const choice = gameplay.participantChoice(view, input);
        const own = choice.rows && choice.rows.find(team => team.teamId === view.teamId && team.eligible === true);
        if (own && choice.payerRequired) return this._readThemeRewardPreview({ ...input, participants: [own.teamId] });
        this.setData({ themeRewardPreview: { input, participantRows: choice.rows, payerRequired: choice.payerRequired, pack: null, loading: false, error: choice.rows ? '参与目录缺少可参与的本队，请刷新后重试' : choice.error } });
        return Promise.resolve(false);
      }
      if (input.operation === 'QUEST_RESOLVE' && row.choices && row.choices.length > 1) {
        this.setData({ themeQuestChoice: { input, choices: row.choices } }); return Promise.resolve(true);
      }
      if (row.choices && row.choices.length === 1) input.choiceKey = row.choices[0].choiceKey;
      return this._readThemeRewardPreview(input);
    },
    selectThemeQuestChoice(e) {
      const sheet = this.data.themeQuestChoice;
      const choice = sheet && sheet.choices[Number(e.currentTarget.dataset.index)];
      if (!choice) return Promise.resolve(false);
      this.setData({ themeQuestChoice: null });
      return this._readThemeRewardPreview({ ...sheet.input, choiceKey: choice.choiceKey });
    },
    closeThemeQuestChoice() { this.setData({ themeQuestChoice: null }); },
    selectThemeParticipant(e) {
      const sheet = this.data.themeRewardPreview;
      if (!sheet || this._themeSending || this._gameplayPending) return Promise.resolve(false);
      const id = Number(e.currentTarget.dataset.id);
      const choice = gameplay.participantChoice(this.data.themeBag, sheet.input);
      const target = choice.rows && choice.rows.find(row => row.teamId === id);
      if (!target || target.eligible !== true || target.required) return Promise.resolve(false);
      const ids = choice.participants.includes(id) ? choice.participants.filter(key => key !== id) : choice.participants.concat(id);
      return this._readThemeRewardPreview({ ...sheet.input, participants: ids, discardRewards: [] });
    },
    _readThemeRewardPreview(input) {
      const view = this.data.themeBag;
      if (!view.sessionId || rewards.ended(view) || this._themeSending || this._themePending || this._gameplayPending || this._tradePending || this._planPending || this.data.isMock || this.data.isPreview) return Promise.resolve(false);
      const team = view.scope === 'TEAM';
      const sequence = this._themePreviewSequence = (this._themePreviewSequence || 0) + 1;
      const epoch = this._themeEpoch || 0;
      const participant = gameplay.participantChoice(view, input);
      if (participant.error) {
        this.setData({ themeRewardPreview: { input, participantRows: participant.rows, payerRequired: participant.payerRequired, pack: null, loading: false, error: participant.error } });
        return Promise.resolve(false);
      }
      const previous = this.data.themeRewardPreview;
      this.setData({ themeRewardPreview: { input, participantRows: participant.rows, payerRequired: participant.payerRequired, pack: previous && previous.pack, loading: true, error: '' } });
      const choices = {};
      for (const key of ['choiceKey', 'costOption', 'discardRewards']) if (input[key] !== undefined) choices[key] = input[key];
      if (participant.participants) choices.participants = participant.participants;
      const command = team ? { activityId: view.activityId, action: input.operation, payload: {
        [input.operation === 'FACILITY_USE' ? 'facilityKey' : 'questKey']: input.contentId, ...choices } }
        : { sessionId: view.sessionId, expectedVersion: view.version, operation: input.operation, contentId: input.contentId, ...choices };
      return req(team ? '/api/game/session/shared/preview' : '/api/play/theme/gameplay-preview', 'POST', command, { 'content-type': 'application/json' }, true).then(res => {
        if (sequence !== this._themePreviewSequence || epoch !== (this._themeEpoch || 0)) return false;
        const pack = res.code == 200 && res.data && rewards.preview(res.data, view);
        const valid = pack && (team ? Number.isSafeInteger(res.data.expectedRevision) && Number.isSafeInteger(res.data.authorityEpoch)
          : String(res.data.sessionId) === String(view.sessionId) && Number.isSafeInteger(res.data.version));
        this.setData({ themeRewardPreview: { input, participantRows: participant.rows, payerRequired: participant.payerRequired, pack: valid ? pack : null, loading: false,
          basis: valid ? { sessionId: view.sessionId, version: res.data.version, revision: res.data.expectedRevision, authorityEpoch: res.data.authorityEpoch } : null,
          error: valid ? '' : res.msg || '奖励预览暂未加载，请重新读取' } });
        return !!valid;
      });
    },
    changeThemeRewardDiscard(e) {
      const sheet = this.data.themeRewardPreview;
      if (!sheet || sheet.loading || !sheet.pack) return Promise.resolve(false);
      const owner = sheet.pack.owners.find(row => row.own);
      const row = owner && owner.rewards.find(item => item.itemId === e.currentTarget.dataset.id);
      const quantity = Number(e.detail.value);
      if (!row || !Number.isSafeInteger(quantity) || quantity < 0 || quantity > row.quantity) {
        this.setData({ 'themeRewardPreview.error': '放弃数量须为 0 到本次奖励数量之间的整数' }); return Promise.resolve(false);
      }
      const rows = owner.rewards.map(item => item === row ? { ...item, discard: quantity } : item);
      return this._readThemeRewardPreview({ ...sheet.input, discardRewards: rewards.selection(rows) });
    },
    rereadThemeRewardPreview() {
      const sheet = this.data.themeRewardPreview;
      if (!sheet || sheet.loading) return Promise.resolve(false);
      const reset = !sheet.pack && sheet.error && sheet.input.discardRewards;
      const input = reset ? { ...sheet.input, discardRewards: [] } : sheet.input;
      return this.refreshThemeBackpack().then(() => this._readThemeRewardPreview(input)).then(ok => {
        if (ok && reset) this.setData({ 'themeRewardPreview.notice': '已重新读取全部奖励，请重新选择要放弃的数量' });
        return ok;
      });
    },
    closeThemeRewardPreview() { this._themePreviewSequence = (this._themePreviewSequence || 0) + 1; this.setData({ themeRewardPreview: null }); },
    confirmThemeRewardPreview() {
      const sheet = this.data.themeRewardPreview;
      if (!sheet || sheet.loading || sheet.error || !sheet.pack || sheet.pack.overflow || this._themeSending) return Promise.resolve(false);
      this._themeCommandError = '';
      return this._themeGameplayCommand({ ...sheet.input, previewVersion: sheet.pack.previewVersion,
        expectedVersion: sheet.basis.version, expectedRevision: sheet.basis.revision, authorityEpoch: sheet.basis.authorityEpoch }).then(ok => {
        if (ok) this.closeThemeRewardPreview();
        else if (!this.data.themeBagUnknown && /预览.*过期|本局已更新|REVISION|STALE/.test(this._themeCommandError || '')) {
          return this._readThemeRewardPreview(sheet.input).then(loaded => {
            this.setData({ 'themeRewardPreview.notice': loaded ? '状态已变化，已重新读取奖励，请再次确认' : '状态已变化，请重新读取并调整放弃选择' }); return false;
          });
        } else this.setData({ 'themeRewardPreview.error': this.data.themeBagError || '操作失败，请核对后重试' });
        return ok;
      });
    },
    openThemePendingClaim(source) {
      const view = this.data.themeBag;
      if (!rewards.ended(view) || !rewards.canClaim(view, source)) return Promise.resolve(false);
      this.setData({ themePendingClaim: { source, reward: { ...view.pending[source], name: view.pending[source].name || rewards.itemName(view.pending[source].itemId, view) },
        owned: (view.items || view.contents || []).filter(row => row.kind === 'ITEM').map(row => ({ ...row, discard: 0 })), error: '' } });
      return Promise.resolve(true);
    },
    changeThemeOwnedDiscard(e) {
      const sheet = this.data.themePendingClaim;
      if (!sheet || this._themeSending || this._themePending || this._gameplayPending) return;
      const index = sheet.owned.findIndex(row => row.id === e.currentTarget.dataset.id);
      const quantity = Number(e.detail.value);
      if (!this.data.themeBag.allowDiscard || index < 0 || !Number.isSafeInteger(quantity) || quantity < 0 || quantity > sheet.owned[index].quantity) {
        this.setData({ 'themePendingClaim.invalid': true, 'themePendingClaim.error': '丢弃数量须为 0 到持有数量之间的整数' }); return;
      }
      this.setData({ ['themePendingClaim.owned.' + index + '.discard']: quantity, 'themePendingClaim.invalid': false, 'themePendingClaim.error': '' });
    },
    closeThemePendingClaim() { if (!this._themeSending) this.setData({ themePendingClaim: null }); },
    confirmThemePendingClaim() {
      const sheet = this.data.themePendingClaim;
      if (!sheet || sheet.invalid || this._themeSending) return Promise.resolve(false);
      return this._themeCommand('RECEIVE', sheet.source, rewards.selection(sheet.owned)).then(ok => {
        if (ok) this.setData({ themePendingClaim: null });
        else this.setData({ 'themePendingClaim.error': this.data.themeBagError || '领取失败，请核对库存后重试' });
        return ok;
      });
    },
    async _readThemeGameplayReceipt(failureMessage) {
      const pending = this._gameplayPending;
      if (!pending) return false;
      const { command } = pending, team = command.activityId != null;
      const epoch = this._themeEpoch || 0;
      this._themeSending = true; this.setData({ themeBagBusy: true });
      try {
        const result = team ? await client().readReceipt(command.activityId, command.requestId) : null;
        if (epoch !== (this._themeEpoch || 0)) return false;
        const receipt = result && result.receipt;
        const matched = receipt && receipt.action === command.action && receipt.requestId === command.requestId && String(receipt.activityId) === String(command.activityId);
        const applied = matched && result.status === 'matched';
        const failed = matched && result.status === 'business-error' && receipt.outcome === 'FAILED';
        if (applied || failed || failureMessage) this._gameplayPending = null;
        if (applied) this.setData({ themeReservationReceipt: gameplay.reservationReceipt(this.data.themeBag.actionRows, pending.input) });
        if (failureMessage) this._themeCommandError = failureMessage;
        if (applied && pending.input.previewVersion) this.closeThemeRewardPreview();
        await this.refreshThemeBackpack(); await this.refreshRouteState();
        if (epoch !== (this._themeEpoch || 0)) return false;
        this.setData({ themeBagUnknown: !applied && !failed && !failureMessage, themeBagError: applied ? '' : failureMessage || (failed ? result.message : (pending.error ? pending.error + '；' : '') + '操作结果待核对，请勿重复提交') });
        return !!applied;
      } finally { this._themeSending = false; if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagBusy: false }); }
    },
    _themeGameplayCommand(input) {
      const view = this.data.themeBag;
      if (this._themeSending || !view.sessionId || this.data.isPreview || this.data.isMock) return Promise.resolve(false);
      if (this._themePending || this._tradePending || this._planPending) { toast('请先核对上一条操作'); return Promise.resolve(false); }
      if (this._gameplayPending && JSON.stringify(this._gameplayPending.input) !== JSON.stringify(input)) { toast('请先核对上一条操作'); return Promise.resolve(false); }
      if (this._gameplayPending && input.operation.startsWith('QUEST_') && this.data.themeBagUnknown) return this._readThemeGameplayReceipt();
      if (!this._gameplayPending && rewards.ended(view) && (input.operation !== 'ITEM_RECEIVE' || !rewards.canClaim(view, input.contentId))) return Promise.resolve(false);
      const team = view.scope === 'TEAM';
      const payload = team ? { contentId: input.contentId } : null;
      if (team && input.operation.startsWith('QUEST_')) { delete payload.contentId; payload.questKey = input.contentId; }
      if (team && input.operation === 'OPERATION_START') { delete payload.contentId; payload.operationKind = 'FACILITY'; payload.operationKey = input.contentId; }
      if (team && input.operation === 'OPERATION_CANCEL') delete payload.contentId;
      if (team && input.operation === 'FACILITY_USE') { delete payload.contentId; payload.facilityKey = input.contentId; }
      if (team && input.operation === 'RECOVER') { delete payload.contentId; payload.recoveryKey = input.contentId; }
      if (team && input.operation.startsWith('ZERO_HP_')) delete payload.contentId;
      const selections = {};
      for (const key of ['costOption', 'choiceKey', 'discardRewards', 'previewVersion', 'discardOwned', 'weRun']) if (input[key] !== undefined) selections[key] = input[key];
      if (!this._gameplayPending) {
        const participant = gameplay.participantChoice(view, input);
        if (participant.error) { this.setData({ themeBagError: participant.error }); return Promise.resolve(false); }
        if (participant.participants) selections.participants = participant.participants;
      }
      if (team) Object.assign(payload, selections);
      const command = this._gameplayPending ? this._gameplayPending.command : team
        ? { activityId: view.activityId, requestId: 'pg-theme-' + Date.now() + '-' + Math.random().toString(36).slice(2), expectedRevision: input.expectedRevision === undefined ? view.revision : input.expectedRevision,
          action: input.operation, payload, authorityEpoch: input.authorityEpoch === undefined ? view.authorityEpoch : input.authorityEpoch }
        : Object.assign({ sessionId: view.sessionId, expectedVersion: input.expectedVersion === undefined ? view.version : input.expectedVersion,
          actionId: 'theme_' + Date.now() + Math.random().toString(36).slice(2), operation: input.operation, contentId: input.contentId },
          selections);
      this._gameplayPending = { input, command }; this._themeSending = true;
      this.setData({ themeBagBusy: true, themeBagError: '' });
      const epoch = this._themeEpoch || 0;
      return req(team ? '/api/game/session/command' : '/api/play/theme/gameplay-action', 'POST', command, { 'content-type': 'application/json' }, true).then(res => {
        if (epoch !== (this._themeEpoch || 0)) return false;
        const receipt = res.data || {};
        if (input.operation.startsWith('QUEST_') && res.code != 200 && !res.netFail && !res.httpFail) this._gameplayPending.error = res.msg || '操作失败';
        if (input.operation.startsWith('QUEST_') && (res.code != 200 || team && (receipt.replayed || receipt.outcome !== 'APPLIED' || receipt.requestId !== command.requestId || receipt.action !== command.action || String(receipt.activityId) !== String(command.activityId)))) return this._readThemeGameplayReceipt(!team && !res.netFail && !res.httpFail && res.code != 200 ? res.msg || '操作失败' : undefined);
        const sameReceipt = team && String(receipt.activityId) === String(command.activityId)
          && receipt.requestId === command.requestId && receipt.action === command.action
          && Number.isSafeInteger(receipt.receiptId) && receipt.receiptId > 0
          && Number.isSafeInteger(receipt.revision);
        if (res.code == 200 && (team ? sameReceipt && receipt.outcome === 'APPLIED'
          : res.data && String(receipt.sessionId) === String(command.sessionId) && Number.isSafeInteger(receipt.version))) {
          this._gameplayPending = null;
          this.setData({ themeBagUnknown: false, themeReservationReceipt: input.operation.startsWith('QUEST_') ? gameplay.reservationReceipt(view.actionRows, input) : this.data.themeReservationReceipt });
          const result = team ? receipt.result && (receipt.result.result || receipt.result) || {} : receipt.settlement || {};
          const text = trade.recoveryText(result, gameplay.labels(view).hp);
          if (text) { toast(text); this.setData({ themeRecoveryText: text }); }
          else if (input.operation === 'ITEM_RECEIVE') toast('待领奖励已领取');
          if (input.previewVersion) this.closeThemeRewardPreview();
          if (input.operation === 'ITEM_RECEIVE') this.setData({ themePendingClaim: null });
          const staleZeroReceipt = input.operation.startsWith('ZERO_HP_') && (String(receipt.sessionId) !== String(this.data.themeBag.sessionId) || this.data.themeBag.version > receipt.version);
          if (!team && !staleZeroReceipt) this._applyThemeView(receipt);
          return this.refreshThemeBackpack().then(() => input.operation.startsWith('QUEST_') ? this.refreshRouteState() : true).then(() => true);
        }
        if (input.operation.startsWith('QUEST_')) return this._readThemeGameplayReceipt();
        if (res.netFail || res.httpFail || res.code == 200 && (team ? !sameReceipt || receipt.outcome === 'PENDING' : true)) this.setData({ themeBagUnknown: true, themeBagError: '操作结果未确认，点击重试' });
        else {
          this._gameplayPending = null;
          const message = team && sameReceipt && receipt.outcome === 'FAILED' ? receipt.result && receipt.result.reason || '操作失败' : res.msg || '操作失败';
          this._themeCommandError = message;
          return this.refreshThemeBackpack().then(() => { this.setData({ themeBagUnknown: false, themeBagError: message }); return false; });
        }
        return false;
      }).catch(() => {
        if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagUnknown: true, themeBagError: '操作结果未确认，点击重试' });
        return false;
      }).finally(() => { this._themeSending = false; if (epoch === (this._themeEpoch || 0)) this.setData({ themeBagBusy: false }); });
    },
    openThemeChallenge(e) {
      const id = Number(e.currentTarget.dataset.id);
      const challenge = this.data.themeBag.challenge || {};
      if (rewards.ended(this.data.themeBag) || !this.data.themeBag.awaitingChallenge || challenge.disabledReason || !this.data.themeBag.challengeNodes.some(row => row.id === id)) return;
      const node = this._nodeById(id);
      if (!node) { this.setData({ themeBagError: '挑战节点尚未加载，请稍后重试' }); return; }
      this._themeChallengeNodeId = id;
      this.setData({ themeBackpackOpen: false, advancedPlay: { show: true, node }, playKit: { show: false, kit: null } });
    },
    _themeChapterIndex() {
      const chapterId = (this.data.chapter || {}).id;
      const binding = (this.data.themeBag.chapterBindings || []).find(c => String(c.chapterId) === String(chapterId));
      return binding ? binding.index : -1;
    },
    _endThemeChapter() {
      const index = this._themeChapterIndex();
      if ((this.data.nodes || []).some(n => String(n.chapterId) === String((this.data.chapter || {}).id) && !n.done)) return Promise.resolve(true);
      if (!(this.data.themeBag.chapterIndexes || []).includes(index)) return Promise.resolve(true);
      return this.refreshThemeBackpack().then(() => this._themeChapterCommand('CHAPTER_END', String(index)));
    },
    _enterThemeChapter() {
      if (!this.data.themeBag.sessionId || this.data.isPreview || this.data.isMock) return;
      const index = this._themeChapterIndex();
      if (index < 0 || !(this.data.themeBag.chapterIndexes || []).includes(index) || this._themeChapterEntered === index || this._themeSending) return;
      this._themeChapterEntered = index;
      this._themeChapterCommand('CHAPTER_START', String(index)).then(ok => { if (!ok) this._themeChapterEntered = null; });
    },
  };
  return bagMethods;
}
module.exports = { present, methods, data: Object.assign({}, trade.data, plans.data, { zeroHpView: null, zeroHpCountdown: '', zeroHpBusy: false, zeroHpError: '', zeroHpPermissionDenied: false, themeBag: { contents: [], items: [], clues: [], slots: [], pendingRows: [], actionRows: [], challengeNodes: [], planRows: [], tradeTargets: [], tradeRows: [], used: 0, capacity: 6, hpEnabled: false }, themeBackpackOpen: false, themeBackpackTab: 'items', themeBagTabs: [{ key: 'items', label: '物品' }, { key: 'clues', label: '线索' }, { key: 'game', label: '任务' }], themeCostChoice: null, themeReservationReceipt: null, themeRewardPreview: null, themePendingClaim: null, themeItemDetail: null, themeQuestChoice: null, themeBagBusy: false, themeBagUnknown: false, themeBagError: '' }) };
