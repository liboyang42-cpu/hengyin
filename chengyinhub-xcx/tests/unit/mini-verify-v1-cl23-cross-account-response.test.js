'use strict';
// CL-23(CX-17 / SC-FAULT-CROSS-ACCOUNT):旧请求回包不覆盖新账号。
// 以会员 A 发起请求/上传,回包前切到会员 B,再把 A 的成功与 A 的失败送回来:
// 页面回调不得收到 A 的数据;请求层只允许以 sessionChanged 收尾一次。
// 负控:把 request-client.js 成功/失败分支里的 sessionChanged() 判定去掉,本文件必须真红。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createRequestClient } = require('../../utils/transport/request-client.js');
const { createUploadClient } = require('../../utils/transport/upload-client.js');

function requestFixture() {
  let member = 'A', token = 'tok-A';
  const calls = [], events = [];
  const client = createRequestClient({
    wxRequest: (options) => { calls.push(options); return { abort() {} }; },
    getBaseUrl: () => 'https://fixture',
    getUserID: () => member,
    getAuthorization: () => token,
    reLogin: (cb) => cb(true),
    scheduleRetry: () => {},
    showToast: (msg) => events.push({ type: 'toast', msg }),
  });
  const param = {
    url: '/api/member/info', method: 'GET',
    success: (body) => events.push({ type: 'success', body }),
    fail: (body) => events.push({ type: 'fail', body }),
    successStatusAbnormal: (body) => events.push({ type: 'abnormal', body }),
    complete: (body) => events.push({ type: 'complete', body }),
  };
  return { client, calls, events, param, switchTo(id, tok) { member = id; token = tok; } };
}

const A_DATA = { code: 200, data: { memberId: 'A', nickname: 'A 的昵称', phone: '138****0001' } };

function assertNoAData(events) {
  const leaked = JSON.stringify(events);
  assert.doesNotMatch(leaked, /A 的昵称|138\*\*\*\*0001/, 'A 的回包数据不得出现在任何页面回调里');
  assert.equal(events.filter((e) => e.type === 'success').length, 0, '旧账号的成功不得进入 success');
  assert.equal(events.filter((e) => e.type === 'toast').length, 0, '旧账号的回包不得给新账号弹提示');
  const fails = events.filter((e) => e.type === 'fail');
  assert.equal(fails.length, 1, '只以 sessionChanged 收尾一次');
  assert.equal(fails[0].body.reason, 'sessionChanged');
  assert.equal(events.filter((e) => e.type === 'complete').length, 1);
}

test('CL-23 request:A 发起、换成 B 后 A 的成功回包不进 success,只以 sessionChanged 收尾', () => {
  const f = requestFixture();
  f.client.send(f.param);
  assert.equal(f.calls[0].header.Authorization, 'tok-A');
  f.switchTo('B', 'tok-B');
  f.calls[0].success({ statusCode: 200, data: JSON.parse(JSON.stringify(A_DATA)) });
  f.calls[0].complete({ statusCode: 200, data: A_DATA });
  assertNoAData(f.events);
  assert.equal(f.calls.length, 1, '不得以 B 的身份重发');
});

test('CL-23 request:A 发起、换成 B 后 A 的失败(网络错 / HTTP 500 / 业务错)不带 A 的报文进页面', () => {
  const failures = [
    (call) => call.fail({ errMsg: 'request:fail A-network' }),
    (call) => call.success({ statusCode: 500, data: { code: 500, msg: 'A 的昵称 服务端异常' } }),
    (call) => call.success({ statusCode: 200, data: { code: 500, msg: 'A 的昵称 业务失败' } }),
  ];
  for (const deliver of failures) {
    const f = requestFixture();
    f.client.send(Object.assign({}, f.param, { retry: 2 }));
    f.switchTo('B', 'tok-B');
    deliver(f.calls[0]);
    f.calls[0].complete({});
    assertNoAData(f.events);
    assert.equal(f.calls.length, 1);
  }
});

test('CL-23 request:同一会员同一 token 时成功照常回到页面(正控)', () => {
  const f = requestFixture();
  f.client.send(f.param);
  f.calls[0].success({ statusCode: 200, data: JSON.parse(JSON.stringify(A_DATA)) });
  f.calls[0].complete({});
  assert.equal(f.events.filter((e) => e.type === 'success').length, 1);
  assert.equal(f.events[0].body.data.nickname, 'A 的昵称');
});

function uploadFixture() {
  let member = 'A', token = 'tok-A';
  const calls = [], done = [];
  const client = createUploadClient({
    getBaseUrl: () => 'https://fixture', getAuthorization: () => token, getUserID: () => member,
    wxUploadFile: (value) => { calls.push(value); return { abort() {} }; },
    setTimer: () => ({ unref() {} }), clearTimer: () => {},
  });
  return { client, calls, done, switchTo(id, tok) { member = id; token = tok; } };
}

test('CL-23 upload:uploadAll / uploadOne 换号后 A 的成功与失败都不进 onDone', () => {
  for (const outcome of ['success', 'fail']) {
    const pool = uploadFixture();
    const control = pool.client.uploadAll(['A-photo.jpg'], { onDone: (r) => pool.done.push(r) });
    assert.equal(pool.calls[0].header.Authorization, 'Bearer tok-A');
    pool.switchTo('B', 'tok-B');
    if (outcome === 'success') pool.calls[0].success({ data: JSON.stringify({ code: 200, url: 'https://fixture/A-photo.jpg' }) });
    else pool.calls[0].fail({ errMsg: 'uploadFile:fail A-network' });
    assert.deepEqual(pool.done, [], 'uploadAll ' + outcome + ' 不得回调到 B');
    control.abort();

    const one = uploadFixture();
    const single = one.client.uploadOne('A-voice.mp3', { onDone: (r) => one.done.push(r) });
    one.switchTo('B', 'tok-B');
    if (outcome === 'success') one.calls[0].success({ data: JSON.stringify({ code: 200, data: { safeText: 'A 的昵称' } }) });
    else one.calls[0].fail({ errMsg: 'uploadFile:fail A-network' });
    assert.deepEqual(one.done, [], 'uploadOne ' + outcome + ' 不得回调到 B');
    single.abort();
  }
});
