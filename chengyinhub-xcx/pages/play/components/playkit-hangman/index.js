// cy-playkit-hangman · 逐字猜词(施工清单 UG-01;l-kits 2026-11-10 照 proto/game gp-kit-full 改成系统键盘)
//
// ★ 答案不在客户端:槽位形状(预填/空槽/分隔符)与本局已补字符都来自服务端 publicProjection。
//   组件只收集玩家补的字,交给 SUBMIT_HANGMAN {slots};对错、重建整词、别名比对全在服务端。
// 不画自定义键盘:格子下面一个输入框接系统键盘,输入什么,可编辑格按顺序填什么;点某一格 = 把光标挪到它。
// 交失败(缺槽/越界/冲突)由页面按原错误提示,本组件**不清空**玩家已填内容;服务端判「没解开」也保留已填的格子(F02),
// 下面显示服务端回的那句话(错答表命中那条或通用错答),没有才用兜底句。
// F03 字母池(inputMode === 'pool'):不用系统键盘,格子下面是服务端给的候选字(每行 7 个,用掉的置灰);
//   点候选字填进第一个空格,点已填的格子退回,可清空。状态逻辑在 utils/letter-pool.js(与打字题字符池共用);
//   提交仍是 SUBMIT_HANGMAN {slots};错答后已填的字保留(F02),不清空。

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');
const { codePoints, hangmanPayload } = require('../../utils/playkit-questo.js');
const letterPool = require('../../utils/letter-pool.js');

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
    feedback: { type: String, value: '' },
    inputMode: { type: String, value: '' },   // 'pool' = 字母池;缺省 / 'keyboard' = 系统键盘(原样)
    pool: { type: Array, value: [] },          // 服务端候选字(已按本局打乱、已去掉提示移除的干扰字)
    wrongFeedback: { type: String, value: '' },
    howto: { type: String, value: '' },
    referenceUrl: { type: String, value: '' },   // CG-10-D-3:看不清时的资料图(内容配了才有)        // CG-01-P2-5:本站填格说明(服务端没给就用通用一句)
  },
  data: { groups: [], active: -1, pop: -1, typedText: '', focused: false, cursor: 0, blankCount: 0, verdict: '', canSubmit: false,
    poolOn: false, poolRows: [], canClear: false, howtoOpen: false, DEFAULT_HOWTO: '一格填一个字或字母；拼音不写声调，ü 用 V 代替。' },
  observers: {
    'show, slots, solved, inputMode, pool': function (show, slots, solved, inputMode, pool) {
      if (!show) { this._typed = {}; this._pending = false; this._pool = null; this._poolSlots = null; this._clearPop(); return; }
      if (inputMode === 'pool') { this._syncPool(slots, solved, pool); return; }
      /* 刚交过、服务端回来仍未解开:提示一句,保留已填的格子让玩家直接改(F02;服务端不给逐格对错) */
      const wrong = this._pending && !solved;
      this._pending = false;
      if (this._typed == null) { this._typed = distribute(slots, initialText(slots)); }
      const cells = paintCells(slots, this._typed);
      const text = this.data.typedText || initialText(slots);
      this.setData({
        poolOn: false,
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
    onToggleHowto() { this.setData({ howtoOpen: !this.data.howtoOpen }); },
    onOpenReference() {
      const url = this.data.referenceUrl;
      if (!url) return;
      wx.previewImage({ current: url, urls: [url] });
      this.triggerEvent('reference');
    },
    _paintCells: paintCells,
    _groupCells: groupCells,
    _distribute: distribute,
    _cellAt: cellAt,
    _initialText: initialText,
    _clearPop() { if (this._popT) { clearTimeout(this._popT); this._popT = null; } },
    /** 字母池:服务端回来新的 slots / pool → 对一遍本地已放的字(错答保留;池变小退掉没了的那份)。 */
    _syncPool(slots, solved, pool) {
      const wrong = this._pending && !solved;
      this._pending = false;
      // 换了一道题(题面不同):上一题放的字不带过来;同一题的重读(交卷回执 / 去掉干扰字)才保留
      if (this._poolTitle !== this.data.title) { this._pool = null; this._poolSlots = null; this._poolTitle = this.data.title; }
      this._pool = letterPool.sync(this._pool, slots, pool, this._poolSlots);
      this._poolSlots = slots;
      this._paintPool(wrong ? 'no' : (solved ? 'ok' : this.data.verdict), -1, -1);
      if (solved) motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      else if (wrong) motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
    },
    /** 字母池重画。字段写死成字面量(不拼 patch):动态 setData 门禁核不出写了哪些顶层字段。 */
    _paintPool(verdict, active, pop) {
      const v = letterPool.view(this._pool || letterPool.empty(), this.data.slots, this.data.pool);
      this.setData({
        poolOn: true,
        groups: groupCells(v.cells),
        poolRows: v.rows,
        blankCount: letterPool.fillable(this.data.slots).length,
        canSubmit: !this.data.solved && !!letterPool.hangmanPayload(this._pool || letterPool.empty(), this.data.slots),
        canClear: !this.data.solved && v.filled > 0,
        verdict,
        active,
        pop,
      });
    },
    _popPool(slot) {
      this._clearPop();
      if (slot < 0) return;
      this._popT = setTimeout(() => { this._popT = null; if (this.data.pop === slot) this.setData({ pop: -1 }); }, POP_MS);
    },
    /** 点候选字:填进第一个空格。已用掉的字 / 格子都满了不响应。 */
    onPickPool(e) {
      if (this.data.solved || !this.data.poolOn) return;
      const k = Number(e.currentTarget.dataset.k);
      const res = letterPool.fill(this._pool || letterPool.empty(), this.data.slots, this.data.pool, k);
      if (res.slot < 0) return;
      this._pool = res.state;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this._paintPool('', this.data.active, res.slot);
      this._popPool(res.slot);
    },
    /** 清空:放进去的字全部退回候选区。 */
    onClearPool() {
      if (this.data.solved || !this.data.poolOn || !this.data.canClear) return;
      this._pool = letterPool.clear(this._pool || letterPool.empty());
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this._paintPool('', this.data.active, -1);
    },
    /** 点格子:把光标挪到它、唤起系统键盘。预填 / 分隔符不响应;点格子板空白处 = 接着往下填。
     *  字母池:点已填的格子 = 把字退回候选区。 */
    onPickCell(e) {
      if (this.data.solved) return;
      const kind = e.currentTarget.dataset.kind;
      if (kind === 'PREFILLED' || kind === 'SEPARATOR') return;
      if (this.data.poolOn) {
        const i = Number(e.currentTarget.dataset.i);
        const next = letterPool.giveBack(this._pool || letterPool.empty(), i);
        if (next === this._pool) return;
        this._pool = next;
        motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
        this._paintPool('', this.data.active, -1);
        return;
      }
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
      const payload = this.data.poolOn ? letterPool.hangmanPayload(this._pool || letterPool.empty(), this.data.slots)
        : hangmanPayload(this.data.slots, this._typed, '');
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
