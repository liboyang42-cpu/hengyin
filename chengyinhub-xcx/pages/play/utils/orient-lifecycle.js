'use strict';
// Lifecycle writes are separate from the local display clock and reward settlement.
const { id, integer, precise, settled, sameScope } = require('../../../utils/orient-score-view.js');
const modal = require('../../../utils/modal.js');
const BASE = '/api/play/orient/session';
const PHASES = { PREPARED: '待开始', RUNNING: '进行中', FINISHED: '已完成', ABANDONED: '已放弃', DISQUALIFIED: '已取消资格' };
const ok = body => body && Number(body.code) === 200;
const terminal = row => row && ['FINISHED', 'ABANDONED', 'DISQUALIFIED'].includes(row.phase);
function valid(row, scope) {
  return row && id(row.sessionId) && id(row.topicId) === scope.topicId
    && integer(row.activityId) === scope.activityId && PHASES[row.phase]
    && Number.isSafeInteger(row.configVersion) && row.configVersion >= 0
    && (row.startedAt == null || Number.isSafeInteger(row.startedAt) && row.startedAt >= 0)
    && (row.elapsedMs == null || Number.isSafeInteger(row.elapsedMs) && row.elapsedMs >= 0);
}
function display(row) {
  return Object.assign({}, row, { sessionId: id(row.sessionId), label: PHASES[row.phase],
    elapsedText: row.elapsedMs == null ? '尚未起表' : Math.floor(row.elapsedMs / 1000) + ' 秒（平台计时）',
    terminal: !!terminal(row) });
}
const data = { orientLifecycle: { open: false, ready: false, busy: false, pending: false, notice: '', row: null, sessions: [], tasks: [], reward: null } };
function methods(send) {
  const lifecycleMethods = {
    suspendOrientLifecycle() {
      this._orientEpoch = (this._orientEpoch || 0) + 1;
      this._orientConfirming = false;
      this._orientSet({ open: false, ready: false, busy: false, row: null, sessions: [] });
    },
    _orientCapture() {
      const app = getApp();
      return { owner: id(app.getUserID()), auth: typeof app.getAuthorization === 'function' ? app.getAuthorization() : '',
        topicId: id(this.data.topicId), activityId: integer(this.data.activityId || 0), epoch: this._orientEpoch };
    },
    _orientCurrent(c) {
      const now = this._orientCapture();
      return !this._playDisposed && !this._themeHidden && c.owner && c.topicId
        && c.owner === now.owner && c.auth === now.auth && c.topicId === now.topicId
        && c.activityId === now.activityId && c.epoch === now.epoch;
    },
    _orientKey(c) { return 'orient-lifecycle:' + c.owner + ':' + c.topicId + ':' + c.activityId; },
    _orientSet(patch) {
      this.setData({ orientLifecycle: Object.assign({}, this.data.orientLifecycle, patch) });
    },
    _orientPending(c) {
      const raw = wx.getStorageSync(this._orientKey(c));
      if (!raw) return null;
      if (!raw || raw.owner !== c.owner || raw.topicId !== c.topicId || raw.activityId !== c.activityId
          || !['prepare', 'start', 'abandon', 'task'].includes(raw.action) || !/^orient_[A-Za-z0-9_]{8,55}$/.test(raw.key)
          || !raw.payload || raw.payload.idempotencyKey !== raw.key
          || raw.action !== 'prepare' && !id(raw.payload.sessionId)
          || raw.action === 'prepare' && (raw.payload.topicId !== c.topicId || raw.payload.activityId !== c.activityId)
          || raw.action === 'task' && !id(raw.payload.taskId)
          || raw.action === 'start' && (!Number.isSafeInteger(raw.payload.ackConfigVersion) || raw.payload.ackConfigVersion < 0)) {
        throw new Error('Invalid pending lifecycle request');
      }
      return raw;
    },
    openOrientLifecycle() {
      if (this.data.isPreview || this.data.isMock || Number(this.data.mode) !== 1) return;
      if (this.data.orientLifecycle.busy || this._orientConfirming) return;
      this._orientEpoch = (this._orientEpoch || 0) + 1;
      const c = this._orientCapture();
      if (!this._orientCurrent(c)) return;
      this._orientSet({ open: true, ready: false, busy: true, row: null, sessions: [], reward: null, notice: '' });
      let pending;
      try { pending = this._orientPending(c); }
      catch (_) { this._orientSet({ busy: false, pending: true, notice: '待确认记录无法读取，请检查本机存储后重新查询' }); return; }
      this._orientSet({ pending: !!pending });
      return send(BASE + '/current', 'GET', { topicId: c.topicId, activityId: c.activityId }).then(body => {
        if (!this._orientCurrent(c)) return;
        if (!ok(body) || !body.data || !Array.isArray(body.data.sessions)
            || !body.data.sessions.every(row => valid(row, c))
            || new Set(body.data.sessions.map(row => id(row.sessionId))).size !== body.data.sessions.length) {
          this._orientSet({ busy: false, notice: body && body.err === 'ORIENT_SESSION_DISABLED'
            ? '此主题的定向对局流程尚未启用' : '暂时无法读取本人对局，请重新查询' }); return;
        }
        const rows = body.data.sessions.map(display);
        this._orientSet({ ready: true, sessions: rows, row: rows.length === 1 ? rows[0] : null, busy: false,
          tasks: (this.data.nodes || []).filter(node => node.done === true && id(node.nodeId))
            .map(node => ({ taskId: id(node.nodeId), name: node.name || node.title || '已完成任务' })) });
        if (pending) return this._orientRecover(c, pending, rows);
      }).catch(() => { if (this._orientCurrent(c)) this._orientSet({ busy: false, notice: '网络异常，请重新查询本人对局' }); });
    },
    chooseOrientSession(e) {
      if (this.data.orientLifecycle.busy || this.data.orientLifecycle.pending || this._orientConfirming) return;
      const chosen = id(e && e.currentTarget && e.currentTarget.dataset.sessionId);
      const row = this.data.orientLifecycle.sessions.find(item => item.sessionId === chosen);
      if (row && this._orientCurrent(this._orientCapture())) {
        this._orientSet({ row, reward: null, notice: '' });
      }
    },
    recordOrientTask(e) {
      const taskId = id(e && e.currentTarget && e.currentTarget.dataset.taskId);
      if (!(this.data.orientLifecycle.tasks || []).some(task => task.taskId === taskId)) return;
      return this._orientWrite('task', taskId);
    },
    prepareOrientSession() { return this._orientWrite('prepare'); },
    startOrientSession() { return this._orientConfirm('start'); },
    abandonOrientSession() { return this._orientConfirm('abandon'); },
    _orientConfirm(action) {
      const state = this.data.orientLifecycle, row = state.row;
      if (state.busy || state.pending || this._orientConfirming || !row || terminal(row)) return;
      const c = this._orientCapture();
      if (!this._orientCurrent(c)) return;
      this._orientConfirming = true;
      modal.show({ title: action === 'start' ? '确认本局规则并开始' : '放弃本局',
        content: action === 'start' ? '本局规则版本 ' + row.configVersion + '。开始后以平台计时为准，退出或暂停页面不会重置成绩计时。'
          : '放弃后本局不可恢复，也不会作为完成结局。是否继续？',
        confirmText: action === 'start' ? '确认开始' : '确认放弃',
        success: result => {
          this._orientConfirming = false;
          if (result.confirm && this._orientCurrent(c) && this.data.orientLifecycle.row === row) this._orientWrite(action);
        }, fail: () => { this._orientConfirming = false; } });
    },
    _orientWrite(action, taskId) {
      const c = this._orientCapture(), state = this.data.orientLifecycle, row = state.row;
      if (!this._orientCurrent(c) || state.busy || state.pending || this._orientConfirming
          || !state.open || !state.ready || !['prepare', 'start', 'abandon', 'task'].includes(action)) return;
      if (action !== 'prepare' && (!row || terminal(row) && !(action === 'task' && row.phase === 'FINISHED'))) return;
      if (action === 'task' && (!id(taskId) || row.startedAt == null
          || !(this.data.orientLifecycle.tasks || []).some(task => task.taskId === taskId))) return;
      // Creating/recovering a current session is explicit; never restart a terminal selection.
      if (action === 'prepare' && state.sessions.length) return;
      const key = 'orient_' + Date.now() + '_' + Math.random().toString(36).slice(2, 12);
      const payload = action === 'prepare' ? { topicId: c.topicId, activityId: c.activityId }
        : { sessionId: row.sessionId };
      if (action === 'start') payload.ackConfigVersion = row.configVersion;
      if (action === 'task') payload.taskId = taskId;
      payload.idempotencyKey = key;
      const pending = { owner: c.owner, topicId: c.topicId, activityId: c.activityId, action, key, payload };
      try {
        if (this._orientPending(c)) { this._orientSet({ pending: true, notice: '请先查询原请求结果' }); return; }
        wx.setStorageSync(this._orientKey(c), pending);
        if (JSON.stringify(this._orientPending(c)) !== JSON.stringify(pending)) throw new Error('Storage verification failed');
      } catch (_) { this._orientSet({ pending: true, notice: '无法保存原请求，暂不提交，请检查本机存储后重新查询' }); return; }
      this._orientSet({ busy: true, pending: true, notice: '' });
      const unknown = () => { if (this._orientCurrent(c)) this._orientSet({ busy: false, pending: true, notice: '结果待确认，请查询原请求；不会重复提交或重开计时' }); };
      // These endpoints bind form parameters, not JSON RequestBody.
      const endpoint = action === 'task' ? '/task/' + taskId + '/result' : '/' + action;
      return send(BASE + endpoint, 'POST', Object.assign({}, payload)).then(body => {
        if (!this._orientCurrent(c)) return;
        if ((ok(body) || Number(body && body.code) === 409 && body.err === 'ORIENT_TASK_ALREADY_COMPLETED')
            && this._orientMatches(body.data, pending, c)) return this._orientConfirmReceipt(c, pending);
        // Explicit precondition rejections have no write; transport/5xx/conflict remain UNKNOWN.
        if (body && Number(body.code) >= 400 && Number(body.code) < 500 && ['ORIENT_SESSION_DISABLED', 'ORIENT_ACK_REQUIRED', 'ORIENT_SESSION_TERMINAL',
          'ORIENT_PRODUCT_TYPE_UNSUPPORTED', 'ORIENT_TEAM_UNSUPPORTED', 'ORIENT_BAD_REQUEST',
          'ORIENT_TASK_EVIDENCE_MISSING', 'ORIENT_SESSION_NOT_STARTED', 'ORIENT_TASK_NOT_FOUND'].includes(body.err)) {
          if (!this._orientClear(c, pending)) { unknown(); return; }
          this._orientSet({ busy: false, pending: false, notice: body.msg || '本次操作未执行，请重新查询对局' });
          return;
        }
        unknown();
      }).catch(unknown);
    },
    _orientMatches(row, pending, c) {
      if (pending.action === 'task') return !!row && id(row.sessionId) === pending.payload.sessionId
        && id(row.taskId) === pending.payload.taskId && row.receiptId === 'orient:task:' + pending.payload.taskId
        && Number.isSafeInteger(row.completedAt) && Number.isSafeInteger(row.elapsedMs) && row.elapsedMs >= 0
        && ['RUNNING', 'FINISHED'].includes(row.phase) && row.alternativeTaskType == null;
      return valid(row, c) && (pending.action === 'prepare' || id(row.sessionId) === pending.payload.sessionId)
        && (pending.action !== 'start' || row.phase === 'RUNNING' && row.startedAt != null)
        && (pending.action !== 'abandon' || row.phase === 'ABANDONED');
    },
    _orientClear(c, pending) {
      try {
        const saved = this._orientPending(c);
        if (!saved || saved.key !== pending.key) return false;
        wx.removeStorageSync(this._orientKey(c));
        return !this._orientPending(c);
      } catch (_) { return false; }
    },
    _orientConfirmReceipt(c, pending) {
      if (!this._orientClear(c, pending)) {
        this._orientSet({ busy: false, pending: true, notice: '已收到结果，待确认记录尚未清理，请重新查询' }); return;
      }
      this._orientSet({ busy: false, pending: false });
      // Original receipts can be older than a terminal state. Always re-read current facts.
      return this.openOrientLifecycle();
    },
    _orientRecover(c, pending, rows) {
      const candidates = pending.action === 'prepare' ? rows : rows.filter(row => row.sessionId === pending.payload.sessionId);
      this._orientSet({ busy: true, notice: '正在查询原请求结果' });
      let found = false;
      return candidates.reduce((chain, row) => chain.then(() => {
        if (found || !this._orientCurrent(c)) return;
        return send(BASE + '/receipt', 'GET', { sessionId: row.sessionId, idempotencyKey: pending.key }).then(body => {
          if (this._orientCurrent(c) && ok(body) && this._orientMatches(body.data, pending, c)) found = true;
        }).catch(() => {});
      }), Promise.resolve()).then(() => {
        if (!this._orientCurrent(c)) return;
        if (found) return this._orientConfirmReceipt(c, pending);
        this._orientSet({ busy: false, pending: true, notice: '尚未确认原请求。未查到结果不代表未写入，请稍后重新查询' });
      });
    },
    readOrientRewards() {
      const c = this._orientCapture(), row = this.data.orientLifecycle.row;
      if (!row || !this._orientCurrent(c)) return;
      const seq = this._orientRewardSeq = (this._orientRewardSeq || 0) + 1;
      const current = () => this._orientCurrent(c) && seq === this._orientRewardSeq
        && this.data.orientLifecycle.row && this.data.orientLifecycle.row.sessionId === row.sessionId;
      this._orientSet({ reward: { state: 'loading', message: '正在独立查询奖励事实' } });
      return send('/api/orient/scoring/session/' + row.sessionId, 'GET', {}).then(body => {
        if (!current()) return;
        const value = body && body.data;
        if (!ok(body) || !precise(value) || !sameScope(value, { ...c, sessionId: row.sessionId })
            || !Object.prototype.hasOwnProperty.call(value, 'settlement')) throw new Error('Reward read unavailable');
        if (value.settlement === null) {
          this._orientSet({ reward: { state: 'unsettled', message: '尚无结算记录；完成结局不等于奖励已到账' } }); return;
        }
        if (!settled(value.settlement, row.sessionId)) throw new Error('Invalid settlement');
        const receipt = value.settlement;
        this._orientSet({ reward: { state: 'settled', message: '已取得平台结算记录',
          settlementId: id(receipt.settlementId), points: integer(receipt.pointsAwarded), xp: integer(receipt.xpAwarded) } });
      }).catch(() => { if (current()) this._orientSet({ reward: { state: 'unknown', message: '奖励事实暂未读回，请重新查询；不能据此判定未发放' } }); });
    },
    openOrientRewards() {
      const row = this.data.orientLifecycle.row;
      if (!row || !this._orientCurrent(this._orientCapture())) return;
      wx.navigateTo({ url: '/subpackageMember/orient-score/index?sessionId=' + encodeURIComponent(row.sessionId)
        + '&topicId=' + encodeURIComponent(id(this.data.topicId)) });
    }
  };
  return lifecycleMethods;
}
module.exports = { data, methods, valid };
