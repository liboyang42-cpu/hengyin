// cy-playkit-typein · 限时打字(契约 §2.5)
//
// ★ 必须先开表:开始那一刻先发 START_CHALLENGE,服务端拿服务器时间记起点,
//   提交时的 elapsedMs 只是客户端读数、供核对用 —— 设备时钟玩家改得动。
// ★ 限时交给 cy-play-stage:它那条截止时刻是单独 setTimeout 钉死的,切后台也会到点判负;
//   组件自己再数一遍秒只负责显示,不参与判定。
// ★ 判定在服务端:内容相等 + 用时 <= seconds 都在后端算,组件只报原始输入。
const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const replayMotionBehavior = require('../../../../behaviors/replay-motion.js');
const motion = require('../../../../utils/motion.js');

Component({
  behaviors: [reducedMotionBehavior, replayMotionBehavior],
  properties: {
    show: { type: Boolean, value: false },
    title: { type: String, value: '' },
    target: { type: String, value: '' },
    seconds: { type: Number, value: 10 },
    r5: { type: Boolean, value: false },
    deadlineAt: { type: Number, value: 0 },
    caseSensitive: { type: Boolean, value: false },
    tries: { type: Number, value: 0 },        // 0 = 不限
    attempts: { type: Number, value: 0 },
    passed: { type: Boolean, value: false },
    startConfirmed: { type: Boolean, value: false },
    startFailureSeq: { type: Number, value: 0 },
    terminalSkin: { type: Boolean, value: false },   // 终端样式(原型 hello world 开机)
  },
  data: {
    phase: 'intro',                            // intro | run
    keyRows: ['qwertyuiop'.split(''), 'asdfghjkl'.split(''), 'zxcvbnm'.split('')],
    ghost: '',
    nextKey: '',
    mistyped: false,
    typed: '',
    attemptLabel: '',
    canSubmit: false,
    locked: false,
    submitting: false,
    off: '',                                    // 终端打对后「关机」:off → gone
  },
  observers: {
    startFailureSeq(seq) {
      if (!seq || this.data.phase !== 'arming') return;
      this._firstKey = null;
      this._t0 = 0;
      this.setData({ phase: 'intro', typed: '', canSubmit: false, submitting: false });
    },
    startConfirmed(confirmed) {
      if (!confirmed || this.data.phase !== 'arming') return;
      const stage = this.selectComponent('#cy-play-stage');
      if (stage) stage.begin({ immediate: this.data.terminalSkin }); else this.onRun();
    },
    /* 同一类型换到下一个 typein 节点时,playkit 的 wx:elif 分支不变、组件不重建,
       .ti__target 只是原地改字 —— 不重播的话目标文本直接跳变。
       首次挂载 prev 是 undefined,不走(那时节点本来就是新建的)。 */
    target(value) {
      const prev = this._seenTarget;
      this._seenTarget = value;
      if (prev !== undefined && prev !== value) this.replayMotion();
    },
    'show, attempts, passed, target': function (show, attempts, passed, target) {
      if (!show) { this._roundSignature = ''; this._firstKey = null; return; }
      const signature = JSON.stringify([attempts, passed, target]);
      if (signature === this._roundSignature) return;
      this._roundSignature = signature;
      /* 没打对整块台面抖一下 —— 与原版问答答错同一条(cy-play-stage 的 nudge,
         原型 .phone.shake:.28s cubic-bezier(.36,.07,.19,.97))。
         判据是「次数涨了但仍没过」:判定在服务端,客户端只认回流的这两个属性。
         ⚠️ 首次进屏(_seenAttempts 还没有)不抖,否则重进一次就平白抖一下。 */
      const seen = this._seenAttempts;
      const now = Number(attempts) || 0;
      if (seen != null && now > seen && !passed) {
        const stage = this.selectComponent('#cy-play-stage');
        if (stage && stage.nudge) stage.nudge();
      }
      this._seenAttempts = now;

      this._t0 = 0;
      this._firstKey = null;
      const tries = Number(this.data.tries) || 0;
      this.setData({
        phase: 'intro',
        typed: '',
        ghost: target || '',
        nextKey: (target || '').charAt(0),
        mistyped: false,
        off: '',
        canSubmit: false,
        submitting: false,
        attemptLabel: tries > 0 ? ('第 ' + Math.min(tries, (Number(attempts) || 0) + 1) + ' / ' + tries + ' 次') : '',
        // 打对了就锁住:服务端也不会再收
        locked: !!passed || (tries > 0 && attempts >= tries),
      });
      const stage = this.selectComponent('#cy-play-stage');
      // 终端样式刚打对:先像老电视关机一样压成一条亮线再收掉,再出判定(原型 bootout);重进直接出判定
      const justPassed = !!passed && this._seenPassed === false;
      this._seenPassed = !!passed;
      if (stage) {
        stage._stopAll();
        if (passed && justPassed && this.data.terminalSkin && !this.data.reducedMotion) this._powerOff(stage);
        else if (passed) stage.win('一字不差');
        else if (tries > 0 && attempts >= tries) stage.fail(false);
      }
    },
  },
  lifetimes: {
    detached() { (this._offTimers || []).forEach(clearTimeout); },
  },
  methods: {
    _powerOff(stage) {
      (this._offTimers || []).forEach(clearTimeout);
      this.setData({ off: 'off' });
      this._offTimers = [
        setTimeout(() => this.setData({ off: 'gone' }), 480),
        setTimeout(() => stage.win('一字不差'), 820),
      ];
    },
    /** 「开始」→ 台面数 3-2-1 → bind:run 回到这儿真正开跑(与 stopwatch 同一条路) */
    onStart() {
      if (this.data.locked || this.data.phase === 'arming') return;
      this.setData({ phase: 'arming' });
      if (this.data.r5 && this.data.startConfirmed) {
        const stage = this.selectComponent('#cy-play-stage');
        if (stage) stage.begin({ immediate: this.data.terminalSkin }); else this.onRun();
        return;
      }
      this.triggerEvent('start');
    },

    onRun() {
      this._t0 = Date.now();
      this.setData({ phase: 'run', typed: '', canSubmit: false });
      // 服务端确认开表后才启动本地计时，网络等待不计入玩家输入时间。
      if (this._firstKey != null) {
        const key = this._firstKey;
        this._firstKey = null;
        this._terminalInput(key);
      }
    },

    onKey(e) {
      if (this.data.locked || this.data.submitting || this.data.phase === 'arming') return;
      const key = e.currentTarget.dataset.key;
      if (key !== 'back' && !/^[a-zA-Z ]$/.test(key || '')) return;
      if (this.data.phase !== 'run') {
        if (key === 'back') return;
        this._firstKey = key;
        this.onStart();
        return;
      }
      this._terminalInput(key);
    },

    _terminalInput(key) {
      const typed = key === 'back' ? this.data.typed.slice(0, -1) : this.data.typed + key;
      const target = this.data.target || '';
      if (typed.length > target.length) return;
      const same = this.data.caseSensitive ? typed : typed.toLowerCase();
      const expected = this.data.caseSensitive ? target : target.toLowerCase();
      this.setData({ typed, ghost: target.slice(typed.length), nextKey: target.charAt(typed.length),
        mistyped: same !== expected.slice(0, typed.length), canSubmit: !!typed.trim() });
      if (typed.length === target.length) this.onSubmit();
    },

    onInput(e) {
      const typed = e.detail.value == null ? '' : String(e.detail.value);
      this.setData({ typed, canSubmit: !!typed.trim() });
    },

    onSubmit() {
      if (this.data.locked || this.data.submitting || this.data.phase !== 'run' || !this.data.canSubmit) return;
      // 交卷不可撤销 —— 与作答同一档触感。提交中锁住按钮:幂等键能兜住重复,但连点两次
      // 会多发一次请求,等回读那段时间里按钮看着还能点
      this.setData({ submitting: true });
      const stage = this.selectComponent('#cy-play-stage');
      if (stage) stage._stopAll();
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      this.triggerEvent('submit', {
        text: this.data.typed,
        elapsedMs: this._t0 ? Date.now() - this._t0 : 0,
      });
    },

    onVerdict(e) {
      if (this.data.r5) return;
      if (!e.detail || !e.detail.timedOut || this.data.phase !== 'run' || this.data.submitting) return;
      // 超时也交给宿主结算本次尝试；作者预览与正式游玩共用同一反馈链。
      this.setData({ canSubmit: true });
      this.onSubmit();
    },

    onDeadline() { if (this.data.r5) this.triggerEvent('refresh'); },

    onClose() { this.triggerEvent('close'); },
  },
});
