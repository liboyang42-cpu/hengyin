const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const transport = require('../../utils/community-post-api');
const tick = () => new Promise(resolve => setImmediate(resolve));
const reporting = { policyVersion: 'server-policy', reasons: [{ label: '服务端原因', code: 'SERVER_REASON' }] };
function harness() {
  let actor = 990005;
  const calls = [], tips = [], results = [], failures = [], completions = [];
  const app = { getUserID: () => actor, tips: message => tips.push(message),
    getRequestErrorMessage: (res, fallback) => res.msg || fallback,
    sendRequest(o) { calls.push(o); return { abort() { o.fail({ errMsg: 'request:fail abort' }); } }; } };
  const client = transport.createClient(app, {});
  const request = (options = {}) => client.request(Object.assign({ url: '/api/creativesquare/report', data: { id: 59 },
    selectReportReason: () => Promise.resolve(0), success: res => results.push(res),
    fail: res => failures.push(res), complete: () => completions.push(true) }, options));
  return { calls, tips, results, failures, completions, client, request, setActor(id) { actor = id; } };
}

test('legacy request still supports only real report commands while typed operations are explicit', () => {
  assert.deepEqual(Object.keys(transport), ['createClient']);
  assert.deepEqual(Object.keys(harness().client), ['request', 'feed', 'myPosts', 'bookmarks', 'detail', 'comments',
    'addPostAction', 'removePostAction', 'addComment', 'likeComment', 'unlikeComment', 'deletePost',
    'deleteComment', 'captureUpload', 'publishNew', 'saveEdit', 'dispose']);
  assert.equal(harness().client.normalizePost, undefined);
  assert.equal(harness().client.normalizeComment, undefined);
  const source = fs.readFileSync(path.resolve(__dirname, '../../utils/community-post-api.js'), 'utf8');
  assert.match(source, /app\.sendRequest\(\{[\s\S]*?url:\s*'\/api\/v1\/community\/capabilities'/);
  assert.match(source, /app\.sendRequest\(\{[\s\S]*?url:\s*'\/api\/v1\/community\/reports'/);
  assert.doesNotMatch(source, /getRequestPlan/);
});

test('unsupported commands cannot forward requests or call removed CRUD paths', async () => {
  for (const url of ['/api/creativesquare/action', '/api/creativesquare/list', '/api/comment/add', '/other']) {
    const h = harness(); h.request({ url }); await tick();
    assert.equal(h.calls.length, 0); assert.equal(h.results[0].code, 409);
    assert.equal(h.completions.length, 1);
  }
});

for (const [url, targetType, id] of [['/api/creativesquare/report', 'CONTENT', 59], ['/api/comment/report', 'COMMENT', 3]]) {
  test('report transport uses literal canonical URLs and server reason for ' + targetType, async () => {
    const h = harness(); const labels = [];
    h.request({ url, data: { id, request_id: 'caller-request' }, selectReportReason: items => { labels.push(items); return Promise.resolve(0); } });
    await tick(); assert.equal(h.calls[0].url, '/api/v1/community/capabilities'); assert.equal(h.calls[0].method, 'GET');
    h.calls[0].success({ code: 200, data: { reporting } }); await tick();
    assert.deepEqual(labels, [['服务端原因']]); assert.equal(h.calls.length, 2);
    const post = h.calls[1]; assert.equal(post.url, '/api/v1/community/reports'); assert.equal(post.method, 'POST'); assert.equal(post.header, 'application/json');
    for (const call of h.calls) {
      assert.equal(call.hideLoading, true); assert.equal(call.silentError, true);

    }
    assert.deepEqual(post.data, { targetType, targetId: id, reasonCode: 'SERVER_REASON', description: '服务端原因', policyVersion: 'server-policy', requestId: 'caller-request' });
    post.success({ code: 200, data: { id: 71 } }); await tick();
    assert.equal(h.results[0].code, 200); assert.equal(h.completions.length, 1);
  });
}

test('report description preserves caller text or uses the exact selected server reason label', async () => {
  const taxonomy = { policyVersion: 'selected-policy', reasons: [
    { label: '未选原因', code: 'FIRST' }, { label: '所选服务端原因', code: 'SELECTED' },
  ] };
  for (const [description, expected] of [
    [undefined, '所选服务端原因'], [null, '所选服务端原因'], ['', '所选服务端原因'],
    [' \t ', '所选服务端原因'], [42, '所选服务端原因'], ['  用户提供的真实说明  ', '  用户提供的真实说明  '],
  ]) {
    const h = harness();
    h.request({ data: { id: 59, request_id: 'selected-request', description },
      selectReportReason: labels => { assert.deepEqual(labels, ['未选原因', '所选服务端原因']); return Promise.resolve(1); } });
    await tick(); h.calls[0].success({ code: 200, data: { reporting: taxonomy } }); await tick();
    assert.deepEqual(h.calls[1].data, { targetType: 'CONTENT', targetId: 59,
      reasonCode: 'SELECTED', description: expected, policyVersion: 'selected-policy', requestId: 'selected-request' });
    h.calls[1].success({ code: 200, data: { id: 71 } }); await tick();
    assert.equal(h.results[0].code, 200); assert.equal(h.completions.length, 1);
  }
});

test('absent taxonomy fails closed before selection or report POST', async () => {
  for (const data of [undefined, {}, { reporting: { reasons: [] } }]) {
    const h = harness(); h.request({ selectReportReason() { assert.fail('missing taxonomy selected'); } });
    await tick(); h.calls[0].success({ code: 200, data }); await tick();
    assert.equal(h.calls.length, 1); assert.equal(h.results[0].code, 503);
  }
});

test('incomplete server policy or reason labels/codes fails before any selection or POST', async () => {
  const invalid = [
    ...[undefined, null, '', ' ', 1, {}].map(policyVersion => ({ policyVersion, reasons: reporting.reasons })),
    ...[null, {}, { label: '', code: 'R1' }, { label: ' ', code: 'R1' },
      { label: 1, code: 'R1' }, { label: '原因' }, { label: '原因', code: '' },
      { label: '原因', code: ' ' }, { label: '原因', code: 1 }]
      .map(reason => ({ policyVersion: reporting.policyVersion, reasons: [reporting.reasons[0], reason] })),
  ];
  for (const taxonomy of invalid) {
    const h = harness(); let selections = 0;
    h.request({ selectReportReason: () => { selections += 1; return Promise.resolve(0); } });
    await tick(); h.calls[0].success({ code: 200, data: { reporting: taxonomy } }); await tick();
    assert.equal(selections, 0); assert.equal(h.calls.length, 1);
    assert.equal(h.results[0].code, 503);
    assert.equal(h.results[0].msg, '举报原因暂不可用');
  }
});

test('owner change before dispatch, during capabilities or during selection cannot report', async () => {
  for (const phase of ['dispatch', 'capabilities', 'selection']) {
    const h = harness(); let select;
    h.request({ selectReportReason: () => new Promise(resolve => { select = resolve; }) });
    if (phase === 'dispatch') h.setActor(990012);
    await tick();
    if (phase !== 'dispatch') {
      if (phase === 'capabilities') h.setActor(990012);
      h.calls[0].success({ code: 200, data: { reporting } }); await tick();
      if (phase === 'selection') { h.setActor(990012); select(0); await tick(); }
    }
    assert.equal(h.calls.length, phase === 'dispatch' ? 0 : 1);
    assert.equal(h.results[0].code, 409);
  }
});

test('abort before dispatch or during either transport suppresses late callbacks and completes once', async () => {
  for (const phase of ['dispatch', 'capabilities', 'reports']) {
    const h = harness(); const task = h.request();
    if (phase === 'dispatch') task.abort();
    await tick();
    if (phase === 'reports') { h.calls[0].success({ code: 200, data: { reporting } }); await tick(); }
    if (phase !== 'dispatch') {
      task.abort();
      h.calls[h.calls.length - 1].success({ code: 200, data: { reporting } }); await tick();
    }
    assert.equal(h.results.length, 0); assert.equal(h.failures.length, 0); assert.equal(h.tips.length, 0);
    assert.equal(h.completions.length, 1);
    assert.equal(h.calls.length, phase === 'dispatch' ? 0 : phase === 'reports' ? 2 : 1);
  }
});

test('business errors, transport errors and cancellation retain separate callback/toast semantics', async () => {
  for (const autoErrorToast of [undefined, false]) {
    const h = harness(); h.request({ autoErrorToast }); await tick();
    h.calls[0].success({ code: 503, msg: '稍后重试' }); await tick();
    assert.equal(h.results[0].code, 503); assert.equal(h.failures.length, 0);
    assert.deepEqual(h.tips, autoErrorToast === false ? [] : ['稍后重试']);
  }
  for (const callback of ['fail', 'successStatusAbnormal']) {
    const h = harness(); h.request(); await tick();
    h.calls[0][callback]({ errMsg: 'request:fail timeout' }, 502); await tick();
    assert.equal(h.results.length, 0); assert.equal(h.failures.length, 1); assert.equal(h.tips.length, 0);
  }
  const h = harness(); h.request({ selectReportReason: () => Promise.reject({ code: 499, msg: '已取消举报' }) });
  await tick(); h.calls[0].success({ code: 200, data: { reporting } }); await tick();
  assert.equal(h.results[0].code, 499); assert.equal(h.calls.length, 1); assert.equal(h.tips.length, 0);
});

test('same-client overlapping reports remain blocked until the active operation settles', async () => {
  const h = harness(); h.request(); await tick(); h.request(); await tick();
  assert.equal(h.calls.length, 1); assert.equal(h.results[0].code, 409);
  h.calls[0].success({ code: 503, msg: '稍后重试' }); await tick();
  h.request(); await tick(); assert.equal(h.calls.length, 2);
  h.calls[1].success({ code: 503, msg: '稍后重试' }); await tick();
});
