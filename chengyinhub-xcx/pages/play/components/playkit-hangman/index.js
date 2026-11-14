// cy-playkit-hangman · 逐字猜词(施工清单 UG-01;l-kits 2026-11-10 照 proto/game gp-kit-full 改成系统键盘)
//
// ★ 答案不在客户端:槽位形状(预填/空槽/分隔符)与本局已补字符都来自服务端 publicProjection。
//   组件只收集玩家补的字,交给 SUBMIT_HANGMAN {slots};对错、重建整词、别名比对全在服务端。
// 不画自定义键盘:格子下面一个输入框接系统键盘,输入什么,可编辑格按顺序填什么;点某一格 = 把光标挪到它。
// 交失败(缺槽/越界/冲突)由页面按原错误提示,本组件**不清空**玩家已填内容;服务端判「没解开」才清空重来(原型)。

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');
const { codePoints, hangmanPayload } = require('../../utils/playkit-questo.js');

const POP_MS = 220;   // 每填一格弹一下:过渡来回的半程(与 --cy-motion-standard 同值)

/** 可编辑格 = 非预填、非分隔符;按出现顺序编号 n(= 输入框里第几个字)。 */
function editable(slots) {
  return (slots || []).filter((s) => s.kind !== 'SEPARATOR' && s.kind !== 'PREFILLED');
}

/** 输入框文本 → 本地补字表 {槽位 i: 字}:去掉空白,按可编辑格顺序一格一字,多出来的丢掉。 */
function distribute(slots, text) {
  const chars = codePoints(text).filter((c) => c.trim());
  const typed = {};
  editable(slots).forEach((slot, n) => { if (chars[n]) typed[slot.i] = chars[n]; });
  return typed;
}

/** 服务端槽位 + 本地补字 → 画面格子。只改可编辑格,预填与分隔符原样;可编辑格带顺序号 n。 */
function paintCells(slots, typed) {
  const edit = typed || {};
  let n = 0;
  return (slots || []).map((slot) => {
    if (slot.kind === 'SEPARATOR' || slot.kind === 'PREFILLED') return slot;
    const local = edit[slot.i];
    return Object.assign({}, slot, { n: n++, ch: local != null && local !== '' ? local : (slot.kind === 'FILLED' ? slot.ch : '') });
  });
}

/** 按分隔符把格子分成词组:一个词不被折断,词与词之间才换行(长短语也能一屏看全)。 */
function groupCells(cells) {
  const groups = [];
  let cur = [];
  (cells || []).forEach((cell) => {
    if (cell.kind === 'SEPARATOR') {
      if (cur.length) groups.push({ k: 'g' + cur[0].i, cells: cur });
      cur = [];
      if (cell.ch && cell.ch !== ' ') groups.push({ k: 's' + cell.i, sep: cell.ch, cells: [] });
      return;
    }
    cur.push(cell);
  });
  if (cur.length) groups.push({ k: 'g' + cur[0].i, cells: cur });
  return groups;
}

/** 第 n 个可编辑格的槽位 i;没有返回 -1。 */
function cellAt(slots, n) {
  const list = editable(slots);
  return list[n] ? list[n].i : -1;
}

/** 服务端已记下的 FILLED 格 → 输入框初值:从头连续有字的那几格(中间断了就到此为止,顺序才对得上)。 */
function initialText(slots) {
  let out = '';
  for (const slot of editable(slots)) { if (!slot.ch) break; out += slot.ch; }
  return out;
}

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    inline: { type: Boolean, value: false },
    show: { type: Boolean, value: false },
    title: { type: String, value: '' },
    slots: { type: Array, value: [] },
    solved: { type: Boolean, value: false },
    display: { type: String, value: '' },
  },
  data: { groups: [], active: -1, pop: -1, typedText: '', focused: false, cursor: 0, blankCount: 0, verdict: '', canSubmit: false },
  observers: {
    'show, slots, solved': function (show, slots, solved) {
      if (!show) { this._typed = {}; this._pending = false; this._clearPop(); return; }
      /* 刚交过、服务端回来仍未解开:整体提示「不对」并清空重来(原型 hangSubmit 失败分支;服务端不给逐格对错) */
      const wrong = this._pending && !solved;
      this._pending = false;
      if (wrong) this._typed = {};
      if (this._typed == null) { this._typed = distribute(slots, initialText(slots)); }
      const cells = paintCells(slots, this._typed);
      const text = wrong ? '' : (this.data.typedText || initialText(slots));
      this.setData({
        groups: groupCells(cells),
        blankCount: editable(slots).length,
        typedText: text,
        verdict: wrong ? 'no' : (solved ? 'ok' : this.data.verdict),
        active: solved ? -1 : this.data.active,
        pop: -1,
        canSubmit: !solved && !!hangmanPayload(slots, this._typed, ''),
      });
      if (solved) motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      else if (wrong) motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
    },
  },
  lifetimes: { detached() { this._clearPop(); } },
  methods: {
    _paintCells: paintCells,
    _groupCells: groupCells,
    _distribute: distribute,
    _cellAt: cellAt,
    _initialText: initialText,
    _clearPop() { if (this._popT) { clearTimeout(this._popT); this._popT = null; } },
    /** 点格子:把光标挪到它、唤起系统键盘。预填 / 分隔符不响应;点格子板空白处 = 接着往下填。 */
    onPickCell(e) {
      if (this.data.solved) return;
      const kind = e.currentTarget.dataset.kind;
      if (kind === 'PREFILLED' || kind === 'SEPARATOR') return;
      const i = Number(e.currentTarget.dataset.i);
      const filled = codePoints(this.data.typedText).filter((c) => c.trim()).length;
      const n = i >= 0 ? Math.min(Number(e.currentTarget.dataset.n) || 0, filled) : filled;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ active: i >= 0 ? i : cellAt(this.data.slots, filled), cursor: n, focused: true });
    },
    onFocus() {
      if (!this.data.focused) this.setData({ focused: true });
    },
    /** 键盘收起:格子退出编辑态,已补的字留着。 */
    onBlur() { this.setData({ focused: false, active: -1 }); },
    onInput(e) {
      const slots = this.data.slots;
      const value = String(e.detail.value || '');
      const typed = distribute(slots, value);
      const prev = this._typed || {};
      // 刚填上的那一格弹一下(只在多了字时;删字不弹)
      let popped = -1;
      for (const key of Object.keys(typed)) { if (typed[key] !== prev[key]) popped = Number(key); }
      this._typed = typed;
      const cells = paintCells(slots, typed);
      const filled = Object.keys(typed).length;
      this.setData({
        groups: groupCells(cells), verdict: '',
        typedText: value,
        active: cellAt(slots, filled),
        pop: popped,
        canSubmit: !!hangmanPayload(slots, typed, ''),
      });
      this._clearPop();
      if (popped >= 0) this._popT = setTimeout(() => { this._popT = null; if (this.data.pop === popped) this.setData({ pop: -1 }); }, POP_MS);
    },
    onSubmit() {
      if (this.data.solved) return;
      const payload = hangmanPayload(this.data.slots, this._typed, '');
      if (!payload) return;
      this._pending = true;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      this.triggerEvent('submit', payload);
    },
    onClose() { this.triggerEvent('close'); },
    /* 解开后的「继续」:内嵌在故事流里时没有可关的整屏,要把「继续」交给页面去收尾本站(到站打卡);
       整屏时仍是关闭(页面 closePlayKit 在 readyForBase 时同样接着走)。 */
    onContinue() { this.triggerEvent(this.data.inline ? 'continue' : 'close'); },
  },
});
