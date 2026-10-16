// BL-148：pages/club/utils/club-badge.js 纯函数单测补齐（依赖 #23 CL3，须在其后合并；不改被测代码）
const { test } = require('node:test');
const assert = require('node:assert/strict');
const clubBadge = require('../../pages/club/utils/club-badge.js');

test('normalizeBadges: null / 非数组 输入返回空数组', () => {
  assert.deepEqual(clubBadge.normalizeBadges(null), []);
  assert.deepEqual(clubBadge.normalizeBadges(undefined), []);
  assert.deepEqual(clubBadge.normalizeBadges({ badgeCode: 'A' }), []);
  assert.deepEqual(clubBadge.normalizeBadges('A'), []);
});

test('normalizeBadges: 丢弃空项与缺 badgeCode / 非字符串 badgeCode 的条目，不编造 code', () => {
  const out = clubBadge.normalizeBadges([
    null,
    'x',
    { id: 1, badgeName: '无码' },
    { id: 2, badgeCode: '', badgeName: '空码' },
    { id: 3, badgeCode: 123, badgeName: '数字码' },
    { id: 4, badgeCode: 'B4', badgeName: '  有效  ', badgeDesc: ' 说明 ', iconUrl: 'https://x/y.png' },
  ]);
  assert.deepEqual(out, [
    { id: 4, badgeCode: 'B4', badgeName: '有效', badgeDesc: '说明', iconUrl: 'https://x/y.png' },
  ]);
});

test('normalizeBadges: 缺名称兜底「未命名勋章」，非字符串说明/图标归空', () => {
  const [row] = clubBadge.normalizeBadges([{ badgeCode: 'C', badgeName: '   ', badgeDesc: 5, iconUrl: null }]);
  assert.equal(row.badgeName, '未命名勋章');
  assert.equal(row.badgeDesc, '');
  assert.equal(row.iconUrl, '');
  assert.equal(row.id, undefined);
});

test('normalizeMembers: null / 非数组 输入返回空数组', () => {
  assert.deepEqual(clubBadge.normalizeMembers(null), []);
  assert.deepEqual(clubBadge.normalizeMembers({ memberId: 1 }), []);
});

test('normalizeMembers: 缺 id / 非法 id 丢弃，重复 memberId 只保留首个', () => {
  const out = clubBadge.normalizeMembers([
    null,
    { nickname: '无 id' },
    { memberId: 0 },
    { memberId: -3 },
    { memberId: 'abc' },
    { memberId: '7', nickname: ' 阿七 ', avatar: 'a.png', isOwner: true },
    { memberId: 7, nickname: '重复' },
    { memberId: 8, avatar: 1, isOwner: 'true' },
  ]);
  assert.deepEqual(out, [
    { memberId: 7, nickname: '阿七', avatar: 'a.png', isOwner: true },
    { memberId: 8, nickname: '成员8', avatar: '', isOwner: false },
  ]);
});

test('grantReceipt: 成功与「已颁发过」幂等回执区分', () => {
  assert.deepEqual(clubBadge.grantReceipt({ code: 200, msg: '已颁发' }), {
    repeated: false, unknown: false, text: '这枚勋章已经记在对方名下。',
  });
  const repeated = clubBadge.grantReceipt({ code: 200, msg: '已颁发过' });
  assert.equal(repeated.repeated, true);
  assert.match(repeated.text, /没有重复颁发/);
});

test('grantReceipt: 页面只在 isSuccess 后调用；结果未知走 fail/successStatusAbnormal 不经 grantReceipt', () => {
  const src = require('node:fs').readFileSync(
    require('node:path').join(__dirname, '../../pages/club/badges/index.js'), 'utf8');
  const grantBlock = src.slice(src.indexOf("url: '/api/club/badge/grant'"));
  const successIdx = grantBlock.indexOf('if (!isSuccess(res))');
  const receiptIdx = grantBlock.indexOf('clubBadge.grantReceipt(res)');
  assert.ok(successIdx > 0 && receiptIdx > successIdx, 'grantReceipt 必须在成功判定之后');
  assert.match(grantBlock, /fail\(\) \{ report\(false, '[^']*结果未确认/);
  assert.match(grantBlock, /successStatusAbnormal\(\) \{ report\(false, '[^']*结果未确认/);
});

test('grantReceipt: 空响应（结果未知）不应返回成功文案（BL-163 转正）', () => {
  for (const res of [null, undefined, {}, { msg: '已颁发' }, { code: 500, msg: '已颁发过' }]) {
    const out = clubBadge.grantReceipt(res);
    assert.equal(out.unknown, true);
    assert.equal(out.repeated, false);
    assert.doesNotMatch(out.text, /已经记在对方名下|没有重复颁发/);
    assert.match(out.text, /未确认/);
  }
  assert.equal(clubBadge.grantReceipt({ code: '200', msg: '已颁发' }).unknown, false);
});

test('charLength: 按码点计数，emoji / 代理对算 1', () => {
  assert.equal(clubBadge.charLength(''), 0);
  assert.equal(clubBadge.charLength(null), 0);
  assert.equal(clubBadge.charLength(undefined), 0);
  assert.equal(clubBadge.charLength('勋章'), 2);
  assert.equal(clubBadge.charLength('😀'), 1);
  assert.equal('😀'.length, 2);
  assert.equal(clubBadge.charLength('a😀b𠮷'), 4);
  assert.equal(clubBadge.charLength(12345), 5);
});

test('charLength: NAME_MAX 边界以码点计（120 个 emoji 合规、121 个超长）', () => {
  const ok = clubBadge.validateCreateForm({ name: '😀'.repeat(clubBadge.NAME_MAX) }, 1);
  assert.equal(ok.ok, true);
  const bad = clubBadge.validateCreateForm({ name: '😀'.repeat(clubBadge.NAME_MAX + 1) }, 1);
  assert.equal(bad.ok, false);
  assert.match(bad.errors.name, /最多 120/);
});

test("positiveId: 只接受正整数（'0' / 负数 / 'abc' / 小数 / 空 均为 null）", () => {
  assert.equal(clubBadge.positiveId('0'), null);
  assert.equal(clubBadge.positiveId(0), null);
  assert.equal(clubBadge.positiveId(-1), null);
  assert.equal(clubBadge.positiveId('-5'), null);
  assert.equal(clubBadge.positiveId('abc'), null);
  assert.equal(clubBadge.positiveId(1.5), null);
  assert.equal(clubBadge.positiveId(null), null);
  assert.equal(clubBadge.positiveId(undefined), null);
  assert.equal(clubBadge.positiveId(NaN), null);
  assert.equal(clubBadge.positiveId('12'), 12);
  assert.equal(clubBadge.positiveId(9), 9);
});
