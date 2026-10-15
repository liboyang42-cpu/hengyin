const { beforeEach, afterEach, test, mock } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const PAGE_PATH = path.join(ROOT, 'pages/gerenziliao/gerenziliao.js');
const VIEW_PATH = path.join(ROOT, 'pages/gerenziliao/gerenziliao.wxml');
const nickname = require('../../utils/nickname-check.js');

let sandbox;

beforeEach(() => {
  mock.timers.enable({ apis: ['setTimeout'] });
  sandbox = { config: null, requests: [] };
  global.getApp = () => ({
    globalData: {},
    getUserID: () => 1,
    tips() {},
    sendRequest(options) { sandbox.requests.push(options); return { abort() {} }; },
  });
  global.Page = config => { sandbox.config = config; };
  global.getCurrentPages = () => [{}, {}];
  global.wx = { showToast() {}, hideToast() {}, navigateBack() {}, hideLoading() {}, showLoading() {} };
});

afterEach(() => { mock.timers.reset(); });

function loadPage() {
  delete require.cache[require.resolve(PAGE_PATH)];
  require(PAGE_PATH);
  const vm = Object.assign({}, sandbox.config);
  vm.data = JSON.parse(JSON.stringify(sandbox.config.data));
  vm.setData = function (patch, cb) {
    for (const key of Object.keys(patch)) {
      const parts = key.split('.');
      let target = this.data;
      while (parts.length > 1) target = target[parts.shift()];
      target[parts[0]] = patch[key];
    }
    if (cb) cb();
  };
  vm.selectComponent = () => null;
  return vm;
}

function req(url) { return sandbox.requests.filter(r => r.url === url); }

function boot() {
  const vm = loadPage();
  vm.onLoad({});
  req('/api/user/info')[0].success({ code: '200', data: { name: '老昵称', phone: '' } });
  vm.openPop4();
  return vm;
}

function type(vm, value) {
  vm.onInputChange({ currentTarget: { dataset: { field: 'name' } }, detail: { value } });
}

test('输入停顿后才查,且按表单字段发到 /api/user/nickname/check', () => {
  const vm = boot();
  type(vm, '新');
  assert.equal(vm.data.nicknameState, 'invalid', '1 个字本地直接判不合规');
  type(vm, '新名字');
  type(vm, '新名字2');
  assert.equal(vm.data.nicknameState, 'checking');
  assert.equal(req('/api/user/nickname/check').length, 0, '防抖期内不发请求');
  mock.timers.tick(400);
  const checks = req('/api/user/nickname/check');
  assert.equal(checks.length, 1);
  assert.deepEqual(checks[0].data, { nickname: '新名字2' });
  assert.equal(checks[0].header, undefined, '后端是 @RequestParam,不能发 JSON body');
  assert.equal(checks[0].autoErrorToast, false);
  checks[0].success({ code: 200, data: { nickname: '新名字2', available: true } });
  assert.equal(vm.data.nicknameState, 'available');
});

test('与当前昵称相同不发请求', () => {
  const vm = boot();
  type(vm, ' 老昵称 ');
  mock.timers.tick(1000);
  assert.equal(req('/api/user/nickname/check').length, 0);
  assert.equal(vm.data.nicknameState, '');
});

test('被占用 → taken,「完成」不收进草稿', () => {
  const vm = boot();
  type(vm, '撞名的');
  mock.timers.tick(400);
  req('/api/user/nickname/check')[0].success({ code: 200, data: { nickname: '撞名的', available: false, errorCode: 'NICKNAME_TAKEN' } });
  assert.equal(vm.data.nicknameState, 'taken');
  vm.confirm4();
  assert.equal(vm.data.popDavid4, true);
  assert.equal(vm.data.userInfo.name, '老昵称');
});

test('晚到的旧响应不覆盖新输入的结论', () => {
  const vm = boot();
  type(vm, '第一个');
  mock.timers.tick(400);
  const first = req('/api/user/nickname/check')[0];
  type(vm, '第二个');
  mock.timers.tick(400);
  const second = req('/api/user/nickname/check')[1];
  second.success({ code: 200, data: { available: true } });
  first.success({ code: 200, data: { available: false, errorCode: 'NICKNAME_TAKEN' } });
  assert.equal(vm.data.nicknameState, 'available');
});

test('查询失败可重试,且不拦「完成」(保存时服务端还会再判)', () => {
  const vm = boot();
  type(vm, '断网时');
  mock.timers.tick(400);
  req('/api/user/nickname/check')[0].fail();
  assert.equal(vm.data.nicknameState, 'error');
  vm.retryNicknameCheck();
  assert.equal(req('/api/user/nickname/check').length, 2);
  req('/api/user/nickname/check')[1].successStatusAbnormal({}, 502);
  assert.equal(vm.data.nicknameState, 'error');
  vm.confirm4();
  assert.equal(vm.data.popDavid4, false);
  assert.equal(vm.data.userInfo.name, '断网时');
});

test('「换一个」用服务端候选并沿用 cursor 翻页', () => {
  const vm = boot();
  type(vm, '撞名的');
  vm.suggestNickname();
  const s1 = req('/api/user/nickname/suggest')[0];
  assert.deepEqual(s1.data, { baseName: '撞名的' });
  s1.success({ code: 200, data: { nickname: '撞名的#0001', available: true, cursor: 1 } });
  assert.equal(vm.data.tempName, '撞名的#0001');
  assert.equal(vm.data.nicknameState, 'available');
  vm.suggestNickname();
  const s2 = req('/api/user/nickname/suggest')[1];
  assert.deepEqual(s2.data, { baseName: '撞名的#0001', cursor: 1 });
});

test('「换一个」失败给出可读原因并可再试', () => {
  const vm = boot();
  type(vm, '撞名的');
  vm.suggestNickname();
  req('/api/user/nickname/suggest')[0].success({ code: 400, msg: '昵称不符合规则或候选已耗尽', errorCode: 'NICKNAME_SUFFIX_EXHAUSTED' });
  assert.match(vm.data.nicknameSuggestError, /候选都被占用/);
  assert.equal(vm.data.nicknameSuggesting, false);
  vm.suggestNickname();
  req('/api/user/nickname/suggest')[1].fail();
  assert.match(vm.data.nicknameSuggestError, /再点一次/);
});

test('推荐返回前用户又改了输入:不覆盖用户的字', () => {
  const vm = boot();
  type(vm, '撞名的');
  vm.suggestNickname();
  type(vm, '我自己想的');
  req('/api/user/nickname/suggest')[0].success({ code: 200, data: { nickname: '撞名的#0001', available: true, cursor: 1 } });
  assert.equal(vm.data.tempName, '我自己想的');
});

test('保存被服务端以 NICKNAME_TAKEN 拒绝 → 重新打开昵称面板可恢复;保存接口语义不变', () => {
  const vm = boot();
  type(vm, '撞名的');
  vm.confirm4();
  vm.saveInfo();
  const update = req('/api/user/update')[0];
  assert.equal(JSON.parse(update.data).name, '撞名的');
  update.success({ code: 409, msg: '昵称已被占用，请选择建议昵称', errorCode: 'NICKNAME_TAKEN' });
  assert.equal(vm.data.saving, false);
  assert.equal(vm.data.popDavid4, true);
  assert.equal(vm.data.nicknameState, 'taken');
  assert.equal(vm.data.saveError, '昵称已被占用，请选择建议昵称');
});

test('本地规则与服务端一致:2–24 字 + 字符集', () => {
  assert.equal(nickname.localIssue(''), '请输入昵称');
  assert.match(nickname.localIssue('a'), /至少/);
  assert.match(nickname.localIssue('字'.repeat(25)), /最多/);
  assert.equal(nickname.localIssue('字'.repeat(24)), '');
  assert.equal(nickname.localIssue('城瘾_玩家#01 ·x'), '');
  assert.match(nickname.localIssue('坏<script>'), /只能用/);
  assert.match(nickname.localIssue('😀😀'), /只能用/, 'emoji 不属于 \\p{L}/\\p{N},服务端同样拒');
  assert.equal(nickname.codePointLength('𠮷野'), 2, '按码点计长,与服务端 codePointCount 一致');
});

test('readCheckResult 只认后端 available/errorCode,未知结构不当作可用', () => {
  assert.equal(nickname.readCheckResult({ code: 200, data: { available: true } }).state, 'available');
  assert.equal(nickname.readCheckResult({ code: 200, data: { available: false } }).state, 'error');
  assert.equal(nickname.readCheckResult({ code: 500 }).state, 'error');
  assert.equal(nickname.readCheckResult({ code: 200, data: { available: false, errorCode: 'NICKNAME_INVALID', nickname: 'a' } }).state, 'invalid');
});

test('视图:昵称输入挂校验反馈、换一个、可恢复错误', () => {
  const view = fs.readFileSync(VIEW_PATH, 'utf8');
  assert.match(view, /errorText="\{\{nicknameHint\}\}"/);
  assert.match(view, /bindtap="suggestNickname"/);
  assert.match(view, /bindtap="retryNicknameCheck"/);
  assert.match(view, /<cy-inline-error wx:if="\{\{nicknameSuggestError\}\}"[^>]*bind:action="suggestNickname"/);
});
