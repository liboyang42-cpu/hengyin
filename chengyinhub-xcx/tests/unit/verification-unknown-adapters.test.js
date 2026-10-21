const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createWriteActionWorkflow } = require('../../utils/write-action-workflow.js');
const verification = require('../../utils/merchant-verification.js');
const entries = [
  ['pages/club/detail/index.js', 'submitClubScan'],
  ['pages/merchant/index/index.js', '_submitVerification'],
  ['subpackageMember/components/cy/profile/index.js', '_submitVerification'],
  ['subpackageMember/components/scene-member-participation-detail/index.js', '_submitVerification'],
];
// 执行生产 adapter 方法本体及真实 workflow，隔离无关页面加载/相机 UI。
function harness(file, method, transport) {
  const source = fs.readFileSync(path.resolve(__dirname, '../..', file), 'utf8');
  const match = source.match(new RegExp('^( +)' + method + '(?:\\(|: function)', 'm'));
  assert.ok(match);
  const start = match.index + match[1].length;
  const end = source.indexOf('\n' + match[1] + '},', start);
  assert.ok(end > start);
  const body = source.slice(start, end + match[1].length + 3);
  const requests = [], messages = [];
  let deadline;
  const app = { sendRequest(options) { requests.push(options); return { abort() {} }; } };
  if (transport) {
    const { createRequestClient } = require('../../utils/transport/request-client.js');
    const client = createRequestClient({ getBaseUrl: () => 'https://example.invalid',
      wxRequest(options) { requests.push(options); return { abort() {} }; } });
    app.sendRequest = options => client.send(options);
  }
  const loading = { show() {}, hide() {} };
  const workflow = options => createWriteActionWorkflow({ ...options,
    setTimer(fn) { deadline = fn; return 1; }, clearTimer() {} });
  const methodObject = new Function('app', 'loading', 'cyLoading', 'cyToast', 'modal',
    'jsonHeader', 'createWriteActionWorkflow', 'classifyVerificationOutcome', 'offerVerificationReadback',
    'return ({' + body + '});')(app, loading, loading, m => messages.push(m),
      { show: o => messages.push(o.content) }, () => ({}), workflow,
      verification.classifyVerificationOutcome, () => {});
  const owner = { ...methodObject };
  const invoke = () => method === 'submitClubScan'
    ? owner[method]('/api/registration/scan_qr_code', { code: 'ticket', data: { code: 'ticket' } })
    : owner[method]('verify:ticket', '核销中', { url: '/api/registration/scan_qr_code', method: 'POST' }, r => messages.push(r.msg));
  return { requests, messages, invoke, deadline: () => deadline() };
}
for (const [file, method] of entries) {
  for (const [label, callback, response, status] of [
    ['丢包', 'fail', { errMsg: 'request:fail timeout' }],
    ['HTTP 500', 'successStatusAbnormal', { code: 500, msg: '网关失败' }, 500],
    ['HTTP 503伪成功正文', 'successStatusAbnormal', { code: 200, msg: '成功' }, 503],
    ['HTTP 202', 'successStatusAbnormal', { code: 200, msg: '成功' }, 202],
    ['HTTP 408', 'successStatusAbnormal', { code: 408, msg: '超时' }, 408],
    ['202', 'success', { code: 202 }],
    ['null', 'success', null],
    ['畸形', 'success', { code: true, msg: 'ok' }],
    ['非正规状态码', 'success', { code: '2e2', msg: 'ok' }],
    ['无正文业务错误', 'success', { code: 500 }],
  ]) test(file + ' ' + label + '保留锁，不发第二POST', () => {
    const h = harness(file, method); h.invoke();
    h.requests[0][callback](response, status); h.invoke();
    assert.equal(h.requests.length, 1);
    assert.ok(h.messages.some(m => /待确认|勿重复/.test(m)));
    assert.ok(h.messages.every(m => !/失败.*重试/.test(m)));
  });
  for (const [label, callback, response, status] of [
    ['明确业务拒绝', 'success', { code: 500, msg: '二维码已过期' }],
    ['HTTP401', 'fail', { code: 401, msg: '未登录' }, 401],
    ['HTTP403', 'successStatusAbnormal', null, 403],
  ]) test(file + ' ' + label + '允许再次提交', () => {
    const h = harness(file, method); h.invoke();
    h.requests[0][callback](response, status); h.invoke();
    assert.equal(h.requests.length, 2);
  });
  test(file + ' deadline持锁、迟到成功不能解锁', () => {
    const h = harness(file, method); h.invoke(); h.deadline();
    h.requests[0].success({ code: 200, msg: '成功' }); h.invoke();
    assert.equal(h.requests.length, 1);
  });
}
test('未知回执不显示失败或重试；合法replayed保留', () => {
  for (const response of [null, {}, { code: 202 }, { code: true }, { code: 500 }]) {
    const result = verification.resolveVerificationResult(response);
    assert.equal(result.state, 'unknown');
    assert.doesNotMatch(result.title + result.message, /失败|重试/);
  }
  assert.equal(verification.resolveVerificationResult({ code: 200, data: { replayed: true } }).state, 'duplicate');
});

for (const [file, method] of entries) {
  for (const [label, http, body, count] of [
    ['业务拒绝', 200, { code: 500, msg: '二维码已过期' }, 2],
    ['鉴权拒绝', 401, { code: 401, msg: '未登录' }, 2],
    ['权限拒绝', 403, null, 2],
    ['网关失败', 502, { code: 500, msg: '网关错误' }, 1],
    ['HTTP202', 202, null, 1],
    ['HTTP200畸形', 200, null, 1],
  ]) test(file + ' 真实request-client透传 ' + label, () => {
    const h = harness(file, method, true); h.invoke();
    h.requests[0].success({ statusCode: http, data: body }); h.invoke();
    assert.equal(h.requests.length, count);
  });
}
