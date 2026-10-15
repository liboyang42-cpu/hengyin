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
  sandbox = { config: null, requests: [], navigations: [], toasts: [], dc: null, chooseImage: null };
  global.getApp = () => ({
    globalData: {},
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
  vm.chooseIcon();
  assert.equal(sandbox.chooseImage, null);
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

test('创建:表单校验先挡,通过后提交合同字段并回读列表核对 badgeCode', () => {
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
  assert.equal(lists.length, 2, '创建成功后回读列表');
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

test('创建:网络中断按「结果未知」处理并回读列表,不让用户盲目重提', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  vm.openCreate();
  vm.onFormInput({ currentTarget: { dataset: { field: 'name' } }, detail: { value: '元老' } });
  vm.submitCreate();
  req('/api/club/badge/create')[0].fail();
  assert.match(vm.data.createError, /结果未确认/);
  assert.equal(req('/api/club/badge/list').length, 2);
});

test('图标走既有上传通道;上传失败给可恢复提示', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  vm.chooseIcon();
  assert.equal(sandbox.chooseImage.count, 1);
  assert.equal(sandbox.chooseImage.options.cropScale, '1:1');
  sandbox.chooseImage.options.onUploadStart();
  assert.equal(vm.data.iconUploading, true);
  assert.equal(sandbox.chooseImage.options.onUploadDone({ ok: true, results: ['https://cdn/x.png'] }), true);
  assert.equal(vm.data.form.iconUrl, 'https://cdn/x.png');
  assert.equal(vm.data.iconUploading, false);

  vm.chooseIcon();
  sandbox.chooseImage.options.onUploadStart();
  sandbox.chooseImage.options.onUploadDone({ ok: false, results: [], failures: [{ msg: 'x' }] });
  assert.equal(vm.data.form.iconUrl, 'https://cdn/x.png', '失败不清掉已有图标');
  assert.match(vm.data.formErrors.icon, /没上传成功/);
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
  assert.match(sandbox.dc.calls[sandbox.dc.calls.length - 1][1], /不会重复颁发/);
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

test('validateCreateForm 按库表上限:名 120、说明 255、图标 255', () => {
  assert.equal(clubBadge.validateCreateForm({ name: 'a'.repeat(121) }, 9).errors.name, '勋章名最多 120 个字');
  assert.equal(clubBadge.validateCreateForm({ name: '名', desc: '字'.repeat(256) }, 9).errors.desc, '说明最多 255 个字');
  assert.ok(clubBadge.validateCreateForm({ name: '名', iconUrl: 'h'.repeat(256) }, 9).errors.icon);
  assert.equal(clubBadge.validateCreateForm({ name: '名' }, null).ok, false, '无 clubId 不出 payload');
  assert.deepEqual(clubBadge.validateCreateForm({ name: '名', desc: ' ', iconUrl: '' }, '9').payload, { clubId: 9, badgeName: '名' });
});

test('club.badge.grant 已登记为不可逆并写明不可撤销', () => {
  const action = getDangerAction('club.badge.grant', { name: '开荒元老', member: '阿青' });
  assert.ok(action);
  assert.equal(action.irreversible, true);
  assert.equal(action.title, '把「开荒元老」颁给 阿青?');
  assert.ok(action.consequences.some(c => c.text.includes('此操作不可撤销')));
});

test('俱乐部详情:概览只读入口给成员/主理人,管理入口只给主理人', () => {
  const view = fs.readFileSync(path.join(ROOT, 'pages/club/detail/index.wxml'), 'utf8');
  assert.match(view, /class="ov-badge-entry" wx:if="\{\{club\.isOwner \|\| club\.isJoined\}\}" catchtap="goClubBadges"/);
  assert.match(view, /class="cset-row" wx:if="\{\{club\.isOwner\}\}" catchtap="goClubBadges"/);
  const appJson = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'));
  const club = appJson.subPackages.find(p => p.root === 'pages/club');
  assert.ok(club.pages.includes('badges/index'));
});
