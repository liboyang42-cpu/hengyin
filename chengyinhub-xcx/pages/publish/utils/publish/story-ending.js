/* 结局清单:journeyStory.ending.endings 的作者侧镜像。
 *
 * 后端(JourneyStoryValidator.validateEndings)认的条件语法比手机上说得清的宽得多。
 * 这一层只把其中两种翻成作者看得懂的话:
 *   「想通了某个念头」 → {op:'HAS_TAG', value:'thought.<key>.done'}
 *   「到过某个站点」   → {op:'NODE_COMPLETED', nodeId:<id>}
 *   「选过某个选项」   → {op:'HAS_TAG', value:'tag.<名>'}(选项效果「贴标签」写的就是它,2026-09-24)
 * 其余一律标成 kind='raw' 原样带回去 —— 界面表达不了不等于可以抹掉,那会让作者在
 * 别处(后台 / 接口)配好的条件在手机上点一次「完成」就没了。
 *
 * ★ 念头键名 thought.<key>.done 不是这里自己编的:TopicRouteRuntimeServiceImpl.syncThoughts
 *   想通时写的就是它(两端都有测试钉住,见 TopicRouteRuntimeServiceImplTest 与
 *   PlayJourneyStoryServiceImplTest.endingBranchesOnThoughtDone)。
 *
 * ★ serialize 只动 ending.endings,journeyStory 的其它字段(call / stages / openingChapterId /
 *   ending 的 epilogues·exhibits·badges·optionalClues·dispositionNodeId)原样保留。
 */

const MAX_ENDINGS = 12;
const THOUGHT_TAG = /^thought\.([a-z][a-z0-9_]{0,47})\.done$/;
const OPTION_TAG = /^tag\.[a-z][a-z0-9_]{0,47}$/;
const CODE = /^[a-z][a-z0-9_]{0,31}$/;

function parse(input) {
  let root = {};
  if (input && typeof input === 'string' && input.trim()) {
    try { root = JSON.parse(input) || {}; } catch (e) { root = {}; }
  } else if (input && typeof input === 'object') {
    root = input;
  }
  const rows = (root.ending && Array.isArray(root.ending.endings)) ? root.ending.endings : [];
  const list = rows.map(toDraft).filter(Boolean);
  if (!list.some(item => item.fallback)) {
    list.push({ code: 'default', title: '', summary: '', fallback: true, kind: 'fallback' });
  }
  return { root: root, list: list };
}

/** 服务端形状 → 界面形状。看不懂的条件留在 _when 里,serialize 时原样写回。 */
function toDraft(row) {
  if (!row || typeof row !== 'object') return null;
  const base = {
    code: String(row.code || '').trim(),
    title: String(row.title || '').trim(),
    summary: String(row.summary || '').trim(),
    fallback: row.fallback === true,
  };
  if (base.fallback) return Object.assign(base, { kind: 'fallback' });

  const when = Array.isArray(row.when) ? row.when : [];
  if (when.length === 1 && when[0] && when[0].op === 'HAS_TAG') {
    const hit = THOUGHT_TAG.exec(String(when[0].value || ''));
    if (hit) return Object.assign(base, { kind: 'thought', thoughtKey: hit[1] });
    if (OPTION_TAG.test(String(when[0].value || ''))) return Object.assign(base, { kind: 'tag', tag: String(when[0].value) });
  }
  if (when.length === 1 && when[0] && when[0].op === 'NODE_COMPLETED' && when[0].nodeId) {
    return Object.assign(base, { kind: 'node', nodeId: Number(when[0].nodeId) });
  }
  return Object.assign(base, { kind: 'raw', _when: when });
}

/** 界面形状 → 服务端形状。校验与 JourneyStoryValidator 同口径,在作者点「完成」之前就说清楚。 */
function serialize(draft) {
  const root = (draft && draft.root && typeof draft.root === 'object') ? draft.root : {};
  const list = (draft && Array.isArray(draft.list)) ? draft.list : [];
  if (!list.length) throw new Error('至少要有一个结局');
  if (list.length > MAX_ENDINGS) throw new Error('结局最多 ' + MAX_ENDINGS + ' 个');

  const seen = [];
  let fallbacks = 0;
  const endings = list.map((item, index) => {
    const code = String((item && item.code) || '').trim();
    if (!CODE.test(code)) throw new Error('第 ' + (index + 1) + ' 个结局的标识不合法:' + code);
    if (seen.indexOf(code) >= 0) throw new Error('结局标识重复:' + code);
    seen.push(code);

    const title = String((item && item.title) || '').trim();
    if (!title || title.length > 20) throw new Error('第 ' + (index + 1) + ' 个结局要有标题,且不超过 20 字');
    const summary = String((item && item.summary) || '').trim();
    if (summary.length > 60) throw new Error('结局的一句话不能超过 60 字');

    const out = { code: code, title: title };
    if (summary) out.summary = summary;

    if (item.fallback === true) {
      fallbacks += 1;
      out.fallback = true;
      return out;
    }
    out.when = conditionOf(item, title);
    return out;
  });

  if (fallbacks !== 1) {
    throw new Error('要恰好一个兜底结局(其它都不中时用它);现在有 ' + fallbacks + ' 个');
  }

  const ending = Object.assign({}, root.ending, { endings: endings });
  const next = Object.assign({}, root, { ending: ending });
  if (!next.schemaVersion) next.schemaVersion = 1;
  return JSON.stringify(next);
}

/** 界面的一条条件 → 服务端 when。缺选择就抛一句作者看得懂的话。 */
function conditionOf(item, title) {
  if (item.kind === 'thought') {
    const key = String(item.thoughtKey || '').trim();
    if (!key) throw new Error('「' + title + '」要选一个伏笔');
    return [{ op: 'HAS_TAG', value: 'thought.' + key + '.done' }];
  }
  if (item.kind === 'tag') {
    const tag = String(item.tag || '').trim();
    if (!OPTION_TAG.test(tag)) throw new Error('「' + title + '」要选一个选项');
    return [{ op: 'HAS_TAG', value: tag }];
  }
  if (item.kind === 'node') {
    const nodeId = Number(item.nodeId);
    if (!nodeId) throw new Error('「' + title + '」要选一个站点');
    return [{ op: 'NODE_COMPLETED', nodeId: nodeId }];
  }
  const raw = Array.isArray(item._when) ? item._when : [];
  if (!raw.length) throw new Error('「' + title + '」还没有触发条件');
  return raw;
}

/* ===== 结局 = 结局章(2026-09-24 用户:「结局应该是添加章节,打开结局开关」)=====
 * 章节上挂 ending:{fallback} 或 {kind, thoughtKey / tag / nodeId / _when}(界面形状,与上面结局清单同一套 kind)。
 * 提交时转成 ChapterDTO.ending;服务端按结局章重建 ending.endings,并记下 chapterId。 */

/** 章节结局 → ChapterDTO.ending;没开返回 null */
function chapterEndingPayload(ending, name) {
  if (!ending || typeof ending !== 'object') return null;
  if (ending.fallback === true) return { fallback: true };
  return { when: conditionOf(ending, name || '这个结局章') };
}

/** 保存章节设置前的检查:结局章不是路线的一站 */
function chapterEndingIssue(chapter) {
  const ending = chapter && chapter.ending;
  if (!ending) return '';
  if (chapter.opening === true) return '开局不能同时是结局';
  if ((chapter.nodes || []).length) return '结局章里不能放站点或玩法';
  if (Number(chapter.recruitEnabled) === 1) return '结局章不能开放商家承接';
  if (ending.fallback === true) return '';
  try { conditionOf(ending, chapter.name || '这个结局章'); } catch (e) { return e.message; }
  return '';
}

/** 整本书的结局章检查:有结局章就必须恰好一章兜底 */
function endingChaptersIssue(chapters) {
  const endings = (chapters || []).filter(c => c && c.ending);
  if (!endings.length) return '';
  const fallbacks = endings.filter(c => c.ending.fallback === true).length;
  return fallbacks === 1 ? '' : (fallbacks ? '结局章里只能有一章兜底' : '结局章里要有一章兜底');
}

/**
 * 回显:journeyStory 里带 chapterId 的结局挂回对应章节;没有 chapterId 的老结局
 * 交给调用方转成新的结局章(legacy 里给出标题 / 一句话 / 条件)。
 */
function attachEndingsToChapters(chapters, journeyStory) {
  const rows = parse(journeyStory).root;
  const list = rows && rows.ending && Array.isArray(rows.ending.endings) ? rows.ending.endings : [];
  const out = (chapters || []).map(c => Object.assign({}, c, { ending: null }));
  const legacy = [];
  list.forEach((row) => {
    const draft = toDraft(row);
    if (!draft) return;
    const ending = draft.fallback ? { fallback: true, kind: 'fallback' }
      : { fallback: false, kind: draft.kind, thoughtKey: draft.thoughtKey || '', tag: draft.tag || '',
        nodeId: draft.nodeId == null ? null : draft.nodeId, _when: draft._when };
    if (row.chapterId != null) {
      const hit = out.find(c => c.id != null && String(c.id) === String(row.chapterId));
      if (hit) { hit.ending = ending; return; }
    }
    legacy.push({ title: draft.title, summary: draft.summary, ending });
  });
  return { chapters: out, legacy };
}

/**
 * 提交前:结局都在章节上,剧情里的结局清单交给服务端按章重建。
 * ending 里只剩 endings 就整段去掉;还有尾声 / 展品等别的段,就留着旧清单过保存校验(服务端会换掉它)。
 */
function withoutEndingList(input) {
  let root;
  try { root = input && typeof input === 'string' && input.trim() ? JSON.parse(input) : (input && typeof input === 'object' ? input : null); } catch (e) { root = null; }
  if (!root || !root.ending || typeof root.ending !== 'object') return input || '';
  const rest = Object.keys(root.ending).filter(k => k !== 'endings');
  if (rest.length) return typeof input === 'string' ? input : JSON.stringify(root);
  const next = Object.assign({}, root);
  delete next.ending;
  return JSON.stringify(next);
}

/** 加一条新结局。code 由现有清单推,连加几个不会互相覆盖;兜底那条不动。 */
function appendTo(list) {
  const rows = Array.isArray(list) ? list.slice() : [];
  const used = rows.map(item => String((item && item.code) || ''));
  let n = 1;
  while (used.indexOf('ending' + n) >= 0) n += 1;
  const fresh = { code: 'ending' + n, title: '', summary: '', kind: 'thought', thoughtKey: '', fallback: false };
  const at = rows.findIndex(item => item && item.fallback);
  if (at < 0) rows.push(fresh); else rows.splice(at, 0, fresh);
  return rows;
}

/** 入口卡副标题:数出配了几个结局(兜底也算一个)。读不出来就当没配。 */
function countOf(input) {
  try { return parse(input).list.filter(item => item.title).length; } catch (e) { return 0; }
}

module.exports = { parse, serialize, appendTo, countOf, MAX_ENDINGS,
  chapterEndingPayload, chapterEndingIssue, endingChaptersIssue, attachEndingsToChapters, withoutEndingList };
