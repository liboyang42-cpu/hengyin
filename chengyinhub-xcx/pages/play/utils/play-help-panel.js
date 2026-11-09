'use strict';
// UG-05/06/07/08/09 提示阶梯面板(G-PACK /api/play/help*)。
//
// ★ 服务端事实驱动控件:GET /api/play/help 返回 enabled=false(没有 hintLadder)时本面板不出现,
//   旧「看提示 / 揭示答案」入口一字不动;enabled=true 时只用这里,新旧入口互斥,不会双扣。
// ★ 每层三态:免费 / 消耗 N 积分 / 不可用;已解锁的层是「重看」,不弹扣费确认。
// ★ 本局剩余免费次数只显示服务端 remainingFreeUses;没下发就不显示计数器(未知不是 0)。
// ★ 使用与跳过都带 requestId,发出前先落盘;结果未知时「继续核对」用同一个号(服务端只回放原结果),永不换号。
// ★ 跳过只在服务端给出可用的 SKIP 层时出现;确认写明「本题记 0 分并标辅助完成」,成功不放庆祝动画。
// ★ 分数明细只搬服务端 helpReceipt;NOT_SCORED 显示「本题不计分」,绝不写 0 分;积分与解谜分分开列。
const { id } = require('../../../utils/orient-score-view.js');
const modal = require('../../../utils/modal.js');

const BASE = '/api/play/help';
const PENDING_KEY = 'play-help-pending:v1';
const TYPE_LABEL = { TEXT: '文字提示', IMAGE: '图片提示', LETTER: '首字', DIGIT: '一个数字', ANSWER: '答案', SKIP: '跳过本题' };
const RENDERABLE = ['TEXT', 'IMAGE', 'LETTER', 'DIGIT', 'ANSWER'];

const data = { helpPanel: { show: false, loading: false, enabled: false, error: '', nodeId: '', runId: '',
  freeLeft: null, rows: [], skip: null, notice: '', receipt: null, receiptOpen: false, receiptError: '', busy: false } };

const ok = body => body && Number(body.code) === 200;
const definitive = body => body && !body.httpFail && !body.netFail && /^4\d\d$/.test(String(body.code));
const nonNegInt = value => Number.isSafeInteger(value) && value >= 0;

function newRequestId() {
  let rand = '';
  while (rand.length < 16) rand += Math.random().toString(36).slice(2);
  return ('h' + Date.now().toString(36) + rand).slice(0, 40);
}

/** 服务端层列表 → 行。顺序就是作者顺序;前一层(非跳过)没解锁的层置灰,标「需先看上一层」。 */
function project(view) {
  if (!view || view.enabled !== true || !Array.isArray(view.layers)) return { enabled: false, rows: [], skip: null, freeLeft: null };
  const rows = [];
  let skip = null;
  let previousUnlocked = true;
  view.layers.forEach(layer => {
    if (!layer || !Number.isSafeInteger(layer.level) || layer.level <= 0) return;
    const type = String(layer.type || '');
    if (type === 'SKIP') {
      if (layer.usable === true) skip = { level: layer.level };
      return;
    }
    const known = RENDERABLE.includes(type);
    const usable = known && layer.usable === true;
    const unlocked = layer.unlocked === true;
    const gated = usable && !unlocked && !previousUnlocked;
    const cost = nonNegInt(layer.costPoints) ? layer.costPoints : null;
    let state = 'unavailable';
    if (usable && unlocked) state = 'review';
    else if (usable && gated) state = 'gated';
    else if (usable && layer.free === true) state = 'free';
    else if (usable && cost != null && cost > 0) state = 'cost';
    else if (usable && cost === 0) state = 'free';   // 作者明确标 0 = 免费,不消耗共享次数
    rows.push({ level: layer.level, type, label: TYPE_LABEL[type] || '提示', state, cost: state === 'cost' ? cost : 0,
      action: { review: '重看', gated: '需先看上一层', free: '免费', cost: cost + ' 积分', unavailable: '暂不可用' }[state],
      content: null, contentType: '', charge: '', pending: false, error: '' });
    previousUnlocked = unlocked;
  });
  const freeLeft = nonNegInt(view.remainingFreeUses) ? view.remainingFreeUses : null;
  return { enabled: rows.length > 0 || !!skip, rows, skip, freeLeft };
}

/** helpReceipt → 明细行。只搬服务端数;缺明细只给净分与「明细待补」,不补 0 行。 */
function receiptView(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const lines = [];
  const notScored = raw.scoreState === 'NOT_SCORED';
  const num = value => Number.isFinite(value) ? value : null;
  if (raw.skipped === true) lines.push({ k: '本题', v: '跳过 · 记 0 分 · 辅助完成' });
  else if (raw.assisted === true) lines.push({ k: '本题', v: '揭示答案，未自行解出' });
  if (notScored) lines.push({ k: '解谜分', v: '本题不计分' });
  else {
    if (num(raw.baseScore) != null) lines.push({ k: '本题解谜分', v: String(raw.baseScore) });
    if (num(raw.bonus) != null && raw.bonus !== 0) lines.push({ k: '加成', v: '+' + raw.bonus });
    if (num(raw.helpDeduction) != null && raw.helpDeduction !== 0) lines.push({ k: '提示扣分', v: '-' + raw.helpDeduction });
    if (num(raw.skipDeduction) != null && raw.skipDeduction !== 0) lines.push({ k: '跳过扣分', v: '-' + raw.skipDeduction });
    if (num(raw.netScore) != null) lines.push({ k: '本题得分', v: String(raw.netScore) });
  }
  if (num(raw.pointsCharged) != null && raw.pointsCharged > 0) lines.push({ k: '提示消耗积分', v: String(raw.pointsCharged) });
  const levels = Array.isArray(raw.levels) ? raw.levels : null;
  if (levels) levels.forEach(row => {
    if (!row || !Number.isSafeInteger(row.level)) return;
    lines.push({ k: '第 ' + row.level + ' 层 · ' + (TYPE_LABEL[row.type] || '提示'),
      v: row.free === true ? '免费' : (Number.isFinite(row.pointsCharged) ? row.pointsCharged + ' 积分' : '') });
  });
  if (!levels && !notScored) lines.push({ k: '明细', v: '明细待补' });
  return { lines };
}

function chargeLine(use) {
  if (!use || !Number.isFinite(use.chargedPoints) || use.chargedPoints <= 0) return '';
  return '消耗 ' + use.chargedPoints + ' 积分' + (Number.isFinite(use.balanceAfter) ? ' · 余 ' + use.balanceAfter : '');
}

function methods(send) {
  const panel = {
    _helpCapture() {
      const app = typeof getApp === 'function' ? getApp() : null;
      return { owner: String(app && app.getUserID ? app.getUserID() || '' : ''), runId: id((this._routeState || {}).sessionId),
        nodeId: id(this.data.game && this.data.game.nodeId), epoch: this._helpEpoch || 0 };
    },
    _helpCurrent(c) {
      const now = this._helpCapture();
      return !this._playDisposed && c.owner && c.owner === now.owner && c.runId === now.runId && c.nodeId === now.nodeId
        && c.epoch === now.epoch;
    },
    _helpSet(patch) { this.setData({ helpPanel: Object.assign({}, this.data.helpPanel, patch) }); },
    _helpPendingAll() {
      try { const all = wx.getStorageSync(PENDING_KEY); return all && typeof all === 'object' ? all : {}; } catch (e) { return null; }
    },
    _helpPendingKey(c, action, level) { return [c.owner, c.runId, c.nodeId, action, level || 0].join('|'); },
    _helpRemember(c, action, level, requestId) {
      const all = this._helpPendingAll();
      if (!all) return false;
      all[this._helpPendingKey(c, action, level)] = requestId;
      try {
        wx.setStorageSync(PENDING_KEY, all);
        return (this._helpPendingAll() || {})[this._helpPendingKey(c, action, level)] === requestId;
      } catch (e) { return false; }
    },
    _helpForget(c, action, level) {
      const all = this._helpPendingAll();
      if (!all) return;
      delete all[this._helpPendingKey(c, action, level)];
      try { wx.setStorageSync(PENDING_KEY, all); } catch (e) { /* 留着只会让下次沿用同号,仍安全 */ }
    },
    /** 进入任务页时读一次;没有阶梯就什么都不改,旧提示入口照旧。 */
    loadHelpLadder() {
      this._helpEpoch = (this._helpEpoch || 0) + 1;
      const c = this._helpCapture();
      this._helpSet({ show: false, enabled: false, rows: [], skip: null, freeLeft: null, receipt: null, receiptOpen: false,
        notice: '', error: '', receiptError: '', busy: false, nodeId: c.nodeId, runId: c.runId });
      if (this.data.isPreview || this.data.isMock || !c.runId || !c.nodeId) return Promise.resolve(false);
      return send(BASE, 'GET', { runId: c.runId, nodeId: c.nodeId }).then(body => {
        if (!this._helpCurrent(c)) return false;
        if (!ok(body)) { this._helpSet({ enabled: false }); return false; }   // 读不到 = 不出新面板,旧入口照旧
        const view = project(body.data);
        if (body.data && (id(body.data.runId) !== c.runId || id(body.data.nodeId) !== c.nodeId)) return false;
        this._helpSet(Object.assign({ enabled: view.enabled }, view));
        if (view.enabled) this.setData({ canHint: false, canReveal: false });   // 新旧入口互斥
        return view.enabled;
      }).catch(() => false);
    },
    openHelpPanel() {
      if (!this.data.helpPanel.enabled) return;
      this._helpSet({ show: true, notice: '' });
      const pending = this._helpPendingAll() || {};
      const c = this._helpCapture();
      // 重进后仍有未核对的原请求:行上直接标「结果待确认」,只给「继续核对」。
      const rows = this.data.helpPanel.rows.map(row => Object.assign({}, row,
        { pending: !!pending[this._helpPendingKey(c, 'use', row.level)] }));
      this._helpSet({ rows, skipPending: !!pending[this._helpPendingKey(c, 'skip', 0)] });
    },
    closeHelpPanel() { this._helpSet({ show: false }); },
    tapHelpRow(e) {
      const level = Number(e && e.currentTarget && e.currentTarget.dataset.level);
      const row = this.data.helpPanel.rows.find(item => item.level === level);
      if (!row || this.data.helpPanel.busy) return;
      if (row.pending) return this._helpUse(row, true);
      if (row.state === 'review') return this._helpUse(row, false);
      if (row.state !== 'free' && row.state !== 'cost') return;
      const c = this._helpCapture();
      modal.show({ title: row.label, content: row.state === 'free' ? '本次免费查看' : '查看将消耗 ' + row.cost + ' 积分',
        confirmText: '查看', success: res => { if (res.confirm && this._helpCurrent(c)) this._helpUse(row, false); } });
    },
    _helpUse(row, recovering) {
      const c = this._helpCapture();
      const all = this._helpPendingAll();
      if (!all) { this._helpSet({ notice: '本机存储不可用，暂不提交' }); return; }
      const requestId = all[this._helpPendingKey(c, 'use', row.level)] || newRequestId();
      if (!this._helpRemember(c, 'use', row.level, requestId)) { this._helpSet({ notice: '无法保存原请求，暂不提交' }); return; }
      const patchRow = patch => this._helpSet({ busy: false, rows: this.data.helpPanel.rows.map(item =>
        item.level === row.level ? Object.assign({}, item, patch) : item) });
      this._helpSet({ busy: true, notice: '' });
      return send(BASE + '/use', 'POST', { runId: c.runId, nodeId: c.nodeId, level: row.level, action: 'HELP', requestId }).then(body => {
        if (!this._helpCurrent(c)) return;
        if (ok(body) && body.data && body.data.level === row.level && typeof body.data.content === 'string') {
          this._helpForget(c, 'use', row.level);
          patchRow({ pending: false, error: '', state: 'review', action: '重看', content: body.data.content,
            contentType: body.data.type, charge: chargeLine(body.data) });
          return this.loadHelpCounter(c);
        }
        if (definitive(body)) {
          this._helpForget(c, 'use', row.level);
          patchRow({ pending: false, content: null,
            error: body.reasonCode === 'POINTS_INSUFFICIENT' ? '积分不足，本层未解锁' : (body.msg || '本层暂不可用') });
          return;
        }
        patchRow({ pending: true, error: '结果待确认' });
      }).catch(() => { if (this._helpCurrent(c)) patchRow({ pending: true, error: '结果待确认' }); });
    },
    /** 用完一层后重读剩余免费次数与各层状态;不本地减次数。 */
    loadHelpCounter(c) {
      return send(BASE, 'GET', { runId: c.runId, nodeId: c.nodeId }).then(body => {
        if (!this._helpCurrent(c) || !ok(body)) return;
        const view = project(body.data);
        const byLevel = {};
        this.data.helpPanel.rows.forEach(row => { byLevel[row.level] = row; });
        const rows = view.rows.map(row => {
          const old = byLevel[row.level] || {};
          return Object.assign(row, { content: old.content || null, contentType: old.contentType || '', charge: old.charge || '' });
        });
        this._helpSet({ rows, skip: view.skip, freeLeft: view.freeLeft });
      }).catch(() => {});
    },
    tapHelpSkip() {
      const panelState = this.data.helpPanel;
      if (!panelState.skip || panelState.busy) return;
      if (panelState.skipPending) return this._helpSkip();
      const c = this._helpCapture();
      modal.show({ title: '跳过本题', content: '本题记 0 分并标辅助完成', confirmText: '跳过', confirmColor: '#E5484D',
        success: res => { if (res.confirm && this._helpCurrent(c)) this._helpSkip(); } });
    },
    _helpSkip() {
      const c = this._helpCapture();
      const all = this._helpPendingAll();
      if (!all) { this._helpSet({ notice: '本机存储不可用，暂不提交' }); return; }
      const requestId = all[this._helpPendingKey(c, 'skip', 0)] || newRequestId();
      if (!this._helpRemember(c, 'skip', 0, requestId)) { this._helpSet({ notice: '无法保存原请求，暂不提交' }); return; }
      this._helpSet({ busy: true, notice: '' });
      return send(BASE + '/skip', 'POST', { runId: c.runId, nodeId: c.nodeId, requestId }).then(body => {
        if (!this._helpCurrent(c)) return;
        if (ok(body) && body.data && body.data.skipped === true) {
          this._helpForget(c, 'skip', 0);
          this._helpSet({ busy: false, skipPending: false, skip: null, skipped: true,
            receipt: receiptView(body.data.helpReceipt), receiptOpen: true });
          if (typeof this.onHelpSkipped === 'function') this.onHelpSkipped(c.nodeId, body.data);
          return;
        }
        if (definitive(body)) {
          this._helpForget(c, 'skip', 0);
          this._helpSet({ busy: false, skipPending: false, notice: body.msg || '本题暂不能跳过' });
          return;
        }
        this._helpSet({ busy: false, skipPending: true, notice: '结果待确认' });
      }).catch(() => { if (this._helpCurrent(c)) this._helpSet({ busy: false, skipPending: true, notice: '结果待确认' }); });
    },
    toggleHelpReceipt() {
      const state = this.data.helpPanel;
      if (state.receiptOpen) { this._helpSet({ receiptOpen: false }); return; }
      const c = this._helpCapture();
      this._helpSet({ receiptOpen: true, receiptError: '' });
      return send(BASE + '/receipt', 'GET', { runId: c.runId, nodeId: c.nodeId }).then(body => {
        if (!this._helpCurrent(c)) return;
        if (ok(body)) this._helpSet({ receipt: receiptView(body.data) });
        else this._helpSet({ receipt: null, receiptError: '结算明细暂不可用' });
      }).catch(() => { if (this._helpCurrent(c)) this._helpSet({ receipt: null, receiptError: '结算明细暂不可用' }); });
    },
  };
  return panel;
}

module.exports = { data, methods, project, receiptView, chargeLine, TYPE_LABEL };
