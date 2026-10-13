// cy-playkit-profile · 出生登记(契约 §2.1)
//
// 一段 1–8 个问题的档案表:text 输入 / pick 点选,可选头像。
// ★ options[].effects 服务端不下发(契约 §2.1)—— 数值加成是暗的,组件也不许自己算:
//   这里只收「玩家填了什么」,effects 由服务端落到它自己的 state 变量上。
// ★ 提交一次即锁档(服务端 done 后拒绝改档),组件看到的 done 就是服务端的权威值。
const reducedMotionBehavior = require('../../../../behaviors/reduced-motion.js');
const motion = require('../../../../utils/motion.js');
const kitDraft = require('../../../../utils/kit-draft.js');

Component({
  behaviors: [reducedMotionBehavior],
  properties: {
    show: { type: Boolean, value: false },
    inline: { type: Boolean, value: false },
    title: { type: String, value: '' },
    lead: { type: String, value: '' },
    // { enabled, required }:头像位开不开、必不必填
    avatar: { type: Object, value: {} },
    questions: { type: Array, value: [] },
    answers: { type: Object, value: {} },
    avatarUrl: { type: String, value: '' },
    done: { type: Boolean, value: false },
    // 填了一半的草稿存哪(页面按账号 + 局 + 站给;空 = 不存)。中途退出重进接着填(2026-09-24)
    draftKey: { type: String, value: '' },
  },
  lifetimes: {
    detached() { this._clearSeal(); },
  },
  data: {
    inked: true, sealed: true,
    form: {},
    step: 0,
    totalSteps: 0,
    currentQuestion: null,
    summary: [],
    avatarStep: false,
    avatarRequired: false,
    ctaDisabled: true,
    ctaLabel: '提交登记',
  },
  observers: {
    'show, questions, answers, done': function (show, questions, answers, done) {
      if (!show) { this._formSignature = ''; return; }
      const signature = JSON.stringify([questions, answers, done]);
      if (signature === this._formSignature) return;
      this._formSignature = signature;
      const form = {};
      (questions || []).forEach((q) => {
        if (!q || !q.key) return;
        const v = answers && answers[q.key];
        form[q.key] = v == null ? '' : String(v);
      });
      const avatar = this.data.avatar || {};
      let step = 0;
      if (done) kitDraft.clear(this.data.draftKey);
      else {
        const draft = kitDraft.read(this.data.draftKey);
        Object.keys(form).forEach((k) => { if (!form[k] && draft.form && draft.form[k]) form[k] = String(draft.form[k]); });
        step = Math.max(0, Math.min(Number(draft.step) || 0, (questions || []).length));
      }
      // 刚在这一屏交上去的,才放「写进档案 + 盖章」(原型 register);重进看到的已登记档案直接是盖好的
      const ceremony = !!done && !!this._submitted && !this.data.reducedMotion;
      this._submitted = false;
      this.setData({
        form,
        step,
        avatarRequired: !!avatar.required,
        ctaLabel: done ? '已登记' : '提交登记',
        inked: !ceremony, sealed: !ceremony,
      }, () => { this._syncCta(); if (ceremony) this._playSeal(); });
    },
    'avatar, avatarUrl': function () {
      const avatar = this.data.avatar || {};
      this.setData({ avatarRequired: !!avatar.required },
        () => this._syncCta());
    },
  },
  methods: {
    /** 答案一行行像墨迹写进档案,写完盖一枚斜章「各项指标在范围内」 */
    _playSeal() {
      const rows = (this.data.questions || []).length;
      this._clearSeal();
      this._inkTimer = setTimeout(() => this.setData({ inked: true }), 40);
      this._sealTimer = setTimeout(() => {
        this.setData({ sealed: true });
        motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'heavy' });
      }, 840 + rows * 250);
    },
    _clearSeal() {
      if (this._inkTimer) clearTimeout(this._inkTimer);
      if (this._sealTimer) clearTimeout(this._sealTimer);
    },
    _saveDraft() {
      if (!this.data.done) kitDraft.write(this.data.draftKey, { form: this.data.form, step: this.data.step });
    },
    /** 每次只问一题；最后一步仍校验整张表，提交保持服务端一次结算。 */
    _syncCta() {
      const { done, form, step, avatarRequired, avatarUrl } = this.data;
      const avatarEnabled = !!(this.data.avatar || {}).enabled;
      const questions = this.data.questions || [];
      const totalSteps = questions.length + (avatarEnabled ? 1 : 0);
      const currentQuestion = questions[step] || null;
      const valid = q => {
        const value = String(form[q.key] || '').trim();
        if (!value) return false;
        return q.kind === 'pick'
          ? (q.options || []).some(o => o.key === value)
          : value.length <= (q.maxLength || 12);
      };
      const final = step >= totalSteps - 1;
      const missing = final
        ? questions.some(q => !valid(q)) || (avatarEnabled && avatarRequired && !avatarUrl)
        : currentQuestion && !valid(currentQuestion);
      const summary = questions.map(q => {
        const value = form[q.key] || '';
        const option = (q.options || []).find(o => o.key === value);
        return { key: q.key, label: q.label || q.key, answer: option ? option.label || option.key : value };
      });
      this.setData({ currentQuestion, totalSteps, summary,
        avatarStep: avatarEnabled && !currentQuestion,
        ctaLabel: done ? '已登记' : final ? '完成建档' : '下一题',
        ctaDisabled: done || !totalSteps || !!missing });
    },

    onInput(e) {
      const key = e.currentTarget.dataset.key;
      if (!key || this.data.done || !this.data.currentQuestion || key !== this.data.currentQuestion.key) return;
      const form = Object.assign({}, this.data.form);
      form[key] = e.detail.value == null ? '' : String(e.detail.value);
      this.setData({ form }, () => { this._syncCta(); this._saveDraft(); });
    },

    onPick(e) {
      if (this.data.done) return;
      const key = e.currentTarget.dataset.key;
      const value = e.currentTarget.dataset.opt;
      if (!key || !this.data.currentQuestion || key !== this.data.currentQuestion.key) return;
      const form = Object.assign({}, this.data.form);
      form[key] = String(value == null ? '' : value);
      this.setData({ form }, () => { this._syncCta(); this._saveDraft(); });
    },

    /** 头像只是**选一张**:上传是两步走,页面传完把地址写回 avatarUrl 属性 */
    onAvatar() {
      if (this.data.done) return;
      wx.chooseMedia({
        count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['compressed'],
        success: (res) => {
          const file = (res && res.tempFiles && res.tempFiles[0]) || {};
          if (!file.tempFilePath) return;
          this.triggerEvent('avatar', { tempFilePath: file.tempFilePath, size: file.size });
        },
        fail: () => {},
      });
    },

    /** 提交只报原始输入,加成与落档都在服务端 */
    onSubmit() {
      if (this.data.done || this.data.ctaDisabled) return;
      if (this.data.step < this.data.totalSteps - 1) {
        this.setData({ step: this.data.step + 1 }, () => { this._syncCta(); this._saveDraft(); });
        return;
      }
      // 档案提交不可撤销 —— 与作答同一档触感
      motion.haptic({ reducedMotion: this.data.reducedMotion, type: 'medium' });
      this._submitted = true;
      this.triggerEvent('submit', {
        answers: Object.assign({}, this.data.form),
        avatarUrl: this.data.avatarUrl || '',
      });
    },

    onPrevious() {
      if (this.data.done || this.data.step <= 0) return;
      this.setData({ step: this.data.step - 1 }, () => { this._syncCta(); this._saveDraft(); });
    },

    onClose() { this.triggerEvent('close'); },
  },
});
