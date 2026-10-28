const app = getApp();
const toast = require('../../../utils/toast.js');
const initial = require('../utils/publish/story-initial-state.js');
const supportMatrix = require('../utils/publish/gameplay-support-matrix.js');
const values = require('./theme-values.js');
const zeroHp = require('./theme-runtime-zero-hp.js');
const PURPOSES = ['展示', '生命值', '幸运值'];
const EFFECTS = ['使用时', '获得时一次', '持有期间', '持有期间减耗'];
const TIMINGS = ['不自动发放', '章节开始', '章节结束'];
const copy = value => JSON.parse(JSON.stringify(value));
const OWN = '__own'; // 额度选项里的「单独额度」:新建一个只给本站/本章用的任务槽
function config(raw) {
  const c = raw ? (typeof raw === 'string' ? JSON.parse(raw) : copy(raw)) : {};
  return Object.assign({ schemaVersion: 1, capacity: 6, allowDiscard: true, items: [], chapters: [], grants: [], events: [] }, c);
}
function chapter(c, index) {
  let row = c.chapters.find(v => v.index === index);
  if (!row) { row = { index, hpCost: 0, luckCost: 0, grants: [], eggs: [] }; c.chapters.push(row); }
  return row;
}
function purposeOptions(c, rules) {
  const out = [{ key: '', label: '展示' }];
  if (rules.hp.enabled) out.push({ key: 'HP', label: c.hpName || '生命值' });
  if (rules.luck.enabled) out.push({ key: 'LUCK', label: c.luckName || '幸运值' });
  if (rules.stateEnabled) (rules.attributes || []).filter(row => values.COUNTER.test(row.key || '')).forEach(row => out.push({ key: row.key, label: row.label }));
  return out;
}
function rows(c, index, options) {
  const grants = chapter(c, index).grants || [];
  const keys = options.map(row => row.key);
  return c.items.map(item => {
    const g = grants.find(v => v.itemId === item.id) || {};
    const purpose = item.statKey || item.stat || '';
    return Object.assign({}, item, {
      purposeIndex: Math.max(0, keys.indexOf(purpose)),
      statLabel: (options.find(row => row.key === purpose) || {}).label || (purpose === 'HP' ? c.hpName || '生命值' : c.luckName || '幸运值'),
      effectIndex: !item.wear ? 0 : item.mode === 'ONCE' ? 1 : item.mode === 'HELD' ? 2 : 3,
      timingIndex: g.timing === 'START' ? 1 : g.timing === 'END' ? 2 : 0,
      quantity: g.quantity || 1, effectOptions: purpose && purpose !== 'HP' ? EFFECTS.slice(0, 3) : EFFECTS,
    });
  });
}
function validate(c, rules, policies) {
  const survivalIssue = require('./theme-runtime-survival.js').validate(c, undefined, rules); if (survivalIssue) return survivalIssue; // survival
  for (const key of ['hpName', 'luckName', 'actionName']) if (String(c[key] || '').length > 20) return '数值名称不能超过 20 字';
  const zeroIssue = zeroHp.validate(c, rules, policies); if (zeroIssue) return zeroIssue;
  if (!Number.isInteger(c.capacity) || c.capacity < 1 || c.capacity > 999) return '物品容量请输入 1 到 999 的整数';
  for (const i of c.items) {
    if (!String(i.name || '').trim()) return '请输入物品或勋章名称';
    if (i.slotCost != null && (i.kind !== 'ITEM' || !Number.isInteger(i.slotCost) || i.slotCost < 1 || i.slotCost > 999)) return '每件占格请输入 1 到 999 的整数';
    if (!/^https:\/\/[^\s]+$/.test(String(i.image || ''))) return '请上传物品或勋章图片';
    if ((i.stat || i.statKey) && (!Number.isInteger(Number(i.delta)) || !Number(i.delta) || Math.abs(Number(i.delta)) > 999 || (i.mode === 'REDUCE' && (i.statKey || i.stat !== 'HP' || Number(i.delta) < 0)))) return '请输入非 0 的整数增减值';
    if (i.stat && rules && !rules[i.stat === 'HP' ? 'hp' : 'luck'].enabled) return '请先开启对应的' + (i.stat === 'HP' ? c.hpName || '生命值' : c.luckName || '幸运值');
    if (i.statKey && rules && (!rules.stateEnabled || !(rules.attributes || []).some(row => row.key === i.statKey))) return '请先开启物品对应的自定义值';
  }
  for (const ch of c.chapters) {
    for (const stat of ['hp', 'luck']) if (!Number.isInteger(ch[stat + 'Cost']) || ch[stat + 'Cost'] < 0 || ch[stat + 'Cost'] > 999 || (ch[stat + 'Cost'] > 0 && rules && !rules[stat].enabled)) return '请开启对应数值，并填写 0 到 999 的章节消耗';
    for (const g of ch.grants || []) if (!Number.isInteger(g.quantity) || g.quantity < 1 || g.quantity > 99) return '发放数量请输入 1 到 99 的整数';
    for (const e of ch.eggs || []) {
      if (!e.name.trim() || !e.content.trim()) return '请填写彩蛋名称和内容';
      if (!['TIME', 'STEPS'].includes(e.trigger) || !Number.isInteger(e.threshold) || e.threshold < 1 || e.threshold > 99999) return '请填写彩蛋触发方式与数值';
      for (const r of e.rewards) if (!c.items.some(i => i.id === r.itemId) || !Number.isInteger(r.quantity) || r.quantity < 1 || r.quantity > 99) return '请选择彩蛋奖励并填写数量';
    }
  }
  return '';
}
const data = {
  ...zeroHp.data,
  runtimeEditor: { show: false, kind: '', title: '', index: 0 },
  runtimeBag: { capacity: 6, allowDiscard: true, items: [] }, runtimeEggs: [], runtimeRewardOptions: [],
  runtimePurposes: PURPOSES, runtimeEffects: EFFECTS, runtimeTimings: TIMINGS,
  runtimePurposeKeys: ['', 'HP', 'LUCK'], runtimeValues: [], runtimeValueError: '', valueEditor: { show: false, index: -1, row: null, links: [], error: '' },
  runtimeEditingStat: '',
  valueTriggers: ['每下降 1 点', '每次下降事件', '首次达到阈值'], valueFrequencies: ['每次事件', '本局一次'],
  valueReasonLabels: ['任务费用', '玩法效果', '章节费用', '使用物品', '设施效果'],
  runtimeTriggers: ['时间', '步数'], runtimeHpEnabled: false, runtimeLuckEnabled: false,
  runtimeHpName: '', runtimeLuckName: '', runtimeActionName: '', runtimeHpLabel: '生命值', runtimeLuckLabel: '幸运值', runtimeActionLabel: '行动次数',
  runtimeHpInitialLabel: '精力', runtimeLuckInitialLabel: '幸运',
  runtimeZeroHpPolicies: [], runtimeZeroHpPolicyKeys: [], runtimeZeroHpPolicyIndex: -1,
  runtimeChallengeError: '', runtimeChallengeHint: '',
  runtimeChallengeNodes: [], runtimeChallengeNodeIndex: -1, runtimeAlternativeNodes: [], runtimeZeroChallenge: { restoreTo: 1, limit: 3 },
  runtimeRewardOpen: false, subtitleEditing: false, runtimeBingoIndex: 0, runtimeCompletionIndex: -1,
  storyNodeRoles: {}, storyRoleOptions: [],
  runtimeOverflowOptions: [], runtimeOverflowLabel: '不允许',
  runtimeChapterCostRows: [], runtimeChapterCostOptions: [],
  runtimeChapterRules: { index: 0, completionType: 'ALL', quantity: '', overflowSlot: '', groups: [], extraNodes: [], nodes: [], slots: [] },
  runtimeChapterRation: { enabled: false, actKey: '', rationKey: '', itemIndex: -1, itemLabel: '请选择', quantity: '1', fallback: '0', values: [], valueIndex: 0, items: [], reason: '' },
};
const methods = {
  ...zeroHp.methods,
  _runtimeConfig() { return config(this.data.formData.themeRuntimeConfig); },
  _saveRuntime(c) {
    const raw = this.data.formData.themeRuntimeConfig;
    if (raw) {
      const previous = typeof raw === 'string' ? JSON.parse(raw) : raw;
      for (const [key, fallback] of Object.entries({ allowDiscard: true, grants: [], events: [] })) {
        if (!(key in previous) && JSON.stringify(c[key]) === JSON.stringify(fallback)) delete c[key];
      }
    }
    this.setData({ 'formData.themeRuntimeConfig': JSON.stringify(c) });
    if (this._syncKeysItems) this._syncKeysItems(c);
    if (this._refreshStructureSummary) this._refreshStructureSummary(); // structure
    if (this._refreshThemeOwnership) this._refreshThemeOwnership();
  },
  _runtimePayload() {
    if (!this.data.formData.themeRuntimeConfig) return null;
    const raw = this.data.formData.themeRuntimeConfig;
    const c = typeof raw === 'string' ? JSON.parse(raw) : copy(raw);
    if (Array.isArray(c.chapters)) c.chapters = c.chapters.filter(ch => ch.index < this.data.formData.chapters.length);
    if (Array.isArray(c.items)) c.items.forEach(i => { if (i.stat || i.statKey) i.delta = Number(i.delta); });
    return JSON.stringify(c);
  },
  _prependRuntimeChapter() { const c = this._runtimeConfig(); const added = c.chapters.find(ch => ch.index === this.data.formData.chapters.length); c.chapters.forEach(ch => { ch.index = ch === added ? 0 : ch.index + 1; }); this._saveRuntime(c); if (this._syncStructureChapters) this._syncStructureChapters(); /* structure */ },
  _removeRuntimeChapter(index) { const c = this._runtimeConfig(); c.chapters = c.chapters.filter(ch => ch.index !== index).map(ch => Object.assign(ch, { index: ch.index > index ? ch.index - 1 : ch.index })); this._saveRuntime(c); if (this._syncStructureChapters) this._syncStructureChapters(this.data.formData.chapters.filter((_, i) => i !== index)); /* structure */ },
  _runtimeChapterIndex() { return this.data.popChapterAction == 0 ? this.data.formData.chapters.length : this.data.popChapterIndex; },
  _chapterSavedNodes(index) {
    if (!this.data.editingTopicId) return [];
    const ch = (this.data.formData.chapters || [])[index];
    return ((ch && ch.nodes) || []).map(node => ({ id: Number(node.id), name: node.name || ('节点 ' + node.id) }))
      .filter(node => Number.isSafeInteger(node.id) && node.id > 0);
  },
  _chapterRuleIssue(index, row) {
    if (!row || (!row.completion && !row.exclusiveGroups && !row.extraNodes)) return '';
    const nodes = this._chapterSavedNodes(index), ids = new Set(nodes.map(node => node.id));
    // 复制出的草稿:规则引用同请求新建站点的 client:<key>,首次保存时由服务端换成真实编号
    (((this.data.formData.chapters || [])[index] || {}).nodes || []).forEach(node => { if (!(Number(node.id) > 0) && node.clientNodeKey) ids.add('client:' + node.clientNodeKey); });
    const extra = row.extraNodes || [], groups = row.exclusiveGroups || [];
    const used = new Set();
    for (const item of extra) {
      if (!ids.has(item.nodeId)) return '额外节点须先保存，且必须属于本章';
      if (!/^[a-z][a-z0-9_]{0,31}$/.test(item.slot || '')) return '额外任务槽名称须以小写字母开头，只能使用小写字母、数字或下划线，最多 32 位';
      if (used.has(item.nodeId)) return '同一节点不能重复配置为额外节点';
      used.add(item.nodeId);
    }
    for (const group of groups) {
      if (!Array.isArray(group) || group.length < 2) return '互斥选项每组至少选择 2 个节点';
      for (const id of group) {
        if (!ids.has(id)) return '互斥选项须先保存，且必须属于本章';
        if (used.has(id)) return '节点不能同时属于多个互斥组或额外节点';
        used.add(id);
      }
    }
    // 计入完成的单位：未分组的普通站点各 1，每个互斥组合计 1，额外节点不计（与后端 A08 计数一致）
    const ch = (this.data.formData.chapters || [])[index];
    const count = ((ch && ch.nodes) || []).length - extra.length - groups.reduce((total, group) => total + group.length - 1, 0);
    if (extra.length && count < 1) return '额外节点不能占满本章全部节点，至少保留一个计入完成的节点';
    if (row.completion) {
      if (row.completion.type !== 'ANY_N') return row.completion.type === 'ALL' ? '' : '本章完成要求不支持该方式';
      if (!Number.isInteger(row.completion.quantity) || row.completion.quantity < 1 || row.completion.quantity > count) return '任选数量须在 1 到本章可完成的节点数之间';
      if (row.completion.overflowSlot && !/^[a-z][a-z0-9_]{0,31}$/.test(row.completion.overflowSlot)) return '超量任务槽名称须以小写字母开头，只能使用小写字母、数字或下划线，最多 32 位';
    }
    return '';
  },
  _openChapterRules(index) {
    const c = this._runtimeConfig(), row = c.chapters.find(ch => ch.index === index) || {};
    const nodes = this._chapterSavedNodes(index);
    const slots = [...new Set(c.chapters.flatMap(ch => (ch.extraNodes || []).map(item => item.slot).concat(ch.completion && ch.completion.overflowSlot || [])).filter(Boolean))];
    this.setData({ runtimeChapterRules: {
      index,
      completionType: row.completion && row.completion.type === 'ANY_N' ? 'ANY_N' : 'ALL',
      quantity: row.completion && row.completion.quantity != null ? String(row.completion.quantity) : '',
      overflowSlot: row.completion && row.completion.overflowSlot || '',
      groups: copy(row.exclusiveGroups || []).map(ids => ({ ids, options: nodes.map(node => ({ ...node, selected: ids.includes(node.id) })) })),
      extraNodes: copy(row.extraNodes || []).map(item => ({ ...item, nodeIndex: nodes.findIndex(node => node.id === item.nodeId), nodeName: (nodes.find(node => node.id === item.nodeId) || {}).name || '选择已保存节点' })),
      nodes, slots,
    } });
    this._openChapterRation(index);
    this._openChapterCosts(index);
    this._refreshOverflowOptions();
  },
  /* 章末数值消耗(通用):本章结束时扣的生命/幸运/自定义数值,对应 chapters[].hpCost / luckCost / statCosts。
     运行端在章末结算时照此扣除(ThemeRuntimeEngine.chapterEnd),所以存量非零消耗必须看得见、改得了。 */
  _openChapterCosts(index) {
    const c = this._runtimeConfig(), row = c.chapters.find(ch => ch.index === index) || {};
    const options = purposeOptions(c, initial.parse(this.data.formData.journeyRules)).filter(o => o.key);
    const labelOf = key => (options.find(o => o.key === key) || {}).label;
    const rows = [];
    if (Number(row.hpCost) > 0) rows.push({ key: 'HP', label: labelOf('HP') || (c.hpName || '生命值'), amount: String(row.hpCost), inactive: !labelOf('HP') });
    if (Number(row.luckCost) > 0) rows.push({ key: 'LUCK', label: labelOf('LUCK') || (c.luckName || '幸运值'), amount: String(row.luckCost), inactive: !labelOf('LUCK') });
    (row.statCosts || []).forEach(cost => rows.push({ key: cost.statKey, label: labelOf(cost.statKey) || cost.statKey, amount: String(cost.amount), inactive: !labelOf(cost.statKey) }));
    this.setData({ runtimeChapterCostRows: rows, runtimeChapterCostOptions: options.filter(o => !rows.some(r => r.key === o.key)) });
  },
  addChapterCost(e) {
    const option = this.data.runtimeChapterCostOptions[Number(e.detail.value)];
    if (!option) return;
    const rows = this.data.runtimeChapterCostRows.concat({ key: option.key, label: option.label, amount: '1', inactive: false });
    this.setData({ runtimeChapterCostRows: rows, runtimeChapterCostOptions: this.data.runtimeChapterCostOptions.filter(o => o.key !== option.key) });
  },
  onChapterCostAmount(e) {
    const index = Number(e.currentTarget.dataset.index);
    const rows = copy(this.data.runtimeChapterCostRows);
    if (!rows[index]) return;
    rows[index].amount = e.detail.value;
    this.setData({ runtimeChapterCostRows: rows });
  },
  removeChapterCost(e) {
    const rows = this.data.runtimeChapterCostRows.slice(), removed = rows.splice(Number(e.currentTarget.dataset.index), 1)[0];
    if (!removed) return;
    const options = removed.inactive ? this.data.runtimeChapterCostOptions : this.data.runtimeChapterCostOptions.concat({ key: removed.key, label: removed.label });
    this.setData({ runtimeChapterCostRows: rows, runtimeChapterCostOptions: options });
  },
  _chapterCostsPatch() {
    const patch = { hpCost: 0, luckCost: 0, statCosts: [] };
    for (const row of this.data.runtimeChapterCostRows || []) {
      const amount = Number(row.amount);
      if (!Number.isInteger(amount) || amount < 0 || amount > 999) return { error: '「' + row.label + '」消耗须为 0 到 999 的整数' };
      if (row.key === 'HP') patch.hpCost = amount;
      else if (row.key === 'LUCK') patch.luckCost = amount;
      else if (amount > 0) patch.statCosts.push({ statKey: row.key, amount });
    }
    return { patch };
  },
  /* 章末配给：本章（幕）结束时每队/每人按配置消耗一次物品，没有物品时扣对应数值。
     写入既有 gameplayRuntime.survival.rations（按本章任务的 actKey），运行端按幕结算一次。 */
  _chapterActKey(index) {
    const ids = new Set(this._chapterSavedNodes(index).map(node => node.id));
    const quest = ((this._runtimeConfig().gameplayRuntime || {}).quests || []).find(row => ids.has(Number(row.nodeId)));
    return quest ? quest.actKey : '';
  },
  _openChapterRation(index) {
    const c = this._runtimeConfig(), game = c.gameplayRuntime || {};
    const actKey = this._chapterActKey(index);
    const items = (c.items || []).filter(item => item.kind === 'ITEM' && !item.wear).map(item => ({ id: item.id, name: item.name || '未命名物品' }));
    const row = actKey ? ((game.survival || {}).rations || []).find(r => r.actKey === actKey) : null;
    const itemIndex = row ? items.findIndex(item => item.id === row.itemId) : -1;
    // 不足时扣哪个数值：复用数值域选项（生命 + 已定义的自定义数值），不含幸运与「展示」
    const valueRows = purposeOptions(c, initial.parse(this.data.formData.journeyRules)).filter(row => row.key === 'HP' || values.COUNTER.test(row.key || ''));
    const fallbackKey = row && row.fallback && row.fallback.statKey ? row.fallback.statKey : 'HP';
    const valueIndex = Math.max(0, valueRows.findIndex(v => v.key === fallbackKey));
    const reason = !game.runtimeScope ? '请先选择进行方式' : !actKey ? '请先保存本章节点' : !items.length ? '请先在主题里添加物品' : '';
    this.setData({ runtimeChapterRation: {
      enabled: !!row, actKey, rationKey: row ? row.rationKey : '', itemIndex, itemLabel: itemIndex >= 0 ? items[itemIndex].name : '请选择',
      quantity: String(row ? row.quantity : 1), fallback: String(row ? (row.fallback ? row.fallback.amount : row.fallbackHpCost || 0) : 0), values: valueRows, valueIndex, items, reason,
    } });
  },
  onChapterRationToggle(e) {
    const on = !!e.detail.value, draft = this.data.runtimeChapterRation;
    if (on && draft.reason) { toast(draft.reason); this.setData({ 'runtimeChapterRation.enabled': false }); return; }
    this.setData({ 'runtimeChapterRation.enabled': on });
  },
  onChapterRationItem(e) {
    const index = Number(e.detail.value), item = this.data.runtimeChapterRation.items[index];
    if (item) this.setData({ 'runtimeChapterRation.itemIndex': index, 'runtimeChapterRation.itemLabel': item.name });
  },
  onChapterRationQuantity(e) { this.setData({ 'runtimeChapterRation.quantity': e.detail.value }); },
  onChapterRationFallback(e) { this.setData({ 'runtimeChapterRation.fallback': e.detail.value }); },
  onChapterRationValue(e) { const index = Number(e.detail.value); if (this.data.runtimeChapterRation.values[index]) this.setData({ 'runtimeChapterRation.valueIndex': index }); },
  _chapterRationIssue(draft) {
    if (!draft.enabled) return '';
    if (draft.reason) return draft.reason;
    if (!draft.items[draft.itemIndex]) return '请选择章末配给的物品';
    const quantity = Number(draft.quantity), fallback = Number(draft.fallback);
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > 999) return '配给数量须为 1 到 999 的整数';
    if (!Number.isInteger(fallback) || fallback < 0 || fallback > 999) return '物品不足时减少的数值须为 0 到 999 的整数';
    return '';
  },
  _saveChapterRation() {
    const draft = this.data.runtimeChapterRation;
    if (!draft.actKey) return true;
    const issue = this._chapterRationIssue(draft);
    if (issue) { toast(issue); return false; }
    const c = this._runtimeConfig(), game = c.gameplayRuntime || (c.gameplayRuntime = {});
    const survival = game.survival || (game.survival = { zeroHpPolicy: 'BLOCK_HP_COST', actionLimitPolicy: 'BLOCK_OVERFLOW' });
    const rations = (survival.rations || []).filter(row => row.actKey !== draft.actKey);
    if (draft.enabled) {
      let rationKey = draft.rationKey;
      if (!rationKey) { let n = 1; while (rations.some(row => row.rationKey === 'ration_' + n)) n++; rationKey = 'ration_' + n; }
      const value = (draft.values || [])[draft.valueIndex] || { key: 'HP' };
      const ration = { rationKey, actKey: draft.actKey, itemId: draft.items[draft.itemIndex].id, quantity: Number(draft.quantity) };
      if (value.key === 'HP') ration.fallbackHpCost = Number(draft.fallback); else ration.fallback = { statKey: value.key, amount: Number(draft.fallback) };
      rations.push(ration);
    }
    const before = JSON.stringify(survival.rations || []);
    if (rations.length) survival.rations = rations; else delete survival.rations;
    if (JSON.stringify(survival.rations || []) !== before) this._saveRuntime(c);
    return true;
  },
  _saveChapterCosts(index, patch) {
    const c = this._runtimeConfig(), existing = c.chapters.find(ch => ch.index === index);
    const had = !!existing && (Number(existing.hpCost) > 0 || Number(existing.luckCost) > 0 || (existing.statCosts || []).length > 0);
    if (!had && !patch.hpCost && !patch.luckCost && !patch.statCosts.length) return;
    const row = chapter(c, index);
    row.hpCost = patch.hpCost; row.luckCost = patch.luckCost;
    if (patch.statCosts.length) row.statCosts = patch.statCosts; else delete row.statCosts;
    this._saveRuntime(c);
  },
  // 「全部完成」开关:开 = 全部完成(默认);关 = 至少完成填写的站数
  onChapterCompleteAll(e) {
    const all = !!e.detail.value;
    this.setData({ 'runtimeChapterRules.completionType': all ? 'ALL' : 'ANY_N', 'runtimeChapterRules.overflowSlot': all ? '' : this.data.runtimeChapterRules.overflowSlot });
    this._refreshOverflowOptions();
  },
  onChapterQuantity(e) { this.setData({ 'runtimeChapterRules.quantity': e.detail.value }); },
  /* 超出数量后补做一站要占哪份额度:不允许 / 单独额度 / 与其他站点或章节共用的既有额度。
     额度 = 任务槽(slot):同名即同一份,每个进度(队伍或个人)每份只能用一次 —— 由作者选,不写死成某一个。 */
  onChapterOverflowPick(e) {
    const option = this.data.runtimeOverflowOptions[Number(e.detail.value)];
    if (!option) return;
    const current = this.data.runtimeChapterRules.overflowSlot;
    let slot = option.value;
    if (slot === OWN) {
      const mine = current && (this._allowances().find(a => a.slot === current) || { members: [] }).members.length <= 1;
      slot = mine ? current : this._uniqueSlot('overflow_' + (this.data.runtimeChapterRules.index + 1));
    }
    this.setData({ 'runtimeChapterRules.overflowSlot': slot });
    this._refreshOverflowOptions();
  },
  _refreshOverflowOptions() {
    const draft = this.data.runtimeChapterRules, scope = this._allowanceScope();
    const others = this._allowances().filter(a => !(a.overflowChapters.length === 1 && a.overflowChapters[0] === draft.index && !a.nodes.length));
    const options = [{ label: '不允许', value: '' }, { label: '单独额度（' + scope + '一次）', value: OWN }]
      .concat(others.map(a => ({ label: '与「' + a.members.join('、') + '」共用（' + scope + '共一次）', value: a.slot })));
    const current = draft.overflowSlot;
    const shared = current && others.find(a => a.slot === current);
    this.setData({ runtimeOverflowOptions: options, runtimeOverflowLabel: !current ? '不允许' : shared ? '与「' + shared.members.join('、') + '」共用' : '单独额度（' + scope + '一次）' });
  },
  addChapterExclusiveGroup() {
    if (this.data.runtimeChapterRules.nodes.length < 2) { toast('请先保存本章至少 2 个节点'); return; }
    const groups = this.data.runtimeChapterRules.groups.slice();
    groups.push({ ids: [], options: this.data.runtimeChapterRules.nodes.map(node => ({ ...node, selected: false })) });
    this.setData({ 'runtimeChapterRules.groups': groups });
  },
  toggleChapterExclusiveNode(e) {
    const index = Number(e.currentTarget.dataset.index), id = Number(e.currentTarget.dataset.id);
    const groups = copy(this.data.runtimeChapterRules.groups), group = groups[index];
    if (!group) return;
    if (!group.ids.includes(id) && groups.some((other, i) => i !== index && other.ids.includes(id))) { toast('该节点已在其他互斥组'); return; }
    group.ids = group.ids.includes(id) ? group.ids.filter(value => value !== id) : group.ids.concat(id);
    group.options.forEach(option => { option.selected = group.ids.includes(option.id); });
    this.setData({ 'runtimeChapterRules.groups': groups });
  },
  removeChapterExclusiveGroup(e) {
    const groups = this.data.runtimeChapterRules.groups.slice(); groups.splice(Number(e.currentTarget.dataset.index), 1);
    this.setData({ 'runtimeChapterRules.groups': groups });
  },
  /* 额度(任务槽 slot):extraNodes[].slot 与 completion.overflowSlot 同名即同一份额度,运行端按进度
     (队伍局 = 每队,个人局 = 每人)记「已用」,每份只能用一次(GameplayTaskEngine extraSlots)。
     这里只按已存的槽名归组展示,不改名、不合并 —— 存量关系原样保留。 */
  _allowanceScope(c) {
    const scope = (((c || this._runtimeConfig()).gameplayRuntime) || {}).runtimeScope;
    return scope === 'PLAYER' ? '每人' : scope === 'TEAM' ? '每队' : '每局';
  },
  _allowances(c) {
    c = c || this._runtimeConfig();
    const names = {};
    (this.data.formData.chapters || []).forEach(ch => (ch.nodes || []).forEach(node => {
      if (Number(node.id) > 0) names[String(Number(node.id))] = node.name;
      if (node.clientNodeKey) names['client:' + node.clientNodeKey] = node.name;
    }));
    const map = new Map();
    const at = slot => { if (!map.has(slot)) map.set(slot, { slot, members: [], nodes: [], overflowChapters: [] }); return map.get(slot); };
    (c.chapters || []).slice().sort((x, y) => x.index - y.index).forEach(ch => {
      (ch.extraNodes || []).forEach(item => { const a = at(item.slot); a.nodes.push(String(item.nodeId)); a.members.push(names[String(item.nodeId)] || '已删除节点'); });
      if (ch.completion && ch.completion.overflowSlot) { const a = at(ch.completion.overflowSlot); a.overflowChapters.push(ch.index); a.members.push('第' + (ch.index + 1) + '章补做'); }
    });
    return Array.from(map.values());
  },
  _uniqueSlot(base) {
    const used = new Set(this._allowances().map(a => a.slot).concat(this.data.runtimeChapterRules.overflowSlot || []));
    let slot = base.slice(0, 28), n = 2;
    while (used.has(slot)) slot = base.slice(0, 28) + '_' + (n++);
    return slot;
  },
  // 故事流站点块上的计入方式:计入完成 / 单独额度 / 与既有额度共用(2026-10-12 用户定:额外站点在故事流里标)
  _storyRolePatch(c) {
    c = c || this._runtimeConfig();
    const scope = this._allowanceScope(c), allowances = this._allowances(c), roles = {};
    allowances.forEach(a => a.nodes.forEach(id => {
      roles[id] = a.members.length > 1 ? '额外 · 与' + (a.members.length - 1) + '处共用（' + scope + '共一次）' : '额外 · ' + scope + '一次';
    }));
    const options = [{ label: '计入完成', value: '' }, { label: '额外节点 · 单独额度（' + scope + '一次）', value: OWN }]
      .concat(allowances.map(a => ({ label: '额外节点 · 与「' + a.members.join('、') + '」共用（' + scope + '共一次）', value: a.slot })));
    return { storyNodeRoles: roles, storyRoleOptions: options };
  },
  _storyNodeRoles(c) { return this._storyRolePatch(c).storyNodeRoles; },
  onStoryNodeRole(e) {
    const nodeId = Number(e.currentTarget.dataset.nodeid), option = this.data.storyRoleOptions[Number(e.detail.value)];
    const index = (this.data.formData.chapters || []).findIndex(ch => (ch.nodes || []).some(node => Number(node.id) === nodeId));
    if (!(nodeId > 0) || index < 0) { toast('先保存草稿，再设置额外节点'); return; }
    if (!option) return;
    const c = this._runtimeConfig(), hadExtra = !!(c.chapters.find(ch => ch.index === index) || {}).extraNodes, row = chapter(c, index);
    const current = (row.extraNodes || []).find(item => item.nodeId === nodeId);
    const next = (row.extraNodes || []).filter(item => item.nodeId !== nodeId);
    if (option.value === OWN) {
      const mine = current && (this._allowances(c).find(a => a.slot === current.slot) || { members: [] }).members.length <= 1;
      next.push({ nodeId, slot: mine ? current.slot : this._uniqueSlot('extra_' + nodeId) });
    } else if (option.value) next.push({ nodeId, slot: option.value });
    const completion = row.completion && row.completion.type === 'ANY_N' ? row.completion : null;
    const issue = this._chapterRuleIssue(index, { completion, exclusiveGroups: row.exclusiveGroups || [], extraNodes: next });
    if (issue) { toast(issue); return; }
    if (next.length) row.extraNodes = next;
    else {
      delete row.extraNodes;
      // 已编译过额外标记的章节恢复默认:写显式 ALL 才能清掉(与 _saveChapterRules 同口径)
      if (hadExtra && !row.completion) row.completion = { type: 'ALL' };
    }
    this._saveRuntime(c);
    const rolePatch = this._storyRolePatch(c);
    this.setData({ storyNodeRoles: rolePatch.storyNodeRoles, storyRoleOptions: rolePatch.storyRoleOptions });
  },
  _saveChapterRules() {
    const costs = this._chapterCostsPatch();
    if (costs.error) { toast(costs.error); return false; }
    if (!this._saveChapterRation()) return false;
    this._saveChapterCosts(this.data.runtimeChapterRules.index, costs.patch);
    const draft = this.data.runtimeChapterRules, index = draft.index;
    const completion = draft.completionType === 'ANY_N' ? { type: 'ANY_N', quantity: Number(draft.quantity) } : null;
    if (completion && String(draft.overflowSlot || '').trim()) completion.overflowSlot = String(draft.overflowSlot).trim();
    const exclusiveGroups = draft.groups.map(group => group.ids);
    const extraNodes = draft.extraNodes.map(item => ({ nodeId: item.nodeId, slot: String(item.slot || '').trim() }));
    const issue = this._chapterRuleIssue(index, { completion, exclusiveGroups, extraNodes });
    if (issue) { toast(issue); return false; }
    const c = this._runtimeConfig();
    let row = c.chapters.find(ch => ch.index === index);
    if (!row && !completion && !exclusiveGroups.length && !extraNodes.length) return true;
    // 已有规则的章节恢复默认时显式写 ALL：已发布编译过的 ANY_N/互斥/额外标记需要靠它清除；从未设过规则的章节仍不写（存量不变）
    const hadRules = !!(row && (row.completion || row.exclusiveGroups || row.extraNodes));
    if (!row) row = chapter(c, index);
    if (completion) row.completion = completion; else if (hadRules) row.completion = { type: 'ALL' }; else delete row.completion;
    if (exclusiveGroups.length) row.exclusiveGroups = exclusiveGroups; else delete row.exclusiveGroups;
    if (extraNodes.length) row.extraNodes = extraNodes; else delete row.extraNodes;
    this._saveRuntime(c);
    if (this._applyStructureChapterCompletion) this._applyStructureChapterCompletion(index, completion); // structure
    return true;
  },
  _refreshRuntimeEditor(c) {
    const index = this.data.runtimeEditor.index;
    const ch = chapter(c, index);
    const options = purposeOptions(c, initial.parse(this.data.formData.journeyRules));
    this.setData({ runtimePurposes: options.map(row => row.label), runtimePurposeKeys: options.map(row => row.key),
      runtimeBag: { capacity: c.capacity, allowDiscard: c.allowDiscard, items: rows(c, index, options) },
      runtimeEggs: (ch.eggs || []).map(e => Object.assign({}, e, { triggerIndex: e.trigger === 'STEPS' ? 1 : 0,
        rewards: e.rewards.map(r => Object.assign({}, r, { selectedIndex: c.items.findIndex(i => i.id === r.itemId) })) })),
      runtimeCompletionIndex: c.items.findIndex(i => (c.grants || []).some(g => g.id === 'completion_material' && g.itemId === i.id)),
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
      const issue = validate(c, initial.parse(this.data.formData.journeyRules), this._zeroHpPolicyKeys(c));
      if (issue) { toast(issue); return; }
    }
    this.setData({ 'runtimeEditor.show': false });
  },
  onRuntimeRewardOpen(e) { this.setData({ runtimeRewardOpen: !!e.detail.open }); },
  toggleSubtitleEditing() { this.setData({ subtitleEditing: !this.data.subtitleEditing }); },
  _syncRuntimeStats() {
    const r = initial.parse(this.data.formData.journeyRules);
    const c = this._runtimeConfig();
    const survival = c.gameplayRuntime && c.gameplayRuntime.survival || {};
    const challenge = survival.zeroChallenge || {};
    const alternatives = Array.isArray(challenge.alternativeNodeIds) ? challenge.alternativeNodeIds : [];
    const nodes = this._runtimeSavedNodes();
    const policies = this._zeroHpPolicyKeys(c);
    const policyLabels = { THEME_FAIL: '主题失败', WAIT: '等待一段时间', STEPS: '走够步数', BLOCK_HP_COST: '仅禁止扣该值的任务', BLOCK_PAID: '禁止所有收费动作', CHALLENGE: '归零挑战', LEGACY_END: '旧版结束' };
    const choices = supportMatrix.challengeChoices(this.data.supportMatrix, nodes, challenge.version);
    const primary = choices.primary;
    const primaryNode = nodes.find(node => node.id === challenge.challengeNodeId);
    const sensor = this.data.supportMatrix && primaryNode && supportMatrix.mains(this.data.supportMatrix, supportMatrix.config(primaryNode.template))
      .some(row => row.tools.zeroChallenge === 'CHALLENGE_ONLY' && row.key !== 'typeIn');
    const hpName = String(c.hpName || '').trim(), luckName = String(c.luckName || '').trim(), actionName = String(c.actionName || '').trim();
    const options = purposeOptions(c, r);
    this.setData({ runtimeHpEnabled: !!r.hp.enabled, runtimeLuckEnabled: !!r.luck.enabled,
      runtimeHpMax: r.hp.max, runtimeLuckMax: r.luck.max,
      runtimeHpName: hpName, runtimeLuckName: luckName, runtimeActionName: actionName,
      runtimeHpLabel: hpName || '生命值', runtimeLuckLabel: luckName || '幸运值', runtimeActionLabel: actionName || '行动次数',
      runtimeHpInitialLabel: hpName || '精力', runtimeLuckInitialLabel: luckName || '幸运',
      initialStateOptions: initial.OPTIONS.map(row => Object.assign({}, row, { label: row.type === 'hp' ? hpName || row.label : luckName || row.label })),
      runtimePurposes: options.map(row => row.label), runtimePurposeKeys: options.map(row => row.key),
      runtimeValues: (r.attributes || []).filter(row => values.COUNTER.test(row.key || '')).map(row => Object.assign({}, row, { invalidInitial: row.initial === '' || row.initial == null || !Number.isInteger(Number(row.initial)) || Number(row.initial) < Number(row.min) || Number(row.initial) > Number(row.max) })),
      runtimeActionEnabled: Number.isInteger(c.actionLimit) && c.actionLimit > 0, runtimeActionLimit: c.actionLimit || '',
      runtimeZeroHpOptions: zeroHp.options(policies),
      runtimeZeroHpSummary: zeroHp.summary(c),
      runtimeZeroHpPolicies: policies.map(policy => policyLabels[policy] || policy), runtimeZeroHpPolicyKeys: policies,
      runtimeZeroHpPolicyIndex: policies.indexOf(survival.zeroHpPolicy || 'BLOCK_HP_COST'),
      runtimeChallengeNodes: primary, runtimeChallengeNodeIndex: primary.findIndex(node => node.id === challenge.challengeNodeId),
      runtimeChallengeError: supportMatrix.challengeError(this.data.supportMatrix, nodes, survival.zeroHpPolicy === 'CHALLENGE' ? challenge : null),
      runtimeChallengeHint: sensor ? this.data.supportMatrix.constraints.CHALLENGE_ONLY : '',
      runtimeAlternativeNodes: primary.concat(nodes.filter(node => alternatives.includes(node.id) && !primary.some(row => row.id === node.id)))
        .filter(node => node.id !== challenge.challengeNodeId).map(node => Object.assign({}, node, { selected: alternatives.includes(node.id),
          reason: this.data.supportMatrix ? supportMatrix.validate(this.data.supportMatrix, node.template, Object.assign({}, node.context, { challenge: true })) : '' })),
      runtimeZeroChallenge: { restoreTo: challenge.restoreTo == null ? 1 : challenge.restoreTo, limit: challenge.limit == null ? 3 : challenge.limit },
    });
    if (this._refreshSurvivalSummary) this._refreshSurvivalSummary(c); // survival
    if (this._refreshSupportMatrix) this._refreshSupportMatrix();
    if (this._refreshStructureSummary) this._refreshStructureSummary(); // structure
    if (this._refreshThemeOwnership) this._refreshThemeOwnership();
  },
  _runtimeSavedNodes() {
    if (!this.data.editingTopicId) return [];
    const nodes = [];
    (this.data.formData.chapters || []).forEach(ch => (ch.nodes || []).forEach(node => {
      const id = Number(node.id);
      if (Number.isSafeInteger(id) && id > 0 && !nodes.some(row => row.id === id)) {
        const template = (this._templateDetailsById || {})[node.templateId] || node.templateInfo || node;
        let game = {};
        try { const raw = template.advancedConfigJson || node.advancedConfigJson || '{}'; game = (typeof raw === 'string' ? JSON.parse(raw) : raw) || {}; } catch (_) {}
        const enabled = key => game[key] && game[key].enabled === true;
        nodes.push({ id, name: node.name || ('节点 ' + id), template: Object.assign({}, template, { advancedConfigJson: game }), context: supportMatrix.context(this.data.formData.themeRuntimeConfig, node), physical: ['ballShake', 'shout', 'steps', 'compass', 'quietHold'].some(enabled), question: ['qa', 'sort', 'match', 'classify'].some(enabled) });
      }
    }));
    return nodes;
  },
  onRuntimeName(e) {
    const field = e.currentTarget.dataset.field;
    if (!['hpName', 'luckName', 'actionName'].includes(field)) return;
    const value = String(e.detail.value || '').slice(0, 20);
    const c = this._runtimeConfig(); c[field] = value;
    this._saveRuntime(c); this._syncRuntimeStats();
  },
  editRuntimeStat(e) {
    const stat = e.currentTarget.dataset.stat;
    if (!['hp', 'luck', 'action'].includes(stat)) return;
    this.setData({ runtimeEditingStat: this.data.runtimeEditingStat === stat ? '' : stat });
  },
  onRuntimeActionToggle(e) {
    const c = this._runtimeConfig();
    if (e.detail.value) c.actionLimit = 20; else delete c.actionLimit;
    this._saveRuntime(c); this._syncRuntimeStats();
  },
  onRuntimeActionLimit(e) {
    const c = this._runtimeConfig(); c.actionLimit = Number(e.detail.value);
    this._saveRuntime(c); this._syncRuntimeStats();
  },
  openValueEditor(e) {
    const r = initial.parse(this.data.formData.journeyRules);
    const attributes = (r.attributes || []).filter(row => values.COUNTER.test(row.key || ''));
    const index = e && e.currentTarget && e.currentTarget.dataset.index != null ? Number(e.currentTarget.dataset.index) : -1;
    if (r.attributeBinding && index < 0) { this.openInitialState(); return; }
    const row = index < 0 ? { key: values.nextKey(r.attributes), label: '', min: 0, max: 100, initial: 0, visible: true }
      : copy(attributes[index]);
    if (!row) return;
    this.setData({ valueEditor: { show: true, index, row, definitionReadOnly: !!r.attributeBinding, links: copy((r.statLinks || []).filter(link => link.sourceKey === row.key)), error: '' } });
  },
  closeValueEditor() { this.setData({ 'valueEditor.show': false }); },
  onValueField(e) {
    if (this.data.valueEditor.definitionReadOnly) return;
    const field = e.currentTarget.dataset.field;
    if (!['label', 'min', 'max', 'visible'].includes(field)) return;
    this.setData({ ['valueEditor.row.' + field]: field === 'visible' ? !!e.detail.value : e.detail.value, 'valueEditor.error': '' });
  },
  addValueLink() {
    if (!initial.parse(this.data.formData.journeyRules).hp.enabled) { this.setData({ 'valueEditor.error': '请先启用' + this.data.runtimeHpLabel }); return; }
    const editor = this.data.valueEditor;
    const existing = (initial.parse(this.data.formData.journeyRules).statLinks || []).concat(editor.links);
    let suffix = 1;
    while (existing.some(link => link.id === 'link_' + suffix)) suffix++;
    const links = editor.links.concat({ id: 'link_' + suffix, sourceKey: editor.row.key, targetKey: 'sys.hp', trigger: 'PER_POINT', allowedReasons: ['TASK_COST'], frequency: 'EVENT', multiplier: 1 });
    this.setData({ 'valueEditor.links': links });
  },
  /** 关联只剩一个开关:打开 = 下降时按点扣生命值(默认一条);关掉 = 去掉这个数值的全部关联。 */
  onValueLinkSwitch(e) {
    if (e.detail.value) {
      if (!this.data.valueEditor.links.length) this.addValueLink();
      if (!this.data.valueEditor.links.length) this.setData({ 'valueEditor.links': [] }); // 没开成:开关弹回
    }
    else this.setData({ 'valueEditor.links': [], 'valueEditor.error': '' });
  },
  saveValueEditor() {
    const editor = this.data.valueEditor, r = initial.parse(this.data.formData.journeyRules);
    const attributes = (r.attributes || []).slice();
    const current = attributes.findIndex(row => row.key === editor.row.key);
    try {
      let row;
      if (r.attributeBinding) {
        if (current < 0 || !r.attributeBinding.enabledKeys.includes(editor.row.key)) throw new Error('请先在初始状态中启用注册属性');
        row = attributes[current];
        if (['key','label','min','max','initial','visible'].some(field => row[field] !== editor.row[field])) throw new Error('属性定义已变化，请重新读取后编辑关联');
      } else {
        const min = Number(editor.row.min); row = values.attribute(Object.assign({}, editor.row, { initial: min }));
        row.initial = current < 0 ? editor.row.initial : attributes[current].initial;
        if (current < 0) attributes.push(row); else attributes[current] = row;
      }
      if (attributes.length > 32) throw new Error('初始属性最多配置 32 项');
      const links = (r.statLinks || []).filter(link => link.sourceKey !== row.key).concat(editor.links);
      r.statLinks = values.links(links, attributes, r.hp.enabled, this.data.runtimeHpLabel);
      r.attributes = attributes; r.stateEnabled = true;
      this.setData({ 'formData.journeyRules': initial.preserveChanges(this.data.formData.journeyRules, r), 'valueEditor.show': false, runtimeValueError: '' });
      this._syncRuntimeStats();
    } catch (error) { this.setData({ 'valueEditor.error': error.message }); }
  },
  removeValue(e) {
    const r = initial.parse(this.data.formData.journeyRules), c = this._runtimeConfig();
    if (r.attributeBinding) { this.setData({ runtimeValueError: '请在初始状态中调整启用属性；定义由注册表管理' }); return; }
    const key = e && e.currentTarget && e.currentTarget.dataset.key || this.data.valueEditor.row.key;
    const refs = values.references(key, this.data.formData, c, r);
    if (refs.length) {
      const error = '请先移除引用：' + refs.join('、');
      if (this.data.valueEditor.show) this.setData({ 'valueEditor.error': error });
      else { this.setData({ runtimeValueError: error }); toast('请先移除引用，位置已列在值域中'); }
      this._syncRuntimeStats();
      return;
    }
    r.attributes = (r.attributes || []).filter(row => row.key !== key);
    r.stateEnabled = r.hp.enabled || r.luck.enabled || r.attributes.length > 0;
    this.setData({ 'formData.journeyRules': initial.preserveChanges(this.data.formData.journeyRules, r), 'valueEditor.show': false, runtimeValueError: '' });
    this._syncRuntimeStats();
  },
  onValueEnabled(e) { if (!e.detail.value) this.removeValue(e); },
  /** 生命/幸运上限归主题数值域；开局值超出时在发布前检查里拦截，不在这里改开局值。 */
  onRuntimeStatMax(e) {
    const r = initial.parse(this.data.formData.journeyRules), stat = e.currentTarget.dataset.stat, max = Number(e.detail.value);
    if (!['hp', 'luck'].includes(stat) || !Number.isInteger(max)) return;
    r[stat].max = max;
    this.setData({ 'formData.journeyRules': initial.preserveChanges(this.data.formData.journeyRules, r),
      runtimeValueError: Number(r[stat].init) > max ? '第一章开局值超过上限，请调整初始状态' : '' });
    this._syncRuntimeStats();
  },
  onRuntimeStatToggle(e) {
    const r = initial.parse(this.data.formData.journeyRules);
    const stat = e.currentTarget.dataset.stat;
    if (!e.detail.value) {
      const refs = values.references(stat === 'hp' ? 'sys.hp' : 'sys.luck', this.data.formData, this._runtimeConfig(), r);
      if (refs.length) { this.setData({ runtimeValueError: '请先移除引用：' + refs.join('、') }); toast('请先移除引用，位置已列在值域中'); this._syncRuntimeStats(); return; }
    }
    r[stat].enabled = !!e.detail.value;
    r.stateEnabled = r.hp.enabled || r.luck.enabled || (r.attributes || []).length > 0;
    if (r.hp.enabled || r.luck.enabled) r.checkEnabled = true;
    try { this.setData({ 'formData.journeyRules': initial.serializeChanges(this.data.formData.journeyRules, r) }); this._saveRuntime(this._runtimeConfig()); this._syncRuntimeStats(); }
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
      delete item.stat; delete item.statKey; delete item.delta; delete item.wear; delete item.mode;
      const key = this.data.runtimePurposeKeys[Number(v)];
      if (key) { if (key.startsWith('counter.')) item.statKey = key; else item.stat = key; item.delta = 1; item.wear = false; }
    } else if (d.field === 'effect') {
      if (item.statKey && Number(v) === 3) { toast('自定义值不能用于减耗'); return; }
      item.wear = Number(v) > 0; delete item.mode;
      if (item.wear) item.mode = ['', 'ONCE', 'HELD', 'REDUCE'][Number(v)];
    } else if (d.field === 'timing' || d.field === 'quantity') {
      const ch = chapter(c, this.data.runtimeEditor.index);
      if (!ch.grants) ch.grants = [];
      let g = ch.grants.find(x => x.itemId === item.id);
      if (!g) { g = { itemId: item.id, quantity: 1, timing: 'START' }; ch.grants.push(g); }
      if (d.field === 'quantity') g.quantity = Number(v);
      else if (Number(v)) g.timing = ['', 'START', 'END'][Number(v)];
      else ch.grants = ch.grants.filter(x => x !== g);
    } else if (d.field === 'slotCost') {
      if (item.kind !== 'ITEM') return;
      item.slotCost = Number(v);
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
  onRuntimeCompletionItem(e) {
    const c = this._runtimeConfig(), id = c.items[Number(e.detail.value)].id;
    c.grants = c.grants.filter(g => g.id !== 'completion_material');
    c.grants.push({ id: 'completion_material', source: 'ENDING', itemId: id, quantity: 1 });
    this._saveRuntime(c); this.setData({ runtimeCompletionIndex: Number(e.detail.value) });
  },
  clearRuntimeCompletionItem() { const c = this._runtimeConfig(); c.grants = c.grants.filter(g => g.id !== 'completion_material'); this._saveRuntime(c); this.setData({ runtimeCompletionIndex: -1 }); },
  _zeroHpPolicyKeys(c) {
    const nodes = (this.data.formData.chapters || []).flatMap(chapter => chapter.nodes || []);
    return supportMatrix.zeroHpPolicies(this.data.supportMatrix, c, nodes);
  },
  _validateRuntimeConfig() {
    const endingIssue = this._nodePlanEndingIssues && this._nodePlanEndingIssues()[0];
    if (endingIssue) return endingIssue.label;
    const c = this._runtimeConfig();
    if (c.actionLimit != null && (!Number.isInteger(c.actionLimit) || c.actionLimit < 1 || c.actionLimit > 99999)) return '行动次数上限须为 1 至 99999 的整数';
    const ranges = initial.parse(this.data.formData.journeyRules);
    for (const stat of ['hp', 'luck']) if (ranges[stat].enabled && Number(ranges[stat].init) > Number(ranges[stat].max))
      return '第一章开局' + (stat === 'hp' ? (c.hpName || '生命值') : (c.luckName || '幸运值')) + '超过主题上限，请调整初始状态';
    c.chapters = c.chapters.filter(ch => ch.index < this.data.formData.chapters.length);
    const issue = this.data.formData.themeRuntimeConfig ? validate(c, initial.parse(this.data.formData.journeyRules), this._zeroHpPolicyKeys(c)) : '';
    if (issue) return issue;
    for (const row of c.chapters) {
      const chapterIssue = this._chapterRuleIssue(row.index, row);
      if (chapterIssue) return chapterIssue;
    }
    try { initial.serialize(this.data.formData.journeyRules, c); } catch (error) { return error.message; }
    const survival = c.gameplayRuntime && c.gameplayRuntime.survival;
    if (survival && survival.zeroHpPolicy === 'CHALLENGE') {
      const nodes = this._runtimeSavedNodes(), challenge = survival.zeroChallenge;
      const ids = [challenge.challengeNodeId].concat(challenge.alternativeNodeIds);
      if (ids.some(id => !nodes.some(node => node.id === id))) return '请先保存主题和挑战节点，再选择真实节点';
      const matrixError = supportMatrix.challengeError(this.data.supportMatrix, nodes, challenge);
      if (matrixError) return matrixError;
      if (!this.data.supportMatrix && nodes.find(node => node.id === challenge.challengeNodeId).physical && !challenge.alternativeNodeIds.some(id => nodes.find(node => node.id === id).question)) return '体能或传感器主挑战须配题目类备选挑战';
    }
    return '';
  },
};
module.exports = { data, methods, config, validate, rows, purposeOptions };
