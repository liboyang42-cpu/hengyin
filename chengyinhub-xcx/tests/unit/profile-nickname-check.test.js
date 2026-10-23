const { beforeEach, afterEach, test, mock } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const PAGE_PATH = path.join(ROOT, 'pages/gerenziliao/gerenziliao.js');
const VIEW_PATH = path.join(ROOT, 'pages/gerenziliao/gerenziliao.wxml');
const nickname = require('../../pages/gerenziliao/nickname-check.js');

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

function takeName(vm, name) {
  type(vm, name);
  mock.timers.tick(400);
  const checks = req('/api/user/nickname/check');
  checks[checks.length - 1].success({ code: 200, data: { nickname: name, available: false, errorCode: 'NICKNAME_TAKEN' } });
}

test('被占用时自动列出推荐(按 cursor 连取 3 个),不自动填入,点选才填', () => {
  const vm = boot();
  takeName(vm, '撞名的');
  const s = req('/api/user/nickname/suggest');
  assert.equal(s.length, 1);
  assert.deepEqual(s[0].data, { baseName: '撞名的' });
  assert.equal(s[0].header, undefined, '后端是 @RequestParam');
  s[0].success({ code: 200, data: { nickname: '撞名的#0001', available: true, cursor: 1 } });
  assert.deepEqual(req('/api/user/nickname/suggest')[1].data, { baseName: '撞名的', cursor: 1 });
  req('/api/user/nickname/suggest')[1].success({ code: 200, data: { nickname: '撞名的#0003', available: true, cursor: 3 } });
  req('/api/user/nickname/suggest')[2].success({ code: 200, data: { nickname: '撞名的#0004', available: true, cursor: 4 } });
  assert.deepEqual(vm.data.nicknameSuggestions, ['撞名的#0001', '撞名的#0003', '撞名的#0004']);
  assert.equal(vm.data.tempName, '撞名的', '推荐不得自动填入');
  assert.equal(vm.data.nicknameState, 'taken');

  vm.pickNicknameSuggestion({ currentTarget: { dataset: { name: '撞名的#0003' } } });
  assert.equal(vm.data.tempName, '撞名的#0003');
  assert.equal(vm.data.nicknameState, 'available');
  assert.deepEqual(vm.data.nicknameSuggestions, []);
  vm.pickNicknameSuggestion({ currentTarget: { dataset: { name: '不在列表里' } } });
  assert.equal(vm.data.tempName, '撞名的#0003');
});

test('「换一批」从上次 cursor 往后取', () => {
  const vm = boot();
  takeName(vm, '撞名的');
  req('/api/user/nickname/suggest')[0].success({ code: 200, data: { nickname: '撞名的#0001', available: true, cursor: 1 } });
  req('/api/user/nickname/suggest')[1].success({ code: 200, data: { nickname: '撞名的#0002', available: true, cursor: 2 } });
  req('/api/user/nickname/suggest')[2].success({ code: 200, data: { nickname: '撞名的#0003', available: true, cursor: 3 } });
  vm.moreNicknameSuggestions();
  assert.deepEqual(req('/api/user/nickname/suggest')[3].data, { baseName: '撞名的', cursor: 3 });
});

test('推荐失败给出可读原因并可再试;部分成功保留已拿到的', () => {
  const vm = boot();
  takeName(vm, '撞名的');
  req('/api/user/nickname/suggest')[0].success({ code: 400, msg: '昵称不符合规则或候选已耗尽', errorCode: 'NICKNAME_SUFFIX_EXHAUSTED' });
  assert.match(vm.data.nicknameSuggestError, /候选都被占用/);
  assert.equal(vm.data.nicknameSuggesting, false);
  vm.moreNicknameSuggestions();
  req('/api/user/nickname/suggest')[1].success({ code: 200, data: { nickname: '撞名的#0001', available: true, cursor: 1 } });
  req('/api/user/nickname/suggest')[2].fail();
  assert.deepEqual(vm.data.nicknameSuggestions, ['撞名的#0001']);
  assert.equal(vm.data.nicknameSuggestError, '');
});

test('推荐在途时用户改了输入:旧推荐作废,不覆盖也不残留', () => {
  const vm = boot();
  takeName(vm, '撞名的');
  type(vm, '我自己想的');
  req('/api/user/nickname/suggest')[0].success({ code: 200, data: { nickname: '撞名的#0001', available: true, cursor: 1 } });
  assert.equal(vm.data.tempName, '我自己想的');
  assert.deepEqual(vm.data.nicknameSuggestions, []);
  assert.equal(req('/api/user/nickname/suggest').length, 1, '作废后不再续取');
});

for (const outcome of ['success', 'fail']) {
  test(`BL-221 旧一轮推荐作废(${outcome})时不清掉新一轮的「推荐中」`, () => {
    const vm = boot();
    takeName(vm, '撞名A');
    assert.equal(req('/api/user/nickname/suggest').length, 1);
    takeName(vm, '撞名B');
    const s = req('/api/user/nickname/suggest');
    assert.equal(s.length, 2, '第二轮已发起');
    assert.deepEqual(s[1].data, { baseName: '撞名B' });
    assert.equal(vm.data.nicknameSuggesting, true);
    if (outcome === 'success') s[0].success({ code: 200, data: { nickname: '撞名A#0001', available: true, cursor: 1 } });
    else s[0].fail();
    assert.equal(vm.data.nicknameSuggesting, true, '第二轮仍在途');
    assert.deepEqual(vm.data.nicknameSuggestions, []);
    assert.equal(req('/api/user/nickname/suggest').length, 2, '作废的一轮不续取');
    vm.moreNicknameSuggestions();
    assert.equal(req('/api/user/nickname/suggest').length, 2, '在途时「换一批」不并发新一轮');
    s[1].success({ code: 200, data: { nickname: '撞名B#0001', available: true, cursor: 1 } });
    req('/api/user/nickname/suggest')[2].success({ code: 200, data: { nickname: '撞名B#0002', available: true, cursor: 2 } });
    req('/api/user/nickname/suggest')[3].success({ code: 200, data: { nickname: '撞名B#0003', available: true, cursor: 3 } });
    assert.deepEqual(vm.data.nicknameSuggestions, ['撞名B#0001', '撞名B#0002', '撞名B#0003']);
    assert.equal(vm.data.nicknameSuggesting, false);
  });
}

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
  assert.equal(req('/api/user/nickname/suggest').length, 1, '保存被拒也列推荐');
});

test('本地规则与服务端一致:2–24 字 + 字符集', () => {
  assert.equal(nickname.localIssue(''), '请输入昵称');
  assert.match(nickname.localIssue('a'), /至少/);
  assert.match(nickname.localIssue('字'.repeat(25)), /最多/);
  assert.equal(nickname.localIssue('字'.repeat(24)), '');
  assert.equal(nickname.localIssue('城瘾_玩家#01 ·x'), '');
  assert.match(nickname.localIssue('坏<script>'), /只能用/);
  assert.match(nickname.localIssue('😀😀'), /只能用/, 'emoji 不属于 \\p{L}/\\p{N},服务端同样拒');
  assert.equal(nickname.codePointLength('𠮷野'), 2, '按码点计长，与服务端 codePointCount 一致');
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
  assert.match(view, /bindtap="pickNicknameSuggestion"/);
  assert.match(view, /bindtap="moreNicknameSuggestions"/);
  assert.match(view, /bindtap="retryNicknameCheck"/);
  assert.match(view, /<cy-inline-error wx:if="\{\{nicknameSuggestError\}\}"[^>]*bind:action="moreNicknameSuggestions"/);
  // <text> 不支持 hover-class:可点的昵称链接/推荐胶囊必须是 <view>,否则没有按压反馈
  assert.doesNotMatch(view, /<text[^>]*hover-class/);
});
