// BL-155：pages/merchant/utils/merchant-prerequisites.js 的 pendingPrerequisites 跳转白名单补充用例
// 依赖 #128（BL-85），须在其后合并；不改被测代码。
// 与已有 merchant-prerequisites.test.js 的差异：那边只覆盖 1 条非白名单路径与 null/[]/缺 items；
// 这里补带 query 的白名单路径、各种绕过写法、available 非严格 true、非数组 items、坏条目与 key 兜底。
// 后端口径：MerchantPrerequisiteServiceImpl APPLY_PATH / DECOR_PATH 均不带 query。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { pendingPrerequisites, ALLOWED_PATHS } = require('../../pages/merchant/utils/merchant-prerequisites.js');

function pathFor(actionTarget) {
  const out = pendingPrerequisites({ canContinue: false, items: [{ code: 'X', title: 'X', status: 'BLOCKED', actionTarget }] });
  assert.equal(out.length, 1);
  return out[0].path;
}

test('ALLOWED_PATHS 与后端两条去处一致', () => {
  assert.deepEqual(ALLOWED_PATHS, ['/pages/merchant/apply/index', '/pages/merchant/decor/index']);
});

test('白名单路径带 query 时按精确匹配不放行（后端当前不下发 query，宁可不跳也不拼接）', () => {
  assert.equal(pathFor({ path: '/pages/merchant/decor/index?tab=logo', available: true }), '');
  assert.equal(pathFor({ path: '/pages/merchant/apply/index?from=prereq', available: true }), '');
  assert.equal(pathFor({ path: '/pages/merchant/apply/index#x', available: true }), '');
});

test('各种绕过写法都不放行', () => {
  for (const p of [
    'pages/merchant/apply/index',
    '/pages/merchant/apply/index/',
    ' /pages/merchant/apply/index',
    '/pages/merchant/apply/../../other/index',
    '/PAGES/merchant/apply/index',
    'https://evil.example/pages/merchant/apply/index',
    '/pages/merchant/apply/index2',
    '',
  ]) {
    assert.equal(pathFor({ path: p, available: true }), '', p);
  }
});

test('available 必须严格为 true；actionTarget 缺失或非对象不放行', () => {
  for (const available of [false, 'true', 1, null, undefined]) {
    assert.equal(pathFor({ path: '/pages/merchant/decor/index', available }), '', String(available));
  }
  for (const t of [null, undefined, '/pages/merchant/decor/index', 1]) {
    assert.equal(pathFor(t), '', String(t));
  }
  assert.equal(pathFor({ path: '/pages/merchant/decor/index', available: true }), '/pages/merchant/decor/index');
  assert.equal(pathFor({ path: '/pages/merchant/apply/index', available: true }), '/pages/merchant/apply/index');
});

test('空 / 非数组输入不展示', () => {
  for (const data of [undefined, '', 0, 'x', true]) {
    assert.deepEqual(pendingPrerequisites(data), [], String(data));
  }
  for (const items of [null, 'x', {}, 3, { 0: { title: 'a' } }]) {
    assert.deepEqual(pendingPrerequisites({ canContinue: false, items }), [], JSON.stringify(items));
  }
  assert.deepEqual(pendingPrerequisites({ canContinue: false, items: [] }), []);
});

test('canContinue 只认严格 true；非 true 时照常列未通过项', () => {
  const items = [{ code: 'A', title: 'A', status: 'BLOCKED' }];
  assert.equal(pendingPrerequisites({ canContinue: 'true', items }).length, 1);
  assert.equal(pendingPrerequisites({ items }).length, 1);
});

test('坏条目跳过；缺 code 用过滤后的序号兜底 key；缺说明归空', () => {
  const out = pendingPrerequisites({
    canContinue: false,
    items: [null, 'x', { status: 'BLOCKED' }, { code: 'P', title: 'P', status: 'PASS' }, { title: '无码', status: 'BLOCKED' }],
  });
  assert.deepEqual(out, [{ key: '0', title: '无码', description: '', path: '' }]);
});
