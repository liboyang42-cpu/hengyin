// BL-149：pages/gerenziliao/nickname-check.js(原 utils/,只给个人资料页用,下沉到分包减主包)的 normalize / codePointLength / localIssue 单测补齐
// 依赖 #23（CL3），须在其后合并；不改被测代码。
// 口径对照 NicknameSuggestionServiceImpl：normalize = String.trim()；
//   合规 = codePointCount 在 [2, 24] 且匹配 [\p{L}\p{N}_#\-.· ]+
const { test } = require('node:test');
const assert = require('node:assert/strict');
const nickname = require('../../pages/gerenziliao/nickname-check.js');

test('常量与服务端一致：MIN_LENGTH=2，MAX_LENGTH=24', () => {
  assert.equal(nickname.MIN_LENGTH, 2);
  assert.equal(nickname.MAX_LENGTH, 24);
});

test('normalize: 去首尾半角空白（空格/制表/换行），保留中间空格', () => {
  assert.equal(nickname.normalize('  城瘾 玩家  '), '城瘾 玩家');
  assert.equal(nickname.normalize('\t城瘾\n'), '城瘾');
  assert.equal(nickname.normalize('   '), '');
});

test('normalize: 非字符串一律归空串', () => {
  for (const v of [null, undefined, 0, 12, true, {}, []]) {
    assert.equal(nickname.normalize(v), '');
  }
});

test('normalize: 全角空格 U+3000 会被 JS trim 去掉（Java String.trim 不去，见下一条口径用例）', () => {
  assert.equal(nickname.normalize('　城瘾　'), '城瘾');
});

test('口径差异：首尾全角空格本地判合规，服务端 trim 后保留 U+3000 判 INVALID —— 本地只是提示，最终以服务端为准', () => {
  // 本地返回 '' 表示「需问服务端」，不会误拦；服务端会回 NICKNAME_INVALID，readCheckResult 折成 invalid
  assert.equal(nickname.localIssue('　城瘾'), '');
  const r = nickname.readCheckResult({ code: 200, data: { nickname: '　城瘾', available: false, errorCode: 'NICKNAME_INVALID' } });
  assert.equal(r.state, 'invalid');
  assert.equal(r.text, '昵称不符合规则');
  // 中间的全角空格本地即拦（字符集只含半角空格）
  assert.match(nickname.localIssue('城　瘾'), /只能用/);
});

test('codePointLength: 按码点计数（代理对算 1），非字符串安全', () => {
  assert.equal(nickname.codePointLength(''), 0);
  assert.equal(nickname.codePointLength(null), 0);
  assert.equal(nickname.codePointLength(undefined), 0);
  assert.equal(nickname.codePointLength('ab'), 2);
  assert.equal(nickname.codePointLength('𠮷𠮷𠮷'), 3);
  assert.equal('𠮷𠮷𠮷'.length, 6);
  assert.equal(nickname.codePointLength(123), 3);
});

test('localIssue: 长度边界按码点（trim 之后计）', () => {
  assert.equal(nickname.localIssue(' a '), '昵称至少 2 个字');
  assert.equal(nickname.localIssue('ab'), '');
  assert.equal(nickname.localIssue('𠮷'), '昵称至少 2 个字', '代理对单字不能被当成 2 个字');
  assert.equal(nickname.localIssue('𠮷'.repeat(24)), '');
  assert.equal(nickname.localIssue('𠮷'.repeat(25)), '昵称最多 24 个字');
  assert.equal(nickname.localIssue('  ' + 'a'.repeat(24) + '  '), '', '首尾空白不计长度');
  assert.equal(nickname.localIssue('a'.repeat(25)), '昵称最多 24 个字');
});

test('localIssue: 空值与纯空白提示「请输入昵称」', () => {
  for (const v of ['', '   ', null, undefined, 0]) {
    assert.equal(nickname.localIssue(v), '请输入昵称');
  }
});

test('localIssue: 字符集与服务端一致（允许 _ # - . · 半角空格、字母数字汉字）', () => {
  for (const ok of ['a_b', 'a#b', 'a-b', 'a.b', 'a·b', 'a b', 'Ünï', '١٢', 'カタ']) {
    assert.equal(nickname.localIssue(ok), '', ok);
  }
  for (const bad of ['a@b', 'a/b', 'a,b', 'a、b', 'a~b', 'a😀']) {
    assert.match(nickname.localIssue(bad), /只能用/, bad);
  }
});

test('localIssue: 长度先于字符集判（超长且含非法字符时报长度）', () => {
  assert.equal(nickname.localIssue('@'), '昵称至少 2 个字');
  assert.equal(nickname.localIssue('@'.repeat(30)), '昵称最多 24 个字');
});
