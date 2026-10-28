// cy-playkit-reaction · 变色就点(原型真源:模板编辑页 v2 · playkit react)
//
// 这个玩法**没有** 3-2-1:它测的就是突然性,先数三下等于预告。
// 原型里 countIn 挂在限时条里,而变色就点没有限时开关 —— 天然不数,不是漏了。
//
// ★ 抢跑要单独判。不判的话所有人都会在变色前狂点,这游戏就没了 ——
// 能靠乱点赢的规则等于没有规则。
//
// ★ 等待时长随机(1.4–4.2 秒)。这里只显示本机从变绿到点击的时间差，
// 按既有协议提交，最终结果仍由服务端处理。

const WAIT_MIN_MS = 1400;
const WAIT_MAX_MS = 4200;
const MIN_HUMAN_MS = 120;      // 与后端 AdvancedGameRuntimeServiceImpl 同值

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');
// C1:有服务端为这一局新发的挑战身份就随成绩交(与 stopwatch/shout 同一口径)
const { withAttempt, freshAttempt } = require('../../utils/sensor-contract.js');

/** 随机等待时长。固定间隔的话第二轮就能背下来,测的就不是反应了。 */
function pickWait(rand) {
  const r = typeof rand === 'function' ? rand() : Math.random();
  return Math.round(WAIT_MIN_MS + r * (WAIT_MAX_MS - WAIT_MIN_MS));
}

/** 配置轮数中取最快。空数组给 0,由调用方判「还没有成绩」。 */
function bestOf(times) {
  const list = (times || []).filter((n) => n > 0);
  return list.length ? Math.min.apply(null, list) : 0;
}

/** 达标 = 最快那次低于商家设的 goalMs,且没有一轮抢跑。 */
function passed(times, goalMs) {
  const best = bestOf(times);
  return best > 0 && best <= goalMs;
}

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    show: { type: Boolean, value: false },
    kicker: { type: String, value: '变绿就点' },
    rounds: { type: Number, value: 3 },
    goalMs: { type: Number, value: 320 },
    // C1:服务端发的挑战身份(0 = 没有,照旧只交成绩)
    attemptId: { type: Number, value: 0 },
  },
  data: {
    phase: 'idle',        // idle | wait | go | early | done
    big: '—',
    unit: '',
    sub: '点一下开始',
  },
  observers: {
    'show, rounds': function (show, rounds) {
      if (!show) { this._abortRound(); this._closed = true; this._configSignature = ''; return; }
      // 开表后服务端回流会把同值属性再赋一遍:配置没变就不重置台面(与 typein 的 _roundSignature 同口径)
      const signature = String(rounds);
      if (signature === this._configSignature && !this._closed) return;
      this._configSignature = signature;
      this._abortRound();
      this._closed = false;
      this.setData({ phase: 'idle', big: '—', unit: '', sub: '点一下开始' });
      // 变色就点没有限时或倒数。重开仅重置台面，不提交旧测量。
      const stage = this.selectComponent('#cy-play-stage');
      if (stage && !this._hidden && !this._detached) stage.begin();
    },
  },
  lifetimes: { detached() { this._detached = true; this._abortRound(); } },
  pageLifetimes: {
    hide() { this._hidden = true; this._abortRound(); },
    show() { this._hidden = false; },
  },
  methods: {
    _pickWait: pickWait,        // 纯算法出口,供单测
    _bestOf: bestOf,
    _passed: passed,
    _minHumanMs: () => MIN_HUMAN_MS,

    _stop() { if (this._timer) { clearTimeout(this._timer); this._timer = null; } },

    _active(epoch) {
      return !this._hidden && !this._closed && !this._detached && this.data.show
        && (epoch === undefined || epoch === this._roundEpoch);
    },
    _abortRound() {
      this._roundEpoch = (this._roundEpoch || 0) + 1;
      this._stop();
      this._times = []; this._armed = false; this._goAt = null; this._reporting = false;
      const done = this.data.phase === 'done';
      this.setData({
        phase: done ? 'done' : 'idle', big: '—', unit: '',
        sub: done ? '本次测量已结束' : '刚才已中断，点一下重新测量',
      });
    },

    /** 进入等待:红屏,随机若干毫秒后转绿。 */
    _arm() {
      if (!this._active()) return;
      const epoch = this._roundEpoch = (this._roundEpoch || 0) + 1;
      this._goAt = null;
      this.setData({ phase: 'wait', big: '等', unit: '', sub: '变绿再点 —— 早点了算抢跑' });
      /* 开表要报给服务端:成绩按服务器时间复核,不先开表提交会被判成「还没开始」。
         第一轮报一次就够,服务端记的是这一局的起点。 */
      // 开表那一刻手里的号是上一局的;只有这次开表后服务端新发的号才随成绩交
      if (!this._armed) { this._armed = true; this._attemptAtRun = this.data.attemptId; this.triggerEvent('start'); }
      if (!this._active(epoch)) return;
      this._stop();
      this._timer = setTimeout(() => {
        if (!this._active(epoch) || this.data.phase !== 'wait') return;
        this._timer = null;
        this._goAt = Date.now();
        this.setData({ phase: 'go', big: '点!', unit: '', sub: '' });
      }, pickWait());
    },

    onTap() {
      if (!this._active()) return;
      const phase = this.data.phase;

      if (phase === 'idle' || phase === 'early') { this._arm(); return; }

      if (phase === 'wait') {
        // 抢跑:这一轮作废,但不判整局输 —— 抢跑是操作失误,不是答错
        this._roundEpoch = (this._roundEpoch || 0) + 1;
        this._stop(); this._goAt = null;
        motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
        this.setData({ phase: 'early', big: '抢跑', unit: '', sub: '绿了才能点。点一下重来' });
        return;
      }

      if (phase !== 'go') return;

      const ms = Date.now() - this._goAt;
      this._goAt = null;
      // 低于人类反应下限的不收:那是提前按住不放蹭出来的,后端也会判抢跑
      if (ms < MIN_HUMAN_MS) {
        this.setData({ phase: 'early', big: '抢跑', unit: '', sub: '这个快得不像人手。点一下重来' });
        return;
      }

      this._times.push(ms);
      const doneCount = this._times.length;
      // 「做成了」落在每一轮成绩落定这一刻
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });

      if (doneCount < (this.data.rounds || 3)) {
        this.setData({ phase: 'idle', big: ms + '', unit: '毫秒 · 本机测量',
          sub: doneCount + '/' + (this.data.rounds || 3) + ' 轮 · 点一下进入下一轮' });
        return;
      }

      const epoch = this._roundEpoch;
      const times = this._times.slice();
      const best = bestOf(times);
      const ok = passed(this._times, this.data.goalMs);
      this.setData({ phase: 'done', big: best + '', unit: '毫秒 · 本机测量',
        sub: doneCount + ' 轮里最快的一次' });
      // 判定屏由 stage 统一出:凡是「猜中了」都走同一张,答错不算通关
      const stage = this.selectComponent('#cy-play-stage');
      this._reporting = true;
      if (stage) { if (ok) stage.win('最快 ' + best + ' 毫秒（本机测量）'); else stage.fail(false); }
      this._reporting = false;
      // 成绩报服务端,由它拿真实墙钟复核 —— 本地这几个数只是显示
      if (this._active(epoch)) this.triggerEvent('submit', withAttempt({ times: times, bestMs: best, passed: ok },
        { attemptId: freshAttempt(this.data.attemptId, this._attemptAtRun) }));
    },

    onVerdict(e) { if (this._active() && this._reporting) this.triggerEvent('verdict', e.detail); },
    onClose() { this._closed = true; this._abortRound(); this.triggerEvent('close'); },
  },
});
