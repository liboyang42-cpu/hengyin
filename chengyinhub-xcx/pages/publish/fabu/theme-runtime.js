const app = getApp();
const toast = require('../../../utils/toast.js');
const initial = require('../utils/publish/story-initial-state.js');
const PURPOSES = ['展示', '生命值', '幸运值'];
const EFFECTS = ['使用时', '获得时一次', '持有期间', '持有期间减耗'];
const TIMINGS = ['不自动发放', '章节开始', '章节结束'];
const copy = value => JSON.parse(JSON.stringify(value));
function config(raw) {
  const c = raw ? (typeof raw === 'string' ? JSON.parse(raw) : copy(raw)) : {};
  return Object.assign({ schemaVersion: 1, capacity: 6, allowDiscard: true, items: [], chapters: [], grants: [], events: [] }, c);
}
function chapter(c, index) {
  let row = c.chapters.find(v => v.index === index);
  if (!row) { row = { index, hpCost: 0, luckCost: 0, grants: [], eggs: [] }; c.chapters.push(row); }
  return row;
}
function rows(c, index) {
  const grants = chapter(c, index).grants;
  return c.items.map(item => {
    const g = grants.find(v => v.itemId === item.id) || {};
    return Object.assign({}, item, {
      purposeIndex: item.stat === 'HP' ? 1 : item.stat === 'LUCK' ? 2 : 0,
      effectIndex: !item.wear ? 0 : item.mode === 'ONCE' ? 1 : item.mode === 'HELD' ? 2 : 3,
      timingIndex: g.timing === 'START' ? 1 : g.timing === 'END' ? 2 : 0,
      quantity: g.quantity || 1, effectOptions: item.stat === 'LUCK' ? EFFECTS.slice(0, 3) : EFFECTS,
    });
  });
}
function validate(c, rules) {
  if (!Number.isInteger(c.capacity) || c.capacity < 1 || c.capacity > 999) return '物品容量请输入 1 到 999 的整数';
  for (const i of c.items) {
    if (!String(i.name || '').trim()) return '请输入物品或勋章名称';
    if (!/^https:\/\/[^\s]+$/.test(String(i.image || ''))) return '请上传物品或勋章图片';
    if (i.stat && (!Number.isInteger(Number(i.delta)) || !Number(i.delta) || Math.abs(Number(i.delta)) > 999 || (i.mode === 'REDUCE' && Number(i.delta) < 0))) return '请输入非 0 的整数增减值';
    if (i.stat && rules && !rules[i.stat === 'HP' ? 'hp' : 'luck'].enabled) return '请先开启对应的生命值或幸运值';
  }
  for (const ch of c.chapters) {
    for (const stat of ['hp', 'luck']) if (!Number.isInteger(ch[stat + 'Cost']) || ch[stat + 'Cost'] < 0 || ch[stat + 'Cost'] > 999 || (ch[stat + 'Cost'] > 0 && rules && !rules[stat].enabled)) return '请开启对应数值，并填写 0 到 999 的章节消耗';
    for (const g of ch.grants) if (!Number.isInteger(g.quantity) || g.quantity < 1 || g.quantity > 99) return '发放数量请输入 1 到 99 的整数';
    for (const e of ch.eggs) {
      if (!e.name.trim() || !e.content.trim()) return '请填写彩蛋名称和内容';
      if (!['TIME', 'STEPS'].includes(e.trigger) || !Number.isInteger(e.threshold) || e.threshold < 1 || e.threshold > 99999) return '请填写彩蛋触发方式与数值';
      for (const r of e.rewards) if (!c.items.some(i => i.id === r.itemId) || !Number.isInteger(r.quantity) || r.quantity < 1 || r.quantity > 99) return '请选择彩蛋奖励并填写数量';
    }
  }
  return '';
}
const data = {
  runtimeEditor: { show: false, kind: '', title: '', index: 0 },
  runtimeBag: { capacity: 6, allowDiscard: true, items: [] }, runtimeEggs: [], runtimeRewardOptions: [],
  runtimePurposes: PURPOSES, runtimeEffects: EFFECTS, runtimeTimings: TIMINGS,
  runtimeTriggers: ['时间', '步数'], runtimeHpEnabled: false, runtimeLuckEnabled: false,
  runtimeRewardOpen: false, subtitleEditing: false, runtimeBingoIndex: 0, runtimeCompletionIndex: -1, runtimeChapterCosts: { hpCost: 0, luckCost: 0 },
};
const methods = {
  _runtimeConfig() { return config(this.data.formData.themeRuntimeConfig); },
  _saveRuntime(c) { this.setData({ 'formData.themeRuntimeConfig': JSON.stringify(c) }); },
  _runtimePayload() { const c = this._runtimeConfig(); c.chapters = c.chapters.filter(ch => ch.index < this.data.formData.chapters.length); c.items.forEach(i => { if (i.stat) i.delta = Number(i.delta); }); return this.data.formData.themeRuntimeConfig ? JSON.stringify(c) : null; },
  _runtimeCosts(index) { return chapter(this._runtimeConfig(), index); },
  _prependRuntimeChapter() { const c = this._runtimeConfig(); const added = c.chapters.find(ch => ch.index === this.data.formData.chapters.length); c.chapters.forEach(ch => { ch.index = ch === added ? 0 : ch.index + 1; }); this._saveRuntime(c); },
  _removeRuntimeChapter(index) { const c = this._runtimeConfig(); c.chapters = c.chapters.filter(ch => ch.index !== index).map(ch => Object.assign(ch, { index: ch.index > index ? ch.index - 1 : ch.index })); this._saveRuntime(c); },
  _runtimeChapterIndex() { return this.data.popChapterAction == 0 ? this.data.formData.chapters.length : this.data.popChapterIndex; },
  _refreshRuntimeEditor(c) {
    const index = this.data.runtimeEditor.index;
    const ch = chapter(c, index);
    this.setData({ runtimeBag: { capacity: c.capacity, allowDiscard: c.allowDiscard, items: rows(c, index) },
      runtimeEggs: ch.eggs.map(e => Object.assign({}, e, { triggerIndex: e.trigger === 'STEPS' ? 1 : 0,
        rewards: e.rewards.map(r => Object.assign({}, r, { selectedIndex: c.items.findIndex(i => i.id === r.itemId) })) })),
      runtimeCompletionIndex: c.items.findIndex(i => c.grants.some(g => g.id === 'completion_material' && g.itemId === i.id)),
      runtimeRewardOptions: c.items.map(i => ({ id: i.id, name: i.name || (i.kind === 'MEDAL' ? '未命名勋章' : '未命名物品') })) });
  },
  _openRuntime(kind, title) {
    const c = this._runtimeConfig();
    this.setData({ runtimeEditor: { show: true, kind, title, index: this._runtimeChapterIndex() } });
    this._refreshRuntimeEditor(c);
  },
  openChapterBackpack() { this._openRuntime('bag', '背包'); },
  openChapterEggs() { this._openRuntime('eggs', '途中彩蛋'); },
  openThemeRewards() { this._openRuntime('rewards', '主题通关奖励'); },
  openThemeBingo() { this._openRuntime('bingo', '宾果九宫格'); },
  openRuntimeBingoCell(e) { this.setData({ runtimeBingoIndex: Number(e.currentTarget.dataset.index), 'runtimeEditor.kind': 'bingo-cell', 'runtimeEditor.title': '编辑格子' }); },
  backRuntimeBingo() { this.setData({ 'runtimeEditor.kind': 'bingo', 'runtimeEditor.title': '宾果九宫格' }); },
  closeRuntimeEditor() {
    const c = this._runtimeConfig();
    if (this.data.runtimeEditor.kind === 'bag' || this.data.runtimeEditor.kind === 'eggs') {
      const issue = validate(c, initial.parse(this.data.formData.journeyRules));
      if (issue) { toast(issue); return; }
    }
    this.setData({ 'runtimeEditor.show': false });
  },
  onRuntimeRewardOpen(e) { this.setData({ runtimeRewardOpen: !!e.detail.open }); },
  toggleSubtitleEditing() { this.setData({ subtitleEditing: !this.data.subtitleEditing }); },
  _syncRuntimeStats() {
    const r = initial.parse(this.data.formData.journeyRules);
    this.setData({ runtimeHpEnabled: !!r.hp.enabled, runtimeLuckEnabled: !!r.luck.enabled });
  },
  onRuntimeStatToggle(e) {
    const r = initial.parse(this.data.formData.journeyRules);
    r[e.currentTarget.dataset.stat].enabled = !!e.detail.value;
    r.stateEnabled = r.hp.enabled || r.luck.enabled || (r.attributes || []).some(a => a.visible);
    if (r.hp.enabled || r.luck.enabled) r.checkEnabled = true;
    try { this.setData({ 'formData.journeyRules': initial.serialize(r) }); this._saveRuntime(this._runtimeConfig()); this._syncRuntimeStats(); }
    catch (error) { toast(error.message); }
  },
  addRuntimeContent(e) {
    if (e.currentTarget.dataset.kind === 'MEDAL' && !this.data.medalEditable) { toast('当前账号无法添加勋章'); return; }
    const c = this._runtimeConfig();
    c.items.push({ id: 'i' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6), kind: e.currentTarget.dataset.kind, name: '', image: '' });
    this._saveRuntime(c); this._refreshRuntimeEditor(c);
  },
  onRuntimePolicy(e) {
    const c = this._runtimeConfig(), f = e.currentTarget.dataset.field;
    c[f] = f === 'capacity' ? Number(e.detail.value) : !!e.detail.value;
    this._saveRuntime(c); this._refreshRuntimeEditor(c);
  },
  onRuntimeItem(e) {
    const c = this._runtimeConfig(), d = e.currentTarget.dataset, item = c.items[Number(d.index)];
    const v = e.detail.value;
    if (item.kind === 'MEDAL' && !this.data.medalEditable && !['timing', 'quantity'].includes(d.field)) return;
    if (d.field === 'purpose') {
      delete item.stat; delete item.delta; delete item.wear; delete item.mode;
      if (Number(v)) { item.stat = Number(v) === 1 ? 'HP' : 'LUCK'; item.delta = 1; item.wear = false; }
    } else if (d.field === 'effect') {
      item.wear = Number(v) > 0; delete item.mode;
      if (item.wear) item.mode = ['', 'ONCE', 'HELD', 'REDUCE'][Number(v)];
    } else if (d.field === 'timing' || d.field === 'quantity') {
      const ch = chapter(c, this.data.runtimeEditor.index);
      let g = ch.grants.find(x => x.itemId === item.id);
      if (!g) { g = { itemId: item.id, quantity: 1, timing: 'START' }; ch.grants.push(g); }
      if (d.field === 'quantity') g.quantity = Number(v);
      else if (Number(v)) g.timing = ['', 'START', 'END'][Number(v)];
      else ch.grants = ch.grants.filter(x => x !== g);
    } else item[d.field] = v;
    this._saveRuntime(c); this._refreshRuntimeEditor(c);
  },
  uploadRuntimeImage(e) {
    const index = Number(e.currentTarget.dataset.index);
    if (this._runtimeConfig().items[index].kind === 'MEDAL' && !this.data.medalEditable) return;
    app.chooseImage(res => { const c = this._runtimeConfig(); if (!c.items[index]) return; c.items[index].image = res[0]; this._saveRuntime(c); this._refreshRuntimeEditor(c); }, 1, { crop: true, cropScale: '1:1' });
  },
  removeRuntimeItem(e) {
    const c = this._runtimeConfig(), index = Number(e.currentTarget.dataset.index), item = c.items[index];
    const referenced = c.grants.some(g => g.itemId === item.id) || c.chapters.some(ch => ch.grants.some(g => g.itemId === item.id) || ch.eggs.some(egg => egg.rewards.some(g => g.itemId === item.id)));
    if (referenced) { toast('请先移除这个物品的发放和奖励配置'); return; }
    c.items.splice(index, 1); this._saveRuntime(c); this._refreshRuntimeEditor(c);
  },
  addRuntimeEgg() {
    const c = this._runtimeConfig(); chapter(c, this.data.runtimeEditor.index).eggs.push({ id: 'e' + Date.now().toString(36), name: '', trigger: 'TIME', threshold: 10, content: '', rewards: [] });
    this._saveRuntime(c); this._refreshRuntimeEditor(c);
  },
  onRuntimeEgg(e) {
    const c = this._runtimeConfig(), d = e.currentTarget.dataset, egg = chapter(c, this.data.runtimeEditor.index).eggs[Number(d.index)];
    egg[d.field] = d.field === 'trigger' ? (Number(e.detail.value) ? 'STEPS' : 'TIME') : d.field === 'threshold' ? Number(e.detail.value) : e.detail.value;
    this._saveRuntime(c); this._refreshRuntimeEditor(c);
  },
  removeRuntimeEgg(e) { const c = this._runtimeConfig(); chapter(c, this.data.runtimeEditor.index).eggs.splice(Number(e.currentTarget.dataset.index), 1); this._saveRuntime(c); this._refreshRuntimeEditor(c); },
  addRuntimeEggReward(e) { const c = this._runtimeConfig(); chapter(c, this.data.runtimeEditor.index).eggs[Number(e.currentTarget.dataset.index)].rewards.push({ itemId: '', quantity: 1 }); this._saveRuntime(c); this._refreshRuntimeEditor(c); },
  onRuntimeEggReward(e) {
    const c = this._runtimeConfig(), d = e.currentTarget.dataset, egg = chapter(c, this.data.runtimeEditor.index).eggs[Number(d.index)], r = egg.rewards[Number(d.reward)];
    if (d.field === 'itemId') r.itemId = c.items[Number(e.detail.value)].id; else r.quantity = Number(e.detail.value);
    this._saveRuntime(c); this._refreshRuntimeEditor(c);
  },
  removeRuntimeEggReward(e) { const c = this._runtimeConfig(), d = e.currentTarget.dataset; chapter(c, this.data.runtimeEditor.index).eggs[Number(d.index)].rewards.splice(Number(d.reward), 1); this._saveRuntime(c); this._refreshRuntimeEditor(c); },
  onRuntimeChapterCost(e) { const c = this._runtimeConfig(); chapter(c, this._runtimeChapterIndex())[e.currentTarget.dataset.field] = Number(e.detail.value); this._saveRuntime(c); this.setData({ runtimeChapterCosts: chapter(c, this._runtimeChapterIndex()) }); },
  onRuntimeCompletionItem(e) {
    const c = this._runtimeConfig(), id = c.items[Number(e.detail.value)].id;
    c.grants = c.grants.filter(g => g.id !== 'completion_material');
    c.grants.push({ id: 'completion_material', source: 'ENDING', itemId: id, quantity: 1 });
    this._saveRuntime(c); this.setData({ runtimeCompletionIndex: Number(e.detail.value) });
  },
  clearRuntimeCompletionItem() { const c = this._runtimeConfig(); c.grants = c.grants.filter(g => g.id !== 'completion_material'); this._saveRuntime(c); this.setData({ runtimeCompletionIndex: -1 }); },
  _validateRuntimeConfig() { const c = this._runtimeConfig(); c.chapters = c.chapters.filter(ch => ch.index < this.data.formData.chapters.length); return this.data.formData.themeRuntimeConfig ? validate(c, initial.parse(this.data.formData.journeyRules)) : ''; },
};
module.exports = { data, methods, config, validate, rows };
