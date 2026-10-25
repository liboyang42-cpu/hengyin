// cy-playkit-qa · 三种问答共用一屏(原型真源:模板编辑页 v2 · playkit qa)
//
// mode: type 打字 / pick 选项 / shot 拍照。三种的差别只在中间那一块,题面、附件、
// 反馈、限时、次数、判定屏全是一样的 —— 铺成三个组件的话,同一处改动要改三遍。
//
// 三条规则不是随手定的:
//   · **答错清空输入框**。留着错答案,人得先自己删一遍才能再试;
//   · **次数按「选项数 - 1」封顶**。三个选项给三次错等于白给 —— 错满两次之后
//     剩下的那个必然是答案,第三次不是机会是走过场;
//   · **答错不算通关**。次数用完就是失败,不给「差不多也算」。
//
// ★ 正确答案永远不下发。这里收到的 options 里没有对错位,判定要么由服务端回,
// 要么(打字题)由服务端比对 —— 组件只负责报玩家输入了什么。

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');
const cyToast = require('../../../../utils/toast.js');

const KEYS = ['A', 'B', 'C', 'D'];
/* 反馈上面那行小字。原型只有这两个词 —— 「答错了」这种判决式说法在这一格里读着太重,
   而人还有下一次机会。 */
const HEAD_OK = '答对了';
const HEAD_NO = '再想想';
/* 打字题答错的固定说法(原型写死这一句)。服务端给了话就用服务端的 */
const RETRY_TEXT = '不对。把它清掉，再试一次。';
/* 一进来题目占满一屏,一秒后选项才淡进来 */
const INTRO_MS = 1000;

/** 打字题的比对:忽略大小写与首尾空格,多个答案用、分隔,命中任一即过。
 *  ⚠️ 真判定在服务端,这里只是本地即时反馈;两边规则必须一致,所以抽成纯函数好对齐。 */
function matchesAnswer(input, answerText) {
  const got = String(input || '').trim().toLowerCase();
  if (!got) return false;
  return String(answerText || '')
    .split(/[、,,]/)
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean)
    .some((a) => a === got);
}

/** 次数封顶:选项题最多只能错「选项数 - 1」次。0 表示不限。 */
function cappedTries(maxTries, optionCount) {
  const n = maxTries > 0 ? maxTries : 0;
  if (!n || !(optionCount > 1)) return n;
  return Math.min(n, optionCount - 1);
}

const buildOptions = (list) => (list || []).map((o, i) => ({
  k: i, key: KEYS[i] || String(i + 1),
  // 服务端给的那一份 id 要带着走:判定按 id 认,下标在它那边没有意义
  id: o.id || '',
  t: o.t || ('选项 ' + (KEYS[i] || i + 1)),
  state: '',
}));

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    show: { type: Boolean, value: false },
    mode: { type: String, value: 'type' },        // type | pick | shot
    sessionId: { type: String, value: '' },      // Original runtime identity; never a client-generated business ID.
    nodeId: { type: String, value: '' },
    theme: { type: String, value: 'dark' },       // dark | light
    title: { type: String, value: '' },
    imageUrl: { type: String, value: '' },
    audioUrl: { type: String, value: '' },
    lead: { type: String, value: '' },
    options: { type: Array, value: [] },
    shotLead: { type: String, value: '' },
    shotNote: { type: String, value: '' },
    limitSeconds: { type: Number, value: 0 },
    maxTries: { type: Number, value: 0 },
    reveal: { type: Boolean, value: false },      // 答错要不要亮出正确答案
    /* 服务端揭晓的那个选项 id。★ 它到齐了这一条链路才成立:
       服务端只在「商家开了答错亮答案 + 次数用完」时才给(见 AdvancedGameRuntimeServiceImpl
       的 submitQa),给了就意味着可以亮。此前 answerIndex 在这个文件里被读、
       却从来没有人写过 —— 恒 undefined,于是「亮出正确答案」一次也没亮过。 */
    answerId: { type: String, value: '' },
    /* 多选揭晓:服务端在同样的条件下给 answerIds(全部正确选项)。与 answerId 一样,给了才亮。 */
    answerIds: { type: Array, value: [] },
    /* 多选(R3):选项题勾多个再提交。老配置服务端不给这个字段,缺省 false = 单选,
       行为与以前一字不差。 */
    multi: { type: Boolean, value: false },
    judge: { type: Boolean, value: true },
    failureSeq: { type: Number, value: 0 },
    lastOptionId: { type: String, value: '' },
    lastOptionIds: { type: Array, value: [] },
    feedbackImages: { type: Array, value: [] },
    // 服务端已经判过几次(0 = 这一屏刚打开)。多选的回执条靠它区分「刚打开」与「判完了」
    attempts: { type: Number, value: 0 },
    passed: { type: Boolean, value: false },
    finished: { type: Boolean, value: false },
    /* 多选的判后反馈:所选项的 fb 用空格拼起来的那段文字(服务端 lastFeedback)。
       服务端不写 fb 时是空串,那时回执条只给整体结果。 */
    feedbackText: { type: String, value: '' },
  },
  data: {
    /* ⚠️ 派生数据必须换个键名。写回被 observer 监听的那个属性(options)
       会自己触发自己,死循环把运行时打挂 —— 截图跑到第 8 张 DevTools
       直接断连,就是这么来的,而且控制台一条错都没有。 */
    optionList: [],
    typed: '',
    hasImage: false,
    playing: false,
    feedback: '',
    shownFeedbackImages: [],
    submitted: false,
    fbHead: HEAD_NO,
    intro: true,
    burning: false,
    triesCap: 0,
    ctaLabel: '提交',
    ctaDisabled: false,
  },
  observers: {
    failureSeq: function (value) {
      if (!value || this.data.finished || this.data.passed || this.data.judge !== false) return;
      this.setData({ submitted: false, ctaDisabled: !this.data.optionList.some(o => o.state === 'is-on') });
    },
    'show, options, mode, multi, passed, finished, judge, lastOptionId, lastOptionIds, feedbackText, feedbackImages, sessionId, nodeId': function (show, options, mode, multi, passed, finished) {
      if (this._qaDisposed) return;
      this._qaEpoch = (this._qaEpoch || 0) + 1;
      const identity = JSON.stringify([String(this.data.sessionId || ''), String(this.data.nodeId || '')]);
      const fresh = this._qaIdentity !== identity || this._qaVisible !== !!show;
      this._qaIdentity = identity;
      this._qaVisible = !!show;
      if (this._introTimer) { clearTimeout(this._introTimer); this._introTimer = null; }
      if (!show) return;
      const chosen = this.data.lastOptionIds && this.data.lastOptionIds.length
        ? this.data.lastOptionIds : [this.data.lastOptionId];
      /* 正确答案标记与「你的选择」分开记:玩家选了什么来自 lastOptionId(s),
         哪些是对的只来自服务端揭晓的 answerId(s),判断关闭时两者都不会有。 */
      const answers = this.data.judge === false || !this.data.reveal ? []
        : (this.data.answerIds && this.data.answerIds.length ? this.data.answerIds : [this.data.answerId]).filter(Boolean);
      const opts = buildOptions(options).map(o => {
        const isChosen = !!finished && chosen.includes(o.id);
        const isAnswer = !!finished && answers.includes(o.id);
        return Object.assign(o, {
          chosen: isChosen,
          answer: isAnswer,
          state: isAnswer ? 'is-right' : (isChosen ? (answers.length ? 'is-wrong' : 'is-on') : ''),
        });
      });
      const confirmPick = mode === 'pick' && this.data.judge === false;
      const isMultiPick = mode === 'pick' && !!multi && !confirmPick;
      this.setData({
        optionList: opts,
        hasImage: !!this.data.imageUrl,
        typed: '', feedback: (passed || finished || this.data.attempts > 0) ? (this.data.feedbackText || '') : '',
        shownFeedbackImages: (passed || finished || this.data.attempts > 0)
          ? (this.data.feedbackImages || []).slice() : [],
        submitted: false,
        fbHead: passed ? HEAD_OK : HEAD_NO, playing: false, intro: !passed && !finished,
        burning: fresh ? false : !!this.data.burning,
        triesCap: this.data.judge === false || finished ? 0 : cappedTries(this.data.maxTries, opts.length),
        ctaLabel: passed ? '继续' : (confirmPick ? '确认选择' : (mode === 'shot' ? '拍一张' : '提交')),
        // 多选一进来一个都没勾,按钮就得是灰的 —— 亮着一颗按不动的按钮比灰着更糟
        ctaDisabled: !passed && (finished || isMultiPick || confirmPick),
      });
      const stage = this.selectComponent('#cy-play-stage');
      if (stage) {
        if (finished) stage._stopAll();
        else stage.begin();
      }
      // rAF 在这儿不行:页面不渲染时它不走,题会永远停在「只有题干」那一屏
      const context = this._qaContext();
      this._introTimer = setTimeout(() => { if (this._isQaContextCurrent(context)) this.setData({ intro: false }); }, INTRO_MS);
    },
  },
  lifetimes: {
    detached() {
      this._qaDisposed = true; this._qaVisible = false; this._qaEpoch = (this._qaEpoch || 0) + 1;
      if (this._introTimer) { clearTimeout(this._introTimer); this._introTimer = null; }
    },
  },
  methods: {
    _qaContext() {
      // 只读的异步快照，不创建/写回 observer 监听的组件属性。
      return Object.freeze({ epoch: this._qaEpoch || 0,
        identity: JSON.stringify([String(this.data.sessionId || ''), String(this.data.nodeId || '')]) });
    },
    _isQaContextCurrent(context) {
      return !!context && !this._qaDisposed && this._qaVisible !== false
        && context.epoch === (this._qaEpoch || 0)
        && context.identity === JSON.stringify([String(this.data.sessionId || ''), String(this.data.nodeId || '')]);
    },
    /** 服务端给的那个 answerId 排在第几个。没给、或找不着就是 -1(那就什么都不亮)。 */
    _answerIndex() {
      const id = String(this.data.answerId || '');
      if (!id) return -1;
      const list = this.data.optionList || [];
      for (let i = 0; i < list.length; i++) if (String(list[i].id) === id) return i;
      return -1;
    },

    _matchesAnswer: matchesAnswer,      // 纯算法出口,供单测
    _cappedTries: cappedTries,

    onRun() { this.triggerEvent('run'); },
    onInput(e) { this.setData({ typed: e.detail.value }); },
    onToggleAudio() {
      this.setData({ playing: !this.data.playing });
      this.triggerEvent('audio', { playing: this.data.playing });
    },

    onPick(e) {
      if (this.data.finished || this.data.passed || this.data.submitted) return;
      const i = Number(e.currentTarget.dataset.i);
      const picked = this.data.optionList[i];
      if (!picked) return;
      if (this.data.judge === false) {
        this.setData({
          optionList: this.data.optionList.map((o, at) => Object.assign({}, o, { state: at === i ? 'is-on' : '' })),
          ctaDisabled: false,
        });
        return;
      }
      /* 多选:点一下只是勾上/取消,提交走底部那颗按钮 —— 只报一个 id 会被服务端
         当成单选读,而多选要的是 optionIds 全集。
         ⚠️ 这一段必须在 ctaDisabled 早退之前:多选里那个标记只表示「一个都没勾」,
         不是「不许碰选项」—— 挡在前面的话,第一次点选项就被自己锁死了。 */
      if (this.data.mode === 'pick' && this.data.multi) {
        motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
        const optionList = this.data.optionList.map((o, at) => (
          at === i ? Object.assign({}, o, { state: o.state === 'is-on' ? '' : 'is-on' }) : o));
        const any = optionList.some((o) => o.state === 'is-on');
        this.setData({ optionList: optionList, ctaDisabled: !any });
        return;
      }
      if (this.data.ctaDisabled) return;
      // 单选:选了哪条就报哪条,对错由服务端回 —— 正确答案不在客户端
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      this.triggerEvent('submit', { mode: 'pick', index: i, optionId: picked.id || '' });
    },
    /** 多选的提交:报勾中的全部 id。一个都没勾时按钮是灰的,这里再兜一道。 */
    _submitPick() {
      if (this.data.finished || this.data.passed || this.data.submitted) return;
      const ids = this.data.optionList.filter((o) => o.state === 'is-on').map((o) => o.id);
      if (!ids.length) return;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      this.triggerEvent('submit', { mode: 'pick', optionIds: ids });
    },
    onSubmitText() { this.onCta(); },
    onCta() {
      if (this.data.ctaDisabled) return;
      if (this.data.passed) { this.triggerEvent('continue'); return; }
      if (this.data.finished || this.data.submitted) return;
      if (this.data.mode === 'shot') {
        const context = this._qaContext();
        if (!this._isQaContextCurrent(context)) return;
        /* 拍照:交上来的是**图片地址**,不是「我拍过了」。判不了照片内容,
           但得留痕 —— 商家事后想看玩家到底交了什么,这是唯一的证据。 */
        wx.chooseMedia({
          count: 1, mediaType: ['image'], sourceType: ['camera', 'album'], sizeType: ['compressed'],
          success: (res) => {
            if (!this._isQaContextCurrent(context)) return;
            const file = (res && res.tempFiles && res.tempFiles[0]) || {};
            const path = file.tempFilePath || '';
            if (!path) { cyToast('没拿到照片'); return; }
            // 带上真实 size:页面交给共享上传入口按生产 10MB 预检(超限不发网络、给可操作提示)。
            // 拿不到 size 时按未知处理(size undefined),仍由服务端拦截,不伪造。
            this.triggerEvent('shoot', { tempFilePath: path, size: file.size });
          },
          fail: () => {},
        });
        return;
      }
      // 单选:点选项本身就是提交。多选:按钮才是提交,这里接住它
      if (this.data.mode === 'pick') {
        if (this.data.judge === false) {
          const index = this.data.optionList.findIndex(o => o.state === 'is-on');
          if (index < 0) return;
          this.setData({ submitted: true, ctaDisabled: true });
          this.triggerEvent('submit', { mode: 'pick', index, optionId: this.data.optionList[index].id });
        } else if (this.data.multi) this._submitPick();
        return;
      }
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      this.triggerEvent('submit', { mode: 'type', input: this.data.typed });
    },

    /** 页面拿到服务端判定后回调这里。组件不自己判对错。 */
    settle(ok, detail) {
      const d = detail || {};
      const stage = this.selectComponent('#cy-play-stage');
      const multi = this.data.mode === 'pick' && !!this.data.multi;
      if (ok) {
        if (this.data.mode === 'pick') {
          this.setData({
            // 多选按「勾没勾」标,不按下标 —— 勾的顺序与服务端选项顺序不是一回事
            optionList: this.data.optionList.map((o, i) => Object.assign({}, o, multi
              ? (o.state === 'is-on' ? { state: 'is-right' } : { state: 'is-dim' })
              : { state: i === d.index ? 'is-right' : 'is-dim' })),
          });
        }
        this.setData({
          feedback: d.feedback || '', fbHead: HEAD_OK,
          ctaLabel: '继续', ctaDisabled: true,
        });
        if (stage) stage.win(d.feedback || '');
        return;
      }
      const exhausted = stage ? stage.miss() : true;
      if (stage && stage.nudge) stage.nudge();   // 答错整块台面抖一下(原型 .phone.shake)
      const pick = this.data.mode === 'pick';
      /* 答错要不要亮出正确答案由商家决定 —— 亮出来这题就没有第二次了。
         亮的方式是把那一条标成对的,不是另起一行写「正确答案:X」:
         那行字会盖过刚才的反馈,人先看到的是判决,不是解释。 */
      const answerIndex = this._answerIndex();
      const reveal = this.data.judge !== false && pick && this.data.reveal && (multi ? Array.isArray(d.answerIds) : answerIndex >= 0);
      // 字段写死成字面量,不拼 patch:动态 setData 门禁核不出写了哪些顶层字段
      this.setData({
        // 答错清空输入框:留着错答案,人得先自己删一遍才能再试
        typed: '',
        feedback: d.feedback || (this.data.mode === 'type' ? RETRY_TEXT : ''),
        fbHead: HEAD_NO,
        optionList: pick ? this.data.optionList.map((o, i) => {
          if (multi) {
            if (o.state === 'is-on') return Object.assign({}, o, { state: 'is-wrong' });
            if (reveal && d.answerIds.indexOf(o.id) >= 0) return Object.assign({}, o, { state: 'is-right' });
            return o;
          }
          if (i === d.index) return Object.assign({}, o, { state: 'is-wrong' });
          if (reveal && i === answerIndex) return Object.assign({}, o, { state: 'is-right' });
          return o;
        }) : this.data.optionList,
        ctaLabel: pick ? '知道了' : (exhausted ? this.data.ctaLabel : '再试一次'),
        ctaDisabled: exhausted,
      });
      if (exhausted && stage) stage.fail(false);
    },

    onPreviewFeedback(e) {
      const urls = this.data.shownFeedbackImages;
      const current = urls[Number(e.currentTarget.dataset.i)];
      if (current) wx.previewImage({ current, urls });
    },

    onVerdict(e) { this.triggerEvent('verdict', e.detail); },
    /* 时间到:屏上这几块先烧掉,判定屏由 stage 在烧完之后自己弹 */
    onBurn() { this.setData({ burning: true }); },
    onClose() { this.triggerEvent('close'); },
  },
});
