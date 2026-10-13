'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { createRequestClient } = require('../../utils/transport/request-client.js');

const PAGE_PATH = path.resolve(__dirname, '../../subpackageP3/pages/stamp-album/index/index.js');
const pageRequire = createRequire(PAGE_PATH);
const requests = [];
const client = createRequestClient({
  wxRequest(options) {
    requests.push(options);
    return { abort() {} };
  },
  getBaseUrl() { return ''; },
  getAuthorization() { return ''; },
  hideToast() {},
  showToast() {},
});

let definition;
vm.runInNewContext(fs.readFileSync(PAGE_PATH, 'utf8'), {
  getApp: () => ({ getUserID: () => 7, sendRequest: options => client.send(options) }),
  Page: config => { definition = config; },
  require: id => pageRequire(id),
  wx: {
    getWindowInfo: () => ({ windowWidth: 390 }),
    getSystemInfoSync: () => ({ windowWidth: 390 }),
    navigateTo() {},
  },
});

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

function mount() {
  const trace = { retired: false, writesAfterRetire: 0 };
  const page = {
    data: plain(definition.data),
    setData(patch, callback) {
      if (trace.retired) trace.writesAfterRetire += 1;
      Object.assign(this.data, patch);
      if (callback) callback.call(this);
    },
  };
  for (const [name, value] of Object.entries(definition)) {
    if (typeof value === 'function') page[name] = value.bind(page);
  }
  page.onLoad();
  return {
    page,
    trace,
    retire() {
      trace.retired = true;
      if (typeof page.onUnload === 'function') page.onUnload();
    },
  };
}

function stamp(id) {
  return { id, picUrl: `https://unit.invalid/stamp-${id}.jpg` };
}

function succeed(request, rows, total) {
  request.success({ statusCode: 200, data: { code: 200, data: { list: rows, total } } });
  request.complete({ statusCode: 200, data: { code: 200 } });
}

function fail(request) {
  request.fail({ errMsg: 'request:fail timeout' });
  request.complete({ errMsg: 'request:fail timeout' });
}

function abnormal(request) {
  request.success({ statusCode: 500, data: { code: 500, msg: 'gateway error' } });
  request.complete({ statusCode: 500, data: { code: 500 } });
}

function state(page) {
  return {
    ids: Array.from(page.data.items || [], item => item.id),
    pics: Array.from(page.data.items || [], item => item.picUrl),
    total: page.data.total,
    pageNum: page.data.pageNum,
    loading: page.data.loading,
    loaded: page.data.loaded,
    error: page.data.error,
    noMore: page.data.noMore,
  };
}

test('集邮册卸载后拒绝旧 success/fail/abnormal；当前续页失败按同游标重试', () => {
  const retiredSuccess = mount();
  const oldSuccess = requests[0];
  retiredSuccess.retire();
  const retiredFail = mount();
  const oldFail = requests[1];
  retiredFail.retire();
  const retiredAbnormal = mount();
  const oldAbnormal = requests[2];
  retiredAbnormal.retire();

  const current = mount();
  succeed(requests[3], [stamp(100)], 3);
  assert.deepEqual(state(current.page), {
    ids: [100],
    pics: ['https://unit.invalid/stamp-100.jpg'],
    total: 3,
    pageNum: 2,
    loading: false,
    loaded: true,
    error: false,
    noMore: false,
  });

  current.page.onReachBottom();
  const continuation = requests[4];
  assert.deepEqual(plain(continuation.data), { pageNum: 2, pageSize: 50 });
  const confirmed = plain(current.page.data.items);
  fail(continuation);
  assert.deepEqual(plain(current.page.data.items), confirmed);
  assert.equal(current.page.data.pageNum, 2);
  assert.equal(current.page.data.loading, false);
  assert.equal(current.page.data.error, true);

  current.page.retryLoad();
  const retry = requests[5];
  assert.deepEqual(plain(retry.data), { pageNum: 2, pageSize: 50 });
  assert.deepEqual(plain(current.page.data.items), confirmed);
  assert.equal(current.page.data.loading, true);
  succeed(retry, [stamp(200), stamp(201)], 3);
  const currentFinal = state(current.page);
  assert.deepEqual(currentFinal, {
    ids: [100, 200, 201],
    pics: [
      'https://unit.invalid/stamp-100.jpg',
      'https://unit.invalid/stamp-200.jpg',
      'https://unit.invalid/stamp-201.jpg',
    ],
    total: 3,
    pageNum: 3,
    loading: false,
    loaded: true,
    error: false,
    noMore: true,
  });

  succeed(oldSuccess, [stamp(1)], 1);
  fail(oldFail);
  abnormal(oldAbnormal);
  assert.deepEqual(state(current.page), currentFinal, '旧页回调不得污染新 Page');
  assert.deepEqual([
    retiredSuccess.trace.writesAfterRetire,
    retiredFail.trace.writesAfterRetire,
    retiredAbnormal.trace.writesAfterRetire,
  ], [0, 0, 0], '退役 Page 不得接收任何 success/fail/abnormal setData');
});
