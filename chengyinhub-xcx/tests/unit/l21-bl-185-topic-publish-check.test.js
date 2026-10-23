// L21 · BL-185 主题发布前检查:「编辑既有草稿 → 发布」在存好并回读确认之后、publish-draft 之前,
// 调 GET /api/topic/publish-check?topicId=&scope=(ApiTopicStateKeyController#publishCheck)。
// 有 error 就不发布(按「草稿已存、未发布」收口);只有 warn 时提示,用户点「继续发布」才发;
// 试检查本身拿不到结果不拦(publish-draft 服务端照样会拦)。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { createServerDraftSaver } = require('../../pages/publish/utils/publish/server-draft.js');

const ROOT = path.resolve(__dirname, '../..');
const FABU_JS = path.join(ROOT, 'pages/publish/fabu/index.js');
const noop = () => {};

const payload = { name: '寒冬', publishMode: 'pro', productType: 1, chapters: [{ nodes: [{ name: '甲' }, { name: '乙' }] }] };
const detail = () => ({ isDraft: true,
  chapters: [{ id: 101, topicId: 501, cmsTopicNodeList: [{ id: 11, chapterId: 101, topicId: 501, name: '甲' }, { id: 12, chapterId: 101, topicId: 501, name: '乙' }] }],
  topic: { id: 501, status: 0, name: '寒冬' } });

function saverWith(publishCheck) {
  const calls = [];
  const events = [];
  const deps = {
    save: (p, cb) => calls.push(['save', p, cb]),
    readback: (id, cb) => calls.push(['readback', id, cb]),
    publish: (id, cb) => calls.push(['publish', id, cb]),
    beforePublish: () => { calls.push(['beforePublish']); return true; },
  };
  if (publishCheck !== undefined) deps.publishCheck = publishCheck;
  const saver = createServerDraftSaver(deps);
  const h = { onSaved: noop, onSuccess: r => events.push(['ok', r]), onFail: r => events.push(['fail', r]) };
  assert.equal(saver.saveAndPublish(payload, h), true);
  calls[0][2]({ ok: true, data: { topicId: 501 } });
  calls[1][2]({ ok: true, data: detail() });
  return { calls, events, saver, h };
}

test('server-draft:试检查报 error → 不落发布标记、不调 publish-draft,按已存未发布报失败', () => {
  const checks = [];
  const { calls, events, saver } = saverWith((id, cb) => checks.push([id, cb]));
  assert.equal(checks.length, 1); assert.equal(checks[0][0], 501);
  assert.equal(calls.filter(c => c[0] === 'publish' || c[0] === 'beforePublish').length, 0, '试检查回来前不发');
  checks[0][1]({ ok: false, msg: '发布前检查未通过：第2站缺坐标' });
  assert.equal(calls.filter(c => c[0] === 'publish' || c[0] === 'beforePublish').length, 0);
  const [kind, fail] = events.at(-1);
  assert.equal(kind, 'fail');
  assert.equal(fail.stage, 'publish'); assert.equal(fail.unknown, false); assert.equal(fail.topicId, 501);
  assert.ok(fail.detail, '带回读结果,页面才能换上真实编号并标「已存云端」');
  assert.match(fail.msg, /第2站缺坐标/);
  assert.equal(saver.isBusy(), false, '失败后可重试');
});

test('server-draft:试检查放行 → 照旧 beforePublish → publish-draft', () => {
  const checks = [];
  const { calls, events } = saverWith((id, cb) => checks.push(cb));
  checks[0]({ ok: true });
  assert.deepEqual(calls.slice(2).map(c => c[0]), ['beforePublish', 'publish']);
  calls[3][2]({ ok: true, data: { topicId: 501, published: true } });
  assert.equal(events.at(-1)[0], 'ok');
});

test('server-draft:未注入 publishCheck 时行为不变(其他调用方不受影响)', () => {
  const { calls } = saverWith(undefined);
  assert.deepEqual(calls.slice(2).map(c => c[0]), ['beforePublish', 'publish']);
});

function loadFabuCheck(options = {}) {
  const requests = [];
  const modals = [];
  const loading = [];
  let captured;
  const realServerDraft = require('../../pages/publish/utils/publish/server-draft.js');
  const wx = {
    getStorageSync: () => '', setStorageSync: noop, removeStorageSync: noop,
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    showToast: noop, hideLoading: noop, showLoading: noop, showModal: noop,
  };
  const app = { globalData: {}, getUserID: () => 42, tips: noop, sendRequest: (r) => requests.push(r) };
  global.getApp = () => app;
  global.wx = wx;
  const localRequire = createRequire(FABU_JS);
  let definition;
  vm.runInNewContext(fs.readFileSync(FABU_JS, 'utf8'), {
    getApp: () => app, Page(p) { definition = p; }, Component(p) { definition = p; },
    getCurrentPages: () => [{}],
    require(name) {
      if (name.endsWith('/server-draft.js')) {
        return Object.assign({}, realServerDraft, { createServerDraftSaver(deps) { captured = deps; return realServerDraft.createServerDraftSaver(deps); } });
      }
      if (name.endsWith('/utils/modal.js')) return { show: (o) => modals.push(o) };
      if (name.endsWith('/utils/loading.js')) return { show: (t) => loading.push(['show', t]), hide: () => loading.push(['hide']) };
      return localRequire(name);
    },
    setTimeout: () => 1, clearTimeout: noop, setInterval: () => 1, clearInterval: noop,
    console, Date, JSON, wx,
  }, { filename: FABU_JS });
  const page = Object.assign({}, definition, { data: { operationScope: options.scope || '', draftMemberId: '42', draftUuid: 'u1', baseRevision: '' } });
  page.setData = (patch) => Object.assign(page.data, patch);
  page._draftIdentity = () => ({ draftUuid: 'u1' });
  page.getServerDraftSaver();
  assert.equal(typeof captured.publishCheck, 'function', 'fabu 必须给云端草稿发布注入试检查');
  const results = [];
  const run = () => captured.publishCheck(501, (r) => results.push(r));
  return { requests, modals, loading, results, run };
}

test('fabu:按 topicId + scope 静默 GET /api/topic/publish-check', () => {
  const f = loadFabuCheck({ scope: 'MERCHANT' });
  f.run();
  assert.equal(f.requests.length, 1);
  const r = f.requests[0];
  assert.equal(r.url, '/api/topic/publish-check');
  assert.equal(r.method, 'GET');
  assert.deepEqual(JSON.parse(JSON.stringify(r.data)), { topicId: 501, scope: 'MERCHANT' });
  assert.equal(r.silentError, true);
});

test('fabu:有 error 就不发布(warn 一并存在也不给「继续」)', () => {
  const f = loadFabuCheck();
  f.run();
  f.requests[0].success({ code: 200, data: [
    { level: 'warn', code: 'W', message: '没有封面图' },
    { level: 'error', code: 'E1', nodeId: 11, message: '第1站缺坐标' },
    { level: 'error', code: 'E2', message: '没有票种' },
  ] });
  f.requests[0].complete();
  assert.equal(f.modals.length, 0);
  assert.equal(f.results.length, 1, '只回调一次');
  assert.equal(f.results[0].ok, false);
  assert.equal(f.results[0].msg, '发布前检查未通过：第1站缺坐标；没有票种');
});

test('fabu:只有 warn → 提示,「继续发布」才发,「再改改」不发', () => {
  for (const confirm of [true, false]) {
    const f = loadFabuCheck();
    f.run();
    f.requests[0].success({ code: 200, data: [{ level: 'warn', code: 'W', message: '第2站没有玩法' }] });
    assert.equal(f.results.length, 0, '用户没选之前不发');
    assert.equal(f.modals.length, 1);
    assert.equal(f.modals[0].content, '第2站没有玩法');
    assert.equal(f.modals[0].confirmText, '继续发布');
    assert.deepEqual(f.loading[0], ['hide'], '弹提示前先撤掉带遮罩的加载层');
    f.modals[0].success({ confirm, cancel: !confirm });
    f.requests[0].complete();
    assert.equal(f.results.length, 1);
    assert.equal(f.results[0].ok, confirm);
    if (!confirm) assert.match(f.results[0].msg, /草稿已保存/);
  }
});

test('fabu:没有问题直接发;试检查拿不到(业务拒绝/网络/HTTP 异常)不拦,交给 publish-draft 服务端判', () => {
  const replies = [
    (r) => { r.success({ code: 200, data: [] }); r.complete(); },
    (r) => { r.success({ code: 500, msg: '无权操作该主题' }); r.complete(); },
    (r) => { r.fail({}); r.complete(); },
    (r) => { r.complete(); },
  ];
  replies.forEach((reply, index) => {
    const f = loadFabuCheck();
    f.run();
    reply(f.requests[0]);
    assert.deepEqual(JSON.parse(JSON.stringify(f.results)), [{ ok: true }], 'case ' + index);
    assert.equal(f.modals.length, 0);
  });
});
