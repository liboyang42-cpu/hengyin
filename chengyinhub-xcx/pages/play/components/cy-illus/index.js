// cy-illus:原型 proto/game/icons.js 的空态风格插图(线索 / 勋章 / 成功 / 失败 / 调查中 / 通行证 / 章节印 …)。
// 进场与待机全部是 transform / opacity 过渡(仓库 keyframes 棘轮固定 146,不新增):
//   进场 = attached 后按 delay 加 is-on;待机 = 计时器在 is-idle 上来回切,过渡时长等于半周期,看起来是往复。
// 减少动态效果(系统或应用内开关):直接终态,不起计时器。页面隐藏时暂停待机。
const art = require('./art.js');
const { readReducedMotion } = require('../../../../utils/motion-preference.js');

const ENTER = { key: 'swing', seal: 'press', fail: 'drop', points: 'drop', success: 'burst', sparkles: 'burst', rays: 'burst' };
const IDLE = {
  success: 'sway', sparkles: 'sway', rays: 'sway', hourglass: 'flip', trophy: 'wobble', compass: 'wobble', key: 'swing',
  badge: 'hang', bookmark: 'hang', envelope: 'hang', wait: 'scan', fail: 'drift', unlocked: 'none', seal: 'none',
};
const IDLE_MS = { flip: 3000, sway: 4500, scan: 1800 };
const DEFAULT_IDLE_MS = 1800;

Component({
  options: { addGlobalClass: false },
  properties: {
    name: { type: String, value: 'clue', observer: '_paint' },
    size: { type: Number, value: 128 },      // rpx
    n: { type: null, value: null, observer: '_paint' }, // 只给「章节印」用
    delay: { type: Number, value: 0 },       // ms,多张错峰
    bare: { type: Boolean, value: false },   // 不要底层白色有机形
    loop: { type: Boolean, value: true },    // 待机往复
    reducedMotion: { type: null, value: null }, // 宿主已知时传入;不传则读系统 + 应用内开关
  },
  data: { kind: 'clue', label: '', blob: '', fg: '', on: false, idle: false, reduced: false, enter: 'pop', idleKind: 'float' },
  lifetimes: {
    attached() { this._paint(); this._start(); },
    detached() { this._stop(); },
  },
  pageLifetimes: {
    show() { if (this._hidden) { this._hidden = false; this._loop(); } },
    hide() { this._hidden = true; clearTimeout(this._idleT); },
  },
  methods: {
    _paint() {
      const l = art.layers(this.properties.name, this.properties.n);
      this.setData({ kind: l.name, label: l.label, blob: l.blob, fg: l.fg, enter: ENTER[l.name] || 'pop', idleKind: IDLE[l.name] || 'float' });
    },
    _isReduced() {
      const r = this.properties.reducedMotion;
      if (r === true || r === false) return r;
      try { return !!readReducedMotion(); } catch (e) { return false; }
    },
    _start() {
      this._stop();
      const reduced = this._isReduced();
      if (reduced) { this.setData({ reduced: true, on: true, idle: false }); return; }
      this.setData({ reduced: false, on: false, idle: false });
      // 两帧后再加 is-on,保证起点样式先落地(与 look-transition 的 is-pre 同一做法)
      this._enterT = setTimeout(() => { this.setData({ on: true }); this._loop(900); }, 34 + Math.max(0, this.properties.delay | 0));
    },
    _loop(wait) {
      clearTimeout(this._idleT);
      if (!this.properties.loop || this.data.reduced || this._hidden || this.data.idleKind === 'none') return;
      const ms = IDLE_MS[this.data.idleKind] || DEFAULT_IDLE_MS;
      this._idleT = setTimeout(() => { this.setData({ idle: !this.data.idle }); this._loop(); }, wait == null ? ms : wait);
    },
    _stop() { clearTimeout(this._enterT); clearTimeout(this._idleT); },
    /** 宿主想重播进场(例如结果切换)时调用。 */
    replay() { this._start(); },
  },
});
