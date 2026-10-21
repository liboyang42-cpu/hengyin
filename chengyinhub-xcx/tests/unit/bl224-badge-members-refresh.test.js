const { beforeEach, test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const PAGE_PATH = path.join(ROOT, 'pages/club/badges/index.js');

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

const MEMBERS_URL = '/api/club/members';

function openGrant(vm) {
  vm.openGrant({ currentTarget: { dataset: { code: 'CLUB9_2' } } });
}

function finishRefresh(vm, n) {
  req('/api/club/detail')[n].success({ code: 200, data: { id: 9, name: '夜行社', isOwner: true } });
  req('/api/club/badge/list')[n].success({ code: 200, data: BADGES });
}

test('BL-224:名单 ready 后下拉刷新,再点「颁发」重读成员并换成新名单', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  openGrant(vm);
  req(MEMBERS_URL)[0].success({ code: 200, data: [{ memberId: 7, nickname: '阿青' }, { memberId: 8, nickname: '已退团' }] });
  assert.equal(vm.data.membersState, 'ready');
  vm.closeMemberPicker();

  vm.onPullDownRefresh();
  finishRefresh(vm, 1);
  assert.equal(vm.data.membersState, 'idle');
  assert.deepEqual(vm.data.members, []);

  openGrant(vm);
  assert.equal(req(MEMBERS_URL).length, 2, '刷新后颁发要重读成员名单');
  req(MEMBERS_URL)[1].success({ code: 200, data: [{ memberId: 7, nickname: '阿青' }, { memberId: 9, nickname: '新成员' }] });
  assert.deepEqual(vm.data.members.map(m => m.memberId), [7, 9]);
});

test('BL-224:选人半屏打开时下拉刷新不清名单', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  openGrant(vm);
  req(MEMBERS_URL)[0].success({ code: 200, data: [{ memberId: 7, nickname: '阿青' }] });
  vm.onPullDownRefresh();
  finishRefresh(vm, 1);
  assert.equal(vm.data.membersState, 'ready');
  assert.deepEqual(vm.data.members.map(m => m.memberId), [7]);
  assert.equal(req(MEMBERS_URL).length, 1);
});

test('BL-224:两次 loadMembers 并发,先发的回包后到不覆盖新名单', () => {
  const vm = loadPage();
  bootAs(vm, { isOwner: true, badges: BADGES });
  openGrant(vm);
  req(MEMBERS_URL)[0].fail();
  assert.equal(vm.data.membersState, 'error');
  vm.loadMembers();
  vm.loadMembers();
  const [, older, newer] = req(MEMBERS_URL);
  newer.success({ code: 200, data: [{ memberId: 9, nickname: '新成员' }] });
  older.success({ code: 200, data: [{ memberId: 8, nickname: '旧名单' }] });
  assert.deepEqual(vm.data.members.map(m => m.memberId), [9]);
  older.fail();
  assert.equal(vm.data.membersState, 'ready');
});
