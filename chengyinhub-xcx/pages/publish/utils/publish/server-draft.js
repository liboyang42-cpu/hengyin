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
  return Number(id) > 0 ? Number(id) : 0;
}

function nodeNames(chapters, listKey) {
  const names = [];
  (chapters || []).forEach(chapter => ((chapter && chapter[listKey]) || []).forEach(node => {
    names.push(String((node && node.name) || '').trim());
  }));
  return names.sort();
}

/** 回读与刚保存的内容是否对得上;对不上返回一句原因,对得上返回 ''。 */
function readbackIssue(sent, detail, expectDraft) {
  if (!detail || !detail.topic) return '回读不到已保存的主题';
  if (expectDraft && detail.isDraft !== true) return '回读到的主题不是草稿';
  if (String(detail.topic.name || '') !== String((sent && sent.name) || '')) return '回读到的主题名与本机不一致';
  const got = detail.chapters || [];
  if (got.some(chapter => (chapter.cmsTopicNodeList || []).some(node => !(Number(node && node.id) > 0)))) {
    return '回读到的站点缺少编号';
  }
  const want = nodeNames(sent && sent.chapters, 'nodes');
  const have = nodeNames(got, 'cmsTopicNodeList');
  if (want.length !== have.length || want.some((name, i) => name !== have[i])) return '回读到的站点与本机不一致';
  return '';
}

function createServerDraftSaver(deps) {
  deps = deps || {};
  ['save', 'readback', 'publish'].forEach(key => {
    if (typeof deps[key] !== 'function') throw new Error('server-draft: 缺少 ' + key + ' 依赖');
  });
  let busy = false;
  let published = false;
  let destroyed = false;

  function run(payload, publish, handlers) {
    handlers = handlers || {};
    // 防重:在途或已发布都不再发起(连点只会有一次请求)
    if (busy || published || destroyed) return false;
    busy = true;
    const finish = (fn, arg) => {
      busy = false;
      if (!destroyed && typeof fn === 'function') fn(arg);
    };
    deps.save(Object.assign({}, payload, { saveDraft: 1 }), (res) => {
      if (destroyed) return;
      const topicId = res && res.ok ? topicIdOf(res.data) : 0;
      if (!topicId) {
        finish(handlers.onFail, Object.assign({ stage: 'save' }, res || {},
          res && res.ok ? { msg: '保存结果缺少主题编号' } : {}));
        return;
      }
      if (typeof handlers.onSaved === 'function') handlers.onSaved(topicId);
      deps.readback(topicId, (back) => {
        if (destroyed) return;
        const issue = back && back.ok ? readbackIssue(payload, back.data, true) : '';
        if (!back || !back.ok || issue) {
          finish(handlers.onFail, Object.assign({ stage: 'readback', topicId }, back || {}, issue ? { ok: false, msg: issue } : {}));
          return;
        }
        if (!publish) {
          finish(handlers.onSuccess, { topicId, detail: back.data });
          return;
        }
        deps.publish(topicId, (pub) => {
          if (destroyed) return;
          if (!pub || !pub.ok) {
            finish(handlers.onFail, Object.assign({ stage: 'publish', topicId, detail: back.data }, pub || {}));
            return;
          }
          published = true;
          finish(handlers.onSuccess, { topicId, detail: back.data, published: pub.data || {} });
        });
      });
    });
    return true;
  }

  return {
    save: (payload, handlers) => run(payload, false, handlers),
    saveAndPublish: (payload, handlers) => run(payload, true, handlers),
    isBusy: () => busy,
    destroy: () => { destroyed = true; },
  };
}

module.exports = { createServerDraftSaver, readbackIssue, topicIdOf };
