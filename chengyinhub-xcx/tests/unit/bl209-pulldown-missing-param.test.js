'use strict';

// BL-209:俱乐部勋章 / 官方主办页缺参时下拉转圈要收;有参时照旧重读。

const assert = require('node:assert/strict');
const path = require('node:path');
const test = require('node:test');

const ROOT = path.resolve(__dirname, '../..');

function setup() {
  const sandbox = { requests: [], stops: 0 };
  global.getApp = () => ({
    globalData: {},
    getUserID: () => 5,
    sendRequest(options) { sandbox.requests.push(options); return { abort() {} }; },
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  });
  global.getCurrentPages = () => [{}, {}];
  global.wx = new Proxy({
    getStorageSync: () => '',
    stopPullDownRefresh() { sandbox.stops += 1; },
  }, { get(target, key) { return key in target ? target[key] : () => {}; } });
  return sandbox;
}

function loadPage(rel) {
  let definition;
  global.Page = (config) => { definition = config; };
  const abs = path.join(ROOT, rel);
  delete require.cache[require.resolve(abs)];
  require(abs);
  const page = Object.assign({}, definition);
  page.data = JSON.parse(JSON.stringify(definition.data));
  page.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb(); };
  page.selectComponent = () => null;
  return page;
}

const CASES = [
  { name: '俱乐部勋章', rel: 'pages/club/badges/index.js', query: { clubId: '9' }, missingState: ['state', 'missing-param'] },
  { name: '官方主办', rel: 'pages/activity/official-organizer/index.js', query: { id: '7' }, missingState: ['phase', 'missing'] },
];

for (const c of CASES) {
  test(`${c.name}:缺参时下拉只收转圈,不发请求`, () => {
    const sandbox = setup();
    const page = loadPage(c.rel);
    page.onLoad({});
    assert.equal(page.data[c.missingState[0]], c.missingState[1]);
    page.onPullDownRefresh();
    assert.equal(sandbox.stops, 1);
    assert.equal(sandbox.requests.length, 0);
  });

  test(`${c.name}:有参时下拉照旧重读`, () => {
    const sandbox = setup();
    const page = loadPage(c.rel);
    page.onLoad(c.query);
    const first = sandbox.requests.length;
    assert.ok(first > 0);
    page.onPullDownRefresh();
    assert.ok(sandbox.requests.length > first, '下拉应再发一轮读取');
    assert.equal(sandbox.stops, 0, '有参时不提前收转圈,由读取回包收');
  });
}
