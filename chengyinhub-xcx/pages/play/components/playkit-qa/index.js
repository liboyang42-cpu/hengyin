// cy-playkit-qa · 三种问答共用一屏(原型真源:模板编辑页 v2 · playkit qa)
//
// mode: type 打字 / pick 选项 / shot 拍照。三种的差别只在中间那一块,题面、附件、
// 反馈、限时、次数、判定屏全是一样的 —— 铺成三个组件的话,同一处改动要改三遍。
//
// 三条规则不是随手定的:
//   · **答错保留输入并全选**(F02)。玩家多半只差一两个字,直接改比重打省事;服务端按错答表回一句话,
//     显示在输入框下方,没有回复才用兜底句;
//   · **单选先选、可以换、按「确认」才提交**(F04,照 Duolingo)。确认后才锁定;
//   · **次数按「选项数 - 1」封顶**。三个选项给三次错等于白给 —— 错满两次之后
//     剩下的那个必然是答案,第三次不是机会是走过场;
//   · **答错不算通关**。次数用完就是失败,不给「差不多也算」。
//
// ★ 正确答案永远不下发。这里收到的 options 里没有对错位,判定要么由服务端回,
// 要么(打字题)由服务端比对 —— 组件只负责报玩家输入了什么。
//
// F03 打字题字符池(letterPool):不出输入框,画 answerLength 个空格 + 服务端给的候选字(每行 7 个);
// 点字填进第一个空格,点已填的格退回,可清空;交卷仍是 SUBMIT_QA {input: 按格序拼起来的字}。
// 与逐字猜词字母池同一份状态逻辑(utils/letter-pool.js);错答后已放的字保留(F03 §3.3 / F02),描红提示再改。

const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');
const mediaDenied = require('../../utils/media-denied.js');
const cyToast = require('../../../../utils/toast.js');
const { stripOptionKey } = require('../../utils/playkit-questo.js');
const letterPool = require('../../utils/letter-pool.js');

const KEYS = ['A', 'B', 'C', 'D'];
/* 反馈上面那行小字。原型只有这两个词 —— 「答错了」这种判决式说法在这一格里读着太重,
   而人还有下一次机会。 */
const HEAD_OK = '答对了';
const HEAD_NO = '再想想';
const HEAD_SHOT = '拍好了';   // 第十二轮 k7 F16:拍照打卡没有对错,交了照片不说「答对了」
/* 打字题答错的兜底说法(与服务端通用错答同一句,句末不带句号)。服务端给了话就用服务端的 */
const RETRY_TEXT = '还不对，再看看题面';
/* 一进来题目占满一屏,一秒后选项才淡进来 */
const INTRO_MS = 1000;

/* 打字题的判定在服务端(AnswerNormalizer);客户端不再留一份本地比对,避免两边规则漂移。
   多答案分隔符与向量见 utils/answer-text.js、tests/unit/answer-text-vectors.test.js。 */

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
  t: stripOptionKey(o.t, KEYS[i]) || ('选项 ' + (KEYS[i] || i + 1)),
  // UG-02:选项图与替代文字(pickPlayKit 已滤掉不可显示的引用);加载失败时降级为纯文字
  img: o.img || '',
  alt: o.alt || '',
  state: '',
}));

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    inline: { type: Boolean, value: false },   // 故事流里就地玩:过了之后往下走用故事流自己的「继续故事」,本屏不再出第二个「继续」
    show: { type: Boolean, value: false },
    mode: { type: String, value: 'type' },        // type | pick | shot
    acceptedInput: { type: String, value: '' },
    sessionId: { type: String, value: '' },      // Original runtime identity; never a client-generated business ID.
    nodeId: { type: String, value: '' },
    theme: { type: String, value: 'dark' },       // dark | light
    title: { type: String, value: '' },
    imageUrl: { type: String, value: '' },
    imageAlt: { type: String, value: '' },
    referenceUrl: { type: String, value: '' },   // CG-10-D-3:看不清时的资料图(内容配了才有)       // CG-ALL-13:题图读不出时的替代文字(服务端没给就用通用一句)
    audioUrl: { type: String, value: '' },
    lead: { type: String, value: '' },
    options: { type: Array, value: [] },
    letterPool: { type: Object, value: null },    // F03:{ chars, answerLength };只在打字题且服务端给了时才有
    shotLead: { type: String, value: '' },
    shotNote: { type: String, value: '' },
    limitSeconds: { type: Number, value: 0 },
    maxTries: { type: Number, value: 0 },
    reveal: { type: Boolean, value: false },      // 答错要不要亮出正确答案
    /* 服务端揭晓的那个选项 id。★ 它到齐了这一条链路才成立:
       服务端只在「商家开了答错亮答案 + 次数用完」时才给(见 AdvancedGameRuntimeServiceImpl
       的 submitQa),给了就意味着可以亮。此前 answerIndex 在这个文件里被读、
       却从来没有人写过 —— 恒 undefined,于是「亮出正确答案」一次也没亮过。 */
    answerText: { type: String, value: '' },
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
    /* P-10 第三轮:作者在模板「结果反馈」里给这次结果写了话('PASS' / 'FAIL',服务端判完才给)。
       作者的话由结算判定屏(cy-play-verdict)摆出来;这一屏答错时只描红清空,不再叠自己的兜底句。 */
    authoredOutcome: { type: String, value: '' },
  },
  data: {
    /* ⚠️ 派生数据必须换个键名。写回被 observer 监听的那个属性(options)
       会自己触发自己,死循环把运行时打挂 —— 截图跑到第 8 张 DevTools
       直接断连,就是这么来的,而且控制台一条错都没有。 */
    optionList: [],
    imgFailed: false,
    picGrid: false,
    typed: '',
    /* F02 答错后全选高亮保留的输入(input selection-end);-1 = 不选 */
    selectAll: -1,
    typeWrong: false,
    playing: false,
    feedback: '',
    shownFeedbackImages: [],
    submitted: false,
    fbHead: HEAD_NO,
    intro: true,
    burning: false,
    triesCap: 0,
    ctaLabel: '提交',
    ctaDisabled: true,
    poolOn: false,
    poolCells: [],
    poolRows: [],
    poolCanClear: false,
  },
  observers: {
    imageUrl() { if (this.data.imgFailed) this.setData({ imgFailed: false }); },
    acceptedInput(value) {
      if (value && this.data.mode === 'type' && this.data.passed) this.setData({ typed: value });
      if (value && this.data.poolOn && this.data.passed) this._syncQaPool();
    },
    /* P-10:故事流里的打字题,页面不调 settle(),判过只回流 attempts / feedbackText。
       次数涨了且仍没过 = 刚答错:保留输入并全选、描红、写一句(F02);首次挂载(_seenTypeAttempts 未定)不算。 */
    attempts: function (value) {
      const now = Number(value) || 0;
      const seen = this._seenTypeAttempts;
      this._seenTypeAttempts = now;
      if (seen == null || now <= seen) return;
      if (this.data.mode !== 'type' || this.data.passed || this.data.finished) return;
      const kept = String(this.data.typed || this._submittedText || '');
      this.setData({ typed: kept, typeWrong: true, selectAll: kept.length, ctaDisabled: !kept.trim(), fbHead: HEAD_NO, feedback: this.data.feedbackText || (this.data.authoredOutcome === 'FAIL' ? '' : RETRY_TEXT) });
      if (this.data.poolOn) this._paintQaPool();   // 字符池:已放的字不清,按钮按「填满没有」重新亮
    },
    /* F03:服务端重读后候选字变了(提示去掉了干扰字)→ 对一遍已放的字 */
    letterPool: function () { if (this._qaVisible) this._syncQaPool(); },
    /* P-10 第四轮 rig 实测:作者的结果反馈(authoredOutcome)可能比 attempts 晚一拍落到组件上 ——
       先到的 attempts 已写了兜底句,这里补清;判定屏摆作者的话,题屏只留描红。 */
    authoredOutcome: function (value) {
      if (value !== 'FAIL' || this.data.mode !== 'type' || this.data.feedback !== RETRY_TEXT) return;
      this.setData({ feedback: '' });
    },
    failureSeq: function (value) {
      const singlePick = this.data.mode === 'pick' && !this.data.multi;
      if (!value || this.data.finished || this.data.passed || (this.data.judge !== false && !singlePick)) return;
      // F04 网络失败:选中状态保留,主按钮变成「重试」
      const any = this.data.optionList.some(o => o.state === 'is-on');
      this.setData({ submitted: false, ctaDisabled: !any, ctaLabel: any ? '重试' : this.data.ctaLabel });
    },
    'show, options, mode, multi, passed, finished, judge, lastOptionId, lastOptionIds, feedbackText, feedbackImages, sessionId, nodeId': function (show, options, mode, multi, passed, finished) {
      if (this._qaDisposed) return;
      this._qaEpoch = (this._qaEpoch || 0) + 1;
      this._pendingEvidence = false;
      const identity = JSON.stringify([String(this.data.sessionId || ''), String(this.data.nodeId || '')]);
      const fresh = this._qaIdentity !== identity || this._qaVisible !== !!show;
      this._qaIdentity = identity;
      if (fresh) this._submittedText = '';
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
      /* F04:单选(判不判对错都一样)先选、可以换,按「确认选择」才提交;多选仍是勾选 + 提交 */
      const confirmPick = mode === 'pick' && (this.data.judge === false || !multi);
      const isMultiPick = mode === 'pick' && !!multi && this.data.judge !== false;
      this.setData({
        optionList: opts,
        /* r2 b2-ug-02:有选项配图时改成图卡网格(图在上、字在下),纯文字题仍是细线行 */
        picGrid: mode === 'pick' && opts.some(o => !!o.img),
        /* P-10:打字题答错时服务端常不带反馈文案,原来整块反馈不渲染 = 玩家看不出判过了。
           没有文案就写一句固定的「不对」,输入框描红,下一次输入即清。 */
        typed: passed ? (this.data.acceptedInput || (!fresh && (this._submittedText || this.data.typed)) || '')
          : ((!fresh && (this.data.typed || this._submittedText)) || ''), feedback: (passed || finished || this.data.attempts > 0)
          ? (this.data.feedbackText || (mode === 'type' && !passed && !finished && this.data.attempts > 0 && this.data.authoredOutcome !== 'FAIL' ? RETRY_TEXT : '')) : '',
        typeWrong: mode === 'type' && !passed && !finished && this.data.attempts > 0,
        shownFeedbackImages: (passed || finished || this.data.attempts > 0)
          ? (this.data.feedbackImages || []).slice() : [],
        submitted: false,
        fbHead: passed ? (mode === 'shot' ? HEAD_SHOT : HEAD_OK) : HEAD_NO, playing: false, intro: !passed && !finished,
        burning: fresh ? false : !!this.data.burning,
        triesCap: this.data.judge === false || finished ? 0 : cappedTries(this.data.maxTries, opts.length),
        ctaLabel: passed ? '继续' : (confirmPick ? '确认选择' : (mode === 'shot' ? '拍一张' : '提交')),
        // 多选一进来一个都没勾,按钮就得是灰的 —— 亮着一颗按不动的按钮比灰着更糟
        ctaDisabled: !passed && (finished || (mode === 'type' && !String(this.data.typed || '').trim() && !(!fresh && this._submittedText))
          || isMultiPick || confirmPick),
      });
      this._syncQaPool(fresh);
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
    /** 选项图读不出来:只撤这一张图,文字照旧 —— 不显示破图(UG-02)。 */
    onOptionImgError(e) {
      const i = Number(e.currentTarget.dataset.i);
      if (!(this.data.optionList[i] && this.data.optionList[i].img)) return;
      this.setData({ ['optionList[' + i + '].img']: '' });
    },
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

    _cappedTries: cappedTries,

    /** F03 字符池是否生效:打字题 + 服务端给了 chars 与 answerLength。 */
    _poolSpec() {
      const lp = this.data.letterPool;
      if (this.data.mode !== 'type' || !lp || !Array.isArray(lp.chars) || !lp.chars.length) return null;
      const slots = letterPool.blankSlots(lp.answerLength);
      return slots.length ? { slots, chars: lp.chars } : null;
    },
    /** 字符池对一遍:换题(fresh)从空开始;通过后按服务端认可的输入摆好(只读);其余保留已放的字。 */
    _syncQaPool(fresh) {
      const spec = this._poolSpec();
      if (!spec) { this._qaPool = null; this._qaPoolSlots = null; if (this.data.poolOn) this.setData({ poolOn: false, poolCells: [], poolRows: [], poolCanClear: false }); return; }
      if (fresh) { this._qaPool = null; this._qaPoolSlots = null; }
      this._qaPool = letterPool.sync(this._qaPool, spec.slots, spec.chars, this._qaPoolSlots);
      this._qaPoolSlots = spec.slots;
      const accepted = this.data.passed ? Array.from(String(this.data.acceptedInput || this._submittedText || '')) : [];
      if (accepted.length === spec.slots.length && !letterPool.complete(this._qaPool, spec.slots)) {
        const locked = {};
        spec.slots.forEach((slot, at) => { locked[slot.i] = accepted[at]; });
        this._qaPool = { picks: {}, locked };
      }
      this._paintQaPool();
    },
    _paintQaPool() {
      const spec = this._poolSpec();
      if (!spec) return;
      const state = this._qaPool || letterPool.empty();
      const v = letterPool.view(state, spec.slots, spec.chars);
      const done = this.data.passed || this.data.finished;
      const next = done ? -1 : (v.cells.find((c) => !c.ch) || { i: -1 }).i;
      this.setData({
        poolOn: true,
        poolCells: v.cells.map((c) => Object.assign(c, { next: c.i === next })),
        poolRows: v.rows,
        poolCanClear: !done && v.filled > 0,
        ctaDisabled: !done && !(v.complete && !this.data.submitted),
      });
    },
    /** 点候选字:填进第一个空格。 */
    onPickPool(e) {
      if (!this.data.poolOn || this.data.passed || this.data.finished || this.data.submitted) return;
      const spec = this._poolSpec();
      if (!spec) return;
      const res = letterPool.fill(this._qaPool || letterPool.empty(), spec.slots, spec.chars, Number(e.currentTarget.dataset.k));
      if (res.slot < 0) return;
      this._qaPool = res.state;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ typeWrong: false });
      this._paintQaPool();
    },
    /** 点已填的格:字退回候选区。 */
    onPoolSlot(e) {
      if (!this.data.poolOn || this.data.passed || this.data.finished || this.data.submitted) return;
      const state = this._qaPool || letterPool.empty();
      const next = letterPool.giveBack(state, Number(e.currentTarget.dataset.i));
      if (next === state) return;
      this._qaPool = next;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ typeWrong: false });
      this._paintQaPool();
    },
    onClearPool() {
      if (!this.data.poolOn || !this.data.poolCanClear || this.data.passed || this.data.finished || this.data.submitted) return;
      this._qaPool = letterPool.clear(this._qaPool || letterPool.empty());
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
      this.setData({ typeWrong: false });
      this._paintQaPool();
    },

    onRun() { this.triggerEvent('run'); },
    onInput(e) {
      if (this.data.passed || this.data.finished || this.data.submitted) return;
      const typed = String(e.detail.value || '');
      this.setData({ typed, typeWrong: false, selectAll: -1, ctaDisabled: !typed.trim() });
    },   // P-10:下一次输入即清掉描红
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
      /* F04 单选(判对错):点选项只是选中,可以随便换;按「确认选择」才提交。上一次答错标的红保留,
         让玩家看得见哪条已经试过。 */
      if (this.data.mode === 'pick' && !this.data.multi) {
        motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'light' });
        this.setData({
          optionList: this.data.optionList.map((o, at) => Object.assign({}, o, {
            state: at === i ? 'is-on' : (o.state === 'is-on' || o.state === 'is-dim' ? '' : o.state) })),
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
    },
    /** F04 单选确认:报最后选中的那一条,对错由服务端回 —— 正确答案不在客户端。 */
    _confirmSingle() {
      const i = this.data.optionList.findIndex(o => o.state === 'is-on');
      if (i < 0) return;
      const picked = this.data.optionList[i];
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      /* l-kits 2026-11-10 图片选项(原型 gp-kit-embed):确认时这张上浮、其余变淡,等服务端判;
         这只是「我选了它」的即时反馈,不是对错 —— 对错由 settle() 按服务端回执重写。 */
      this.setData({
        submitted: true, ctaDisabled: true,
        optionList: this.data.picGrid
          ? this.data.optionList.map((o, at) => Object.assign({}, o, { state: at === i ? 'is-on' : 'is-dim' }))
          : this.data.optionList,
      });
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
      if (this._pendingEvidence || this.data.passed) { this.triggerEvent('continue'); return; }
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
          fail: (err) => { if (this._isQaContextCurrent(context)) mediaDenied.guideIfDenied(err); },
        });
        return;
      }
      // 单选与多选都由按钮提交(F04:单选先选可换,确认才算)
      if (this.data.mode === 'pick') {
        if (this.data.judge === false) {
          const index = this.data.optionList.findIndex(o => o.state === 'is-on');
          if (index < 0) return;
          this.setData({ submitted: true, ctaDisabled: true });
          this.triggerEvent('submit', { mode: 'pick', index, optionId: this.data.optionList[index].id });
        } else if (this.data.multi) this._submitPick();
        else this._confirmSingle();
        return;
      }
      if (this.data.poolOn) {
        const spec = this._poolSpec();
        const input = spec ? letterPool.joined(this._qaPool || letterPool.empty(), spec.slots) : '';
        if (!input) return;
        this._submittedText = input;
        motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
        this.triggerEvent('submit', { mode: 'type', input });
        return;
      }
      if (!String(this.data.typed || '').trim()) return;
      this._submittedText = this.data.typed;
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      this.triggerEvent('submit', { mode: 'type', input: this.data.typed });
    },

    /** 页面拿到服务端判定后回调这里。组件不自己判对错。 */
    settle(ok, detail) {
      const d = detail || {};
      const stage = this.selectComponent('#cy-play-stage');
      const multi = this.data.mode === 'pick' && !!this.data.multi;
      // 答案已由服务端判对但照片尚未通过：只显示答案事实，不能触发通关或发奖。
      this._pendingEvidence = !!(ok && d.pendingEvidence && this.data.mode === 'pick');
      if (this._pendingEvidence) {
        this.setData({
          optionList: this.data.optionList.map((o, i) => Object.assign({}, o, { state: i === d.index ? 'is-right' : 'is-dim' })),
          feedback: d.feedback || '', fbHead: HEAD_OK, ctaLabel: '重拍', ctaDisabled: false,
        });
        return;
      }
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
          feedback: d.feedback || '', fbHead: this.data.mode === 'shot' ? HEAD_SHOT : HEAD_OK,
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
      const kept = this.data.mode === 'type' ? String(this.data.typed || '') : '';
      // 字段写死成字面量,不拼 patch:动态 setData 门禁核不出写了哪些顶层字段
      this.setData({
        // F02 答错保留输入并全选:玩家直接改,不用重打
        typed: kept,
        selectAll: kept.length,
        submitted: false,
        typeWrong: this.data.mode === 'type',
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
          // 图片选项点选时变淡的其余几张在判错后回到原样,还能再选(原型答错 2.4s 后复位,这里随回执即复位)
          return o.state === 'is-dim' ? Object.assign({}, o, { state: '' }) : o;
        }) : this.data.optionList,
        ctaLabel: pick ? (multi ? '知道了' : '确认选择') : (exhausted ? this.data.ctaLabel : '再试一次'),
        // 单选答错后要先另选一条才能再确认;打字题保留了输入,可以直接改了再交
        ctaDisabled: exhausted || (pick && !multi) || (this.data.mode === 'type' && !kept.trim()),
      });
      if (this.data.poolOn && !exhausted) this._paintQaPool();   // 字符池:已放的字保留,填满就能直接再交
      if (exhausted && stage) stage.fail(false);
    },

    /** CG-ALL-13:题图读不出来不留破图,换成替代文字 + 重试(重挂 image 再取一次)。 */
    onOpenReference() {
      const url = this.data.referenceUrl;
      if (!url) return;
      wx.previewImage({ current: url, urls: [url] });
      this.triggerEvent('reference');
    },
    onQuestionImgError() { this.setData({ imgFailed: true }); },
    onRetryQuestionImg() { this.setData({ imgFailed: false }); },
    onPreviewQuestion() {
      const current = this.data.imageUrl;
      if (current) wx.previewImage({ current, urls: [current] });
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
