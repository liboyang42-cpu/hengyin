const story = require('./pro-editor-story.js');
const storyEnding = require('./story-ending.js');
const { optionTagsOf, parse: parseInitialState } = require('./story-initial-state.js');
const { buildPreviewKit } = require('./advanced-game-preview.js');

/** 伏笔(journeyRules.thoughts):试玩读到念头块时要显示名字与说明。读不出来当没配。 */
function previewThoughts(raw) {
  let rules;
  try { rules = typeof raw === 'string' && raw.trim() ? JSON.parse(raw) : (raw || {}); } catch (e) { return []; }
  return (Array.isArray(rules && rules.thoughts) ? rules.thoughts : [])
    .filter(t => t && t.key)
    .map(t => ({ key: String(t.key), name: String(t.name || ''), desc: String(t.desc || ''), need: Number(t.need) || 0 }));
}

/** 结局(W-15):试玩末尾给作者看每个结局长什么样、什么时候触发。读不出来当没配。 */
/** 试玩结局卡上那句「触发:……」 */
function endingCondLabel(form, e) {
  const thoughtName = {};
  previewThoughts(form.journeyRules).forEach(t => { thoughtName[t.key] = t.name; });
  const nodeName = {};
  (form.chapters || []).forEach(c => (c.nodes || []).forEach(n => { if (n.id != null) nodeName[String(n.id)] = n.name; }));
  if (e.fallback) return '其它结局都不中时';
  if (e.kind === 'thought') return '想通「' + (thoughtName[e.thoughtKey] || e.thoughtKey) + '」';
  if (e.kind === 'node') return '到过「' + (nodeName[String(e.nodeId)] || '某一站') + '」';
  if (e.kind === 'tag') {
    const hit = optionTagsOf(form.chapters).find(t => t.tag === e.tag);
    return '选过「' + (hit ? hit.label : e.tag) + '」';
  }
  return '自定义条件';
}

/** 老数据:结局清单在 journeyStory 里(没有结局章)时照旧只出标题 + 一句话 */
function previewEndings(form) {
  let list;
  try { list = storyEnding.parse(form.journeyStory).list.filter(e => e.title); } catch (e) { return []; }
  return list.map(e => ({ code: e.code, title: e.title, summary: e.summary || '', cond: endingCondLabel(form, e), fallback: !!e.fallback }));
}

/** 条件正文 / 条件内心声音在试玩里全部亮出来,并标上「只给谁看」—— 试玩没有真实的选择可比对。 */
function condLabels(form) {
  const out = {};
  optionTagsOf(form.chapters).forEach(t => { out[t.tag] = '选过「' + t.label + '」才看到'; });
  previewThoughts(form.journeyRules).forEach(t => { out['thought.' + t.key + '.done'] = '想通「' + t.name + '」才看到'; });
  return out;
}

function buildTopicPreview(form) {
  if (form.routeMode === 'BRANCH_GRAPH') throw new Error('分支路线暂不支持整局预览，可先预览单个玩法');
  const conds = condLabels(form);
  const nodes = [];
  let chapterNumber = 0;
  const all = (form.chapters || []).map((source, chapterIndex) => {
    const chapter = story.toPayloadChapter(story.materializeChapter(source));
    const chapterId = chapterIndex + 1;
    const chapterNodes = chapter.nodes.map(node => {
      const template = node.templateInfo || {};
      let config = null;
      try {
        config = typeof template.advancedConfigJson === 'string'
          ? (template.advancedConfigJson.trim() ? JSON.parse(template.advancedConfigJson) : null)
          : template.advancedConfigJson || null;
        if (config && (typeof config !== 'object' || Array.isArray(config))) throw new Error();
      } catch (_) { throw new Error((node.name || '玩法') + '的配置无法读取，请重新选择模板'); }
      if (node.templateId && template.validationMethod == null && !config) {
        throw new Error('请重新选择「' + (node.name || '玩法') + '」的模板，载入完整配置');
      }
      const row = Object.assign({}, template, node, {
        nodeId: nodes.length + 1, chapterId, sortId: nodes.length + 1,
        validationMethod: template.validationMethod == null ? node.validationMethod : template.validationMethod,
        gameTitle: template.title || '', question: template.questionName || '',
        options: Object.fromEntries(['A', 'B', 'C', 'D'].filter(key => template['question' + key])
          .map(key => [key, template['question' + key]])),
        advancedConfigJson: config ? JSON.stringify(config) : '',
        previewKit: buildPreviewKit(config), done: false,
      });
      delete row.templateInfo;
      nodes.push(row);
      return row;
    });
    if (!chapter.opening && !source.ending) chapterNumber += 1;
    return Object.assign({}, chapter, { id: chapterId, chapterId,
      idxLabel: chapter.opening ? '序章' : (source.ending ? '结局' : (chapter.idxLabel || ('第 ' + chapterNumber + ' 章'))), nodes: undefined,
      blocks: chapter.blocks.map(block => block.type === 'node'
        ? Object.assign({}, block, { nodeId: chapterNodes[block.nodeIndex].nodeId })
        : (block.when ? Object.assign({}, block, { condLabel: conds[block.when.value] || '有条件才看到' }) : block)) });
  });
  // 结局章(2026-09-24)不进试玩的章节顺序:读完最后一章才按结局播;每条结局带上它那一章
  const sources = form.chapters || [];
  const route = all.filter((c, i) => !sources[i].ending);
  const endingChapters = sources.map((c, i) => ({ source: c, chapter: all[i] })).filter(x => x.source.ending);
  const endings = endingChapters.length
    ? endingChapters.map((x, i) => ({ code: 'ch' + (i + 1), title: x.source.name || ('结局 ' + (i + 1)), summary: '',
      cond: endingCondLabel(form, x.source.ending), fallback: !!x.source.ending.fallback, chapter: x.chapter }))
    : previewEndings(form);
  if (!route.length) throw new Error('先添加一个章节再预览');
  return { topicId: '', topicName: form.name || '草稿预览', topicDesc: form.description || '',
    mode: Number(form.productType) === 2 ? 2 : 1, chapters: route, nodes, startStory: true,
    thoughts: previewThoughts(form.journeyRules), endings, startState: previewStartState(form.journeyRules),
    look: form.look || '' };  // 画面风格:预览也要看到作者刚选的风格,不能回落默认
}

/** 试玩的初始状态页:取作者配的起始值(精力 / 幸运开了才列,可见属性全列) */
function previewStartState(raw) {
  let rules;
  try { rules = parseInitialState(raw); } catch (e) { return []; }
  if (!rules.stateEnabled) return [];
  const rows = [];
  if (rules.hp && rules.hp.enabled) rows.push({ label: '精力', value: rules.hp.init });
  if (rules.luck && rules.luck.enabled) rows.push({ label: '幸运', value: rules.luck.init });
  (Array.isArray(rules.attributes) ? rules.attributes : []).forEach((a) => {
    if (a && a.visible && a.label) rows.push({ label: String(a.label), value: Number(a.initial) || 0 });
  });
  return rows;
}

module.exports = { buildTopicPreview };
