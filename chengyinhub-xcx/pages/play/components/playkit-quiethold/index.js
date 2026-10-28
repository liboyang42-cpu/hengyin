// 安静挑战：只比较本机相对 PCM 振幅；录音不上传、不落盘。
const TICK_MS = 100;
const FIRST_FRAME_MS = 3000;
const FRAME_GAP_MS = 500;
const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');
const cyToast = require('../../../../utils/toast.js');
const { baseline, thresholds, bandOf, peakOf } = require('../../utils/play-audio-level.js');
// C1:挑战身份与奖励上限只搬服务端的;阈值说的是相对振幅,不是分贝
const { withAttempt, freshAttempt, rewardNote, relativeThresholdText } = require('../../utils/sensor-contract.js');

function pcmPeak(buffer) {
  try {
    const bytes = Object.getOwnPropertyDescriptor(ArrayBuffer.prototype, 'byteLength').get.call(buffer);
    if (!bytes || bytes % 2) return null;
    const value = peakOf(buffer);
    return typeof value === 'number' && Number.isFinite(value) ? value : null;
  } catch (error) { return null; }
}
function denied(error) {
  const message = error && error.errMsg;
  return typeof message === 'string' && /^(?:operateRecorder|startRecord|startRecorder|recorderManager\.start):fail (?:auth deny|auth denied|authorize denied|permission denied)$/i.test(message);
}
Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    show: { type: Boolean, value: false },
    kicker: { type: String, value: '别出声' },
    sub: { type: String, value: '' },
    seconds: { type: Number, value: 15 },
    // C1:服务端开表发的挑战身份(0 = 没有)与奖励上限(空 = 服务端没说,什么都不承诺)
    attemptId: { type: Number, value: 0 },
    rewardCeiling: { type: String, value: '' },
  },
  data: { left: '15.0', band: 'ok', running: false, preparing: false, stageKeys: [0],
    // calibration: '' 未校准 / 'calibrating' 正在听环境音 / 'calibrated' 已校准(切走、重开即作废)
    calibration: '', thresholdText: '', rewardNote: '' },
  observers: {
    rewardCeiling(value) { this.setData({ rewardNote: rewardNote(value) }); },
    'show, seconds': function (show, seconds) {
      if (!show) { this._abort(); this._configSignature = ''; return; }
      // 开表后服务端回流会把同值属性再赋一遍:配置没变就不重置台面(与 typein 的 _roundSignature 同口径)
      const signature = String(seconds);
      if (signature === this._configSignature && !this._closed) return;
      this._configSignature = signature;
      this._abort();
      this._closed = false;
    },
  },
  lifetimes: { detached() { this._detached = true; this._abort(); } },
  pageLifetimes: {
    hide() { this._hidden = true; this._abort(); },
    show() { this._hidden = false; },
  },
  methods: {
    _baseline: baseline,
    _thresholds: thresholds,
    _bandOf: bandOf,
    _peakOf: peakOf,
    _total() {
      const value = this.data.seconds;
      return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 15;
    },
    _current(attempt) {
      return !!attempt && this._attempt === attempt && this.data.show
        && !this._hidden && !this._closed && !this._detached;
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
    _stopMic(attempt) {
      const owner = attempt || this._attempt;
      if (!owner || !owner.rec) return;
      const rec = owner.rec;
      owner.rec = null;
      if (this._rec === rec) this._rec = null;
      // Remove exact owned callbacks before stop() can synchronously emit onStop/error.
      for (const name of ['Start', 'Stop', 'Error', 'FrameRecorded']) {
        try { rec['off' + name](owner.handlers[name]); } catch (error) { /* invalidated callback remains inert */ }
      }
      try { rec.stop(); } catch (error) { /* already stopped */ }
    },
    _abort(after) {
      const attempt = this._attempt;
      const stage = attempt ? attempt.stage : this.selectComponent('#cy-play-stage');
      this._attempt = null;
      this._stop(); this._stopMic(attempt);
      this._samples = []; this._peak = null; this._held = 0;
      if (stage) stage._stopAll();
      this._retiredStage = stage;
      const key = this.data.stageKeys[0] + 1;
      this.setData({ running: false, preparing: false, band: 'ok', left: this._total().toFixed(1),
        calibration: '', thresholdText: '',
        stageKeys: [key] }, () => { if (after && this.data.stageKeys[0] === key && !this._detached) after(); });
    },
    _micFailure(attempt, error) {
      if (!this._current(attempt) || attempt.phase === 'finished') return;
      this._abort();
      const key = this.data.stageKeys[0];
      const canReport = () => !this._attempt && this.data.stageKeys[0] === key && this.data.show
        && !this._hidden && !this._closed && !this._detached;
      if (denied(error)) { if (canReport()) this.triggerEvent('micdenied'); return; }
      cyToast('声音读取失败，请重试或退出');
      if (typeof wx.getSetting === 'function') {
        try { wx.getSetting({
          success: (res) => {
            if (canReport() && res && res.authSetting && res.authSetting['scope.record'] === false) this.triggerEvent('micdenied');
          },
          fail() { /* failure is already visible; unknown permission is not denial */ },
        }); } catch (settingError) { /* permission remains unknown; failure is already visible */ }
      }
    },
    _watch(attempt, ms) {
      if (this._watchdog) clearTimeout(this._watchdog);
      this._watchdog = setTimeout(() => this._micFailure(attempt), ms);
    },
    _fresh(attempt) {
      return attempt.lastFrame !== null && Number.isFinite(attempt.lastFrame)
        && Date.now() - attempt.lastFrame < FRAME_GAP_MS;
    },
    onStart() {
      if (!this.data.show || this._hidden || this._closed || this._detached) return;
      if (this._attempt && this._attempt.phase === 'finished') { this._abort(() => this.onStart()); return; }
      if (this._attempt) return;
      const stage = this.selectComponent('#cy-play-stage');
      if (!stage || stage === this._retiredStage) return;
      const attempt = { stage, key: this.data.stageKeys[0], phase: 'starting', lastFrame: null,
        runFrame: null, serial: 0, sampled: 0, startReturned: false, started: false };
      this._attempt = attempt;
      this._samples = []; this._peak = null; this._held = 0;
      this.setData({ preparing: true, running: false, band: 'ok', left: this._total().toFixed(1) });
      this._startMic(attempt);
    },
    _startMic(attempt) {
      try {
        if (typeof wx.getRecorderManager !== 'function') throw Error('unavailable');
        const rec = wx.getRecorderManager();
        if (!rec || typeof rec.start !== 'function' || typeof rec.stop !== 'function'
          || ['Start', 'Stop', 'Error', 'FrameRecorded'].some(name => typeof rec['on' + name] !== 'function' || typeof rec['off' + name] !== 'function')) throw Error('unavailable');
        attempt.rec = rec; this._rec = rec;
        attempt.handlers = {
          Start: () => {
            if (!this._current(attempt) || attempt.phase !== 'starting') return;
            attempt.started = true;
            this._beginStage(attempt);
          },
          Stop: () => {
            // Singleton recorder may broadcast a queued previous stop to newly registered listeners.
            // Stop does not certify any samples; the frame watchdog detects a current interruption.
          },
          Error: error => this._micFailure(attempt, error),
          FrameRecorded: res => this._onFrame(attempt, res),
        };
        this._watch(attempt, FIRST_FRAME_MS);
        for (const name of ['Start', 'Stop', 'Error', 'FrameRecorded']) {
          rec['on' + name](attempt.handlers[name]);
          if (!this._current(attempt)) return;
        }
        rec.start({ duration: 600000, sampleRate: 16000, numberOfChannels: 1, format: 'PCM', frameSize: 1 });
        if (!this._current(attempt)) return;
        attempt.startReturned = true;
        this._beginStage(attempt);
      } catch (error) { this._micFailure(attempt, error); }
    },
    _beginStage(attempt) {
      if (!this._current(attempt) || attempt.phase !== 'starting' || !attempt.started || !attempt.startReturned) return;
      attempt.phase = 'calibrating';
      this.setData({ calibration: 'calibrating', thresholdText: '' });
      this._watch(attempt, attempt.lastFrame === null ? FIRST_FRAME_MS : FRAME_GAP_MS);
      attempt.stage.begin();
    },
    _onFrame(attempt, res) {
      if (!this._current(attempt) || !['calibrating', 'ready', 'running'].includes(attempt.phase)) return;
      const peak = pcmPeak(res && res.frameBuffer);
      if (peak === null) {
        if (attempt.phase === 'running') {
          attempt.runFrame = null; this._held = 0;
          this.setData({ left: this._total().toFixed(1) });
        }
        return;
      }
      const now = Date.now();
      if (attempt.lastFrame !== null && now - attempt.lastFrame >= FRAME_GAP_MS) { this._micFailure(attempt); return; }
      attempt.lastFrame = now; attempt.serial += 1; this._peak = peak;
      this._watch(attempt, FRAME_GAP_MS);
      if (attempt.phase !== 'running') return;
      const previous = attempt.runFrame;
      attempt.runFrame = now;
      const band = bandOf(peak, this._mid, this._hot);
      this.setData({ band });
      if (band === 'hot') { this._finish(false); return; }
      // Only consecutive real PCM frames establish a measured interval; timers never add time.
      const dt = previous === null ? 0 : Math.max(0, Math.min(TICK_MS / 1000, (now - previous) / 1000));
      this._held += dt;
      const left = Math.max(0, this._total() - this._held);
      this.setData({ left: left.toFixed(1) });
      if (this._held >= this._total()) this._finish(true);
    },
    onSample(e) {
      const attempt = this._eventAttempt(e);
      if (!attempt || attempt.phase !== 'calibrating' || !this._fresh(attempt) || attempt.serial === attempt.sampled) return;
      this._samples.push(this._peak); attempt.sampled = attempt.serial;
    },
    onCalibrated(e) {
      const attempt = this._eventAttempt(e);
      if (!attempt || attempt.phase !== 'calibrating') return;
      if (!this._fresh(attempt)) { this._micFailure(attempt); return; }
      if (!this._samples.length) this._samples.push(this._peak);
      const t = thresholds(baseline(this._samples));
      this._mid = t.mid; this._hot = t.hot;
      attempt.phase = 'ready';
      this.setData({ calibration: 'calibrated', thresholdText: relativeThresholdText(t.hot) });
    },
    onRun(e) {
      const attempt = this._eventAttempt(e);
      if (!attempt || attempt.phase !== 'ready') return;
      if (!this._fresh(attempt)) { this._micFailure(attempt); return; }
      attempt.phase = 'running'; attempt.runFrame = null;
      // 开局那一刻手里的号是上一次的;只有这一局开表后服务端新发的号才随成绩交
      attempt.attemptAtRun = this.data.attemptId;
      this._held = 0;
      this.setData({ running: true, preparing: false, left: this._total().toFixed(1), band: 'ok' });
      this.triggerEvent('start');
      if (!this._current(attempt)) return;
      this._timer = setInterval(() => {
        if (this._current(attempt) && attempt.phase === 'running' && !this._fresh(attempt)) this._micFailure(attempt);
      }, TICK_MS);
    },
    _finish(won) {
      const attempt = this._attempt;
      if (!this._current(attempt) || attempt.phase !== 'running') return;
      if (!this._fresh(attempt) || attempt.runFrame === null || !Number.isFinite(this._held)
        || this._held < 0 || won && this._held < this._total()) { this._micFailure(attempt); return; }
      attempt.phase = 'finished';
      const heldSeconds = Math.round(this._held);
      this._stop(); this._stopMic(attempt);
      this.setData({ running: false, preparing: false });
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      attempt.reporting = true;
      if (won) attempt.stage.win('本机相对振幅保持了 ' + this._total() + ' 秒'); else attempt.stage.fail(false);
      attempt.reporting = false;
      if (this._current(attempt)) this.triggerEvent('submit', withAttempt({ heldSeconds, passed: !!won },
        { attemptId: freshAttempt(this.data.attemptId, attempt.attemptAtRun) }));
    },
    onVerdict(e) {
      const attempt = this._eventAttempt(e);
      if (attempt && attempt.reporting) this.triggerEvent('verdict', e.detail);
    },
    onClose(e) {
      if (e && (!e.currentTarget || !e.currentTarget.dataset || String(e.currentTarget.dataset.stageKey) !== String(this.data.stageKeys[0]))) return;
      this._closed = true; this._abort(); this.triggerEvent('close');
    },
  },
});
