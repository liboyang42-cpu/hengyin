'use strict';
// 文档二 L5(原型 A p-story / p-tools / p-char / p-score / p-finish,原型 B B6–B9 / X8):
// 状态栏第二行(前 3 项属性)、「…」里的我的角色 / 本局用时 / 当前得分 / 联系主办、
// 盖在游玩页上的全屏角色卡与本局成绩。只读现有接口:
//   GET  /api/play/character?topicId              角色卡(开局冻结值)
//   GET  /api/orient/scoring/session/{id}         本局得分、维度、公示截止
//   GET  /api/orient/scoring/session/{id}/leaderboard  本场排名
//   POST /api/orient/scoring/dispute              成绩争议(局终后公示期内,理由 1–500 字)
const character = require('../../../utils/user-character.js');
const scoreView = require('../../../utils/orient-score-view.js');
const cyModal = require('../../../utils/modal.js');
const cyToast = require('../../../utils/toast.js');

const REASON_MAX = 500;

function reasonError(content, empty) {
  const text = String(content || '').trim();
  if (!text) return empty;
  return text.length > REASON_MAX ? '最多 ' + REASON_MAX + ' 字' : '';
}

// 状态栏第二行:按注册表顺序取前 3 项(服务端已按 sortOrder 下发)
function hudAttrs(card) {
  return card && Array.isArray(card.attributes) ? card.attributes.slice(0, 3) : [];
}

function initial(name) {
  const text = String(name || '').trim();
  return text ? text[0] : '';
}

// 角色卡小雷达:3–6 项属性画成多边形;少于 3 项不画。
function radarUri(attrs) {
  const list = (attrs || []).slice(0, 6);
  if (list.length < 3) return '';
  const c = 75, r = 44, n = list.length;
  const at = (i, k) => {
    const a = -Math.PI / 2 + i * 2 * Math.PI / n;
    return (c + Math.cos(a) * r * k).toFixed(1) + ',' + (c + Math.sin(a) * r * k).toFixed(1);
  };
  const ring = k => list.map((_, i) => at(i, k)).join(' ');
  const value = list.map((row, i) => at(i, Math.max(0.08, (row.percent || 0) / 100))).join(' ');
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 150 150">'
    + [1, 0.66, 0.33].map(k => '<polygon points="' + ring(k) + '" fill="none" stroke="#ffffff" stroke-opacity=".16"/>').join('') /* ds-ok 雷达图 SVG 装饰色 */
    + '<polygon points="' + value + '" fill="#F59F00" fill-opacity=".32" stroke="#F59F00" stroke-width="1.5"/></svg>'; /* ds-ok 雷达图 SVG 装饰色 */
  return 'data:image/svg+xml,' + encodeURIComponent(svg);
}

function charView(card, name) {
  const rpg = card.rpg || null;
  const clocks = rpg ? rpg.clocks : [];
  return { name: name || '', ava: initial(name), attrs: card.attributes, radar: radarUri(card.attributes),
    traits: rpg ? rpg.facets.concat(rpg.aspects) : [],
    momentum: rpg && rpg.momentum ? Object.assign({}, rpg.momentum,
      { pips: Array.from({ length: Math.min(rpg.momentum.max, 10) }, (_, i) => ({ i, on: i < rpg.momentum.value })) }) : null,
    clocks };
}

// 章节结算 RPG 小结:同一 .finsheet__grid 三格 —— 进度钟(走满写「已满」)在前,动量在后
function rpgCells(card) {
  const rpg = card && card.rpg;
  if (!rpg) return [];
  const cells = rpg.clocks.map(row => ({ key: 'clock:' + row.key, icon: 'clock', label: row.label,
    value: row.value >= row.max ? '已满' : row.value + '/' + row.max }));
  if (rpg.momentum) cells.push({ key: 'momentum', icon: 'route', label: '动量', value: rpg.momentum.value + '/' + rpg.momentum.max });
  return cells.slice(0, 3);
}

function points(centi) {
  const text = scoreView.integer(centi);
  if (!text) return '';
  const value = Number(text) / 100;
  return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, '');
}

function clock(ms) {
  const total = Math.max(0, Math.floor(Number(ms) / 1000));
  if (!Number.isFinite(total)) return '';
  const h = Math.floor(total / 3600), m = Math.floor(total % 3600 / 60), s = total % 60;
  const pad = v => (v < 10 ? '0' : '') + v;
  return h ? h + ':' + pad(m) + ':' + pad(s) : pad(m) + ':' + pad(s);
}

function stamp(value) {
  if (typeof value === 'number') return value;
  if (typeof value !== 'string' || !value) return NaN;
  return new Date(value.replace(/-/g, '/').replace('T', ' ').replace(/\.\d+.*$/, '')).getTime();
}

function deadline(value) {
  const at = stamp(value);
  if (!Number.isFinite(at)) return '';
  const d = new Date(at), pad = v => (v < 10 ? '0' : '') + v;
  return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

const DIMENSION_LABEL = { TASK: '到达节点', PUZZLE: '解谜', BONUS: '奖励', PENALTY: '处罚', ADJUSTMENT: '调整' };

// 本局成绩:breakdown(scoring/session) + 榜单(可缺)→ 全屏结算画面
function scoreScreen(raw, board, extras) {
  if (!raw || !scoreView.id(raw.sessionId) || !scoreView.integer(raw.totalCenti)) return null;
  const ended = [1, 2].includes(Number(raw.status));
  const timeline = Array.isArray(raw.timeline) ? raw.timeline : [];
  const rows = [];
  Object.keys(DIMENSION_LABEL).forEach(key => {
    const centi = raw.byDimension && scoreView.integer(raw.byDimension[key]);
    if (!centi || centi === '0') return;
    const count = timeline.filter(row => row && row.dimension === key).length;
    const value = points(centi);
    rows.push({ key, label: DIMENSION_LABEL[key] + (key === 'TASK' && count > 1 ? ' × ' + count : ''),
      value: (value[0] === '-' ? '' : '+') + value });
  });
  const pending = Number(scoreView.integer(raw.pendingReviewCount) || 0);
  if (pending > 0) rows.push({ key: 'wait', label: '照片 × ' + pending, value: '审核中', wait: true });
  const me = board && board.me;
  const list = board && Array.isArray(board.list) ? board.list.slice(0, 5) : [];
  const meMs = me && me.elapsedMs != null ? me.elapsedMs : null;
  const startedAt = stamp(raw.startedAt), endedAt = ended ? stamp(raw.endedAt) : (extras && extras.now) || Date.now();
  const elapsed = meMs != null ? clock(meMs) : Number.isFinite(startedAt) && Number.isFinite(endedAt) ? clock(endedAt - startedAt) : '';
  const disputes = Array.isArray(raw.disputes) ? raw.disputes : [];
  const disputed = disputes.some(row => row && Number(row.status) === 0);
  const windowOpen = ended && !!raw.disputeWindowDeadline && stamp(raw.disputeWindowDeadline) > ((extras && extras.now) || Date.now());
  return {
    sessionId: scoreView.id(raw.sessionId), final: ended && !windowOpen, ended,
    rank: me && scoreView.id(me.rank) ? me.rank : '',
    of: board && scoreView.id(board.total) ? board.total : '',
    total: points(raw.totalCenti), elapsed,
    done: extras && extras.done != null ? extras.done : '', nodes: extras && extras.nodes != null ? extras.nodes : '',
    rows,
    board: list.map(row => ({ rowKey: row.rowKey, rank: row.rank, mine: !!row.mine, name: row.mine ? '你' : '同行者',
      ava: row.mine ? '你' : '', time: row.elapsedMs != null ? clock(row.elapsedMs) : '', score: points(row.scoreCenti) })),
    deadline: windowOpen ? deadline(raw.disputeWindowDeadline) : '',
    canDispute: windowOpen && !disputed, disputed,
  };
}

// 联系主办 / 联系商家(B6/B7/B9):号码取内容归属方已有电话,只给拨打,不显示号码。没有号码时整行不出。
function contact(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const phone = typeof raw.phone === 'string' ? raw.phone.trim() : '';
  if (!/^[0-9+\-\s()]{3,32}$/.test(phone)) return null;
  const merchant = String(raw.kind || '').toUpperCase() === 'MERCHANT';
  return { label: merchant ? '联系商家' : '联系主办', name: String(raw.name || '').trim().slice(0, 40), phone };
}

const data = {
  hudChar: null,          // { state:'loading'|'ok'|'error', ava, attrs }
  hudRpg: [],             // 章节结算 RPG 小结三格
  hudTypes: {},           // stateKey → displayType(背包属性行按类型画)
  charOv: { show: false, state: 'loading', view: null },
  scoreOv: { show: false, state: 'loading', view: null, shown: '' },
  sessStats: null,        // { state:'loading'|'ok'|'error', elapsed, score, rank, provisional }
  playContact: null,
};

function methods(req, keyFactory) {
  const handlers = {
    _overlayLive() {
      return this._isPlayAccountCurrent() && !this.data.isPreview && !this.data.isMock;
    },
    _orientSessionId() {
      return this.data.mode == 1 ? scoreView.id((this._routeState || {}).sessionId) : '';
    },
    /** 状态栏第二行 + 角色卡共用一次读取。主题没开 d20(局里没有可见属性)时不读、不出。 */
    loadHudCharacter() {
      const topicId = character.exactId(this.data.topicId);
      const attrs = ((this._routeState || {}).attributes || []).filter(a => !String(a.key || '').startsWith('sys.'));
      if (!topicId || !attrs.length || !this._overlayLive()) { this.setData({ hudChar: null }); return Promise.resolve(null); }
      const seq = this._hudSeq = (this._hudSeq || 0) + 1;
      const current = () => this._overlayLive() && seq === this._hudSeq && character.exactId(this.data.topicId) === topicId;
      this.setData({ hudChar: Object.assign({}, this.data.hudChar || { ava: '', attrs: [] }, { state: 'loading' }) });
      return req('/api/play/character', 'GET', { topicId }, undefined, true).then(res => {
        if (!current()) return null;
        const card = res && Number(res.code) === 200 ? character.projectCard(res.data, topicId) : null;
        if (!card) { this.setData({ 'hudChar.state': 'error' }); return null; }
        this._hudCard = card;
        this.setData({ hudChar: card.attributes.length ? { state: 'ok', ava: initial(this.data.topicName), attrs: hudAttrs(card) } : null,
          hudRpg: card.attributes.length ? rpgCells(card) : [],
          hudTypes: card.attributes.reduce((map, row) => Object.assign(map, { [row.stateKey]: row.displayType }), {}) });
        return card;
      }).catch(() => { if (current()) this.setData({ 'hudChar.state': 'error' }); return null; });
    },
    onHudCharTap() {
      if (this.data.hudChar && this.data.hudChar.state === 'error') { this.loadHudCharacter(); return; }
      this.openCharOverlay();
    },
    openCharOverlay() {
      if (!this._overlayLive()) return;
      if (this.data.sessionToolsOpen) this.toggleSessionTools();
      this.setData({ charOv: { show: true, state: 'loading', view: null } });
      this.loadHudCharacter().then(card => {
        if (!this.data.charOv.show) return;
        if (!card || !card.attributes.length) { this.setData({ 'charOv.state': 'error' }); return; }
        this.setData({ charOv: { show: true, state: 'ok', view: charView(card, this.data.topicName) } });
      });
    },
    closeCharOverlay() { this.setData({ 'charOv.show': false }); },
    /** 「…」本局用时 · 当前得分;章节结算「本局成绩」右边的名次。城市定向且已有对局时才读。 */
    loadSessionStats() {
      const sessionId = this._orientSessionId();
      if (!sessionId || !this._overlayLive()) { this.setData({ sessStats: null }); return Promise.resolve(null); }
      const seq = this._sessSeq = (this._sessSeq || 0) + 1;
      const current = () => this._overlayLive() && seq === this._sessSeq && this._orientSessionId() === sessionId;
      if (!this.data.sessStats || this.data.sessStats.state !== 'ok') this.setData({ sessStats: { state: 'loading' } });
      const base = '/api/orient/scoring/session/' + sessionId;
      return Promise.all([
        req(base, 'GET', {}, undefined, true),
        req(base + '/leaderboard', 'GET', {}, undefined, true).catch(() => null),
      ]).then(([score, board]) => {
        if (!current()) return null;
        if (!score || Number(score.code) !== 200 || !score.data) {
          // 计分没开 / 不适用:三行都不出,不是错误
          if (score && ['ORIENT_SCORING_DISABLED', 'ORIENT_SCORING_NOT_APPLICABLE'].includes(score.errorCode || score.err)) {
            this.setData({ sessStats: null }); return null;
          }
          this.setData({ sessStats: { state: 'error' } }); return null;
        }
        const boardData = board && Number(board.code) === 200 && board.data && scoreView.id(board.data.sessionId) === sessionId ? board.data : null;
        const view = scoreScreen(score.data, boardData, { done: this.data.doneCount, nodes: this.data.total });
        if (!view) { this.setData({ sessStats: { state: 'error' } }); return null; }
        this.setData({ sessStats: { state: 'ok', elapsed: view.elapsed, score: view.total, rank: view.rank, provisional: !view.final } });
        return view;
      }).catch(() => { if (current()) this.setData({ sessStats: { state: 'error' } }); return null; });
    },
    /** 联系主办 / 联系商家(L8 契约 §4 GET /api/play/contact):菜单打开时按当前站读一次;data=null 时整行不出。 */
    loadPlayContact() {
      if (!this._overlayLive() || this.data.isPreview || this.data.isMock) return Promise.resolve(null);
      const nodeId = (this._routeState || {}).currentNodeId;
      const params = this.data.activityId ? { activityId: this.data.activityId } : Object.assign({ topicId: this.data.topicId }, nodeId ? { nodeId } : {});
      const seq = this._contactSeq = (this._contactSeq || 0) + 1;
      return req('/api/play/contact', 'GET', params, undefined, true).then(res => {
        if (seq !== this._contactSeq || !this._overlayLive()) return null;
        if (res && Number(res.code) === 200) this.setData({ playContact: contact(res.data) });
        return null;
      }).catch(() => null);
    },
    openScoreOverlay() {
      if (!this._overlayLive() || !this._orientSessionId()) return;
      if (this.data.sessionToolsOpen) this.toggleSessionTools();
      this.setData({ scoreOv: { show: true, state: 'loading', view: null, shown: '' } });
      this.loadSessionStats().then(view => {
        if (!this.data.scoreOv.show) return;
        if (!view) { this.setData({ 'scoreOv.state': 'error' }); return; }
        this.setData({ scoreOv: { show: true, state: 'ok', view, shown: this.data.reducedMotion ? view.total : '0' } });
        if (!this.data.reducedMotion) this._countScore(view.total);
      });
    },
    _countScore(total) {
      clearInterval(this._scoreCountTimer);
      const target = Number(total);
      if (!Number.isFinite(target) || target <= 0) { this.setData({ 'scoreOv.shown': total }); return; }
      let step = 0;
      this._scoreCountTimer = setInterval(() => {
        step += 1;
        const done = step >= 16 || !this.data.scoreOv.show;
        this.setData({ 'scoreOv.shown': done ? total : String(Math.round(target * step / 16)) });
        if (done) clearInterval(this._scoreCountTimer);
      }, 40);
    },
    closeScoreOverlay() { clearInterval(this._scoreCountTimer); this.setData({ 'scoreOv.show': false }); },
    /** 成绩争议(X8):原生带输入框的确认框,理由必填 1–500 字;同一意图复用同一个幂等键。 */
    disputeScore() {
      const view = this.data.scoreOv.view;
      if (!this._overlayLive() || !view || !view.canDispute) return;
      const sessionId = view.sessionId;
      cyModal.show({ title: '申诉成绩', content: '结果会显示在「我的申诉」。', editable: true, multiline: true, maxlength: REASON_MAX,
        placeholderText: '写下你认为有误的地方', confirmText: '提交', cancelText: '取消',
        validate: content => reasonError(content, '请填写申诉理由'),
        success: result => {
          if (!result.confirm || !this._overlayLive() || this._orientSessionId() !== sessionId) return;
          const reason = String(result.content || '').trim();
          if (reasonError(reason, '请填写申诉理由')) return;
          const pending = this._disputeIntent && this._disputeIntent.sessionId === sessionId && this._disputeIntent.reason === reason
            ? this._disputeIntent : { sessionId, reason, key: keyFactory() };
          this._disputeIntent = pending;
          req('/api/orient/scoring/dispute', 'POST', { sessionId, reason, idempotencyKey: pending.key },
            { 'content-type': 'application/x-www-form-urlencoded' }, true).then(res => {
            if (!this._overlayLive()) return;
            if (res && Number(res.code) === 200) {
              this._disputeIntent = null;
              cyToast('已提交');
              this.setData({ 'scoreOv.view.canDispute': false, 'scoreOv.view.disputed': true });
              return;
            }
            cyToast((res && res.msg) || '结果未确认，稍后在我的申诉查看');
          }).catch(() => { if (this._overlayLive()) cyToast('结果未确认，稍后在我的申诉查看'); });
        } });
    },
    callPlayContact() {
      const c = this.data.playContact;
      if (!c) return;
      cyModal.show({ title: '', content: '拨打「' + (c.name || (c.label === '联系商家' ? '商家' : '主办')) + '」的电话？', confirmText: '拨打', cancelText: '取消',
        success: result => { if (result.confirm) wx.makePhoneCall({ phoneNumber: c.phone, fail: () => {} }); } });
    },
    openBoardFromTools() {
      if (this.data.sessionToolsOpen) this.toggleSessionTools();
      this.openBoard();
    },
    /** 站点卡「本次到达未记录 · 申诉」(X8):读资格后弹带输入框的确认框。 */
    async appealArrivalFromSheet() {
      if (!this._isPlayAccountCurrent()) return;
      const flow = this._ensureGuardAppeal();
      await flow.read();
      const view = flow.view();
      if (!this._isPlayAccountCurrent() || !view || view.command) return;
      if (!view.eligibility || !view.eligibility.canSubmit) { cyToast(view.message || '这次到达暂时不能申诉'); return; }
      const session = this._sessionKey();
      cyModal.show({ title: '申诉这次到达', content: '24 小时内给出结论。', editable: true, multiline: true, maxlength: REASON_MAX,
        placeholderText: '写下当时的情况，例如：定位跳了一下', confirmText: '提交', cancelText: '取消',
        validate: content => reasonError(content, '请填写申诉理由'),
        success: async result => {
          if (!result.confirm || !this._isPlayAccountCurrent() || session !== this._sessionKey()) return;
          try { await flow.submit(result.content); cyToast('已提交'); } catch (error) { if (this._isPlayAccountCurrent()) cyToast(error.message); }
        } });
    },
  };
  return handlers;
}

module.exports = { data, methods, hudAttrs, rpgCells, radarUri, charView, scoreScreen, contact, points, clock, deadline, reasonError, REASON_MAX };
