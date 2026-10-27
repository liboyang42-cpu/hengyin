'use strict';
const app = getApp();
const view = require('../../utils/orient-score-view.js');
const modal = require('../../utils/modal.js');
const { createVerificationWorkflow } = require('../../utils/verification-readback.js');
const BASE = '/api/orient/scoring';
const ok = response => response && Number(response.code) === 200;

Page({
  data: { state: 'loading', stateTitle: '', score: null, pending: null, busy: false,
    notice: '', form: '', reason: '', nodeId: '', entryId: '', formError: '', sessions: [], canChoose: false },
  onLoad(options) { this._scope = view.scope(options); this._epoch = 0; this._hidden = false; },
  onShow() { this._hidden = false; this.load(); },
  onHide() { this.suspend(); },
  onUnload() { this.suspend(); },
  suspend() {
    this._hidden = true; this._epoch += 1;
    this._confirming = false; this._recovering = false;
    if (this._workflow) this._workflow.destroy();
    this._workflow = null;
    this._identity = null;
    this.setData({ score: null, form: '', reason: '', nodeId: '', entryId: '', busy: false });
  },
  identity() { return { owner: view.id(app.getUserID()), auth: app.getAuthorization() }; },
  current(capture) {
    const now = this.identity();
    return !this._hidden && capture && capture.epoch === this._epoch
      && capture.owner === now.owner && capture.auth === now.auth;
  },
  capture() { return Object.assign({ epoch: this._epoch }, this.identity()); },
  load() {
    if (this._hidden) return;
    const identity = this.identity();
    const changed = !this._identity || this._identity.owner !== identity.owner || this._identity.auth !== identity.auth;
    if (this.data.busy && !changed) return;
    this._epoch += 1;
    this._recovering = false;
    if (!this._scope) { this.setData({ state: 'missing-param', stateTitle: '缺少合法对局参数', score: null }); return; }
    if (!identity.owner || !identity.auth) {
      if (this._workflow) this._workflow.destroy();
      this._workflow = null; this._identity = null;
      this.setData({ state: 'no-permission', stateTitle: '请登录本人账号查看对局', score: null, form: '', reason: '', busy: false, pending: null, sessions: [] }); return;
    }
    if (changed) {
      if (this._workflow) this._workflow.destroy();
      this._workflow = createVerificationWorkflow({ storage: wx, requestIdentity: true, getOwner: () => this.identity().owner });
      this._identity = identity;
      this._sessionId = this._scope.sessionId;
      this._confirming = false;
      this.setData({ score: null, pending: null, form: '', reason: '', nodeId: '', entryId: '', notice: '', busy: false, sessions: [], canChoose: false });
    }
    const capture = this.capture();
    this.setData({ state: 'loading' });
    if (this._sessionId) this.readScore(capture);
    else this.readSessions(capture);
  },
  readSessions(capture) {
    if (!this._scope.topicId) { this.setData({ state: 'missing-param', stateTitle: '请从带主题信息的对局记录进入' }); return; }
    const data = { topicId: this._scope.topicId };
    if (this._scope.activityId) data.activityId = this._scope.activityId;
    app.sendRequest({ url: '/api/play/orient/session/current', method: 'GET', data, hideLoading: true,
      success: response => {
        if (!this.current(capture)) return;
        const sessions = ok(response) && view.sessions(response.data, this._scope);
        if (!sessions) { this.readFailure(response); return; }
        this.setData({ sessions, canChoose: sessions.length > 1 });
        if (!sessions.length) { this.setData({ state: 'empty', stateTitle: '没有可查看的本人对局', score: null }); return; }
        if (sessions.length === 1) { this._sessionId = sessions[0].sessionId; this.readScore(capture); return; }
        this.setData({ state: 'choosing', score: null, pending: null, form: '', reason: '' });
      }, fail: (body, status) => { if (this.current(capture)) this.readFailure(body, status); },
      successStatusAbnormal: (body, status) => { if (this.current(capture)) this.readFailure(body, status); }
    });
  },
  chooseSession(event) {
    if (!this.current(Object.assign({ epoch: this._epoch }, this._identity || {}))) { this.load(); return; }
    const sessionId = view.id(event && event.currentTarget && event.currentTarget.dataset.sessionId);
    if (this._hidden || this.data.state !== 'choosing' || !this.data.sessions.some(row => row.sessionId === sessionId)) return;
    this._sessionId = sessionId; this._epoch += 1;
    this.setData({ state: 'loading', pending: null, notice: '', form: '', reason: '' });
    this.readScore(this.capture());
  },
  chooseAnother() {
    if (this.data.busy || this._confirming || !this._scope || this._scope.sessionId || !this._scope.topicId) return;
    this._sessionId = ''; this.load();
  },
  readScore(capture) {
    const target = Object.assign({}, this._scope, { sessionId: this._sessionId });
    app.sendRequest({ url: BASE + '/session/' + this._sessionId, method: 'GET', hideLoading: true,
      success: response => {
        if (!this.current(capture)) return;
        const score = ok(response) && view.normalize(response.data, target);
        if (!score) { this.readFailure(response); return; }
        this.setData({ state: 'ready', score });
        this.restorePending();
      },
      fail: (body, status) => { if (this.current(capture)) this.readFailure(body, status); },
      successStatusAbnormal: (body, status) => { if (this.current(capture)) this.readFailure(body, status); }
    });
  },
  readFailure(body, status) {
    const state = view.failure(body, status);
    this.setData({ state: state.kind, stateTitle: state.title, score: null, form: '', reason: '' });
  },
  restorePending() {
    const score = this.data.score;
    if (!score || !this._workflow) return;
    try {
      const pending = view.KINDS.map(kind => {
        const key = view.writeKey(this._identity.owner, score.sessionId, kind);
        const request = this._workflow.pendingRequest(key);
        return request && Object.assign({ kind, key }, request);
      }).filter(Boolean);
      this._pending = pending;
      this.setData({ pending: pending[0] || null });
      if (pending.length) this.recover();
    } catch (error) {
      this._pending = [];
      this.setData({ pending: { kind: 'STORAGE' }, notice: '待办无法读取，请检查本机存储后刷新；暂不提交新操作' });
    }
  },
  recover() {
    if (this._recovering || this.data.busy || !this._pending || !this._pending.length) return;
    const pending = this._pending[0];
    if (!pending.requestId) {
      this.setData({ notice: '同步结果待确认；已刷新计分，但仍无法确认本次同步是否完成。请勿重复同步，无法确认时联系平台处理。' }); return;
    }
    const capture = this.capture(), sessionId = this.data.score && this.data.score.sessionId;
    if (!sessionId) return;
    this._recovering = true;
    if (pending.kind === 'SYNC') { this.recoverSync(pending, capture, sessionId); return; }
    const unknown = () => {
      if (!this.current(capture)) return;
      this._recovering = false;
      this.setData({ notice: '操作结果仍待确认；暂未查到记录不代表提交失败，请勿重复操作。' });
    };
    app.sendRequest({ url: BASE + '/receipt', method: 'GET', data: { kind: pending.kind, sessionId, idempotencyKey: pending.requestId }, hideLoading: true,
      success: response => {
        if (!this.current(capture)) return;
        this._recovering = false;
        if (!ok(response) && view.failure(response).kind !== 'offline') { this.readFailure(response); return; }
        if (!ok(response) || !view.receiptMatches(response.data, pending, capture.owner, sessionId)
            || !this._workflow.confirmRequest(pending.key, pending.requestId)) { unknown(); return; }
        this._pending.shift();
        this.setData({ pending: this._pending[0] || null, form: '', reason: '', entryId: '', nodeId: '',
          notice: pending.kind === 'SETTLE' ? '已查到本局结算结果' : '已查到原申请记录，处理进度以列表为准' });
        if (this._pending.length) this.recover();
        else this.load();
      }, fail: (body, status) => {
        if (!this.current(capture)) return;
        this._recovering = false;
        if (view.failure(body, status).kind !== 'offline') { this.readFailure(body, status); return; }
        this.setData({ notice: '操作结果仍待确认，请勿重复操作' });
      }, successStatusAbnormal: (body, status) => {
        if (!this.current(capture)) return;
        if (view.failure(body, status).kind !== 'offline') { this._recovering = false; this.readFailure(body, status); return; }
        unknown();
      }
    });
  },
  // 同步按节点幂等入账,结果收敛到计分本身:回读本局最新计分即可了结原同步待办,不重放写请求。
  recoverSync(pending, capture, sessionId) {
    const target = Object.assign({}, this._scope, { sessionId });
    const unknown = (body, status) => {
      if (!this.current(capture)) return;
      this._recovering = false;
      if (view.failure(body, status).kind !== 'offline') { this.readFailure(body, status); return; }
      this.setData({ notice: '同步结果待确认，请联网后点「查询原操作结果」核对最新计分' });
    };
    app.sendRequest({ url: BASE + '/session/' + sessionId, method: 'GET', hideLoading: true,
      success: response => {
        if (!this.current(capture)) return;
        const score = ok(response) && view.normalize(response.data, target);
        if (!score) { unknown(response); return; }
        this._recovering = false;
        if (!this._workflow.confirmRequest(pending.key, pending.requestId)) {
          this.setData({ score, notice: '同步结果待确认；本机待办未能清除，请检查本机存储后刷新' }); return;
        }
        this._pending.shift();
        this.setData({ state: 'ready', score, pending: this._pending[0] || null,
          notice: '已按最新计分核对同步结果；如仍有已通过任务未计入，可再次同步' });
        if (this._pending.length) this.recover();
      },
      fail: unknown, successStatusAbnormal: unknown
    });
  },
  allowed(kind) {
    if (!this.current(Object.assign({ epoch: this._epoch }, this._identity || {}))) { this.load(); return false; }
    const score = this.data.score;
    return this.data.state === 'ready' && score && !this.data.busy && !this.data.pending && !this._confirming
      && ({ SYNC: score.canSync, SETTLE: score.canSettle, APPEAL: score.canAppeal, DISPUTE: score.canDispute })[kind];
  },
  sync() { this.confirmWrite('SYNC', {}); },
  settle() { this.confirmWrite('SETTLE', {}); },
  openAppeal(event) {
    if (!this.allowed('APPEAL')) return;
    const entryId = view.id(event && event.currentTarget && event.currentTarget.dataset.entryId);
    if (entryId && (!this.data.score.timeline.some(row => row.entryId === entryId && !row.appealPending))) return;
    this.setData({ form: 'APPEAL', reason: '', nodeId: '', entryId, formError: '' });
  },
  openDispute() { if (this.allowed('DISPUTE')) this.setData({ form: 'DISPUTE', reason: '', entryId: '', nodeId: '', formError: '' }); },
  onReasonInput(event) { if (!this.data.pending && !this.data.busy && !this._confirming) this.setData({ reason: event.detail.value, formError: '' }); },
  onNodeInput(event) { if (!this.data.pending && !this.data.busy && !this._confirming) this.setData({ nodeId: event.detail.value, formError: '' }); },
  closeForm() { if (!this.data.pending && !this.data.busy && !this._confirming) this.setData({ form: '', reason: '', nodeId: '', entryId: '' }); },
  submitReview() {
    const kind = this.data.form, reason = this.data.reason.trim();
    if (!['APPEAL', 'DISPUTE'].includes(kind) || !this.allowed(kind)) return;
    if (!reason || reason.length > 500) { this.setData({ formError: '请填写 1–500 字的理由' }); return; }
    const data = { reason };
    if (kind === 'APPEAL') {
      if (this.data.entryId) data.entryId = this.data.entryId;
      else if (view.id(this.data.nodeId)) data.nodeId = view.id(this.data.nodeId);
      else { this.setData({ formError: '请选择流水，或填写漏记任务的节点编号' }); return; }
      if (this.data.score.appeals.some(row => row.status === 0
          && (data.entryId ? row.entryId === data.entryId : row.nodeId === data.nodeId))) {
        this.setData({ formError: '该条目已有待处理申诉，请等待复核' }); return;
      }
    }
    this.confirmWrite(kind, data);
  },
  confirmWrite(kind, data) {
    if (!this.allowed(kind)) return;
    const capture = this.capture();
    this._confirming = true;
    const titles = { SYNC: '同步已通过任务', SETTLE: '结算本局成绩', APPEAL: '提交计分申诉', DISPUTE: '提交成绩争议' };
    modal.show({ title: titles[kind], content: kind === 'SETTLE' ? '按本局已记录的成绩结算，积分与探索值以结算结果为准。'
      : kind === 'SYNC' ? '将本局已通过任务同步入账，已结算的对局不再入账。' : '提交后进入平台复核，提交成功不代表已采纳。\n' + data.reason,
    confirmText: '确认提交', cancelText: '取消', success: result => {
      this._confirming = false;
      if (this.current(capture) && result.confirm && this.allowed(kind)) this.write(kind, data);
    }, fail: () => { this._confirming = false; } });
  },
  write(kind, payload) {
    const sessionId = this.data.score.sessionId, owner = this._identity.owner;
    const key = view.writeKey(owner, sessionId, kind);
    this._epoch += 1;
    const capture = this.capture();
    this.setData({ busy: true, notice: '' });
    this._workflow.run(key, (done, identity) => {
      const pending = { kind, key, requestId: identity.requestId };
      const data = Object.assign({ sessionId, idempotencyKey: identity.requestId }, payload);
      const path = kind === 'SYNC' || kind === 'SETTLE' ? '/session/' + sessionId + '/' + kind.toLowerCase() : '/' + kind.toLowerCase();
      const finish = (body, status, channel) => {
        if (!this.current(capture)) return;
        const success = channel === 'success' && ok(body) && (kind === 'SYNC'
          ? view.precise(body.data) && body.data && view.id(body.data.sessionId) === sessionId && !!view.integer(body.data.totalCenti)
            && /^\d+$/.test(view.integer(body.data.added))
          : view.receiptMatches(body.data, pending, owner, sessionId, payload));
        done({ status: success ? 'success' : view.rejected(body, status) ? 'failed' : 'unknown', response: body, httpStatus: status });
      };
      return app.sendRequest({ url: BASE + path, method: 'POST', write: true, data,
        header: { 'content-type': 'application/x-www-form-urlencoded' }, hideLoading: true,
        success: body => finish(body, 200, 'success'), fail: (body, status) => finish(body, status, 'fail'),
        successStatusAbnormal: (body, status) => finish(body, status, 'abnormal') });
    }, result => {
      if (!this.current(capture)) return;
      this.setData({ busy: false });
      if (result.status === 'success') {
        this.setData({ form: '', reason: '', entryId: '', nodeId: '', notice: kind === 'SETTLE' ? '已取得本局结算结果' : kind === 'SYNC' ? '任务同步已确认' : '已提交复核，请等待处理' });
        this.load();
      } else if (result.status === 'failed') {
        const state = view.failure(result.response, result.httpStatus);
        if (state.kind !== 'offline') this.readFailure(result.response, result.httpStatus);
        else this.setData({ notice: '请求未受理，请刷新计分后核对当前状态' });
      } else {
        this.setData({ notice: result.status === 'blocked' ? '待办未能保存，本次未提交，请检查本机存储' : '操作结果待确认，请勿重复提交' });
        this.restorePending();
      }
    });
  },
  retry() { this.load(); },
  onPullDownRefresh() { this.load(); wx.stopPullDownRefresh(); }
});
