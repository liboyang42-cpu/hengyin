/**
 * Questo 新玩法的玩家端字段搬运(UG-01 逐字猜词 / UG-02 图片选项 / UG-03·04 指认结案 / UG-10 谜面变换)。
 *
 * 只做「服务端事实 → 组件 props」:不判对错、不存答案、不推断提前或成功。
 * 判定、机会、结局段、提前结案全部来自服务端回包(施工清单 UG-01..04、§27.9)。
 */

/** 选项图只认能直接显示的地址:https 或站内路径。media-asset:/http:/data: 一律不出图,只留文字。 */
function displayImage(raw, resolve) {
  const value = typeof raw === 'string' ? raw.trim() : '';
  if (!value) return '';
  const site = value.charAt(0) === '/' && value.charAt(1) !== '/' && value.indexOf('\\') < 0;
  if (!site && value.indexOf('https://') !== 0) return '';
  const shown = typeof resolve === 'function' ? resolve(value) : value;
  return typeof shown === 'string' && /^(https:\/\/|\/)/.test(shown) ? shown : '';
}

function appResolver() {
  try {
    const app = typeof getApp === 'function' ? getApp() : null;
    return app && typeof app.getImgUrl === 'function' ? app.getImgUrl.bind(app) : null;
  } catch (e) { return null; }
}

/** qa 选项 → 组件选项。img 不可显示时整组媒体不出,alt 也不单独出现。 */
function qaOptions(options, resolve) {
  const r = resolve === undefined ? appResolver() : resolve;
  return (options || []).map((o) => {
    const img = displayImage(o && o.img, r);
    const row = { id: (o && o.id) || '', t: (o && o.label) || '' };
    if (img) { row.img = img; row.alt = (o && typeof o.alt === 'string') ? o.alt.trim() : ''; }
    return row;
  });
}

/** 谜面变换:只认服务端给的两种非原样模式;缺省、STANDARD、未知值都按原样呈现。 */
function clueMode(view) {
  const mode = view && view.clueTransform && view.clueTransform.mode;
  return mode === 'MIRROR' || mode === 'REVERSE' ? mode : '';
}

/** 逐字猜词:服务端 publicProjection → 槽位。BLANK 不带字符;answer/aliases/format 本来就不在回包里。 */
function hangmanSlots(seg) {
  return ((seg && seg.slots) || []).map((slot, idx) => {
    const kind = ['SEPARATOR', 'PREFILLED', 'FILLED', 'BLANK'].indexOf(slot && slot.kind) >= 0 ? slot.kind : 'BLANK';
    const i = Number.isSafeInteger(slot && slot.i) ? slot.i : idx;
    return { i, kind, ch: kind === 'BLANK' ? '' : String((slot && slot.ch) || '') };
  });
}

function hangmanKit(seg) {
  const s = seg || {};
  return {
    title: s.title || '',
    slots: hangmanSlots(s),
    length: Number.isSafeInteger(s.length) ? s.length : 0,
    blankCount: Number.isSafeInteger(s.blankCount) ? s.blankCount : 0,
    caseSensitive: s.caseSensitive === true,
    solved: s.solved === true,
    display: s.solved === true ? String(s.display || '') : '',
  };
}

/** 码点切分:补充平面字符各占一格(UG-01 验收④)。 */
function codePoints(text) {
  return Array.from(String(text || ''));
}

/**
 * 逐字猜词提交载荷(服务端三种形状之一)。整词框有字就交整词;否则交全部可编辑槽位。
 * 可编辑 = 非预填、非分隔符;要求每格都有字,缺格返回 null(按钮不亮,不发半截)。
 */
function hangmanPayload(slots, typed, word) {
  const whole = String(word || '').trim();
  if (whole) return { input: whole };
  const out = {};
  const edit = typed || {};
  for (const slot of slots || []) {
    if (slot.kind === 'SEPARATOR' || slot.kind === 'PREFILLED') continue;
    const ch = edit[slot.i] != null && edit[slot.i] !== '' ? edit[slot.i] : (slot.kind === 'FILLED' ? slot.ch : '');
    if (!ch) return null;
    out[String(slot.i)] = ch;
  }
  return Object.keys(out).length ? { slots: out } : null;
}

const SCREEN_TEXT_KEYS = ['title', 'text', 'body', 'content', 'caption'];
const SCREEN_IMAGE_KEYS = ['img', 'image', 'imageUrl'];

/** 作者写的屏(intro / win / lose):形状原文未定,原样展示文字与可显示的图,不猜结构。 */
function screenBlocks(screen, resolve) {
  const r = resolve === undefined ? appResolver() : resolve;
  const out = [];
  const visit = (node) => {
    if (node == null) return;
    if (typeof node === 'string') { if (node.trim()) out.push({ kind: 'text', text: node.trim() }); return; }
    if (Array.isArray(node)) { node.forEach(visit); return; }
    if (typeof node !== 'object') return;
    SCREEN_IMAGE_KEYS.forEach((key) => {
      const img = displayImage(node[key], r);
      if (img) out.push({ kind: 'img', img, alt: typeof node.alt === 'string' ? node.alt : '' });
    });
    SCREEN_TEXT_KEYS.forEach((key) => {
      if (typeof node[key] === 'string' && node[key].trim()) out.push({ kind: 'text', text: node[key].trim() });
    });
  };
  visit(screen);
  return out;
}

/**
 * 指认结论标签(UG-04 三态)。只看服务端 result.verdict / early:
 * CORRECT+非提前 = 正常结案;CORRECT+提前 = 提前结案(不是失败,也不是成功指认);FAILED = 指认失败。
 * WRONG(还能继续调查)不给结局标签;REFUSED 不落账(服务端回滚),这里也不出标签。
 */
function accusationOutcome(result) {
  if (!result || typeof result !== 'object') return '';
  if (result.verdict === 'CORRECT') return result.early === true ? 'EARLY' : 'SOLVED';
  if (result.verdict === 'FAILED') return 'FAILED';
  if (result.verdict === 'WRONG') return 'WRONG';
  return '';
}

const OUTCOME_LABEL = { SOLVED: '正常结案', EARLY: '提前结案', FAILED: '指认失败' };

/**
 * 指认页 kit。qa 段给判定事实(attempts/finished/passed),accusation 段给人物卡与结论。
 * ★ readyForBase 不参与:有限次数答错耗尽时它也为 true(§27.9)。
 */
function accusationKit(view, qaSeg, resolve) {
  const r = resolve === undefined ? appResolver() : resolve;
  const acc = (view && view.accusation) || {};
  const qa = qaSeg || {};
  const labels = {};
  (qa.options || []).forEach((o) => { if (o && o.id) labels[o.id] = o.label || ''; });
  const suspects = (acc.suspects || []).filter((s) => s && s.id).map((s) => ({
    id: s.id,
    name: s.name || labels[s.id] || '',
    identity: s.identity || '',
    avatar: displayImage(s.avatar, r),
    testimony: (s.testimony || []).filter((t) => t && t.text).map((t) => ({ key: t.key || '', text: t.text })),
  }));
  const board = acc.corkboardImage;
  const boardImg = typeof board === 'string' ? displayImage(board, r) : displayImage(board && board.img, r);
  const outcome = accusationOutcome(acc.result);
  const terminal = !!(acc.result && acc.result.terminal === true);
  /* qa 判完但服务端还没给结论:结算确认中。不拿 passed 推断「指认成功」。 */
  const pending = !acc.result && qa.finished === true;
  return {
    title: qa.title || '',
    boardImg,
    boardAlt: board && typeof board === 'object' && typeof board.alt === 'string' ? board.alt : '',
    intro: screenBlocks(acc.introScreens, r),
    suspects,
    earlyAllowed: !!(acc.earlyAccuse && acc.earlyAccuse.allowed === true),
    outcome,
    outcomeLabel: OUTCOME_LABEL[outcome] || '',
    terminal,
    pending,
    canRetry: !!(acc.result && acc.result.canRetry === true) || (!acc.result && !qa.finished),
    retryNote: (outcome === 'WRONG' || (!acc.result && Number(qa.attempts) > 0 && !qa.finished && qa.passed !== true)) ? (qa.lastFeedback || '') : '',
    wrongAttempt: outcome === 'WRONG' || (!acc.result && Number(qa.attempts) > 0 && !qa.finished && qa.passed !== true),
    screen: terminal ? screenBlocks(acc.screen, r) : [],
    lastPick: qa.lastOptionId || '',
  };
}

module.exports = { displayImage, qaOptions, clueMode, hangmanSlots, hangmanKit, codePoints, hangmanPayload,
  screenBlocks, accusationOutcome, accusationKit, OUTCOME_LABEL };
