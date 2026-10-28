const { applyPreviewAction } = require('../../../../utils/game-preview-action.js');
const toast = require('../../../../utils/toast.js');
const { id, integer } = require('../../../../utils/orient-score-view.js');
const app = getApp();
const { safeUserMessage } = require('../../../../utils/transport/safe-user-message.js');
const { buildActionKey, buildUnitId } = require('../../utils/play-action-key.js');
const { canContinue, isR5, feedbackWritable } = require('../../utils/playkit-r5-segments.js');

const { bundledMerchantGameAudioPath } = require('../../utils/merchant-game-assets.js');

Component({
  properties: {
    show: { type: Boolean, value: false },
    hidePanel: { type: Boolean, value: false },
    node: { type: Object, value: null },
    activityId: { type: null, value: '' },
    topicId: { type: null, value: '' },
    preview: { type: Boolean, value: false },
    mock: { type: Boolean, value: false }
  },
  data: {
    loading: false,
    acting: false,
    error: '',
    // unknown：请求已发出但结果未知。不是失败，也不能当成功——保持锁定，
    // 所有局只读原请求回执；负向查询不解除未知状态。
    unknown: false,
    unknownText: '',
    readingBack: false,
    state: null,
    currentStep: null,
    members: [],
    remainingSeconds: null,
    leaderboard: [],
    leaderboardLoading: false,
    leaderboardError: false,
    playingDrawId: ''
  },
  observers: {
    'show,node.nodeId': function (show, nodeId) {
      if (show && nodeId) this.start();
      if (!show) { this._requestEpoch = (this._requestEpoch || 0) + 1; this.stopTimer(); this.stopDrawAudio(); this.stopBackgroundMusic(); }
    }
  },
  lifetimes: { detached() { this._requestEpoch = (this._requestEpoch || 0) + 1; this.stopTimer(); this.destroyDrawAudio(); this.stopBackgroundMusic(); } },
  pageLifetimes: { hide() { this.stopDrawAudio(); this.stopBackgroundMusic(); } },
  methods: {
    sessionRequestContext() {
      return { memberId: String(app.getUserID() || ''),
        sessionId: String((this.data.state || {}).sessionId || ''),
        nodeId: String((this.data.node || {}).nodeId || ''),
        topicId: String(this.data.topicId || ''), activityId: String(this.data.activityId || ''),
        epoch: this._requestEpoch || 0 };
    },
    currentRequestContext(context, requireSession = true) {
      const current = this.sessionRequestContext();
      return current.memberId === context.memberId
        && current.epoch === context.epoch && current.nodeId === context.nodeId
        && current.topicId === context.topicId && current.activityId === context.activityId
        && (!requireSession || current.sessionId === context.sessionId);
    },
    responseMatchesSession(state, context, action) {
      if (!state) return false;
      const same = String(state.sessionId || '') === context.sessionId;
      const old = this.data.state || {};
      const retry = action === 'RETRY' && !!old.challenge
        && String(state.retrySourceSessionId || '') === context.sessionId
        && !!id(state.sessionId)
        && state.ownerType === old.ownerType && String(state.ownerId) === String(old.ownerId)
        && String(state.nodeId) === context.nodeId
        && (!context.topicId || String(state.topicId) === context.topicId)
        && (!context.activityId || String(state.activityId) === context.activityId);
      return (same || retry) && (state.nodeId == null || !context.nodeId || String(state.nodeId) === context.nodeId);
    },
    request(url, method, data) {
      return new Promise((resolve, reject) => app.sendRequest({
        hideLoading: true,
        url,
        method,
        data: method === 'GET' ? data : JSON.stringify(data || {}),
        header: method === 'GET' ? undefined : { 'Content-Type': 'application/json' },
        success: resolve,
        successStatusAbnormal: reject,
        fail: reject
      }));
    },
    pendingKey() {
      const scope = this.sessionRequestContext();
      return 'advanced-original:' + [scope.memberId, scope.topicId, scope.activityId, scope.nodeId].join(':');
    },
    start() {
      const node = this.data.node || {};
      const previousStart = this._startingContext;
      if (this.data.loading && previousStart && this.currentRequestContext(previousStart, false)) return;
      this._requestEpoch = (this._requestEpoch || 0) + 1;
      const context = this._startingContext = this.sessionRequestContext();
      this._unknownCommand = null;
      if (this.data.preview || this.data.mock) {
        const config = node.advancedConfig || {};
        this._previewTypeStartedAt = 0;
        const previewKit = this.data.preview ? node.previewKit || null : null;
        this.hydrate({ nodeId: node.nodeId, previewKit, sessionId: 'preview', status: 'RUNNING', version: 0, config,
          draws: [], branch: config.branch && config.branch.enabled
            ? { currentStepId: config.branch.startStepId, visitedStepIds: [config.branch.startStepId] } : null,
          multiplayer: config.multiplayer && config.multiplayer.enabled
            ? { roles: {}, members: [], completedUnitIds: [], turnIndex: 0 } : null,
          deadlineAt: config.timer && config.timer.enabled ? Date.now() + config.timer.durationSeconds * 1000 : null,
          readyForBase: previewKit ? node.previewReadyForBase === true : !(config.random && config.random.enabled) && !(config.branch && config.branch.enabled)
            && !(config.multiplayer && config.multiplayer.enabled) });
        return;
      }
      try {
        const pending = wx.getStorageSync(this.pendingKey());
        if (pending) {
          this._unknownCommand = pending.command;
          this.setData({ loading: false, acting: false, unknown: true, readingBack: false,
            state: pending.state, error: '', unknownText: '正在查询原请求结果，请勿重新提交' });
          return this.resolveUnknown();
        }
      } catch (_) {
        this.setData({ loading: false, unknown: true, error: '原请求记录无法读取，暂不启动新动作' });
        return;
      }
      const scope = { activityId: integer(this.data.activityId || 0),
        topicId: integer(this.data.topicId || 0), nodeId: integer(node.nodeId || 0) };
      if (Object.values(scope).some(value => !value)) {
        this.setData({ loading: false, error: '玩法标识不完整，请重新进入' }); return;
      }
      this.setData({ loading: true, acting: false, unknown: false, readingBack: false, error: '', state: null });
      this.request('/api/play/advanced/start', 'POST', scope).then(res => {
        if (!this.currentRequestContext(context, false)) return;
        if (res.code == 200 || res.code == '200') this.hydrate(res.data || {});
        else this.setData({ error: safeUserMessage(res, '高级玩法启动失败') });
      }).catch(() => { if (this.currentRequestContext(context, false)) this.setData({ error: '网络错误，请重试' }); })
        .finally(() => { if (this.currentRequestContext(context, false)) this.setData({ loading: false }); });
    },
    /** 把服务端权威视图抛给页面:v5.1 新玩法的壳是独立 sheet,套不进本面板(sheet 套 sheet),
     *  由页面在面板之外渲染。这里只递数据,不替页面决定弹哪个。 */
    emitSession(state) {
      this.triggerEvent('session', {
        sessionId: state && state.sessionId,
        nodeId: state && state.nodeId,
        version: state && state.version,
        ownerType: state && state.ownerType,
        status: state && state.status,
        canRetry: !!(state && state.canRetry === true),
        score: state && state.score,
        completedUnits: state && state.completedUnits,
        challenge: state && state.challenge,
        config: state && state.config,
        check: state && state.check,
        deadlineAt: state && state.deadlineAt,
        present: state && state.present,
        playKit: (state && state.playKit) || null,
        config: state && state.config,
        branch: state && state.branch,
        previewKit: this.data.preview && state ? state.previewKit || null : null,
        readyForBase: !!(state && state.readyForBase),
        /* 故事变量 map:{名字} 替换用。服务端不下发时是 undefined,页面按「没值」处理
           (没兜底就原样留花括号,不渲染成空串)。 */
        vars: (state && state.vars) || null,
        /* 拍物成卡:判过这一次的回包带着刚铸的卡(契约 §10.2「当场看到卡」)。只有那一次动作的回包有,
           回读 /state 没有 —— 页面据此当场揭晓。丢在这一层,那一屏就永远只说「这张过了」。 */
        objectCard: (state && state.objectCard) || null,
      });
    },
    /** 这一步没提交成功:页面据此让拍物成卡那一屏回到取景,别让玩家对着「处理中」干等。 */
    emitActionFail(action, sessionId) {
      this.triggerEvent('actionfail', { action: action || '',
        sessionId: sessionId == null ? (this.data.state || {}).sessionId : sessionId });
    },
    staleSnapshot(state) {
      const current = this.data.state || {};
      return String(state.sessionId || '') === String(current.sessionId || '')
        && Number(state.version) < Number(current.version);
    },
    hydrate(state) {
      if (state.nodeId != null && this.data.node && this.data.node.nodeId != null
          && String(state.nodeId) !== String(this.data.node.nodeId)) return;
      if (this.staleSnapshot(state)) {
        // 成功动作的当次卡片不是旧快照，仍递交给当前同局页面。
        if (state.objectCard) this.emitSession(Object.assign({}, this.data.state, { objectCard: state.objectCard }));
        return false;
      }
      const config = Object.assign({
        timer: { enabled: false }, random: { enabled: false, drawCount: 0, items: [] },
        branch: { enabled: false, steps: [] }, leaderboard: { enabled: false },
        multiplayer: { enabled: false, roles: [], requiredTurns: 1 }
      }, state.config || {});
      state.config = config;
      state.draws = state.draws || [];
      const roleAssignments = (state.multiplayer || {}).roleAssignments || (state.multiplayer || {}).roles;
      state.multiplayer = Object.assign({ members: [], completedUnitIds: [], turnIndex: 0 }, state.multiplayer || {});
      const branch = state.branch || {};
      const steps = config.branch && Array.isArray(config.branch.steps) ? config.branch.steps : [];
      const currentStep = steps.find(step => step.id === branch.currentStepId) || null;
      const multi = state.multiplayer || {};
      const roles = roleAssignments || {};
      const roleDefinitions = config.multiplayer.roles || [];
      const members = (multi.members || []).map(member => {
        const roleId = roles[String(member.memberId)] || '';
        const role = roleDefinitions.find(item => item.id === roleId);
        return Object.assign({}, member, { roleId,
          roleText: role && role.label || roleId || (roleAssignments ? '待分配' : '角色仅本人可见') });
      });
      this.setData({ state, currentStep, members, error: '' }, () => {
        this.syncTimer();
        this.syncBackgroundMusic(config.backgroundMusic);
        if (config.leaderboard && config.leaderboard.enabled) this.loadLeaderboard();
      });
      this.emitSession(state);
      return true;
    },
    retry() {
      const state = this.data.state;
      if (this.data.loading || this.data.acting || this.data.unknown || !state || state.canRetry !== true
          || !(['FAILED', 'EXPIRED'].includes(state.status) || state.status === 'COMPLETED' && !!state.challenge)) return false;
      return this.action('RETRY', {});
    },
    action(name, payload) {
      if (this.data.acting || this.data.unknown || !this.data.state) return;
      if (name === 'CHOOSE' && isR5(this.data.state)
          && !feedbackWritable(this.data.state, { stepId: (this.data.currentStep || {}).id })) return;
      if (this.data.preview || this.data.mock) return this.previewAction(name, payload || {});
      const context = this.sessionRequestContext();
      const sessionId = this.data.state.sessionId;
      const version = this.data.state.version;
      const body = payload || {};
      // 幂等键由「session + 服务端 version + 动作 + 入参」派生：同一次意图重试复用同一个键，
      // 服务端 selectByIdempotencyKey 才能 replay；写成功后 version 前进，下一动作自动换键。
      const idempotencyKey = buildActionKey(sessionId, version, name, body);
      if (!id(sessionId) || !idempotencyKey) {
        this.setData({ error: '当前玩法状态不完整，请退出节点后重新进入' });
        return;
      }
      this.setData({ acting: true, error: '' });
      const command = {
        sessionId, version, idempotencyKey, action: name, payload: body
      };
      // Persist only original identity, not answers/photos or the command payload.
      const original = { sessionId, version, idempotencyKey, action: name };
      const originalState = { sessionId, version, nodeId: this.data.state.nodeId,
        ownerId: this.data.state.ownerId, ownerType: this.data.state.ownerType,
        challenge: this.data.state.challenge ? {} : null };
      const pendingKey = this.pendingKey();
      try { wx.setStorageSync(pendingKey, { command: original, state: originalState }); }
      catch (_) { this.setData({ acting: false, error: '原请求记录无法保存，尚未提交' }); return; }
      this._unknownCommand = original;
      return this.request('/api/play/advanced/action', 'POST', command).then(res => {
        if (!this.currentRequestContext(context)) return;
        if (res.code == 200 || res.code == '200') {
          if (!this.responseMatchesSession(res.data, context, name)) throw new Error('session response mismatch');
          wx.removeStorageSync(pendingKey);
          this._unknownCommand = null;
          this.hydrate(res.data);
          if (!this.currentRequestContext(context, false)) return;
          this.setData({ acting: false });
          return true;
        }
        if (Number(res.code) === 409 && res.data && this.responseMatchesSession(res.data.view, context)) {
          wx.removeStorageSync(pendingKey);
          this._unknownCommand = null;
          this.hydrate(res.data.view);
          this.setData({ acting: false, error: safeUserMessage(res, '玩法状态已更新，请确认后再操作') });
          this.emitActionFail(name, sessionId);
          return false;
        }
        // Explicit 4xx come from ServiceException inside the @Transactional act: rolled back, nothing written.
        const status = Number(res.code);
        if (status >= 400 && status < 500 && status !== 408) {
          wx.removeStorageSync(pendingKey);
          this._unknownCommand = null;
          this.setData({ acting: false, error: safeUserMessage(res, '操作失败，请重试') });
          this.emitActionFail(name, sessionId);
          return false;
        }
        // act can commit before finishPendingCardMint fails. A generic error is not proof of rollback.
        this._unknownAction = name;
        this.enterUnknown(version);
        return false;
      }).catch(() => {
        if (!this.currentRequestContext(context)) return;
        this._unknownAction = name;
        this._unknownChallengeGame = body.game;
        this._unknownCommand = original;
        this.enterUnknown(version);
      });
    },
    /** 请求没拿到响应：服务端可能已写入，只按原意图核对，不能生成新键重复提交。 */
    enterUnknown(sentVersion) {
      this.setData({
        acting: false, unknown: true, error: '',
        unknownText: '结果还没确认，正在确认，请勿重复提交'
      });
      this._unknownVersion = sentVersion;
      this.resolveUnknown();
    },
    resolveUnknown() {
      if (this.data.readingBack || !this._unknownCommand || !this.data.state) return Promise.resolve(false);
      const original = this._unknownCommand;
      const context = this.sessionRequestContext();
      const pendingKey = this.pendingKey();
      this.setData({ readingBack: true });
      return this.request('/api/play/advanced/receipt', 'GET', {
        sessionId: original.sessionId, idempotencyKey: original.idempotencyKey
      }).then(res => {
        if (!this.currentRequestContext(context)) return false;
        const receipt = res.data || {};
        if (Number(res.code) !== 200 || String(receipt.sessionId) !== String(original.sessionId)
            || receipt.idempotencyKey !== original.idempotencyKey
            || !this.responseMatchesSession(receipt.view, context, original.action)) throw new Error('Original receipt unconfirmed');
        // hydrate already rejects older versions. A historical receipt proves this request only.
        wx.removeStorageSync(pendingKey);
        this.hydrate(receipt.view);
        if (!this.currentRequestContext(context, false)) return true;
        this._unknownCommand = null;
        this.setData({ readingBack: false, unknown: false, unknownText: '', error: '' });
        return true;
      }).catch(() => {
        if (!this.currentRequestContext(context)) return false;
        this.setData({ readingBack: false, unknown: true,
          unknownText: '原请求仍待确认，未查到不代表未提交；请勿重复提交，核对原结果' });
        return false;
      });
    },
    previewKitAction(event) {
      if (!this.data.preview || !this.data.state || !this.data.state.previewKit) return false;
      const result = applyPreviewAction(this.data.state.previewKit, event, this._previewTypeStartedAt);
      if (!result) return false;
      this._previewTypeStartedAt = result.startedAt;
      // 多段题目：本段完成后进入下一段，全部完成才算可继续
      const queue = Array.isArray(result.kit.queue) ? result.kit.queue : [];
      const next = result.ready && queue.length ? Object.assign({}, queue[0], { queue: queue.slice(1) }) : null;
      const state = Object.assign({}, this.data.state, {
        previewKit: next || result.kit, version: this.data.state.version + 1,
        readyForBase: this.data.state.readyForBase || (result.ready && !next),
      });
      if (result.kit.type === 'profile' && result.kit.done) state.vars = result.kit.answers;
      this.hydrate(state);
      if (result.message) toast(result.message);
      return true;
    },
    previewAction(name, payload) {
      const state = JSON.parse(JSON.stringify(this.data.state));
      if (name === 'DRAW') {
        const items = (state.config.random.items || []).filter(item => !(state.draws || []).some(row => row.id === item.id));
        if (items.length) (state.draws || (state.draws = [])).push(items[0]);
      } else if (name === 'CHOOSE') {
        const option = (this.data.currentStep.options || []).find(row => row.id === payload.optionId);
        if (option) state.branch.currentStepId = option.nextStepId;
      } else if (name === 'COMPLETE_UNIT') {
        const units = state.multiplayer.completedUnitIds || (state.multiplayer.completedUnitIds = []);
        units.push(payload.unitId);
      }
      const randomReady = !(state.config.random && state.config.random.enabled)
        || state.draws.length >= Number(state.config.random.drawCount);
      const branchStep = state.config.branch && (state.config.branch.steps || []).find(s => s.id === (state.branch || {}).currentStepId);
      const branchReady = !(state.config.branch && state.config.branch.enabled) || (branchStep && branchStep.terminal);
      const multiReady = !(state.config.multiplayer && state.config.multiplayer.enabled)
        || (state.multiplayer.completedUnitIds || []).length >= Number(state.config.multiplayer.requiredTurns || 1);
      state.readyForBase = randomReady && branchReady && multiReady;
      state.version += 1;
      this.hydrate(state);
    },
    draw() { this.action('DRAW', {}); },
    initDrawAudio() {
      if (this._drawAudio) return;
      const audio = wx.createInnerAudioContext();
      audio.obeyMuteSwitch = false;
      audio.onPlay(() => {});
      audio.onPause(() => this.setData({ playingDrawId: '' }));
      // 切换音频时 stop 可能晚于新 play 回调 这里不能把新播放态清掉
      audio.onStop(() => {});
      audio.onEnded(() => this.setData({ playingDrawId: '' }));
      audio.onError(() => {
        this.setData({ playingDrawId: '' });
        toast('音频加载失败');
      });
      this._drawAudio = audio;
    },
    toggleDrawAudio(e) {
      const id = String(e.currentTarget.dataset.id || '');
      let url = String(e.currentTarget.dataset.url || '');
      if (!id || !url) return;
      if (url.startsWith('/audio/merchant-games/')) {
        url = bundledMerchantGameAudioPath(url);
        if (!url) {
          toast('音频素材不存在');
          return;
        }
      }
      this.initDrawAudio();
      if (this.data.playingDrawId === id) {
        this._drawAudio.pause();
        this.setData({ playingDrawId: '' });
        return;
      }
      this._drawAudio.stop();
      this._drawAudio.src = url;
      this._drawAudio.play();
      this.setData({ playingDrawId: id });
    },
    stopDrawAudio() {
      if (!this._drawAudio) return;
      this._drawAudio.stop();
      this.setData({ playingDrawId: '' });
    },
    /* 背景音乐(模板互动工具,2026-10-22):玩这一关时播放,离开 / 收起 / 切后台就停。
       跟随静音键 —— 它是氛围音,不是玩家主动点开的内容。 */
    syncBackgroundMusic(music) {
      const src = music && typeof music.audioUrl === 'string' ? music.audioUrl : '';
      if (!src || !this.data.show) { this.stopBackgroundMusic(); return; }
      if (this._bgm && this._bgmSrc === src) return;
      this.stopBackgroundMusic();
      const audio = wx.createInnerAudioContext();
      audio.obeyMuteSwitch = true;
      audio.loop = music.loop !== false;
      audio.src = src;
      audio.onError(() => this.stopBackgroundMusic());
      audio.play();
      this._bgm = audio; this._bgmSrc = src;
    },
    stopBackgroundMusic() {
      if (!this._bgm) return;
      try { this._bgm.stop(); this._bgm.destroy(); } catch (e) { /* 已销毁 */ }
      this._bgm = null; this._bgmSrc = '';
    },
    destroyDrawAudio() {
      if (!this._drawAudio) return;
      this._drawAudio.destroy();
      this._drawAudio = null;
      this.setData({ playingDrawId: '' });
    },
    choose(e) { this.action('CHOOSE', { optionId: e.currentTarget.dataset.id }); },
    completeUnit() {
      const state = this.data.state || {};
      // unitId 也必须稳定：带时间戳的话重试会申报成「另一个轮次」，
      // 服务端那道 containsText 去重根本拦不住。
      this.action('COMPLETE_UNIT', { unitId: buildUnitId(state.sessionId, state.version) });
    },
    assignRole(e) {
      this.action('ASSIGN_ROLE', {
        memberId: Number(e.currentTarget.dataset.memberid), roleId: e.currentTarget.dataset.roleid
      });
    },
    loadLeaderboard() {
      if (this.data.preview || this.data.mock || this.data.leaderboardLoading) return;
      const context = this.sessionRequestContext();
      this.setData({ leaderboardLoading: true, leaderboardError: false });
      this.request('/api/play/advanced/leaderboard', 'GET', {
        activityId: Number(this.data.activityId) || 0,
        topicId: Number(this.data.topicId) || 0,
        nodeId: Number((this.data.node || {}).nodeId) || 0
      }).then(res => {
        if (!this.currentRequestContext(context)) return;
        if (res.code == 200 || res.code == '200') {
          const metric = this.data.state.config.leaderboard.metric;
          const rows = (res.data || []).map(row => Object.assign({}, row, {
            metricValue: metric === 'ELAPSED_TIME' ? `${row.elapsedSeconds || 0}s`
              : (metric === 'COMPLETED_UNITS' ? (row.completedUnits || 0) : (row.score || 0))
          }));
          this.setData({ leaderboard: rows });
          return;
        }
        this.setData({ leaderboardError: true });
      })
        // request 的 fail 是 reject:原来没有 catch,失败在控制台是一条 unhandled rejection,
        // 而榜单区既不转也不说 —— 玩家只能反复点「刷新」。落一个行内失败态,可再点刷新。
        .catch(() => { if (this.currentRequestContext(context)) this.setData({ leaderboardError: true }); })
        .finally(() => { if (this.currentRequestContext(context)) this.setData({ leaderboardLoading: false }); });
    },
    syncTimer() {
      this.stopTimer();
      const deadline = this.data.state && Number(this.data.state.deadlineAt);
      if (!deadline) { this.setData({ remainingSeconds: null }); return; }
      const r5 = (this.data.state.config || {}).outcomeRulesVersion === 1;
      const tick = () => {
        const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
        this.setData({ remainingSeconds: left });
        if (!left) { this.stopTimer(); if (!this.data.preview && !this.data.mock && (!r5 || this.data.state.status === 'RUNNING')) this.refreshState(); }
      };
      tick();
      if (!r5 || deadline > Date.now()) this._timer = setInterval(tick, 1000);
    },
    refreshState() {
      const state = this.data.state;
      if (!state || !state.sessionId) return Promise.resolve(false);
      const context = this.sessionRequestContext();
      return this.request('/api/play/advanced/state', 'GET', { sessionId: state.sessionId })
        .then(res => {
          if (!this.currentRequestContext(context) || !this.responseMatchesSession(res.data, context)) return false;
          if (res.code == 200 || res.code == '200') return this.hydrate(res.data) !== false;
          return false;
        })
        .catch(() => false);
    },
    stopTimer() { if (this._timer) { clearInterval(this._timer); this._timer = null; } },
    continueBase() {
      if (!canContinue(this.data.state)) return;
      this.triggerEvent('ready', { nodeId: (this.data.node || {}).nodeId });
    },
    close() { this.triggerEvent('close'); },
    // 遮罩 catchtouchmove 吃掉滑动,弹层打开时底下页面不跟着滚
    noop() {}
  }
});
