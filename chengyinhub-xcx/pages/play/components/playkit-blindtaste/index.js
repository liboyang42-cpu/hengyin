const { stepIcons, stepsA11yLabel } = require('../../utils/playkit-steps.js');
const motion = require('../../../../utils/motion.js');
const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const cyToast = require('../../../../utils/toast.js');
// cy-playkit-blindtaste · 闭眼盲品(2026-10-23 主人定稿:城瘾 D20 与盲品试玩原型)
//
// 三步:领小样 → 按住闭眼 3 秒 → 睁眼作答。闭眼是这个玩法的规则本身,所以做成一个
// 要按住的动作:眼皮合上、屏幕全黑,中途松手就算偷看,要重新闭。
// 点中哪一项就是作答。这里只发 answer,不本地判对错 —— 对错是服务端的事,
// 前端自己判就等于把答案发到客户端。

const TASTE_SECONDS = 3;

/** 已经作答过(回执回填了 selectedKey / locked)就直接停在作答那一步 */
function startStage(selectedKey, locked) { return selectedKey || locked ? 2 : 0; }

Component({
  behaviors: [reducedMotionBehavior],
  data: {
    stepIcons: stepIcons('blindtaste'),
    stage: 0,           // 0 领小样 · 1 闭眼尝 · 2 作答
    eyes: 'open',       // open | closed | peek
    count: TASTE_SECONDS,
    breathIn: false,
    opened: false,
    lifted: -1,
  },
  properties: {
    show:     { type: Boolean, value: false },
    eyebrow:  { type: String,  value: '闭眼味觉师 · 盲品' },
    title:    { type: String,  value: '' },
    // 整句步骤说明只给读屏(图形步骤行的 aria-label)。默认值兜底,服务端没配也听得到。
    steps:    { type: String,  value: stepsA11yLabel('blindtaste') },
    // [{ key: 'A', label: '桂花乌龙' }, ...]
    options:  { type: Array,   value: [] },
    hint:     { type: String,  value: '' },
    xpLabel:  { type: String,  value: '' },
    // 已作答的项;由页面在服务端回执后回填,组件不自己记(刷新后不能装作答过)
    selectedKey: { type: String, value: '' },
    locked:   { type: Boolean, value: false },
  },
  observers: {
    'show, selectedKey, locked': function (show, selectedKey, locked) {
      if (!show) { this._stopTaste(); return; }
      const stage = startStage(selectedKey, locked);
      if (stage === 2 || this._shownOnce !== true) {
        this._shownOnce = true;
        this.setData({ stage, eyes: 'open', opened: stage === 2 });
        if (stage === 0) this._liftCups();
      }
    },
  },
  lifetimes: { detached() { this._stopTaste(); clearTimeout(this._liftT); } },
  pageLifetimes: { hide() { if (this.data.eyes === 'closed') this.onEyesOpen(); } },
  methods: {
    _startStage: startStage,   // 纯算法出口,供单测

    /** 杯子挨个抬一下 —— 店员把小样摆上桌 */
    _liftCups() {
      if (this.data.reducedMotion) return;
      const n = (this.data.options || []).length;
      let i = 0;
      const step = () => {
        this.setData({ lifted: i < n ? i : -1 });
        if (i++ < n) this._liftT = setTimeout(step, 220);
      };
      this._liftT = setTimeout(step, 300);
    },
    onGot() {
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ stage: 1 });
    },
    onEyesClose() {
      if (this.data.stage !== 1 || this.data.eyes === 'closed') return;
      this._closedAt = Date.now();
      this._tasted = false;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ eyes: 'closed', count: TASTE_SECONDS, breathIn: true });
      this._tick = setInterval(() => {
        const left = Math.max(0, TASTE_SECONDS - Math.floor((Date.now() - this._closedAt) / 1000));
        if (left === 0 && !this._tasted) { this._tasted = true; motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' }); }
        // 呼吸圈一秒一涨一缩:用 transition 来回切,不另起关键帧
        this.setData({ count: left, breathIn: !this.data.breathIn });
      }, 1000);
    },
    _stopTaste() { if (this._tick) clearInterval(this._tick); this._tick = null; },
    onEyesOpen() {
      if (this.data.eyes !== 'closed') return;
      this._stopTaste();
      this._released = Date.now();
      const tasted = this._tasted || Date.now() - this._closedAt >= TASTE_SECONDS * 1000;
      if (!tasted) {
        // 偷看:眼皮弹开一条缝又回去,按钮还在,重新闭
        this.setData({ eyes: 'peek' });
        setTimeout(() => { if (this.data.eyes === 'peek') this.setData({ eyes: 'open' }); }, 380);
        cyToast('偷看了？再闭一次眼');
        return;
      }
      this.setData({ eyes: 'open', stage: 2, opened: false });
      setTimeout(() => this.setData({ opened: true }), 30);
    },
    // 读屏 / 轻点:不按住也能走,点一下就闭眼,再点一下睁眼
    onEyesTap() {
      if (this._released && Date.now() - this._released < 500) return;
      if (this.data.eyes === 'closed') this.onEyesOpen(); else this.onEyesClose();
    },
    onPick(e) {
      if (this.data.locked || this.data.selectedKey || this.data.stage !== 2) return;
      const key = e.currentTarget.dataset.key;
      if (!key) return;
      // 作答不可撤销(选中即提交,没有提交按钮)→ medium。
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      this.triggerEvent('answer', { key });
    },
    onClose() { this.triggerEvent('close'); },
  },
});
