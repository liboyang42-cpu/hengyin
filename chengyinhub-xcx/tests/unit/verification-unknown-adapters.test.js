const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const verification = require('../../utils/merchant-verification.js');
const entries = [
  ['pages/club/detail/index.js', 'submitClubScan'],
  ['pages/merchant/index/index.js', '_submitVerification'],
  ['subpackageMember/components/cy/profile/index.js', '_submitVerification'],
  ['subpackageMember/components/scene-member-participation-detail/index.js', '_submitVerification'],
];
// 执行生产 adapter 方法本体及真实 workflow，隔离无关页面加载/相机 UI。
function harness(file, method, transport, request, sharedStorage) {
  request = request || { url: '/api/registration/scan_qr_code', method: 'POST', data: { type: 'activity', code: 'ticket' } };
  const source = fs.readFileSync(path.resolve(__dirname, '../..', file), 'utf8');
  const match = source.match(new RegExp('^( +)' + method + '(?:\\(|: function)', 'm'));
  assert.ok(match);
  const start = match.index + match[1].length;
  const end = source.indexOf('\n' + match[1] + '},', start);
  assert.ok(end > start);
  let body = source.slice(start, end + match[1].length + 3);
  const hook = method === 'submitClubScan' ? 'onUnload' : file.startsWith('pages/') ? 'onUnload' : 'detached';
  if (hook) {
    const hm = source.match(new RegExp('^( +)' + hook + '(?:\\(|: function)', 'm'));
    const hs = hm.index + hm[1].length;
    const he = source.indexOf('\n' + hm[1] + '}' + (hook === 'detached' && file.includes('/profile/') ? '' : ','), hs);
    assert.ok(he > hs);
    const row = source.slice(hs, he + hm[1].length + 2).replace(/,$/, '');
    body += row + ',';
  }
  for (const [name, alias] of (file.startsWith('pages/') ? [['onShow', 'pageShow'], ['onHide', 'pageHide']] : [['show', 'pageShow'], ['hide', 'pageHide']])) {
    const scope = file.startsWith('pages/') ? source : source.slice(source.indexOf('  pageLifetimes:'));
    const hm = scope.match(new RegExp('^( +)' + name + '(?:\\(|: function)', 'm'));
    if (!hm) continue;
    const hs = hm.index + hm[1].length;
    const he = scope.indexOf('\n' + hm[1] + '}', hs);
    assert.ok(he > hs);
    body += scope.slice(hs, he + hm[1].length + 2).replace(new RegExp('^' + name), alias) + ',';
  }
  const values = {};
  const storage = sharedStorage || { getStorageSync: key => values[key],
    setStorageSync(key, value) { values[key] = JSON.parse(JSON.stringify(value)); },
    removeStorageSync(key) { delete values[key]; } };
  const requests = [], messages = [];
  let memberId = 11;
  let deadline;
  const app = { getUserID: () => memberId, getAvatar: () => '', getNickname: () => '', sendRequest(options) { requests.push(options); return { abort() {} }; } };
  if (transport) {
    const { createRequestClient } = require('../../utils/transport/request-client.js');
    const client = createRequestClient({ getBaseUrl: () => 'https://example.invalid', getUserID: () => memberId,
      wxRequest(options) { requests.push(options); return { abort() {} }; } });
    app.sendRequest = options => client.send(options);
  }
  const loading = { show() {}, hide() {} };
  const workflow = options => verification.createVerificationWorkflow({ ...options,
    setTimer(fn) { deadline = fn; return 1; }, clearTimer() {} });
  const methodObject = new Function('app', 'loading', 'cyLoading', 'cyToast', 'modal',
    'jsonHeader', 'readReducedMotion', 'applyTheme', 'merchantTheme', 'createVerificationWorkflow', 'wx', 'classifyVerificationOutcome', 'offerVerificationReadback',
    'return ({' + body + '});')(app, loading, loading, m => messages.push(m),
      { show: o => messages.push(o.content) }, () => ({}), () => false, () => {}, { merchantPageRestore() {}, merchantPageShow() {} }, workflow, storage,
      verification.classifyVerificationOutcome, () => {});
  const owner = { ...methodObject, clearGroupCodeTimer() {}, resetGameStationSummaries() {}, _invalidateParticipationCancellation() {},
    setData(patch) { Object.assign(this.data, patch); },
    data: { reducedMotion: false, myAvatar: '', myNickname: '', pointsSheet: {}, id: '' },
    _profileAttached: true, _syncProfileReadIdentity: () => true, initializeProfile() {}, _syncCommunityProfile() {},
    refreshCoopPublisherAccess() {}, enterConsole() {}, _syncCancellationAccount() {} };
  const invoke = () => method === 'submitClubScan'
    ? owner[method](request.url, { code: request.data.code, data: request.data })
    : owner[method](request.url + ':' + (request.data.code || ''), '核销中', request, r => messages.push(r.msg));
  return { owner, storage, requests, messages, invoke, dispose: () => { if (hook) owner[hook](); else if (owner._verificationWorkflow) { owner._verificationWorkflow.destroy(); owner._verificationWorkflow = null; } }, switchOwner: id => { memberId = id; }, deadline: () => deadline() };
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
  ]) test(file + ' 畸形/异目标/事务后失败不能解锁 ' + url + (response.msg || '畸形回执'), () => {
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

function durableStorage() {
  const values = {};
  return { values, getStorageSync: key => values[key],
    setStorageSync(key, value) { values[key] = JSON.parse(JSON.stringify(value)); },
    removeStorageSync(key) { delete values[key]; } };
}
for (const [file, method] of entries) {
  for (const outcome of ['inflight', 'deadline', '202', 'postWrite500', 'empty200']) {
    test(file + ' 持久待办销毁重建 ' + outcome, () => {
      const storage = durableStorage();
      const first = harness(file, method, false, undefined, storage); first.invoke();
      assert.equal(first.requests.length, 1);
      if (outcome === 'deadline') first.deadline();
      if (outcome === '202') first.requests[0].success({ code: 202 });
      if (outcome === 'postWrite500') first.requests[0].success({ code: 500, msg: '数据库操作异常，请稍后重试' });
      if (outcome === 'empty200') first.requests[0].success({ code: 200 });
      first.dispose();
      const next = harness(file, method, false, undefined, storage); next.invoke();
      assert.equal(next.requests.length, 0);
      assert.ok(next.messages.some(message => /待确认|勿重复/.test(message)));
      first.requests[0].success({ code: 200, msg: '核销成功' });
      const again = harness(file, method, false, undefined, storage); again.invoke();
      assert.equal(again.requests.length, 0);
    });
  }
  test(file + ' 重建跨账号隔离，B明确成功只清B待办', () => {
    const storage = durableStorage();
    const first = harness(file, method, true, undefined, storage); first.invoke(); first.switchOwner(22);
    first.requests[0].success({ statusCode: 200, data: { code: 200, msg: '核销成功' } }); first.dispose();
    const next = harness(file, method, false, undefined, storage); next.switchOwner(22); next.invoke();
    assert.equal(next.requests.length, 1); next.requests[0].success({ code: 200, msg: '核销成功' }); next.dispose();
    const original = harness(file, method, false, undefined, storage); original.invoke();
    assert.equal(original.requests.length, 0);
  });
  for (const failure of ['read', 'write', 'silentWrite', 'corrupt']) test(file + ' 存储失败不发POST ' + failure, () => {
    const storage = durableStorage();
    if (failure === 'read') storage.getStorageSync = () => { throw Error('unavailable'); };
    if (failure === 'write') storage.setStorageSync = () => { throw Error('quota'); };
    if (failure === 'silentWrite') storage.setStorageSync = () => {};
    if (failure === 'corrupt') storage.getStorageSync = () => ({ version: 1 });
    const h = harness(file, method, false, undefined, storage); h.invoke(); h.invoke();
    assert.equal(h.requests.length, 0);
    assert.ok(h.messages.some(message => /未提交/.test(message)));
  });
  test(file + ' 成功回执清理存储失败不丢失原待办，重建不重发', () => {
    const storage = durableStorage(); storage.removeStorageSync = () => { throw Error('unavailable'); };
    const h = harness(file, method, false, undefined, storage); h.invoke();
    h.requests[0].success({ code: 200, msg: '核销成功' }); h.dispose();
    const next = harness(file, method, false, undefined, storage); next.invoke();
    assert.equal(next.requests.length, 0);
  });
}
test('待办仅保存原owner与业务键摘要，使用既有SHA256合同', () => {
  const crypto = require('node:crypto');
  const storage = durableStorage();
  const owner = 'synthetic-owner-' + Date.now(); const target = '/synthetic/verification:' + Math.random();
  const flow = verification.createVerificationWorkflow({ storage, getOwner: () => owner, setTimer: () => 1, clearTimer() {} });
  flow.run(owner + ':' + target, () => null, () => {});
  const row = Object.values(storage.values)[0];
  const hash = text => crypto.createHash('sha256').update(text).digest('hex');
  const existingHash = require('../../pages/publish/utils/publish/template-binding.js').sha256;
  assert.equal(row.ownerDigest, existingHash('verification-owner:' + owner));
  assert.equal(row.ownerDigest, hash('verification-owner:' + owner));
  assert.equal(row.targetDigest, hash('verification-target:' + target));
  assert.equal(Object.keys(storage.values)[0], 'verification_unknown_v1:' + hash(row.ownerDigest + ':' + row.targetDigest));
  assert.ok(!JSON.stringify(storage.values).includes(owner)); assert.ok(!JSON.stringify(storage.values).includes(target));
});

for (const [file, method] of entries) test(file + ' 真实hide/show不释放UNKNOWN，重建仍保留', () => {
  const storage = durableStorage(); const h = harness(file, method, false, undefined, storage);
  h.invoke(); h.requests[0].success({ code: 202 });
  const pending = JSON.stringify(storage.values);
  if (h.owner.pageHide) h.owner.pageHide();
  assert.equal(typeof h.owner.pageShow, 'function'); h.owner.pageShow(); h.invoke();
  assert.equal(JSON.stringify(storage.values), pending, 'hide/show must preserve the original UNKNOWN record');
  assert.equal(h.requests.length, 1); h.dispose();
  const next = harness(file, method, false, undefined, storage); next.invoke(); assert.equal(next.requests.length, 0);
});
test('重复旧callback不能结算下一次尝试；deadline后合法迟回执不能清持久待办', () => {
  const storage = durableStorage(); let deadline, first, second;
  const make = () => verification.createVerificationWorkflow({ storage, getOwner: () => 'synthetic',
    setTimer(fn) { deadline = fn; return 1; }, clearTimer() {} });
  const flow = make(); const key = 'synthetic:/synthetic-target';
  flow.run(key, done => { first = done; }, () => {}); first({ status: 'failed' });
  flow.run(key, done => { second = done; }, () => {}); const snapshot = JSON.stringify(storage.values);
  first({ status: 'success' }); assert.equal(JSON.stringify(storage.values), snapshot);
  deadline(); second({ status: 'success' }); assert.equal(JSON.stringify(storage.values), snapshot);
  flow.destroy(); let posts = 0; make().run(key, () => { posts++; }, () => {}); assert.equal(posts, 0);
});

test('正常退出清账号态仍保留原UNKNOWN，重新登录原owner不能重发', () => {
  const storage = durableStorage(); storage.getStorageInfoSync = () => ({ keys: Object.keys(storage.values) });
  storage.values.authorization = 'synthetic';
  const h = harness(entries[0][0], entries[0][1], false, undefined, storage); h.invoke(); h.dispose();
  const { clearStoragePreservingPlayerRoamMemory } = require('../../utils/roam-player-memory.js');
  assert.equal(clearStoragePreservingPlayerRoamMemory(storage), true);
  assert.equal(storage.values.authorization, undefined);
  const next = harness(entries[0][0], entries[0][1], false, undefined, storage); next.invoke();
  assert.equal(next.requests.length, 0);
});

test('同账号同端点原目标在四入口共享持久锁，不能换入口重发', () => {
  const storage = durableStorage();
  const first = harness(entries[0][0], entries[0][1], false, undefined, storage); first.invoke(); first.dispose();
  for (const [file, method] of entries.slice(1)) {
    const next = harness(file, method, false, undefined, storage); next.invoke(); assert.equal(next.requests.length, 0);
  }
});

test('明确回执清理后读取异常不能改报无持久记录的UNKNOWN', () => {
  const storage = durableStorage(); let finish, outcome;
  const originalRead = storage.getStorageSync;
  storage.removeStorageSync = key => { delete storage.values[key]; storage.getStorageSync = () => { throw Error('unavailable'); }; };
  const flow = verification.createVerificationWorkflow({ storage, getOwner: () => 'synthetic', setTimer: () => 1, clearTimer() {} });
  flow.run('synthetic:/synthetic-target', done => { finish = done; }, result => { outcome = result; });
  finish({ status: 'success' }); assert.equal(outcome.status, 'success');
  storage.getStorageSync = originalRead; assert.equal(Object.keys(storage.values).length, 0);
});

for (const [file, method] of entries) {
  test(file + ' 非法UTF16在持久写入和POST前拒绝，不与合法原码共用摘要', () => {
    const storage = durableStorage(); let writes = 0;
    const write = storage.setStorageSync;
    storage.setStorageSync = (key, value) => { writes++; write(key, value); };
    for (const code of ['\ud800\u0000', '\ud800', '\udc00', '\ud800A']) {
      const h = harness(file, method, false, { url: '/api/registration/scan_qr_code', data: { type: 'activity', code } }, storage);
      h.invoke();
      assert.equal(h.requests.length, 0, '非法代理项不得进入写请求');
      assert.equal(writes, 0, '非法代理项不得占用持久待办');
      assert.deepEqual(storage.values, {});
      assert.ok(h.messages.some(message => /未提交/.test(message)));
    }
    const valid = harness(file, method, false, { url: '/api/registration/scan_qr_code', data: { type: 'activity', code: '\u2400' } }, storage);
    valid.invoke();
    assert.equal(valid.requests.length, 1, '合法原码不能被非法UTF16的编码碰撞锁住');
    assert.equal(writes, 1);
  });

  test(file + ' 真实request-client历史code2重登失败释放原待办，重开仍可提交', () => {
    const storage = durableStorage();
    const h = harness(file, method, true, undefined, storage); h.invoke();
    h.requests[0].success({ statusCode: 200, data: { code: 2, msg: '登录已过期' } });
    assert.equal(h.requests.length, 1, '重登失败不能自动重发写请求');
    assert.deepEqual(storage.values, {}, '明确鉴权拒绝不能永久占用UNKNOWN');
    assert.ok(h.messages.every(message => !/待确认/.test(message)));
    h.dispose();
    const next = harness(file, method, false, undefined, storage); next.invoke();
    assert.equal(next.requests.length, 1);
  });
}

test('合法Unicode与摘要块边界保持标准SHA256，非法owner也不得落盘', () => {
  const hash = text => require('node:crypto').createHash('sha256').update(text).digest('hex');
  for (const target of ['', 'a'.repeat(55), 'b'.repeat(56), 'c'.repeat(63), 'd'.repeat(64), 'e'.repeat(1000), '核销🙂𐀀']) {
    const storage = durableStorage();
    const flow = verification.createVerificationWorkflow({ storage, getOwner: () => '11', setTimer: () => 1, clearTimer() {} });
    flow.run('11:' + target, () => null, () => {});
    assert.equal(Object.values(storage.values)[0].targetDigest, hash('verification-target:' + target));
  }
  const storage = durableStorage(); let posts = 0;
  verification.createVerificationWorkflow({ storage, getOwner: () => '\ud800', setTimer: () => 1, clearTimer() {} })
    .run('\ud800:target', () => { posts++; }, () => {});
  assert.equal(posts, 0); assert.deepEqual(storage.values, {});
});

test('历史登录拒绝仅接受fail通道的精确status2与code2，其余仍UNKNOWN', () => {
  for (const code of [2, '2']) {
    assert.equal(verification.classifyVerificationOutcome({ code }, 'fail', 2).status, 'failed');
  }
  for (const [code, channel, status] of [[2, 'success', 200], [2, 'abnormal', 2], ['2e0', 'fail', 2], [true, 'fail', 2], [500, 'fail', 2], [2, 'fail', '2']]) {
    assert.equal(verification.classifyVerificationOutcome({ code }, channel, status).status, 'unknown');
  }
});
