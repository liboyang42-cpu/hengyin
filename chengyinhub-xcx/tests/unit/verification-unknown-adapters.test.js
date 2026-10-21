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
function harness(file, method, transport, request) {
  request = request || { url: '/api/registration/scan_qr_code', method: 'POST', data: { type: 'activity', code: 'ticket' } };
  const source = fs.readFileSync(path.resolve(__dirname, '../..', file), 'utf8');
  const match = source.match(new RegExp('^( +)' + method + '(?:\\(|: function)', 'm'));
  assert.ok(match);
  const start = match.index + match[1].length;
  const end = source.indexOf('\n' + match[1] + '},', start);
  assert.ok(end > start);
  let body = source.slice(start, end + match[1].length + 3);
  const hook = method === 'submitClubScan' ? 'onUnload' : file.startsWith('pages/') ? '' : 'detached';
  if (hook) {
    const hm = source.match(new RegExp('^( +)' + hook + '(?:\\(|: function)', 'm'));
    const hs = hm.index + hm[1].length;
    const he = source.indexOf('\n' + hm[1] + '}' + (hook === 'detached' && file.includes('/profile/') ? '' : ','), hs);
    assert.ok(he > hs);
    const row = source.slice(hs, he + hm[1].length + 2).replace(/,$/, '');
    body += row + ',';
  }
  const requests = [], messages = [];
  let memberId = 11;
  let deadline;
  const app = { getUserID: () => memberId, sendRequest(options) { requests.push(options); return { abort() {} }; } };
  if (transport) {
    const { createRequestClient } = require('../../utils/transport/request-client.js');
    const client = createRequestClient({ getBaseUrl: () => 'https://example.invalid', getUserID: () => memberId,
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
  const owner = { ...methodObject, clearGroupCodeTimer() {}, _invalidateParticipationCancellation() {} };
  const invoke = () => method === 'submitClubScan'
    ? owner[method](request.url, { code: request.data.code, data: request.data })
    : owner[method]('verify:ticket', '核销中', request, r => messages.push(r.msg));
  return { owner, requests, messages, invoke, dispose: () => hook && owner[hook](), switchOwner: id => { memberId = id; }, deadline: () => deadline() };
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
    ['事务后发分异常', 'success', { code: 500, msg: '发分失败' }],
    ['空成功包', 'success', { code: 200 }],
  ]) test(file + ' ' + label + '保留锁，不发第二POST', () => {
    const h = harness(file, method); h.invoke();
    h.requests[0][callback](response, status); h.invoke();
    assert.equal(h.requests.length, 1);
    assert.ok(h.messages.some(m => /待确认|勿重复/.test(m)));
    assert.ok(h.messages.every(m => !/失败.*重试/.test(m)));
  });
  for (const [label, callback, response, status] of [
    ['明确HTTP拒绝', 'successStatusAbnormal', { code: 400, msg: '参数错误' }, 400],
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
    ['业务500不能证明未提交', 200, { code: 500, msg: '二维码已过期' }, 1],
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

const endpointCases = [
  ['/api/coupon/verification', { code: 'coupon' }, { code: 200, msg: '核销成功' }],
  ['/api/coupon/verification', { code: 'coupon' }, { code: 200, msg: '该优惠券已核销', data: { alreadyUsed: true, useTime: null } }],
  ['/api/registration/scan_qr_code', { type: 'activity', code: 'ticket' }, { code: 200, msg: '核销成功' }],
  ['/api/registration/scan_dynamic_code', { code: 'v1.1.activity.9.1.sig' }, { code: 200, msg: '核销成功' }],
  ['/api/registration/scan_group_member_ticket', { code: 'ticket', activityId: 3 }, { code: 200, msg: '核销成功' }],
  ['/api/registration/scan_qr_code_station', { code: 'ticket', registrationMerchantId: 8 }, { code: 200, msg: '核销成功' }],
  ['/api/registration/scan_qr_code_chapter', { code: 'ticket', chapterId: 4 }, { code: 200, msg: '核销成功', data: { chapterId: 4, redemptionId: 5 } }],
  ['/api/registration/scan_qr_code_chapter', { code: 'ticket', chapterId: 4 }, { code: 200, data: { chapterId: 4, redemptionId: 5, replayed: true } }],
  ['/api/verify/groupcode/redeem', { code: 'v1.2.group_v2_3_4.9.1.sig' }, { code: 200, msg: '已记录接待该团', data: { topicId: 2, activityId: 3, leaderMemberId: 4, coopOrderId: 5, alreadyVerified: false } }],
  ['/api/registration/scan_qr_code', { code: 'ticket' }, { code: 500, msg: '该订单已核销', data: { alreadyVerified: true } }],
  ['/api/registration/scan_qr_code', { code: 'ticket' }, { code: 500, msg: '请选择要核销的章节', data: { needChapterChoice: true, chapters: [{ id: 4 }] } }],
];
for (const [file, method] of entries) {
  for (const [url, data, response] of endpointCases) test(file + ' 接受真实端点回执 ' + url + ' ' + response.msg, () => {
    const h = harness(file, method, false, { url, data, method: 'POST' }); h.invoke();
    h.requests[0].success(response); h.invoke();
    assert.equal(h.requests.length, 2);
  });
  for (const [url, data, response] of [
    ['/api/registration/scan_qr_code_chapter', { chapterId: 4 }, { code: 200, msg: '核销成功', data: {} }],
    ['/api/registration/scan_qr_code_chapter', { chapterId: 4 }, { code: 200, data: { chapterId: 7, redemptionId: 5 } }],
    ['/api/registration/scan_qr_code_chapter', { chapterId: 4 }, { code: 200, data: { chapterId: true, redemptionId: 5 } }],
    ['/api/verify/groupcode/redeem', { code: 'v1.2.group_v2_3_4.9.1.sig' }, { code: 200, msg: '核销成功' }],
    ['/api/verify/groupcode/redeem', { code: 'v1.2.group_v2_3_4.9.1.sig' }, { code: 200, data: { topicId: 9, activityId: 3, leaderMemberId: 4, coopOrderId: 5, alreadyVerified: false } }],
    ['/api/coupon/verification', { code: 'coupon' }, { code: 200 }],
    ['/api/registration/scan_qr_code', { code: 'ticket' }, { code: 500, msg: '数据库操作异常，请稍后重试' }],
  ]) test(file + ' 畸形/异目标/事务后失败不能解锁 ' + url + JSON.stringify(response), () => {
    const h = harness(file, method, false, { url, data, method: 'POST' }); h.invoke();
    h.requests[0].success(response); h.invoke();
    assert.equal(h.requests.length, 1);
  });
  test(file + ' 真实request-client A切B迟到回包不提示B、B不被A锁死，返回A仍未知', () => {
    const h = harness(file, method, true); h.invoke(); h.switchOwner(22);
    h.requests[0].success({ statusCode: 200, data: { code: 200, msg: '核销成功' } });
    assert.deepEqual(h.messages, []);
    h.invoke(); assert.equal(h.requests.length, 2);
    h.switchOwner(11); h.invoke(); assert.equal(h.requests.length, 2);
  });
  test(file + ' 组件/页面世代变化后旧回包不再提示', () => {
    const h = harness(file, method); h.invoke();
    if (method === 'submitClubScan') h.owner._scanGeneration = 1;
    else if (file.startsWith('pages/')) h.owner._verificationEpoch = 1;
    else if (file.includes('/profile/')) h.owner._verificationGeneration = 1;
    else h.owner._detailGeneration = 1;
    h.requests[0].fail({ reason: 'sessionChanged' });
    assert.deepEqual(h.messages, []);
  });
}

test('真实事务后异常反例：核销事务先于发分，最外层仍返回无类型业务500', () => {
  const source = fs.readFileSync(path.resolve(__dirname, '../../../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java'), 'utf8');
  const method = source.slice(source.indexOf('public AjaxResult scanQrCode(String type, String code, Long activityId)'));
  assert.ok(method.indexOf('registrationService.verifyAndSettle(') < method.indexOf('pointsAwardService.award('));
  assert.match(method, /catch \(Exception e\) \{\s*return AjaxResult.error\(e.getMessage\(\)\)/);
});

for (const [file, method] of entries.filter(row => row[0] !== 'pages/merchant/index/index.js')) {
  test(file + ' 实际销毁钩子中止请求、禁止旧回包UI和在已销毁实例上新写入', () => {
    const h = harness(file, method); h.invoke(); h.dispose();
    h.requests[0].success({ code: 200, msg: '核销成功' }); h.invoke();
    assert.equal(h.requests.length, 1);
    assert.deepEqual(h.messages, []);
  });
}
