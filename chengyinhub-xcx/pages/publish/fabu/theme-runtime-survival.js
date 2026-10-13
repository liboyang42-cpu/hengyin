// 兼容旧恢复/配给/设施配置；新作者路径归属模板效果与章节。
const toast = require('../../../utils/toast.js');
const initial = require('../utils/publish/story-initial-state.js');
const clone = value => JSON.parse(JSON.stringify(value));
const CARD_FIELDS = ['scene', 'ending', 'hp', 'luck', 'actions', 'medals', 'items', 'story', 'rescue'];
const TARGETS = [{ value: 'OWNER', label: '本队 / 本人' }, { value: 'PARTICIPANTS', label: '选择参与队伍' }, { value: 'WORLD', label: '全场队伍' }];
const RECOVERY_KINDS = [{ value: 'REST', label: '休整：增加数值' }, { value: 'RESCUE', label: '救助：恢复至下限' }];
const SCOPES = [{ value: 'OWNER', label: '每队 / 每人' }, { value: 'WORLD', label: '全场共用' }];
const OPS = [{ value: 'EQ', label: '等于' }, { value: 'NE', label: '不等于' }, { value: 'GT', label: '大于' }, { value: 'GTE', label: '至少' }, { value: 'LT', label: '小于' }, { value: 'LTE', label: '至多' }, { value: 'ITEM_HELD', label: '持有物品' }, { value: 'ITEM_ACQUIRED', label: '曾获得物品' }, { value: 'NODE_COMPLETED', label: '已完成站点' }];
const integer = (v, min, max) => Number.isInteger(v) && v >= min && v <= max;
function key(rows, field, prefix) { let n = 1; while (rows.some(row => row[field] === prefix + n)) n++; return prefix + n; }
function validate(c, nodes, rules) {
  const game = c.gameplayRuntime || {}, survival = game.survival || {};
  if (Object.prototype.hasOwnProperty.call(survival, 'consumeOnNoEffect') && typeof survival.consumeOnNoEffect !== 'boolean') return '无效果消耗策略请选择开启或关闭';
  const acts = new Set((game.acts || []).map(row => row.actKey));
  if (rules && (!rules.stateEnabled || !rules.hp.enabled) && ((survival.recoveries || []).length || (survival.rations || []).some(r => r.fallbackHpCost > 0) || (game.facilities || []).some(f => f.hpDelta))) return '请先开启' + (c.hpName || '生命值') + '，再配置恢复或数值消耗';
  const items = new Set((c.items || []).filter(row => row.kind === 'ITEM').map(row => row.id));
  if ((survival.recoveries || []).length > 20) return '休整与救助合计最多 20 项';
  if ((survival.rations || []).length > 200) return '配给最多 200 项';
  for (const [field, id] of [['recoveries', 'recoveryKey'], ['rations', 'rationKey']]) {
    const rows = survival[field] || [];
    if (new Set(rows.map(row => row[id])).size !== rows.length || rows.some(row => !/^[a-z][a-z0-9_]{0,31}$/.test(row[id] || ''))) return '恢复或配给记录重复，请重新添加';
  }
  for (const row of survival.recoveries || []) {
    if (!['REST', 'RESCUE'].includes(row.kind) || !integer(row.limit, 1, 99)) return '恢复次数须为 1 到 99 的整数';
    if (!integer(row[row.kind === 'REST' ? 'hpDelta' : 'hpFloor'], 1, 999) || row[row.kind === 'REST' ? 'hpFloor' : 'hpDelta'] != null) return '休整增加值或救助恢复下限须为 1 到 999 的整数';
    if ((row.actKeys || []).some(k => !acts.has(k)) || new Set(row.actKeys || []).size !== (row.actKeys || []).length) return '引用的幕已删除或重复，请重新选择';
  }
  for (const row of survival.rations || []) {
    if (!acts.has(row.actKey)) return '引用的幕已删除，请重新选择';
    if (!items.has(row.itemId)) return '请选择主题中的配给物品';
    if (!integer(row.quantity, 1, 999) || (row.fallbackHpCost != null && !integer(row.fallbackHpCost, 0, 999))) return '配给数量须为 1 到 999，物品不足时消耗须为 0 到 999 的整数';
    if (row.fallback) {
      if (!integer(row.fallback.amount, 0, 999)) return '物品不足时减少的数值须为 0 到 999 的整数';
      if (rules && !(rules.attributes || []).some(attr => attr.key === row.fallback.statKey)) return '章末配给扣减的数值已不存在，请重新选择';
    }
  }
  if ((game.facilities || []).length > 100) return '设施最多 100 项';
  if (c.replayEnabled === true && game.runtimeScope === 'TEAM') return '队伍玩法不支持个人重玩，请关闭再玩一次';
  for (const row of game.facilities || []) {
    if (!acts.has(row.actKey)) return '引用的幕已删除，请重新选择';
    if (!integer(row.limit, 1, 99)) return '设施次数须为 1 到 99 的整数';
    if (!['OWNER', 'WORLD'].includes(row.scope) || (row.scope === 'WORLD' && game.worldMode !== 'WORLD_SHARED')) return '全场共用次数仅适用于公共世界';
    if (!TARGETS.some(option => option.value === row.targetScope)) return '请选择设施作用对象';
    if (row.targetScope !== 'OWNER' && game.worldMode !== 'WORLD_SHARED') return '多人设施仅适用于公共世界';
    if (row.targetScope === 'PARTICIPANTS' && (game.quests || []).some(q => q.facilityKey === row.facilityKey)) return '参与队伍设施不能绑定单队任务';
    if (row.hpDelta != null && !integer(row.hpDelta, -999, 999)) return '设施增减值须为负 999 到 999 的整数';
    if (row.arrivalLocationKey && !/^[a-z][a-z0-9_]{0,31}$/.test(row.arrivalLocationKey)) return '请选择有效的到场站点';
    if (row.arrivalLocationKey && game.runtimeScope === 'PLAYER') return '个人玩法不支持工作人员到场核验';
    if (row.operationNodeId != null && (!integer(row.operationNodeId, 1, Number.MAX_SAFE_INTEGER) || (nodes && !nodes.some(n => n.value === row.operationNodeId)))) return '请先保存主题与站点，再选择设施操作站点';
    if ((row.when || []).length > 30) return '设施前置条件最多 30 项';
    for (const condition of row.when || []) {
      if (['ITEM_HELD', 'ITEM_ACQUIRED'].includes(condition.op)) {
        if (!(c.items || []).some(i => i.id === condition.itemId) || !integer(condition.quantity, 1, 999)) return '请选择前置物品，数量须为 1 到 999 的整数';
      } else if (condition.op === 'NODE_COMPLETED') {
        if (!integer(condition.nodeId, 1, Number.MAX_SAFE_INTEGER) || (nodes && !nodes.some(n => n.value === condition.nodeId))) return '请先保存主题与前置站点，再选择';
      } else if (!OPS.slice(0, 6).some(op => op.value === condition.op) || !/^(sys\.(hp|luck|actions)|(clue|relation|counter)\.[a-z][a-z0-9_]{0,47})$/.test(condition.var || '') || !integer(condition.value, -999999, 999999)) return '请选择前置数值，比较值须为负 999999 到 999999 的整数';
    }
  }
  if (c.card) {
    if (c.card.cover && !/^https:\/\/[^\s]{1,492}$/.test(c.card.cover)) return '请上传有效的结果卡封面';
    if ((c.card.fields || []).some(field => !CARD_FIELDS.includes(field))) return '请选择支持的结果卡内容';
  }
  return '';
}
function apply(base, draft, dirty) {
  const out = clone(base);
  for (const field of ['recoveries', 'rations']) if (dirty[field]) {
    const rows = draft.gameplayRuntime.survival[field];
    if (rows.length) {
      out.gameplayRuntime = out.gameplayRuntime || { runtimeScope: 'PLAYER' };
      out.gameplayRuntime.survival = out.gameplayRuntime.survival || {};
      out.gameplayRuntime.survival[field] = clone(rows);
    } else if (out.gameplayRuntime && out.gameplayRuntime.survival) delete out.gameplayRuntime.survival[field];
  }
  if (dirty.facilities) out.gameplayRuntime.facilities = clone(draft.gameplayRuntime.facilities);
  if (dirty.replayEnabled) out.replayEnabled = draft.replayEnabled;
  if (dirty.card) { if (draft.card) out.card = clone(draft.card); else delete out.card; }
  return out;
}
function copyReferences(c, notices) {
  for (const [i, facility] of ((c.gameplayRuntime || {}).facilities || []).entries()) {
    let changed = false;
    if (facility.operationNodeId != null) { delete facility.operationNodeId; changed = true; }
    const when = (facility.when || []).filter(row => row.op !== 'NODE_COMPLETED');
    if (when.length !== (facility.when || []).length) { facility.when = when; changed = true; }
    if (changed) notices.push('设施' + (i + 1) + '操作与前置站点');
  }
}
// 删除幕后保留规则内容；恢复的空幕集合代表全幕，不能自动清空适用范围。
function pruneActRefs(runtime, removedActKeys) {
  const removed = new Set(removedActKeys || []), game = runtime.gameplayRuntime || {}, notices = [];
  (game.facilities || []).forEach((row, i) => { if (removed.has(row.actKey)) { delete row.actKey; notices.push('设施' + (i + 1) + '：引用的幕已删除，请重新选择'); } });
  ((game.survival || {}).rations || []).forEach((row, i) => { if (removed.has(row.actKey)) { delete row.actKey; notices.push('配给' + (i + 1) + '：引用的幕已删除，请重新选择'); } });
  ((game.survival || {}).recoveries || []).forEach((row, i) => { if ((row.actKeys || []).some(k => removed.has(k))) notices.push((row.kind === 'REST' ? '休整' : '救助') + (i + 1) + '：引用的幕已删除，请重新选择'); });
  return notices;
}
const data = { survivalEditor: { show: false, section: '', kind: '', index: -1, row: null, error: '' }, themeReplayEnabled: false, themeReplayError: '', survivalActCheckedCount: 0, survivalSummary: { recoveryText: '未配置', facilityText: '未配置', cardText: '默认' }, survivalRecoveryKinds: RECOVERY_KINDS, survivalDraft: {}, survivalRecoveries: [], survivalRations: [], survivalFacilities: [], survivalActs: [], survivalItems: [], survivalNodes: [], survivalLocations: [], survivalStates: [], survivalSelected: {}, survivalConditionRows: [], survivalActChecks: [], survivalMaterialItems: [], survivalCardFields: [], survivalTargets: TARGETS, survivalScopes: SCOPES, survivalOps: OPS };
const methods = {
  _pruneSurvivalActRefs: pruneActRefs,
  _refreshSurvivalSummary(c = this._runtimeConfig()) {
    const game = c.gameplayRuntime || {}, rows = (game.survival || {}).recoveries || [], rations = (game.survival || {}).rations || [], facilities = game.facilities || [];
    this.setData({ themeReplayEnabled: c.replayEnabled === true, survivalSummary: { recoveryText: rows.length || rations.length ? '休整 ' + rows.filter(r => r.kind === 'REST').length + ' · 救助 ' + rows.filter(r => r.kind === 'RESCUE').length + ' · 配给 ' + rations.length : '未配置', facilityText: facilities.length ? '独立 ' + facilities.filter(f => !f.facilityKey.startsWith('tpl_node_')).length + ' · 站点生成 ' + facilities.filter(f => f.facilityKey.startsWith('tpl_node_')).length : '未配置', cardText: c.card ? '已自定义' : '默认' } });
  },
  openSurvivalEditor(e) {
    this._survivalDraft = clone(this._runtimeConfig()); this._survivalDirty = {};
    this._survivalDraft.gameplayRuntime = this._survivalDraft.gameplayRuntime || {};
    this._survivalDraft.gameplayRuntime.survival = this._survivalDraft.gameplayRuntime.survival || {};
    const requested = e && e.currentTarget.dataset.section, section = ['recovery', 'facility', 'card'].includes(requested) ? requested : '';
    this.setData({ survivalEditor: { show: true, section, kind: '', index: -1, row: null, error: '' } }); this._refreshSurvival();
    if (section === 'card') this.editSurvivalRow({ currentTarget: { dataset: { kind: 'card' } } });
  },
  closeSurvivalEditor() { this._survivalDraft = null; this.setData({ survivalEditor: { show: false, section: '', kind: '', index: -1, row: null, error: '' } }); },
  _refreshSurvival() {
    const c = this._survivalDraft, game = c.gameplayRuntime, survival = game.survival;
    const chapters = this.data.formData.chapters || [], allNodes = chapters.flatMap(ch => ch.nodes || []);
    const nodes = this.data.editingTopicId ? allNodes.filter(n => Number(n.id) > 0).map(n => ({ value: Number(n.id), label: n.name || n.templateName || '未命名站点' })) : [];
    const acts = (game.acts || []).map((act, i) => { const q = (game.quests || []).find(q => q.actKey === act.actKey); const chapter = chapters.find(ch => (ch.nodes || []).some(n => q && Number(n.id) === q.nodeId)); return { value: act.actKey, label: chapter && chapter.name || '第' + (i + 1) + '幕' }; });
    const items = (c.items || []).map(i => ({ value: i.id, label: i.name || '未命名物品', kind: i.kind }));
    const rules = initial.parse(this.data.formData.journeyRules);
    const states = [{ value: 'sys.hp', label: c.hpName || '生命值' }, { value: 'sys.luck', label: c.luckName || '幸运值' }, { value: 'sys.actions', label: c.actionName || '行动次数' }].concat((rules.attributes || []).map(row => ({ value: row.key, label: row.label })));
    const locations = [];
    (game.quests || []).forEach(q => { if (q.locationKey && !locations.some(row => row.value === q.locationKey)) locations.push({ value: q.locationKey, label: (nodes.find(n => n.value === q.nodeId) || {}).label || '站点' + (locations.length + 1) }); });
    (game.facilities || []).forEach(f => { if (f.arrivalLocationKey && !locations.some(l => l.value === f.arrivalLocationKey)) locations.push({ value: f.arrivalLocationKey, label: '已有到场站点' }); });
    const cardLabels = ['场景', '结局', c.hpName || '生命值', c.luckName || '幸运值', c.actionName || '行动次数', '勋章', '物品', '故事', '救助记录'];
    this.setData({ survivalDraft: clone(c), survivalActs: acts, survivalItems: items, survivalNodes: [{ value: '', label: '不绑定操作站点' }].concat(nodes), survivalLocations: [{ value: '', label: '不要求工作人员核验' }].concat(locations), survivalStates: states,
      survivalRecoveries: (survival.recoveries || []).map((r, i) => ({ label: (r.kind === 'REST' ? '休整' : '救助') + ' ' + (i + 1), summary: (r.kind === 'REST' ? '增加 ' + r.hpDelta : '恢复到 ' + r.hpFloor) + ' · 最多 ' + r.limit + ' 次' })),
      survivalRations: (survival.rations || []).map(r => ({ label: (items.find(i => i.value === r.itemId) || {}).label || '请选择物品', summary: ((acts.find(a => a.value === r.actKey) || {}).label || '引用的幕已删除，请重新选择') + ' · ' + r.quantity + ' 件' })),
      survivalFacilities: (game.facilities || []).map((f, i) => ({ label: (nodes.find(n => n.value === f.operationNodeId) || {}).label || '设施 ' + (i + 1), generated: f.facilityKey.startsWith('tpl_node_'), sourceLabel: f.facilityKey.startsWith('tpl_node_') ? '由站点「' + ((nodes.find(n => n.value === Number(f.facilityKey.slice(9))) || {}).label || '未命名站点') + '」的玩法工具生成' : '', summary: (TARGETS.find(t => t.value === f.targetScope) || {}).label || '请选择作用对象' })),
      survivalMaterialItems: items.filter(item => item.kind === 'ITEM'),
      survivalCardFields: CARD_FIELDS.map((value, i) => ({ value, label: cardLabels[i], checked: (c.card && c.card.fields || []).includes(value) })) });
  },
  editSurvivalRow(e) {
    const { kind, index } = e.currentTarget.dataset; const c = this._survivalDraft;
    const list = kind === 'facilities' ? c.gameplayRuntime.facilities || [] : c.gameplayRuntime.survival[kind] || [];
    const i = index == null ? -1 : Number(index);
    if (kind !== 'card' && i < 0 && !this.data.survivalActs.length) { this.setData({ 'survivalEditor.error': '请先配置幕与站点，再添加恢复或配给' }); return; }
    if (kind === 'facilities' && i >= 0 && list[i].facilityKey.startsWith('tpl_node_')) { this.setData({ 'survivalEditor.error': '请在站点玩法工具中编辑此设施' }); return; }
    let row = kind === 'card' ? clone(c.card || {}) : i >= 0 ? clone(list[i]) : kind === 'recoveries' ? { recoveryKey: key(list, 'recoveryKey', 'recovery_'), kind: 'REST', limit: 1, hpDelta: 1 } : kind === 'facilities' ? { facilityKey: key(list, 'facilityKey', 'facility_'), settlementSlot: key(list.concat((c.gameplayRuntime.quests || [])), 'settlementSlot', 'facility_slot_'), actKey: this.data.survivalActs[0].value, scope: 'OWNER', targetScope: 'OWNER', limit: 1 } : { rationKey: key(list, 'rationKey', 'ration_'), actKey: this.data.survivalActs[0].value, itemId: '', quantity: 1, fallbackHpCost: 0 };
    this.setData({ survivalEditor: { show: true, section: this.data.survivalEditor.section, kind, index: i, row, customized: kind === 'card' && !!c.card, error: '' } }); this._setSurvivalRow(row);
  },
  backSurvivalList() { this.setData({ survivalEditor: { show: true, section: this.data.survivalEditor.section, kind: '', index: -1, row: null, error: '' } }); },
  _setSurvivalRow(row) {
    const selected = {};
    for (const [field, options] of Object.entries({ actKey: 'survivalActs', itemId: 'survivalMaterialItems', targetScope: 'survivalTargets', scope: 'survivalScopes', arrivalLocationKey: 'survivalLocations', operationNodeId: 'survivalNodes' })) {
      const index = this.data[options].findIndex(option => option.value === (row[field] == null && ['arrivalLocationKey', 'operationNodeId'].includes(field) ? '' : row[field])); selected[field] = { index, label: index < 0 ? (field === 'actKey' && this.data.survivalEditor.index >= 0 ? '引用的幕已删除，请重新选择' : '请选择') : this.data[options][index].label };
    }
    this.setData({ 'survivalEditor.row': row, 'survivalEditor.error': '', survivalSelected: selected, survivalActCheckedCount: (row.actKeys || []).length,
      survivalActChecks: this.data.survivalActs.map(act => Object.assign({}, act, { checked: (row.actKeys || []).includes(act.value), missing: false })).concat((row.actKeys || []).filter(k => !this.data.survivalActs.some(a => a.value === k)).map(k => ({ value: k, label: '已删除的幕 · 点击移除后重新选择', checked: true, missing: true }))),
      survivalCardFields: this.data.survivalCardFields.map(field => Object.assign({}, field, { checked: (row.fields || []).includes(field.value) })),
      survivalConditionRows: (row.when || []).map(condition => Object.assign({}, condition, {
        opIndex: OPS.findIndex(op => op.value === condition.op), opLabel: (OPS.find(op => op.value === condition.op) || {}).label || '请选择',
        stateLabel: (this.data.survivalStates.find(s => s.value === condition.var) || {}).label || '请选择数值',
        itemLabel: (this.data.survivalItems.find(i => i.value === condition.itemId) || {}).label || '请选择物品',
        nodeLabel: (this.data.survivalNodes.find(n => n.value === condition.nodeId) || {}).label || '请选择已保存站点' })) });
  },
  inputSurvivalField(e) {
    const { field } = e.currentTarget.dataset; const row = clone(this.data.survivalEditor.row);
    if (field === 'hpDelta' && e.detail.value === '') delete row[field]; else row[field] = Number(e.detail.value); this._setSurvivalRow(row);
  },
  pickSurvivalField(e) {
    const { field, options } = e.currentTarget.dataset, value = e.detail.value;
    const row = clone(this.data.survivalEditor.row);
    const option = this.data[options].find((o, i) => o.value === value || i === Number(value));
    if (!option) return;
    if (option.value === '' && ['arrivalLocationKey', 'operationNodeId'].includes(field)) delete row[field]; else row[field] = option.value; this._setSurvivalRow(row);
  },
  pickRecoveryKind(e) {
    const row = clone(this.data.survivalEditor.row); row.kind = e.detail.value === 'RESCUE' || Number(e.detail.value) === 1 ? 'RESCUE' : 'REST';
    delete row.hpDelta; delete row.hpFloor; row[row.kind === 'REST' ? 'hpDelta' : 'hpFloor'] = 1;
    this._setSurvivalRow(row);
  },
  toggleRecoveryAct(e) {
    const value = e.currentTarget.dataset.value, row = clone(this.data.survivalEditor.row), keys = row.actKeys || [];
    row.actKeys = keys.includes(value) ? keys.filter(k => k !== value) : keys.concat(value); if (!row.actKeys.length) delete row.actKeys;
    this._setSurvivalRow(row);
  },
  clearSurvivalField(e) { const row = clone(this.data.survivalEditor.row); delete row[e.currentTarget.dataset.field]; this._setSurvivalRow(row); },
  addSurvivalCondition() {
    const row = clone(this.data.survivalEditor.row); row.when = (row.when || []).concat({ op: 'GTE', var: 'sys.hp', value: 1 }); this._setSurvivalRow(row);
  },
  editSurvivalCondition(e) {
    const { index, field, options } = e.currentTarget.dataset, row = clone(this.data.survivalEditor.row), old = row.when[Number(index)];
    if (field === 'remove') row.when.splice(Number(index), 1);
    else {
      let value = e.detail.value;
      if (options) { const option = this.data[options].find((o, i) => o.value === value || i === Number(value)); if (!option) return; value = option.value; }
      else value = Number(value);
      if (field === 'op') row.when[Number(index)] = value === 'NODE_COMPLETED' ? { op: value, nodeId: 0 } : ['ITEM_HELD', 'ITEM_ACQUIRED'].includes(value) ? { op: value, itemId: '', quantity: 1 } : { op: value, var: old.var || 'sys.hp', value: old.value || 0 };
      else old[field] = value;
    }
    this._setSurvivalRow(row);
  },
  toggleSurvivalCardField(e) {
    const field = e.currentTarget.dataset.value, row = clone(this.data.survivalEditor.row), fields = row.fields || [];
    row.fields = fields.includes(field) ? fields.filter(f => f !== field) : fields.concat(field); this._setSurvivalRow(row);
  },
  toggleSurvivalCardUnlocks(e) { this.setData({ 'survivalEditor.row.showUnlocks': e.detail.value === true }); },
  uploadSurvivalCover() { getApp().chooseImage(res => { if (this.data.survivalEditor.show && this.data.survivalEditor.kind === 'card' && res[0]) this.setData({ 'survivalEditor.row.cover': res[0] }); }, 1, { crop: true, cropScale: '1:1' }); },
  toggleThemeReplay(e) {
    const c = this._runtimeConfig(), value = e.detail.value === true;
    if (value && (c.gameplayRuntime || {}).runtimeScope === 'TEAM') { const error = '队伍玩法不支持个人重玩，请关闭再玩一次'; this.setData({ themeReplayError: error, themeReplayEnabled: c.replayEnabled === true }); toast.error(error); return false; }
    c.replayEnabled = value; this._saveRuntime(c);
    if (this._survivalDraft) { this._survivalDraft.replayEnabled = value; this._refreshSurvival(); }
    this.setData({ themeReplayEnabled: value, themeReplayError: '' }); return true;
  },
  toggleSurvivalReplay(e) { this._survivalDraft.replayEnabled = e.detail.value === true; this._survivalDirty.replayEnabled = true; this._refreshSurvival(); },
  removeSurvivalRow() {
    const { kind, index } = this.data.survivalEditor;
    if (kind === 'card') delete this._survivalDraft.card;
    else if (index >= 0) {
      const parent = kind === 'facilities' ? this._survivalDraft.gameplayRuntime : this._survivalDraft.gameplayRuntime.survival;
      if (kind === 'facilities' && (this._survivalDraft.gameplayRuntime.quests || []).some(q => q.facilityKey === parent[kind][index].facilityKey)) { this.setData({ 'survivalEditor.error': '此设施被任务引用，请先解除任务关联' }); return; }
      parent[kind].splice(index, 1);
    }
    this._survivalDirty[kind] = true; this.backSurvivalList(); this._refreshSurvival();
  },
  commitSurvivalRow() {
    const { kind, index, row } = this.data.survivalEditor;
    if (!kind) return true;
    const draft = clone(this._survivalDraft);
    if (kind === 'card') draft.card = clone(row);
    else { const parent = kind === 'facilities' ? draft.gameplayRuntime : draft.gameplayRuntime.survival; parent[kind] = parent[kind] || []; if (index < 0) parent[kind].push(clone(row)); else parent[kind][index] = clone(row); }
    // 逐项修复悬空引用时，其他尚未修复项留到整体完成再验证。
    const check = clone(draft); check.gameplayRuntime.survival.recoveries = kind === 'recoveries' ? [row] : []; check.gameplayRuntime.survival.rations = kind === 'rations' ? [row] : []; check.gameplayRuntime.facilities = kind === 'facilities' ? [row] : []; delete check.replayEnabled; if (kind !== 'card') delete check.card;
    const issue = validate(check, this.data.survivalNodes, initial.parse(this.data.formData.journeyRules));
    if (issue) { this.setData({ 'survivalEditor.error': issue }); return false; }
    this._survivalDraft = draft; this._survivalDirty[kind] = true; this.backSurvivalList(); this._refreshSurvival(); return true;
  },
  saveSurvivalEditor() {
    if (this.data.survivalEditor.kind) {
      const directCard = this.data.survivalEditor.section === 'card';
      if (!this.commitSurvivalRow()) return false;
      if (!directCard) return true;
    }
    const c = apply(this._runtimeConfig(), this._survivalDraft, this._survivalDirty), issue = validate(c, this.data.survivalNodes, initial.parse(this.data.formData.journeyRules));
    if (issue) { this.setData({ 'survivalEditor.error': issue }); return false; }
    if (Object.keys(this._survivalDirty).length) this._saveRuntime(c);
    this._refreshSurvivalSummary(c); this.closeSurvivalEditor(); return true;
  },

};
module.exports = { data, methods, validate, apply, key, copyReferences, pruneActRefs, CARD_FIELDS };
