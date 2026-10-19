// cy-playkit-shout · 喊一嗓子(原型真源:模板编辑页 v2 · playkit shout)
//
// ★ quietHold 的反面:不是「一声都不许出」,是「让这儿的声音够大,而且别断」。
// ★ 判**峰值持续过线**,过一下又掉回去不算 —— 「持续」是这个玩法唯一的难处,
//   也是它好玩的地方:一嗓子吼不满秒数,得一群人一起喊才顶得住。
// ★ 线画在红档(底噪 + 0.06 + 0.09):黄线只代表「有人出声」,
//   市集里风声都过黄线;要当「齐声喊」的线,得是整场都听见的那一档。
// ★ 阈值现场校准,与 quietHold 共用 play-audio-level —— 书店的线拿到球场就是
//   「一开局就赢」,反过来球场的线拿到书店永远触发不了(契约 §4.2 原话)。
// ★ 成绩按服务器时间复核:开表先报 start,提交只交 heldMs。
//
// ⚠️ 录音**不上传、不落盘,用完即弃**:这一屏只需要每帧的振幅,不需要内容。
// ⚠️ 麦克风授权弹窗会劝退一部分人,而且在公共场合喊是社交成本 ——
//   这条要如实写在商家编辑页上,别摆得和别的 kit 一样轻(契约 §4.5)。

const motion = require('../../../../utils/motion.js');
const { readReducedMotion } = require('../../../../utils/motion-preference.js');
const { baseline, thresholds, bandOf, peakOf } = require('../../utils/play-audio-level.js');

const TICK_MS = 100;
const COMFORT = '不用喊到嗓子疼：比环境音明显大就过线，留意身边的人';

/** 相对音量:此刻峰值 / 本机过线阈值,夹到 0–100。100 = 正好过线 */
function relativePct(peak, hot) {
  if (!(hot > 0)) return 0;
  return Math.max(0, Math.min(100, Math.round((peak / hot) * 100)));
}

Component({
  properties: {
    show: { type: Boolean, value: false },
    kicker: { type: String, value: '喊出来' },
    seconds: { type: Number, value: 5 },
    // C1:证据说明 / 前后台规则 / 校准说明,分发器从 kit 喂
    evidenceNote: { type: String, value: '' },
    bgRule: { type: String, value: '' },
    calibrationNote: { type: String, value: '' },
  },
  data: {
    left: '5.0',
    loud: false,        // 此刻过没过线(数字变色的依据)
    running: false,
    // F0338:相对音量(此刻峰值 / 本机过线阈值,夹到 0–100)。不是分贝,也不和别的手机比
    levelPct: 0,
    comfort: COMFORT,
    micDenied: false,
  },
  observers: {
    'show, seconds': function (show, seconds) {
      this._stop();
      if (!show) { this._stopMic(); return; }
      this.setData({ left: (seconds > 0 ? seconds : 0).toFixed(1), loud: false, running: false, levelPct: 0, micDenied: false });
    },
  },
  lifetimes: {
    attached() { this._rm = readReducedMotion(); },
    detached() { this._stop(); this._stopMic(); },
  },
  // 切后台就停:回来那一帧的间隔可能有好几秒,不停等于「闭嘴那几秒」被记成
  // 还在计时 —— 与 quietHold 同一条,方向反过来也一样成立
  pageLifetimes: { hide() { this._abort(); } },
  methods: {
    _relativePct: relativePct,   // 纯算法出口,供单测
    _stop() { if (this._timer) { clearInterval(this._timer); this._timer = null; } },
    _stopMic() {
      if (!this._rec) return;
      try { this._rec.stop(); } catch (e) { /* 已经停了 */ }
      this._rec = null;
    },
    _abort() {
      if (!this.data.running) return;
      this._stop(); this._stopMic();
      this.setData({ running: false, loud: false, levelPct: 0 });
    },

    onStart() {
      if (this.data.running) return;
      this.setData({ micDenied: false });
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
      // 拿不到麦克风就明说,不假装在听 —— 与 quietHold 同一条(契约 §4.2)
      rec.onError(() => {
        this._stop(); this._stopMic();
        const stage = this.selectComponent('#cy-play-stage');
        if (stage && stage._stopAll) stage._stopAll();
        // 拒绝麦克风:给授权替代(去设置打开 / 跳过这一项不记成绩),不假装在听
        this.setData({ running: false, loud: false, levelPct: 0, micDenied: true });
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
    },

    /** 授权替代①:去系统设置打开麦克风,回来再点开始 */
    onOpenMicSetting() {
      if (wx.openSetting) wx.openSetting({ fail() {} });
    },
    /** 授权替代②:跳过这一项 —— 不记成绩、不发奖,只是不卡在这一屏 */
    onSkipMic() { this.onClose(); },

    /** stage 校准完之后真正开跑 */
    onRun() {
      const total = this.data.seconds > 0 ? this.data.seconds : 5;
      this._held = 0;
      this._last = Date.now();
      this.setData({ running: true, left: total.toFixed(1), loud: false });
      // 开表要报给服务端:成绩按服务器时间复核,不先开表提交会被判成「还没开始」
      this.triggerEvent('start');
      this._stop();
      this._timer = setInterval(() => {
        // 判过/中止之后哪怕表还在走也别再动:清表靠 _stop,这一行兜住任何漏网的拍
        // (与 compass._tick 同一条守卫)
        if (!this.data.running) return;
        const now = Date.now();
        // 一帧最多只记 0.1 秒:后台限频时不夹,等于熄屏几秒白赚几秒
        const dt = Math.min(0.1, (now - this._last) / 1000);
        this._last = now;

        const loud = bandOf(this._peak || 0, this._mid, this._hot) === 'hot';
        if (loud !== this.data.loud) this.setData({ loud });
        const levelPct = relativePct(this._peak || 0, this._hot);
        if (levelPct !== this.data.levelPct) this.setData({ levelPct });

        if (loud) this._held += dt;
        else this._held = 0;   // ★ 掉线清零:「持续 N 秒」不许攒着喊

        const left = Math.max(0, total - this._held);
        this.setData({ left: left.toFixed(1) });
        if (this._held >= total) this._finish();
      }, TICK_MS);
    },

    _finish() {
      this._stop(); this._stopMic();
      this.setData({ running: false });
      motion.haptic({ reducedMotion: this._rm, type: 'medium' });
      const stage = this.selectComponent('#cy-play-stage');
      if (stage) stage.win('整整 ' + this.data.seconds + ' 秒，声音没掉下去');
      this.triggerEvent('submit', { heldMs: Math.round(this._held * 1000) });
    },

    onVerdict(e) { this.triggerEvent('verdict', e.detail); },
    onClose() { this._stop(); this._stopMic(); this.triggerEvent('close'); },
  },
});
