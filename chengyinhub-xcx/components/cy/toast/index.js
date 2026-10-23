// cy-toast · 轻提示(替代 wx.showToast,2026-09-06 三端审核「原生弹层全删」批次)
//
// 一页一个实例,id 固定 "cy-toast",由 utils/toast.js 通过 getCurrentPages() 栈顶页 selectComponent 找到它。
// 只做四件事:排队、限时、跟主题走色、给读屏报 alert。不做交互(要用户点的就不是 toast,该用 cy-modal/cy-inline-error)。
//
// 2026-10-23 例外只有一种:可撤销提示条(snackbar,utils/toast.js 的 toast.undo)。
// 先在界面上做完、提示条上留一个「撤销」,4 秒内没点才真正提交(onCommit);点了走 onUndo、不提交。
// 被下一条提示顶掉、或页面卸载时,未决的那一笔立即提交 —— 用户没点撤销,就等于同意。
const reducedMotionBehavior = require('../../../behaviors/reduced-motion.js');

const DEFAULT_MS = 2000;
const MAX_MS = 6000;
const UNDO_MS = 4000;

Component({
  behaviors: [reducedMotionBehavior],
  data: {
    visible: false,
    entered: false,   // 挂上后下一拍置 true,让 transition 有起点
    title: '',
    kind: 'info',       // info | success | error
    glyph: '',
    actionText: '', // 非空 = 可撤销提示条:可点,右侧一个文字按钮
  },
  lifetimes: {
    detached() { this._clearTimers(); this._flushCommit(); },
  },
  methods: {
    // show({ title, kind, duration }) —— 同一实例连续 show 时直接换文案并重置计时,不排队叠层
    show(options) {
      const opts = options || {};
      const title = String(opts.title == null ? '' : opts.title).trim();
      if (!title) return;
      const kind = opts.kind === 'success' || opts.kind === 'error' ? opts.kind : 'info';
      const actionText = String(opts.actionText || '').trim();
      const duration = Math.min(MAX_MS, Math.max(800, Number(opts.duration) || (actionText ? UNDO_MS : DEFAULT_MS)));
      this._clearTimers();
      // 上一条可撤销提示还没到期就被顶掉:用户没点撤销,按同意处理,立即提交
      this._flushCommit();
      this._onAction = actionText && typeof opts.onAction === 'function' ? opts.onAction : null;
      this._onCommit = actionText && typeof opts.onCommit === 'function' ? opts.onCommit : null;
      const glyph = kind === 'success' ? 'check' : (kind === 'error' ? 'warning' : '');
      if (this.data.visible || this.data.reducedMotion) {
        this.setData({ visible: true, entered: true, title, kind, glyph, actionText });
      } else {
        this.setData({ visible: true, entered: false, title, kind, glyph, actionText });
        this._enterTimer = setTimeout(() => { this._enterTimer = null; this.setData({ entered: true }); }, 20);
      }
      this._hideTimer = setTimeout(() => this.hide(), duration);
    },
    hide() {
      this._clearTimers();
      this._flushCommit();
      if (!this.data.visible) return;
      if (this.data.reducedMotion) { this.setData({ visible: false, entered: false }); return; }
      this.setData({ entered: false });
      // 150ms = --cy-motion-toast-out 的 JS 镜像(WXSS 变量 JS 读不到),与 .ct 收起那条 transition 时长一致
      this._exitTimer = setTimeout(() => { this._exitTimer = null; this.setData({ visible: false }); }, 150);
    },
    // 点「撤销」:不提交,交回调用方恢复界面
    onAction() {
      const undo = this._onAction;
      this._onAction = null;
      this._onCommit = null;
      this.hide();
      if (undo) { try { undo(); } catch (e) { /* 调用方异常不该卡住提示条 */ } }
    },
    _flushCommit() {
      const commit = this._onCommit;
      this._onAction = null;
      this._onCommit = null;
      if (commit) { try { commit(); } catch (e) { /* 同上 */ } }
    },
    _clearTimers() {
      if (this._enterTimer) { clearTimeout(this._enterTimer); this._enterTimer = null; }
      if (this._hideTimer) { clearTimeout(this._hideTimer); this._hideTimer = null; }
      if (this._exitTimer) { clearTimeout(this._exitTimer); this._exitTimer = null; }
    },
  },
});
