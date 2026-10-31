// cy-playkit-ballshake · 弹球(原型真源:模板编辑页 v2 · playkit shake)
//
// 倾斜手机让球撞够手机四条边。三条设计决定都不是随手定的:
//   · **不画内框** —— 墙就是手机的四条边。多一个框,人会以为框外还有别的东西;
//   · **不给默认重力** —— 球只跟着倾斜走。给一个默认向下的话,手机平放它也一直在弹,
//     玩家会以为这屏在自己动,而不是自己在控制它;
//   · **球很轻** —— 重力只有普通的三分之一、阻尼几乎不给。沉的球会滚到底边来回蹭,
//     弹不起来就没得玩。
//
// ★ 零点必须校准:人举着手机是三十几度不是平的。把开局那一刻的姿势记成「水平」,
// 之后只按相对偏移给力 —— 不然一撒手球就一直往下滚。校准由 cy-play-stage 统一出屏。
//
// ★ 撞击数报服务端复核:后端按 MAX_SHAKE_HITS_PER_SECOND(12)乘真实经过时间算上限,
// 超了就是伪造。设备可以谎报次数,谎报不了时间。

const STEP_MS = 16;
const SENSOR_GAP_MS = 3500;
const R = 26;                  // 球半径,rpx。换算见下面 _r(按 750rpx 基准折成 px)
const EMBED_H_RPX = 1000; // 内嵌球场高度,与 index.wxss 的 .bl--embed 同值
const GRAVITY = 0.05;          // 倾斜 → 加速度。普通重力的三分之一
const DAMP = 0.999;            // 阻尼几乎不给:球要一直有劲
const EDGES = ['top', 'bottom', 'left', 'right'];

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');
const cyToast = require('../../../../utils/toast.js');
// C1:有服务端为这一局新发的挑战身份就随撞击数交(与 stopwatch/shout 同一口径)
const { withAttempt, freshAttempt } = require('../../utils/sensor-contract.js');

/** 反弹:反向 + 一点随机。不加随机的话球会卡进一条来回直线,
 *  分照涨但人已经不用操作了 —— 那是动画不是游戏。 */
function bounce(v, rand) {
  const r = typeof rand === 'function' ? rand() : Math.random();
  return -v * 0.98 + (r - 0.5) * 0.5;
}

/** 相对零点的倾斜 → 这一帧的加速度。零点没量过时不给力,球不动。 */
function finiteTilt(value) {
  return !!value && typeof value.x === 'number' && Number.isFinite(value.x)
    && typeof value.y === 'number' && Number.isFinite(value.y);
}

function tiltAccel(acc, zero) {
  if (!finiteTilt(acc) || !finiteTilt(zero)) return { gx: 0, gy: 0 };
  return {
    gx: -(acc.x - zero.x) * GRAVITY,
    gy: (acc.y - zero.y) * GRAVITY,
  };
}

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    inline: { type: Boolean, value: false }, // 故事流内嵌:墙 = 这一块球场的四条边,不是手机四边
    show: { type: Boolean, value: false },
    kicker: { type: String, value: '撞够 30 次' },
    goal: { type: Number, value: 30 },
    timed: { type: Boolean, value: false },
    seconds: { type: Number, value: 12 },
    challenge: { type: Object, value: null },
    sharedChallenge: { type: Boolean, value: false },
    // C1:服务端发的挑战身份(0 = 没有,照旧只交撞击数)
    attemptId: { type: Number, value: 0 },
  },
  data: {
    stageKeys: [0],
    hits: 0,
    running: false,
    awaitingResult: false,
    localNotice: '',
    ball: { x: 0, y: 0 },
    trail: [],
    flash: '',
    edges: EDGES,
  },
  observers: {
    challenge: function (challenge) {
      if (!challenge) return;
      this.setData({ awaitingResult: false });
      if (challenge.success === true || challenge.disabledReason) {
        this._interrupt(() => {
          const stage = this.selectComponent('#cy-play-stage');
          if (challenge.success === true && stage && !this._hidden && !this._detached) stage.win('挑战已完成');
        }, '');
      }
    },
    show: function (show) {
      if (!show) { this._interrupt(); return; }
      this._closed = false;
      this.setData({ hits: 0, running: false, flash: '', trail: [] });
      this._measure();
    },
  },
  lifetimes: { detached() { this._detached = true; this._interrupt(); } },
  pageLifetimes: {
    hide() { this._hidden = true; this._interrupt(); },
    show() { this._hidden = false; },
  },
  methods: {
    _bounce: bounce,
    _tiltAccel: tiltAccel,

    _stop() {
      if (this._timer) clearInterval(this._timer);
      if (this._watchdog) clearTimeout(this._watchdog);
      this._timer = null; this._watchdog = null;
    },
    _offTilt() {
      // Relinquish ownership before calling platform APIs: they may fail synchronously.
      const listener = this._onTilt;
      this._onTilt = null;
      if (!this._tiltOn) return;
      this._tiltOn = false;
      try { if (wx.offAccelerometerChange) wx.offAccelerometerChange(listener); } catch (e) { /* invalidated callback is inert */ }
      try { if (wx.stopAccelerometer) wx.stopAccelerometer({ fail() {} }); } catch (e) { /* cleanup is best effort */ }
    },
    _interrupt(after, notice) {
      const interrupted = this._attempt && (this._attempt.phase === 'preparing' || this._attempt.phase === 'running');
      const stage = this._attempt ? this._attempt.stage : this.selectComponent('#cy-play-stage');
      this._attempt = null; // invalidate before cleanup or any reentrant callback
      this._stop(); this._offTilt();
      this._gx = 0; this._gy = 0; this._vx = 0; this._vy = 0; this._zero = null;
      if (stage) stage._stopAll();
      this._retiredStage = stage;
      // Keyed replacement isolates even an already queued child calibration/deadline callback.
      // It must never run against the stage used by the next attempt.
      const key = this.data.stageKeys[0] + 1;
      this.setData({ running: false, stageKeys: [key],
        localNotice: notice !== undefined ? notice : (interrupted ? '本次已中断，重新开始会重新校准。' : this.data.localNotice) }, () => {
        if (after && this.data.stageKeys[0] === key && !this._detached) after();
      });
    },
    _current(attempt) {
      return !!attempt && this._attempt === attempt && !this._hidden && !this._detached
        && !this._closed && this.data.show;
    },
    _eventAttempt(e) {
      const attempt = this._attempt;
      return this._current(attempt) && e && e.currentTarget && e.currentTarget.dataset
        && String(e.currentTarget.dataset.stageKey) === String(attempt.key) ? attempt : null;
    },
    _sensorFailed(attempt, notice = '动作读取已中断，重新开始会重新校准。') {
      if (!this._current(attempt) || attempt.phase !== 'running') return;
      this._interrupt(null, notice);
      cyToast('动作读取中断，请重新开始');
    },
    _armWatchdog(attempt) {
      if (this._watchdog) clearTimeout(this._watchdog);
      this._watchdog = setTimeout(() => this._sensorFailed(attempt), SENSOR_GAP_MS);
    },

    _measure() {
      const info = wx.getSystemInfoSync ? wx.getSystemInfoSync() : { windowWidth: 375, windowHeight: 700 };
      this._w = info.windowWidth;
      // 内嵌时球场是故事流里一块 EMBED_H_RPX 高、整宽的场地(见 .bl--embed),墙就是它的四条边
      this._h = this.data.inline ? EMBED_H_RPX * (info.windowWidth / 750) : info.windowHeight;
      /* rpx → px:750rpx = 屏宽,所以 1rpx = windowWidth/750 px。
         写死 R/2 只在 375pt 的屏上对,窄屏球会偏大、宽屏偏小 —— 而这一屏
         的判定是「球撞到边几次」,球的大小直接改变难度。 */
      this._r = R * (this._w / 750);
    },

    onStart() {
      if (this._hidden || this._detached || this._closed || !this.data.show || this._attempt && this._attempt.phase !== 'finished'
        || this.data.running || this.data.awaitingResult
        || this.data.challenge && (this.data.challenge.success || this.data.challenge.disabledReason)) return;
      if (this._attempt && this._attempt.phase === 'finished') {
        this._interrupt(() => this.onStart()); return;
      }
      const stage = this.selectComponent('#cy-play-stage');
      if (!stage || stage === this._retiredStage) return;
      this._closed = false;
      this.setData({ localNotice: '' });
      this._zero = null;
      this._attempt = { stage, key: this.data.stageKeys[0], phase: 'preparing', lastFrame: null };
      stage.begin();
    },

    /** Only this keyed stage's calibration/countdown may start its sensor. */
    onRun(e) {
      const attempt = this._eventAttempt(e);
      if (!attempt || attempt.phase !== 'preparing') return;
      attempt.phase = 'running';
      attempt.startedAt = Date.now();
      this._measure();
      this._x = this._w / 2; this._y = this._h * 0.35;
      this._vx = 0; this._vy = 0; this._gx = 0; this._gy = 0;
      this._hits = 0; this._submitted = false;
      this.setData({ hits: 0, running: true });
      if (typeof wx.startAccelerometer !== 'function' || typeof wx.onAccelerometerChange !== 'function'
        || typeof wx.offAccelerometerChange !== 'function' || typeof wx.stopAccelerometer !== 'function') {
        this._sensorFailed(attempt, '当前无法读取手机动作，重新开始会重新校准。'); return;
      }
      const listener = (res) => {
        if (!this._current(attempt) || attempt.phase !== 'running' || !finiteTilt(res)) return;
        const now = Date.now();
        const previous = attempt.lastFrame === null ? attempt.startedAt : attempt.lastFrame;
        if (now - previous >= SENSOR_GAP_MS) { this._sensorFailed(attempt); return; }
        attempt.lastFrame = now;
        this._armWatchdog(attempt);
        if (!this._zero) { this._zero = { x: res.x, y: res.y }; return; }
        const a = tiltAccel(res, this._zero);
        this._gx = a.gx; this._gy = a.gy;
      };
      // Own the exact listener before registration/start can call a synchronous failure.
      this._onTilt = listener; this._tiltOn = true;
      try {
        wx.onAccelerometerChange(listener);
        if (!this._current(attempt)) return;
        wx.startAccelerometer({ interval: 'game', fail: () => this._sensorFailed(attempt, '动作读取未能开始，重新开始会重新校准。') });
      } catch (error) { this._sensorFailed(attempt, '动作读取未能开始，重新开始会重新校准。'); return; }
      if (!this._current(attempt)) return;
      /* Preserve the existing server start/submit protocol. No result is sent on interruption. */
      // 开表那一刻手里的号是上一局的;只有这次开表后服务端新发的号才随成绩交
      attempt.attemptAtRun = this.data.attemptId;
      this.triggerEvent('start');
      if (!this._current(attempt)) return;
      this._armWatchdog(attempt);
      this._timer = setInterval(() => { if (this._current(attempt)) this._step(); }, STEP_MS);
    },

    _step() {
      const attempt = this._attempt;
      if (!this._current(attempt) || !this.data.running) return;
      // Check freshness before physics too: a delayed watchdog must not allow inertial hits.
      const previous = attempt.lastFrame === null ? attempt.startedAt : attempt.lastFrame;
      if (Date.now() - previous >= SENSOR_GAP_MS) { this._sensorFailed(attempt); return; }
      if (attempt.lastFrame === null) return;
      this._vx += this._gx; this._vy += this._gy;
      this._vx *= DAMP; this._vy *= DAMP;
      this._x += this._vx; this._y += this._vy;

      const r = this._r;
      let hit = '';
      if (this._x < r)             { this._x = r;             this._vx = bounce(this._vx); hit = 'left'; }
      if (this._x > this._w - r)   { this._x = this._w - r;   this._vx = bounce(this._vx); hit = 'right'; }
      if (this._y < r)             { this._y = r;             this._vy = bounce(this._vy); hit = 'top'; }
      if (this._y > this._h - r)   { this._y = this._h - r;   this._vy = bounce(this._vy); hit = 'bottom'; }

      const trail = (this.data.trail || []).slice(0, 3);
      trail.unshift({ i: Date.now(), x: Math.round(this._x), y: Math.round(this._y), o: 0.14 });
      trail.forEach((t, i) => { t.o = 0.14 - i * 0.035; });

      if (hit) {
        this._hits += 1;
        motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      }
      /* 字段写死成字面量,不拼 patch 对象:UI-GATE-0 认不出动态 setData 写了哪些顶层字段,
         也就核不出它们在 WXML 有没有被消费。撞边那两个字段没撞时按原值回写,不多不少。 */
      this.setData({
        ball: { x: Math.round(this._x), y: Math.round(this._y) },
        trail: trail,
        hits: this._hits,
        flash: hit ? hit : this.data.flash,
      });

      if (hit && this._hits >= this.data.goal) this._finish(true);
    },

    _finish(won) {
      const attempt = this._attempt;
      if (!this._current(attempt) || attempt.phase !== 'running' || this._submitted) return;
      // Every exit, including the stage deadline and manual submit, needs a real fresh sample.
      if (typeof attempt.lastFrame !== 'number' || !Number.isFinite(attempt.lastFrame)
        || Date.now() - attempt.lastFrame >= SENSOR_GAP_MS) { this._sensorFailed(attempt); return; }
      this._submitted = true;
      attempt.phase = 'reporting';
      this._stop(); this._offTilt();
      this.setData({ running: false, awaitingResult: !!this.data.challenge });
      const stage = this.selectComponent('#cy-play-stage');
      if (stage) {
        if (this.data.challenge) stage._stopAll(); // 本机目标和超时都不是团队成功事实。
        else if (won) stage.win('撞满了'); else stage.fail(true);
      }
      attempt.phase = 'finished';
      this.triggerEvent('submit', withAttempt({ hits: this._hits || 0, passed: !!won },
        { attemptId: freshAttempt(this.data.attemptId, attempt.attemptAtRun) }));
    },
    onSubmitHits() { if (this.data.running && this.data.sharedChallenge) this._finish(false); },

    onVerdict(e) {
      const attempt = this._eventAttempt(e);
      if (!attempt || attempt.phase === 'finished') return;
      // 到点也提交本次部分撞击；去重和全队累计由服务端完成。
      if (e.detail && e.detail.kind === 'fail') this._finish(false);
      if (this._current(attempt)) this.triggerEvent('verdict', e.detail);
    },
    onClose(e) {
      if (e && (!e.currentTarget || !e.currentTarget.dataset
        || String(e.currentTarget.dataset.stageKey) !== String(this.data.stageKeys[0]))) return;
      this._closed = true; this._interrupt(); this.triggerEvent('close');
    },
  },
});
