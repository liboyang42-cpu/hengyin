const { beforeEach, test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const PAGE_PATH = path.resolve(__dirname, '../../subpackageMember/mycanyu/mycanyu.js');
const VIEW_PATH = path.resolve(__dirname, '../../subpackageMember/mycanyu/mycanyu.wxml');
let sandbox;

beforeEach(() => {
  sandbox = { config: null, requests: [], pullStops: 0 };
  global.getApp = () => ({
    globalData: {},
    getRequestErrorMessage: (_res, fallback) => fallback,
    sendRequest(options) {
      sandbox.requests.push(options);
      return { abort() {} };
    },
  });
  global.Page = config => { sandbox.config = config; };
  global.wx = {
    stopPullDownRefresh() { sandbox.pullStops += 1; },
  };
});

test('我的参与刷新失败不回到首屏骨架，并保留筛选与旧列表直到 complete', () => {
  const vm = loadPage();
  vm.onLoad();
  sandbox.requests[0].success({
    code: '200',
    data: [{
      id: 21,
      ownerType: 1,
      purchaseKind: 3,
      registrationStatus: 2,
      cmsTopic: { name: '原参与主题', startDate: '2099-01-01', endDate: '2099-01-02' },
    }],
  });
  sandbox.requests[0].complete();
  vm.switchTab({ detail: { key: 'not_started' }, currentTarget: { dataset: {} } });
  const previous = vm.data.list;

  vm.onPullDownRefresh();

  assert.equal(vm.data.firstLoading, false);
  assert.equal(vm.data.refreshing, true);
  assert.equal(vm.data.activeTab, 'not_started');
  assert.deepEqual(vm.data.list, previous);
  assert.equal(sandbox.pullStops, 0);

  sandbox.requests[1].successStatusAbnormal({ statusCode: 502 });

  assert.equal(vm.data.firstLoading, false);
  assert.equal(vm.data.refreshing, false);
  assert.equal(vm.data.activeTab, 'not_started');
  assert.deepEqual(vm.data.list, previous);
  assert.equal(vm.data.errorMsg, '');
  assert.equal(sandbox.pullStops, 0);

  sandbox.requests[1].complete();

  assert.equal(sandbox.pullStops, 1);
  const view = fs.readFileSync(VIEW_PATH, 'utf8');
  assert.doesNotMatch(view, /<cy-inline-error[^>]*staleError/);
  assert.match(view, /<cy-tabs[^>]+active="\{\{activeTab\}\}"/);
});

function loadPage() {
  delete require.cache[require.resolve(PAGE_PATH)];
  require(PAGE_PATH);
  const vm = Object.assign({}, sandbox.config);
  vm.data = JSON.parse(JSON.stringify(sandbox.config.data));
  vm.setData = function (patch, callback) {
    Object.assign(this.data, patch);
    if (callback) callback();
  };
  return vm;
}


test('ORDER-01:我的参与已完成筛选按首次合法扫码读回，不等待通关或核销', () => {
  const page = loadPage();
  const row = { id: 21, ownerType: 1, registrationStatus: 2, paymentStatus: 2, verificationStatus: 0,
    firstSuccessfulScanAt: '2026-09-30 11:00:00',
    cmsTopic: { name: '城市路线', startDate: '2099-09-30', endDate: '2099-10-01' } };
  page.onLoad();
  sandbox.requests[0].success({ code: 200, data: [row, { ...row, id: 22, firstSuccessfulScanAt: null }] });
  page.switchTab({ detail: { key: 'completed' }, currentTarget: { dataset: {} } });
  assert.equal(page.data.list.length, 1);
  assert.equal(page.data.list[0].id, row.id);
  assert.equal(page.data.list[0].stateText, '已完成');
  assert.equal(page.data.list[0].verificationStatus, 0);
});
