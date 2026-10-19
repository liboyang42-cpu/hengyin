// 主题云端草稿:保存草稿 → 经 edit-detail 回读确认 → (可选)发布这份已保存的草稿。
//
// 三种「保存」别混:
//  - 本机自动保存(proEditorDraft):每次编辑写本机 storage,只防杀进程丢稿;
//  - 保存草稿(本模块):v2/create 或 v2/update 带 saveDraft=1,写进服务端但不上架、不送审、不占在架额度;
//  - 发布:先按「保存草稿」存下最新内容并回读确认,再调 /api/topic/publish-draft 发布这份已持久化的草稿。
//
// 依赖注入(页面保留各自 UI):
//  save(payload, cb)      cb({ ok, data?, msg?, networkError? }),data 为 topicId 或 { topicId, ... }
//  readback(topicId, cb)  cb({ ok, data?, msg?, networkError? }),data 为 edit-detail 的回包
//  publish(topicId, cb)   cb({ ok, data?, msg?, networkError? })
//
// handlers:onSaved(topicId) 服务端已接受(拿到主题号,后续保存走 update,避免重复建题);
//           onSuccess({ topicId, detail, published? }) / onFail({ stage, topicId?, detail?, msg?, networkError? })。

function topicIdOf(data) {
  const id = data && typeof data === 'object' ? data.topicId : data;
  if (typeof id === 'number') return Number.isSafeInteger(id) && id > 0 ? id : 0;
  if (typeof id !== 'string' || !/^[1-9][0-9]{0,18}$/.test(id)
      || (id.length === 19 && id > '9223372036854775807')) return 0;
  const number = Number(id);
  return Number.isSafeInteger(number) ? number : id;
}

function nodeNames(chapters, listKey) {
  const names = [];
  (chapters || []).forEach(chapter => ((chapter && chapter[listKey]) || []).forEach(node => {
    names.push(String((node && node.name) || '').trim());
  }));
  return names.sort();
}

/** 回读与刚保存的内容是否对得上;对不上返回一句原因,对得上返回 ''。 */
function readbackIssue(sent, detail, expectDraft, expectedTopicId) {
  if (!detail || !detail.topic) return '回读不到已保存的主题';
  const id = topicIdOf(detail.topic.id);
  if (!id || (expectedTopicId && String(id) !== String(expectedTopicId))) return '回读到的主题编号与保存结果不一致';
  if (expectDraft && detail.isDraft !== true) return '回读到的主题不是草稿';
  if (String(detail.topic.name || '') !== String((sent && sent.name) || '')) return '回读到的主题名与本机不一致';
  const got = detail.chapters;
  if (!Array.isArray(got) || got.some(chapter => !chapter || !topicIdOf(chapter.id) || !Array.isArray(chapter.cmsTopicNodeList)
      || chapter.cmsTopicNodeList.some(node => !node || !topicIdOf(node.id)))) return '回读到的章节或站点编号不完整';
  if (got.length !== ((sent && sent.chapters) || []).length) return '回读到的章节与本机不一致';
  const chapterIds = new Set(), nodeIds = new Set();
  for (const chapter of got) {
    const chapterId = String(topicIdOf(chapter.id));
    if (chapterIds.has(chapterId)) return '回读到的章节编号重复';
    chapterIds.add(chapterId);
    for (const node of chapter.cmsTopicNodeList) {
      const nodeId = String(topicIdOf(node.id));
      if (nodeIds.has(nodeId)) return '回读到的站点编号重复';
      nodeIds.add(nodeId);
    }
  }
  // 章节/站点可能由服务端重新排序，但不能把站点移到另一章后清掉本机恢复稿。
  const chapterContent = (chapters, listKey) => (chapters || []).map(chapter => JSON.stringify([
    String((chapter && chapter.name) || '').trim(), nodeNames([chapter], listKey)
  ])).sort();
  const want = chapterContent(sent && sent.chapters, 'nodes');
  const have = chapterContent(got, 'cmsTopicNodeList');
  if (want.some((content, i) => content !== have[i])) return '回读到的章节或站点归属与本机不一致';
  return '';
}

function createServerDraftSaver(deps) {
  deps = deps || {};
  ['save', 'readback', 'publish'].forEach(key => {
    if (typeof deps[key] !== 'function') throw new Error('server-draft: 缺少 ' + key + ' 依赖');
  });
  let active = null;
  let published = false;
  let destroyed = false;

  function run(payload, publish, handlers) {
    handlers = handlers || {};
    if (active || published || destroyed) return false;
    let snapshot;
    try { snapshot = JSON.parse(JSON.stringify(payload)); }
    catch (error) {
      if (typeof handlers.onFail === 'function') handlers.onFail({ stage: 'save', msg: '草稿内容暂时无法保存，请保留本机记录' });
      return false;
    }
    const operation = { phase: '' };
    active = operation;
    const current = () => !destroyed && active === operation
      && (typeof handlers.isCurrent !== 'function' || handlers.isCurrent());
    const changed = () => typeof handlers.hasChanges === 'function' && handlers.hasChanges();
    const finish = (fn, arg) => {
      if (active !== operation) return;
      const notify = current();
      active = null;
      if (notify && typeof fn === 'function') fn(arg);
    };
    const invoke = (fn, value, stage, receive) => {
      if (!current()) { finish(); return; }
      operation.phase = stage;
      try {
        fn(value, res => {
          if (destroyed || active !== operation || operation.phase !== stage) return;
          operation.phase = '';
          if (!current()) {
            if (stage === 'publish' && res && res.ok) published = true;
            finish(); return;
          }
          receive(res);
        });
      } catch (error) {
        if (active === operation && (operation.phase === stage || operation.phase === ''))
          finish(handlers.onFail, { stage, networkError: true });
      }
    };
    invoke(deps.save, Object.assign({}, snapshot, { saveDraft: 1 }), 'save', res => {
      const topicId = res && res.ok ? topicIdOf(res.data) : 0;
      if (!topicId) {
        finish(handlers.onFail, Object.assign({ stage: 'save' }, res || {},
          res && res.ok ? { msg: '保存结果缺少主题编号' } : {}));
        return;
      }
      if (typeof handlers.onSaved === 'function') handlers.onSaved(topicId);
      invoke(deps.readback, topicId, 'readback', back => {
        const issue = back && back.ok ? readbackIssue(snapshot, back.data, true, topicId) : '';
        if (!back || !back.ok || issue) {
          finish(handlers.onFail, Object.assign({ stage: 'readback', topicId }, back || {}, issue ? { ok: false, msg: issue } : {}));
          return;
        }
        if (changed()) {
          finish(handlers.onFail, { stage: 'readback', topicId, ok: false, localChanged: true,
            msg: '本机已有后续修改，请再次保存；已保存版本不会覆盖本机内容' });
          return;
        }
        if (!publish) {
          finish(handlers.onSuccess, { topicId, detail: back.data });
          return;
        }
        invoke(deps.publish, topicId, 'publish', pub => {
          if (!pub || !pub.ok) {
            finish(handlers.onFail, Object.assign({ stage: 'publish', topicId, detail: back.data }, pub || {}, { localChanged: changed() }));
            return;
          }
          published = true;
          finish(handlers.onSuccess, { topicId, detail: back.data, published: pub.data || {}, localChanged: changed() });
        });
      });
    });
    return true;
  }

  return {
    save: (payload, handlers) => run(payload, false, handlers),
    saveAndPublish: (payload, handlers) => run(payload, true, handlers),
    isBusy: () => !!active,
    destroy: () => { destroyed = true; active = null; },
  };
}

module.exports = { createServerDraftSaver, readbackIssue, topicIdOf };
