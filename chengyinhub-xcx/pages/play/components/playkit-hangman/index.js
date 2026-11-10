// cy-playkit-hangman · 逐字猜词(施工清单 UG-01)
//
// ★ 答案不在客户端:槽位形状(预填/空槽/分隔符)与本局已补字符都来自服务端 publicProjection。
//   组件只收集玩家补的字或整词,交给 SUBMIT_HANGMAN;对错、重建整词、别名比对全在服务端。
// 交失败(缺槽/越界/冲突)由页面按原错误提示,本组件**不清空**玩家已填内容。

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');
const { codePoints, hangmanPayload } = require('../../utils/playkit-questo.js');

/** 服务端槽位 + 本地补字 → 画面格子。只改可编辑格,预填与分隔符原样。 */
function paintCells(slots, typed) {
  const edit = typed || {};
  return (slots || []).map((slot) => {
    if (slot.kind === 'SEPARATOR' || slot.kind === 'PREFILLED') return slot;
    const local = edit[slot.i];
    return Object.assign({}, slot, { ch: local != null && local !== '' ? local : (slot.kind === 'FILLED' ? slot.ch : '') });
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

/** 下一格可编辑的空格;没有返回 -1。 */
function nextBlank(cells, from) {
  const list = cells || [];
  for (let k = 0; k < list.length; k += 1) {
    const cell = list[k];
    if (cell.i > from && cell.kind !== 'SEPARATOR' && cell.kind !== 'PREFILLED' && !cell.ch) return cell.i;
  }
  return -1;
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
  data: { groups: [], active: -1, word: '', verdict: '', canSubmit: false },
  observers: {
    'show, slots, solved': function (show, slots, solved) {
      if (!show) { this._typed = {}; this._pending = false; return; }
      this._typed = this._typed || {};
      const cells = paintCells(slots, this._typed);
      /* 刚交过、服务端回来仍未解开:整体提示「还不对」,不逐格标红(服务端不给逐格对错) */
      const verdict = this._pending && !solved ? 'no' : (solved ? 'ok' : this.data.verdict);
      this._pending = false;
      this.setData({ groups: groupCells(cells), verdict, active: solved ? -1 : this.data.active, canSubmit: !!hangmanPayload(slots, this._typed, this.data.word) });
      if (solved) motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
    },
  },
  methods: {
    _paintCells: paintCells,
    _nextBlank: nextBlank,
    _groupCells: groupCells,
    onPickCell(e) {
      if (this.data.solved) return;
      const kind = e.currentTarget.dataset.kind;
      if (kind === 'PREFILLED' || kind === 'SEPARATOR') return;
      const i = Number(e.currentTarget.dataset.i);
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ active: this.data.active === i ? -1 : i });
    },
    onCellInput(e) {
      const i = this.data.active;
      if (i < 0) return '';
      const chars = codePoints(e.detail.value).filter((c) => c.trim());
      const ch = chars.length ? chars[chars.length - 1] : '';
      this._typed = Object.assign({}, this._typed, { [i]: ch });
      const cells = paintCells(this.data.slots, this._typed);
      const next = ch ? nextBlank(cells, i) : i;
      this.setData({ groups: groupCells(cells), verdict: '', active: next,
        canSubmit: !!hangmanPayload(this.data.slots, this._typed, this.data.word) });
      return '';
    },
    /** 键盘收起:格子退出编辑态,已补的字留着。 */
    onCellBlur() { if (this.data.active >= 0) this.setData({ active: -1 }); },
    onWordInput(e) {
      const word = e.detail.value;
      this.setData({ word, verdict: '', canSubmit: !!hangmanPayload(this.data.slots, this._typed, word) });
    },
    onSubmit() {
      if (this.data.solved) return;
      const payload = hangmanPayload(this.data.slots, this._typed, this.data.word);
      if (!payload) return;
      this._pending = true;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      this.triggerEvent('submit', payload);
    },
    onClose() { this.triggerEvent('close'); },
  },
});
