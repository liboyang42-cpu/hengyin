const { beforeEach, test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const PAGE_PATH = path.join(ROOT, 'pages/club/badges/index.js');
const VIEW_PATH = path.join(ROOT, 'pages/club/badges/index.wxml');
const clubBadge = require('../../pages/club/utils/club-badge.js');
const { getDangerAction } = require('../../utils/danger-actions.js');

let sandbox;

beforeEach(() => {
  sandbox = { config: null, requests: [], navigations: [], toasts: [], dc: null, chooseImage: null, userId: 5 };
  global.getApp = () => ({
    globalData: {},
    getUserID: () => sandbox.userId,
    sendRequest(options) { sandbox.requests.push(options); return { abort() {} }; },
    chooseImage(callback, count, options) { sandbox.chooseImage = { callback, count, options }; },
  });
  global.Page = config => { sandbox.config = config; };
  global.getCurrentPages = () => [{}, {}];
  global.wx = {
    stopPullDownRefresh() {},
    navigateTo(opts) { sandbox.navigations.push(opts.url); },
    navigateBack() {},
    showToast(opts) { sandbox.toasts.push(opts); },
  };
});

function loadPage() {
  delete require.cache[require.resolve(PAGE_PATH)];
  require(PAGE_PATH);
  const vm = Object.assign({}, sandbox.config);
  vm.data = JSON.parse(JSON.stringify(sandbox.config.data));
  vm.setData = function (patch, cb) {
    for (const key of Object.keys(patch)) {
      const parts = key.split('.');
      let target = this.data;
      while (parts.length > 1) {
        const p = parts.shift();
        target[p] = target[p] && typeof target[p] === 'object' ? target[p] : {};
        target = target[p];
      }
      target[parts[0]] = patch[key];
    }
    if (cb) cb();
  };
  sandbox.dc = {
    calls: [],
    open(key, params) { this.calls.push(['open', key, params]); return true; },
    busyOn() { this.calls.push(['busyOn']); },
    done(text) { this.calls.push(['done', text]); },
    failed(text) { this.calls.push(['failed', text]); },
    close() { this.calls.push(['close']); },
  };
  vm.selectComponent = sel => (sel === '#dc' ? sandbox.dc : null);
  return vm;
}

function req(url) {
  return sandbox.requests.filter(r => r.url === url);
}

function bootAs(vm, { isOwner, badges }) {
  vm.onLoad({ clubId: '9' });
  req('/api/club/detail')[0].success({ code: 200, data: { id: 9, name: '夜行社', isOwner } });
  req('/api/club/badge/list')[0].success({ code: 200, data: badges });
}

const BADGES = [
  { id: 2, badgeCode: 'CLUB9_2', badgeName: '开荒元老', badgeDesc: '第一批成员', iconUrl: 'https://x/a.png' },
  { id: 1, badgeCode: '', badgeName: '缺 code 的脏行' },
];

test('缺 clubId 时进入 missing-param,不发任何请求', () => {
  const vm = loadPage();
  vm.onLoad({});
  assert.equal(vm.data.state, 'missing-param');
  assert.equal(sandbox.requests.length, 0);
});

test('列表与身份走既有接口 + JSON body;缺 badgeCode 的行被丢弃', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: false, badges: BADGES });
  const list = req('/api/club/badge/list')[0];
  assert.equal(list.method, 'POST');
  assert.deepEqual(JSON.parse(list.data), { id: 9 });
  assert.equal(list.header['Content-Type'], 'application/json');
  assert.equal(vm.data.state, 'ready');
  assert.equal(vm.data.badges.length, 1);
  assert.equal(vm.data.badges[0].badgeCode, 'CLUB9_2');
  assert.equal(vm.data.isOwner, false);
});

test('空列表 → empty;首载失败 → error 可重试', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: [] });
  assert.equal(vm.data.state, 'empty');

  const vm2 = loadPage();
  sandbox.requests = [];
  vm2.onLoad({ clubId: 9 });
  req('/api/club/badge/list')[0].fail();
  assert.equal(vm2.data.state, 'error');
  assert.equal(vm2.data.errorKind, 'network');
  vm2.retryLoad();
  assert.equal(req('/api/club/badge/list').length, 2);
});

test('非主理人:所有管理动作都是 no-op,不打写接口', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: false, badges: BADGES });
  vm.openCreate();
  assert.equal(vm.data.createVisible, false);
  vm.setData({ form: { name: '偷建', desc: '', iconUrl: '' } });
  vm.submitCreate();
  vm.openGrant({ currentTarget: { dataset: { code: 'CLUB9_2' } } });
  assert.equal(vm.data.memberPickerVisible, false);
  assert.equal(req('/api/club/badge/create').length, 0);
  assert.equal(req('/api/club/badge/grant').length, 0);
  assert.equal(req('/api/club/members').length, 0);
});

test('身份读取失败时按非主理人处理(fail-closed)', () => {
  const vm = loadPage();
  vm.onLoad({ clubId: 9 });
  req('/api/club/detail')[0].fail();
  req('/api/club/badge/list')[0].success({ code: 200, data: BADGES });
  assert.equal(vm.data.isOwner, false);
});

test('视图:管理控件全部挂在 isOwner 条件下', () => {
  const view = fs.readFileSync(VIEW_PATH, 'utf8');
  assert.match(view, /wx:if="\{\{isOwner\}\}" class="section">\s*<cy-btn[^>]*bindtap="openCreate"/);
  assert.match(view, /<view wx:if="\{\{isOwner\}\}" class="badge-grant[^"]*" bindtap="openGrant"/);
  assert.match(view, /<cy-state-shell wx:elif="\{\{state === 'error'\}\}"/);
  assert.match(view, /<cy-danger-confirm id="dc" bind:confirm="onConfirmGrant"/);
});

test('创建:表单校验先挡，通过后提交合同字段并刷新列表确认 badgeCode', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  vm.openCreate();
  vm.submitCreate();
  assert.equal(vm.data.formErrors.name, '请填写勋章名');
  assert.equal(req('/api/club/badge/create').length, 0);

  vm.onFormInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '  夜跑之星 ' } });
  vm.onFormInput({ currentTarget: { dataset: { field: 'desc' } }, detail: { value: '连续三次夜跑' } });
  vm.submitCreate();
  const create = req('/api/club/badge/create')[0];
  assert.deepEqual(JSON.parse(create.data), { clubId: 9, badgeName: '夜跑之星', badgeDesc: '连续三次夜跑' });
  assert.equal(vm.data.creating, true);
  vm.submitCreate();
  assert.equal(req('/api/club/badge/create').length, 1, '提交中不得重复提交');

  create.success({ code: 200, msg: '已创建', data: { id: 3, badgeCode: 'CLUB9_3' } });
  assert.equal(vm.data.createVisible, false);
  const lists = req('/api/club/badge/list');
  assert.equal(lists.length, 2, '创建成功后刷新列表');
  lists[1].success({ code: 200, data: BADGES });
  assert.match(vm.data.refreshError, /暂时没看到/);
});

test('创建:服务端拒绝(能力位/非主理人)原话展示且保留表单', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  vm.openCreate();
  vm.onFormInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '元老' } });
  vm.submitCreate();
  req('/api/club/badge/create')[0].success({ code: 500, msg: '当前身份未解锁勋章设计' });
  assert.equal(vm.data.createVisible, true);
  assert.equal(vm.data.createError, '当前身份未解锁勋章设计');
  assert.equal(vm.data.form.name, '元老');
});

function submitName(vm, name) {
  vm.openCreate();
  vm.onFormInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: name } });
  vm.submitCreate();
}

test('创建结果未知(断网/5xx):锁住提交，未刷新确认前 submitCreate 被拒', () => {
  for (const settle of [r => r.fail(), r => r.successStatusAbnormal({}, 502)]) {
    sandbox.requests = [];
    const vm = loadPage();
    bootAs(vm, { isOwner: true, badges: BADGES });
    submitName(vm, '元老');
    settle(req('/api/club/badge/create')[0]);
    assert.equal(vm.data.createUnknown, true);
    assert.equal(vm.data.creating, false);
    assert.match(vm.data.createError, /结果未确认/);
    assert.equal(req('/api/club/badge/list').length, 2, '未知后自动刷新');
    vm.submitCreate();
    vm.submitCreate();
    assert.equal(req('/api/club/badge/create').length, 1, '未知期间不得再次提交');
  }
});

test('结果未知 → 刷新出现提交前没有的同名勋章:确认已建成并收起表单', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  submitName(vm, '元老');
  req('/api/club/badge/create')[0].fail();
  req('/api/club/badge/list')[1].success({ code: 200, data: [
    { id: 9, badgeCode: 'CLUB9_9', badgeName: '元老' }, ...BADGES,
  ] });
  assert.equal(vm.data.createUnknown, false);
  assert.equal(vm.data.createVisible, false);
  assert.equal(vm.data.form.name, '');
});

test('结果未知 → 刷新没看到:保持锁定；用户明确「我已确认」才解锁重提', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  submitName(vm, '元老');
  req('/api/club/badge/create')[0].fail();
  req('/api/club/badge/list')[1].success({ code: 200, data: BADGES });
  assert.equal(vm.data.createUnknown, true);
  assert.match(vm.data.createError, /我已确认/);
  vm.submitCreate();
  assert.equal(req('/api/club/badge/create').length, 1);
  vm.confirmCreateChecked();
  assert.equal(vm.data.createUnknown, false);
  vm.submitCreate();
  assert.equal(req('/api/club/badge/create').length, 2);
  const view = fs.readFileSync(VIEW_PATH, 'utf8');
  assert.match(view, /disabled="\{\{createUnknown\}\}" bindtap="submitCreate"/);
  assert.match(view, /action="我已确认" bind:action="confirmCreateChecked"/);
});

test('HTTP 4xx 是明确失败:不锁定，可直接改后重提', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  submitName(vm, '元老');
  req('/api/club/badge/create')[0].successStatusAbnormal({}, 400);
  assert.equal(vm.data.createUnknown, false);
  vm.submitCreate();
  assert.equal(req('/api/club/badge/create').length, 2);
});

test('换号:迟到响应丢弃，旧身份/名单/弹层/草稿全部清空并按新账号重读', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  vm.openGrant({ currentTarget: { dataset: { code: 'CLUB9_2' } } });
  const membersReq = req('/api/club/members')[0];
  vm.openCreate();
  vm.onFormInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: 'A 的草稿' } });

  sandbox.userId = 6; // A 登出,B 登录
  membersReq.success({ code: 200, data: [{ memberId: 7, nickname: 'A 团成员' }] });
  assert.deepEqual(vm.data.members, [], 'A 的成员名单不得落到 B');
  assert.equal(vm.data.isOwner, false);
  assert.equal(vm.data.createVisible, false);
  assert.equal(vm.data.memberPickerVisible, false);
  assert.equal(vm.data.form.name, '');
  assert.equal(vm.data.badges.length, 0);
  assert.equal(req('/api/club/detail').length, 2, '按 B 重读身份');

  // B 的刷新才生效
  req('/api/club/detail')[1].success({ code: 200, data: { id: 9, isOwner: false } });
  req('/api/club/badge/list')[1].success({ code: 200, data: BADGES });
  assert.equal(vm.data.isOwner, false);
  assert.equal(vm.data.state, 'ready');
});

test('换号后回到页面(onShow)也会重置；同号 onShow 不打扰', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  vm.onShow();
  assert.equal(req('/api/club/detail').length, 1);
  sandbox.userId = 6;
  vm.onShow();
  assert.equal(vm.data.isOwner, false);
  assert.equal(req('/api/club/detail').length, 2);
});

test('换号前发出的创建/颁发，迟到回执不写进新账号页面', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  submitName(vm, '元老');
  sandbox.userId = 6;
  req('/api/club/badge/create')[0].fail();
  assert.equal(vm.data.createUnknown, false, 'A 的未知态不得带给 B');
  assert.equal(vm.data.createError, '');
});

test('刷新后失去主理人身份:收起创建/成员弹层并清掉待执行颁发', () => {
  for (const settle of [
    r => r.success({ code: 200, data: { id: 9, isOwner: false } }),
    r => r.fail(),
    r => r.successStatusAbnormal({}, 502),
  ]) {
    sandbox.requests = [];
    const vm = loadPage();
    bootAs(vm, { isOwner: true, badges: BADGES });
    vm.openGrant({ currentTarget: { dataset: { code: 'CLUB9_2' } } });
    req('/api/club/members')[0].success({ code: 200, data: [{ memberId: 7, nickname: '阿青' }] });
    vm.pickMember({ currentTarget: { dataset: { memberId: 7 } } });
    vm.openCreate();
    vm.retryLoad();
    settle(req('/api/club/detail')[1]);
    assert.equal(vm.data.isOwner, false);
    assert.equal(vm.data.createVisible, false);
    assert.equal(vm.data.memberPickerVisible, false);
    assert.equal(vm.data.grantBadge, null);
    assert.deepEqual(sandbox.dc.calls[sandbox.dc.calls.length - 1], ['close']);
    vm.onConfirmGrant({ detail: { key: 'club.badge.grant' } });
    assert.equal(req('/api/club/badge/grant').length, 0, '失权后不得再发颁发');
  }
});

test('图标不开放上传:页面不调上传通道，创建 payload 不带 iconUrl(2026-10-15 用户决定)', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  assert.equal(typeof vm.chooseIcon, 'undefined');
  vm.openCreate();
  vm.onFormInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '元老' } });
  vm.submitCreate();
  const body = JSON.parse(req('/api/club/badge/create')[0].data);
  assert.equal(Object.prototype.hasOwnProperty.call(body, 'iconUrl'), false);
  assert.equal(sandbox.chooseImage, null);
  const view = fs.readFileSync(VIEW_PATH, 'utf8');
  assert.doesNotMatch(view, /chooseIcon|iconUploading/);
  // 渲染与平台勋章墙一致:有 iconUrl 用 aspectFit 图片，没有就星标
  assert.match(view, /<image wx:if="\{\{item\.iconUrl\}\}"[^>]*mode="aspectFit"/);
  assert.match(view, /<cy-icon wx:else name="star"/);
});

test('颁发:成员取自 /api/club/members → 危险确认 → 发 grant → 回执', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  vm.openGrant({ currentTarget: { dataset: { code: 'CLUB9_2' } } });
  assert.equal(vm.data.memberPickerVisible, true);
  const members = req('/api/club/members')[0];
  assert.deepEqual(JSON.parse(members.data), { clubId: 9 });
  members.success({ code: 200, data: [
    { memberId: 5, nickname: '主理人', isOwner: true },
    { memberId: 7, nickname: '阿青' },
    { memberId: 7, nickname: '重复行' },
  ] });
  assert.equal(vm.data.members.length, 2);

  vm.pickMember({ currentTarget: { dataset: { memberId: 7 } } });
  assert.deepEqual(sandbox.dc.calls[0], ['open', 'club.badge.grant', { name: '开荒元老', member: '阿青' }]);
  assert.equal(req('/api/club/badge/grant').length, 0, '确认前不得发请求');

  vm.onConfirmGrant({ detail: { key: 'club.badge.grant' } });
  const grant = req('/api/club/badge/grant')[0];
  assert.deepEqual(JSON.parse(grant.data), { clubId: 9, memberId: 7, badgeCode: 'CLUB9_2' });
  grant.success({ code: 200, msg: '已颁发过' });
  const last = sandbox.dc.calls[sandbox.dc.calls.length - 1];
  assert.equal(last[0], 'done');
  assert.match(last[1], /没有重复颁发/);
});

test('颁发:服务端拒绝与网络失败都留在确认框原地重试', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  vm.openGrant({ currentTarget: { dataset: { code: 'CLUB9_2' } } });
  req('/api/club/members')[0].success({ code: 200, data: [{ memberId: 7, nickname: '阿青' }] });
  vm.pickMember({ currentTarget: { dataset: { memberId: 7 } } });
  vm.onConfirmGrant({ detail: { key: 'club.badge.grant' } });
  req('/api/club/badge/grant')[0].success({ code: 500, msg: '对方不是本俱乐部成员' });
  assert.deepEqual(sandbox.dc.calls[sandbox.dc.calls.length - 1], ['failed', '对方不是本俱乐部成员']);
  assert.equal(vm.data.granting, false);

  vm.onConfirmGrant({ detail: { key: 'club.badge.grant' } });
  req('/api/club/badge/grant')[1].fail();
  assert.match(sandbox.dc.calls[sandbox.dc.calls.length - 1][1], /重试时平台会提示是否已颁发/);
  assert.doesNotMatch(fs.readFileSync(PAGE_PATH, 'utf8'), /不会重复颁发/);
});

test('成员名单失败 → 可重试', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  vm.openGrant({ currentTarget: { dataset: { code: 'CLUB9_2' } } });
  req('/api/club/members')[0].success({ code: 500, msg: '仅俱乐部成员可查看成员名单' });
  assert.equal(vm.data.membersState, 'error');
  vm.loadMembers();
  assert.equal(req('/api/club/members').length, 2);
});

test('validateCreateForm 按库表上限:名 120、说明 255;即便传入 iconUrl 也不进 payload', () => {
  assert.equal(clubBadge.validateCreateForm({ name: 'a'.repeat(121) }, 9).errors.name, '勋章名最多 120 个字');
  assert.equal(clubBadge.validateCreateForm({ name: '名', desc: '字'.repeat(256) }, 9).errors.desc, '说明最多 255 个字');
  assert.deepEqual(clubBadge.validateCreateForm({ name: '名', iconUrl: 'https://x/a.png' }, 9).payload, { clubId: 9, badgeName: '名' });
  assert.equal(clubBadge.validateCreateForm({ name: '名' }, null).ok, false, '无 clubId 不出 payload');
  assert.deepEqual(clubBadge.validateCreateForm({ name: '名', desc: ' ', iconUrl: '' }, '9').payload, { clubId: 9, badgeName: '名' });
});

test('club.badge.grant 已登记为不可逆并写明不可撤销', () => {
  const action = getDangerAction('club.badge.grant', { name: '开荒元老', member: '阿青' });
  assert.ok(action);
  assert.equal(action.irreversible, true);
  assert.equal(action.title, '把「开荒元老」颁给 阿青?');
  assert.ok(action.consequences.some(c => c.text.includes('此操作不可撤销')));
  // 不承诺未验证的展示口径(团名来源)与并发下的去重
  assert.ok(action.consequences.every(c => !/来源标注|不会多记/.test(c.text)));
});

test('俱乐部详情:概览只读入口给成员/主理人，管理入口只给主理人', () => {
  const view = fs.readFileSync(path.join(ROOT, 'pages/club/detail/index.wxml'), 'utf8');
  assert.match(view, /class="ov-badge-entry" wx:if="\{\{club\.isOwner \|\| club\.isJoined\}\}" catchtap="goClubBadges"/);
  assert.match(view, /class="cset-row" wx:if="\{\{club\.isOwner\}\}" catchtap="goClubBadges"/);
  const appJson = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'));
  const club = appJson.subPackages.find(p => p.root === 'pages/club');
  assert.ok(club.pages.includes('badges/index'));
});
