// d20 与角色设定(文档二 L1,2026-10-28):叠在主题详情上的一张全高半屏。
// 第一层:规则难度三张卡 / 三个规则开关 / 属性(就地展开 + 预设药丸)/ 更多规则;
// 下一层「更多规则」:进度钟、特质,都就地展开、就地添加。从这张半屏出发最多再开这一层。
// 改动只记在本地副本里:「完成」把整份交回主题(save),「取消」丢掉。写回哪些字段见 fabu/theme-d20.js。
const d20 = require('../../fabu/theme-d20.js');
const modal = require('../../../../utils/modal.js');

const TAP_MS = 420; // 卡片回弹一次的时长,和 --cy-motion-sheet-in 对齐

Component({
  options: { addGlobalClass: true },
  properties: {
    show: { type: Boolean, value: false },
    rules: { type: String, value: '' },
    readOnly: { type: Boolean, value: false },
  },
  data: {
    presets: d20.PRESETS,
    clockKinds: d20.CLOCK_KINDS,
    clockSegments: d20.CLOCK_SEGMENTS,
    level: 'root',
    model: null,
    tapKey: '',
    editing: false,
    openAttr: '',
    swipeAttr: -1,
    attrChips: [],
    attrTaken: [],
    openClock: '',
    swipeClock: -1,
    addingClock: false,
    clockName: '',
    openAspect: '',
    swipeAspect: -1,
    aspectChips: [],
    moreValue: '',
    attrLocked: false,
  },
  observers: {
    show(v) { if (v) this.load(); },
  },
  lifetimes: {
    detached() { if (this._tapTimer) { clearTimeout(this._tapTimer); this._tapTimer = null; } },
  },
  methods: {
    load() {
      const model = d20.read(this.data.rules);
      this.setData({ level: 'root', editing: false, openAttr: '', swipeAttr: -1, openClock: '', swipeClock: -1,
        addingClock: false, clockName: '', openAspect: '', swipeAspect: -1, model });
      this.refresh(model);
    },
    refresh(model) {
      const m = model || this.data.model;
      const have = m.attrs.map(a => a.label);
      const aspects = m.aspects.map(a => a.label);
      this.setData({
        attrTaken: have,
        attrChips: m.attrs.length >= d20.MAX_ATTRS ? [] : d20.ATTR_PRESETS.filter(p => have.indexOf(p.label) < 0).map(p => ({ label: p.label, dot: p.dot })),
        aspectChips: m.aspects.length >= d20.MAX_ASPECTS ? [] : d20.ASPECT_PRESETS.filter(p => aspects.indexOf(p.label) < 0).map(p => ({ label: p.label })),
        moreValue: '进度钟 ' + m.clocks.length + ' · 特质 ' + m.aspects.length,
        attrLocked: this.data.readOnly || m.bound,
      });
    },
    // extra 只认下面这几项界面状态;字段写死,setData 的消费面才核得清
    commit(model, extra) {
      const x = extra || {};
      const keep = (k) => (Object.prototype.hasOwnProperty.call(x, k) ? x[k] : this.data[k]);
      this.setData({
        model,
        tapKey: keep('tapKey'),
        openAttr: keep('openAttr'),
        swipeAttr: keep('swipeAttr'),
        openClock: keep('openClock'),
        swipeClock: keep('swipeClock'),
        addingClock: keep('addingClock'),
        clockName: keep('clockName'),
        openAspect: keep('openAspect'),
        swipeAspect: keep('swipeAspect'),
      });
      this.refresh(model);
    },
    edit(fn, extra) {
      if (this.data.readOnly) return;
      const model = JSON.parse(JSON.stringify(this.data.model));
      fn(model);
      this.commit(model, extra);
    },

    onCancel() { this.triggerEvent('close'); },
    onDone() {
      if (this.data.readOnly) { this.triggerEvent('close'); return; }
      this.triggerEvent('save', { rules: d20.apply(this.data.rules, this.data.model) });
    },
    goMore() { this.setData({ level: 'more', swipeAttr: -1 }); },
    goBack() { this.setData({ level: 'root', swipeClock: -1, swipeAspect: -1, addingClock: false, clockName: '' }); },

    // ---- 规则难度 / 规则开关 ----
    pickPreset(e) {
      const key = e.currentTarget.dataset.key;
      if (this.data.readOnly || key === this.data.model.preset) return;
      if (this._tapTimer) clearTimeout(this._tapTimer);
      this._tapTimer = setTimeout(() => { this._tapTimer = null; this.setData({ tapKey: '' }); }, TAP_MS);
      this.edit(m => { m.preset = key; }, { tapKey: key });
    },
    onRule(e) {
      const field = e.currentTarget.dataset.field;
      const on = !!e.detail.value;
      this.edit(m => { m[field] = on; });
    },

    // ---- 属性 ----
    toggleEdit() { this.setData({ editing: !this.data.editing, openAttr: '', swipeAttr: -1 }); },
    attrTap(e) {
      if (this.data.editing) return;
      const key = e.currentTarget.dataset.key;
      this.setData({ openAttr: this.data.openAttr === key ? '' : key, swipeAttr: -1 });
    },
    attrStep(e) {
      const { key, field } = e.currentTarget.dataset;
      this.edit(m => {
        const i = m.attrs.findIndex(a => a.key === key);
        if (i >= 0) m.attrs[i] = d20.clampAttr(m.attrs[i], field, e.detail.value);
      });
    },
    attrSwipe(e) { this.setData({ swipeAttr: e.detail.open ? e.detail.index : -1, openAttr: '' }); },
    attrMove(e) { this.edit(m => { m.attrs = d20.move(m.attrs, e.detail.from, e.detail.to); }); },
    attrDelete(e) {
      const index = e.detail && e.detail.index != null ? e.detail.index : Number(e.currentTarget.dataset.index);
      const attr = this.data.model.attrs[index];
      if (!attr || this.data.attrLocked) return;
      modal.show({
        title: '删除「' + attr.label + '」？',
        content: '用到它的检定将不再加上属性值。',
        confirmText: '删除',
        danger: true,
        success: (res) => {
          if (!res.confirm) return;
          this.edit(m => { m.attrs = m.attrs.filter(a => a.key !== attr.key); }, { swipeAttr: -1, openAttr: '' });
        },
      });
    },
    attrAdd(e) {
      const label = e.detail.label || e.detail.value;
      if (!label || this.data.attrLocked || this.data.model.attrs.length >= d20.MAX_ATTRS) return;
      if (this.data.model.attrs.some(a => a.label === label)) return;
      const attr = d20.newAttr(label, this.data.model);
      this.edit(m => { m.attrs.push(attr); }, { openAttr: attr.key, swipeAttr: -1 });
    },

    // ---- 进度钟 ----
    clockTap(e) {
      const key = e.currentTarget.dataset.key;
      this.setData({ openClock: this.data.openClock === key ? '' : key, swipeClock: -1 });
    },
    clockRename(e) {
      const key = e.currentTarget.dataset.key;
      const label = String(e.detail.value || '').trim();
      if (!label) return;
      this.edit(m => { const c = m.clocks.find(x => x.var === key); if (c) c.label = label; });
    },
    clockField(e) {
      const { key, field } = e.currentTarget.dataset;
      this.edit(m => { const c = m.clocks.find(x => x.var === key); if (c) c[field] = e.detail.value; });
    },
    clockSwipe(e) { this.setData({ swipeClock: e.detail.open ? e.detail.index : -1, openClock: '' }); },
    clockDelete(e) {
      const index = e.detail && e.detail.index != null ? e.detail.index : Number(e.currentTarget.dataset.index);
      const clock = this.data.model.clocks[index];
      if (!clock || this.data.readOnly) return;
      modal.show({
        title: '删除进度钟「' + clock.label + '」？',
        content: '用到它的条件和效果需要重新选择。',
        confirmText: '删除',
        danger: true,
        success: (res) => {
          if (!res.confirm) return;
          this.edit(m => { m.clocks = m.clocks.filter(c => c.var !== clock.var); }, { swipeClock: -1, openClock: '' });
        },
      });
    },
    clockAddStart() { if (!this.data.readOnly) this.setData({ addingClock: true, clockName: '', openClock: '' }); },
    clockInput(e) { this.setData({ clockName: e.detail.value }); },
    clockBlur() { if (!this.data.clockName.trim()) this.setData({ addingClock: false }); },
    clockAdd() {
      const label = this.data.clockName.trim();
      if (!label || this.data.model.clocks.length >= d20.MAX_CLOCKS) return;
      const clock = d20.newClock(label, this.data.model);
      this.edit(m => { m.clocks.push(clock); }, { addingClock: false, clockName: '', openClock: clock.var });
    },

    // ---- 特质 ----
    aspectTap(e) {
      const key = e.currentTarget.dataset.key;
      this.setData({ openAspect: this.data.openAspect === key ? '' : key, swipeAspect: -1 });
    },
    aspectSwipe(e) { this.setData({ swipeAspect: e.detail.open ? e.detail.index : -1, openAspect: '' }); },
    aspectDelete(e) {
      const index = e.detail && e.detail.index != null ? e.detail.index : Number(e.currentTarget.dataset.index);
      const aspect = this.data.model.aspects[index];
      if (!aspect || this.data.readOnly) return;
      modal.show({
        title: '移除特质「' + aspect.label + '」？',
        content: '用到它的条件需要重新选择。',
        confirmText: '移除',
        danger: true,
        success: (res) => {
          if (!res.confirm) return;
          this.edit(m => { m.aspects = m.aspects.filter(a => a.key !== aspect.key); }, { swipeAspect: -1, openAspect: '' });
        },
      });
    },
    aspectAdd(e) {
      const label = e.detail.label;
      if (!label || this.data.model.aspects.length >= d20.MAX_ASPECTS) return;
      const aspect = d20.newAspect(label, this.data.model);
      this.edit(m => { m.aspects.push(aspect); }, { openAspect: aspect.key, swipeAspect: -1 });
    },
  },
});
