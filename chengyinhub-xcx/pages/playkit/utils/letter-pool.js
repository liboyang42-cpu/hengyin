'use strict';
// F03 字母池 / 字符集输入(「疯狂猜图」式):答案格在上,候选字在下;点候选字填进第一个空格,
// 点已填的格子把字退回候选区,可清空。逐字猜词(hangman pool 模式)与打字题字符池(qa.letterPool)共用这一份。
//
// ★ 纯函数,不判对错:候选字、格子形状都来自服务端会话视图;这里只记「玩家把哪个字放进了哪一格」,
//   交卷载荷照原合同(SUBMIT_HANGMAN {slots} / SUBMIT_QA {input}),对错与「只能使用给出的字」都由服务端判。
// ★ 状态 = { picks: {格 i: 字}, locked: {格 i: 字} }。
//   picks 是玩家从池里放进去的字(占池里的一份,可退回);
//   locked 是服务端已记下、但池里已经没有那一份的字(提示揭开的字从池里扣掉了):照样显示,不能点,不占池,也不进载荷。
// ★ 分隔符 / 预填格不能点,也不占池里的字。

const FIXED = ['SEPARATOR', 'PREFILLED'];
const PER_ROW = 7;   // 候选字每行 7 个(规格 §3.3)

function isFillable(slot) { return !!slot && FIXED.indexOf(slot.kind) < 0; }

/** 可填格:BLANK / FILLED;按出现顺序。 */
function fillable(slots) { return (slots || []).filter(isFillable); }

/** 打字题字符池:answerLength 个空格(没有分隔符 / 预填)。 */
function blankSlots(length) {
  const n = Number.isSafeInteger(length) && length > 0 ? length : 0;
  const out = [];
  for (let i = 0; i < n; i += 1) out.push({ i, kind: 'BLANK', ch: '' });
  return out;
}

/** 服务端候选字 → 只留 1 个字符的字符串(按码点算,补充平面字也是 1 个)。 */
function poolChars(pool) {
  return (Array.isArray(pool) ? pool : []).map((c) => (typeof c === 'string' ? c : ''))
    .filter((c) => Array.from(c).length === 1);
}

function counts(chars) {
  const out = {};
  chars.forEach((c) => { out[c] = (out[c] || 0) + 1; });
  return out;
}

function empty() { return { picks: {}, locked: {} }; }

/** 放一个字进格 i:池里还有这一份就算 pick,没有就记 locked(只读显示)。left 是池里剩余份数,就地扣减。 */
function place(state, i, ch, left) {
  if (left[ch] > 0) { left[ch] -= 1; state.picks[i] = ch; } else state.locked[i] = ch;
}

/**
 * 服务端回来一份新的 slots / pool(首次打开、交卷回执、提示去掉干扰字后的重读)→ 新状态。
 * - 首次(prev 为空,或格子形状变了 = 换了一道题):服务端已记下的 FILLED 字按格放好;
 * - 之后:玩家本地放的字保留(错答后不清空);只有服务端那一格的字**变了**(提示揭开一格)才以服务端为准;
 * - 池变小(去掉干扰字):按格序重新对一遍份数,池里已经没有的那一份从格子里退掉。
 */
function sync(prev, slots, pool, prevSlots) {
  const chars = poolChars(pool);
  const left = counts(chars);
  const next = empty();
  const before = {};
  (prevSlots || []).forEach((s) => { if (s) before[s.i] = s; });
  // 换了一道题(格子形状不同)就当首次:上一题放的字不能串到这一题
  const list = slots || [];
  const sameShape = !!prevSlots && prevSlots.length === list.length
    && prevSlots.every((s, at) => !!s && !!list[at] && s.i === list[at].i && isFillable(s) === isFillable(list[at]));
  const first = !prev || !sameShape;
  fillable(slots).forEach((slot) => {
    const server = slot.kind === 'FILLED' && slot.ch ? String(slot.ch) : '';
    const old = before[slot.i];
    const changed = !first && server && !(old && old.kind === 'FILLED' && String(old.ch || '') === server);
    if (first || changed) { if (server) place(next, slot.i, server, left); return; }
    if (prev.locked[slot.i] != null) { next.locked[slot.i] = prev.locked[slot.i]; return; }
    const mine = prev.picks[slot.i];
    if (mine != null && left[mine] > 0) { left[mine] -= 1; next.picks[slot.i] = mine; }
  });
  return next;
}

/** 候选字各自用掉没有:按格序把 picks 贪心对到池里第一份同字的未用项上(同字两份时灰哪一份看起来一样)。 */
function usedFlags(state, slots, pool) {
  const chars = poolChars(pool);
  const used = chars.map(() => false);
  fillable(slots).forEach((slot) => {
    const ch = state.picks[slot.i];
    if (ch == null) return;
    const k = chars.findIndex((c, idx) => c === ch && !used[idx]);
    if (k >= 0) used[k] = true;
  });
  return used;
}

/** 点第 k 个候选字:填进第一个空的可填格。这一份已用掉 / 没有空格时原样返回。返回 { state, slot }(slot = 填进的格 i,没填为 -1)。 */
function fill(state, slots, pool, k) {
  const chars = poolChars(pool);
  const ch = chars[k];
  const used = usedFlags(state, slots, pool);
  if (ch == null || used[k]) return { state, slot: -1 };
  const target = fillable(slots).find((s) => state.picks[s.i] == null && state.locked[s.i] == null);
  if (!target) return { state, slot: -1 };
  const picks = Object.assign({}, state.picks);
  picks[target.i] = ch;
  return { state: { picks, locked: state.locked }, slot: target.i };
}

/** 点已填的格 i:字退回候选区。预填 / 分隔符 / locked / 空格原样返回。 */
function giveBack(state, i) {
  if (state.picks[i] == null) return state;
  const picks = Object.assign({}, state.picks);
  delete picks[i];
  return { picks, locked: state.locked };
}

/** 清空:玩家放的字全部退回;服务端锁定的字不动。 */
function clear(state) { return { picks: {}, locked: state.locked }; }

function filledCount(state) { return Object.keys(state.picks).length; }

/** 每个可填格都有字。 */
function complete(state, slots) {
  const list = fillable(slots);
  return list.length > 0 && list.every((s) => state.picks[s.i] != null || state.locked[s.i] != null);
}

/** SUBMIT_HANGMAN 载荷:{ slots: { "格 i": 字 } } —— 只带玩家从池里放的字;没填满返回 null(按钮不亮,不发半截)。 */
function hangmanPayload(state, slots) {
  if (!complete(state, slots)) return null;
  const out = {};
  fillable(slots).forEach((s) => { if (state.picks[s.i] != null) out[String(s.i)] = state.picks[s.i]; });
  return Object.keys(out).length ? { slots: out } : null;
}

/** SUBMIT_QA 的 input:按格序把字拼起来;没填满返回 ''。 */
function joined(state, slots) {
  if (!complete(state, slots)) return '';
  return fillable(slots).map((s) => (state.picks[s.i] != null ? state.picks[s.i] : state.locked[s.i])).join('');
}

/** 画面:格子(带 ch / 能不能点 / 可填序号 n)+ 候选字(按 7 个一行分好)。 */
function view(state, slots, pool) {
  const chars = poolChars(pool);
  const used = usedFlags(state, slots, pool);
  let n = 0;
  const cells = (slots || []).map((slot) => {
    if (!isFillable(slot)) return Object.assign({}, slot, { tap: false });
    const pick = state.picks[slot.i];
    const lock = state.locked[slot.i];
    const ch = pick != null ? pick : (lock != null ? lock : '');
    return Object.assign({}, slot, { n: n++, ch, tap: pick != null, locked: lock != null });
  });
  const keys = chars.map((ch, k) => ({ k, ch, used: used[k] }));
  const rows = [];
  for (let at = 0; at < keys.length; at += PER_ROW) rows.push({ r: at / PER_ROW, keys: keys.slice(at, at + PER_ROW) });
  return { cells, rows, filled: filledCount(state), complete: complete(state, slots) };
}

module.exports = { PER_ROW, empty, fillable, blankSlots, poolChars, sync, fill, giveBack, clear, complete,
  hangmanPayload, joined, view, filledCount };
