// cy-playkit-compass · 罗盘指向(原型真源:模板编辑页 v2 · playkit compass)
//
// ★ 做过就算,没有对错、不限次数 —— 与估数/预测同口径(现场感契约 §3.4)。
// ★ 判「对准并保持」不判「扫过」:手抖、边走边看会在某一帧正好压进容差区,
//   只认瞬间值等于送分。holdSeconds 就是商家要人站在那儿的那几秒。
// ★ 针角必须走**连续**轨道:朝向从 359° 回到 1° 时针只该转 2°,
//   直接拿 (target-heading) mod 360 会让针每次过正北都倒着绕一大圈。
// ★ 判定在服务端复核(容差同样验一遍),客户端只做呈现,不做裁决。
// ★ 禁止当到店凭证或有金额的完成条件 —— 罗盘读数在室内偏几十度是常事,
//   拿它换真金白银的东西,第一周就会做成一个没人能赢的活动。

const motion = require('../../../../utils/motion.js');
const { readReducedMotion } = require('../../../../utils/motion-preference.js');
const cyToast = require('../../../../utils/toast.js');
// C1:罗盘方位是本机自报,奖励上限只听服务端;有服务端发的挑战身份就随方位一起交
const { withAttempt, rewardNote } = require('../../utils/sensor-contract.js');

const TICK_MS = 100;
const FIRST_FRAME_MS = 3000;
const FRAME_GAP_MS = 500;

/** 两方位角的最小夹角。与服务端 angleDiff 同口径:359° 与 1° 相差 2° 而不是 358°。 */
function angleDiff(a, b) {
  const raw = Math.abs((((a - b) % 360) + 360) % 360);
  return Math.min(raw, 360 - raw);
}

/** 归一到 0-359(提交前用,服务端门禁认这个区间)。 */
function normalize(deg) {
  return ((Math.round(deg) % 360) + 360) % 360;
}

/** 连续针角:在等价角度里挑离上一帧最近的那个,针就永远走短弧。 */
function continuous(target, prev) {
  let deg = ((target % 360) + 360) % 360;
  if (prev == null) return deg;
  while (deg - prev > 180) deg -= 360;
  while (deg - prev < -180) deg += 360;
  return deg;
}

/** 目标相对当前朝向的带符号夹角,落在 [-180, 180):正 = 目标在右手边(往右转朝向变大)。 */
function signedDelta(target, heading) {
  return ((((target - heading) % 360) + 540) % 360) - 180;
}

/** 转向提示:只说往哪边、还差多少度,不另立规则。 */
function turnText(delta) {
  const n = Math.round(Math.abs(delta));
  return (delta > 0 ? '往右转 ' : '往左转 ') + n + '°';
}

/** 读数不稳:Android 给枚举串;iOS 的 accuracy 是相对磁北的偏角、不表精度,不据此判 */
const LOW_ACCURACY = ['low', 'unreliable', 'no-contact'];
function isLowAccuracy(accuracy) {
  return typeof accuracy === 'string' && LOW_ACCURACY.indexOf(accuracy) !== -1;
}

// 刻度尺:每 5° 一格、30° 一道长刻度并标度数,四个正方位换成方位字
/** 容差区:顶上 ±tolerance 那一段,每 2.5° 一根短棒(固定在屏上,不随刻度盘转) */
function zoneBars(tol) {
  tol = typeof tol === 'number' && Number.isFinite(tol) && tol > 0 ? Math.min(180, tol) : 15;
  const n = Math.floor(tol / 2.5);
  const bars = Array.from({ length: n * 2 + 1 }, (_, i) => (i - n) * 2.5);
  // 容差不是 2.5 的整数倍时补上两端,画出来的区不能比判定区窄
  return n * 2.5 < tol ? [-tol].concat(bars, [tol]) : bars;
}

const TICKS = Array.from({ length: 72 }, (_, i) => ({ deg: i * 5, major: i % 6 === 0 }));
const CARDINAL = { 0: '北', 90: '东', 180: '南', 270: '西' };
const LABELS = Array.from({ length: 12 }, (_, i) => {
  const deg = i * 30;
  return { deg, text: CARDINAL[deg] || String(deg), cardinal: !!CARDINAL[deg], north: deg === 0 };
});

Component({
  properties: {
    show: { type: Boolean, value: false },
    kicker: { type: String, value: '' },
    target: { type: Number, value: 0 },        // 商家选定的目标方位角 0-359
    tolerance: { type: Number, value: 15 },
    holdSeconds: { type: Number, value: 3 },
    hint: { type: String, value: '' },
    done: { type: Boolean, value: false },     // 进来就对准过:只读状态,不再收
    // C1:服务端发的挑战身份(0 = 没有,照旧只交方位)与奖励上限(空 = 服务端没说,不承诺)
    attemptId: { type: Number, value: 0 },
    rewardCeiling: { type: String, value: '' },
  },
  data: {
    stageKeys: [0],
    phase: 'idle',       // idle = 还没开始 / run = 表在走
    heading: 0,          // 当前朝向(屏上那个大数)
    needleDeg: 0,        // 连续针角,transform 用它
    aligned: false,
    holdLeft: '0.0',
    noData: false,
    rm: false,
    ticks: TICKS,
    labels: LABELS,
    roseDeg: 0,          // 刻度盘反转角(连续轨道,同针角)
    hasHeading: false,   // 拿到过朝向才画方位字,没朝向时方位字是假的
    zoneBars: zoneBars(15),
    holdPct: 0,
    turnHint: '',
    lowAccuracy: false,
    rewardNote: '',
  },
  observers: {
    rewardCeiling(value) { this.setData({ rewardNote: rewardNote(value) }); },
    show(v) { if (!v) this._abort(); else this._closed = false; },
    done(v) { if (v) this._abort(); },
    tolerance(v) { this.setData({ zoneBars: zoneBars(v > 0 ? v : 15) }); },
  },
  lifetimes: {
    attached() { this.setData({ rm: readReducedMotion() }); },
    detached() { this._detached = true; this._abort(); },
  },
  // 切后台停表:罗盘事件在后台会被限频,回来那一帧的间隔可能好几秒 ——
  // 不夹住的话等于「揣兜里那半分钟」白赚保持时长(与 quiethold 同一条)
  pageLifetimes: {
    hide() { this._hidden = true; this._abort(); },
    show() { this._hidden = false; },
  },
  methods: {
    _angleDiff: angleDiff,       // 纯算法出口,供单测
    _normalize: normalize,
    _continuous: continuous,
    _signedDelta: signedDelta,
    _turnText: turnText,
    _isLowAccuracy: isLowAccuracy,
    _zoneBars: zoneBars,

    _current(attempt) {
      return !!attempt && this._attempt === attempt && !this._hidden && !this._closed
        && !this._detached && this.data.show;
    },
    _eventAttempt(e) {
      const attempt = this._attempt;
      return this._current(attempt) && e && e.currentTarget && e.currentTarget.dataset
        && String(e.currentTarget.dataset.stageKey) === String(attempt.key) ? attempt : null;
    },
    _stop() {
      if (this._timer) clearInterval(this._timer);
      if (this._watchdog) clearTimeout(this._watchdog);
      this._timer = null; this._watchdog = null;
    },
    _stopCompass() {
      const cb = this._cb;
      this._cb = null;
      if (!this._listening) return;
      this._listening = false;
      try { if (cb && wx.offCompassChange) wx.offCompassChange(cb); } catch (e) { /* invalidated callback is inert */ }
      try { if (wx.stopCompass) wx.stopCompass({ fail() {} }); } catch (e) { /* best effort after callback invalidation */ }
    },
    _abort() {
      const stage = this._attempt ? this._attempt.stage : this.selectComponent('#cy-play-stage');
      this._attempt = null;
      this._stop(); this._stopCompass();
      this._heading = null; this._held = 0;
      if (stage) stage._stopAll();
      this._retiredStage = stage;
      this.setData({
        stageKeys: [this.data.stageKeys[0] + 1], phase: this._sent ? 'done' : 'idle',
        aligned: false, noData: false, hasHeading: false, holdPct: 0, holdLeft: '0.0', turnHint: '', lowAccuracy: false,
      });
    },
    _failed(attempt) {
      if (!this._current(attempt) || attempt.phase === 'reporting' || attempt.phase === 'done') return;
      this._abort();
      this.setData({ noData: true });
      cyToast('方向读取失败，请重试或退出');
    },
    _armWatchdog(attempt, ms) {
      if (this._watchdog) clearTimeout(this._watchdog);
      this._watchdog = setTimeout(() => this._failed(attempt), ms);
    },
    _fresh(attempt) {
      return this._current(attempt) && typeof attempt.lastFrame === 'number'
        && Number.isFinite(attempt.lastFrame) && Date.now() - attempt.lastFrame < FRAME_GAP_MS;
    },

    onStart() {
      if (this._hidden || this._closed || this._detached || !this.data.show || this._sent || this.data.done || this._attempt) return;
      const stage = this.selectComponent('#cy-play-stage');
      if (!stage || stage === this._retiredStage) return;
      const attempt = this._attempt = { stage, key: this.data.stageKeys[0], phase: 'starting',
        startedAt: Date.now(), lastFrame: null, frames: 0, lastAligned: false, startReturned: false, succeeded: false };
      this._heading = null; this._held = 0; this._lowAccuracy = false;
      this._prevNeedle = null; this._prevRose = null;
      this.setData({ phase: 'idle', hasHeading: false, noData: false, aligned: false, holdPct: 0, turnHint: '', lowAccuracy: false });
      if (typeof wx.startCompass !== 'function' || typeof wx.onCompassChange !== 'function'
        || typeof wx.offCompassChange !== 'function' || typeof wx.stopCompass !== 'function'
        || typeof this.data.target !== 'number' || !Number.isFinite(this.data.target)) {
        this._failed(attempt); return;
      }
      this._target = ((this.data.target % 360) + 360) % 360;
      const accept = () => {
        if (!this._current(attempt) || !attempt.startReturned || !attempt.succeeded || attempt.phase !== 'starting') return;
        attempt.phase = 'preparing';
        stage.begin();
      };
      const cb = (res) => {
        if (!this._current(attempt) || attempt.phase !== 'run') return;
        if (!res || typeof res.direction !== 'number' || !Number.isFinite(res.direction)) {
          attempt.lastAligned = false; this._held = 0;
          this.setData({ aligned: false, hasHeading: false, holdPct: 0, holdLeft: this._holdTotal().toFixed(1) });
          return;
        }
        const now = Date.now();
        const gap = now - (attempt.lastFrame === null ? attempt.startedAt : attempt.lastFrame);
        if (gap >= (attempt.lastFrame === null ? FIRST_FRAME_MS : FRAME_GAP_MS)) { this._failed(attempt); return; }
        const direction = ((res.direction % 360) + 360) % 360;
        const aligned = angleDiff(direction, this._target) <= this._tolerance();
        // Only measured sample pairs earn time. Repeating a timer against one sample earns none.
        if (attempt.lastFrame !== null && attempt.lastAligned && aligned) this._held += Math.max(0, Math.min(0.1, gap / 1000));
        else this._held = 0;
        attempt.lastFrame = now; attempt.lastAligned = aligned; attempt.frames += 1;
        this._heading = direction; this._lowAccuracy = isLowAccuracy(res.accuracy);
        this._armWatchdog(attempt, FRAME_GAP_MS);
      };
      this._cb = cb; this._listening = true;
      this._armWatchdog(attempt, FIRST_FRAME_MS);
      try {
        wx.onCompassChange(cb);
        if (!this._current(attempt)) return;
        wx.startCompass({
          success: () => { attempt.succeeded = true; accept(); },
          fail: () => this._failed(attempt),
        });
        attempt.startReturned = true;
      } catch (error) { this._failed(attempt); return; }
      accept();
    },

    onRun(e) {
      const attempt = this._eventAttempt(e);
      if (!attempt || attempt.phase !== 'preparing' || this._sent || this.data.done) return;
      attempt.phase = 'run';
      this.setData({ phase: 'run', aligned: false, noData: false, holdLeft: this._holdTotal().toFixed(1),
        hasHeading: false, roseDeg: 0, holdPct: 0, turnHint: '', lowAccuracy: false });
      this._timer = setInterval(() => { if (this._current(attempt)) this._tick(); }, TICK_MS);
    },
    _tolerance() {
      const t = this.data.tolerance;
      return typeof t === 'number' && Number.isFinite(t) && t > 0 ? t : 15;
    },

    _holdTotal() {
      const h = this.data.holdSeconds;
      return typeof h === 'number' && Number.isFinite(h) && h > 0 ? h : 1;
    },

    _tick() {
      const attempt = this._attempt;
      if (!this._current(attempt) || this._sent || attempt.phase !== 'run' || this.data.phase !== 'run') return;
      const now = Date.now();
      if (attempt.lastFrame === null) {
        if (now - attempt.startedAt >= FIRST_FRAME_MS) this._failed(attempt);
        return;
      }
      if (!this._fresh(attempt)) { this._failed(attempt); return; }
      const target = this._target;
      const aligned = angleDiff(this._heading, target) <= this._tolerance();
      const needle = continuous(target - this._heading, this._prevNeedle);
      this._prevNeedle = needle;
      const rose = continuous(-this._heading, this._prevRose);
      this._prevRose = rose;

      if (aligned && this._held >= this._holdTotal() && attempt.frames >= 2) { this._finish(); return; }

      this.setData({
        heading: normalize(this._heading),
        needleDeg: needle,
        roseDeg: rose,
        hasHeading: true,
        aligned,
        holdPct: Math.min(99, Math.floor(Math.max(0, this._held / this._holdTotal()) * 100)),
        turnHint: turnText(signedDelta(target, this._heading)),
        lowAccuracy: this._lowAccuracy,
        holdLeft: Math.max(0, this._holdTotal() - this._held).toFixed(1),
      });
    },

    _finish() {
      const attempt = this._attempt;
      if (!this._fresh(attempt) || attempt.phase !== 'run' || this._sent || attempt.frames < 2
        || this._held < this._holdTotal() || typeof this._heading !== 'number' || !Number.isFinite(this._heading)
        || angleDiff(this._heading, this._target) > this._tolerance()) return;
      const bearing = normalize(this._heading);
      this._stop(); this._stopCompass();
      this._sent = true; attempt.phase = 'reporting';
      this.setData({ phase: 'done', holdPct: 100 });
      motion.haptic({ reducedMotion: this.data.rm, type: 'medium' });
      const stage = attempt.stage;
      if (stage) stage.win('对准了，保持了 ' + this._holdTotal() + ' 秒');
      attempt.phase = 'done';
      if (this._current(attempt)) this.triggerEvent('submit', withAttempt({ bearing }, { attemptId: this.data.attemptId }));
    },

    onVerdict(e) {
      const attempt = this._eventAttempt(e);
      if (attempt && attempt.phase === 'reporting') this.triggerEvent('verdict', e.detail);
    },
    onClose(e) {
      if (e && (!e.currentTarget || !e.currentTarget.dataset
        || String(e.currentTarget.dataset.stageKey) !== String(this.data.stageKeys[0]))) return;
      this._closed = true; this._abort(); this.triggerEvent('close');
    },
  },
});
