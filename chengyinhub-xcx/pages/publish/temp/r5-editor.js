const catalog = require('../utils/publish/node-game-catalog.js');
const after = require('../utils/publish/outcome-after.js');
const tools = require('../utils/publish/template-tools.js');
const matrix = require('../utils/publish/gameplay-support-matrix.js');
const clone = value => JSON.parse(JSON.stringify(value));
const PARTICIPATION = [{ key: 'direct', label: '直接参与', value: 0, sub: '直接开始', reason: '' }, { key: 'gps', label: '定位到达', value: 5, sub: '到达节点后开始', reason: '' }, { key: 'scan', label: '扫码参与', value: 4, sub: '扫现场码后开始', reason: '' }];
const REMOVED = ['branch', 'silentOrder', 'diyName', 'musicCorner', 'slowTask', 'multiplayer'];
function legacyRows(config) {
  const rows = [];
  const add = (path, label) => { const value = after.get(config, path); if (value != null) rows.push({ path, label, value: clone(value), displayValue: typeof value === 'object' ? JSON.stringify(value) : String(value), readOnly: true }); };
  if (tools.selected(config).trade) add('interactionTools.allowTrade', '旧物资交换');
  REMOVED.filter(key => config[key]?.enabled).forEach(key => add(key, '旧规则：' + key));
  for (const path of ['ballShake.timed', 'ballShake.seconds', 'typeIn.seconds', 'sort.maxAttempts', 'qa.maxTries', 'photoCheck.maxTries', 'estimate.maxTries', 'pricePair.maxTries', 'hiddenObject.maxTries']) {
    const value = after.get(config, path);
    if (config[path.split('.')[0]]?.enabled && value !== 0 && value !== '' && value !== false && !(path === 'qa.maxTries' && config.qa.mode === 'PICK')) add(path, '旧设置：' + path);
  }
  if (config.variants?.length) add('variants', '旧放宽条件');
  return rows;
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
  // 2026-10-23 主人:选择玩法照原型 —— 每个玩法自己一枚标签(选项问答 / 猜图 / 闭眼盲品 / 竞猜 …),
  // 不再先选一类再在卡里挑「形式」。有形式的那几类按形式摊开,标签 key 用玩法 key。
  const flat = item => (item.forms || []).length ? item.forms.map(f => { const g = catalog.findGame(f.gameKey) || {}; return { key: f.gameKey, pickKey: f.gameKey, label: g.label || f.label, icon: g.icon || item.icon, sub: g.sub || '', category: item.category }; }) : [Object.assign({}, item, { pickKey: item.gameKey || '' })];
  const gameGroups = catalog.CATEGORIES.filter(title => title !== '全部').map(title => ({ title, items: [].concat(...catalog.BASE_GAMES.filter(item => item.category === title).map(flat)).map(item => Object.assign({}, item, { sub: item.sub || '', reason: item.reason || (item.pickKey ? matrix.choice(support, item.pickKey, config, context) : '') })) })).filter(group => group.items.length);
  gameGroups.forEach(group => { group.rows = tagRows(group.items); });
  // 玩法图标按分类上一层浅色(2026-10-22 原型定稿:只有玩法有图标,颜色不用渐变)
  const tint = { 问答: 'qa', 推理: 'logic', 互动: 'play', 挑战: 'challenge' }[(catalog.BASE_GAMES.find(item => item.key === selected.baseKey) || {}).category] || 'plain';
  selected.forms = selected.forms.map(item => Object.assign({}, item, { sub: '', reason: matrix.choice(support, item.gameKey, config, context) }));
  return Object.assign(selected, { toolRows, toolOptions, gameSummary: game ? game.label + ((selected.forms.find(item => item.key === selected.formKey)?.label) ? ' · ' + selected.forms.find(item => item.key === selected.formKey).label : '') + ' · ' + summary(config.outcomeRules?.pass) : '未选择玩法', scanSummary: config.scan?.kind === 'VOICE' ? '语音回复' : config.scan?.kind === 'IMAGE' ? '图片回复' : config.scan?.reply || '未配置扫码回复', participation, participationOptions: PARTICIPATION, category, categories: catalog.CATEGORIES, gameItems, gameGroups, afterRows: rows, fbRows: feedbackRows(config, rows), optionAfterRows: rows.filter(row => !FB_KEYS[row.path]).map(row => Object.assign({}, row, { label: afterLabel(config, row.path, row.label) })), fbSteps: feedbackSteps(config, section, after.once(config)), gameTint: tint, valueSlots: tools.slotsOf(config).filter(slot => slot.kind === 'VALUE'), legacyRows: legacyRows(config), nextStepOptions, feedbackStepOptions: nextStepOptions.slice(1).map(({ key, label }) => ({ key, label })), nextDisabledReason: support?.outcomeRules?.nextStepId === 'SETTLED_FEEDBACK' ? '' : '暂未开放', error: '' });
}
// 结果反馈(2026-10-22 主人定稿):三步说明 + 成功/失败各一行;其余「选了某项之后」单独列出,标签写清是哪一项。
const FB_KEYS = { 'outcomeRules.pass': 'pass', 'outcomeRules.fail': 'fail' };
const FIRST_STEP = { coinFlip: '玩家抛硬币', check: '玩家掷 D20', estimate: '玩家猜一个数', pricePair: '玩家挑一张图', hiddenObject: '玩家在图上找目标', random: '玩家抽一张卡', predict: '玩家押一个答案', blindTaste: '玩家闭眼尝一口再选', sort: '玩家把几条排好顺序', match: '玩家把两列一一连好', classify: '玩家把每条归到一类', photoCheck: '玩家拍照提交', steps: '玩家走够步数', reaction: '玩家等屏幕变色再点', ballShake: '玩家倾斜手机让球撞够次数', quietHold: '玩家保持安静到时间', compass: '玩家把手机对准方位', shout: '玩家喊到声音够大', countdown: '玩家在倒计时里完成', stopwatch: '玩家盲停在目标秒数', typeIn: '玩家照着一行字打出来' };
function feedbackSteps(config, section, isOnce) {
  if (!section) return [];
  let first = FIRST_STEP[section] || '玩家完成这一关';
  if (section === 'qa') first = config.qa?.mode === 'PICK' ? '玩家从选项里选一个' : config.qa?.mode === 'SHOT' ? '玩家拍一张照片交上来' : '玩家打字作答';
  if (section === 'diceRoll') first = config.diceRoll?.mode === 'd20' ? '玩家掷 D20' : '玩家掷骰子';
  if (isOnce) return [first, '做完就算完成', '玩家看到完成反馈，然后继续'];
  return [first, '达成就是成功，没达成就是失败', '玩家看到成功或失败的反馈，然后继续'];
}
function afterLabel(config, path, fallback) {
  const name = row => row && (row.label || row.text || row.title || row.name);
  let m = path.match(/^(qa|blindTaste|predict)\.options\.(\d+)\.after$/);
  if (m) { const row = config[m[1]]?.options?.[Number(m[2])]; return '选「' + (name(row) || '选项 ' + (Number(m[2]) + 1)) + '」之后'; }
  m = path.match(/^(pricePair|random)\.items\.(\d+)\.after$/);
  if (m) { const row = config[m[1]]?.items?.[Number(m[2])]; return (m[1] === 'random' ? '抽到「' : '选「') + (name(row) || '第 ' + (Number(m[2]) + 1) + ' 项') + '」之后'; }
  m = path.match(/^coinFlip\.(heads|tails)\.after$/);
  if (m) return m[1] === 'heads' ? '正面之后' : '反面之后';
  m = path.match(/^diceRoll\.faces\.(\d+)\.after$/);
  if (m) return '掷到 ' + (Number(m[1]) + 1) + ' 之后';
  return fallback;
}
function feedbackRows(config, rows) {
  const isOnce = after.once(config);
  return rows.filter(row => FB_KEYS[row.path]).map(item => {
    const row = after.get(config, item.path) || {};
    const images = Array.isArray(row.images) ? row.images : [];
    const costs = (row.costs || []).length, effects = (row.effects || []).length;
    return { path: item.path, key: FB_KEYS[item.path], label: item.path === 'outcomeRules.pass' ? (isOnce ? '完成反馈' : '成功反馈') : '失败反馈',
      has: !!(row.reply || images.length || costs || effects || row.nextStepId), reply: row.reply || '', images,
      extra: [costs ? '消耗 ' + costs + ' 项' : '', effects ? '效果 ' + effects + ' 项' : ''].filter(Boolean).join(' · ') || '无',
      count: costs + effects,
      disabled: item.disabled, reason: item.reason };
  });
}
// 选择玩法的标签条:每个分类最多四行,标签按顺序轮流落到各行(宽度随字数,不强行对齐)。
function tagRows(items) { const rows = [[], [], [], []]; items.forEach((item, i) => rows[i % 4].push(item)); return rows.filter(row => row.length); }
function summary(row) { return !row ? '未配置' : [row.reply, (row.costs || []).length ? '消耗 ' + row.costs.length + ' 项' : '', (row.effects || []).length ? '效果 ' + row.effects.length + ' 项' : '', row.nextStepId ? '进入 ' + row.nextStepId : ''].filter(Boolean).join(' · ') || '无额外处理'; }
const data = { cgOut: '', pvVerdict: { show: false, kind: 'win', text: '', images: [] }, fbOpenPass: false, fbOpenFail: false, sheetPick: '', sheetPickItem: null, sheetNavAnchor: '', toolPickerOpen: false, toolPickerItems: [], toolPickerHint: '', voiceSheetOpen: false, musicSheetOpen: false, musicDraft: { title: '', audioUrl: '', fileName: '', loop: true }, musicError: '',
  scanReplyKinds: [{ key: 'TEXT', label: '文字' }, { key: 'VOICE', label: '语音' }, { key: 'IMAGE', label: '图片' }, { key: 'OVERLAY', label: '显形' }],
  gameConfigOpen: false, scanSheetOpen: false, toolSheetKey: '', afterOpOptions: [{ key: 'INC', label: '增加' }, { key: 'FLOOR', label: '恢复到至少' }], effectScopeOptions: [], r5Editor: view({}, 0), afterEditor: { show: false, path: '', draft: after.empty(), error: '' } };
const methods = {
  /* 2026-10-23 弹层动效:编辑器里页面自绘的半屏原来开合都是一帧切换。点遮罩 / 退出时先挂 is-out
     落下(--cy-motion-sheet-out 250ms)再调原来的关闭函数 —— 关闭逻辑(快照还原、清错误)一行不改,只是晚 250ms。
     程序里的一关一开(选完玩法直接进工具等)不走这里,仍是直接切换,不会两层同时动。 */
  cgDismiss(e) {
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    const fn = ds.close;
    if (!fn || typeof this[fn] !== 'function') return;
    if (this.data.reducedMotion) { this[fn](); return; }
    if (this.data.cgOut) return;
    this.setData({ cgOut: ds.out || 'x' });
    if (this._cgOutTimer) clearTimeout(this._cgOutTimer);
    this._cgOutTimer = setTimeout(() => {
      this._cgOutTimer = null;
      this.setData({ cgOut: '' });
      this[fn]();
    }, 250);
  },
  // 试玩判定屏(照试玩台):判得出对错才弹,返回是否弹了。成功「继续」进下一步;失败回到玩法开头再来。
  // 按哪份配置判:选择玩法里的「预览」用那份范例(_pvAdvanced),其余用作者正在填的这份
  showPvVerdict(kit, detail, action) {
    const pv = require('./preview-verdict.js'), config = this._pvAdvanced || this.data.advanced, kind = pv.judge(config, kit, action || (detail && detail.action), detail && detail.detail);
    if (!kind) return false;
    const fb = pv.feedback(config, kind);
    this.setData({ pvVerdict: { show: true, kind, text: fb.text, images: fb.images } });
    return true;
  },
  closePvVerdict() {
    const kind = this.data.pvVerdict.kind, kit = this.data.pvKit;
    this.setData({ pvVerdict: { show: false, kind: 'win', text: '', images: [] } });
    // 只有玩法这一屏的试玩(选择玩法里的「预览」、「试玩当前玩法」)玩完就回编辑页
    if (kind === 'win' || !kit) { if (this.data.pvStep >= this.data.pvSteps.length - 1) this.closePreview(); else this.pvNext(); return; }
    // 玩法组件的「已提交」是它自己的状态,卸掉重挂才回到开局那一屏(判定屏盖着,看不到这一下)
    this._pvTypeStartedAt = 0;
    this.setData({ pvKit: null }, () => this.setData({ pvKit: Object.assign({}, kit, { passed: false, attempts: 0, startConfirmed: false, result: null, receipt: null, selectedKey: '' }) }));
  },
  _takeR5Snapshot(kind) { this['_r5Snapshot_' + kind] = clone({ advanced: this.data.advanced, formData: this.data.formData, dirty: this.data.dirty || false, gameKey: this.data.gameKey, gameCurrent: this.data.gameCurrent, gameSection: this.data.gameSection, timerAvailable: this.data.timerAvailable, presentChoice: this.data.presentChoice, presentInlineLocked: this.data.presentInlineLocked, presentLockReason: this.data.presentLockReason, pvMethodLabel: this.data.pvMethodLabel, pvMethodIconName: this.data.pvMethodIconName, participationChanged: !!this._r5ParticipationChanged }); },
  _restoreR5Snapshot(kind) { const saved = this['_r5Snapshot_' + kind]; if (!saved) return; this._r5ParticipationChanged = saved.participationChanged; delete saved.participationChanged; this._setFormState(saved, null, { pristine: true }); delete this['_r5Snapshot_' + kind]; },
  _validateR5Panel() { return require('../utils/publish/advanced-game-config.js').validate(this.data.advanced, this._advancedValidateOpts()) || matrix.validate(this._supportMatrix, Object.assign({}, this.data.formData, { advancedConfigJson: this.data.advanced }), this._supportContext); },
  openGameConfig() { this._takeR5Snapshot('game'); this.setData({ gameConfigOpen: true, 'r5Editor.error': '' }); },
  closeGameConfig() { this._restoreR5Snapshot('game'); this.setData({ gameConfigOpen: false }); },
  saveGameConfig() { const error = this._validateR5Panel(); if (error) { this.setData({ 'r5Editor.error': error }); return false; } delete this._r5Snapshot_game; this.setData({ gameConfigOpen: false }); this._refreshR5Editor(); return true; },
  openScanSheet() { this._takeR5Snapshot('scan'); this.setData({ scanSheetOpen: true }); },
  closeScanSheet() { this._restoreR5Snapshot('scan'); this.setData({ scanSheetOpen: false }); },
  saveScanSheet() { const error = this._validateR5Panel(); if (error) { this.setData({ 'r5Editor.error': error }); return false; } delete this._r5Snapshot_scan; this.setData({ scanSheetOpen: false }); this._refreshR5Editor(); return true; },
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
  _currentTagKey() { const key = this.data.gameKey || ''; for (const group of this.data.r5Editor.gameGroups || []) { const item = group.items.find(row => row.pickKey === key); if (item) return item.key; } return ''; },
  _gameTag(key) { for (const group of this.data.r5Editor.gameGroups || []) { const item = group.items.find(row => row.key === key); if (item) return Object.assign({}, item, { reason: item.reason || this.data.lockedGames?.[item.pickKey] || this.data.supportReasons?.[item.pickKey] || '' }); } return null; },
  openR5GameSheet() {
    const key = this._currentTagKey();
    const index = Math.max(0, (this.data.r5Editor.gameGroups || []).findIndex(group => group.items.some(item => item.key === key)));
    this.setData({ gameSheetOpen: true, sheetGroup: index, sheetAnchor: index ? 'gsheet-g' + index : '', sheetNavAnchor: 'gnav-' + index, sheetPick: key, sheetPickItem: key ? this._gameTag(key) : null }, () => this._measureGameStrip());
  },
  closeR5GameSheet() { this.setData({ gameSheetOpen: false }); },
  jumpR5GameGroup(e) { const index = Number(e.currentTarget.dataset.index); this.setData({ sheetGroup: index, sheetAnchor: 'gsheet-g' + index, sheetNavAnchor: 'gnav-' + index }); },
  pickR5GameTag(e) { const key = e.currentTarget.dataset.key; this.setData({ sheetPick: key, sheetPickItem: this._gameTag(key) }); },
  confirmR5GameSheet() {
    const item = this.data.sheetPickItem;
    if (!item) { this.closeR5GameSheet(); return; }
    if (item.reason) { require('../../../utils/toast.js')(item.reason); return; }
    if (!item.pickKey || item.pickKey === this.data.gameKey) { this.closeR5GameSheet(); return; }
    this.pickGame({ currentTarget: { dataset: { key: item.pickKey, r5: true } } });
    // 新建时名字还空着就先用玩法名,作者随时可改
    if (!String(this.data.formData.title || '').trim() && this.data.gameKey) this._setFormState({ 'formData.title': (this.data.gameCurrent && this.data.gameCurrent.label) || item.label });
    if (this.data.gameSheetOpen) this.closeR5GameSheet();
  },
  _measureGameStrip() {
    if (typeof this.createSelectorQuery !== 'function') return;
    const query = this.createSelectorQuery();
    query.select('.cg-tstrip').boundingClientRect();
    query.selectAll('.cg-tblock').boundingClientRect();
    query.exec(res => { const strip = res && res[0], blocks = res && res[1]; if (!strip || !blocks) return; this._tstripWidth = strip.width; this._tblockLefts = blocks.map(rect => rect.left - strip.left); });
  },
  onGameStripScroll(e) {
    const lefts = this._tblockLefts; if (!lefts || !lefts.length) return;
    const x = e.detail.scrollLeft; let index = 0;
    lefts.forEach((left, i) => { if (left <= x + 40) index = i; });
    if (e.detail.scrollWidth && x + 8 >= e.detail.scrollWidth - (this._tstripWidth || 0)) index = lefts.length - 1;
    if (index !== this.data.sheetGroup) this.setData({ sheetGroup: index, sheetNavAnchor: 'gnav-' + index });
  },
  pickR5GameFromSheet(e) { const before = this.data.gameKey; this.pickR5Game(e); if (this.data.gameKey !== before || !before) this.setData({ gameSheetOpen: false }); },
  pickR5Form(e) { const forms = this.data.r5Editor.forms; const form = forms.find((row, i) => row.key === e.detail.value || i === Number(e.detail.value)); if (form && !form.reason) this.pickGame({ currentTarget: { dataset: { key: form.gameKey, r5: true } } }); },
  pickParticipation(e) {
    const value = e.currentTarget.dataset.key || e.detail.value;
    const option = PARTICIPATION.find((row, i) => row.key === value || i === Number(value)); if (!option) return;
    const config = clone(this.data.advanced); config.scan.enabled = option.key === 'scan';
    this._r5ParticipationChanged = true;
    this._setFormState({ advanced: config, 'formData.validationMethod': option.value });
  },
  openAfterSheet(e) { const path = e.currentTarget.dataset.path; const row = this.data.r5Editor.afterRows.find(row => row.path === path); if (!row || row.disabled) return; this._r5AfterNextPicked = false; this._takeR5Snapshot('after'); this.setData({ afterEditor: { show: true, inline: !!e.currentTarget.dataset.inline, path, draft: Object.assign(after.empty(), clone(row.draft)), error: '' } }); },
  closeAfterSheet() { this._restoreR5Snapshot('after'); this._r5AfterNextPicked = false; this.setData({ afterEditor: { show: false, inline: false, path: '', draft: after.empty(), error: '' }, afterOpen: '', afterNewValue: false, afterAdd: { show: false, open: '' } }); },
  uploadAfterImage() { const images = this.data.afterEditor.draft.images || []; const remain = after.IMAGE_MAX - images.length; if (remain <= 0) return; getApp().chooseImage(res => { if (res && res.length) this.setData({ 'afterEditor.draft.images': images.concat(res).slice(0, after.IMAGE_MAX) }); }, remain); },
  removeAfterImage(e) { const index = Number(e.currentTarget.dataset.index); this.setData({ 'afterEditor.draft.images': (this.data.afterEditor.draft.images || []).filter((url, i) => i !== index) }); },

  // ===== 结果反馈:成功 / 失败各一个开关,打开才编辑;文字与配图直接写进这一行结果 =====
  _outcomeConfig() {
    const config = clone(this.data.advanced);
    if (!config.outcomeRules) config.outcomeRules = Object.assign({ version: 1, pass: {} }, after.once(config) ? {} : { fail: {} });
    return config;
  },
  _writeOutcomeRow(path, mutate) {
    const config = this._outcomeConfig(), key = path.split('.')[1];
    const row = Object.assign({}, config.outcomeRules[key] || {});
    mutate(row);
    if (!row.reply) delete row.reply;
    if (!row.images || !row.images.length) delete row.images;
    config.outcomeRules[key] = row;
    this._setFormState({ advanced: config });
    this._refreshR5Editor();
  },
  toggleOutcomeFeedback(e) {
    const { path, key } = e.currentTarget.dataset, on = !!e.detail.value, flag = key === 'pass' ? 'fbOpenPass' : 'fbOpenFail';
    if (on) { this.setData({ [flag]: true }); return; }
    const row = (this.data.r5Editor.fbRows || []).find(item => item.path === path);
    const clear = () => { this._writeOutcomeRow(path, item => { Object.keys(item).forEach(field => { delete item[field]; }); }); this.setData({ [flag]: false }); };
    if (!row || !row.has) { this.setData({ [flag]: false }); return; }
    require('../../../utils/modal.js').show({ title: '关掉' + row.label + '？', content: '这条反馈的文字、配图和消耗效果会一起清空', confirmText: '清空', success: res => { if (res.confirm) clear(); else this.setData({ 'r5Editor.fbRows': [] }, () => this._refreshR5Editor()); } });
  },
  inputOutcomeReply(e) { const path = e.currentTarget.dataset.path, value = e.detail.value; this._writeOutcomeRow(path, row => { row.reply = value; }); },
  uploadOutcomeImage(e) {
    const path = e.currentTarget.dataset.path, current = (after.get(this.data.advanced, path) || {}).images || [];
    const remain = after.IMAGE_MAX - current.length; if (remain <= 0) return;
    getApp().chooseImage(res => { if (res && res.length) this._writeOutcomeRow(path, row => { row.images = (row.images || []).concat(res).slice(0, after.IMAGE_MAX); }); }, remain);
  },
  removeOutcomeImage(e) { const { path, index } = e.currentTarget.dataset; this._writeOutcomeRow(path, row => { row.images = (row.images || []).filter((url, i) => i !== Number(index)); }); },

  // ===== 参与方式:扫码时「扫完回复」单独一个开关;关 = kind NONE(只扫码进场,不回复) =====
  toggleScanReply(e) {
    const on = !!e.detail.value, kind = this.data.advanced.scan.kind;
    if (!on && kind !== 'NONE') this._scanKindBefore = kind;
    this._setFormState({ 'advanced.scan.kind': on ? (this._scanKindBefore || 'TEXT') : 'NONE' });
    this._refreshR5Editor();
  },
  pickScanReplyKind(e) { const row = this.data.scanReplyKinds[Number(e.detail.value)]; if (row) { this._setFormState({ 'advanced.scan.kind': row.key }); this._refreshR5Editor(); } },

  // ===== 互动工具:底部弹出,标签选择;已添加的点一下去配置。语音讲解、背景音乐也在这里 =====
  _toolPickerItems() {
    const hasVoice = (this.data.moduleList || []).some(item => item.key === 'voice');
    const items = (this.data.r5Editor.toolOptions || []).map(item => ({ key: item.key, label: item.label, icon: item.icon || 'check', reason: item.reason || '', on: (this.data.r5Editor.toolRows || []).some(row => row.key === item.key) }));
    return items.concat([{ key: 'voice', label: '语音讲解', icon: 'play', reason: '', on: hasVoice }, { key: 'music', label: '背景音乐', icon: 'play-filled', reason: '', on: !!this.data.advanced.backgroundMusic }]);
  },
  openToolPicker() { this.setData({ toolPickerOpen: true, toolPickerHint: '', toolPickerItems: this._toolPickerItems() }); },
  closeToolPicker() { this.setData({ toolPickerOpen: false }); },
  pickToolTag(e) {
    const { key, reason } = e.currentTarget.dataset;
    if (reason) { this.setData({ toolPickerHint: reason }); return; }
    this.setData({ toolPickerOpen: false });
    if (key === 'voice') { if (!(this.data.moduleList || []).some(item => item.key === 'voice')) this.addModule({ currentTarget: { dataset: { key: 'voice' } } }); this.openVoiceSheet(); return; }
    if (key === 'music') { this.openMusicSheet(); return; }
    this.openR5ToolSheet({ currentTarget: { dataset: { key } } });
  },
  // 移除玩法 / 完成奖励会清空已填内容:有内容时先确认(2026-10-22 原型定稿的防误操作)
  confirmRemoveModule(e) {
    const key = e.currentTarget.dataset.key;
    const filled = key === 'finish' ? !!this.data.gameKey : key === 'reward' ? !!this.data.rwAny : false;
    if (!filled) { this.removeModule(e); return; }
    require('../../../utils/modal.js').show({ title: key === 'finish' ? '移除玩法？' : '移除完成奖励？', content: key === 'finish' ? '题目、选项和结果反馈会一起清空' : '优惠券和勋章设置会一起清空', confirmText: '移除', success: res => { if (res.confirm) this.removeModule({ currentTarget: { dataset: { key } } }); } });
  },
  openVoiceSheet() { this.setData({ voiceSheetOpen: true }); },
  closeVoiceSheet() { if (typeof this.destroyPreviewAudio === 'function') this.destroyPreviewAudio(); this.setData({ voiceSheetOpen: false }); },
  removeVoiceTool() { this.removeModule({ currentTarget: { dataset: { key: 'voice' } } }); this.setData({ voiceSheetOpen: false }); },
  openMusicSheet() { const music = this.data.advanced.backgroundMusic || {}; this.setData({ musicSheetOpen: true, musicError: '', musicDraft: { title: music.title || '', audioUrl: music.audioUrl || '', fileName: music.title || '', loop: music.loop !== false } }); },
  closeMusicSheet() { this.setData({ musicSheetOpen: false, musicError: '' }); },
  inputMusicTitle(e) { this.setData({ 'musicDraft.title': e.detail.value }); },
  toggleMusicLoop(e) { this.setData({ 'musicDraft.loop': !!e.detail.value }); },
  uploadMusicAudio() { getApp().chooseDocument(res => { const file = res && res[0]; if (file && file.url) this.setData({ 'musicDraft.audioUrl': file.url, 'musicDraft.fileName': file.filename || '', musicError: '' }); }, 1, ['mp3', 'm4a', 'aac']); },
  clearMusicAudio() { this.setData({ 'musicDraft.audioUrl': '', 'musicDraft.fileName': '' }); },
  saveMusicSheet() {
    const draft = this.data.musicDraft;
    if (!draft.audioUrl) { this.setData({ musicError: '请上传背景音乐' }); return; }
    const config = clone(this.data.advanced);
    config.backgroundMusic = Object.assign({ audioUrl: draft.audioUrl, loop: !!draft.loop }, String(draft.title || '').trim() ? { title: String(draft.title).trim().slice(0, 40) } : {});
    this._setFormState({ advanced: config }); this.setData({ musicSheetOpen: false });
  },
  removeMusicTool() { const config = clone(this.data.advanced); delete config.backgroundMusic; this._setFormState({ advanced: config }); this.setData({ musicSheetOpen: false }); },
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
module.exports = { data, methods, view, legacyRows, PARTICIPATION };
