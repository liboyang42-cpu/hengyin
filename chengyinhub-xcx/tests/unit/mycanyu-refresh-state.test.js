const { beforeEach, test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const PAGE_PATH = path.resolve(__dirname, '../../subpackageMember/mycanyu/mycanyu.js');
const VIEW_PATH = path.resolve(__dirname, '../../subpackageMember/mycanyu/mycanyu.wxml');
let sandbox;

beforeEach(() => {
  sandbox = { config: null, requests: [], pullStops: 0, owner: 'member-a', navigations: [] };
  global.getApp = () => ({
    globalData: {},
    getUserID: () => sandbox.owner,
    getRequestErrorMessage: (_res, fallback) => fallback,
    sendRequest(options) {
      sandbox.requests.push(options);
      return { abort() {} };
    },
  });
  global.Page = config => { sandbox.config = config; };
  global.wx = {
    navigateTo(options) { sandbox.navigations.push(options.url); },
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

const joined = (id, verificationStatus = 0) => ({ id, ownerType: 1, registrationStatus: 2,
  paymentStatus: 2, verificationStatus, cmsTopic: { id: 77, name: '原路线' } });
const clickVoucher = (page, id) => page.viewVoucher({ currentTarget: { dataset: { id } } });

test('核销状态只接受明确0/1，不从已完成状态推断核销', () => {
  const page = loadPage();
  for (const [status, label] of [[1, '已核销'], ['1', '已核销'], [0, '未核销'], ['0', '未核销'],
    [true, '—'], [null, '—'], [undefined, '—'], [2, '—']]) {
    assert.equal(page.normalize({ ...joined(21, status), verificationStatus: status,
      firstSuccessfulScanAt: '2026-10-01 10:00:00' }).verificationText, label);
  }
  const view = fs.readFileSync(VIEW_PATH, 'utf8');
  assert.match(view, /item\.verificationText/);
  assert.match(view, /catchtap="viewVoucher"/);
});

test('凭证只能来自本人当前可见列表，非法ID和不存在的行不跳转', () => {
  const page = loadPage(); page.onLoad();
  sandbox.requests[0].success({ code: 200, data: [joined(21), joined('0022')] });
  for (const id of [true, false, [], [21], {}, null, undefined, 0, -1, 1.5,
    Number.MAX_SAFE_INTEGER + 1, '9223372036854775808', '1e1', Symbol('id'), 999]) clickVoucher(page, id);
  assert.equal(sandbox.navigations.length, 0);
  clickVoucher(page, '0022');
  assert.equal(sandbox.navigations[0], '/subpackageMember/signup/index?focusId=0022');
  page.setData({ list: [] }); clickVoucher(page, 21);
  assert.equal(sandbox.navigations.length, 1);
  assert.ok(sandbox.requests.every(r => r.url === '/api/registration/my-joined'));
});

test('切号、隐藏、卸载后的旧列表与旧回调不能打开凭证', () => {
  for (const transition of ['owner', 'hide', 'unload']) {
    const page = loadPage(); page.onLoad();
    const request = sandbox.requests.at(-1);
    request.success({ code: 200, data: [joined(21)] });
    if (transition === 'owner') sandbox.owner = 'member-b';
    if (transition === 'hide') page.onHide();
    if (transition === 'unload') page.onUnload();
    const before = JSON.stringify(page.data);
    request.success({ code: 200, data: [joined(99)] });
    request.fail({}); request.complete();
    assert.equal(JSON.stringify(page.data), before);
    clickVoucher(page, 21); assert.equal(sandbox.navigations.length, 0);
    sandbox.owner = 'member-a';
  }
});

test('新读取优先，切号返页清旧列表；隐藏返页读回本人新记录后才可跳转', () => {
  const page = loadPage(); page.onLoad(); const old = sandbox.requests[0];
  page.onRetry(); sandbox.requests[1].success({ code: 200, data: [joined(22)] });
  old.success({ code: 200, data: [joined(21)] });
  assert.deepEqual(page.data.list.map(x => x.id), [22]);
  page.onHide(); sandbox.owner = 'member-b'; page.onShow();
  assert.equal(page.data.list.length, 0); clickVoucher(page, 22);
  sandbox.requests.at(-1).success({ code: 200, data: [joined(23)] });
  clickVoucher(page, 23); assert.equal(sandbox.navigations[0], '/subpackageMember/signup/index?focusId=23');
});

test('凭证导航实参由真实票夹focusId消费并定位原报名票卡，两种ownerType均适用', () => {
  const vm = require('../helpers/ui-sandbox-vm.js');
  for (const ownerType of [1, 2]) {
    const page = loadPage(); page.onLoad();
    sandbox.requests.at(-1).success({ code: 200, data: [{ ...joined('0022'), ownerType }] });
    clickVoucher(page, '0022');
    const url = sandbox.navigations.at(-1);
    assert.doesNotMatch(url, /stype/);
    const options = Object.fromEntries(new URLSearchParams(url.split('?')[1]));
    let definition; const requests = [];
    const context = { getApp: () => ({ globalData: {}, getImgUrl: x => x, getPageSize: () => 10,
      getTotalPage: (total, size) => Math.ceil(total / size), getRequestErrorMessage: (_r, fallback) => fallback,
      sendRequest: r => requests.push(r) }), Page: value => { definition = value; },
      setTimeout: () => 1, wx: { showToast() {} }, console };
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.resolve(__dirname, '../../subpackageMember/signup/index.js'), 'utf8'), context);
    const wallet = { ...definition, data: JSON.parse(JSON.stringify(definition.data)) };
    wallet.setData = patch => Object.assign(wallet.data, patch);
    wallet.onLoad(options);
    for (const r of requests) {
      const rows = r.url === '/api/team/my' ? [] : r.data.owner_type === ownerType
        ? [{ ...joined(10), ownerType }, { ...joined('0022'), ownerType }] : [];
      r.success({ code: 200, data: r.url === '/api/team/my' ? rows : { rows, total: rows.length } });
      if (r.complete) r.complete();
    }
    assert.equal(wallet.data.ticketList[wallet.data.currentSwiperTicket].id, '0022');
    assert.equal(wallet._focusId, '');
    assert.ok(requests.every(r => r.url === '/api/team/my' || r.url === '/api/registration/list'));
  }
});


test('原报名Long字符串不经Number丢精度，畸形服务端行不产生凭证入口', () => {
  const page = loadPage(); page.onLoad();
  sandbox.requests[0].success({ code: 200, data: [joined('9223372036854775807'), joined(true), joined([21])] });
  clickVoucher(page, '9223372036854775807');
  assert.equal(sandbox.navigations[0], '/subpackageMember/signup/index?focusId=9223372036854775807');
  assert.equal(page.data.list[1].voucherAvailable, false);
  assert.equal(page.data.list[2].voucherAvailable, false);
  clickVoucher(page, 1); clickVoucher(page, 21);
  assert.equal(sandbox.navigations.length, 1);
});

test('导航回调前连点只发一次，失败可重试，成功返页重读后可再打开', () => {
  const page = loadPage(); page.onLoad();
  sandbox.requests[0].success({ code: 200, data: [joined(21)] });
  let pending;
  global.wx.navigateTo = options => { pending = options; sandbox.navigations.push(options.url); };
  clickVoucher(page, 21); clickVoucher(page, 21);
  assert.equal(sandbox.navigations.length, 1);
  pending.fail({}); clickVoucher(page, 21); assert.equal(sandbox.navigations.length, 2);
  const oldFail = pending.fail;
  page.onHide(); page.onShow(); clickVoucher(page, 21); assert.equal(sandbox.navigations.length, 2);
  sandbox.requests.at(-1).success({ code: 200, data: [joined(21)] });
  clickVoucher(page, 21); oldFail({}); clickVoucher(page, 21);
  assert.equal(sandbox.navigations.length, 3);
});

test('未知核销值不能被共享读模型强转成已完成；返页旧快照仅展示不能导航', () => {
  const page = loadPage(); page.onLoad();
  const row = { ...joined(21, true), cmsTopic: { startDate: '2099-01-01', endDate: '2099-01-02' } };
  sandbox.requests[0].success({ code: 200, data: [row] });
  assert.equal(page.data.list[0].stateText, '未开始');
  assert.equal(page.data.list[0].verificationText, '—');
  page.onHide(); page.onShow(); assert.equal(page.data.refreshing, true);
  clickVoucher(page, 21); assert.equal(sandbox.navigations.length, 0);
  sandbox.requests.at(-1).fail({}); clickVoucher(page, 21); assert.equal(sandbox.navigations.length, 0);
});
