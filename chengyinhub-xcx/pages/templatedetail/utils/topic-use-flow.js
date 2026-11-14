// 主题模板「用这个模板」采用流程：预览页与详情页共用。
// requestId：同一次采用意图（模板+范围+意图）内只生成一次，网络失败后的重试复用它，
// 服务端 /use 按 requestId 幂等，已落库的草稿不会再复制一份。
// 回包到达时页面已卸载：复制出的草稿 id 记在模块里，短时间内再次采用直接回到那一份。
const adoption = require('./topic-adoption.js');

const ORPHAN_TTL_MS = 5 * 60 * 1000;
const pending = {};   // key -> requestId（尚无明确结果）
const orphans = {};   // key -> { id, at }（页面已卸载时收到的草稿 id）

function newRequestId() {
  return 'tu-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12);
}

function keyOf(owner, input) {
  return [owner, input.id, input.scope || '', input.intent || ''].join('|');
}

// page 需提供：_using / _unloaded 标志位；opts.toast(text)、opts.open(copiedId)、opts.onCopied(id)
function submit(page, input, opts) {
  const app = getApp();
  if (page._using) { opts.toast('正在配置，请稍候'); return; }
  const key = keyOf(app.getUserID && app.getUserID(), input);
  const orphan = orphans[key];
  if (orphan && Date.now() - orphan.at < ORPHAN_TTL_MS) {
    delete orphans[key];
    opts.onCopied(orphan.id); opts.open(orphan.id);
    return;
  }
  const built = adoption.usePayload(input);
  const requestId = pending[key] || (pending[key] = newRequestId());
  built.data.requestId = requestId;
  page._using = true;
  app.sendRequest({
    url: '/api/template/topic-template/use',
    method: 'POST',
    data: built.data,
    success(res) {
      page._using = false;
      const result = adoption.useResult(res);
      if (!result.ok) {
        delete pending[key]; // 服务端明确拒绝：下一次是新意图
        if (!page._unloaded) opts.toast(result.message || '使用模板失败');
        return;
      }
      delete pending[key];
      if (page._unloaded) { orphans[key] = { id: result.topicId, at: Date.now() }; return; }
      opts.onCopied(result.topicId);
      opts.open(result.topicId);
    },
    fail() {
      page._using = false;
      // 网络失败：不自动重发；requestId 保留，用户再点一次按同号重试
      if (!page._unloaded) opts.toast('网络错误，请重试');
    }
  });
}

module.exports = { submit };
