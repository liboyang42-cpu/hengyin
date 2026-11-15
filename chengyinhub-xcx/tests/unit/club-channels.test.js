const assert = require('node:assert/strict');
const test = require('node:test');

const ch = require('../../utils/club-channels.js');

function row(id, extra) {
  return Object.assign({ id: String(id), guild_kind: 1, guild_id: '42', parent_id: '0', kind: 1, name: 'c' + id,
    position: 1, is_default: 0, status: 0, version: '3' }, extra || {});
}

const inventory = [
  row(900, { kind: 0, name: 'A', position: 1 }),
  row(901, { kind: 0, name: 'B', position: 2 }),
  row(1000, { name: '闲聊', position: 0, is_default: 1 }),
  row(1001, { name: '公告', kind: 2, position: 1 }),
  row(1002, { parent_id: '900', position: 1 }),
  row(1003, { parent_id: '900', position: 2 }),
  row(1004, { parent_id: '900', position: 3, status: 3 })
];

test('成员目录:未分组在前，公告频道没有发言权限时显示只读', () => {
  const sections = ch.directorySections({
    ungrouped: [{ id: '1001', kind: 2, name: '公告', status: 0, my_permissions_list: ['VIEW_CHANNEL', 'READ_HISTORY'] }],
    groups: [{ category: { id: '900', name: 'A' }, channels: [{ id: '1002', kind: 1, name: 'chat', status: 0, my_permissions_list: ['VIEW_CHANNEL', 'SEND_MESSAGE'] }] }]
  });
  assert.equal(sections.length, 2);
  assert.equal(sections[0].channels[0].readOnly, true);
  assert.equal(sections[0].channels[0].kindLabel, '公告频道');
  assert.equal(sections[1].title, 'A');
  assert.equal(sections[1].channels[0].readOnly, false);
});

test('下移:与邻居交换并重新编号，已删除的频道不算兄弟', () => {
  const items = ch.planMove(inventory, inventory[4], 'down');
  assert.deepEqual(items.map((i) => [i.id, i.position, i.parent_id, i.expected_version]),
    [['1003', 1, '900', '3'], ['1002', 2, '900', '3']]);
  assert.equal(ch.canMove(inventory, inventory[4], 'up'), false);
});

test('分类只在分类之间排序', () => {
  const items = ch.planMove(inventory, inventory[1], 'up');
  assert.deepEqual(items.map((i) => i.id), ['901', '900']);
  assert.ok(items.every((i) => i.parent_id === '0'));
});

test('默认频道固定在最前，不能移动', () => {
  assert.deepEqual(ch.planMove(inventory, inventory[2], 'down'), []);
  assert.deepEqual(ch.planMoveTo(inventory, inventory[2], '900'), []);
  assert.equal(ch.canMove(inventory, inventory[3], 'up'), false);
});

test('移到分类:放在末尾；不能移进频道或把分类放进分类', () => {
  assert.deepEqual(ch.planMoveTo(inventory, inventory[3], '900'), [{ id: '1001', parent_id: '900', position: 3, expected_version: '3' }]);
  assert.deepEqual(ch.planMoveTo(inventory, inventory[3], '1002'), []);
  assert.deepEqual(ch.planMoveTo(inventory, inventory[0], '901'), []);
});

test('写请求带 request_id 与 reason，审计头与 reason 逐字一致', () => {
  const body = ch.reorderBody([{ id: '1', parent_id: '0', position: 1, expected_version: '0' }]);
  assert.match(body.request_id, /^[A-Za-z0-9_.:-]{8,64}$/);
  assert.equal(body.reason, ch.writeHeader()['X-Audit-Log-Reason']);
  assert.ok(/^[\x20-\x7e]+$/.test(ch.AUDIT_REASON), 'header value stays ASCII');
});

test('创建:六个字段齐全，分类强制顶层', () => {
  const created = ch.createBody({ name: ' 活动 ', topic: '', kind: 1, parentId: '900', slowmodeSec: 99999 }, 'req-create-0001');
  assert.deepEqual(created, { name: '活动', topic: null, kind: 1, parent_id: '900', visibility: 1, slowmode_sec: 21600,
    request_id: 'req-create-0001', reason: ch.AUDIT_REASON });
  assert.equal(ch.createBody({ name: '分组', kind: 0, parentId: '900' }).parent_id, '0');
});

test('编辑只发有变化的字段并带版本；没变化返回 null', () => {
  const detail = { id: '1002', name: 'chat', topic: null, slowmode_sec: 0, version: '7' };
  assert.equal(ch.editBody(detail, { name: 'chat', topic: '', slowmodeSec: 0 }), null);
  const body = ch.editBody(detail, { name: '闲聊区', topic: '随便聊', slowmodeSec: 30 }, 'req-edit-0001');
  assert.deepEqual(body, { name: '闲聊区', topic: '随便聊', slowmode_sec: 30, expected_version: '7', request_id: 'req-edit-0001', reason: ch.AUDIT_REASON });
});

test('名称校验与错误文案', () => {
  assert.equal(ch.validName(''), false);
  assert.equal(ch.validName('a/b'), false);
  assert.equal(ch.validName('公告'), true);
  assert.equal(ch.errorText({ errorCode: 'CHANNEL_FORBIDDEN' }), '只有主理人和管理员可以管理频道');
  assert.equal(ch.errorText({ errorCode: 'UNKNOWN' }, '兜底'), '兜底');
});

test('管理视图:空分类也列出，行上带能否上下移动', () => {
  const sections = ch.manageSections(inventory);
  assert.deepEqual(sections.map((s) => s.key), ['0', '900', '901']);
  assert.equal(sections[2].channels.length, 0);
  const top = sections[0].channels;
  assert.deepEqual(top.map((c) => c.id), ['1000', '1001']);
  assert.equal(top[0].canDown, false);
  assert.equal(sections[1].category.canUp, false);
  assert.equal(sections[1].category.canDown, true);
});
