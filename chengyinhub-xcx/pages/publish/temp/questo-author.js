/**
 * 模板编辑页 · Questo 新玩法的作者配置(施工清单 UG-01 / UG-02 / UG-03·04 / UG-10 / UG-11 / §23.1 别名)。
 *
 * 只往原 advancedConfigJson 里写服务端已接线的键,不新增接口:
 *   hangman{enabled,answer,format[],aliases[],caseSensitive}
 *   qa.options[].img / alt
 *   accusation{enabled,suspects[],introScreens,winScreen,loseScreen,corkboardImage,earlyAccuse}
 *   hintLadder[{level,type,payload,cost:{points}}] + 根层 hintFreeUsesPerRun
 *   answerPolicy{version:1,normalizationPolicy:'LEGACY_V1',acceptedAliases[]}
 * 界面不露这些键名。就地校验只挡服务端必拒的形状,其余仍以服务端保存/发布校验为准。
 */

const answerText = require('../../../utils/answer-text.js');

const HINT_TYPES = [
  { value: 'TEXT', label: '文字' },
  { value: 'IMAGE', label: '图片' },
  { value: 'LETTER', label: '首字' },
  { value: 'DIGIT', label: '数字' },
  { value: 'ANSWER', label: '答案' },
  { value: 'SKIP', label: '跳过' },
];
const MAX_HINT_LEVELS = 6;
const MAX_RUN_FREE_USES = 1000;
const MAX_HANGMAN_CHARS = 40;
const MAX_HANGMAN_ALIASES = 10;
const MAX_ANSWER_ALIASES = 20;
const BLANK = '_';

const clone = (v) => JSON.parse(JSON.stringify(v == null ? null : v));
const chars = (text) => Array.from(String(text || ''));
/** 与服务端 HangmanGameplay.isComparable(Character.isLetterOrDigit)同口径:字母/数字(含汉字)可补,其余是分隔符。 */
const comparable = (ch) => /[\p{L}\p{Nd}]/u.test(ch);

/** 作者答案 + 已存 format → 编辑格子。format 缺省 = 全部待补。 */
function hangmanCells(section) {
  const s = section || {};
  const answer = chars(s.answer);
  const format = Array.isArray(s.format) ? s.format : [];
  return answer.map((ch, i) => {
    const sep = !comparable(ch);
    const slot = format.length === answer.length ? format[i] : null;
    const given = !sep && typeof slot === 'string' && slot !== '' && slot !== BLANK;
    return { i, ch, sep, given };
  });
}

/** 格子按词分组:一个词不折行,词与词之间才换行(长答案也一眼看全)。 */
function hangmanGroups(cells) {
  const groups = [];
  let cur = [];
  (cells || []).forEach((cell) => {
    if (cell.sep) { if (cur.length) groups.push({ k: 'g' + cur[0].i, cells: cur }); cur = []; if (cell.ch.trim()) groups.push({ k: 's' + cell.i, sep: cell.ch, cells: [] }); return; }
    cur.push(cell);
  });
  if (cur.length) groups.push({ k: 'g' + cur[0].i, cells: cur });
  return groups;
}

/** 改答案时保留仍对得上的预填格,对不上的改回待补。全部待补时不写 format。 */
function reshapeFormat(answer, previous) {
  const cps = chars(answer);
  const before = Array.isArray(previous) && previous.length === cps.length ? previous : [];
  const format = cps.map((ch, i) => (comparable(ch) && before[i] === ch ? ch : (comparable(ch) ? BLANK : ch)));
  return format.some((slot, i) => comparable(cps[i]) && slot !== BLANK) ? format : [];
}

function toggleGiven(section, index) {
  const cells = hangmanCells(section);
  const cell = cells[index];
  if (!cell || cell.sep) return null;
  const format = cells.map((c) => (c.sep ? c.ch : (c.i === index ? (c.given ? BLANK : c.ch) : (c.given ? c.ch : BLANK))));
  return cells.some((c, i) => !c.sep && format[i] !== BLANK) ? format : [];
}

/** live=true 是编辑中的就地提示:只报形状冲突,「还没填」等保存时再报(新建时不先给一片红字)。 */
function hangmanError(section, live) {
  const s = section || {};
  if (!s.enabled) return '';
  const answer = String(s.answer || '');
  if (!answer.trim()) return live ? '' : '请填写答案';
  const cps = chars(answer);
  if (cps.length > MAX_HANGMAN_CHARS) return '答案最多 ' + MAX_HANGMAN_CHARS + ' 个字';
  if (!cps.some(comparable)) return '答案至少要有一个可补的字母或数字';
  const cells = hangmanCells(s);
  if (!cells.some((c) => !c.sep && !c.given)) return '至少留一格给玩家补';
  const aliases = (s.aliases || []).map((a) => String(a || '').trim());
  if (aliases.some((a) => !a)) return '别名不能为空';
  const norm = (t) => (s.caseSensitive ? t : t.toLowerCase());
  const seen = new Set([norm(answer.trim())]);
  for (const alias of aliases) { if (seen.has(norm(alias))) return '别名与答案或其他别名重复'; seen.add(norm(alias)); }
  return '';
}

/** 选项配图(UG-02):替代文字默认取选项文案(存盘时补),不再单独要作者填。
 *  只剩一种冲突:配了图却没写选项文案,读屏和图片说明都无从取。返回 { 选项下标: 错误 }。 */
function optionMediaErrors(qa, live) {
  const out = {};
  if (live || !qa || !qa.enabled || qa.mode !== 'PICK') return out;
  (qa.options || []).forEach((o, i) => {
    const img = String((o && o.img) || '').trim();
    const alt = String((o && o.alt) || '').trim() || String((o && o.label) || '').trim();
    if (img && !alt) out[i] = '配了图的选项要写文案';
  });
  return out;
}

/** 指认人物与问答选项一一对应:同 id、姓名取选项文案。作者不另填正确人物。 */
function syncSuspects(accusation, options) {
  const prev = {};
  (accusation.suspects || []).forEach((s) => { if (s && s.id) prev[s.id] = s; });
  return (options || []).map((o) => {
    const old = prev[o.id] || {};
    const row = { id: o.id, name: String(o.label || '').trim() };
    if (old.avatar) row.avatar = old.avatar;
    if (old.identity) row.identity = old.identity;
    row.testimony = Array.isArray(old.testimony) ? old.testimony.map((t) => ({ key: t.key || '', text: t.text || '' })) : [];
    return row;
  });
}

function accusationError(advanced, live) {
  const acc = advanced && advanced.accusation;
  if (!acc || !acc.enabled) return '';
  const qa = advanced.qa || {};
  if (qa.mode !== 'PICK' || qa.multi) return '指认只能用在单选的选项问答上';
  const suspects = syncSuspects(acc, qa.options);
  if (!live && suspects.some((s) => !s.avatar)) return '每个人物都要有头像';
  if (!live && suspects.some((s) => (s.testimony || []).some((t) => !String(t.key || '').trim() || !String(t.text || '').trim()))) return '证词要同时写线索和内容';
  if (!live && !String(acc.winScreen || '').trim()) return '请写指认正确的结局';
  if (!live && !String(acc.loseScreen || '').trim()) return '请写指认错误的结局';
  if (acc.earlyAccuse && acc.earlyAccuse.allowed && !String(acc.earlyAccuse.recordAs || '').trim()) return '允许提前指认时要写结束记录名';
  return '';
}

function hintErrors(advanced, live) {
  const out = { quota: '', levels: {} };
  const ladder = advanced && advanced.hintLadder;
  if (!Array.isArray(ladder)) return out;
  if (advanced.hintFreeUsesPerRun != null) {
    const n = Number(advanced.hintFreeUsesPerRun);
    if (!Number.isInteger(n) || n < 0 || n > MAX_RUN_FREE_USES) out.quota = '免费次数须为 0 至 ' + MAX_RUN_FREE_USES + ' 的整数';
  }
  ladder.forEach((row, i) => {
    const type = row && row.type;
    const payload = row && row.payload;
    const points = row && row.cost && row.cost.points;
    let msg = '';
    if (!live && type === 'TEXT' && !String(payload || '').trim()) msg = '请填写提示文字';
    else if (!live && type === 'IMAGE' && !String(payload || '').trim()) msg = '请上传提示图片';
    else if (type === 'LETTER' && payload != null && payload !== '' && !(Number.isInteger(Number(payload)) && Number(payload) >= 1)) msg = '位置须为 1 起的整数';
    if (!msg && points != null && points !== '' && !(Number.isInteger(Number(points)) && Number(points) >= 0)) msg = '积分须为非负整数';
    if (msg) out.levels[i] = msg;
  });
  return out;
}

function aliasError(policy) {
  if (!policy || !Array.isArray(policy.acceptedAliases)) return '';
  const list = policy.acceptedAliases.map((a) => String(a || '').trim());
  if (list.some((a) => !a)) return '写法不能为空';
  if (new Set(list.map((a) => a.toLowerCase())).size !== list.length) return '写法不能重复';
  return '';
}

/** 存盘前收口:人物与选项对齐、空的可选键不写、提示层号按顺序重排。不改作者已写的内容。 */
function beforeSave(advanced) {
  const next = clone(advanced || {});
  if (next.accusation && next.accusation.enabled && next.qa) {
    next.accusation.suspects = syncSuspects(next.accusation, next.qa.options).map((s) => {
      const row = Object.assign({}, s);
      if (!row.testimony.length) delete row.testimony;
      return row;
    });
    if (!next.accusation.corkboardImage) delete next.accusation.corkboardImage;
    if (next.accusation.earlyAccuse && !next.accusation.earlyAccuse.allowed) next.accusation.earlyAccuse = { allowed: false };
  }
  if (next.qa && Array.isArray(next.qa.options)) {
    next.qa.options = next.qa.options.map((o) => {
      const row = Object.assign({}, o);
      if (!String(row.img || '').trim()) { delete row.img; delete row.alt; return row; }
      if (!String(row.alt || '').trim() && String(row.label || '').trim()) row.alt = String(row.label).trim();
      return row;
    });
  }
  if (Array.isArray(next.hintLadder)) {
    next.hintLadder = next.hintLadder.map((row, i) => {
      const out = { level: i + 1, type: row.type };
      if (['TEXT', 'IMAGE', 'ANSWER'].includes(row.type) && String(row.payload || '').trim()) out.payload = String(row.payload).trim();
      if (row.type === 'LETTER' && row.payload !== '' && row.payload != null) out.payload = Number(row.payload);
      const points = row.cost && row.cost.points;
      if (points !== '' && points != null) out.cost = { points: Number(points) };
      return out;
    });
    if (next.hintFreeUsesPerRun === '' || next.hintFreeUsesPerRun == null) delete next.hintFreeUsesPerRun;
    else next.hintFreeUsesPerRun = Number(next.hintFreeUsesPerRun);
  }
  if (next.hangman && next.hangman.enabled) {
    if (!(next.hangman.format || []).length) delete next.hangman.format;
    if (!(next.hangman.aliases || []).length) delete next.hangman.aliases;
  }
  if (next.answerPolicy && !(next.answerPolicy.acceptedAliases || []).length) delete next.answerPolicy;
  return next;
}

/** 编辑页要的派生数据(一次算齐,WXML 只读)。submitted=false 时只给形状冲突类提示。 */
function view(advanced, submitted) {
  const a = advanced || {};
  const live = !submitted;
  const hint = hintErrors(a, live);
  return {
    qzHangmanCells: hangmanCells(a.hangman),
    qzHangmanGroups: hangmanGroups(hangmanCells(a.hangman)),
    qzHangmanError: hangmanError(a.hangman, live),
    qzOptionErrors: optionMediaErrors(a.qa, live),
    qzAccusationOn: !!(a.accusation && a.accusation.enabled),
    qzSuspects: a.accusation && a.accusation.enabled ? syncSuspects(a.accusation, a.qa && a.qa.options) : [],
    qzAccusationError: accusationError(a, live),
    qzHintOn: Array.isArray(a.hintLadder),
    qzHintRows: (a.hintLadder || []).map((row, i) => ({ i, type: row.type, typeIndex: Math.max(0, HINT_TYPES.findIndex((t) => t.value === row.type)),
      payload: row.payload == null ? '' : row.payload, points: row.cost && row.cost.points != null ? row.cost.points : '', error: hint.levels[i] || '' })),
    qzHintQuotaError: hint.quota,
    qzAliasError: aliasError(a.answerPolicy),
  };
}

function hasErrors(advanced) {
  const v = view(advanced, true);
  return v.qzHangmanError || Object.values(v.qzOptionErrors)[0] || v.qzAccusationError
    || v.qzHintQuotaError || (v.qzHintRows.find((r) => r.error) || {}).error || v.qzAliasError || '';
}

const methods = {
  /** 保存/发布前调用:之后就地提示连「还没填」一起报,并返回第一条错误。 */
  _qzSaveError() {
    this._qzSubmitted = true;
    this._syncQuesto();
    return hasErrors(this.data.advanced);
  },
  _syncQuesto() {
    const next = view(this.data.advanced, !!this._qzSubmitted);
    const patch = {};
    Object.keys(next).forEach((key) => { if (JSON.stringify(next[key]) !== JSON.stringify(this.data[key])) patch[key] = next[key]; });
    if (Object.keys(patch).length) this.setData(patch);
  },
  _qzAdvanced(mutate) {
    const next = clone(this.data.advanced);
    if (mutate(next) === false) return;
    this._setFormState({ advanced: next, advancedConfigError: '' });
  },
  /* ---- UG-01 逐字猜词 ---- */
  qzHangmanAnswer(e) {
    const answer = e.detail.value;
    this._qzAdvanced((a) => { a.hangman = Object.assign({}, a.hangman, { answer, format: reshapeFormat(answer, (a.hangman || {}).format) }); });
  },
  qzHangmanCell(e) {
    const index = Number(e.currentTarget.dataset.index);
    this._qzAdvanced((a) => { const format = toggleGiven(a.hangman, index); if (!format) return false; a.hangman.format = format; });
  },
  qzHangmanCase(e) { this._qzAdvanced((a) => { a.hangman.caseSensitive = !!e.detail.value; }); },
  qzHangmanAddAlias() {
    this._qzAdvanced((a) => { const list = (a.hangman.aliases || []).slice(); if (list.length >= MAX_HANGMAN_ALIASES) return false; list.push(''); a.hangman.aliases = list; });
  },
  qzHangmanAlias(e) {
    const i = Number(e.currentTarget.dataset.index);
    this._qzAdvanced((a) => { a.hangman.aliases[i] = e.detail.value; });
  },
  qzHangmanDelAlias(e) {
    const i = Number(e.currentTarget.dataset.index);
    this._qzAdvanced((a) => { a.hangman.aliases.splice(i, 1); });
  },
  /* ---- UG-02 选项图 ---- */
  qzOptionImg(e) {
    const id = (this.data.advanced.qa.options || [])[Number(e.currentTarget.dataset.index)]?.id;
    if (!id) return;
    getApp().chooseImage((res) => {
      const url = res && res[0];
      if (!url) return;
      this._qzAdvanced((a) => { const o = (a.qa.options || []).find((row) => row.id === id); if (!o) return false; o.img = url; });
    }, 1);
  },
  qzOptionImgDel(e) {
    const i = Number(e.currentTarget.dataset.index);
    this._qzAdvanced((a) => { const o = a.qa.options[i]; if (!o) return false; delete o.img; delete o.alt; });
  },
  /* ---- b2-x-cat 逐字猜词在文字问答表单里切换(不单独占选择器标签) ---- */
  qzHangmanForm(e) {
    const on = !!e.detail.value;
    const before = this.data.advanced || {};
    const answer = answerText.split((before.qa && before.qa.answerText) || '')[0] || '';
    this.pickGame({ currentTarget: { dataset: { key: on ? 'hangman' : 'qaText', r5: true } } });
    if (on && answer && !String((this.data.advanced.hangman || {}).answer || '').trim()) {
      this._qzAdvanced((a) => { a.hangman = Object.assign({}, a.hangman, { answer, format: reshapeFormat(answer, []) }); });
    }
  },
  /* ---- UG-03/04 指认结案 ---- */
  qzAccusationToggle(e) {
    const on = !!e.detail.value;
    this._qzAdvanced((a) => {
      if (!on) { if (a.accusation) a.accusation.enabled = false; return; }
      const acc = Object.assign({ earlyAccuse: { allowed: false } }, a.accusation, { enabled: true });
      acc.suspects = syncSuspects(acc, (a.qa || {}).options);
      a.accusation = acc;
    });
  },
  _qzSuspect(i, fn) {
    this._qzAdvanced((a) => {
      a.accusation.suspects = syncSuspects(a.accusation, a.qa.options);
      if (!a.accusation.suspects[i]) return false;
      return fn(a.accusation.suspects[i], a);
    });
  },
  qzSuspectAvatar(e) {
    const i = Number(e.currentTarget.dataset.index);
    getApp().chooseImage((res) => { const url = res && res[0]; if (url) this._qzSuspect(i, (s) => { s.avatar = url; }); }, 1);
  },
  qzSuspectIdentity(e) { const i = Number(e.currentTarget.dataset.index); this._qzSuspect(i, (s) => { s.identity = e.detail.value; }); },
  qzTestimonyAdd(e) { const i = Number(e.currentTarget.dataset.index); this._qzSuspect(i, (s) => { s.testimony.push({ key: '', text: '' }); }); },
  qzTestimonyField(e) {
    const i = Number(e.currentTarget.dataset.index), t = Number(e.currentTarget.dataset.row), field = e.currentTarget.dataset.field;
    this._qzSuspect(i, (s) => { if (!s.testimony[t]) return false; s.testimony[t][field] = e.detail.value; });
  },
  qzTestimonyDel(e) {
    const i = Number(e.currentTarget.dataset.index), t = Number(e.currentTarget.dataset.row);
    this._qzSuspect(i, (s) => { s.testimony.splice(t, 1); });
  },
  qzAccusationText(e) { const field = e.currentTarget.dataset.field; this._qzAdvanced((a) => { a.accusation[field] = e.detail.value; }); },
  qzBoardImg() { getApp().chooseImage((res) => { const url = res && res[0]; if (url) this._qzAdvanced((a) => { a.accusation.corkboardImage = url; }); }, 1); },
  qzBoardDel() { this._qzAdvanced((a) => { delete a.accusation.corkboardImage; }); },
  qzEarlyToggle(e) { const on = !!e.detail.value; this._qzAdvanced((a) => { a.accusation.earlyAccuse = Object.assign({}, a.accusation.earlyAccuse, { allowed: on }); }); },
  qzEarlyRecord(e) { this._qzAdvanced((a) => { a.accusation.earlyAccuse = Object.assign({}, a.accusation.earlyAccuse, { allowed: true, recordAs: e.detail.value }); }); },
  /* ---- UG-11 提示 ---- */
  qzHintToggle(e) {
    const on = !!e.detail.value;
    this._qzAdvanced((a) => {
      if (!on) { delete a.hintLadder; delete a.hintFreeUsesPerRun; return; }
      a.hintLadder = [{ level: 1, type: 'TEXT', payload: '' }];
    });
  },
  qzHintQuota(e) { const v = e.detail.value; this._qzAdvanced((a) => { if (v === '') delete a.hintFreeUsesPerRun; else a.hintFreeUsesPerRun = Number(v); }); },
  qzHintAdd() {
    this._qzAdvanced((a) => { if ((a.hintLadder || []).length >= MAX_HINT_LEVELS) return false; a.hintLadder = (a.hintLadder || []).concat({ level: a.hintLadder.length + 1, type: 'TEXT', payload: '' }); });
  },
  qzHintDel(e) { const i = Number(e.currentTarget.dataset.index); this._qzAdvanced((a) => { a.hintLadder.splice(i, 1); a.hintLadder.forEach((row, k) => { row.level = k + 1; }); if (!a.hintLadder.length) { delete a.hintLadder; delete a.hintFreeUsesPerRun; } }); },
  qzHintType(e) {
    const i = Number(e.currentTarget.dataset.index), type = (HINT_TYPES[Number(e.detail.value)] || HINT_TYPES[0]).value;
    this._qzAdvanced((a) => { const row = a.hintLadder[i]; if (!row) return false; if (row.type !== type) { row.type = type; delete row.payload; } });
  },
  qzHintPayload(e) { const i = Number(e.currentTarget.dataset.index); this._qzAdvanced((a) => { a.hintLadder[i].payload = e.detail.value; }); },
  qzHintImg(e) {
    const i = Number(e.currentTarget.dataset.index);
    getApp().chooseImage((res) => { const url = res && res[0]; if (url) this._qzAdvanced((a) => { if (!a.hintLadder || !a.hintLadder[i]) return false; a.hintLadder[i].payload = url; }); }, 1);
  },
  qzHintPoints(e) {
    const i = Number(e.currentTarget.dataset.index), v = e.detail.value;
    this._qzAdvanced((a) => { if (v === '') delete a.hintLadder[i].cost; else a.hintLadder[i].cost = { points: Number(v) }; });
  },
  /* ---- §23.1 文字答案别名 ---- */
  qzAliasAdd() {
    this._qzAdvanced((a) => {
      const p = a.answerPolicy || { version: 1, normalizationPolicy: 'LEGACY_V1', acceptedAliases: [] };
      if (p.acceptedAliases.length >= MAX_ANSWER_ALIASES) return false;
      p.acceptedAliases = p.acceptedAliases.concat('');
      a.answerPolicy = p;
    });
  },
  qzAlias(e) { const i = Number(e.currentTarget.dataset.index); this._qzAdvanced((a) => { a.answerPolicy.acceptedAliases[i] = e.detail.value; }); },
  qzAliasDel(e) { const i = Number(e.currentTarget.dataset.index); this._qzAdvanced((a) => { a.answerPolicy.acceptedAliases.splice(i, 1); if (!a.answerPolicy.acceptedAliases.length) delete a.answerPolicy; }); },
};

module.exports = { HINT_TYPES, hangmanCells, hangmanGroups, reshapeFormat, toggleGiven, hangmanError, optionMediaErrors,
  syncSuspects, accusationError, hintErrors, aliasError, beforeSave, view, hasErrors, methods };
