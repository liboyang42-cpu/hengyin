'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {readClientVersion} = require('../../utils/client-version');
const {createRequestClient} = require('../../utils/transport/request-client');

test('uses actual platform version and has no fabricated package or latest version fallback', () => {
  for (const version of ['1.2.3', '2.10', 'v3.0.0-rc.1']) assert.equal(readClientVersion({getAccountInfoSync: () => ({miniProgram: {version}})}), version);
  for (const api of [null, {}, {getAccountInfoSync: () => ({miniProgram: {}})}, {getAccountInfoSync: () => {throw Error('unavailable');}}]) assert.equal(readClientVersion(api), '');
  for (const version of [42, null, '', 'x\r\nAuthorization: injected', 'x'.repeat(65)]) assert.equal(readClientVersion({getAccountInfoSync: () => ({miniProgram: {version}})}), '');
  const app = fs.readFileSync(path.resolve(__dirname, '../../app.js'), 'utf8');
  assert.match(app, /getClientVersion: function \(\) \{ return readClientVersion\(wx\); \}/);
});

test('version header is added from platform getter without changing auth/content type/custom headers', () => {
  const calls = [];
  const client = createRequestClient({wxRequest: o => calls.push(o), getBaseUrl: () => 'https://base', getAuthorization: () => 'current', getClientVersion: () => '2.1.4'});
  const header = {'content-type': 'application/json', 'X-Trace': 'trace'};
  client.send({url: '/read', method: 'POST', header});
  assert.deepEqual(calls[0].header, {'content-type': 'application/json', 'X-Trace': 'trace', Authorization: 'current', 'X-Mini-Program-Version': '2.1.4'});
  assert.equal(header['X-Mini-Program-Version'], undefined);
});

test('missing or invalid platform version keeps old requests compatible and omits version header', () => {
  for (const getClientVersion of [undefined, () => '', () => '\r\n', () => {throw Error('unavailable');}]) {
    let options;
    const client = createRequestClient({wxRequest: o => {options = o;}, getBaseUrl: () => 'https://base', getClientVersion});
    client.send({url: '/read'});
    assert.equal(options.header['X-Mini-Program-Version'], undefined);
  }
});

test('426 HTTP or business response never succeeds or renews login and settles failure/complete once', () => {
  for (const status of [200, 426]) {
    let options, success = 0, renewals = 0, completes = 0;
    const toasts = [], failures = [];
    const client = createRequestClient({wxRequest: o => {options = o;}, getBaseUrl: () => 'https://base', reLogin: () => {renewals++;}, showToast: s => toasts.push(s)});
    client.send({url: '/write', method: 'POST', success: () => {success++;}, fail: (body, code) => failures.push(code), complete: () => {completes++;}});
    const res = {statusCode: status, data: {code: 426, msg: 'internal version path'}};
    options.success(res); options.complete(res);
    assert.deepEqual(failures, [426]); assert.equal(success, 0); assert.equal(renewals, 0); assert.equal(completes, 1);
    assert.match(toasts[0], /请保存后重开/); assert.doesNotMatch(toasts[0], /internal/);
    client.send({url: '/write', fail: () => {}}); options.success(res); options.complete(res);
    assert.equal(toasts.length, 1);
  }
});

test('HTTP permissions and ordinary failure are not overwritten by a misleading body version code', () => {
  for (const status of [403, 500]) {
    let options, received;
    const toasts = [];
    const client = createRequestClient({wxRequest: o => {options = o;}, getBaseUrl: () => 'https://base', showToast: s => toasts.push(s)});
    client.send({url: '/read', successStatusAbnormal: (body, code) => {received = code;}});
    options.success({statusCode: status, data: {code: 426}});
    assert.equal(received, status); assert.deepEqual(toasts, []);
  }
});

test('multipart carries actual version and 426 never becomes a successful upload', () => {
  const {createUploadClient} = require('../../utils/transport/upload-client');
  for (const mode of ['one', 'all']) {
    for (const response of [{statusCode: 426, data: 'invalid-json'}, {statusCode: 200, data: '{"code":426}'}]) {
      let options, result;
      const client = createUploadClient({wxUploadFile: o => {options = o; return {abort() {}};}, getBaseUrl: () => 'https://base', getAuthorization: () => 'current', getClientVersion: () => '2.1.4'});
      if (mode === 'one') client.uploadOne('file', {onDone: r => {result = r;}});
      else client.uploadAll(['file'], {onDone: r => {result = r;}});
      assert.equal(options.header['X-Mini-Program-Version'], '2.1.4');
      options.success(response);
      assert.equal(result.ok, false);
      const failure = mode === 'one' ? result : result.failures[0];
      assert.equal(failure.reason, 'upgradeRequired');
      assert.match(failure.msg, /请先保存内容/);
    }
  }
});
