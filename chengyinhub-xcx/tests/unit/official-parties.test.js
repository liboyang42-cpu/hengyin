'use strict';

const assert = require('assert');
const test = require('node:test');
const {
  ROLE_LABEL, roleLabel, statusMeta, isPublicStatus, buildOfficialPartiesView, _timeOf
} = require('../../utils/official-parties');

// 字段名照抄后端 OfficialEventParty(id/partyType/partyId/role/status/statusAt/invitedAt),
// 不出现名称/头像字段 —— 名称只经 names 显式传入。
const party = (id, role, status, extra) => Object.assign({
  id, eventId: 7, partyType: 'OFFICIAL', partyId: 100 + id, identitySource: 'UMS_MEMBER',
  role, status, statusAt: null, invitedAt: null
}, extra || {});

test('角色文案与收件箱同一套,未知角色不伪造', () => {
  assert.deepStrictEqual(ROLE_LABEL, {
    ORGANIZER: '主办方', CO_ORGANIZER: '协办方', FULFILLMENT_MERCHANT: '商家履约',
    FULFILLMENT_CLUB: '俱乐部履约', SPONSOR: '支持方', PROMOTION_PARTNER: '推广伙伴'
  });
  assert.strictEqual(roleLabel('co_organizer'), '协办方');
  assert.strictEqual(roleLabel('SOMETHING_NEW'), '合作方');
});

test('公开状态只认 ACCEPTED/ACTIVE/COMPLETED', () => {
  ['ACCEPTED', 'ACTIVE', 'COMPLETED', 'active'].forEach((s) => assert.strictEqual(isPublicStatus(s), true, s));
  ['INVITED', 'DECLINED', 'WITHDRAWN', 'SUSPENDED', 'ARCHIVED', '', null, 'X'].forEach((s) =>
    assert.strictEqual(isPublicStatus(s), false, String(s)));
  assert.deepStrictEqual(statusMeta('INVITED'), { text: '待对方确认', variant: 'warning' });
  assert.deepStrictEqual(statusMeta('nope'), { text: '状态待确认', variant: 'neutral' });
});

test('非管理者只看到已接受的参与方,待确认/已拒绝隐藏', () => {
  const rows = [
    party(1, 'ORGANIZER', 'ACTIVE'),
    party(2, 'CO_ORGANIZER', 'INVITED'),
    party(3, 'CO_ORGANIZER', 'DECLINED'),
    party(4, 'CO_ORGANIZER', 'ACCEPTED', { statusAt: '2026-10-01 10:00:00' }),
    party(5, 'CO_ORGANIZER', 'WITHDRAWN')
  ];
  const vm = buildOfficialPartiesView(rows, {});
  assert.deepStrictEqual(vm.list.map((e) => e.key), ['p1', 'p4']);
  assert.strictEqual(vm.hasPending, false);
  assert.strictEqual(vm.hostCount, 2);
});

test('管理者额外看到待确认/已拒绝,并排在同角色已接受者之后', () => {
  const rows = [
    party(2, 'CO_ORGANIZER', 'INVITED', { invitedAt: '2026-09-01 08:00:00' }),
    party(3, 'CO_ORGANIZER', 'DECLINED'),
    party(4, 'CO_ORGANIZER', 'ACCEPTED'),
    party(1, 'ORGANIZER', 'ACCEPTED')
  ];
  const vm = buildOfficialPartiesView(rows, { viewerIsManager: true });
  assert.deepStrictEqual(vm.list.map((e) => e.key), ['p1', 'p4', 'p2', 'p3']);
  assert.strictEqual(vm.hasPending, true);
  assert.deepStrictEqual(vm.list.map((e) => e.statusText), ['已接受', '已接受', '待对方确认', '已拒绝']);
  // 首屏一行只放已接受的主办/协办,管理者也一样
  assert.deepStrictEqual(vm.hero.map((e) => e.key), ['p1', 'p4']);
});

test('排序:主办 → 协办(按接受时间) → 其他角色(按角色表)', () => {
  const rows = [
    party(10, 'SPONSOR', 'ACCEPTED', { statusAt: '2026-01-01 00:00:00' }),
    party(11, 'CO_ORGANIZER', 'ACTIVE', { statusAt: '2026-10-03 09:00:00' }),
    party(12, 'FULFILLMENT_MERCHANT', 'ACTIVE', { partyType: 'MERCHANT', statusAt: '2026-10-09 00:00:00' }),
    party(13, 'CO_ORGANIZER', 'ACCEPTED', { statusAt: 1759280000000 }), // 2025-10-01 前后,早于 11
    party(14, 'ORGANIZER', 'ACTIVE', { statusAt: '2026-12-01 00:00:00' }),
    party(15, 'CO_ORGANIZER', 'ACCEPTED') // 无接受时间 → 同组最后
  ];
  const vm = buildOfficialPartiesView(rows, {});
  assert.deepStrictEqual(vm.list.map((e) => e.key), ['p14', 'p13', 'p11', 'p15', 'p12', 'p10']);
  assert.deepStrictEqual(vm.list.map((e) => e.roleLabel), ['主办方', '协办方', '协办方', '协办方', '商家履约', '支持方']);
  assert.strictEqual(vm.list[0].isHost, true);
  assert.strictEqual(vm.list[4].isHost, false);
});

test('多主办方并存时都在最前,按接受时间', () => {
  const rows = [
    party(2, 'ORGANIZER', 'ACTIVE', { statusAt: '2026-10-02 00:00:00' }),
    party(1, 'ORGANIZER', 'ACTIVE', { statusAt: '2026-10-01 00:00:00' }),
    party(3, 'CO_ORGANIZER', 'ACTIVE', { statusAt: '2026-09-01 00:00:00' })
  ];
  assert.deepStrictEqual(buildOfficialPartiesView(rows).list.map((e) => e.key), ['p1', 'p2', 'p3']);
});

test('首屏一行按 heroLimit 截断并给出剩余数', () => {
  const rows = [party(1, 'ORGANIZER', 'ACTIVE')];
  for (let i = 2; i <= 6; i++) rows.push(party(i, 'CO_ORGANIZER', 'ACTIVE', { statusAt: '2026-10-0' + i + ' 00:00:00' }));
  rows.push(party(9, 'SPONSOR', 'ACTIVE'));
  const vm = buildOfficialPartiesView(rows, { heroLimit: 3 });
  assert.deepStrictEqual(vm.hero.map((e) => e.key), ['p1', 'p2', 'p3']);
  assert.strictEqual(vm.heroMore, 3);
  assert.strictEqual(vm.hostCount, 6);
  assert.strictEqual(vm.list.length, 7);
});

test('名称只来自显式 names,没有就退回参与方类型的通用称呼', () => {
  const rows = [
    party(1, 'ORGANIZER', 'ACTIVE'),
    party(2, 'FULFILLMENT_MERCHANT', 'ACTIVE', { partyType: 'MERCHANT', partyId: 55 }),
    party(3, 'FULFILLMENT_CLUB', 'ACTIVE', { partyType: 'CLUB', partyId: 66 })
  ];
  const plain = buildOfficialPartiesView(rows).list;
  assert.deepStrictEqual(plain.map((e) => e.name), ['城瘾官方', '合作商家', '合作俱乐部']);
  assert.ok(plain.every((e) => e.named === false && e.avatar === ''));
  const named = buildOfficialPartiesView(rows, {
    names: { 'OFFICIAL:101': '城瘾运营', 'MERCHANT:55': { name: '  老街咖啡 ', avatar: 'https://x/a.png' } }
  }).list;
  assert.deepStrictEqual(named.map((e) => [e.name, e.avatar, e.named]), [
    ['城瘾运营', '', true], ['老街咖啡', 'https://x/a.png', true], ['合作俱乐部', '', false]
  ]);
});

test('输出不带内部排序字段;脏输入与重复行不炸', () => {
  const vm = buildOfficialPartiesView([null, 1, 'x', party(1, 'ORGANIZER', 'ACTIVE'), party(1, 'ORGANIZER', 'ACTIVE')]);
  assert.strictEqual(vm.list.length, 1);
  assert.ok(Object.keys(vm.list[0]).every((k) => k.charAt(0) !== '_'));
  assert.deepStrictEqual(buildOfficialPartiesView(undefined), { hero: [], heroMore: 0, list: [], hostCount: 0, hasPending: false });
});

test('时间解析:毫秒数、上海时间字符串、坏值', () => {
  assert.strictEqual(_timeOf(1700000000000), 1700000000000);
  assert.strictEqual(_timeOf('2026-10-15 08:00:00'), Date.UTC(2026, 9, 15, 0, 0, 0));
  assert.strictEqual(_timeOf('garbage'), null);
  assert.strictEqual(_timeOf(null), null);
});
