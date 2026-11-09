const support = require('../utils/publish/gameplay-support-matrix.js');
const modal = require('../../../utils/modal.js');
const copy = value => JSON.parse(JSON.stringify(value));
// 找不到时换个方式（10-28 e-quest）：扫码 / 答题 / 拍照，存 POST /api/orient/task/alternative。
// 作者详情只返回配置与答案是否已设置，答案输入始终留空。
const ALTS = [{ type: 'SCAN', label: '扫码' }, { type: 'QUIZ', label: '答题' }, { type: 'PHOTO', label: '拍照' }];
const ALT_WORD = { SCAN: '扫码', QUIZ: '答题', PHOTO: '拍照' };
function altRows(draft) {
  return ALTS.map(row => Object.assign({ type: row.type, label: row.label, on: false, prompt: '', answer: '', answerConfigured: false }, (draft || {})[row.type]));
}
function alternativeDrafts(detail, chapters) {
  const drafts = {}, configs = detail.nodeAlternativeConfigs;
  if (detail.nodeAlternativeConfigsLoaded !== true || !configs || typeof configs !== 'object' || Array.isArray(configs)) return drafts;
  nodes(chapters).forEach(node => {
    const rows = configs[node.id];
    if (!Array.isArray(rows) || rows.some(row => !row || !ALTS.some(item => item.type === row.type)
      || (row.promptText !== null && typeof row.promptText !== 'string') || typeof row.enabled !== 'boolean' || typeof row.answerConfigured !== 'boolean')
      || new Set(rows.map(row => row.type)).size !== rows.length) return;
    drafts[node.id] = Object.fromEntries(rows.map(row => [row.type, {
      type: row.type, on: row.enabled, prompt: row.promptText || '', answer: '',
      answerConfigured: row.type !== 'PHOTO' && row.answerConfigured,
    }]));
  });
  return drafts;
}
const MODES = [
  { key: 'PLAYER', icon: 'tab-me', runtimeScope: 'PLAYER', label: '个人独立进行', summary: '每人独立完成任务、支付资源；不使用工作人员开幕或跨队交易、表决。' },
  { key: 'TEAM_INDEPENDENT', icon: 'tab-club', runtimeScope: 'TEAM', worldMode: 'TEAM_INDEPENDENT', label: '队伍独立进行', summary: '同队共用任务进度和资源，各队分别完成；工作人员可开幕、核验到达和引导答题。' },
  { key: 'WORLD_SHARED', icon: 'tab-explore', runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED', label: '队伍共用现场', summary: '各队保留自己的资源，在同一现场交易、投入和表决；工作人员可开幕和引导。' },
];
function nodes(chapters) { return (chapters || []).flatMap((chapter, index) => (chapter.nodes || []).map(node => ({ ...node, chapterIndex: index }))); }
function template(node, details) { return node.templateInfo || (details || {})[node.templateId]; }
function modeKey(game = {}) { return game.runtimeScope === 'PLAYER' ? 'PLAYER' : game.runtimeScope === 'TEAM' ? game.worldMode : ''; }
function modeReason(raw, chapters, matrix, details, mode) {
  const config = support.parse(raw), game = config.gameplayRuntime || {};
  if (mode.runtimeScope === 'PLAYER' && (game.acts || []).some(act => act.hostUnlock)) return '请先关闭章节的工作人员开幕';
  if (mode.worldMode !== 'WORLD_SHARED' && (game.allowTrade || game.votePolicy || (game.plans || []).length
    || (game.facilities || []).some(row => row.scope !== 'OWNER' || row.targetScope !== 'OWNER'))) return '跨队交易、表决和共享设施需要队伍共用现场';
  if (!matrix) return '';
  const next = copy(config); next.gameplayRuntime = { ...game, runtimeScope: mode.runtimeScope };
  if (mode.worldMode) next.gameplayRuntime.worldMode = mode.worldMode; else delete next.gameplayRuntime.worldMode;
  for (const node of nodes(chapters)) {
    const info = template(node, details);
    if (!info && node.templateId) return '正在读取节点玩法，请稍后再选择';
    if (!info) continue;
    const context = support.context(next, node); context.settled = !context.challenge;
    const reason = support.validate(matrix, info, context);
    if (reason) return (node.name || '节点') + '：' + reason;
  }
  return '';
}
function modeOptions(raw, chapters, matrix, details) {
  return MODES.map(mode => ({ ...mode, selected: mode.key === modeKey(support.parse(raw).gameplayRuntime), reason: modeReason(raw, chapters, matrix, details, mode) }));
}
function setMode(raw, key, chapters, matrix, details) {
  const mode = MODES.find(row => row.key === key);
  if (!mode) return { error: '请选择进行方式', field: 'mode' };
  const error = modeReason(raw, chapters, matrix, details, mode);
  if (error) return { error, field: 'mode' };
  const config = copy(support.parse(raw));
  if (!config.schemaVersion) Object.assign(config, { schemaVersion: 1, capacity: 0 });
  const game = config.gameplayRuntime || (config.gameplayRuntime = {});
  game.runtimeScope = mode.runtimeScope;
  if (mode.worldMode) game.worldMode = mode.worldMode; else delete game.worldMode;
  // 与服务端自动编译相同的明确默认策略，已有生存设置原样保留。
  if (!game.survival) game.survival = { zeroHpPolicy: 'BLOCK_HP_COST', actionLimitPolicy: 'BLOCK_OVERFLOW' };
  return { value: config };
}
function syncChapters(raw, chapters) {
  const config = copy(support.parse(raw)), game = config.gameplayRuntime;
  if (!game || !MODES.some(mode => mode.key === modeKey(game))) return { error: '请先选择进行方式', field: 'mode' };
  if (nodes(chapters).some(node => !Number.isSafeInteger(Number(node.id)) || Number(node.id) < 1))
    return { error: '请先保存主题和节点草稿，再设置章节及到达关系', field: 'chapters' };
  const previous = game.quests || [], acts = game.acts || [], nextQuests = [], nextActs = [];
  const zero = game.survival && game.survival.zeroChallenge || {};
  const challengeIds = [zero.challengeNodeId].concat(zero.alternativeNodeIds || []);
  if ((chapters || []).some(chapter => new Set(previous.filter(quest => (chapter.nodes || []).some(node => Number(node.id) === quest.nodeId)).map(quest => quest.actKey)).size > 1))
    return { error: '本章已有多幕配置，请先将幕内任务分别放入对应章节', field: 'acts' };
  let copiedActIndex = 0;
  (chapters || []).forEach((chapter, index) => {
    const members = (chapter.nodes || []).filter(node => !challengeIds.includes(Number(node.id)));
    if (!members.length) return;
    const existing = previous.filter(quest => members.some(node => Number(node.id) === quest.nodeId));
    const actKey = existing[0] && existing[0].actKey || (!previous.length && acts[copiedActIndex++]?.actKey) || (chapter.id ? 'chapter_' + chapter.id : 'chapter_index_' + index);
    const act = copy(acts.find(row => row.actKey === actKey) || { actKey, completionRule: { type: 'ALL' } });
    const rule = (config.chapters || []).find(row => row.index === index);
    if (rule && rule.completion) { act.completionRule = { type: rule.completion.type }; if (rule.completion.type === 'ANY_N') act.completionRule.quantity = rule.completion.quantity; }
    act.questKeys = members.map(node => {
      const id = Number(node.id), old = previous.find(quest => quest.nodeId === id);
      const quest = old ? copy(old) : { questKey: 'node_' + id, nodeId: id, locationKey: 'node_' + id, settlementSlot: 'node_' + id };
      quest.actKey = actKey; nextQuests.push(quest); return quest.questKey;
    });
    nextActs.push(act);
  });
  if (!previous.length) nextActs.push(...acts.slice(copiedActIndex).map(act => ({ ...copy(act), questKeys: [] })));
  game.acts = nextActs; game.quests = nextQuests;
  const keys = new Set(nextQuests.map(row => row.questKey));
  nextQuests.forEach(row => { if (row.prerequisites) row.prerequisites = row.prerequisites.filter(key => keys.has(key)); });
  return { value: config };
}
function locations(raw, chapters) {
  const quests = support.parse(raw).gameplayRuntime?.quests || [], result = [];
  nodes(chapters).filter(node => Number(node.id) > 0).forEach(node => {
    result.push({ value: 'node_' + node.id, label: node.name || '节点 ' + node.id });
  });
  quests.forEach(quest => {
    if (!result.some(row => row.value === quest.locationKey)) {
      const node = nodes(chapters).find(row => Number(row.id) === quest.nodeId);
      result.push({ value: quest.locationKey, label: '已有共用地点（' + (node?.name || '已保存节点') + '）' });
    }
  });
  return result;
}
function validate(raw, chapters) {
  const config = support.parse(raw), game = config.gameplayRuntime;
  if (!game) return null;
  if (!MODES.some(mode => mode.key === modeKey(game))) return { field: 'mode', error: '请选择个人或队伍的进行方式' };
  if (game.runtimeScope === 'PLAYER' && (game.acts || []).some(act => act.hostUnlock)) return { field: 'acts', error: '个人独立进行不能设置工作人员开幕' };
  const all = nodes(chapters), ids = new Set(all.map(node => Number(node.id)).filter(id => id > 0));
  for (const quest of game.quests || []) {
    if (!ids.has(quest.nodeId)) return { field: 'quests', error: '任务对应节点已改变，请保存草稿后重新设置章节及到达关系' };
    const choices = quest.approvedFallbackNodeIds || [];
    if (choices.length > 20 || new Set(choices).size !== choices.length || choices.some(id => !ids.has(id) || id === quest.nodeId))
      return { field: 'quests', nodeId: quest.nodeId, error: '备用节点须从本主题其他节点选择，最多 20 个且不能重复' };
  }
  if ((config.endings || []).length) {
    if (config.endings.length > 30) return { field: 'endings', error: '最多添加 30 个运行结局' };
    const seen = new Set();
    for (let index = 0; index < config.endings.length; index++) {
      const row = config.endings[index];
      if (!/^[a-z][a-z0-9_]{0,31}$/.test(row.id) || seen.has(row.id)) return { field: 'endings', index, error: '结局编号重复或无效，请重新添加该结局' };
      seen.add(row.id);
      if (!String(row.title || '').trim() || row.title.length > 80 || String(row.summary || '').length > 2000)
        return { field: 'endings', index, error: '请填写结局名称（最多 80 字）和说明（最多 2000 字）' };
    }
    if (config.endings.filter(row => row.fallback === true).length !== 1) return { field: 'endings', error: '请指定且仅指定一个默认收尾结局' };
  }
  for (const plan of game.plans || []) {
    if (plan.terminal === false) continue;
    // 结局章或最终章收场（方案未单独链接结局章时以最终章收场）
    if (plan.storyEndingId && (chapters || []).some(ch => Number(ch.id) === plan.endingChapterId)) continue;
    if (!(config.endings || []).some(row => row.id === plan.endingKey))
      return { field: 'endings', error: '表决方案须选择本主题已保存的结局章' };
  }
  return null;
}
function copyReferences(runtime, reselect) {
  const game = runtime.gameplayRuntime;
  if (!game) return;
  if ((game.quests || []).length) {
    reselect.push('通用玩法结构需在副本中重新配置（到达地点、工作人员引导和备用节点）');
    game.quests = [];
    (game.acts || []).forEach(act => { act.questKeys = []; });
  }
  if (Array.isArray(game.facilities)) game.facilities = game.facilities.filter(row => !/^tpl_node_[1-9][0-9]*$/.test(row.facilityKey));
  const metadata = game.templateTools;
  if (metadata) {
    const generated = new Set((metadata.nodes || []).flatMap(row => row.planKeys || []));
    if (game.plans) game.plans = game.plans.filter(row => !generated.has(row.endingKey));
    for (const [field, baseline] of [['allowTrade', 'baselineAllowTrade'], ['votePolicy', 'baselineVotePolicy']]) {
      if (Object.prototype.hasOwnProperty.call(metadata, baseline)) game[field] = metadata[baseline]; else delete game[field];
    }
    delete game.templateTools;
  }
}
const data = { structureEditor: { show: false, kind: '', title: '' }, structureDraft: null, structureError: '', structureErrorField: '', structureModeOptions: [], structureLocations: [], structureFallbackNodes: [], structureGuideAllowed: false, structureLocationIndex: -1, chapterHostUnlock: false, chapterHostUnlockDisabledReason: '', structureSummary: { modeLabel: '未选择', endingsText: '未配置', endingsError: '' }, nodeStructureSummary: '未配置', structureEndings: [], structureReferenceNotice: '', structureFallbackCount: 0, structureAlts: altRows(), structureFallbackOpen: false, structureFallbackSummary: '', structureSaving: false };
const methods = {
  _structureAltContext() {
    const app = getApp(), session = app.getSession && app.getSession();
    return JSON.stringify([app.getUserID && app.getUserID(), app.getAuthorization && app.getAuthorization(),
      session && session.getRevision && session.getRevision(), this.data.operationScope,
      this.data.editingTopicId, this._editLoadToken]);
  },
  _resetStructureAlts() {
    this._altContext = this._structureAltContext();
    this._altDrafts = {}; this._altWritePending = {}; this._altDirty = {};
    this._altEditorGeneration = (this._altEditorGeneration || 0) + 1;
    this.setData({ structureAlts: altRows(), structureSaving: false });
  },
  _hydrateStructureAlts(detail, chapters) {
    this._resetStructureAlts();
    this._altDrafts = alternativeDrafts(detail, chapters);
  },
  _structureAltsReady(nodeId) {
    if (this._altContext !== this._structureAltContext()) this._resetStructureAlts();
    return Object.prototype.hasOwnProperty.call(this._altDrafts, nodeId);
  },
  _refreshStructureSummary() {
    const config = support.parse(this.data.formData.themeRuntimeConfig), game = config.gameplayRuntime || {};
    const mode = MODES.find(row => row.key === modeKey(game)), endings = config.endings || [];
    const fallback = endings.find(row => row.fallback === true), issue = validate(config, this.data.formData.chapters);
    const chapter = this.data.popChapterAction === 0 ? null : (this.data.formData.chapters || [])[this.data.popChapterIndex];
    const quest = (game.quests || []).find(row => (chapter?.nodes || []).some(node => Number(node.id) === row.nodeId));
    const act = (game.acts || []).find(row => row.actKey === quest?.actKey);
    const current = (game.quests || []).find(row => row.nodeId === Number(this.data.nodesForm?.id));
    const place = locations(config, this.data.formData.chapters).find(row => row.value === current?.locationKey);
    this.setData({ structureSummary: { modeLabel: mode?.label || '未选择',
      endingsText: endings.length ? endings.length + ' 个 · 默认收尾：' + (fallback?.title || '未选择') : '未配置',
      endingsError: issue?.field === 'endings' ? issue.error : '' },
      chapterHostUnlock: act?.hostUnlock === true,
      chapterHostUnlockDisabledReason: !act ? '请先在主题详情里选进行方式并保存' : game.runtimeScope === 'PLAYER' ? '个人独立进行不使用工作人员开幕' : '',
      nodeStructureSummary: !current ? '未配置' : (() => {
        if (!this._structureAltsReady(current.nodeId)) return place?.label || '需重选';
        const alts = altRows((this._altDrafts || {})[current.nodeId]).filter(row => row.on).map(row => ALT_WORD[row.type]);
        return alts.length ? '可' + alts.join('或') : (place?.label || '需重选');
      })(),
      structureModeOptions: modeOptions(config, this.data.formData.chapters, this.data.supportMatrix, this._templateDetailsById),
    });
  },
  _refreshStructureEndings() {
    this.setData({ structureEndings: (this.data.structureDraft?.endings || []).map(row => ({ ...row, isFallback: row.fallback === true })) });
  },
  onChapterHostUnlock(e) {
    const synced = syncChapters(this.data.formData.themeRuntimeConfig, this.data.formData.chapters);
    if (synced.error) return this.setData({ structureError: synced.error, structureErrorField: synced.field, chapterHostUnlockDisabledReason: synced.error });
    const config = synced.value, game = config.gameplayRuntime;
    const chapter = this.data.popChapterAction === 0 ? null : (this.data.formData.chapters || [])[this.data.popChapterIndex];
    const quest = game.quests.find(row => (chapter?.nodes || []).some(node => Number(node.id) === row.nodeId));
    const act = game.acts.find(row => row.actKey === quest?.actKey);
    const reason = !act ? '本章没有普通任务节点，请先添加并保存节点' : game.runtimeScope === 'PLAYER' ? '个人独立进行不能设置工作人员开幕' : '';
    // 关闭永远允许（可能是旧数据处于不合法状态，要让作者能自己关掉）；只拦“打开”。
    if (reason && (!act || e.detail.value)) return this.setData({ structureError: reason, structureErrorField: 'acts', chapterHostUnlockDisabledReason: reason });
    act.hostUnlock = !!e.detail.value;
    this._saveRuntime(config); this.setData({ structureError: '', structureErrorField: '' });
  },
  openStructure(e) {
    this._altEditorGeneration = (this._altEditorGeneration || 0) + 1;
    const ds = e && e.currentTarget && e.currentTarget.dataset || {}, kind = ds.kind || 'mode';
    let config = copy(support.parse(this.data.formData.themeRuntimeConfig));
    const editor = { show: true, kind, title: { mode: '进行方式', acts: '章节开幕', quests: '到达与工作人员引导', endings: '运行结局' }[kind], chapterIndex: Number(ds.index ?? this.data.popChapterIndex) };
    if (kind === 'mode') {
      if (!config.gameplayRuntime) config.gameplayRuntime = {};
      if (ds.nextKind === 'quests') editor.nextKind = 'quests';
    }
    let error = '';
    if (kind === 'acts' || kind === 'quests') {
      const synced = syncChapters(config, this.data.formData.chapters); error = synced.error || '';
      if (kind === 'quests' && synced.field === 'mode') return this.openStructure({ currentTarget: { dataset: { kind: 'mode', nextKind: 'quests', index: editor.chapterIndex } } });
      if (synced.value) config = synced.value;
      if (kind === 'acts') {
        const chapter = this.data.formData.chapters[editor.chapterIndex];
        const first = chapter && (chapter.nodes || []).find(node => (config.gameplayRuntime?.quests || []).some(row => row.nodeId === Number(node.id)));
        editor.actKey = first && config.gameplayRuntime.quests.find(row => row.nodeId === Number(first.id)).actKey;
        if (!error && !editor.actKey) error = '本章没有普通任务节点，请先添加并保存节点';
        editor.hostUnlock = (config.gameplayRuntime?.acts || []).find(row => row.actKey === editor.actKey)?.hostUnlock === true;
      } else {
        editor.nodeId = Number(this.data.nodesForm.id);
        const quest = (config.gameplayRuntime?.quests || []).find(row => row.nodeId === editor.nodeId);
        if (!error && !quest) error = editor.nodeId > 0 ? '归零挑战站不设置到达与引导' : '先点「完成」保存这个节点，再设置到达与引导';
        editor.quest = quest ? copy(quest) : null;
      }
    }
    const info = template(this.data.nodesForm || {}, this._templateDetailsById);
    const advanced = support.config(info);
    const allowed = this.data.supportMatrix && support.mains(this.data.supportMatrix, advanced).some(row => ['qaText', 'qaPick', 'sort', 'match', 'classify'].includes(row.key));
    const locationRows = locations(config, this.data.formData.chapters), quest = editor.quest || {};
    const fallbackNodes = nodes(this.data.formData.chapters).filter(node => Number(node.id) > 0 && Number(node.id) !== editor.nodeId)
      .map(node => ({ id: Number(node.id), label: node.name || '节点 ' + node.id, selected: (quest.approvedFallbackNodeIds || []).includes(Number(node.id)) }));
    const altsReady = this._structureAltsReady(editor.nodeId), pending = (this._altWritePending || {})[editor.nodeId];
    if (kind === 'quests' && !error && !altsReady) error = '换个方式配置未读取，请重新读取主题后再修改';
    this.setData({ structureEditor: editor, structureDraft: config, structureError: error, structureErrorField: error ? kind : '',
      structureModeOptions: modeOptions(config, this.data.formData.chapters, this.data.supportMatrix, this._templateDetailsById),
      structureLocations: locationRows, structureLocationIndex: locationRows.findIndex(row => row.value === quest.locationKey),
      structureGuideAllowed: !!allowed, structureFallbackCount: (quest.approvedFallbackNodeIds || []).length,
      structureFallbackNodes: fallbackNodes, structureFallbackOpen: false,
      structureFallbackSummary: fallbackNodes.filter(node => node.selected).map(node => node.label).join('、'),
      structureAlts: pending ? copy(pending.rows) : altRows((this._altDrafts || {})[editor.nodeId]), structureSaving: false,
    });
    this._altDirty = pending ? copy(pending.dirty) : {};
    this._refreshStructureEndings();
  },
  closeStructure() { this._altEditorGeneration = (this._altEditorGeneration || 0) + 1; this.setData({ 'structureEditor.show': false, structureSaving: false }); },
  chooseStructureMode(e) {
    const result = setMode(this.data.structureDraft, e.currentTarget.dataset.key, this.data.formData.chapters, this.data.supportMatrix, this._templateDetailsById);
    if (result.error) return this.setData({ structureError: result.error, structureErrorField: result.field });
    this.setData({ structureDraft: result.value, structureError: '', structureErrorField: '', structureModeOptions: modeOptions(result.value, this.data.formData.chapters, this.data.supportMatrix, this._templateDetailsById) });
  },
  onStructureLocation(e) {
    const index = Number(e.detail.value), row = this.data.structureLocations[index]; if (!row || !this.data.structureEditor.quest) return;
    this.setData({ 'structureEditor.quest.locationKey': row.value, structureLocationIndex: index, structureError: '' });
  },
  onStructureGuide(e) {
    const quest = copy(this.data.structureEditor.quest); if (!quest) return;
    if (e.detail.value && !this.data.structureGuideAllowed) return this.setData({ structureError: '请选择已有客观题玩法；引导使用该玩法原有题目和答案', structureErrorField: 'guide' });
    if (e.detail.value) quest.guideKey = quest.guideKey || 'guide_' + quest.nodeId; else delete quest.guideKey;
    this.setData({ 'structureEditor.quest': quest, structureError: '' });
  },
  toggleStructureFallback(e) {
    const id = Number(e.currentTarget.dataset.id), rows = copy(this.data.structureFallbackNodes), row = rows.find(node => node.id === id);
    if (!row || !this.data.structureEditor.quest) return;
    row.selected = !row.selected;
    if (rows.filter(node => node.selected).length > 20) return this.setData({ structureError: '最多选择 20 个备用节点', structureErrorField: 'fallback' });
    this.setData({ structureFallbackNodes: rows, structureFallbackCount: rows.filter(node => node.selected).length, 'structureEditor.quest.approvedFallbackNodeIds': rows.filter(node => node.selected).map(node => node.id), structureError: '',
      structureFallbackSummary: rows.filter(node => node.selected).map(node => node.label).join('、') });
  },
  toggleStructureFallbackOpen() { this.setData({ structureFallbackOpen: !this.data.structureFallbackOpen }); },
  onStructureAltToggle(e) {
    if (this.data.structureSaving) return;
    if (!this._structureAltsReady(this.data.structureEditor.nodeId)) return this.setData({ structureError: '换个方式配置未读取，请重新读取主题后再修改', structureErrorField: 'alternative' });
    const type = e.currentTarget.dataset.type, rows = copy(this.data.structureAlts), row = rows.find(item => item.type === type);
    if (!row) return;
    row.on = !!e.detail.value; this._altDirty = Object.assign({}, this._altDirty, { [type]: true });
    this.setData({ structureAlts: rows, structureError: '' });
  },
  onStructureAltInput(e) {
    if (this.data.structureSaving) return;
    if (!this._structureAltsReady(this.data.structureEditor.nodeId)) return this.setData({ structureError: '换个方式配置未读取，请重新读取主题后再修改', structureErrorField: 'alternative' });
    const ds = e.currentTarget.dataset, rows = copy(this.data.structureAlts), row = rows.find(item => item.type === ds.type);
    if (!row || !['prompt', 'answer'].includes(ds.field)) return;
    row[ds.field] = e.detail.value; this._altDirty = Object.assign({}, this._altDirty, { [ds.type]: true });
    this.setData({ structureAlts: rows });
  },
  // 只发本次改过的方式；一条失败就停在这张半屏，原样显示后端那句话。
  _saveStructureAlts(nodeId, done) {
    const rows = copy(this.data.structureAlts), dirty = rows.filter(row => (this._altDirty || {})[row.type]);
    const topicId = this.data.editingTopicId;
    if (!dirty.length) return done();
    if (!topicId) return this.setData({ structureError: '请先保存主题草稿，再设置换个方式', structureErrorField: 'alternative' });
    if (!this._structureAltsReady(nodeId)) return this.setData({ structureError: '换个方式配置未读取，请重新读取主题后再修改', structureErrorField: 'alternative' });
    const missing = dirty.find(row => row.on && (row.type === 'QUIZ' ? !row.prompt.trim() || !row.answer.trim() && !row.answerConfigured : row.type === 'PHOTO' && !row.prompt.trim()));
    if (missing) return this.setData({ structureError: missing.type === 'QUIZ' ? '请填写题目和答案' : '请填写拍摄内容', structureErrorField: 'alternative' });
    if (dirty.some(row => row.on && row.type === 'SCAN' && !row.answerConfigured))
      return this.setData({ structureError: '扫码尚未配置有效码，请先完成扫码配置', structureErrorField: 'alternative' });
    const context = this._structureAltContext(), generation = this._altEditorGeneration, epoch = this._loginContinuationEpoch;
    const current = () => !this._unloaded && !this._hidden && context === this._structureAltContext()
      && generation === this._altEditorGeneration && epoch === this._loginContinuationEpoch
      && this.data.structureEditor.show && this.data.structureEditor.nodeId === nodeId && Number(this.data.nodesForm?.id) === nodeId;
    this._altWritePending[nodeId] = { rows, dirty: copy(this._altDirty) };
    const failed = res => {
      if (current()) this.setData({ structureSaving: false, structureError: (res && res.msg) || '没有保存成功，请再试一次。', structureErrorField: 'alternative' });
    };
    this.setData({ structureSaving: true });
    const next = index => {
      if (!current()) return;
      if (index >= dirty.length) {
        return getApp().sendRequest({ url: '/api/topic/author-saved-detail', method: 'POST', hideLoading: true, silentError: true,
          data: { id: topicId, scope: this.data.operationScope },
          success: res => {
            if (!current()) return;
            const detail = res && res.data, chapters = detail && detail.chapters;
            const fetched = detail && alternativeDrafts(detail, this.data.formData.chapters);
            if (String(res?.code) !== '200' || String(detail?.topic?.id) !== String(topicId)
              || !Array.isArray(chapters) || chapters.some(chapter => !chapter || !Array.isArray(chapter.cmsTopicNodeList))
              || !chapters.some(chapter => chapter.cmsTopicNodeList.some(node => node && Number(node.id) === nodeId))
              || !fetched || !Object.prototype.hasOwnProperty.call(fetched, nodeId))
              return failed({ msg: res?.msg || '配置回读未确认，请重试或重新读取主题' });
            // 先保留本次输入，只有读回每条已写配置后才清除待恢复内容。
            const saved = fetched[nodeId];
            if (dirty.some(row => !saved[row.type] || saved[row.type].on !== row.on || saved[row.type].prompt !== row.prompt.trim()
              || row.type !== 'PHOTO' && (row.answerConfigured || row.answer.trim()) && !saved[row.type].answerConfigured))
              return failed({ msg: '配置回读与本次保存不一致，请重试' });
            this._altDrafts = fetched;
            delete this._altWritePending[nodeId]; this._altDirty = {};
            this.setData({ structureAlts: altRows(this._altDrafts[nodeId]), structureSaving: false }); done();
          }, fail: failed, successStatusAbnormal: failed });
      }
      const row = dirty[index], data = { topicId, taskId: nodeId, alternativeTaskType: row.type, promptText: row.prompt, enabled: row.on };
      if (row.type === 'QUIZ' && row.answer.trim()) data.answer = row.answer;
      getApp().sendRequest({ url: '/api/orient/task/alternative', method: 'POST', hideLoading: true, silentError: true,
        data: JSON.stringify(data), header: { 'Content-Type': 'application/json' },
        success: res => {
          if (!current()) return;
          if (res && String(res.code) === '200') return next(index + 1);
          failed(res);
        },
        fail: failed, successStatusAbnormal: failed });
    };
    next(0);
  },
  addStructureEnding() {
    const config = copy(this.data.structureDraft), rows = config.endings || (config.endings = []);
    if (rows.length >= 30) return this.setData({ structureError: '最多添加 30 个运行结局', structureErrorField: 'endings' });
    let n = 1; while (rows.some(row => row.id === 'ending_' + n)) n++;
    rows.push({ id: 'ending_' + n, title: '', summary: '', fallback: rows.length === 0 });
    this.setData({ structureDraft: config, structureError: '' }); this._refreshStructureEndings();
  },
  onStructureEnding(e) {
    const ds = e.currentTarget.dataset, config = copy(this.data.structureDraft), row = config.endings[Number(ds.index)];
    if (!row || !['title', 'summary'].includes(ds.field)) return;
    row[ds.field] = e.detail.value; this.setData({ structureDraft: config, structureError: '' }); this._refreshStructureEndings();
  },
  chooseStructureFallbackEnding(e) {
    const config = copy(this.data.structureDraft), index = Number(e.currentTarget.dataset.index);
    if (!config.endings[index]) return;
    config.endings.forEach((row, i) => { row.fallback = i === index; }); this.setData({ structureDraft: config, structureError: '' }); this._refreshStructureEndings();
  },
  removeStructureEnding(e) {
    const config = copy(this.data.structureDraft), index = Number(e.currentTarget.dataset.index), row = config.endings[index]; if (!row) return;
    const used = (config.gameplayRuntime?.plans || []).some(plan => plan.endingKey === row.id)
      || nodes(this.data.formData.chapters).some(node => (support.config(template(node, this._templateDetailsById)).interactionTools?.plans || []).some(plan => plan.endingKey === row.id));
    if (used) return this.setData({ structureError: '该结局正在被表决方案使用，请先修改对应方案', structureErrorField: 'endings' });
    config.endings.splice(index, 1); this.setData({ structureDraft: config, structureError: '' }); this._refreshStructureEndings();
  },
  saveStructure() {
    const editor = this.data.structureEditor, config = copy(this.data.structureDraft);
    if ((editor.kind === 'acts' && !editor.actKey) || (editor.kind === 'quests' && !editor.quest)) return;
    if (editor.kind === 'quests') config.gameplayRuntime.quests = config.gameplayRuntime.quests.map(row => row.nodeId === editor.nodeId ? copy(editor.quest) : row);
    if (!config.schemaVersion) Object.assign(config, { schemaVersion: 1, capacity: 0 });
    const issue = validate(config, this.data.formData.chapters);
    if (issue) return this.setData({ structureError: issue.error, structureErrorField: issue.field });
    if (this.data.structureSaving) return;
    const finish = () => {
      this._saveRuntime(config); this.setData({ 'structureEditor.show': false, structureError: '' }); this._syncRuntimeStats();
      if (editor.kind === 'mode' && editor.nextKind === 'quests') this.openStructure({ currentTarget: { dataset: { kind: 'quests', index: editor.chapterIndex } } });
    };
    if (editor.kind === 'quests') return this._saveStructureAlts(editor.nodeId, finish);
    finish();
  },
  _syncStructureChapters(chapters) {
    const raw = this.data.formData.themeRuntimeConfig, game = support.parse(raw).gameplayRuntime;
    if (!game || !(game.acts || []).length) return;
    const result = syncChapters(raw, chapters || this.data.formData.chapters); if (result.value) {
      const remaining = new Set(result.value.gameplayRuntime.acts.map(row => row.actKey));
      const removed = (game.acts || []).map(row => row.actKey).filter(key => !remaining.has(key));
      const notices = removed.length && typeof this._pruneSurvivalActRefs === 'function' ? this._pruneSurvivalActRefs(result.value, removed) : [];
      this._saveRuntime(result.value);
      this.setData({ structureReferenceNotice: (notices || []).join('；') });
      if (notices?.length) modal.show({ title: '请重新选择章节', content: notices.join('；'), showCancel: false });
    }
  },
  _applyStructureChapterCompletion(index, completion) {
    const config = copy(support.parse(this.data.formData.themeRuntimeConfig)), game = config.gameplayRuntime;
    if (!game) return;
    const chapter = (this.data.formData.chapters || [])[index];
    const keys = new Set((game.quests || []).filter(row => (chapter?.nodes || []).some(node => Number(node.id) === row.nodeId)).map(row => row.actKey));
    for (const act of game.acts || []) if (keys.has(act.actKey)) {
      act.completionRule = { type: completion?.type || 'ALL' };
      if (completion?.type === 'ANY_N') act.completionRule.quantity = completion.quantity;
    }
    this._saveRuntime(config);
  },
  _locateStructureIssue(field) {
    const issue = this._structureIssue() || {}, chapters = this.data.formData.chapters || [];
    if (field === 'quests') {
      const all = nodes(chapters), target = all.find(row => Number(row.id) === issue.nodeId) || all[0];
      if (target) this.showEditNodes({ currentTarget: { dataset: { chapterindex: target.chapterIndex, nodeindex: chapters[target.chapterIndex].nodes.findIndex(row => Number(row.id) === Number(target.id)) } } });
      this.openStructure({ currentTarget: { dataset: { kind: 'quests' } } });
    } else if (field === 'acts' || field === 'chapters') {
      const config = support.parse(this.data.formData.themeRuntimeConfig), act = (config.gameplayRuntime?.acts || []).find(row => row.hostUnlock);
      const quest = (config.gameplayRuntime?.quests || []).find(row => row.actKey === act?.actKey);
      const index = Math.max(0, chapters.findIndex(ch => (ch.nodes || []).some(row => Number(row.id) === quest?.nodeId)));
      if (chapters[index]) this.showEditChapter({ currentTarget: { dataset: { index } } });
      this.setData({ structureError: issue.error || '', structureErrorField: field });
    } else this.openStructure({ currentTarget: { dataset: { kind: field === 'mode' ? 'mode' : 'endings' } } });
  },
  _structureIssue() { return validate(this.data.formData.themeRuntimeConfig, this.data.formData.chapters); },
};
module.exports = { MODES, modeKey, modeOptions, setMode, syncChapters, locations, validate, copyReferences, data, methods };
