// cy-playkit-quiethold · 安静挑战(原型真源:模板编辑页 v2 · playkit quiet)
//
// ★ 过线就判输,不是清零重来。「一声都不许出」这条规则要立得住,就不能给第二次。
//
// ⚠️ 录音**不上传、不落盘,用完即弃**:这一屏只需要每帧的振幅,不需要内容。
// ⚠️ 这是这批里最贵的一个玩法 —— 麦克风授权弹窗会劝退一部分人。
//    适合本来就安静的场所(书店、茶室),不适合街边。

const TICK_MS = 100;
const FRAME_MS = 100;

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');
// 电平算法与 shout 共用一份(现场感契约 §5.1 抽出),这里只留用法。
const audioLevel = require('../../utils/play-audio-level.js');
const { baseline, thresholds, bandOf, peakOf } = audioLevel;

/** 相对阈值 → 一句人话:相对满幅的百分比,明说不是分贝 */
function levelText(level) {
  return '本机阈值 ' + Math.round((level || 0) * 100) + '%（相对满幅，不是分贝）';
}

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    show: { type: Boolean, value: false },
    kicker: { type: String, value: '别出声' },
    sub: { type: String, value: '' },
    seconds: { type: Number, value: 15 },
    // C1:证据说明 / 前后台规则 / 校准说明,分发器从 kit 喂
    evidenceNote: { type: String, value: '' },
    bgRule: { type: String, value: '' },
    calibrationNote: { type: String, value: '' },
  },
  data: {
    left: '15.0',
    band: 'ok',
    running: false,
    // F0337:本机相对阈值与校准状态。阈值是相对满幅的振幅比例,不是分贝
    calibrated: false,
    thresholdText: '',
    // 本机记到的安静时长(heldMs)≠ 可信完成:那要服务器复核,奖励上限也由服务器定
    heldText: '',
    micDenied: false,
  },
  observers: {
    'show, seconds': function (show, seconds) {
      this._stop();
      if (!show) { this._stopMic(); return; }
      this.setData({ left: (seconds > 0 ? seconds : 0).toFixed(1), band: 'ok', running: false,
        calibrated: false, thresholdText: '', heldText: '', micDenied: false });
    },
  },
  lifetimes: { detached() { this._stop(); this._stopMic(); } },
  // 切后台就停:回来那一帧的间隔可能有好几秒,不停等于「熄屏几秒」白赚几秒安静,
  // 而那几秒根本没在听
  pageLifetimes: { hide() { this._abort(); } },
  methods: {
    _baseline: baseline,        // 纯算法出口,供单测
    _thresholds: thresholds,
    _bandOf: bandOf,
    _peakOf: peakOf,

    _stop() { if (this._timer) { clearInterval(this._timer); this._timer = null; } },
    _stopMic() {
      if (!this._rec) return;
      try { this._rec.stop(); } catch (e) { /* 已经停了 */ }
      this._rec = null;
    },
    _abort() {
      if (!this.data.running) return;
      this._stop(); this._stopMic();
      // 切走那几秒没在听:本机记的时长作废,校准也作废(回来可能换了地方)
      this.setData({ running: false, band: 'ok', calibrated: false, thresholdText: '', heldText: '' });
    },

    onStart() {
      if (this.data.running) return;
      this.setData({ micDenied: false, heldText: '' });
      this._samples = [];
      this._peak = 0;
      this._startMic();
      // 校准屏由 stage 出:量 2 秒环境音,期间每一拍回调 onSample
      const stage = this.selectComponent('#cy-play-stage');
      if (stage) stage.begin();
    },

    _startMic() {
      const rec = wx.getRecorderManager();
      this._rec = rec;
      rec.onFrameRecorded((res) => { this._peak = peakOf(res.frameBuffer); });
      // 拿不到麦克风就明说,不假装在听 —— 让人以为在真听是更糟的事
      rec.onError(() => {
        this._stop(); this._stopMic();
        const stage = this.selectComponent('#cy-play-stage');
        if (stage && stage._stopAll) stage._stopAll();
        // 拒绝麦克风:给授权替代(去设置打开 / 跳过这一项不记成绩),不假装在听
        this.setData({ running: false, band: 'ok', micDenied: true, calibrated: false, thresholdText: '' });
        this.triggerEvent('micdenied');
      });
      rec.start({ duration: 600000, sampleRate: 16000, numberOfChannels: 1,
                  format: 'PCM', frameSize: 1 });
    },

    /** 校准期间每一拍采一个峰值 */
    onSample() { this._samples.push(this._peak || 0); },

    onCalibrated() {
      const t = thresholds(baseline(this._samples));
      this._mid = t.mid;
      this._hot = t.hot;
      this.setData({ calibrated: true, thresholdText: levelText(t.hot) });
    },

    /** 授权替代①:去系统设置打开麦克风,回来再点开始 */
    onOpenMicSetting() {
      if (wx.openSetting) wx.openSetting({ fail() {} });
    },
    /** 授权替代②:跳过这一项 —— 不记成绩、不发奖,只是不卡在这一屏 */
    onSkipMic() { this.onClose(); },

    /** stage 校准完(这玩法没有限时开关,所以不数 3-2-1)之后真正开跑 */
    onRun() {
      const total = this.data.seconds > 0 ? this.data.seconds : 15;
      this._held = 0;
      this._last = Date.now();
      this.setData({ running: true, left: total.toFixed(1), band: 'ok' });
      // 开表要报给服务端:成绩按服务器时间复核,不先开表提交会被判成「还没开始」
      this.triggerEvent('start');
      this._stop();
      this._timer = setInterval(() => {
        const now = Date.now();
        // ★ 一帧最多只记 0.1 秒:切后台时定时器会被限频,回来那一帧的间隔可能有好几秒 ——
        // 不夹的话等于熄屏几秒就白得几秒安静,而那几秒根本没在听
        const dt = Math.min(0.1, (now - this._last) / 1000);
        this._last = now;

        const band = bandOf(this._peak || 0, this._mid, this._hot);
        if (band !== this.data.band) this.setData({ band });
        if (band === 'hot') { this._finish(false); return; }

        this._held += dt;
        const left = Math.max(0, total - this._held);
        this.setData({ left: left.toFixed(1) });
        if (this._held >= total) this._finish(true);
      }, TICK_MS);
    },

    _finish(won) {
      this._stop(); this._stopMic();
      this.setData({ running: false, heldText: '本机记到 ' + (this._held || 0).toFixed(1) + ' 秒，以最终复核为准' });
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      const stage = this.selectComponent('#cy-play-stage');
      if (stage) {
        if (won) stage.win('整整 ' + this.data.seconds + ' 秒,一声没出');
        else stage.fail(false);      // 出声不是超时,那两个字是「失败」
      }
      this.triggerEvent('submit', { heldSeconds: Math.round(this._held || 0), passed: !!won });
    },

    onVerdict(e) { this.triggerEvent('verdict', e.detail); },
    onClose() { this._stop(); this._stopMic(); this.triggerEvent('close'); },
  },
});
