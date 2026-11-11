// cy-illus:原型 proto/game/icons.js 的空态风格插图(勋章以外的 15 枚)+ proto/icons 风格 B v2 精致插画(线索 / 勋章 / 钥匙 / 成功 / 失败 / 调查中 / 队伍 / 通行证 / 积分 / 章节印)。
// 进场与待机全部是 transform / opacity 过渡(仓库 keyframes 棘轮固定 146,不新增):
//   旧画法:进场 = attached 后按 delay 加 is-on;待机 = 计时器在 is-idle 上来回切,过渡时长等于半周期,看起来是往复。
//   风格 B:进场 = is-on 后按 art-b.js MOTION.enter 逐个加 e1、e2…;待机 = 每圈按 MOTION.steps 逐个累加 p1、p2…,周期 T 到了撤掉重来。
// 减少动态效果(系统或应用内开关):直接终态,不起计时器。页面隐藏时暂停待机。
const art = require('./art.js');
const { MOTION } = require('./art-b.js');
const { readReducedMotion } = require('../../../../utils/motion-preference.js');

const ENTER = { key: 'swing', seal: 'press', fail: 'drop', points: 'drop', success: 'burst', sparkles: 'burst', rays: 'burst' };
const IDLE = {
  success: 'sway', sparkles: 'sway', rays: 'sway', hourglass: 'flip', trophy: 'wobble', compass: 'wobble', key: 'swing',
  badge: 'hang', bookmark: 'hang', envelope: 'hang', wait: 'scan', fail: 'drift', unlocked: 'none', seal: 'none',
};
const IDLE_MS = { flip: 3000, sway: 4500, scan: 1800 };
const DEFAULT_IDLE_MS = 1800;
const SNAP_MS = 40; // 一圈开头先撤掉所有阶段类,留一帧让「瞬回」落地,再加第一个阶段

function stages(prefix, n) { let s = ''; for (let i = 1; i <= n; i++) s += (i > 1 ? ' ' : '') + prefix + i; return s; }

Component({
  options: { addGlobalClass: false },
  properties: {
    name: { type: String, value: 'clue', observer: '_paint' },
    size: { type: Number, value: 128 },      // rpx
    n: { type: null, value: null, observer: '_paint' }, // 只给旧画法「章节印」用(风格 B 的章节印是白星,不画数字)
    delay: { type: Number, value: 0 },       // ms,多张错峰
    bare: { type: Boolean, value: false },   // 不要底层(旧画法白色有机形 / 风格 B 脚下软影)
    loop: { type: Boolean, value: true },    // 待机往复
    reducedMotion: { type: null, value: null }, // 宿主已知时传入;不传则读系统 + 应用内开关
  },
  data: { kind: 'clue', b2: false, layers: [], blob: '', fg: '', on: false, idle: false, reduced: false, enter: 'pop', idleKind: 'float', ec: '', pc: '' },
  lifetimes: {
    attached() { this._paint(); this._start(); },
    detached() { this._stop(); },
  },
  pageLifetimes: {
    show() { if (this._hidden) { this._hidden = false; this._loop(); } },
    hide() { this._hidden = true; this._stopIdle(); },
  },
  methods: {
    _paint() {
      const l = art.layers(this.properties.name, this.properties.n);
      const changed = this.data.kind !== l.name;
      if (l.b2) this.setData({ kind: l.name, b2: true, layers: l.layers, blob: '', fg: '', enter: 'b2', idleKind: 'b2' });
      else this.setData({ kind: l.name, b2: false, layers: [], blob: l.blob, fg: l.fg, enter: ENTER[l.name] || 'pop', idleKind: IDLE[l.name] || 'float' });
      // 换了另一枚风格 B(例如判定从调查中变成成功):时间表不同,从进场重来
      if (changed && this._started && this.data.b2) this._start();
    },
    _isReduced() {
      const r = this.properties.reducedMotion;
      if (r === true || r === false) return r;
      try { return !!readReducedMotion(); } catch (e) { return false; }
    },
    _start() {
      this._stop();
      this._started = true;
      const reduced = this._isReduced();
      if (reduced) { this.setData({ reduced: true, on: true, idle: false, ec: '', pc: '' }); return; }
      this.setData({ reduced: false, on: false, idle: false, ec: '', pc: '' });
      // 两帧后再加 is-on,保证起点样式先落地(与 look-transition 的 is-pre 同一做法)
      this._enterT = setTimeout(() => {
        this.setData({ on: true });
        if (!this.data.b2) { this._loop(900); return; }
        const m = MOTION[this.data.kind] || {};
        (m.enter || []).forEach((at, i) => this._push(this._fx, () => this.setData({ ec: stages('e', i + 1) }), at));
        if (m.T) this._push(this._fx, () => this._loop(), m.idleAt || 0);
      }, 34 + Math.max(0, this.properties.delay | 0));
    },
    _push(list, fn, ms) { list.push(setTimeout(fn, ms)); },
    _loop(wait) {
      this._stopIdle();
      if (!this.properties.loop || this.data.reduced || this._hidden || !this.data.on) return;
      if (this.data.b2) {
        const m = MOTION[this.data.kind];
        if (!m || !m.T) return;
        this.setData({ idle: true, pc: '' });
        m.steps.forEach((at, i) => this._push(this._idle, () => this.setData({ pc: stages('p', i + 1) }), Math.max(SNAP_MS, at)));
        this._push(this._idle, () => this._loop(), m.T);
        return;
      }
      if (this.data.idleKind === 'none') return;
      const ms = IDLE_MS[this.data.idleKind] || DEFAULT_IDLE_MS;
      this._idleT = setTimeout(() => { this.setData({ idle: !this.data.idle }); this._loop(); }, wait == null ? ms : wait);
    },
    _stopIdle() { clearTimeout(this._idleT); (this._idle || []).forEach(clearTimeout); this._idle = []; },
    _stop() { clearTimeout(this._enterT); (this._fx || []).forEach(clearTimeout); this._fx = []; this._stopIdle(); },
    /** 宿主想重播进场(例如结果切换)时调用。 */
    replay() { this._start(); },
  },
});
