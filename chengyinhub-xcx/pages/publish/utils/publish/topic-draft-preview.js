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
  if (form.routeMode === 'BRANCH_GRAPH') throw new Error('分支路线的整局预览尚未接通，可先从玩法配置预览单个游戏');
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
    themeOverview: buildThemeOverview(form) };
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

/** Local CONFIG projection, not the Java engine. Never forward config, identifiers or media. */
function buildThemeOverview(form) {
  const raw = form.themeRuntimeConfig;
  if (raw == null || raw === '') return null;
  const unavailable = () => ({ kind: 'CONFIG', version: 1, clipped: false, rows: [
    { section: '说明', label: '配置清单暂不可读', detail: '请返回编辑器检查主题配置。故事预览仍可继续；这里没有运行系统校验。' }
  ] });
  try {
    if (typeof raw !== 'string' || raw.length > 200000) return unavailable();
    const root = JSON.parse(raw);
    if (!root || typeof root !== 'object' || Array.isArray(root) || root.schemaVersion !== 1) return unavailable();
    // Bound traversal even for unknown nested fields; none of those fields are projected.
    const stack = [[root, 0]]; let visited = 0;
    while (stack.length) {
      const [value, depth] = stack.pop();
      if (++visited > 20000 || depth > 32) return unavailable();
      if (value && typeof value === 'object') Object.keys(value).forEach(k => stack.push([value[k], depth + 1]));
    }
    const has = (o, k) => Object.prototype.hasOwnProperty.call(o, k);
    const object = o => { if (!o || typeof o !== 'object' || Array.isArray(o)) throw new Error(); return o; };
    const list = (o, k, limit) => {
      if (o[k] == null) return [];
      if (!Array.isArray(o[k]) || o[k].length > limit) throw new Error();
      return o[k];
    };
    const rows = []; let clipped = false;
    const text = (value, limit) => {
      if (typeof value !== 'string') return '';
      const clean = value.replace(/[\u0000-\u001f\u007f]/g, ' ');
      if (clean.length <= limit) return clean;
      clipped = true;
      let end = limit - 1;
      if (/[\uD800-\uDBFF]/.test(clean.charAt(end - 1))) end--;
      return clean.slice(0, end) + '…';
    };
    const add = (section, label, detail) => {
      if (rows.length >= 600) { clipped = true; return; }
      rows.push({ section, label: text(label, 80), detail: text(detail, 240) });
    };
    const number = (o, k, min, max, fallback) => !has(o, k) ? fallback
      : Number.isInteger(o[k]) && o[k] >= min && o[k] <= max ? String(o[k]) : '不可读取';
    const bool = (o, k) => !has(o, k) ? '未显式设置' : o[k] === true ? '允许' : o[k] === false ? '不允许' : '不可读取';
    const sources = { NODE: '完成节点', CHOICE: '选择', DRAW: '抽取' };
    add('规则', '背包容量配置', number(root, 'capacity', 0, 999, '未填写'));
    add('规则', '丢弃配置', bool(root, 'allowDiscard'));
    add('规则', 'HP / LUCK 名称配置', (text(root.hpName, 20) || '未填写') + ' / ' + (text(root.luckName, 20) || '未填写'));
    add('规则', '行动上限配置', number(root, 'actionLimit', 1, 99999, '未设置上限'));
    const actions = list(root, 'actionSources', 3);
    add('规则', '行动计数来源配置', !has(root, 'actionSources') ? '未显式设置（现有默认：完成节点、选择、抽取）'
      : root.actionSources === null ? '显式空值（不计数）' : !actions.length ? '显式空列表（不计数）'
        : actions.map(v => typeof v === 'string' && has(sources, v) ? sources[v] : '不可读取').join('、'));
    add('规则', '救援次数上限配置', number(root, 'rescueLimit', 0, 10, '未设置（现有默认为 0）'));
    add('规则', '重玩配置', bool(root, 'replayEnabled'));
    const items = list(root, 'items', 200);
    add('目录', '物品与勋章配置', items.length + ' 项；这是目录，不代表玩家已经持有');
    items.forEach((value, i) => {
      const item = object(value);
      const kind = item.kind === 'ITEM' ? '物品' : item.kind === 'MEDAL' ? '勋章' : '类型不可读取';
      const stat = ['HP', 'LUCK'].includes(item.stat) ? item.stat : '';
      const mode = { ONCE: '穿戴时一次生效', HELD: '持有时生效', REDUCE: '减少消耗' };
      add('目录', text(item.name, 40) || ('未命名目录项 ' + (i + 1)), kind + (stat ? '；' + stat + ' 变化配置 ' + number(item, 'delta', -999, 999, '未填写')
        + '；' + (item.wear === true ? (typeof item.mode === 'string' && has(mode, item.mode) ? mode[item.mode] : '穿戴方式不可读取') : '使用物品') : '；展示配置'));
    });
    const chapters = list(root, 'chapters', 200);
    add('章节', '章节规则配置', chapters.length + ' 组；这里只展示声明，不扣数值、不发物资、不触发彩蛋');
    chapters.forEach((value, i) => {
      const ch = object(value), grants = list(ch, 'grants', 200), eggs = list(ch, 'eggs', 100);
      const validIndex = Number.isInteger(ch.index) && ch.index >= 0 && ch.index < 200;
      const source = validIndex && Array.isArray(form.chapters) ? form.chapters[ch.index] : null;
      const name = source && typeof source.name === 'string' ? text(source.name, 40) : '';
      const start = grants.filter(g => g && g.timing === 'START').length;
      const end = grants.filter(g => g && g.timing === 'END').length;
      add('章节', name || ('章节配置 ' + (i + 1)), 'HP 消耗 ' + number(ch, 'hpCost', 0, 999, '未填写')
        + '；LUCK 消耗 ' + number(ch, 'luckCost', 0, 999, '未填写') + '；开始物资 ' + start + ' 种；结束物资 ' + end + ' 种；彩蛋 ' + eggs.length + ' 条');
    });
    const events = list(root, 'events', 100);
    add('广播', '根配置事件广播', events.length + ' 条；只查看配置文案，不表示已触发或已发送');
    events.forEach((value, i) => {
      const event = object(value);
      add('广播', '广播配置 ' + (i + 1), text(event.content, 180) + '（条件 ' + list(event, 'when', 30).length
        + ' 条；物资 ' + list(event, 'grants', 20).length + ' 项；状态效果 ' + list(event, 'effects', 20).length + ' 项）');
    });
    const scenes = list(root, 'scenes', 50);
    const rotation = { FIXED: '固定场景', ROUND_ROBIN: '按局轮换', WEEKLY: '按周轮换（UTC 周一 / 北京时间周一 08:00）' };
    add('场景', '轮换配置', !has(root, 'rotation') ? '未显式设置（固定场景）' : typeof root.rotation === 'string' && has(rotation, root.rotation) ? rotation[root.rotation] : '不可读取');
    const override = (scene, key, label, limit) => !has(scene, key) ? label + '继承根配置'
      : scene[key] === null ? label + '显式空值覆盖' : label + '覆盖 ' + list(scene, key, limit).length + ' 条';
    scenes.forEach((value, i) => {
      const scene = object(value);
      add('场景', (text(scene.name, 40) || ('未命名场景 ' + (i + 1))) + (typeof scene.id === 'string' && scene.id === root.currentScene ? '（起始配置）' : ''),
        [override(scene, 'grants', '物资', 300), override(scene, 'events', '广播', 100), override(scene, 'endings', '终局', 30),
          has(scene, 'journeyStory') ? '故事使用覆盖配置' : '故事继承根配置', has(scene, 'nodeConfigs') ? '玩法使用覆盖配置' : '玩法继承根配置'].join('；'));
    });
    add('规则', '根配置触发物资', list(root, 'grants', 300).length + ' 条；高级条件和效果请返回编辑器查看');
    return { kind: 'CONFIG', version: 1, clipped, rows };
  } catch (_) { return unavailable(); }
}

module.exports = { buildTopicPreview, buildThemeOverview };
