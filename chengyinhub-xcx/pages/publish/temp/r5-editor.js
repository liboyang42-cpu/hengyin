const catalog = require('../utils/publish/node-game-catalog.js');
const after = require('../utils/publish/outcome-after.js');
const tools = require('../utils/publish/template-tools.js');
const matrix = require('../utils/publish/gameplay-support-matrix.js');
const clone = value => JSON.parse(JSON.stringify(value));
const PARTICIPATION = [{ key: 'direct', label: '直接参与', value: 0, sub: '直接开始', reason: '' }, { key: 'gps', label: '定位到达', value: 5, sub: '到达站点后开始', reason: '' }, { key: 'scan', label: '扫码参与', value: 4, sub: '扫现场码后开始', reason: '' }];
const REMOVED = ['branch', 'silentOrder', 'diyName', 'musicCorner', 'slowTask', 'multiplayer'];
// 旧段的中文名只用于页面上那一行摘要;原始值仍留在 value/displayValue 里供保存与校验。
const REMOVED_NAMES = { branch: '分支', silentOrder: '沉默点单', diyName: '作品命名', musicCorner: '音乐角', slowTask: '跨日任务', multiplayer: '多人协作' };
function legacyRows(config) {
  const rows = [];
  const add = (path, label, name) => { const value = after.get(config, path); if (value != null) rows.push({ path, label, name, value: clone(value), displayValue: typeof value === 'object' ? JSON.stringify(value) : String(value), readOnly: true }); };
  if (tools.selected(config).trade) add('interactionTools.allowTrade', '旧物资交换', '物资交换');
  REMOVED.filter(key => config[key]?.enabled).forEach(key => add(key, '旧规则：' + key, REMOVED_NAMES[key]));
  for (const path of ['ballShake.timed', 'ballShake.seconds', 'typeIn.seconds', 'sort.maxAttempts', 'qa.maxTries', 'photoCheck.maxTries', 'estimate.maxTries', 'pricePair.maxTries', 'hiddenObject.maxTries']) {
    if (config[path.split('.')[0]]?.enabled) add(path, '旧参数：' + path, '玩法参数');
  }
  if (config.variants?.length) add('variants', '旧放宽条件', '放宽条件');
  return rows;
}
// 2026-10-22 业主裁决(L09):旧设置不再把原始 JSON 打到页面上,只给一句人话摘要;保存仍原样保留。
function legacySummary(rows) {
  if (!rows || !rows.length) return '';
  const names = rows.map(row => row.name).filter((name, index, all) => name && all.indexOf(name) === index);
  return '包含 ' + rows.length + ' 条旧版' + (names.length ? names.join('、') : '') + '设置，保存时保留';
}
function view(config, validationMethod, support, context, category = '全部') {
  const selected = catalog.selection(catalog.detectGame(config));
  const participation = config.scan?.enabled || Number(validationMethod) === 4 ? 'scan' : Number(validationMethod) === 5 ? 'gps' : 'direct';
  const section = catalog.findGame(catalog.detectGame(config))?.section;
  const gameItems = catalog.BASE_GAMES.filter(item => category === '全部' || item.category === category).map(item => {
    const key = item.gameKey || item.forms?.[0].gameKey;
    const reason = item.reason || matrix.choice(support, key, config, context);
    return Object.assign({}, item, { sub: item.sub || '', disabled: !!reason, reason });
  });
  const rows = section ? after.paths(config).filter(path => path.startsWith('outcomeRules.') || path.startsWith(section + '.') && !(section === 'qa' && config.qa.mode !== 'PICK') && !(section === 'diceRoll' && config.diceRoll.mode === 'd20')).map(path => {
    const reason = after.get(config, path)?.nextStepId && support?.outcomeRules?.nextStepId !== 'SETTLED_FEEDBACK' ? '旧后段只读，暂未开放' : after.localReason(config, path);
    return { path, label: path === 'outcomeRules.pass' ? after.once(config) ? '完成之后' : '通过之后' : path === 'outcomeRules.fail' ? '没通过之后' : '选了之后', draft: after.draft(after.get(config, path)), summary: summary(after.get(config, path)), disabled: !!reason, reason };
  }) : [];
  const toolOptions = tools.TOOLS.map(item => Object.assign({}, item, { kind: 'tool', reason: !section ? '请先选择玩法' : matrix.toolPolicy(support, catalog.detectGame(config), item.key, config, context) })).concat(catalog.MODIFIERS.map(item => Object.assign({}, item, { kind: 'addon', sub: item.sub || '', reason: item.reason || (!section ? '请先选择玩法' : '') })));
  const toolRows = toolOptions.filter(item => item.kind === 'tool' ? tools.selected(config)[item.key] : config[item.key]?.enabled).map(item => Object.assign({}, item, { summary: item.kind === 'tool' ? tools.summaries(config)[item.key] : item.key === 'timer' ? config.timer.durationSeconds + ' 秒 · ' + (config.timer.timeoutResult === 'COMPLETED' ? '超时完成' : '超时失败') : item.key === 'timeWindow' ? config.timeWindow.openFrom + '–' + config.timeWindow.openTo : item.key === 'multiplayer' ? '旧配置只读' : config.leaderboard.metric + ' · 前 ' + config.leaderboard.limit + ' 名' }));
  const nextStepOptions = [{ key: '', label: '不进入下一段', reason: '' }].concat((config.branch?.steps || []).map((step, index) => ({ key: step.id, label: step.title || '第 ' + (index + 1) + ' 段', reason: typeof step.body === 'string' && step.body.trim() ? '' : '先在反馈分段里配置内容' })));
  const game = catalog.BASE_GAMES.find(item => item.key === selected.baseKey);
  // 选择玩法纵向列表：按分类分组（只含玩法），每项带不可选原因
  const gameGroups = catalog.CATEGORIES.filter(title => title !== '全部').map(title => ({ title, items: catalog.BASE_GAMES.filter(item => item.category === title).map(item => { const key = item.gameKey || item.forms?.[0].gameKey; return Object.assign({}, item, { pickKey: key, sub: item.sub || '', reason: item.reason || matrix.choice(support, key, config, context) }); }) })).filter(group => group.items.length);
  selected.forms = selected.forms.map(item => Object.assign({}, item, { sub: '', reason: matrix.choice(support, item.gameKey, config, context) }));
  return Object.assign(selected, { toolRows, toolOptions, gameSummary: game ? game.label + ((selected.forms.find(item => item.key === selected.formKey)?.label) ? ' · ' + selected.forms.find(item => item.key === selected.formKey).label : '') + ' · ' + summary(config.outcomeRules?.pass) : '未选择玩法', scanSummary: config.scan?.kind === 'VOICE' ? '语音回复' : config.scan?.kind === 'IMAGE' ? '图片回复' : config.scan?.reply || '未配置扫码回复', participation, participationOptions: PARTICIPATION, category, categories: catalog.CATEGORIES, gameItems, gameGroups, afterRows: rows, valueSlots: tools.slotsOf(config).filter(slot => slot.kind === 'VALUE'), legacyRows: legacyRows(config), legacySummary: legacySummary(legacyRows(config)), nextStepOptions, feedbackStepOptions: nextStepOptions.slice(1).map(({ key, label }) => ({ key, label })), nextDisabledReason: support?.outcomeRules?.nextStepId === 'SETTLED_FEEDBACK' ? '' : '暂未开放', error: '' });
}
function summary(row) { return !row ? '未配置' : [row.reply, (row.costs || []).length ? '消耗 ' + row.costs.length + ' 项' : '', (row.effects || []).length ? '效果 ' + row.effects.length + ' 项' : '', row.nextStepId ? '进入 ' + row.nextStepId : ''].filter(Boolean).join(' · ') || '无额外处理'; }
const data = { gameConfigOpen: false, scanSheetOpen: false, toolSheetKey: '', afterOpOptions: [{ key: 'INC', label: '增加' }, { key: 'FLOOR', label: '恢复到至少' }], effectScopeOptions: [], r5Editor: view({}, 0), afterEditor: { show: false, path: '', draft: after.empty(), error: '' } };
const methods = {
  _takeR5Snapshot(kind) { this['_r5Snapshot_' + kind] = clone({ advanced: this.data.advanced, formData: this.data.formData, dirty: this.data.dirty || false, gameKey: this.data.gameKey, gameCurrent: this.data.gameCurrent, gameSection: this.data.gameSection, timerAvailable: this.data.timerAvailable, presentChoice: this.data.presentChoice, presentInlineLocked: this.data.presentInlineLocked, presentLockReason: this.data.presentLockReason, pvMethodLabel: this.data.pvMethodLabel, pvMethodIconName: this.data.pvMethodIconName, participationChanged: !!this._r5ParticipationChanged }); },
  _restoreR5Snapshot(kind) { const saved = this['_r5Snapshot_' + kind]; if (!saved) return; this._r5ParticipationChanged = saved.participationChanged; delete saved.participationChanged; this._setFormState(saved, null, { pristine: true }); delete this['_r5Snapshot_' + kind]; },
  _validateR5Panel() { return require('../utils/publish/advanced-game-config.js').validate(this.data.advanced, this._advancedValidateOpts()) || matrix.validate(this._supportMatrix, Object.assign({}, this.data.formData, { advancedConfigJson: this.data.advanced }), this._supportContext); },
  openGameConfig() { this._takeR5Snapshot('game'); this.setData({ gameConfigOpen: true, 'r5Editor.error': '' }); },
  closeGameConfig() { this._restoreR5Snapshot('game'); this.setData({ gameConfigOpen: false }); },
  saveGameConfig() { const error = this._validateR5Panel(); if (error) { this.setData({ 'r5Editor.error': error }); return false; } delete this._r5Snapshot_game; this.setData({ gameConfigOpen: false }); this._refreshR5Editor(); return true; },
  openScanSheet() { this._takeR5Snapshot('scan'); this.setData({ scanSheetOpen: true }); },
  closeScanSheet() { this._restoreR5Snapshot('scan'); this.setData({ scanSheetOpen: false }); },
  saveScanSheet() { const error = this._validateR5Panel(); if (error) { this.setData({ 'r5Editor.error': error }); return false; } delete this._r5Snapshot_scan; this.setData({ scanSheetOpen: false }); this._refreshR5Editor(); return true; },
  // 2026-10-22 业主裁决(L09):参与方式 / 添加互动工具改走本页底部 pickerSheet,选中后仍落到原来的 pickParticipation / openR5ToolSheet。
  openParticipationSheet() { const current = this.data.r5Editor.participation; this.setData({ pickerSheet: { open: true, key: 'participation', title: '玩家怎么参与', options: PARTICIPATION.map(item => ({ value: item.key, label: item.label, reason: item.reason || '', active: item.key === current })) } }); },
  openR5ToolPicker() { this.setData({ pickerSheet: { open: true, key: 'r5Tool', title: '添加互动工具', options: this.data.r5Editor.toolOptions.map(item => ({ value: item.key, label: item.label, reason: item.reason || '', active: false })) } }); },
  addR5Tool(e) { const option = this.data.r5Editor.toolOptions[Number(e.detail.value)]; if (!option || option.reason) return; this.openR5ToolSheet({ currentTarget: { dataset: { key: option.key } } }); },
  openR5ToolSheet(e) {
    const key = e.currentTarget.dataset.key, option = this.data.r5Editor.toolOptions.find(item => item.key === key); if (!option || option.reason) return;
    this._takeR5Snapshot('tool');
    if (option.kind === 'tool') { this.openToolSheet(e); if (!this.data.toolSheetOpen) { delete this._r5Snapshot_tool; return; } }
    else this._setFormState({ ['advanced.' + key + '.enabled']: true });
    this.setData({ toolSheetKey: key });
  },
  closeR5ToolSheet() { this._restoreR5Snapshot('tool'); this.closeToolSheet(); this.setData({ toolSheetKey: '' }); },
  saveR5ToolSheet() {
    if (tools.TOOLS.some(item => item.key === this.data.toolSheetKey) && !this.saveTool()) return false;
    const error = this._validateR5Panel(); if (error) { this.setData({ 'r5Editor.error': error }); return false; }
    delete this._r5Snapshot_tool; this.closeToolSheet(); this.setData({ toolSheetKey: '' }); this._refreshR5Editor(); return true;
  },
  removeR5Tool(e) { const key = e.currentTarget.dataset.key; if (tools.TOOLS.some(item => item.key === key)) this.removeTool(e); else if (catalog.MODIFIERS.some(item => item.key === key && !item.disabled)) this._setFormState({ ['advanced.' + key + '.enabled']: false }); },
  pickAfterRowStat(e) { const slot = this.data.r5Editor.valueSlots[Number(e.detail.value)]; const { kind, index } = e.currentTarget.dataset; if (slot) this.inputAfterRow({ currentTarget: { dataset: { kind, index, field: kind === 'costs' ? 'statKey' : 'var' } }, detail: { value: slot.key } }); },
  pickAfterRowOp(e) { const op = this.data.afterOpOptions[Number(e.detail.value)]; if (op) this.inputAfterRow({ currentTarget: { dataset: { kind: 'effects', index: e.currentTarget.dataset.index, field: 'op' } }, detail: { value: op.key } }); },
  pickEffectScope(e) { const option = this.data.effectScopeOptions[Number(e.detail.value)]; if (!option || option.reason) return; const config = clone(this.data.advanced); config.interactionRules = Object.assign({}, config.interactionRules, { targetScope: option.key }); this._setFormState({ advanced: config }); },

  _refreshR5Editor() { const effectScopeOptions = tools.TARGETS.map(item => { const config = clone(this.data.advanced); config.interactionRules = Object.assign({}, config.interactionRules, { targetScope: item.key }); return Object.assign({}, item, { reason: matrix.validate(this._supportMatrix, Object.assign({}, this.data.formData, { advancedConfigJson: config }), this._supportContext) || (item.key !== 'OWNER' && this._supportContext?.runtime?.runtimeScope && !(this._supportContext.runtime.runtimeScope === 'TEAM' && this._supportContext.runtime.worldMode === 'WORLD_SHARED') ? '仅全场共享的组队主题可用' : '') }); }); this.setData({ effectScopeOptions, r5Editor: view(this.data.advanced, this.data.formData.validationMethod, this._supportMatrix, this._supportContext, this.data.r5Editor?.category) }); },
  pickR5Category(e) { const category = e.currentTarget.dataset.category || catalog.CATEGORIES[Number(e.detail.value)]; if (!catalog.CATEGORIES.includes(category)) return; this.setData({ 'r5Editor.category': category }); this._refreshR5Editor(); },
  pickR5Game(e) { const base = e.currentTarget.dataset.key ? catalog.BASE_GAMES.find(row => row.key === e.currentTarget.dataset.key) : this.data.r5Editor.gameItems[Number(e.detail.value)]; if (!base || base.disabled || base.reason) return; this.pickGame({ currentTarget: { dataset: { key: base.gameKey || base.forms[0].gameKey, r5: true } } }); },
  openR5GameSheet() { this.setData({ gameSheetOpen: true, sheetGroup: 0, sheetAnchor: '' }); },
  closeR5GameSheet() { this.setData({ gameSheetOpen: false }); },
  jumpR5GameGroup(e) { const index = Number(e.currentTarget.dataset.index); this.setData({ sheetGroup: index, sheetAnchor: 'gsheet-g' + index }); },
  pickR5GameFromSheet(e) { const before = this.data.gameKey; this.pickR5Game(e); if (this.data.gameKey !== before || !before) this.setData({ gameSheetOpen: false }); },
  pickR5Form(e) { const forms = this.data.r5Editor.forms; const form = forms.find((row, i) => row.key === e.detail.value || i === Number(e.detail.value)); if (form && !form.reason) this.pickGame({ currentTarget: { dataset: { key: form.gameKey, r5: true } } }); },
  pickParticipation(e) {
    const value = e.currentTarget.dataset.key || e.detail.value;
    const option = PARTICIPATION.find((row, i) => row.key === value || i === Number(value)); if (!option) return;
    const config = clone(this.data.advanced); config.scan.enabled = option.key === 'scan';
    this._r5ParticipationChanged = true;
    this._setFormState({ advanced: config, 'formData.validationMethod': option.value });
  },
  openAfterSheet(e) { const path = e.currentTarget.dataset.path; const row = this.data.r5Editor.afterRows.find(row => row.path === path); if (!row || row.disabled) return; this._r5AfterNextPicked = false; this._takeR5Snapshot('after'); this.setData({ afterEditor: { show: true, path, draft: clone(row.draft), error: '' } }); },
  closeAfterSheet() { this._restoreR5Snapshot('after'); this._r5AfterNextPicked = false; this.setData({ afterEditor: { show: false, path: '', draft: after.empty(), error: '' } }); },
  pickAfterNextStep(e) { const row = this.data.r5Editor.nextStepOptions[Number(e.detail.value)]; if (row && !row.reason && !this.data.r5Editor.nextDisabledReason) { this._r5AfterNextPicked = true; this.setData({ 'afterEditor.draft.next': row.key }); } },
  inputAfterReply(e) { this.setData({ 'afterEditor.draft.reply': e.detail.value }); },
  addAfterRow(e) { const kind = e.currentTarget.dataset.kind; if (!['costs', 'effects'].includes(kind)) return; const row = kind === 'costs' ? { statKey: '', amount: '' } : { var: '', op: 'INC', value: '' }; const rows = this.data.afterEditor.draft[kind]; if (rows.length < 20) this.setData({ ['afterEditor.draft.' + kind]: rows.concat(row) }); },
  inputAfterRow(e) {
    const { kind, index, field } = e.currentTarget.dataset;
    if (!['costs', 'effects'].includes(kind) || !(kind === 'costs' ? ['statKey', 'amount'] : ['var', 'op', 'value']).includes(field)) return;
    const rows = clone(this.data.afterEditor.draft[kind]); if (!rows[Number(index)]) return;
    rows[Number(index)][field] = ['amount', 'value'].includes(field) ? e.detail.value === '' ? '' : Number(e.detail.value) : e.detail.value;
    this.setData({ ['afterEditor.draft.' + kind]: rows });
  },
  removeAfterRow(e) { const { kind, index } = e.currentTarget.dataset; if (['costs', 'effects'].includes(kind)) this.setData({ ['afterEditor.draft.' + kind]: this.data.afterEditor.draft[kind].filter((row, i) => i !== Number(index)) }); },
  saveAfterSheet() {
    const editor = this.data.afterEditor; const result = after.apply(this.data.advanced, editor.path, editor.draft);
    if (result.error) { this.setData({ 'afterEditor.error': result.error }); return false; }
    if (this._r5AfterNextPicked && editor.draft.next) {
      const row = this.data.r5Editor.nextStepOptions.find(row => row.key === editor.draft.next);
      if (!row || row.reason) { this.setData({ 'afterEditor.error': row?.reason || '请选择已有反馈段' }); return false; }
      result.value.branch.enabled = true;
      if (!result.value.branch.steps.some(step => step.id === result.value.branch.startStepId)) result.value.branch.startStepId = result.value.branch.steps[0].id;
    }
    const reason = matrix.validate(this._supportMatrix, Object.assign({}, this.data.formData, { advancedConfigJson: result.value }), this._supportContext);
    if (reason) { this.setData({ 'afterEditor.error': reason }); return false; }
    this._setFormState({ advanced: result.value }); delete this._r5Snapshot_after; this.closeAfterSheet(); return true;
  },
  declareAfterValueSlot(e) {
    const label = String(e.detail.value || '').trim(); if (!label || label.length > 20) return;
    const config = clone(this.data.advanced), slots = tools.slotsOf(config);
    let index = 1; while (slots.some(slot => slot.key === 'value_' + index) || tools.reservedRefs(config).has('value_' + index)) index++;
    if (slots.length >= 20) { this.setData({ 'afterEditor.error': '最多声明 20 项需要的资源' }); return; }
    slots.push({ key: 'value_' + index, kind: 'VALUE', label, required: true });
    config.interactionRules = Object.assign({}, config.interactionRules, { resourceSlots: slots }); this._setFormState({ advanced: config });
  },
};
module.exports = { data, methods, view, legacyRows, legacySummary, PARTICIPATION };
