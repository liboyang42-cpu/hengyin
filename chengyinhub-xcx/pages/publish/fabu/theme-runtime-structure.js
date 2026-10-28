const support = require('../utils/publish/gameplay-support-matrix.js');
const modal = require('../../../utils/modal.js');
const cyToast = require('../../../utils/toast.js');
const copy = value => JSON.parse(JSON.stringify(value));
// 找不到时换个方式（10-28 e-quest）：扫码 / 答题 / 拍照，存 POST /api/orient/task/alternative。
// 后端只存答案摘要、没有作者读回接口，所以本次会话里填过的才回显（需后端，见 PR）。
const ALTS = [{ type: 'SCAN', label: '扫码' }, { type: 'QUIZ', label: '答题' }, { type: 'PHOTO', label: '拍照' }];
const ALT_WORD = { SCAN: '扫码', QUIZ: '答题', PHOTO: '拍照' };
function altRows(draft) {
  return ALTS.map(row => Object.assign({ type: row.type, label: row.label, on: false, prompt: '', answer: '' }, (draft || {})[row.type]));
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
  if (!game || !game.runtimeScope) return { error: '请先选择进行方式', field: 'mode' };
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
    const ds = e && e.currentTarget && e.currentTarget.dataset || {}, kind = ds.kind || 'mode';
    let config = copy(support.parse(this.data.formData.themeRuntimeConfig));
    const editor = { show: true, kind, title: { mode: '进行方式', acts: '章节开幕', quests: '到达与工作人员引导', endings: '运行结局' }[kind], chapterIndex: Number(ds.index ?? this.data.popChapterIndex) };
    let error = '';
    if (kind === 'acts' || kind === 'quests') {
      const synced = syncChapters(config, this.data.formData.chapters); error = synced.error || '';
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
        if (!error && !quest) error = '请先保存本站草稿；归零挑战不设置普通任务到达关系';
        editor.quest = quest ? copy(quest) : null;
      }
    }
    const info = template(this.data.nodesForm || {}, this._templateDetailsById);
    const advanced = support.config(info);
    const allowed = this.data.supportMatrix && support.mains(this.data.supportMatrix, advanced).some(row => ['qaText', 'qaPick', 'sort', 'match', 'classify'].includes(row.key));
    const locationRows = locations(config, this.data.formData.chapters), quest = editor.quest || {};
    const fallbackNodes = nodes(this.data.formData.chapters).filter(node => Number(node.id) > 0 && Number(node.id) !== editor.nodeId)
      .map(node => ({ id: Number(node.id), label: node.name || '节点 ' + node.id, selected: (quest.approvedFallbackNodeIds || []).includes(Number(node.id)) }));
    this.setData({ structureEditor: editor, structureDraft: config, structureError: error, structureErrorField: error ? kind : '',
      structureModeOptions: modeOptions(config, this.data.formData.chapters, this.data.supportMatrix, this._templateDetailsById),
      structureLocations: locationRows, structureLocationIndex: locationRows.findIndex(row => row.value === quest.locationKey),
      structureGuideAllowed: !!allowed, structureFallbackCount: (quest.approvedFallbackNodeIds || []).length,
      structureFallbackNodes: fallbackNodes, structureFallbackOpen: false,
      structureFallbackSummary: fallbackNodes.filter(node => node.selected).map(node => node.label).join('、'),
      structureAlts: altRows((this._altDrafts || {})[editor.nodeId]), structureSaving: false,
    });
    this._altDirty = {};
    this._refreshStructureEndings();
  },
  closeStructure() { this.setData({ 'structureEditor.show': false }); },
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
    const type = e.currentTarget.dataset.type, rows = copy(this.data.structureAlts), row = rows.find(item => item.type === type);
    if (!row) return;
    row.on = !!e.detail.value; this._altDirty = Object.assign({}, this._altDirty, { [type]: true });
    this.setData({ structureAlts: rows, structureError: '' });
  },
  onStructureAltInput(e) {
    const ds = e.currentTarget.dataset, rows = copy(this.data.structureAlts), row = rows.find(item => item.type === ds.type);
    if (!row || !['prompt', 'answer'].includes(ds.field)) return;
    row[ds.field] = e.detail.value; this._altDirty = Object.assign({}, this._altDirty, { [ds.type]: true });
    this.setData({ structureAlts: rows });
  },
  // 只发本次改过的方式；一条失败就停在这张半屏，原样显示后端那句话。
  _saveStructureAlts(nodeId, done) {
    const dirty = this.data.structureAlts.filter(row => (this._altDirty || {})[row.type]);
    const topicId = this.data.editingTopicId;
    if (!dirty.length) return done();
    if (!topicId) return this.setData({ structureError: '请先保存主题草稿，再设置换个方式', structureErrorField: 'alternative' });
    const missing = dirty.find(row => row.on && (row.type === 'QUIZ' ? !row.prompt.trim() || !row.answer.trim() : row.type === 'PHOTO' && !row.prompt.trim()));
    if (missing) return this.setData({ structureError: missing.type === 'QUIZ' ? '请填写题目和答案' : '请填写拍摄内容', structureErrorField: 'alternative' });
    this.setData({ structureSaving: true });
    const next = index => {
      if (index >= dirty.length) {
        this._altDrafts = Object.assign({}, this._altDrafts, { [nodeId]: Object.fromEntries(this.data.structureAlts.map(row => [row.type, row])) });
        this._altDirty = {}; this.setData({ structureSaving: false }); return done();
      }
      const row = dirty[index], data = { topicId, taskId: nodeId, alternativeTaskType: row.type, promptText: row.prompt, enabled: row.on };
      if (row.type === 'QUIZ' && row.on) data.answer = row.answer;
      getApp().sendRequest({ url: '/api/orient/task/alternative', method: 'POST', hideLoading: true, silentError: true,
        data: JSON.stringify(data), header: { 'Content-Type': 'application/json' },
        success: res => {
          if (res && String(res.code) === '200') return next(index + 1);
          this.setData({ structureSaving: false, structureError: (res && res.msg) || '没有保存成功，请再试一次。', structureErrorField: 'alternative' });
        },
        fail: () => { this.setData({ structureSaving: false }); cyToast('没有保存成功，请再试一次。'); } });
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
    const finish = () => { this._saveRuntime(config); this.setData({ 'structureEditor.show': false, structureError: '' }); this._syncRuntimeStats(); };
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
